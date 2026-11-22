using System.Collections.Concurrent;
using System.Net;
using System.Net.WebSockets;
using System.Security.Cryptography;
using System.Security.Cryptography.X509Certificates;
using System.Text.Json.Nodes;

namespace UKPro.Core;

public sealed record TrustedDevice(string DeviceId, string Name, string Platform, byte[] Secret, DateTimeOffset PairedAt);

public interface ITrustStore
{
    TrustedDevice? Get(string deviceId);
    void Save(TrustedDevice device);
    void Remove(string deviceId);
    IReadOnlyList<TrustedDevice> All();
}

public sealed class InMemoryTrustStore : ITrustStore
{
    private readonly ConcurrentDictionary<string, TrustedDevice> _d = new();
    public TrustedDevice? Get(string id) => _d.GetValueOrDefault(id);
    public void Save(TrustedDevice d) => _d[d.DeviceId] = d;
    public void Remove(string id) => _d.TryRemove(id, out _);
    public IReadOnlyList<TrustedDevice> All() => _d.Values.ToList();
}

/// <summary>A one-time pairing invitation shown as a QR code (PROTOCOL §4.1).</summary>
public sealed class PairingOffer : IDisposable
{
    internal PairingOffer(TimeSpan ttl)
    {
        Key = PairingCrypto.NewKey();
        PublicKey = PairingCrypto.ExportPublic(Key);
        Code = PairingCrypto.NewCode();
        ExpiresAt = DateTimeOffset.UtcNow + ttl;
    }

    public string Code { get; }
    public string PublicKey { get; }
    public DateTimeOffset ExpiresAt { get; }
    internal ECDiffieHellman Key { get; }
    internal bool Used { get; set; }
    public bool IsValid => !Used && DateTimeOffset.UtcNow < ExpiresAt;
    public string DisplayCode => $"{Code[..4]}-{Code[4..]}";

    public string ToUri(IEnumerable<string> hosts, int port, string fingerprint, string hubId, string hubName) =>
        $"ukpro://pair?v=1&h={Uri.EscapeDataString(string.Join(',', hosts))}&p={port}&fp={fingerprint}" +
        $"&id={Uri.EscapeDataString(hubId)}&n={Uri.EscapeDataString(hubName)}&c={Code}&pk={PublicKey}";

    public void Dispose() => Key.Dispose();
}

public sealed record PairRequestInfo(string DeviceId, string Name, string Platform, string VerificationNumber, IPEndPoint? Remote);

/// <summary>State that outlives individual connections (outbox, sequence numbers, transport).</summary>
public sealed class PeerSession
{
    internal PeerSession(TrustedDevice device, string hubId)
    {
        Device = device;
        Channel = new ReliableChannel(hubId);
    }

    public TrustedDevice Device { get; internal set; }
    public ReliableChannel Channel { get; }
    public bool IsConnected => Connection?.IsOpen == true;
    public long RttMs { get; internal set; } = -1;
    public string Transport { get; internal set; } = "wifi";
    public DateTimeOffset LastSeen { get; internal set; }
    internal WsConnection? Connection { get; set; }
    internal long PingCounter;
    internal int MissedPongs;
    internal readonly ConcurrentDictionary<long, long> PingSentAt = new();
}

public sealed class HubOptions
{
    public required string DeviceId { get; init; }
    public required string Name { get; init; }
    public required X509Certificate2 Certificate { get; init; }
    public int Port { get; init; } = 47821;
    public TimeSpan PingInterval { get; init; } = TimeSpan.FromSeconds(5);
    public TimeSpan HandshakeTimeout { get; init; } = TimeSpan.FromSeconds(15);
}

/// <summary>
/// Windows-side hub: accepts many Android clients, handles pairing, mutual authentication,
/// reliable delivery (ACK / resend), keep-alive and routing. UI-agnostic and cross-platform.
/// </summary>
public sealed class UkHub : IAsyncDisposable
{
    private readonly HubOptions _o;
    private readonly ITrustStore _store;
    private readonly WssServer _server;
    private readonly ConcurrentDictionary<string, PeerSession> _peers = new();
    private readonly ConcurrentDictionary<string, Queue<DateTimeOffset>> _authFailures = new();
    private PairingOffer? _offer;

