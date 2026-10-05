namespace Glance;

public enum AlertKind { Starting, Reminder }

public record Alert(AlertKind Kind, Ev Event, DateTime At);

/// The heads-up: at [At], [Event] starts ([Starts]) or, if nothing starts then, ends.
public record HeadsUp(Ev Event, bool Starts, DateTime At);

/// When to nudge. Pure functions over the event list, no UI, so they can be unit tested (see tests/).
public static class Alerts
{
    /// Alerts whose moment fell in (since, now]. Callers advance `since` each check, so each fires once.
    public static List<Alert> Due(IEnumerable<Ev> events, DateTime since, DateTime now, bool reminders)
    {
        var due = new List<Alert>();
        foreach (var e in events)
        {
            if (!e.AllDay && e.Start > since && e.Start <= now) due.Add(new(AlertKind.Starting, e, e.Start));
            if (!reminders) continue;
            foreach (var m in (e.Reminders ?? []).Where(m => m > 0).Distinct())   // 0 = "at start", already covered
            {
                var at = e.Start.AddMinutes(-m);
                if (at > since && at <= now) due.Add(new(AlertKind.Reminder, e, at));
            }
        }
        return due.OrderBy(a => a.At).ToList();
    }

    /// The next moment what you're meant to be doing changes: a current event ending or the next one starting.
    public static DateTime? NextChange(IEnumerable<Ev> events, DateTime now)
    {
        var times = events.Where(e => !e.AllDay)
            .SelectMany(e => new[] { e.Start, e.End })
            .Where(t => t > now);
        return times.Any() ? times.Min() : null;
    }

    /// The heads-up for the next change if it's within [minutes]. When one event ends as another starts, the start wins:
    /// "what's next" is the useful thing to know.
    public static HeadsUp? Coming(IEnumerable<Ev> events, DateTime now, int minutes)
    {
        if (minutes <= 0 || NextChange(events, now) is not DateTime at || at - now > TimeSpan.FromMinutes(minutes)) return null;
        var timed = events.Where(e => !e.AllDay).ToList();
        var starting = timed.FirstOrDefault(e => e.Start == at);
        return starting != null ? new(starting, true, at) : new(timed.First(e => e.End == at), false, at);
    }
}

/// The alert page's Snooze, the same rules as Android's: the page comes back a few minutes later, and the calendar
/// is left alone.
public static class Snooze
{
    public static readonly int[] Minutes = [2, 5, 10, 15];
    /// The lengths that come back before the event ends.
    public static int[] Options(Ev e, DateTime now) => Minutes.Where(m => now.AddMinutes(m) < e.End).ToArray();

    /// Takes the snoozes due by [now] out of [snoozed] (At: when it comes back) and returns them to fire again, with
    /// the event as it is now. Dropped instead: an event moved, skipped or over, and a reminder whose event has started
    /// (its start alert has said so).
    public static List<Alert> Due(List<Alert> snoozed, IEnumerable<Ev> events, DateTime now)
    {
        var back = new List<Alert>();
        foreach (var a in snoozed.Where(a => a.At <= now).ToList())
        {
            snoozed.Remove(a);
            var e = events.FirstOrDefault(x => (a.Event.Id != null ? x.Id == a.Event.Id : x.Title == a.Event.Title) && x.Start == a.Event.Start);
            if (e != null && now < (a.Kind == AlertKind.Reminder ? e.Start : e.End)) back.Add(a with { Event = e, At = now });
        }
        return back;
    }
}

/// "I've answered this alert", passed between Windows and the phone over ntfy.sh: the same topic from the Google
/// account and the same key from an event's title and start minute, so nothing needs pairing and no titles leave the
/// machine. Must match Android's Sync object exactly.
public static class Sync
{
    public static string Key(string title, DateTime start) => Sha($"{title}|{new DateTimeOffset(start).ToUnixTimeSeconds() / 60}")[..16];
    public static string Topic(string account) => "glance-" + Sha("glance-sync:" + account.ToLowerInvariant())[..20];
    static string Sha(string s) => Convert.ToHexString(System.Security.Cryptography.SHA256.HashData(System.Text.Encoding.UTF8.GetBytes(s))).ToLowerInvariant();
}
