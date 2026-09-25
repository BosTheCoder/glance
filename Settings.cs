using System.IO;
using System.Text.Json;
using System.Text.Json.Serialization;
using System.Windows.Media;

namespace Glance;

/// Everything the user can change, saved as settings.json next to Glance.exe.
/// Hand edits are picked up live (see MainWindow's file watcher).
public class Settings
{
    // Placement and size
    public double? Left { get; set; }
    public double? Top { get; set; }
    public double Width { get; set; } = 300;
    public double? IdleWidth { get; set; }          // width when collapsed (Compact view, not hovered); null = same as Width
    public double ListHeight { get; set; } = 320;   // max height of the expanded agenda
    public double Scale { get; set; } = 1.0;

    // Behaviour
    public bool Pinned { get; set; } = true;
    public bool ShowInTaskbar { get; set; } = true;
    public string? Hotkey { get; set; } = "Win+Shift+G";   // global hide/show; null or "" = none
    public double IdleOpacity { get; set; } = 0.5;
    public string? Docked { get; set; }             // null, or "Left"/"Right": shrunk to a strip on that screen edge
    public int DockCount { get; set; } = 2;         // events in the side strip (1-5)
    public double DockOpacity { get; set; } = 0.75; // strip opacity when not hovered
    public int RefreshSeconds { get; set; } = 60;   // how often to check Google for changes

    // Look: a named theme, optionally overridden by custom hex colours
    public string Theme { get; set; } = "Graphite";
    public string Glass { get; set; } = "Clear";   // Clear | Frosted | Solid
    public string? Tint { get; set; }               // e.g. "#102030" overrides the theme background
    public string? Text { get; set; }               // e.g. "#FFFFFF" overrides the theme text colour

    // Content
    public List<string>? Calendars { get; set; }    // null = whatever is ticked in Google Calendar
    public string AllDay { get; set; } = "Always";   // today's all-day events as chips: Always | Hover | List (old style, in the agenda only)
    public string View { get; set; } = "Compact";   // Compact: agenda opens on hover | Full: agenda always open
    public int NextCount { get; set; } = 1;         // upcoming events shown when collapsed (1-7)
    public int MaxEvents { get; set; } = 10;        // events in the expanded list, 0 = all
    public int DaysAhead { get; set; } = 7;         // 0 = today only
    public bool Clock24 { get; set; } = true;

    // Travel events: public transport times from TfL (docs/travel.md)
    public bool Travel { get; set; } = true;
    public int TravelBuffer { get; set; } = 5;          // minutes to get ready, taken off each departure
    public Dictionary<string, string>? Places { get; set; }   // extra named places, e.g. {"Gym": "…, London E16 1AA"}

    // Alerts
    public int HeadsUpMinutes { get; set; } = 5;       // amber countdown before things change; 0 = off
    public bool Reminders { get; set; } = true;        // show the events' own Google reminders
    public string Sound { get; set; } = "Reminders";   // Off | Reminders (heads-ups and reminders) | All (starts too)
    public string Shake { get; set; } = "Reminders";   // a little shake to catch your eye: same choices as Sound
    public bool AlertsReveal { get; set; } = true;     // bring the widget back for an alert if it's hidden

    public static string Path => System.IO.Path.Combine(AppContext.BaseDirectory, "settings.json");
    static readonly JsonSerializerOptions Json = new() { WriteIndented = true };

    public static Settings Load()
    {
        try { return JsonSerializer.Deserialize<Settings>(File.ReadAllText(Path)) ?? new(); }
        catch { return new(); }
    }

    public void Save() => File.WriteAllText(Path, JsonSerializer.Serialize(this, Json));

    public static readonly Dictionary<string, (string Tint, string Text)> Themes = new()
    {
        ["Graphite"] = ("#101010", "#FFFFFF"),
        ["Midnight"] = ("#0B1030", "#EEF1FF"),
        ["Ocean"] = ("#06304A", "#E6F6FF"),
        ["Forest"] = ("#0E2E1E", "#E9FBEF"),
        ["Plum"] = ("#2E0F36", "#FBEEFF"),
        ["Ember"] = ("#3A1606", "#FFF2E6"),
        ["Light"] = ("#F4F4F4", "#161616"),
    };

    public (Color Tint, Color Text) Colors()
    {
        var t = Themes.GetValueOrDefault(Theme, Themes["Graphite"]);
        return (Parse(Tint) ?? Parse(t.Tint)!.Value, Parse(Text) ?? Parse(t.Text)!.Value);
    }

    [JsonIgnore] public byte GlassAlpha => Glass switch { "Solid" => 0xEE, "Frosted" => 0x99, _ => 0x30 };

    static Color? Parse(string? hex)
    {
        try { return hex == null ? null : (Color)ColorConverter.ConvertFromString(hex); }
        catch { return null; }
    }
}
