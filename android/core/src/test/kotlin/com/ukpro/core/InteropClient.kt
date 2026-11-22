package com.ukpro.core

import java.io.File
import java.net.Socket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.WebSocket
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.CompletionStage
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.X509ExtendedTrustManager

/**
 * Kotlin (phone-side core) ↔ C# (Windows hub) interoperability test over real TLS + WebSocket.
 * The JDK WebSocket stands in for OkHttp; everything above the Link is the code the APK ships.
 */
object InteropClient {
    private val inbox = LinkedBlockingQueue<Envelope>()
    private var failures = 0
    private val dispatch = Executors.newSingleThreadExecutor()

    @JvmStatic
    fun main(args: Array<String>) {
        val uri = File(args[0]).readText().trim()
        val offer = PairingOffer.parse(uri)
        val authed = CountDownLatch(1)
        lateinit var transfers: ClientTransfers
        val dl = File(System.getProperty("java.io.tmpdir"), "ukpro-interop-phone-" + System.nanoTime())
        val listener = object : SessionListener {
            override fun onPaired(hub: TrustedHub) = println("  [phone] paired with ${hub.name}")
            override fun onAuthenticated(session: ClientSession) = authed.countDown()
            override fun onMessage(session: ClientSession, env: Envelope) { if (!transfers.onMessage(env)) inbox.add(env) }
            override fun onBinary(session: ClientSession, frame: BinaryFrame.Decoded) { transfers.onBinary(frame) }
            override fun onFailure(session: ClientSession, code: String, message: String) = println("  [phone] failure $code $message")
        }
        val session = ClientSession("and-interop-phone", "Interop Phone", listener)
        transfers = ClientTransfers(session, { dl })
        val ticker = Executors.newSingleThreadScheduledExecutor()
        ticker.scheduleAtFixedRate({ dispatch.execute { session.tick() } }, 50, 50, TimeUnit.MILLISECONDS)

        step("pair over TLS with pinned certificate + mutual auth") {
            val link = JdkWsLink.open(offer.hosts[0], offer.port, offer.fingerprint, session)
            session.startPairing(link, offer)
            check(authed.await(10, TimeUnit.SECONDS), "not authenticated")
            check(session.hub!!.secret.size == 32, "secret")
        }
        step("wrong certificate fingerprint is refused") {
            val bad = try { JdkWsLink.open(offer.hosts[0], offer.port, "00".repeat(32), session); "connected" } catch (e: Exception) { "refused" }
            check(bad == "refused", "pinning not enforced")
        }
        step("TEXT_COMMIT in every script (Bangla, Hindi, Urdu, Arabic, CJK, Korean, Thai, emoji …) round-trip") {
            val text = "আমি বাংলায় লিখি — UK Pro 😀\nনতুন লাইন | नमस्ते | خوش آمدید | مرحبا | Привет | Γειά | 你好 | こんにちは | 안녕하세요 | สวัสดี | வணக்கம் | 👨‍👩‍👧‍👦🇧🇩 | 𠜎"
            session.send(MessageTypes.TEXT_COMMIT, payloadOf("text" to text))
            check(next(MessageTypes.TEXT_COMMIT).str("text") == text, "text mismatch")
        }
        step("KEY_DOWN Ctrl+C round-trip") {
            session.send(MessageTypes.KEY_DOWN, payloadOf("code" to "KeyC", "key" to "c", "mods" to KeyMods.CTRL.toLong(), "repeat" to false))
            val e = next(MessageTypes.KEY_DOWN)
            check(e.str("code") == "KeyC" && e.long("mods") == 1L, "key mismatch")
        }
        step("touchpad stream: MOUSE_MOVE ×200 + click accepted by the hub") {
            repeat(200) { session.send(MessageTypes.MOUSE_MOVE, payloadOf("dx" to 3L, "dy" to -2L)) }
            session.send(MessageTypes.MOUSE_BUTTON, payloadOf("button" to "left", "action" to "click"))
            check(next(MessageTypes.MOUSE_BUTTON).str("action") == "click", "click echo")
            check(session.channel.outboxCount <= 2, "moves must not be queued")
        }
        step("clipboard: hub verifies hash, then rejects duplicate clipId") {
            val resolver = ClipboardResolver(session.deviceId)
            val text = "কপি করা লেখা\r\nline 2"
            val st = resolver.onLocalChange(ClipboardResolver.hashText(text))!!
            val p = st.toPayload(); p["text"] = text; p["mime"] = "text/plain"
            session.send(MessageTypes.CLIPBOARD_TEXT, p)
            val r1 = next(MessageTypes.SYNC_CONFIG)
            check(r1.str("clipDecision") == "Apply" && r1.bool("hashOk"), "first clip: ${r1.payload}")
            session.send(MessageTypes.CLIPBOARD_TEXT, LinkedHashMap(p))
            check(next(MessageTypes.SYNC_CONFIG).str("clipDecision") == "IgnoreDuplicate", "duplicate not detected")
        }
        step("file 3 MiB phone → laptop (SHA-256 verified), then laptop → phone") {
            val src = File(dl, "src").also { it.mkdirs() }.let { File(it, "রিপোর্ট.pdf") }
            src.writeBytes(ByteArray(3 * 1024 * 1024 + 11).also { SecureRandom().nextBytes(it) })
            val t0 = System.nanoTime()
            val up = transfers.sendFile(LocalFileSource(src), 256 * 1024)
            val upMs = (System.nanoTime() - t0) / 1_000_000
            check(up.state == TransferProgress.State.COMPLETED, "upload ${up.state} ${up.error}")
            println("        upload ${"%.1f".format(src.length() / 1048576.0 / (upMs / 1000.0))} MiB/s over loopback")
            val deadline = System.currentTimeMillis() + 15_000
            var back: File? = null
            while (back == null && System.currentTimeMillis() < deadline) {
                back = dl.listFiles()?.firstOrNull { it.isFile && it.name.startsWith("রিপোর্ট") && !it.name.endsWith(".ukpart") && !it.name.endsWith(".json") }
                Thread.sleep(100)
            }
            check(back != null, "file did not come back")
            check(back!!.readBytes().contentEquals(src.readBytes()), "returned file differs")
        }
        step("offline message is delivered exactly once after reconnect") {
            session.link!!.close("simulated Wi-Fi loss"); session.onLinkClosed()
            Thread.sleep(300)
            session.send(MessageTypes.CLIPBOARD_TEXT, payloadOf("clipId" to "offline-1", "hash" to ClipboardResolver.hashText("x"),
                "lamport" to 99L, "origin" to session.deviceId, "text" to "x"))
            val link = JdkWsLink.open(offer.hosts[0], offer.port, offer.fingerprint, session)
            session.connect(link)
            val deadline = System.currentTimeMillis() + 5000
            while (!session.isReady && System.currentTimeMillis() < deadline) Thread.sleep(20)
            check(session.isReady, "reconnect failed")
            check(next(MessageTypes.SYNC_CONFIG).str("clipDecision") == "Apply", "offline clip not applied")
            val dup = inbox.poll(1500, TimeUnit.MILLISECONDS)
            check(dup == null || dup.type != MessageTypes.SYNC_CONFIG, "duplicate delivery after reconnect")
        }
        step("keyboard round-trip latency (100 × TEXT_COMMIT echo)") {
            val samples = ArrayList<Long>()
            repeat(100) { i ->
                val t0 = System.nanoTime()
                session.send(MessageTypes.TEXT_COMMIT, payloadOf("text" to "k$i"))
                check(next(MessageTypes.TEXT_COMMIT).str("text") == "k$i", "order")
                samples.add((System.nanoTime() - t0) / 1000)
            }
            samples.sort()
            println("        RTT median ${samples[50] / 1000.0} ms, p95 ${samples[95] / 1000.0} ms (loopback, includes JSON + TLS + echo)")
        }
        step("manual-code pairing (no QR): PAIR_INFO + TOFU fingerprint + verification number") {
            session.send(MessageTypes.REMOTE_ACTION, payloadOf("action" to "INTEROP_NEW_OFFER"))
            val code = next(MessageTypes.SYNC_CONFIG).str("code")
            check(code.length == 9, "no code")
            val ok = CountDownLatch(1)
            val second = ClientSession("and-interop-manual", "Second Phone", object : SessionListener {
                override fun onAuthenticated(session: ClientSession) = ok.countDown()
                override fun onFailure(session: ClientSession, code: String, message: String) = println("  [phone2] $code $message")
            })
            val tm = PinningTrustManager("*")
            val link = JdkWsLink.open(offer.hosts[0], offer.port, "*", second, tm)
            second.startManualPairing(link, offer.hosts[0], offer.port, tm.observed!!, code)
            check(ok.await(10, TimeUnit.SECONDS), "manual pairing failed")
            check(tm.observed == offer.fingerprint, "observed fingerprint differs")
            println("        verification number on phone: ${second.verificationNumber()}")
        }
        session.send(MessageTypes.REMOTE_ACTION, payloadOf("action" to "INTEROP_STOP"))
        Thread.sleep(300)
        println(if (failures == 0) "\nINTEROP: ALL PASSED" else "\nINTEROP: $failures FAILED")
        System.exit(if (failures == 0) 0 else 1)
    }

