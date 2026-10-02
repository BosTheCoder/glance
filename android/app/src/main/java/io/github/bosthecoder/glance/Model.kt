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
    val eventId: Long = 0,     // Events._ID, to open it in the calendar app
    val location: String? = null,
)

sealed class Alert {
    abstract val ev: Ev
    /** An event has just started: green ▶ banner. */
    data class Starting(override val ev: Ev) : Alert()
    /** One of the event's own reminders is due: blue bell banner. */
    data class Reminder(override val ev: Ev, val minutes: Int) : Alert()
    /** Heads-up: at [at] [ev] starts ("Next:") or, if [starting] is false, the current [ev] ends ("Ending:"). Amber. */
    data class Coming(override val ev: Ev, val starting: Boolean, val at: Long) : Alert()
}

const val MIN = 60_000L

/** When [this] became due. A heads-up's moment is when its window opened, [headsUpMinutes] before the change. */
fun Alert.dueAt(headsUpMinutes: Int): Long = when (this) {
    is Alert.Starting -> ev.begin
    is Alert.Reminder -> ev.begin - minutes * MIN
    is Alert.Coming -> at - headsUpMinutes * MIN
}

/**
 * An alert for an event you moved at [movedAt] that was already due then: you've just set that time, so it's not news.
 * Delay 5 min and the "in 5 min" heads-up is stale; delay 15 and it still comes, 5 min before the new start.
 */
fun Alert.stale(movedAt: Long, headsUpMinutes: Int) = dueAt(headsUpMinutes) <= movedAt

object Plan {
    fun current(events: List<Ev>, now: Long) = events.filter { !it.allDay && it.begin <= now && it.end > now }.sortedBy { it.begin }
    fun upcoming(events: List<Ev>, now: Long) = events.filter { !it.allDay && it.begin > now }.sortedBy { it.begin }

    /** The "Up next" rows: the first [count] upcoming timed events (Windows `NextCount`). */
    fun next(events: List<Ev>, now: Long, count: Int) = upcoming(events, now).take(count.coerceIn(1, 7))

    /** Side-strip rows: everything on now, then upcoming timed events up to [count] rows in all (on-now rows are never cut). */
    fun strip(events: List<Ev>, now: Long, count: Int): List<Ev> {
        val cur = current(events, now)
        return cur + upcoming(events, now).take((count.coerceIn(1, 5) - cur.size).coerceAtLeast(0))
    }

    /** When the pill's content next changes: the current event ends or the next one starts. */
    fun nextChange(events: List<Ev>, now: Long): Long? =
        (current(events, now).map { it.end } + listOfNotNull(upcoming(events, now).firstOrNull()?.begin)).minOrNull()

    /** True in the last [minutes] before [nextChange]: the pill untucks and turns amber. */
    fun headsUp(events: List<Ev>, now: Long, minutes: Int): Boolean =
        nextChange(events, now)?.let { it - now in 1..minutes * MIN } ?: false

    /**
     * The heads-up banner's subject, or null outside the heads-up window: the event that starts at the next
     * change, or failing that the current one that ends then. A start wins when both happen at once.
     */
    fun coming(events: List<Ev>, now: Long, minutes: Int): Alert.Coming? {
        if (!headsUp(events, now, minutes)) return null
        val at = nextChange(events, now) ?: return null
        upcoming(events, now).firstOrNull { it.begin == at }?.let { return Alert.Coming(it, true, at) }
        return current(events, now).firstOrNull { it.end == at }?.let { Alert.Coming(it, false, at) }
    }

    /** The next instant anything on screen should change, so the tick can land on it instead of up to 30 s late. */
    fun nextMoment(events: List<Ev>, now: Long, headsUpMinutes: Int): Long? =
        events.flatMap { e ->
            val t = if (e.allDay) emptyList() else listOf(e.begin, e.end, e.begin - headsUpMinutes * MIN, e.end - headsUpMinutes * MIN)
            t + e.reminders.map { e.begin - it * MIN }
        }.filter { it > now }.minOrNull()
}

