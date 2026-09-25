package io.github.bosthecoder.glance

import java.net.URLEncoder
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

// Pure Kotlin like Model.kt: the rules in docs/travel.md, so TravelTest runs on the plain JVM.

/** One public transport option from TfL: [depart] already includes the walk to the stop. */
data class Journey(val depart: Long, val arrive: Long, val via: String)

object Travel {
    /** A timed event whose title starts with "Travel": "Travel: to Office", "Travel Home". */
    fun isTravel(e: Ev) = !e.allDay && e.title.trimStart().startsWith("travel", ignoreCase = true)

    /** The place a travel title names, and whether it's "from" it: "Travel: from Gym" -> ("gym", true). Work and office are one place. */
    fun name(title: String): Pair<String, Boolean> {
        var s = title.trim().drop(6).trimStart(':', '-', '–', ' ').trim().lowercase()
        val from = s.startsWith("from ")
        s = s.removePrefix("from ").removePrefix("to ").trim()
        return (if (s == "work") "office" else s) to from
    }

    /** Named places learned from travel events that have a location: "Travel: Home" at 35 Starboard Way teaches "home". */
    fun places(events: List<Ev>): Map<String, String> = events.filter { isTravel(it) && !it.location.isNullOrBlank() }
        .sortedBy { it.begin }
        .mapNotNull { e -> name(e.title).takeIf { !it.second && it.first.isNotEmpty() }?.let { it.first to e.location!!.trim() } }
        .toMap()

    private fun sameDay(a: Long, b: Long, zone: ZoneId) =
        Instant.ofEpochMilli(a).atZone(zone).toLocalDate() == Instant.ofEpochMilli(b).atZone(zone).toLocalDate()

    /** Where trip [t] goes: its location, a learned place, back to where "from X"'s outbound trip set off, or the next event's location. */
    fun destination(t: Ev, events: List<Ev>, places: Map<String, String>, zone: ZoneId): String? {
        t.location?.takeIf { it.isNotBlank() }?.let { return it.trim() }
        val (n, from) = name(t.title)
        if (!from) places[n]?.let { return it }
        if (from) events.filter { isTravel(it) && it.end <= t.begin && sameDay(it.begin, t.begin, zone) && name(it.title) == (n to false) }
            .maxByOrNull { it.begin }?.let { out -> origin(out, events, places, zone, null, 0)?.let { return it } }
        return events.filter { !it.allDay && !isTravel(it) && it.begin >= t.end && it.begin <= t.end + 120 * MIN && !it.location.isNullOrBlank() }
            .minByOrNull { it.begin }?.location?.trim()
    }

    /**
     * Where trip [t] starts: the device's [here] ("lat,lon") for a trip starting within 90 min of [now], the "from X"
     * place, the place of the last event before it that day (a trip's destination, another event's location), or home.
     */
    fun origin(t: Ev, events: List<Ev>, places: Map<String, String>, zone: ZoneId, here: String?, now: Long): String? {
        if (here != null && t.begin - now <= 90 * MIN) return here
        val (n, from) = name(t.title)
        if (from) places[n]?.let { return it }
        events.filter { !it.allDay && it !== t && it.end <= t.begin && sameDay(it.begin, t.begin, zone) }
            .sortedByDescending { it.end }
            .firstNotNullOfOrNull { e -> if (isTravel(e)) destination(e, events, places, zone) else e.location?.takeIf { it.isNotBlank() }?.trim() }
            ?.let { return it }
        return places["home"]
    }

    private val postcode = Regex("""\b([A-Z]{1,2}[0-9][A-Z0-9]?) ?([0-9][A-Z]{2})\b""", RegexOption.IGNORE_CASE)
    private val coords = Regex("""^\s*(-?\d{1,3}\.\d+)\s*,\s*(-?\d{1,3}\.\d+)\s*$""")

    /** What TfL takes for a place: a postcode without its space ("E162NZ") or "lat,lon". Null for anything else. */
    fun code(place: String): String? {
        coords.find(place)?.let { return "${it.groupValues[1]},${it.groupValues[2]}" }
        return postcode.findAll(place).lastOrNull()?.let { (it.groupValues[1] + it.groupValues[2]).uppercase() }
    }

    /** The options still worth showing: leave time (departure minus [bufferMin]) not yet passed, earliest first. */
    fun options(journeys: List<Journey>, now: Long, bufferMin: Int) =
        journeys.filter { it.depart - bufferMin * MIN >= now }.distinctBy { it.depart }.sortedBy { it.depart }

    /** The one to catch: the last option that still arrives by [arriveBy]. */
    fun catch(options: List<Journey>, arriveBy: Long) = options.lastOrNull { it.arrive <= arriveBy }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    fun citymapper(start: DoubleArray, end: DoubleArray, endAddress: String, arriveBy: Long, zone: ZoneId) =
        "https://citymapper.com/directions?startcoord=${start[0]},${start[1]}&endcoord=${end[0]},${end[1]}" +
            "&endaddress=${enc(endAddress)}&arrival_time=${enc(DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(Instant.ofEpochMilli(arriveBy).atZone(zone).toOffsetDateTime()))}"

    fun googleMaps(from: String, to: String) =
        "https://www.google.com/maps/dir/?api=1&origin=${enc(from)}&destination=${enc(to)}&travelmode=transit"
}
