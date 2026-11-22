using System.Diagnostics;
using System.Net.WebSockets;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json.Nodes;
using UKPro.Core;

// Dependency-free test runner. Usage:
//   dotnet run                       → run all tests
//   dotnet run -- --gen-vectors DIR  → write shared cross-language test vectors
//   dotnet run -- --interop-hub FILE → start a hub for the Kotlin interop test, write its pairing URI to FILE

if (args.Length >= 2 && args[0] == "--gen-vectors") { Vectors.Write(args[1]); return 0; }
if (args.Length >= 2 && args[0] == "--interop-hub") return await InteropHub.RunAsync(args[1]);

var tests = new (string Name, Func<Task> Body)[]
{
    ("envelope round-trip keeps Bangla and line breaks", Tests.EnvelopeRoundTrip),
    ("envelope rejects unsupported version", Tests.EnvelopeVersion),
    ("all scripts survive envelope + clipboard hashing", Tests.AllScripts),
    ("dead keys compose like Windows (´e → é, ¨u → ü, ~n → ñ)", Tests.DeadKeys),
    ("binary frame round-trip", Tests.BinaryFrameRoundTrip),
    ("reliable: in-order delivery, duplicates dropped", Tests.ReliableInOrder),
    ("reliable: gap buffered then drained", Tests.ReliableGap),
    ("reliable: ACK trims outbox, resend after reconnect", Tests.ReliableResend),
    ("reliable: stale keystrokes become NOOP, never replayed", Tests.ReliableStaleInput),
    ("reliable: new peer session resets inbound state", Tests.ReliableNewSession),
    ("mouse moves are an unqueued stream; clicks are reliable but never replayed late", Tests.MouseStream),
    ("pairing: both sides derive the same secret", Tests.PairingAgreement),
    ("pairing: wrong code fails confirmation", Tests.PairingWrongCode),
    ("pairing: code normalisation", Tests.CodeNormalise),
    ("session auth MACs bind nonces and fingerprint", Tests.SessionMacs),
    ("clipboard: echo of applied remote clip is not re-sent", Tests.ClipLoop),
    ("clipboard: concurrent copies converge on same winner", Tests.ClipConvergence),
    ("clipboard: canonical hashes", Tests.ClipHashes),
    ("file: 5 MiB transfer with SHA-256 verification", Tests.FileTransferFull),
    ("file: interrupted transfer resumes from stored offset", Tests.FileTransferResume),
    ("file: corrupted data detected", Tests.FileTransferCorrupt),
    ("file: unsafe names sanitised", Tests.SafeNames),
    ("keycodes: VK mapping", Tests.KeyCodeMap),
    ("hub e2e: pair, auth, text both ways, ACK", Tests.HubEndToEnd),
    ("hub e2e: reconnect delivers queued messages exactly once", Tests.HubReconnect),
    ("hub e2e: bad MAC rejected, unknown device rejected", Tests.HubRejects),
    ("hub e2e: multi-device broadcast reaches every phone", Tests.HubMultiDevice),
};

int failed = 0;
var sw = Stopwatch.StartNew();
foreach (var (name, body) in tests)
{
    var t = Stopwatch.StartNew();
    try { await body(); Console.WriteLine($"  PASS  {name} ({t.ElapsedMilliseconds} ms)"); }
    catch (Exception e) { failed++; Console.WriteLine($"  FAIL  {name}\n        {e.GetType().Name}: {e.Message}\n{e.StackTrace}"); }
}
Console.WriteLine($"\n{tests.Length - failed}/{tests.Length} passed in {sw.ElapsedMilliseconds} ms");
return failed == 0 ? 0 : 1;

static class Assert
{
    public static void True(bool c, string msg = "expected true") { if (!c) throw new Exception(msg); }
    public static void Eq<T>(T expected, T actual, string what = "")
    {
        if (!EqualityComparer<T>.Default.Equals(expected, actual)) throw new Exception($"{what} expected <{expected}> got <{actual}>");
    }
    public static async Task Throws<TEx>(Func<Task> f) where TEx : Exception
    {
        try { await f(); }
        catch (TEx) { return; }
        throw new Exception($"expected {typeof(TEx).Name}");
    }
    public static void Throws<TEx>(Action f) where TEx : Exception
    {
        try { f(); }
        catch (TEx) { return; }
        throw new Exception($"expected {typeof(TEx).Name}");
    }
}

