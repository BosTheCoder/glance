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
            val j = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
            val arr = j.optJSONArray("journeys") ?: return null
            var start: DoubleArray? = null; var end: DoubleArray? = null
            val out = (0 until arr.length()).map { i ->
                val jr = arr.getJSONObject(i)
                val legs = jr.getJSONArray("legs")
                fun point(o: JSONObject?) = o?.takeIf { it.has("lat") }?.let { doubleArrayOf(it.getDouble("lat"), it.getDouble("lon")) }
                if (legs.length() > 0) {
                    start = start ?: point(legs.getJSONObject(0).optJSONObject("departurePoint"))
                    end = end ?: point(legs.getJSONObject(legs.length() - 1).optJSONObject("arrivalPoint"))
                }
                val via = (0 until legs.length()).map { legs.getJSONObject(it) }
                    .filter { it.getJSONObject("mode").optString("id") != "walking" }
                    .joinToString(" → ") { leg ->
                        leg.optJSONArray("routeOptions")?.optJSONObject(0)?.optString("name")?.takeIf { it.isNotBlank() }
                            ?: leg.getJSONObject("mode").optString("name")
                    }
                Journey(ms(jr.getString("startDateTime")), ms(jr.getString("arrivalDateTime")), via.ifEmpty { "walk" })
            }
            return Answer(out, start, end)
        } finally {
            c.disconnect()
        }
    }

    private fun ms(s: String) = LocalDateTime.parse(s).atZone(LONDON).toInstant().toEpochMilli()
}