    private fun next(type: String, timeoutMs: Long = 5000): Envelope {
        val until = System.currentTimeMillis() + timeoutMs
        while (true) {
            val left = until - System.currentTimeMillis()
            if (left <= 0) throw AssertionError("timeout waiting for $type")
            val e = inbox.poll(left, TimeUnit.MILLISECONDS) ?: continue
            if (e.type == type) return e
        }
    }

    private fun check(c: Boolean, msg: String) { if (!c) throw AssertionError(msg) }

    private fun step(name: String, body: () -> Unit) {
        try { body(); println("  PASS  $name") } catch (e: Throwable) { failures++; println("  FAIL  $name: $e") }
    }

    /** java.net.http WebSocket as a [Link], with SHA-256 certificate pinning. */
    class JdkWsLink private constructor(private val ws: WebSocket) : Link {
        override val transport = "wifi"
        @Synchronized override fun sendText(text: String): Boolean = try { ws.sendText(text, true).join(); true } catch (e: Exception) { false }
        @Synchronized override fun sendBinary(data: ByteArray): Boolean = try { ws.sendBinary(ByteBuffer.wrap(data), true).join(); true } catch (e: Exception) { false }
        override fun close(reason: String) { try { ws.abort() } catch (e: Exception) { } }

        companion object {
            fun open(host: String, port: Int, fp: String, session: ClientSession, tm: PinningTrustManager = PinningTrustManager(fp)): JdkWsLink {
                val ssl = SSLContext.getInstance("TLS")
                ssl.init(null, arrayOf(tm), SecureRandom())
                val client = HttpClient.newBuilder().sslContext(ssl).build()
                val text = StringBuilder()
                var bin = java.io.ByteArrayOutputStream()
                val listener = object : WebSocket.Listener {
                    override fun onText(w: WebSocket, data: CharSequence, last: Boolean): CompletionStage<*>? {
                        text.append(data)
                        if (last) { val s = text.toString(); text.setLength(0); dispatch.execute { session.onText(s) } }
                        w.request(1); return null
                    }
                    override fun onBinary(w: WebSocket, data: ByteBuffer, last: Boolean): CompletionStage<*>? {
                        val b = ByteArray(data.remaining()); data.get(b); bin.write(b)
                        if (last) { val all = bin.toByteArray(); bin = java.io.ByteArrayOutputStream(); dispatch.execute { session.onBinary(all) } }
                        w.request(1); return null
                    }
                    override fun onClose(w: WebSocket, statusCode: Int, reason: String): CompletionStage<*>? {
                        dispatch.execute { session.onLinkClosed() }; return null
                    }
                }
                val ws = client.newWebSocketBuilder().connectTimeout(java.time.Duration.ofSeconds(5))
                    .buildAsync(URI.create("wss://$host:$port/ukpro/v1"), listener).get(10, TimeUnit.SECONDS)
                return JdkWsLink(ws)
            }
        }
    }

    /** Pins to [fp]; with "*" it accepts any certificate once and records it (trust-on-first-use). */
    class PinningTrustManager(private val fp: String) : X509ExtendedTrustManager() {
        @Volatile var observed: String? = null
        private fun pin(chain: Array<out X509Certificate>) {
            val actual = Hex.encode(MessageDigest.getInstance("SHA-256").digest(chain[0].encoded))
            observed = actual
            if (fp != "*" && actual != fp) throw CertificateException("certificate pin mismatch")
        }
        override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String) = pin(chain)
        override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String, socket: Socket?) = pin(chain)
        override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String, engine: SSLEngine?) = pin(chain)
        override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String) = throw CertificateException("server only")
        override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String, socket: Socket?) = throw CertificateException("server only")
        override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String, engine: SSLEngine?) = throw CertificateException("server only")
        override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
    }
}