static class Tests
{
    public static Task EnvelopeRoundTrip()
    {
        var e = Envelope.Create(MessageTypes.TextCommit, new JsonObject { ["text"] = "আমি বাংলায় লিখি\r\nline2 😀 \"q\"" });
        e.SourceDeviceId = "a"; e.Seq = 7;
        var json = e.ToJson();
        Assert.True(json.Contains("আমি"), "Bangla should be emitted unescaped");
        var p = Envelope.Parse(json);
        Assert.Eq(e.Str("text"), p.Str("text"), "text");
        Assert.Eq(7L, p.Seq, "seq"); Assert.Eq(e.EventId, p.EventId, "eventId"); Assert.Eq("a", p.SourceDeviceId);
        return Task.CompletedTask;
    }

    public static readonly string[] Samples =
    [
        "English", "বাংলা: আমি বাংলায় গান গাই, ক্ষ জ্ঞ", "हिन्दी: नमस्ते दुनिया", "اردو: خوش آمدید", "العربية: مرحبا بالعالم",
        "Русский: Привет, мир", "Ελληνικά: Γειά σου", "中文: 你好世界", "日本語: こんにちは、カタカナ", "한국어: 안녕하세요",
        "ไทย: สวัสดี", "עברית: שלום", "Tiếng Việt: Xin chào", "Español: ¡Hola, señor! Ça va? Grüße", "தமிழ்: வணக்கம்",
        "Emoji: 😀👍🏽👨‍👩‍👧‍👦🇧🇩", "Math: ∑∫√∞ ≠ ≤", "Rare: 𠜎𠜱 (non-BMP CJK)",
    ];

    public static Task AllScripts()
    {
        foreach (var t in Samples)
        {
            var e = Envelope.Create(MessageTypes.ClipboardText, new JsonObject { ["text"] = t + "\r\nline 2" });
            var back = Envelope.Parse(e.ToJson()).Str("text");
            Assert.Eq(t + "\r\nline 2", back, "round-trip");
            Assert.Eq(ClipboardResolver.HashText(t + "\nline 2"), ClipboardResolver.HashText(back), "hash");
            Assert.True(TextUtil.IsTypeable(t), "typeable " + t);
        }
        Assert.Eq(1, TextUtil.Graphemes("👨‍👩‍👧‍👦"), "family emoji is one grapheme");
        Assert.True(TextUtil.Graphemes("ক্ষা") <= 2, "bangla conjunct is not split per code unit");
        return Task.CompletedTask;
    }

    public static Task DeadKeys()
    {
        Assert.Eq("é", TextUtil.ComposeDeadKey('´', "e"));
        Assert.Eq("Ü", TextUtil.ComposeDeadKey('¨', "U"));
        Assert.Eq("ñ", TextUtil.ComposeDeadKey('~', "n"));
        Assert.Eq("ç", TextUtil.ComposeDeadKey('¸', "c"));
        Assert.Eq("š", TextUtil.ComposeDeadKey('ˇ', "s"));
        Assert.Eq("â", TextUtil.ComposeDeadKey('^', "a"));
        Assert.Eq("^", TextUtil.ComposeDeadKey('^', " "));
        Assert.Eq("´x", TextUtil.ComposeDeadKey('´', "x"));
        return Task.CompletedTask;
    }

    public static Task EnvelopeVersion()
    {
        var ex = new ProtocolException("", "");
        try { Envelope.Parse("{\"v\":2,\"type\":\"PING\",\"eventId\":\"x\",\"payload\":{}}"); }
        catch (ProtocolException e) { ex = e; }
        Assert.Eq("UNSUPPORTED_VERSION", ex.Code);
        Assert.Throws<ProtocolException>(() => Envelope.Parse("not json"));
        return Task.CompletedTask;
    }

    public static Task BinaryFrameRoundTrip()
    {
        var data = RandomNumberGenerator.GetBytes(1000);
        var f = BinaryFrame.Encode(new JsonObject { ["type"] = "FILE_CHUNK", ["offset"] = 5 }, data);
        var (h, d) = BinaryFrame.Decode(f);
        Assert.Eq(5L, h["offset"]!.GetValue<long>());
        Assert.True(d.Span.SequenceEqual(data), "data");
        return Task.CompletedTask;
    }

    static Envelope Msg(ReliableChannel tx, string type = MessageTypes.TextCommit, string text = "x") =>
        tx.Stamp(Envelope.Create(type, new JsonObject { ["text"] = text }));

    // Envelopes must be serialised/parsed like on the wire so instances are independent.
    static Envelope Wire(Envelope e) => Envelope.Parse(e.ToJson());

