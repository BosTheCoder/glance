using System.IO;
using System.Runtime.InteropServices;
using System.Text.Json;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using System.Windows.Interop;
using System.Windows.Media;
using System.Windows.Shapes;
using System.Windows.Threading;

namespace Glance;

public class Settings
{
    public double? Left { get; set; }
    public double? Top { get; set; }
    public bool Pinned { get; set; } = true;
    public double IdleOpacity { get; set; } = 0.5;
    public List<string>? Calendars { get; set; }   // null = whatever is ticked in Google Calendar

    static string P => System.IO.Path.Combine(AppContext.BaseDirectory, "settings.json");
    public static Settings Load() { try { return JsonSerializer.Deserialize<Settings>(File.ReadAllText(P)) ?? new(); } catch { return new(); } }
    public void Save() => File.WriteAllText(P, JsonSerializer.Serialize(this, new JsonSerializerOptions { WriteIndented = true }));
}

public partial class MainWindow : Window
{
    readonly Settings s = Settings.Load();
    readonly GoogleCal g = new();
    List<Cal> cals = new();
    List<Ev> events = new();
    DateTime lastFetch = DateTime.MinValue;
    string? error;
    bool busy;
    double? shiftedFrom;   // original Top when expanding had to move the window up to stay on screen
    IntPtr hwnd;
    double alpha = 1, alphaTarget = 1;
    readonly DispatcherTimer tick = new() { Interval = TimeSpan.FromSeconds(20) };
    readonly DispatcherTimer collapseDelay = new() { Interval = TimeSpan.FromMilliseconds(400) };
    readonly DispatcherTimer fade = new() { Interval = TimeSpan.FromMilliseconds(16) };

    public MainWindow()
    {
        InitializeComponent();
        Topmost = s.Pinned;
        SourceInitialized += (_, _) => Glass();
        // Saved spot if it's still on a monitor, else top-right of the primary screen.
        var wa = SystemParameters.WorkArea;
        Left = s.Left is double l && l >= SystemParameters.VirtualScreenLeft && l < SystemParameters.VirtualScreenLeft + SystemParameters.VirtualScreenWidth - 40 ? l : wa.Right - Width - 24;
        Top = s.Top is double t && t >= SystemParameters.VirtualScreenTop && t < SystemParameters.VirtualScreenTop + SystemParameters.VirtualScreenHeight - 40 ? t : wa.Top + 24;
        Loaded += async (_, _) =>
        {
            FadeTo(s.IdleOpacity);
            if (!g.SignedIn && g.ConfigError == null && s.Calendars == null) await SignIn();   // first run
            else await Refresh();
        };
        Status.MouseLeftButtonDown += async (_, e) => { if (!g.SignedIn) { e.Handled = true; await SignIn(); } };
        tick.Tick += async (_, _) => { if (DateTime.Now - lastFetch > TimeSpan.FromMinutes(5)) await Refresh(); else Render(); };
        tick.Start();
        collapseDelay.Tick += (_, _) => { collapseDelay.Stop(); Collapse(); };
        fade.Tick += (_, _) =>
        {
            alpha += Math.Clamp(alphaTarget - alpha, -0.08, 0.08);
            SetAlpha(alpha);
            if (Math.Abs(alpha - alphaTarget) < 0.001) fade.Stop();
        };

        MouseEnter += async (_, _) =>
        {
            collapseDelay.Stop();
            Expand();
            if (DateTime.Now - lastFetch > TimeSpan.FromMinutes(1)) await Refresh();
        };
        MouseLeave += (_, _) => { if (!ContextMenu.IsOpen) collapseDelay.Start(); };
        ContextMenu.Closed += (_, _) => { if (!IsMouseOver) collapseDelay.Start(); };
        ContextMenuOpening += (_, _) => BuildMenu();
        MouseLeftButtonDown += (_, _) =>
        {
            DragMove();
            shiftedFrom = null;
            s.Left = Left; s.Top = Top; s.Save();
        };
        Pin.MouseLeftButtonDown += (_, e) => { e.Handled = true; TogglePin(); };
        Render();
    }

    // ---------- data ----------

