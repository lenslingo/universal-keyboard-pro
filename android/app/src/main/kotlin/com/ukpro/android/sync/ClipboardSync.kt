package com.ukpro.android.sync

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.os.Build
import com.ukpro.android.UkRuntime
import com.ukpro.android.data.ClipEntity
import com.ukpro.android.transport.HubConnection
import com.ukpro.core.ClipStamp
import com.ukpro.core.ClipboardResolver
import com.ukpro.core.Envelope
import com.ukpro.core.MessageTypes
import com.ukpro.core.bool
import com.ukpro.core.list
import com.ukpro.core.long
import com.ukpro.core.str
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.launch

/**
 * Bidirectional clipboard (PROTOCOL §3.2) with history. Local changes are read by the IME's
 * clipboard listener (the default IME is exempt from Android 10+ background-read restrictions)
 * or by ClipboardSendActivity (manual sync). Remote clips are written with setPrimaryClip;
 * images/files go through FileProvider content URIs.
 */
class ClipboardSync(private val context: Context) {
    private val cm = context.getSystemService(ClipboardManager::class.java)
    private val resolver by lazy { ClipboardResolver(UkRuntime.secrets.deviceId) }
    private val pending = ConcurrentHashMap<String, Pair<ClipStamp, Envelope>>()  // transferId → clip
    private val pendingFiles = ConcurrentHashMap<String, MutableList<File>>()     // clipId → received files
    private val pendingFileClips = ConcurrentHashMap<String, Pair<ClipStamp, Envelope>>()
    private val outgoingFiles = ConcurrentHashMap<String, List<Uri>>()            // clipId → our URIs (for CLIPBOARD_FETCH)
    @Volatile private var lastAppliedUris: Set<String> = emptySet()
    @Volatile private var lastAppliedHash: String? = null

    companion object {
        const val INLINE_TEXT_LIMIT = 1024 * 1024
        const val MAX_IMAGE_BYTES = 32L * 1024 * 1024
        const val AUTO_FILE_LIMIT = 50L * 1024 * 1024
    }

    // ================================================================ local → laptops

    /** Call with the current primary clip (from the IME listener or a focused activity). */
    fun onLocalClip(clip: ClipData?, manual: Boolean = false) {
        val s = UkRuntime.settings.current
        if (clip == null || clip.itemCount == 0) return
        if (!manual && (!s.clipboardSync || s.paused)) return
        if (isSensitive(clip.description)) return
        UkRuntime.io.execute { runCatching { handleLocal(clip) }.onFailure { UkRuntime.toast("Clipboard: ${it.message}") } }
    }

    private fun isSensitive(d: ClipDescription?): Boolean =
        d?.extras?.getBoolean(if (Build.VERSION.SDK_INT >= 33) ClipDescription.EXTRA_IS_SENSITIVE else "android.content.extra.IS_SENSITIVE") == true

