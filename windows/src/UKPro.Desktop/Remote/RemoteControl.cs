using System;
using System.Runtime.InteropServices;
using UKPro.Core;
using UKPro.Desktop.Input;

namespace UKPro.Desktop.Remote;

/// <summary>Executes whitelisted remote-control actions from the phone (PROTOCOL §3.4).</summary>
public sealed class RemoteControl(KeyboardInjector injector)
{
    public static readonly string[] Actions =
        ["ALT_TAB", "ALT_SHIFT_TAB", "WIN_D", "WIN_E", "WIN_TAB", "TASK_MANAGER", "WIN_L", "ALT_F4", "PRINT_SCREEN"];

    /// <summary>Returns null on success, otherwise an error code.</summary>
    public string? Execute(string action, int repeat = 1)
    {
        repeat = Math.Clamp(repeat, 1, 20);
        var r = action switch
        {
            "ALT_TAB" => injector.HoldAndTap(KeyMods.Alt, 0x09, repeat),
            "ALT_SHIFT_TAB" => injector.HoldAndTap(KeyMods.Alt | KeyMods.Shift, 0x09, repeat),
            "WIN_D" => injector.Chord("KeyD", KeyMods.Meta),
            "WIN_E" => injector.Chord("KeyE", KeyMods.Meta),
            "WIN_TAB" => injector.Chord("Tab", KeyMods.Meta),
            "TASK_MANAGER" => injector.Chord("Escape", KeyMods.Ctrl | KeyMods.Shift),
            "WIN_L" => LockWorkStation() ? KeyboardInjector.Result.Ok : KeyboardInjector.Result.Failed,
            "ALT_F4" => injector.Chord("F4", KeyMods.Alt),
            "PRINT_SCREEN" => injector.TapVk(0x2C),
            _ => (KeyboardInjector.Result?)null,
        };
        return r switch { null => "BAD_MESSAGE", KeyboardInjector.Result.Ok => null, _ => "ELEVATED_TARGET" };
    }

    public string? Media(string action)
    {
        ushort? vk = action switch
        {
            "PLAY_PAUSE" => 0xB3, "NEXT" => 0xB0, "PREV" => 0xB1, "STOP" => 0xB2,
            "VOL_UP" => 0xAF, "VOL_DOWN" => 0xAE, "MUTE" => 0xAD, _ => null,
        };
        if (vk is null) return "BAD_MESSAGE";
        return injector.TapVk(vk.Value) == KeyboardInjector.Result.Ok ? null : "FAILED";
    }

    [DllImport("user32.dll")] private static extern bool LockWorkStation();
}

/// <summary>Absolute master volume through Core Audio (IAudioEndpointVolume).</summary>
public static class MasterVolume
{
    public static (int Level, bool Muted)? Get()
    {
        try
        {
            var v = Endpoint();
            Marshal.ThrowExceptionForHR(v.GetMasterVolumeLevelScalar(out float level));
            Marshal.ThrowExceptionForHR(v.GetMute(out bool mute));
            return ((int)Math.Round(level * 100), mute);
        }
        catch (Exception e) when (e is COMException or InvalidCastException) { return null; }
    }

    public static bool Set(int level)
    {
        try
        {
            var g = Guid.Empty;
            return Endpoint().SetMasterVolumeLevelScalar(Math.Clamp(level, 0, 100) / 100f, ref g) == 0;
        }
        catch (Exception e) when (e is COMException or InvalidCastException) { return false; }
    }

    private static IAudioEndpointVolume Endpoint()
    {
        var enumerator = (IMMDeviceEnumerator)new MMDeviceEnumerator();
        Marshal.ThrowExceptionForHR(enumerator.GetDefaultAudioEndpoint(0 /* eRender */, 1 /* eMultimedia */, out var device));
        var iid = typeof(IAudioEndpointVolume).GUID;
        Marshal.ThrowExceptionForHR(device.Activate(ref iid, 23 /* CLSCTX_ALL */, IntPtr.Zero, out var o));
        return (IAudioEndpointVolume)o;
    }

    [ComImport, Guid("BCDE0395-E52F-467C-8E3D-C4579291692E")] private class MMDeviceEnumerator;

    [ComImport, Guid("A95664D2-9614-4F35-A746-DE8DB63617E6"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    private interface IMMDeviceEnumerator
    {
        int EnumAudioEndpoints(int dataFlow, int stateMask, out IntPtr devices);
        int GetDefaultAudioEndpoint(int dataFlow, int role, out IMMDevice endpoint);
    }

    [ComImport, Guid("D666063F-1587-4E43-81F1-B948E807363F"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    private interface IMMDevice
    {
        int Activate(ref Guid iid, int clsCtx, IntPtr activationParams, [MarshalAs(UnmanagedType.IUnknown)] out object iface);
    }

    [ComImport, Guid("5CDF2C82-841E-4546-9722-0CF74078229A"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    private interface IAudioEndpointVolume
    {
        int RegisterControlChangeNotify(IntPtr notify);
        int UnregisterControlChangeNotify(IntPtr notify);
        int GetChannelCount(out uint count);
        int SetMasterVolumeLevel(float levelDb, ref Guid ctx);
        int SetMasterVolumeLevelScalar(float level, ref Guid ctx);
        int GetMasterVolumeLevel(out float levelDb);
        int GetMasterVolumeLevelScalar(out float level);
        int SetChannelVolumeLevel(uint ch, float levelDb, ref Guid ctx);
        int SetChannelVolumeLevelScalar(uint ch, float level, ref Guid ctx);
        int GetChannelVolumeLevel(uint ch, out float levelDb);
        int GetChannelVolumeLevelScalar(uint ch, out float level);
        int SetMute([MarshalAs(UnmanagedType.Bool)] bool mute, ref Guid ctx);
        int GetMute([MarshalAs(UnmanagedType.Bool)] out bool mute);
    }
}