    async Task Refresh()
    {
        if (busy) return;
        busy = true;
        try
        {
            if (g.ConfigError != null) { error = g.ConfigError; return; }
            if (!g.SignedIn) return;
            cals = await g.Calendars();
            if (s.Calendars == null) { s.Calendars = cals.Where(c => c.DefaultOn).Select(c => c.Id).ToList(); s.Save(); }
            var lists = await Task.WhenAll(cals.Where(c => s.Calendars.Contains(c.Id))
                .Select(c => g.Events(c, DateTime.Today, DateTime.Now.AddDays(7))));
            events = lists.SelectMany(x => x).ToList();
            lastFetch = DateTime.Now;
            error = null;
        }
        catch (NeedsSignIn) { events.Clear(); }
        catch (Exception e)
        {
            error = "Offline, showing last known. " + e.Message;
            lastFetch = DateTime.Now.AddMinutes(-4);   // retry in about a minute
        }
        finally { busy = false; Render(); }
    }

    // ---------- view ----------

    void Render()
    {
        var now = DateTime.Now;
        Clock.Text = now.ToString("HH:mm  ·  ddd d MMM");
        Pin.Text = s.Pinned ? "" : "";
        NowPanel.Children.Clear(); NextPanel.Children.Clear(); Agenda.Children.Clear();

        var timed = events.Where(e => !e.AllDay && e.End > now).OrderBy(e => e.Start).ToList();
        var current = timed.Where(e => e.Start <= now).ToList();
        var upcoming = timed.Where(e => e.Start > now).ToList();

        foreach (var e in current)
            NowPanel.Children.Add(Card(e, $"until {e.End:HH:mm}  ·  {Dur(e.End - now)} left", (now - e.Start) / (e.End - e.Start)));
        if (current.Count == 0 && g.SignedIn)
            NowPanel.Children.Add(Text(upcoming.Count > 0 && upcoming[0].Start.Date == now.Date ? $"Free until {upcoming[0].Start:HH:mm}" : "Free", 15, 0.8));

        if (upcoming.FirstOrDefault() is Ev next)
        {
            NextPanel.Children.Add(Text($"NEXT  ·  {In(next.Start - now, next.Start)}", 11, 0.55));
            NextPanel.Children.Add(Row(next, 14));
        }

        // Expanded view: today's all-day items, then everything after "next", grouped by day.
        foreach (var e in events.Where(e => e.AllDay && e.Start <= now && e.End > now))
            Agenda.Children.Add(Row(e, 13));
        DateTime? day = now.Date;
        foreach (var e in upcoming.Skip(1).Take(40))
        {
            if (e.Start.Date != day)
            {
                day = e.Start.Date;
                Agenda.Children.Add(Text(day == now.Date.AddDays(1) ? "TOMORROW" : day.Value.ToString("dddd d MMM").ToUpper(), 11, 0.55, new(0, 10, 0, 2)));
            }
            Agenda.Children.Add(Row(e, 13));
        }
        if (Agenda.Children.Count == 0) Agenda.Children.Add(Text("Nothing else this week", 12, 0.5));

        var status = error ?? (g.SignedIn ? null : "Click to sign in with Google");
        Status.Text = status;
        Status.Visibility = status == null ? Visibility.Collapsed : Visibility.Visible;
    }

    static Brush B(string hex) => (Brush)new BrushConverter().ConvertFromString(hex)!;

    static TextBlock Text(string t, double size, double opacity, Thickness? margin = null) => new()
    {
        Text = t, FontSize = size, Opacity = opacity, Margin = margin ?? new(0, 0, 0, 2), TextTrimming = TextTrimming.CharacterEllipsis,
    };

    static UIElement Card(Ev e, string sub, double progress)
    {
        var bar = new Grid { Height = 3, Margin = new(0, 6, 0, 0) };
        bar.ColumnDefinitions.Add(new() { Width = new(Math.Clamp(progress, 0, 1), GridUnitType.Star) });
        bar.ColumnDefinitions.Add(new() { Width = new(1 - Math.Clamp(progress, 0, 1), GridUnitType.Star) });
        bar.Children.Add(new Border { Background = B(e.Color), CornerRadius = new(1.5) });
        var track = new Border { Background = B("#30FFFFFF"), CornerRadius = new(1.5) };
        Grid.SetColumn(track, 1);
        bar.Children.Add(track);

        var sp = new StackPanel();
        sp.Children.Add(new TextBlock { Text = e.Title, FontSize = 17, FontWeight = FontWeights.SemiBold, TextTrimming = TextTrimming.CharacterEllipsis });
        sp.Children.Add(Text(sub, 12, 0.7));
        sp.Children.Add(bar);
        return new Border { Background = B("#18FFFFFF"), CornerRadius = new(8), Padding = new(10, 7, 10, 9), Margin = new(0, 0, 0, 6), Child = sp };
    }

