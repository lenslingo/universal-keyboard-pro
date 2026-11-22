using System;
using System.Collections.Concurrent;
using System.Collections.Generic;
using System.ComponentModel;
using System.Runtime.InteropServices;
using System.Text;
using System.Threading;
using UKPro.Core;
using static UKPro.Desktop.Native.Win32;

namespace UKPro.Desktop.Input;

public readonly record struct CapturedKey(bool Down, string Code, string Key, KeyMods Mods, bool Repeat, uint Vk);

/// <summary>
/// Read-only global keyboard capture via WH_KEYBOARD_LL on a dedicated message-loop thread.
/// The hook never blocks or swallows keys (always CallNextHookEx), ignores injected events
/// (ours and other apps'), and only enqueues while <see cref="Enabled"/> is true. No keystroke is
/// written to disk or kept after it has been forwarded.
/// </summary>
public sealed class KeyboardCapture : IDisposable
{
    private readonly BlockingCollection<CapturedKey> _queue = new(new ConcurrentQueue<CapturedKey>(), 4096);
    private readonly Thread _hookThread;
    private readonly Thread _consumer;
    private readonly LowLevelKeyboardProc _proc; // keep delegate alive
    private readonly bool[] _down = new bool[256];
    private IntPtr _hook;
    private uint _hookThreadId;
    private volatile bool _enabled;

    public KeyboardCapture(Action<CapturedKey> onKey)
    {
        _proc = HookProc;
        _hookThread = new Thread(HookThread) { IsBackground = true, Name = "UKPro keyboard hook" };
        _consumer = new Thread(() =>
        {
            foreach (var k in _queue.GetConsumingEnumerable())
            {
                try { onKey(k); } catch (Exception e) { System.Diagnostics.Trace.WriteLine("key handler: " + e); }
            }
        }) { IsBackground = true, Name = "UKPro key forwarder" };
        _consumer.Start();
        _hookThread.Start();
    }

    /// <summary>Set when the hook could not be installed (shown on the dashboard).</summary>
    public string? StartError { get; private set; }

    /// <summary>Forwarding switch. When false the hook stays installed but captures nothing.</summary>
    public bool Enabled
    {
        get => _enabled;
        set { _enabled = value; if (!value) Array.Clear(_down); }
    }

    private void HookThread()
    {
        _hookThreadId = GetCurrentThreadId();
        _hook = SetWindowsHookEx(WH_KEYBOARD_LL, _proc, GetModuleHandle(null), 0);
        if (_hook == IntPtr.Zero)
        {
            StartError = new Win32Exception(Marshal.GetLastWin32Error()).Message;
            return;
        }
        while (GetMessage(out var msg, IntPtr.Zero, 0, 0) > 0) { TranslateMessage(ref msg); DispatchMessage(ref msg); }
        UnhookWindowsHookEx(_hook);
    }

    private IntPtr HookProc(int nCode, IntPtr wParam, IntPtr lParam)
    {
        if (nCode >= 0 && _enabled)
        {
            try
            {
                var k = Marshal.PtrToStructure<KBDLLHOOKSTRUCT>(lParam);
                bool injected = (k.flags & (LLKHF_INJECTED | LLKHF_LOWER_IL_INJECTED)) != 0 || k.dwExtraInfo == InjectedMarker;
                if (!injected && k.vkCode < 256) Capture(k, (int)wParam);
            }
            catch (Exception) { /* never let an exception escape into the hook chain */ }
        }
        return CallNextHookEx(_hook, nCode, wParam, lParam);
    }

