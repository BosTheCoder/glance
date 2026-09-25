package io.github.bosthecoder.glance

import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** TfL's Journey Planner (Unified API, no key needed at this rate). Call off the main thread. */
object Tfl {
    private val LONDON = ZoneId.of("Europe/London")   // TfL's times are London local, without an offset

    /** [start]/[end]: the first leg's departure point and the last leg's arrival point, as (lat, lon), for Citymapper. */
    class Answer(val journeys: List<Journey>, val start: DoubleArray?, val end: DoubleArray?)

    /**
     * Journeys between two codes from [Travel.code], [arriving] by or departing at [at]. Null when TfL has none for
     * this pair (an unknown postcode, outside its area): that's an answer, not a failure. Throws on a network error.
     */
    fun journeys(from: String, to: String, at: Long, arriving: Boolean): Answer? {
        val t = Instant.ofEpochMilli(at).atZone(LONDON)
        val url = "https://api.tfl.gov.uk/Journey/JourneyResults/$from/to/$to?date=${DateTimeFormatter.ofPattern("yyyyMMdd").format(t)}" +
            "&time=${DateTimeFormatter.ofPattern("HHmm").format(t)}&timeIs=${if (arriving) "Arriving" else "Departing"}"
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000; readTimeout = 20_000
            setRequestProperty("User-Agent", "Glance-Android")
        }
        try {
            val code = c.responseCode
            if (code == 429 || code >= 500) throw IOException("TfL answered $code")
            if (code != 200) return null   // 300 = it couldn't place an end, 404 = no journey
            return parse(c.inputStream.bufferedReader().use { it.readText() })
        } finally {
            c.disconnect()
        }
    }

    /** A JourneyResults body: each journey's times, its lines, and its first ride (the first leg that isn't a walk). */
    fun parse(body: String): Answer? {
        val arr = JSONObject(body).optJSONArray("journeys") ?: return null
        var start: DoubleArray? = null; var end: DoubleArray? = null
        fun point(o: JSONObject?) = o?.takeIf { it.has("lat") }?.let { doubleArrayOf(it.getDouble("lat"), it.getDouble("lon")) }
        /** "DLR", "25 bus", "Central line": the route's name, else the mode's. */
        fun line(leg: JSONObject): String {
            val mode = leg.getJSONObject("mode")
            val name = leg.optJSONArray("routeOptions")?.optJSONObject(0)?.optString("name")?.takeIf { it.isNotBlank() }
                ?: return mode.optString("name")
            return when (mode.optString("id")) { "bus" -> "$name bus"; "tube" -> "$name line"; else -> name }
        }
        val out = (0 until arr.length()).map { i ->
            val jr = arr.getJSONObject(i)
            val legs = jr.getJSONArray("legs").let { a -> (0 until a.length()).map { a.getJSONObject(it) } }
            if (legs.isNotEmpty()) {
                start = start ?: point(legs.first().optJSONObject("departurePoint"))
                end = end ?: point(legs.last().optJSONObject("arrivalPoint"))
            }
            val rides = legs.filter { it.getJSONObject("mode").optString("id") != "walking" }
            val first = rides.firstOrNull()?.let { l ->
                Ride(l.optString("departureTime").takeIf { it.isNotEmpty() }?.let(::ms) ?: ms(jr.getString("startDateTime")), line(l),
                    l.optJSONObject("departurePoint")?.optString("commonName").orEmpty(),
                    l.optJSONArray("routeOptions")?.optJSONObject(0)?.optJSONArray("directions")?.optString(0)?.takeIf { it.isNotBlank() })
            }
            Journey(ms(jr.getString("startDateTime")), ms(jr.getString("arrivalDateTime")), rides.joinToString(" → ", transform = ::line).ifEmpty { "walk" }, first)
        }
        return Answer(out, start, end)
    }

    private fun ms(s: String) = LocalDateTime.parse(s).atZone(LONDON).toInstant().toEpochMilli()
}
