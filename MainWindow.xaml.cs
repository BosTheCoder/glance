using System.IO;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Interop;
using System.Windows.Media;
using System.Windows.Shapes;
using System.Windows.Threading;

namespace Glance;

public partial class MainWindow : Window
{
    Settings s = Settings.Load();
    readonly GoogleCal g = new();
    readonly bool demo;
    List<Cal> cals = new();
    List<Ev> events = new();
    DateTime lastFetch = DateTime.MinValue;
    string? error;
    bool busy, expanded, hotkeyOk = true;
    double? shiftedFrom;   // original Top when expanding had to move the window up to stay on screen
    IntPtr hwnd;
    double alpha = 1, alphaTarget = 1;
    Brush card = Brushes.Transparent, track = Brushes.Transparent;
    readonly DispatcherTimer tick = new() { Interval = TimeSpan.FromSeconds(15) };
    readonly DispatcherTimer collapseDelay = new() { Interval = TimeSpan.FromMilliseconds(400) };
    readonly DispatcherTimer fade = new() { Interval = TimeSpan.FromMilliseconds(16) };
    readonly FileSystemWatcher watcher = new(AppContext.BaseDirectory, "settings.json");

    public MainWindow(bool demo)
    {
        this.demo = demo;
        InitializeComponent();
        PlaceOnScreen();
        SourceInitialized += (_, _) =>
        {
            hwnd = new WindowInteropHelper(this).Handle;
            Native.Init(this, hwnd);
            HwndSource.FromHwnd(hwnd).AddHook((IntPtr h, int msg, IntPtr wp, IntPtr lp, ref bool handled) =>
            {
                if (msg == Native.WM_HOTKEY) { ToggleVisible(); handled = true; }
                return IntPtr.Zero;
            });
            ApplyLook();
            Native.Alpha(hwnd, alpha);
        };
        Loaded += async (_, _) =>
        {
            FadeTo(s.IdleOpacity);
            if (!demo && !g.SignedIn && g.ConfigError == null && s.Calendars == null) await SignIn();   // first run
            else await Refresh();
        };

        // Google has no push to a desktop app, so poll: cheap (a few requests per minute) and keeps edits near-live.
        tick.Tick += async (_, _) => { if (DateTime.Now - lastFetch >= TimeSpan.FromSeconds(s.RefreshSeconds - 5)) await Refresh(); else Render(); };
        tick.Start();
        collapseDelay.Tick += (_, _) => { collapseDelay.Stop(); Collapse(); };
        fade.Tick += (_, _) =>
        {
            alpha += Math.Clamp(alphaTarget - alpha, -0.08, 0.08);
            Native.Alpha(hwnd, alpha);
            if (Math.Abs(alpha - alphaTarget) < 0.001) fade.Stop();
        };

        MouseEnter += async (_, _) =>
        {
            collapseDelay.Stop();
            Expand();
            if (DateTime.Now - lastFetch > TimeSpan.FromSeconds(20)) await Refresh();
        };
        MouseLeave += (_, _) => { if (!ContextMenu.IsOpen && !Grip.IsDragging) collapseDelay.Start(); };
        ContextMenu.Closed += (_, _) => { if (!IsMouseOver) collapseDelay.Start(); };
        ContextMenuOpening += (_, _) => BuildMenu();
        MouseLeftButtonDown += (_, _) =>
        {
            DragMove();
            shiftedFrom = null;
            s.Left = Left; s.Top = Top; s.Save();
        };
        Pin.MouseLeftButtonDown += (_, e) => { e.Handled = true; Set(() => s.Pinned = !s.Pinned); };
        Status.MouseLeftButtonDown += async (_, e) => { if (!demo && !g.SignedIn) { e.Handled = true; await SignIn(); } };

        Grip.DragStarted += (_, _) => { if (expanded) s.ListHeight = Scroll.ActualHeight; };   // shrink from what's visible
        Grip.DragDelta += (_, e) =>
        {
            s.Width = Math.Clamp(s.Width + e.HorizontalChange / s.Scale, 200, 900);
            if (expanded) s.ListHeight = Math.Clamp(s.ListHeight + e.VerticalChange / s.Scale, 60, 1400);
            ApplySize();
        };
        Grip.DragCompleted += (_, _) => { s.Save(); if (!IsMouseOver) collapseDelay.Start(); };

        // Hand edits to settings.json (custom colours etc.) apply without a restart.
        watcher.Changed += (_, _) => Dispatcher.InvokeAsync(async () =>
        {
            await Task.Delay(150);   // let the editor finish writing
            var fresh = Settings.Load();
            fresh.Left = s.Left; fresh.Top = s.Top;
            s = fresh;
            ApplyLook();
        });
        watcher.EnableRaisingEvents = true;
    }