    public UkHub(HubOptions options, ITrustStore store)
    {
        _o = options; _store = store;
        Fingerprint = Certificates.Fingerprint(options.Certificate);
        _server = new WssServer(options.Certificate, options.Port, HandleConnectionAsync);
    }

    public string DeviceId => _o.DeviceId;
    public string Name => _o.Name;
    public string Fingerprint { get; }
    public int Port => _server.Port;
    public bool AllowNonPrivateAddresses { get => _server.AllowNonPrivateAddresses; set => _server.AllowNonPrivateAddresses = value; }

    /// <summary>Asked when a phone presents a valid pairing proof. Return true to trust it.</summary>
    public Func<PairRequestInfo, Task<bool>> ApprovePairing { get; set; } = _ => Task.FromResult(false);

    public event Action<PeerSession>? PeerConnected;
    public event Action<PeerSession>? PeerDisconnected;
    public event Action<PeerSession, Envelope>? MessageReceived;
    public event Action<PeerSession, JsonObject, ReadOnlyMemory<byte>>? BinaryReceived;
    public event Action<string>? Log;

    public IReadOnlyCollection<PeerSession> Peers => _peers.Values.ToList();
    public PeerSession? GetPeer(string id) => _peers.GetValueOrDefault(id);

    public void Start() => _server.Start();

    public PairingOffer CreatePairingOffer(TimeSpan? ttl = null)
    {
        var o = new PairingOffer(ttl ?? TimeSpan.FromMinutes(5));
        Interlocked.Exchange(ref _offer, o)?.Dispose();
        return o;
    }

    public void CancelPairingOffer() => Interlocked.Exchange(ref _offer, null)?.Dispose();

    public void Forget(string deviceId)
    {
        _store.Remove(deviceId);
        if (_peers.TryRemove(deviceId, out var p) && p.Connection is { } c) _ = c.CloseAsync("unpaired");
    }

    // ------------------------------------------------------------------ sending

    /// <summary>Queues a message for one peer. Reliable messages survive disconnects until ACKed.</summary>
    public async Task<bool> SendAsync(string peerId, string type, JsonObject? payload = null)
    {
        var peer = PeerFor(peerId);
        if (peer is null) return false;
        var env = peer.Channel.Stamp(Envelope.Create(type, payload));
        env.TargetDeviceId = peerId;
        return await TrySendAsync(peer, env).ConfigureAwait(false);
    }

    /// <summary>Sends to every authorised peer except <paramref name="exceptPeerId"/>. The payload is cloned per peer.</summary>
    public async Task BroadcastAsync(string type, JsonObject payload, string? exceptPeerId = null)
    {
        foreach (var p in _peers.Values)
            if (p.Device.DeviceId != exceptPeerId)
                await SendAsync(p.Device.DeviceId, type, (JsonObject)payload.DeepClone()).ConfigureAwait(false);
    }

    public async Task SendBinaryAsync(string peerId, byte[] frame, CancellationToken ct = default)
    {
        var peer = PeerFor(peerId) ?? throw new InvalidOperationException("unknown peer");
        var c = peer.Connection;
        if (c is null || !c.IsOpen) throw new IOException("peer not connected");
        await c.SendBinaryAsync(frame, ct).ConfigureAwait(false);
    }

    private PeerSession? PeerFor(string id)
    {
        if (_peers.TryGetValue(id, out var p)) return p;
        var d = _store.Get(id);
        return d is null ? null : _peers.GetOrAdd(id, _ => new PeerSession(d, _o.DeviceId));
    }

    private async Task<bool> TrySendAsync(PeerSession peer, Envelope env)
    {
        var c = peer.Connection;
        if (c is null || !c.IsOpen) return false;
        try { await c.SendTextAsync(env.ToJson()).ConfigureAwait(false); return true; }
        catch (Exception e) when (e is WebSocketException or IOException or ObjectDisposedException or OperationCanceledException)
        {
            return false; // stays in outbox, resent after reconnect
        }
    }

    private Task SendRawAsync(WsConnection c, string type, JsonObject payload, string? target = null)
    {
        var env = Envelope.Create(type, payload);
        env.SourceDeviceId = _o.DeviceId;
        env.TargetDeviceId = target ?? "*";
        return c.SendTextAsync(env.ToJson());
    }

