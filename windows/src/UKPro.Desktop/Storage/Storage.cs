using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Runtime.InteropServices;
using System.Security.Cryptography.X509Certificates;
using System.Text.Json;
using System.Text.Json.Nodes;
using UKPro.Core;
using UKPro.Desktop.Native;

namespace UKPro.Desktop;

/// <summary>DPAPI (CurrentUser) protection via CryptProtectData — no extra packages needed.</summary>
public static class Dpapi
{
    public static byte[] Protect(byte[] data) => Run(data, protect: true);
    public static byte[] Unprotect(byte[] data) => Run(data, protect: false);

    private static byte[] Run(byte[] input, bool protect)
    {
        var inBlob = new Win32.DATA_BLOB { cbData = input.Length, pbData = Marshal.AllocHGlobal(Math.Max(input.Length, 1)) };
        var outBlob = new Win32.DATA_BLOB();
        try
        {
            Marshal.Copy(input, 0, inBlob.pbData, input.Length);
            bool ok = protect
                ? Win32.CryptProtectData(ref inBlob, "UK Pro", IntPtr.Zero, IntPtr.Zero, IntPtr.Zero, Win32.CRYPTPROTECT_UI_FORBIDDEN, ref outBlob)
                : Win32.CryptUnprotectData(ref inBlob, IntPtr.Zero, IntPtr.Zero, IntPtr.Zero, IntPtr.Zero, Win32.CRYPTPROTECT_UI_FORBIDDEN, ref outBlob);
            if (!ok) throw new System.ComponentModel.Win32Exception(Marshal.GetLastWin32Error());
            var result = new byte[outBlob.cbData];
            Marshal.Copy(outBlob.pbData, result, 0, outBlob.cbData);
            return result;
        }
        finally
        {
            Marshal.FreeHGlobal(inBlob.pbData);
            if (outBlob.pbData != IntPtr.Zero) Win32.LocalFree(outBlob.pbData);
        }
    }
}

public static class AppPaths
{
    public static string Root { get; } = Dir(Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "UKPro"));
    public static string History { get; } = Dir(Path.Combine(Root, "history"));
    public static string DefaultDownloads { get; } = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.UserProfile), "Downloads", "UK Pro");
    private static string Dir(string p) { Directory.CreateDirectory(p); return p; }
}

/// <summary>User settings (plain JSON — contains no secrets).</summary>
public sealed class Settings
{
    public string DeviceId { get; set; } = "win-" + Guid.NewGuid().ToString("N")[..12];
    public string DeviceName { get; set; } = Environment.MachineName;
    public int Port { get; set; } = 47821;
    public bool AndroidToWindows { get; set; } = true;
    public bool WindowsToAndroid { get; set; }            // must be explicitly enabled (PART 5)
    public bool ClipboardSync { get; set; } = true;
    public bool ClipboardImages { get; set; } = true;
    public bool ClipboardFiles { get; set; } = true;
    public bool HistoryEnabled { get; set; } = true;
    public int HistoryMaxItems { get; set; } = 200;
    public int HistoryRetentionDays { get; set; } = 30;
    public bool RemoteControlEnabled { get; set; } = true;
    public string DownloadFolder { get; set; } = AppPaths.DefaultDownloads;
    public string? ActiveKeyboardTarget { get; set; }
    public HashSet<string> ExcludedApps { get; set; } = new(StringComparer.OrdinalIgnoreCase);
    public HashSet<string> TrustedApps { get; set; } = new(StringComparer.OrdinalIgnoreCase) { "notepad.exe", "WINWORD.EXE", "Code.exe" };
    public bool StartWithWindows { get; set; } = true;
    public string ConnectionMode { get; set; } = "auto"; // auto | wifi | bluetooth

    private static string FilePath => Path.Combine(AppPaths.Root, "settings.json");
    private static readonly JsonSerializerOptions Opt = new() { WriteIndented = true };

