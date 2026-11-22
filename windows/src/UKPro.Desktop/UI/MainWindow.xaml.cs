using System;
using System.Diagnostics;
using System.IO;
using System.Linq;
using System.Threading.Tasks;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using System.Windows.Media.Imaging;
using System.Windows.Threading;
using Microsoft.Win32;
using QRCoder;
using UKPro.Core;

namespace UKPro.Desktop.UI;

public partial class MainWindow : Window
{
    private sealed record DeviceRow(string Id, string Display);
    private sealed record HistoryRow(string ClipId, bool Pinned, string Display);
    private sealed record TransferRow(string Id, string Display);

    private readonly App _app;
    private readonly DispatcherTimer _timer;
    private PairingOffer? _offer;

    public MainWindow(App app)
    {
        _app = app;
        InitializeComponent();
        LoadSettingsIntoUi();
        _app.Engine.History.Changed += () => Dispatcher.InvokeAsync(RefreshHistory);
        _timer = new DispatcherTimer { Interval = TimeSpan.FromSeconds(1) };
        _timer.Tick += (_, _) => { Refresh(); UpdatePairCountdown(); };
        _timer.Start();
        Closed += (_, _) => _timer.Stop();
        Refresh();
        RefreshHistory();
    }

    public void SelectTab(string header)
    {
        foreach (TabItem t in Tabs.Items) if ((string)t.Header == header) { Tabs.SelectedItem = t; break; }
    }

    // ------------------------------------------------------------------ status

    public void Refresh()
    {
        var hub = _app.Hub; var e = _app.Engine; var s = _app.Settings;
        var connected = hub.Peers.Where(p => p.IsConnected).ToList();
        StatusText.Text = e.Paused ? "Paused" : connected.Count > 0 ? $"{connected.Count} connected" : "Waiting for phone";
        StatusText.Foreground = (System.Windows.Media.Brush)FindResource(e.Paused ? "Warn" : connected.Count > 0 ? "Ok" : "Muted");
        PauseButton.Content = e.Paused ? "▶ Resume" : "⏸ Pause";

        ConnText.Text = connected.Count == 0 ? "No phone connected"
            : string.Join(", ", connected.Select(p => $"{p.Device.Name} · {p.Transport.ToUpperInvariant()} · {(p.RttMs >= 0 ? p.RttMs + " ms" : "…")}"));
        ConnDetail.Text = $"This PC: {hub.Name}  ·  port {hub.Port}  ·  cert {hub.Fingerprint[..16]}…";

        A2W.IsChecked = s.AndroidToWindows; W2A.IsChecked = s.WindowsToAndroid; ClipSync.IsChecked = s.ClipboardSync;
        var target = e.ActiveTarget is { } t ? hub.GetPeer(t)?.Device.Name : null;
        var focus = e.ActiveTarget is { } t2 ? e.FocusOf(t2) : null;
        KbDetail.Text = e.CaptureError is { } err ? "Keyboard hook failed: " + err
            : !s.WindowsToAndroid ? "Mirroring is off. Turn it on to type on the phone with this keyboard."
            : target is null ? "Mirroring on — no phone connected."
            : $"Target: {target} · " + (e.LastBlockReason is { } r ? $"paused because: {r}"
                : focus is { Editable: true } ? $"typing into {focus.App}" : "waiting for a text field on the phone");
        ClipDetail.Text = $"{e.History.Items.Count} items in history";

        var active = e.Transfers.All.Where(x => x.State is TransferState.Running or TransferState.Paused or TransferState.Verifying).ToList();
        FileDetail.Text = active.Count == 0 ? "Idle" : string.Join("\n", active.Select(x => $"{x.Name} {x.Progress:P0} · {Speed(x.BytesPerSecond)}"));

        var selDev = (DevicesList.SelectedItem as DeviceRow)?.Id;
        DevicesList.ItemsSource = hub.Peers.Select(p => new DeviceRow(p.Device.DeviceId,
            $"{(p.IsConnected ? "●" : "○")}  {p.Device.Name}   {(e.ActiveTarget == p.Device.DeviceId ? "⌨ keyboard target" : "")}   paired {p.Device.PairedAt:d}")).ToList();
        if (selDev is not null) DevicesList.SelectedItem = DevicesList.Items.Cast<DeviceRow>().FirstOrDefault(r => r.Id == selDev);

        var selTr = (TransfersList.SelectedItem as TransferRow)?.Id;
        TransfersList.ItemsSource = e.Transfers.All.Reverse().Select(x => new TransferRow(x.TransferId,
            $"{(x.Direction == TransferDirection.Incoming ? "↓" : "↑")}  {x.Name}   {FormatSize(x.Size)}   {x.State}   {x.Progress:P0}   {Speed(x.BytesPerSecond)}{(x.Error is null ? "" : "   " + x.Error)}")).ToList();
        if (selTr is not null) TransfersList.SelectedItem = TransfersList.Items.Cast<TransferRow>().FirstOrDefault(r => r.Id == selTr);
    }

