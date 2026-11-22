package com.ukpro.android.ime

import android.content.ClipboardManager
import android.graphics.Typeface
import android.inputmethodservice.InputMethodService
import android.os.SystemClock
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.LinearLayout
import android.widget.TextView
import com.ukpro.android.UkRuntime
import com.ukpro.android.data.OutputMode
import com.ukpro.android.keyboard.DesktopKeyboardView
import com.ukpro.android.keyboard.Key
import com.ukpro.android.keyboard.KeyKind
import com.ukpro.android.keyboard.KeyboardActionListener
import com.ukpro.android.keyboard.KeyboardLayouts
import com.ukpro.android.keyboard.Languages
import com.ukpro.android.sync.FocusInfo
import com.ukpro.android.sync.InputSync
import com.ukpro.android.sync.RemoteInputSink
import com.ukpro.core.Envelope
import com.ukpro.core.KeyCodes
import com.ukpro.core.KeyMods
import com.ukpro.core.MessageTypes
import com.ukpro.core.long
import com.ukpro.core.str

/**
 * UK Pro Desktop Keyboard (Android IME).
 *  • Local typing goes to the focused Android field through InputConnection and — when
 *    "Phone → PC" is on — to the active laptop (TEXT_COMMIT for text, KEY_DOWN/UP for keys/shortcuts).
 *  • Remote typing from the laptop arrives via [onRemoteInput] and is applied to the same InputConnection.
 *  • Password fields (EditorInfo.inputType) are never mirrored in either direction.
 *  • Text the IME commits on behalf of the laptop is never sent back (structural loop prevention).
 *  • As the default IME it may read the clipboard in the background (Android 10+), so it owns the
 *    clipboard listener.
 */
class UkImeService : InputMethodService(), KeyboardActionListener, RemoteInputSink {
    private lateinit var keyboard: DesktopKeyboardView
    private lateinit var statusView: TextView
    private lateinit var outputView: TextView
    private var showPanel = false
    private var password = false
    private val remoteDown = HashSet<String>()
    private val cm by lazy { getSystemService(ClipboardManager::class.java) }
    private val clipListener = ClipboardManager.OnPrimaryClipChangedListener { UkRuntime.clipboard.onLocalClip(cm.primaryClip) }
    private val statusTicker = object : Runnable {
        override fun run() { updateStatus(); statusView.postDelayed(this, 1500) }
    }

    override fun onCreate() {
        super.onCreate()
        UkRuntime.init(this)
        UkRuntime.start() // the selected IME is bound by the system, which keeps the process (and connections) alive
        cm.addPrimaryClipChangedListener(clipListener)
    }

    override fun onDestroy() {
        cm.removePrimaryClipChangedListener(clipListener)
        if (InputSync.sink === this) InputSync.sink = null
        super.onDestroy()
    }

    // ------------------------------------------------------------------ views

