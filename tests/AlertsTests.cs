using Glance;
using Xunit;

public class AlertsTests
{
    static readonly DateTime T = new(2026, 9, 25, 10, 0, 0);
    static Ev Timed(string title, int startMin, int lenMin, params int[] reminders) =>
        new(title, T.AddMinutes(startMin), T.AddMinutes(startMin + lenMin), false, "#fff", reminders);

    [Fact]
    public void Start_and_reminder_fire_once_across_consecutive_checks()
    {
        var events = new[] { Timed("Standup", 1, 15, 10), Timed("Later", 60, 30) };

        // Reminder at 09:51, start at 10:01. Walk the clock in 15 s ticks like the widget does.
        var fired = new List<Alert>();
        var since = T.AddMinutes(-10);
        for (var now = since.AddSeconds(15); now <= T.AddMinutes(5); now = now.AddSeconds(15))
        {
            fired.AddRange(Alerts.Due(events, since, now, reminders: true));
            since = now;
        }

        Assert.Equal(new[] { AlertKind.Reminder, AlertKind.Starting }, fired.Select(a => a.Kind));
        Assert.All(fired, a => Assert.Equal("Standup", a.Event.Title));
    }

    [Fact]
    public void Reminders_off_all_day_starts_and_zero_minute_reminders_do_not_fire()
    {
        var allDay = new Ev("Holiday", T, T.AddDays(1), true, "#fff", [0]);
        var events = new[] { Timed("Standup", 0, 15, 0, 5), allDay };

        Assert.DoesNotContain(Alerts.Due(events, T.AddMinutes(-10), T, reminders: false), a => a.Kind == AlertKind.Reminder);
        var due = Alerts.Due(events, T.AddMinutes(-10), T, reminders: true);
        Assert.Equal(new[] { (AlertKind.Reminder, "Standup"), (AlertKind.Starting, "Standup") }, due.Select(a => (a.Kind, a.Event.Title)));
    }

    [Fact]
    public void Next_change_is_the_sooner_of_current_end_and_next_start()
    {
        var events = new[] { Timed("Deep work", -30, 45), Timed("Lunch", 20, 60), new Ev("Trip", T, T.AddDays(3), true, "#fff") };
        Assert.Equal(T.AddMinutes(15), Alerts.NextChange(events, T));   // deep work ends before lunch starts
        Assert.Null(Alerts.NextChange(events, T.AddHours(3)));            // all-day events never count
    }

    [Fact]
    public void Heads_up_names_whats_next_and_falls_back_to_what_ends()
    {
        var backToBack = new[] { Timed("Standup", -20, 30), Timed("Lunch", 10, 60) };
        var h = Alerts.Coming(backToBack, T.AddMinutes(7), 5);
        Assert.Equal(("Lunch", true), (h!.Event.Title, h.Starts));

        var gapAfter = new[] { Timed("Standup", -20, 30), Timed("Lunch", 40, 60) };
        h = Alerts.Coming(gapAfter, T.AddMinutes(7), 5);
        Assert.Equal(("Standup", false), (h!.Event.Title, h.Starts));

        Assert.Null(Alerts.Coming(backToBack, T.AddMinutes(2), 5));   // 8 min away
        Assert.Null(Alerts.Coming(backToBack, T.AddMinutes(7), 0));   // turned off
    }
}
