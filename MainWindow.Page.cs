using System.Diagnostics;
using System.IO;
using System.Net.Http;
using System.Text.Json.Nodes;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Controls.Primitives;
using System.Windows.Media;
using System.Windows.Threading;

namespace Glance;

/// The big alert page in the middle of the screen, Android's StartScreen for the desktop: an event starting (black and
/// green), or a reminder or a clicked event (calmer blue), with Start now, Delay start, Skip and Open. Topmost but it
/// doesn't take focus, so typing carries on where it was. Several queue up and show one after another.
/// Answering one tells the phone over ntfy.sh (see Sync), and an answer from the phone closes it here.
public partial class MainWindow
{
    record Page(Ev Ev, bool Soft, bool Bell, DateTime Queued);
    readonly List<Page> pages = new();
    Window? pageWindow;
    bool showingResult;   // the page shows how an action went (with Undo), not the event
    static string K(Ev e) => Sync.Key(e.Title, e.Start);
    /// (event id, new start) -> when it was moved here; see Retime.Stale.
    readonly Dictionary<(string?, DateTime), DateTime> moved = new();

    DateTime? MovedAt(Ev e) => moved.TryGetValue((e.Id, e.Start), out var at) ? at : null;

    /// [soft]: a reminder or a click, not a start. A start replaces a reminder for the same event still waiting.
    void ShowPage(Ev e, bool soft, bool bell)
    {
        if (pages.Any(p => K(p.Ev) == K(e) && p.Soft == soft)) return;
        if (!soft) pages.RemoveAll(p => K(p.Ev) == K(e) && p.Soft);
        pages.Add(new(e, soft, bell, DateTime.Now));
        if (pages.Count == 1 || !showingResult) DrawPage();
    }

    /// On to the next page. [send]: tell the phone this one's answered (an action has already done so).
    void NextPage(bool send)
    {
        if (pages.Count > 0) { if (send) Answered(K(pages[0].Ev)); pages.RemoveAt(0); }
        DrawPage();
    }
    void NextPage() => NextPage(true);

    // ---------- sync with the phone ----------

    static readonly HttpClient relay = new() { Timeout = Timeout.InfiniteTimeSpan };
    readonly HashSet<string> sent = new();   // so this machine's own message coming back doesn't close the next page
    CancellationTokenSource? listening;
    string? Topic => demo || g.Account == null ? null : Sync.Topic(g.Account);

    async void Answered(string key)
    {
        sent.Add(key);
        if (Topic is not string t) return;
        try { using var c = new CancellationTokenSource(10_000); await relay.PostAsync("https://ntfy.sh/" + t, new StringContent(key), c.Token); }
        catch { }   // offline: the phone just won't hear about it
    }

    /// While a page is up: every answer sent in the last 10 minutes and from now on (ntfy keeps them), until [stop].
    async Task Listen(CancellationToken stop)
    {
        while (!stop.IsCancellationRequested && Topic is string t)
        {
            try
            {
                using var r = await relay.GetAsync($"https://ntfy.sh/{t}/json?since=10m", HttpCompletionOption.ResponseHeadersRead, stop);
                using var reader = new StreamReader(await r.Content.ReadAsStreamAsync(stop));
                while (await reader.ReadLineAsync(stop) is string line)
                    if (JsonNode.Parse(line) is { } m && (string?)m["event"] == "message")
                        Remote((string)m["message"]!, DateTimeOffset.FromUnixTimeSeconds((long)m["time"]!).LocalDateTime);
            }
            catch when (!stop.IsCancellationRequested) { }
            catch { return; }
            try { await Task.Delay(5000, stop); } catch { return; }
        }
    }

    /// Answered on the phone at [at]: close it here too. Only what was already up then: an answer to the event's
    /// reminder mustn't close its start page that came later.
    void Remote(string key, DateTime at)
    {
        if (sent.Contains(key)) return;
        banners.RemoveAll(a => K(a.Event) == key && a.At <= at);
        Drop(p => K(p.Ev) == key && p.Queued <= at);
        Render();
    }

