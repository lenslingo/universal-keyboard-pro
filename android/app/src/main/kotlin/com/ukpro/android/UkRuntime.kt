package com.ukpro.android

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.os.Handler
import android.os.Looper
import com.ukpro.android.data.SettingsRepo
import com.ukpro.android.data.TransferEntity
import com.ukpro.android.data.UkDatabase
import com.ukpro.android.security.SecretStore
import com.ukpro.android.sync.ClipboardSync
import com.ukpro.android.sync.InputSync
import com.ukpro.android.sync.Transfers
import com.ukpro.android.transport.Discovery
import com.ukpro.android.transport.HubConnection
import com.ukpro.android.transport.HubEvents
import com.ukpro.core.Envelope
import com.ukpro.core.MessageTypes
import com.ukpro.core.PairingOffer
import com.ukpro.core.TransferProgress
import com.ukpro.core.TrustedHub
import com.ukpro.core.bool
import com.ukpro.core.list
import com.ukpro.core.long
import com.ukpro.core.str
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/** What the UI shows about one laptop. */
data class HubUi(
    val id: String, val name: String, val connected: Boolean, val transport: String, val rttMs: Long,
    val error: String?, val a2wOnHub: Boolean = true, val w2a: Boolean = false, val pausedOnHub: Boolean = false,
    val volume: Int? = null, val muted: Boolean = false, val foregroundApp: String? = null, val kbCaptureOnHub: Boolean = false,
    val isActive: Boolean = false,
)

data class LaptopClip(val clipId: String, val kind: String, val preview: String, val ts: Long)
data class Discovered(val id: String, val name: String, val host: String, val port: Int)

/**
 * Process-wide runtime: owns the connections to every paired laptop, routes incoming messages to the
 * IME / clipboard / transfers, and exposes state for Compose. Lives as long as the process; the
 * foreground ConnectionService keeps the process alive.
 */
object UkRuntime : HubEvents {
    lateinit var app: Context; private set
    lateinit var settings: SettingsRepo; private set
    lateinit var secrets: SecretStore; private set
    lateinit var db: UkDatabase; private set
    lateinit var clipboard: ClipboardSync; private set
    lateinit var transfers: Transfers; private set

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val io = Executors.newCachedThreadPool()
    val main = Handler(Looper.getMainLooper())
    private val connections = ConcurrentHashMap<String, HubConnection>()
    private val pairing = ConcurrentHashMap.newKeySet<HubConnection>()
    private val hubExtras = ConcurrentHashMap<String, HubUi>()
    private var discovery: Discovery? = null
    private var started = false

    val hubs = MutableStateFlow<List<HubUi>>(emptyList())
    val discovered = MutableStateFlow<List<Discovered>>(emptyList())
    val laptopHistory = MutableStateFlow<List<LaptopClip>>(emptyList())
    val transferList = MutableStateFlow<List<TransferProgress>>(emptyList())
    val messages = MutableStateFlow<String?>(null)

    /** English + Bangla dictionaries (40k words each, wordfreq data) for suggestions; loaded in the background. */
    @Volatile var suggester: com.ukpro.core.Suggester? = null
        private set
    private var dictLoading = false

    @Synchronized fun loadDictionaries() {
        if (suggester != null || dictLoading) return
        dictLoading = true
        io.execute {
            fun read(name: String, key: (String) -> String) = runCatching {
                app.assets.open("dict/$name").bufferedReader(Charsets.UTF_8).useLines { com.ukpro.core.WordDictionary(it.toList().asSequence(), key) }
            }.getOrNull()
            suggester = com.ukpro.core.Suggester(read("bn.txt") { com.ukpro.core.BanglaPhonetic.skeleton(it) }, read("en.txt") { it })
        }
    }

    fun init(ctx: Context) {
        if (::app.isInitialized) return
        app = ctx.applicationContext
        settings = SettingsRepo(app, scope)
        secrets = SecretStore(app)
        db = UkDatabase.create(app)
        clipboard = ClipboardSync(app)
        transfers = Transfers(app)
    }

