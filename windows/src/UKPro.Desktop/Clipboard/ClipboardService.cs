using System;
using System.Collections.Generic;
using System.Collections.Specialized;
using System.IO;
using System.Linq;
using System.Runtime.InteropServices;
using System.Threading.Tasks;
using System.Windows;
using System.Windows.Interop;
using System.Windows.Media.Imaging;
using UKPro.Core;
using UKPro.Desktop.Native;
using WpfClipboard = System.Windows.Clipboard;

namespace UKPro.Desktop.Clipboard;

public abstract record ClipContent(string Hash);
public sealed record TextClip(string Text, string Hash) : ClipContent(Hash);
public sealed record ImageClip(byte[] Png, int Width, int Height, string Hash) : ClipContent(Hash);
public sealed record FileClip(IReadOnlyList<string> Paths, string Hash) : ClipContent(Hash);

/// <summary>
/// Windows clipboard monitor + writer. Uses AddClipboardFormatListener / WM_CLIPBOARDUPDATE on a
/// message-only window, honours the "do not monitor" formats password managers set, and writes
/// CF_UNICODETEXT, PNG + CF_DIBV5 images and CF_HDROP file lists. Must be created on the UI (STA) thread.
/// </summary>
public sealed class ClipboardService : IDisposable
{
    private static readonly string[] PrivateFormats =
        ["ExcludeClipboardContentFromMonitorProcessing", "Clipboard Viewer Ignore"];
    private readonly HwndSource _window;
    private HashSet<string>? _lastWrittenFiles;
    private string? _lastFileSetHash;
    private byte[]? _lastWrittenPng;
    private string? _lastImageHash;

    public ClipboardService()
    {
        var p = new HwndSourceParameters("UKPro clipboard") { ParentWindow = new IntPtr(-3) /* HWND_MESSAGE */, Width = 0, Height = 0 };
        _window = new HwndSource(p);
        _window.AddHook(WndProc);
        if (!Win32.AddClipboardFormatListener(_window.Handle))
            throw new InvalidOperationException("AddClipboardFormatListener failed: " + Marshal.GetLastWin32Error());
    }

    /// <summary>Raised on the UI thread with the new local clipboard content (sensitive content is never raised).</summary>
    public event Action<ClipContent>? LocalChanged;
    public long MaxImageBytes { get; set; } = 32 * 1024 * 1024;

    private IntPtr WndProc(IntPtr hwnd, int msg, IntPtr w, IntPtr l, ref bool handled)
    {
        if (msg == Win32.WM_CLIPBOARDUPDATE) { handled = true; _ = ReadAsync(); }
        return IntPtr.Zero;
    }

    private async Task ReadAsync()
    {
        await Task.Delay(30); // let the source app finish writing all formats
        for (int attempt = 0; attempt < 5; attempt++)
        {
            try
            {
                var c = Read();
                if (c is not null) LocalChanged?.Invoke(c);
                return;
            }
            catch (COMException) { await Task.Delay(50 * (attempt + 1)); } // CLIPBRD_E_CANT_OPEN: another app holds it
            catch (ExternalException) { await Task.Delay(50 * (attempt + 1)); }
        }
    }

    private ClipContent? Read()
    {
        var data = WpfClipboard.GetDataObject();
        if (data is null) return null;
        if (PrivateFormats.Any(data.GetDataPresent)) return null;
        if (data.GetDataPresent("CanIncludeInClipboardHistory") &&
            data.GetData("CanIncludeInClipboardHistory") is MemoryStream ms && ms.Length >= 4 && BitConverter.ToInt32(ms.ToArray(), 0) == 0)
            return null; // e.g. password managers

        if (data.GetDataPresent(DataFormats.FileDrop) && data.GetData(DataFormats.FileDrop) is string[] files && files.Length > 0)
        {
            var set = new HashSet<string>(files, StringComparer.OrdinalIgnoreCase);
            if (_lastWrittenFiles is not null && set.SetEquals(_lastWrittenFiles)) return new FileClip(files, _lastFileSetHash!);
            var meta = files.Where(File.Exists).Select(f => (Path.GetFileName(f), new FileInfo(f).Length, QuickId(f))).ToList();
            return new FileClip(files, ClipboardResolver.HashFiles(meta));
        }
        if (data.GetDataPresent("PNG") && data.GetData("PNG") is MemoryStream png && png.Length <= MaxImageBytes)
        {
            var bytes = png.ToArray();
            var (w, h) = PngSize(bytes);
            if (_lastWrittenPng is not null && bytes.AsSpan().SequenceEqual(_lastWrittenPng))
                return new ImageClip(bytes, w, h, _lastImageHash!); // our own remote write: keep the origin's hash
            return new ImageClip(bytes, w, h, ClipboardResolver.HashBytes(bytes));
        }
        if (WpfClipboard.ContainsImage() && WpfClipboard.GetImage() is { } bmp)
        {
            var enc = new PngBitmapEncoder();
            enc.Frames.Add(BitmapFrame.Create(bmp));
            using var o = new MemoryStream();
            enc.Save(o);
            var bytes = o.ToArray();
            return new ImageClip(bytes, bmp.PixelWidth, bmp.PixelHeight, ClipboardResolver.HashBytes(bytes));
        }
        if (WpfClipboard.ContainsText(TextDataFormat.UnicodeText))
        {
            var t = WpfClipboard.GetText(TextDataFormat.UnicodeText);
            return t.Length == 0 ? null : new TextClip(t, ClipboardResolver.HashText(t));
        }
        return null;
    }

