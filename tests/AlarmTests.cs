using Glance;
using Xunit;

// Fails if: a non-tailnet URL is trusted (the anti-spam guarantee), a malformed alarm parses, an id fires twice, or the
// topic stops matching Android's (the concierge app posts to it).
public class AlarmTests
{
    [Theory]
    [InlineData("https://box.tail1234.ts.net:18787/api/alarms/1", true)]
    [InlineData("  https://x.ts.net/a\n", true)]
    [InlineData("http://box.tail1234.ts.net/a", false)]
    [InlineData("https://ts.net.evil.com/a", false)]
    [InlineData("https://box.ts.net.evil.com/a", false)]
    [InlineData("https://evil.com/x.ts.net", false)]
    [InlineData("https://x.ts.net@evil.com/a", false)]
    [InlineData("not a url", false)]
    public void Only_tailnet_https_is_trusted(string url, bool ok) => Assert.Equal(ok, Alarm.Trusted(url));

    [Fact]
    public void Parses_a_well_formed_alarm_and_rejects_the_rest()
    {
        var a = Alarm.Parse("""{"id":"a1","level":"critical","title":"T","body":"B","open":"https://x.ts.net/t/1"}""");
        Assert.Equal(new Alarm("a1", "critical", "T", "B", "https://x.ts.net/t/1"), a);
        Assert.Equal("", Alarm.Parse("""{"id":"a1","level":"needs","open":"javascript:alert(1)"}""")!.Open);
        Assert.Null(Alarm.Parse("""{"id":"a1","level":"loud"}"""));
        Assert.Null(Alarm.Parse("""{"level":"urgent"}"""));
        Assert.Null(Alarm.Parse("<html>"));
    }

    [Fact]
    public void Each_id_fires_once_and_the_set_stays_small()
    {
        var seen = new List<string>();
        Assert.True(Alarm.Remember(seen, "a", keep: 3));
        Assert.False(Alarm.Remember(seen, "a", keep: 3));
        foreach (var id in new[] { "b", "c", "d" }) Alarm.Remember(seen, id, keep: 3);
        Assert.Equal(["b", "c", "d"], seen);
    }

    [Fact]   // the same value is asserted in Android's AlarmTest, and by the concierge app that posts to it
    public void Alarm_topic_is_the_contract() =>
        Assert.Equal("glance-b961b409ea7e67871153", Sync.AlarmTopic("Someone@Example.com"));
}