    /// The calendar changed (say, the phone delayed or skipped it): pages and banners for occurrences that are gone.
    void DropGone()
    {
        bool Gone(Ev e) => e.Id != null && !events.Any(x => x.Id == e.Id && x.Start == e.Start);
        banners.RemoveAll(a => Gone(a.Event));
        Drop(p => Gone(p.Ev));
    }

    /// Takes pages off the queue, but not the one showing how an action here went.
    void Drop(Func<Page, bool> gone)
    {
        var first = pages.FirstOrDefault();
        pages.RemoveAll(p => gone(p) && !(p == first && showingResult));
        if (pages.FirstOrDefault() != first) DrawPage();
    }

    void DrawPage(UIElement? content = null)
    {
        showingResult = content != null;
        if (pages.Count == 0) { pageWindow?.Close(); pageWindow = null; return; }
        var (e, soft, bell, _) = pages[0];
        if (pageWindow == null)
        {
            var wa = Native.WorkArea(this, hwnd);
            pageWindow = new Window
            {
                WindowStyle = WindowStyle.None, AllowsTransparency = true, Background = Brushes.Transparent, ResizeMode = ResizeMode.NoResize,
                Topmost = true, ShowInTaskbar = false, ShowActivated = false, SizeToContent = SizeToContent.Height,
                // A % of a 16:9 screen's width, so an ultrawide doesn't get a page two-thirds of its height.
                Width = Math.Min(wa.Width, wa.Height * 16 / 9) * (s.BigAlerts > 0 ? s.BigAlerts : 40) / 100.0, MaxHeight = wa.Height * 0.9,
                FontFamily = FontFamily, Foreground = Brushes.White,
                Owner = this,   // an owned window stays above its owner, so the pinned widget can't cover it
            };
            pageWindow.Closed += (_, _) => { pageWindow = null; pages.Clear(); listening?.Cancel(); };
            listening = new CancellationTokenSource();
            _ = Listen(listening.Token);
            pageWindow.SizeChanged += (_, _) =>   // centred on the widget's screen, whatever height the content takes
            {
                pageWindow.Left = wa.Left + (wa.Width - pageWindow.ActualWidth) / 2;
                pageWindow.Top = wa.Top + (wa.Height - pageWindow.ActualHeight) / 2;
            };
            pageWindow.Show();
        }
        // Designed at 560 wide; the Viewbox scales it to the window, so the size setting scales everything.
        pageWindow.Content = new Border
        {
            CornerRadius = new(18), Padding = new(0),
            Background = new SolidColorBrush(soft ? Color.FromRgb(0x17, 0x21, 0x3A) : Color.FromRgb(0x0E, 0x0E, 0x10)),
            BorderBrush = new SolidColorBrush(Color.FromArgb(0x90, (soft ? Blue : Green).R, (soft ? Blue : Green).G, (soft ? Blue : Green).B)),
            BorderThickness = new(1.5),
            Child = new Viewbox { Stretch = Stretch.Uniform, Child = content ?? PageBody(e, soft, bell) },
        };
    }