    public static Task ReliableInOrder()
    {
        var tx = new ReliableChannel("A"); var rx = new ReliableChannel("B");
        var m1 = Wire(Msg(tx, text: "1")); var m2 = Wire(Msg(tx, text: "2"));
        Assert.Eq(1, rx.Receive(m1).Count); Assert.Eq(0, rx.Receive(m1).Count, "duplicate");
        Assert.Eq("2", rx.Receive(m2)[0].Str("text"));
        Assert.Eq(0, rx.Receive(Wire(m2)).Count, "duplicate 2");
        return Task.CompletedTask;
    }

    public static Task ReliableGap()
    {
        var tx = new ReliableChannel("A"); var rx = new ReliableChannel("B");
        var ms = Enumerable.Range(1, 5).Select(i => Wire(Msg(tx, text: i.ToString()))).ToList();
        Assert.Eq(1, rx.Receive(ms[0]).Count);
        Assert.Eq(0, rx.Receive(ms[3]).Count); Assert.Eq(0, rx.Receive(ms[2]).Count);
        var d = rx.Receive(ms[1]);
        Assert.Eq("2,3,4", string.Join(',', d.Select(x => x.Str("text"))));
        Assert.Eq(1, rx.Receive(ms[4]).Count);
        return Task.CompletedTask;
    }

    public static Task ReliableResend()
    {
        var tx = new ReliableChannel("A");
        for (int i = 0; i < 10; i++) Msg(tx, MessageTypes.ClipboardText);
        tx.Stamp(Envelope.Create(MessageTypes.Ping)); // unreliable: not kept
        Assert.Eq(10, tx.OutboxCount);
        tx.OnAck(4);
        Assert.Eq(6, tx.OutboxCount);
        var resend = tx.ResendAfterReconnect(tx.LocalSessionId, 7);
        Assert.Eq("8,9,10", string.Join(',', resend.Select(e => e.Seq)));
        var all = tx.ResendAfterReconnect("other-session", 0); // peer lost our session → everything unacked
        Assert.Eq(3, all.Count);
        return Task.CompletedTask;
    }

    public static Task ReliableStaleInput()
    {
        long now = 1_000_000;
        var tx = new ReliableChannel("A", clockMs: () => now);
        Msg(tx, MessageTypes.TextCommit, "old keystrokes");
        Msg(tx, MessageTypes.ClipboardText, "clip");
        now += 11_000;
        Msg(tx, MessageTypes.KeyDown, "fresh");
        var r = tx.ResendAfterReconnect(tx.LocalSessionId, 0);
        Assert.Eq("NOOP,CLIPBOARD_TEXT,KEY_DOWN", string.Join(',', r.Select(e => e.Type)));
        var rx = new ReliableChannel("B");
        var delivered = r.SelectMany(e => rx.Receive(Wire(e))).ToList();
        Assert.Eq("CLIPBOARD_TEXT,KEY_DOWN", string.Join(',', delivered.Select(e => e.Type)));
        Assert.Eq(3L, rx.LastDelivered);
        return Task.CompletedTask;
    }

    public static Task ReliableNewSession()
    {
        var tx1 = new ReliableChannel("A"); var rx = new ReliableChannel("B");
        rx.Receive(Wire(Msg(tx1))); rx.Receive(Wire(Msg(tx1)));
        var tx2 = new ReliableChannel("A"); // app restarted
        Assert.Eq(1, rx.Receive(Wire(Msg(tx2))).Count);
        Assert.Eq(1L, rx.LastDelivered);
        return Task.CompletedTask;
    }

    public static Task MouseStream()
    {
        long now = 1_000_000;
        var tx = new ReliableChannel("A", clockMs: () => now); var rx = new ReliableChannel("B");
        var m1 = Wire(tx.Stamp(Envelope.Create(MessageTypes.MouseMove, new JsonObject { ["dx"] = 5, ["dy"] = -3 })));
        var m2 = Wire(tx.Stamp(Envelope.Create(MessageTypes.MouseMove, new JsonObject { ["dx"] = 5, ["dy"] = -3 })));
        Assert.Eq(0L, m1.Seq, "moves are not sequenced");
        Assert.Eq(0, tx.OutboxCount, "moves are never queued for replay");
        Assert.Eq(1, rx.Receive(m1).Count); Assert.Eq(1, rx.Receive(m2).Count);
        tx.Stamp(Envelope.Create(MessageTypes.MouseButton, new JsonObject { ["button"] = "left", ["action"] = "click" }));
        Assert.Eq(1, tx.OutboxCount, "clicks are reliable");
        now += 11_000;
        Assert.Eq("NOOP", tx.ResendAfterReconnect(tx.LocalSessionId, 0)[0].Type, "a click older than 10 s is never replayed");
        return Task.CompletedTask;
    }