    public static Settings Load()
    {
        try
        {
            if (File.Exists(FilePath))
            {
                var s = JsonSerializer.Deserialize<Settings>(File.ReadAllText(FilePath)) ?? new Settings();
                s.ExcludedApps = new HashSet<string>(s.ExcludedApps, StringComparer.OrdinalIgnoreCase);
                s.TrustedApps = new HashSet<string>(s.TrustedApps, StringComparer.OrdinalIgnoreCase);
                return s;
            }
        }
        catch (Exception e) when (e is IOException or JsonException) { }
        var fresh = new Settings();
        fresh.Save();
        return fresh;
    }

    public void Save() => File.WriteAllText(FilePath, JsonSerializer.Serialize(this, Opt));
}

/// <summary>Trusted phones; pairing secrets are DPAPI-encrypted at rest.</summary>
public sealed class DpapiTrustStore : ITrustStore
{
    private readonly object _lock = new();
    private readonly Dictionary<string, TrustedDevice> _devices;
    private static string FilePath => Path.Combine(AppPaths.Root, "trusted-devices.bin");

    public DpapiTrustStore()
    {
        _devices = new();
        try
        {
            if (!File.Exists(FilePath)) return;
            var json = JsonNode.Parse(Dpapi.Unprotect(File.ReadAllBytes(FilePath)))!.AsArray();
            foreach (var n in json)
            {
                var d = new TrustedDevice(n!["id"]!.GetValue<string>(), n["name"]!.GetValue<string>(), n["platform"]!.GetValue<string>(),
                    Convert.FromBase64String(n["secret"]!.GetValue<string>()), DateTimeOffset.Parse(n["pairedAt"]!.GetValue<string>()));
                _devices[d.DeviceId] = d;
            }
        }
        catch (Exception e) when (e is IOException or JsonException or System.ComponentModel.Win32Exception or FormatException) { }
    }

    public TrustedDevice? Get(string id) { lock (_lock) return _devices.GetValueOrDefault(id); }
    public IReadOnlyList<TrustedDevice> All() { lock (_lock) return _devices.Values.ToList(); }
    public void Save(TrustedDevice d) { lock (_lock) { _devices[d.DeviceId] = d; Persist(); } }
    public void Remove(string id) { lock (_lock) { _devices.Remove(id); Persist(); } }

    private void Persist()
    {
        var arr = new JsonArray(_devices.Values.Select(d => (JsonNode)new JsonObject
        {
            ["id"] = d.DeviceId, ["name"] = d.Name, ["platform"] = d.Platform,
            ["secret"] = Convert.ToBase64String(d.Secret), ["pairedAt"] = d.PairedAt.ToString("O"),
        }).ToArray());
        File.WriteAllBytes(FilePath, Dpapi.Protect(System.Text.Encoding.UTF8.GetBytes(arr.ToJsonString())));
    }
}

/// <summary>The hub's TLS identity. Created once; private key stored as a DPAPI-encrypted PKCS#12.</summary>
public static class IdentityStore
{
    private static string FilePath => Path.Combine(AppPaths.Root, "identity.pfx.bin");

    public static X509Certificate2 LoadOrCreate(string deviceName)
    {
        try
        {
            if (File.Exists(FilePath))
                return X509CertificateLoader.LoadPkcs12(Dpapi.Unprotect(File.ReadAllBytes(FilePath)), null, X509KeyStorageFlags.UserKeySet);
        }
        catch (Exception e) when (e is IOException or System.Security.Cryptography.CryptographicException or System.ComponentModel.Win32Exception) { }
        var cert = Certificates.CreateSelfSigned(deviceName);
        File.WriteAllBytes(FilePath, Dpapi.Protect(cert.Export(X509ContentType.Pkcs12)));
        return cert;
    }
}

/// <summary>Clipboard history (PROTOCOL §3.3): DPAPI-encrypted index + blobs, retention and pinning.</summary>
public sealed class ClipboardHistory
{
    public sealed record Item(string ClipId, string Kind, string Preview, long Size, string Origin, DateTimeOffset Time, bool Pinned, string? BlobFile, string? Text, string Hash);

