package io.github.bosthecoder.glance

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/**
 * "I've answered this alert" between the phone and Windows, over ntfy.sh: one topic per Google account (see [Sync]),
 * the message just the event's key. Sent for every button on the start page; listened to while the page is up.
 */
object Relay {
    private const val HOST = "https://ntfy.sh/"

    /** Topics for the accounts of the calendars Glance shows. Provider query: not on the main thread. */
    private fun topics(ctx: Context): List<String> {
        val chosen = Prefs(ctx).chosenCalendars(ctx)
        return Cal.calendars(ctx).filter { it.id in chosen && "@" in it.account }.map { Sync.topic(it.account) }.distinct()
    }

    fun send(ctx: Context, key: String) = thread {
        for (t in topics(ctx)) runCatching {
            (URL(HOST + t).openConnection() as HttpURLConnection).run {
                requestMethod = "POST"; doOutput = true; connectTimeout = 10_000; readTimeout = 10_000
                outputStream.use { it.write(key.toByteArray()) }
                responseCode.also { disconnect() }
            }
        }.onFailure { Log.w("Glance", "relay send failed", it) }
    }

    /**
     * Calls [onKey] (key, when it was sent in epoch ms) on a background thread for each key sent in the last 10 minutes
     * and from now on (ntfy keeps them), reconnecting after a drop, until the returned function is called.
     */
    fun listen(ctx: Context, onKey: (String, Long) -> Unit): () -> Unit {
        val stopped = java.util.concurrent.atomic.AtomicBoolean(false)
        val conn = java.util.concurrent.atomic.AtomicReference<HttpURLConnection?>()
        thread {
            while (!stopped.get()) {
                runCatching {
                    val ts = topics(ctx).takeIf { it.isNotEmpty() } ?: return@thread
                    val c = (URL("$HOST${ts.joinToString(",")}/json?since=10m").openConnection() as HttpURLConnection)
                        .apply { connectTimeout = 10_000; readTimeout = 90_000 }   // ntfy sends a keepalive every 45 s
                    conn.set(c)
                    c.inputStream.bufferedReader().forEachLine { line ->
                        val m = runCatching { JSONObject(line) }.getOrNull()
                        if (m?.optString("event") == "message") onKey(m.optString("message"), m.optLong("time") * 1000)
                    }
                }
                if (!stopped.get()) Thread.sleep(5_000)
            }
        }
        return { stopped.set(true); thread { conn.get()?.disconnect() } }   // disconnect does network work: not on the main thread
    }
}
