package com.ukpro.core

import java.util.UUID

class ClipStamp(val clipId: String, val hash: String, val lamport: Long, val origin: String) {
    fun toPayload(): MutableMap<String, Any?> = payloadOf("clipId" to clipId, "hash" to hash, "lamport" to lamport, "origin" to origin)

    companion object {
        fun from(p: Map<String, Any?>) = ClipStamp(p.str("clipId"), p.str("hash"), p.long("lamport"), p.str("origin"))
    }
}

/** Mirrors ClipboardResolver.cs: loop prevention + deterministic (lamport, origin) conflict resolution. */
class ClipboardResolver(private val localDeviceId: String) {
    enum class Decision { APPLY, IGNORE_DUPLICATE, IGNORE_OLDER, SAME_CONTENT }

    private val lock = Any()
    private val seen = LruSet(2048)
    private var lamport = 0L
    @Volatile var current: ClipStamp? = null
        private set

    /** Local clipboard changed; returns the stamp to broadcast or null for echoes / unchanged content. */
    fun onLocalChange(hash: String): ClipStamp? = synchronized(lock) {
        val cur = current
        if (cur != null && cur.hash == hash) return null
        lamport++
        val s = ClipStamp(UUID.randomUUID().toString(), hash, lamport, localDeviceId)
        seen.add(s.clipId)
        current = s
        s
    }

    fun onRemote(s: ClipStamp): Decision = synchronized(lock) {
        if (!seen.add(s.clipId)) return Decision.IGNORE_DUPLICATE
        lamport = Math.max(lamport, s.lamport)
        val cur = current
        if (cur != null && compare(s, cur) <= 0) return Decision.IGNORE_OLDER
        current = s
        if (cur != null && cur.hash == s.hash) Decision.SAME_CONTENT else Decision.APPLY
    }

    companion object {
        fun compare(a: ClipStamp, b: ClipStamp): Int {
            val c = a.lamport.compareTo(b.lamport)
            return if (c != 0) c else ordinal(a.origin, b.origin)
        }

        /** Ordinal UTF-16 comparison, identical to .NET string.CompareOrdinal. */
        private fun ordinal(a: String, b: String): Int {
            val n = Math.min(a.length, b.length)
            for (i in 0 until n) { val d = a[i] - b[i]; if (d != 0) return d }
            return a.length - b.length
        }

        fun normalizeNewlines(s: String) = s.replace("\r\n", "\n").replace('\r', '\n')
        fun hashText(text: String): String = sha256Hex(normalizeNewlines(text).toByteArray(Charsets.UTF_8))
        fun hashBytes(b: ByteArray): String = sha256Hex(b)

        fun hashFiles(files: List<Triple<String, Long, String>>): String {
            val sb = StringBuilder()
            for (f in files.sortedWith(Comparator { x, y -> ordinal(x.first, y.first) }))
                sb.append(f.first).append('\u0000').append(f.second).append('\u0000').append(f.third).append('\n')
            return hashText(sb.toString())
        }
    }
}
