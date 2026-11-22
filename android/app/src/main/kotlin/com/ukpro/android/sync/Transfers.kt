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

/** A content:// URI as a streamable source (never loaded into memory). */
class UriFileSource(private val context: Context, private val uri: Uri) : FileSource {
    override val name: String
    override val size: Long
    override val mime: String

    init {
        var n = uri.lastPathSegment ?: "file"; var s = -1L
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                c.getString(0)?.let { n = it }
                if (!c.isNull(1)) s = c.getLong(1)
            }
        }
        if (s < 0) s = context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: 0
        name = n; size = s
        mime = context.contentResolver.getType(uri) ?: MimeTypes.fromName(n)
    }

    override fun open(): InputStream = context.contentResolver.openInputStream(uri) ?: throw java.io.IOException("cannot open $uri")
}

/**
 * Android side of file transfer: sends picked/shared files, saves received files to
 * Downloads/UK Pro through MediaStore (no storage permission needed on Android 10+), and
 * notifies with Open / Share actions.
 */
class Transfers(private val context: Context) {

    fun sendUris(uris: List<Uri>, conn: HubConnection? = UkRuntime.activeConnection()) {
        val c = conn ?: run { UkRuntime.toast("No laptop connected"); return }
        for (u in uris) UkRuntime.io.execute {
            try { c.transfers.sendFile(UriFileSource(context, u)) }
            catch (e: Exception) { UkRuntime.toast("Send failed: ${e.message}") }
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