    void PlaceOnScreen()
    {
        // Saved spot if it's still on a monitor, else top-right of the primary screen.
        var wa = SystemParameters.WorkArea;
        bool okX = s.Left is double l && l >= SystemParameters.VirtualScreenLeft && l < SystemParameters.VirtualScreenLeft + SystemParameters.VirtualScreenWidth - 40;
        bool okY = s.Top is double t && t >= SystemParameters.VirtualScreenTop && t < SystemParameters.VirtualScreenTop + SystemParameters.VirtualScreenHeight - 40;
        Left = okX ? s.Left!.Value : wa.Right - s.Width * s.Scale - 24;
        Top = okY ? s.Top!.Value : wa.Top + 24;
    }

    // ---------- data ----------

    async Task Refresh()
    {
        if (busy) return;
        busy = true;
        try
        {
            if (demo) { events = Demo.Events(); lastFetch = DateTime.Now; return; }
            if (g.ConfigError != null) { error = g.ConfigError; return; }
            if (!g.SignedIn) return;
            cals = await g.Calendars();
            if (s.Calendars == null) { s.Calendars = cals.Where(c => c.DefaultOn).Select(c => c.Id).ToList(); s.Save(); }
            var lists = await Task.WhenAll(cals.Where(c => s.Calendars.Contains(c.Id))
                .Select(c => g.Events(c, DateTime.Today, DateTime.Today.AddDays(s.DaysAhead + 1))));
            events = lists.SelectMany(x => x).ToList();
            lastFetch = DateTime.Now;
            error = null;
        }
        catch (NeedsSignIn) { events.Clear(); }
        catch (Exception e)
        {
            error = "Offline, showing last known. " + e.Message;
            lastFetch = DateTime.Now.AddSeconds(Math.Min(0, 60 - s.RefreshSeconds));   // retry in about a minute
        }
        finally { busy = false; Render(); }
    }

    async Task SignIn()
    {
        error = "Waiting for Google sign-in in your browser…";
        Render();
        try { await g.SignIn(); error = null; }
        catch (Exception e) { error = "Sign-in failed: " + e.Message; }
        await Refresh();
    }

    // ---------- look ----------

    /// Push every visual setting onto the window. Called at start, after any menu change and on settings.json edits.
    void ApplyLook()
    {
        var (tint, text) = s.Colors();
        Root.Background = new SolidColorBrush(Color.FromArgb(s.GlassAlpha, tint.R, tint.G, tint.B));
        Foreground = new SolidColorBrush(text);
        card = new SolidColorBrush(Color.FromArgb(0x1C, text.R, text.G, text.B));
        track = new SolidColorBrush(Color.FromArgb(0x33, text.R, text.G, text.B));
        Topmost = s.Pinned;
        ShowInTaskbar = s.ShowInTaskbar;
        if (hwnd != IntPtr.Zero)
        {
            Native.DarkBackdrop(hwnd, 0.299 * tint.R + 0.587 * tint.G + 0.114 * tint.B < 128);
            Native.ToolWindow(hwnd, !s.ShowInTaskbar);
            hotkeyOk = Native.Hotkey(hwnd, s.Hotkey);
        }
        if (!expanded) FadeTo(s.IdleOpacity);
        ApplySize();
        Render();
    }

