using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.Threading;
using System.Windows.Automation;
using UKPro.Desktop.Native;

namespace UKPro.Desktop.Input;

/// <summary>Snapshot of the current foreground input context.</summary>
public sealed record ForegroundContext(
    bool Known, bool IsPassword, bool IsEditable, uint ProcessId, string? ProcessName, bool IsElevated, bool SecureDesktop)
{
    public static readonly ForegroundContext Unknown = new(false, false, false, 0, null, false, false);
}

/// <summary>
/// Decides whether physical keystrokes may leave this PC (PROTOCOL §3.1, KNOWN_LIMITATIONS #5, #8).
/// Uses UI Automation focus events (IsPassword), the input desktop name, process elevation and a
/// user-editable exclusion list. Privacy-first: unknown context ⇒ blocked unless the app is trusted.
/// </summary>
public sealed class PrivacyGuard : IDisposable
{
    private static readonly bool SelfElevated = Win32.IsProcessElevated((uint)Environment.ProcessId);
    private readonly Thread _uiaThread;
    private readonly ManualResetEventSlim _stop = new();
    private volatile ForegroundContext _focused = ForegroundContext.Unknown;

    public PrivacyGuard(Settings settings)
    {
        Settings = settings;
        _uiaThread = new Thread(UiaThread) { IsBackground = true, Name = "UKPro UIA focus" };
        _uiaThread.SetApartmentState(ApartmentState.MTA); // UIA clients must not run on the WPF UI thread
        _uiaThread.Start();
    }

    public Settings Settings { get; }
    public event Action<ForegroundContext>? FocusChanged;

    /// <summary>Default apps whose keystrokes are never forwarded (password managers, credential UIs).</summary>
    public static readonly string[] DefaultExcluded =
    [
        "KeePass.exe", "KeePassXC.exe", "1Password.exe", "Bitwarden.exe", "LastPass.exe", "Dashlane.exe",
        "consent.exe", "CredentialUIBroker.exe", "LogonUI.exe", "mstsc.exe",
    ];

    /// <summary>Fresh context of the window that currently has focus (cheap; safe to call from any thread).</summary>
    public ForegroundContext Current()
    {
        bool secure = !Win32.IsDefaultInputDesktop();
        var hwnd = Win32.GetForegroundWindow();
        Win32.GetWindowThreadProcessId(hwnd, out var pid);
        var f = _focused;
        if (f.ProcessId == pid && pid != 0)
            return f with { SecureDesktop = secure };
        // UIA event not yet delivered for this window: elevation/name are still reliable
        return new ForegroundContext(false, false, false, pid, pid == 0 ? null : Win32.ProcessImageName(pid),
            pid != 0 && !SelfElevated && Win32.IsProcessElevated(pid), secure);
    }

    /// <summary>Why forwarding physical keystrokes is not allowed right now, or null if allowed.</summary>
    public string? BlockReason(ForegroundContext c)
    {
        if (c.SecureDesktop) return "secure desktop";
        if (c.ProcessName is { } n && IsExcluded(n)) return $"{n} is excluded";
        if (c.IsPassword) return "password field";
        if (!c.Known && !(c.ProcessName is { } t && Settings.TrustedApps.Contains(t))) return "input context unknown";
        return null;
    }

    public bool IsExcluded(string processName) =>
        Array.Exists(DefaultExcluded, x => x.Equals(processName, StringComparison.OrdinalIgnoreCase))
        || Settings.ExcludedApps.Contains(processName);

    private void UiaThread()
    {
        AutomationFocusChangedEventHandler handler = (_, _) => Refresh();
        try
        {
            Automation.AddAutomationFocusChangedEventHandler(handler);
            Refresh();
            _stop.Wait();
        }
        catch (Exception e) { Trace.WriteLine("UIA unavailable: " + e.Message); }
        finally
        {
            try { Automation.RemoveAutomationFocusChangedEventHandler(handler); } catch (Exception) { }
        }
    }

    private void Refresh()
    {
        try
        {
            var el = AutomationElement.FocusedElement;
            if (el is null) { _focused = ForegroundContext.Unknown; return; }
            var cur = el.Current;
            var pid = (uint)cur.ProcessId;
            bool editable = cur.ControlType == ControlType.Edit || cur.ControlType == ControlType.Document
                            || (bool)el.GetCurrentPropertyValue(AutomationElement.IsValuePatternAvailableProperty);
            var ctx = new ForegroundContext(true, cur.IsPassword, editable, pid, Win32.ProcessImageName(pid),
                !SelfElevated && Win32.IsProcessElevated(pid), false);
            _focused = ctx;
            FocusChanged?.Invoke(ctx);
        }
        catch (Exception e) when (e is ElementNotAvailableException or InvalidOperationException
                                      or System.Runtime.InteropServices.COMException or ArgumentException)
        {
            _focused = ForegroundContext.Unknown; // privacy-first
        }
    }

    public void Dispose() => _stop.Set();
}
