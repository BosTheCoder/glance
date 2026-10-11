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
 * Glance no longer changes the calendar; this only reverts what older versions logged to history.json. A skipped
 * occurrence of a repeating event was a cancelled exception, and reverting puts that exception back to how the
 * occurrence was. A skipped one-off event was deleted after keeping a copy of its fields and reminders, so revert
 * inserts it again (as a new event: guests and conferencing links don't come back). Moves logged before 1.22 (Delay
 * and Start now) can still be reverted too.
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
