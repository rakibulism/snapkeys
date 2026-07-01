package com.snapkeys.app.ime

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.SystemClock
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewGroup
import android.widget.Button
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.ScrollView

/**
 * The on-screen keyboard, built in code (no XML) so the scaffold has zero
 * layout-inflation surprises. It emits high-level events to its [listener];
 * all text-manipulation lives in [SnapKeysService].
 *
 * Four pages: QWERTY letters (with a number row), two symbol pages, and an
 * emoji picker. Shift supports caps lock via double-tap; backspace repeats
 * on long-press.
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
    }

    var listener: Listener? = null

    private enum class Page { LETTERS, SYMBOLS, SYMBOLS_ALT, EMOJI }
    private enum class ShiftState { OFF, ON, CAPS_LOCK }

    private var shift = ShiftState.OFF
    private var lastShiftTapMs = 0L
    private val letterKeys = mutableListOf<Pair<Button, Char>>()
    private var shiftKey: Button? = null

    private val pages = mutableMapOf<Page, LinearLayout>()

    init {
        orientation = VERTICAL
        setBackgroundColor(BG)
        val pad = dp(3)
        setPadding(pad, pad, pad, pad)
        switchTo(Page.LETTERS)
    }

    // region Pages

    private fun switchTo(page: Page) {
        removeAllViews()
        addView(pages.getOrPut(page) { buildPage(page) })
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
            addView(key("space", weight = 5f, textSizeSp = 14f) { listener?.onSpace() })
            addView(backspaceKey(weight = 1.5f))
        })
    }

    /** Shared bottom row: page switch, emoji, comma, space, period, enter. */
    private fun bottomRow(pageSwitchLabel: String, pageSwitchTarget: Page): LinearLayout = row {
        addView(specialKey(pageSwitchLabel, weight = 1.5f) { switchTo(pageSwitchTarget) })
        addView(specialKey("😊") { switchTo(Page.EMOJI) })
        addView(charKey(','))
        addView(key("space", weight = 4f, textSizeSp = 14f) { listener?.onSpace() })
        addView(charKey('.'))
        addView(specialKey("⏎", weight = 1.5f, accent = true) { listener?.onEnter() })
    }

    // endregion

    // region Shift

    private fun onShiftTap() {
        val now = SystemClock.uptimeMillis()
        shift = when {
            shift == ShiftState.OFF && now - lastShiftTapMs < DOUBLE_TAP_MS -> ShiftState.CAPS_LOCK
            shift == ShiftState.OFF -> ShiftState.ON
            else -> ShiftState.OFF
        }
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
            it.background = keyBackground(if (up) ACCENT else SPECIAL_KEY)
        }
    }

    private fun emitLetter(c: Char) {
        listener?.onCharacter(if (shift != ShiftState.OFF) c.uppercaseChar() else c)
        if (shift == ShiftState.ON) {
            shift = ShiftState.OFF
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
        key(c.toString()) { emitLetter(c) }.also { letterKeys += it to c }

    private fun charKey(c: Char): Button =
        key(c.toString()) { listener?.onCharacter(c) }

    private fun specialKey(label: String, weight: Float = 1f, accent: Boolean = false, onClick: () -> Unit): Button =
        key(label, weight, textSizeSp = 16f, color = if (accent) ACCENT else SPECIAL_KEY, onClick = onClick)

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

    private fun key(
        label: String,
        weight: Float = 1f,
        textSizeSp: Float = 19f,
        color: Int = KEY,
        flat: Boolean = false,
        onClick: () -> Unit,
    ): Button = Button(context, null, 0).apply {
        text = label
        isAllCaps = false
        gravity = Gravity.CENTER
        typeface = Typeface.DEFAULT
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, textSizeSp)
        setPadding(0, 0, 0, 0)
        background = if (flat) rippleOnly() else keyBackground(color)
        val m = dp(2)
        layoutParams = LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, weight)
            .apply { setMargins(m, m, m, m) }
        setOnClickListener { onClick() }
    }

    private fun spacer(weight: Float) = android.widget.Space(context).apply {
        layoutParams = LayoutParams(0, 0, weight)
    }

    private fun keyBackground(color: Int) = RippleDrawable(
        ColorStateList.valueOf(RIPPLE),
        GradientDrawable().apply {
            cornerRadius = dp(7).toFloat()
            setColor(color)
        },
        null,
    )

    private fun rippleOnly() = RippleDrawable(
        ColorStateList.valueOf(RIPPLE),
        null,
        GradientDrawable().apply {
            cornerRadius = dp(7).toFloat()
            setColor(Color.WHITE)
        },
    )

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    // endregion

    private companion object {
        const val ROW_HEIGHT_DP = 52
        const val EMOJI_COLUMNS = 8
        const val DOUBLE_TAP_MS = 350L
        const val BACKSPACE_REPEAT_MS = 60L

        // Dark keyboard palette.
        const val BG = 0xFF1B1E24.toInt()
        const val KEY = 0xFF3C4148.toInt()
        const val SPECIAL_KEY = 0xFF2A2E35.toInt()
        const val ACCENT = 0xFF4C8BF5.toInt()
        const val RIPPLE = 0x40FFFFFF

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