    StackPanel PageBody(Ev e, bool soft, bool bell)
    {
        var now = DateTime.Now;
        var started = e.Start <= now;
        var late = Retime.Late(e, now);
        var p = new StackPanel { Width = 560, Margin = new(30, 28, 30, 28) };
        var head = late ? $"▶  STARTED {Dur(now - e.Start).ToUpper()} AGO"
            : started ? (soft ? "NOW" : "▶  NOW")
            : $"{(bell ? "🔔  " : "")}IN {Dur(e.Start - now).ToUpper()}  ·  {Time(e.Start)}";
        if (pages.Count > 1) head += $"  ·  1 OF {pages.Count}";
        p.Children.Add(new TextBlock { Text = head, FontSize = 15, FontWeight = FontWeights.Bold, Foreground = new SolidColorBrush(soft ? Blue : Green) });
        p.Children.Add(new TextBlock { Text = e.Title, FontSize = 36, FontWeight = FontWeights.Bold, TextWrapping = TextWrapping.Wrap, MaxHeight = 100, TextTrimming = TextTrimming.CharacterEllipsis, Margin = new(0, 8, 0, 4) });
        p.Children.Add(Text($"{Time(e.Start)} – {Time(e.End)}  ·  {(started ? Dur(e.End - now) + " left" : Dur(e.End - e.Start))}", 18, 0.7, new(0, 0, 0, 18)));

        if (e.Link != null) p.Children.Add(PageButton("Open event", Color.FromArgb(0x1F, 0xFF, 0xFF, 0xFF), 46, () => { Process.Start(new ProcessStartInfo(e.Link) { UseShellExecute = true }); NextPage(); }));
        var delays = Retime.Delays(e, now);
        if (delays.Length > 0)
        {
            p.Children.Add(Text("DELAY START", 12, 0.55, new(0, 14, 0, 0)));
            var row = new UniformGrid { Rows = 1 };
            foreach (var m in delays) row.Children.Add(PageButton($"+{m}m", Color.FromRgb(0x22, 0x30, 0x5A), 46, () => _ = Act(e, "move", Retime.Delayed(e, m, DateTime.Now))));
            p.Children.Add(row);
        }
        // The big button is the likeliest next step: Dismiss for a reminder, otherwise Start now. Just started, Start now
        // only closes the page; not yet, or a while ago, it moves the start to now.
        Border StartNow(double h) => PageButton("Start now", Color.FromRgb(0x1F, 0x8F, 0x5F), h, () =>
        {
            if (started && !Retime.Late(e, DateTime.Now)) NextPage(); else _ = Act(e, "move", Retime.StartNow(DateTime.Now));
        });
        var reminder = soft && bell;
        var pair = new UniformGrid { Rows = 1 };
        pair.Children.Add(PageButton("Skip event", Color.FromRgb(0x3A, 0x1F, 0x1F), 46, () => _ = Act(e, "skip", null)));
        if (reminder) pair.Children.Add(StartNow(46));
        else if (soft || late) pair.Children.Add(PageButton("Dismiss", Color.FromArgb(0x1F, 0xFF, 0xFF, 0xFF), 46, NextPage));   // late: you did start on time
        p.Children.Add(pair);
        p.Children.Add(reminder ? PageButton("Dismiss", Color.FromRgb(0x2F, 0x4A, 0x86), 58, NextPage) : StartNow(58));
        return p;
    }

    Border PageButton(string label, Color bg, double height, Action click)
    {
        var b = new Border
        {
            Height = height, CornerRadius = new(12), Margin = new(4, 8, 4, 0), Background = new SolidColorBrush(bg),
            Cursor = System.Windows.Input.Cursors.Hand,
            Child = new TextBlock { Text = label, FontSize = height > 50 ? 20 : 16, HorizontalAlignment = HorizontalAlignment.Center, VerticalAlignment = VerticalAlignment.Center },
        };
        b.MouseEnter += (_, _) => b.Opacity = 0.85;
        b.MouseLeave += (_, _) => b.Opacity = 1;
        b.MouseLeftButtonUp += (_, _) => click();
        return b;
    }

