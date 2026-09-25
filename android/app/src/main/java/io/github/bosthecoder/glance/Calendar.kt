package io.github.bosthecoder.glance

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.CalendarContract
import android.provider.CalendarContract.Calendars
import android.provider.CalendarContract.Instances
import android.provider.CalendarContract.Reminders
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

/** Reads the phone's own calendar provider, which the Google account already syncs into. No OAuth. */
object Cal {
    data class Info(val id: Long, val name: String, val account: String, val color: Int, val visible: Boolean)

    fun granted(ctx: Context) = ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED

    fun calendars(ctx: Context): List<Info> {
        if (!granted(ctx)) return emptyList()
        val out = mutableListOf<Info>()
        ctx.contentResolver.query(
            Calendars.CONTENT_URI,
            arrayOf(Calendars._ID, Calendars.CALENDAR_DISPLAY_NAME, Calendars.ACCOUNT_NAME, Calendars.CALENDAR_COLOR, Calendars.VISIBLE),
            null, null, "${Calendars.ACCOUNT_NAME}, ${Calendars.CALENDAR_DISPLAY_NAME}",
        )?.use { c ->
            while (c.moveToNext()) out += Info(c.getLong(0), c.getString(1) ?: "?", c.getString(2) ?: "", c.getInt(3), c.getInt(4) == 1)
        }
        return out
    }

    /** Occurrences overlapping [today 00:00, now + 7 days] on the chosen calendars, recurrences already expanded. */
    fun events(ctx: Context, calendarIds: Set<Long>): List<Ev> {
        if (!granted(ctx) || calendarIds.isEmpty()) return emptyList()
        val zone = ZoneId.systemDefault()
        val from = LocalDate.now(zone).atStartOfDay(zone).toInstant().toEpochMilli()
        val to = System.currentTimeMillis() + 7 * 24 * 60 * MIN
        val uri = Instances.CONTENT_URI.buildUpon().also { ContentUris.appendId(it, from); ContentUris.appendId(it, to) }.build()
        val rows = mutableListOf<Pair<Long, Ev>>()   // event id -> occurrence
        ctx.contentResolver.query(
            uri,
            arrayOf(Instances._ID, Instances.EVENT_ID, Instances.TITLE, Instances.BEGIN, Instances.END,
                Instances.ALL_DAY, Instances.DISPLAY_COLOR, Instances.SELF_ATTENDEE_STATUS),
            "${Instances.CALENDAR_ID} IN (${calendarIds.joinToString()})", null, "${Instances.BEGIN} ASC",
        )?.use { c ->
            while (c.moveToNext()) {
                if (c.getInt(7) == CalendarContract.Attendees.ATTENDEE_STATUS_DECLINED) continue
                val allDay = c.getInt(5) == 1
                // All-day times are UTC midnight; move them to local midnight of the same date.
                fun t(ms: Long) = if (allDay) Instant.ofEpochMilli(ms).atOffset(ZoneOffset.UTC).toLocalDate()
                    .atStartOfDay(zone).toInstant().toEpochMilli() else ms
                rows += c.getLong(1) to Ev(c.getLong(0), c.getString(2)?.takeIf { it.isNotBlank() } ?: "(no title)",
                    t(c.getLong(3)), t(c.getLong(4)), allDay, c.getInt(6))
            }
        }
        val reminders = reminders(ctx, rows.map { it.first }.toSet())
        return rows.map { (eventId, e) -> e.copy(reminders = reminders[eventId].orEmpty()) }
    }

    private fun reminders(ctx: Context, eventIds: Set<Long>): Map<Long, List<Int>> {
        if (eventIds.isEmpty()) return emptyMap()
        val out = HashMap<Long, MutableList<Int>>()
        ctx.contentResolver.query(
            Reminders.CONTENT_URI, arrayOf(Reminders.EVENT_ID, Reminders.MINUTES),
            "${Reminders.EVENT_ID} IN (${eventIds.joinToString()}) AND ${Reminders.METHOD} IN (${Reminders.METHOD_ALERT}, ${Reminders.METHOD_DEFAULT})",
            null, null,
        )?.use { c ->
            while (c.moveToNext()) if (c.getInt(1) >= 0) out.getOrPut(c.getLong(0)) { mutableListOf() } += c.getInt(1)
        }
        return out
    }
}

/** Everything the user can change, in SharedPreferences. */
class Prefs(ctx: Context) {
    val sp = ctx.getSharedPreferences("glance", Context.MODE_PRIVATE)

    /** null = never picked: use whatever is visible in the phone's calendar app. */
    var calendars: Set<Long>?
        get() = sp.getStringSet("calendars", null)?.map { it.toLong() }?.toSet()
        set(v) = sp.edit { putStringSet("calendars", v?.map { it.toString() }?.toSet()) }
    var idleOpacity: Int get() = sp.getInt("idleOpacity", 50); set(v) = sp.edit { putInt("idleOpacity", v) }
    var headsUp: Int get() = sp.getInt("headsUp", 5); set(v) = sp.edit { putInt("headsUp", v) }
    var vibrate: Boolean get() = sp.getBoolean("vibrate", true); set(v) = sp.edit { putBoolean("vibrate", v) }
    var nextCount: Int get() = sp.getInt("nextCount", 1); set(v) = sp.edit { putInt("nextCount", v) }
    /** "Compact": pill that expands on tap. "Full": the expanded card, always. */
    var view: String get() = sp.getString("view", "Compact")!!; set(v) = sp.edit { putString("view", v) }
    /** Side strip: rows shown, opacity while untouched, and whether it's docked now (side = [right]). */
    var dockCount: Int get() = sp.getInt("dockCount", 2); set(v) = sp.edit { putInt("dockCount", v) }
    var dockOpacity: Int get() = sp.getInt("dockOpacity", 75); set(v) = sp.edit { putInt("dockOpacity", v) }
    var docked: Boolean get() = sp.getBoolean("docked", false); set(v) = sp.edit { putBoolean("docked", v) }
    /** Card size from the corner grip, in dp; -1 = the default (85% of the width, 60% of the height). */
    var cardWidth: Int get() = sp.getInt("cardWidth", -1); set(v) = sp.edit { putInt("cardWidth", v) }
    var listHeight: Int get() = sp.getInt("listHeight", -1); set(v) = sp.edit { putInt("listHeight", v) }
    /** The pill's width cap from a pinch, in dp; -1 = 60% of the screen. */
    var pillWidth: Int get() = sp.getInt("pillWidth", -1); set(v) = sp.edit { putInt("pillWidth", v) }
    /** Card and pill sizes back to their defaults. Up next stays: it's content, not size. */
    fun resetSize() = sp.edit { remove("cardWidth"); remove("listHeight"); remove("pillWidth") }
    var onBoot: Boolean get() = sp.getBoolean("onBoot", false); set(v) = sp.edit { putBoolean("onBoot", v) }
    var y: Int get() = sp.getInt("y", -1); set(v) = sp.edit { putInt("y", v) }
    var right: Boolean get() = sp.getBoolean("right", true); set(v) = sp.edit { putBoolean("right", v) }

    fun chosenCalendars(ctx: Context) = calendars ?: Cal.calendars(ctx).filter { it.visible }.map { it.id }.toSet()
}

fun canOverlay(ctx: Context) = Settings.canDrawOverlays(ctx)
fun canNotify(ctx: Context) = Build.VERSION.SDK_INT < 33 ||
    ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