    private static string FormatSize(long b) => b >= 1 << 30 ? $"{b / (double)(1 << 30):F2} GB" : b >= 1 << 20 ? $"{b / (double)(1 << 20):F1} MB" : $"{b / 1024.0:F0} KB";
    private static string Speed(double bps) => bps <= 0 ? "" : $"{bps / (1 << 20):F1} MB/s";

    private void OnPause(object s, RoutedEventArgs e) => _app.Engine.SetPaused(!_app.Engine.Paused);
    private void OnDirections(object s, RoutedEventArgs e) => _app.Engine.SetDirections(A2W.IsChecked == true, W2A.IsChecked == true);
    private void OnClipSync(object s, RoutedEventArgs e) => _app.Engine.SetClipboardSync(ClipSync.IsChecked == true);

    // ------------------------------------------------------------------ pairing

    public void StartPairing()
    {
        _offer = _app.Hub.CreatePairingOffer();
        var hosts = WssServer.LocalAddresses();
        var uri = _offer.ToUri(hosts, _app.Hub.Port, _app.Hub.Fingerprint, _app.Hub.DeviceId, _app.Hub.Name);
        using var gen = new QRCodeGenerator();
        using var data = gen.CreateQrCode(uri, QRCodeGenerator.ECCLevel.M);
        var png = new PngByteQRCode(data).GetGraphic(8);
        var bmp = new BitmapImage();
        bmp.BeginInit(); bmp.StreamSource = new MemoryStream(png); bmp.CacheOption = BitmapCacheOption.OnLoad; bmp.EndInit();
        QrImage.Source = bmp;
        PairCode.Text = _offer.DisplayCode;
        UpdatePairCountdown();
    }

    private void UpdatePairCountdown()
    {
        if (_offer is null) { PairInfo.Text = "Press the button to show a one-time pairing QR code."; return; }
        var left = _offer.ExpiresAt - DateTimeOffset.UtcNow;
        PairInfo.Text = _offer.IsValid
            ? $"Scan with UK Pro on your phone (Connect → Scan QR), or enter the code. Expires in {left:m\\:ss}.\nAddresses: {string.Join(", ", WssServer.LocalAddresses())} : {_app.Hub.Port}"
            : "Code used or expired.";
        if (!_offer.IsValid) { QrImage.Source = null; PairCode.Text = "— — — —"; }
    }

    private void OnNewPairing(object s, RoutedEventArgs e) => StartPairing();

    private void OnSetTarget(object s, RoutedEventArgs e)
    {
        if (DevicesList.SelectedItem is DeviceRow r) _app.Engine.ActiveTarget = r.Id;
    }

    private void OnForget(object s, RoutedEventArgs e)
    {
        if (DevicesList.SelectedItem is not DeviceRow r) return;
        if (MessageBox.Show("Forget this phone? It will need to pair again.", "UK Pro", MessageBoxButton.YesNo) == MessageBoxResult.Yes)
            _app.Hub.Forget(r.Id);
    }

    // ------------------------------------------------------------------ history

    private void RefreshHistory()
    {
        var q = HistorySearch.Text.Trim();
        HistoryList.ItemsSource = _app.Engine.History.Items
            .Where(i => q.Length == 0 || i.Preview.Contains(q, StringComparison.CurrentCultureIgnoreCase))
            .Select(i => new HistoryRow(i.ClipId, i.Pinned,
                $"{(i.Pinned ? "📌 " : "")}{KindIcon(i.Kind)}  {OneLine(i.Preview)}   · {i.Time:g} · from {(i.Origin == _app.Hub.DeviceId ? "this PC" : _app.Hub.GetPeer(i.Origin)?.Device.Name ?? "phone")}"))
            .ToList();
    }

    private static string KindIcon(string kind) => kind switch { "image" => "🖼", "files" => "📄", _ => "📝" };
    private static string OneLine(string s) { var t = s.Replace("\r", " ").Replace("\n", " ⏎ "); return t.Length > 120 ? t[..120] + "…" : t; }

    private void OnHistorySearch(object s, TextChangedEventArgs e) => RefreshHistory();
    private void OnHistoryCopy(object s, RoutedEventArgs e) { if (HistoryList.SelectedItem is HistoryRow r) _app.Engine.ApplyHistory(null, r.ClipId, paste: false); }

    private async void OnHistoryPaste(object s, RoutedEventArgs e)
    {
        if (HistoryList.SelectedItem is not HistoryRow r) return;
        WindowState = WindowState.Minimized; // give focus back to the previous app before Ctrl+V
        await Task.Delay(350);
        _app.Engine.ApplyHistory(null, r.ClipId, paste: true);
    }