    // ------------------------------------------------------------------ connection handling

    private async Task HandleConnectionAsync(WsConnection conn)
    {
        var ip = conn.Remote?.Address.ToString() ?? "?";
        if (IsRateLimited(ip)) { await conn.CloseAsync("rate limited"); return; }

        PeerSession? peer;
        using (var hs = new CancellationTokenSource(_o.HandshakeTimeout))
            peer = await HandshakeAsync(conn, ip, hs.Token).ConfigureAwait(false);
        if (peer is null) { await conn.CloseAsync("handshake failed"); return; }

        var old = peer.Connection;
        peer.Connection = conn;
        peer.MissedPongs = 0;
        peer.LastSeen = DateTimeOffset.UtcNow;
        if (old is not null && !ReferenceEquals(old, conn)) _ = old.CloseAsync("replaced");
        Log?.Invoke($"peer {peer.Device.Name} connected from {ip}");
        PeerConnected?.Invoke(peer);

        using var life = new CancellationTokenSource();
        var keepAlive = KeepAliveLoop(peer, conn, life.Token);
        try { await ReceiveLoop(peer, conn, life.Token).ConfigureAwait(false); }
        catch (Exception e) when (e is WebSocketException or IOException or OperationCanceledException or ObjectDisposedException)
        {
            Log?.Invoke($"peer {peer.Device.Name} link error: {e.Message}");
        }
        finally
        {
            await life.CancelAsync().ConfigureAwait(false);
            try { await keepAlive.ConfigureAwait(false); } catch (OperationCanceledException) { }
            if (ReferenceEquals(peer.Connection, conn)) peer.Connection = null;
            Log?.Invoke($"peer {peer.Device.Name} disconnected");
            PeerDisconnected?.Invoke(peer);
        }
    }

    private async Task<PeerSession?> HandshakeAsync(WsConnection conn, string ip, CancellationToken ct)
    {
        try
        {
            while (true)
            {
                var m = await conn.ReceiveAsync(ct).ConfigureAwait(false);
                if (m is not { IsText: true } msg) return null;
                var env = Envelope.Parse(msg.Text!);
                switch (env.Type)
                {
                    case MessageTypes.PairInfo:
                        // manual-code pairing: the phone has no QR, so it learns the offer key here (PROTOCOL §4.1)
                        var o = _offer;
                        await SendRawAsync(conn, MessageTypes.PairInfo, o is { IsValid: true }
                            ? new JsonObject { ["hubId"] = _o.DeviceId, ["name"] = _o.Name, ["pk"] = o.PublicKey }
                            : new JsonObject { ["reason"] = "NO_ACTIVE_OFFER" });
                        continue;
                    case MessageTypes.PairRequest:
                        if (!await HandlePairRequestAsync(conn, env).ConfigureAwait(false)) { NoteFailure(ip); return null; }
                        continue; // client proceeds with AUTH_HELLO on the same connection
                    case MessageTypes.AuthHello:
                        var p = await HandleAuthAsync(conn, env, ct).ConfigureAwait(false);
                        if (p is null) NoteFailure(ip);
                        return p;
                    default:
                        await SendRawAsync(conn, MessageTypes.Error, new JsonObject { ["code"] = "NOT_AUTHORISED" });
                        return null;
                }
            }
        }
        catch (ProtocolException e)
        {
            try { await SendRawAsync(conn, MessageTypes.Error, new JsonObject { ["code"] = e.Code, ["message"] = e.Message }); }
            catch (Exception) { /* best effort */ }
            return null;
        }
        catch (Exception e) when (e is OperationCanceledException or WebSocketException or IOException
                                      or CryptographicException or InvalidOperationException or FormatException)
        {
            return null;
        }
    }

