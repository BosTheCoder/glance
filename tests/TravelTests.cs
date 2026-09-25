using System.Text.Json.Nodes;
using Glance;
using Xunit;

public class TravelTests
{
    static readonly DateTime Day = new(2026, 9, 28);
    const string Home = "1 Home St, London E16 2NZ, UK", Office = "Office, 3 Queen Victoria St, London EC4N 4TQ, UK";

    static Ev T(string title, double h, double len, string? where = null) => new(title, Day.AddHours(h), Day.AddHours(h + len), false, "#000", Location: where);

    [Fact]
    public void Ends_come_from_learned_places_and_the_day_so_far()
    {
        var toOffice = T("Travel: to Office", 7, 0.75, Office);
        var gym = T("Travel: gym", 12, 0.25);
        var fromGym = T("Travel: from Gym", 13, 0.25);
        var home = T("Travel: Home ", 17, 0.75);   // no location: learned from another day's trip home
        var events = new List<Ev> { toOffice, gym, T("Gym", 12.25, 0.75), fromGym, home, T("Travel: Home", 24 + 17, 0.75, Home) };
        var places = Travel.Places(events, null);

        Assert.Equal(Home, Travel.Origin(toOffice, events, places));            // nothing earlier that day: home
        Assert.Equal(Office, Travel.Origin(gym, events, places));               // where the last trip went
        Assert.Equal(Office, Travel.Destination(fromGym, events, places));      // back to where the trip out started
        Assert.Equal(Home, Travel.Destination(home, events, places));
        Assert.Equal(Office, Travel.Origin(home, events, places));
        Assert.Null(Travel.Destination(gym, events, places));                   // the gym has no address anywhere
        Assert.Equal("E162NZ", Travel.Code(Home));
    }

    [Fact]
    public void Parses_tfl_and_picks_the_last_one_on_time()
    {
        var json = JsonNode.Parse("""
        {"journeys":[
          {"startDateTime":"2026-09-28T16:40:00","arrivalDateTime":"2026-09-28T17:16:00","legs":[
            {"mode":{"id":"walking","name":"walking"},"routeOptions":[{"name":""}],"departurePoint":{"lat":51.5127,"lon":-0.0908},"arrivalPoint":{"lat":51.51,"lon":-0.09}},
            {"mode":{"id":"dlr","name":"dlr"},"departureTime":"2026-09-28T16:49:00","departurePoint":{"commonName":"Bank DLR Station"},"routeOptions":[{"name":"DLR","directions":["Woolwich Arsenal DLR Station"]}]},
            {"mode":{"id":"walking","name":"walking"},"routeOptions":[],"arrivalPoint":{"lat":51.501,"lon":0.031}}]},
          {"startDateTime":"2026-09-28T16:49:00","arrivalDateTime":"2026-09-28T17:25:00","legs":[
            {"mode":{"id":"bus","name":"bus"},"routeOptions":[{"name":"25"}]},{"mode":{"id":"tube","name":"tube"},"routeOptions":[{"name":"Central"}]}]}
        ]}
        """)!;
        var (list, start, end) = Travel.Parse(json);
        Assert.Equal(["DLR", "25 bus → Central line"], list.Select(j => j.Via));
        Assert.Equal(("51.5127,-0.0908", "51.501,0.031"), (start, end));
        Assert.Equal(new Ride(new DateTime(2026, 9, 28, 16, 49, 0), "DLR", "Bank DLR Station", "Woolwich Arsenal DLR Station"), list[0].First);   // the walk before it doesn't count
        Assert.True(Travel.Catchable(list[0], new DateTime(2026, 9, 28, 16, 45, 0)));    // set off 5 min ago, but the DLR is still to come
        Assert.False(Travel.Catchable(list[0], new DateTime(2026, 9, 28, 16, 50, 0)));
        Assert.Equal(new DateTime(2026, 9, 28, 16, 40, 0), Travel.Catch(list, new DateTime(2026, 9, 28, 17, 20, 0))!.Depart);
        Assert.Equal(new DateTime(2026, 9, 28, 16, 49, 0), Travel.Catch(list, new DateTime(2026, 9, 28, 17, 30, 0))!.Depart);
    }
}