    private readonly object _lock = new();
    private readonly List<Item> _items;
    private readonly Settings _settings;
    private static string IndexPath => Path.Combine(AppPaths.History, "index.bin");

    public ClipboardHistory(Settings settings)
    {
        _settings = settings;
        _items = new();
        try
        {
            if (File.Exists(IndexPath))
                _items = JsonSerializer.Deserialize<List<Item>>(Dpapi.Unprotect(File.ReadAllBytes(IndexPath))) ?? new();
        }
        catch (Exception e) when (e is IOException or JsonException or System.ComponentModel.Win32Exception) { }
        Prune();
    }

    public event Action? Changed;
    public IReadOnlyList<Item> Items { get { lock (_lock) return _items.ToList(); } }

    public void AddText(string clipId, string text, string origin, string hash) =>
        Add(new Item(clipId, "text", text.Length > 200 ? text[..200] : text, text.Length, origin, DateTimeOffset.Now, false, null, text, hash));

    public void AddImage(string clipId, byte[] png, string origin, string hash)
    {
        if (!_settings.HistoryEnabled) return;
        var blob = Path.Combine(AppPaths.History, clipId + ".png.bin");
        File.WriteAllBytes(blob, Dpapi.Protect(png));
        Add(new Item(clipId, "image", "Image", png.Length, origin, DateTimeOffset.Now, false, blob, null, hash));
    }

    public void AddFiles(string clipId, IReadOnlyList<string> paths, string origin, string hash) =>
        Add(new Item(clipId, "files", string.Join(", ", paths.Select(Path.GetFileName)), paths.Count, origin, DateTimeOffset.Now, false,
            null, string.Join("\n", paths), hash));

    public byte[]? LoadImage(Item i) => i.BlobFile is { } f && File.Exists(f) ? Dpapi.Unprotect(File.ReadAllBytes(f)) : null;
    public Item? Find(string clipId) { lock (_lock) return _items.FirstOrDefault(i => i.ClipId == clipId); }

    public void SetPinned(string clipId, bool pinned)
    {
        lock (_lock)
        {
            var i = _items.FindIndex(x => x.ClipId == clipId);
            if (i >= 0) _items[i] = _items[i] with { Pinned = pinned };
            Persist();
        }
        Changed?.Invoke();
    }

    public void Delete(string clipId)
    {
        lock (_lock)
        {
            var it = _items.FirstOrDefault(x => x.ClipId == clipId);
            if (it is null) return;
            _items.Remove(it);
            if (it.BlobFile is { } f) File.Delete(f);
            Persist();
        }
        Changed?.Invoke();
    }

    public void Clear(bool keepPinned = true)
    {
        lock (_lock)
        {
            foreach (var it in _items.Where(i => !keepPinned || !i.Pinned).ToList())
            {
                _items.Remove(it);
                if (it.BlobFile is { } f) File.Delete(f);
            }
            Persist();
        }
        Changed?.Invoke();
    }

    private void Add(Item item)
    {
        if (!_settings.HistoryEnabled) return;
        lock (_lock)
        {
            _items.RemoveAll(i => i.Hash == item.Hash && !i.Pinned); // move duplicate content to the top
            _items.Insert(0, item);
            Prune();
            Persist();
        }
        Changed?.Invoke();
    }

    private void Prune()
    {
        var cutoff = DateTimeOffset.Now.AddDays(-_settings.HistoryRetentionDays);
        var remove = _items.Where((i, idx) => !i.Pinned && (i.Time < cutoff || idx >= _settings.HistoryMaxItems)).ToList();
        foreach (var r in remove) { _items.Remove(r); if (r.BlobFile is { } f) try { File.Delete(f); } catch (IOException) { } }
    }

    private void Persist() => File.WriteAllBytes(IndexPath, Dpapi.Protect(JsonSerializer.SerializeToUtf8Bytes(_items)));
}
