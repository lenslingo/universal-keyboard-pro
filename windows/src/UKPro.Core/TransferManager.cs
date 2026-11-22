using System.Collections.Concurrent;
using System.Diagnostics;
using System.Text.Json.Nodes;

namespace UKPro.Core;

public enum TransferDirection { Incoming, Outgoing }
public enum TransferState { Waiting, Running, Paused, Verifying, Completed, Failed, Cancelled }

public sealed class TransferInfo
{
    public required string TransferId { get; init; }
    public required string PeerId { get; init; }
    public required string Name { get; init; }
    public required long Size { get; init; }
    public required TransferDirection Direction { get; init; }
    public string Purpose { get; init; } = "file";
    public long Transferred { get; internal set; }
    public double BytesPerSecond { get; internal set; }
    public TransferState State { get; internal set; } = TransferState.Waiting;
    public string? FinalPath { get; internal set; }
    public string? Error { get; internal set; }
    public double Progress => Size == 0 ? 1 : (double)Transferred / Size;
}

/// <summary>
/// Hub-side file transfer engine (PROTOCOL §5): incoming via FileReceiver, outgoing via FileSender,
/// resume, pause, cancel, progress and speed. Bound to a <see cref="UkHub"/>.
/// </summary>
public sealed class TransferManager
{
    private readonly UkHub _hub;
    private readonly Func<string, TransferInfo, string> _destinationFor;
    private readonly ConcurrentDictionary<string, (FileReceiver Rx, TransferInfo Info, Stopwatch Clock, long StartOffset)> _incoming = new();
    private readonly ConcurrentDictionary<string, Outgoing> _outgoing = new();

    private sealed class Outgoing(FileSender sender, TransferInfo info)
    {
        public FileSender Sender { get; } = sender;
        public TransferInfo Info { get; } = info;
        public TaskCompletionSource<long> Accepted = new(TaskCreationOptions.RunContinuationsAsynchronously);
        public TaskCompletionSource<bool> Result = new(TaskCreationOptions.RunContinuationsAsynchronously);
        public CancellationTokenSource Cts = new();
        public volatile bool Paused;
    }

    public TransferManager(UkHub hub, Func<string, TransferInfo, string> destinationDirFor)
    {
        _hub = hub; _destinationFor = destinationDirFor;
        hub.MessageReceived += OnMessage;
        hub.BinaryReceived += OnBinary;
        hub.PeerDisconnected += p =>
        {
            foreach (var (_, o) in _outgoing) if (o.Info.PeerId == p.Device.DeviceId) o.Accepted.TrySetException(new IOException("peer disconnected"));
        };
    }

    public long MaxIncomingBytes { get; set; } = 64L * 1024 * 1024 * 1024;
    public Func<TransferInfo, bool> AcceptIncoming { get; set; } = _ => true;
    public event Action<TransferInfo>? Changed;
    public IEnumerable<TransferInfo> All => _incoming.Values.Select(v => v.Info).Concat(_outgoing.Values.Select(o => o.Info));

    // ------------------------------------------------------------ outgoing

