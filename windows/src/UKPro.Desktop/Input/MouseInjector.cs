using System;
using System.Collections.Generic;
using System.Runtime.InteropServices;
using static UKPro.Desktop.Native.Win32;

namespace UKPro.Desktop.Input;

/// <summary>
/// Phone touchpad → Windows mouse via SendInput (relative moves, buttons, vertical/horizontal wheel).
/// Tracks pressed buttons so a drag can never leave a button stuck if the phone disconnects.
/// </summary>
public sealed class MouseInjector
{
    private const uint INPUT_MOUSE = 0;
    private const uint MOVE = 0x0001, LEFTDOWN = 0x0002, LEFTUP = 0x0004, RIGHTDOWN = 0x0008, RIGHTUP = 0x0010,
        MIDDLEDOWN = 0x0020, MIDDLEUP = 0x0040, WHEEL = 0x0800, HWHEEL = 0x1000;
    private static readonly int InputSize = Marshal.SizeOf<INPUT>();
    private readonly HashSet<string> _down = new();
    private readonly object _lock = new();

    public void Move(int dx, int dy)
    {
        if (dx == 0 && dy == 0) return;
        Send(Mouse(MOVE, dx, dy, 0));
    }

    /// <summary>Wheel units: 120 = one notch; smaller values scroll smoothly in modern apps.</summary>
    public void Scroll(int dx, int dy)
    {
        var l = new List<INPUT>(2);
        if (dy != 0) l.Add(Mouse(WHEEL, 0, 0, -dy));  // protocol: positive dy = content moves up (scroll down)
        if (dx != 0) l.Add(Mouse(HWHEEL, 0, 0, dx));
        if (l.Count > 0) Send(l.ToArray());
    }

    public void Button(string button, string action)
    {
        var (down, up) = button switch
        {
            "right" => (RIGHTDOWN, RIGHTUP),
            "middle" => (MIDDLEDOWN, MIDDLEUP),
            _ => (LEFTDOWN, LEFTUP),
        };
        lock (_lock)
        {
            switch (action)
            {
                case "down": _down.Add(button); Send(Mouse(down, 0, 0, 0)); break;
                case "up": _down.Remove(button); Send(Mouse(up, 0, 0, 0)); break;
                case "double": Send(Mouse(down, 0, 0, 0), Mouse(up, 0, 0, 0), Mouse(down, 0, 0, 0), Mouse(up, 0, 0, 0)); break;
                default: Send(Mouse(down, 0, 0, 0), Mouse(up, 0, 0, 0)); break;
            }
        }
    }

    /// <summary>Called when the phone disconnects: release anything held by a drag.</summary>
    public void ReleaseAll()
    {
        lock (_lock)
        {
            foreach (var b in _down.ToArrayCopy()) Button(b, "up");
            _down.Clear();
        }
    }

    private static INPUT Mouse(uint flags, int dx, int dy, int data) => new()
    {
        type = INPUT_MOUSE,
        U = new InputUnion { mi = new MOUSEINPUT { dx = dx, dy = dy, mouseData = unchecked((uint)data), dwFlags = flags, dwExtraInfo = InjectedMarker } },
    };

    private static void Send(params INPUT[] inputs) => SendInput((uint)inputs.Length, inputs, InputSize);
}

internal static class SetExtensions
{
    public static string[] ToArrayCopy(this HashSet<string> s) { var a = new string[s.Count]; s.CopyTo(a); return a; }
}
