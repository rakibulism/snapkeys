package com.snapkeys.app.ime

import android.annotation.SuppressLint
import android.content.Context
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout

/**
 * A deliberately simple QWERTY key grid built in code (no XML) so the scaffold
 * has zero layout-inflation surprises. It emits high-level events to its
 * [listener]; all text-manipulation lives in [SnapKeysService].
 *
 * This is a functional starting point, not a polished keyboard — swap in a
 * richer layout (symbols page, long-press, theming) as the app grows.
 */
@SuppressLint("ViewConstructor")
class KeyboardView(context: Context) : LinearLayout(context) {

    interface Listener {
        fun onCharacter(c: Char)
        fun onBackspace()
        fun onEnter()
        fun onSpace()
    }

    var listener: Listener? = null

    private var shifted = false

    private val rows = listOf(
        "qwertyuiop",
        "asdfghjkl",
        "zxcvbnm",
    )

    init {
        orientation = VERTICAL
        val pad = dp(4)
        setPadding(pad, pad, pad, pad)
        buildLetterRows()
        buildBottomRow()
    }

    private fun buildLetterRows() {
        rows.forEach { row ->
            addView(rowLayout().apply {
                row.forEach { ch -> addView(key(ch.toString()) { emit(ch) }) }
            })
        }
        // Shift + backspace row.
        addView(rowLayout().apply {
            addView(key("⇧", weight = 1.5f) { toggleShift() })
            addView(key("⌫", weight = 1.5f) { listener?.onBackspace() })
        })
    }

    private fun buildBottomRow() {
        addView(rowLayout().apply {
            addView(key(",") { listener?.onCharacter(',') })
            addView(key("space", weight = 5f) { listener?.onSpace() })
            addView(key(".") { listener?.onCharacter('.') })
            addView(key("⏎", weight = 1.5f) { listener?.onEnter() })
        })
    }

    private fun emit(ch: Char) {
        listener?.onCharacter(if (shifted) ch.uppercaseChar() else ch)
        if (shifted) toggleShift()
    }

    private fun toggleShift() {
        shifted = !shifted
    }

    private fun rowLayout(): LinearLayout = LinearLayout(context).apply {
        orientation = HORIZONTAL
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f)
    }

    private fun key(label: String, weight: Float = 1f, onClick: () -> Unit): Button =
        Button(context).apply {
            text = label
            gravity = Gravity.CENTER
            val m = dp(2)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, weight)
                .apply { setMargins(m, m, m, m) }
            setOnClickListener { onClick() }
        }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()
}
