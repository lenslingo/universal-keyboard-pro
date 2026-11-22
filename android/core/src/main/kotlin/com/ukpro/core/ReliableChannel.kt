package com.ukpro.core

import java.util.ArrayDeque
import java.util.LinkedList
import java.util.TreeMap
import java.util.UUID

/** Exactly-once, in-order delivery for one peer link. Mirrors ReliableChannel.cs (PROTOCOL §2.1). */
class ReliableChannel(
    private val localDeviceId: String,
    sessionId: String? = null,
    private val clock: () -> Long = { System.currentTimeMillis() }
) {
    val localSessionId: String = sessionId ?: UUID.randomUUID().toString().replace("-", "")

    private val lock = Any()
    private var nextSeq = 1L
    private class Queued(val env: Envelope, val queuedAt: Long)
    private val outbox = LinkedList<Queued>()

    private var peerSession: String? = null
    private var lastDeliveredSeq = 0L
    private val pending = TreeMap<Long, Envelope>()
    private val seen = LruSet(4096)
    private var lastAckSent = 0L
    private var lastAckTime = 0L

    val peerSessionId: String? get() = synchronized(lock) { peerSession }
    val lastDelivered: Long get() = synchronized(lock) { lastDeliveredSeq }
    val outboxCount: Int get() = synchronized(lock) { outbox.size }

    fun stamp(e: Envelope): Envelope = synchronized(lock) {
        e.sessionId = localSessionId
        e.sourceDeviceId = localDeviceId
        e.ts = clock()
        if (MessageTypes.isReliable(e.type)) {
            e.seq = nextSeq++
            outbox.addLast(Queued(e, e.ts))
            while (outbox.size > MAX_OUTBOX) outbox.removeFirst()
        } else e.seq = 0
        e
    }

    fun onAck(upTo: Long) = synchronized(lock) { ackUnlocked(upTo) }

    private fun ackUnlocked(upTo: Long) {
        while (outbox.isNotEmpty() && outbox.first.env.seq <= upTo) outbox.removeFirst()
    }

    fun resendAfterReconnect(peerKnowsSessionId: String?, peerLastRecv: Long): List<Envelope> = synchronized(lock) {
        ackUnlocked(if (peerKnowsSessionId == localSessionId) peerLastRecv else 0)
        val now = clock()
        outbox.map { q ->
            if (MessageTypes.isTimeSensitive(q.env.type) && now - q.queuedAt > INPUT_TTL_MS)
                Envelope(MessageTypes.NOOP, LinkedHashMap(), q.env.eventId, q.env.sessionId, q.env.sourceDeviceId,
                    q.env.targetDeviceId, q.env.seq, q.env.ts)
            else q.env
        }
    }

    fun beginPeerSession(sid: String) = synchronized(lock) {
        if (peerSession != sid) resetInbound(sid)
    }

    private fun resetInbound(sid: String) {
        peerSession = sid; lastDeliveredSeq = 0; lastAckSent = 0; pending.clear()
    }

    /** Messages that became deliverable, in order. Empty for duplicates and gaps. */
    fun receive(e: Envelope): List<Envelope> = synchronized(lock) {
        if (e.seq == 0L) {
            if (e.type == MessageTypes.PING || e.type == MessageTypes.PONG || e.type == MessageTypes.ACK) return listOf(e)
            return if (seen.add(e.eventId)) listOf(e) else emptyList()
        }
        if (peerSession == null || e.sessionId != peerSession) resetInbound(e.sessionId)
        if (e.seq <= lastDeliveredSeq || pending.containsKey(e.seq)) return emptyList()
        if (e.seq != lastDeliveredSeq + 1) {
            if (pending.size >= MAX_PENDING) throw ProtocolException("RESYNC", "too many out-of-order messages")
            pending[e.seq] = e
            return emptyList()
        }
        val out = ArrayList<Envelope>()
        deliver(e, out)
        while (true) {
            val next = pending.remove(lastDeliveredSeq + 1) ?: break
            deliver(next, out)
        }
        out
    }

    private fun deliver(e: Envelope, into: MutableList<Envelope>) {
        lastDeliveredSeq = e.seq
        if (e.type == MessageTypes.NOOP) return
        if (seen.add(e.eventId)) into.add(e)
    }

    /** Returns the cumulative ACK to send now, or -1. */
    fun takeAck(force: Boolean = false): Long = synchronized(lock) {
        val upTo = lastDeliveredSeq
        if (upTo == lastAckSent) return -1
        val now = clock()
        if (!force && upTo - lastAckSent < 32 && now - lastAckTime < 50) return -1
        lastAckSent = upTo; lastAckTime = now
        upTo
    }

    companion object {
        const val MAX_PENDING = 1024
        const val MAX_OUTBOX = 20000
        const val INPUT_TTL_MS = 10_000L
    }
}

class LruSet(private val capacity: Int) {
    private val set = HashSet<String>()
    private val order = ArrayDeque<String>()
    fun add(id: String): Boolean {
        if (!set.add(id)) return false
        order.addLast(id)
        if (order.size > capacity) set.remove(order.removeFirst())
        return true
    }
    operator fun contains(id: String) = id in set
}
