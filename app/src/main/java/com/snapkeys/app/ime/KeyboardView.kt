package com.snapkeys.app.ime

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.SystemClock
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.SoundEffectConstants
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ScrollView
import android.widget.TextView

/**
 * The on-screen keyboard, built in code (no XML) so the scaffold has zero
 * layout-inflation surprises. It emits high-level events to its [listener];
 * all text-manipulation lives in [SnapKeysService].
 *
 * Styled and behaved like Gboard: light/dark palette following the system
 * theme, key-press preview bubbles, haptic + sound feedback, caps-lock via
 * double-tap shift, auto-capitalization (driven by the service through
 * [setAutoShift]), repeating backspace, and an accent-colored pill enter key.
 * Four pages: QWERTY with a number row, two symbol pages, and an emoji picker.
 */
@SuppressLint("ViewConstructor")
class KeyboardView(context: Context) : LinearLayout(context) {

    interface Listener {
        fun onCharacter(c: Char)

        /** Multi-code-point input (emoji). Committed verbatim, no expansion. */
        fun onText(text: String)
        fun onBackspace()
        fun onEnter()
        fun onSpace()

        /** Globe key tap: switch to the next enabled keyboard. */
        fun onSwitchIme()

        /** Globe key long-press: show the system keyboard picker. */
        fun onImePicker()

        /** Toolbar "save snippet" tapped: capture text from the editor. */
        fun onSaveSnippetTapped()

        /** Snippet capture confirmed (✓ or enter). */
        fun onSnippetConfirm()

        /** Snippet capture dismissed (✕). */
        fun onSnippetCancel()
    }

    var listener: Listener? = null

    private enum class Page { LETTERS, SYMBOLS, SYMBOLS_ALT, EMOJI }
    private enum class ShiftState { OFF, ON, CAPS_LOCK }

    /** Gboard palettes: light (f1f3f4 board / white keys) and dark. */
    private class Palette(
        val background: Int,
        val key: Int,
        val keyText: Int,
        val special: Int,
        val specialText: Int,
        val accent: Int,
        val ripple: Int,
        val hint: Int,
    )

    private val palette: Palette = run {
        val night = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
            Configuration.UI_MODE_NIGHT_YES
        if (night) Palette(
            background = 0xFF202124.toInt(),
            key = 0xFF4E5257.toInt(),
            keyText = 0xFFE8EAED.toInt(),
            special = 0xFF33363B.toInt(),
            specialText = 0xFFE8EAED.toInt(),
            accent = 0xFF1A73E8.toInt(),
            ripple = 0x33FFFFFF,
            hint = 0xFF9AA0A6.toInt(),
        ) else Palette(
            background = 0xFFF1F3F4.toInt(),
            key = 0xFFFFFFFF.toInt(),
            keyText = 0xFF202124.toInt(),
            special = 0xFFDADCE0.toInt(),
            specialText = 0xFF3C4043.toInt(),
            accent = 0xFF1A73E8.toInt(),
            ripple = 0x1F000000,
            hint = 0xFF5F6368.toInt(),
        )
    }

    private var shift = ShiftState.OFF
    private var autoShifted = false
    private var lastShiftTapMs = 0L
    private val letterKeys = mutableListOf<Pair<Button, Char>>()
    private var shiftKey: Button? = null

    private val pages = mutableMapOf<Page, LinearLayout>()

