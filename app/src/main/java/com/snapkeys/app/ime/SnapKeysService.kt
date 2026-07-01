package com.snapkeys.app.ime

import android.inputmethodservice.InputMethodService
import android.view.View
import android.view.inputmethod.EditorInfo
import com.snapkeys.app.data.ShortcutStore

/**
 * The SnapKeys keyboard. Once enabled in system settings and selected as the
 * active input method, this runs in every text field on the device, giving the
 * text-expansion feature its "works universally" behaviour.
 *
 * The on-screen key layout is provided by [KeyboardView]; this service owns the
 * connection to the focused editor and drives the [ExpansionEngine].
 */
class SnapKeysService : InputMethodService(), KeyboardView.Listener {

    private lateinit var engine: ExpansionEngine
    private var keyboardView: KeyboardView? = null

    override fun onCreate() {
        super.onCreate()
        reloadShortcuts()
    }

    /** Rebuild the engine so edits made in the management UI take effect. */
    private fun reloadShortcuts() {
        engine = ExpansionEngine(ShortcutStore.get(this).load())
    }

    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        // Shortcuts may have changed while another app was in front.
        reloadShortcuts()
    }

    override fun onStartInputView(editorInfo: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(editorInfo, restarting)
        updateAutoShift()
    }

    override fun onCreateInputView(): View {
        return KeyboardView(this).also {
            it.listener = this
            keyboardView = it
        }
    }

    /**
     * Gboard-style auto-capitalization: raise shift whenever the editor says
     * the cursor sits where a capital is expected (sentence start etc.).
     */
    private fun updateAutoShift() {
        val view = keyboardView ?: return
        val ic = currentInputConnection ?: return
        val inputType = currentInputEditorInfo?.inputType ?: 0
        val caps = inputType != 0 && ic.getCursorCapsMode(inputType) != 0
        view.setAutoShift(caps)
    }

    // region KeyboardView.Listener

    override fun onCharacter(c: Char) {
        val ic = currentInputConnection ?: return
        // Run expansion when a delimiter finishes the preceding word.
        if (ExpansionEngine.isDelimiter(c)) {
            val before = ic.getTextBeforeCursor(MAX_LOOKBEHIND, 0) ?: ""
            val expansion = engine.onDelimiter(before, c)
            if (expansion != null) {
                ic.beginBatchEdit()
                ic.deleteSurroundingText(expansion.deleteBefore, 0)
                ic.commitText(expansion.insert, 1)
                ic.endBatchEdit()
                updateAutoShift()
                return
            }
        }
        ic.commitText(c.toString(), 1)
        updateAutoShift()
    }

    override fun onText(text: String) {
        currentInputConnection?.commitText(text, 1)
        updateAutoShift()
    }

    override fun onBackspace() {
        val ic = currentInputConnection ?: return
        val selected = ic.getSelectedText(0)
        if (!selected.isNullOrEmpty()) {
            ic.commitText("", 1)
            return
        }
        // Delete whole characters, not UTF-16 code units — emoji are surrogate
        // pairs and some carry a trailing variation selector (e.g. ❤️).
        val before = ic.getTextBeforeCursor(2, 0) ?: ""
        val units = when {
            before.length >= 2 &&
                Character.isSurrogatePair(before[before.length - 2], before[before.length - 1]) -> 2
            before.length >= 2 && before.last() == '\uFE0F' -> 2
            else -> 1
        }
        ic.deleteSurroundingText(units, 0)
        updateAutoShift()
    }

    override fun onEnter() {
        val ic = currentInputConnection ?: return
        // Expand a pending trigger before submitting the line.
        val before = ic.getTextBeforeCursor(MAX_LOOKBEHIND, 0) ?: ""
        val expansion = engine.onDelimiter(before, null)
        if (expansion != null) {
            ic.beginBatchEdit()
            ic.deleteSurroundingText(expansion.deleteBefore, 0)
            ic.commitText(expansion.insert, 1)
            ic.endBatchEdit()
        }
        sendDefaultEditorAction(true)
        updateAutoShift()
    }

    override fun onSpace() = onCharacter(' ')

    // endregion

    companion object {
        /** Longest trigger we ever need to look back for. */
        private const val MAX_LOOKBEHIND = 64
    }
}