    public static Task PairingAgreement()
    {
        using var s = PairingCrypto.NewKey(); using var c = PairingCrypto.NewKey();
        string sp = PairingCrypto.ExportPublic(s), cp = PairingCrypto.ExportPublic(c), code = PairingCrypto.NewCode();
        var ks = PairingCrypto.DeriveSecret(PairingCrypto.SharedSecret(s, cp), code, "hub", "phone");
        var kc = PairingCrypto.DeriveSecret(PairingCrypto.SharedSecret(c, sp), code.ToLowerInvariant(), "hub", "phone");
        Assert.True(ks.SequenceEqual(kc), "secrets differ");
        var t = PairingCrypto.Transcript("hub", "phone", sp, cp, "fp");
        Assert.Eq(PairingCrypto.Confirm(ks, "client-confirm", t), PairingCrypto.Confirm(kc, "client-confirm", t));
        Assert.Eq(6, PairingCrypto.VerificationNumber(t).Length);
        return Task.CompletedTask;
    }

    public static Task PairingWrongCode()
    {
        using var s = PairingCrypto.NewKey(); using var c = PairingCrypto.NewKey();
        string sp = PairingCrypto.ExportPublic(s), cp = PairingCrypto.ExportPublic(c);
        var ks = PairingCrypto.DeriveSecret(PairingCrypto.SharedSecret(s, cp), "ABCD1234", "hub", "phone");
        var kc = PairingCrypto.DeriveSecret(PairingCrypto.SharedSecret(c, sp), "ABCD1235", "hub", "phone");
        Assert.True(!ks.SequenceEqual(kc));
        return Task.CompletedTask;
    }

    public static Task CodeNormalise()
    {
        Assert.Eq("AB01-0".Replace("-", "") + "1", PairingCrypto.NormalizeCode("ab0i-ol"));
        Assert.Eq(8, PairingCrypto.NewCode().Length);
        return Task.CompletedTask;
    }

    public static Task SessionMacs()
    {
        var k = RandomNumberGenerator.GetBytes(32);
        string a = SessionAuth.NewNonce(), b = SessionAuth.NewNonce();
        Assert.True(SessionAuth.ServerMac(k, a, b, "fp") != SessionAuth.ClientMac(k, a, b, "fp"), "direction");
        Assert.True(SessionAuth.ServerMac(k, a, b, "fp") != SessionAuth.ServerMac(k, a, b, "fp2"), "channel binding");
        Assert.Eq(32, SessionAuth.SessionKey(k, a, b).Length);
        return Task.CompletedTask;
    }

    public static Task ClipLoop()
    {
        var win = new ClipboardResolver("win"); var phone = new ClipboardResolver("phone");
        var s = phone.OnLocalChange(ClipboardResolver.HashText("hello"))!;
        Assert.Eq(ClipboardResolver.RemoteDecision.Apply, win.OnRemote(s));
        // Windows writes it to the OS clipboard → WM_CLIPBOARDUPDATE → must not bounce back
        Assert.True(win.OnLocalChange(ClipboardResolver.HashText("hello")) is null, "echo must be suppressed");
        Assert.Eq(ClipboardResolver.RemoteDecision.IgnoreDuplicate, win.OnRemote(s));
        return Task.CompletedTask;
    }

    public static Task ClipConvergence()
    {
        var a = new ClipboardResolver("and-1"); var b = new ClipboardResolver("win-1");
        var sa = a.OnLocalChange(ClipboardResolver.HashText("from phone"))!;
        var sb = b.OnLocalChange(ClipboardResolver.HashText("from laptop"))!; // same lamport (1)
        a.OnRemote(sb); b.OnRemote(sa);
        Assert.Eq(a.Current!.ClipId, b.Current!.ClipId, "winner");
        Assert.Eq("win-1", a.Current.Origin, "higher origin wins ties");
        var sc = a.OnLocalChange(ClipboardResolver.HashText("newer"))!; // lamport 2
        Assert.Eq(ClipboardResolver.RemoteDecision.Apply, b.OnRemote(sc));
        return Task.CompletedTask;
    }