    private void OnHistoryPin(object s, RoutedEventArgs e) { if (HistoryList.SelectedItem is HistoryRow r) _app.Engine.History.SetPinned(r.ClipId, !r.Pinned); }
    private void OnHistoryDelete(object s, RoutedEventArgs e) { if (HistoryList.SelectedItem is HistoryRow r) _app.Engine.History.Delete(r.ClipId); }
    private void OnHistoryClear(object s, RoutedEventArgs e) => _app.Engine.History.Clear(keepPinned: true);

    // ------------------------------------------------------------------ files

    private void OnSendFile(object s, RoutedEventArgs e)
    {
        var target = _app.Engine.ActiveTarget;
        if (target is null) { MessageBox.Show("Connect a phone first.", "UK Pro"); return; }
        var dlg = new OpenFileDialog { Multiselect = true, Title = "Send to phone" };
        if (dlg.ShowDialog(this) != true) return;
        foreach (var f in dlg.FileNames) _ = _app.Engine.Transfers.SendFileAsync(target, f);
        SelectTab("Files");
    }

    private void OnOpenDownloads(object s, RoutedEventArgs e)
    {
        Directory.CreateDirectory(_app.Settings.DownloadFolder);
        Process.Start(new ProcessStartInfo("explorer.exe", $"\"{_app.Settings.DownloadFolder}\"") { UseShellExecute = true });
    }

    private void OnTransferPause(object s, RoutedEventArgs e)
    {
        if (TransfersList.SelectedItem is not TransferRow r) return;
        var t = _app.Engine.Transfers.All.FirstOrDefault(x => x.TransferId == r.Id);
        if (t is not null) _app.Engine.Transfers.Pause(r.Id, t.State != TransferState.Paused);
    }

    private void OnTransferCancel(object s, RoutedEventArgs e)
    {
        if (TransfersList.SelectedItem is TransferRow r) _ = _app.Engine.Transfers.CancelAsync(r.Id);
    }

    // ------------------------------------------------------------------ remote text panel

    private void OnRemoteKey(object s, KeyEventArgs e)
    {
        if (e.Key == Key.Enter && Keyboard.Modifiers != ModifierKeys.Shift)
        {
            e.Handled = true;
            if (RemoteLive.IsChecked == true) { _ = _app.Engine.SendKeyTapAsync("Enter"); RemoteBox.Clear(); }
            else SendRemoteBox();
        }
        else if (e.Key == Key.Enter)
        {
            e.Handled = true;
            RemoteBox.SelectedText = "\n"; RemoteBox.CaretIndex = RemoteBox.SelectionStart + 1;
        }
        else if (e.Key == Key.Back && RemoteLive.IsChecked == true) _ = _app.Engine.SendKeyTapAsync("Backspace");
    }

    /// <summary>PreviewTextInput carries text after the IME/TSF committed it — exactly what we want to send.</summary>
    private void OnRemoteTextInput(object s, TextCompositionEventArgs e)
    {
        if (RemoteLive.IsChecked == true && !string.IsNullOrEmpty(e.Text)) _ = _app.Engine.SendComposedTextAsync(e.Text);
    }

    private void OnRemoteSend(object s, RoutedEventArgs e) => SendRemoteBox();

    private async void SendRemoteBox()
    {
        var t = RemoteBox.Text;
        if (t.Length == 0) return;
        if (await _app.Engine.SendComposedTextAsync(t)) RemoteBox.Clear();
        else MessageBox.Show("No phone with an active text field.", "UK Pro");
    }

    // ------------------------------------------------------------------ settings

    private void LoadSettingsIntoUi()
    {
        var s = _app.Settings;
        SetAutostart.IsChecked = s.StartWithWindows; SetRemote.IsChecked = s.RemoteControlEnabled;
        SetImages.IsChecked = s.ClipboardImages; SetFiles.IsChecked = s.ClipboardFiles; SetHistory.IsChecked = s.HistoryEnabled;
        SetDownload.Text = s.DownloadFolder;
        SetExcluded.Text = string.Join(Environment.NewLine, s.ExcludedApps);
        SetTrusted.Text = string.Join(Environment.NewLine, s.TrustedApps);
    }

    private void OnSaveSettings(object sender, RoutedEventArgs e)
    {
        var s = _app.Settings;
        s.StartWithWindows = SetAutostart.IsChecked == true; s.RemoteControlEnabled = SetRemote.IsChecked == true;
        s.ClipboardImages = SetImages.IsChecked == true; s.ClipboardFiles = SetFiles.IsChecked == true; s.HistoryEnabled = SetHistory.IsChecked == true;
        s.DownloadFolder = SetDownload.Text.Trim().Length > 0 ? SetDownload.Text.Trim() : AppPaths.DefaultDownloads;
        static string[] Lines(string t) => t.Split(['\r', '\n'], StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries);
        s.ExcludedApps = new(Lines(SetExcluded.Text), StringComparer.OrdinalIgnoreCase);
        s.TrustedApps = new(Lines(SetTrusted.Text), StringComparer.OrdinalIgnoreCase);
        s.Save();
        MessageBox.Show("Saved. Autostart changes apply at next launch.", "UK Pro");
    }
}
