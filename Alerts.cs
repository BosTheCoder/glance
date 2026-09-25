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