    void ApplySize()
    {
        Root.Width = s.Width;
        Zoom.ScaleX = Zoom.ScaleY = s.Scale;
        Scroll.MaxHeight = s.ListHeight;
    }

    // ---------- view ----------

    string Time(DateTime d) => s.Clock24 ? d.ToString("HH:mm") : d.ToString("h:mmtt").ToLower();

    void Render()
    {
        var now = DateTime.Now;
        Clock.Text = $"{Time(now)}  ·  {now:ddd d MMM}";
        Pin.Text = s.Pinned ? "" : "";
        NowPanel.Children.Clear(); NextPanel.Children.Clear(); Agenda.Children.Clear();

        var horizon = DateTime.Today.AddDays(s.DaysAhead + 1);
        var timed = events.Where(e => !e.AllDay && e.End > now && e.Start < horizon).OrderBy(e => e.Start).ToList();
        var current = timed.Where(e => e.Start <= now).ToList();
        var upcoming = timed.Where(e => e.Start > now).ToList();

        foreach (var e in current)
            NowPanel.Children.Add(Card(e, $"until {Time(e.End)}  ·  {Dur(e.End - now)} left", (now - e.Start) / (e.End - e.Start)));
        if (current.Count == 0 && (demo || g.SignedIn))
            NowPanel.Children.Add(Text(upcoming.Count > 0 && upcoming[0].Start.Date == now.Date ? $"Free until {Time(upcoming[0].Start)}" : "Free", 15, 0.8));

        var next = upcoming.Take(s.NextCount).ToList();
        if (next.Count > 0)
        {
            NextPanel.Children.Add(Text($"NEXT  ·  {In(next[0].Start - now, next[0].Start)}", 11, 0.55));
            foreach (var e in next) NextPanel.Children.Add(Row(e, 14));
        }

        // Expanded view: today's all-day items, then everything after "next", grouped by day.
        foreach (var e in events.Where(e => e.AllDay && e.Start <= now && e.End > now))
            Agenda.Children.Add(Row(e, 13));
        var rest = upcoming.Skip(s.NextCount);
        if (s.MaxEvents > 0) rest = rest.Take(s.MaxEvents);
        DateTime day = now.Date;
        foreach (var e in rest)
        {
            if (e.Start.Date != day)
            {
                day = e.Start.Date;
                Agenda.Children.Add(Text(day == now.Date.AddDays(1) ? "TOMORROW" : day.ToString("dddd d MMM").ToUpper(), 11, 0.55, new(0, 10, 0, 2)));
            }
            Agenda.Children.Add(Row(e, 13));
        }
        if (Agenda.Children.Count == 0) Agenda.Children.Add(Text(s.DaysAhead == 0 ? "Nothing else today" : "Nothing else coming up", 12, 0.5));

        var status = error
            ?? (demo || g.SignedIn ? null : "Click to sign in with Google")
            ?? (hotkeyOk ? null : $"Shortcut {s.Hotkey} is taken by another app. Right-click → Hide/show shortcut");
        Status.Text = status;
        Status.Visibility = status == null ? Visibility.Collapsed : Visibility.Visible;
    }

    static Brush B(string hex) => (Brush)new BrushConverter().ConvertFromString(hex)!;

    static TextBlock Text(string t, double size, double opacity, Thickness? margin = null) => new()
    {
        Text = t, FontSize = size, Opacity = opacity, Margin = margin ?? new(0, 0, 0, 2), TextTrimming = TextTrimming.CharacterEllipsis,
    };

