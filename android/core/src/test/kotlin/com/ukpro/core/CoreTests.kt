package com.ukpro.core

import java.io.File
import java.security.SecureRandom

/**
 * Dependency-free test suite for the shared Kotlin core. Runs with plain `java -cp` (see
 * interop/run.sh) and from Gradle through CoreTestsJUnit. Pass the shared vectors dir as arg 0.
 */
object CoreTests {
    private var vectorsDir = File("../../shared/test-vectors")

    @JvmStatic
    fun main(args: Array<String>) {
        if (args.isNotEmpty()) vectorsDir = File(args[0])
        val failed = runAll()
        System.exit(if (failed == 0) 0 else 1)
    }

    fun runAll(vectors: File? = null): Int {
        if (vectors != null) vectorsDir = vectors
        val tests = listOf<Pair<String, () -> Unit>>(
            "json round-trip (Bangla, emoji, escapes, nesting)" to ::jsonRoundTrip,
            "json parses .NET-escaped output" to ::jsonDotNetEscapes,
            "envelope round-trip and version check" to ::envelope,
            "binary frame round-trip" to ::binaryFrame,
            "vectors: ECDH shared secret matches C#" to ::vectorEcdh,
            "vectors: pairing secret, confirms, verification number match C#" to ::vectorPairing,
            "vectors: session MACs and session key match C#" to ::vectorSession,
            "vectors: clipboard text/file hashes match C#" to ::vectorHashes,
            "vectors: 18 scripts (বাংলা, हिन्दी, اردو, 中文, 日本語, 한국어, emoji …) hash identically to C#" to ::vectorScripts,
            "reliable: in-order, duplicates, gaps" to ::reliableOrder,
            "reliable: ACK + resend after reconnect" to ::reliableResend,
            "reliable: stale keystrokes become NOOP" to ::reliableStale,
            "clipboard: loop prevention" to ::clipLoop,
            "clipboard: convergence" to ::clipConverge,
            "file: transfer with verification" to ::fileFull,
            "file: resume after interruption" to ::fileResume,
            "file: corruption detected" to ::fileCorrupt,
            "file: safe names" to ::safeNames,
            "pairing offer URI parsing" to ::offerParse,
            "keycodes: Android mapping & text rule" to ::keyCodes)
        var failed = 0
        for ((name, body) in tests) {
            val t0 = System.nanoTime()
            try { body(); println("  PASS  $name (${(System.nanoTime() - t0) / 1_000_000} ms)") }
            catch (e: Throwable) { failed++; println("  FAIL  $name\n        $e"); e.printStackTrace() }
        }
        println("\n${tests.size - failed}/${tests.size} passed")
        return failed
    }

    private fun check(c: Boolean, msg: String = "expected true") { if (!c) throw AssertionError(msg) }
    private fun <T> eq(expected: T, actual: T, what: String = "") { if (expected != actual) throw AssertionError("$what expected <$expected> got <$actual>") }
    private inline fun <reified T : Throwable> throws(f: () -> Unit) {
        try { f() } catch (e: Throwable) { if (e is T) return; throw AssertionError("expected ${T::class.java.simpleName}, got $e") }
        throw AssertionError("expected ${T::class.java.simpleName}")
    }

    private fun jsonRoundTrip() {
        val m = payloadOf("text" to "আমি বাংলায় লিখি\r\n\"q\" \\ 😀\t\u0001", "n" to 42L, "d" to 1.5, "b" to true,
            "z" to null, "l" to listOf(1L, "x", payloadOf("k" to "v")))
        val back = Json.parseObject(Json.write(m))
        eq(m["text"], back["text"], "text"); eq(42L, back["n"]); eq(1.5, back["d"]); eq(true, back["b"])
        check(back.containsKey("z") && back["z"] == null, "null")
        eq("v", ((back["l"] as List<*>)[2] as Map<*, *>)["k"])
        throws<JsonException> { Json.parse("{\"a\":") }
        throws<JsonException> { Json.parse("[1,2] x") }
    }