    private async Task<bool> HandlePairRequestAsync(WsConnection conn, Envelope env)
    {
        var offer = _offer;
        string clientId = env.Str("deviceId"), clientPk = env.Str("pk"), confirm = env.Str("confirm");
        if (offer is null || !offer.IsValid || clientId.Length is 0 or > 128)
        {
            await SendRawAsync(conn, MessageTypes.PairRejected, new JsonObject { ["reason"] = "NO_ACTIVE_OFFER" });
            return false;
        }
        offer.Used = true; // exactly one attempt per code
        var z = PairingCrypto.SharedSecret(offer.Key, clientPk);
        var secret = PairingCrypto.DeriveSecret(z, offer.Code, _o.DeviceId, clientId);
        var transcript = PairingCrypto.Transcript(_o.DeviceId, clientId, offer.PublicKey, clientPk, Fingerprint);
        if (!PairingCrypto.FixedEquals(confirm, PairingCrypto.Confirm(secret, "client-confirm", transcript)))
        {
            await SendRawAsync(conn, MessageTypes.PairRejected, new JsonObject { ["reason"] = "BAD_CODE" });
            return false;
        }
        var info = new PairRequestInfo(clientId, env.Str("name", "Android"), env.Str("platform", "android"),
            PairingCrypto.VerificationNumber(transcript), conn.Remote);
        if (!await ApprovePairing(info).ConfigureAwait(false))
        {
            await SendRawAsync(conn, MessageTypes.PairRejected, new JsonObject { ["reason"] = "USER_DECLINED" });
            return false;
        }
        var device = new TrustedDevice(clientId, info.Name, info.Platform, secret, DateTimeOffset.UtcNow);
        _store.Save(device);
        if (_peers.TryGetValue(clientId, out var existing)) existing.Device = device;
        CancelPairingOffer();
        await SendRawAsync(conn, MessageTypes.PairAccepted, new JsonObject
        {
            ["deviceId"] = _o.DeviceId, ["name"] = _o.Name, ["platform"] = "windows",
            ["confirm"] = PairingCrypto.Confirm(secret, "server-confirm", transcript),
        }, clientId);
        Log?.Invoke($"paired with {info.Name} ({clientId})");
        return true;
    }

    private async Task<PeerSession?> HandleAuthAsync(WsConnection conn, Envelope hello, CancellationToken ct)
    {
        var clientId = hello.Str("deviceId");
        var device = _store.Get(clientId);
        if (device is null)
        {
            await SendRawAsync(conn, MessageTypes.AuthFail, new JsonObject { ["reason"] = "UNKNOWN_DEVICE" });
            return null;
        }
        var peer = _peers.GetOrAdd(clientId, _ => new PeerSession(device, _o.DeviceId));
        peer.Device = device;
        string nC = hello.Str("nonce"), nS = SessionAuth.NewNonce();
        string clientSession = hello.Str("sessionId");
        if (nC.Length < 32 || clientSession.Length == 0) throw new ProtocolException("BAD_MESSAGE", "bad hello");

        long lastRecvFromClient = peer.Channel.PeerSessionId == clientSession ? peer.Channel.LastDelivered : 0;
        await SendRawAsync(conn, MessageTypes.AuthChallenge, new JsonObject
        {
            ["deviceId"] = _o.DeviceId, ["nonce"] = nS, ["sessionId"] = peer.Channel.LocalSessionId,
            ["peerSessionId"] = clientSession, ["lastRecvSeq"] = lastRecvFromClient,
            ["mac"] = SessionAuth.ServerMac(device.Secret, nC, nS, Fingerprint),
        }, clientId).ConfigureAwait(false);

        var m = await conn.ReceiveAsync(ct).ConfigureAwait(false);
        if (m is not { IsText: true } resp) return null;
        var r = Envelope.Parse(resp.Text!);
        if (r.Type != MessageTypes.AuthResponse ||
            !PairingCrypto.FixedEquals(r.Str("mac"), SessionAuth.ClientMac(device.Secret, nC, nS, Fingerprint)))
        {
            await SendRawAsync(conn, MessageTypes.AuthFail, new JsonObject { ["reason"] = "BAD_MAC" });
            return null;
        }
        peer.Channel.BeginPeerSession(clientSession);
        await SendRawAsync(conn, MessageTypes.AuthOk, new JsonObject(), clientId).ConfigureAwait(false);

        // replay what the client has not seen yet (exactly-once across reconnects / transport switches)
        foreach (var e in peer.Channel.ResendAfterReconnect(hello.Str("peerSessionId"), hello.Long("lastRecvSeq")))
            await conn.SendTextAsync(e.ToJson(), ct).ConfigureAwait(false);
        return peer;
    }

