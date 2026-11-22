package com.ukpro.core

/** A live transport link (Wi-Fi WebSocket or Bluetooth RFCOMM). Implementations must be thread-safe. */
interface Link {
    val transport: String
    fun sendText(text: String): Boolean
    fun sendBinary(data: ByteArray): Boolean
    fun close(reason: String)
    /** Bytes accepted but not yet written to the network (for backpressure). */
    fun queuedBytes(): Long = 0
}

/** What a phone remembers about a paired laptop. */
class TrustedHub(
    val hubId: String, val name: String, val secret: ByteArray, val fingerprint: String,
    val hosts: List<String>, val port: Int
)

interface SessionListener {
    fun onPaired(hub: TrustedHub) {}
    fun onAuthenticated(session: ClientSession) {}
    fun onMessage(session: ClientSession, env: Envelope) {}
    fun onBinary(session: ClientSession, frame: BinaryFrame.Decoded) {}
    fun onFailure(session: ClientSession, code: String, message: String) {}
    fun onRtt(session: ClientSession, ms: Long) {}
}

/**
 * Transport-agnostic client side of one phone ↔ laptop session (PROTOCOL §2, §4).
 * One instance per paired laptop; it survives disconnects and transport switches, so its
 * [ReliableChannel] guarantees exactly-once delivery when Wi-Fi drops and Bluetooth takes over.
 */
