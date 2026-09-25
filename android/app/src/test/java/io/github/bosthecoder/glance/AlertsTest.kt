package io.github.bosthecoder.glance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// Fails if: the heads-up window is computed from the wrong edge, all-day events leak into now/next,
// alerts replay after a restart, or a reminder fires twice.
class AlertsTest {
    private val t0 = 1_800_000_000_000L
    private fun ev(id: Long, startMin: Int, lenMin: Int, allDay: Boolean = false, reminders: List<Int> = emptyList()) =
        Ev(id, "e$id", t0 + startMin * MIN, t0 + (startMin + lenMin) * MIN, allDay, 0, reminders)

    private val allDay = ev(1, -600, 24 * 60, allDay = true)
    private val meeting = ev(2, -30, 60)      // 30 min in, 30 left
    private val lunch = ev(3, 45, 60)
    private val events = listOf(lunch, allDay, meeting)

    @Test fun nowAndNextIgnoreAllDay() {
        assertEquals(listOf(meeting), Plan.current(events, t0))
        assertEquals(listOf(lunch), Plan.upcoming(events, t0))
        assertEquals(meeting.end, Plan.nextChange(events, t0))
        assertEquals(lunch.begin, Plan.nextChange(events, meeting.end))   // free: next start is the change
    }

    @Test fun headsUpOnlyInLastMinutesBeforeChange() {
        assertFalse(Plan.headsUp(events, t0, 5))                      // 30m left
        assertTrue(Plan.headsUp(events, meeting.end - 4 * MIN, 5))    // 4m before it ends
        assertFalse(Plan.headsUp(events, meeting.end + MIN, 5))       // free, lunch 44m away
        assertTrue(Plan.headsUp(events, lunch.begin - 2 * MIN, 5))    // 2m before lunch starts
        assertFalse(Plan.headsUp(listOf(allDay), t0, 5))              // an all-day event never counts
    }

    @Test fun startingFiresOnceAndNotAfterRestart() {
        val tracker = AlertTracker()
        assertEquals(listOf(Alert.Starting(lunch)), tracker.due(events, lunch.begin + 10_000))
        assertEquals(emptyList<Alert>(), tracker.due(events, lunch.begin + 40_000))   // duplicate suppressed
        // A fresh tracker (service restarted) 3 minutes later must not replay it.
        assertEquals(emptyList<Alert>(), AlertTracker().due(events, lunch.begin + 3 * MIN))
        // All-day events never produce a Starting alert.
        assertEquals(emptyList<Alert>(), AlertTracker().due(listOf(allDay), allDay.begin))
    }

    @Test fun remindersFireOncePerMinutesValue() {
        val talk = ev(4, 60, 30, reminders = listOf(10, 30))
        val tracker = AlertTracker()
        assertEquals(emptyList<Alert>(), tracker.due(listOf(talk), talk.begin - 31 * MIN))
        assertEquals(listOf(Alert.Reminder(talk, 30)), tracker.due(listOf(talk), talk.begin - 30 * MIN))
        assertEquals(emptyList<Alert>(), tracker.due(listOf(talk), talk.begin - 29 * MIN))
        assertEquals(listOf(Alert.Reminder(talk, 10)), tracker.due(listOf(talk), talk.begin - 9 * MIN))
        assertEquals(emptyList<Alert>(), tracker.due(listOf(talk), talk.begin - 8 * MIN))
    }

    @Test fun nextMomentLandsOnHeadsUpAndReminders() {
        val talk = ev(4, 60, 30, reminders = listOf(10))
        assertEquals(talk.begin - 10 * MIN, Plan.nextMoment(listOf(talk), t0, 5))
        assertEquals(talk.begin - 5 * MIN, Plan.nextMoment(listOf(talk), talk.begin - 9 * MIN, 5))
    }

    @Test fun durMatchesWindows() {
        assertEquals("1m", dur(10_000))
        assertEquals("25m", dur(25 * MIN))
        assertEquals("2h", dur(120 * MIN))
        assertEquals("1h 5m", dur(65 * MIN))
    }
}
