package com.miniqwerty.kb

import android.inputmethodservice.InputMethodService
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection

/**
 * Custom Android IME implementing a compact 3-row keyboard layout with an
 * integrated Vietnamese Telex processing engine.
 *
 * ## Architecture
 * - [MiniKeyboardView] — Custom View canvas handling rendering & touch.
 * - [TelexProcessor]  — Stateless engine that transforms raw keystrokes
 *   into composed Vietnamese text.
 * - Composing buffer — Accumulates raw characters in the current word.
 *   On space/return, the resolved text is committed and the buffer is cleared.
 *   On backspace, the last raw character is removed and the display is updated.
 *
 * ## Lifecycle
 * - [onCreateInputView] — inflates the custom View and attaches the listener.
 * - [onStartInputView] — resets composing state for a new input session.
 * - [onFinishInputView] — ensures any pending composing text is committed.
 */
class MiniKeyboardIME : InputMethodService(), OnKeyActionListener {

    // ── Composing state ──────────────────────────────────────────────────
    /** Raw character buffer for the current Telex word. */
    private val rawBuffer = StringBuilder()

    /** Editor capabilities from the last [onStartInput]. */
    private var editorInfo: EditorInfo? = null

    // ── View ─────────────────────────────────────────────────────────────
    private lateinit var keyboardView: MiniKeyboardView

    // ─────────────────────────────────────────────────────────────────────
    // InputMethodService lifecycle
    // ─────────────────────────────────────────────────────────────────────

    override fun onCreateInputView(): View {
        keyboardView = MiniKeyboardView(this)
        keyboardView.onKeyActionListener = this
        return keyboardView
    }

    override fun onStartInputView(info: EditorInfo, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        editorInfo = info
        // Reset composing state when switching input targets
        commitPending()
        rawBuffer.clear()
        keyboardView.shiftActive = false
        updateComposingText()
    }

    // Never enter fullscreen IME mode in landscape — keep the compact
    // keyboard anchored to the bottom of the screen.
    override fun onEvaluateFullscreenMode(): Boolean = false

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        commitPending()
        rawBuffer.clear()
    }

    override fun onStartInput(info: EditorInfo, restarting: Boolean) {
        super.onStartInput(info, restarting)
        editorInfo = info
    }

    // ─────────────────────────────────────────────────────────────────────
    // OnKeyActionListener implementation
    // ─────────────────────────────────────────────────────────────────────

    override fun onCharacter(char: Char) {
        val ic = currentInputConnection ?: return

        if (TelexProcessor.shouldCommit(char)) {
            // Commit current word and reset
            commitPending()
            ic.commitText(char.toString(), 1)
            rawBuffer.clear()
            updateComposingText()
            return
        }

        // Append to raw buffer and resolve
        rawBuffer.append(char)
        updateComposingText()
    }

    override fun onReplaceCharacter(char: Char) {
        // Double-tap: swap the last raw character for the key's secondary
        // before re-resolving the buffer.
        if (rawBuffer.isNotEmpty()) {
            rawBuffer.deleteCharAt(rawBuffer.lastIndex)
        }
        onCharacter(char)
    }

    override fun onDirectCharacter(char: Char) {
        // Numeric layer: commit the pending word, then insert the character
        // without passing it through the Telex buffer.
        val ic = currentInputConnection ?: return
        commitPending()
        rawBuffer.clear()
        ic.commitText(char.toString(), 1)
    }

    override fun onBackspace() {
        val ic = currentInputConnection ?: return

        if (rawBuffer.isNotEmpty()) {
            // Remove last character from raw buffer
            rawBuffer.deleteCharAt(rawBuffer.lastIndex)
            updateComposingText()
        } else {
            // No composing text — delegate to the target app
            ic.deleteSurroundingText(1, 0)
        }
    }

    override fun onShift() {
        // Shift state is managed by the View; we just update for visual feedback.
        // The View applies case to the next character before calling onCharacter.
    }

    override fun onNumeric() {
        // Layer switching is handled by MiniKeyboardView; nothing to do here.
    }

    override fun onSpace() {
        val ic = currentInputConnection ?: return

        if (rawBuffer.isNotEmpty()) {
            // Resolve and commit the word, then append the space
            val resolved = TelexProcessor.resolve(rawBuffer.toString())
            ic.commitText(resolved + " ", 1)
            rawBuffer.clear()
        } else {
            ic.commitText(" ", 1)
        }
        updateComposingText()
    }

    override fun onReturn() {
        val ic = currentInputConnection ?: return
        commitPending()
        rawBuffer.clear()

        // Dispatch the Enter key action as configured by the target editor
        val actionId = editorInfo?.imeOptions?.and(EditorInfo.IME_MASK_ACTION)
            ?: EditorInfo.IME_ACTION_NONE

        if (actionId != EditorInfo.IME_ACTION_NONE && actionId != EditorInfo.IME_ACTION_UNSPECIFIED) {
            ic.performEditorAction(actionId)
        } else {
            ic.commitText("\n", 1)
        }
        updateComposingText()
    }

    // ─────────────────────────────────────────────────────────────────────
    // Hard-key fallback
    // ─────────────────────────────────────────────────────────────────────

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {

        if (keyCode == KeyEvent.KEYCODE_DEL) {
            onBackspace()
            return true
        }
        if (keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER) {
            onReturn()
            return true
        }
        if (keyCode == KeyEvent.KEYCODE_SPACE) {
            onSpace()
            return true
        }

        // Pass through printable characters to the Telex pipeline
        val unicode = event.unicodeChar
        if (unicode != 0 && !Character.isISOControl(unicode)) {
            onCharacter(unicode.toChar())
            return true
        }

        return super.onKeyDown(keyCode, event)
    }

    // ─────────────────────────────────────────────────────────────────────
    // Internal helpers
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Resolves the raw buffer through [TelexProcessor] and updates the
     * composing region in the target editor via [InputConnection.setComposingText].
     */
    private fun updateComposingText() {
        val ic = currentInputConnection ?: return
        if (rawBuffer.isEmpty()) {
            // Clear any stale composing span
            ic.setComposingText("", 0)
            return
        }
        val resolved = TelexProcessor.resolve(rawBuffer.toString())
        ic.setComposingText(resolved, 1)
    }

    /**
     * Commits any pending composing text to the target editor and resets state.
     */
    private fun commitPending() {
        val ic = currentInputConnection ?: return
        val resolved = TelexProcessor.resolve(rawBuffer.toString())
        if (resolved.isNotEmpty()) {
            ic.commitText(resolved, 1)
        }
        ic.setComposingText("", 0)
    }
}
