using System;
using System.Collections.Generic;
using System.Runtime.InteropServices;
using UKPro.Core;
using static UKPro.Desktop.Native.Win32;

namespace UKPro.Desktop.Input;

/// <summary>
/// Injects remote input into the foreground app with SendInput:
/// committed text as KEYEVENTF_UNICODE (any script, incl. Bangla and emoji), shortcuts/navigation as VKs.
/// Every event carries <see cref="InjectedMarker"/> so our own hook ignores it (no loops).
/// </summary>
public sealed class KeyboardInjector
{
    private static readonly int InputSize = Marshal.SizeOf<INPUT>();

    public enum Result { Ok, Blocked, Failed }

    /// <summary>
    /// Types text exactly, in any language/script. \n → Enter, \t → Tab; everything else as UTF-16 units with
    /// KEYEVENTF_UNICODE (VK_PACKET), independent of the active Windows keyboard layout. Characters outside the BMP
    /// (emoji, rare CJK) are sent as their two surrogate units, which Windows reassembles into one character.
    /// </summary>
    public Result TypeText(string text)
    {
        var inputs = new List<INPUT>(text.Length * 2);
        foreach (var ch in ClipboardResolver.NormalizeNewlines(text))
        {
            if (ch == '\n') { AddVk(inputs, 0x0D, true); AddVk(inputs, 0x0D, false); continue; }
            if (ch == '\t') { AddVk(inputs, 0x09, true); AddVk(inputs, 0x09, false); continue; }
            inputs.Add(Unicode(ch, false));
            inputs.Add(Unicode(ch, true));
        }
        return Send(inputs);
    }

    /// <summary>
    /// Plays a complete chord (modifiers down, key down, key up, modifiers up). Treating remote key-downs as
    /// self-contained taps means a lost KEY_UP can never leave Ctrl/Alt/Win stuck on the laptop.
    /// </summary>
    public Result Chord(string code, KeyMods mods)
    {
        if (!KeyCodes.CodeToVk.TryGetValue(code, out var vk)) return Result.Failed;
        var inputs = new List<INPUT>(10);
        var modVks = ModifierVks(mods);
        foreach (var m in modVks) AddVk(inputs, m, true);
        if (!KeyCodes.IsModifierCode(code)) { AddVk(inputs, vk, true); AddVk(inputs, vk, false); }
        for (int i = modVks.Count - 1; i >= 0; i--) AddVk(inputs, modVks[i], false);
        return Send(inputs);
    }

    /// <summary>Holds modifiers while tapping a key several times (Alt+Tab ×n). Releases everything at the end.</summary>
    public Result HoldAndTap(KeyMods mods, ushort vk, int taps)
    {
        var inputs = new List<INPUT>();
        var modVks = ModifierVks(mods);
        foreach (var m in modVks) AddVk(inputs, m, true);
        for (int i = 0; i < taps; i++) { AddVk(inputs, vk, true); AddVk(inputs, vk, false); }
        for (int i = modVks.Count - 1; i >= 0; i--) AddVk(inputs, modVks[i], false);
        return Send(inputs);
    }

    public Result TapVk(ushort vk) { var l = new List<INPUT>(); AddVk(l, vk, true); AddVk(l, vk, false); return Send(l); }

    private static List<ushort> ModifierVks(KeyMods mods)
    {
        var l = new List<ushort>(4);
        if (mods.HasFlag(KeyMods.Ctrl)) l.Add(0xA2);
        if (mods.HasFlag(KeyMods.Alt)) l.Add(0xA4);
        if (mods.HasFlag(KeyMods.Shift)) l.Add(0xA0);
        if (mods.HasFlag(KeyMods.Meta)) l.Add(0x5B);
        return l;
    }

    private static void AddVk(List<INPUT> l, ushort vk, bool down)
    {
        uint flags = down ? 0 : KEYEVENTF_KEYUP;
        if (KeyCodes.ExtendedVk.Contains(vk)) flags |= KEYEVENTF_EXTENDEDKEY;
        l.Add(new INPUT
        {
            type = INPUT_KEYBOARD,
            U = new InputUnion { ki = new KEYBDINPUT { wVk = vk, wScan = (ushort)MapVirtualKey(vk, 0), dwFlags = flags, dwExtraInfo = InjectedMarker } },
        });
    }

    private static INPUT Unicode(char c, bool up) => new()
    {
        type = INPUT_KEYBOARD,
        U = new InputUnion { ki = new KEYBDINPUT { wVk = 0, wScan = c, dwFlags = KEYEVENTF_UNICODE | (up ? KEYEVENTF_KEYUP : 0), dwExtraInfo = InjectedMarker } },
    };

    private static Result Send(List<INPUT> inputs)
    {
        if (inputs.Count == 0) return Result.Ok;
        // Long pastes: some apps drop input if thousands of events arrive in one call, so send in batches.
        if (inputs.Count > 256)
        {
            for (int i = 0; i < inputs.Count; i += 256)
            {
                var r = Send(inputs.GetRange(i, Math.Min(256, inputs.Count - i)));
                if (r != Result.Ok) return r;
                System.Threading.Thread.Sleep(2);
            }
            return Result.Ok;
        }
        var arr = inputs.ToArray();
        var n = SendInput((uint)arr.Length, arr, InputSize);
        // NOTE: UIPI blocking is silent (SendInput still reports success), so callers must check
        // ForegroundContext.IsElevated before injecting — see SyncEngine.CanInjectNow().
        return n == arr.Length ? Result.Ok : n == 0 ? Result.Blocked : Result.Failed;
    }
}
