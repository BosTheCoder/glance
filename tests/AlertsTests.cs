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

    // Fails if: a delay counts from a start that has passed (the new start lands in the past and the alert fires
    // straight back), a delay that would swallow the event is offered, or "in 5 min" pops right after delaying 5 min.
    [Fact]
    public void Delays_move_the_start_and_alerts_already_due_when_moved_are_stale()
    {
        var e = Timed("Write", 0, 12);
        Assert.Equal(T.AddMinutes(5), Retime.Delayed(e, 5, T));
        Assert.Equal(T.AddMinutes(9), Retime.Delayed(e, 2, T.AddMinutes(7).AddSeconds(30)));   // seen late: from now, to the minute
        Assert.Equal(new[] { 2, 5, 10 }, Retime.Delays(e, T));
        Assert.True(Retime.Late(e, T.AddMinutes(2)));
        Assert.False(Retime.Late(e, T.AddSeconds(90)));

        // Delayed at T: a 5-min heads-up for +5 opened at T (stale), for +15 it opens at T+10 (still comes).
        Assert.True(Retime.Stale(T.AddMinutes(5).AddMinutes(-5), T));
        Assert.False(Retime.Stale(T.AddMinutes(15).AddMinutes(-5), T));
        Assert.True(Retime.Stale(T, T));             // Start now: its own start alert
        Assert.False(Retime.Stale(T, null));         // never moved
    }

    // Fails if: Windows and the phone stop agreeing on an event's key or the account's topic (the Android test asserts
    // the same values), so an answer on one no longer clears the other.
    [Fact]
    public void Sync_key_and_topic_match_android()
    {
        Assert.Equal("f7937b3128b02ef3", Sync.Key("Standup", DateTimeOffset.FromUnixTimeMilliseconds(1790916000000).LocalDateTime));
        Assert.Equal("glance-d0b01ebc6c9bb6feda89", Sync.Topic("Kelvin@Example.com"));
    }

    // Fails if: a delay offers to extend the delayed event itself, misses one of several on now, or offers nothing between events.
    [Fact]
    public void Extend_offers_whats_on_else_the_last_to_end()
    {
        var next = Timed("Next", 0, 30) with { Id = "n" };
        var on = new[] { Timed("A", -30, 60) with { Id = "a" }, Timed("B", -10, 20) with { Id = "b" }, next };
        Assert.Equal(new[] { "a", "b" }, Sync.Extendable(on, next, T).Select(e => e.Id));
        var ranOver = Timed("Reading", -20, 20) with { Id = "ro" };   // ended just as Next was due; delayed 7 min late
        Assert.Equal(new[] { "ro" }, Sync.Extendable(new[] { ranOver, next }, next, T.AddMinutes(7)).Select(e => e.Id));
        var between = new[] { Timed("Old", -90, 30) with { Id = "o" }, Timed("Recent", -50, 40) with { Id = "r" }, next };
        Assert.Equal(new[] { "r" }, Sync.Extendable(between, next, T).Select(e => e.Id));
    }
}
