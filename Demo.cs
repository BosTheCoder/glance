namespace Glance;

/// Fake events around the current time, for `Glance.exe --demo`: screenshots and UI work without a Google account.
/// Laid out so every state shows at launch: an event that just started (green banner), a reminder that just
/// fired (blue banner) and a change inside the heads-up window (amber).
static class Demo
{
    public static List<Ev> Events()
    {
        var now = DateTime.Now;
        var h = new DateTime(now.Year, now.Month, now.Day, now.Hour, 0, 0);
        var m = new DateTime(now.Year, now.Month, now.Day, now.Hour, now.Minute, 0);
        const string link = "https://calendar.google.com/calendar/r";   // clicking a demo event opens Google Calendar itself
        Ev E(string title, double startH, double lenH, string color, string? place = null) => new(title, h.AddHours(startH), h.AddHours(startH + lenH), false, color, null, link, place);
        Ev M(string title, double startMin, double lenMin, string color, params int[] reminders) =>
            new(title, m.AddMinutes(startMin), m.AddMinutes(startMin + lenMin), false, color, reminders, link);
        return new()
        {
            new("Mum's birthday", now.Date, now.Date.AddDays(1), true, "#F6BF26"),
            new("☀ 12° / 19°", now.Date, now.Date.AddDays(1), true, "#4FC3F7"),
            new("Lisbon trip", now.Date.AddDays(-1), now.Date.AddDays(4), true, "#F4511E"),
            new("Bin day", now.Date.AddDays(2), now.Date.AddDays(3), true, "#9E9E9E"),
            M("Design sync", 0, 30, "#7986CB"),
            M("Coffee with Ana", 4, 30, "#33B679", 5),
            // Travel times: the office trip has no start of its own, so it sets off from "home", learned from the trip home.
            E("Travel: to Office", 1.25, 0.75, "#9E69AF", "The British Library, 96 Euston Rd, London NW1 2DB"),
            E("Travel: Home", 6, 0.75, "#9E69AF", "Canary Wharf, London E14 5AB"),
            E("Lunch", 1, 1, "#F6BF26"),
            E("1:1 with Sam", 2, 0.5, "#E67C73"),
            E("Gym", 3.5, 1.25, "#8E24AA"),
            E("Read", 5, 1, "#039BE5"),
            E("Standup", 24 + 9 - now.Hour, 0.25, "#7986CB"),
            E("Design review", 24 + 11 - now.Hour, 1, "#E67C73"),
            E("Dentist", 48 + 15 - now.Hour, 1, "#33B679"),
            E("Football", 72 + 18 - now.Hour, 2, "#F4511E"),
        };
    }
}
