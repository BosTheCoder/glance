using System.Globalization;
using System.Text.Json.Nodes;
using System.Text.RegularExpressions;

namespace Glance;

/// Focus time from TickTick's focus records (pomodoros and stopwatch), added up per task. No UI.
public static class FocusTime
{
    public static readonly string[] Ranges = ["Today", "Week", "Month"];

    public static string NextRange(string r) => Ranges[(Array.IndexOf(Ranges, r) + 1) % Ranges.Length];

    /// Where the range starts: midnight, Monday, or the 1st. TickTick takes at most a month per call, so Month fits.
    public static DateTime Start(string range, DateTime now) => range switch
    {
        "Week" => now.Date.AddDays(-(((int)now.DayOfWeek + 6) % 7)),
        "Month" => new DateTime(now.Year, now.Month, 1),
        _ => now.Date,
    };

    public static string Label(string range) => range switch { "Week" => "this week", "Month" => "this month", _ => "today" };

    /// Time per task, most first. A record's focused time (its duration, which leaves out pauses) is shared between
    /// the tasks it switched between, in proportion to how long each ran.
    public static List<(string Task, TimeSpan Time)> Totals(IEnumerable<JsonNode?> records)
    {
        var by = new Dictionary<string, double>();
        foreach (var r in records.OfType<JsonObject>())
        {
            var ms = (double?)r["duration"] ?? 0;
            if (ms <= 0 && Date(r["startTime"]) is { } a && Date(r["endTime"]) is { } b)
                ms = (b - a).TotalMilliseconds - ((double?)r["pauseDuration"] ?? 0) * 1000;
            if (ms <= 0) continue;
            var segs = (r["tasks"]?.AsArray() ?? []).OfType<JsonObject>()
                .Select(t => (Name: (string?)t["title"] ?? (string?)t["timerName"] ?? "No task",
                              Len: Date(t["endTime"]) - Date(t["startTime"]) is TimeSpan d ? Math.Max(d.TotalMilliseconds, 0) : 0))
                .ToList();
            var whole = segs.Sum(x => x.Len);
            if (segs.Count == 0 || whole <= 0) { Add("No task", ms); continue; }
            foreach (var (name, len) in segs) Add(name, ms * len / whole);
        }
        return by.OrderByDescending(p => p.Value).Select(p => (p.Key, TimeSpan.FromMilliseconds(p.Value))).ToList();

        void Add(string name, double ms) => by[name] = by.GetValueOrDefault(name) + ms;
    }

    /// "2h 45m", "45m", "0m".
    public static string Dur(TimeSpan t) => t.TotalHours >= 1 ? $"{(int)t.TotalHours}h {t.Minutes:00}m" : $"{(int)t.TotalMinutes}m";

    /// TickTick writes offsets as +0000, which DateTimeOffset won't parse without the colon.
    static DateTimeOffset? Date(JsonNode? n)
    {
        var s = (string?)n;
        if (string.IsNullOrEmpty(s)) return null;
        s = Regex.Replace(s, @"([+-]\d\d)(\d\d)$", "$1:$2");
        return DateTimeOffset.TryParse(s, CultureInfo.InvariantCulture, DateTimeStyles.None, out var d) ? d : null;
    }
}
