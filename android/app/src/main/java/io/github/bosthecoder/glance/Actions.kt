package io.github.bosthecoder.glance

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract.Events
import android.provider.CalendarContract.Reminders
import android.util.Log
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

fun canWrite(ctx: Context) =
    ContextCompat.checkSelfPermission(ctx, Manifest.permission.WRITE_CALENDAR) == PackageManager.PERMISSION_GRANTED

/**
 * Move (delay or start now) and Won't do (Skip), written to the calendar provider (which syncs them to Google), each logged to history.json so
 * it can be reverted. A repeating event is never changed as a whole: its occurrence gets an exception (moved, or
 * cancelled), and reverting puts that exception back to how the occurrence was. A one-off event is moved in place, or
 * deleted after keeping a copy of its fields and reminders so revert can insert it again (as a new event: guests and
 * conferencing links don't come back).
 */
object Actions {
    private const val TAG = "Glance"
    private const val MAX = 300
    private val COPY = arrayOf(Events.CALENDAR_ID, Events.TITLE, Events.DESCRIPTION, Events.EVENT_LOCATION, Events.DTSTART,
        Events.DTEND, Events.EVENT_TIMEZONE, Events.EVENT_END_TIMEZONE, Events.ALL_DAY, Events.EVENT_COLOR_KEY,
        Events.AVAILABILITY, Events.ACCESS_LEVEL)

    private fun file(ctx: Context) = File(ctx.filesDir, "history.json")
    @Synchronized fun history(ctx: Context): List<JSONObject> = runCatching {
        JSONArray(file(ctx).readText()).let { a -> (0 until a.length()).map { a.getJSONObject(it) } }
    }.getOrDefault(emptyList())
    @Synchronized private fun save(ctx: Context, all: List<JSONObject>) =
        file(ctx).writeText(JSONArray(all.takeLast(MAX)).toString())

    /** Appends [entry] (stamped with the time) and returns it. Errors are logged too, so a failed action is visible. */
    @Synchronized private fun log(ctx: Context, entry: JSONObject): JSONObject {
        entry.put("at", System.currentTimeMillis())
        save(ctx, history(ctx) + entry)
        Log.i(TAG, "action: $entry")
        return entry
    }

    private fun series(ctx: Context, eventId: Long): JSONObject? = ctx.contentResolver.query(
        ContentUris.withAppendedId(Events.CONTENT_URI, eventId),
        arrayOf(Events.RRULE, Events.RDATE, Events.ORIGINAL_ID, Events.STATUS, Events._SYNC_ID), null, null, null,
    )?.use { c ->
        if (!c.moveToFirst()) null
        else JSONObject().put("recurring", !c.getString(0).isNullOrEmpty() || !c.getString(1).isNullOrEmpty())
            .put("synced", !c.getString(4).isNullOrEmpty())
            .put("exception", !c.isNull(2))   // already a moved/changed occurrence of a repeating event
            .put("status", if (c.isNull(3)) JSONObject.NULL else c.getInt(3))
    }

    private fun base(kind: String, e: Ev) = JSONObject().put("kind", kind).put("title", e.title)
        .put("begin", e.begin).put("end", e.end).put("eventId", e.eventId)

    /**
     * Moves the start of this occurrence of [e] to [begin] and keeps its end, so nothing after it shifts: a delay, or
     * starting it early.
     */
    fun move(ctx: Context, e: Ev, begin: Long): JSONObject {
        val entry = base("move", e).put("newBegin", begin)
        return log(ctx, runCatching {
            val s = series(ctx, e.eventId) ?: error("event ${e.eventId} not found")
            val times = ContentValues().apply { put(Events.DTSTART, begin); put(Events.DTEND, e.end) }
            if (s.getBoolean("recurring")) {
                // The exception takes the series' length on insert (the provider refuses an explicit DTEND there),
                // so its end is put back afterwards.
                val id = exception(ctx, e, ContentValues().apply { put(Events.DTSTART, begin) })
                entry.put("how", "exception").put("target", id)
                update(ctx, id, times)
            } else {
                update(ctx, e.eventId, times)
                entry.put("how", "times").put("target", e.eventId)
            }
        }.fold({ entry }, { entry.put("error", it.toString()) }))
    }