    private void Capture(KBDLLHOOKSTRUCT k, int msg)
    {
        bool down = msg is WM_KEYDOWN or WM_SYSKEYDOWN;
        uint vk = k.vkCode;
        bool repeat = down && _down[vk];
        _down[vk] = down;
        // Right-hand/extended variants: the LL hook reports VK_RETURN for numpad Enter with LLKHF_EXTENDED
        string? code = vk == 0x0D && (k.flags & LLKHF_EXTENDED) != 0 ? "NumpadEnter" : KeyCodes.VkToCode.GetValueOrDefault((ushort)vk);
        if (code is null) return;

        var mods = CurrentMods(out bool altGr);
        string key = "";
        if (down && !KeyCodes.IsModifierCode(code))
        {
            key = Translate(vk, k.scanCode, mods, altGr, out bool dead);
            if (dead)
            {
                // Accent layouts (French, Spanish, German, Portuguese, Vietnamese…): the dead key itself types
                // nothing; remember it and combine with the next character, exactly like Windows does.
                _pendingDead = key.Length > 0 ? key[0] : null;
                return;
            }
            if (_pendingDead is { } d && !KeyCodes.IsModifierCode(code))
            {
                _pendingDead = null;
                if (key.Length > 0) key = TextUtil.ComposeDeadKey(d, key);
            }
        }
        if (altGr && key.Length > 0) mods = (mods & ~(KeyMods.Ctrl | KeyMods.Alt)) | KeyMods.AltGr;
        _queue.TryAdd(new CapturedKey(down, code, key, mods, repeat, vk));
    }

    private static KeyMods CurrentMods(out bool altGr)
    {
        static bool D(int vk) => (GetAsyncKeyState(vk) & 0x8000) != 0;
        var m = KeyMods.None;
        if (D(0x11)) m |= KeyMods.Ctrl;
        if (D(0x10)) m |= KeyMods.Shift;
        if (D(0x12)) m |= KeyMods.Alt;
        if (D(0x5B) || D(0x5C)) m |= KeyMods.Meta;
        if ((GetKeyState(0x14) & 1) != 0) m |= KeyMods.CapsLock;
        altGr = D(0xA5) && D(0xA2); // AltGr arrives as RAlt + LCtrl
        return m;
    }

    /// <summary>Character the foreground keyboard layout produces, without disturbing dead-key state (wFlags 0x4).</summary>
    private char? _pendingDead;

    private static string Translate(uint vk, uint scan, KeyMods mods, bool altGr, out bool dead)
    {
        dead = false;
        var state = new byte[256];
        if (mods.HasFlag(KeyMods.Shift)) state[0x10] = 0x80;
        if (mods.HasFlag(KeyMods.CapsLock)) state[0x14] = 0x01;
        if (altGr) { state[0x11] = 0x80; state[0x12] = 0x80; }
        else if (mods.HasFlag(KeyMods.Ctrl) || mods.HasFlag(KeyMods.Alt) || mods.HasFlag(KeyMods.Meta)) return "";
        var layout = GetKeyboardLayout(GetWindowThreadProcessId(GetForegroundWindow(), out _));
        var sb = new StringBuilder(16);
        int n = ToUnicodeEx(vk, scan, state, sb, sb.Capacity, 0x4, layout);
        if (n < 0) { dead = true; return sb.Length > 0 ? sb.ToString(0, 1) : ""; } // dead key: buffer holds the accent
        if (n == 0) return "";
        // n can be 2 (UTF-16 surrogate pair) or more (layouts that emit ligatures, e.g. Bengali/Devanagari conjuncts)
        var s = sb.ToString(0, Math.Min(n, sb.Length));
        return TextUtil.IsTypeable(s) && !s.Contains('\n') && !s.Contains('\t') ? s : "";
    }

    public void Dispose()
    {
        _enabled = false;
        if (_hookThreadId != 0) PostThreadMessage(_hookThreadId, 0x0012 /* WM_QUIT */, IntPtr.Zero, IntPtr.Zero);
        _queue.CompleteAdding();
    }

    // message loop P/Invokes
    [StructLayout(LayoutKind.Sequential)]
    private struct MSG { public IntPtr hwnd; public uint message; public IntPtr wParam, lParam; public uint time; public int x, y; }
    [DllImport("user32.dll")] private static extern int GetMessage(out MSG msg, IntPtr hwnd, uint min, uint max);
    [DllImport("user32.dll")] private static extern bool TranslateMessage(ref MSG msg);
    [DllImport("user32.dll")] private static extern IntPtr DispatchMessage(ref MSG msg);
    [DllImport("user32.dll")] private static extern bool PostThreadMessage(uint tid, uint msg, IntPtr w, IntPtr l);
    [DllImport("kernel32.dll")] private static extern uint GetCurrentThreadId();
}
