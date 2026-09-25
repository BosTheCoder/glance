# Travel times

Any timed event whose title starts with **Travel** ("Travel: to Office", "Travel: Home", "Travel gym") gets the
next public transport options, from TfL's Journey Planner, for getting there by the time the event ends.

- **Glancing:** the travel row says when to leave ("🚆 leave 16:44") where other rows show their length. It turns
  amber once leaving is within the heads-up time.
- **Focused** (expanded on Windows, the open card on Android): a row of times sits under it, "16:44 → 17:25".
  - The one to catch (the last that still gets you there on time) is outlined.
  - Later ones, which arrive after the event ends, are dimmed.
  - Hover a time (Windows) or hold it (Android) for the first ride, the one you can't run for: "DLR 16:40 from Bank
    DLR Station, towards Woolwich Arsenal", plus the lines and the length. **Later** fetches three more.
- **Click or tap a time** to open the trip in Citymapper, set to arrive by the end of the event. Where TfL has no
  times (outside London, or no postcode), there's a **Directions** link to Google Maps instead, which works anywhere.

## Where to and where from

**Destination**, the first of these that exists:

1. The event's location.
2. A place named in the title, learned from your other travel events: if "Travel: Home" has your address as its
   location, then a "Travel: Home" without a location goes there too. Words like "to" or "from" at the start of
   the title are ignored. Work and office count as the same place.
3. For "Travel: from *X*": where you set off from on the way to *X* earlier that day.
4. The location of the next event, if it starts within 2 hours after the trip.

**Start**, the first of these that exists:

1. On Android, your phone's location, for a trip that starts within 90 minutes. Windows doesn't use your location.
2. For "Travel: from *X*": *X*, if it's a known place.
3. The place of your last event before the trip that same day: a travel event's destination, or another event's location.
4. Home (learned the same way: the location of a "Travel: Home" event).

No times show if either end is missing, if the two ends are the same place, or if an address has no UK postcode
(TfL needs a postcode or coordinates). For a place like the gym, put its address on one "Travel: gym" event and every
other one learns it. On Windows you can also name places in settings.json (`Places`, below).

## The times

- **Leave time** = departure minus your get-ready **buffer** (one setting for all trips, 5 min by default). The
  departure already includes the walk to the stop.
- **An option stays while its train hasn't gone.** The walk to the stop can be run, a departure can't, so an option
  is kept until its first ride leaves (a walk-only one, until its start), even once its leave time has passed. Such
  a "hurry" option shows the ride instead of the leave time, in amber: "🏃 DLR 19:57 → 20:33". The glance label
  says **go now** once the chosen option's leave time has passed.
- The first options come from asking TfL for journeys **arriving by** the end of the trip. If fewer than three of
  them can still be caught, Glance asks for ones leaving from 10 minutes ago (a train you could still run for), then
  if still short, ones leaving once you're ready (now plus the buffer). They're merged, without duplicates, earliest first.
- Times refresh every 5 minutes when the trip starts within 2 hours, and hourly before that. Only trips that
  start within the next 12 hours are looked up.

## Settings

| Windows (settings.json) | Android | Default | Does |
| --- | --- | --- | --- |
| `Travel` | Travel times | `true` | Look up times for travel events |
| `TravelBuffer` | Get-ready time | `5` | Minutes taken off each departure to give the leave time |
| `Places` | (learned only) | `null` | Extra named places, e.g. `{"Gym": "PureGym, 1 High St, London E16 1AA"}`. These override learned ones |

## Behind it

- **TfL Unified API** `Journey/JourneyResults/{from}/to/{to}?date=yyyyMMdd&time=HHmm&timeIs=Arriving|Departing`.
  It needs no key for this light use. `from`/`to` are a postcode without spaces (`E162NZ`) or `lat,lon`. Free
  text gets back a list of guesses that are often wrong, so it isn't sent.
- **Citymapper** `https://citymapper.com/directions?startcoord=lat,lon&endcoord=lat,lon&endaddress=…&arrival_time=<ISO 8601>`.
  The coordinates are the first leg's departure point and the last leg's arrival point from TfL's answer. On
  Android the link opens the Citymapper app if it's installed.
- **Google Maps** `https://www.google.com/maps/dir/?api=1&origin=…&destination=…&travelmode=transit`.
