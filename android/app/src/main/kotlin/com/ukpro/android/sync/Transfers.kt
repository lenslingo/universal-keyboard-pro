package com.ukpro.android.sync

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import com.ukpro.android.R
import com.ukpro.android.UkApp
import com.ukpro.android.UkRuntime
import com.ukpro.android.transport.HubConnection
import com.ukpro.core.FileSource
import com.ukpro.core.MimeTypes
import com.ukpro.core.TransferProgress
import java.io.File
import java.io.InputStream

/**
 * A content:// URI as a streamable source (never loaded into memory).
 *
 * The file descriptor is opened immediately, while the activity that received the URI (Share sheet,
 * file picker) still holds the temporary read permission. Android revokes that permission when the
 * activity finishes; an already-open descriptor stays valid, so the transfer can run in the background
 * and resume later. Non-seekable streams (some cloud providers) are copied to the cache once.
 */
class UriFileSource(private val context: Context, private val uri: Uri) : FileSource, java.io.Closeable {
    override val name: String
    override val size: Long
    override val mime: String
    private val pfd: android.os.ParcelFileDescriptor?
    @Volatile private var spooled: File? = null

    init {
        var n = uri.lastPathSegment?.substringAfterLast('/') ?: "file"; var s = -1L
        runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    c.getString(0)?.let { n = it }
                    if (!c.isNull(1)) s = c.getLong(1)
                }
            }
        }
        pfd = try { context.contentResolver.openFileDescriptor(uri, "r") } catch (e: Exception) { null }
        if (s < 0) s = pfd?.statSize?.takeIf { it >= 0 } ?: -1
        name = n
        mime = runCatching { context.contentResolver.getType(uri) }.getOrNull() ?: MimeTypes.fromName(n)
        if (s < 0) s = spool().length() // unknown size (stream): copy once so size and re-reads are exact
        size = s
    }

    private fun seekableStream(): InputStream? {
        val fd = pfd ?: return null
        return try {
            val fis = java.io.FileInputStream(fd.fileDescriptor)
            fis.channel.position(0)
            // do not close the shared descriptor when one pass is done (hash pass, then the send pass)
            object : java.io.FilterInputStream(fis) { override fun close() {} }
        } catch (e: Exception) { null }
    }

    private fun spool(): File {
        spooled?.let { return it }
        val f = File(context.cacheDir, "outgoing/${System.nanoTime()}-${name.replace('/', '_')}").apply { parentFile?.mkdirs() }
        val input = pfd?.let { android.os.ParcelFileDescriptor.AutoCloseInputStream(it) } ?: context.contentResolver.openInputStream(uri)
            ?: throw java.io.IOException("cannot open $uri")
        input.use { i -> f.outputStream().use { o -> i.copyTo(o, 1 shl 16) } }
        spooled = f
        return f
    }

    override fun open(): InputStream =
        spooled?.inputStream() ?: seekableStream() ?: spool().inputStream()

    override fun close() {
        try { pfd?.close() } catch (e: Exception) { }
        spooled?.delete()
    }
}

/**
 * Android side of file transfer: sends picked/shared files, saves received files to
 * Downloads/UK Pro through MediaStore (no storage permission needed on Android 10+), and
 * notifies with Open / Share actions.
 */
class Transfers(private val context: Context) {

    /** Must be called while the Share/picker activity is still alive: the files are opened right here. */
    fun sendUris(uris: List<Uri>, conn: HubConnection? = UkRuntime.activeConnection()) {
        val c = conn ?: run { UkRuntime.toast("No laptop connected"); return }
        val sources = uris.mapNotNull { u ->
            try { UriFileSource(context, u) } catch (e: Exception) { UkRuntime.toast("Cannot read ${u.lastPathSegment}: ${e.message}"); null }
        }
        for (src in sources) UkRuntime.io.execute {
            try {
                val p = c.transfers.sendFile(src)
                if (p.state == TransferProgress.State.FAILED) UkRuntime.toast("${p.name}: ${p.error ?: "failed"}")
            } catch (e: Exception) { UkRuntime.toast("Send failed: ${e.message}") }
        }
    }

    fun onProgress(conn: HubConnection, p: TransferProgress) {
        if (!p.incoming || p.state != TransferProgress.State.COMPLETED) return
        val f = p.finalFile ?: return
        when {
            p.purpose == "clipboard" -> UkRuntime.clipboard.onTransferDone(p.transferId, f)
            p.purpose.startsWith("clipboard-file:") -> UkRuntime.clipboard.onClipFileDone(p.purpose.removePrefix("clipboard-file:"), f)
            else -> UkRuntime.io.execute { publish(conn, f) }
        }
    }

    /**
     * Where received files are written. Android 11+: straight into the public Download/UK Pro folder
     * (allowed without permission for files the app creates), so a 50 GB video needs no second copy
     * and no space in the small app cache. Android 10: the same with the legacy storage permission,
     * otherwise the app's own external folder followed by a MediaStore copy.
     */
    fun incomingDir(): File {
        val public = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "UK Pro")
        val direct = android.os.Build.VERSION.SDK_INT >= 30 || context.checkSelfPermission(
            android.Manifest.permission.WRITE_EXTERNAL_STORAGE) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (direct && (public.isDirectory || public.mkdirs()) && public.canWrite()) return public
        return File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir, "incoming").apply { mkdirs() }
    }

    private fun publish(conn: HubConnection, f: File) {
        val publicDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "UK Pro")
        if (f.parentFile?.absolutePath == publicDir.absolutePath) {
            // already in Downloads: just index it so Files / Gallery / other apps see it
            android.media.MediaScannerConnection.scanFile(context, arrayOf(f.absolutePath), arrayOf(MimeTypes.fromName(f.name))) { _, uri ->
                notifyReceived(conn.name, f.name, uri ?: uriFor(f), MimeTypes.fromName(f.name))
            }
        } else saveToDownloads(conn, f)
    }

    private fun saveToDownloads(conn: HubConnection, f: File) {
        val mime = MimeTypes.fromName(f.name)
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, f.name)
            put(MediaStore.Downloads.MIME_TYPE, mime)
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/UK Pro")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return
        resolver.openOutputStream(uri)?.use { out -> f.inputStream().use { it.copyTo(out, 1 shl 16) } }
        values.clear(); values.put(MediaStore.Downloads.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        f.delete()
        notifyReceived(conn.name, f.name, uri, mime)
    }

    fun notifyReceived(from: String, name: String, uri: Uri, mime: String) {
        val open = Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        val share = Intent.createChooser(Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Share").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val n = NotificationCompat.Builder(context, UkApp.CHANNEL_FILES)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Received from $from")
            .setContentText(name)
            .setAutoCancel(true)
            .setContentIntent(PendingIntent.getActivity(context, name.hashCode(), open, flags))
            .addAction(0, "Open", PendingIntent.getActivity(context, name.hashCode() + 1, open, flags))
            .addAction(0, "Share", PendingIntent.getActivity(context, name.hashCode() + 2, share, flags))
            .build()
        context.getSystemService(NotificationManager::class.java).notify(name.hashCode(), n)
    }

    /** content:// URI for a file in our cache/files dirs (FileProvider). */
    fun uriFor(f: File): Uri = FileProvider.getUriForFile(context, context.packageName + ".files", f)
}
