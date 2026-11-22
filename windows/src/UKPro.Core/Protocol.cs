using System.Buffers.Binary;
using System.Text;
using System.Text.Encodings.Web;
using System.Text.Json;
using System.Text.Json.Nodes;

namespace UKPro.Core;

/// <summary>All message types of protocol v1 (see docs/PROTOCOL.md §3).</summary>
public static class MessageTypes
{
    public const string KeyDown = "KEY_DOWN", KeyUp = "KEY_UP", TextCommit = "TEXT_COMMIT",
        CompositionCommit = "COMPOSITION_COMMIT", ClipboardText = "CLIPBOARD_TEXT",
        ClipboardImage = "CLIPBOARD_IMAGE", ClipboardFile = "CLIPBOARD_FILE",
        FileStart = "FILE_START", FileChunk = "FILE_CHUNK", FileComplete = "FILE_COMPLETE",
        DeviceStatus = "DEVICE_STATUS", PairRequest = "PAIR_REQUEST", PairAccepted = "PAIR_ACCEPTED",
        Ping = "PING", Pong = "PONG",
        // extensions
        Ack = "ACK", Error = "ERROR", Noop = "NOOP", PairRejected = "PAIR_REJECTED", PairInfo = "PAIR_INFO",
        AuthHello = "AUTH_HELLO", AuthChallenge = "AUTH_CHALLENGE", AuthResponse = "AUTH_RESPONSE",
        AuthOk = "AUTH_OK", AuthFail = "AUTH_FAIL",
        FileAccept = "FILE_ACCEPT", FileResume = "FILE_RESUME", FilePause = "FILE_PAUSE",
        FileCancel = "FILE_CANCEL", FileResult = "FILE_RESULT",
        InputFocus = "INPUT_FOCUS", SyncConfig = "SYNC_CONFIG",
        RemoteAction = "REMOTE_ACTION", MediaControl = "MEDIA_CONTROL", VolumeSet = "VOLUME_SET",
        ClipboardHistoryRequest = "CLIPBOARD_HISTORY_REQUEST",
        ClipboardHistoryResponse = "CLIPBOARD_HISTORY_RESPONSE",
        ClipboardHistoryApply = "CLIPBOARD_HISTORY_APPLY", ClipboardFetch = "CLIPBOARD_FETCH", DeviceList = "DEVICE_LIST",
        MouseMove = "MOUSE_MOVE", MouseButton = "MOUSE_BUTTON", MouseScroll = "MOUSE_SCROLL";

    private static readonly HashSet<string> Unreliable = new()
    {
        Ping, Pong, Ack, FileChunk, AuthHello, AuthChallenge, AuthResponse, AuthOk, AuthFail,
        PairRequest, PairAccepted, PairRejected, PairInfo, DeviceStatus, Error, MouseMove, MouseScroll
    };

    /// <summary>Input events that must not be replayed if they waited too long in the outbox.</summary>
    private static readonly HashSet<string> TimeSensitive = new()
    {
        KeyDown, KeyUp, TextCommit, CompositionCommit, RemoteAction, MediaControl, VolumeSet, MouseButton
    };

    public static bool IsReliable(string type) => !Unreliable.Contains(type);

    /// <summary>High-rate, order-insensitive streams: no outbox, no event-id dedup (a lost move is simply skipped).</summary>
    public static bool IsStream(string type) => type is Ping or Pong or Ack or MouseMove or MouseScroll;
    public static bool IsTimeSensitive(string type) => TimeSensitive.Contains(type);
}

[Flags]
public enum KeyMods { None = 0, Ctrl = 1, Shift = 2, Alt = 4, Meta = 8, CapsLock = 16, AltGr = 32 }

public sealed class ProtocolException(string code, string message) : Exception(message)
{
    public string Code { get; } = code;
}

/// <summary>JSON envelope common to every message.</summary>
public sealed class Envelope
{
    public const int ProtocolVersion = 1;
    private static readonly JsonWriterOptions WriterOptions = new() { Encoder = JavaScriptEncoder.UnsafeRelaxedJsonEscaping };

    public int V { get; set; } = ProtocolVersion;
    public string Type { get; set; } = "";
    public string EventId { get; set; } = Guid.NewGuid().ToString();
    public string SessionId { get; set; } = "";
    public string SourceDeviceId { get; set; } = "";
    public string TargetDeviceId { get; set; } = "*";
    public long Seq { get; set; }
    public long Ts { get; set; } = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
    public JsonObject Payload { get; set; } = new();

