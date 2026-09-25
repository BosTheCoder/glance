package io.github.bosthecoder.glance

import org.junit.Assert.assertEquals
import org.junit.Test

// Fails if: the first ride stops being the first non-walking leg (a walk counts, or a later leg is picked), its
// departure falls back to the door time, or line names lose their "bus"/"line" suffix.
class TflTest {
    @Test fun firstRide() {
        val a = Tfl.parse("""
            {"journeys":[
              {"startDateTime":"2026-09-28T16:31:00","arrivalDateTime":"2026-09-28T17:07:00","legs":[
                {"mode":{"id":"walking","name":"walking"},"departureTime":"2026-09-28T16:31:00","departurePoint":{"commonName":"EC4N 4TQ","lat":51.5127,"lon":-0.0908},"routeOptions":[{"name":""}]},
                {"mode":{"id":"dlr","name":"dlr"},"departureTime":"2026-09-28T16:40:00","departurePoint":{"commonName":"Bank DLR Station"},"routeOptions":[{"name":"DLR","directions":["Woolwich Arsenal DLR Station"]}]},
                {"mode":{"id":"walking","name":"walking"},"departureTime":"2026-09-28T17:00:00","arrivalPoint":{"lat":51.501,"lon":0.031},"routeOptions":[]}]},
              {"startDateTime":"2026-09-28T16:45:00","arrivalDateTime":"2026-09-28T17:30:00","legs":[
                {"mode":{"id":"bus","name":"bus"},"departureTime":"2026-09-28T16:45:00","departurePoint":{"commonName":"Cannon Street"},"routeOptions":[{"name":"25","directions":["Ilford"]}]},
                {"mode":{"id":"tube","name":"tube"},"departureTime":"2026-09-28T17:05:00","routeOptions":[{"name":"Central"}]}]},
              {"startDateTime":"2026-09-28T16:50:00","arrivalDateTime":"2026-09-28T17:20:00","legs":[
                {"mode":{"id":"walking","name":"walking"},"routeOptions":[]}]}
            ]}""")!!
        val hm = { ms: Long -> hm(ms) }
        assertEquals("DLR", a.journeys[0].via)
        assertEquals("25 bus → Central line", a.journeys[1].via)
        assertEquals("DLR ${hm(a.journeys[0].first!!.at)} from Bank DLR Station, towards Woolwich Arsenal DLR Station · arrive ${hm(a.journeys[0].arrive)} · 36m",
            Travel.rideText(a.journeys[0], hm))
        assertEquals(9 * MIN, a.journeys[0].first!!.at - a.journeys[0].depart)   // 16:40, not the 16:31 walk
        assertEquals("25 bus", a.journeys[1].first!!.line)
        assertEquals(null, a.journeys[2].first)
        assertEquals(true, Travel.rideText(a.journeys[2], hm).startsWith("Walk, no ride to catch"))
    }
}
