package com.ukpro.core

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.UUID

/** Anything that can be streamed: a java.io.File, or an Android content:// URI. */
interface FileSource {
    val name: String
    val size: Long
    val mime: String
    fun open(): InputStream
}

class LocalFileSource(private val file: File, override val mime: String = MimeTypes.fromName(file.name)) : FileSource {
    override val name: String get() = file.name
    override val size: Long get() = file.length()
    override fun open(): InputStream = file.inputStream()
}

/** Mirrors FileSender in FileTransfer.cs (PROTOCOL §5). Never holds more than one chunk in memory. */
class FileSender(
    val source: FileSource,
    val chunkSize: Int = 256 * 1024,
    val purpose: String = "file",
    val transferId: String = UUID.randomUUID().toString()
) {
    init { require(chunkSize in 16 * 1024..4 * 1024 * 1024) { "chunk size out of range" } }

    val sha256: String by lazy {
        val md = MessageDigest.getInstance("SHA-256")
        source.open().use { s ->
            val buf = ByteArray(64 * 1024)
            while (true) { val n = s.read(buf); if (n < 0) break; md.update(buf, 0, n) }
        }
        Hex.encode(md.digest())
    }

    fun startPayload(): MutableMap<String, Any?> = payloadOf(
        "transferId" to transferId, "name" to source.name, "size" to source.size, "mime" to source.mime,
        "sha256" to sha256, "chunkSize" to chunkSize, "purpose" to purpose)

    class Frame(val offset: Long, val length: Int, val bytes: ByteArray)

    /** Lazily produces complete binary frames from [fromOffset]. Close the stream by finishing or abandoning it. */
    fun frames(fromOffset: Long): Sequence<Frame> = sequence {
        val size = source.size
        source.open().use { input ->
            var skipped = 0L
            while (skipped < fromOffset) {
                val n = input.skip(fromOffset - skipped)
                if (n <= 0) { if (input.read() < 0) throw IOException("cannot seek"); skipped++ } else skipped += n
            }
            val buf = ByteArray(chunkSize)
            var offset = fromOffset
            while (offset < size) {
                val want = Math.min(chunkSize.toLong(), size - offset).toInt()
                var got = 0
                while (got < want) {
                    val n = input.read(buf, got, want - got)
                    if (n < 0) throw IOException("file shrank during transfer")
                    got += n
                }
                val header = payloadOf("type" to MessageTypes.FILE_CHUNK, "transferId" to transferId, "offset" to offset, "length" to got)
                yield(Frame(offset, got, BinaryFrame.encode(header, buf, 0, got)))
                offset += got
            }
        }
    }
}

/** Mirrors FileReceiver in FileTransfer.cs: .ukpart + side-car for resume, SHA-256 verification, safe names. */
class FileReceiver(start: Map<String, Any?>, private val destDir: File) {
    val transferId = start.str("transferId")
    val name = safeFileName(start.str("name"))
    val size = start.long("size", -1)
    val expectedSha256 = start.str("sha256").let { s -> String(s.toCharArray().map { if (it in 'A'..'F') 'a' + (it - 'A') else it }.toCharArray()) }
    val purpose = start.str("purpose", "file")
    var received = 0L
        private set
    val partFile: File
    private val metaFile: File
    private var raf: RandomAccessFile? = null
    private var lastPersist = 0L

    init {
        if (size < 0) throw ProtocolException("BAD_MESSAGE", "bad size")
        if (expectedSha256.length != 64 || expectedSha256.any { it !in '0'..'9' && it !in 'a'..'f' })
            throw ProtocolException("BAD_MESSAGE", "bad sha256")
        destDir.mkdirs()
        partFile = File(destDir, "$name.${expectedSha256.substring(0, 12)}.ukpart")
        metaFile = File(partFile.path + ".json")
        received = loadResume()
    }

    val resumeFrom: Long get() = received

