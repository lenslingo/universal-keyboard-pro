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

    private lateinit var root: LinearLayout
    private lateinit var adjustPanel: LinearLayout
    private lateinit var keyboardHolder: android.widget.FrameLayout
    private lateinit var bottomSpacer: View
    private var navInset = 0
    private var liveOffsetDp = -1 // while dragging, before it is saved
    /** Korean / Japanese / Vietnamese: text stays composing (underlined) until committed; only commits go to the PC. */
    private var composer: com.ukpro.core.Composer? = null
    private var composerLang: String? = null

    override fun onCreateInputView(): View {
        val s = UkRuntime.settings.current
        val bg = if (s.darkTheme) 0xFF0F1320.toInt() else 0xFFD5D9E2.toInt()
        val fg = if (s.darkTheme) 0xFFE8ECF8.toInt() else 0xFF1B2133.toInt()
        val chipBg = if (s.darkTheme) 0xFF1A2034.toInt() else 0xFFC1C7D4.toInt()
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(bg)
            isForceDarkAllowed = false // MIUI/One UI must not invert the keyboard colours
        }
        fun chip(label: String, onClick: () -> Unit) = TextView(this).apply {
            text = label; setTextColor(fg); textSize = 12f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
            setPadding(dp(10), dp(6), dp(10), dp(6)); setOnClickListener { onClick() }
            background = android.graphics.drawable.GradientDrawable().apply { cornerRadius = dp(10).toFloat(); setColor(chipBg) }
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(5) }
        }

        // --- adjust panel (hidden until ⇕ is tapped): move up/down, height, width, alignment, drag handle
        adjustPanel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE; setPadding(dp(6), dp(4), dp(6), dp(2)) }
        val handle = TextView(this).apply {
            text = "≡  drag up / down to move the keyboard  ≡"; setTextColor(fg); textSize = 12f; gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, dp(8))
            background = android.graphics.drawable.GradientDrawable().apply { cornerRadius = dp(10).toFloat(); setColor(0xFF5CC8FF.toInt() and 0x55FFFFFF) }
        }
        var dragStartY = 0f; var dragStartOffset = 0
        handle.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> { dragStartY = e.rawY; dragStartOffset = currentOffsetDp() }
                android.view.MotionEvent.ACTION_MOVE -> {
                    liveOffsetDp = (dragStartOffset + ((dragStartY - e.rawY) / resources.displayMetrics.density).toInt()).coerceIn(0, maxOffsetDp())
                    applyGeometry()
                }
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                    val v = liveOffsetDp; liveOffsetDp = -1
                    if (v >= 0) UkRuntime.settings.update { it.copy(keyboardOffsetDp = v) }
                }
            }
            true
        }
        fun row(vararg views: View) = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; setPadding(0, dp(3), 0, dp(3)); views.forEach { addView(it) }
        }
        fun adj(change: (com.ukpro.android.data.AppSettings) -> com.ukpro.android.data.AppSettings) {
            UkRuntime.settings.update(change); keyboard.post { applySettings() }
        }
        adjustPanel.addView(handle)
        adjustPanel.addView(row(
            chip("▲ Up") { adj { it.copy(keyboardOffsetDp = (it.keyboardOffsetDp + 24).coerceAtMost(maxOffsetDp())) } },
            chip("▼ Down") { adj { it.copy(keyboardOffsetDp = (it.keyboardOffsetDp - 24).coerceAtLeast(0)) } },
            chip("A− Shorter") { adj { it.copy(keyboardHeight = (it.keyboardHeight - 0.1f).coerceAtLeast(0.5f)) } },
            chip("A+ Taller") { adj { it.copy(keyboardHeight = (it.keyboardHeight + 0.1f).coerceAtMost(2.0f)) } }))
        adjustPanel.addView(row(
            chip("◂▸ Narrower") { adj { it.copy(keyboardWidth = (it.keyboardWidth - 0.05f).coerceAtLeast(0.5f)) } },
            chip("◂  ▸ Wider") { adj { it.copy(keyboardWidth = (it.keyboardWidth + 0.05f).coerceAtMost(1.0f)) } },
            chip("⇤") { adj { it.copy(keyboardAlign = "left") } },
            chip("⇔") { adj { it.copy(keyboardAlign = "center") } },
            chip("⇥") { adj { it.copy(keyboardAlign = "right") } }))
        adjustPanel.addView(row(
            chip("Reset") { adj { it.copy(keyboardOffsetDp = 0, keyboardHeight = 1.0f, keyboardWidth = 1.0f, keyboardAlign = "center") } },
            chip("✓ Done") { adjustPanel.visibility = View.GONE }))

        // --- toolbar
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(6), dp(4), dp(6), 0) }
        statusView = chip("…") { cycleActiveLaptop() }.apply { layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(5) }; maxLines = 1 }
        outputView = chip(outputLabel()) { cycleOutput() }
        bar.addView(statusView)
        bar.addView(outputView)
        bar.addView(chip("⇕") { adjustPanel.visibility = if (adjustPanel.visibility == View.VISIBLE) View.GONE else View.VISIBLE })
        bar.addView(chip("📋→PC") { UkRuntime.clipboard.onLocalClip(cm.primaryClip, manual = true); UkRuntime.toast("Clipboard sent") })
        bar.addView(chip(if (s.paused) "▶" else "⏸") { UkRuntime.setPaused(!UkRuntime.settings.current.paused); updateStatus() })
        bar.addView(chip("🌐") { if (!switchToNextInputMethod(false)) requestHideSelf(0) })

        keyboard = DesktopKeyboardView(this).apply { listener = this@UkImeService }
        keyboardHolder = android.widget.FrameLayout(this)
        keyboardHolder.addView(keyboard, android.widget.FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        bottomSpacer = View(this)

        root.addView(adjustPanel)
        root.addView(bar)
        root.addView(keyboardHolder, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(bottomSpacer, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0))

        // Android 15 draws apps edge-to-edge: keep the bottom row above the navigation / gesture bar
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            navInset = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.navigationBars()).bottom
            applyGeometry()
            insets
        }
        applySettings()
        statusView.post(statusTicker)
        return root
    }

    private fun currentOffsetDp() = if (liveOffsetDp >= 0) liveOffsetDp else UkRuntime.settings.current.keyboardOffsetDp
    private fun maxOffsetDp() = (resources.displayMetrics.heightPixels / resources.displayMetrics.density / 2).toInt()

    /** Position and size: lift above the bottom, width and one-handed alignment. */
    private fun applyGeometry() {
        if (!::root.isInitialized) return
        val s = UkRuntime.settings.current
        val lp = bottomSpacer.layoutParams
        lp.height = navInset + dp(currentOffsetDp())
        bottomSpacer.layoutParams = lp
        val screenW = resources.displayMetrics.widthPixels
        val kbLp = keyboard.layoutParams as android.widget.FrameLayout.LayoutParams
        kbLp.width = if (s.keyboardWidth >= 0.999f) ViewGroup.LayoutParams.MATCH_PARENT else (screenW * s.keyboardWidth).toInt()
        kbLp.gravity = when (s.keyboardAlign) { "left" -> Gravity.START; "right" -> Gravity.END; else -> Gravity.CENTER_HORIZONTAL }
        keyboard.layoutParams = kbLp
    }

    private fun applySettings() {
        val s = UkRuntime.settings.current
        keyboard.heightScale = s.keyboardHeight
        keyboard.dark = s.darkTheme; keyboard.sound = s.keySound; keyboard.vibration = s.vibration
        val lang = Languages.byId(s.language)
        if (lang.id != composerLang) {
            commitComposition()
            composer = lang.composer?.invoke()
            composerLang = lang.id
        }
        keyboard.layoutDirection = View.LAYOUT_DIRECTION_LTR // physical layout stays left-to-right even for RTL scripts
        keyboard.setRows(if (showPanel) KeyboardLayouts.navPanel() else KeyboardLayouts.main(lang))
        applyGeometry()
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
        commitComposition()
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
            val c = composer
            if (c != null) {
                val committed = c.input(text)
                if (committed.isNotEmpty()) emitCommit(committed, toPhone, toPc)
                showComposing(toPhone)
                return
            }
            if (toPhone) currentInputConnection?.commitText(text, 1)
            if (toPc) UkRuntime.sendInput(MessageTypes.TEXT_COMMIT, linkedMapOf("text" to text))
            return
        }
        if (key.code == "Backspace" && !shortcut && composer?.backspace() == true) { showComposing(toPhone); return }
        commitComposition()
        if (toPhone && !pcOnlyCombo) sendLocalKey(key.code, mods)
        if (toPc) {
            val p = linkedMapOf<String, Any?>("code" to key.code, "key" to (text ?: ""), "mods" to (mods and KeyMods.CAPS.inv()).toLong(), "repeat" to false)
            UkRuntime.sendInput(MessageTypes.KEY_DOWN, p)
            UkRuntime.sendInput(MessageTypes.KEY_UP, LinkedHashMap(p))
        }
    }

    private fun emitCommit(text: String, toPhone: Boolean, toPc: Boolean) {
        if (toPhone) currentInputConnection?.commitText(text, 1)
        if (toPc) UkRuntime.sendInput(MessageTypes.COMPOSITION_COMMIT, linkedMapOf("text" to text, "composer" to (composerLang ?: "")))
    }

    private fun showComposing(toPhone: Boolean) {
        val comp = composer?.composing ?: ""
        if (toPhone) currentInputConnection?.setComposingText(comp, 1)
        else if (::statusView.isInitialized) statusView.text = if (comp.isEmpty()) statusView.text else "✎ $comp"
    }

    /** Commits whatever is still composing (before Enter, arrows, shortcuts, language change or leaving the field). */
    private fun commitComposition() {
        val c = composer ?: return
        val t = c.flush()
        if (t.isEmpty()) return
        val mode = UkRuntime.settings.current.outputMode
        emitCommit(t, mode != OutputMode.PC_ONLY, mode != OutputMode.PHONE_ONLY && !password)
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
