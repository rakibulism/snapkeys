package com.snapkeys.app.ime

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Rect
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
import android.view.View
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
 * [setAutoShift]), repeating backspace, an accent-colored pill enter key,
 * a suggestion bar, long-press alternates on letters, and swipe typing
 * (the crossed-key path is reported via [Listener.onSwipe]; the service
 * resolves it to a word). Four pages: QWERTY with a number row, two symbol
 * pages, and an emoji picker. The toolbar also hosts the save-snippet flow.
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

        /** A suggestion chip was tapped (index into the last shown list). */
        fun onSuggestionPicked(index: Int)

        /** A swipe across letter keys ended; [path] is the crossed keys. */
        fun onSwipe(path: String)

        /** Slide-on-space cursor control: move the cursor by [steps] chars. */
        fun onCursorMove(steps: Int)
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

    // Long-press alternates popup.
    private var alternatesPopup: PopupWindow? = null

    // Emoji recents, persisted across keyboard sessions.
    private val uiPrefs = context.getSharedPreferences("snapkeys_ui", Context.MODE_PRIVATE)
    private var recentsGrid: GridLayout? = null
    private var recentsHeader: TextView? = null

    // Swipe typing state: non-null while a gesture is in flight.
    private var swipePath: StringBuilder? = null
    private var swipeStartX = 0f
    private var swipeStartY = 0f
    private var letterBounds: List<Pair<Rect, Char>> = emptyList()

    // Toolbar strip above the keys (Gboard-style): suggestions + snippet flow.
    private lateinit var toolbarTitle: TextView
    private lateinit var normalBar: LinearLayout
    private lateinit var captureBar: LinearLayout
    private lateinit var suggestionArea: LinearLayout
    private lateinit var saveSnippetButton: Button
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

    // region Toolbar: suggestions + snippet capture

    /** Replace the toolbar content with up to 3 tappable suggestion chips. */
    fun showSuggestions(displays: List<String>) {
        if (captureBar.visibility == VISIBLE) return
        suggestionArea.removeAllViews()
        if (displays.isEmpty()) {
            suggestionArea.addView(
                toolbarTitle,
                LayoutParams(0, LayoutParams.MATCH_PARENT, 1f),
            )
            saveSnippetButton.text = "🔖 Save snippet"
            return
        }
        saveSnippetButton.text = "🔖"
        displays.take(3).forEachIndexed { index, text ->
            if (index > 0) suggestionArea.addView(divider())
            suggestionArea.addView(suggestionChip(text, index))
        }
    }

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
        showSuggestions(emptyList())
        toolbarTitle.text = message
        toolbarTitle.setTextColor(palette.keyText)
        toolbarTitle.postDelayed({
            toolbarTitle.text = "SnapKeys"
            toolbarTitle.setTextColor(palette.hint)
        }, MESSAGE_MS)
    }

    fun isShifted(): Boolean = shift != ShiftState.OFF

    /** After a swipe word was capitalized, spend a one-shot shift. */
    fun consumeShift() {
        if (shift == ShiftState.ON) {
            shift = ShiftState.OFF
            autoShifted = false
            applyShift()
        }
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
        suggestionArea = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        saveSnippetButton = flatBar("🔖 Save snippet", palette.accent) {
            listener?.onSaveSnippetTapped()
        }
        normalBar = LinearLayout(context).apply {
            orientation = HORIZONTAL
            addView(suggestionArea, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
            addView(saveSnippetButton)
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
            }, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
            addView(flatBar("✓ Save", palette.accent) { listener?.onSnippetConfirm() })
        }
        addView(normalBar, FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(captureBar, FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        showSuggestions(emptyList())
    }

    private fun suggestionChip(text: String, index: Int): Button =
        Button(context, null, 0).apply {
            this.text = text
            isAllCaps = false
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.CENTER
            setTextColor(palette.keyText)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setPadding(dp(4), 0, dp(4), 0)
            background = rippleOnly()
            layoutParams = LayoutParams(0, LayoutParams.MATCH_PARENT, 1f)
            setOnClickListener {
                performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                listener?.onSuggestionPicked(index)
            }
        }

    private fun divider(): View = View(context).apply {
        setBackgroundColor(palette.hint and 0x60FFFFFF.toInt())
        layoutParams = LayoutParams(dp(1), dp(20)).apply {
            gravity = Gravity.CENTER_VERTICAL
        }
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
            layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT)
            setOnClickListener {
                performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                onClick()
            }
        }

    // endregion

    // region Auto-shift

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

    // endregion

    // region Pages

    private fun switchTo(page: Page) {
        pageContainer.removeAllViews()
        pageContainer.addView(pages.getOrPut(page) { buildPage(page) })
        if (page == Page.EMOJI) refreshRecentEmojis()
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

    /** Number-row setting captured at build time; a change needs a rebuild. */
    private val builtWithNumberRow = KeyboardSettings.numberRow(context)

    /** True when a settings change requires recreating the view. */
    fun isStale(): Boolean = builtWithNumberRow != KeyboardSettings.numberRow(context)

    private fun buildLettersPage(): LinearLayout = page {
        if (builtWithNumberRow) addView(charRow("1234567890"))
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
        recentsHeader = sectionLabel("Recents")
        recentsGrid = emojiGrid()
        val content = LinearLayout(context).apply {
            orientation = VERTICAL
            addView(recentsHeader)
            addView(recentsGrid)
            addView(emojiGrid().apply { EMOJIS.forEach { addView(emojiCell(it)) } })
        }
        addView(ScrollView(context).apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, dp(ROW_HEIGHT_DP * 4))
            addView(content)
        })
        addView(row {
            addView(specialKey("ABC", weight = 1.5f) { switchTo(Page.LETTERS) })
            addView(spaceKey(weight = 5f))
            addView(backspaceKey(weight = 1.5f))
        })
    }

    private fun emojiGrid(): GridLayout = GridLayout(context).apply {
        columnCount = EMOJI_COLUMNS
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
    }

    private fun emojiCell(emoji: String): Button =
        key(emoji, textSizeSp = 26f, flat = true) {
            recordRecentEmoji(emoji)
            listener?.onText(emoji)
        }.apply {
            layoutParams = GridLayout.LayoutParams(
                GridLayout.spec(GridLayout.UNDEFINED, 1f),
                GridLayout.spec(GridLayout.UNDEFINED, 1f),
            ).apply { width = 0; height = dp(48) }
        }

    private fun sectionLabel(text: String): TextView = TextView(context).apply {
        this.text = text
        setTextColor(palette.hint)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setPadding(dp(10), dp(6), 0, dp(2))
    }

    private fun refreshRecentEmojis() {
        val grid = recentsGrid ?: return
        val recents = loadRecentEmojis()
        recentsHeader?.visibility = if (recents.isEmpty()) GONE else VISIBLE
        grid.visibility = if (recents.isEmpty()) GONE else VISIBLE
        grid.removeAllViews()
        recents.forEach { grid.addView(emojiCell(it)) }
    }

    private fun loadRecentEmojis(): List<String> =
        uiPrefs.getString(KEY_EMOJI_RECENTS, null)
            ?.split('\n')?.filter { it.isNotEmpty() } ?: emptyList()

    private fun recordRecentEmoji(emoji: String) {
        val updated = (listOf(emoji) + loadRecentEmojis().filter { it != emoji })
            .take(MAX_RECENT_EMOJIS)
        uiPrefs.edit().putString(KEY_EMOJI_RECENTS, updated.joinToString("\n")).apply()
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

    // region Long-press alternates

    private fun showAlternates(anchor: Button, base: Char) {
        val alternates = ALTERNATES[base] ?: return
        preview.dismiss()
        val row = LinearLayout(context).apply {
            orientation = HORIZONTAL
            val pad = dp(4)
            setPadding(pad, pad, pad, pad)
            background = GradientDrawable().apply {
                cornerRadius = dp(10).toFloat()
                setColor(palette.key)
            }
            elevation = dp(6).toFloat()
        }
        alternates.forEach { alt ->
            val out = if (shift != ShiftState.OFF) alt.uppercaseChar() else alt
            row.addView(Button(context, null, 0).apply {
                text = out.toString()
                isAllCaps = false
                gravity = Gravity.CENTER
                setTextColor(palette.keyText)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
                setPadding(0, 0, 0, 0)
                background = rippleOnly()
                layoutParams = LayoutParams(dp(40), dp(46))
                setOnClickListener {
                    performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    playSoundEffect(SoundEffectConstants.CLICK)
                    emitLetter(alt)
                    alternatesPopup?.dismiss()
                }
            })
        }
        val popup = PopupWindow(row, LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
            isClippingEnabled = false
            isOutsideTouchable = true
            setBackgroundDrawable(GradientDrawable())
        }
        alternatesPopup = popup
        val location = IntArray(2)
        anchor.getLocationInWindow(location)
        val x = location[0]
        val y = location[1] - dp(46 + 12)
        popup.showAtLocation(this, Gravity.NO_GRAVITY, x, y)
    }

    // endregion

    // region Swipe typing

    private fun maybeStartSwipe(view: Button, c: Char, event: MotionEvent): Boolean {
        val dx = event.rawX - swipeStartX
        val dy = event.rawY - swipeStartY
        if (dx * dx + dy * dy < dp(SWIPE_START_DP) * dp(SWIPE_START_DP)) return false
        swipePath = StringBuilder().append(c)
        letterBounds = letterKeys.map { (button, char) ->
            val location = IntArray(2)
            button.getLocationOnScreen(location)
            Rect(location[0], location[1], location[0] + button.width, location[1] + button.height) to char
        }
        preview.dismiss()
        view.cancelLongPress()
        // Release the key that received DOWN so it neither clicks nor stays
        // visually pressed for the rest of the gesture.
        val cancel = MotionEvent.obtain(event)
        cancel.action = MotionEvent.ACTION_CANCEL
        view.onTouchEvent(cancel)
        cancel.recycle()
        return true
    }

    private fun trackSwipe(event: MotionEvent) {
        val path = swipePath ?: return
        val hit = letterBounds.firstOrNull { (rect, _) ->
            rect.contains(event.rawX.toInt(), event.rawY.toInt())
        } ?: return
        if (path.last() != hit.second) path.append(hit.second)
    }

    private fun finishSwipe(event: MotionEvent) {
        trackSwipe(event)
        val path = swipePath?.toString().orEmpty()
        swipePath = null
        if (path.toSet().size >= 2) {
            performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            listener?.onSwipe(path)
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
        key(c.toString(), showPreview = true, swipeChar = c) { emitLetter(c) }
            .also { button ->
                letterKeys += button to c
                if (ALTERNATES.containsKey(c)) {
                    button.setOnLongClickListener {
                        if (swipePath == null) {
                            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                            showAlternates(button, c)
                            true
                        } else false
                    }
                }
            }

    private fun charKey(c: Char): Button =
        key(c.toString(), showPreview = true) { listener?.onCharacter(c) }
            .also { button ->
                if (ALTERNATES.containsKey(c)) {
                    button.setOnLongClickListener {
                        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                        showAlternates(button, c)
                        true
                    }
                }
            }

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
        ) { listener?.onSpace() }.also(::attachCursorControl)

    /** Gboard's slide-on-space: horizontal drags move the cursor stepwise. */
    @SuppressLint("ClickableViewAccessibility")
    private fun attachCursorControl(space: Button) {
        var downX = 0f
        var lastStepX = 0f
        var cursorMode = false
        space.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    lastStepX = event.rawX
                    cursorMode = false
                    view.onTouchEvent(event)
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!cursorMode && kotlin.math.abs(event.rawX - downX) > dp(CURSOR_START_DP)) {
                        cursorMode = true
                        lastStepX = event.rawX
                        // The drag owns the gesture now — a space must not
                        // also be typed on finger-up.
                        val cancel = MotionEvent.obtain(event)
                        cancel.action = MotionEvent.ACTION_CANCEL
                        view.onTouchEvent(cancel)
                        cancel.recycle()
                    }
                    if (cursorMode) {
                        val step = dp(CURSOR_STEP_DP).toFloat()
                        var moved = 0
                        while (event.rawX - lastStepX >= step) {
                            moved++
                            lastStepX += step
                        }
                        while (lastStepX - event.rawX >= step) {
                            moved--
                            lastStepX -= step
                        }
                        if (moved != 0) {
                            performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                            listener?.onCursorMove(moved)
                        }
                        true
                    } else {
                        view.onTouchEvent(event)
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val wasDragging = cursorMode
                    cursorMode = false
                    if (wasDragging) true else view.onTouchEvent(event)
                }
                else -> view.onTouchEvent(event)
            }
        }
    }

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
        swipeChar: Char? = null,
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
            if (KeyboardSettings.vibration(context)) {
                performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            }
            if (KeyboardSettings.sound(context)) playSoundEffect(sound)
            onClick()
        }
        if (showPreview || swipeChar != null) {
            setOnTouchListener { view, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        if (showPreview) showPreview(view as Button)
                        swipeStartX = event.rawX
                        swipeStartY = event.rawY
                        view.onTouchEvent(event)
                    }
                    MotionEvent.ACTION_MOVE -> {
                        if (swipeChar != null && swipePath == null) {
                            maybeStartSwipe(view as Button, swipeChar, event)
                        }
                        if (swipePath != null && swipeChar != null) {
                            trackSwipe(event)
                            true
                        } else {
                            view.onTouchEvent(event)
                        }
                    }
                    MotionEvent.ACTION_UP -> {
                        preview.dismiss()
                        if (swipePath != null && swipeChar != null) {
                            finishSwipe(event)
                            true
                        } else {
                            view.onTouchEvent(event)
                        }
                    }
                    MotionEvent.ACTION_CANCEL -> {
                        preview.dismiss()
                        swipePath = null
                        view.onTouchEvent(event)
                    }
                    else -> view.onTouchEvent(event)
                }
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
        const val SWIPE_START_DP = 24
        const val CURSOR_START_DP = 18
        const val CURSOR_STEP_DP = 14
        const val MAX_RECENT_EMOJIS = 16
        const val KEY_EMOJI_RECENTS = "emoji_recents"

        /** Long-press alternates per key. */
        val ALTERNATES = mapOf(
            'a' to "àáâäãå",
            'c' to "ç",
            'e' to "èéêë",
            'g' to "ğ",
            'i' to "ìíîï",
            'n' to "ñ",
            'o' to "òóôöõø",
            's' to "śšß",
            'u' to "ùúûü",
            'y' to "ýÿ",
            'z' to "žź",
            '.' to "…?!,;:-",
        )

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
