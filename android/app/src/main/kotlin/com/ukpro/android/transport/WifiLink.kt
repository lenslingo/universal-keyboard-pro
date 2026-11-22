package com.ukpro.android.transport

import com.ukpro.core.Hex
import com.ukpro.core.Link
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString

interface LinkEvents {
    fun onOpen(link: WifiLink)
    fun onText(text: String)
    fun onBinary(bytes: ByteArray)
    fun onClosed(link: WifiLink, reason: String)
}

/**
 * Wi-Fi transport: WebSocket over TLS (OkHttp) with SHA-256 certificate pinning (PROTOCOL §1, §4).
 * Host-name verification is replaced by the pin: the laptop's self-signed certificate is accepted
 * only if its fingerprint equals the one from the pairing QR (or, for manual pairing, the one
 * observed on first use, which is then bound into the pairing transcript).
 */
class WifiLink private constructor(val host: String) : Link {
    override val transport = "wifi"
    @Volatile private var ws: WebSocket? = null
    @Volatile var observedFingerprint: String? = null
        private set

    override fun sendText(text: String): Boolean = ws?.send(text) ?: false
    override fun sendBinary(data: ByteArray): Boolean = ws?.send(data.toByteString()) ?: false
    override fun close(reason: String) { ws?.close(1000, reason.take(100)); ws = null }
    override fun queuedBytes(): Long = ws?.queueSize() ?: 0

    companion object {
        /** @param pin expected fingerprint, or null for trust-on-first-use (manual pairing only). */
        fun connect(host: String, port: Int, pin: String?, events: LinkEvents): WifiLink {
            val link = WifiLink(host)
            val tm = object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String) = throw CertificateException()
                override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String) {
                    val fp = Hex.encode(MessageDigest.getInstance("SHA-256").digest(chain[0].encoded))
                    link.observedFingerprint = fp
                    if (pin != null && fp != pin) throw CertificateException("UK Pro: certificate does not match the paired laptop")
                }
                override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
            }
            val ssl = SSLContext.getInstance("TLS").apply { init(null, arrayOf(tm), SecureRandom()) }
            val client = OkHttpClient.Builder()
                .sslSocketFactory(ssl.socketFactory, tm)
                .hostnameVerifier { _, _ -> true } // identity is enforced by the pin above
                .connectTimeout(4, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.MILLISECONDS)
                .retryOnConnectionFailure(false)
                .build()
            val hostPart = if (host.contains(':')) "[$host]" else host
            val req = Request.Builder().url("https://$hostPart:$port/ukpro/v1").build()
            link.ws = client.newWebSocket(req, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) { link.ws = webSocket; events.onOpen(link) }
                override fun onMessage(webSocket: WebSocket, text: String) = events.onText(text)
                override fun onMessage(webSocket: WebSocket, bytes: ByteString) = events.onBinary(bytes.toByteArray())
                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(1000, null) }
                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { link.ws = null; events.onClosed(link, reason) }
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    link.ws = null; events.onClosed(link, t.message ?: t.javaClass.simpleName)
                }
            })
            return link
        }
    }
}
