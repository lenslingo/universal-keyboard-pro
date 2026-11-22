using System;
using System.Collections.Concurrent;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Text.Json.Nodes;
using System.Threading.Tasks;
using System.Windows.Threading;
using UKPro.Core;
using UKPro.Desktop.Clipboard;
using UKPro.Desktop.Input;
using UKPro.Desktop.Remote;

namespace UKPro.Desktop.Services;

public sealed record PhoneFocus(bool Editable, bool Password, string App);

/// <summary>
/// Glue between the hub and the Windows-specific modules. Implements the routing rules of
/// PROTOCOL §3 and §8: keyboard both directions (independently switchable), clipboard text/image/files
/// with history, remote control, multi-device targets, pause and the concurrency warning.
/// </summary>
public sealed class SyncEngine : IDisposable
{
    public const long ClipboardInlineTextLimit = 1024 * 1024;
    public const long ClipboardAutoFileLimit = 50L * 1024 * 1024;

    private readonly UkHub _hub;
    private readonly Settings _s;
    private readonly Dispatcher _ui;
    private readonly KeyboardInjector _injector = new();
    private readonly PrivacyGuard _guard;
    private readonly KeyboardCapture _capture;
    private readonly ClipboardService _clipboard;
    private readonly ClipboardResolver _resolver;
    private readonly RemoteControl _remote;
    private readonly ConcurrentDictionary<string, PhoneFocus> _phoneFocus = new();
    private readonly ConcurrentDictionary<string, (ClipStamp Stamp, string Kind, JsonObject Payload)> _pendingClips = new();
    private readonly ConcurrentDictionary<string, List<string>> _clipFilePaths = new();
    private long _lastLocalKeyTicks, _lastRemoteInputTicks, _lastWarningTicks;

    public SyncEngine(UkHub hub, Settings settings, Dispatcher ui)
    {
        _hub = hub; _s = settings; _ui = ui;
        _guard = new PrivacyGuard(settings);
        _resolver = new ClipboardResolver(hub.DeviceId);
        _remote = new RemoteControl(_injector);
        History = new ClipboardHistory(settings);
        Transfers = new TransferManager(hub, (_, info) => info.Purpose == "clipboard"
            ? Directory.CreateDirectory(Path.Combine(AppPaths.Root, "clipboard-in")).FullName
            : Directory.CreateDirectory(_s.DownloadFolder).FullName);
        Transfers.Changed += OnTransferChanged;
        _capture = new KeyboardCapture(OnPhysicalKey);
        _clipboard = new ClipboardService();
        _clipboard.LocalChanged += c => _ = OnLocalClipboardAsync(c);
        hub.MessageReceived += OnMessage;
        hub.PeerConnected += p => _ = OnPeerConnectedAsync(p);
        hub.PeerDisconnected += p => { _phoneFocus.TryRemove(p.Device.DeviceId, out _); StatusChanged?.Invoke(); _ = SendDeviceListAsync(); };
        ApplyCaptureState();
    }

    public ClipboardHistory History { get; }
    public TransferManager Transfers { get; }
    public PrivacyGuard Guard => _guard;
    public string? CaptureError => _capture.StartError;
    public bool Paused { get; private set; }
    public string? LastBlockReason { get; private set; }

    public event Action? StatusChanged;
    /// <summary>Raised (rate-limited) when local and remote input hit this PC at the same time.</summary>
    public event Action<string>? Warning;

    public string? ActiveTarget
    {
        get
        {
            var t = _s.ActiveKeyboardTarget;
            if (t is not null && _hub.GetPeer(t)?.IsConnected == true) return t;
            return _hub.Peers.FirstOrDefault(p => p.IsConnected)?.Device.DeviceId;
        }
        set { _s.ActiveKeyboardTarget = value; _s.Save(); StatusChanged?.Invoke(); }
    }

    public PhoneFocus? FocusOf(string peerId) => _phoneFocus.GetValueOrDefault(peerId);

    public void SetPaused(bool paused)
    {
        Paused = paused;
        ApplyCaptureState();
        _ = BroadcastConfigAsync();
    }

    public void SetDirections(bool androidToWindows, bool windowsToAndroid)
    {
        _s.AndroidToWindows = androidToWindows; _s.WindowsToAndroid = windowsToAndroid; _s.Save();
        ApplyCaptureState();
        _ = BroadcastConfigAsync();
    }

    public void SetClipboardSync(bool on) { _s.ClipboardSync = on; _s.Save(); _ = BroadcastConfigAsync(); }