    private fun loadResume(): Long = try {
        if (!partFile.exists() || !metaFile.exists()) 0 else {
            val m = Json.parseObject(metaFile.readText())
            if (m.str("sha256") != expectedSha256 || m.long("size") != size) 0
            else Math.min(m.long("received"), partFile.length())
        }
    } catch (e: Exception) { 0 }

    fun writeChunk(offset: Long, data: ByteArray, dataOffset: Int = 0, length: Int = data.size - dataOffset) {
        if (offset != received) throw ProtocolException("BAD_MESSAGE", "unexpected offset $offset, have $received")
        if (received + length > size) throw ProtocolException("TRANSFER_TOO_LARGE", "more data than announced")
        val f = raf ?: RandomAccessFile(partFile, "rw").also { it.setLength(received); it.seek(received); raf = it }
        f.write(data, dataOffset, length)
        received += length
        if (received - lastPersist >= 4L * 1024 * 1024 || received == size) persist()
    }

    private fun persist() {
        raf?.fd?.sync()
        metaFile.writeText(Json.write(payloadOf("transferId" to transferId, "sha256" to expectedSha256, "size" to size, "received" to received)))
        lastPersist = received
    }

    /** Verifies SHA-256 and moves the file into place. Returns the final file. */
    fun complete(): File {
        if (raf == null && size == 0L) RandomAccessFile(partFile, "rw").use { it.setLength(0) }
        raf?.close(); raf = null
        if (received != size) throw ProtocolException("BAD_MESSAGE", "incomplete: $received/$size")
        val md = MessageDigest.getInstance("SHA-256")
        partFile.inputStream().use { s ->
            val buf = ByteArray(64 * 1024)
            while (true) { val n = s.read(buf); if (n < 0) break; md.update(buf, 0, n) }
        }
        if (Hex.encode(md.digest()) != expectedSha256) {
            partFile.delete(); metaFile.delete()
            throw ProtocolException("HASH_MISMATCH", "SHA-256 mismatch")
        }
        val dest = uniqueFile(File(destDir, name))
        if (!partFile.renameTo(dest)) throw IOException("rename failed")
        metaFile.delete()
        return dest
    }

    fun abort(deletePartial: Boolean) {
        try { raf?.close() } catch (e: IOException) { }
        raf = null
        if (deletePartial) { partFile.delete(); metaFile.delete() } else if (received > 0) persist()
    }

    companion object {
        fun safeFileName(raw: String): String {
            var n = raw.replace('\\', '/').substringAfterLast('/')
            for (c in "<>:\"|?*\u0000") n = n.replace(c, '_')
            n = n.filter { it >= ' ' }.trim().trimEnd('.')
            return if (n.isEmpty() || n == "." || n == "..") "file" else n
        }

        private fun uniqueFile(f: File): File {
            if (!f.exists()) return f
            val stem = f.nameWithoutExtension
            val ext = if (f.extension.isEmpty()) "" else "." + f.extension
            var i = 1
            while (true) { val c = File(f.parentFile, "$stem ($i)$ext"); if (!c.exists()) return c; i++ }
        }
    }
}

object MimeTypes {
    private val map = mapOf(
        "pdf" to "application/pdf", "doc" to "application/msword",
        "docx" to "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "xls" to "application/vnd.ms-excel", "xlsx" to "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        "ppt" to "application/vnd.ms-powerpoint",
        "pptx" to "application/vnd.openxmlformats-officedocument.presentationml.presentation",
        "jpg" to "image/jpeg", "jpeg" to "image/jpeg", "png" to "image/png", "gif" to "image/gif", "webp" to "image/webp",
        "mp4" to "video/mp4", "mkv" to "video/x-matroska", "mp3" to "audio/mpeg", "zip" to "application/zip",
        "txt" to "text/plain", "apk" to "application/vnd.android.package-archive")

    fun fromName(name: String): String {
        val ext = name.substringAfterLast('.', "")
        val lower = String(ext.toCharArray().map { if (it in 'A'..'Z') 'a' + (it - 'A') else it }.toCharArray())
        return map[lower] ?: "application/octet-stream"
    }
}
