package com.ukpro.android.keyboard

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.media.AudioManager
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import com.ukpro.core.KeyMods

interface KeyboardActionListener {
    /** A key fired. [mods] = protocol bitmask (Ctrl/Shift/Alt/Meta/Caps); [text] = character(s) for CHAR keys. */
    fun onKey(key: Key, mods: Int, text: String?)
    /** APP keys: "LANG", "PANEL". */
    fun onAppKey(code: String)
}

/**
 * Canvas-drawn desktop keyboard: multi-touch (hold Ctrl with one finger, tap C with another),
 * key repeat, long-press alternatives, one-shot / locked modifiers, Caps Lock, adjustable height,
 * dark/light themes, sound and vibration feedback.
 */
class DesktopKeyboardView(context: Context) : View(context) {
    enum class ModState { OFF, ONESHOT, LOCKED }

    var listener: KeyboardActionListener? = null
    var heightScale = 1.0f; set(v) { field = v.coerceIn(0.5f, 2.0f); requestLayout() }

    init { isForceDarkAllowed = false }
    var dark = true; set(v) { field = v; invalidate() }
    var sound = false
    var vibration = true

    private var rows: List<Row> = emptyList()
    private val rects = ArrayList<Pair<RectF, Key>>()
    private val density = resources.displayMetrics.density
    private val modState = HashMap<String, ModState>()   // "Ctrl","Shift","Alt","Meta"
    private val heldMods = HashMap<Int, String>()         // pointerId → modifier group held down
    private val lastModTap = HashMap<String, Long>()
    private var capsLock = false

