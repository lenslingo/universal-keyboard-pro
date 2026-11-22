package com.ukpro.android.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.ukpro.android.UkRuntime
import com.ukpro.android.data.ClipEntity
import com.ukpro.android.data.ConnectionMode
import com.ukpro.android.keyboard.DesktopKeyboardView
import com.ukpro.android.keyboard.Key
import com.ukpro.android.keyboard.KeyboardActionListener
import com.ukpro.android.keyboard.KeyboardLayouts
import com.ukpro.android.service.ConnectionService
import com.ukpro.core.MessageTypes
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private fun time(ts: Long): String = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(ts))

// ------------------------------------------------------------------ Keyboard

@Composable
fun KeyboardScreen() {
    val s by UkRuntime.settings.state.collectAsState()
    var typed by remember { mutableStateOf("") }
    var langQuery by remember { mutableStateOf("") }
    LazyColumn(contentPadding = PaddingValues(vertical = 8.dp)) {
        item {
            UkCard("Appearance") {
                Text("Tip: tap ⇕ on the keyboard's toolbar to move and resize it while you type.", style = MaterialTheme.typography.bodySmall)
                Text("Key height: ${(s.keyboardHeight * 100).toInt()}%")
                Slider(value = s.keyboardHeight, valueRange = 0.5f..2.0f, onValueChange = { v -> UkRuntime.settings.update { it.copy(keyboardHeight = v) } })
                Text("Lift above the bottom edge: ${s.keyboardOffsetDp} dp")
                Slider(value = s.keyboardOffsetDp.toFloat(), valueRange = 0f..480f, onValueChange = { v -> UkRuntime.settings.update { it.copy(keyboardOffsetDp = v.toInt()) } })
                Text("Width: ${(s.keyboardWidth * 100).toInt()}% of the screen")
                Slider(value = s.keyboardWidth, valueRange = 0.5f..1.0f, onValueChange = { v -> UkRuntime.settings.update { it.copy(keyboardWidth = v) } })
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for ((id, label) in listOf("left" to "⇤ Left", "center" to "⇔ Center", "right" to "⇥ Right"))
                        FilterChip(selected = s.keyboardAlign == id, onClick = { UkRuntime.settings.update { it.copy(keyboardAlign = id) } }, label = { Text(label) })
                }
                ToggleRow("Dark theme", s.darkTheme) { v -> UkRuntime.settings.update { it.copy(darkTheme = v) } }
                ToggleRow("Key sound", s.keySound) { v -> UkRuntime.settings.update { it.copy(keySound = v) } }
                ToggleRow("Vibration", s.vibration) { v -> UkRuntime.settings.update { it.copy(vibration = v) } }
                ToggleRow("Clipboard strip above the keyboard (tap to paste)", s.clipStrip) { v -> UkRuntime.settings.update { it.copy(clipStrip = v) } }
            }
        }
        item {
            UkCard("Languages — ${com.ukpro.android.keyboard.Languages.all.size} layouts (🌐 key cycles through the ticked ones)") {
                OutlinedTextField(langQuery, { langQuery = it }, label = { Text("Search language") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                var lastGroup = ""
                for (l in com.ukpro.android.keyboard.Languages.all.filter { langQuery.isBlank() || it.name.contains(langQuery, ignoreCase = true) || it.id.startsWith(langQuery.trim(), ignoreCase = true) }) {
                    if (l.group != lastGroup && l.group.isNotEmpty()) { lastGroup = l.group; Text(l.group, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 8.dp)) }
                    ToggleRow("${l.name}${if (l.id == s.language) "  ← current" else ""}", l.id == "en" || l.id in s.languages, enabled = l.id != "en") { on ->
                        UkRuntime.settings.update { st -> st.copy(languages = if (on) st.languages + l.id else st.languages - l.id,
                            language = if (on) l.id else if (st.language == l.id) "en" else st.language) }
                    }
                }
                Text("Chinese (Pinyin/Zhuyin/Cangjie), Japanese kanji and any other language: use the Universal text screen with any keyboard installed on your phone (e.g. Gboard), or copy & paste — clipboard sync carries any Unicode text.",
                    style = MaterialTheme.typography.bodySmall)
            }
        }
        item {
            UkCard("How it works") {
                Text("• Tap Ctrl/Shift/Alt/⊞ once = next key only; double-tap = locked; or hold it with one finger.\n" +
                    "• Long-press a key for alternatives (বাংলা: া → আ, ি → ী, ু → ূ, দ → ড ঢ, ত → ট ঠ …). " +
                    "Juktakkhor: consonant + q (্) + consonant, e.g. ক q ষ = ক্ষ.\n" +
                    "• Nav opens Ins/Home/End/PgUp/PgDn and the numeric keypad.\n" +
                    "• Toolbar: active laptop · Phone+PC / PC only / Phone only · send clipboard · pause.",
                    style = MaterialTheme.typography.bodySmall)
            }
        }
        item {
            UkCard("Preview (try it)") {
                Text(if (typed.isEmpty()) "Type on the preview below…" else typed, maxLines = 3, overflow = TextOverflow.Ellipsis)
                AndroidView(factory = { ctx ->
                    DesktopKeyboardView(ctx).apply {
                        listener = object : KeyboardActionListener {
                            override fun onKey(key: Key, mods: Int, text: String?) {
                                typed = when {
                                    text != null && mods and 0x0D == 0 -> (typed + text).takeLast(200)
                                    key.code == "Backspace" -> typed.dropLast(1)
                                    else -> "$typed[${key.label}]".takeLast(200)
                                }
                            }
                            override fun onAppKey(code: String) {
                                if (code == "LANG") UkRuntime.settings.update { st ->
                    val order = com.ukpro.android.keyboard.Languages.all.map { it.id }.filter { it in st.languages || it == "en" }
                    st.copy(language = order[(order.indexOf(st.language) + 1).mod(order.size)])
                }
                                if (code == "PANEL") UkRuntime.settings.update { it.copy(showNavPanel = !it.showNavPanel) }
                            }
                        }
                    }
                }, update = { v ->
                    v.heightScale = s.keyboardHeight.coerceAtMost(1.2f); v.dark = s.darkTheme; v.vibration = s.vibration; v.sound = s.keySound
                    v.setRows(if (s.showNavPanel) KeyboardLayouts.navPanel() else KeyboardLayouts.main(com.ukpro.android.keyboard.Languages.byId(s.language)))
                }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
            }
        }
    }
}

// ------------------------------------------------------------------ Remote Control Panel

@Composable
fun RemoteScreen() {
    val hubs by UkRuntime.hubs.collectAsState()
    val active = hubs.firstOrNull { it.isActive && it.connected }
    fun action(a: String, repeat: Int = 1) { UkRuntime.sendToActive(MessageTypes.REMOTE_ACTION, linkedMapOf("action" to a, "repeat" to repeat.toLong())) }
    fun media(a: String) { UkRuntime.sendToActive(MessageTypes.MEDIA_CONTROL, linkedMapOf("action" to a)) }
    var volume by remember(active?.volume) { mutableStateOf((active?.volume ?: 50).toFloat()) }

    LazyColumn(contentPadding = PaddingValues(vertical = 8.dp)) {
        item {
            UkCard(if (active != null) "Controlling ${active.name}" else "No laptop connected") {
                if (active?.foregroundApp != null) Text("Foreground: ${active.foregroundApp}", style = MaterialTheme.typography.bodySmall)
            }
        }
        item {
            UkCard("Windows") {
                ButtonGrid(listOf("Alt+Tab" to { action("ALT_TAB") }, "Alt+Tab ×2" to { action("ALT_TAB", 2) },
                    "Alt+Shift+Tab" to { action("ALT_SHIFT_TAB") }, "Win+Tab" to { action("WIN_TAB") },
                    "Win+D (desktop)" to { action("WIN_D") }, "Win+E (explorer)" to { action("WIN_E") },
                    "Task Manager" to { action("TASK_MANAGER") }, "Alt+F4" to { action("ALT_F4") },
                    "Print Screen" to { action("PRINT_SCREEN") }, "Lock (Win+L)" to { action("WIN_L") }))
            }
        }
        item {
            UkCard("Volume ${volume.toInt()}%${if (active?.muted == true) " (muted)" else ""}") {
                Slider(value = volume, valueRange = 0f..100f, onValueChange = { volume = it },
                    onValueChangeFinished = { UkRuntime.sendToActive(MessageTypes.VOLUME_SET, linkedMapOf("level" to volume.toLong())) })
                ButtonGrid(listOf("🔉 −" to { media("VOL_DOWN") }, "🔇 Mute" to { media("MUTE") }, "🔊 +" to { media("VOL_UP") }))
            }
        }
        item {
            UkCard("Media") {
                ButtonGrid(listOf("⏮ Prev" to { media("PREV") }, "⏯ Play/Pause" to { media("PLAY_PAUSE") },
                    "⏭ Next" to { media("NEXT") }, "⏹ Stop" to { media("STOP") }))
            }
        }
    }
}

@Composable
private fun ButtonGrid(buttons: List<Pair<String, () -> Unit>>, columns: Int = 3) {
    for (row in buttons.chunked(columns)) {
        Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for ((label, onClick) in row) OutlinedButton(onClick = { onClick() }, modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 10.dp)) { Text(label, maxLines = 1) }
            repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
        }
    }
}

