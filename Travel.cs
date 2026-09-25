using System.Globalization;
using System.Net;
using System.Net.Http;
using System.Text.Json.Nodes;
using System.Text.RegularExpressions;

namespace Glance;

/// One way to get there. Depart is when to walk out of the door (TfL counts the walk to the stop).
public record Journey(DateTime Depart, DateTime Arrive, string Via);

/// Travel events ("Travel: to Office") and their public transport times. Rules in docs/travel.md; UI-free so the tests can run it.
public static class Travel
{
    public static bool Is(Ev e) => !e.AllDay && e.Title.TrimStart().StartsWith("travel", StringComparison.OrdinalIgnoreCase);

    /// "Travel: to Office" → ("office", false), "Travel: from Gym" → ("gym", true). Work and office are one place.
    public static (string Name, bool From) Name(string title)
    {
        var t = Regex.Replace(title.Trim(), @"^travel\s*:?\s*", "", RegexOptions.IgnoreCase);
        var m = Regex.Match(t, @"^(to|from)\s+", RegexOptions.IgnoreCase);
        var name = t[m.Length..].Trim().ToLowerInvariant();
        return (name == "work" ? "office" : name, m.Success && m.Groups[1].Value.Equals("from", StringComparison.OrdinalIgnoreCase));
    }

    /// Named places learned from travel events that have a location (the latest wins), then [extra] from settings on top.
    public static Dictionary<string, string> Places(IEnumerable<Ev> events, IDictionary<string, string>? extra)
    {
        var p = new Dictionary<string, string>();
        foreach (var e in events.Where(e => Is(e) && !string.IsNullOrWhiteSpace(e.Location)).OrderBy(e => e.Start))
            if (Name(e.Title) is (var n, false) && n != "") p[n] = e.Location!;
        foreach (var (k, v) in extra ?? new Dictionary<string, string>()) p[Name(k).Name] = v;
        return p;
    }

    public static string? Destination(Ev t, IReadOnlyList<Ev> events, IReadOnlyDictionary<string, string> places)
    {
        if (!string.IsNullOrWhiteSpace(t.Location)) return t.Location;
        var (name, from) = Name(t.Title);
        if (!from && places.TryGetValue(name, out var p)) return p;
        if (from)   // back to wherever the trip out there started
        {
            var outbound = events.Where(e => Is(e) && e.Start.Date == t.Start.Date && e.End <= t.Start && Name(e.Title) == (name, false)).MaxBy(e => e.Start);
            if (outbound != null && Origin(outbound, events, places) is string o) return o;
        }
        return events.Where(e => !Is(e) && !e.AllDay && e.Start >= t.Start && e.Start <= t.End.AddHours(2) && !string.IsNullOrWhiteSpace(e.Location))
            .MinBy(e => e.Start)?.Location;
    }

    /// Where the trip starts, from the calendar alone: "from X", else the last place you were that day, else home.
    public static string? Origin(Ev t, IReadOnlyList<Ev> events, IReadOnlyDictionary<string, string> places)
    {
        var (name, from) = Name(t.Title);
        if (from && places.TryGetValue(name, out var p)) return p;
        foreach (var e in events.Where(e => e != t && !e.AllDay && e.Start.Date == t.Start.Date && e.End <= t.Start).OrderByDescending(e => e.End))
            if ((Is(e) ? Destination(e, events, places) : e.Location) is { } place && !string.IsNullOrWhiteSpace(place)) return place;
        return places.GetValueOrDefault("home");
    }

    static readonly Regex Postcode = new(@"\b([A-Z]{1,2}\d[A-Z\d]?)\s*(\d[A-Z]{2})\b", RegexOptions.IgnoreCase);
    static readonly Regex Coords = new(@"^\s*(-?\d{1,3}\.\d+)\s*,\s*(-?\d{1,3}\.\d+)\s*$");

