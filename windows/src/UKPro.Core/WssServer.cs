using System.Net;
using System.Net.Security;
using System.Net.Sockets;
using System.Net.WebSockets;
using System.Security.Authentication;
using System.Security.Cryptography;
using System.Security.Cryptography.X509Certificates;
using System.Text;

namespace UKPro.Core;

public readonly record struct WsMessage(bool IsText, string? Text, byte[]? Data);

/// <summary>A WebSocket connection with serialized sends and size-limited message reassembly.</summary>
public sealed class WsConnection : IAsyncDisposable
{
    public const int MaxMessageBytes = 8 * 1024 * 1024;
    private readonly WebSocket _ws;
    private readonly IDisposable? _owner;
    private readonly SemaphoreSlim _sendLock = new(1, 1);
    private readonly byte[] _rx = new byte[64 * 1024];

    public WsConnection(WebSocket ws, IPEndPoint? remote, IDisposable? owner = null)
    {
        _ws = ws; Remote = remote; _owner = owner;
    }

    public IPEndPoint? Remote { get; }
    public bool IsOpen => _ws.State == WebSocketState.Open;

    public Task SendTextAsync(string text, CancellationToken ct = default) =>
        SendAsync(Encoding.UTF8.GetBytes(text), WebSocketMessageType.Text, ct);

    public Task SendBinaryAsync(ReadOnlyMemory<byte> data, CancellationToken ct = default) =>
        SendAsync(data, WebSocketMessageType.Binary, ct);

    private async Task SendAsync(ReadOnlyMemory<byte> data, WebSocketMessageType type, CancellationToken ct)
    {
        await _sendLock.WaitAsync(ct).ConfigureAwait(false);
        try { await _ws.SendAsync(data, type, true, ct).ConfigureAwait(false); }
        finally { _sendLock.Release(); }
    }

    /// <summary>Next complete message, or null when the peer closed.</summary>
    public async Task<WsMessage?> ReceiveAsync(CancellationToken ct = default)
    {
        using var ms = new MemoryStream();
        while (true)
        {
            var r = await _ws.ReceiveAsync(_rx.AsMemory(), ct).ConfigureAwait(false);
            if (r.MessageType == WebSocketMessageType.Close) return null;
            ms.Write(_rx, 0, r.Count);
            if (ms.Length > MaxMessageBytes) throw new ProtocolException("BAD_MESSAGE", "message too large");
            if (!r.EndOfMessage) continue;
            return r.MessageType == WebSocketMessageType.Text
                ? new WsMessage(true, Encoding.UTF8.GetString(ms.GetBuffer(), 0, (int)ms.Length), null)
                : new WsMessage(false, null, ms.ToArray());
        }
    }

    public async Task CloseAsync(string reason = "bye")
    {
        try
        {
            if (_ws.State == WebSocketState.Open)
            {
                using var cts = new CancellationTokenSource(TimeSpan.FromSeconds(2));
                await _ws.CloseOutputAsync(WebSocketCloseStatus.NormalClosure, reason, cts.Token).ConfigureAwait(false);
            }
        }
        catch (Exception e) when (e is WebSocketException or OperationCanceledException or IOException or ObjectDisposedException) { }
        _ws.Abort();
    }

    public async ValueTask DisposeAsync()
    {
        await CloseAsync().ConfigureAwait(false);
        _ws.Dispose();
        _owner?.Dispose();
    }
}

/// <summary>
/// WebSocket-over-TLS server built only on the BCL: TcpListener + SslStream + RFC 6455 handshake +
/// WebSocket.CreateFromStream. Accepts only private-network / loopback peers.
/// </summary>
public sealed class WssServer : IAsyncDisposable
{
    public const string Path = "/ukpro/v1";
    private const string WsGuid = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";
    private readonly X509Certificate2 _cert;
    private readonly Func<WsConnection, Task> _onConnection;
    private readonly CancellationTokenSource _cts = new();
    private TcpListener? _listener;

    public WssServer(X509Certificate2 cert, int port, Func<WsConnection, Task> onConnection)
    {
        _cert = cert; Port = port; _onConnection = onConnection;
    }

    public int Port { get; private set; }
    public bool AllowNonPrivateAddresses { get; set; }

    /// <summary>Binds the port (or the next free one, up to +20) and starts accepting.</summary>
    public void Start()
    {
        for (int p = Port; p < Port + 20; p++)
        {
            try
            {
                TcpListener l;
                if (Socket.OSSupportsIPv6)
                {
                    l = new TcpListener(IPAddress.IPv6Any, p);
                    l.Server.DualMode = true;
                }
                else l = new TcpListener(IPAddress.Any, p);
                l.Start();
                _listener = l; Port = ((IPEndPoint)l.LocalEndpoint).Port;
                break;
            }
            catch (SocketException) when (p < Port + 19) { }
        }
        _ = AcceptLoop(_listener!, _cts.Token);
    }

    private async Task AcceptLoop(TcpListener l, CancellationToken ct)
    {
        while (!ct.IsCancellationRequested)
        {
            TcpClient client;
            try { client = await l.AcceptTcpClientAsync(ct).ConfigureAwait(false); }
            catch (Exception) when (ct.IsCancellationRequested) { return; }
            catch (SocketException) { continue; }
            _ = Task.Run(() => HandleClient(client, ct), ct);
        }
    }

