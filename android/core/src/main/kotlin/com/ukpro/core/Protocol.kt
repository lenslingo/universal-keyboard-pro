package com.ukpro.core

import java.nio.ByteBuffer
import java.util.Base64
import java.util.UUID

/** Protocol v1 message types — must match windows/src/UKPro.Core/Protocol.cs. */
object MessageTypes {
    const val KEY_DOWN = "KEY_DOWN"; const val KEY_UP = "KEY_UP"; const val TEXT_COMMIT = "TEXT_COMMIT"
    const val COMPOSITION_COMMIT = "COMPOSITION_COMMIT"; const val CLIPBOARD_TEXT = "CLIPBOARD_TEXT"
    const val CLIPBOARD_IMAGE = "CLIPBOARD_IMAGE"; const val CLIPBOARD_FILE = "CLIPBOARD_FILE"
    const val FILE_START = "FILE_START"; const val FILE_CHUNK = "FILE_CHUNK"; const val FILE_COMPLETE = "FILE_COMPLETE"
    const val DEVICE_STATUS = "DEVICE_STATUS"; const val PAIR_REQUEST = "PAIR_REQUEST"; const val PAIR_ACCEPTED = "PAIR_ACCEPTED"
    const val PING = "PING"; const val PONG = "PONG"
    const val ACK = "ACK"; const val ERROR = "ERROR"; const val NOOP = "NOOP"; const val PAIR_REJECTED = "PAIR_REJECTED"
    const val PAIR_INFO = "PAIR_INFO"
    const val AUTH_HELLO = "AUTH_HELLO"; const val AUTH_CHALLENGE = "AUTH_CHALLENGE"; const val AUTH_RESPONSE = "AUTH_RESPONSE"
    const val AUTH_OK = "AUTH_OK"; const val AUTH_FAIL = "AUTH_FAIL"
    const val FILE_ACCEPT = "FILE_ACCEPT"; const val FILE_RESUME = "FILE_RESUME"; const val FILE_PAUSE = "FILE_PAUSE"
    const val FILE_CANCEL = "FILE_CANCEL"; const val FILE_RESULT = "FILE_RESULT"
    const val INPUT_FOCUS = "INPUT_FOCUS"; const val SYNC_CONFIG = "SYNC_CONFIG"
    const val REMOTE_ACTION = "REMOTE_ACTION"; const val MEDIA_CONTROL = "MEDIA_CONTROL"; const val VOLUME_SET = "VOLUME_SET"
    const val CLIPBOARD_HISTORY_REQUEST = "CLIPBOARD_HISTORY_REQUEST"
    const val CLIPBOARD_HISTORY_RESPONSE = "CLIPBOARD_HISTORY_RESPONSE"
    const val CLIPBOARD_HISTORY_APPLY = "CLIPBOARD_HISTORY_APPLY"; const val CLIPBOARD_FETCH = "CLIPBOARD_FETCH"
    const val DEVICE_LIST = "DEVICE_LIST"

    private val unreliable = setOf(PING, PONG, ACK, FILE_CHUNK, AUTH_HELLO, AUTH_CHALLENGE, AUTH_RESPONSE, AUTH_OK,
        AUTH_FAIL, PAIR_REQUEST, PAIR_ACCEPTED, PAIR_REJECTED, PAIR_INFO, DEVICE_STATUS, ERROR)
    private val timeSensitive = setOf(KEY_DOWN, KEY_UP, TEXT_COMMIT, COMPOSITION_COMMIT, REMOTE_ACTION, MEDIA_CONTROL, VOLUME_SET)

    fun isReliable(type: String) = type !in unreliable
    fun isTimeSensitive(type: String) = type in timeSensitive
}

object KeyMods {
    const val CTRL = 1; const val SHIFT = 2; const val ALT = 4; const val META = 8; const val CAPS = 16; const val ALTGR = 32
}

class ProtocolException(val code: String, message: String) : RuntimeException(message)