    public static Envelope Create(string type, JsonObject? payload = null) => new() { Type = type, Payload = payload ?? new JsonObject() };

    public string ToJson()
    {
        var buffer = new MemoryStream();
        using (var w = new Utf8JsonWriter(buffer, WriterOptions))
        {
            w.WriteStartObject();
            w.WriteNumber("v", V);
            w.WriteString("type", Type);
            w.WriteString("eventId", EventId);
            w.WriteString("sessionId", SessionId);
            w.WriteString("sourceDeviceId", SourceDeviceId);
            w.WriteString("targetDeviceId", TargetDeviceId);
            w.WriteNumber("seq", Seq);
            w.WriteNumber("ts", Ts);
            w.WritePropertyName("payload");
            Payload.WriteTo(w);
            w.WriteEndObject();
        }
        return Encoding.UTF8.GetString(buffer.ToArray());
    }

    public static Envelope Parse(string json)
    {
        JsonObject o;
        try { o = JsonNode.Parse(json) as JsonObject ?? throw new ProtocolException("BAD_MESSAGE", "not an object"); }
        catch (JsonException e) { throw new ProtocolException("BAD_MESSAGE", e.Message); }
        int v = o["v"]?.GetValue<int>() ?? 0;
        if (v != ProtocolVersion) throw new ProtocolException("UNSUPPORTED_VERSION", $"version {v}");
        var type = o["type"]?.GetValue<string>();
        if (string.IsNullOrEmpty(type)) throw new ProtocolException("BAD_MESSAGE", "missing type");
        var payload = o["payload"] as JsonObject ?? new JsonObject();
        o.Remove("payload");
        return new Envelope
        {
            V = v,
            Type = type,
            EventId = o["eventId"]?.GetValue<string>() ?? throw new ProtocolException("BAD_MESSAGE", "missing eventId"),
            SessionId = o["sessionId"]?.GetValue<string>() ?? "",
            SourceDeviceId = o["sourceDeviceId"]?.GetValue<string>() ?? "",
            TargetDeviceId = o["targetDeviceId"]?.GetValue<string>() ?? "*",
            Seq = o["seq"]?.GetValue<long>() ?? 0,
            Ts = o["ts"]?.GetValue<long>() ?? 0,
            Payload = payload,
        };
    }

    public string Str(string key, string def = "") => Payload[key]?.GetValue<string>() ?? def;
    public long Long(string key, long def = 0) => Payload[key] is JsonValue jv && jv.TryGetValue(out long l) ? l : def;
    public bool Bool(string key, bool def = false) => Payload[key] is JsonValue jv && jv.TryGetValue(out bool b) ? b : def;
}

/// <summary>Binary FILE_CHUNK frame: headerLen:u32 BE ‖ header JSON ‖ data.</summary>
public static class BinaryFrame
{
    public static byte[] Encode(JsonObject header, ReadOnlySpan<byte> data)
    {
        var h = Encoding.UTF8.GetBytes(header.ToJsonString());
        var frame = new byte[4 + h.Length + data.Length];
        BinaryPrimitives.WriteUInt32BigEndian(frame, (uint)h.Length);
        h.CopyTo(frame, 4);
        data.CopyTo(frame.AsSpan(4 + h.Length));
        return frame;
    }

    public static (JsonObject Header, ReadOnlyMemory<byte> Data) Decode(ReadOnlyMemory<byte> frame)
    {
        if (frame.Length < 4) throw new ProtocolException("BAD_MESSAGE", "short frame");
        var len = (int)BinaryPrimitives.ReadUInt32BigEndian(frame.Span);
        if (len <= 0 || 4 + len > frame.Length) throw new ProtocolException("BAD_MESSAGE", "bad header length");
        var header = JsonNode.Parse(frame.Span.Slice(4, len)) as JsonObject
                     ?? throw new ProtocolException("BAD_MESSAGE", "bad header");
        return (header, frame[(4 + len)..]);
    }
}

public static class Base64Url
{
    public static string Encode(ReadOnlySpan<byte> b) => Convert.ToBase64String(b).TrimEnd('=').Replace('+', '-').Replace('/', '_');

    public static byte[] Decode(string s)
    {
        var t = s.Replace('-', '+').Replace('_', '/');
        return Convert.FromBase64String(t.PadRight(t.Length + (4 - t.Length % 4) % 4, '='));
    }
}