    /** Cancels this occurrence of [e]: a cancelled exception for a repeating event, otherwise deletes it (keeping a copy). */
    fun wontDo(ctx: Context, e: Ev): JSONObject {
        val entry = base("wontdo", e)
        return log(ctx, runCatching {
            val s = series(ctx, e.eventId) ?: error("event ${e.eventId} not found")
            if (s.getBoolean("recurring")) {
                val id = exception(ctx, e, ContentValues().apply { put(Events.STATUS, Events.STATUS_CANCELED) })
                entry.put("how", "exception").put("target", id).put("oldStatus", s.get("status"))
            } else if (s.getBoolean("exception")) {   // cancel the changed occurrence rather than delete it
                update(ctx, e.eventId, ContentValues().apply { put(Events.STATUS, Events.STATUS_CANCELED) })
                entry.put("how", "exception").put("target", e.eventId).put("oldStatus", s.get("status"))
            } else {
                entry.put("how", "deleted").put("copy", copy(ctx, e.eventId))
                if (ctx.contentResolver.delete(ContentUris.withAppendedId(Events.CONTENT_URI, e.eventId), null, null) != 1)
                    error("delete changed nothing")
            }
        }.fold({ entry }, { entry.put("error", it.toString()) }))
    }

    /** Undoes [entry] (by its "at"), marks it reverted and logs the revert. */
    fun revert(ctx: Context, at: Long): JSONObject {
        val entry = history(ctx).first { it.getLong("at") == at }
        val out = JSONObject().put("kind", "revert").put("title", entry.optString("title")).put("of", at)
        val r = runCatching {
            check(!entry.optBoolean("reverted") && !entry.has("error")) { "nothing to revert" }
            val back = ContentValues().apply { put(Events.DTSTART, entry.getLong("begin")); put(Events.DTEND, entry.getLong("end")) }
            when (entry.getString("how")) {
                "times" -> update(ctx, entry.getLong("target"), back)
                // An exception at the original times (and status) is the occurrence as it was. Deleting the exception
                // instead would sync to Google as cancelling that occurrence.
                "exception" -> update(ctx, entry.getLong("target"), back.apply {
                    if (entry.getString("kind") == "wontdo") put(Events.STATUS, entry.opt("oldStatus").takeIf { it is Int } as Int? ?: Events.STATUS_CONFIRMED)
                })
                "deleted" -> out.put("restoredAs", restore(ctx, entry.getJSONObject("copy")))
                else -> error("unknown action")
            }
        }
        r.exceptionOrNull()?.let { out.put("error", it.toString()) }
        if (r.isSuccess) synchronized(this) { save(ctx, history(ctx).map { if (it.getLong("at") == at) it.put("reverted", true) else it }) }
        return log(ctx, out)
    }

    private fun update(ctx: Context, id: Long, v: ContentValues) {
        if (ctx.contentResolver.update(ContentUris.withAppendedId(Events.CONTENT_URI, id), v, null, null) != 1)
            error("update of event $id changed nothing")
    }

    /** An exception for the occurrence of [e] that starts at e.begin; [v] says what's different. Returns its event id. */
    private fun exception(ctx: Context, e: Ev, v: ContentValues): Long {
        // Without a sync id the provider stops expanding the rest of the series once it has an exception (seen on a
        // local calendar), so wait for the event to reach Google rather than hide its other occurrences.
        check(series(ctx, e.eventId)?.optBoolean("synced") == true) { "this repeating event hasn't synced yet; try again in a minute" }
        v.put(Events.ORIGINAL_INSTANCE_TIME, e.begin)
        val uri = ctx.contentResolver.insert(ContentUris.withAppendedId(Events.CONTENT_EXCEPTION_URI, e.eventId), v)
            ?: error("exception insert returned nothing")
        return ContentUris.parseId(uri)
    }

    private fun copy(ctx: Context, id: Long): JSONObject {
        val o = JSONObject()
        ctx.contentResolver.query(ContentUris.withAppendedId(Events.CONTENT_URI, id), COPY, null, null, null)?.use { c ->
            if (!c.moveToFirst()) error("event $id not found")
            COPY.forEachIndexed { i, col -> if (!c.isNull(i)) o.put(col, c.getString(i)) }
        }
        val rem = JSONArray()
        ctx.contentResolver.query(Reminders.CONTENT_URI, arrayOf(Reminders.MINUTES, Reminders.METHOD), "${Reminders.EVENT_ID} = ?", arrayOf("$id"), null)
            ?.use { c -> while (c.moveToNext()) rem.put(JSONObject().put("m", c.getInt(0)).put("method", c.getInt(1))) }
        return o.put("reminders", rem)
    }

    private fun restore(ctx: Context, o: JSONObject): Long {
        val v = ContentValues()
        COPY.filter { o.has(it) }.forEach { v.put(it, o.getString(it)) }
        val id = ContentUris.parseId(ctx.contentResolver.insert(Events.CONTENT_URI, v) ?: error("insert returned nothing"))
        val rem = o.optJSONArray("reminders") ?: JSONArray()
        for (i in 0 until rem.length()) ctx.contentResolver.insert(Reminders.CONTENT_URI, ContentValues().apply {
            put(Reminders.EVENT_ID, id); put(Reminders.MINUTES, rem.getJSONObject(i).getInt("m")); put(Reminders.METHOD, rem.getJSONObject(i).getInt("method"))
        })
        return id
    }
}