enum class Side { LEFT, RIGHT }

/**
 * Where a released drag docks, or null to stay where it was dropped. A throw faster than
 * [flingV] (px/s) docks on the side it was thrown towards; otherwise more than 40% of the view past a
 * screen edge docks on that edge. [left] is the view's left in screen pixels, so it goes negative off the left.
 */
fun dockSide(left: Int, width: Int, screenW: Int, vx: Float, flingV: Float): Side? = when {
    vx > flingV -> Side.RIGHT
    vx < -flingV -> Side.LEFT
    -left > width * 0.4f -> Side.LEFT
    left + width - screenW > width * 0.4f -> Side.RIGHT
    else -> null
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

/**
 * New start times for the start screen's buttons. Only the start moves; the end stays, so later plans don't shift.
 * Times are whole minutes, as a calendar shows them.
 */
object Retime {
    val DELAYS = listOf(2, 5, 10, 15)
    /** [minutes] after the event's start, or after now if the start has already passed: "I need 5 more minutes". */
    fun delayed(e: Ev, minutes: Int, now: Long) = maxOf(e.begin, startNow(now)) + minutes * MIN
    /** The delays that still leave some of the event. */
    fun delays(e: Ev, now: Long) = DELAYS.filter { delayed(e, it, now) < e.end }
    fun startNow(now: Long) = now / MIN * MIN
    /** Started a while ago without you: Start now then moves its start to now instead of just closing the page. */
    fun late(e: Ev, now: Long) = now - e.begin >= LATE
    const val LATE = 2 * MIN
}

/**
 * What to stretch after delaying an event due at [due]: everything on then or now (so the one that ran over into it
 * counts), or failing that the event that ended last (in the past 6 hours). Extended by the delay, it runs on until
 * the delayed event now starts. [skip]: the delayed event's ids.
 */
fun extendable(events: List<Ev>, skip: Set<Long>, now: Long, due: Long): List<Ev> {
    val timed = events.filter { !it.allDay && it.eventId !in skip }
    val on = timed.filter { it.begin <= now && it.end >= minOf(now, due) }
    return on.ifEmpty { listOfNotNull(timed.filter { it.end <= now && it.end > now - 6 * 60 * MIN }.maxByOrNull { it.end }) }
}

/**
 * "I've answered this alert", passed between the phone and Windows over ntfy.sh (docs/android.md#on-both-devices).
 * Both apps work out the same topic from the Google account and the same key from an event's title and start minute,
 * so nothing needs pairing and no titles leave the device. Windows' Sync class must match these exactly.
 */
object Sync {
    fun key(title: String, begin: Long) = sha("$title|${begin / MIN}").take(16)
    fun topic(account: String) = "glance-" + sha("glance-sync:${account.lowercase()}").take(20)
    private fun sha(s: String) = java.security.MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
}

/** "25m", "1h", "1h 5m": same as the Windows app. */
fun dur(ms: Long): String {
    val m = maxOf(1L, (ms + MIN - 1) / MIN)
    return if (m < 60) "${m}m" else if (m % 60 == 0L) "${m / 60}h" else "${m / 60}h ${m % 60}m"
}

/** True if release tag [tag] ("v1.8.0") is a later version than [current] ("1.7.1"), compared number by number. */
fun isNewer(tag: String, current: String): Boolean {
    fun parts(v: String) = v.trim().removePrefix("v").split('.').map { p -> p.takeWhile { it.isDigit() }.toIntOrNull() ?: 0 }
    val a = parts(tag); val b = parts(current)
    for (i in 0 until maxOf(a.size, b.size)) {
        val d = a.getOrElse(i) { 0 } - b.getOrElse(i) { 0 }
        if (d != 0) return d > 0
    }
    return false
}