    public static Task ClipHashes()
    {
        Assert.Eq(ClipboardResolver.HashText("a\r\nb"), ClipboardResolver.HashText("a\nb"));
        Assert.Eq(ClipboardResolver.HashFiles([("b", 1, "x"), ("a", 2, "y")]), ClipboardResolver.HashFiles([("a", 2, "y"), ("b", 1, "x")]));
        return Task.CompletedTask;
    }

    static string TempDir() { var d = Path.Combine(Path.GetTempPath(), "ukpro-" + Guid.NewGuid().ToString("N")); Directory.CreateDirectory(d); return d; }

    static string RandomFile(string dir, string name, int size)
    {
        var p = Path.Combine(dir, name);
        File.WriteAllBytes(p, RandomNumberGenerator.GetBytes(size));
        return p;
    }

    public static async Task FileTransferFull()
    {
        var dir = TempDir(); var src = RandomFile(dir, "report.pdf", 5 * 1024 * 1024 + 123);
        var tx = await FileSender.CreateAsync(src, 256 * 1024);
        using var rx = new FileReceiver(tx.StartPayload(), Path.Combine(dir, "in"));
        Assert.Eq(0L, rx.ResumeFrom);
        int chunks = 0;
        await foreach (var (_, _, frame) in tx.ReadFramesAsync(0))
        {
            var (h, d) = BinaryFrame.Decode(frame);
            rx.WriteChunk(h["offset"]!.GetValue<long>(), d.Span); chunks++;
        }
        var final = await rx.CompleteAsync();
        Assert.Eq(21, chunks, "chunks");
        Assert.True(File.ReadAllBytes(final).AsSpan().SequenceEqual(File.ReadAllBytes(src)), "content");
        Assert.Eq("application/pdf", tx.Mime);
    }

    public static async Task FileTransferResume()
    {
        var dir = TempDir(); var src = RandomFile(dir, "video.mp4", 3 * 1024 * 1024);
        var tx = await FileSender.CreateAsync(src, 64 * 1024);
        var dest = Path.Combine(dir, "in");
        var rx1 = new FileReceiver(tx.StartPayload(), dest);
        int n = 0;
        await foreach (var (_, _, frame) in tx.ReadFramesAsync(0))
        {
            var (h, d) = BinaryFrame.Decode(frame);
            rx1.WriteChunk(h["offset"]!.GetValue<long>(), d.Span);
            if (++n == 20) break; // connection lost
        }
        rx1.Dispose();
        using var rx2 = new FileReceiver(tx.StartPayload(), dest);
        Assert.Eq(20L * 64 * 1024, rx2.ResumeFrom, "resume offset");
        await foreach (var (_, _, frame) in tx.ReadFramesAsync(rx2.ResumeFrom))
        {
            var (h, d) = BinaryFrame.Decode(frame);
            rx2.WriteChunk(h["offset"]!.GetValue<long>(), d.Span);
        }
        var final = await rx2.CompleteAsync();
        Assert.True(File.ReadAllBytes(final).AsSpan().SequenceEqual(File.ReadAllBytes(src)), "content after resume");
    }

    public static async Task FileTransferCorrupt()
    {
        var dir = TempDir(); var src = RandomFile(dir, "a.zip", 100_000);
        var tx = await FileSender.CreateAsync(src, 16 * 1024);
        using var rx = new FileReceiver(tx.StartPayload(), Path.Combine(dir, "in"));
        await foreach (var (_, _, frame) in tx.ReadFramesAsync(0))
        {
            var (h, d) = BinaryFrame.Decode(frame);
            var copy = d.ToArray(); copy[0] ^= 0xFF;
            rx.WriteChunk(h["offset"]!.GetValue<long>(), copy);
        }
        await Assert.Throws<ProtocolException>(() => rx.CompleteAsync());
    }

    public static Task SafeNames()
    {
        Assert.Eq("passwd", FileReceiver.SafeFileName("../../etc/passwd"));
        Assert.Eq("evil.exe", FileReceiver.SafeFileName("C:\\Windows\\evil.exe"));
        Assert.Eq("a_b.txt", FileReceiver.SafeFileName("a:b.txt"));
        Assert.Eq("file", FileReceiver.SafeFileName(".."));
        return Task.CompletedTask;
    }

    public static Task KeyCodeMap()
    {
        Assert.Eq((ushort)0x41, KeyCodes.CodeToVk["KeyA"]);
        Assert.Eq((ushort)0x7B, KeyCodes.CodeToVk["F12"]);
        Assert.Eq("Enter", KeyCodes.VkToCode[0x0D]);
        Assert.Eq("MetaLeft", KeyCodes.VkToCode[0x5B]);
        Assert.True(KeyCodes.ExtendedVk.Contains(KeyCodes.CodeToVk["ArrowLeft"]));
        return Task.CompletedTask;
    }