    UIElement Card(Ev e, string sub, double progress)
    {
        progress = Math.Clamp(progress, 0, 1);
        var bar = new Grid { Height = 3, Margin = new(0, 6, 0, 0) };
        bar.ColumnDefinitions.Add(new() { Width = new(progress, GridUnitType.Star) });
        bar.ColumnDefinitions.Add(new() { Width = new(1 - progress, GridUnitType.Star) });
        bar.Children.Add(new Border { Background = B(e.Color), CornerRadius = new(1.5) });
        var rest = new Border { Background = track, CornerRadius = new(1.5) };
        Grid.SetColumn(rest, 1);
        bar.Children.Add(rest);

        var sp = new StackPanel();
        sp.Children.Add(new TextBlock { Text = e.Title, FontSize = 17, FontWeight = FontWeights.SemiBold, TextTrimming = TextTrimming.CharacterEllipsis });
        sp.Children.Add(Text(sub, 12, 0.7));
        sp.Children.Add(bar);
        return new Border { Background = card, CornerRadius = new(8), Padding = new(10, 7, 10, 9), Margin = new(0, 0, 0, 6), Child = sp };
    }

    UIElement Row(Ev e, double size)
    {
        var dp = new DockPanel { Margin = new(0, 2, 0, 2) };
        var dot = new Ellipse { Width = 8, Height = 8, Fill = B(e.Color), Margin = new(0, 0, 8, 0), VerticalAlignment = VerticalAlignment.Center };
        var time = new TextBlock
        {
            Text = e.AllDay ? "all day" : Time(e.Start), Width = s.Clock24 ? 46 : 60,
            Opacity = 0.6, FontSize = size - 1, VerticalAlignment = VerticalAlignment.Center,
        };
        DockPanel.SetDock(dot, Dock.Left); DockPanel.SetDock(time, Dock.Left);
        dp.Children.Add(dot); dp.Children.Add(time);
        dp.Children.Add(new TextBlock { Text = e.Title, FontSize = size, TextTrimming = TextTrimming.CharacterEllipsis });
        return dp;
    }

    static string Dur(TimeSpan t) =>
        t.TotalMinutes < 60 ? $"{Math.Max(1, (int)Math.Ceiling(t.TotalMinutes))}m"
        : t.Minutes == 0 ? $"{(int)t.TotalHours}h" : $"{(int)t.TotalHours}h {t.Minutes}m";

    string In(TimeSpan t, DateTime at) => t.TotalHours < 12 ? $"IN {Dur(t).ToUpper()}" : $"{at:ddd} {Time(at)}".ToUpper();

    // ---------- hover ----------

    void Expand()
    {
        expanded = true;
        Scroll.Visibility = Visibility.Visible;
        Pin.Opacity = 0.7;
        Grip.Opacity = 0.5;
        FadeTo(1);
        // Grow upward instead of off the bottom of the screen.
        Dispatcher.InvokeAsync(() =>
        {
            var wa = Native.WorkArea(this, hwnd);
            if (Top + ActualHeight > wa.Bottom) { shiftedFrom ??= Top; Top = Math.Max(wa.Top, wa.Bottom - ActualHeight); }
        }, DispatcherPriority.Loaded);
    }

    void Collapse()
    {
        expanded = false;
        Scroll.Visibility = Visibility.Collapsed;
        Scroll.ScrollToTop();
        Pin.Opacity = 0;
        Grip.Opacity = 0;
        if (shiftedFrom is double t) { Top = t; shiftedFrom = null; }
        FadeTo(s.IdleOpacity);
    }

    public void ToggleVisible()
    {
        if (IsVisible) { collapseDelay.Stop(); Collapse(); Hide(); }
        else Reveal();
    }

    /// Show and flash to full brightness so it's easy to spot, then settle back to idle.
    public void Reveal()
    {
        Show();
        alpha = 1;
        Native.Alpha(hwnd, 1);
        if (!IsMouseOver) collapseDelay.Start();
        Render();
    }

    void FadeTo(double target) { alphaTarget = target; fade.Start(); }
}
