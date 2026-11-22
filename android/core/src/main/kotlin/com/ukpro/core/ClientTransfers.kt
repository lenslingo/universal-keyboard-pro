package com.ukpro.core

import java.io.File
import java.io.IOException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** Progress record shared by Android UI, Room history and tests. */
class TransferProgress(
    val transferId: String, val name: String, val size: Long, val incoming: Boolean, val purpose: String
) {
    enum class State { WAITING, RUNNING, PAUSED, VERIFYING, COMPLETED, FAILED, CANCELLED }
    @Volatile var transferred = 0L
    @Volatile var bytesPerSecond = 0.0
    @Volatile var state = State.WAITING
    @Volatile var finalFile: File? = null
    @Volatile var error: String? = null
    val fraction: Double get() = if (size == 0L) 1.0 else transferred.toDouble() / size
}

/**
 * Phone-side file transfer engine (PROTOCOL §5), the Kotlin twin of TransferManager.cs.
 * Feed it every message/binary frame from the session; it returns true when it consumed one.
 * [sendFile] blocks the calling (background) thread until done.
 */
class ClientTransfers(
    private val session: ClientSession,
    private val destinationDir: (TransferProgress) -> File,
    private val onChange: (TransferProgress) -> Unit = {}
) {
    private class Incoming(val rx: FileReceiver, val p: TransferProgress, val startedAt: Long, val startOffset: Long)
    private class Outgoing(val p: TransferProgress) {
        @Volatile var accepted = CompletableFuture<Long>()
        val result = CompletableFuture<Boolean>()
        @Volatile var paused = false
        @Volatile var cancelled = false
    }

    private val incoming = ConcurrentHashMap<String, Incoming>()
    private val outgoing = ConcurrentHashMap<String, Outgoing>()
    /** Snapshot of running and recent transfers for the UI. */
    val active: List<TransferProgress> get() = incoming.values.map { it.p } + outgoing.values.map { it.p }
    var maxIncomingBytes = 64L * 1024 * 1024 * 1024
    var acceptIncoming: (TransferProgress) -> Boolean = { true }

    fun sendFile(source: FileSource, chunkSize: Int = 256 * 1024, purpose: String = "file", attempts: Int = 5,
                 transferId: String = java.util.UUID.randomUUID().toString()): TransferProgress {
        val tx = FileSender(source, chunkSize, purpose, transferId)
        val p = TransferProgress(tx.transferId, source.name, source.size, false, purpose)
        val o = Outgoing(p)
        outgoing[tx.transferId] = o
        try {
            for (attempt in 1..attempts) {
                if (o.cancelled) { set(p, TransferProgress.State.CANCELLED); return p }
                try {
                    runOnce(tx, o)
                    return p
                } catch (e: IOException) {
                    p.error = e.message; set(p, TransferProgress.State.WAITING)
                    waitReady(120_000)
                    o.accepted = CompletableFuture()
                } catch (e: TimeoutException) {
                    p.error = "timeout"; set(p, TransferProgress.State.WAITING)
                    waitReady(120_000)
                    o.accepted = CompletableFuture()
                } catch (e: ProtocolException) {
                    p.error = e.message; set(p, TransferProgress.State.FAILED); return p
                }
            }
            set(p, TransferProgress.State.FAILED)
            return p
        } finally { outgoing.remove(tx.transferId) }
    }

    private fun runOnce(tx: FileSender, o: Outgoing) {
        val p = o.p
        session.send(MessageTypes.FILE_START, tx.startPayload())
        val resume = try { o.accepted.get(60, TimeUnit.SECONDS) } catch (e: java.util.concurrent.ExecutionException) { throw IOException(e.cause) }
        if (resume < 0) throw ProtocolException("FEATURE_DISABLED", "receiver declined")
        set(p, TransferProgress.State.RUNNING)
        val t0 = System.nanoTime(); var sent = 0L
        for (frame in tx.frames(resume)) {
            while (o.paused && !o.cancelled) { set(p, TransferProgress.State.PAUSED); Thread.sleep(200) }
            if (o.cancelled) {
                session.send(MessageTypes.FILE_CANCEL, payloadOf("transferId" to tx.transferId))
                set(p, TransferProgress.State.CANCELLED); return
            }
            // simple backpressure for queue-based links (OkHttp): keep < 4 MiB in flight
            while ((session.link?.queuedBytes() ?: 0) > 4L * 1024 * 1024) Thread.sleep(5)
            if (!session.sendBinary(frame.bytes)) throw IOException("link lost")
            sent += frame.length
            p.transferred = frame.offset + frame.length
            p.bytesPerSecond = sent / Math.max((System.nanoTime() - t0) / 1e9, 0.001)
            if (p.state != TransferProgress.State.RUNNING) p.state = TransferProgress.State.RUNNING
            onChange(p)
        }
        set(p, TransferProgress.State.VERIFYING)
        session.send(MessageTypes.FILE_COMPLETE, payloadOf("transferId" to tx.transferId))
        val ok = o.result.get(5, TimeUnit.MINUTES)
        set(p, if (ok) TransferProgress.State.COMPLETED else TransferProgress.State.FAILED)
    }

    private fun waitReady(timeoutMs: Long) {
        val until = System.currentTimeMillis() + timeoutMs
        while (!session.isReady) {
            if (System.currentTimeMillis() > until) throw ProtocolException("TIMEOUT", "laptop did not come back")
            Thread.sleep(250)
        }
    }

    fun pause(id: String, paused: Boolean) { outgoing[id]?.paused = paused }

    fun cancel(id: String) {
        outgoing[id]?.let { it.cancelled = true; it.accepted.complete(-1) }
        incoming.remove(id)?.let {
            it.rx.abort(true); set(it.p, TransferProgress.State.CANCELLED)
            session.send(MessageTypes.FILE_CANCEL, payloadOf("transferId" to id))
        }
    }

    /** Returns true if the message belonged to the transfer engine. */
    fun onMessage(e: Envelope): Boolean {
        val id = e.str("transferId")
        when (e.type) {
            MessageTypes.FILE_START -> handleStart(e)
            MessageTypes.FILE_ACCEPT -> outgoing[id]?.accepted?.complete(e.long("resumeFrom"))
            MessageTypes.FILE_RESULT -> outgoing[id]?.result?.complete(e.bool("ok"))
            MessageTypes.FILE_PAUSE -> outgoing[id]?.paused = e.bool("paused", true)
            MessageTypes.FILE_CANCEL -> {
                outgoing[id]?.let { it.cancelled = true; it.accepted.complete(-1) }
                incoming.remove(id)?.let { it.rx.abort(true); set(it.p, TransferProgress.State.CANCELLED) }
            }
            MessageTypes.FILE_COMPLETE -> handleComplete(id)
            else -> return false
        }
        return true
    }

    fun onBinary(f: BinaryFrame.Decoded): Boolean {
        if (f.header.str("type") != MessageTypes.FILE_CHUNK) return false
        val t = incoming[f.header.str("transferId")] ?: return true
        try {
            t.rx.writeChunk(f.header.long("offset"), f.frame, f.dataOffset, f.dataLength)
            t.p.transferred = t.rx.received
            t.p.bytesPerSecond = (t.p.transferred - t.startOffset) / Math.max((System.nanoTime() - t.startedAt) / 1e9, 0.001)
            onChange(t.p)
        } catch (e: ProtocolException) {
            t.p.error = e.message; set(t.p, TransferProgress.State.FAILED)
        }
        return true
    }

    private fun handleStart(e: Envelope) {
        val id = e.str("transferId")
        val p = TransferProgress(id, FileReceiver.safeFileName(e.str("name")), e.long("size"), true, e.str("purpose", "file"))
        if (p.size > maxIncomingBytes || !acceptIncoming(p)) {
            session.send(MessageTypes.FILE_CANCEL, payloadOf("transferId" to id, "reason" to "declined")); return
        }
        incoming.remove(id)?.rx?.abort(false)
        val rx = FileReceiver(e.payload, destinationDir(p))
        p.transferred = rx.resumeFrom
        incoming[id] = Incoming(rx, p, System.nanoTime(), rx.resumeFrom)
        set(p, TransferProgress.State.RUNNING)
        session.send(MessageTypes.FILE_ACCEPT, payloadOf("transferId" to id, "resumeFrom" to rx.resumeFrom))
    }

    private fun handleComplete(id: String) {
        val t = incoming.remove(id) ?: return
        set(t.p, TransferProgress.State.VERIFYING)
        val ok = try { t.p.finalFile = t.rx.complete(); true } catch (e: Exception) { t.p.error = e.message; false }
        set(t.p, if (ok) TransferProgress.State.COMPLETED else TransferProgress.State.FAILED)
        session.send(MessageTypes.FILE_RESULT, payloadOf("transferId" to id, "ok" to ok, "sha256" to t.rx.expectedSha256))
    }

    private fun set(p: TransferProgress, s: TransferProgress.State) { p.state = s; onChange(p) }
}
