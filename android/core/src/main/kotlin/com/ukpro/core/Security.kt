package com.ukpro.core

import java.math.BigInteger
import java.net.URLDecoder
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPrivateKeySpec
import java.security.spec.ECPublicKeySpec
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Mirrors PairingCrypto in Security.cs (PROTOCOL §4.1). */
object PairingCrypto {
    private const val CROCKFORD = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
    private val random = SecureRandom()

    private val p256: ECParameterSpec by lazy {
        val ap = AlgorithmParameters.getInstance("EC")
        ap.init(ECGenParameterSpec("secp256r1"))
        ap.getParameterSpec(ECParameterSpec::class.java)
    }

    fun newKeyPair(): KeyPair {
        val g = KeyPairGenerator.getInstance("EC")
        g.initialize(ECGenParameterSpec("secp256r1"), random)
        return g.generateKeyPair()
    }

    fun exportPublic(pub: PublicKey): String {
        val w = (pub as ECPublicKey).w
        val raw = ByteArray(65)
        raw[0] = 4
        fixed32(w.affineX).copyInto(raw, 1)
        fixed32(w.affineY).copyInto(raw, 33)
        return Base64Url.encode(raw)
    }

    fun importPublic(b64: String): PublicKey {
        val raw = Base64Url.decode(b64)
        if (raw.size != 65 || raw[0].toInt() != 4) throw IllegalArgumentException("bad public key")
        val pt = ECPoint(BigInteger(1, raw.copyOfRange(1, 33)), BigInteger(1, raw.copyOfRange(33, 65)))
        return KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(pt, p256))
    }

    /** Test vectors only. */
    fun importPrivate(d: ByteArray): PrivateKey =
        KeyFactory.getInstance("EC").generatePrivate(ECPrivateKeySpec(BigInteger(1, d), p256))

    fun sharedSecret(priv: PrivateKey, peerPublicB64: String): ByteArray {
        val ka = KeyAgreement.getInstance("ECDH")
        ka.init(priv)
        ka.doPhase(importPublic(peerPublicB64), true)
        val z = ka.generateSecret()
        return if (z.size == 32) z else fixed32(BigInteger(1, z))
    }

    fun deriveSecret(z: ByteArray, code: String, hubId: String, clientId: String): ByteArray =
        Hkdf.sha256(z, utf8(normalizeCode(code)), utf8("UKPro pair v1|$hubId|$clientId"), 32)

    fun transcript(hubId: String, clientId: String, serverPk: String, clientPk: String, fp: String) =
        "$hubId|$clientId|$serverPk|$clientPk|$fp"

    fun confirm(secret: ByteArray, label: String, transcript: String): String =
        Base64Url.encode(hmac(secret, utf8("$label|$transcript")))

    fun verificationNumber(transcript: String): String {
        val h = MessageDigest.getInstance("SHA-256").digest(utf8(transcript))
        val v = ((h[0].toInt() and 0xff) shl 12) or ((h[1].toInt() and 0xff) shl 4) or ((h[2].toInt() and 0xff) shr 4)
        return String.format("%06d", v % 1_000_000)
    }

    fun newCode(): String {
        val sb = StringBuilder()
        repeat(8) { sb.append(CROCKFORD[random.nextInt(32)]) }
        return sb.toString()
    }

    fun normalizeCode(code: String): String {
        val sb = StringBuilder()
        for (ch0 in code) {
            val ch = if (ch0 in 'a'..'z') 'A' + (ch0 - 'a') else ch0
            val x = when (ch) { 'I', 'L' -> '1'; 'O' -> '0'; else -> ch }
            if (CROCKFORD.indexOf(x) >= 0) sb.append(x)
        }
        return sb.toString()
    }

    fun fixedEquals(a: String, b: String): Boolean = MessageDigest.isEqual(utf8(a), utf8(b))

    private fun fixed32(v: BigInteger): ByteArray {
        val b = v.toByteArray()
        return when {
            b.size == 32 -> b
            b.size == 33 && b[0].toInt() == 0 -> b.copyOfRange(1, 33)
            b.size < 32 -> ByteArray(32 - b.size) + b
            else -> throw IllegalArgumentException("coordinate too large")
        }
    }
}

/** Mirrors SessionAuth in Security.cs (PROTOCOL §4.2, §4.3). */
object SessionAuth {
    private val random = SecureRandom()
    fun newNonce(): String = ByteArray(32).also { random.nextBytes(it) }.let { Base64Url.encode(it) }
    fun serverMac(secret: ByteArray, nC: String, nS: String, fp: String) = Base64Url.encode(hmac(secret, utf8("S|$nC|$nS|$fp")))
    fun clientMac(secret: ByteArray, nC: String, nS: String, fp: String) = Base64Url.encode(hmac(secret, utf8("C|$nC|$nS|$fp")))
    fun sessionKey(secret: ByteArray, nC: String, nS: String): ByteArray =
        Hkdf.sha256(secret, Base64Url.decode(nC) + Base64Url.decode(nS), utf8("UKPro session v1"), 32)
}

object Hkdf {
    fun sha256(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
        val prk = hmac(if (salt.isEmpty()) ByteArray(32) else salt, ikm)
        val out = ByteArray(length)
        var t = ByteArray(0)
        var pos = 0
        var counter = 1
        while (pos < length) {
            t = hmac(prk, t + info + byteArrayOf(counter.toByte()))
            val n = Math.min(t.size, length - pos)
            t.copyInto(out, pos, 0, n)
            pos += n; counter++
        }
        return out
    }
}

internal fun hmac(key: ByteArray, data: ByteArray): ByteArray {
    val m = Mac.getInstance("HmacSHA256")
    m.init(SecretKeySpec(key, "HmacSHA256"))
    return m.doFinal(data)
}

internal fun utf8(s: String) = s.toByteArray(Charsets.UTF_8)

fun sha256Hex(b: ByteArray): String = Hex.encode(MessageDigest.getInstance("SHA-256").digest(b))

/** Parsed `ukpro://pair?...` QR payload. */
class PairingOffer(
    val hosts: List<String>, val port: Int, val fingerprint: String, val hubId: String,
    val hubName: String, val code: String, val serverPublicKey: String
) {
    companion object {
        fun parse(uri: String): PairingOffer {
            if (!uri.startsWith("ukpro://pair?")) throw IllegalArgumentException("not a UK Pro pairing code")
            val q = HashMap<String, String>()
            for (part in uri.substringAfter('?').split('&')) {
                val k = part.substringBefore('=')
                q[k] = URLDecoder.decode(part.substringAfter('=', ""), "UTF-8")
            }
            if (q["v"] != "1") throw IllegalArgumentException("unsupported pairing version")
            val fp = q["fp"] ?: ""
            if (fp.length != 64) throw IllegalArgumentException("bad fingerprint")
            return PairingOffer(
                (q["h"] ?: "").split(',').filter { it.isNotBlank() }, (q["p"] ?: "47821").toInt(), fp,
                q["id"] ?: throw IllegalArgumentException("missing id"), q["n"] ?: "Windows",
                q["c"] ?: throw IllegalArgumentException("missing code"),
                q["pk"] ?: throw IllegalArgumentException("missing key"))
        }
    }
}