// ------------------------------------------------------------------ Clipboard (history on both sides)

@Composable
fun ClipboardScreen() {
    val ctx = LocalContext.current
    var tab by remember { mutableStateOf(0) }
    var filter by remember { mutableStateOf("all") }
    val clips by UkRuntime.db.clips().all().collectAsState(initial = emptyList())
    val laptop by UkRuntime.laptopHistory.collectAsState()
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    Column {
        TabRow(selectedTabIndex = tab) {
            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("This phone") })
            Tab(selected = tab == 1, onClick = {
                tab = 1
                UkRuntime.sendToActive(MessageTypes.CLIPBOARD_HISTORY_REQUEST, linkedMapOf("limit" to 100L))
            }, text = { Text("Laptop") })
        }
        if (tab == 0) {
            Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                for (f in listOf("all", "text", "image", "files")) FilterChip(selected = filter == f, onClick = { filter = f }, label = { Text(f) })
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { scope.launch { UkRuntime.db.clips().clearUnpinned() } }) { Text("Clear") }
            }
            LazyColumn {
                items(clips.filter { filter == "all" || it.kind == filter }, key = { it.clipId }) { e -> ClipRow(e, ctx) }
            }
        } else {
            LazyColumn {
                items(laptop, key = { it.clipId }) { c ->
                    UkCard("${c.kind} · ${time(c.ts)}") {
                        Text(c.preview, maxLines = 3, overflow = TextOverflow.Ellipsis)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { UkRuntime.sendToActive(MessageTypes.CLIPBOARD_HISTORY_APPLY, linkedMapOf("clipId" to c.clipId, "paste" to false)) }) { Text("Copy on PC") }
                            Button(onClick = { UkRuntime.sendToActive(MessageTypes.CLIPBOARD_HISTORY_APPLY, linkedMapOf("clipId" to c.clipId, "paste" to true)) }) { Text("Paste on PC") }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ClipRow(e: ClipEntity, ctx: android.content.Context) {
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    UkCard("${if (e.pinned) "📌 " else ""}${e.kind} · ${e.originName} · ${time(e.ts)}") {
        Text(e.preview, maxLines = 4, overflow = TextOverflow.Ellipsis)
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(onClick = { UkRuntime.clipboard.restore(e); UkRuntime.toast("Copied") }) { Text("Copy") }
            TextButton(onClick = { UkRuntime.clipboard.sendToLaptop(e) }) { Text("To PC") }
            TextButton(onClick = {
                val send = Intent(Intent.ACTION_SEND)
                if (e.kind == "text") send.setType("text/plain").putExtra(Intent.EXTRA_TEXT, e.text)
                else e.uris?.lineSequence()?.firstOrNull()?.let { send.setType("*/*").putExtra(Intent.EXTRA_STREAM, Uri.parse(it)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                ctx.startActivity(Intent.createChooser(send, "Share"))
            }) { Text("Share") }
            TextButton(onClick = { scope.launch { UkRuntime.db.clips().pin(e.clipId, !e.pinned) } }) { Text(if (e.pinned) "Unpin" else "Pin") }
            TextButton(onClick = { scope.launch { UkRuntime.db.clips().delete(e.clipId) } }) { Text("Delete") }
        }
    }
}

// ------------------------------------------------------------------ Files

@Composable
fun FilesScreen() {
    val active by UkRuntime.transferList.collectAsState()
    val history by UkRuntime.db.transfers().all().collectAsState(initial = emptyList())
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) UkRuntime.transfers.sendUris(uris)
    }
    LazyColumn(contentPadding = PaddingValues(vertical = 8.dp)) {
        item {
            UkCard("Send to laptop") {
                Text("PDF, Office, images, video, ZIP — no size limit on Wi-Fi (only free storage space limits it). " +
                    "Files are streamed in chunks, interrupted transfers resume where they stopped, and every file is SHA-256 verified. " +
                    "Received files go straight to Download/UK Pro.", style = MaterialTheme.typography.bodySmall)
                Button(onClick = { pick.launch(arrayOf("*/*")) }, modifier = Modifier.padding(top = 8.dp)) { Text("Choose files…") }
            }
        }
        items(active, key = { "a" + it.transferId }) { t ->
            val conn = UkRuntime.activeConnection()
            UkCard("${if (t.incoming) "Receiving" else "Sending"} · ${t.state}") {
                Text("${t.name.take(48)} · ${"%.1f".format(t.size / 1048576.0)} MB")
                LinearProgressIndicator(progress = { t.fraction.toFloat() }, modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp))
                Text("%.1f MB/s".format(t.bytesPerSecond / 1048576))
                if (!t.incoming) Row {
                    TextButton(onClick = { conn?.transfers?.pause(t.transferId, t.state != com.ukpro.core.TransferProgress.State.PAUSED) }) { Text("Pause/Resume") }
                    TextButton(onClick = { conn?.transfers?.cancel(t.transferId) }) { Text("Cancel") }
                }
            }
        }
        item { Text("History", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(16.dp)) }
        items(history, key = { "h" + it.transferId }) { h ->
            Text("${if (h.incoming) "↓" else "↑"} ${h.name} · ${"%.1f".format(h.size / 1048576.0)} MB · ${h.state} · ${h.peerName} · ${time(h.ts)}",
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp), style = MaterialTheme.typography.bodySmall)
        }
    }
}

