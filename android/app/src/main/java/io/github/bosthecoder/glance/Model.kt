package io.github.bosthecoder.glance

// Pure Kotlin: no Android imports here, so AlertsTest runs on the plain JVM.

/** One occurrence of an event. Times are epoch millis; all-day ones are already at local midnight. */
data class Ev(
    val id: Long,              // Instances._ID, unique per occurrence
    val title: String,
    val begin: Long,
    val end: Long,
    val allDay: Boolean,
    val color: Int,
    val reminders: List<Int> = emptyList(),   // minutes before begin
)

sealed class Alert {
    abstract val ev: Ev
    /** An event has just started: green ▶ banner. */
    data class Starting(override val ev: Ev) : Alert()
    /** One of the event's own reminders is due: blue bell banner. */
    data class Reminder(override val ev: Ev, val minutes: Int) : Alert()
}

const val MIN = 60_000L

object Plan {
    fun current(events: List<Ev>, now: Long) = events.filter { !it.allDay && it.begin <= now && it.end > now }.sortedBy { it.begin }
    fun upcoming(events: List<Ev>, now: Long) = events.filter { !it.allDay && it.begin > now }.sortedBy { it.begin }

    /** When the pill's content next changes: the current event ends or the next one starts. */
    fun nextChange(events: List<Ev>, now: Long): Long? =
        (current(events, now).map { it.end } + listOfNotNull(upcoming(events, now).firstOrNull()?.begin)).minOrNull()

    /** True in the last [minutes] before [nextChange]: the pill untucks and turns amber. */
    fun headsUp(events: List<Ev>, now: Long, minutes: Int): Boolean =
        nextChange(events, now)?.let { it - now in 1..minutes * MIN } ?: false

    /** The next instant anything on screen should change, so the tick can land on it instead of up to 30 s late. */
    fun nextMoment(events: List<Ev>, now: Long, headsUpMinutes: Int): Long? =
        events.flatMap { e ->
            val t = if (e.allDay) emptyList() else listOf(e.begin, e.end, e.begin - headsUpMinutes * MIN, e.end - headsUpMinutes * MIN)
            t + e.reminders.map { e.begin - it * MIN }
        }.filter { it > now }.minOrNull()
}

/**
 * Decides which alerts fire now. Each fires once (key = instance id + begin + minutes), and only if its
 * moment was within the last [windowMs], so a restart doesn't replay the morning's alerts.
 */
class AlertTracker(private val windowMs: Long = 2 * MIN) {
    private val fired = HashMap<String, Long>()   // key -> moment it was due

    fun due(events: List<Ev>, now: Long): List<Alert> {
        fired.values.removeAll { it < now - windowMs }   // too old to fire again anyway
        val out = mutableListOf<Alert>()
        fun fire(key: String, at: Long, alert: Alert) {
            if (at <= now && at > now - windowMs && fired.put(key, at) == null) out += alert
        }
        for (e in events) {
            if (!e.allDay) fire("s:${e.id}:${e.begin}", e.begin, Alert.Starting(e))
            for (m in e.reminders) if (now < e.begin) fire("r:${e.id}:${e.begin}:$m", e.begin - m * MIN, Alert.Reminder(e, m))
        }
        return out
    }
}

/** "25m", "1h", "1h 5m": same as the Windows app. */
fun dur(ms: Long): String {
    val m = maxOf(1L, (ms + MIN - 1) / MIN)
    return if (m < 60) "${m}m" else if (m % 60 == 0L) "${m / 60}h" else "${m / 60}h ${m % 60}m"
}
