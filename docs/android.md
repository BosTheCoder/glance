# Glance for Android

A floating pill that sits on the edge of your screen over any app. It shows what's on **now** (with the time left) or when you're free until, and it tucks itself away when you're not using it. Tap it to see the rest of the week.

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

| Do this | To |
| --- | --- |
| Tap the pill | Expand it: clock, all-day chips, the current event, what's next and a scrollable agenda |
| Tap the clock, or anywhere outside | Collapse it again |
| Drag the pill | Move it. It snaps to the nearest left or right edge and remembers where it was |
| Leave it for 3 seconds | It slides mostly off the edge and fades to the idle opacity |
| Touch the tab | Bring it back |
| Long-press the pill | Open the settings screen |
| Tap an alert banner | Dismiss it |

Settings: idle opacity (25/50/75/100%), heads-up time (2/5/10 minutes), vibrate on reminders, start when the phone boots.

## Alerts

The alerts show inside the pill, not as system notifications, and each kind has its own colour:

| When | What you see |
| --- | --- |
| The last few minutes before the current event ends or the next one starts (the heads-up setting, 5 min by default) | The pill comes out from the edge, its outline and countdown turn **amber** |
| An event starts | A **green** "▶ Now: *title*" banner that pulses twice and stays about 20 seconds |
| One of the event's own reminders is due | A **blue** bell banner, "*title* · in 10m", with one short vibration. It stays until you tap it or the event starts |

Each alert fires once. After a restart it only fires alerts that were due in the last 2 minutes, so it doesn't replay the morning's.

## How it gets your calendar

Glance reads Android's calendar provider (`CalendarContract`), the same local database your calendar app uses. Your Google account syncs into it, so edits in Google show up as soon as the phone syncs. Glance listens for changes, so it updates within a second of that and re-reads every 30 seconds for the countdowns.

- `Instances` from today 00:00 to 7 days ahead, with recurring events already expanded.
- Events you've declined are skipped. All-day events show as chips and don't count as "now".
- Reminders come from each event's own pop-up reminders (`Reminders` with method alert or default).

Nothing leaves the phone. The app has no internet permission.

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

**Signing.** `app/build.gradle.kts` reads `~/.config/glance/keystore.properties` (`storeFile`, `storePassword`, `keyAlias`, `keyPassword`). If that file isn't there, the release APK is signed with the debug key, which is fine for trying it out. Keep the release keystore: Android only accepts an update signed with the same key as the installed app.

## Layout

| File (under `android/app/src/`) | What's in it |
| --- | --- |
| `main/.../Model.kt` | Pure Kotlin, no Android: the `Ev` model, now/next and heads-up (`Plan`), which alerts are due (`AlertTracker`), `dur()` |
| `main/.../Calendar.kt` | `CalendarContract` queries (calendars, instances, reminders) and `Prefs` |
| `main/.../OverlayService.kt` | The foreground service and the overlay window: pill, expanded card, drag/snap/tuck, alerts |
| `main/.../MainActivity.kt` | Setup screen: permissions, calendar picker, settings, Start/Stop |
| `main/.../BootReceiver.kt` | Restarts the service after a reboot if "start on boot" is on |
| `main/res/` | Bell and notification vectors, adaptive launcher icon (foreground cut from `assets/source.png`, background `#0B1030`) |
| `test/.../AlertsTest.kt` | JVM tests for now/next, the heads-up window and alert de-duplication |

## Notes

- **Battery.** It runs as a foreground service so Android doesn't kill it. It wakes every 30 seconds, and at the exact moment something changes, and reads a local database. There's no network use.
- **Android 14+** requires a declared type for every foreground service. Glance uses `specialUse` with a short explanation, which is allowed for sideloaded apps.
- **Some phones kill background apps anyway** (Samsung, Xiaomi, OnePlus, Huawei and others). If the pill vanishes after a while, set Glance's battery use to "Unrestricted" or add it to the battery exceptions. [dontkillmyapp.com](https://dontkillmyapp.com) has the steps for each brand.
- After you install an update, open Glance and tap Start again.