    /** Starts connections to every paired laptop, discovery and network monitoring (called by the service). */
    @Synchronized fun start() {
        if (started) return
        started = true
        for (h in secrets.hubs()) connectionFor(h).start()
        discovery = Discovery(app) { id, name, host, port, _ ->
            discovered.value = (discovered.value.filter { it.id != id } + Discovered(id, name, host, port))
            connections[id]?.let { c -> if (c.discoveredHosts.add(host)) c.kick() }
        }.also { it.start() }
        app.getSystemService(ConnectivityManager::class.java)?.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { connections.values.forEach { it.kick() } }
        })
        refresh()
    }

    @Synchronized fun stop() {
        started = false
        connections.values.forEach { it.stop() }
        discovery?.stop(); discovery = null
        refresh()
    }

    private fun connectionFor(h: TrustedHub): HubConnection =
        connections.getOrPut(h.hubId) { HubConnection.forHub(app, secrets.deviceId, secrets.deviceName, h, this) }

    // ------------------------------------------------------------------ pairing

    fun pairWithQr(uri: String): String? {
        val offer = try { PairingOffer.parse(uri.trim()) } catch (e: IllegalArgumentException) { return e.message }
        val c = HubConnection.forQr(app, secrets.deviceId, secrets.deviceName, offer, this)
        pairing.add(c); c.start()
        return null
    }

    fun pairManual(host: String, port: Int, code: String) {
        val c = HubConnection.forManual(app, secrets.deviceId, secrets.deviceName, host.trim(), port, code, this)
        pairing.add(c); c.start()
    }

    /** Number to compare with the laptop's dialog during manual-code pairing. */
    fun currentVerification(): String? = pairing.firstNotNullOfOrNull { it.session.verificationNumber() }

    fun forget(hubId: String) {
        connections.remove(hubId)?.shutdown()
        secrets.remove(hubId)
        if (settings.current.activeHubId == hubId) settings.update { it.copy(activeHubId = null) }
        refresh()
    }

    // ------------------------------------------------------------------ sending

    /** The laptop that receives this phone's keyboard / remote-control input. */
    fun activeConnection(): HubConnection? {
        val id = settings.current.activeHubId
        val chosen = id?.let { connections[it] }
        if (chosen != null && chosen.isConnected) return chosen
        return connections.values.firstOrNull { it.isConnected } ?: chosen
    }

    fun setActive(hubId: String) { settings.update { it.copy(activeHubId = hubId) }; refresh() }

    /** Input to the active laptop. Returns false if not delivered now (offline input is not queued for long). */
    fun sendInput(type: String, payload: MutableMap<String, Any?>): Boolean {
        val s = settings.current
        if (!s.androidToWindows || s.paused) return false
        val c = activeConnection() ?: return false
        return c.session.send(type, payload)
    }

    fun sendToActive(type: String, payload: MutableMap<String, Any?> = LinkedHashMap()): Boolean =
        activeConnection()?.session?.send(type, payload) ?: false

    /** Same message to every connected laptop (clipboard, focus state). Payload is copied per laptop. */
    fun broadcast(type: String, payload: Map<String, Any?>) {
        for (c in connections.values) if (c.isConnected) c.session.send(type, LinkedHashMap(payload))
    }

    /** Phone touchpad → laptop mouse (active laptop). Moves/scrolls are unreliable streams: never queued offline. */
    val mouse = object : com.ukpro.android.keyboard.MouseSink {
        private fun send(type: String, p: MutableMap<String, Any?>) {
            if (settings.current.paused) return
            activeConnection()?.session?.send(type, p)
        }
        override fun move(dx: Int, dy: Int) = send(MessageTypes.MOUSE_MOVE, linkedMapOf("dx" to dx.toLong(), "dy" to dy.toLong()))
        override fun scroll(dx: Int, dy: Int) = send(MessageTypes.MOUSE_SCROLL, linkedMapOf("dx" to dx.toLong(), "dy" to dy.toLong()))
        override fun button(button: String, action: String) = send(MessageTypes.MOUSE_BUTTON, linkedMapOf("button" to button, "action" to action))
    }

    fun connectedHubs(): List<HubConnection> = connections.values.filter { it.isConnected }
    fun connection(id: String): HubConnection? = connections[id]

    fun setPaused(paused: Boolean) { settings.update { it.copy(paused = paused) }; InputSync.republishFocus() }

    // ------------------------------------------------------------------ HubEvents

    override fun onPaired(conn: HubConnection, hub: TrustedHub) {
        secrets.save(hub)
        pairing.remove(conn)
        connections[hub.hubId]?.shutdown()
        connections[hub.hubId] = conn
        if (settings.current.activeHubId == null) settings.update { it.copy(activeHubId = hub.hubId) }
        toast("Paired with ${hub.name}")
        refresh()
    }

    override fun onConnected(conn: HubConnection) {
        InputSync.sendFocusTo(conn)
        conn.session.send(MessageTypes.DEVICE_STATUS, linkedMapOf("name" to secrets.deviceName, "platform" to "android"))
        refresh()
    }

    override fun onDisconnected(conn: HubConnection, reason: String) = refresh()

    override fun onFailure(conn: HubConnection, code: String, message: String) {
        if (pairing.remove(conn)) { conn.shutdown(); toast("Pairing failed: $message") }
        refresh()
    }

    override fun onTransfer(conn: HubConnection, p: TransferProgress) {
        transfers.onProgress(conn, p)
        transferList.value = connections.values.flatMap { it.transfers.active }
        if (p.state == TransferProgress.State.COMPLETED || p.state == TransferProgress.State.FAILED || p.state == TransferProgress.State.CANCELLED) {
            transferList.value = connections.values.flatMap { it.transfers.active }.filter { it.transferId != p.transferId }
            scope.launch {
                db.transfers().put(TransferEntity(p.transferId, p.name, p.size, p.incoming,
                    p.state.name + (p.error?.let { " — $it" } ?: ""),
                    p.finalFile?.absolutePath, conn.name, System.currentTimeMillis()))
            }
        }
    }

    override fun onMessage(conn: HubConnection, env: Envelope) {
        when (env.type) {
            MessageTypes.KEY_DOWN, MessageTypes.KEY_UP, MessageTypes.TEXT_COMMIT, MessageTypes.COMPOSITION_COMMIT ->
                InputSync.deliver(conn, env)
            MessageTypes.CLIPBOARD_TEXT, MessageTypes.CLIPBOARD_IMAGE, MessageTypes.CLIPBOARD_FILE -> clipboard.onRemote(conn, env)
            MessageTypes.CLIPBOARD_FETCH -> clipboard.onFetch(conn, env.str("clipId"))
            MessageTypes.SYNC_CONFIG -> { update(conn) { it.copy(a2wOnHub = env.bool("a2w", true), w2a = env.bool("w2a"), pausedOnHub = env.bool("paused")) } }
            MessageTypes.DEVICE_STATUS -> update(conn) {
                it.copy(volume = (env.payload["volume"] as? Number)?.toInt(), muted = env.bool("muted"),
                    foregroundApp = env.payload["foregroundApp"] as? String, kbCaptureOnHub = env.bool("kbCapture"))
            }
            MessageTypes.CLIPBOARD_HISTORY_RESPONSE -> laptopHistory.value = env.payload.list("items").mapNotNull {
                @Suppress("UNCHECKED_CAST") val m = it as? Map<String, Any?> ?: return@mapNotNull null
                LaptopClip(m.str("clipId"), m.str("kind"), m.str("preview"), m.long("ts"))
            }
            MessageTypes.ERROR -> toast(errorText(env.str("code"), conn.name))
        }
    }

    private fun errorText(code: String, laptop: String) = when (code) {
        "ELEVATED_TARGET" -> "$laptop: the focused app runs as administrator, Windows blocks remote typing into it."
        "FEATURE_DISABLED" -> "$laptop: this feature is switched off on the laptop."
        "NO_EDITABLE_FIELD" -> "No text field focused."
        else -> "$laptop: $code"
    }

    private fun update(conn: HubConnection, f: (HubUi) -> HubUi) {
        val id = conn.hubId ?: return
        hubExtras[id] = f(hubExtras[id] ?: base(conn))
        refresh()
    }

    private fun base(c: HubConnection) = HubUi(c.hubId ?: "", c.name, c.isConnected, c.transportName, c.session.rttMs, c.lastError)

    /** Rebuilds the UI state (cheap; called on every connection event and once a second by the service). */
    fun refresh() {
        val active = activeConnection()?.hubId
        val known = secrets.hubs()
        hubs.value = known.map { h ->
            val c = connections[h.hubId]
            val extra = hubExtras[h.hubId]
            val b = if (c != null) base(c) else HubUi(h.hubId, h.name, false, "—", -1, "not started")
            (extra ?: b).copy(id = h.hubId, name = h.name, connected = b.connected, transport = b.transport, rttMs = b.rttMs,
                error = b.error, isActive = h.hubId == active)
        }
    }

    fun toast(msg: String) { messages.value = msg }
}