    private async Task HandleClient(TcpClient client, CancellationToken ct)
    {
        var remote = client.Client.RemoteEndPoint as IPEndPoint;
        if (remote is null || (!AllowNonPrivateAddresses && !IsPrivate(remote.Address))) { client.Dispose(); return; }
        client.NoDelay = true; // keystrokes must not wait for Nagle
        SslStream? ssl = null;
        bool transferred = false;
        try
        {
            ssl = new SslStream(client.GetStream(), leaveInnerStreamOpen: false);
            using (var hs = CancellationTokenSource.CreateLinkedTokenSource(ct))
            {
                hs.CancelAfter(TimeSpan.FromSeconds(10));
                await ssl.AuthenticateAsServerAsync(new SslServerAuthenticationOptions
                {
                    ServerCertificate = _cert,
                    EnabledSslProtocols = SslProtocols.Tls13 | SslProtocols.Tls12,
                    ClientCertificateRequired = false,
                }, hs.Token).ConfigureAwait(false);
                await UpgradeAsync(ssl, hs.Token).ConfigureAwait(false);
            }
            var ws = WebSocket.CreateFromStream(ssl, new WebSocketCreationOptions
            {
                IsServer = true, KeepAliveInterval = TimeSpan.FromSeconds(20),
            });
            var owner = new CompositeDisposable(ssl, client);
            transferred = true;
            await using var conn = new WsConnection(ws, remote, owner);
            await _onConnection(conn).ConfigureAwait(false);
        }
        catch (Exception e) when (e is IOException or AuthenticationException or OperationCanceledException
                                      or ProtocolException or WebSocketException or SocketException) { }
        finally
        {
            if (!transferred) { ssl?.Dispose(); client.Dispose(); }
        }
    }

    private static async Task UpgradeAsync(Stream s, CancellationToken ct)
    {
        var buf = new byte[8192];
        int len = 0;
        while (true)
        {
            int n = await s.ReadAsync(buf.AsMemory(len), ct).ConfigureAwait(false);
            if (n == 0) throw new IOException("closed during handshake");
            len += n;
            if (Encoding.ASCII.GetString(buf, 0, len).Contains("\r\n\r\n")) break;
            if (len == buf.Length) throw new ProtocolException("BAD_MESSAGE", "headers too large");
        }
        var lines = Encoding.ASCII.GetString(buf, 0, len).Split("\r\n");
        var reqLine = lines[0].Split(' ');
        var headers = new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase);
        foreach (var line in lines.Skip(1))
        {
            var i = line.IndexOf(':');
            if (i > 0) headers[line[..i].Trim()] = line[(i + 1)..].Trim();
        }
        bool ok = reqLine.Length >= 2 && reqLine[0] == "GET" && reqLine[1].Split('?')[0] == Path
                  && headers.TryGetValue("Upgrade", out var up) && up.Equals("websocket", StringComparison.OrdinalIgnoreCase)
                  && headers.TryGetValue("Sec-WebSocket-Key", out _)
                  && headers.TryGetValue("Sec-WebSocket-Version", out var ver) && ver == "13";
        if (!ok)
        {
            await s.WriteAsync("HTTP/1.1 400 Bad Request\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"u8.ToArray(), ct).ConfigureAwait(false);
            throw new ProtocolException("BAD_MESSAGE", "not a websocket upgrade");
        }
        var accept = Convert.ToBase64String(SHA1.HashData(Encoding.ASCII.GetBytes(headers["Sec-WebSocket-Key"] + WsGuid)));
        var resp = $"HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: {accept}\r\n\r\n";
        await s.WriteAsync(Encoding.ASCII.GetBytes(resp), ct).ConfigureAwait(false);
        await s.FlushAsync(ct).ConfigureAwait(false);
    }

    public static bool IsPrivate(IPAddress a)
    {
        if (IPAddress.IsLoopback(a)) return true;
        if (a.IsIPv4MappedToIPv6) a = a.MapToIPv4();
        if (a.AddressFamily == AddressFamily.InterNetworkV6)
            return a.IsIPv6LinkLocal || a.IsIPv6UniqueLocal;
        var b = a.GetAddressBytes();
        return b[0] == 10 || (b[0] == 172 && b[1] >= 16 && b[1] <= 31) || (b[0] == 192 && b[1] == 168)
               || (b[0] == 169 && b[1] == 254) || (b[0] == 100 && b[1] >= 64 && b[1] <= 127);
    }

    /// <summary>LAN addresses to put in the pairing QR code.</summary>
    public static IReadOnlyList<string> LocalAddresses() =>
        System.Net.NetworkInformation.NetworkInterface.GetAllNetworkInterfaces()
            .Where(n => n.OperationalStatus == System.Net.NetworkInformation.OperationalStatus.Up)
            .SelectMany(n => n.GetIPProperties().UnicastAddresses)
            .Select(u => u.Address)
            .Where(a => a.AddressFamily == AddressFamily.InterNetwork && !IPAddress.IsLoopback(a) && IsPrivate(a))
            .Select(a => a.ToString()).Distinct().ToList();

    public async ValueTask DisposeAsync()
    {
        await _cts.CancelAsync().ConfigureAwait(false);
        _listener?.Stop();
        _cts.Dispose();
    }

    private sealed class CompositeDisposable(params IDisposable[] items) : IDisposable
    {
        public void Dispose() { foreach (var i in items) i.Dispose(); }
    }
}
