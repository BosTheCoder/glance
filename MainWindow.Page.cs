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
/// green), or a reminder or a clicked event (calmer blue), with Snooze, Open and Start now (or Dismiss). Nothing
/// here changes the calendar. Topmost but it doesn't take focus, so typing carries on where it was. Several queue up and
/// show one after another, and each closes itself once its event is over.
/// Answering one tells the phone over ntfy.sh (see Sync), and an answer from the phone closes it here.
public partial class MainWindow
{
    record Page(Ev Ev, bool Soft, bool Bell, DateTime Queued);
    readonly List<Page> pages = new();
    Window? pageWindow;
    static string K(Ev e) => Sync.Key(e.Title, e.Start);
    readonly List<Alert> snoozed = new();   // At: when it comes back (see Snooze.Due)

    /// [soft]: a reminder or a click, not a start. A start replaces a reminder for the same event still waiting.
    void ShowPage(Ev e, bool soft, bool bell)
    {
        if (pages.Any(p => K(p.Ev) == K(e) && p.Soft == soft)) return;
        if (!soft) pages.RemoveAll(p => K(p.Ev) == K(e) && p.Soft);
        pages.Add(new(e, soft, bell, DateTime.Now));
        DrawPage();
    }

    /// On to the next page, telling the phone this one's answered.
    void NextPage()
    {
        if (pages.Count > 0) { Answered(K(pages[0].Ev)); pages.RemoveAt(0); }
        DrawPage();
    }

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

    /// Every tick: a reminder's page goes once its event starts (its start page takes over), any page once its event
    /// is over, and what's left is redrawn so "IN 5M" counts down.
    void RefreshPages(DateTime now)
    {
        if (pageWindow == null) return;
        pages.RemoveAll(p => p.Ev.End <= now || p.Soft && p.Bell && p.Ev.Start <= now);
        DrawPage();
    }

    void Drop(Func<Page, bool> gone)
    {
        var first = pages.FirstOrDefault();
        pages.RemoveAll(p => gone(p));
        if (pages.FirstOrDefault() != first) DrawPage();
    }

    void DrawPage()
    {
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
            Child = new Viewbox { Stretch = Stretch.Uniform, Child = PageBody(e, soft, bell) },
        };
    }

    StackPanel PageBody(Ev e, bool soft, bool bell)
    {
        var now = DateTime.Now;
        var started = e.Start <= now;
        var late = now - e.Start >= TimeSpan.FromMinutes(2);
        var p = new StackPanel { Width = 560, Margin = new(30, 28, 30, 28) };
        var head = late ? $"▶  STARTED {Dur(now - e.Start).ToUpper()} AGO"
            : started ? (soft ? "NOW" : "▶  NOW")
            : $"{(bell ? "🔔  " : "")}IN {Dur(e.Start - now).ToUpper()}  ·  {Time(e.Start)}";
        if (pages.Count > 1) head += $"  ·  1 OF {pages.Count}";
        p.Children.Add(new TextBlock { Text = head, FontSize = 15, FontWeight = FontWeights.Bold, Foreground = new SolidColorBrush(soft ? Blue : Green) });
        p.Children.Add(new TextBlock { Text = e.Title, FontSize = 36, FontWeight = FontWeights.Bold, TextWrapping = TextWrapping.Wrap, MaxHeight = 100, TextTrimming = TextTrimming.CharacterEllipsis, Margin = new(0, 8, 0, 4) });
        p.Children.Add(Text($"{Time(e.Start)} – {Time(e.End)}  ·  {(started ? Dur(e.End - now) + " left" : Dur(e.End - e.Start))}", 18, 0.7, new(0, 0, 0, 18)));

        if (e.Link != null) p.Children.Add(PageButton("Open event", Color.FromArgb(0x1F, 0xFF, 0xFF, 0xFF), 46, () => { Process.Start(new ProcessStartInfo(e.Link) { UseShellExecute = true }); NextPage(); }));
        var snoozes = Snooze.Options(e, now);
        if (snoozes.Length > 0)
        {
            p.Children.Add(Text("SNOOZE", 12, 0.55, new(0, 14, 0, 0)));
            var row = new UniformGrid { Rows = 1 };
            foreach (var m in snoozes) row.Children.Add(PageButton($"{m}m", Color.FromRgb(0x22, 0x30, 0x5A), 46, () =>
            {
                snoozed.Add(new(soft ? AlertKind.Reminder : AlertKind.Starting, e, DateTime.Now.AddMinutes(m)));
                NextPage();
            }));
            p.Children.Add(row);
        }
        // The big button: Dismiss for a reminder or a look ahead, Start now for a start. Both just close the page.
        p.Children.Add(soft ? PageButton("Dismiss", Color.FromRgb(0x2F, 0x4A, 0x86), 58, NextPage) : PageButton("Start now", Color.FromRgb(0x1F, 0x8F, 0x5F), 58, NextPage));
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
}