    private fun handleLocal(clip: ClipData) {
        val s = UkRuntime.settings.current
        val uris = (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).uri }
        val desc = clip.description
        if (uris.isNotEmpty()) {
            val key = uris.map { it.toString() }.toSet()
            if (key == lastAppliedUris) { resolver.onLocalChange(lastAppliedHash ?: return); return } // our own write
            val first = UriFileSource(context, uris[0])
            if (uris.size == 1 && first.mime.startsWith("image/") && first.size <= MAX_IMAGE_BYTES) {
                if (!s.clipboardImages) return
                val bytes = first.open().use { it.readBytes() }
                val stamp = resolver.onLocalChange(ClipboardResolver.hashBytes(bytes)) ?: return
                remember(stamp, "image", "Image (${first.mime})", null, uris[0].toString())
                for (c in UkRuntime.connectedHubs()) sendAsFile(c, MessageTypes.CLIPBOARD_IMAGE, stamp, UriFileSource(context, uris[0]))
                return
            }
            if (!s.clipboardFiles) return
            val sources = uris.map { UriFileSource(context, it) }
            val hash = ClipboardResolver.hashFiles(sources.map { Triple(it.name, it.size, it.mime) })
            val stamp = resolver.onLocalChange(hash) ?: return
            outgoingFiles[stamp.clipId] = uris
            remember(stamp, "files", sources.joinToString { it.name }, null, uris.joinToString("\n"))
            val total = sources.sumOf { it.size }
            val p = stamp.toPayload()
            p["files"] = sources.map { linkedMapOf<String, Any?>("name" to it.name, "size" to it.size) }
            p["auto"] = total <= AUTO_FILE_LIMIT
            for (c in UkRuntime.connectedHubs()) {
                c.session.send(MessageTypes.CLIPBOARD_FILE, LinkedHashMap(p))
                if (total <= AUTO_FILE_LIMIT) sendFiles(c, stamp.clipId)
            }
            return
        }
        val text = clip.getItemAt(0).coerceToText(context)?.toString() ?: return
        if (text.isEmpty()) return
        val stamp = resolver.onLocalChange(ClipboardResolver.hashText(text)) ?: return
        remember(stamp, "text", text.take(200), text, null)
        if (text.toByteArray(Charsets.UTF_8).size <= INLINE_TEXT_LIMIT) {
            val p = stamp.toPayload(); p["text"] = text; p["mime"] = desc?.getMimeType(0) ?: "text/plain"
            UkRuntime.broadcast(MessageTypes.CLIPBOARD_TEXT, p)
        } else {
            val f = File(context.cacheDir, "clipboard/clip-${stamp.clipId}.txt").apply { parentFile?.mkdirs(); writeText(text) }
            for (c in UkRuntime.connectedHubs()) sendAsFile(c, MessageTypes.CLIPBOARD_TEXT, stamp, com.ukpro.core.LocalFileSource(f, "text/plain"))
        }
    }

    private fun sendAsFile(c: HubConnection, type: String, stamp: ClipStamp, src: com.ukpro.core.FileSource) {
        val transferId = UUID.randomUUID().toString()
        val p = stamp.toPayload()
        p["mime"] = src.mime; p["size"] = src.size; p["transferId"] = transferId
        c.session.send(type, p)
        UkRuntime.io.execute { c.transfers.sendFile(src, purpose = "clipboard", transferId = transferId) }
    }

    private fun sendFiles(c: HubConnection, clipId: String) {
        val uris = outgoingFiles[clipId] ?: return
        UkRuntime.io.execute { for (u in uris) c.transfers.sendFile(UriFileSource(context, u), purpose = "clipboard-file:$clipId") }
    }

    fun onFetch(c: HubConnection, clipId: String) = sendFiles(c, clipId)

    // ================================================================ laptops → local

    fun onRemote(conn: HubConnection, env: Envelope) {
        val s = UkRuntime.settings.current
        if (!s.clipboardSync || s.paused) return
        val stamp = ClipStamp.from(env.payload)
        val d = resolver.onRemote(stamp)
        if (d == ClipboardResolver.Decision.IGNORE_DUPLICATE || d == ClipboardResolver.Decision.IGNORE_OLDER) return
        when (env.type) {
            MessageTypes.CLIPBOARD_TEXT -> {
                if (env.payload.containsKey("text")) {
                    val text = env.str("text")
                    if (ClipboardResolver.hashText(text) != stamp.hash) return
                    if (d == ClipboardResolver.Decision.APPLY) setText(text)
                    remember(stamp, "text", text.take(200), text, null, conn.name)
                } else pending[env.str("transferId")] = stamp to env
            }
            MessageTypes.CLIPBOARD_IMAGE -> if (s.clipboardImages) pending[env.str("transferId")] = stamp to env
            MessageTypes.CLIPBOARD_FILE -> if (s.clipboardFiles) {
                pendingFileClips[stamp.clipId] = stamp to env
                pendingFiles[stamp.clipId] = mutableListOf()
                if (!env.bool("auto")) UkRuntime.toast("${conn.name} copied ${env.payload.list("files").size} file(s) — open Clipboard to fetch")
            }
        }
    }

    fun fetchFiles(conn: HubConnection, clipId: String) = conn.session.send(MessageTypes.CLIPBOARD_FETCH, linkedMapOf("clipId" to clipId))

    fun onTransferDone(transferId: String, file: File) {
        val (stamp, env) = pending.remove(transferId) ?: return
        if (resolver.current?.clipId != stamp.clipId) return // a newer clip won meanwhile
        if (env.type == MessageTypes.CLIPBOARD_IMAGE) {
            val uri = UkRuntime.transfers.uriFor(file)
            setUris(listOf(uri), "image", stamp.hash, env.str("mime", "image/png"))
            remember(stamp, "image", "Image", null, uri.toString())
        } else {
            val text = file.readText()
            setText(text)
            remember(stamp, "text", text.take(200), text, null)
        }
    }

    fun onClipFileDone(clipId: String, file: File) {
        val list = pendingFiles[clipId] ?: return
        val (stamp, env) = pendingFileClips[clipId] ?: return
        synchronized(list) { list.add(file) }
        if (list.size < env.payload.list("files").size) return
        pendingFiles.remove(clipId); pendingFileClips.remove(clipId)
        if (resolver.current?.clipId != clipId) return
        val uris = list.map { UkRuntime.transfers.uriFor(it) }
        setUris(uris, "files", stamp.hash, "*/*")
        remember(stamp, "files", list.joinToString { it.name }, null, uris.joinToString("\n"))
        // Most Android apps cannot paste files: also offer Open / Share from the notification (KNOWN_LIMITATIONS #4)
        for ((i, f) in list.withIndex()) UkRuntime.transfers.notifyReceived("clipboard", f.name, uris[i], com.ukpro.core.MimeTypes.fromName(f.name))
    }

    private fun setText(text: String) = UkRuntime.main.post {
        lastAppliedUris = emptySet()
        cm.setPrimaryClip(ClipData.newPlainText("UK Pro", text))
    }

    private fun setUris(uris: List<Uri>, label: String, hash: String, mime: String) = UkRuntime.main.post {
        val clip = ClipData(label, arrayOf(mime), ClipData.Item(uris[0]))
        for (u in uris.drop(1)) clip.addItem(ClipData.Item(u))
        lastAppliedUris = uris.map { it.toString() }.toSet()
        lastAppliedHash = hash
        cm.setPrimaryClip(clip)
    }

    // ================================================================ history

    private fun remember(stamp: ClipStamp, kind: String, preview: String, text: String?, uris: String?, originName: String? = null) {
        if (!UkRuntime.settings.current.historyEnabled) return
        val name = originName ?: if (stamp.origin == UkRuntime.secrets.deviceId) "This phone"
            else UkRuntime.connection(stamp.origin)?.name ?: "Laptop"
        UkRuntime.scope.launch {
            val dao = UkRuntime.db.clips()
            dao.put(ClipEntity(stamp.clipId, kind, preview, text, uris, stamp.origin, name, stamp.hash, System.currentTimeMillis()))
            dao.dedupe(stamp.hash, stamp.clipId)
            dao.prune(UkRuntime.settings.current.historyMaxItems)
        }
    }

    /** Puts an older history item back on this phone's clipboard. */
    fun restore(e: ClipEntity) {
        when (e.kind) {
            "text" -> e.text?.let { UkRuntime.main.post { cm.setPrimaryClip(ClipData.newPlainText("UK Pro", it)) } }
            else -> e.uris?.split('\n')?.filter { it.isNotBlank() }?.map { Uri.parse(it) }?.takeIf { it.isNotEmpty() }?.let { u ->
                UkRuntime.main.post {
                    val clip = ClipData(e.kind, arrayOf(if (e.kind == "image") "image/*" else "*/*"), ClipData.Item(u[0]))
                    for (x in u.drop(1)) clip.addItem(ClipData.Item(x))
                    cm.setPrimaryClip(clip)
                }
            }
        }
    }

    /** Sends a history item to the active laptop's clipboard (and optionally pastes there). */
    fun sendToLaptop(e: ClipEntity) {
        val c = UkRuntime.activeConnection() ?: run { UkRuntime.toast("No laptop connected"); return }
        if (e.kind == "text" && e.text != null) {
            val stamp = resolver.onLocalChange(ClipboardResolver.hashText(e.text) + ":" + System.nanoTime())
                ?: return
            val p = ClipStamp(stamp.clipId, ClipboardResolver.hashText(e.text), stamp.lamport, stamp.origin).toPayload()
            p["text"] = e.text; p["mime"] = "text/plain"
            c.session.send(MessageTypes.CLIPBOARD_TEXT, p)
        } else restore(e) // images/files: put back locally; the IME listener syncs them
    }
}
