# Settings

Everything in the right-click menu is saved to `settings.json` next to `Glance.exe`. The file is watched, so hand edits apply straight away without a restart. **Theme → Custom colours…** opens it in your default editor.

| Key | Default | Meaning |
| --- | --- | --- |
| `Theme` | `"Graphite"` | `Graphite`, `Midnight`, `Ocean`, `Forest`, `Plum`, `Ember` or `Light` |
| `Tint` | `null` | Custom background colour, overriding the theme's. Any WPF colour: `"#1E1B4B"`, `"#801E1B4B"` (with alpha), `"Teal"` |
| `Text` | `null` | Custom text colour, overriding the theme's |
| `Glass` | `"Clear"` | How much tint sits on the acrylic: `Clear`, `Frosted` or `Solid` |
| `IdleOpacity` | `0.5` | Whole-window opacity when you're not hovering (`0.1`–`1`) |
| `Width` | `300` | Widget width. Also set by dragging the corner |
| `ListHeight` | `320` | Maximum height of the expanded agenda. Also set by dragging the corner while it's expanded |
| `Scale` | `1.0` | Text size multiplier |
| `Pinned` | `true` | Always on top |
| `ShowInTaskbar` | `true` | `false` also hides it from Alt+Tab |
| `Hotkey` | `"Win+Shift+G"` | Global hide/show shortcut. Modifiers are `Ctrl`, `Alt`, `Shift` and `Win`, plus any key name (`G`, `Space`, `F9`, `1`). `""` means none. If another app already owns it, the widget says so and you can pick another one |
| `Calendars` | `null` | Calendar IDs to show. `null` means the ones ticked in Google Calendar |
| `AllDay` | `"Always"` | Today's all-day events: `Always` (chips under the clock), `Hover` (chips only while expanded) or `List` (rows at the top of the agenda) |
| `NextCount` | `1` | Upcoming events shown when collapsed |
| `MaxEvents` | `10` | Events in the expanded list. `0` means all |
| `DaysAhead` | `7` | How far ahead to look. `0` means today only |
| `Clock24` | `true` | 24-hour times |
| `RefreshSeconds` | `60` | How often to check Google for changes. Google can't push updates to a desktop app, so Glance polls |
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
