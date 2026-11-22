package com.ukpro.android.ui

import android.app.Activity
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import com.ukpro.android.UkRuntime

/**
 * Manual clipboard sync (KNOWN_LIMITATIONS #2): Android 10+ only lets the focused app (or the default
 * IME) read the clipboard, so the notification action opens this invisible activity, which reads the
 * clip once it has window focus, sends it, and closes.
 */
class ClipboardSendActivity : Activity() {
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus) return
        val clip = getSystemService(ClipboardManager::class.java).primaryClip
        if (clip == null) Toast.makeText(this, "Clipboard is empty", Toast.LENGTH_SHORT).show()
        else if (UkRuntime.connectedHubs().isEmpty()) Toast.makeText(this, "No laptop connected", Toast.LENGTH_SHORT).show()
        else { UkRuntime.clipboard.onLocalClip(clip, manual = true); Toast.makeText(this, "Clipboard sent", Toast.LENGTH_SHORT).show() }
        finish()
    }
}

/** Share sheet target: "Send to PC (UK Pro)". */
class ShareReceiverActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        com.ukpro.android.service.ConnectionService.start(this)
        UkRuntime.start() // creates the connections now; queued sends go out as soon as the laptop is reached
        val uris = when (intent?.action) {
            Intent.ACTION_SEND -> listOfNotNull(stream(intent))
            Intent.ACTION_SEND_MULTIPLE -> streams(intent)
            else -> emptyList()
        }
        val text = intent?.getStringExtra(Intent.EXTRA_TEXT)
        when {
            uris.isNotEmpty() -> { UkRuntime.transfers.sendUris(uris); Toast.makeText(this, "Sending ${uris.size} file(s)…", Toast.LENGTH_SHORT).show() }
            text != null -> {
                val ok = UkRuntime.sendToActive(com.ukpro.core.MessageTypes.TEXT_COMMIT, linkedMapOf("text" to text))
                Toast.makeText(this, if (ok) "Typed on laptop" else "No laptop connected", Toast.LENGTH_SHORT).show()
            }
        }
        finish()
    }

    @Suppress("DEPRECATION")
    private fun stream(i: Intent): Uri? =
        if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java) else i.getParcelableExtra(Intent.EXTRA_STREAM)

    @Suppress("DEPRECATION")
    private fun streams(i: Intent): List<Uri> =
        (if (Build.VERSION.SDK_INT >= 33) i.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
         else i.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)) ?: emptyList()
}
