namespace Glance;

public record Cal(string Id, string Name, string Color, bool DefaultOn, int[] DefaultReminders);

/// One event occurrence. Reminders are minutes before Start (Google "popup" reminders).
public record Ev(string Title, DateTime Start, DateTime End, bool AllDay, string Color, int[]? Reminders = null, string? Link = null, string? Location = null,
    string? CalId = null, string? Id = null);   // Link: the event in Google Calendar. Id: this occurrence's, so a change touches only it
