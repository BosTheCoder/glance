# Glance for Android

A floating pill that floats over any app, wherever you put it. It shows what's on **now** (with the time left) or when you're free until, and it stays where you put it until you throw it to the side, where it turns into a narrow strip that still shows what you're meant to be doing. Tap it to see the rest of the week, or set **View** to **Full** to keep that card open all the time.

It reads the calendars your phone already syncs from your Google account, so there's no Google sign-in and no OAuth client to set up.

## Install

1. Download **`Glance.apk`** from the [latest release](https://github.com/BosTheCoder/glance/releases/latest) on your phone.
2. Open it. Android asks you to allow installs from your browser or file manager the first time. Allow it, then install.
3. Open Glance and tap each **Grant** button:
   - **Calendar access**, so it can read your events.
   - **Display over other apps**. This opens a system screen: find Glance and switch it on, then go back.
   - **Notifications** (Android 13+). You get the quiet "Glance is floating" one that keeps it running, and the pop-ups when events start.
   - **Full screen when events start** (Android 14+, only if Android hasn't already allowed it). This opens the "Full screen notifications" page: switch Glance on and go back.
   - **Change events (skip)**, optional. Needed for Skip on the start screen.
   - **Location (for travel times)**, optional. Travel times then start from where the phone is (see [Travel times](#travel-times)).
4. Tick the calendars you want. By default it picks the ones that are visible in your calendar app.
5. Tap **Start floating widget**.

If a calendar is missing, check that it's syncing: in Google Calendar, open Settings, tap the account and make sure the calendar is ticked for sync.

## Using it

In the default Compact view:

| Do this | To |
| --- | --- |
| Tap the pill | Expand it: clock, all-day chips (they wrap onto as many lines as they need, so you never scroll sideways), the current event (with when it started and how long it has been going), what's next and a scrollable agenda |
| Tap the clock, or anywhere outside | Collapse it again |
| Drag the open card by its clock row (or its edges) | Move it. The list still scrolls. When it collapses, the pill lands where you left the card |
| Drag the grip in the card's bottom corner (the one away from the screen edge) | Resize the card: inwards makes it wider, down gives the agenda more height. It remembers the size |
| Pinch the pill | Resize it: spread your fingers sideways to set the pill's width (every line fills it, and long titles are cut short inside it), up and down to show more or fewer Up next rows (1 to 7). It remembers both; **Reset size** puts it back to fitting its text |
| Tap an event in the card (the current event or an agenda row) | Open that event in your calendar app. Only the open card does this, so a tap on the pill can't open one by accident: tapping the pill expands it |
| Tap **Earlier N** at the top of the open card | Show today's finished events, in case you missed one. It closes again when you collapse the card |
| Drag the pill | Move it. It stays exactly where you let go (kept on screen) and remembers the spot |
| Throw it towards an edge, or drag it mostly off one | Dock it there as the side strip (below). Only a real flick or a push mostly off the edge docks it; a slow drag never does |
| Leave it for a few seconds (**Fade after**) | It fades to the idle opacity. It never moves on its own |
| Tap the side strip | Bring the pill back out, on the same side and at the same height |
| Long-press anywhere on the pill, card or strip | Open the settings screen, with a short buzz so you know it took. Anywhere means anywhere: an event row or the list works too (the tap underneath is cancelled). Not on a travel time, which has its own long-press |
| Long-press a travel time | Show the ride you can't run for: "DLR 16:40 from Bank DLR Station, towards Woolwich Arsenal · arrive 17:16 · 36m" |
| Tap an alert banner | Dismiss it |

Settings (same names as the Windows app):

| Setting | Options |
| --- | --- |
| **View** | **Compact** (default): the pill above, which expands on tap. **Full**: the expanded card, all the time |
| **Up next** | 1 to 7 (default 1): how many upcoming events the pill lists under what's on now (if several events overlap, the pill shows each of them with its time left and progress bar) (pinching the pill up and down changes it too). The expanded card lists the same number under NEXT. Every upcoming timed event, here and in the agenda, shows how long it lasts ("30m", "1h 15m") at the end of its row |
| **Items at the side** | 1 to 5 (default 2): rows in the side strip |
| **Opacity at the side** | 25/50/75/100% (default 75%): the strip's opacity while you're not touching it |
| **Opacity when active** | 50/75/90/100% (default 100%, solid): the glass behind the pill, card and strip while you use it |
| Idle opacity | 25/50/75/100%: the pill's or card's opacity once it fades |
| **Fade after** | 3/5/10/30 s (default 5 s): how long untouched before it fades to the idle opacity |
| **Pause: hide and mute alerts** | 30 min, 1 h, 2 h, 4 h (at the top of settings): the widget hides and no alert buzzes, sounds, pops up or fills the screen until then; alerts due meanwhile are dropped, not saved up. The "Glance is paused until 14:30" notification has **Resume** to end it early, as does the button that replaces these choices. Pausing the phone doesn't pause Windows |
| Heads-up before a change | 2/5/10 minutes |
| Vibrate on alerts | On/off (default on): every start, reminder and heads-up buzzes, except in silent mode or Do Not Disturb |
| **Alert sound** (button) | The sound every alert plays when the ringer is on (default: the phone's notification sound), or None. Vibrate mode buzzes only |
| **Keep buzzing when an event starts** | Off (default), 30 s, 1/2/5 min, or Until stopped: buzzes on repeat like an alarm, through vibrate mode but not silent mode or Do Not Disturb. Stop it with the pop-up's **Stop**, by swiping the pop-up away, or by touching the widget |
| **Fill the screen for starts and reminders** | On (default): a full-screen page when an event starts (black and green) and when one of its reminders is due (calmer blue), over the lock screen too (see [Alerts](#alerts)) |
| **Action history** (button) | Every skip (and move, from before 1.22), newest first, with **Revert** (see [Snooze and Skip](#snooze-and-skip)) |
| **Pop-ups: Android's settings** (button) | Android's page for the pop-up notification ("Event alerts"): on/off and lock-screen display. Its sound and vibration are off on purpose; Glance plays its own, above |
| **Travel times** | On/off (default on): public transport times for "Travel" events, see [Travel times](#travel-times) |
| **Get-ready time** | 0/3/5/10 min (default 5): taken off each departure to give the leave time |
| Start when the phone boots | On/off |
| **Check for updates** (button, under Updates) | See [Updates](#updates) |
| **Reset size** (button) | Puts the card back to its default size (85% of the screen width, or up to 360dp in Full view; agenda up to 60% of the screen height) and the pill back to sizing itself to its text (up to 60% of the screen). Up next isn't changed |

### Full view

The card stays open: clock, all-day chips, what's on now, up next and the agenda. Resize it with the grip in its bottom corner, as in Compact. Drag it by the clock row to move it; it stays where you let go, like the pill. After **Fade after** without a touch it fades to the idle opacity, but it doesn't collapse, and tapping outside it does nothing. Throw it at an edge to dock it, as with the pill. Touch it anywhere to bring it back to full opacity. Alerts work the same way: the banner shows at the top of the card and the card's outline changes colour. Long-press anywhere on it for settings.

### Side strip

When you want it out of the way, throw it towards the left or right edge (a quick flick), or drag it until most of it is past an edge, and let go. It glides to that side, carrying the throw's up/down motion too, and turns into a strip about 112dp wide, flush with the edge. It works the same from Compact and Full, and it stays docked after a restart.

The strip shows **Items at the side** rows:

- Each event on now gets a row: its title (bold, cut short if it's long), the time left ("23m left") and a thin bar in the event's colour. If several overlap they all show, even if that's more rows than **Items at the side**.
- The other rows are the next timed events: title, then when it starts and how long it lasts ("in 4m · 30m", or "Fri 09:00 · 30m" if it's more than 12 hours away). All-day events don't appear.

It sits at **Opacity at the side** while you're not touching it. An alert brings it to full opacity and colours its outline (amber, green or blue) without undocking it; the row the alert is about says "▶ Now" or "🔔 in 10m", or turns amber for a heads-up. Drag the strip up or down to move it along the edge, pull it inwards to undock it and carry on dragging, or tap it to bring back the pill (or card in Full view). The strip's text sits clear of the back-gesture zone, so touches on it reach Glance rather than the system.

## Alerts

The alerts show inside the pill, card or side strip, and each kind has its own colour. Starts also get a pop-up notification (below):

| When | What you see |
| --- | --- |
| The last few minutes before the current event ends or the next one starts (the heads-up setting, 5 min by default) | An **amber** clock banner: "Next: *title* · in 4m" if something starts, or "Ending: *title* · in 4m" if the current event just ends (a start wins when both happen at once). It pulses once, and the outline and countdown turn amber. It stays until the change happens or you tap it away. If a blue reminder for the same event is already showing, that stays and you just get the pulse. A heads-up for a start also pops up |
| An event starts | A **green** "▶ Now: *title*" banner that pulses twice and stays about 20 seconds |
| One of the event's own reminders is due | A **blue** bell banner, "*title* · in 10m". It stays until you tap it or the event starts. With **Fill the screen** on, it also pops up and opens the blue full-screen page (below) |

**Sound and vibration.** Every alert (a start, a reminder, a heads-up) gives a long double buzz and plays the **Alert sound** when the ringer is on. Vibrate mode only buzzes; silent mode and Do Not Disturb get neither.

**Pop-up for starts.** A reminder, "*title* in 5m" at the heads-up and "Now: *title*" when it starts show as a notification that pops up over whatever is open. Each replaces the one before, and tapping it opens the event. Turn it off with **Pop-ups: Android's settings**.

**Full screen for starts.** When an event starts, a full-screen page shows "▶ NOW", the title, its times, **Open event**, **Snooze** (2m, 5m, 10m, 15m), **Skip event** and a big green **Start now**, which closes the page and stops "Keep buzzing" (see [Snooze and Skip](#snooze-and-skip)). Unanswered, the page closes itself when the event ends. It works like an alarm clock's: if the phone is locked or asleep, the screen turns on and the page shows over the lock screen; if you're using the phone, it opens over whatever is open. The floating widget hides while it's up. If several events start at once, their pages queue up ("NOW · 1 OF 3") and each one shows after you deal with the one before; Back closes the lot. Turn it off with **Fill the screen for starts and reminders**. On Android 14+ it needs "Full screen notifications" allowed for Glance; sideloaded apps usually have it already, and the setup screen shows a Grant button if not.

**Full screen for reminders.** When one of an event's reminders is due, the same page opens in a calmer blue, headed "🔔 IN 10M · 14:00", so it reads as a heads-up rather than a start. It has the same buttons, but the big one is **Dismiss**. If you leave it and the event starts, the start's page replaces it.

**Started late.** If the start page is still up 2 minutes or more after the event began, it says "▶ STARTED 12M AGO".

**Tap an event** in the open card to get its page: an upcoming one gets the blue page (no bell), the one on now gets the green start page. **Open event** on the page opens it in the calendar app. Earlier events still open straight in the calendar app.

### Snooze and Skip

- **Snooze** closes the page and brings the alert back (buzz, pop-up and page) that many minutes later. The calendar isn't touched: moving an event is yours to do in the calendar. Lengths that would come back after the event ends aren't offered. A snoozed alert doesn't come back if the event has ended, moved or been skipped by then, and a snoozed reminder doesn't come back once the event has started (its start alert has said so).
- **Start now** and **Dismiss** just close the page.
- **Skip event** is the one button that changes your calendar, and the change syncs to Google: it deletes the event.
- **Repeating events** only ever change the one occurrence: it's cancelled as an exception, the same as "this event" in Google Calendar. The rest of the series stays as it was. An occurrence of a repeating event created in the last minute or so is refused ("hasn't synced yet") until it has reached Google, because changing it before then can hide the series' other occurrences.
- Events where you're a guest rather than the organiser change on the phone, but Google may put them back on the next sync.

### On both devices

Answer an alert on Windows and its page and pop-up close on the phone, and the other way round. Every button on the page (Start now, Dismiss, Snooze, Skip, Open event) sends a short message over [ntfy.sh](https://ntfy.sh), a free push service, while each device listens whenever its page is up. Nothing needs pairing: both work out the same private topic from your Google account, and the message is a scrambled code made from the event's title and start time, never the title itself. A message only closes pages that were already up when it was sent, so answering a reminder doesn't close the start page that comes later. Changes to the calendar (a skip on the other device) also close the page once the phone's calendar syncs, even without ntfy.

**Action history** (in settings) lists every move, extend, skip and revert (and snoozes from before 1.18), including ones that failed and why. **Revert** undoes one: a moved event goes back to its old time, a cancelled occurrence comes back. A deleted one-off event is recreated from a copy taken before it was deleted (title, times, location, description, colour, reminders). It comes back as a new event, so guests and video-call links aren't restored. The history keeps the last 300 actions on the phone (`history.json` in Glance's app storage) and each action is also written to logcat under the `Glance` tag.

Each alert fires once. After a restart it only fires alerts that were due in the last 2 minutes, so it doesn't replay the morning's.

## Travel times

Any timed event whose title starts with "Travel" gets the next public transport options from TfL. The rules for where a trip starts and ends are the same as on Windows, in [docs/travel.md](travel.md).

- **In the pill and on agenda rows**, a travel event shows "🚆 leave 16:44" where other rows show their length. It turns amber once leaving is within the heads-up time, and says "go now" once it has passed.
- **In the open card**, a row of times sits under each travel event: "16:44 → 17:25". The one to catch is outlined, and ones that arrive after the event ends are dimmed. One whose leave time has passed but whose train hasn't gone shows the train instead, in amber: "🏃 DLR 16:49 → 17:25". **Hold a time** to see its first ride: line, time, stop and direction. Tap a time to open the trip in Citymapper (the app if it's installed), set to arrive by the end of the event. **Later** adds three more. Without TfL times (outside London, or an address with no postcode) there's a **Directions** chip for Google Maps instead.
- **Your location** is used for a trip that starts within 90 minutes, if you've granted it. It's one fix at a time from Android's own location service (fused on Android 12+, else network, else GPS), rounded to about 100 m, and only sent to TfL. Without it, or without a fix, the start comes from your calendar.

## How it gets your calendar

Glance reads Android's calendar provider (`CalendarContract`), the same local database your calendar app uses. Your Google account syncs into it, so edits in Google show up as soon as the phone syncs. Glance listens for changes, so it updates within a second of that and re-reads every 30 seconds for the countdowns.

- `Instances` from today 00:00 to 7 days ahead, with recurring events already expanded.
- Events you've declined are skipped. All-day events show as chips and don't count as "now".
- Reminders come from each event's own pop-up reminders (`Reminders` with method alert or default).
- The queries run on a background thread and the result is handed to the main thread, so a slow provider can't freeze the pill.

Your calendar never leaves the phone. The network is used for the update check below (GitHub), and for travel times: the two ends of each trip (postcodes, or your rounded location) and its time go to TfL's Journey Planner.

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
| `main/.../OverlayService.kt` | The foreground service and the overlay window: pill, expanded card, side strip, drag/throw-to-dock, alerts |
| `main/.../StartScreen.kt` | The full-screen page for starts, reminders (blue) and tapped upcoming events: `showWhenLocked` and `turnScreenOn`, opened by the start notification's full-screen intent when locked, or straight from the service when the phone is in use |
| `main/.../Actions.kt` | Move (start now, delay), Skip and Revert against the calendar provider (exceptions for repeating events), and the action history (`history.json`) |
| `main/.../HistoryActivity.kt` | The Action history screen with Revert |
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
- **Location for travel times** is foreground-only: there's no "Allow all the time" permission. Android counts a foreground service of type `location` as foreground use ([location permissions](https://developer.android.com/develop/sensors-and-location/location/permissions)), so the widget's service adds that type when it can. Android only allows adding it while Glance's screen is open ([while-in-use restrictions](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start)), not when the widget starts at boot or after an update. Opening Glance once afterwards turns it back on; until then travel times start from your calendar.
- **Alert vibration** uses the notification vibration usage, which Android requires for vibrating from the background, so it also follows your phone's notification vibration setting.
- **Pop-ups** use a high-importance notification channel, which is how Android shows heads-up notifications. A channel's sound and vibration can't be changed by the app once it exists, so the channel is silent and Glance buzzes and plays the sound itself, checking the ringer mode first. 1.20 replaced the old "Events starting" channel (which had its own sound and buzz) with "Event alerts".
- **Some phones kill background apps anyway** (Samsung, Xiaomi, OnePlus, Huawei and others). If the pill vanishes after a while, set Glance's battery use to "Unrestricted" or add it to the battery exceptions. [dontkillmyapp.com](https://dontkillmyapp.com) has the steps for each brand.
- After an update from the settings screen, the widget starts again by itself if it was running. After installing an APK by hand, open Glance and tap Start.