    /// <summary>Visible indicator is bound to this: the hook only captures while true.</summary>
    public bool CapturingNow => _capture.Enabled;

    private void ApplyCaptureState()
    {
        _capture.Enabled = _s.WindowsToAndroid && !Paused;
        StatusChanged?.Invoke();
    }

    // =================================================================== Windows → Android keyboard

    private void OnPhysicalKey(CapturedKey k)
    {
        _lastLocalKeyTicks = Environment.TickCount64;
        var target = ActiveTarget;
        if (target is null) return;
        var focus = FocusOf(target);
        if (focus is null || !focus.Editable) { SetBlock("no text field focused on phone"); return; }
        if (focus.Password) { SetBlock("password field on phone"); return; }
        var reason = _guard.BlockReason(_guard.Current());
        if (reason is not null) { SetBlock(reason); return; }
        SetBlock(null);
        _ = _hub.SendAsync(target, k.Down ? MessageTypes.KeyDown : MessageTypes.KeyUp, new JsonObject
        {
            ["code"] = k.Code, ["key"] = k.Key, ["mods"] = (int)k.Mods, ["repeat"] = k.Repeat,
        });
    }

    /// <summary>Remote Text Panel: guaranteed Unicode (IME/TSF-composed Bangla) Windows → Android.</summary>
    public Task<bool> SendComposedTextAsync(string text) =>
        ActiveTarget is { } t && !Paused ? _hub.SendAsync(t, MessageTypes.CompositionCommit, new JsonObject { ["text"] = text, ["composer"] = "windows-tsf" })
                                         : Task.FromResult(false);

    /// <summary>Remote Text Panel live mode: Backspace / Enter as real keys on the phone.</summary>
    public async Task SendKeyTapAsync(string code)
    {
        if (ActiveTarget is not { } t || Paused) return;
        await _hub.SendAsync(t, MessageTypes.KeyDown, new JsonObject { ["code"] = code, ["key"] = "", ["mods"] = 0, ["repeat"] = false });
        await _hub.SendAsync(t, MessageTypes.KeyUp, new JsonObject { ["code"] = code, ["key"] = "", ["mods"] = 0, ["repeat"] = false });
    }

    private void SetBlock(string? reason)
    {
        if (LastBlockReason == reason) return;
        LastBlockReason = reason;
        StatusChanged?.Invoke();
    }

    // =================================================================== Android → Windows input

    private string? CanInjectNow()
    {
        if (!_s.AndroidToWindows || Paused) return "FEATURE_DISABLED";
        var c = _guard.Current();
        if (c.SecureDesktop) return "ELEVATED_TARGET";
        if (c.IsElevated) return "ELEVATED_TARGET"; // UIPI would silently drop the input
        return null;
    }

    private void NoteRemoteInput()
    {
        var now = Environment.TickCount64;
        _lastRemoteInputTicks = now;
        if (_s.WindowsToAndroid && now - _lastLocalKeyTicks < 500 && now - _lastWarningTicks > 10_000)
        {
            _lastWarningTicks = now;
            Warning?.Invoke("Phone and laptop keyboard are typing at the same time.");
        }
    }

    private void HandleInput(PeerSession peer, Envelope e)
    {
        var err = CanInjectNow();
        if (err is not null) { _ = ReplyError(peer, err, e.Type); return; }
        NoteRemoteInput();
        switch (e.Type)
        {
            case MessageTypes.TextCommit or MessageTypes.CompositionCommit:
                _injector.TypeText(e.Str("text"));
                break;
            case MessageTypes.KeyDown:
                var mods = (KeyMods)e.Long("mods");
                var key = e.Str("key");
                bool printable = key.Length > 0 && !mods.HasFlag(KeyMods.Ctrl) && !mods.HasFlag(KeyMods.Alt) && !mods.HasFlag(KeyMods.Meta)
                                 && !key.Any(char.IsControl);
                if (printable) _injector.TypeText(key);
                else _injector.Chord(e.Str("code"), mods & (KeyMods.Ctrl | KeyMods.Alt | KeyMods.Shift | KeyMods.Meta));
                break;
            // KEY_UP: chords are self-contained taps, so nothing to release (no stuck modifiers).
        }
    }

    // =================================================================== message routing

