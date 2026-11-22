using System.Text.Json.Nodes;
using UKPro.Core;

/// <summary>Writes cross-language vectors so the Kotlin core is checked against the C# core bit-for-bit.</summary>
static class Vectors
{
    public static void Write(string dir)
    {
        Directory.CreateDirectory(dir);
        using var s = PairingCrypto.NewKey(); using var c = PairingCrypto.NewKey();
        string sp = PairingCrypto.ExportPublic(s), cp = PairingCrypto.ExportPublic(c);
        const string code = "7K3M9QXZ", hub = "win-81c0d2", client = "and-2f9a11";
        var fp = "3b4c" + new string('a', 60);
        var z = PairingCrypto.SharedSecret(s, cp);
        var secret = PairingCrypto.DeriveSecret(z, code, hub, client);
        var t = PairingCrypto.Transcript(hub, client, sp, cp, fp);
        string nC = SessionAuth.NewNonce(), nS = SessionAuth.NewNonce();
        var v = new JsonObject
        {
            ["serverD"] = Base64Url.Encode(PairingCrypto.ExportPrivateD(s)), ["serverPk"] = sp,
            ["clientD"] = Base64Url.Encode(PairingCrypto.ExportPrivateD(c)), ["clientPk"] = cp,
            ["code"] = "7k3m-9qxz", ["hubId"] = hub, ["clientId"] = client, ["fp"] = fp,
            ["z"] = Base64Url.Encode(z), ["secret"] = Base64Url.Encode(secret), ["transcript"] = t,
            ["clientConfirm"] = PairingCrypto.Confirm(secret, "client-confirm", t),
            ["serverConfirm"] = PairingCrypto.Confirm(secret, "server-confirm", t),
            ["verification"] = PairingCrypto.VerificationNumber(t),
            ["nC"] = nC, ["nS"] = nS,
            ["serverMac"] = SessionAuth.ServerMac(secret, nC, nS, fp),
            ["clientMac"] = SessionAuth.ClientMac(secret, nC, nS, fp),
            ["sessionKey"] = Base64Url.Encode(SessionAuth.SessionKey(secret, nC, nS)),
            ["text"] = "আমি বাংলায় লিখি\r\nline 2 😀",
            ["textHash"] = ClipboardResolver.HashText("আমি বাংলায় লিখি\r\nline 2 😀"),
            ["filesHash"] = ClipboardResolver.HashFiles([("b.pdf", 10, "aa"), ("a.docx", 20, "bb")]),
            ["scripts"] = new JsonArray(Tests.Samples.Select(x => (JsonNode)x).ToArray()),
            ["scriptHashes"] = new JsonArray(Tests.Samples.Select(x => (JsonNode)ClipboardResolver.HashText(x + "\r\nline 2")).ToArray()),
        };
        File.WriteAllText(Path.Combine(dir, "crypto-v1.json"), v.ToJsonString(new() { WriteIndented = true, Encoder = System.Text.Encodings.Web.JavaScriptEncoder.UnsafeRelaxedJsonEscaping }));
        Console.WriteLine("vectors written to " + dir);
    }
}

/// <summary>
/// Hub process for the Kotlin↔C# interop test (interop/run.sh). Auto-approves pairing, echoes input
/// and clipboard messages, receives a file then sends it back, exits on REMOTE_ACTION INTEROP_STOP.
/// </summary>
static class InteropHub
{
    public static async Task<int> RunAsync(string uriFile)
    {
        var dir = Path.Combine(Path.GetTempPath(), "ukpro-interop-" + Guid.NewGuid().ToString("N")[..8]);
        await using var hub = new UkHub(new HubOptions
        {
            DeviceId = "win-interop", Name = "Interop Laptop", Certificate = Certificates.CreateSelfSigned("interop"),
            Port = 47850, PingInterval = TimeSpan.FromSeconds(1),
        }, new InMemoryTrustStore()) { ApprovePairing = i => { Console.WriteLine($"[hub] approve {i.Name} code {i.VerificationNumber}"); return Task.FromResult(true); } };
        var transfers = new TransferManager(hub, (_, _) => dir);
        var stop = new TaskCompletionSource();
        var clip = new ClipboardResolver(hub.DeviceId);
        hub.Log += m => Console.WriteLine("[hub] " + m);
        transfers.Changed += i =>
        {
            if (i.State is TransferState.Completed or TransferState.Failed)
                Console.WriteLine($"[hub] transfer {i.Direction} {i.Name} {i.State} {i.Error}");
            if (i is { Direction: TransferDirection.Incoming, State: TransferState.Completed })
                _ = Task.Run(async () =>
                {
                    var back = await transfers.SendFileAsync(i.PeerId, i.FinalPath!, 64 * 1024);
                    Console.WriteLine($"[hub] sent back: {back.State}");
                });
        };
        hub.MessageReceived += (peer, e) =>
        {
            switch (e.Type)
            {
                case MessageTypes.TextCommit or MessageTypes.CompositionCommit or MessageTypes.KeyDown or MessageTypes.KeyUp
                    or MessageTypes.MediaControl:
                    _ = hub.SendAsync(peer.Device.DeviceId, e.Type, (JsonObject)e.Payload.DeepClone());
                    break;
                case MessageTypes.ClipboardText:
                    var st = new ClipStamp(e.Str("clipId"), e.Str("hash"), e.Long("lamport"), e.Str("origin"));
                    var ok = ClipboardResolver.HashText(e.Str("text")) == st.Hash;
                    var decision = clip.OnRemote(st);
                    _ = hub.SendAsync(peer.Device.DeviceId, MessageTypes.SyncConfig, new JsonObject
                    {
                        ["clipDecision"] = decision.ToString(), ["hashOk"] = ok,
                    });
                    break;
                case MessageTypes.RemoteAction when e.Str("action") == "INTEROP_NEW_OFFER":
                    var o2 = hub.CreatePairingOffer();
                    _ = hub.SendAsync(peer.Device.DeviceId, MessageTypes.SyncConfig, new JsonObject { ["code"] = o2.DisplayCode });
                    break;
                case MessageTypes.RemoteAction when e.Str("action") == "INTEROP_STOP":
                    stop.TrySetResult();
                    break;
            }
        };
        hub.Start();
        var offer = hub.CreatePairingOffer();
        await File.WriteAllTextAsync(uriFile, offer.ToUri(["127.0.0.1"], hub.Port, hub.Fingerprint, hub.DeviceId, hub.Name));
        Console.WriteLine($"[hub] listening on {hub.Port}, fp {hub.Fingerprint[..16]}…");
        var finished = await Task.WhenAny(stop.Task, Task.Delay(TimeSpan.FromMinutes(3)));
        return finished == stop.Task ? 0 : 2;
    }
}