    /// <summary>Sends a file; retries/resumes up to <paramref name="attempts"/> times across reconnects.</summary>
    public async Task<TransferInfo> SendFileAsync(string peerId, string path, int chunkSize = 256 * 1024, string purpose = "file",
        int attempts = 5, string? transferId = null, CancellationToken ct = default)
    {
        var sender = await FileSender.CreateAsync(path, chunkSize, purpose, transferId: transferId, ct: ct).ConfigureAwait(false);
        var info = new TransferInfo
        {
            TransferId = sender.TransferId, PeerId = peerId, Name = sender.Name, Size = sender.Size,
            Direction = TransferDirection.Outgoing, Purpose = purpose,
        };
        var o = new Outgoing(sender, info);
        _outgoing[info.TransferId] = o;
        using var linked = CancellationTokenSource.CreateLinkedTokenSource(ct, o.Cts.Token);
        for (int attempt = 1; attempt <= attempts; attempt++)
        {
            try
            {
                await RunOnceAsync(o, linked.Token).ConfigureAwait(false);
                return info;
            }
            catch (OperationCanceledException)
            {
                Set(info, TransferState.Cancelled);
                await _hub.SendAsync(peerId, MessageTypes.FileCancel, new JsonObject { ["transferId"] = info.TransferId });
                return info;
            }
            catch (Exception e) when (e is IOException or System.Net.WebSockets.WebSocketException or TimeoutException)
            {
                info.Error = e.Message;
                Set(info, TransferState.Waiting);
                await WaitForPeerAsync(peerId, linked.Token).ConfigureAwait(false);
                o.Accepted = new(TaskCreationOptions.RunContinuationsAsynchronously);
            }
            catch (ProtocolException e)
            {
                info.Error = e.Message; Set(info, TransferState.Failed); return info;
            }
        }
        Set(info, TransferState.Failed);
        return info;
    }

    private async Task RunOnceAsync(Outgoing o, CancellationToken ct)
    {
        var info = o.Info;
        await _hub.SendAsync(info.PeerId, MessageTypes.FileStart, o.Sender.StartPayload()).ConfigureAwait(false);
        var resumeFrom = await o.Accepted.Task.WaitAsync(TimeSpan.FromSeconds(60), ct).ConfigureAwait(false);
        if (resumeFrom < 0) throw new ProtocolException("FEATURE_DISABLED", "receiver declined");
        Set(info, TransferState.Running);
        var clock = Stopwatch.StartNew(); long sentThisRun = 0;
        await foreach (var (offset, len, frame) in o.Sender.ReadFramesAsync(resumeFrom, ct).ConfigureAwait(false))
        {
            while (o.Paused) { Set(info, TransferState.Paused); await Task.Delay(200, ct).ConfigureAwait(false); }
            await _hub.SendBinaryAsync(info.PeerId, frame, ct).ConfigureAwait(false);
            sentThisRun += len;
            info.Transferred = offset + len;
            info.BytesPerSecond = sentThisRun / Math.Max(clock.Elapsed.TotalSeconds, 0.001);
            if (info.State != TransferState.Running) info.State = TransferState.Running;
            Changed?.Invoke(info);
        }
        Set(info, TransferState.Verifying);
        await _hub.SendAsync(info.PeerId, MessageTypes.FileComplete, new JsonObject { ["transferId"] = info.TransferId }).ConfigureAwait(false);
        var ok = await o.Result.Task.WaitAsync(TimeSpan.FromMinutes(5), ct).ConfigureAwait(false);
        Set(info, ok ? TransferState.Completed : TransferState.Failed);
    }

    private async Task WaitForPeerAsync(string peerId, CancellationToken ct)
    {
        var until = DateTime.UtcNow.AddMinutes(2);
        while (_hub.GetPeer(peerId)?.IsConnected != true)
        {
            if (DateTime.UtcNow > until) throw new TimeoutException("peer did not come back");
            await Task.Delay(500, ct).ConfigureAwait(false);
        }
    }

    public void Pause(string id, bool paused) { if (_outgoing.TryGetValue(id, out var o)) o.Paused = paused; }

    public async Task CancelAsync(string id)
    {
        if (_outgoing.TryGetValue(id, out var o)) await o.Cts.CancelAsync();
        else if (_incoming.TryRemove(id, out var i))
        {
            i.Rx.Abort(deletePartial: true);
            Set(i.Info, TransferState.Cancelled);
            await _hub.SendAsync(i.Info.PeerId, MessageTypes.FileCancel, new JsonObject { ["transferId"] = id });
        }
    }

    // ------------------------------------------------------------ incoming

