# Seeing the Android widget on the emulator

AVD `glance35` (Android 35, Google APIs, x86_64). Needs KVM: `bosire` is in the `kvm` group since 2026-10-01.
Without KVM it boots in ~10 min and then ANRs everywhere, so don't bother.

```bash
# `sg` on this box is ast-grep (brew); the group tool is /usr/bin/sg. Only needed until the next WSL login.
/usr/bin/sg kvm -c "$HOME/Android/Sdk/emulator/emulator -avd glance35 -no-window -no-audio -no-boot-anim -no-snapshot -gpu swiftshader_indirect" &
A=~/Android/Sdk/platform-tools/adb
$A install -r android/dist/Glance.apk
$A shell pm grant io.github.bosthecoder.glance android.permission.READ_CALENDAR
$A shell appops set io.github.bosthecoder.glance SYSTEM_ALERT_WINDOW allow
bash docs/claude-notes/seed-calendar.sh   # local "Test" calendar + events around now; edit the add lines
```

- The first calendar-provider call after boot can time out ("Could not find provider"); run it again.
- Delete seeded events with `content delete --uri "'content://com.android.calendar/events?caller_is_syncadapter=true&account_name=test&account_type=LOCAL'" --where "'_id<=N'"` (a where clause is required).
- The Start button is below the fold: `input swipe 540 2000 540 400`, then `uiautomator dump` for its bounds and `input tap`.
- Dock it: a fast `input swipe <x> <y> 1075 <y> 60` throws it to the right edge. Screenshot with `exec-out screencap -p`.