    /// What TfL can plan from: a postcode without its space, or "lat,lon". Null for anything else (free text gets poor guesses).
    public static string? Code(string place)
    {
        if (Coords.Match(place) is { Success: true } c) return $"{c.Groups[1]},{c.Groups[2]}";
        return Postcode.Matches(place).LastOrDefault() is { } m ? (m.Groups[1].Value + m.Groups[2].Value).ToUpperInvariant() : null;
    }

    /// The one to catch: the last that still arrives by [by].
    public static Journey? Catch(IEnumerable<Journey> options, DateTime by) => options.Where(j => j.Arrive <= by).MaxBy(j => j.Depart);

    public static List<Journey> Merge(IEnumerable<Journey> a, IEnumerable<Journey> b) => a.Concat(b).DistinctBy(j => (j.Depart, j.Via)).OrderBy(j => j.Depart).ToList();

    /// TfL's journeys, plus the first leg's start and the last leg's end as "lat,lon" for the Citymapper link.
    public static (List<Journey> Journeys, string? Start, string? End) Parse(JsonNode j)
    {
        static DateTime T(JsonNode? n) => DateTime.Parse((string)n!, CultureInfo.InvariantCulture);
        static string? Point(JsonNode? p) => p?["lat"] is { } lat && p["lon"] is { } lon
            ? $"{((double)lat).ToString(CultureInfo.InvariantCulture)},{((double)lon).ToString(CultureInfo.InvariantCulture)}" : null;
        static string Line(JsonNode l)
        {
            var mode = (string?)l["mode"]?["id"];
            var name = (string?)l["routeOptions"]?[0]?["name"];
            if (string.IsNullOrEmpty(name)) return (string?)l["mode"]?["name"] ?? "?";
            return mode switch { "bus" => $"{name} bus", "tube" => $"{name} line", _ => name };
        }
        var list = new List<Journey>();
        string? start = null, end = null;
        foreach (var x in j["journeys"]?.AsArray() ?? new JsonArray())
        {
            var legs = x!["legs"]!.AsArray();
            var via = string.Join(" → ", legs.Where(l => (string?)l!["mode"]?["id"] != "walking").Select(l => Line(l!)));
            list.Add(new(T(x["startDateTime"]), T(x["arrivalDateTime"]), via == "" ? "Walk" : via));
            start ??= Point(legs[0]!["departurePoint"]);
            end ??= Point(legs[^1]!["arrivalPoint"]);
        }
        return (list, start, end);
    }

    static readonly HttpClient Http = new() { Timeout = TimeSpan.FromSeconds(15), DefaultRequestHeaders = { { "User-Agent", "Glance" } } };

    /// Journeys arriving by (or leaving after) [at]. Empty when TfL finds no journey between the two.
    public static async Task<(List<Journey> Journeys, string? Start, string? End)> Fetch(string from, string to, DateTime at, bool arriving)
    {
        var url = $"https://api.tfl.gov.uk/Journey/JourneyResults/{Uri.EscapeDataString(from)}/to/{Uri.EscapeDataString(to)}" +
                  $"?date={at:yyyyMMdd}&time={at:HHmm}&timeIs={(arriving ? "Arriving" : "Departing")}";
        using var r = await Http.GetAsync(url);
        if (r.StatusCode is HttpStatusCode.MultipleChoices or HttpStatusCode.NotFound) return (new(), null, null);   // couldn't place an end, or no route
        r.EnsureSuccessStatusCode();
        return Parse(JsonNode.Parse(await r.Content.ReadAsStringAsync())!);
    }

    static string E(string s) => Uri.EscapeDataString(s);

    public static string Citymapper(string? start, string end, string endAddress, DateTime by) =>
        "https://citymapper.com/directions?" + (start != null ? $"startcoord={start}&" : "") +
        $"endcoord={end}&endaddress={E(endAddress)}&arrival_time={E(by.ToString("yyyy-MM-ddTHH:mm:sszzz", CultureInfo.InvariantCulture))}";

    public static string GoogleMaps(string from, string to) =>
        $"https://www.google.com/maps/dir/?api=1&origin={E(from)}&destination={E(to)}&travelmode=transit";
}