    private class Touch(val key: Key, var repeating: Boolean = false, var popup: List<String>? = null, var popupSel: Int = 0, var usedAsHold: Boolean = false)
    private val touches = HashMap<Int, Touch>()
    private var popupRects: List<RectF> = emptyList()
    private var popupOwner: Int = -1

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT }
    private val small = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.RIGHT }
    private val audio = context.getSystemService(AudioManager::class.java)

    fun setRows(r: List<Row>) { rows = r; requestLayout(); invalidate() }

    val currentMods: Int get() {
        var m = 0
        fun on(g: String) = (modState[g] ?: ModState.OFF) != ModState.OFF || heldMods.containsValue(g)
        if (on("Ctrl")) m = m or KeyMods.CTRL
        if (on("Shift")) m = m or KeyMods.SHIFT
        if (on("Alt")) m = m or KeyMods.ALT
        if (on("Meta")) m = m or KeyMods.META
        if (capsLock) m = m or KeyMods.CAPS
        return m
    }

    private fun group(code: String) = when (code) {
        "ControlLeft", "ControlRight" -> "Ctrl"; "ShiftLeft", "ShiftRight" -> "Shift"
        "AltLeft", "AltRight" -> "Alt"; "MetaLeft", "MetaRight" -> "Meta"; else -> null
    }

    // ------------------------------------------------------------------ layout & drawing

    private fun rowUnit() = 42f * density * heightScale

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = (rows.sumOf { (it.height * rowUnit()).toDouble() } + 8 * density).toInt()
        setMeasuredDimension(w, h)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        rects.clear()
        val gap = 3f * density
        var y = 4f * density
        val w = width.toFloat()
        for (row in rows) {
            val rh = row.height * rowUnit()
            val total = row.keys.sumOf { it.width.toDouble() }.toFloat()
            var x = gap
            val unit = (w - gap) / total
            for (k in row.keys) {
                val kw = k.width * unit
                rects.add(RectF(x, y + gap / 2, x + kw - gap, y + rh - gap / 2) to k)
                x += kw
            }
            y += rh
        }
    }

    private fun color(dark: Long, light: Long) = (if (this.dark) dark else light).toInt()

    override fun onDraw(c: Canvas) {
        c.drawColor(color(0xFF0F1320, 0xFFD5D9E2))
        val r = 6f * density
        val pressed = touches.values.map { it.key }.toSet()
        for ((rect, k) in rects) {
            if (k.code.isEmpty()) continue
            val g = group(k.code)
            val ms = g?.let { modState[it] } ?: ModState.OFF
            val active = (g != null && (ms != ModState.OFF || heldMods.containsValue(g))) || (k.code == "CapsLock" && capsLock)
            fill.color = when {
                k in pressed -> color(0xFF3B4A73, 0xFFAFC4F0)
                active && ms == ModState.LOCKED -> color(0xFFFFB040, 0xFFFFB040)
                active -> color(0xFF5CC8FF, 0xFF5CC8FF)
                k.kind == KeyKind.CHAR -> color(0xFF222A42, 0xFFFFFFFF)
                else -> color(0xFF1A2034, 0xFFC1C7D4)
            }
            c.drawRoundRect(rect, r, r, fill)
            val label = if (k.kind == KeyKind.CHAR && k.cased &&
                ((currentMods and KeyMods.SHIFT != 0) xor capsLock)) k.shiftOut else k.label
            text.color = if (active) 0xFF0F1320.toInt() else color(0xFFE8ECF8, 0xFF1B2133)
            text.textSize = (if (label.length > 3) 11f else if (k.kind == KeyKind.CHAR) 17f else 13f) * density * minOf(1f, heightScale + 0.15f)
            c.drawText(label, rect.centerX(), rect.centerY() - (text.descent() + text.ascent()) / 2, text)
            val sl = k.shiftLabel
            if (sl != null && !k.isLetter) {
                small.color = color(0xFF8E97B3, 0xFF5B6478); small.textSize = 9f * density
                c.drawText(sl, rect.right - 4 * density, rect.top + 11 * density, small)
            }
        }
        // long-press popup
        if (popupRects.isNotEmpty()) {
            val t = touches[popupOwner]
            val alts = t?.popup ?: emptyList()
            for ((i, pr) in popupRects.withIndex()) {
                fill.color = if (i == t?.popupSel) 0xFF5CC8FF.toInt() else color(0xFF2E3858, 0xFFFFFFFF)
                c.drawRoundRect(pr, r, r, fill)
                text.color = if (i == t?.popupSel) 0xFF0F1320.toInt() else color(0xFFE8ECF8, 0xFF1B2133)
                text.textSize = 18f * density
                c.drawText(alts.getOrElse(i) { "" }, pr.centerX(), pr.centerY() - (text.descent() + text.ascent()) / 2, text)
            }
        }
    }

    // ------------------------------------------------------------------ touch

    private fun keyAt(x: Float, y: Float): Key? = rects.firstOrNull { it.first.contains(x, y) }?.second
        ?: rects.minByOrNull { val r = it.first; Math.abs(r.centerX() - x) + Math.abs(r.centerY() - y) * 2 }?.second

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        val idx = e.actionIndex
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> down(e.getPointerId(idx), e.getX(idx), e.getY(idx))
            MotionEvent.ACTION_MOVE -> for (i in 0 until e.pointerCount) move(e.getPointerId(i), e.getX(i))
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> up(e.getPointerId(idx), cancelled = false)
            MotionEvent.ACTION_CANCEL -> touches.keys.toList().forEach { up(it, cancelled = true) }
        }
        invalidate()
        return true
    }

    private fun down(id: Int, x: Float, y: Float) {
        val k = keyAt(x, y) ?: return
        if (k.code.isEmpty()) return
        feedback()
        val t = Touch(k)
        touches[id] = t
        group(k.code)?.let { heldMods[id] = it }
        if (k.repeatable) {
            fire(k) // fires immediately, then repeats while held
            t.repeating = true
            postDelayed(RepeatRunnable(id, t), 400)
        } else if (k.alts.isNotEmpty() && k.kind == KeyKind.CHAR) {
            postDelayed({ if (touches[id] === t) showPopup(id) }, 420)
        }
    }

    private inner class RepeatRunnable(val id: Int, val t: Touch) : Runnable {
        override fun run() {
            if (touches[id] !== t) return // released (or a new touch reused the pointer id)
            fire(t.key); feedback(light = true)
            postDelayed(this, 50)
        }
    }

    private fun showPopup(id: Int) {
        val t = touches[id] ?: return
        val rect = rects.firstOrNull { it.second === t.key }?.first ?: return
        val alts = t.key.alts
        val w = rect.width().coerceAtLeast(36 * density)
        val h = rect.height()
        val left = (rect.centerX() - w * alts.size / 2).coerceIn(2f, maxOf(2f, width - w * alts.size - 2f))
        val top = if (rect.top - h - 4 * density >= 0) rect.top - h - 4 * density else rect.bottom + 4 * density
        popupRects = alts.indices.map { i -> RectF(left + i * w, top, left + (i + 1) * w - 2 * density, top + h) }
        t.popup = alts; t.popupSel = 0; popupOwner = id
        feedback()
        invalidate()
    }

    private fun move(id: Int, x: Float) {
        val t = touches[id] ?: return
        if (t.popup != null && popupOwner == id) {
            val i = popupRects.indexOfFirst { x >= it.left && x <= it.right }
            if (i >= 0 && i != t.popupSel) { t.popupSel = i; invalidate() }
        }
    }

    private fun up(id: Int, cancelled: Boolean) {
        val t = touches.remove(id) ?: return
        heldMods.remove(id)
        if (popupOwner == id) { popupOwner = -1; popupRects = emptyList() }
        if (cancelled) return
        val k = t.key
        val p = t.popup
        when {
            p != null -> {
                val alt = p[t.popupSel]
                val upper = k.cased && ((currentMods and KeyMods.SHIFT != 0) xor capsLock)
                emitText(k, if (upper) alt.uppercase() else alt)
            }
            t.repeating -> {}
            k.kind == KeyKind.MODIFIER && k.code == "CapsLock" -> capsLock = !capsLock
            k.kind == KeyKind.MODIFIER -> if (!t.usedAsHold) tapModifier(group(k.code)!!)
            else -> fire(k)
        }
    }

    /** Tap: off → one-shot; double-tap → locked; tap while on → off. Holding works as a real modifier. */
    private fun tapModifier(g: String) {
        val now = SystemClock.uptimeMillis()
        val st = modState[g] ?: ModState.OFF
        val double = now - (lastModTap[g] ?: 0) < 350
        modState[g] = when {
            st == ModState.OFF -> ModState.ONESHOT
            st == ModState.ONESHOT && double -> ModState.LOCKED
            else -> ModState.OFF
        }
        lastModTap[g] = now
    }

    private fun fire(k: Key) {
        if (k.kind == KeyKind.APP) { listener?.onAppKey(k.code); return }
        for (pid in heldMods.keys) touches[pid]?.usedAsHold = true
        val mods = currentMods
        if (k.kind == KeyKind.CHAR) {
            val shift = mods and KeyMods.SHIFT != 0
            val upper = if (k.cased) shift xor capsLock else shift
            emitText(k, if (upper) k.shiftOut else k.out, mods)
        } else listener?.onKey(k, mods, null)
        releaseOneShots()
    }

    private fun emitText(k: Key, s: String, mods: Int = currentMods) {
        // with Ctrl/Alt/Win the key is a shortcut: send the base letter, the receiver uses code + mods
        listener?.onKey(k, mods, s)
        releaseOneShots()
    }

    private fun releaseOneShots() {
        var changed = false
        for ((g, s) in modState.entries.toList()) if (s == ModState.ONESHOT && !heldMods.containsValue(g)) { modState[g] = ModState.OFF; changed = true }
        if (changed) invalidate()
    }

    private fun feedback(light: Boolean = false) {
        if (vibration) performHapticFeedback(if (light) HapticFeedbackConstants.CLOCK_TICK else HapticFeedbackConstants.KEYBOARD_TAP)
        if (sound && !light) audio?.playSoundEffect(AudioManager.FX_KEYPRESS_STANDARD, -1f)
    }
}
