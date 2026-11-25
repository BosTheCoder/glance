using System.Text.Json.Nodes;
using Glance;
using Xunit;

public class FocusTests
{
    [Fact]
    public void Totals_use_focused_time_without_pauses_and_split_a_record_across_its_tasks()
    {
        // Real shapes from get_focuses_by_time: a paused pomodoro (21m10s focused over 30 min), a stopwatch entry,
        // and a record that switched task halfway.
        var records = JsonNode.Parse("""
        [
          {"type":0,"duration":1270000,"pauseDuration":544,"startTime":"2026-11-25T13:16:24+0000","endTime":"2026-11-25T13:46:38+0000",
           "tasks":[{"title":"ML Theory","startTime":"2026-11-25T13:16:24+0000","endTime":"2026-11-25T13:17:07+0000"},
                    {"title":"ML Theory","startTime":"2026-11-25T13:17:16+0000","endTime":"2026-11-25T13:37:42+0000"}]},
          {"type":1,"duration":3000000,"startTime":"2026-11-20T12:00:00+0000","endTime":"2026-11-20T12:50:00+0000",
           "tasks":[{"title":"Work","startTime":"2026-11-20T12:00:00+0000","endTime":"2026-11-20T12:50:00+0000"}]},
          {"type":0,"duration":1200000,"startTime":"2026-11-21T09:00:00+0000","endTime":"2026-11-21T09:20:00+0000",
           "tasks":[{"title":"Work","startTime":"2026-11-21T09:00:00+0000","endTime":"2026-11-21T09:10:00+0000"},
                    {"title":"Reading","startTime":"2026-11-21T09:10:00+0000","endTime":"2026-11-21T09:20:00+0000"}]},
          {"type":0,"startTime":"2026-11-22T09:00:00+0000","endTime":"2026-11-22T09:25:00+0000","pauseDuration":300,"tasks":[]}
        ]
        """)!.AsArray();

        var totals = FocusTime.Totals(records).ToDictionary(t => t.Task, t => t.Time);

        Assert.Equal(TimeSpan.FromMinutes(60), totals["Work"]);
        Assert.Equal(TimeSpan.FromMinutes(10), totals["Reading"]);
        Assert.Equal(TimeSpan.FromMilliseconds(1270000), totals["ML Theory"]);
        Assert.Equal(TimeSpan.FromMinutes(20), totals["No task"]);   // no duration: end - start - pause
        Assert.Equal("Work", FocusTime.Totals(records)[0].Task);
    }

    [Fact]
    public void Ranges_start_at_midnight_monday_and_the_first()
    {
        var wed = new DateTime(2026, 11, 25, 14, 17, 0);
        Assert.Equal(new DateTime(2026, 11, 25), FocusTime.Start("Today", wed));
        Assert.Equal(new DateTime(2026, 11, 23), FocusTime.Start("Week", wed));
        Assert.Equal(new DateTime(2026, 11, 23), FocusTime.Start("Week", new DateTime(2026, 11, 29, 23, 0, 0)));   // Sunday is still that week
        Assert.Equal(new DateTime(2026, 11, 1), FocusTime.Start("Month", wed));
        Assert.Equal("Today", FocusTime.NextRange("Month"));
    }
}