    private void OnMessage(PeerSession peer, Envelope e)
    {
        var id = peer.Device.DeviceId;
        switch (e.Type)
        {
            case MessageTypes.TextCommit or MessageTypes.CompositionCommit or MessageTypes.KeyDown or MessageTypes.KeyUp:
                HandleInput(peer, e); break;
            case MessageTypes.InputFocus:
                _phoneFocus[id] = new PhoneFocus(e.Bool("editable"), e.Bool("password"), e.Str("app"));
                StatusChanged?.Invoke(); break;
            case MessageTypes.ClipboardText or MessageTypes.ClipboardImage or MessageTypes.ClipboardFile:
                _ = OnRemoteClipAsync(peer, e); break;
            case MessageTypes.ClipboardFetch:
                _ = SendClipFilesAsync(id, e.Str("clipId")); break;
            case MessageTypes.ClipboardHistoryRequest:
                _ = SendHistoryAsync(id, (int)e.Long("limit", 50)); break;
            case MessageTypes.ClipboardHistoryApply:
                _ui.InvokeAsync(() => ApplyHistory(peer, e.Str("clipId"), e.Bool("paste"))); break;
            case MessageTypes.RemoteAction when _s.RemoteControlEnabled:
                var r = CanInjectNow() is "ELEVATED_TARGET" ? "ELEVATED_TARGET" : _remote.Execute(e.Str("action"), (int)e.Long("repeat", 1));
                if (r is not null) _ = ReplyError(peer, r, e.Type);
                break;
            case MessageTypes.MediaControl when _s.RemoteControlEnabled:
                _remote.Media(e.Str("action"));
                _ = SendStatusAsync(id); break;
            case MessageTypes.VolumeSet when _s.RemoteControlEnabled:
                MasterVolume.Set((int)e.Long("level"));
                _ = SendStatusAsync(id); break;
            case MessageTypes.RemoteAction or MessageTypes.MediaControl or MessageTypes.VolumeSet:
                _ = ReplyError(peer, "FEATURE_DISABLED", e.Type); break;
        }
    }

    private Task ReplyError(PeerSession p, string code, string forType) =>
        _hub.SendAsync(p.Device.DeviceId, MessageTypes.Error, new JsonObject { ["code"] = code, ["for"] = forType });

    private async Task OnPeerConnectedAsync(PeerSession p)
    {
        await SendConfigAsync(p.Device.DeviceId);
        await SendStatusAsync(p.Device.DeviceId);
        await SendDeviceListAsync();
        StatusChanged?.Invoke();
    }

    private JsonObject ConfigPayload() => new()
    {
        ["a2w"] = _s.AndroidToWindows, ["w2a"] = _s.WindowsToAndroid, ["clipboard"] = _s.ClipboardSync,
        ["paused"] = Paused, ["remoteControl"] = _s.RemoteControlEnabled, ["keyboardTarget"] = ActiveTarget,
    };

    private Task SendConfigAsync(string id) => _hub.SendAsync(id, MessageTypes.SyncConfig, ConfigPayload());
    private Task BroadcastConfigAsync() => _hub.BroadcastAsync(MessageTypes.SyncConfig, ConfigPayload());

    private Task SendStatusAsync(string id)
    {
        var vol = MasterVolume.Get();
        var p = new JsonObject
        {
            ["name"] = _hub.Name, ["platform"] = "windows", ["transport"] = _hub.GetPeer(id)?.Transport ?? "wifi",
            ["kbCapture"] = CapturingNow, ["foregroundApp"] = _guard.Current().ProcessName,
        };
        if (vol is { } v) { p["volume"] = v.Level; p["muted"] = v.Muted; }
        return _hub.SendAsync(id, MessageTypes.DeviceStatus, p);
    }

    private Task SendDeviceListAsync()
    {
        var list = new JsonArray(_hub.Peers.Select(p => (JsonNode)new JsonObject
        {
            ["id"] = p.Device.DeviceId, ["name"] = p.Device.Name, ["platform"] = p.Device.Platform, ["connected"] = p.IsConnected,
        }).ToArray());
        return _hub.BroadcastAsync(MessageTypes.DeviceList, new JsonObject { ["devices"] = list });
    }

    // =================================================================== clipboard

    private async Task OnLocalClipboardAsync(ClipContent c)
    {
        if (!_s.ClipboardSync || Paused) return;
        var stamp = _resolver.OnLocalChange(c.Hash);
        if (stamp is null) return; // echo of a clip we just applied
        await BroadcastClipAsync(c, stamp, exceptPeer: null);
    }