    // ---------------------------------------------------------------- hub end-to-end

    static async Task<(UkHub Hub, PairingOffer Offer)> StartHub(InMemoryTrustStore store, bool approve = true)
    {
        var hub = new UkHub(new HubOptions
        {
            DeviceId = "win-test", Name = "Test Laptop", Certificate = Certificates.CreateSelfSigned("test"),
            Port = 0 /* OS-assigned: CI runners reserve port blocks */, PingInterval = TimeSpan.FromMilliseconds(500),
        }, store) { ApprovePairing = _ => Task.FromResult(approve) };
        hub.Start();
        await Task.Delay(50);
        return (hub, hub.CreatePairingOffer());
    }

    public static async Task HubEndToEnd()
    {
        var store = new InMemoryTrustStore();
        var (hub, offer) = await StartHub(store);
        await using var _ = hub;
        var got = new List<Envelope>(); var gotSignal = new SemaphoreSlim(0);
        hub.MessageReceived += (_, e) => { lock (got) got.Add(e); gotSignal.Release(); };

        var phone = new TestClient("and-phone1", hub.Fingerprint, hub.Port);
        var secret = await phone.PairAsync(offer, hub.DeviceId);
        Assert.Eq(1, store.All().Count, "stored");
        await phone.AuthAsync(secret);
        await phone.SendReliableAsync(MessageTypes.TextCommit, new JsonObject { ["text"] = "হ্যালো Windows" });
        Assert.True(await gotSignal.WaitAsync(3000), "hub did not receive");
        Assert.Eq("হ্যালো Windows", got[0].Str("text"));

        await hub.SendAsync("and-phone1", MessageTypes.TextCommit, new JsonObject { ["text"] = "hello phone" });
        var m = await phone.NextAsync(MessageTypes.TextCommit);
        Assert.Eq("hello phone", m.Str("text"));
        await phone.AckAsync(m.Seq);
        await Wait(() => hub.GetPeer("and-phone1")!.Channel.OutboxCount == 0, "outbox not trimmed by ACK");
        await phone.NextAsync(MessageTypes.Ping); // keep-alive works
        await phone.DisposeAsync();
    }

    public static async Task HubReconnect()
    {
        var store = new InMemoryTrustStore();
        var (hub, offer) = await StartHub(store);
        await using var _ = hub;
        var phone = new TestClient("and-phone2", hub.Fingerprint, hub.Port);
        var secret = await phone.PairAsync(offer, hub.DeviceId);
        await phone.AuthAsync(secret);
        await hub.SendAsync("and-phone2", MessageTypes.ClipboardText, new JsonObject { ["text"] = "clip 0" });
        Assert.Eq("clip 0", (await phone.NextAsync(MessageTypes.ClipboardText)).Str("text"));
        phone.DropConnection(); // Wi-Fi lost before the ACK went out
        await Wait(() => !hub.GetPeer("and-phone2")!.IsConnected, "disconnect not noticed");

        for (int i = 1; i <= 3; i++)
            await hub.SendAsync("and-phone2", MessageTypes.ClipboardText, new JsonObject { ["text"] = $"clip {i}" });
        // the phone queues messages while offline too
        await phone.SendReliableOfflineAsync(MessageTypes.ClipboardText, new JsonObject { ["text"] = "phone offline clip" });

        var hubGot = new List<string>();
        hub.MessageReceived += (_, e) => { lock (hubGot) hubGot.Add(e.Str("text")); };
        await phone.AuthAsync(secret); // reconnect (e.g. over Bluetooth)
        var texts = new List<string>();
        for (int i = 0; i < 3; i++) texts.Add((await phone.NextAsync(MessageTypes.ClipboardText)).Str("text"));
        Assert.Eq("clip 1,clip 2,clip 3", string.Join(',', texts), "hub->phone resend (clip 0 must not repeat)");
        await Wait(() => { lock (hubGot) return hubGot.Count == 1; }, "phone->hub resend");
        Assert.Eq("phone offline clip", hubGot[0]);
        await phone.AckAsync(phone.LastRecv);

        phone.DropConnection();
        await Wait(() => !hub.GetPeer("and-phone2")!.IsConnected, "disconnect 2");
        var before = phone.RawReceived;
        await phone.AuthAsync(secret);
        await hub.SendAsync("and-phone2", MessageTypes.ClipboardText, new JsonObject { ["text"] = "clip 4" });
        Assert.Eq("clip 4", (await phone.NextAsync(MessageTypes.ClipboardText)).Str("text"));
        Assert.True(phone.RawReceived - before <= 6, $"hub resent already-seen messages ({phone.RawReceived - before} frames)");
        Assert.Eq(1, hubGot.Count, "no duplicate on the hub");
        await phone.DisposeAsync();
    }

