package com.ukpro.android.sync

import com.ukpro.android.UkRuntime
import com.ukpro.android.transport.HubConnection
import com.ukpro.core.Envelope
import com.ukpro.core.MessageTypes

/** Implemented by the IME while it has an active InputConnection. */
interface RemoteInputSink {
    /** Called on the main thread. */
    fun onRemoteInput(env: Envelope)
}

data class FocusInfo(val editable: Boolean, val password: Boolean, val app: String)

/**
 * Routes Windows → Android input to the IME. Remote typing only works while UK Pro Keyboard is the
 * selected IME and an editable field is focused (KNOWN_LIMITATIONS #1); laptops are told the current
 * state with INPUT_FOCUS so they stop sending when nothing can receive it.
 */
object InputSync {
    @Volatile var sink: RemoteInputSink? = null
    @Volatile var focus = FocusInfo(editable = false, password = false, app = "")
        private set
    @Volatile var lastRemoteInputAt = 0L
        private set
    @Volatile var lastLocalInputAt = 0L

    fun deliver(conn: HubConnection, env: Envelope) {
        if (UkRuntime.settings.current.paused) return
        UkRuntime.main.post {
            val s = sink
            if (s == null || !focus.editable || focus.password) return@post
            val now = System.currentTimeMillis()
            if (now - lastLocalInputAt < 500 && now - lastWarn > 10_000) {
                lastWarn = now
                UkRuntime.toast("Phone and ${conn.name} keyboards are typing at the same time")
            }
            lastRemoteInputAt = now
            s.onRemoteInput(env)
        }
    }

    private var lastWarn = 0L

    fun setFocus(f: FocusInfo) {
        if (f == focus) return
        focus = f
        republishFocus()
    }

    fun republishFocus() {
        for (c in UkRuntime.connectedHubs()) sendFocusTo(c)
    }

    fun sendFocusTo(c: HubConnection) {
        val paused = UkRuntime.settings.current.paused
        c.session.send(MessageTypes.INPUT_FOCUS, linkedMapOf(
            "editable" to (focus.editable && !paused), "password" to focus.password, "app" to focus.app))
    }
}
