package com.miniqwerty.kb

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.SharedPreferences
import android.inputmethodservice.InputMethodService
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedTextRequest
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

    // ── Clipboard history ────────────────────────────────────────────────
    /** In-memory copy history, newest first. Session-only by design. */
    private val clipboardHistory = ArrayList<String>()

    /** Texts the user dismissed — kept out even though the system clipboard
     *  still holds them, until a fresh copy of that text re-arms it. */
    private val dismissedClips = LinkedHashSet<String>()

    private lateinit var clipboardManager: ClipboardManager

    private val onPrimaryClipChanged = ClipboardManager.OnPrimaryClipChangedListener {
        // Runs on the main thread; the system filters callbacks by clipboard
        // access (IME visible) on Android 10+. A change event is a fresh copy
        // by the user — it re-arms a previously dismissed text.
        addToHistory(readClipboardText(), freshCopy = true)
        if (::keyboardView.isInitialized) {
            keyboardView.updateClipboardItems(clipboardHistory)
        }
    }

    // Applies a theme change the moment the settings screen writes it —
    // same process, so the SharedPreferences listener fires live.
    private val onPrefsChanged = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == Prefs.KEY_THEME_MODE && ::keyboardView.isInitialized) {
            keyboardView.refreshTheme()
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // InputMethodService lifecycle
    // ─────────────────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        clipboardManager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboardManager.addPrimaryClipChangedListener(onPrimaryClipChanged)
        getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE)
            .registerOnSharedPreferenceChangeListener(onPrefsChanged)

        // Suggestion engine: corpus words, bigrams, and the user's learned
        // words. Each load fails independently — the strip degrades to the
        // center word only / no next-word predictions rather than crashing.
        runCatching { SuggestionEngine.loadWords(assets.open("vi_words.txt")) }
        runCatching { SuggestionEngine.loadBigrams(assets.open("vi_bigrams.txt")) }
        val stored = getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE)
            .getString(Prefs.KEY_USER_WORDS, null)
        SuggestionEngine.setUserCounts(SuggestionEngine.parseUserCounts(stored))
    }

    override fun onDestroy() {
        clipboardManager.removePrimaryClipChangedListener(onPrimaryClipChanged)
        getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE)
            .unregisterOnSharedPreferenceChangeListener(onPrefsChanged)
        super.onDestroy()
    }

    override fun onCreateInputView(): View {
        keyboardView = MiniKeyboardView(this)
        keyboardView.onKeyActionListener = this
        return keyboardView
    }

    override fun onStartInputView(info: EditorInfo, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        editorInfo = info
        // Re-apply user theme/height preferences (may have changed in settings).
        keyboardView.refreshTheme()
        // Every new input session starts on the letters layer — the previous
        // layer (numeric/clipboard) is not remembered.
        keyboardView.resetLayer()
        // Reset composing state when switching input targets
        commitPending()
        rawBuffer.clear()
        lastCommittedWord = ""
        keyboardView.setSuggestions(emptyList())
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
        keyboardView.setSuggestions(emptyList())
        persistLearningNow()
    }

    override fun onStartInput(info: EditorInfo, restarting: Boolean) {
        super.onStartInput(info, restarting)
        editorInfo = info
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        // Re-apply the dark/light palette when the system theme changes.
        if (::keyboardView.isInitialized) {
            keyboardView.refreshTheme()
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // OnKeyActionListener implementation
    // ─────────────────────────────────────────────────────────────────────

    override fun onCharacter(char: Char) {
        val ic = currentInputConnection ?: return

        if (TelexProcessor.shouldCommit(char)) {
            // Commit current word and reset. Explicit user action — commit
            // unconditionally (see commitBuffer()).
            commitBuffer(ic)
            ic.commitText(char.toString(), 1)
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
        // without passing it through the Telex buffer. Explicit user action —
        // commit unconditionally (see commitBuffer()).
        val ic = currentInputConnection ?: return
        commitBuffer(ic)
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
            recordCommittedWord(resolved)
        } else {
            ic.commitText(" ", 1)
        }
        updateComposingText()
    }

    override fun onReturn() {
        val ic = currentInputConnection ?: return
        commitBuffer(ic)

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

    override fun onCursorMove(delta: Int) {
        val ic = currentInputConnection ?: return

        // A pending Telex word would break the buffer's assumption about the
        // surrounding text once the cursor moves — commit it first. Explicit
        // user action, so commit unconditionally (see commitBuffer()).
        if (rawBuffer.isNotEmpty()) {
            commitBuffer(ic)
        }

        // Probe the cursor position: setSelection takes absolute offsets, so
        // measure how much text lies on each side of the caret.
        val before = ic.getTextBeforeCursor(CURSOR_PROBE_LEN, 0) ?: return
        val after = ic.getTextAfterCursor(CURSOR_PROBE_LEN, 0)
        val cursor = before.length
        val end = cursor + (after?.length ?: 0)
        val target = (cursor + delta).coerceIn(0, end)
        ic.setSelection(target, target)
    }

    override fun onClipboard() {
        // Re-read the current clip first: while the keyboard was hidden the
        // change listener does not fire, so catch up here. This is a re-read
        // of an old clip, not a fresh copy — respect dismissals.
        val current = readClipboardText()
        if (current != null && current !in dismissedClips) {
            addToHistory(current, freshCopy = false)
        }
        keyboardView.showClipboardLayer(clipboardHistory)
    }

    override fun onClipboardItem(index: Int) {
        val text = clipboardHistory.getOrNull(index) ?: return
        // Move to top BEFORE any setPrimaryClip below, so the change listener
        // this triggers sees the text already first and dedupes it.
        addToHistory(text, freshCopy = true)

        val ic = currentInputConnection
        if (ic != null) {
            // Paste into the focused field. A pending Telex word is committed
            // first (explicit user action — see commitBuffer()), then the
            // pasted text follows it.
            commitBuffer(ic)
            ic.commitText(text, 1)
            updateComposingText()
        } else {
            // No focused text field — re-copy the item so it is ready for the
            // next paste.
            clipboardManager.setPrimaryClip(ClipData.newPlainText(CLIP_LABEL, text))
        }
        // Stay on the clipboard layer; the pasted item moved to the top.
        keyboardView.updateClipboardItems(clipboardHistory)
    }

    override fun onClipboardDismiss(index: Int) {
        if (index !in clipboardHistory.indices) return
        val text = clipboardHistory.removeAt(index)
        // The system clipboard still holds this text — keep it out of the
        // history until the user copies it again.
        dismissedClips.add(text)
        while (dismissedClips.size > MAX_CLIP_ITEMS) {
            dismissedClips.remove(dismissedClips.first())
        }
        keyboardView.updateClipboardItems(clipboardHistory)
    }

    override fun onSuggestionSelected(word: String) {
        if (word.isEmpty()) return
        val ic = currentInputConnection ?: return
        // Explicit user action — commit unconditionally, mirroring onSpace().
        // The buffer may be empty here (next-word mode); the editor may or
        // may not hold a composing region — commitText with newCursorPosition
        // 1 inserts at the caret either way.
        ic.commitText(word + " ", 1)
        ic.setComposingText("", 0)
        rawBuffer.clear()
        recordCommittedWord(word)
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
    // Clipboard history helpers
    // ─────────────────────────────────────────────────────────────────────

    /**
     * First text item of the primary clip, or null for blank / non-text clips
     * (images would otherwise surface as useless `content://` URIs). OEM
     * clipboard guards (e.g. MIUI) can throw SecurityException — degrade to null.
     */
    private fun readClipboardText(): String? {
        return try {
            val clip = clipboardManager.primaryClip ?: return null
            for (i in 0 until clip.itemCount) {
                val text = clip.getItemAt(i).text?.toString()
                if (!text.isNullOrBlank()) return text
            }
            null
        } catch (e: SecurityException) {
            null
        }
    }

    /**
     * Insert [text] at the head of the history; dedupe identical newest entry.
     * [freshCopy] means the text just arrived as a clipboard change event
     * (user copied it) — that re-arms a previously dismissed text; a plain
     * re-read keeps dismissals in force.
     */
    private fun addToHistory(text: String?, freshCopy: Boolean) {
        if (text.isNullOrBlank()) return
        val capped = if (text.length > MAX_CLIP_CHARS) text.take(MAX_CLIP_CHARS) else text
        if (freshCopy) {
            dismissedClips.remove(capped)
        } else if (capped in dismissedClips) {
            return
        }
        if (capped == clipboardHistory.firstOrNull()) return
        clipboardHistory.remove(capped)
        clipboardHistory.add(0, capped)
        while (clipboardHistory.size > MAX_CLIP_ITEMS) {
            clipboardHistory.removeAt(clipboardHistory.size - 1)
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // Suggestion learning
    // ─────────────────────────────────────────────────────────────────────

    /** Last word committed to the editor — the anchor for next-word mode. */
    private var lastCommittedWord: String = ""

    private var learningDirty: Boolean = false
    private var learningPersistScheduled: Boolean = false

    /** Count one committed word: feed the engine, set the next-word anchor,
     *  and schedule a throttled prefs write. */
    private fun recordCommittedWord(word: String) {
        val clean = word.lowercase()
        if (!clean.any { it.isLetter() }) return
        SuggestionEngine.addUserWord(clean)
        lastCommittedWord = clean
        learningDirty = true
        if (learningPersistScheduled) return
        learningPersistScheduled = true
        keyboardView.postDelayed({
            learningPersistScheduled = false
            persistLearningNow()
        }, LEARNING_PERSIST_DELAY_MS)
    }

    private fun persistLearningNow() {
        if (!learningDirty) return
        learningDirty = false
        val serialized = SuggestionEngine.serializeUserCounts(SuggestionEngine.userCountsSnapshot())
        getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE)
            .edit().putString(Prefs.KEY_USER_WORDS, serialized).apply()
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
            pushNextWordSuggestions()
            return
        }
        val resolved = TelexProcessor.resolve(rawBuffer.toString())
        ic.setComposingText(resolved, 1)
        pushComposingSuggestions(resolved)
    }

    /** Composing mode: center = resolved word, flanks = top-2 prefix matches. */
    private fun pushComposingSuggestions(resolved: String) {
        val flanks = SuggestionEngine.suggestions(resolved, max = 2)
        keyboardView.setSuggestions(
            listOf(flanks.getOrNull(0).orEmpty(), resolved, flanks.getOrNull(1).orEmpty())
        )
    }

    /** Next-word mode: all 3 slots are bigram predictions for the last word. */
    private fun pushNextWordSuggestions() {
        val predictions = SuggestionEngine.nextWords(lastCommittedWord, max = 3)
        if (predictions.isEmpty()) {
            keyboardView.setSuggestions(emptyList())
            return
        }
        keyboardView.setSuggestions(listOf(
            predictions.getOrNull(0).orEmpty(),
            predictions.getOrNull(1).orEmpty(),
            predictions.getOrNull(2).orEmpty(),
        ))
    }

    /**
     * Commits the resolved raw buffer unconditionally and clears state.
     *
     * Used for explicit user actions (enter, cursor move). Unlike
     * [commitPending], it does not gate on the editor's composing-region
     * probe: editors that fail to report partial offsets would otherwise
     * have the visible composing word silently wiped.
     */
    private fun commitBuffer(ic: InputConnection) {
        val resolved = TelexProcessor.resolve(rawBuffer.toString())
        if (resolved.isNotEmpty()) {
            ic.commitText(resolved, 1)
            recordCommittedWord(resolved)
        }
        ic.setComposingText("", 0)
        rawBuffer.clear()
        pushNextWordSuggestions()
    }

    /**
     * Commits any pending composing text to the target editor and resets state.
     *
     * The commit is conditional on the editor still holding a composing region:
     * if it is gone (e.g. a chat app cleared the field while we were composing),
     * the buffer is stale and re-inserting it would resurrect deleted text.
     */
    private fun commitPending() {
        val ic = currentInputConnection ?: return
        val resolved = TelexProcessor.resolve(rawBuffer.toString())
        if (resolved.isNotEmpty() && editorHasComposingRegion()) {
            ic.commitText(resolved, 1)
        }
        ic.setComposingText("", 0)
    }

    /**
     * True if the editor reports an active composing region
     * (partialStartOffset < partialEndOffset). Editors without composing
     * support return null extracted text — treat as "commit" (legacy behavior,
     * their text was already inserted by setComposingText falling back to
     * commitText).
     */
    private fun editorHasComposingRegion(): Boolean {
        val ic = currentInputConnection ?: return true
        val req = ExtractedTextRequest().apply { hintMaxChars = 0; hintMaxLines = 1 }
        val et = ic.getExtractedText(req, 0) ?: return true
        return et.partialStartOffset >= 0 && et.partialEndOffset > et.partialStartOffset
    }

    companion object {
        /** Max chars probed around the caret to locate the cursor position. */
        private const val CURSOR_PROBE_LEN = 5000

        /** Clipboard history size cap (the list scrolls, so it can be long). */
        private const val MAX_CLIP_ITEMS = 30

        /** Per-item length cap — stays well under the ~1MB Binder transaction
         *  limit for commitText/setPrimaryClip. */
        private const val MAX_CLIP_CHARS = 50_000

        /** Label used for ClipData created when re-copying an item. */
        private const val CLIP_LABEL = "clipboard"

        /** Learned words persist at most once per this delay (plus on finish). */
        private const val LEARNING_PERSIST_DELAY_MS = 10_000L
    }
}
