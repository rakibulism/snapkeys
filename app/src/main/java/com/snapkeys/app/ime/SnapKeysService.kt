package com.snapkeys.app.ime

import android.inputmethodservice.InputMethodService
import android.os.Build
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import com.snapkeys.app.data.Shortcut
import com.snapkeys.app.data.ShortcutStore
import com.snapkeys.app.sync.SyncManager

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

    // Snippet capture: while non-null, key presses type the trigger for this
    // snippet in the keyboard's toolbar instead of going to the editor.
    private var snippetCapture: String? = null
    private val triggerBuffer = StringBuilder()

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
        // A different field means the captured text is gone — drop the flow.
        if (snippetCapture != null) onSnippetCancel()
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
        if (snippetCapture != null) {
            // Any visible character may be part of a trigger; whitespace
            // can't (space is inert here and enter confirms the capture).
            if (!c.isWhitespace()) appendToTrigger(c.toString())
            return
        }
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
        if (snippetCapture != null) {
            appendToTrigger(text)
            return
        }
        currentInputConnection?.commitText(text, 1)
        updateAutoShift()
    }

    override fun onBackspace() {
        if (snippetCapture != null) {
            if (triggerBuffer.isNotEmpty()) {
                val len = triggerBuffer.length
                val units = if (len >= 2 &&
                    Character.isSurrogatePair(triggerBuffer[len - 2], triggerBuffer[len - 1])
                ) 2 else 1
                triggerBuffer.setLength(len - units)
                keyboardView?.setSnippetTrigger(triggerBuffer.toString())
            }
            return
        }
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
        if (snippetCapture != null) {
            onSnippetConfirm()
            return
        }
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

    override fun onSwitchIme() {
        val switched = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            switchToNextInputMethod(false)
        } else {
            val token = window?.window?.attributes?.token
            @Suppress("DEPRECATION")
            token != null && imm().switchToNextInputMethod(token, false)
        }
        // Nothing to cycle to (only one keyboard enabled) — offer the picker
        // so the user can see what's available.
        if (!switched) onImePicker()
    }

    override fun onImePicker() {
        imm().showInputMethodPicker()
    }

    override fun onSaveSnippetTapped() {
        val ic = currentInputConnection ?: return
        // Prefer an explicit selection; fall back to the line being written.
        val selected = ic.getSelectedText(0)?.toString()?.trim()
        val text = if (!selected.isNullOrBlank()) {
            selected
        } else {
            (ic.getTextBeforeCursor(SNIPPET_LOOKBEHIND, 0) ?: "")
                .toString().substringAfterLast('\n').trim()
        }
        if (text.isBlank()) {
            keyboardView?.flashToolbarMessage("Select or write some text first")
            return
        }
        snippetCapture = text
        triggerBuffer.clear()
        keyboardView?.enterSnippetMode(text)
    }

    override fun onSnippetConfirm() {
        val text = snippetCapture ?: return
        val trigger = triggerBuffer.toString()
        if (trigger.isEmpty()) {
            keyboardView?.flashToolbarMessage("Type a trigger first")
            return
        }
        ShortcutStore.get(this).upsert(Shortcut(trigger, text))
        reloadShortcuts()
        onSnippetCancel()
        keyboardView?.flashToolbarMessage("✓ Saved — $trigger now expands to it")
        // Back up right away if Drive sync is set up; errors are non-fatal
        // here and will surface on the next sync from the app.
        SyncManager.get(this).syncInBackground()
    }

    override fun onSnippetCancel() {
        snippetCapture = null
        triggerBuffer.clear()
        keyboardView?.exitSnippetMode()
    }

    private fun appendToTrigger(text: String) {
        if (triggerBuffer.length + text.length > MAX_TRIGGER_LENGTH) return
        triggerBuffer.append(text)
        keyboardView?.setSnippetTrigger(triggerBuffer.toString())
    }

    private fun imm(): InputMethodManager =
        getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager

    // endregion

    companion object {
        /** Longest trigger we ever need to look back for. */
        private const val MAX_LOOKBEHIND = 64

        /** How far back to scan when capturing a snippet from the editor. */
        private const val SNIPPET_LOOKBEHIND = 1000

        private const val MAX_TRIGGER_LENGTH = 24
    }
}