    static UIElement Row(Ev e, double size)
    {
        var dp = new DockPanel { Margin = new(0, 2, 0, 2) };
        var dot = new Ellipse { Width = 8, Height = 8, Fill = B(e.Color), Margin = new(0, 0, 8, 0), VerticalAlignment = VerticalAlignment.Center };
        var time = new TextBlock { Text = e.AllDay ? "all day" : e.Start.ToString("HH:mm"), Width = 46, Opacity = 0.6, FontSize = size - 1, VerticalAlignment = VerticalAlignment.Center };
        DockPanel.SetDock(dot, Dock.Left); DockPanel.SetDock(time, Dock.Left);
        dp.Children.Add(dot); dp.Children.Add(time);
        dp.Children.Add(new TextBlock { Text = e.Title, FontSize = size, TextTrimming = TextTrimming.CharacterEllipsis });
        return dp;
    }

    static string Dur(TimeSpan t) =>
        t.TotalMinutes < 60 ? $"{Math.Max(1, (int)Math.Ceiling(t.TotalMinutes))}m"
        : t.Minutes == 0 ? $"{(int)t.TotalHours}h" : $"{(int)t.TotalHours}h {t.Minutes}m";

    static string In(TimeSpan t, DateTime at) => t.TotalHours < 12 ? $"IN {Dur(t).ToUpper()}" : at.ToString("ddd HH:mm").ToUpper();

    // ---------- behaviour ----------

    void Expand()
    {
        Scroll.Visibility = Visibility.Visible;
        Pin.Opacity = 0.7;
        FadeTo(1);
        // Grow upward instead of off the bottom of the screen.
        Dispatcher.InvokeAsync(() =>
        {
            var wa = WorkArea();
            if (Top + ActualHeight > wa.Bottom) { shiftedFrom ??= Top; Top = Math.Max(wa.Top, wa.Bottom - ActualHeight); }
        }, DispatcherPriority.Loaded);
    }

    void Collapse()
    {
        Scroll.Visibility = Visibility.Collapsed;
        Scroll.ScrollToTop();
        Pin.Opacity = 0;
        if (shiftedFrom is double t) { Top = t; shiftedFrom = null; }
        FadeTo(s.IdleOpacity);
    }

    async Task SignIn()
    {
        error = "Waiting for Google sign-in in your browser…";
        Render();
        try { await g.SignIn(); error = null; }
        catch (Exception e) { error = "Sign-in failed: " + e.Message; }
        await Refresh();
    }

    void TogglePin()
    {
        s.Pinned = !s.Pinned;
        Topmost = s.Pinned;
        s.Save();
        Render();
    }

    void BuildMenu()
    {
        var m = ContextMenu;
        m.Items.Clear();
        MenuItem Item(string header, Action act) { var i = new MenuItem { Header = header }; i.Click += (_, _) => act(); m.Items.Add(i); return i; }

        Item("Pin on top", TogglePin).IsChecked = s.Pinned;

        var calMenu = new MenuItem { Header = "Calendars", IsEnabled = cals.Count > 0 };
        foreach (var c in cals)
        {
            var i = new MenuItem { Header = c.Name, IsCheckable = true, IsChecked = s.Calendars?.Contains(c.Id) == true, StaysOpenOnClick = true };
            i.Click += async (_, _) =>
            {
                s.Calendars ??= new();
                if (i.IsChecked) s.Calendars.Add(c.Id); else s.Calendars.Remove(c.Id);
                s.Save();
                lastFetch = DateTime.MinValue;
                await Refresh();
            };
            calMenu.Items.Add(i);
        }
        m.Items.Add(calMenu);

        var fadeMenu = new MenuItem { Header = "Opacity when idle" };
        foreach (var o in new[] { 0.25, 0.5, 0.75, 1.0 })
        {
            var i = new MenuItem { Header = $"{o:P0}", IsChecked = Math.Abs(s.IdleOpacity - o) < 0.01 };
            i.Click += (_, _) => { s.IdleOpacity = o; s.Save(); };
            fadeMenu.Items.Add(i);
        }
        m.Items.Add(fadeMenu);

        m.Items.Add(new Separator());
        Item("Refresh now", async () => { lastFetch = DateTime.MinValue; await Refresh(); });
        if (g.SignedIn)
            Item("Sign out", () => { g.SignOut(); events.Clear(); cals.Clear(); Render(); });
        else
            Item("Sign in with Google…", async () => await SignIn());
        Item("Exit", () => Application.Current.Shutdown());
    }