// ------------------------------------------------------------------ Devices & pairing

@Composable
fun DevicesScreen() {
    val ctx = LocalContext.current
    val hubs by UkRuntime.hubs.collectAsState()
    val found by UkRuntime.discovered.collectAsState()
    var host by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("47821") }
    var code by remember { mutableStateOf("") }
    var verification by remember { mutableStateOf<String?>(null) }
    var forget by remember { mutableStateOf<String?>(null) }
    val scan = rememberLauncherForActivityResult(ScanContract()) { r ->
        r.contents?.let { uri ->
            val err = UkRuntime.pairWithQr(uri)
            if (err != null) UkRuntime.toast(err) else { ConnectionService.start(ctx); UkRuntime.toast("Pairing… confirm on the laptop") }
        }
    }
    LaunchedEffect(Unit) { UkRuntime.start() }
    LaunchedEffect(Unit) {
        while (true) { verification = UkRuntime.currentVerification(); delay(500) }
    }

    LazyColumn(contentPadding = PaddingValues(vertical = 8.dp)) {
        item {
            UkCard("Pair with QR code (recommended)") {
                Text("On the laptop: UK Pro tray icon → Pair a new phone.", style = MaterialTheme.typography.bodySmall)
                Button(onClick = { scan.launch(ScanOptions().setPrompt("Scan the UK Pro code on your laptop").setBeepEnabled(false).setOrientationLocked(false)) },
                    modifier = Modifier.padding(top = 8.dp)) { Text("Scan QR code") }
            }
        }
        item {
            UkCard("Pair with code (no camera)") {
                if (found.isNotEmpty()) {
                    Text("Found on this network:", style = MaterialTheme.typography.bodySmall)
                    for (d in found) TextButton(onClick = { host = d.host; port = d.port.toString() }) { Text("${d.name}  (${d.host})") }
                }
                OutlinedTextField(host, { host = it }, label = { Text("Laptop IP address") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(port, { port = it.filter(Char::isDigit) }, label = { Text("Port") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
                OutlinedTextField(code, { code = it }, label = { Text("One-time code (e.g. 7K3M-9QXZ)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Button(onClick = { UkRuntime.pairManual(host, port.toIntOrNull() ?: 47821, code); ConnectionService.start(ctx) },
                    enabled = host.isNotBlank() && code.length >= 8, modifier = Modifier.padding(top = 8.dp)) { Text("Pair") }
                verification?.let { Text("Verification number: $it — it must match the laptop's dialog.", style = MaterialTheme.typography.titleMedium) }
            }
        }
        item { Text("Paired laptops", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(16.dp)) }
        items(hubs, key = { it.id }) { h ->
            UkCard((if (h.connected) "● " else "○ ") + h.name + if (h.isActive) "  ·  keyboard target" else "") {
                Text(if (h.connected) "${h.transport.uppercase()} · ${h.rttMs} ms" else (h.error ?: "offline"), style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!h.isActive) OutlinedButton(onClick = { UkRuntime.setActive(h.id) }) { Text("Use for typing") }
                    TextButton(onClick = { forget = h.id }) { Text("Forget") }
                }
            }
        }
    }
    forget?.let { id ->
        AlertDialog(onDismissRequest = { forget = null }, title = { Text("Forget laptop?") },
            text = { Text("You will need to pair again.") },
            confirmButton = { TextButton(onClick = { UkRuntime.forget(id); forget = null }) { Text("Forget") } },
            dismissButton = { TextButton(onClick = { forget = null }) { Text("Cancel") } })
    }
}

// ------------------------------------------------------------------ History

@Composable
fun HistoryScreen() {
    val clips by UkRuntime.db.clips().all().collectAsState(initial = emptyList())
    val transfers by UkRuntime.db.transfers().all().collectAsState(initial = emptyList())
    val events = (clips.map { it.ts to "📋 ${it.kind} from ${it.originName}: ${it.preview.take(60)}" } +
        transfers.map { it.ts to "${if (it.incoming) "⬇" else "⬆"} ${it.name} (${it.state}) — ${it.peerName}" }).sortedByDescending { it.first }
    LazyColumn(contentPadding = PaddingValues(16.dp)) {
        if (events.isEmpty()) item { Text("Nothing yet. Copied items and transfers appear here. Keystrokes are never recorded.") }
        items(events.size) { i ->
            val (ts, text) = events[i]
            Column(Modifier.padding(vertical = 6.dp)) {
                Text(time(ts), style = MaterialTheme.typography.labelSmall)
                Text(text, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

// ------------------------------------------------------------------ Settings

@Composable
fun SettingsScreen() {
    val ctx = LocalContext.current
    val s by UkRuntime.settings.state.collectAsState()
    LazyColumn(contentPadding = PaddingValues(vertical = 8.dp)) {
        item {
            UkCard("Connection mode") {
                for ((m, label) in listOf(ConnectionMode.AUTO to "Automatic (Wi-Fi first)", ConnectionMode.WIFI to "Wi-Fi preferred",
                        ConnectionMode.BLUETOOTH to "Bluetooth preferred — not available yet (Phase 8)")) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = s.connectionMode == m, enabled = m != ConnectionMode.BLUETOOTH,
                            onClick = { UkRuntime.settings.update { it.copy(connectionMode = m) } })
                        Text(label)
                    }
                }
            }
        }
        item {
            UkCard("Clipboard") {
                ToggleRow("Sync clipboard", s.clipboardSync) { v -> UkRuntime.settings.update { it.copy(clipboardSync = v) } }
                ToggleRow("Include images", s.clipboardImages) { v -> UkRuntime.settings.update { it.copy(clipboardImages = v) } }
                ToggleRow("Include files", s.clipboardFiles) { v -> UkRuntime.settings.update { it.copy(clipboardFiles = v) } }
                ToggleRow("Keep history on this phone", s.historyEnabled) { v -> UkRuntime.settings.update { it.copy(historyEnabled = v) } }
                Text("History size: ${s.historyMaxItems} items")
                Slider(value = s.historyMaxItems.toFloat(), valueRange = 20f..1000f,
                    onValueChange = { v -> UkRuntime.settings.update { it.copy(historyMaxItems = v.toInt()) } })
            }
        }
        item {
            UkCard("Privacy") {
                Text("• Password fields are never mirrored (both directions).\n• Keystrokes are not logged or stored.\n" +
                    "• Sensitive clips (marked by password managers) are not synced.\n• No cloud: everything stays on your local network.\n" +
                    "• Pairing secrets are encrypted with the Android Keystore.", style = MaterialTheme.typography.bodySmall)
            }
        }
        item {
            UkCard("Service") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { ConnectionService.start(ctx) }) { Text("Start") }
                    OutlinedButton(onClick = { ConnectionService.stop(ctx) }) { Text("Stop") }
                }
                Text("This phone: ${UkRuntime.secrets.deviceName} (${UkRuntime.secrets.deviceId})", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

// ------------------------------------------------------------------ Universal text (any language, any phone keyboard)

/** Splits text into user-perceived characters (grapheme clusters) so edits never cut a conjunct or emoji. */
private fun graphemes(s: String): List<String> {
    val it = java.text.BreakIterator.getCharacterInstance()
    it.setText(s)
    val out = ArrayList<String>()
    var start = it.first()
    var end = it.next()
    while (end != java.text.BreakIterator.DONE) { out.add(s.substring(start, end)); start = end; end = it.next() }
    return out
}

/**
 * Type in ANY language with ANY keyboard installed on the phone (Gboard Chinese/Japanese/Korean,
 * Avro, SwiftKey…). Only committed text is sent: while the phone keyboard is still composing
 * (pinyin, kana, Hangul jamo, transliteration) nothing leaves the phone (PART 4: no unfinished composition).
 */
@Composable
fun UniversalTextScreen() {
    var value by remember { mutableStateOf(androidx.compose.ui.text.input.TextFieldValue("")) }
    var sent by remember { mutableStateOf("") }
    var live by remember { mutableStateOf(false) }
    val hubs by UkRuntime.hubs.collectAsState()
    val target = hubs.firstOrNull { it.isActive && it.connected }

    fun mirror(now: String) {
        val a = graphemes(sent); val b = graphemes(now)
        var common = 0
        while (common < a.size && common < b.size && a[common] == b[common]) common++
        val removed = a.drop(common).joinToString("")
        val added = b.drop(common).joinToString("")
        // Windows edit controls delete one code point per Backspace
        repeat(removed.codePointCount(0, removed.length)) {
            val p = linkedMapOf<String, Any?>("code" to "Backspace", "key" to "", "mods" to 0L, "repeat" to false)
            UkRuntime.sendInput(MessageTypes.KEY_DOWN, p); UkRuntime.sendInput(MessageTypes.KEY_UP, LinkedHashMap(p))
        }
        if (added.isNotEmpty()) UkRuntime.sendInput(MessageTypes.COMPOSITION_COMMIT, linkedMapOf("text" to added, "composer" to "android-system-ime"))
        sent = now
    }

    LazyColumn(contentPadding = PaddingValues(vertical = 8.dp)) {
        item {
            UkCard(if (target != null) "Typing to ${target.name}" else "No laptop connected") {
                Text("Pick any keyboard with 🌐 on your phone (e.g. Gboard 中文 / 日本語 / 한국어, Avro). " +
                    "Composed text is sent only after the keyboard commits it.", style = MaterialTheme.typography.bodySmall)
                ToggleRow("Live (send as you type)", live) { live = it; if (it) sent = value.text }
            }
        }
        item {
            OutlinedTextField(
                value = value,
                onValueChange = { v ->
                    value = v
                    if (live && v.composition == null) mirror(v.text) // composing → wait for the commit
                },
                label = { Text("Type here — any language") },
                minLines = 4,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            )
        }
        item {
            Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    if (live) { if (value.composition == null) mirror(value.text) }
                    else if (value.text.isNotEmpty()) {
                        if (UkRuntime.sendInput(MessageTypes.COMPOSITION_COMMIT, linkedMapOf("text" to value.text, "composer" to "android-system-ime")))
                            value = androidx.compose.ui.text.input.TextFieldValue("")
                        else UkRuntime.toast("Not sent: no laptop, typing to PC is off, or sync is paused")
                    }
                }, enabled = !live || value.composition == null) { Text(if (live) "Sync now" else "Send to PC") }
                OutlinedButton(onClick = {
                    value = androidx.compose.ui.text.input.TextFieldValue(""); sent = ""
                }) { Text("Clear (keeps PC text)") }
            }
        }
    }
}


// ------------------------------------------------------------------ Touchpad / mouse

@Composable
fun MouseScreen() {
    val s by UkRuntime.settings.state.collectAsState()
    val hubs by UkRuntime.hubs.collectAsState()
    val target = hubs.firstOrNull { it.isActive && it.connected }
    LazyColumn(contentPadding = PaddingValues(vertical = 8.dp)) {
        item {
            UkCard(if (target != null) "Mouse for ${target.name}" else "No laptop connected") {
                Text("1 finger: move · tap: click · double-tap & hold or long-press: drag · 2 fingers: tap = right-click, slide = scroll · " +
                    "right strip: scroll · buttons below: Left / Middle / Right (hold to drag). Also on the keyboard: 🖱.",
                    style = MaterialTheme.typography.bodySmall)
            }
        }
        item {
            AndroidView(factory = { ctx -> com.ukpro.android.keyboard.TouchpadView(ctx).apply { sink = UkRuntime.mouse } },
                update = { v ->
                    v.sensitivity = s.mouseSensitivity; v.scrollSpeed = s.scrollSpeed; v.naturalScroll = s.naturalScroll
                    v.dark = s.darkTheme; v.heightScale = 1.5f
                }, modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp))
        }
        item {
            UkCard("Settings") {
                Text("Pointer speed: ${"%.1f".format(s.mouseSensitivity)}×")
                Slider(value = s.mouseSensitivity, valueRange = 0.4f..4f, onValueChange = { v -> UkRuntime.settings.update { it.copy(mouseSensitivity = v) } })
                Text("Scroll speed: ${"%.1f".format(s.scrollSpeed)}×")
                Slider(value = s.scrollSpeed, valueRange = 0.3f..3f, onValueChange = { v -> UkRuntime.settings.update { it.copy(scrollSpeed = v) } })
                ToggleRow("Natural scrolling (content follows fingers)", s.naturalScroll) { v -> UkRuntime.settings.update { it.copy(naturalScroll = v) } }
            }
        }
    }
}


// ------------------------------------------------------------------ About & developer

@Composable
fun AboutScreen() {
    val ctx = LocalContext.current
    val version = runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: ""
    LazyColumn(contentPadding = PaddingValues(vertical = 16.dp), horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth()) {
        item {
            androidx.compose.foundation.Image(
                painter = androidx.compose.ui.res.painterResource(com.ukpro.android.R.drawable.developer),
                contentDescription = "Developer photo",
                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                modifier = Modifier.padding(8.dp).size(160.dp).clip(androidx.compose.foundation.shape.CircleShape))
        }
        item { Text(DEVELOPER_NAME, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(top = 8.dp)) }
        item { Text("Developer", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary) }
        item {
            UkCard("Universal Keyboard Pro $version") {
                Text("Android ⇄ Windows: two-way keyboard in ~40 languages, touchpad/mouse, universal clipboard with history, " +
                    "unlimited Wi-Fi file transfer, remote control panel and multi-device — encrypted, local network only, no cloud.",
                    style = MaterialTheme.typography.bodyMedium)
            }
        }
        item {
            UkCard("Privacy") {
                Text("Keystrokes are never logged or stored. Password fields are never mirrored. Pairing secrets stay in the Android Keystore.",
                    style = MaterialTheme.typography.bodySmall)
            }
        }
        item { Text("© 2026 $DEVELOPER_NAME", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(16.dp)) }
    }
}
