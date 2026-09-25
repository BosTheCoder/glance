package io.github.bosthecoder.glance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

// Fails if: a place stops being learned from travel events, a trip's start or end resolves from the wrong
// source (device, previous event, outbound trip, home), the next event's location is used past 2 h, the
// "one to catch" arrives late or a departed option still shows, or postcodes stop being found in addresses.
class TravelTest {
    private val zone = ZoneId.of("Europe/London")
    private val home = "35 Starboard Way, London E16 2NZ, UK"
    private val office = "Bloomberg, 3 Queen Victoria St, London EC4N 4TQ, UK"
    private val blood = "Bishopsgate Institute, 230 Bishopsgate, London EC2M 4QH"
    private var id = 0L
    private fun at(day: Int, h: Int, m: Int = 0) = LocalDateTime.of(2026, 9, day, h, m).atZone(zone).toInstant().toEpochMilli()
    private fun ev(title: String, day: Int, h: Int, m: Int, lenMin: Int, location: String? = null) =
        at(day, h, m).let { Ev(++id, title, it, it + lenMin * MIN, false, 0, location = location) }

    // A day like the real calendar: the commute has addresses, the gym trips and a later "Home" don't.
    private val toOffice = ev("Travel: to Office", 28, 7, 45, 45, office)
    private val toGym = ev("Travel: gym", 28, 11, 45, 15)
    private val fromGym = ev("Travel: from Gym", 28, 12, 45, 15)
    private val homeTue = ev("Travel: Home", 29, 17, 0, 45, home)
    private val give = ev("Give Blood", 30, 10, 50, 60, blood)
    private val homeWed = ev("Travel: Home ", 30, 12, 0, 45)
    private val toPark = ev("Travel: to park", 30, 15, 0, 30)
    private val picnic = ev("Picnic", 30, 16, 30, 120, "Victoria Park, London E9 7BT")
    private val lateWork = ev("Travel: work", 1, 7, 0, 45)
    private val events = listOf(toOffice, toGym, fromGym, homeTue, give, homeWed, toPark, picnic, lateWork)
    private val places = Travel.places(events)

    private fun dest(e: Ev) = Travel.destination(e, events, places, zone)
    private fun origin(e: Ev, here: String? = null, now: Long = 0) = Travel.origin(e, events, places, zone, here, now)

    @Test fun destinations() {
        assertEquals(mapOf("office" to office, "home" to home), places)
        assertEquals(office, dest(toOffice))                  // its own location
        assertEquals(home, dest(homeWed))                     // learned from another "Travel: Home"
        assertEquals(office, dest(lateWork))                  // work and office are one place
        assertEquals(office, dest(fromGym))                   // back to where the gym trip set off
        assertEquals("Victoria Park, London E9 7BT", dest(toPark))   // the next event, within 2 h
        assertNull(dest(ev("Travel: somewhere", 30, 13, 0, 30)))       // picnic is 3 h after: too far
    }

    @Test fun origins() {
        assertEquals(home, origin(toOffice))                  // nothing before it that day: home
        assertEquals(office, origin(toGym))                   // where the last trip went
        assertEquals(blood, origin(homeWed))                  // the last event's own location
        // The phone's location wins only for a trip starting within 90 minutes.
        assertEquals("51.501,0.031", origin(homeWed, "51.501,0.031", homeWed.begin - 60 * MIN))
        assertEquals(blood, origin(homeWed, "51.501,0.031", homeWed.begin - 120 * MIN))
    }

    @Test fun theOneToCatch() {
        val now = at(29, 16, 40)
        val js = listOf(16 to 49, 16 to 58, 17 to 7, 17 to 16, 16 to 43).map { (h, m) -> Journey(at(29, h, m), at(29, h, m) + 36 * MIN, "DLR") }
        val opts = Travel.options(js, now, bufferMin = 5)      // 16:43 leaves at 16:38: gone
        assertEquals(listOf(16 to 49, 16 to 58, 17 to 7, 17 to 16).map { (h, m) -> at(29, h, m) }, opts.map { it.depart })
        assertEquals(at(29, 16, 58), Travel.catch(opts, at(29, 17, 40))?.depart)   // arrives 17:34; the 17:07 gets in at 17:43
        assertEquals(at(29, 17, 7), Travel.catch(opts, at(29, 17, 43))?.depart)    // exactly on time counts
        assertNull(Travel.catch(opts, at(29, 17, 0)))
    }

    @Test fun codes() {
        assertEquals("EC4N4TQ", Travel.code(office))
        assertEquals("E162NZ", Travel.code(home))
        assertEquals("51.5,-0.09", Travel.code(" 51.5, -0.09 "))
        assertNull(Travel.code("PureGym Canary Wharf"))
    }
}
