package com.miniqwerty.kb

import android.content.Context
import android.graphics.*
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
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
    fun onBackspace()
    fun onShift()
    fun onNumeric()
    fun onSpace()
    fun onReturn()
}

// ─────────────────────────────────────────────────────────────────────────────
// Key Definitions
// ─────────────────────────────────────────────────────────────────────────────

private enum class KeyType {
    CHARACTER, BACKSPACE, SHIFT, NUMERIC, ABC, SYMBOLS, SPACE, RETURN
}

/** Which keyboard layer is currently displayed. */
private enum class KeyboardLayer { LETTERS, NUMERIC }

private data class KeyDef(
    val primary: String,
    val secondary: String?,
    val isVowel: Boolean = false,
    val widthUnits: Float = 1f,
    val keyType: KeyType = KeyType.CHARACTER,
    /** Explicit display label for function keys. */
    val label: String? = null,
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

    // ── Dimensions (set during onSizeChanged) ─────────────────────────────
    private var viewWidth: Int = 0
    private var viewHeight: Int = 0
    private var keyWidth: Float = 0f
    private var keyHeight: Float = 0f
    private var colWidth: Float = 0f

    // ── Layer state ───────────────────────────────────────────────────────
    private var currentLayer: KeyboardLayer = KeyboardLayer.LETTERS

    // ── Touch-tracking state ──────────────────────────────────────────────
    private var downKey: KeyDef? = null
    private var downX: Float = 0f
    private var downY: Float = 0f
    private var isSwipeDetected: Boolean = false
    private var longPressTriggered: Boolean = false
    private val touchSlop: Int = ViewConfiguration.get(context).scaledTouchSlop

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
    private val keyBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFE0E0E0.toInt()
        style = Paint.Style.FILL
    }
    private val keyBgPressedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFBDBDBD.toInt()
        style = Paint.Style.FILL
    }
    private val keyBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFB0B0B0.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 1f
    }
    private val primaryTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF212121.toInt()
        textAlign = Paint.Align.CENTER
        isFakeBoldText = false
    }
    private val vowelTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFF6D00.toInt() // orange accent
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }
    private val secondaryTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF9E9E9E.toInt()
        textAlign = Paint.Align.CENTER
    }
    private val functionTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF616161.toInt()
        textAlign = Paint.Align.CENTER
        isFakeBoldText = false
    }

    // ── Corner radius ─────────────────────────────────────────────────────
    private val cornerRadius = 6f

    // ─────────────────────────────────────────────────────────────────────
    // Layout definitions
    // ─────────────────────────────────────────────────────────────────────

    // Letters layer — 9 columns × 3 rows.
    // F, X, J are the Telex tone keys, so they sit on the primary (top) slot.
    private val letterKeys: List<List<KeyDef>> = listOf(
        // Row 1 — 10 columns so both R and the Telex tone key F get a top slot
        listOf(
            KeyDef("W", "Q"),
            KeyDef("E", null, isVowel = true),
            KeyDef("R", null),
            KeyDef("F", null),
            KeyDef("T", "G"),
            KeyDef("Y", "P"),
            KeyDef("U", ",", isVowel = true),
            KeyDef("I", null, isVowel = true),
            KeyDef("O", ".", isVowel = true),
            KeyDef("⌫", null, keyType = KeyType.BACKSPACE),
        ),
        // Row 2
        listOf(
            KeyDef("A", null, isVowel = true),
            KeyDef("S", "Z"),
            KeyDef("X", "D"),
            KeyDef("C", "V"),
            KeyDef("H", "B"),
            KeyDef("J", "N"),
            KeyDef("M", "K"),
            KeyDef("L", "?"),
            KeyDef("!", "."),
        ),
        // Row 3 — control row with variable-width spans
        listOf(
            KeyDef("⇧", null, widthUnits = 1.5f, keyType = KeyType.SHIFT),
            KeyDef("123", null, widthUnits = 1f, keyType = KeyType.NUMERIC),
            KeyDef(" ", null, widthUnits = 5f, keyType = KeyType.SPACE),
            KeyDef("⏎", null, widthUnits = 1.5f, keyType = KeyType.RETURN),
        ),
    )

    // Numeric layer. Row 2 is denser (10 columns) to fit the full symbol set.
    private val numericKeys: List<List<KeyDef>> = listOf(
        // Row 1 — digits
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
        ),
        // Row 2 — symbols
        listOf(
            KeyDef("0", null),
            KeyDef("-", null),
            KeyDef("/", null),
            KeyDef(":", null),
            KeyDef(";", null),
            KeyDef("(", null),
            KeyDef(")", null),
            KeyDef("$", null),
            KeyDef("&", null),
            KeyDef("@", null),
        ),
        // Row 3 — control row with variable-width spans
        listOf(
            KeyDef("#+=", null, widthUnits = 2f, keyType = KeyType.SYMBOLS),
            KeyDef(",", null),
            KeyDef(".", null),
            KeyDef(" ", null, widthUnits = 2f, keyType = KeyType.SPACE),
            KeyDef("ABC", null, widthUnits = 2f, keyType = KeyType.ABC),
            KeyDef("⌫", null, widthUnits = 2f, keyType = KeyType.BACKSPACE),
        ),
    )

    private val keys: List<List<KeyDef>>
        get() = if (currentLayer == KeyboardLayer.LETTERS) letterKeys else numericKeys

    // ─────────────────────────────────────────────────────────────────────
    // Measurement & Layout
    // ─────────────────────────────────────────────────────────────────────

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // Fixed compact height (3 rows). Without this, the default View
        // measurement returns the full AT_MOST spec size, making the IME
        // fill the entire screen.
        val width = if (MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.UNSPECIFIED) {
            resources.displayMetrics.widthPixels
        } else {
            MeasureSpec.getSize(widthMeasureSpec)
        }
        val density = resources.displayMetrics.density
        val height = (KEY_ROW_HEIGHT_DP * keys.size * density).toInt()
        setMeasuredDimension(width, height)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        viewWidth = w
        viewHeight = h

        val rows = keys.size
        keyHeight = h.toFloat() / rows
        colWidth = w.toFloat() / 9f // base column width
        keyWidth = colWidth   // default 1-unit key width

        // Size text paints proportionally
        primaryTextPaint.textSize = keyHeight * 0.32f
        vowelTextPaint.textSize = keyHeight * 0.32f
        secondaryTextPaint.textSize = keyHeight * 0.20f
        functionTextPaint.textSize = keyHeight * 0.24f

        layoutKeys()
    }

    /** Assign pixel bounds to every key based on column spans. */
    private fun layoutKeys() {
        for ((rowIdx, row) in keys.withIndex()) {
            val y = rowIdx * keyHeight
            var x = 0f

            // Compute total width-units for this row
            val totalUnits = row.sumOf { it.widthUnits.toDouble() }.toFloat()
            val unitWidth = viewWidth / totalUnits

            for (key in row) {
                val w = key.widthUnits * unitWidth
                key.left = x
                key.top = y
                key.right = x + w
                key.bottom = y + keyHeight
                x += w
            }
        }
    }

    /** Switch the displayed layer and re-layout. */
    private fun setLayer(layer: KeyboardLayer) {
        if (currentLayer == layer) return
        currentLayer = layer
        if (layer == KeyboardLayer.NUMERIC) shiftActive = false
        lastTapKey = null
        layoutKeys()
        invalidate()
    }

    // ─────────────────────────────────────────────────────────────────────
    // Drawing
    // ─────────────────────────────────────────────────────────────────────

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        for (row in keys) {
            for (key in row) {
                drawKey(canvas, key, key == downKey)
            }
        }
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
            KeyType.CHARACTER -> drawCharacterKey(canvas, key, cx, cy)
            else              -> drawFunctionKey(canvas, key, cx, cy)
        }
    }

    private fun drawCharacterKey(canvas: Canvas, key: KeyDef, cx: Float, cy: Float) {
        val primaryPaint = if (key.isVowel) vowelTextPaint else primaryTextPaint

        // Primary (top-left quadrant)
        canvas.drawText(
            resolveCase(key.primary),
            cx - keyWidth * 0.22f,
            cy - keyHeight * 0.12f,
            primaryPaint
        )

        // Secondary (bottom-right quadrant) — only if present
        if (key.secondary != null) {
            canvas.drawText(
                resolveCase(key.secondary),
                cx + keyWidth * 0.22f,
                cy + keyHeight * 0.22f,
                secondaryTextPaint
            )
        }
    }

    private fun drawFunctionKey(canvas: Canvas, key: KeyDef, cx: Float, cy: Float) {
        val text = key.label ?: when (key.keyType) {
            KeyType.SHIFT     -> "⇧"
            KeyType.BACKSPACE -> "⌫"
            KeyType.NUMERIC   -> "123"
            KeyType.ABC       -> "ABC"
            KeyType.SYMBOLS   -> "#+="
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
            canvas.drawText(text, cx, cy + keyHeight * 0.1f, functionTextPaint)
        }

        // Space bar subtle label
        if (key.keyType == KeyType.SPACE) {
            val smallPaint = Paint(functionTextPaint).apply {
                textSize = keyHeight * 0.18f
                alpha = 128
            }
            canvas.drawText("space", cx, cy + keyHeight * 0.1f, smallPaint)
        }
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
                downKey = findKeyAt(event.x, event.y)
                downX = event.x
                downY = event.y
                isSwipeDetected = false
                longPressTriggered = false

                if (downKey?.keyType == KeyType.BACKSPACE) {
                    // Hold-to-repeat instead of long-press secondary.
                    scheduleBackspaceRepeat()
                } else {
                    longPressRunnable = Runnable {
                        if (downKey != null && !isSwipeDetected && !longPressTriggered) {
                            longPressTriggered = true
                            performHapticFeedback(HAPTIC_FEEDBACK_ENABLED)
                            lastTapKey = null
                            commitSecondary(downKey!!, replace = false)
                            downKey = null
                            invalidate()
                        }
                    }
                    longPressHandler.postDelayed(longPressRunnable!!, LONG_PRESS_MS)
                }
                invalidate()
                return true
            }

            MotionEvent.ACTION_MOVE -> {
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
                longPressRunnable?.let { longPressHandler.removeCallbacks(it) }
                backspaceRepeatRunnable?.let { repeatHandler.removeCallbacks(it) }

                val key = downKey
                if (key != null && !longPressTriggered) {
                    if (key.keyType == KeyType.BACKSPACE) {
                        // Released before the repeat kicked in → single delete.
                        if (!backspaceRepeatActive) {
                            performHapticFeedback(HAPTIC_FEEDBACK_ENABLED)
                            onKeyActionListener?.onBackspace()
                        }
                    } else if (isSwipeDetected) {
                        performHapticFeedback(HAPTIC_FEEDBACK_ENABLED)
                        lastTapKey = null
                        commitSecondary(key, replace = false)
                    } else {
                        handleQuickTap(key)
                    }
                }

                backspaceRepeatActive = false
                downKey = null
                invalidate()
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                longPressRunnable?.let { longPressHandler.removeCallbacks(it) }
                backspaceRepeatRunnable?.let { repeatHandler.removeCallbacks(it) }
                backspaceRepeatActive = false
                downKey = null
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    /**
     * Quick tap. First tap emits the top (primary) character; a second tap on
     * the same key within [DOUBLE_TAP_MS] replaces it with the bottom
     * (secondary) character. Only letter secondaries take part — punctuation
     * secondaries stay on long-press/swipe so Telex transforms like oo→ô
     * keep working under fast typing.
     */
    private fun handleQuickTap(key: KeyDef) {
        performHapticFeedback(HAPTIC_FEEDBACK_ENABLED)

        val now = SystemClock.uptimeMillis()
        val secondary = key.secondary
        val isDoubleTap = currentLayer == KeyboardLayer.LETTERS &&
            secondary != null && secondary.first().isLetter() &&
            key === lastTapKey && now - lastTapTime <= DOUBLE_TAP_MS

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
        for (row in keys) {
            for (key in row) {
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
            KeyType.SYMBOLS   -> {
                // No extended symbol layer yet — dead key.
            }
            KeyType.SPACE     -> listener.onSpace()
            KeyType.RETURN    -> listener.onReturn()
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
                listener.onReplaceCharacter(ch)
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
        private const val DOUBLE_TAP_MS = 250L
        private const val BACKSPACE_INITIAL_DELAY_MS = 400L
        private const val BACKSPACE_REPEAT_MS = 60L
        private const val HAPTIC_FEEDBACK_ENABLED = 1 // matches HapticFeedbackConstants
        private const val KEY_ROW_HEIGHT_DP = 46f
    }
}