class Envelope(
    var type: String,
    var payload: MutableMap<String, Any?> = LinkedHashMap(),
    var eventId: String = UUID.randomUUID().toString(),
    var sessionId: String = "",
    var sourceDeviceId: String = "",
    var targetDeviceId: String = "*",
    var seq: Long = 0,
    var ts: Long = System.currentTimeMillis(),
    var v: Int = VERSION
) {
    fun toJson(): String {
        val m = LinkedHashMap<String, Any?>()
        m["v"] = v; m["type"] = type; m["eventId"] = eventId; m["sessionId"] = sessionId
        m["sourceDeviceId"] = sourceDeviceId; m["targetDeviceId"] = targetDeviceId
        m["seq"] = seq; m["ts"] = ts; m["payload"] = payload
        return Json.write(m)
    }

    fun str(k: String, def: String = "") = payload.str(k, def)
    fun long(k: String, def: Long = 0) = payload.long(k, def)
    fun bool(k: String, def: Boolean = false) = payload.bool(k, def)

    companion object {
        const val VERSION = 1

        fun parse(json: String): Envelope {
            val o = try { Json.parseObject(json) } catch (e: JsonException) { throw ProtocolException("BAD_MESSAGE", e.message ?: "bad json") }
            val v = o.long("v").toInt()
            if (v != VERSION) throw ProtocolException("UNSUPPORTED_VERSION", "version $v")
            val type = o.str("type")
            if (type.isEmpty()) throw ProtocolException("BAD_MESSAGE", "missing type")
            val eventId = o["eventId"] as? String ?: throw ProtocolException("BAD_MESSAGE", "missing eventId")
            @Suppress("UNCHECKED_CAST")
            val payload = (o["payload"] as? MutableMap<String, Any?>) ?: LinkedHashMap()
            return Envelope(type, payload, eventId, o.str("sessionId"), o.str("sourceDeviceId"),
                o.str("targetDeviceId", "*"), o.long("seq"), o.long("ts"), v)
        }
    }
}

fun payloadOf(vararg pairs: Pair<String, Any?>): MutableMap<String, Any?> = linkedMapOf(*pairs)

/** FILE_CHUNK binary frame: headerLen:u32 BE ‖ header JSON ‖ data. */
object BinaryFrame {
    fun encode(header: Map<String, Any?>, data: ByteArray, offset: Int = 0, length: Int = data.size): ByteArray {
        val h = Json.write(header).toByteArray(Charsets.UTF_8)
        val buf = ByteBuffer.allocate(4 + h.size + length)
        buf.putInt(h.size); buf.put(h); buf.put(data, offset, length)
        return buf.array()
    }

    class Decoded(val header: Map<String, Any?>, val frame: ByteArray, val dataOffset: Int) {
        val dataLength: Int get() = frame.size - dataOffset
        fun data(): ByteArray = frame.copyOfRange(dataOffset, frame.size)
    }

    fun decode(frame: ByteArray): Decoded {
        if (frame.size < 4) throw ProtocolException("BAD_MESSAGE", "short frame")
        val len = ByteBuffer.wrap(frame, 0, 4).int
        if (len <= 0 || 4 + len > frame.size) throw ProtocolException("BAD_MESSAGE", "bad header length")
        val header = Json.parseObject(String(frame, 4, len, Charsets.UTF_8))
        return Decoded(header, frame, 4 + len)
    }
}

object Base64Url {
    fun encode(b: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(b)
    fun decode(s: String): ByteArray = Base64.getUrlDecoder().decode(s)
}

object Hex {
    private const val DIGITS = "0123456789abcdef"
    fun encode(b: ByteArray): String {
        val sb = StringBuilder(b.size * 2)
        for (x in b) { val v = x.toInt() and 0xff; sb.append(DIGITS[v shr 4]); sb.append(DIGITS[v and 0xf]) }
        return sb.toString()
    }
}
