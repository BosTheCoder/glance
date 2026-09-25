namespace Glance;

/// Fake events around the current time, for `Glance.exe --demo`: screenshots and UI work without a Google account.
static class Demo
{
    public static List<Ev> Events()
    {
        var now = DateTime.Now;
        var h = new DateTime(now.Year, now.Month, now.Day, now.Hour, 0, 0);
        Ev E(string title, double startH, double lenH, string color) => new(title, h.AddHours(startH), h.AddHours(startH + lenH), false, color);
        return new()
        {
            new("Mum's birthday", now.Date, now.Date.AddDays(1), true, "#F6BF26"),
            new("☀ 12° / 19°", now.Date, now.Date.AddDays(1), true, "#4FC3F7"),
            new("Lisbon trip", now.Date.AddDays(-1), now.Date.AddDays(4), true, "#F4511E"),
            new("Bin day", now.Date.AddDays(2), now.Date.AddDays(3), true, "#9E9E9E"),
            E("Deep work: API design", -0.5, 1.5, "#7986CB"),
            E("Lunch", 1, 1, "#33B679"),
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
