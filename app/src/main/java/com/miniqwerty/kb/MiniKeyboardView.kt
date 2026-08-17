package com.miniqwerty.kb

import android.content.Context
import android.content.res.Configuration
import android.graphics.*
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.abs
import kotlin.math.min

/**
 * Listener dispatched by [MiniKeyboardView] when the user triggers a key action.
 */
interface OnKeyActionListener {
    fun onCharacter(char: Char)
    /** Replace the last raw character in the composing buffer (double-tap). */
    fun onReplaceCharacter(char: Char)
    /** Commit a character directly, bypassing the Telex buffer (numeric layer). */
    fun onDirectCharacter(char: Char)
    /** Replace the last directly-committed character (numeric-layer double-tap). */
    fun onReplaceDirectCharacter(char: Char)
    fun onBackspace()
    fun onShift()
    fun onNumeric()
    fun onSpace()
    fun onReturn()
    /** Move the cursor by [delta] characters (negative = left). Space-bar cursor mode. */
    fun onCursorMove(delta: Int)
    /** Open (or toggle) the clipboard layer. */
    fun onClipboard()
    /** Paste the clipboard history item at [index]. */
    fun onClipboardItem(index: Int)
    /** Remove the clipboard history item at [index]. */
    fun onClipboardDismiss(index: Int)
}

// ─────────────────────────────────────────────────────────────────────────────
// Key Definitions
// ─────────────────────────────────────────────────────────────────────────────

private enum class KeyType {
    CHARACTER, BACKSPACE, SHIFT, NUMERIC, ABC, SPACE, RETURN,
    CLIPBOARD, CLIPBOARD_ITEM, CLIPBOARD_CLOSE
}

/** Which keyboard layer is currently displayed. */
private enum class KeyboardLayer { LETTERS, NUMERIC, CLIPBOARD }

private data class KeyDef(
    val primary: String,
    val secondary: String?,
    val isVowel: Boolean = false,
    val widthUnits: Float = 1f,
    val keyType: KeyType = KeyType.CHARACTER,
    /** Position in the clipboard history (CLIPBOARD_ITEM keys only). */
    val index: Int = -1,
) {
    /** Pixel bounds set during layout. */
    var left: Float = 0f
    var top: Float = 0f
    var right: Float = 0f
    var bottom: Float = 0f
}

// ─────────────────────────────────────────────────────────────────────────────
// Custom View
// ─────────────────────────────────────────────────────────────────────────────

class MiniKeyboardView(context: Context) : View(context) {

    var onKeyActionListener: OnKeyActionListener? = null

    /** Whether the Shift key is latched (uppercase next char). */
    var shiftActive: Boolean = false
        set(value) {
            field = value
            invalidate()
        }