    private void OnMessage(PeerSession peer, Envelope e)
    {
        var id = e.Str("transferId");
        switch (e.Type)
        {
            case MessageTypes.FileStart: _ = HandleStartAsync(peer, e); break;
            case MessageTypes.FileAccept:
                if (_outgoing.TryGetValue(id, out var oa)) oa.Accepted.TrySetResult(e.Long("resumeFrom"));
                break;
            case MessageTypes.FileResult:
                if (_outgoing.TryGetValue(id, out var or)) or.Result.TrySetResult(e.Bool("ok"));
                break;
            case MessageTypes.FilePause:
                if (_outgoing.TryGetValue(id, out var op)) op.Paused = e.Bool("paused", true);
                break;
            case MessageTypes.FileCancel:
                if (_outgoing.TryGetValue(id, out var oc)) { oc.Accepted.TrySetResult(-1); oc.Cts.Cancel(); }
                if (_incoming.TryRemove(id, out var ic)) { ic.Rx.Abort(true); Set(ic.Info, TransferState.Cancelled); }
                break;
            case MessageTypes.FileComplete: _ = HandleCompleteAsync(peer, id); break;
        }
    }

    private async Task HandleStartAsync(PeerSession peer, Envelope e)
    {
        var id = e.Str("transferId");
        var size = e.Long("size");
        var info = new TransferInfo
        {
            TransferId = id, PeerId = peer.Device.DeviceId, Name = FileReceiver.SafeFileName(e.Str("name")), Size = size,
            Direction = TransferDirection.Incoming, Purpose = e.Str("purpose", "file"),
        };
        if (size > MaxIncomingBytes || !AcceptIncoming(info))
        {
            await _hub.SendAsync(peer.Device.DeviceId, MessageTypes.FileCancel, new JsonObject { ["transferId"] = id, ["reason"] = "declined" });
            return;
        }
        if (_incoming.TryRemove(id, out var prev)) prev.Rx.Dispose(); // resumed attempt: reopen side-car
        var rx = new FileReceiver((JsonObject)e.Payload.DeepClone(), _destinationFor(peer.Device.DeviceId, info));
        info.Transferred = rx.ResumeFrom;
        _incoming[id] = (rx, info, Stopwatch.StartNew(), rx.ResumeFrom);
        Set(info, TransferState.Running);
        await _hub.SendAsync(peer.Device.DeviceId, MessageTypes.FileAccept, new JsonObject { ["transferId"] = id, ["resumeFrom"] = rx.ResumeFrom });
    }

    private void OnBinary(PeerSession peer, JsonObject header, ReadOnlyMemory<byte> data)
    {
        if (header["type"]?.GetValue<string>() != MessageTypes.FileChunk) return;
        var id = header["transferId"]?.GetValue<string>() ?? "";
        if (!_incoming.TryGetValue(id, out var t) || t.Info.PeerId != peer.Device.DeviceId) return;
        try
        {
            t.Rx.WriteChunk(header["offset"]!.GetValue<long>(), data.Span);
            t.Info.Transferred = t.Rx.Received;
            t.Info.BytesPerSecond = (t.Info.Transferred - t.StartOffset) / Math.Max(t.Clock.Elapsed.TotalSeconds, 0.001);
            Changed?.Invoke(t.Info);
        }
        catch (ProtocolException ex)
        {
            t.Info.Error = ex.Message;
            Set(t.Info, TransferState.Failed);
        }
    }

    private async Task HandleCompleteAsync(PeerSession peer, string id)
    {
        if (!_incoming.TryRemove(id, out var t)) return;
        Set(t.Info, TransferState.Verifying);
        bool ok;
        try { t.Info.FinalPath = await t.Rx.CompleteAsync().ConfigureAwait(false); ok = true; }
        catch (ProtocolException ex) { t.Info.Error = ex.Message; ok = false; }
        Set(t.Info, ok ? TransferState.Completed : TransferState.Failed);
        await _hub.SendAsync(peer.Device.DeviceId, MessageTypes.FileResult, new JsonObject
        {
            ["transferId"] = id, ["ok"] = ok, ["sha256"] = t.Rx.ExpectedSha256,
        });
    }

    private void Set(TransferInfo i, TransferState s) { i.State = s; Changed?.Invoke(i); }
}