    private fun jsonDotNetEscapes() {
        val o = Json.parseObject("{\"t\":\"\\u0986\\u09AE\\u09BF \\uD83D\\uDE00\",\"s\":\"a\\/b\"}")
        eq("আমি 😀", o["t"]); eq("a/b", o["s"])
    }

    private fun envelope() {
        val e = Envelope(MessageTypes.TEXT_COMMIT, payloadOf("text" to "হ্যালো"), sourceDeviceId = "and-1", seq = 9)
        val p = Envelope.parse(e.toJson())
        eq("হ্যালো", p.str("text")); eq(9L, p.seq); eq(e.eventId, p.eventId)
        val ex = try { Envelope.parse("{\"v\":2,\"type\":\"PING\",\"eventId\":\"x\"}"); null } catch (x: ProtocolException) { x }
        eq("UNSUPPORTED_VERSION", ex?.code)
    }

    private fun binaryFrame() {
        val data = ByteArray(1000).also { SecureRandom().nextBytes(it) }
        val f = BinaryFrame.decode(BinaryFrame.encode(payloadOf("offset" to 5L), data))
        eq(5L, f.header.long("offset")); check(f.data().contentEquals(data), "data")
    }

    private val v: Map<String, Any?> by lazy { Json.parseObject(File(vectorsDir, "crypto-v1.json").readText()) }

    private fun vectorEcdh() {
        val zClient = PairingCrypto.sharedSecret(PairingCrypto.importPrivate(Base64Url.decode(v.str("clientD"))), v.str("serverPk"))
        val zServer = PairingCrypto.sharedSecret(PairingCrypto.importPrivate(Base64Url.decode(v.str("serverD"))), v.str("clientPk"))
        eq(v.str("z"), Base64Url.encode(zClient), "z (client side)")
        eq(v.str("z"), Base64Url.encode(zServer), "z (server side)")
    }

    private fun vectorPairing() {
        val secret = PairingCrypto.deriveSecret(Base64Url.decode(v.str("z")), v.str("code"), v.str("hubId"), v.str("clientId"))
        eq(v.str("secret"), Base64Url.encode(secret), "secret")
        val t = PairingCrypto.transcript(v.str("hubId"), v.str("clientId"), v.str("serverPk"), v.str("clientPk"), v.str("fp"))
        eq(v.str("transcript"), t, "transcript")
        eq(v.str("clientConfirm"), PairingCrypto.confirm(secret, "client-confirm", t), "client confirm")
        eq(v.str("serverConfirm"), PairingCrypto.confirm(secret, "server-confirm", t), "server confirm")
        eq(v.str("verification"), PairingCrypto.verificationNumber(t), "verification number")
        eq("7K3M9QXZ", PairingCrypto.normalizeCode("7k3m-9qxz")); eq("AB010", PairingCrypto.normalizeCode("ab0i-o"))
    }

    private fun vectorSession() {
        val secret = Base64Url.decode(v.str("secret"))
        eq(v.str("serverMac"), SessionAuth.serverMac(secret, v.str("nC"), v.str("nS"), v.str("fp")))
        eq(v.str("clientMac"), SessionAuth.clientMac(secret, v.str("nC"), v.str("nS"), v.str("fp")))
        eq(v.str("sessionKey"), Base64Url.encode(SessionAuth.sessionKey(secret, v.str("nC"), v.str("nS"))))
    }

    private fun vectorHashes() {
        eq(v.str("textHash"), ClipboardResolver.hashText(v.str("text")), "text hash")
        eq(v.str("filesHash"), ClipboardResolver.hashFiles(listOf(Triple("b.pdf", 10L, "aa"), Triple("a.docx", 20L, "bb"))), "files hash")
    }

