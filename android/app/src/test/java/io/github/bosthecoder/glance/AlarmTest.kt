package io.github.bosthecoder.glance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

// Fails if: a non-tailnet URL is trusted (the anti-spam guarantee), a malformed alarm parses, an id fires twice, or the
// topic drifts from the contract the concierge app posts to (Windows' AlarmTests asserts the same string).
class AlarmTest {
    @Test fun onlyTailnetHttpsIsTrusted() {
        for ((url, ok) in listOf(
            "https://bos-desktop.fish-grouper.ts.net:18787/api/alarms/1" to true,
            "  https://x.ts.net/a\n" to true,
            "http://bos-desktop.fish-grouper.ts.net/a" to false,
            "https://ts.net.evil.com/a" to false,
            "https://box.ts.net.evil.com/a" to false,
            "https://evil.com/x.ts.net" to false,
            "https://x.ts.net@evil.com/a" to false,
            "not a url" to false,
        )) assertEquals(url, ok, Alarm.trusted(url))
    }

    @Test fun parsesWellFormedAlarmsOnly() {
        assertEquals(Alarm("a1", "critical", "T", "B", "https://x.ts.net/t/1"),
            Alarm.parse("""{"id":"a1","level":"critical","title":"T","body":"B","open":"https://x.ts.net/t/1"}"""))
        assertEquals("", Alarm.parse("""{"id":"a1","level":"needs","open":"javascript:alert(1)"}""")!!.open)
        assertNull(Alarm.parse("""{"id":"a1","level":"loud"}"""))
        assertNull(Alarm.parse("""{"level":"urgent"}"""))
        assertNull(Alarm.parse("<html>"))
    }

    @Test fun eachIdFiresOnceAndTheSetStaysSmall() {
        var seen = Alarm.remember(emptyList(), "a", keep = 3)!!
        assertNull(Alarm.remember(seen, "a", keep = 3))
        for (id in listOf("b", "c", "d")) seen = Alarm.remember(seen, id, keep = 3)!!
        assertEquals(listOf("b", "c", "d"), seen)
    }

    @Test fun alarmTopicIsTheContract() = assertEquals("glance-44ecad7f70439178d8b1", Sync.alarmTopic("NyakundiHotmail@gmail.com"))
}
