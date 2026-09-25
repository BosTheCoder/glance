# Glance

A small floating Google Calendar widget for Windows 11: what's on **now**, what's **next**, and the rest of the week when you hover.

- Dark acrylic glass, rounded, draggable anywhere, remembers its position
- Fades to see-through when idle, goes solid and expands to a scrollable agenda on hover; scroll resets when you leave
- Pin on top (pin icon, or right-click menu)
- Right-click → **Calendars** to pick which calendars it shows (defaults to the ones ticked in Google Calendar)
- Right-click → **Opacity when idle** (25/50/75/100%)
- Grows upward instead of off the bottom of the screen
- Refreshes every 5 minutes, and on hover if the data is more than a minute old

## Portable

One 200 KB `Glance.exe` (needs the .NET 8 Desktop Runtime, already installed on this PC). Everything else lives next to it:

| File | What |
| --- | --- |
| `client.json` | Google OAuth **desktop** client (the JSON Cloud Console downloads, or `{"client_id","client_secret"}`) |
| `token.dat` | Refresh token, DPAPI-encrypted to your Windows user |
| `settings.json` | Position, pin, idle opacity, chosen calendars |

Read-only scope (`calendar.readonly`). Sign in happens in your browser via a loopback redirect, no app publishing needed: the consent screen belongs to your own Cloud project.

## Build

Builds from WSL with the .NET 8 SDK (`EnableWindowsTargeting`):

```
just deploy   # publish, copy to C:\Users\Bosire\Apps\Glance, relaunch
```