    private fun vectorScripts() {
        val scripts = v.list("scripts").map { it as String }
        val hashes = v.list("scriptHashes").map { it as String }
        check(scripts.size >= 18, "scripts in vector file")
        for ((i, t) in scripts.withIndex()) {
            val text = t + "\r\nline 2"
            val back = Envelope.parse(Envelope(MessageTypes.CLIPBOARD_TEXT, payloadOf("text" to text)).toJson()).str("text")
            eq(text, back, "round-trip $t")
            eq(hashes[i], ClipboardResolver.hashText(back), "hash $t")
            eq(text, KeyCodes.textFor(text.replace("\r\n", " "), 0)?.let { text }, "typeable $t")
        }
    }

    private fun wire(e: Envelope) = Envelope.parse(e.toJson())
    private fun msg(tx: ReliableChannel, type: String = MessageTypes.TEXT_COMMIT, text: String = "x") =
        tx.stamp(Envelope(type, payloadOf("text" to text)))

    private fun reliableOrder() {
        val tx = ReliableChannel("A"); val rx = ReliableChannel("B")
        val ms = (1..5).map { wire(msg(tx, text = "$it")) }
        eq(1, rx.receive(ms[0]).size); eq(0, rx.receive(ms[0]).size, "dup")
        eq(0, rx.receive(ms[3]).size); eq(0, rx.receive(ms[2]).size)
        eq("2,3,4", rx.receive(ms[1]).joinToString(",") { it.str("text") })
        eq(1, rx.receive(ms[4]).size)
    }

    private fun reliableResend() {
        val tx = ReliableChannel("A")
        repeat(10) { msg(tx, MessageTypes.CLIPBOARD_TEXT) }
        tx.stamp(Envelope(MessageTypes.PING))
        eq(10, tx.outboxCount); tx.onAck(4); eq(6, tx.outboxCount)
        eq("8,9,10", tx.resendAfterReconnect(tx.localSessionId, 7).joinToString(",") { it.seq.toString() })
        eq(3, tx.resendAfterReconnect("other", 0).size)
    }

    private fun reliableStale() {
        var now = 1_000_000L
        val tx = ReliableChannel("A", clock = { now })
        msg(tx, MessageTypes.TEXT_COMMIT); msg(tx, MessageTypes.CLIPBOARD_TEXT)
        now += 11_000
        msg(tx, MessageTypes.KEY_DOWN)
        val r = tx.resendAfterReconnect(tx.localSessionId, 0)
        eq("NOOP,CLIPBOARD_TEXT,KEY_DOWN", r.joinToString(",") { it.type })
        val rx = ReliableChannel("B")
        eq("CLIPBOARD_TEXT,KEY_DOWN", r.flatMap { rx.receive(wire(it)) }.joinToString(",") { it.type })
    }

    private fun clipLoop() {
        val win = ClipboardResolver("win"); val phone = ClipboardResolver("phone")
        val s = phone.onLocalChange(ClipboardResolver.hashText("hello"))!!
        eq(ClipboardResolver.Decision.APPLY, win.onRemote(s))
        check(win.onLocalChange(ClipboardResolver.hashText("hello")) == null, "echo suppressed")
        eq(ClipboardResolver.Decision.IGNORE_DUPLICATE, win.onRemote(s))
    }

    private fun clipConverge() {
        val a = ClipboardResolver("and-1"); val b = ClipboardResolver("win-1")
        val sa = a.onLocalChange(ClipboardResolver.hashText("p"))!!
        val sb = b.onLocalChange(ClipboardResolver.hashText("l"))!!
        a.onRemote(sb); b.onRemote(sa)
        eq(a.current!!.clipId, b.current!!.clipId, "winner"); eq("win-1", a.current!!.origin)
    }

    private fun tmp(): File = File(System.getProperty("java.io.tmpdir"), "ukpro-kt-" + System.nanoTime()).also { it.mkdirs() }
    private fun randomFile(dir: File, name: String, size: Int) = File(dir, name).also { f ->
        f.writeBytes(ByteArray(size).also { SecureRandom().nextBytes(it) })
    }

