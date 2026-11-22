using System;
using System.Linq;
using System.Threading;
using System.Threading.Tasks;
using System.Windows;
using Microsoft.Win32;
using UKPro.Core;
using UKPro.Desktop.Services;
using UKPro.Desktop.UI;
using WinForms = System.Windows.Forms;

namespace UKPro.Desktop;

/// <summary>
/// Tray application running in the logged-in user's session (no Windows service: hooks, SendInput and the
/// clipboard all need the interactive desktop).
/// </summary>
public partial class App : Application
{
    private Mutex? _singleInstance;
    private WinForms.NotifyIcon? _tray;
    private MainWindow? _main;
    private SyncIndicatorWindow? _indicator;
    private MdnsAdvertiser? _mdns;

    public Settings Settings { get; private set; } = null!;
    public UkHub Hub { get; private set; } = null!;
    public SyncEngine Engine { get; private set; } = null!;
    public static new App Current => (App)Application.Current;

    private void OnStartup(object sender, StartupEventArgs e)
    {
        _singleInstance = new Mutex(true, @"Local\UKPro.Desktop", out bool first);
        if (!first) { MessageBox.Show("UK Pro is already running (see the tray).", "UK Pro"); Shutdown(); return; }

        Settings = Settings.Load();
        var cert = IdentityStore.LoadOrCreate(Settings.DeviceName);
        Hub = new UkHub(new HubOptions
        {
            DeviceId = Settings.DeviceId, Name = Settings.DeviceName, Certificate = cert, Port = Settings.Port,
        }, new DpapiTrustStore())
        {
            ApprovePairing = info => Dispatcher.InvokeAsync(() => MessageBox.Show(
                $"Allow \"{info.Name}\" to pair with this PC?\n\nVerification number: {info.VerificationNumber}\n" +
                "It must match the number on the phone.", "UK Pro — new device",
                MessageBoxButton.YesNo, MessageBoxImage.Question) == MessageBoxResult.Yes).Task,
        };
        Hub.Start();
        Engine = new SyncEngine(Hub, Settings, Dispatcher);
        _mdns = new MdnsAdvertiser();
        _mdns.Start(Settings.DeviceName, Settings.DeviceId, Hub.Fingerprint, Hub.Port);

        CreateTray();
        _indicator = new SyncIndicatorWindow(Engine);
        Engine.StatusChanged += () => Dispatcher.InvokeAsync(UpdateStatus);
        Engine.Warning += msg => Dispatcher.InvokeAsync(() => _tray?.ShowBalloonTip(3000, "UK Pro", msg, WinForms.ToolTipIcon.Warning));
        ApplyAutostart();
        UpdateStatus();

        if (Hub.Peers.Count == 0 && new DpapiTrustStore().All().Count == 0) ShowMain(tab: "Devices", startPairing: true);
    }

    private void CreateTray()
    {
        var iconStream = GetResourceStream(new Uri("pack://application:,,,/Assets/ukpro.ico"))!.Stream;
        _tray = new WinForms.NotifyIcon { Icon = new System.Drawing.Icon(iconStream), Visible = true, Text = "UK Pro" };
        _tray.DoubleClick += (_, _) => ShowMain();
        _tray.ContextMenuStrip = new WinForms.ContextMenuStrip();
        _tray.ContextMenuStrip.Opening += (_, _) => BuildMenu(_tray.ContextMenuStrip);
        BuildMenu(_tray.ContextMenuStrip);
    }

    private void BuildMenu(WinForms.ContextMenuStrip m)
    {
        m.Items.Clear();
        m.Items.Add("Open UK Pro", null, (_, _) => ShowMain());
        m.Items.Add(Engine.Paused ? "▶ Resume synchronization" : "⏸ Pause synchronization", null, (_, _) => Engine.SetPaused(!Engine.Paused));
        m.Items.Add(new WinForms.ToolStripSeparator());
        var a2w = new WinForms.ToolStripMenuItem("Phone → PC typing") { Checked = Settings.AndroidToWindows };
        a2w.Click += (_, _) => Engine.SetDirections(!Settings.AndroidToWindows, Settings.WindowsToAndroid);
        var w2a = new WinForms.ToolStripMenuItem("PC keyboard → Phone") { Checked = Settings.WindowsToAndroid };
        w2a.Click += (_, _) => Engine.SetDirections(Settings.AndroidToWindows, !Settings.WindowsToAndroid);
        var clip = new WinForms.ToolStripMenuItem("Clipboard sync") { Checked = Settings.ClipboardSync };
        clip.Click += (_, _) => Engine.SetClipboardSync(!Settings.ClipboardSync);
        m.Items.Add(a2w); m.Items.Add(w2a); m.Items.Add(clip);
        var target = new WinForms.ToolStripMenuItem("Keyboard target");
        foreach (var p in Hub.Peers.Where(p => p.IsConnected))
        {
            var id = p.Device.DeviceId;
            var item = new WinForms.ToolStripMenuItem(p.Device.Name) { Checked = Engine.ActiveTarget == id };
            item.Click += (_, _) => Engine.ActiveTarget = id;
            target.DropDownItems.Add(item);
        }
        if (target.DropDownItems.Count == 0) target.DropDownItems.Add(new WinForms.ToolStripMenuItem("(no phone connected)") { Enabled = false });
        m.Items.Add(target);
        m.Items.Add("Pair a new phone…", null, (_, _) => ShowMain(tab: "Devices", startPairing: true));
        m.Items.Add("Remote text panel…", null, (_, _) => ShowMain(tab: "Remote text"));
        m.Items.Add(new WinForms.ToolStripSeparator());
        m.Items.Add("Exit", null, (_, _) => Shutdown());
    }

    private void UpdateStatus()
    {
        if (_tray is null) return;
        var connected = Hub.Peers.Count(p => p.IsConnected);
        var text = Engine.Paused ? "UK Pro — paused" : $"UK Pro — {connected} phone(s) connected";
        _tray.Text = text.Length > 63 ? text[..63] : text;
        _indicator?.Refresh();
        _main?.Refresh();
    }

    public void ShowMain(string? tab = null, bool startPairing = false)
    {
        if (_main is null) { _main = new MainWindow(this); _main.Closed += (_, _) => _main = null; }
        _main.Show();
        if (_main.WindowState == WindowState.Minimized) _main.WindowState = WindowState.Normal;
        _main.Activate();
        if (tab is not null) _main.SelectTab(tab);
        if (startPairing) _main.StartPairing();
    }

    private void ApplyAutostart()
    {
        using var key = Registry.CurrentUser.OpenSubKey(@"Software\Microsoft\Windows\CurrentVersion\Run", writable: true);
        if (key is null) return;
        if (Settings.StartWithWindows) key.SetValue("UKPro", $"\"{Environment.ProcessPath}\" --tray");
        else key.DeleteValue("UKPro", throwOnMissingValue: false);
    }

    private void OnExit(object sender, ExitEventArgs e)
    {
        if (_tray is not null) { _tray.Visible = false; _tray.Dispose(); }
        _mdns?.Dispose();
        Engine?.Dispose();
        if (Hub is not null) Task.Run(async () => await Hub.DisposeAsync()).Wait(TimeSpan.FromSeconds(2));
        _singleInstance?.Dispose();
    }
}
