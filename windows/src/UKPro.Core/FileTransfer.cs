using System.Security.Cryptography;
using System.Text.Json;
using System.Text.Json.Nodes;

namespace UKPro.Core;

/// <summary>Streams a file in chunks without loading it into memory (PROTOCOL §5).</summary>
public sealed class FileSender
{
    public string TransferId { get; } = Guid.NewGuid().ToString();
    public string Path { get; }
    public string Name { get; }
    public long Size { get; }
    public string Sha256 { get; }
    public int ChunkSize { get; }
    public string Mime { get; }
    public string Purpose { get; }

    private FileSender(string path, string name, long size, string sha, int chunk, string mime, string purpose, string? id)
    {
        Path = path; Name = name; Size = size; Sha256 = sha; ChunkSize = chunk; Mime = mime; Purpose = purpose;
        if (id is not null) TransferId = id;
    }

    public static async Task<FileSender> CreateAsync(string path, int chunkSize = 256 * 1024, string purpose = "file",
        string? mime = null, string? transferId = null, CancellationToken ct = default)
    {
        if (chunkSize is < 16 * 1024 or > 4 * 1024 * 1024) throw new ArgumentOutOfRangeException(nameof(chunkSize));
        var info = new FileInfo(path);
        string sha;
        await using (var fs = new FileStream(path, FileMode.Open, FileAccess.Read, FileShare.Read, 1 << 16, useAsync: true))
            sha = ClipboardResolver.Hex(await SHA256.HashDataAsync(fs, ct));
        return new FileSender(path, info.Name, info.Length, sha, chunkSize, mime ?? MimeTypes.FromName(info.Name), purpose, transferId);
    }

    public JsonObject StartPayload() => new()
    {
        ["transferId"] = TransferId, ["name"] = Name, ["size"] = Size, ["mime"] = Mime,
        ["sha256"] = Sha256, ["chunkSize"] = ChunkSize, ["purpose"] = Purpose,
    };

    /// <summary>Yields complete binary FILE_CHUNK frames starting at <paramref name="fromOffset"/>.</summary>
    public async IAsyncEnumerable<(long Offset, int Length, byte[] Frame)> ReadFramesAsync(long fromOffset,
        [System.Runtime.CompilerServices.EnumeratorCancellation] CancellationToken ct = default)
    {
        await using var fs = new FileStream(Path, FileMode.Open, FileAccess.Read, FileShare.Read, 1 << 16, useAsync: true);
        fs.Seek(fromOffset, SeekOrigin.Begin);
        var buf = new byte[ChunkSize];
        long offset = fromOffset;
        while (offset < Size)
        {
            ct.ThrowIfCancellationRequested();
            int want = (int)Math.Min(ChunkSize, Size - offset), got = 0;
            while (got < want)
            {
                int n = await fs.ReadAsync(buf.AsMemory(got, want - got), ct);
                if (n == 0) throw new IOException("file shrank during transfer");
                got += n;
            }
            var header = new JsonObject
            {
                ["type"] = MessageTypes.FileChunk, ["transferId"] = TransferId, ["offset"] = offset, ["length"] = got,
            };
            yield return (offset, got, BinaryFrame.Encode(header, buf.AsSpan(0, got)));
            offset += got;
        }
    }
}

/// <summary>Receives a file into &lt;name&gt;.ukpart with a resumable side-car, verifies SHA-256, then renames.</summary>
public sealed class FileReceiver : IDisposable
{
    public string TransferId { get; }
    public string Name { get; }
    public long Size { get; }
    public string ExpectedSha256 { get; }
    public string Purpose { get; }
    public long Received { get; private set; }
    public string PartPath { get; }
    private readonly string _metaPath;
    private readonly string _destDir;
    private FileStream? _stream;
    private long _lastPersist;

    public FileReceiver(JsonObject start, string destinationDir)
    {
        TransferId = start["transferId"]!.GetValue<string>();
        Name = SafeFileName(start["name"]!.GetValue<string>());
        Size = start["size"]!.GetValue<long>();
        ExpectedSha256 = start["sha256"]!.GetValue<string>();
        Purpose = start["purpose"]?.GetValue<string>() ?? "file";
        if (Size < 0) throw new ProtocolException("BAD_MESSAGE", "negative size");
        if (ExpectedSha256.Length != 64 || !ExpectedSha256.All(Uri.IsHexDigit))
            throw new ProtocolException("BAD_MESSAGE", "bad sha256");
        ExpectedSha256 = ExpectedSha256.ToLowerInvariant();
        _destDir = destinationDir;
        Directory.CreateDirectory(destinationDir);
        PartPath = System.IO.Path.Combine(destinationDir, $"{Name}.{ExpectedSha256[..12]}.ukpart");
        _metaPath = PartPath + ".json";
        Received = LoadResumeOffset();
    }

    /// <summary>Bytes already stored from an interrupted attempt (sent in FILE_ACCEPT.resumeFrom).</summary>
    public long ResumeFrom => Received;

