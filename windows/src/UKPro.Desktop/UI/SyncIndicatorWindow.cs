using System.Windows;
using System.Windows.Controls;
using System.Windows.Media;
using UKPro.Desktop.Services;

namespace UKPro.Desktop.UI;

/// <summary>
/// Always-visible pill at the top of the screen whenever the physical keyboard is being mirrored
/// (PART 5: visible indicator + immediate pause). It never takes focus.
/// </summary>
public sealed class SyncIndicatorWindow : Window
{
    private readonly SyncEngine _engine;
    private readonly TextBlock _label = new() { Foreground = Brushes.White, FontSize = 12, FontWeight = FontWeights.SemiBold, VerticalAlignment = VerticalAlignment.Center };

    public SyncIndicatorWindow(SyncEngine engine)
    {
        _engine = engine;
        WindowStyle = WindowStyle.None; AllowsTransparency = true; Background = Brushes.Transparent;
        Topmost = true; ShowInTaskbar = false; ShowActivated = false; ResizeMode = ResizeMode.NoResize;
        SizeToContent = SizeToContent.WidthAndHeight;
        var pause = new Button { Content = "Pause", Padding = new Thickness(10, 2, 10, 2), Margin = new Thickness(10, 0, 0, 0), Focusable = false };
        pause.Click += (_, _) => _engine.SetPaused(true);
        Content = new Border
        {
            Background = new SolidColorBrush(Color.FromArgb(0xE6, 0xC2, 0x41, 0x0C)), CornerRadius = new CornerRadius(14),
            Padding = new Thickness(14, 6, 6, 6),
            Child = new StackPanel { Orientation = Orientation.Horizontal, Children = { _label, pause } },
        };
        Loaded += (_, _) => { Left = (SystemParameters.WorkArea.Width - ActualWidth) / 2; Top = 6; };
        Refresh();
    }

    public void Refresh()
    {
        if (_engine.CapturingNow)
        {
            var name = _engine.ActiveTarget is { } t ? App.Current.Hub.GetPeer(t)?.Device.Name : null;
            _label.Text = _engine.LastBlockReason is { } r
                ? $"⌨ Keyboard mirroring paused: {r}"
                : $"⌨ Keyboard mirroring → {name ?? "no phone"}";
            if (!IsVisible) Show();
            Left = (SystemParameters.WorkArea.Width - ActualWidth) / 2;
        }
        else if (IsVisible) Hide();
    }
}
