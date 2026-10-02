using System.Diagnostics;
using System.Net.Http;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Controls.Primitives;
using System.Windows.Media;
using System.Windows.Threading;

namespace Glance;

/// The big alert page in the middle of the screen, Android's StartScreen for the desktop: an event starting (black and
/// green), or a reminder or a clicked event (calmer blue), with Start now, Delay start, Skip and Open. Topmost but it
/// doesn't take focus, so typing carries on where it was. Several queue up and show one after another.
public partial class MainWindow
{
    record Page(Ev Ev, bool Soft, bool Bell);
    readonly List<Page> pages = new();
    Window? pageWindow;
    /// (event id, new start) -> when it was moved here; see Retime.Stale.
    readonly Dictionary<(string?, DateTime), DateTime> moved = new();

    DateTime? MovedAt(Ev e) => moved.TryGetValue((e.Id, e.Start), out var at) ? at : null;

    /// [soft]: a reminder or a click, not a start. A start replaces a reminder for the same event still waiting.
    void ShowPage(Ev e, bool soft, bool bell)
    {
        if (pages.Any(p => p.Ev == e && p.Soft == soft)) return;
        if (!soft) pages.RemoveAll(p => p.Ev == e && p.Soft);
        pages.Add(new(e, soft, bell));
        DrawPage();
    }

    void NextPage()
    {
        if (pages.Count > 0) pages.RemoveAt(0);
        DrawPage();
    }

    void DrawPage(UIElement? content = null)
    {
        if (pages.Count == 0) { pageWindow?.Close(); pageWindow = null; return; }
        var (e, soft, bell) = pages[0];
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
            pageWindow.Closed += (_, _) => { pageWindow = null; pages.Clear(); };
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

    /// Moves [e] to start at [start] ("move") or cancels it ("skip"), then shows how it went, with Undo, for a few seconds.
    async Task Act(Ev e, string kind, DateTime? start)
    {
        if (!demo && e.Id == null) { DrawPage(PageMessage("That's a preview, so there's nothing to change.", null)); return; }
        DrawPage(PageMessage(kind == "move" ? "Changing…" : "Skipping…", null));
        try
        {
            if (!demo) { if (kind == "move") await g.Move(e, start!.Value); else await g.Skip(e); }
            Changed(e, kind == "move" ? e with { Start = start!.Value } : null);
            if (kind == "move") moved[(e.Id, start!.Value)] = DateTime.Now;
            var undo = async () =>
            {
                try
                {
                    if (!demo) { if (kind == "move") await g.Move(e, e.Start); else await g.Restore(e); }
                    Changed(kind == "move" ? e with { Start = start!.Value } : null, e);
                    NextPage();
                }
                catch (Exception x) { DrawPage(PageMessage("Couldn't undo it: " + x.Message, null)); }
            };
            DrawPage(PageMessage(kind == "move" ? $"{e.Title} starts at {Time(start!.Value)}" : $"Skipped {e.Title}", undo));
            var close = new DispatcherTimer { Interval = TimeSpan.FromSeconds(6) };
            var shown = pages.FirstOrDefault();
            close.Tick += (_, _) => { close.Stop(); if (pages.FirstOrDefault() == shown && pageWindow?.Content is Border { Child: Viewbox { Child: Grid } }) NextPage(); };
            close.Start();
        }
        catch (NeedsWrite)
        {
            DrawPage(PageMessage("Glance can only read your calendar so far. Sign in again to let it change events.", null,
                ("Sign in again", async () => { NextPage(); g.SignOut(); await SignIn(); })));
        }
        catch (Exception x) when (x is HttpRequestException or NeedsSignIn or TaskCanceledException)
        {
            DrawPage(PageMessage("Couldn't change it: " + x.Message, null));
        }
    }

    /// The page's after-an-action view: what happened, Undo if it can be undone, and OK.
    Grid PageMessage(string text, Func<Task>? undo, (string Label, Func<Task> Do)? extra = null)
    {
        var p = new StackPanel { Width = 560, Margin = new(30, 40, 30, 36) };
        p.Children.Add(new TextBlock { Text = text, FontSize = 26, FontWeight = FontWeights.SemiBold, TextWrapping = TextWrapping.Wrap });
        var row = new UniformGrid { Rows = 1, Margin = new(0, 20, 0, 0) };
        if (undo != null) row.Children.Add(PageButton("Undo", Color.FromRgb(0x22, 0x30, 0x5A), 50, () => _ = undo()));
        if (extra is { } x) row.Children.Add(PageButton(x.Label, Color.FromRgb(0x22, 0x30, 0x5A), 50, () => _ = x.Do()));
        row.Children.Add(PageButton("OK", Color.FromArgb(0x1F, 0xFF, 0xFF, 0xFF), 50, NextPage));
        p.Children.Add(row);
        return new Grid { Children = { p } };
    }

    /// Puts a change into the events on screen straight away ([to] null = gone), then checks Google for the real thing.
    void Changed(Ev? from, Ev? to)
    {
        if (from != null) { events.Remove(from); banners.RemoveAll(a => a.Event == from); }
        if (to != null) events.Add(to);
        Render();
        if (!demo) { lastFetch = DateTime.MinValue; _ = Refresh(); }
    }
}
