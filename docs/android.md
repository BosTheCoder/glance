# Glance for Android

A floating pill that sits on the edge of your screen over any app. It shows what's on **now** (with the time left) or when you're free until, and it stays where you put it until you throw it to the side, where it turns into a narrow strip that still shows what you're meant to be doing. Tap it to see the rest of the week, or set **View** to **Full** to keep that card open all the time.

It reads the calendars your phone already syncs from your Google account, so there's no Google sign-in and no OAuth client to set up.

## Install

1. Download **`Glance.apk`** from the [latest release](https://github.com/BosTheCoder/glance/releases/latest) on your phone.
2. Open it. Android asks you to allow installs from your browser or file manager the first time. Allow it, then install.
3. Open Glance and tap each **Grant** button:
   - **Calendar access**, so it can read your events.
   - **Display over other apps**. This opens a system screen: find Glance and switch it on, then go back.
   - **Notifications** (Android 13+). The only notification is the quiet "Glance is floating" one that keeps it running.
4. Tick the calendars you want. By default it picks the ones that are visible in your calendar app.
5. Tap **Start floating widget**.

If a calendar is missing, check that it's syncing: in Google Calendar, open Settings, tap the account and make sure the calendar is ticked for sync.

## Using it

In the default Compact view:

| Do this | To |
| --- | --- |
| Tap the pill | Expand it: clock, all-day chips (they wrap onto as many lines as they need, so you never scroll sideways), the current event, what's next and a scrollable agenda |
| Tap the clock, or anywhere outside | Collapse it again |
| Drag the grip in the card's bottom corner (the one away from the screen edge) | Resize the card: inwards makes it wider, down gives the agenda more height. It remembers the size |
| Pinch the pill | Resize it: spread your fingers sideways to set the pill's width (every line fills it, and long titles are cut short inside it), up and down to show more or fewer Up next rows (1 to 7). It remembers both; **Reset size** puts it back to fitting its text |
| Tap an event (an Up next row in the pill, or the current event or an agenda row in the card) | Open that event in your calendar app. Tapping the pill anywhere else still expands it |
| Drag the pill | Move it. It snaps to the nearest left or right edge and remembers where it was |
| Throw it towards an edge, or drag it mostly off one | Dock it there as the side strip (below) |
| Leave it for 3 seconds | It fades to the idle opacity. It never moves on its own |
| Tap the side strip | Bring the pill back out, on the same side and at the same height |
| Long-press the pill or strip | Open the settings screen |
| Tap an alert banner | Dismiss it |

Settings (same names as the Windows app):

| Setting | Options |
| --- | --- |
| **View** | **Compact** (default): the pill above, which expands on tap. **Full**: the expanded card, all the time |
| **Up next** | 1 to 7 (default 1): how many upcoming events the pill lists under what's on now (pinching the pill up and down changes it too). The expanded card lists the same number under NEXT. Every upcoming timed event, here and in the agenda, shows how long it lasts ("30m", "1h 15m") at the end of its row |
| **Items at the side** | 1 to 5 (default 2): rows in the side strip |
| **Opacity at the side** | 25/50/75/100% (default 75%): the strip's opacity while you're not touching it |
| Idle opacity | 25/50/75/100%: the pill's or card's opacity after 3 seconds untouched |
| Heads-up before a change | 2/5/10 minutes |
| Vibrate on reminders and heads-ups | On/off |
| Start when the phone boots | On/off |
| **Check for updates** (button, under Updates) | See [Updates](#updates) |
| **Reset size** (button) | Puts the card back to its default size (85% of the screen width, or up to 360dp in Full view; agenda up to 60% of the screen height) and the pill back to sizing itself to its text (up to 60% of the screen). Up next isn't changed |

### Full view

The card stays open: clock, all-day chips, what's on now, up next and the agenda. Resize it with the grip in its bottom corner, as in Compact. Drag it by the clock row to move it; it snaps to the nearer left or right edge like the pill does. After 3 seconds without a touch it fades to the idle opacity, but it doesn't collapse, and tapping outside it does nothing. Throw it at an edge to dock it, as with the pill. Touch it anywhere to bring it back to full opacity. Alerts work the same way: the banner shows at the top of the card and the card's outline changes colour. Long-press the clock row for settings.

### Side strip

When you want it out of the way, throw it towards the left or right edge (a quick flick), or drag it until most of it is past an edge, and let go. It glides to that side, carrying the throw's up/down motion too, and turns into a strip about 112dp wide, flush with the edge. It works the same from Compact and Full, and it stays docked after a restart.

The strip shows **Items at the side** rows:

- If something is on now, the first row is its title (bold, cut short if it's long), the time left ("23m left") and a thin bar in the event's colour.
- The other rows are the next timed events: title, then when it starts and how long it lasts ("in 4m · 30m", or "Fri 09:00 · 30m" if it's more than 12 hours away). All-day events don't appear.

It sits at **Opacity at the side** while you're not touching it. An alert brings it to full opacity and colours its outline (amber, green or blue) without undocking it; the row the alert is about says "▶ Now" or "🔔 in 10m", or turns amber for a heads-up. Drag the strip up or down to move it along the edge, pull it inwards to undock it and carry on dragging, or tap it to bring back the pill (or card in Full view). The strip's text sits clear of the back-gesture zone, so touches on it reach Glance rather than the system.

## Alerts

The alerts show inside the pill, card or side strip, not as system notifications, and each kind has its own colour:

| When | What you see |
| --- | --- |
| The last few minutes before the current event ends or the next one starts (the heads-up setting, 5 min by default) | An **amber** clock banner: "Next: *title* · in 4m" if something starts, or "Ending: *title* · in 4m" if the current event just ends (a start wins when both happen at once). It pulses once and vibrates once, and the outline and countdown turn amber. It stays until the change happens or you tap it away. If a blue reminder for the same event is already showing, that stays and you just get the pulse and vibration |
| An event starts | A **green** "▶ Now: *title*" banner that pulses twice and stays about 20 seconds |
| One of the event's own reminders is due | A **blue** bell banner, "*title* · in 10m", with one short vibration. It stays until you tap it or the event starts |

Each alert fires once. After a restart it only fires alerts that were due in the last 2 minutes, so it doesn't replay the morning's.

## How it gets your calendar

Glance reads Android's calendar provider (`CalendarContract`), the same local database your calendar app uses. Your Google account syncs into it, so edits in Google show up as soon as the phone syncs. Glance listens for changes, so it updates within a second of that and re-reads every 30 seconds for the countdowns.

- `Instances` from today 00:00 to 7 days ahead, with recurring events already expanded.
- Events you've declined are skipped. All-day events show as chips and don't count as "now".
- Reminders come from each event's own pop-up reminders (`Reminders` with method alert or default).
- The queries run on a background thread and the result is handed to the main thread, so a slow provider can't freeze the pill.

Your calendar never leaves the phone. The only network use is the update check below, which asks GitHub for the latest release.

## Updates

Open the settings screen and it quietly checks GitHub for a newer release. If there is one, the button under **Updates** reads **Update to v1.8.0**; otherwise it reads **Check for updates** and tapping it tells you whether you're on the latest. There's no checking in the background.

Tapping **Update to …** downloads `Glance.apk` from that release, checks it against the SHA-256 digest GitHub publishes for it (and stops if they differ), and hands it to Android's installer, which asks you to confirm. The first time, Android sends you to **Install unknown apps**: switch on **Allow from this source** for Glance and go back, and the update carries on. The widget stops while it updates; open Glance and tap Start afterwards.

This uses the `INTERNET` and `REQUEST_INSTALL_PACKAGES` permissions and Android's `PackageInstaller` session API (the install intent it replaced is deprecated). A release only installs over the current app if its `versionCode` is higher and it's signed with the same key.

## Build

Needs JDK 17 and the Android SDK. On Linux or WSL:

```bash
brew install openjdk@17
# Android command-line tools unzipped to ~/Android/Sdk/cmdline-tools/latest, then:
~/Android/Sdk/cmdline-tools/latest/bin/sdkmanager --sdk_root=$HOME/Android/Sdk \
  "platform-tools" "platforms;android-35" "build-tools;35.0.0"

just android    # unit tests + signed release APK -> android/dist/Glance.apk
```

Or by hand:

```bash
cd android
export JAVA_HOME=$(brew --prefix openjdk@17)/libexec ANDROID_HOME=$HOME/Android/Sdk
./gradlew :app:testDebugUnitTest :app:assembleRelease
```

The release build is shrunk with R8 (`isMinifyEnabled` and `isShrinkResources`), which keeps the APK small now that it uses AndroidX (`core-ktx`, `activity-ktx`, `dynamicanimation`). Those are the newest releases that still build against `compileSdk 35`.

**Signing.** `app/build.gradle.kts` reads `~/.config/glance/keystore.properties` (`storeFile`, `storePassword`, `keyAlias`, `keyPassword`). If that file isn't there, the release APK is signed with the debug key, which is fine for trying it out. Keep the release keystore: Android only accepts an update signed with the same key as the installed app.

## Layout

| File (under `android/app/src/`) | What's in it |
| --- | --- |
| `main/.../Model.kt` | Pure Kotlin, no Android: the `Ev` model, now/next, the side strip's rows, heads-up and its banner wording (`Plan`), where a release docks (`dockSide`), which alerts are due (`AlertTracker`), `isNewer()` for release tags, `dur()` |
| `main/.../Calendar.kt` | `CalendarContract` queries (calendars, instances, reminders) and `Prefs` |
| `main/.../OverlayService.kt` | The foreground service and the overlay window: pill, expanded card, side strip, drag/snap/throw-to-dock, alerts |
| `main/.../MainActivity.kt` | Setup screen (edge to edge): permissions, calendar picker, settings, Start/Stop |
| `main/.../Update.kt` | Check for updates: the GitHub releases API, the SHA-256 check and the `PackageInstaller` session |
| `main/.../BootReceiver.kt` | Restarts the service after a reboot if "start on boot" is on |
| `main/res/` | Bell and notification vectors, adaptive launcher icon (foreground cut from `assets/source.png`, background `#0B1030`) |
| `test/.../AlertsTest.kt` | JVM tests for now/next, Up next, the side strip's rows, the dock decision, the heads-up window and banner, alert de-duplication and the release-tag compare |

## Notes

- **Battery.** It runs as a foreground service so Android doesn't kill it. It wakes every 30 seconds, and at the exact moment something changes, and reads a local database. There's no network use.
- **Why an overlay and not a bubble.** Android's Bubbles API only floats *conversation* notifications (a `MessagingStyle` notification tied to a sharing shortcut), so it can't host a calendar. A `TYPE_APPLICATION_OVERLAY` window with the "Display over other apps" permission is still the supported way to keep your own view on screen over other apps. The window never takes keyboard focus and passes touches outside itself straight to the app underneath, so Android 12's block on untrusted touches doesn't affect it. On Android 11+ it's built from a window context, as the platform docs ask for windows added from a service.
- **Gesture navigation.** The pill stays between the status bar and the navigation bar. The side strip is widened by the back-gesture zone on its edge (read from the system's gesture insets on Android 11+) and its text starts past it, so dragging or tapping the text moves or opens the strip instead of going back.
- **Opening events** uses the Calendar Provider's documented view intent (`ACTION_VIEW` on the event's `Events` URI, with the occurrence's begin and end times so a repeating event opens on the right day). Starting an activity from the floating window is allowed because Glance holds "Display over other apps", one of Android's background-activity-start exemptions. If no calendar app can open it, you get a short message instead.
- **Throwing.** Glance measures the release speed with `VelocityTracker`. Anything faster than 800 dp/s counts as a throw. The glide is a low-stiffness, no-bounce `SpringAnimation` sideways and a `FlingAnimation` with friction up and down, both from AndroidX `dynamicanimation`.
- **Android 14+** requires a declared type for every foreground service. None of the specific types fit a floating widget, so Glance uses `specialUse` with a short explanation in the manifest. Starting it from `BOOT_COMPLETED` is still allowed on Android 15 (the new boot restriction covers data sync, camera, media, phone call and microphone services, not `specialUse`). If Android refuses to start it anyway, Glance logs it and stops quietly instead of crashing.
- **Reminder vibration** uses the notification vibration usage, which Android requires for vibrating from the background, so it follows your phone's notification vibration setting.
- **Some phones kill background apps anyway** (Samsung, Xiaomi, OnePlus, Huawei and others). If the pill vanishes after a while, set Glance's battery use to "Unrestricted" or add it to the battery exceptions. [dontkillmyapp.com](https://dontkillmyapp.com) has the steps for each brand.
- After you install an update (from the settings screen or by hand), open Glance and tap Start again.