    override fun onCreateInputView(): View {
        val s = UkRuntime.settings.current
        val bg = if (s.darkTheme) 0xFF0F1320.toInt() else 0xFFD5D9E2.toInt()
        val fg = if (s.darkTheme) 0xFFE8ECF8.toInt() else 0xFF1B2133.toInt()
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(bg) }
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(6), dp(4), dp(6), 0) }
        fun chip(label: String, onClick: () -> Unit) = TextView(this).apply {
            text = label; setTextColor(fg); textSize = 12f; typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(10), dp(6), dp(10), dp(6)); setOnClickListener { onClick() }
            background = android.graphics.drawable.GradientDrawable().apply { cornerRadius = dp(10).toFloat(); setColor(if (s.darkTheme) 0xFF1A2034.toInt() else 0xFFC1C7D4.toInt()) }
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(6) }
        }
        statusView = chip("…") { cycleActiveLaptop() }.apply { layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(6) }; maxLines = 1 }
        outputView = chip(outputLabel()) { cycleOutput() }
        bar.addView(statusView)
        bar.addView(outputView)
        bar.addView(chip("📋→PC") { UkRuntime.clipboard.onLocalClip(cm.primaryClip, manual = true); UkRuntime.toast("Clipboard sent") })
        bar.addView(chip(if (s.paused) "▶" else "⏸") { UkRuntime.setPaused(!UkRuntime.settings.current.paused); updateStatus() })
        bar.addView(chip("🌐") { if (!switchToNextInputMethod(false)) requestHideSelf(0) })
        keyboard = DesktopKeyboardView(this).apply { listener = this@UkImeService }
        applySettings()
        root.addView(bar)
        root.addView(keyboard, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        statusView.post(statusTicker)
        return root
    }

    private fun applySettings() {
        val s = UkRuntime.settings.current
        keyboard.heightScale = s.keyboardHeight
        keyboard.dark = s.darkTheme; keyboard.sound = s.keySound; keyboard.vibration = s.vibration
        val lang = Languages.byId(s.language)
        keyboard.layoutDirection = View.LAYOUT_DIRECTION_LTR // physical layout stays left-to-right even for RTL scripts
        keyboard.setRows(if (showPanel) KeyboardLayouts.navPanel() else KeyboardLayouts.main(lang))
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun outputLabel() = when (UkRuntime.settings.current.outputMode) {
        OutputMode.BOTH -> "Phone+PC"; OutputMode.PC_ONLY -> "PC only"; OutputMode.PHONE_ONLY -> "Phone only"
    }

    private fun cycleOutput() {
        UkRuntime.settings.update { it.copy(outputMode = OutputMode.values()[(it.outputMode.ordinal + 1) % OutputMode.values().size]) }
        outputView.text = outputLabel()
    }

    private fun cycleActiveLaptop() {
        val hubs = UkRuntime.hubs.value.filter { it.connected }
        if (hubs.size < 2) { updateStatus(); return }
        val i = hubs.indexOfFirst { it.isActive }
        UkRuntime.setActive(hubs[(i + 1) % hubs.size].id)
        updateStatus()
    }

    private fun updateStatus() {
        if (!::statusView.isInitialized) return
        val s = UkRuntime.settings.current
        val active = UkRuntime.hubs.value.firstOrNull { it.isActive && it.connected }
        statusView.text = when {
            s.paused -> "⏸ Sync paused"
            password -> "🔒 Password field — not mirrored"
            active == null -> "○ No laptop"
            !s.androidToWindows -> "● ${active.name} (typing to PC off)"
            else -> "● ${active.name} · ${if (active.rttMs >= 0) "${active.rttMs} ms" else "…"}"
        }
    }

    // ------------------------------------------------------------------ editor lifecycle

    override fun onStartInputView(info: EditorInfo, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        password = isPassword(info)
        InputSync.sink = this
        InputSync.setFocus(FocusInfo(editable = true, password = password, app = info.packageName ?: ""))
        applySettings()
        updateStatus()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        InputSync.setFocus(FocusInfo(editable = false, password = false, app = ""))
    }

    override fun onFinishInput() {
        super.onFinishInput()
        if (InputSync.sink === this) InputSync.sink = null
        InputSync.setFocus(FocusInfo(editable = false, password = false, app = ""))
        remoteDown.clear()
    }

    private fun isPassword(info: EditorInfo): Boolean {
        val cls = info.inputType and InputType.TYPE_MASK_CLASS
        val v = info.inputType and InputType.TYPE_MASK_VARIATION
        return (cls == InputType.TYPE_CLASS_TEXT && (v == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                v == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD || v == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD)) ||
            (cls == InputType.TYPE_CLASS_NUMBER && v == InputType.TYPE_NUMBER_VARIATION_PASSWORD)
    }

    // ------------------------------------------------------------------ local keys

    override fun onAppKey(code: String) {
        when (code) {
            "LANG" -> UkRuntime.settings.update { st ->
                val order = Languages.all.map { it.id }.filter { it in st.languages || it == "en" }
                st.copy(language = order[(order.indexOf(st.language) + 1).mod(order.size)])
            }
            "PANEL" -> showPanel = !showPanel
        }
        keyboard.post { applySettings() }
    }

    override fun onKey(key: Key, mods: Int, text: String?) {
        InputSync.lastLocalInputAt = System.currentTimeMillis()
        val mode = UkRuntime.settings.current.outputMode
        val shortcut = mods and (KeyMods.CTRL or KeyMods.ALT or KeyMods.META) != 0
        val toPhone = mode != OutputMode.PC_ONLY
        val toPc = mode != OutputMode.PHONE_ONLY && !password
        // Win-key and Alt combos only make sense on Windows (Win+D, Alt+Tab, Alt+F4…)
        val pcOnlyCombo = mods and (KeyMods.META or KeyMods.ALT) != 0
        if (key.kind == KeyKind.CHAR && !shortcut && text != null) {
            if (toPhone) currentInputConnection?.commitText(text, 1)
            if (toPc) UkRuntime.sendInput(MessageTypes.TEXT_COMMIT, linkedMapOf("text" to text))
            return
        }
        if (toPhone && !pcOnlyCombo) sendLocalKey(key.code, mods)
        if (toPc) {
            val p = linkedMapOf<String, Any?>("code" to key.code, "key" to (text ?: ""), "mods" to (mods and KeyMods.CAPS.inv()).toLong(), "repeat" to false)
            UkRuntime.sendInput(MessageTypes.KEY_DOWN, p)
            UkRuntime.sendInput(MessageTypes.KEY_UP, LinkedHashMap(p))
        }
    }

    private fun sendLocalKey(code: String, mods: Int) {
        val ic = currentInputConnection ?: return
        val info = currentInputEditorInfo
        if (code == "Enter" && mods == 0 && info != null) {
            val action = info.imeOptions and EditorInfo.IME_MASK_ACTION
            val multiLine = info.inputType and InputType.TYPE_TEXT_FLAG_MULTI_LINE != 0
            if (!multiLine && action != EditorInfo.IME_ACTION_NONE && action != EditorInfo.IME_ACTION_UNSPECIFIED &&
                info.imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION == 0) { ic.performEditorAction(action); return }
        }
        val kc = KeyCodes.codeToAndroid[code] ?: return
        val meta = KeyCodes.toAndroidMeta(mods and KeyMods.CAPS.inv())
        val t = SystemClock.uptimeMillis()
        ic.sendKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_DOWN, kc, 0, meta))
        ic.sendKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_UP, kc, 0, meta))
    }

    // ------------------------------------------------------------------ remote keys (Windows → Android)

    override fun onRemoteInput(env: Envelope) {
        val ic = currentInputConnection ?: return
        if (password) return
        when (env.type) {
            MessageTypes.TEXT_COMMIT, MessageTypes.COMPOSITION_COMMIT -> ic.commitText(env.str("text"), 1)
            MessageTypes.KEY_DOWN -> {
                val code = env.str("code")
                if (KeyCodes.isModifier(code)) return // modifier state travels in `mods`
                val mods = env.long("mods").toInt()
                val text = KeyCodes.textFor(env.str("key"), mods)
                if (text != null) { ic.commitText(text, 1); return }
                val kc = KeyCodes.codeToAndroid[code] ?: return
                val t = SystemClock.uptimeMillis()
                ic.sendKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_DOWN, kc, if (env.payload["repeat"] == true) 1 else 0,
                    KeyCodes.toAndroidMeta(mods and KeyMods.CAPS.inv())))
                remoteDown.add(code)
            }
            MessageTypes.KEY_UP -> {
                val code = env.str("code")
                if (!remoteDown.remove(code)) return
                val kc = KeyCodes.codeToAndroid[code] ?: return
                val t = SystemClock.uptimeMillis()
                ic.sendKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_UP, kc, 0, KeyCodes.toAndroidMeta(env.long("mods").toInt() and KeyMods.CAPS.inv())))
            }
        }
    }
}