    public static async Task HubRejects()
    {
        var store = new InMemoryTrustStore();
        var (hub, offer) = await StartHub(store);
        await using var _ = hub;
        var phone = new TestClient("and-x", hub.Fingerprint, hub.Port);
        await phone.PairAsync(offer, hub.DeviceId);
        await Assert.Throws<Exception>(() => phone.AuthAsync(new byte[32])); // wrong secret
        var stranger = new TestClient("and-unknown", hub.Fingerprint, hub.Port);
        await Assert.Throws<Exception>(() => stranger.AuthAsync(new byte[32]));
        // offer is single-use
        var late = new TestClient("and-late", hub.Fingerprint, hub.Port);
        await Assert.Throws<Exception>(() => late.PairAsync(offer, hub.DeviceId));
        await phone.DisposeAsync(); await stranger.DisposeAsync(); await late.DisposeAsync();
    }

    public static async Task HubMultiDevice()
    {
        var store = new InMemoryTrustStore();
        var (hub, offer) = await StartHub(store);
        await using var _ = hub;
        var phones = new List<TestClient>();
        for (int i = 0; i < 3; i++)
        {
            var o = i == 0 ? offer : hub.CreatePairingOffer();
            var p = new TestClient($"and-multi{i}", hub.Fingerprint, hub.Port);
            await p.AuthAsync(await p.PairAsync(o, hub.DeviceId));
            phones.Add(p);
        }
        await hub.BroadcastAsync(MessageTypes.ClipboardText, new JsonObject { ["text"] = "to all" }, exceptPeerId: "and-multi0");
        Assert.Eq("to all", (await phones[1].NextAsync(MessageTypes.ClipboardText)).Str("text"));
        Assert.Eq("to all", (await phones[2].NextAsync(MessageTypes.ClipboardText)).Str("text"));
        await Assert.Throws<Exception>(() => phones[0].NextAsync(MessageTypes.ClipboardText, 400));
        foreach (var p in phones) await p.DisposeAsync();
    }

    public static async Task Wait(Func<bool> cond, string msg, int ms = 3000)
    {
        var sw = Stopwatch.StartNew();
        while (!cond()) { if (sw.ElapsedMilliseconds > ms) throw new Exception(msg); await Task.Delay(20); }
    }
}

/// <summary>Minimal C# client used only by tests (the real client is the Kotlin one).</summary>
sealed class TestClient(string deviceId, string pinnedFp, int port, string? sessionId = null) : IAsyncDisposable
{
    private ClientWebSocket? _ws;
    private readonly ReliableChannel _ch = new(deviceId, sessionId);
    public string SessionId => _ch.LocalSessionId;
    public string? HubSessionId => _ch.PeerSessionId;
    public long LastRecv => _ch.LastDelivered;

    private async Task<ClientWebSocket> Ws()
    {
        if (_ws is { State: WebSocketState.Open }) return _ws;
        var ws = new ClientWebSocket();
        ws.Options.RemoteCertificateValidationCallback = (_, cert, _, _) =>
            cert is not null && Convert.ToHexString(SHA256.HashData(cert.GetRawCertData())).ToLowerInvariant() == pinnedFp;
        await ws.ConnectAsync(new Uri($"wss://127.0.0.1:{port}{WssServer.Path}"), CancellationToken.None);
        return _ws = ws;
    }

    public async Task<byte[]> PairAsync(PairingOffer offer, string hubId)
    {
        using var k = PairingCrypto.NewKey();
        var pk = PairingCrypto.ExportPublic(k);
        var secret = PairingCrypto.DeriveSecret(PairingCrypto.SharedSecret(k, offer.PublicKey), offer.Code, hubId, deviceId);
        var t = PairingCrypto.Transcript(hubId, deviceId, offer.PublicKey, pk, pinnedFp);
        await SendRaw(MessageTypes.PairRequest, new JsonObject
        {
            ["deviceId"] = deviceId, ["name"] = "Test Phone", ["platform"] = "android", ["pk"] = pk,
            ["confirm"] = PairingCrypto.Confirm(secret, "client-confirm", t),
        });
        var r = await NextRawAsync();
        if (r.Type != MessageTypes.PairAccepted) throw new Exception("pair rejected: " + r.Str("reason"));
        if (r.Str("confirm") != PairingCrypto.Confirm(secret, "server-confirm", t)) throw new Exception("server confirm bad");
        return secret;
    }