    /// Moves [e] to start at [start] ("move") or cancels it ("skip"), then shows how it went, with Undo. After a delay
    /// it offers to extend what you're on by as much; otherwise the result closes itself after a few seconds.
    async Task Act(Ev e, string kind, DateTime? start)
    {
        if (!demo && e.Id == null) { DrawPage(PageMessage("That's a preview, so there's nothing to change.", null)); return; }
        DrawPage(PageMessage(kind == "move" ? "Changing…" : "Skipping…", null));
        try
        {
            if (!demo) { if (kind == "move") await g.Move(e, start!.Value); else await g.Skip(e); }
            Answered(K(e));
            var now = e with { Start = start ?? e.Start };
            Changed(e, kind == "move" ? now : null);
            if (kind == "move") moved[(e.Id, start!.Value)] = DateTime.Now;
            var undo = async () =>
            {
                try
                {
                    if (!demo) { if (kind == "move") await g.Move(e, e.Start); else await g.Restore(e); }
                    Changed(kind == "move" ? now : null, e);
                    NextPage(false);
                }
                catch (Exception x) { DrawPage(PageMessage("Couldn't undo it: " + x.Message, null)); }
            };
            var delay = kind == "move" ? start!.Value - e.Start : TimeSpan.Zero;
            var stretch = delay > TimeSpan.Zero ? Sync.Extendable(events, e, DateTime.Now) : [];
            var msg = kind == "move" ? $"{e.Title} starts at {Time(start!.Value)}" : $"Skipped {e.Title}";
            if (stretch.Count > 0)
            {
                DrawPage(PageMessage(msg, undo, $"Extend what you're on by {Dur(delay)}?",
                    stretch.Select(x => ($"{x.Title}  ·  until {Time(x.End)} → {Time(x.End + delay)}", (Func<Task>)(() => Extend(x, x.End + delay)))).ToArray()));
                return;
            }
            DrawPage(PageMessage(msg, undo));
            var close = new DispatcherTimer { Interval = TimeSpan.FromSeconds(6) };
            var shown = pages.FirstOrDefault();
            close.Tick += (_, _) => { close.Stop(); if (pages.FirstOrDefault() == shown && showingResult) NextPage(false); };
            close.Start();
        }
        catch (NeedsWrite)
        {
            DrawPage(PageMessage("Glance can only read your calendar so far. Sign in again to let it change events.", null, null,
                ("Sign in again", async () => { NextPage(false); g.SignOut(); await SignIn(); })));
        }
        catch (Exception x) when (x is HttpRequestException or NeedsSignIn or TaskCanceledException)
        {
            DrawPage(PageMessage("Couldn't change it: " + x.Message, null));
        }
    }

    /// After a delay: runs [e] on until [end], with Undo.
    async Task Extend(Ev e, DateTime end)
    {
        try
        {
            if (!demo) await g.Extend(e, end);
            var longer = e with { End = end };
            Changed(e, longer);
            DrawPage(PageMessage($"{e.Title} now runs until {Time(end)}", async () =>
            {
                try { if (!demo) await g.Extend(e, e.End); Changed(longer, e); NextPage(false); }
                catch (Exception x) { DrawPage(PageMessage("Couldn't undo it: " + x.Message, null)); }
            }));
        }
        catch (Exception x) when (x is HttpRequestException or NeedsSignIn or NeedsWrite or TaskCanceledException)
        {
            DrawPage(PageMessage("Couldn't extend it: " + x.Message, null));
        }
    }

    /// The page's after-an-action view: what happened, Undo if it can be undone, any [choices] under [ask], and OK.
    Grid PageMessage(string text, Func<Task>? undo, string? ask = null, params (string Label, Func<Task> Do)[] choices)
    {
        var p = new StackPanel { Width = 560, Margin = new(30, 40, 30, 36) };
        p.Children.Add(new TextBlock { Text = text, FontSize = 26, FontWeight = FontWeights.SemiBold, TextWrapping = TextWrapping.Wrap });
        if (ask != null) p.Children.Add(Text(ask, 18, 0.75, new(0, 14, 0, 4)));
        foreach (var c in choices) p.Children.Add(PageButton(c.Label, Color.FromRgb(0x22, 0x30, 0x5A), 50, () => _ = c.Do()));
        var row = new UniformGrid { Rows = 1, Margin = new(0, 20, 0, 0) };
        if (undo != null) row.Children.Add(PageButton("Undo", Color.FromRgb(0x22, 0x30, 0x5A), 50, () => _ = undo()));
        row.Children.Add(PageButton(ask != null ? "No thanks" : "OK", Color.FromArgb(0x1F, 0xFF, 0xFF, 0xFF), 50, () => NextPage(false)));
        p.Children.Add(row);
        return new Grid { Children = { p } };
    }

    /// Puts a change into the events on screen straight away ([to] null = gone), then checks Google for the real thing.
    void Changed(Ev? from, Ev? to)
    {
        if (from != null) { events.RemoveAll(x => x.Id == from.Id && x.Start == from.Start && x.Title == from.Title); banners.RemoveAll(a => K(a.Event) == K(from)); }
        if (to != null) events.Add(to);
        Render();
        if (!demo) { lastFetch = DateTime.MinValue; _ = Refresh(); }
    }
}