    private long LoadResumeOffset()
    {
        try
        {
            if (!File.Exists(PartPath) || !File.Exists(_metaPath)) return 0;
            var meta = JsonNode.Parse(File.ReadAllText(_metaPath))!;
            if (meta["sha256"]?.GetValue<string>() != ExpectedSha256 || meta["size"]?.GetValue<long>() != Size) return 0;
            var stored = Math.Min(meta["received"]!.GetValue<long>(), new FileInfo(PartPath).Length);
            return stored;
        }
        catch (Exception e) when (e is IOException or JsonException or InvalidOperationException) { return 0; }
    }

    public void WriteChunk(long offset, ReadOnlySpan<byte> data)
    {
        if (offset != Received) throw new ProtocolException("BAD_MESSAGE", $"unexpected offset {offset}, have {Received}");
        if (Received + data.Length > Size) throw new ProtocolException("TRANSFER_TOO_LARGE", "more data than announced");
        _stream ??= OpenPart();
        _stream.Write(data);
        Received += data.Length;
        if (Received - _lastPersist >= 4 * 1024 * 1024 || Received == Size) PersistProgress();
    }

    private FileStream OpenPart()
    {
        var fs = new FileStream(PartPath, FileMode.OpenOrCreate, FileAccess.Write, FileShare.None, 1 << 16);
        fs.SetLength(Received);
        fs.Seek(Received, SeekOrigin.Begin);
        return fs;
    }

    private void PersistProgress()
    {
        _stream?.Flush(flushToDisk: true);
        File.WriteAllText(_metaPath, new JsonObject
        {
            ["transferId"] = TransferId, ["sha256"] = ExpectedSha256, ["size"] = Size, ["received"] = Received,
        }.ToJsonString());
        _lastPersist = Received;
    }

    /// <summary>Verifies integrity and moves the file into place. Returns the final path.</summary>
    public async Task<string> CompleteAsync(CancellationToken ct = default)
    {
        if (_stream is null && Size == 0) _stream = OpenPart();
        if (_stream is not null) { await _stream.FlushAsync(ct); await _stream.DisposeAsync(); _stream = null; }
        if (Received != Size) throw new ProtocolException("BAD_MESSAGE", $"incomplete: {Received}/{Size}");
        string actual;
        await using (var fs = new FileStream(PartPath, FileMode.Open, FileAccess.Read, FileShare.Read, 1 << 16, useAsync: true))
            actual = ClipboardResolver.Hex(await SHA256.HashDataAsync(fs, ct));
        if (actual != ExpectedSha256)
        {
            File.Delete(PartPath); File.Delete(_metaPath);
            throw new ProtocolException("HASH_MISMATCH", "SHA-256 mismatch");
        }
        var final = UniquePath(System.IO.Path.Combine(_destDir, Name));
        File.Move(PartPath, final);
        File.Delete(_metaPath);
        return final;
    }

    public void Abort(bool deletePartial)
    {
        _stream?.Dispose(); _stream = null;
        if (deletePartial) { TryDelete(PartPath); TryDelete(_metaPath); }
        else if (Received > 0) PersistProgress();
    }

    public void Dispose() => Abort(deletePartial: false);

    private static void TryDelete(string p) { try { File.Delete(p); } catch (IOException) { } }

    public static string SafeFileName(string name)
    {
        var n = System.IO.Path.GetFileName(name.Replace('\\', '/'));
        foreach (var c in System.IO.Path.GetInvalidFileNameChars().Concat(['<', '>', ':', '"', '|', '?', '*']))
            n = n.Replace(c, '_');
        n = n.Trim().TrimEnd('.');
        return string.IsNullOrEmpty(n) || n is "." or ".." ? "file" : n;
    }

    private static string UniquePath(string p)
    {
        if (!File.Exists(p)) return p;
        var dir = System.IO.Path.GetDirectoryName(p)!;
        var stem = System.IO.Path.GetFileNameWithoutExtension(p);
        var ext = System.IO.Path.GetExtension(p);
        for (int i = 1; ; i++)
        {
            var c = System.IO.Path.Combine(dir, $"{stem} ({i}){ext}");
            if (!File.Exists(c)) return c;
        }
    }
}

public static class MimeTypes
{
    private static readonly Dictionary<string, string> Map = new(StringComparer.OrdinalIgnoreCase)
    {
        [".pdf"] = "application/pdf", [".doc"] = "application/msword",
        [".docx"] = "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        [".xls"] = "application/vnd.ms-excel",
        [".xlsx"] = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        [".ppt"] = "application/vnd.ms-powerpoint",
        [".pptx"] = "application/vnd.openxmlformats-officedocument.presentationml.presentation",
        [".jpg"] = "image/jpeg", [".jpeg"] = "image/jpeg", [".png"] = "image/png", [".gif"] = "image/gif",
        [".webp"] = "image/webp", [".mp4"] = "video/mp4", [".mkv"] = "video/x-matroska", [".mp3"] = "audio/mpeg",
        [".zip"] = "application/zip", [".txt"] = "text/plain", [".apk"] = "application/vnd.android.package-archive",
    };

    public static string FromName(string name) => Map.GetValueOrDefault(System.IO.Path.GetExtension(name), "application/octet-stream");
}