    // ── Persisted preferences ────────────────────────────────────────────
    private val prefs = context.getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE)

    // ── Behavior toggles (re-read in refreshTheme) ───────────────────────
    private var hapticEnabled: Boolean = prefs.getBoolean(Prefs.KEY_HAPTIC_ENABLED, true)
    private var popupEnabled: Boolean = prefs.getBoolean(Prefs.KEY_KEY_POPUP_ENABLED, true)
    private var doubleTapMs: Long = prefs.getLong(Prefs.KEY_DOUBLE_TAP_MS, Prefs.DOUBLE_TAP_DEFAULT_MS)

    // ── Theme state ───────────────────────────────────────────────────────
    private var darkTheme: Boolean = resolveDarkTheme()

    // ── Dimensions (set during onSizeChanged) ─────────────────────────────
    private var viewWidth: Int = 0
    private var viewHeight: Int = 0
    private var keyWidth: Float = 0f
    private var keyHeight: Float = 0f
    private var handleHeightPx: Float = 0f

    // ── Keyboard height (drag-adjustable, persisted) ──────────────────────
    private var rowHeightDp: Float =
        prefs.getFloat(Prefs.KEY_ROW_HEIGHT_DP, Prefs.ROW_HEIGHT_DEFAULT_DP)
            .coerceIn(Prefs.ROW_HEIGHT_MIN_DP, Prefs.ROW_HEIGHT_MAX_DP)

    private var dragActive: Boolean = false
    private var dragStartY: Float = 0f
    private var dragStartRowDp: Float = 0f

    // ── Layer state ───────────────────────────────────────────────────────
    private var currentLayer: KeyboardLayer = KeyboardLayer.LETTERS

    // ── Touch-tracking state ──────────────────────────────────────────────
    private var downKey: KeyDef? = null
    private var downX: Float = 0f
    private var downY: Float = 0f
    private var isSwipeDetected: Boolean = false
    private var longPressTriggered: Boolean = false
    private val touchSlop: Int = ViewConfiguration.get(context).scaledTouchSlop

    // Space-bar cursor mode: after long-pressing space, horizontal drag moves
    // the text cursor. cursorPxPerChar maps finger dx to character steps.
    private var spaceCursorMode: Boolean = false
    private var lastCursorChars: Int = 0
    private var cursorPxPerChar: Float = 40f

    private val longPressHandler = Handler(Looper.getMainLooper())
    private var longPressRunnable: Runnable? = null

    // Double-tap state: second quick tap on the same key emits its secondary.
    private var lastTapKey: KeyDef? = null
    private var lastTapTime: Long = 0L

    // Backspace repeat state.
    private val repeatHandler = Handler(Looper.getMainLooper())
    private var backspaceRepeatRunnable: Runnable? = null
    private var backspaceRepeatActive: Boolean = false

    // ── Paints ────────────────────────────────────────────────────────────
    private val bgPaint = Paint().apply { style = Paint.Style.FILL }
    private val keyBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val keyBgPressedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val keyBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f
    }
    private val primaryTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
    }
    private val vowelTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }
    private val secondaryTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
    }
    private val functionTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
    }
    private val functionBoldTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }
    private val clipboardItemTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.LEFT
    }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val popupBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val popupTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }

    // ── Corner radius ─────────────────────────────────────────────────────
    private val cornerRadius = 6f

    init {
        applyTheme()
    }

    // ─────────────────────────────────────────────────────────────────────
    // Theme
    // ─────────────────────────────────────────────────────────────────────

    private fun isSystemDark(c: Context): Boolean =
        (c.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES

    /** Resolve dark/light from the saved theme preference (system by default). */
    private fun resolveDarkTheme(): Boolean =
        when (prefs.getInt(Prefs.KEY_THEME_MODE, Prefs.THEME_SYSTEM)) {
            Prefs.THEME_LIGHT -> false
            Prefs.THEME_DARK  -> true
            else              -> isSystemDark(context)
        }

    /** Re-read the theme preference and recolor. Called on init, when the keyboard
     *  window is shown, and on configuration change. Also re-reads the behavior
     *  toggles so settings changes land on the open keyboard. */
    fun refreshTheme() {
        hapticEnabled = prefs.getBoolean(Prefs.KEY_HAPTIC_ENABLED, true)
        popupEnabled = prefs.getBoolean(Prefs.KEY_KEY_POPUP_ENABLED, true)
        doubleTapMs = prefs.getLong(Prefs.KEY_DOUBLE_TAP_MS, Prefs.DOUBLE_TAP_DEFAULT_MS)
        val dark = resolveDarkTheme()
        if (darkTheme == dark) return
        darkTheme = dark
        applyTheme()
        invalidate()
    }

    private fun applyTheme() {
        if (darkTheme) {
            bgPaint.color = 0xFF17181C.toInt()
            keyBgPaint.color = 0xFF3C4043.toInt()
            keyBgPressedPaint.color = 0xFF50545B.toInt()
            keyBorderPaint.color = 0xFF0F1012.toInt()
            primaryTextPaint.color = 0xFFE8EAED.toInt()
            vowelTextPaint.color = 0xFFFF8A50.toInt() // orange accent
            secondaryTextPaint.color = 0xFF9AA0A6.toInt()
            functionTextPaint.color = 0xFFBDC1C6.toInt()
            functionBoldTextPaint.color = 0xFFBDC1C6.toInt()
            clipboardItemTextPaint.color = 0xFFE8EAED.toInt()
            handlePaint.color = 0x66E8EAED.toInt()
            popupBgPaint.color = 0xFFE8EAED.toInt()
            popupTextPaint.color = 0xFF202124.toInt()
        } else {
            bgPaint.color = 0xFFC7CBD2.toInt()
            keyBgPaint.color = 0xFFE0E0E0.toInt()
            keyBgPressedPaint.color = 0xFFBDBDBD.toInt()
            keyBorderPaint.color = 0xFFB0B0B0.toInt()
            primaryTextPaint.color = 0xFF212121.toInt()
            vowelTextPaint.color = 0xFFFF6D00.toInt() // orange accent
            secondaryTextPaint.color = 0xFF9E9E9E.toInt()
            functionTextPaint.color = 0xFF616161.toInt()
            functionBoldTextPaint.color = 0xFF616161.toInt()
            clipboardItemTextPaint.color = 0xFF212121.toInt()
            handlePaint.color = 0x66808080.toInt()
            popupBgPaint.color = 0xFF3C4043.toInt()
            popupTextPaint.color = 0xFFFFFFFF.toInt()
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // Layout definitions
    // ─────────────────────────────────────────────────────────────────────

    // Letters layer. QWERTY-familiar layout from tools/layout_analyzer.py:
    // the familiarity objective (effort + λ·displacement from each letter's
    // QWERTY home, λ=0.5) keeps every key at or next to its QWERTY position,
    // with the 9 rarest letters as double-tap secondaries on the key nearest
    // their QWERTY home. Tone keys X, S, F, R, J sit where Vietnamese Telex
    // typists expect them. A, E, O, D sit on keys without secondaries so the
    // Telex same-key digraphs aa/ee/oo/dd stay typeable via quick double-press.
    private val letterKeys: List<List<KeyDef>> = listOf(
        // Row 1 — "," at the right end, "." below it
        listOf(
            KeyDef("X", "Q"),
            KeyDef("W", "?"),
            KeyDef("E", null, isVowel = true),
            KeyDef("R", null),
            KeyDef("T", null),
            KeyDef("H", "Y"),
            KeyDef("U", null, isVowel = true),
            KeyDef("I", "P", isVowel = true),
            KeyDef("O", null, isVowel = true),
            KeyDef(",", "."),
        ),
        // Row 2 — backspace at the end, narrower than the letters grid so
        // the row centers with a small margin (see layoutKeys())
        listOf(
            KeyDef("A", null, isVowel = true),
            KeyDef("S", "Z"),
            KeyDef("D", null),
            KeyDef("F", "C"),
            KeyDef("G", "V"),
            KeyDef("N", "B"),
            KeyDef("J", "K"),
            KeyDef("M", "L"),
            KeyDef("⌫", null, widthUnits = 1.5f, keyType = KeyType.BACKSPACE),
        ),
        // Row 3 — control row with variable-width spans
        listOf(
            KeyDef("⇧", null, widthUnits = 1.5f, keyType = KeyType.SHIFT),
            KeyDef("123", null, widthUnits = 1f, keyType = KeyType.NUMERIC),
            KeyDef(" ", null, widthUnits = 4f, keyType = KeyType.SPACE),
            KeyDef("📋", null, widthUnits = 1f, keyType = KeyType.CLIPBOARD),
            KeyDef("⏎", null, widthUnits = 1.5f, keyType = KeyType.RETURN),
        ),
    )

    // Numeric layer. Row 1 is digits only; row 2 holds the frequent symbols,
    // with the rarer ones reachable by double-tap (secondaries).
    private val numericKeys: List<List<KeyDef>> = listOf(
        // Row 1 — digits only; symbols live on row 2
        listOf(
            KeyDef("1", null),
            KeyDef("2", null),
            KeyDef("3", null),
            KeyDef("4", null),
            KeyDef("5", null),
            KeyDef("6", null),
            KeyDef("7", null),
            KeyDef("8", null),
            KeyDef("9", null),
            KeyDef("0", null),
        ),
        // Row 2 — frequent symbols, backspace at the end; double-tap gives
        // the remaining symbols ( ( under ), [ under ], & under :)
        listOf(
            KeyDef("@", "~"),
            KeyDef("!", "#"),
            KeyDef("%", "$"),
            KeyDef(":", "&"),
            KeyDef(")", "("),
            KeyDef("-", "_"),
            KeyDef("?", "+"),
            KeyDef("=", ";"),
            KeyDef("/", "'"),
            KeyDef("]", "["),
            KeyDef("⌫", null, keyType = KeyType.BACKSPACE),
        ),
        // Row 3 — control row, "," below "." on the right of the space.
        // 11 total units so the dot matches the symbol-key width in row 2.
        listOf(
            KeyDef("ABC", null, widthUnits = 1.5f, keyType = KeyType.ABC),
            KeyDef(" ", null, widthUnits = 6f, keyType = KeyType.SPACE),
            KeyDef("📋", null, widthUnits = 1f, keyType = KeyType.CLIPBOARD),
            KeyDef(".", ","),
            KeyDef("⏎", null, widthUnits = 1.5f, keyType = KeyType.RETURN),
        ),
    )

    // Clipboard layer rows, rebuilt from the current history (see showClipboardLayer).
    private var clipboardItems: List<String> = emptyList()
    private var clipboardRows: List<List<KeyDef>> = emptyList()

    /** Fixed close button, pinned to the top-right slot corner — no dedicated row. */
    private val clipboardCloseKey = KeyDef("✕", null, keyType = KeyType.CLIPBOARD_CLOSE)

    // Clipboard list scroll state (drag vertically on an item row to scroll).
    private var clipboardScrollPx: Float = 0f
    private var clipboardScrollActive: Boolean = false
    private var clipboardScrollStartY: Float = 0f
    private var clipboardScrollStartPx: Float = 0f

    // Height of one clipboard item slot. Computed per layout — NOT keyHeight,
    // which is stale when the layer switches because the view size does not
    // change (clipboard layer is the same total height as the letters layer).
    private var clipboardSlotH: Float = 0f

    private val keys: List<List<KeyDef>>
        get() = when (currentLayer) {
            KeyboardLayer.LETTERS   -> letterKeys
            KeyboardLayer.NUMERIC   -> numericKeys
            KeyboardLayer.CLIPBOARD -> clipboardRows
        }

    // ─────────────────────────────────────────────────────────────────────
    // Measurement & Layout
    // ─────────────────────────────────────────────────────────────────────

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = if (MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.UNSPECIFIED) {
            resources.displayMetrics.widthPixels
        } else {
            MeasureSpec.getSize(widthMeasureSpec)
        }
        val density = resources.displayMetrics.density
        // The clipboard layer keeps the main keyboard height (letterKeys.size
        // rows) and fits its 5 compact item slots inside it. Row 3 is 75% of
        // the standard row height, so the total is (rows - 1) + 0.75 rows.
        val rows = if (currentLayer == KeyboardLayer.CLIPBOARD) letterKeys.size else keys.size
        val height = ((HANDLE_HEIGHT_DP + rowHeightDp * effectiveRows(rows)) * density).toInt()
        setMeasuredDimension(width, height)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        viewWidth = w
        viewHeight = h

        val density = resources.displayMetrics.density
        handleHeightPx = HANDLE_HEIGHT_DP * density
        // Clipboard layer: compact item slots in the main keyboard's height.
        val rows = if (currentLayer == KeyboardLayer.CLIPBOARD) CLIPBOARD_SLOTS.toFloat()
                   else effectiveRows(keys.size)
        keyHeight = (h - handleHeightPx) / rows
        keyWidth = w.toFloat() / rows  // rough default for hit padding

        // Size text paints proportionally
        primaryTextPaint.textSize = keyHeight * 0.32f
        vowelTextPaint.textSize = keyHeight * 0.32f
        secondaryTextPaint.textSize = keyHeight * 0.20f
        functionTextPaint.textSize = keyHeight * 0.24f
        functionBoldTextPaint.textSize = keyHeight * 0.30f
        popupTextPaint.textSize = keyHeight * 0.5f
        // Clip rows are short — scale text relative to the row, not the key.
        clipboardItemTextPaint.textSize = keyHeight * 0.34f

        layoutKeys()
        clipboardScrollPx = clipboardScrollPx.coerceIn(0f, clipboardMaxScrollPx)

        // Cursor mode granularity: a quarter of a letter-column width per
        // character — smaller step per char makes the cursor move faster
        // for the same horizontal finger travel.
        val aKey = letterKeys[1][0]
        cursorPxPerChar = (aKey.right - aKey.left) * 0.25f
    }

    /** Total row-height units: every row is 1 unit, the last row is 75%. */
    private fun effectiveRows(rowCount: Int): Float = (rowCount - 1) + ROW3_HEIGHT_RATIO

    /** Assign pixel bounds to every key based on column spans. */
    private fun layoutKeys() {
        if (currentLayer == KeyboardLayer.CLIPBOARD) {
            layoutClipboardKeys()
            return
        }
        for ((rowIdx, row) in keys.withIndex()) {
            // The last row (control row) is 75% of the standard row height.
            val rowH = if (rowIdx == keys.lastIndex) keyHeight * ROW3_HEIGHT_RATIO else keyHeight
            val y = handleHeightPx + rowIdx * keyHeight

            // Compute total width-units for this row
            val totalUnits = row.sumOf { it.widthUnits.toDouble() }.toFloat()

            // The letters-layer middle row pins to row 1's 10-unit grid and
            // centers itself: letters keep row 1's exact width, the narrower
            // backspace leaves a small margin on each side, and the slight
            // offset against row 1 gives a natural staggered typing feel.
            val isMiddleLetterRow = currentLayer == KeyboardLayer.LETTERS && rowIdx == 1
            val unitWidth = if (isMiddleLetterRow) viewWidth / 10f else viewWidth / totalUnits
            val rowWidth = if (isMiddleLetterRow) unitWidth * totalUnits else viewWidth.toFloat()
            var x = (viewWidth - rowWidth) / 2f

            for (key in row) {
                val w = key.widthUnits * unitWidth
                key.left = x
                key.top = y
                key.right = x + w
                key.bottom = y + rowH
                x += w
            }
        }
    }

    /**
     * Clipboard layer layout: item rows stack full-width from the top,
     * shifted up by [clipboardScrollPx]. The close button is a small FAB
     * near the bottom-right corner, floating above the list.
     */
    private fun layoutClipboardKeys() {
        val density = resources.displayMetrics.density
        val r = CLIP_FAB_RADIUS_DP * density
        val cx = viewWidth - (CLIP_FAB_MARGIN_DP + r) * density
        val cy = viewHeight - (CLIP_FAB_MARGIN_DP + r) * density
        clipboardCloseKey.left = cx - r
        clipboardCloseKey.top = cy - r
        clipboardCloseKey.right = cx + r
        clipboardCloseKey.bottom = cy + r

        // Slot height from the layer's own geometry, not keyHeight (which is
        // stale until a size change — see clipboardSlotH declaration).
        clipboardSlotH = (viewHeight - handleHeightPx) / CLIPBOARD_SLOTS
        clipboardItemTextPaint.textSize = clipboardSlotH * 0.34f

        var y = handleHeightPx - clipboardScrollPx
        for (row in clipboardRows) {
            for (key in row) {
                key.left = 0f
                key.right = viewWidth.toFloat()
                key.top = y
                key.bottom = y + clipboardSlotH
            }
            y += clipboardSlotH
        }
    }

    /** Called when a new input session starts — always return to letters. */
    fun resetLayer() {
        setLayer(KeyboardLayer.LETTERS)
    }

    /** Switch the displayed layer and re-layout. */
    private fun setLayer(layer: KeyboardLayer) {
        if (currentLayer == layer) return
        currentLayer = layer
        if (layer != KeyboardLayer.LETTERS) shiftActive = false
        lastTapKey = null
        // Row count can differ between layers — remeasure so the IME window
        // resizes and keyHeight is recomputed in onSizeChanged.
        requestLayout()
        layoutKeys()
        invalidate()
    }

    /**
     * Show the clipboard layer with the given history items, one full-width
     * row per item. Tapping the clipboard button while the layer is open
     * toggles it closed, back to letters.
     */
    fun showClipboardLayer(items: List<String>) {
        if (currentLayer == KeyboardLayer.CLIPBOARD) {
            setLayer(KeyboardLayer.LETTERS)
            return
        }
        clipboardItems = items
        clipboardScrollPx = 0f  // newest item is first — start at the top
        rebuildClipboardRows()
        currentLayer = KeyboardLayer.CLIPBOARD
        shiftActive = false
        lastTapKey = null
        requestLayout()
        layoutKeys()
        invalidate()
    }

    /** Refresh the item rows when the clipboard changes while the layer is open. */
    fun updateClipboardItems(items: List<String>) {
        if (currentLayer != KeyboardLayer.CLIPBOARD) return
        clipboardItems = items
        rebuildClipboardRows()
        clipboardScrollPx = clipboardScrollPx.coerceIn(0f, clipboardMaxScrollPx)
        requestLayout()
        layoutKeys()
        invalidate()
    }

    private fun rebuildClipboardRows() {
        clipboardRows = clipboardItems.mapIndexed { index, text ->
            // Display label only — pasting goes through the index, so the
            // full text stays in the IME's history untouched.
            val label = text.replace('\n', ' ').let {
                if (it.length > CLIP_LABEL_MAX_CHARS) it.take(CLIP_LABEL_MAX_CHARS) + "…" else it
            }
            listOf(KeyDef(label, null, index = index, keyType = KeyType.CLIPBOARD_ITEM))
        }
    }

    /** True when the touch x falls in the per-item dismiss (✕) zone. */
    private fun isInDismissZone(key: KeyDef, x: Float): Boolean {
        val zone = resources.displayMetrics.density * CLIP_DISMISS_ZONE_DP
        return x >= key.right - zone
    }

    /** Max scroll offset so the last item can reach the last slot. */
    private val clipboardMaxScrollPx: Float
        get() {
            val itemRows = clipboardItems.size.coerceAtLeast(1)
            val regionH = clipboardSlotH * CLIPBOARD_SLOTS
            return (itemRows * clipboardSlotH - regionH).coerceAtLeast(0f)
        }

    // ─────────────────────────────────────────────────────────────────────
    // Drawing
    // ─────────────────────────────────────────────────────────────────────

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // Opaque background so the IME window is not transparent.
        canvas.drawRect(0f, 0f, viewWidth.toFloat(), viewHeight.toFloat(), bgPaint)

        for (row in keys) {
            for (key in row) {
                drawKey(canvas, key, key == downKey)
            }
        }

        drawKeyPopup(canvas)

        if (currentLayer == KeyboardLayer.CLIPBOARD) {
            // Close FAB floats above the list, and the empty state is a plain
            // centered hint — the layer itself is otherwise blank.
            drawClipboardFab(canvas)
            if (clipboardItems.isEmpty()) {
                canvas.drawText(
                    "Clipboard empty",
                    viewWidth / 2f,
                    viewHeight / 2f,
                    functionTextPaint
                )
            }
        }

        // Drag handle pill at the top center
        val density = resources.displayMetrics.density
        val pillW = HANDLE_PILL_WIDTH_DP * density
        val pillH = HANDLE_PILL_HEIGHT_DP * density
        val pillL = (viewWidth - pillW) / 2f
        val pillT = (handleHeightPx - pillH) / 2f
        canvas.drawRoundRect(
            RectF(pillL, pillT, pillL + pillW, pillT + pillH),
            pillH / 2f, pillH / 2f,
            handlePaint
        )
    }

    private fun drawKey(canvas: Canvas, key: KeyDef, pressed: Boolean) {
        val l = key.left + 2f
        val t = key.top + 2f
        val r = key.right - 2f
        val b = key.bottom - 2f
        val rect = RectF(l, t, r, b)

        // Background
        canvas.drawRoundRect(
            rect, cornerRadius, cornerRadius,
            if (pressed) keyBgPressedPaint else keyBgPaint
        )
        // Border
        canvas.drawRoundRect(rect, cornerRadius, cornerRadius, keyBorderPaint)

        val cx = key.left + (key.right - key.left) / 2f
        val cy = key.top + (key.bottom - key.top) / 2f

        when (key.keyType) {
            KeyType.CHARACTER      -> drawCharacterKey(canvas, key, cx, cy)
            KeyType.CLIPBOARD_ITEM -> drawClipboardItemKey(canvas, key)
            KeyType.CLIPBOARD      -> drawClipboardKey(canvas, cx, cy)
            else                   -> drawFunctionKey(canvas, key, cx, cy)
        }
    }

    /** Floating close button: small circular FAB near the bottom-right,
     *  accent-colored for contrast, labeled ABC — it returns to the letters
     *  layer. Floats above the item rows with a drop shadow. */
    private fun drawClipboardFab(canvas: Canvas) {
        val density = resources.displayMetrics.density
        val key = clipboardCloseKey
        val r = (key.right - key.left) / 2f
        val cx = (key.left + key.right) / 2f
        val cy = (key.top + key.bottom) / 2f

        val fabPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            // Accent orange (same as the vowel accent) stands out from the
            // grey key background.
            color = vowelTextPaint.color
            setShadowLayer(3f * density, 0f, 1.5f * density, 0x80000000.toInt())
        }
        canvas.drawCircle(cx, cy, r, fabPaint)

        val labelPaint = Paint(functionTextPaint).apply {
            color = 0xFF212121.toInt()  // dark label reads on both accent oranges
            textSize = r * 0.55f
        }
        canvas.drawText("ABC", cx, cy + r * 0.2f, labelPaint)
    }

    /** Clipboard button glyph: small monochrome outlined clipboard icon,
     *  matching the other function-key labels (⇧ ⏎) instead of the color emoji. */
    private fun drawClipboardKey(canvas: Canvas, cx: Float, cy: Float) {
        val stroke = resources.displayMetrics.density * 1.1f
        val strokePaint = Paint(functionTextPaint).apply {
            style = Paint.Style.STROKE
            strokeWidth = stroke
        }
        val hw = keyHeight * 0.11f  // half width
        val hh = keyHeight * 0.14f  // half height
        val radius = keyHeight * 0.03f
        // Board
        canvas.drawRoundRect(RectF(cx - hw, cy - hh, cx + hw, cy + hh), radius, radius, strokePaint)
        // Top tab
        val tabW = hw * 0.45f
        canvas.drawRect(RectF(cx - tabW, cy - hh - stroke, cx + tabW, cy - hh + stroke), strokePaint)
        // Divider line under the tab
        canvas.drawLine(cx - tabW, cy - hh * 0.4f, cx + tabW, cy - hh * 0.4f, strokePaint)
    }

    /** Clipboard history rows: single truncated line, left-aligned like a list,
     *  with a dismiss (✕) button on the right. */
    private fun drawClipboardItemKey(canvas: Canvas, key: KeyDef) {
        val density = resources.displayMetrics.density
        val pad = 12f * density
        val baseline = key.top + clipboardSlotH * 0.65f
        canvas.drawText(key.primary, key.left + pad, baseline, clipboardItemTextPaint)
        // Dismiss button — own size, rows are shorter than normal keys now.
        val dismissPaint = Paint(clipboardItemTextPaint).apply {
            textAlign = Paint.Align.CENTER
        }
        val zone = CLIP_DISMISS_ZONE_DP * density
        canvas.drawText("✕", key.right - zone / 2f, baseline, dismissPaint)
    }

    private fun drawCharacterKey(canvas: Canvas, key: KeyDef, cx: Float, cy: Float) {
        val primaryPaint = if (key.isVowel) vowelTextPaint else primaryTextPaint
        val kw = key.right - key.left

        // Primary (top-left quadrant). Labels are lowercase by default and
        // follow the shift latch; the KeyDef literals themselves stay uppercase.
        canvas.drawText(
            resolveCase(key.primary.lowercase()),
            cx - kw * 0.22f,
            cy - keyHeight * 0.12f,
            primaryPaint
        )

        // Secondary (bottom-right quadrant) — only if present
        if (key.secondary != null) {
            canvas.drawText(
                resolveCase(key.secondary.lowercase()),
                cx + kw * 0.22f,
                cy + keyHeight * 0.22f,
                secondaryTextPaint
            )
        }
    }

    /** Key-press popup: bubble with the typed character near the pressed key.
     *  Drawn above the key (below when there is no room, e.g. row 1), while
     *  [downKey] is held. Character keys only. */
    private fun drawKeyPopup(canvas: Canvas) {
        val key = downKey ?: return
        if (!popupEnabled || key.keyType != KeyType.CHARACTER) return
        val density = resources.displayMetrics.density
        val bubbleH = keyHeight * 0.85f
        val bubbleW = maxOf(keyWidth * 1.1f, bubbleH * 1.5f)
        val cx = key.left + (key.right - key.left) / 2f
        val margin = 6f * density

        var top = key.top - margin - bubbleH          // prefer above
        val below = top < handleHeightPx + 2f * density
        if (below) top = key.bottom + margin          // row 1: not enough room
        top = top.coerceAtMost(viewHeight - bubbleH - margin)

        val left = maxOf(4f * density, cx - bubbleW / 2f)
        val right = minOf(viewWidth - 4f * density, cx + bubbleW / 2f)
        if (right <= left) return

        val rect = RectF(left, top, right, top + bubbleH)
        canvas.drawRoundRect(rect, cornerRadius, cornerRadius, popupBgPaint)

        // Pointer tail toward the key
        val tail = 7f * density
        val tailPath = Path()
        if (below) {
            tailPath.moveTo(cx - 5f * density, top + 1f)
            tailPath.lineTo(cx + 5f * density, top + 1f)
            tailPath.lineTo(cx, top - tail)
        } else {
            tailPath.moveTo(cx - 5f * density, rect.bottom - 1f)
            tailPath.lineTo(cx + 5f * density, rect.bottom - 1f)
            tailPath.lineTo(cx, rect.bottom + tail)
        }
        tailPath.close()
        canvas.drawPath(tailPath, popupBgPaint)

        val label = resolveCase(key.primary.lowercase())
        val baseline = rect.centerY() + popupTextPaint.textSize * 0.35f
        canvas.drawText(label, cx, baseline, popupTextPaint)
    }

    private fun drawFunctionKey(canvas: Canvas, key: KeyDef, cx: Float, cy: Float) {
        val text = when (key.keyType) {
            KeyType.SHIFT     -> "⇧"
            KeyType.BACKSPACE -> "⌫"
            KeyType.NUMERIC   -> "123"
            KeyType.ABC       -> "ABC"
            KeyType.SPACE     -> ""
            KeyType.RETURN    -> "⏎"
            else              -> key.primary
        }

        // Pressed state bg for shift when active
        if (key.keyType == KeyType.SHIFT && shiftActive) {
            val l = key.left + 2f
            val t = key.top + 2f
            val r = key.right - 2f
            val b = key.bottom - 2f
            canvas.drawRoundRect(
                RectF(l, t, r, b), cornerRadius, cornerRadius,
                keyBgPressedPaint
            )
        }

        if (text.isNotEmpty()) {
            val paint = if (key.keyType == KeyType.SHIFT || key.keyType == KeyType.RETURN) {
                functionBoldTextPaint
            } else {
                functionTextPaint
            }
            // Per-key height keeps labels centered on the shorter row 3.
            val rowH = key.bottom - key.top
            canvas.drawText(text, cx, cy + rowH * 0.1f, paint)
        }

        // Space bar is deliberately unlabeled — a wide blank key.
    }

    /** Apply shift state: uppercase the first char of [s]. */
    private fun resolveCase(s: String): String {
        if (!shiftActive || s.isEmpty()) return s
        val first = s[0]
        return if (first.isLowerCase()) first.uppercaseChar() + s.substring(1)
        else s
    }

    // ─────────────────────────────────────────────────────────────────────
    // Touch Handling
    // ─────────────────────────────────────────────────────────────────────

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (event.y < handleHeightPx) {
                    // Drag the handle strip to resize the keyboard.
                    dragActive = true
                    dragStartY = event.y
                    dragStartRowDp = rowHeightDp
                    invalidate()
                    return true
                }

                downKey = findKeyAt(event.x, event.y)
                downX = event.x
                downY = event.y
                isSwipeDetected = false
                longPressTriggered = false
                spaceCursorMode = false
                lastCursorChars = 0

                if (downKey?.keyType == KeyType.BACKSPACE) {
                    // Hold-to-repeat instead of long-press secondary.
                    scheduleBackspaceRepeat()
                } else if (downKey?.keyType == KeyType.CLIPBOARD_ITEM) {
                    // No long-press: item rows are for tap-to-paste and
                    // drag-to-scroll; a long-press pasting mid-scroll would
                    // be accidental.
                } else {
                    longPressRunnable = Runnable {
                        if (downKey != null && !isSwipeDetected && !longPressTriggered) {
                            longPressTriggered = true
                            haptic(HapticFeedbackConstants.LONG_PRESS)
                            lastTapKey = null
                            if (downKey?.keyType == KeyType.SPACE) {
                                // Long-press space enters cursor mode; the key
                                // stays pressed and drags move the cursor.
                                spaceCursorMode = true
                                lastCursorChars = 0
                            } else {
                                commitSecondary(downKey!!, replace = false)
                                downKey = null
                            }
                            invalidate()
                        }
                    }
                    longPressHandler.postDelayed(longPressRunnable!!, LONG_PRESS_MS)
                }
                invalidate()
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (dragActive) {
                    val density = resources.displayMetrics.density
                    // Use the visible row count — the clipboard layer always
                    // shows its 5 compact slots even when the list is longer.
                    val rows = if (currentLayer == KeyboardLayer.CLIPBOARD) CLIPBOARD_SLOTS.toFloat()
                               else effectiveRows(keys.size)
                    val rowDelta = (dragStartY - event.y) / density / rows
                    rowHeightDp = (dragStartRowDp + rowDelta)
                        .coerceIn(Prefs.ROW_HEIGHT_MIN_DP, Prefs.ROW_HEIGHT_MAX_DP)
                    requestLayout()
                    return true
                }

                if (spaceCursorMode) {
                    // Cursor mode: horizontal finger position maps to a cursor
                    // offset from the touch-down point, one step per
                    // cursorPxPerChar. Emit only the delta since last step.
                    val dx = event.x - downX
                    val chars = Math.round(dx / cursorPxPerChar)
                    if (chars != lastCursorChars) {
                        haptic(HapticFeedbackConstants.KEYBOARD_TAP)
                        onKeyActionListener?.onCursorMove(chars - lastCursorChars)
                        lastCursorChars = chars
                    }
                    invalidate()
                    return true
                }

                if (downKey?.keyType == KeyType.CLIPBOARD_ITEM) {
                    // Drag vertically on an item row to scroll the list.
                    val dy = event.y - downY
                    if (!clipboardScrollActive && abs(dy) > touchSlop) {
                        clipboardScrollActive = true
                        clipboardScrollStartY = event.y
                        clipboardScrollStartPx = clipboardScrollPx
                    }
                    if (clipboardScrollActive) {
                        clipboardScrollPx = (clipboardScrollStartPx - (event.y - clipboardScrollStartY))
                            .coerceIn(0f, clipboardMaxScrollPx)
                        layoutKeys()
                        invalidate()
                        return true
                    }
                }

                if (downKey == null || longPressTriggered) return true

                val dy = event.y - downY
                // Require vertical movement > touch slop AND vertical dominates horizontal
                if (dy > touchSlop && dy > abs(event.x - downX)) {
                    isSwipeDetected = true
                    longPressRunnable?.let { longPressHandler.removeCallbacks(it) }
                }
                invalidate()
                return true
            }

            MotionEvent.ACTION_UP -> {
                if (dragActive) {
                    dragActive = false
                    prefs.edit().putFloat(Prefs.KEY_ROW_HEIGHT_DP, rowHeightDp).apply()
                    invalidate()
                    return true
                }

                longPressRunnable?.let { longPressHandler.removeCallbacks(it) }
                backspaceRepeatRunnable?.let { repeatHandler.removeCallbacks(it) }

                val key = downKey
                if (key != null && !longPressTriggered) {
                    if (key.keyType == KeyType.BACKSPACE) {
                        // Released before the repeat kicked in → single delete.
                        if (!backspaceRepeatActive) {
                            haptic(HapticFeedbackConstants.KEYBOARD_TAP)
                            onKeyActionListener?.onBackspace()
                        }
                    } else if (isSwipeDetected) {
                        haptic(HapticFeedbackConstants.KEYBOARD_TAP)
                        lastTapKey = null
                        commitSecondary(key, replace = false)
                    } else if (key.keyType == KeyType.CLIPBOARD_ITEM) {
                        if (clipboardScrollActive) {
                            // Scroll gesture, not a tap — keep the position.
                        } else {
                            haptic(HapticFeedbackConstants.KEYBOARD_TAP)
                            if (isInDismissZone(key, event.x)) {
                                onKeyActionListener?.onClipboardDismiss(key.index)
                            } else {
                                onKeyActionListener?.onClipboardItem(key.index)
                            }
                        }
                    } else {
                        handleQuickTap(key)
                    }
                }

                backspaceRepeatActive = false
                spaceCursorMode = false
                clipboardScrollActive = false
                lastCursorChars = 0
                downKey = null
                invalidate()
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                dragActive = false
                longPressRunnable?.let { longPressHandler.removeCallbacks(it) }
                backspaceRepeatRunnable?.let { repeatHandler.removeCallbacks(it) }
                backspaceRepeatActive = false
                spaceCursorMode = false
                clipboardScrollActive = false
                lastCursorChars = 0
                downKey = null
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    /** Vibration on key press, gated by the user toggle. */
    private fun haptic(feedback: Int) {
        if (hapticEnabled) performHapticFeedback(feedback)
    }

    /**
     * Quick tap. First tap emits the top (primary) character; a second tap on
     * the same key within [doubleTapMs] replaces it with the bottom
     * (secondary) character.
     */
    private fun handleQuickTap(key: KeyDef) {
        haptic(HapticFeedbackConstants.KEYBOARD_TAP)

        val now = SystemClock.uptimeMillis()
        val isDoubleTap = (currentLayer == KeyboardLayer.LETTERS ||
            currentLayer == KeyboardLayer.NUMERIC) &&
            key.secondary != null &&
            key === lastTapKey && now - lastTapTime <= doubleTapMs

        if (isDoubleTap) {
            commitSecondary(key, replace = true)
            lastTapKey = null
        } else {
            commitPrimary(key)
            lastTapKey = key
            lastTapTime = now
        }
    }

    /** Repeatedly fire [OnKeyActionListener.onBackspace] while held down. */
    private fun scheduleBackspaceRepeat() {
        backspaceRepeatActive = false
        val runnable = object : Runnable {
            override fun run() {
                if (downKey?.keyType != KeyType.BACKSPACE) return
                backspaceRepeatActive = true
                onKeyActionListener?.onBackspace()
                repeatHandler.postDelayed(this, BACKSPACE_REPEAT_MS)
            }
        }
        backspaceRepeatRunnable = runnable
        repeatHandler.postDelayed(runnable, BACKSPACE_INITIAL_DELAY_MS)
    }

    // ── Key lookup ────────────────────────────────────────────────────────

    private fun findKeyAt(x: Float, y: Float): KeyDef? {
        // The clipboard close FAB floats above the list — hit it first.
        if (currentLayer == KeyboardLayer.CLIPBOARD) {
            val ck = clipboardCloseKey
            val r = (ck.right - ck.left) / 2f
            val cx = (ck.left + ck.right) / 2f
            val cy = (ck.top + ck.bottom) / 2f
            val dx = x - cx
            val dy = y - cy
            if (dx * dx + dy * dy <= r * r) return ck
        }
        for (row in keys) {
            for (key in row) {
                // Off-view keys (scrolled clipboard items) never hit.
                if (key.bottom <= handleHeightPx || key.top >= viewHeight) continue
                // Slightly expand hit area for narrow keys
                val pad = min(keyWidth * 0.05f, 4f)
                if (x >= key.left - pad && x <= key.right + pad &&
                    y >= key.top  - pad && y <= key.bottom + pad
                ) {
                    return key
                }
            }
        }
        return null
    }

    // ── Commit helpers ────────────────────────────────────────────────────

    private fun commitPrimary(key: KeyDef) {
        val listener = onKeyActionListener ?: return
        when (key.keyType) {
            KeyType.CHARACTER -> {
                val ch = if (shiftActive) {
                    key.primary[0].uppercaseChar()
                } else {
                    key.primary[0].lowercaseChar()
                }
                // Auto-release shift after one character
                if (shiftActive) shiftActive = false

                if (currentLayer == KeyboardLayer.NUMERIC) {
                    // Numbers and symbols commit directly, no Telex processing.
                    listener.onDirectCharacter(ch)
                } else {
                    listener.onCharacter(ch)
                }
            }
            KeyType.BACKSPACE -> listener.onBackspace()
            KeyType.SHIFT     -> {
                shiftActive = !shiftActive
                listener.onShift()
            }
            KeyType.NUMERIC   -> setLayer(KeyboardLayer.NUMERIC)
            KeyType.ABC       -> setLayer(KeyboardLayer.LETTERS)
            KeyType.SPACE     -> listener.onSpace()
            KeyType.RETURN    -> listener.onReturn()
            KeyType.CLIPBOARD -> listener.onClipboard()
            KeyType.CLIPBOARD_ITEM -> listener.onClipboardItem(key.index)
            KeyType.CLIPBOARD_CLOSE -> setLayer(KeyboardLayer.LETTERS)
        }
    }

    private fun commitSecondary(key: KeyDef, replace: Boolean) {
        val listener = onKeyActionListener ?: return
        if (key.secondary != null) {
            val ch = if (shiftActive) {
                key.secondary[0].uppercaseChar()
            } else {
                key.secondary[0].lowercaseChar()
            }
            if (shiftActive) shiftActive = false
            if (replace) {
                if (currentLayer == KeyboardLayer.NUMERIC) {
                    // Double-tap: the digit was already committed directly,
                    // so the editor replaces it (no Telex buffer involved).
                    listener.onReplaceDirectCharacter(ch)
                } else {
                    listener.onReplaceCharacter(ch)
                }
            } else if (currentLayer == KeyboardLayer.NUMERIC) {
                // Numeric-layer symbols commit directly, no Telex processing.
                listener.onDirectCharacter(ch)
            } else {
                listener.onCharacter(ch)
            }
        } else {
            // Fallback: if no secondary defined, treat as primary
            commitPrimary(key)
        }
    }

    companion object {
        private const val LONG_PRESS_MS = 350L
        private const val BACKSPACE_INITIAL_DELAY_MS = 400L
        private const val BACKSPACE_REPEAT_MS = 60L

        private const val HANDLE_HEIGHT_DP = 14f
        private const val HANDLE_PILL_WIDTH_DP = 40f
        private const val HANDLE_PILL_HEIGHT_DP = 4f

        /** Row 3 (control row) is 75% of the standard row height. */
        private const val ROW3_HEIGHT_RATIO = 0.75f

        /** Max chars of a clipboard item shown on the list row (display only;
         *  keeps the label clear of the dismiss button). */
        private const val CLIP_LABEL_MAX_CHARS = 30

        /** Item slots visible on the clipboard layer without scrolling — 5
         *  compact rows squeezed into the main keyboard's height; the full
         *  history (30) scrolls through them. */
        private const val CLIPBOARD_SLOTS = 5

        /** Width of the per-item dismiss (✕) button zone. */
        private const val CLIP_DISMISS_ZONE_DP = 40f

        /** Radius of the floating close FAB on the clipboard layer. */
        private const val CLIP_FAB_RADIUS_DP = 18f

        /** Distance of the close FAB from the layer's bottom/right edges. */
        private const val CLIP_FAB_MARGIN_DP = 10f
    }
}
