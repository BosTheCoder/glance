using System.Diagnostics;
using System.IO;
using System.Media;
using System.Windows.Media.Animation;
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
    string? note;                      // a passing message in the status line (update checks)
    Updater.Release? update;           // a newer release, found by the daily check
    DateTime updateChecked = DateTime.MinValue;
    bool busy, hotkeyOk = true;
    bool expanded;   // agenda showing: while hovered, or always in Full view
    bool hovering;   // mouse is over the widget: full brightness
    DateTime hoveredSince;   // when it expanded under the mouse; clicks on events count only once it has settled
    double? shiftedFrom;   // original Top when expanding had to move the window up to stay on screen
    double widthShift;     // how far expanding moved it left, when it's narrower idle and sits on the right of the screen
    IntPtr hwnd;
    double alpha = 1, alphaTarget = 1;
    Brush card = Brushes.Transparent, track = Brushes.Transparent;
    readonly DispatcherTimer tick = new() { Interval = TimeSpan.FromSeconds(15) };
    readonly DispatcherTimer collapseDelay = new() { Interval = TimeSpan.FromMilliseconds(400) };
    readonly DispatcherTimer fade = new() { Interval = TimeSpan.FromMilliseconds(16) };
    // Docking: throw it (or push it mostly off) a screen edge and it glides there as a narrow strip.
    const double StripWidth = 170;
    const double TopItemWidth = 150;   // docked at the top, events sit side by side
    readonly List<(long Ms, double X, double Y)> dragTrail = new();
    bool dragging;
    readonly DispatcherTimer slide = new() { Interval = TimeSpan.FromMilliseconds(16) };
    readonly DispatcherTimer shake = new() { Interval = TimeSpan.FromMilliseconds(16) };
    double shakeFrom;
    long shakeStart;
    Point slideFrom, slideTo;
    long slideStart;
    // Alerts: amber = something changes soon, green = an event just started, blue = one of the event's reminders.
    static readonly Color Amber = Color.FromRgb(0xF5, 0xA6, 0x23), Green = Color.FromRgb(0x3D, 0xDC, 0x97), Blue = Color.FromRgb(0x7A, 0xA2, 0xFF);
    readonly List<Alert> banners = new();
    readonly HashSet<Alert> pulsed = new();
    DateTime alertsCheckedTo = DateTime.Now.AddMinutes(-2);   // catch something that started just before launch
    bool headsUp;
    HeadsUp? coming;
    DateTime? headsUpFor, headsUpDismissed;   // the change we last alerted for / had its banner clicked away
    readonly FileSystemWatcher watcher = new(AppContext.BaseDirectory, "settings.json");
    // Travel times, per trip (from|to|arrive-by). Options empty = no times, just a link to directions.
    record Trip(List<Journey> Options, string Link, DateTime Fetched, bool Failed, string From, string To, DateTime By);
    readonly Dictionary<string, Trip> trips = new();
    Dictionary<string, string> places = new();
    bool tripsBusy;

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
            if (s.Docked != null) DockTo(s.Docked, animate: false);   // re-fit to the edge in case the screens changed
            FadeTo(IdleOpacity());
            if (!demo && !g.SignedIn && g.ConfigError == null && s.Calendars == null) await SignIn();   // first run
            else await Refresh();
        };

        // Google has no push to a desktop app, so poll: cheap (a few requests per minute) and keeps edits near-live.
        tick.Tick += async (_, _) =>
        {
            if (DateTime.Now - updateChecked > TimeSpan.FromDays(1))   // quietly, so the status line can say when there's one
            {
                updateChecked = DateTime.Now;
                try { update = await Updater.Newer(); } catch { }
            }
            if (DateTime.Now - lastFetch >= TimeSpan.FromSeconds(s.RefreshSeconds - 5)) await Refresh(); else Render();
        };
        tick.Start();
        collapseDelay.Tick += (_, _) => { collapseDelay.Stop(); Collapse(); };
        fade.Tick += (_, _) =>
        {
            alpha += Math.Clamp(alphaTarget - alpha, -0.08, 0.08);
            Native.Alpha(hwnd, alpha);
            if (Math.Abs(alpha - alphaTarget) < 0.001) fade.Stop();
        };

        slide.Tick += (_, _) =>
        {
            var p = Math.Min(1, (Environment.TickCount64 - slideStart) / 320.0);
            var k = 1 - Math.Pow(1 - p, 3);   // ease out: quick start, gentle landing
            Left = slideFrom.X + (slideTo.X - slideFrom.X) * k;
            Top = slideFrom.Y + (slideTo.Y - slideFrom.Y) * k;
            if (p >= 1) slide.Stop();
        };

        shake.Tick += (_, _) =>
        {
            var t = (Environment.TickCount64 - shakeStart) / 1000.0;
            if (t >= 0.45 || dragging) { shake.Stop(); if (!dragging) Left = shakeFrom; return; }
            Left = shakeFrom + 10 * Math.Sin(2 * Math.PI * 11 * t) * (1 - t / 0.45);   // a few quick, fading wiggles
        };

        MouseEnter += async (_, _) =>
        {
            collapseDelay.Stop();
            if (s.Docked != null) { hovering = true; FadeTo(1); }   // the strip lights up but stays a strip
            else Expand();
            if (DateTime.Now - lastFetch > TimeSpan.FromSeconds(20)) await Refresh();
        };
        MouseLeave += (_, _) => { if (!ContextMenu.IsOpen && !Grip.IsDragging && !IdleGrip.IsDragging) collapseDelay.Start(); };
        ContextMenu.Closed += (_, _) => { if (!IsMouseOver) collapseDelay.Start(); };
        ContextMenuOpening += (_, _) => BuildMenu();
        LocationChanged += (_, _) => { if (dragging) dragTrail.Add((Environment.TickCount64, Left, Top)); };
        MouseLeftButtonDown += (_, down) =>
        {
            slide.Stop();
            var (x0, y0, downAt) = (Left, Top, DateTime.Now);
            dragTrail.Clear();
            dragging = true;
            DragMove();
            dragging = false;
            shiftedFrom = null;
            var wa = Native.WorkArea(this, hwnd);
            if (s.Docked == null)
            {
                var (vx, vy) = Docking.Velocity(dragTrail, Environment.TickCount64);
                var side = Docking.Side(Left, Top, ActualWidth, ActualHeight, wa.Left, wa.Top, wa.Right, vx, vy);
                if (side != null) { DockTo(side); return; }
            }
            else if (Math.Abs(Left - x0) < 4 && Math.Abs(Top - y0) < 4) { Undock(); return; }   // a click brings it back
            // Only in the settled, focused view: a click that lands as the widget is still expanding under the pointer isn't aimed.
            if (s.Docked == null && hovering && downAt - hoveredSince > TimeSpan.FromMilliseconds(500) && Math.Abs(Left - x0) < 4 && Math.Abs(Top - y0) < 4 && LinkAt(down.OriginalSource) is string link)
            {
                Process.Start(new ProcessStartInfo(link) { UseShellExecute = true });   // a click (not a drag) on an event or a travel time opens it
                return;
            }
            else if (s.Docked switch { "Left" => Left < wa.Left + 60, "Right" => Left + ActualWidth > wa.Right - 60, _ => Top < wa.Top + 60 })
            { DockTo(s.Docked); return; }   // slid along the edge
            else { s.Docked = null; ApplyDock(); }   // pulled away from the edge: back to normal where it was dropped
            s.Left = Left + widthShift; s.Top = Top; s.Save();
        };
        Pin.MouseLeftButtonDown += (_, e) => { e.Handled = true; Set(() => s.Pinned = !s.Pinned); };
        Status.MouseLeftButtonDown += async (_, e) =>
        {
            if (!demo && !g.SignedIn) { e.Handled = true; await SignIn(); }
            else if (update != null && error == null) { e.Handled = true; await CheckForUpdates(); }
        };

        Grip.DragStarted += (_, _) => { if (expanded) s.ListHeight = Scroll.ActualHeight; };   // shrink from what's visible
        Grip.DragDelta += (_, e) =>
        {
            s.Width = Math.Clamp(s.Width + e.HorizontalChange / s.Scale, 200, 900);
            if (expanded) s.ListHeight = Math.Clamp(s.ListHeight + e.VerticalChange / s.Scale, 60, 1400);
            ApplySize();
        };
        Grip.DragCompleted += (_, _) => { s.Save(); if (!IsMouseOver) collapseDelay.Start(); };

        // The idle size is set from the expanded view, WYSIWYG: sideways for its width, down/up for more or fewer Up next rows.
        IdleGrip.MouseEnter += (_, _) => IdleOutline.Opacity = 0.6;
        IdleGrip.MouseLeave += (_, _) => { if (!IdleGrip.IsDragging) IdleOutline.Opacity = 0; };
        IdleGrip.DragDelta += (_, _) =>
        {
            // Follow the pointer itself, not drag deltas: rows reflow as the width changes, which would read as vertical movement.
            var p = System.Windows.Input.Mouse.GetPosition(IdleLayer);
            s.IdleWidth = Math.Clamp(p.X / s.Scale, 160, 900);
            if (s.IdleWidth > s.Width) s.Width = s.IdleWidth.Value;   // stretching past the edge widens the expanded view too
            ApplySize();
            var bottom = IdleBottom();
            var row = 24 * s.Scale;   // about one Up next row
            if (p.Y > bottom + row * 0.7 && s.NextCount < 7) { s.NextCount++; Render(); }
            else if (p.Y < bottom - row * 0.7 && s.NextCount > 1) { s.NextCount--; Render(); }
            PlaceIdleFrame();
        };
        IdleGrip.DragCompleted += (_, _) => { IdleOutline.Opacity = 0; s.Save(); if (!IsMouseOver) collapseDelay.Start(); };
        NextPanel.SizeChanged += (_, _) => PlaceIdleFrame();
        Root.SizeChanged += (_, _) => PlaceIdleFrame();

        // Hand edits to settings.json (custom colours etc.) apply without a restart.
        watcher.Changed += (_, _) => Dispatcher.InvokeAsync(async () =>
        {
            await Task.Delay(150);   // let the editor finish writing
            var fresh = Settings.Load();
            fresh.Left = s.Left; fresh.Top = s.Top;
            s = fresh;
            ApplyDock();
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
        finally { busy = false; Render(); _ = LoadTrips(); }
    }

    /// The trip for a travel event, if both ends are known (see docs/travel.md).
    Trip? TripOf(Ev t)
    {
        if (!s.Travel || !Travel.Is(t) || Travel.Destination(t, events, places) is not string to || Travel.Origin(t, events, places) is not string from) return null;
        return trips.GetValueOrDefault($"{from}|{to}|{t.End:O}") ?? new(new(), Travel.GoogleMaps(from, to), DateTime.MinValue, false, from, to, t.End);
    }

    /// Look up times for travel events in the next 12 hours: every 5 min when they're within 2 hours, else hourly.
    async Task LoadTrips()
    {
        if (tripsBusy || !s.Travel) return;
        tripsBusy = true;
        try
        {
            var now = DateTime.Now;
            places = Travel.Places(events, s.Places);
            foreach (var t in events.Where(e => Travel.Is(e) && e.End > now && e.Start < now.AddHours(12)).ToList())
            {
                if (TripOf(t) is not { } trip) continue;
                var ttl = TimeSpan.FromMinutes(trip.Failed || t.Start - now < TimeSpan.FromHours(2) ? 5 : 60);
                if (now - trip.Fetched < ttl) continue;
                trips[$"{trip.From}|{trip.To}|{trip.By:O}"] = await FetchTrip(trip, now);
            }
        }
        finally { tripsBusy = false; Render(); }
    }

    async Task<Trip> FetchTrip(Trip trip, DateTime now)
    {
        var (from, to) = (Travel.Code(trip.From), Travel.Code(trip.To));
        if (from == null || to == null || from == to) return trip with { Fetched = now };   // TfL can't plan it: directions link only
        try
        {
            var buffer = TimeSpan.FromMinutes(s.TravelBuffer);
            var (list, a, b) = await Travel.Fetch(from, to, trip.By, arriving: true);
            list = list.Where(j => j.Depart - buffer >= now).ToList();
            if (list.Count < 3)   // running late, or TfL gave few: the next ones from now
            {
                var (more, a2, b2) = await Travel.Fetch(from, to, now + buffer, arriving: false);
                (list, a, b) = (Travel.Merge(list, more), a ?? a2, b ?? b2);
            }
            var link = list.Count > 0 && b != null ? Travel.Citymapper(a, b, trip.To, trip.By) : Travel.GoogleMaps(trip.From, trip.To);
            return trip with { Options = list, Link = link, Fetched = now, Failed = false };
        }
        catch { return trip with { Fetched = now, Failed = true }; }   // offline: keep what we had, try again in 5 min
    }

    /// The "Later" chip: three more leaving after the last one shown.
    async Task Later(Trip trip)
    {
        var (from, to) = (Travel.Code(trip.From)!, Travel.Code(trip.To)!);
        try
        {
            var (more, _, _) = await Travel.Fetch(from, to, trip.Options[^1].Depart.AddMinutes(1), arriving: false);
            trips[$"{trip.From}|{trip.To}|{trip.By:O}"] = trip with { Options = Travel.Merge(trip.Options, more) };
        }
        catch { }
        Render();
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
        ApplyView();
        if (!hovering) FadeTo(IdleOpacity());
        ApplySize();
        Render();
    }

    double IdleWidth() => Math.Min(s.IdleWidth ?? s.Width, s.Width);

    void ApplySize()
    {
        Root.Width = s.Docked switch { null => expanded ? s.Width : IdleWidth(), "Top" => double.NaN, _ => StripWidth };
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
        NowPanel.Children.Clear(); NextPanel.Children.Clear(); Agenda.Children.Clear(); AllDayPanel.Children.Clear();

        CheckAlerts(now);
        var change = Alerts.NextChange(events, now);
        coming = Alerts.Coming(events, now, s.HeadsUpMinutes);
        headsUp = coming != null;
        if (coming != null && coming.At != headsUpFor && lastFetch != DateTime.MinValue)
        {
            // Once per change: pulse the whole widget (so the side strip gets it too), chime, and come back if hidden.
            headsUpFor = coming.At;
            Root.BeginAnimation(OpacityProperty, new DoubleAnimation(0.3, 1, TimeSpan.FromMilliseconds(450)) { RepeatBehavior = new RepeatBehavior(2) });
            if (!IsVisible && s.AlertsReveal) Show();
            Attention(null);
        }
        // Docked, the banners don't fit, so the strip's rim takes the alert's colour instead.
        Color? rim = s.Docked != null && banners.Count > 0 ? (banners[^1].Kind == AlertKind.Reminder ? Blue : Green) : headsUp ? Amber : null;
        Root.BorderBrush = rim is Color r ? new SolidColorBrush(Color.FromArgb(s.Docked != null ? (byte)0xDD : (byte)0x99, r.R, r.G, r.B)) : Brushes.Transparent;
        RenderBanners(now);
        if (!hovering && IsVisible) FadeTo(IdleOpacity());

        var allDayToday = events.Where(e => e.AllDay && e.Start <= now && e.End > now).ToList();
        if (s.AllDay != "List")
            foreach (var e in allDayToday) AllDayPanel.Children.Add(Chip(e, now));
        ShowAllDay();

        var horizon = DateTime.Today.AddDays(s.DaysAhead + 1);
        var timed = events.Where(e => !e.AllDay && e.End > now && e.Start < horizon).OrderBy(e => e.Start).ToList();
        var current = timed.Where(e => e.Start <= now).ToList();
        var upcoming = timed.Where(e => e.Start > now).ToList();

        foreach (var e in current)
        {
            NowPanel.Children.Add(Card(e, $"until {Time(e.End)}  ·  {Dur(e.End - now)} left", (now - e.Start) / (e.End - e.Start),
                headsUp && e.End == change));
            if (Times(e, now) is { } times) NowPanel.Children.Add(times);
        }
        if (current.Count == 0 && (demo || g.SignedIn))
            NowPanel.Children.Add(Text(upcoming.Count > 0 && upcoming[0].Start.Date == now.Date ? $"Free until {Time(upcoming[0].Start)}" : "Free", 15, 0.8));

        if (s.Docked != null) RenderStrip(now, current, upcoming, change);

        var next = upcoming.Take(s.NextCount).ToList();
        if (next.Count > 0)
        {
            var label = Text($"NEXT  ·  {In(next[0].Start - now, next[0].Start)}", 11, 0.55);
            if (headsUp && next[0].Start == change) { label.Foreground = new SolidColorBrush(Amber); label.Opacity = 1; label.FontWeight = FontWeights.SemiBold; }
            NextPanel.Children.Add(label);
            foreach (var e in next)
            {
                NextPanel.Children.Add(Row(e, 14));
                if (Times(e, now) is { } times) NextPanel.Children.Add(times);
            }
        }

        // Expanded view: everything after "next", grouped by day, with each future day's all-day items first.
        if (s.AllDay == "List")
            foreach (var e in allDayToday) Agenda.Children.Add(Row(e, 13));
        var laterAllDay = events.Where(e => e.AllDay && e.Start.Date > now.Date && e.Start < horizon);
        var rest = upcoming.Skip(s.NextCount).Concat(laterAllDay)
            .OrderBy(e => e.Start.Date).ThenBy(e => !e.AllDay).ThenBy(e => e.Start).AsEnumerable();
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
            if (Times(e, now) is { } times) Agenda.Children.Add(times);
        }
        if (Agenda.Children.Count == 0) Agenda.Children.Add(Text(s.DaysAhead == 0 ? "Nothing else today" : "Nothing else coming up", 12, 0.5));

        var status = error
            ?? (demo || g.SignedIn ? null : "Click to sign in with Google")
            ?? (hotkeyOk ? null : $"Shortcut {s.Hotkey} is taken by another app. Right-click → Hide/show shortcut")
            ?? note
            ?? (update == null ? null : $"Update to {update.Tag} available. Click to install");
        Status.Text = status;
        Status.Visibility = status == null ? Visibility.Collapsed : Visibility.Visible;
    }

    static Brush B(string hex) => (Brush)new BrushConverter().ConvertFromString(hex)!;

    static TextBlock Text(string t, double size, double opacity, Thickness? margin = null) => new()
    {
        Text = t, FontSize = size, Opacity = opacity, Margin = margin ?? new(0, 0, 0, 2), TextTrimming = TextTrimming.CharacterEllipsis,
    };

    /// Time-used bar in the event's colour (amber when it's about to end).
    UIElement Bar(Ev e, double progress, bool endingSoon)
    {
        progress = Math.Clamp(progress, 0, 1);
        var bar = new Grid { Height = 3, Margin = new(0, 6, 0, 0) };
        bar.ColumnDefinitions.Add(new() { Width = new(progress, GridUnitType.Star) });
        bar.ColumnDefinitions.Add(new() { Width = new(1 - progress, GridUnitType.Star) });
        bar.Children.Add(new Border { Background = endingSoon ? new SolidColorBrush(Amber) : B(e.Color), CornerRadius = new(1.5) });
        var rest = new Border { Background = track, CornerRadius = new(1.5) };
        Grid.SetColumn(rest, 1);
        bar.Children.Add(rest);
        return bar;
    }

    UIElement Card(Ev e, string sub, double progress, bool endingSoon = false)
    {
        var bar = Bar(e, progress, endingSoon);
        var sp = new StackPanel();
        sp.Children.Add(new TextBlock { Text = e.Title, FontSize = 17, FontWeight = FontWeights.SemiBold, TextTrimming = TextTrimming.CharacterEllipsis });
        var subText = Text(sub, 12, 0.7);
        if (endingSoon) { subText.Foreground = new SolidColorBrush(Amber); subText.Opacity = 1; }
        sp.Children.Add(subText);
        sp.Children.Add(bar);
        return Clickable(new Border { Background = card, CornerRadius = new(8), Padding = new(10, 7, 10, 9), Margin = new(0, 0, 0, 6), Child = sp }, e);
    }

    /// A compact pill for an all-day event; multi-day ones say which day you're on.
    UIElement Chip(Ev e, DateTime now)
    {
        var days = (int)Math.Round((e.End.Date - e.Start.Date).TotalDays);
        var label = days > 1 ? $"{e.Title}  ·  {(now.Date - e.Start.Date).Days + 1}/{days}" : e.Title;
        var sp = new StackPanel { Orientation = Orientation.Horizontal };
        sp.Children.Add(new Ellipse { Width = 6, Height = 6, Fill = B(e.Color), Margin = new(0, 0, 6, 0), VerticalAlignment = VerticalAlignment.Center });
        sp.Children.Add(new TextBlock { Text = label, FontSize = 11.5, TextTrimming = TextTrimming.CharacterEllipsis, MaxWidth = s.Width - 60 });
        return new Border { Background = card, CornerRadius = new(9), Padding = new(8, 2, 9, 3), Margin = new(0, 0, 5, 4), Child = sp, ToolTip = label };
    }

    void ShowAllDay() => AllDayPanel.Visibility =
        AllDayPanel.Children.Count > 0 && (s.AllDay == "Always" || s.AllDay == "Hover" && expanded) ? Visibility.Visible : Visibility.Collapsed;

    UIElement Row(Ev e, double size)
    {
        var dp = Clickable(new DockPanel { Margin = new(0, 2, 0, 2), Background = Brushes.Transparent }, e);   // transparent: clickable between the words too
        var dot = new Ellipse { Width = 8, Height = 8, Fill = B(e.Color), Margin = new(0, 0, 8, 0), VerticalAlignment = VerticalAlignment.Center };
        var time = new TextBlock
        {
            Text = e.AllDay ? "all day" : Time(e.Start), Width = s.Clock24 ? 46 : 60,
            Opacity = 0.6, FontSize = size - 1, VerticalAlignment = VerticalAlignment.Center,
        };
        DockPanel.SetDock(dot, Dock.Left); DockPanel.SetDock(time, Dock.Left);
        dp.Children.Add(dot); dp.Children.Add(time);
        if (!e.AllDay)   // how long it lasts, for planning around it
        {
            var len = new TextBlock { Text = Dur(e.End - e.Start), Opacity = 0.5, FontSize = size - 2, Margin = new(8, 0, 0, 0), VerticalAlignment = VerticalAlignment.Center };
            if (LeaveAt(e, DateTime.Now) is DateTime leave)   // a travel event: when to leave matters more than how long
            {
                len.Text = null;
                len.Inlines.Add(new System.Windows.Documents.Run("\uE7C0  ") { FontFamily = new FontFamily("Segoe Fluent Icons, Segoe MDL2 Assets") });   // Train
                len.Inlines.Add(new System.Windows.Documents.Run($"leave {Time(leave)}"));
                if (leave - DateTime.Now <= TimeSpan.FromMinutes(s.HeadsUpMinutes)) { len.Foreground = new SolidColorBrush(Amber); len.Opacity = 1; }
            }
            DockPanel.SetDock(len, Dock.Right);
            dp.Children.Add(len);
        }
        dp.Children.Add(new TextBlock { Text = e.Title, FontSize = size, TextTrimming = TextTrimming.CharacterEllipsis });
        return dp;
    }

    /// Tag an element with its event so a click on it can open the event in Google Calendar.
    static T Clickable<T>(T el, Ev e) where T : FrameworkElement
    {
        el.Tag = e;
        if (e.Link != null) el.Cursor = System.Windows.Input.Cursors.Hand;
        return el;
    }

    /// The link under a click: an event's (see Clickable) or a travel time's (see TimeChip).
    static string? LinkAt(object? source)
    {
        for (var d = source as DependencyObject; d != null; d = d is Visual ? VisualTreeHelper.GetParent(d) : LogicalTreeHelper.GetParent(d))
            switch (d)
            {
                case FrameworkElement { Tag: Ev e }: return e.Link;
                case FrameworkElement { Tag: string url }: return url;
            }
        return null;
    }

    // ---------- travel ----------

    List<Journey> Upcoming(Trip trip, DateTime now) => trip.Options.Where(j => j.Depart.AddMinutes(-s.TravelBuffer) >= now.AddMinutes(-1)).ToList();

    /// When to leave: for the one to catch, else (running late) the next one. Null without times.
    DateTime? LeaveAt(Ev e, DateTime now) =>
        TripOf(e) is { } trip && Upcoming(trip, now) is { Count: > 0 } opts ? (Travel.Catch(opts, trip.By) ?? opts[0]).Depart.AddMinutes(-s.TravelBuffer) : null;

    /// Under a travel event in the expanded view: leave → arrive for each option, the one to catch outlined, late ones dimmed.
    UIElement? Times(Ev e, DateTime now)
    {
        if (!expanded || TripOf(e) is not { } trip || trip.Fetched == DateTime.MinValue) return null;   // only trips it has looked up
        var wrap = new WrapPanel { Margin = new(16, 1, 0, 4) };
        var opts = Upcoming(trip, now);
        var best = Travel.Catch(opts, trip.By);
        var app = trip.Link.Contains("citymapper") ? "Citymapper" : "Google Maps";
        foreach (var j in opts)
        {
            var leave = j.Depart.AddMinutes(-s.TravelBuffer);
            var c = TimeChip($"{Time(leave)} → {Time(j.Arrive)}", trip.Link);
            c.ToolTip = $"Leave {Time(leave)}{(s.TravelBuffer > 0 ? $" ({s.TravelBuffer} min to get ready, out the door {Time(j.Depart)})" : "")}\n" +
                        $"{j.Via}  ·  {Dur(j.Arrive - j.Depart)}\nArrive {Time(j.Arrive)}{(j.Arrive > trip.By ? $", {Dur(j.Arrive - trip.By)} late" : "")}\nClick to open in {app}";
            if (j == best) c.BorderBrush = new SolidColorBrush(Green);
            if (j.Arrive > trip.By) c.Opacity = 0.5;
            wrap.Children.Add(c);
        }
        if (opts.Count > 0)
        {
            var later = TimeChip("Later", null);
            later.Opacity = 0.7;
            later.MouseLeftButtonDown += async (_, a) => { a.Handled = true; await Later(trip); };
            wrap.Children.Add(later);
        }
        else wrap.Children.Add(TimeChip("Directions", trip.Link));
        return wrap;
    }

    Border TimeChip(string text, string? link) => new()
    {
        Background = card, CornerRadius = new(9), Padding = new(8, 1, 8, 2), Margin = new(0, 0, 5, 4), Tag = link,
        BorderBrush = Brushes.Transparent, BorderThickness = new(1), Cursor = System.Windows.Input.Cursors.Hand,
        Child = new TextBlock { Text = text, FontSize = 11.5 },
    };

    static string Dur(TimeSpan t) =>
        t.TotalMinutes < 60 ? $"{Math.Max(1, (int)Math.Ceiling(t.TotalMinutes))}m"
        : t.Minutes == 0 ? $"{(int)t.TotalHours}h" : $"{(int)t.TotalHours}h {t.Minutes}m";

    string In(TimeSpan t, DateTime at) => t.TotalHours < 12 ? $"IN {Dur(t).ToUpper()}" : $"{at:ddd} {Time(at)}".ToUpper();

    // ---------- updates ----------

    /// Menu → Check for updates (or click the "update available" line): install if there's a newer release.
    async Task CheckForUpdates()
    {
        note = "Checking for updates…"; Render();
        try
        {
            update = await Updater.Newer();
            updateChecked = DateTime.Now;
            if (update != null)
            {
                note = $"Updating to {update.Tag}…"; Render();
                await Updater.Install(update);
                return;
            }
            note = $"You're on the latest (v{Updater.Current})";
        }
        catch (Exception e) { note = "Update failed: " + e.Message; }
        Render();
        await Task.Delay(6000);
        note = null; Render();
    }

    // ---------- side strip ----------

    /// The docked form: what's on with time left and a bar, then what's next, [DockCount] events in all.
    void RenderStrip(DateTime now, List<Ev> current, List<Ev> upcoming, DateTime? change)
    {
        Strip.Children.Clear();
        var top = s.Docked == "Top";
        Strip.Orientation = top ? Orientation.Horizontal : Orientation.Vertical;
        foreach (var a in banners.Where(b => b.Kind == AlertKind.Reminder).TakeLast(1))
        {
            var line = new TextBlock { FontSize = 11.5, Foreground = new SolidColorBrush(Blue), Margin = new(0, 0, 0, 6), TextTrimming = TextTrimming.CharacterEllipsis };
            line.Inlines.Add(new System.Windows.Documents.Run("\uEA8F  ") { FontFamily = new FontFamily("Segoe Fluent Icons, Segoe MDL2 Assets") });
            line.Inlines.Add(new System.Windows.Documents.Run(a.Event.Title));
            if (top) { line.Width = TopItemWidth; line.Margin = new(0, 0, 14, 0); }
            Strip.Children.Add(line);
        }
        var items = current.Concat(upcoming).Take(Math.Clamp(s.DockCount, 1, 5)).ToList();
        if (items.Count == 0) Strip.Children.Add(Text("Nothing coming up", 12, 0.6));
        foreach (var e in items)
        {
            var on = e.Start <= now;
            var last = e == items[^1];
            var sp = top ? new StackPanel { Width = TopItemWidth, Margin = new(0, 0, last ? 0 : 14, 0) } : new StackPanel { Margin = new(0, 0, 0, last ? 0 : 8) };
            sp.Children.Add(new TextBlock { Text = e.Title, FontSize = on ? 13 : 12, FontWeight = on ? FontWeights.SemiBold : FontWeights.Normal, TextTrimming = TextTrimming.CharacterEllipsis });
            var until = e.Start - now;
            var sub = on ? $"{Dur(e.End - now)} left" : $"{(until.TotalHours < 12 ? $"in {Dur(until)}" : $"{e.Start:ddd} {Time(e.Start)}")}  ·  {Dur(e.End - e.Start)}";
            if (LeaveAt(e, now) is DateTime leave) sub = $"leave {Time(leave)}  ·  {sub}";
            sp.Children.Add(Text(sub, 11, 0.65, new(0)));
            if (on) sp.Children.Add(Bar(e, (now - e.Start) / (e.End - e.Start), headsUp && e.End == change));
            else if (coming is { Starts: true } h && h.Event == e) { var line = (TextBlock)sp.Children[1]; line.Foreground = new SolidColorBrush(Amber); line.Opacity = 1; }
            Strip.Children.Add(sp);
        }
    }

    /// Swap between the full widget and the strip.
    void ApplyDock()
    {
        var docked = s.Docked != null;
        Main.Visibility = docked ? Visibility.Collapsed : Visibility.Visible;
        Strip.Visibility = docked ? Visibility.Visible : Visibility.Collapsed;
        Grip.Visibility = docked ? Visibility.Collapsed : Visibility.Visible;
        Root.Padding = docked ? new(10, 8, 10, 9) : new(14, 10, 14, 12);
        ApplySize();
        Render();
    }

    void SlideTo(double x, double y)
    {
        slideFrom = new(Left, Top); slideTo = new(x, y); slideStart = Environment.TickCount64;
        slide.Start();
    }

    /// Shrink to the strip and glide flush against that edge of the current screen.
    void DockTo(string side, bool animate = true)
    {
        collapseDelay.Stop();
        if (hovering) Collapse();
        var wa = Native.WorkArea(this, hwnd);   // before shrinking, while it's still on the screen it was dropped on
        s.Docked = side;
        ApplyDock();
        var x = side switch { "Left" => wa.Left, "Right" => wa.Right - StripWidth * s.Scale, _ => Left };
        var y = side == "Top" ? wa.Top : Top;
        if (animate) SlideTo(x, y); else (Left, Top) = (x, y);
        s.Left = x; s.Top = y; s.Save();
        FadeTo(IdleOpacity());
        // Once the strip's real size is known, keep it all on screen along its edge.
        Dispatcher.InvokeAsync(() =>
        {
            if (side == "Top") { slideTo.X = Math.Clamp(slideTo.X, wa.Left, Math.Max(wa.Left, wa.Right - ActualWidth)); if (!slide.IsEnabled) Left = slideTo.X; s.Left = slideTo.X; }
            else { Top = Math.Clamp(Top, wa.Top, Math.Max(wa.Top, wa.Bottom - ActualHeight)); s.Top = Top; }
            s.Save();
        }, DispatcherPriority.Loaded);
    }

    /// Pop back out from the edge as the normal widget, at the same height.
    void Undock()
    {
        var wa = Native.WorkArea(this, hwnd);
        var side = s.Docked;
        s.Docked = null;
        ApplyDock();
        var w = s.Width * s.Scale;
        double x, y;
        if (side == "Top")
        {
            (x, y) = (Math.Clamp(Left, wa.Left, Math.Max(wa.Left, wa.Right - w)), wa.Top + 16);
            (Left, Top) = (x, wa.Top - 40);   // start part-hidden so it drops out
        }
        else
        {
            (x, y) = (side == "Left" ? wa.Left + 16 : wa.Right - w - 16, Top);
            Left = side == "Left" ? wa.Left - w * 0.4 : wa.Right - w * 0.6;   // start part-hidden so it slides out
        }
        SlideTo(x, y);
        s.Left = x; s.Top = y; s.Save();
        if (IsMouseOver) Expand();
    }

    // ---------- hover ----------

    /// Full view keeps the agenda open all the time; Compact only opens it while hovered.
    void ApplyView()
    {
        expanded = hovering || s.View == "Full";
        Scroll.Visibility = expanded ? Visibility.Visible : Visibility.Collapsed;
        ShowAllDay();
        ApplySize();
        IdleLayer.Visibility = hovering && s.View != "Full" && s.Docked == null ? Visibility.Visible : Visibility.Collapsed;
        PlaceIdleFrame();
    }

    /// Outline the part of the expanded widget that stays when idle: the idle width, down to the last Up next row.
    void PlaceIdleFrame()
    {
        if (IdleLayer.Visibility != Visibility.Visible || !IsLoaded) return;
        var bottom = IdleBottom() + 8 * s.Scale;
        var w = IdleWidth() * s.Scale;
        IdleOutline.Width = w; IdleOutline.Height = bottom;
        Canvas.SetLeft(IdleGrip, w - IdleGrip.Width); Canvas.SetTop(IdleGrip, bottom - IdleGrip.Height);
    }

    /// The bottom of the last Up next row, in IdleLayer coordinates. Travel times under it only show expanded, so they don't count.
    double IdleBottom()
    {
        var last = NextPanel.Children.OfType<FrameworkElement>().LastOrDefault(c => c is not WrapPanel) ?? NextPanel;
        return last.TranslatePoint(new Point(0, last.ActualHeight), IdleLayer).Y;
    }

    void Expand()
    {
        if (!hovering) hoveredSince = DateTime.Now;
        hovering = true;
        ApplyView();
        Render();   // travel times show only now
        Pin.Opacity = 0.7;
        Grip.Opacity = 0.5;
        FadeTo(1);
        // Narrower when idle and on the right half of the screen: grow leftwards, so the right edge stays put.
        var wa0 = Native.WorkArea(this, hwnd);
        var grow = (s.Width - IdleWidth()) * s.Scale;
        if (grow > 0 && widthShift == 0 && s.View != "Full" && Left + ActualWidth / 2 > (wa0.Left + wa0.Right) / 2) { widthShift = grow; Left -= grow; }
        // Grow upward instead of off the bottom of the screen.
        Dispatcher.InvokeAsync(() =>
        {
            var wa = Native.WorkArea(this, hwnd);
            if (Top + ActualHeight > wa.Bottom) { shiftedFrom ??= Top; Top = Math.Max(wa.Top, wa.Bottom - ActualHeight); }
        }, DispatcherPriority.Loaded);
    }

    void Collapse()
    {
        hovering = false;
        ApplyView();
        banners.RemoveAll(a => a.Kind == AlertKind.Starting);   // you've hovered, so you've seen it
        Render();
        Scroll.ScrollToTop();
        Pin.Opacity = 0;
        Grip.Opacity = 0;
        if (!expanded && shiftedFrom is double t) { Top = t; shiftedFrom = null; }
        // Back to the idle width; on the right half of the screen keep the right edge where it is.
        var wa = Native.WorkArea(this, hwnd);
        var shrink = (s.Width - IdleWidth()) * s.Scale;
        if (s.View != "Full" && shrink > 0 && (widthShift != 0 || Left + ActualWidth / 2 > (wa.Left + wa.Right) / 2)) Left += shrink;
        widthShift = 0;
        FadeTo(IdleOpacity());
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

    /// Idle is see-through, but an unread alert keeps it fully lit and a change coming up keeps it mostly lit.
    double IdleOpacity()
    {
        var idle = s.Docked != null ? s.DockOpacity : s.IdleOpacity;
        return banners.Count > 0 ? 1 : headsUp ? Math.Max(idle, 0.85) : idle;
    }

    // ---------- alerts ----------

    /// Sound and shake for an alert, per their settings. [kind] null = the heads-up.
    /// Both settings read Off | Reminders (heads-ups and reminders) | All (event starts too).
    void Attention(AlertKind? kind)
    {
        bool Wants(string setting) => setting == "All" || setting == "Reminders" && kind != AlertKind.Starting;
        if (Wants(s.Sound)) SystemSounds.Asterisk.Play();
        if (Wants(s.Shake) && IsVisible && !dragging && !slide.IsEnabled && !shake.IsEnabled)
        {
            shakeFrom = Left; shakeStart = Environment.TickCount64;
            shake.Start();
        }
    }

    void CheckAlerts(DateTime now)
    {
        // A "starting" banner stays until you've hovered the widget (seen it), at most 10 min; a reminder until clicked or its event starts.
        banners.RemoveAll(a => a.Kind == AlertKind.Starting ? now - a.At > TimeSpan.FromMinutes(10) : now >= a.Event.Start);
        if (lastFetch == DateTime.MinValue) return;   // nothing loaded yet; don't advance past alerts we can't see

        var since = alertsCheckedTo < now.AddMinutes(-2) ? now.AddMinutes(-2) : alertsCheckedTo;   // after sleep, don't replay the day
        alertsCheckedTo = now;
        var fresh = Alerts.Due(events, since, now, s.Reminders);
        if (fresh.Count == 0) return;

        banners.AddRange(fresh.Select(a => a with { At = now }));   // At = when shown, for expiry
        if (!IsVisible && s.AlertsReveal) Show();
        Attention(fresh.Any(a => a.Kind == AlertKind.Reminder) ? AlertKind.Reminder : AlertKind.Starting);
    }

    void RenderBanners(DateTime now)
    {
        Banners.Children.Clear();
        if (coming is { } h && h.At != headsUpDismissed && !banners.Any(a => a.Kind == AlertKind.Reminder && a.Event == h.Event))   // the reminder already says it
        {
            var b = Banner("\uE823", Amber, $"{(h.Starts ? "Next" : "Ending")}: {h.Event.Title}", $"in {Dur(h.At - now)}  ·  {Time(h.At)}");   // Clock
            b.MouseLeftButtonDown += (_, e) => { e.Handled = true; headsUpDismissed = h.At; Render(); };
            Banners.Children.Add(b);
        }
        foreach (var a in banners.TakeLast(3))
        {
            var reminder = a.Kind == AlertKind.Reminder;
            var accent = reminder ? Blue : Green;
            var until = a.Event.Start - now;
            var title = reminder ? a.Event.Title : $"Now: {a.Event.Title}";
            var sub = reminder
                ? (a.Event.AllDay ? $"{a.Event.Start:ddd d MMM}" : until.TotalHours < 12 ? $"in {Dur(until)}  ·  {Time(a.Event.Start)}" : $"{a.Event.Start:ddd} {Time(a.Event.Start)}")
                : $"until {Time(a.Event.End)}";

            var b = Banner(reminder ? "\uEA8F" : "\uE768", accent, title, sub);   // Ringer (bell) / Play
            b.MouseLeftButtonDown += (_, e) => { e.Handled = true; banners.Remove(a); Render(); };
            if (pulsed.Add(a))   // a gentle double pulse the first time it appears
                b.BeginAnimation(OpacityProperty, new DoubleAnimation(0.35, 1, TimeSpan.FromMilliseconds(450)) { RepeatBehavior = new RepeatBehavior(2) });
            Banners.Children.Add(b);
        }
    }

    Border Banner(string icon, Color accent, string title, string sub)
    {
        var glyph = new TextBlock
        {
            Text = icon, FontFamily = new FontFamily("Segoe Fluent Icons, Segoe MDL2 Assets"), FontSize = 14,
            Foreground = new SolidColorBrush(accent), Margin = new(0, 2, 10, 0), VerticalAlignment = VerticalAlignment.Top,
        };
        var text = new StackPanel();
        text.Children.Add(new TextBlock { Text = title, FontSize = 13, FontWeight = FontWeights.SemiBold, TextTrimming = TextTrimming.CharacterEllipsis });
        text.Children.Add(Text(sub, 11.5, 0.75));
        var row = new DockPanel();
        DockPanel.SetDock(glyph, Dock.Left);
        row.Children.Add(glyph); row.Children.Add(text);
        return new Border
        {
            Child = row, CornerRadius = new(8), Padding = new(10, 6, 10, 7), Margin = new(0, 0, 0, 6), Cursor = System.Windows.Input.Cursors.Hand,
            Background = new SolidColorBrush(Color.FromArgb(0x26, accent.R, accent.G, accent.B)),
            BorderBrush = new SolidColorBrush(Color.FromArgb(0xB0, accent.R, accent.G, accent.B)), BorderThickness = new(1),
            ToolTip = "Click to dismiss",
        };
    }

    /// Menu → Alerts → Preview: one of each banner using the next event, so you can see what they look like.
    void PreviewAlerts()
    {
        var now = DateTime.Now;
        var e = events.Where(x => !x.AllDay && x.Start > now).OrderBy(x => x.Start).FirstOrDefault()
                ?? new Ev("Example event", now.AddMinutes(10), now.AddMinutes(40), false, "#7986CB");
        banners.Add(new(AlertKind.Starting, e, now));
        banners.Add(new(AlertKind.Reminder, e, now));
        Attention(AlertKind.Reminder);
        Render();
    }
}
