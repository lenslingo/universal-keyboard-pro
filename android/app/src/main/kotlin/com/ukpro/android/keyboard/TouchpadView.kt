package com.ukpro.android.keyboard

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

/** Where touchpad events go (UkRuntime → MOUSE_MOVE / MOUSE_BUTTON / MOUSE_SCROLL). */
interface MouseSink {
    fun move(dx: Int, dy: Int)
    fun button(button: String, action: String)
    fun scroll(dx: Int, dy: Int)
}

/**
 * Laptop-style touchpad:
 *  • 1 finger: move pointer (with acceleration) · tap = left click · double-tap-and-hold or long-press = drag
 *  • 2 fingers: tap = right click · drag = scroll (vertical + horizontal)
 *  • right edge strip: one-finger scroll · bottom bar: Left / Middle / Right buttons (hold to drag)
 */
class TouchpadView(context: Context) : View(context) {
    var sink: MouseSink? = null
    var sensitivity = 1.6f
    var scrollSpeed = 1.0f
    var naturalScroll = true // content follows the fingers, like phones and Windows precision touchpads
    var dark = true; set(v) { field = v; invalidate() }
    var heightScale = 1.0f; set(v) { field = v.coerceIn(0.5f, 2.0f); requestLayout() }

    private val density = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private var pad = RectF(); private var strip = RectF()
    private val buttons = LinkedHashMap<String, RectF>()
    private val pressedButtons = HashMap<Int, String>()

    // gesture state
    private var lastX = 0f; private var lastY = 0f
    private var downTime = 0L; private var downX = 0f; private var downY = 0f
    private var moved = false
    private var maxPointers = 0
    private var twoFingerLastY = 0f; private var twoFingerLastX = 0f
    private var lastTapUp = 0L
    private var dragging = false
    private var inStrip = false
    private var fracX = 0f; private var fracY = 0f; private var scrollAccX = 0f; private var scrollAccY = 0f
    private val longPress = Runnable { if (!moved && maxPointers == 1 && !inStrip) startDrag() }

