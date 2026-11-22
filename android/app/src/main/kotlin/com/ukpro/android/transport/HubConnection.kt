package com.ukpro.android.transport

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import com.ukpro.core.BinaryFrame
import com.ukpro.core.ClientSession
import com.ukpro.core.ClientTransfers
import com.ukpro.core.Envelope
import com.ukpro.core.PairingOffer
import com.ukpro.core.SessionListener
import com.ukpro.core.TransferProgress
import com.ukpro.core.TrustedHub
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/** Callbacks from a connection to the app runtime (invoked on the connection's own thread). */
interface HubEvents {
    fun onPaired(conn: HubConnection, hub: TrustedHub)
    fun onConnected(conn: HubConnection)
    fun onDisconnected(conn: HubConnection, reason: String)
    fun onMessage(conn: HubConnection, env: Envelope)
    fun onTransfer(conn: HubConnection, p: TransferProgress)
    fun onFailure(conn: HubConnection, code: String, message: String)
}

/**
 * Keeps one phone ↔ laptop session alive: tries every known address, reconnects with back-off,
 * reacts to network changes, and serialises all session work on one thread. The [ClientSession]
 * outlives individual links, so queued events are delivered exactly once after a reconnect.
 */
class HubConnection private constructor(
    private val context: Context,
    deviceId: String,
    deviceName: String,
    hub: TrustedHub?,
    private val events: HubEvents,
) {
    private val exec = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "ukpro-hub").apply { isDaemon = true } }
    private var retry: ScheduledFuture<*>? = null
    private var backoffMs = 1000L
    @Volatile var running = false
        private set
    @Volatile var lastError: String? = null
        private set
    @Volatile private var connecting = false
    private var pendingPair: (() -> Unit)? = null
    /** Addresses learned from mDNS/NSD in addition to the remembered ones. */
    val discoveredHosts: MutableSet<String> = ConcurrentHashMap.newKeySet()

    val session: ClientSession = ClientSession(deviceId, deviceName, object : SessionListener {
        override fun onPaired(hub: TrustedHub) = events.onPaired(this@HubConnection, hub)
        override fun onAuthenticated(session: ClientSession) { backoffMs = 1000; lastError = null; events.onConnected(this@HubConnection) }
        override fun onMessage(session: ClientSession, env: Envelope) {
            if (!transfers.onMessage(env)) events.onMessage(this@HubConnection, env)
        }
        override fun onBinary(session: ClientSession, frame: BinaryFrame.Decoded) { transfers.onBinary(frame) }
        override fun onFailure(session: ClientSession, code: String, message: String) {
            lastError = "$code: $message"; events.onFailure(this@HubConnection, code, message)
        }
    }, hub)

    val transfers: ClientTransfers = ClientTransfers(session,
        { p -> File(context.cacheDir, if (p.purpose == "file") "incoming" else "clipboard").apply { mkdirs() } },
        { p -> events.onTransfer(this, p) })

    val hubId: String? get() = session.hub?.hubId
    val name: String get() = session.hub?.name ?: "Laptop"
    val isConnected: Boolean get() = session.isReady
    val transportName: String get() = session.link?.transport ?: "—"

    fun start() {
        if (running) return
        running = true
        exec.scheduleAtFixedRate(Runnable { runCatching { session.tick() } }, 50, 50, TimeUnit.MILLISECONDS)
        exec.execute { connectNow() }
    }

    fun stop() {
        running = false
        exec.execute { retry?.cancel(false); session.link?.close("stopped"); session.onLinkClosed() }
    }

    fun shutdown() { stop(); exec.shutdown() }

    /** Network came back / changed: retry immediately instead of waiting for the back-off. */
    fun kick() = exec.execute { backoffMs = 1000; if (running && !session.isReady && !connecting) { retry?.cancel(false); connectNow() } }

    fun post(r: () -> Unit) = exec.execute(r)

    private fun candidates(): List<Pair<String, Int>> {
        val h = session.hub
        val port = h?.port ?: 47821
        return (discoveredHosts + (h?.hosts ?: emptyList())).distinct().map { it to port }
    }

    private fun connectNow() {
        if (!running || session.isReady || connecting) return
        val hub = session.hub
        if (hub == null) { pendingPair?.invoke(); return }
        val list = candidates()
        if (list.isEmpty()) { scheduleRetry("no address — waiting for discovery"); return }
        tryHost(list, 0, hub)
    }

    private fun tryHost(list: List<Pair<String, Int>>, i: Int, hub: TrustedHub) {
        if (i >= list.size) { scheduleRetry("laptop not reachable"); return }
        connecting = true
        val (host, port) = list[i]
        var opened = false
        var abandoned = false
        lateinit var attempt: WifiLink
        // runs on exec, so `attempt` is always assigned before it can fire
        val timeout = exec.schedule(Runnable {
            if (!opened) { abandoned = true; attempt.close("timeout"); connecting = false; tryHost(list, i + 1, hub) }
        }, 5, TimeUnit.SECONDS)
        attempt = WifiLink.connect(host, port, hub.fingerprint, object : LinkEvents {
            override fun onOpen(link: WifiLink) = exec.execute {
                if (abandoned) { link.close("late"); return@execute }
                opened = true; timeout.cancel(false); connecting = false
                session.connect(link)
            }
            override fun onText(text: String) = exec.execute { if (!abandoned) session.onText(text) }
            override fun onBinary(bytes: ByteArray) = exec.execute { if (!abandoned) session.onBinary(bytes) }
            override fun onClosed(link: WifiLink, reason: String) = exec.execute {
                if (abandoned) return@execute
                if (!opened) { timeout.cancel(false); connecting = false; tryHost(list, i + 1, hub); return@execute }
                if (session.link === link) { session.onLinkClosed(); events.onDisconnected(this@HubConnection, reason) }
                scheduleRetry(reason)
            }
        })
    }

    private fun scheduleRetry(reason: String) {
        if (!running) return
        lastError = reason
        retry?.cancel(false)
        retry = exec.schedule(Runnable { connectNow() }, backoffMs, TimeUnit.MILLISECONDS)
        backoffMs = minOf(backoffMs * 2, 30_000)
    }

    companion object {
        fun forHub(ctx: Context, deviceId: String, deviceName: String, hub: TrustedHub, events: HubEvents) =
            HubConnection(ctx, deviceId, deviceName, hub, events)

        /** Pairs using a scanned QR code; the connection then continues as a normal session. */
        fun forQr(ctx: Context, deviceId: String, deviceName: String, offer: PairingOffer, events: HubEvents): HubConnection {
            val c = HubConnection(ctx, deviceId, deviceName, null, events)
            c.pendingPair = { c.pairQr(offer, 0) }
            return c
        }

        /** Pairs with host + one-time code (no camera). */
        fun forManual(ctx: Context, deviceId: String, deviceName: String, host: String, port: Int, code: String, events: HubEvents): HubConnection {
            val c = HubConnection(ctx, deviceId, deviceName, null, events)
            c.pendingPair = { c.pairManual(host, port, code) }
            return c
        }
    }

    private fun pairQr(offer: PairingOffer, i: Int) {
        if (i >= offer.hosts.size) { running = false; events.onFailure(this, "UNREACHABLE", "Could not reach ${offer.hubName}. Same Wi-Fi? Firewall?"); return }
        var opened = false
        WifiLink.connect(offer.hosts[i], offer.port, offer.fingerprint, object : LinkEvents {
            override fun onOpen(link: WifiLink) = exec.execute { opened = true; session.startPairing(link, offer) }
            override fun onText(text: String) = exec.execute { session.onText(text) }
            override fun onBinary(bytes: ByteArray) = exec.execute { session.onBinary(bytes) }
            override fun onClosed(link: WifiLink, reason: String) = exec.execute {
                if (!opened) pairQr(offer, i + 1)
                else { session.onLinkClosed(); if (session.hub != null) scheduleRetry(reason) }
            }
        })
    }

    private fun pairManual(host: String, port: Int, code: String) {
        var opened = false
        WifiLink.connect(host, port, null, object : LinkEvents {
            override fun onOpen(link: WifiLink) = exec.execute {
                opened = true
                session.startManualPairing(link, host, port, link.observedFingerprint ?: "", code)
            }
            override fun onText(text: String) = exec.execute { session.onText(text) }
            override fun onBinary(bytes: ByteArray) = exec.execute { session.onBinary(bytes) }
            override fun onClosed(link: WifiLink, reason: String) = exec.execute {
                session.onLinkClosed()
                if (!opened) { running = false; events.onFailure(this@HubConnection, "UNREACHABLE", reason) }
                else if (session.hub != null) scheduleRetry(reason)
            }
        })
    }
}

