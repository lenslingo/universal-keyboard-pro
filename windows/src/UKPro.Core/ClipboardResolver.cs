using System.Security.Cryptography;
using System.Text;

namespace UKPro.Core;

public sealed record ClipStamp(string ClipId, string Hash, long Lamport, string Origin);

/// <summary>
/// Loop prevention and deterministic conflict resolution for clipboard sync (PROTOCOL §3.2).
/// Winner = max (lamport, origin) — every device converges on the same clip.
/// </summary>
public sealed class ClipboardResolver(string localDeviceId)
{
    private readonly object _lock = new();
    private readonly LruSet _seen = new(2048);
    private long _lamport;

    public ClipStamp? Current { get; private set; }

    /// <summary>Local clipboard changed. Returns a stamp to broadcast, or null if this is an echo / unchanged content.</summary>
    public ClipStamp? OnLocalChange(string hash)
    {
        lock (_lock)
        {
            if (Current is not null && Current.Hash == hash) return null; // our own remote apply, or no change
            _lamport++;
            Current = new ClipStamp(Guid.NewGuid().ToString(), hash, _lamport, localDeviceId);
            _seen.Add(Current.ClipId);
            return Current;
        }
    }

    public enum RemoteDecision { Apply, IgnoreDuplicate, IgnoreOlder, SameContent }

    /// <summary>Incoming clip. Only <see cref="RemoteDecision.Apply"/> should touch the OS clipboard.</summary>
    public RemoteDecision OnRemote(ClipStamp s)
    {
        lock (_lock)
        {
            if (!_seen.Add(s.ClipId)) return RemoteDecision.IgnoreDuplicate;
            _lamport = Math.Max(_lamport, s.Lamport);
            if (Current is not null && Compare(s, Current) <= 0) return RemoteDecision.IgnoreOlder;
            var same = Current is not null && Current.Hash == s.Hash;
            Current = s;
            return same ? RemoteDecision.SameContent : RemoteDecision.Apply;
        }
    }

    public static int Compare(ClipStamp a, ClipStamp b)
    {
        var c = a.Lamport.CompareTo(b.Lamport);
        return c != 0 ? c : string.CompareOrdinal(a.Origin, b.Origin);
    }

    // ---- canonical hashes ----
    public static string HashText(string text) =>
        Hex(SHA256.HashData(Encoding.UTF8.GetBytes(NormalizeNewlines(text))));

    public static string NormalizeNewlines(string s) => s.Replace("\r\n", "\n").Replace('\r', '\n');

    public static string HashBytes(ReadOnlySpan<byte> b) => Hex(SHA256.HashData(b));

    public static string HashFiles(IEnumerable<(string Name, long Size, string Sha256)> files)
    {
        var sb = new StringBuilder();
        foreach (var f in files.OrderBy(f => f.Name, StringComparer.Ordinal))
            sb.Append(f.Name).Append('\0').Append(f.Size).Append('\0').Append(f.Sha256).Append('\n');
        return HashText(sb.ToString());
    }

    public static string Hex(ReadOnlySpan<byte> b) => Convert.ToHexString(b).ToLowerInvariant();
}