    // Key-press preview bubble, Gboard's signature feedback.
    private val previewText = TextView(context).apply {
        gravity = Gravity.CENTER
        setTextColor(palette.keyText)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 30f)
        background = GradientDrawable().apply {
            cornerRadius = dp(10).toFloat()
            setColor(palette.key)
        }
        elevation = dp(4).toFloat()
    }
    private val preview = PopupWindow(previewText).apply {
        isClippingEnabled = false
        isTouchable = false
        animationStyle = 0
    }

    // Toolbar strip above the keys (Gboard-style), hosting the snippet flow.
    private lateinit var toolbarTitle: TextView
    private lateinit var normalBar: LinearLayout
    private lateinit var captureBar: LinearLayout
    private lateinit var snippetPreview: TextView
    private lateinit var triggerView: TextView
    private val pageContainer = LinearLayout(context).apply { orientation = VERTICAL }

    init {
        orientation = VERTICAL
        setBackgroundColor(palette.background)
        setPadding(dp(3), 0, dp(3), dp(7))
        addView(buildToolbar())
        addView(pageContainer)
        switchTo(Page.LETTERS)
    }

    // region Toolbar + snippet capture UI

    /** Show the capture strip: [snippet preview / trigger being typed] ✕ ✓. */
    fun enterSnippetMode(snippet: String) {
        snippetPreview.text = snippet.replace('\n', ' ')
        setSnippetTrigger("")
        normalBar.visibility = GONE
        captureBar.visibility = VISIBLE
        switchTo(Page.LETTERS)
    }

    fun setSnippetTrigger(trigger: String) {
        triggerView.text = if (trigger.isEmpty()) "type a trigger…" else "$trigger▏"
        triggerView.setTextColor(if (trigger.isEmpty()) palette.hint else palette.keyText)
    }

    fun exitSnippetMode() {
        captureBar.visibility = GONE
        normalBar.visibility = VISIBLE
    }

    /** Briefly show [message] in the toolbar, then restore the app name. */
    fun flashToolbarMessage(message: String) {
        toolbarTitle.text = message
        toolbarTitle.setTextColor(palette.keyText)
        toolbarTitle.postDelayed({
            toolbarTitle.text = "SnapKeys"
            toolbarTitle.setTextColor(palette.hint)
        }, MESSAGE_MS)
    }

    private fun buildToolbar(): FrameLayout = FrameLayout(context).apply {
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, dp(TOOLBAR_HEIGHT_DP))

        toolbarTitle = TextView(context).apply {
            text = "SnapKeys"
            setTextColor(palette.hint)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), 0, 0, 0)
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
        }
        normalBar = LinearLayout(context).apply {
            orientation = HORIZONTAL
            addView(toolbarTitle, LinearLayout.LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
            addView(flatBar("🔖 Save snippet", palette.accent) { listener?.onSaveSnippetTapped() })
        }
        captureBar = LinearLayout(context).apply {
            orientation = HORIZONTAL
            visibility = GONE
            addView(flatBar("✕", palette.specialText) { listener?.onSnippetCancel() })
            addView(LinearLayout(context).apply {
                orientation = VERTICAL
                gravity = Gravity.CENTER_VERTICAL
                snippetPreview = TextView(context).apply {
                    setTextColor(palette.hint)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                    isSingleLine = true
                    ellipsize = TextUtils.TruncateAt.END
                }
                triggerView = TextView(context).apply {
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                    typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                    isSingleLine = true
                }
                addView(snippetPreview)
                addView(triggerView)
            }, LinearLayout.LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
            addView(flatBar("✓ Save", palette.accent) { listener?.onSnippetConfirm() })
        }
        addView(normalBar, FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(captureBar, FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    private fun flatBar(label: String, color: Int, onClick: () -> Unit): Button =
        Button(context, null, 0).apply {
            text = label
            isAllCaps = false
            setTextColor(color)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            gravity = Gravity.CENTER
            setPadding(dp(14), 0, dp(14), 0)
            background = rippleOnly()
            layoutParams = LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT)
            setOnClickListener {
                performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                onClick()
            }
        }

    // endregion

    /**
     * Auto-capitalization hook for the service: raises shift at sentence
     * starts and lowers it again, without disturbing manual shift/caps-lock.
     */
    fun setAutoShift(enabled: Boolean) {
        if (shift == ShiftState.CAPS_LOCK) return
        when {
            enabled && shift == ShiftState.OFF -> {
                shift = ShiftState.ON
                autoShifted = true
                applyShift()
            }
            !enabled && shift == ShiftState.ON && autoShifted -> {
                shift = ShiftState.OFF
                autoShifted = false
                applyShift()
            }
        }
    }

    // region Pages

    private fun switchTo(page: Page) {
        pageContainer.removeAllViews()
        pageContainer.addView(pages.getOrPut(page) { buildPage(page) })
    }

    private fun buildPage(page: Page): LinearLayout = when (page) {
        Page.LETTERS -> buildLettersPage()
        Page.SYMBOLS -> buildSymbolsPage(
            rows = listOf("1234567890", "@#\$_&-+()/", "*\"':;!?"),
            switchLabel = "=\\<",
            switchTarget = Page.SYMBOLS_ALT,
        )
        Page.SYMBOLS_ALT -> buildSymbolsPage(
            rows = listOf("~`|•√π÷×¶∆", "£€¥¢^°={}\\", "%©®™✓[]<>"),
            switchLabel = "?123",
            switchTarget = Page.SYMBOLS,
        )
        Page.EMOJI -> buildEmojiPage()
    }

    private fun buildLettersPage(): LinearLayout = page {
        addView(charRow("1234567890"))
        addView(charRow("qwertyuiop", trackShift = true))
        addView(charRow("asdfghjkl", trackShift = true, sidePad = 0.5f))
        addView(row {
            shiftKey = specialKey("⇧", weight = 1.5f) { onShiftTap() }
            addView(shiftKey)
            "zxcvbnm".forEach { c -> addView(letterKey(c)) }
            addView(backspaceKey(weight = 1.5f))
        })
        addView(bottomRow(pageSwitchLabel = "?123", pageSwitchTarget = Page.SYMBOLS))
    }

    private fun buildSymbolsPage(
        rows: List<String>,
        switchLabel: String,
        switchTarget: Page,
    ): LinearLayout = page {
        addView(charRow(rows[0]))
        addView(charRow(rows[1]))
        addView(row {
            addView(specialKey(switchLabel, weight = 1.5f) { switchTo(switchTarget) })
            rows[2].forEach { c -> addView(charKey(c)) }
            addView(backspaceKey(weight = 1.5f))
        })
        addView(bottomRow(pageSwitchLabel = "ABC", pageSwitchTarget = Page.LETTERS))
    }

    private fun buildEmojiPage(): LinearLayout = page {
        val grid = GridLayout(context).apply {
            columnCount = EMOJI_COLUMNS
            EMOJIS.forEach { emoji ->
                addView(key(emoji, textSizeSp = 26f, flat = true) { listener?.onText(emoji) }.apply {
                    layoutParams = GridLayout.LayoutParams(
                        GridLayout.spec(GridLayout.UNDEFINED, 1f),
                        GridLayout.spec(GridLayout.UNDEFINED, 1f),
                    ).apply { width = 0; height = dp(48) }
                })
            }
        }
        addView(ScrollView(context).apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, dp(ROW_HEIGHT_DP * 4))
            addView(grid)
        })
        addView(row {
            addView(specialKey("ABC", weight = 1.5f) { switchTo(Page.LETTERS) })
            addView(spaceKey(weight = 5f))
            addView(backspaceKey(weight = 1.5f))
        })
    }

    /** Shared bottom row: page switch, emoji, globe, comma, space, period, enter. */
    private fun bottomRow(pageSwitchLabel: String, pageSwitchTarget: Page): LinearLayout = row {
        addView(specialKey(pageSwitchLabel, weight = 1.5f) { switchTo(pageSwitchTarget) })
        addView(specialKey("😊") { switchTo(Page.EMOJI) })
        addView(globeKey())
        addView(charKey(','))
        addView(spaceKey(weight = 3f))
        addView(charKey('.'))
        addView(enterKey(weight = 1.5f))
    }

    /** Gboard's globe key: tap switches keyboard, long-press opens the picker. */
    private fun globeKey(): Button =
        specialKey("🌐") { listener?.onSwitchIme() }.apply {
            setOnLongClickListener {
                performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                listener?.onImePicker()
                true
            }
        }

    // endregion

    // region Shift

    private fun onShiftTap() {
        val now = SystemClock.uptimeMillis()
        shift = when {
            shift == ShiftState.OFF && now - lastShiftTapMs < DOUBLE_TAP_MS -> ShiftState.CAPS_LOCK
            shift == ShiftState.OFF -> ShiftState.ON
            shift == ShiftState.ON && now - lastShiftTapMs < DOUBLE_TAP_MS -> ShiftState.CAPS_LOCK
            else -> ShiftState.OFF
        }
        autoShifted = false
        lastShiftTapMs = now
        applyShift()
    }

    private fun applyShift() {
        val up = shift != ShiftState.OFF
        letterKeys.forEach { (button, c) ->
            button.text = (if (up) c.uppercaseChar() else c).toString()
        }
        shiftKey?.let {
            it.text = if (shift == ShiftState.CAPS_LOCK) "⇪" else "⇧"
            it.setTextColor(if (up) 0xFFFFFFFF.toInt() else palette.specialText)
            it.background = keyBackground(if (up) palette.accent else palette.special)
        }
    }

    private fun emitLetter(c: Char) {
        listener?.onCharacter(if (shift != ShiftState.OFF) c.uppercaseChar() else c)
        if (shift == ShiftState.ON) {
            shift = ShiftState.OFF
            autoShifted = false
            applyShift()
        }
    }

    // endregion

    // region Key + row builders

    private fun page(block: LinearLayout.() -> Unit): LinearLayout =
        LinearLayout(context).apply {
            orientation = VERTICAL
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
            block()
        }

    private fun row(block: LinearLayout.() -> Unit): LinearLayout =
        LinearLayout(context).apply {
            orientation = HORIZONTAL
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, dp(ROW_HEIGHT_DP))
            block()
        }

    private fun charRow(chars: String, trackShift: Boolean = false, sidePad: Float = 0f): LinearLayout =
        row {
            if (sidePad > 0) addView(spacer(sidePad))
            chars.forEach { c -> addView(if (trackShift) letterKey(c) else charKey(c)) }
            if (sidePad > 0) addView(spacer(sidePad))
        }

    private fun letterKey(c: Char): Button =
        key(c.toString(), showPreview = true) { emitLetter(c) }.also { letterKeys += it to c }

    private fun charKey(c: Char): Button =
        key(c.toString(), showPreview = true) { listener?.onCharacter(c) }

    private fun specialKey(label: String, weight: Float = 1f, onClick: () -> Unit): Button =
        key(
            label, weight, textSizeSp = 16f,
            color = palette.special, textColor = palette.specialText,
            onClick = onClick,
        )

    private fun spaceKey(weight: Float): Button =
        key(
            "SnapKeys", weight, textSizeSp = 13f,
            textColor = palette.hint,
            sound = SoundEffectConstants.NAVIGATION_DOWN,
        ) { listener?.onSpace() }

    private fun enterKey(weight: Float): Button =
        key(
            "⏎", weight, textSizeSp = 20f,
            color = palette.accent, textColor = 0xFFFFFFFF.toInt(),
            pill = true, sound = SoundEffectConstants.NAVIGATION_UP,
        ) { listener?.onEnter() }

    private fun backspaceKey(weight: Float = 1f): Button {
        val repeat = object : Runnable {
            override fun run() {
                listener?.onBackspace()
                postDelayed(this, BACKSPACE_REPEAT_MS)
            }
        }
        return specialKey("⌫", weight) { listener?.onBackspace() }.apply {
            setOnLongClickListener {
                postDelayed(repeat, BACKSPACE_REPEAT_MS)
                true
            }
            setOnTouchListener { v, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> removeCallbacks(repeat)
                }
                v.onTouchEvent(event)
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun key(
        label: String,
        weight: Float = 1f,
        textSizeSp: Float = 20f,
        color: Int = palette.key,
        textColor: Int = palette.keyText,
        flat: Boolean = false,
        pill: Boolean = false,
        showPreview: Boolean = false,
        sound: Int = SoundEffectConstants.CLICK,
        onClick: () -> Unit,
    ): Button = Button(context, null, 0).apply {
        text = label
        isAllCaps = false
        gravity = Gravity.CENTER
        typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        setTextColor(textColor)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, textSizeSp)
        setPadding(0, 0, 0, 0)
        background = if (flat) rippleOnly() else keyBackground(color, pill)
        if (!flat) {
            stateListAnimator = null
            elevation = dp(1).toFloat()
        }
        val h = dp(3)
        val v = dp(5)
        layoutParams = LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, weight)
            .apply { setMargins(h, v, h, v) }
        setOnClickListener {
            performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            playSoundEffect(sound)
            onClick()
        }
        if (showPreview) {
            setOnTouchListener { view, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> showPreview(view as Button)
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> preview.dismiss()
                }
                view.onTouchEvent(event)
            }
        }
    }

    private fun showPreview(key: Button) {
        val w = key.width + dp(16)
        val h = key.height + dp(12)
        previewText.text = key.text
        preview.width = w
        preview.height = h
        val loc = IntArray(2)
        key.getLocationInWindow(loc)
        val x = loc[0] - (w - key.width) / 2
        val y = loc[1] - h - dp(8)
        if (preview.isShowing) {
            preview.update(x, y, w, h)
        } else {
            preview.showAtLocation(this, Gravity.NO_GRAVITY, x, y)
        }
    }

    private fun spacer(weight: Float) = android.widget.Space(context).apply {
        layoutParams = LayoutParams(0, 0, weight)
    }

    private fun keyBackground(color: Int, pill: Boolean = false) = RippleDrawable(
        ColorStateList.valueOf(palette.ripple),
        GradientDrawable().apply {
            cornerRadius = dp(if (pill) ROW_HEIGHT_DP / 2 else 8).toFloat()
            setColor(color)
        },
        null,
    )

    private fun rippleOnly() = RippleDrawable(
        ColorStateList.valueOf(palette.ripple),
        null,
        GradientDrawable().apply {
            cornerRadius = dp(8).toFloat()
            setColor(0xFFFFFFFF.toInt())
        },
    )

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    // endregion

    private companion object {
        const val ROW_HEIGHT_DP = 54
        const val TOOLBAR_HEIGHT_DP = 44
        const val EMOJI_COLUMNS = 8
        const val DOUBLE_TAP_MS = 350L
        const val BACKSPACE_REPEAT_MS = 60L
        const val MESSAGE_MS = 1800L

        val EMOJIS = listOf(
            // Smileys
            "😀", "😁", "😂", "🤣", "😊", "😇", "🙂", "😉", "😍", "🥰", "😘", "😜",
            "🤪", "🤔", "🤨", "😐", "😴", "🥱", "😎", "🥳", "😅", "😬", "🙄", "😢",
            "😭", "😤", "😡", "🤯", "😱", "🤗", "🤫", "🤤", "😷", "🤒", "🥺", "😳",
            // Gestures & people
            "👍", "👎", "👌", "✌️", "🤞", "🫶", "🤝", "👏", "🙌", "🙏", "💪", "👋",
            "🤙", "👉", "👈", "👆", "👇", "🖐️", "✊", "🤘",
            // Hearts & symbols
            "❤️", "🧡", "💛", "💚", "💙", "💜", "🖤", "🤍", "💔", "❣️", "💕", "💯",
            "✨", "🔥", "⭐", "🌈", "✅", "❌", "❓", "❗", "💡", "🔔",
            // Celebration & activities
            "🎉", "🎂", "🎁", "🎵", "🎮", "⚽", "🏀", "🏆", "🎯", "🎬",
            // Nature & animals
            "🌹", "🌞", "🌙", "🌍", "🌊", "🐶", "🐱", "🐼", "🦊", "🐸", "🐵", "🦁",
            "🙈", "🙉", "🙊", "🦄",
            // Food & drink
            "☕", "🍕", "🍔", "🍟", "🍦", "🍫", "🍎", "🍌", "🥑", "🍩", "🍿", "🧋",
            // Objects & travel
            "🚗", "✈️", "🏠", "📱", "💻", "⌚", "📚", "💰", "🛒", "📷",
        )
    }
}