/** mDNS/NSD discovery of `_ukpro._tcp` laptops on the current network (PROTOCOL §1). */
class Discovery(private val context: Context, private val onFound: (id: String, name: String, host: String, port: Int, fp: String) -> Unit) {
    private val nsd = context.getSystemService(NsdManager::class.java)
    private var lock: WifiManager.MulticastLock? = null
    private var listener: NsdManager.DiscoveryListener? = null

    fun start() {
        if (listener != null) return
        lock = context.getSystemService(WifiManager::class.java)?.createMulticastLock("ukpro-mdns")?.apply { setReferenceCounted(false); acquire() }
        val l = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onServiceLost(info: NsdServiceInfo) {}
            override fun onServiceFound(info: NsdServiceInfo) { resolve(info) }
        }
        listener = l
        runCatching { nsd.discoverServices("_ukpro._tcp", NsdManager.PROTOCOL_DNS_SD, l) }
    }

    @Suppress("DEPRECATION") // resolveService is the only API before Android 14
    private fun resolve(info: NsdServiceInfo) {
        nsd.resolveService(info, object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {}
            override fun onServiceResolved(s: NsdServiceInfo) {
                val attrs = s.attributes
                fun a(k: String) = attrs[k]?.let { String(it, Charsets.UTF_8) } ?: ""
                val host = if (Build.VERSION.SDK_INT >= 34) s.hostAddresses.firstOrNull()?.hostAddress else s.host?.hostAddress
                if (host != null) onFound(a("id"), a("name").ifEmpty { s.serviceName }, host, s.port, a("fp"))
            }
        })
    }

    fun stop() {
        listener?.let { runCatching { nsd.stopServiceDiscovery(it) } }
        listener = null
        lock?.release(); lock = null
    }
}