    // ---------- Win32: acrylic, fade, work area ----------

    [DllImport("dwmapi.dll")] static extern int DwmSetWindowAttribute(IntPtr h, int attr, ref int val, int size);
    [DllImport("user32.dll")] static extern int GetWindowLong(IntPtr h, int i);
    [DllImport("user32.dll")] static extern int SetWindowLong(IntPtr h, int i, int v);
    [DllImport("user32.dll")] static extern bool SetLayeredWindowAttributes(IntPtr h, uint key, byte alpha, uint flags);
    [DllImport("user32.dll")] static extern IntPtr MonitorFromWindow(IntPtr h, uint flags);
    [DllImport("user32.dll")] static extern bool GetMonitorInfo(IntPtr m, ref MonitorInfo mi);
    [StructLayout(LayoutKind.Sequential)] struct RectI { public int L, T, R, B; }
    [StructLayout(LayoutKind.Sequential)] struct MonitorInfo { public int Size; public RectI Monitor, Work; public uint Flags; }

    void Glass()
    {
        hwnd = new WindowInteropHelper(this).Handle;
        HwndSource.FromHwnd(hwnd).CompositionTarget.BackgroundColor = Colors.Transparent;
        int dark = 1, acrylic = 3, round = 2;
        DwmSetWindowAttribute(hwnd, 20, ref dark, 4);      // DWMWA_USE_IMMERSIVE_DARK_MODE
        DwmSetWindowAttribute(hwnd, 38, ref acrylic, 4);   // DWMWA_SYSTEMBACKDROP_TYPE = transient (acrylic)
        DwmSetWindowAttribute(hwnd, 33, ref round, 4);     // DWMWA_WINDOW_CORNER_PREFERENCE = round
        // WPF vetoes WS_EX_LAYERED on non-transparent windows; keep it so the whole window can fade.
        HwndSource.FromHwnd(hwnd).AddHook((IntPtr h, int msg, IntPtr w, IntPtr l, ref bool handled) =>
        {
            if (msg == 0x7C && (int)w == -20)   // WM_STYLECHANGING, GWL_EXSTYLE
            {
                Marshal.WriteInt32(l, 4, Marshal.ReadInt32(l, 4) | 0x80000);
                handled = true;
            }
            return IntPtr.Zero;
        });
        SetWindowLong(hwnd, -20, GetWindowLong(hwnd, -20) | 0x80 | 0x80000);   // + WS_EX_TOOLWINDOW: stay out of Alt+Tab
        SetAlpha(alpha);
    }

    void SetAlpha(double a) { if (hwnd != IntPtr.Zero) SetLayeredWindowAttributes(hwnd, 0, (byte)(a * 255), 2); }

    void FadeTo(double target) { alphaTarget = target; fade.Start(); }

    Rect WorkArea()
    {
        var mi = new MonitorInfo { Size = Marshal.SizeOf<MonitorInfo>() };
        if (hwnd == IntPtr.Zero || !GetMonitorInfo(MonitorFromWindow(hwnd, 2), ref mi)) return SystemParameters.WorkArea;
        var m = PresentationSource.FromVisual(this)!.CompositionTarget!.TransformFromDevice;
        return new Rect(m.Transform(new Point(mi.Work.L, mi.Work.T)), m.Transform(new Point(mi.Work.R, mi.Work.B)));
    }
}