    init { isForceDarkAllowed = false; isHapticFeedbackEnabled = true }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(w, (250 * density * heightScale).toInt())
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val g = 6 * density
        val btnH = 46 * density
        val stripW = 34 * density
        pad = RectF(g, g, w - stripW - 2 * g, h - btnH - 2 * g)
        strip = RectF(w - stripW - g, g, w - g, h - btnH - 2 * g)
        val bw = (w - 4 * g) / 3f
        buttons.clear()
        buttons["left"] = RectF(g, h - btnH - g, g + bw, h - g)
        buttons["middle"] = RectF(2 * g + bw, h - btnH - g, 2 * g + 2 * bw, h - g)
        buttons["right"] = RectF(3 * g + 2 * bw, h - btnH - g, w - g, h - g)
    }

    private fun c(darkColor: Long, light: Long) = (if (dark) darkColor else light).toInt()

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(c(0xFF0F1320, 0xFFD5D9E2))
        val r = 12 * density
        paint.color = c(0xFF1A2034, 0xFFFFFFFF); canvas.drawRoundRect(pad, r, r, paint)
        paint.color = c(0xFF222A42, 0xFFC1C7D4); canvas.drawRoundRect(strip, r, r, paint)
        text.color = c(0xFF8E97B3, 0xFF5B6478); text.textSize = 12 * density
        canvas.drawText(if (dragging) "Dragging… lift to drop" else "Touchpad · tap = click · 2 fingers = right-click / scroll",
            pad.centerX(), pad.centerY(), text)
        canvas.drawText("⇕", strip.centerX(), strip.centerY(), text)
        for ((name, rect) in buttons) {
            val held = pressedButtons.containsValue(name) || (dragging && name == "left")
            paint.color = if (held) 0xFF5CC8FF.toInt() else c(0xFF222A42, 0xFFFFFFFF)
            canvas.drawRoundRect(rect, r, r, paint)
            text.color = if (held) 0xFF0F1320.toInt() else c(0xFFE8ECF8, 0xFF1B2133); text.textSize = 14 * density
            canvas.drawText(when (name) { "left" -> "Left"; "middle" -> "Middle"; else -> "Right" }, rect.centerX(),
                rect.centerY() - (text.descent() + text.ascent()) / 2, text)
        }
    }

    private fun startDrag() {
        dragging = true
        sink?.button("left", "down")
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        invalidate()
    }

    private fun endDrag() {
        if (!dragging) return
        dragging = false
        sink?.button("left", "up")
        invalidate()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        val i = e.actionIndex
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true) // a scrolling screen must not steal touchpad drags
                val x = e.getX(i); val y = e.getY(i)
                val btn = buttons.entries.firstOrNull { it.value.contains(x, y) }?.key
                if (btn != null) {
                    pressedButtons[e.getPointerId(i)] = btn
                    sink?.button(btn, "down"); performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP); invalidate()
                    return true
                }
                if (e.actionMasked == MotionEvent.ACTION_DOWN) {
                    downTime = SystemClock.uptimeMillis(); downX = x; downY = y; lastX = x; lastY = y
                    moved = false; maxPointers = 1; inStrip = strip.contains(x, y)
                    // double-tap-and-hold = drag (like a laptop touchpad)
                    if (!inStrip && downTime - lastTapUp < 250) startDrag() else postDelayed(longPress, 550)
                } else {
                    maxPointers = maxOf(maxPointers, padPointers(e))
                    if (padPointers(e) >= 2) { val (cx, cy) = centroid(e); twoFingerLastX = cx; twoFingerLastY = cy }
                }
            }
            MotionEvent.ACTION_MOVE -> {
                val n = padPointers(e)
                if (n >= 2) {
                    val (cx, cy) = centroid(e)
                    scroll(cx - twoFingerLastX, cy - twoFingerLastY)
                    twoFingerLastX = cx; twoFingerLastY = cy; moved = true
                } else if (n == 1) {
                    val idx = (0 until e.pointerCount).firstOrNull { e.getPointerId(it) !in pressedButtons.keys } ?: return true
                    val x = e.getX(idx); val y = e.getY(idx)
                    val dx = x - lastX; val dy = y - lastY
                    if (hypot(x - downX, y - downY) > 8 * density) { moved = true; removeCallbacks(longPress) }
                    if (inStrip) scroll(0f, dy) else movePointer(dx, dy, e.eventTime - e.getHistoricalEventTimeOrNow())
                    lastX = x; lastY = y
                }
            }
            MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val pid = e.getPointerId(i)
                val btn = pressedButtons.remove(pid)
                if (btn != null) { sink?.button(btn, "up"); invalidate() }
                else if (e.actionMasked == MotionEvent.ACTION_POINTER_UP) {
                    // keep the remaining finger's position as reference so the pointer does not jump
                    val rest = (0 until e.pointerCount).firstOrNull { it != i && e.getPointerId(it) !in pressedButtons.keys }
                    if (rest != null) { lastX = e.getX(rest); lastY = e.getY(rest) }
                }
                if (e.actionMasked != MotionEvent.ACTION_POINTER_UP) {
                    removeCallbacks(longPress)
                    val quick = SystemClock.uptimeMillis() - downTime < 220 && !moved
                    when {
                        btn != null -> {}
                        dragging -> endDrag()
                        e.actionMasked == MotionEvent.ACTION_CANCEL -> {}
                        quick && maxPointers >= 2 -> sink?.button("right", "click")
                        quick && !inStrip -> { sink?.button("left", "click"); lastTapUp = SystemClock.uptimeMillis() }
                    }
                    maxPointers = 0
                }
            }
        }
        return true
    }

    private fun MotionEvent.getHistoricalEventTimeOrNow(): Long = if (historySize > 0) getHistoricalEventTime(0) else eventTime - 16

    private fun padPointers(e: MotionEvent) = (0 until e.pointerCount).count { e.getPointerId(it) !in pressedButtons.keys }

    private fun centroid(e: MotionEvent): Pair<Float, Float> {
        var x = 0f; var y = 0f; var n = 0
        for (k in 0 until e.pointerCount) if (e.getPointerId(k) !in pressedButtons.keys) { x += e.getX(k); y += e.getY(k); n++ }
        return if (n == 0) 0f to 0f else x / n to y / n
    }

    /** Pointer acceleration: slow movements are precise, fast flicks cross the screen. */
    private fun movePointer(dxPx: Float, dyPx: Float, dtMs: Long) {
        val dist = hypot(dxPx, dyPx) / density                     // in dp
        val speed = dist / maxOf(dtMs, 1L)                         // dp per ms
        val accel = 1f + minOf(speed * 1.8f, 3f)
        val k = sensitivity * accel
        fracX += dxPx / density * k; fracY += dyPx / density * k
        val ix = fracX.roundToInt(); val iy = fracY.roundToInt()
        fracX -= ix; fracY -= iy
        if (ix != 0 || iy != 0) sink?.move(ix, iy)
    }

    /** Wheel units (120 = 1 notch). Positive dy = scroll down (content moves up), as with a laptop touchpad. */
    private fun scroll(dxPx: Float, dyPx: Float) {
        val dir = if (naturalScroll) 1f else -1f
        scrollAccX += -dxPx / density * 4f * scrollSpeed * dir
        scrollAccY += dyPx / density * 4f * scrollSpeed * -dir
        val ix = scrollAccX.toInt(); val iy = scrollAccY.toInt()
        if (abs(ix) >= 10 || abs(iy) >= 10) {
            scrollAccX -= ix; scrollAccY -= iy
            sink?.scroll(ix, iy)
        }
    }
}