    private async Task BroadcastClipAsync(ClipContent c, ClipStamp stamp, string? exceptPeer)
    {
        var basePayload = new JsonObject { ["clipId"] = stamp.ClipId, ["hash"] = stamp.Hash, ["lamport"] = stamp.Lamport, ["origin"] = stamp.Origin };
        var peers = _hub.Peers.Where(p => p.Device.DeviceId != exceptPeer).Select(p => p.Device.DeviceId).ToList();
        switch (c)
        {
            case TextClip t:
                History.AddText(stamp.ClipId, t.Text, stamp.Origin, stamp.Hash);
                if (System.Text.Encoding.UTF8.GetByteCount(t.Text) <= ClipboardInlineTextLimit)
                {
                    basePayload["text"] = t.Text; basePayload["mime"] = "text/plain";
                    foreach (var p in peers) await _hub.SendAsync(p, MessageTypes.ClipboardText, (JsonObject)basePayload.DeepClone());
                }
                else
                {
                    var tmp = Path.Combine(Path.GetTempPath(), $"ukpro-clip-{stamp.ClipId}.txt");
                    await File.WriteAllTextAsync(tmp, t.Text);
                    foreach (var p in peers) await SendClipAsFileAsync(p, MessageTypes.ClipboardText, basePayload, tmp, "text/plain");
                }
                break;
            case ImageClip img when _s.ClipboardImages:
                History.AddImage(stamp.ClipId, img.Png, stamp.Origin, stamp.Hash);
                var png = Path.Combine(Path.GetTempPath(), $"ukpro-clip-{stamp.ClipId}.png");
                await File.WriteAllBytesAsync(png, img.Png);
                basePayload["width"] = img.Width; basePayload["height"] = img.Height;
                foreach (var p in peers) await SendClipAsFileAsync(p, MessageTypes.ClipboardImage, basePayload, png, "image/png");
                break;
            case FileClip f when _s.ClipboardFiles:
                var existing = f.Paths.Where(File.Exists).ToList();
                if (existing.Count == 0) return;
                History.AddFiles(stamp.ClipId, existing, stamp.Origin, stamp.Hash);
                _clipFilePaths[stamp.ClipId] = existing;
                long total = existing.Sum(x => new FileInfo(x).Length);
                basePayload["files"] = new JsonArray(existing.Select(x => (JsonNode)new JsonObject
                {
                    ["name"] = Path.GetFileName(x), ["size"] = new FileInfo(x).Length,
                }).ToArray());
                basePayload["auto"] = total <= ClipboardAutoFileLimit;
                foreach (var p in peers)
                {
                    await _hub.SendAsync(p, MessageTypes.ClipboardFile, (JsonObject)basePayload.DeepClone());
                    if (total <= ClipboardAutoFileLimit) _ = SendClipFilesAsync(p, stamp.ClipId);
                }
                break;
        }
    }

    private async Task SendClipAsFileAsync(string peer, string type, JsonObject basePayload, string path, string mime)
    {
        var transferId = Guid.NewGuid().ToString();
        var p = (JsonObject)basePayload.DeepClone();
        p["mime"] = mime; p["size"] = new FileInfo(path).Length; p["transferId"] = transferId;
        await _hub.SendAsync(peer, type, p);
        _ = Transfers.SendFileAsync(peer, path, purpose: "clipboard", transferId: transferId);
    }

    private async Task SendClipFilesAsync(string peer, string clipId)
    {
        if (!_clipFilePaths.TryGetValue(clipId, out var paths)) return;
        foreach (var path in paths) await Transfers.SendFileAsync(peer, path, purpose: "clipboard-file:" + clipId);
    }

    private async Task OnRemoteClipAsync(PeerSession peer, Envelope e)
    {
        if (!_s.ClipboardSync || Paused) return;
        var stamp = new ClipStamp(e.Str("clipId"), e.Str("hash"), e.Long("lamport"), e.Str("origin"));
        var decision = _resolver.OnRemote(stamp);
        if (decision == ClipboardResolver.RemoteDecision.IgnoreDuplicate) return;
        if (decision == ClipboardResolver.RemoteDecision.IgnoreOlder) return;
        switch (e.Type)
        {
            case MessageTypes.ClipboardText when e.Payload.ContainsKey("text"):
                var text = e.Str("text");
                if (ClipboardResolver.HashText(text) != stamp.Hash) return; // corrupted or forged
                if (decision == ClipboardResolver.RemoteDecision.Apply) await _ui.InvokeAsync(() => _clipboard.WriteText(text));
                History.AddText(stamp.ClipId, text, stamp.Origin, stamp.Hash);
                await BroadcastClipAsync(new TextClip(text, stamp.Hash), stamp, exceptPeer: peer.Device.DeviceId); // multi-device relay
                break;
            case MessageTypes.ClipboardText or MessageTypes.ClipboardImage:
                _pendingClips[e.Str("transferId")] = (stamp, e.Type, (JsonObject)e.Payload.DeepClone());
                break;
            case MessageTypes.ClipboardFile:
                _pendingClips["files:" + stamp.ClipId] = (stamp, e.Type, (JsonObject)e.Payload.DeepClone());
                _clipFilePaths[stamp.ClipId] = new List<string>();
                if (!e.Bool("auto")) StatusChanged?.Invoke(); // UI offers "Fetch files"
                break;
        }
    }

