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

/// New start times for the alert page's buttons, the same rules as Android's Retime. Only the start moves; the end
/// stays, so later plans don't shift. Times are whole minutes, as a calendar shows them.
public static class Retime
{
    public static readonly int[] DelayMinutes = [2, 5, 10, 15];
    public static DateTime StartNow(DateTime now) => new(now.Year, now.Month, now.Day, now.Hour, now.Minute, 0, now.Kind);
    /// [minutes] after the start, or after now if the start has passed: "I need 5 more minutes".
    public static DateTime Delayed(Ev e, int minutes, DateTime now) => (e.Start > StartNow(now) ? e.Start : StartNow(now)).AddMinutes(minutes);
    /// The delays that still leave some of the event.
    public static int[] Delays(Ev e, DateTime now) => DelayMinutes.Where(m => Delayed(e, m, now) < e.End).ToArray();
    /// Started a while ago: Start now then moves its start to now instead of just closing the page.
    public static bool Late(Ev e, DateTime now) => now - e.Start >= TimeSpan.FromMinutes(2);
    /// An alert that was already due ([due]) when its event was moved at [movedAt] isn't news: delay 5 min and the
    /// "in 5 min" heads-up is stale, delay 15 and it still comes 5 min before the new start.
    public static bool Stale(DateTime due, DateTime? movedAt) => movedAt is DateTime m && due <= m;
}

/// "I've answered this alert", passed between Windows and the phone over ntfy.sh: the same topic from the Google
/// account and the same key from an event's title and start minute, so nothing needs pairing and no titles leave the
/// machine. Must match Android's Sync object exactly.
public static class Sync
{
    public static string Key(string title, DateTime start) => Sha($"{title}|{new DateTimeOffset(start).ToUnixTimeSeconds() / 60}")[..16];
    public static string Topic(string account) => "glance-" + Sha("glance-sync:" + account.ToLowerInvariant())[..20];
    static string Sha(string s) => Convert.ToHexString(System.Security.Cryptography.SHA256.HashData(System.Text.Encoding.UTF8.GetBytes(s))).ToLowerInvariant();

    /// What to stretch after delaying [moved] (as it was): everything on when it was due or now (so the one that ran
    /// over into it counts), or failing that the event that ended last (in the past 6 hours). Extended by the delay, it
    /// runs on until the delayed event now starts.
    public static List<Ev> Extendable(IEnumerable<Ev> events, Ev moved, DateTime now)
    {
        var timed = events.Where(e => !e.AllDay && !(e.Id != null ? e.Id == moved.Id : e.Title == moved.Title)).ToList();
        var since = moved.Start < now ? moved.Start : now;
        var on = timed.Where(e => e.Start <= now && e.End >= since).ToList();
        if (on.Count > 0) return on;
        var last = timed.Where(e => e.End <= now && e.End > now.AddHours(-6)).MaxBy(e => e.End);
        return last == null ? [] : [last];
    }
}