    private fun pump(tx: FileSender, rx: FileReceiver, from: Long, limit: Int = Int.MAX_VALUE, corrupt: Boolean = false): Int {
        var n = 0
        for (fr in tx.frames(from)) {
            val d = BinaryFrame.decode(fr.bytes)
            if (corrupt) d.frame[d.dataOffset] = (d.frame[d.dataOffset].toInt() xor 0xff).toByte()
            rx.writeChunk(d.header.long("offset"), d.frame, d.dataOffset, d.dataLength)
            if (++n == limit) break
        }
        return n
    }

    private fun fileFull() {
        val dir = tmp(); val src = randomFile(dir, "photo.JPG", 3 * 1024 * 1024 + 7)
        val tx = FileSender(LocalFileSource(src), 256 * 1024)
        eq("image/jpeg", tx.source.mime)
        val rx = FileReceiver(tx.startPayload(), File(dir, "in"))
        eq(13, pump(tx, rx, 0))
        check(rx.complete().readBytes().contentEquals(src.readBytes()), "content")
    }

    private fun fileResume() {
        val dir = tmp(); val src = randomFile(dir, "movie.mp4", 2 * 1024 * 1024)
        val tx = FileSender(LocalFileSource(src), 64 * 1024)
        val rx1 = FileReceiver(tx.startPayload(), File(dir, "in"))
        pump(tx, rx1, 0, limit = 10); rx1.abort(false)
        val rx2 = FileReceiver(tx.startPayload(), File(dir, "in"))
        eq(10L * 64 * 1024, rx2.resumeFrom, "resume")
        pump(tx, rx2, rx2.resumeFrom)
        check(rx2.complete().readBytes().contentEquals(src.readBytes()), "content after resume")
    }

    private fun fileCorrupt() {
        val dir = tmp(); val src = randomFile(dir, "a.zip", 50_000)
        val tx = FileSender(LocalFileSource(src), 16 * 1024)
        val rx = FileReceiver(tx.startPayload(), File(dir, "in"))
        pump(tx, rx, 0, corrupt = true)
        throws<ProtocolException> { rx.complete() }
    }

    private fun safeNames() {
        eq("passwd", FileReceiver.safeFileName("../../etc/passwd"))
        eq("evil.exe", FileReceiver.safeFileName("C:\\Windows\\evil.exe"))
        eq("a_b.txt", FileReceiver.safeFileName("a:b.txt"))
        eq("file", FileReceiver.safeFileName(".."))
    }

    private fun offerParse() {
        val o = PairingOffer.parse("ukpro://pair?v=1&h=192.168.1.5%2C10.0.0.2&p=47821&fp=" + "ab".repeat(32) +
            "&id=win-1&n=My%20Laptop&c=ABCD1234&pk=BBBB")
        eq(listOf("192.168.1.5", "10.0.0.2"), o.hosts); eq("My Laptop", o.hubName); eq(47821, o.port)
        throws<IllegalArgumentException> { PairingOffer.parse("https://evil") }
    }

    private fun keyCodes() {
        eq(29, KeyCodes.codeToAndroid["KeyA"]); eq(142, KeyCodes.codeToAndroid["F12"]); eq(67, KeyCodes.codeToAndroid["Backspace"])
        eq("Enter", KeyCodes.androidToCode[66])
        eq("a", KeyCodes.textFor("a", 0)); eq("A", KeyCodes.textFor("A", KeyMods.SHIFT))
        eq(null, KeyCodes.textFor("c", KeyMods.CTRL)); eq("অ", KeyCodes.textFor("অ", 0)); eq(null, KeyCodes.textFor("\r", 0))
        eq(KeyCodes.META_CTRL_ON or KeyCodes.META_CTRL_LEFT_ON, KeyCodes.toAndroidMeta(KeyMods.CTRL))
    }
}
