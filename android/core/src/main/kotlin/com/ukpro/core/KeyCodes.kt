package com.ukpro.core

/**
 * W3C KeyboardEvent.code ⇄ Android KeyEvent.KEYCODE_* values (numeric constants so this file has no
 * Android dependency; values are stable public API).
 */
object KeyCodes {
    val codeToAndroid: Map<String, Int>
    val androidToCode: Map<Int, String>

    // android.view.KeyEvent meta-state flags
    const val META_SHIFT_ON = 0x1; const val META_SHIFT_LEFT_ON = 0x40
    const val META_ALT_ON = 0x2; const val META_ALT_LEFT_ON = 0x10
    const val META_CTRL_ON = 0x1000; const val META_CTRL_LEFT_ON = 0x2000
    const val META_META_ON = 0x10000; const val META_META_LEFT_ON = 0x20000
    const val META_CAPS_LOCK_ON = 0x100000

    init {
        val m = LinkedHashMap<String, Int>()
        for (i in 0 until 26) m["Key" + ('A' + i)] = 29 + i          // KEYCODE_A = 29
        for (i in 0..9) { m["Digit$i"] = 7 + i; m["Numpad$i"] = 144 + i } // KEYCODE_0 = 7, NUMPAD_0 = 144
        for (i in 1..12) m["F$i"] = 130 + i                          // KEYCODE_F1 = 131
        val pairs = arrayOf(
            "Enter" to 66, "NumpadEnter" to 160, "Backspace" to 67, "Delete" to 112, "Tab" to 61, "Escape" to 111,
            "Space" to 62, "ArrowUp" to 19, "ArrowDown" to 20, "ArrowLeft" to 21, "ArrowRight" to 22,
            "Home" to 122, "End" to 123, "PageUp" to 92, "PageDown" to 93, "Insert" to 124,
            "ControlLeft" to 113, "ControlRight" to 114, "ShiftLeft" to 59, "ShiftRight" to 60,
            "AltLeft" to 57, "AltRight" to 58, "MetaLeft" to 117, "MetaRight" to 118, "CapsLock" to 115,
            "NumpadDivide" to 154, "NumpadMultiply" to 155, "NumpadSubtract" to 156, "NumpadAdd" to 157,
            "NumpadDecimal" to 158, "NumLock" to 143, "ScrollLock" to 116, "PrintScreen" to 120, "Pause" to 121,
            "Backquote" to 68, "Minus" to 69, "Equal" to 70, "BracketLeft" to 71, "BracketRight" to 72,
            "Backslash" to 73, "Semicolon" to 74, "Quote" to 75, "Slash" to 76, "Comma" to 55, "Period" to 56,
            "ContextMenu" to 82, "AudioVolumeUp" to 24, "AudioVolumeDown" to 25, "AudioVolumeMute" to 164,
            "MediaPlayPause" to 85, "MediaTrackNext" to 87, "MediaTrackPrevious" to 88, "MediaStop" to 86)
        for (p in pairs) m[p.first] = p.second
        codeToAndroid = m
        val r = LinkedHashMap<Int, String>()
        for ((k, v) in m) if (!r.containsKey(v)) r[v] = k
        androidToCode = r
    }

    fun isModifier(code: String) = code == "ShiftLeft" || code == "ShiftRight" || code == "ControlLeft" ||
        code == "ControlRight" || code == "AltLeft" || code == "AltRight" || code == "MetaLeft" || code == "MetaRight"

    /** Protocol mods bitmask → Android meta state for KeyEvent. */
    fun toAndroidMeta(mods: Int): Int {
        var meta = 0
        if (mods and KeyMods.CTRL != 0) meta = meta or META_CTRL_ON or META_CTRL_LEFT_ON
        if (mods and KeyMods.SHIFT != 0) meta = meta or META_SHIFT_ON or META_SHIFT_LEFT_ON
        if (mods and KeyMods.ALT != 0) meta = meta or META_ALT_ON or META_ALT_LEFT_ON
        if (mods and KeyMods.META != 0) meta = meta or META_META_ON or META_META_LEFT_ON
        if (mods and KeyMods.CAPS != 0) meta = meta or META_CAPS_LOCK_ON
        return meta
    }

    /**
     * Receiver rule (PROTOCOL §3.1): plain printable keys become text, everything else a key event.
     * Returns the text to insert, or null if the key must be replayed as a key event.
     */
    fun textFor(key: String, mods: Int): String? {
        if (mods and (KeyMods.CTRL or KeyMods.ALT or KeyMods.META) != 0) return null
        if (key.isEmpty()) return null
        return if (key.all { it >= ' ' && it != '\u007f' }) key else null
    }
}