    public Task FetchRemoteFilesAsync(string peerId, string clipId) =>
        _hub.SendAsync(peerId, MessageTypes.ClipboardFetch, new JsonObject { ["clipId"] = clipId });

    private void OnTransferChanged(TransferInfo t)
    {
        StatusChanged?.Invoke();
        if (t is not { Direction: TransferDirection.Incoming, State: TransferState.Completed, FinalPath: { } path }) return;
        if (t.Purpose == "clipboard" && _pendingClips.TryRemove(t.TransferId, out var pc))
        {
            if (_resolver.Current?.ClipId != pc.Stamp.ClipId) return; // superseded by a newer copy
            if (pc.Kind == MessageTypes.ClipboardImage)
            {
                var png = File.ReadAllBytes(path);
                _ui.InvokeAsync(() => _clipboard.WriteImage(png, pc.Stamp.Hash));
                History.AddImage(pc.Stamp.ClipId, png, pc.Stamp.Origin, pc.Stamp.Hash);
            }
            else
            {
                var text = File.ReadAllText(path);
                _ui.InvokeAsync(() => _clipboard.WriteText(text));
                History.AddText(pc.Stamp.ClipId, text, pc.Stamp.Origin, pc.Stamp.Hash);
            }
        }
        else if (t.Purpose.StartsWith("clipboard-file:", StringComparison.Ordinal))
        {
            var clipId = t.Purpose["clipboard-file:".Length..];
            if (!_pendingClips.TryGetValue("files:" + clipId, out var fc)) return;
            var list = _clipFilePaths.GetOrAdd(clipId, _ => new List<string>());
            lock (list) list.Add(path);
            var expected = fc.Payload["files"]?.AsArray().Count ?? 0;
            if (list.Count < expected) return;
            _pendingClips.TryRemove("files:" + clipId, out _);
            if (_resolver.Current?.ClipId != clipId) return;
            var copy = list.ToList();
            _ui.InvokeAsync(() => _clipboard.WriteFiles(copy, fc.Stamp.Hash));
            History.AddFiles(clipId, copy, fc.Stamp.Origin, fc.Stamp.Hash);
        }
    }

    // =================================================================== clipboard history

    private Task SendHistoryAsync(string peer, int limit)
    {
        var items = new JsonArray(History.Items.Take(Math.Clamp(limit, 1, 200)).Select(i => (JsonNode)new JsonObject
        {
            ["clipId"] = i.ClipId, ["kind"] = i.Kind, ["preview"] = i.Preview, ["size"] = i.Size,
            ["origin"] = i.Origin, ["ts"] = i.Time.ToUnixTimeMilliseconds(), ["pinned"] = i.Pinned,
        }).ToArray());
        return _hub.SendAsync(peer, MessageTypes.ClipboardHistoryResponse, new JsonObject { ["items"] = items });
    }

    /// <summary>Puts an older history entry back on the Windows clipboard; optionally pastes it.</summary>
    public void ApplyHistory(PeerSession? requester, string clipId, bool paste)
    {
        var it = History.Find(clipId);
        if (it is null) return;
        switch (it.Kind)
        {
            case "text" when it.Text is not null: _clipboard.WriteText(it.Text); break;
            case "image" when History.LoadImage(it) is { } png: _clipboard.WriteImage(png, it.Hash); break;
            case "files" when it.Text is not null:
                var paths = it.Text.Split('\n').Where(File.Exists).ToList();
                if (paths.Count > 0) _clipboard.WriteFiles(paths, it.Hash);
                break;
        }
        if (paste && CanInjectNow() is null) _injector.Chord("KeyV", KeyMods.Ctrl);
        else if (paste && requester is not null) _ = ReplyError(requester, "ELEVATED_TARGET", MessageTypes.ClipboardHistoryApply);
    }

    public void Dispose()
    {
        _capture.Dispose();
        _clipboard.Dispose();
        _guard.Dispose();
    }
}
