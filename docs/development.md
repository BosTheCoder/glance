# Development

Glance is a small WPF app on .NET 8, with no NuGet dependencies. It talks to Google over plain `HttpClient`, and it uses a handful of Win32/DWM calls for the glass.

## Build and run

**Windows** (.NET 8 SDK):

```powershell
dotnet run -- --demo                # fake events, no Google needed
dotnet run                          # real calendar (needs client.json in bin\Debug\net8.0-windows\win-x64\)
dotnet publish -c Release -o out    # out\Glance.exe, framework-dependent single file
```

**Linux / WSL.** WPF can't run there, but it builds, because `EnableWindowsTargeting` is set:

```bash
dotnet publish -c Release -o out    # then run out/Glance.exe from Windows
just deploy                         # WSL: publish, copy to %USERPROFILE%\Apps\Glance (or $GLANCE_DIR), relaunch
just demo                           # WSL: same, but starts a --demo copy from %TEMP%
```

`--demo` uses its own single-instance lock, so a demo copy runs alongside your real one.

## Layout

| File | What's in it |
| --- | --- |
| `App.xaml.cs` | Startup: the single-instance mutex, the `--demo` flag, and logging crashes to `glance.log` |
| `MainWindow.xaml` | The whole UI tree: header, now card, next, agenda, and the resize grip |
| `MainWindow.xaml.cs` | Behaviour: refresh loop, rendering, hover expand/collapse, fade, resize, live settings reload |
| `MainWindow.Menu.cs` | The right-click menu, rebuilt on every open from `Settings` |
| `Settings.cs` | `settings.json` model, themes and colour resolution |
| `GoogleCal.cs` | OAuth for installed apps (loopback + PKCE), token refresh and storage, calendar and event fetch |
| `Native.cs` | P/Invoke for the acrylic backdrop, rounded corners, whole-window alpha, tool-window style and per-monitor work area |
| `Demo.cs` | Fake events relative to now, for `--demo` |
| `assets/` | Icon: `source.png` (AI-generated), `glance.ico`/`glance.png`, and `icon.py`, which rebuilds them |
| `tools/` | `screenshot.ps1` captures the window, and `compose.py` builds the README images from those captures |

## How it works

- **Data.** Glance polls every `RefreshSeconds`, and also on hover if the data is more than 20 s old. Each refresh fetches `calendarList` plus one `events.list` per chosen calendar, with `singleEvents=true` so Google expands recurring events. It skips declined events and working-location entries.
- **Glass.** `WindowChrome` with `GlassFrameThickness=-1` extends the DWM frame. `DWMWA_SYSTEMBACKDROP_TYPE=3` makes it acrylic, and `DWMWA_USE_IMMERSIVE_DARK_MODE` follows how light the tint is. The theme tint is a semi-transparent brush over that.
- **Fade.** The whole window fades via `WS_EX_LAYERED` and `SetLayeredWindowAttributes`. WPF silently strips `WS_EX_LAYERED` from windows that aren't `AllowsTransparency`, so `Native.Init` hooks `WM_STYLECHANGING` to keep it. Without that hook, tools like AutoHotkey's `WinSetTransparent` don't work on WPF windows either.
- **Hover growth.** If expanding would run off the bottom of the monitor, the window moves up and moves back when it collapses.

## Refreshing the docs images

```powershell
# with a --demo copy running and positioned:
powershell -File tools\screenshot.ps1 %TEMP%\shot-collapsed.png
# …hover it, then capture shot-expanded, shot-midnight, shot-ocean, shot-plum, shot-light the same way
uv run tools/compose.py %TEMP%
```

## Releasing

1. Bump `<Version>` in `Glance.csproj`.
2. Tag and push: `git tag v1.2.0 && git push --tags`.
3. The [build workflow](../.github/workflows/build.yml) builds `Glance.exe` (framework-dependent) and `Glance-standalone.exe` (self-contained) on `windows-latest` and attaches both to a GitHub release. Every push and PR also builds, and uploads the exes as a workflow artifact.

## Requirements and limits

- Windows 11 22H2 or later for acrylic. On older builds the widget still works, but the backdrop falls back to plain tint.
- Glance polls, because Google's push notifications need a public HTTPS webhook, which a desktop app doesn't have.