class ClientSession(
    val deviceId: String,
    val deviceName: String,
    private val listener: SessionListener,
    hub: TrustedHub? = null,
    sessionId: String? = null,
    private val clock: () -> Long = { System.currentTimeMillis() }
) {
    enum class State { IDLE, PAIRING, AUTHENTICATING, READY, FAILED }

    @Volatile var hub: TrustedHub? = hub
        private set
    val channel = ReliableChannel(deviceId, sessionId, clock)
    @Volatile var state = State.IDLE
        private set
    @Volatile var rttMs = -1L
        private set
    @Volatile var link: Link? = null
        private set

    private var offer: PairingOffer? = null
    private var pairSecret: ByteArray? = null
    private var pairTranscript = ""
    private var nonceC = ""
    private var pingN = 0L
    private val pingSent = HashMap<Long, Long>()
    private var lastPingAt = 0L
    private var missedPongs = 0

    /** Starts pairing over [link] using a scanned QR offer. The certificate must already be pinned to offer.fingerprint. */
    fun startPairing(link: Link, offer: PairingOffer, platform: String = "android") {
        this.link = link; this.offer = offer; state = State.PAIRING
        val kp = PairingCrypto.newKeyPair()
        val pk = PairingCrypto.exportPublic(kp.public)
        val z = PairingCrypto.sharedSecret(kp.private, offer.serverPublicKey)
        val secret = PairingCrypto.deriveSecret(z, offer.code, offer.hubId, deviceId)
        pairSecret = secret
        pairTranscript = PairingCrypto.transcript(offer.hubId, deviceId, offer.serverPublicKey, pk, offer.fingerprint)
        raw(MessageTypes.PAIR_REQUEST, payloadOf("deviceId" to deviceId, "name" to deviceName, "platform" to platform,
            "pk" to pk, "confirm" to PairingCrypto.confirm(secret, "client-confirm", pairTranscript)))
    }

    /**
     * Manual-code pairing without a QR code. [observedFingerprint] is the SHA-256 of the certificate the
     * TLS layer saw (trust-on-first-use); it is bound into the transcript, so a man-in-the-middle with a
     * different certificate makes the key confirmation fail. Both screens also show [verificationNumber].
     */
    fun startManualPairing(link: Link, host: String, port: Int, observedFingerprint: String, code: String) {
        this.link = link; state = State.PAIRING
        manual = arrayOf(host, port.toString(), observedFingerprint, code)
        raw(MessageTypes.PAIR_INFO, payloadOf())
    }

    private var manual: Array<String>? = null

    /** 6-digit number to compare on both screens in manual-code pairing. */
    fun verificationNumber(): String? = if (pairTranscript.isEmpty()) null else PairingCrypto.verificationNumber(pairTranscript)

    /** Authenticates over a fresh link to an already-paired laptop. */
    fun connect(link: Link) {
        if (hub == null) throw IllegalStateException("not paired")
        this.link = link; state = State.AUTHENTICATING
        nonceC = SessionAuth.newNonce()
        raw(MessageTypes.AUTH_HELLO, payloadOf("deviceId" to deviceId, "nonce" to nonceC,
            "sessionId" to channel.localSessionId, "peerSessionId" to (channel.peerSessionId ?: ""),
            "lastRecvSeq" to channel.lastDelivered))
    }

    fun onLinkClosed() {
        link = null
        if (state != State.FAILED) state = State.IDLE
    }

    val isReady: Boolean get() = state == State.READY && link != null

    /**
     * Sends a message. Reliable types are queued while offline and delivered after reconnect
     * (stale keystrokes are dropped, PROTOCOL §2.1). Returns true if written to a live link now.
     */
    fun send(type: String, payload: MutableMap<String, Any?> = LinkedHashMap(), target: String? = null): Boolean {
        val env = channel.stamp(Envelope(type, payload))
        env.targetDeviceId = target ?: hub?.hubId ?: "*"
        val l = link
        return if (state == State.READY && l != null) l.sendText(env.toJson()) else false
    }

    fun sendBinary(frame: ByteArray): Boolean {
        val l = link
        return state == State.READY && l != null && l.sendBinary(frame)
    }

    fun onText(text: String) {
        val env = try { Envelope.parse(text) } catch (e: ProtocolException) {
            raw(MessageTypes.ERROR, payloadOf("code" to e.code, "message" to e.message)); return
        }
        when (state) {
            State.PAIRING -> handlePairing(env)
            State.AUTHENTICATING -> handleAuth(env)
            State.READY -> handleReady(env)
            else -> {}
        }
    }

    fun onBinary(data: ByteArray) {
        if (state != State.READY) return
        listener.onBinary(this, BinaryFrame.decode(data))
    }

    /** Call every ~50 ms: flushes ACKs, sends keep-alive pings, detects dead links. */
    fun tick() {
        val l = link ?: return
        if (state != State.READY) return
        val ack = channel.takeAck()
        if (ack >= 0) raw(MessageTypes.ACK, payloadOf("upTo" to ack))
        val now = clock()
        if (now - lastPingAt >= PING_INTERVAL_MS) {
            lastPingAt = now
            if (++missedPongs > 3) { l.close("timeout"); onLinkClosed(); return }
            val n = ++pingN
            synchronized(pingSent) { pingSent[n] = now; pingSent.keys.removeAll { it < n - 10 } }
            raw(MessageTypes.PING, payloadOf("n" to n))
        }
    }

    private fun handlePairing(env: Envelope) {
        val m = manual
        if (env.type == MessageTypes.PAIR_INFO && m != null) {
            val pk = env.str("pk")
            if (pk.isEmpty()) { fail("PAIR_REJECTED", env.str("reason", "NO_ACTIVE_OFFER")); return }
            manual = null
            val l = link ?: return
            startPairing(l, PairingOffer(listOf(m[0]), m[1].toInt(), m[2], env.str("hubId"), env.str("name"), m[3], pk))
            return
        }
        val o = offer ?: return
        when (env.type) {
            MessageTypes.PAIR_ACCEPTED -> {
                val secret = pairSecret!!
                if (!PairingCrypto.fixedEquals(env.str("confirm"), PairingCrypto.confirm(secret, "server-confirm", pairTranscript))) {
                    fail("PAIR_BAD_CONFIRM", "laptop could not prove it knows the pairing code"); return
                }
                val h = TrustedHub(o.hubId, env.str("name", o.hubName), secret, o.fingerprint, o.hosts, o.port)
                hub = h; offer = null; pairSecret = null
                listener.onPaired(h)
                link?.let { connect(it) } // continue with AUTH on the same connection
            }
            MessageTypes.PAIR_REJECTED -> fail("PAIR_REJECTED", env.str("reason"))
            MessageTypes.ERROR -> fail(env.str("code"), env.str("message"))
        }
    }

    private fun handleAuth(env: Envelope) {
        val h = hub ?: return
        when (env.type) {
            MessageTypes.AUTH_CHALLENGE -> {
                val nS = env.str("nonce")
                if (env.str("deviceId") != h.hubId ||
                    !PairingCrypto.fixedEquals(env.str("mac"), SessionAuth.serverMac(h.secret, nonceC, nS, h.fingerprint))) {
                    fail("AUTH_BAD_SERVER", "laptop failed authentication"); return
                }
                challengeSession = env.str("sessionId")
                challengePeerSession = env.str("peerSessionId")
                challengeLastRecv = env.long("lastRecvSeq")
                raw(MessageTypes.AUTH_RESPONSE, payloadOf("mac" to SessionAuth.clientMac(h.secret, nonceC, nS, h.fingerprint)))
            }
            MessageTypes.AUTH_OK -> {
                channel.beginPeerSession(challengeSession)
                state = State.READY
                missedPongs = 0; lastPingAt = clock()
                val l = link ?: return
                for (e in channel.resendAfterReconnect(challengePeerSession, challengeLastRecv)) l.sendText(e.toJson())
                listener.onAuthenticated(this)
            }
            MessageTypes.AUTH_FAIL -> fail("AUTH_FAIL", env.str("reason"))
            MessageTypes.ERROR -> fail(env.str("code"), env.str("message"))
        }
    }

    private var challengeSession = ""
    private var challengePeerSession = ""
    private var challengeLastRecv = 0L

    private fun handleReady(env: Envelope) {
        missedPongs = 0
        when (env.type) {
            MessageTypes.ACK -> { channel.onAck(env.long("upTo")); return }
            MessageTypes.PING -> { raw(MessageTypes.PONG, payloadOf("n" to env.long("n"))); return }
            MessageTypes.PONG -> {
                val sent = synchronized(pingSent) { pingSent.remove(env.long("n")) }
                if (sent != null) { rttMs = clock() - sent; listener.onRtt(this, rttMs) }
                return
            }
        }
        val h = hub
        if (h != null && env.sourceDeviceId != h.hubId) return
        val deliver = try { channel.receive(env) } catch (e: ProtocolException) {
            link?.close("resync"); onLinkClosed(); return
        }
        for (d in deliver) listener.onMessage(this, d)
        val ack = channel.takeAck()
        if (ack >= 0) raw(MessageTypes.ACK, payloadOf("upTo" to ack))
    }

    private fun fail(code: String, msg: String) {
        state = State.FAILED
        listener.onFailure(this, code, msg)
        link?.close(code)
        link = null
    }

    private fun raw(type: String, payload: MutableMap<String, Any?>) {
        val e = Envelope(type, payload, sourceDeviceId = deviceId, targetDeviceId = hub?.hubId ?: offer?.hubId ?: "*")
        link?.sendText(e.toJson())
    }

    companion object {
        const val PING_INTERVAL_MS = 5_000L
    }
}