    private async Task ReceiveLoop(PeerSession peer, WsConnection conn, CancellationToken ct)
    {
        while (!ct.IsCancellationRequested)
        {
            var m = await conn.ReceiveAsync(ct).ConfigureAwait(false);
            if (m is null) return;
            peer.LastSeen = DateTimeOffset.UtcNow;
            peer.MissedPongs = 0;
            if (!m.Value.IsText)
            {
                var (header, data) = BinaryFrame.Decode(m.Value.Data!);
                BinaryReceived?.Invoke(peer, header, data);
                continue;
            }
            Envelope env;
            try { env = Envelope.Parse(m.Value.Text!); }
            catch (ProtocolException e)
            {
                await SendRawAsync(conn, MessageTypes.Error, new JsonObject { ["code"] = e.Code, ["message"] = e.Message });
                continue;
            }
            if (env.SourceDeviceId != peer.Device.DeviceId) continue; // spoofed source
            switch (env.Type)
            {
                case MessageTypes.Ack:
                    peer.Channel.OnAck(env.Long("upTo"));
                    continue;
                case MessageTypes.Ping:
                    await SendRawAsync(conn, MessageTypes.Pong, new JsonObject { ["n"] = env.Long("n") }, peer.Device.DeviceId);
                    continue;
                case MessageTypes.Pong:
                    if (peer.PingSentAt.TryRemove(env.Long("n"), out var at))
                        peer.RttMs = Environment.TickCount64 - at;
                    continue;
            }
            IReadOnlyList<Envelope> deliver;
            try { deliver = peer.Channel.Receive(env); }
            catch (ProtocolException e) when (e.Code == "RESYNC")
            {
                await SendRawAsync(conn, MessageTypes.Error, new JsonObject { ["code"] = "RESYNC" });
                return;
            }
            foreach (var d in deliver) MessageReceived?.Invoke(peer, d);
            if (peer.Channel.TryTakeAck(out var upTo))
                await SendRawAsync(conn, MessageTypes.Ack, new JsonObject { ["upTo"] = upTo }, peer.Device.DeviceId);
        }
    }

    private async Task KeepAliveLoop(PeerSession peer, WsConnection conn, CancellationToken ct)
    {
        var nextPing = Environment.TickCount64 + (long)_o.PingInterval.TotalMilliseconds;
        while (!ct.IsCancellationRequested)
        {
            await Task.Delay(50, ct).ConfigureAwait(false);
            if (!conn.IsOpen) return;
            try
            {
                if (peer.Channel.TryTakeAck(out var upTo))
                    await SendRawAsync(conn, MessageTypes.Ack, new JsonObject { ["upTo"] = upTo }, peer.Device.DeviceId);
                if (Environment.TickCount64 >= nextPing)
                {
                    nextPing = Environment.TickCount64 + (long)_o.PingInterval.TotalMilliseconds;
                    if (++peer.MissedPongs > 3) { await conn.CloseAsync("timeout"); return; }
                    var n = Interlocked.Increment(ref peer.PingCounter);
                    peer.PingSentAt[n] = Environment.TickCount64;
                    foreach (var k in peer.PingSentAt.Keys.Where(k => k < n - 10)) peer.PingSentAt.TryRemove(k, out _);
                    await SendRawAsync(conn, MessageTypes.Ping, new JsonObject { ["n"] = n }, peer.Device.DeviceId);
                }
            }
            catch (Exception e) when (e is WebSocketException or IOException or ObjectDisposedException) { return; }
        }
    }

    private bool IsRateLimited(string ip)
    {
        if (!_authFailures.TryGetValue(ip, out var q)) return false;
        lock (q)
        {
            while (q.Count > 0 && DateTimeOffset.UtcNow - q.Peek() > TimeSpan.FromSeconds(60)) q.Dequeue();
            return q.Count >= 5;
        }
    }

    private void NoteFailure(string ip)
    {
        var q = _authFailures.GetOrAdd(ip, _ => new Queue<DateTimeOffset>());
        lock (q) q.Enqueue(DateTimeOffset.UtcNow);
    }

    public async ValueTask DisposeAsync()
    {
        foreach (var p in _peers.Values) if (p.Connection is { } c) await c.CloseAsync("shutdown");
        await _server.DisposeAsync();
        CancelPairingOffer();
    }
}