    /// <summary>Authenticates; reuses this client's reliable-channel state like the real app does after a reconnect.</summary>
    public async Task AuthAsync(byte[] secret)
    {
        var nC = SessionAuth.NewNonce();
        await SendRaw(MessageTypes.AuthHello, new JsonObject
        {
            ["deviceId"] = deviceId, ["nonce"] = nC, ["sessionId"] = _ch.LocalSessionId,
            ["peerSessionId"] = _ch.PeerSessionId ?? "", ["lastRecvSeq"] = _ch.LastDelivered,
        });
        var c = await NextRawAsync();
        if (c.Type != MessageTypes.AuthChallenge) throw new Exception("auth: " + c.Type + " " + c.Str("reason"));
        var nS = c.Str("nonce");
        if (c.Str("mac") != SessionAuth.ServerMac(secret, nC, nS, pinnedFp)) throw new Exception("server MAC bad");
        await SendRaw(MessageTypes.AuthResponse, new JsonObject { ["mac"] = SessionAuth.ClientMac(secret, nC, nS, pinnedFp) });
        var ok = await NextRawAsync();
        if (ok.Type != MessageTypes.AuthOk) throw new Exception("auth failed: " + ok.Str("reason"));
        _ch.BeginPeerSession(c.Str("sessionId"));
        foreach (var e in _ch.ResendAfterReconnect(c.Str("peerSessionId"), c.Long("lastRecvSeq"))) await SendText(e.ToJson());
    }

    /// <summary>Simulates losing the link (Wi-Fi drop) while keeping session state.</summary>
    public void DropConnection() { _ws?.Abort(); _ws?.Dispose(); _ws = null; }

    public int RawReceived { get; private set; }

    public Task SendReliableAsync(string type, JsonObject payload) => SendText(_ch.Stamp(Envelope.Create(type, payload)).ToJson());

    public Task SendReliableOfflineAsync(string type, JsonObject payload) { _ch.Stamp(Envelope.Create(type, payload)); return Task.CompletedTask; }

    public Task AckAsync(long upTo) => SendRaw(MessageTypes.Ack, new JsonObject { ["upTo"] = upTo });

    private Task SendRaw(string type, JsonObject payload) =>
        SendText(new Envelope { Type = type, SourceDeviceId = deviceId, Payload = payload }.ToJson());

    private async Task SendText(string s) =>
        await (await Ws()).SendAsync(Encoding.UTF8.GetBytes(s), WebSocketMessageType.Text, true, CancellationToken.None);

    private async Task<Envelope> NextRawAsync(int timeoutMs = 3000)
    {
        var ws = await Ws();
        using var cts = new CancellationTokenSource(timeoutMs);
        var buf = new byte[1 << 16]; using var ms = new MemoryStream();
        while (true)
        {
            var r = await ws.ReceiveAsync(buf, cts.Token);
            if (r.MessageType == WebSocketMessageType.Close) throw new Exception("closed by hub");
            ms.Write(buf, 0, r.Count);
            if (r.EndOfMessage) { RawReceived++; return Envelope.Parse(Encoding.UTF8.GetString(ms.ToArray())); }
        }
    }

    /// <summary>Next delivered message of the given type, passing everything through the reliable channel.</summary>
    public async Task<Envelope> NextAsync(string type, int timeoutMs = 3000)
    {
        var sw = Stopwatch.StartNew();
        while (true)
        {
            var left = timeoutMs - (int)sw.ElapsedMilliseconds;
            if (left <= 0) throw new Exception("timeout waiting for " + type);
            var e = await NextRawAsync(left);
            if (e.Type == type && e.Seq == 0) return e;
            foreach (var d in _ch.Receive(e)) if (d.Type == type) return d;
        }
    }

    public async ValueTask DisposeAsync()
    {
        if (_ws is { State: WebSocketState.Open })
            try { await _ws.CloseAsync(WebSocketCloseStatus.NormalClosure, "bye", CancellationToken.None); } catch (Exception) { }
        _ws?.Dispose();
    }
}
