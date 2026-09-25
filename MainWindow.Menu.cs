using System.Diagnostics;
using System.Windows;
using System.Windows.Controls;
using Microsoft.Win32;

namespace Glance;

/// The right-click menu. Rebuilt on every open so it always reflects the current settings.
public partial class MainWindow
{
    void Set(Action change)
    {
        change();
        s.Save();
        ApplyLook();
    }

    void BuildMenu()
    {
        var m = ContextMenu;
        m.Items.Clear();

        m.Items.Add(Toggle("Pin on top", s.Pinned, v => s.Pinned = v));
        m.Items.Add(Toggle("Show in taskbar", s.ShowInTaskbar, v => s.ShowInTaskbar = v));
        m.Items.Add(Toggle("Start with Windows", StartsWithWindows, v => StartsWithWindows = v));
        m.Items.Add(new Separator());

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

        var theme = Choice("Theme", Settings.Themes.Keys.Select(k => (k, k)), s.Tint == null && s.Text == null ? s.Theme : null,
            v => { s.Theme = v; s.Tint = s.Text = null; });
        theme.Items.Add(new Separator());
        theme.Items.Add(Action("Custom colours… (edit settings.json)", () => Process.Start(new ProcessStartInfo(Settings.Path) { UseShellExecute = true })));
        m.Items.Add(theme);
        m.Items.Add(Choice("Glass", new[] { ("Clear", "Clear"), ("Frosted", "Frosted"), ("Solid", "Solid") }, s.Glass, v => s.Glass = v));
        m.Items.Add(Choice("Opacity when idle", new[] { 0.25, 0.5, 0.75, 1.0 }.Select(o => ($"{o:P0}", o)), s.IdleOpacity, v => s.IdleOpacity = v));
        m.Items.Add(Choice("Text size", new[] { 0.85, 1.0, 1.15, 1.3, 1.5 }.Select(o => ($"{o:P0}", o)), s.Scale, v => s.Scale = v));
        m.Items.Add(new Separator());

        m.Items.Add(Choice("Up next", new[] { 1, 2, 3 }.Select(n => ($"{n}", n)), s.NextCount, v => s.NextCount = v));
        m.Items.Add(Choice("Events when expanded", new[] { ("5", 5), ("10", 10), ("20", 20), ("All", 0) }, s.MaxEvents, v => s.MaxEvents = v));
        m.Items.Add(Choice("Look ahead", new[] { ("Today", 0), ("3 days", 3), ("1 week", 7), ("2 weeks", 14) }, s.DaysAhead, v =>
        {
            s.DaysAhead = v;
            lastFetch = DateTime.MinValue;   // fetch the new range
        }));
        m.Items.Add(Toggle("24-hour clock", s.Clock24, v => s.Clock24 = v));
        m.Items.Add(Choice("Check for changes", new[] { ("Every 30 seconds", 30), ("Every minute", 60), ("Every 5 minutes", 300) }, s.RefreshSeconds, v => s.RefreshSeconds = v));
        m.Items.Add(new Separator());

        m.Items.Add(Action("Reset size and position", () =>
        {
            var d = new Settings();
            Set(() => { s.Width = d.Width; s.ListHeight = d.ListHeight; s.Scale = d.Scale; s.Left = s.Top = null; });
            PlaceOnScreen();
        }));
        m.Items.Add(Action("Refresh now", async () => { lastFetch = DateTime.MinValue; await Refresh(); }));
        if (!demo)
            m.Items.Add(g.SignedIn
                ? Action("Sign out", () => { g.SignOut(); events.Clear(); cals.Clear(); Render(); })
                : Action("Sign in with Google…", async () => await SignIn()));
        m.Items.Add(Action("Exit", () => Application.Current.Shutdown()));
    }

    MenuItem Action(string header, Action act)
    {
        var i = new MenuItem { Header = header };
        i.Click += (_, _) => act();
        return i;
    }

    MenuItem Toggle(string header, bool on, Action<bool> set)
    {
        var i = new MenuItem { Header = header, IsChecked = on };
        i.Click += (_, _) => Set(() => set(!on));
        return i;
    }

    MenuItem Choice<T>(string header, IEnumerable<(string Label, T Value)> options, T? current, Action<T> set)
    {
        var parent = new MenuItem { Header = header };
        foreach (var (label, value) in options)
        {
            var i = new MenuItem { Header = label, IsChecked = Equals(value, current) };
            i.Click += (_, _) => Set(() => set(value));
            parent.Items.Add(i);
        }
        return parent;
    }

    const string RunKey = @"Software\Microsoft\Windows\CurrentVersion\Run";

    static bool StartsWithWindows
    {
        get => Registry.CurrentUser.OpenSubKey(RunKey)?.GetValue("Glance") != null;
        set
        {
            using var k = Registry.CurrentUser.OpenSubKey(RunKey, true)!;
            if (value) k.SetValue("Glance", $"\"{Environment.ProcessPath}\"");
            else k.DeleteValue("Glance", false);
        }
    }
}