    /// <summary>
    /// Clipboard file identity uses name+size+mtime (not SHA-256) so copying a 4 GB video never stalls the UI;
    /// the real SHA-256 is computed by the transfer engine when the files are actually sent.
    /// </summary>
    private static string QuickId(string path) => File.GetLastWriteTimeUtc(path).Ticks.ToString();

    public void WriteText(string text) => Retry(() => WpfClipboard.SetText(text, TextDataFormat.UnicodeText));

    /// <summary>Writes an image (PNG or JPEG from the phone). <paramref name="clipHash"/> suppresses the echo.</summary>
    public void WriteImage(byte[] imageBytes, string? clipHash = null)
    {
        var dec = BitmapDecoder.Create(new MemoryStream(imageBytes), BitmapCreateOptions.PreservePixelFormat, BitmapCacheOption.OnLoad);
        var frame = dec.Frames[0];
        var png = imageBytes;
        if (imageBytes.Length < 8 || imageBytes[0] != 0x89 || imageBytes[1] != (byte)'P') // not PNG → lossless re-encode
        {
            var enc = new PngBitmapEncoder();
            enc.Frames.Add(BitmapFrame.Create(frame));
            using var o = new MemoryStream();
            enc.Save(o);
            png = o.ToArray();
        }
        _lastWrittenPng = png;
        _lastImageHash = clipHash ?? ClipboardResolver.HashBytes(png);
        var obj = new DataObject();
        obj.SetData("PNG", new MemoryStream(png), false);           // lossless, alpha (Office, browsers, Paint.NET)
        obj.SetData("DeviceIndependentBitmapV5", Dib.FromBitmap(frame), false); // CF_DIBV5 – native apps
        obj.SetImage(frame);                                           // CF_BITMAP / CF_DIB fallback
        Retry(() => WpfClipboard.SetDataObject(obj, true));
    }

    public void WriteFiles(IReadOnlyList<string> paths, string clipHash)
    {
        var list = new StringCollection();
        list.AddRange(paths.ToArray());
        var obj = new DataObject();
        obj.SetFileDropList(list);                                        // CF_HDROP
        obj.SetData("Preferred DropEffect", new MemoryStream(BitConverter.GetBytes(1))); // DROPEFFECT_COPY
        _lastWrittenFiles = new HashSet<string>(paths, StringComparer.OrdinalIgnoreCase);
        _lastFileSetHash = clipHash;
        Retry(() => WpfClipboard.SetDataObject(obj, true));
    }

    private static void Retry(Action a)
    {
        for (int i = 0; ; i++)
        {
            try { a(); return; }
            catch (COMException) when (i < 5) { System.Threading.Thread.Sleep(40 * (i + 1)); }
        }
    }

    private static (int, int) PngSize(byte[] b) =>
        b.Length > 24 ? ((b[16] << 24) | (b[17] << 16) | (b[18] << 8) | b[19], (b[20] << 24) | (b[21] << 16) | (b[22] << 8) | b[23]) : (0, 0);

    public void Dispose()
    {
        Win32.RemoveClipboardFormatListener(_window.Handle);
        _window.Dispose();
    }
}

/// <summary>Builds a CF_DIBV5 (BITMAPV5HEADER + 32-bpp BGRA, bottom-up) payload that keeps alpha.</summary>
internal static class Dib
{
    public static MemoryStream FromBitmap(BitmapSource src)
    {
        var bgra = src.Format == System.Windows.Media.PixelFormats.Bgra32 ? src
            : new FormatConvertedBitmap(src, System.Windows.Media.PixelFormats.Bgra32, null, 0);
        int w = bgra.PixelWidth, h = bgra.PixelHeight, stride = w * 4;
        var pixels = new byte[stride * h];
        bgra.CopyPixels(pixels, stride, 0);
        var ms = new MemoryStream(124 + pixels.Length);
        var bw = new BinaryWriter(ms);
        bw.Write(124); bw.Write(w); bw.Write(h);           // bV5Size, width, height (positive = bottom-up)
        bw.Write((short)1); bw.Write((short)32);           // planes, bit count
        bw.Write(3);                                       // BI_BITFIELDS
        bw.Write(pixels.Length); bw.Write(2835); bw.Write(2835); bw.Write(0); bw.Write(0);
        bw.Write(0x00FF0000); bw.Write(0x0000FF00); bw.Write(0x000000FF); bw.Write(unchecked((int)0xFF000000)); // R,G,B,A masks
        bw.Write(0x73524742);                              // LCS_sRGB
        bw.Write(new byte[36]);                            // endpoints
        bw.Write(0); bw.Write(0); bw.Write(0);             // gamma
        bw.Write(4);                                       // LCS_GM_IMAGES
        bw.Write(0); bw.Write(0); bw.Write(0);             // profile data, size, reserved
        for (int y = h - 1; y >= 0; y--) bw.Write(pixels, y * stride, stride);
        bw.Flush();
        ms.Position = 0;
        return ms;
    }
}
