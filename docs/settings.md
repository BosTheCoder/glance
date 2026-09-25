# Settings

Everything in the right-click menu is saved to `settings.json` next to `Glance.exe`. The file is watched, so hand edits apply straight away without a restart. **Theme → Custom colours…** opens it in your default editor.

| Key | Default | Meaning |
| --- | --- | --- |
| `Theme` | `"Graphite"` | `Graphite`, `Midnight`, `Ocean`, `Forest`, `Plum`, `Ember` or `Light` |
| `Tint` | `null` | Custom background colour, overriding the theme's. Any WPF colour: `"#1E1B4B"`, `"#801E1B4B"` (with alpha), `"Teal"` |
| `Text` | `null` | Custom text colour, overriding the theme's |
| `Glass` | `"Clear"` | How much tint sits on the acrylic: `Clear`, `Frosted` or `Solid` |
| `IdleOpacity` | `0.5` | Whole-window opacity when you're not hovering (`0.1`–`1`) |
| `Docked` | `null` | `"Left"`, `"Right"` or `"Top"` while it's shrunk to a strip on that screen edge. Throw it sideways or upwards, or push it mostly off an edge, to dock. At the top, the events sit side by side. Click the strip to bring it back |
| `DockCount` | `2` | Events shown in the side strip (1–5): what's on now with time left and a bar, then what's next |
| `DockOpacity` | `0.75` | Strip opacity when you're not hovering it |
| `Width` | `300` | Widget width. Also set by dragging the corner |
| `ListHeight` | `320` | Maximum height of the expanded agenda. Also set by dragging the corner while it's expanded |
| `Scale` | `1.0` | Text size multiplier |
| `Pinned` | `true` | Always on top |
| `ShowInTaskbar` | `true` | `false` also hides it from Alt+Tab |
| `Hotkey` | `"Win+Shift+G"` | Global hide/show shortcut. Modifiers are `Ctrl`, `Alt`, `Shift` and `Win`, plus any key name (`G`, `Space`, `F9`, `1`). `""` means none. If another app already owns it, the widget says so and you can pick another one |
| `Calendars` | `null` | Calendar IDs to show. `null` means the ones ticked in Google Calendar |
| `AllDay` | `"Always"` | Today's all-day events: `Always` (chips under the clock), `Hover` (chips only while expanded) or `List` (rows at the top of the agenda) |
| `View` | `"Compact"` | `Compact` opens the agenda on hover. `Full` keeps it open all the time, which is handy on a spare screen. Idle fading still applies |
| `NextCount` | `1` | Upcoming events shown under "Next" (1–7) |
| `MaxEvents` | `10` | Events in the expanded list. `0` means all |
| `DaysAhead` | `7` | How far ahead to look. `0` means today only |
| `Clock24` | `true` | 24-hour times |
| `RefreshSeconds` | `60` | How often to check Google for changes. Google can't push updates to a desktop app, so Glance polls |
| `HeadsUpMinutes` | `5` | Amber heads-up this many minutes before the current event ends or the next starts: a "Next: …" (or "Ending: …") banner, one pulse and a chime. `0` turns it off |
| `Reminders` | `true` | Show events' own Google popup reminders as blue banners |
| `Sound` | `"Reminders"` | `Off`, `Reminders` (heads-ups and reminders) or `All` (starts too). Uses the Windows notification sound |
| `AlertsReveal` | `true` | Bring the widget back for an alert if you've hidden it |
| `Left`, `Top` | `null` | Position. `null` means top-right of the primary screen |

"Start with Windows" isn't in this file. It's the `Glance` value under `HKCU\Software\Microsoft\Windows\CurrentVersion\Run`.

## Example: a custom indigo glass

```json
{
  "Theme": "Midnight",
  "Tint": "#1E1B4B",
  "Text": "#E0E7FF",
  "Glass": "Frosted",
  "IdleOpacity": 0.35
}
```
