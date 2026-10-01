A=~/Android/Sdk/platform-tools/adb
$A shell content insert --uri "'content://com.android.calendar/calendars?caller_is_syncadapter=true&account_name=test&account_type=LOCAL'" --bind account_name:s:test --bind account_type:s:LOCAL --bind name:s:Test --bind calendar_displayName:s:Test --bind calendar_color:i:-12627531 --bind calendar_access_level:i:700 --bind ownerAccount:s:test --bind visible:i:1 --bind sync_events:i:1
CID=$($A shell content query --uri content://com.android.calendar/calendars --projection _id:name | grep 'name=Test' | grep -o '_id=[0-9]*' | cut -d= -f2 | head -1); echo cid=$CID
[ -n "$CID" ] || exit 1
now=$(( $(date +%s) * 1000 )); M=60000
add() { $A shell content insert --uri content://com.android.calendar/events --bind calendar_id:i:$CID --bind "title:s:$1" --bind dtstart:l:$2 --bind dtend:l:$3 --bind eventTimezone:s:UTC --bind eventColor:i:$4; }
add Deep-work $((now-40*M)) $((now+50*M)) -14575885
add Team-standup $((now-5*M)) $((now+25*M)) -1086464
add Gym-booking $((now-15*M)) $((now+75*M)) -11751600
add Lunch $((now+90*M)) $((now+150*M)) -6543440
$A shell content query --uri content://com.android.calendar/events --projection title:dtstart:dtend
