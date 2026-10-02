package io.github.bosthecoder.glance

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Toast
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.IntentCompat
import org.json.JSONObject
import kotlin.concurrent.thread

/**
 * Full-screen "Now: title" when an event starts, like an alarm clock: it turns the screen on and shows over the lock
 * screen (showWhenLocked / turnScreenOn in the manifest). Opened by the start notification's full-screen intent, or
 * straight from the service when the phone is in use. Start now also stops a "keep buzzing" alarm.
 * A reminder, or an upcoming event tapped in the widget, opens the same page in a softer blue ("soft"), where Start
 * now moves the event's start to now. Delay moves the start later; neither touches the end.
 * Several at once queue up and show one after another; each is started, delayed or skipped in turn.
 */
class StartScreen : Activity() {
    companion object {
        /** [soft]: not a start (a reminder, or a tap in the widget). [bell]: it's one of the event's reminders. */
        fun intent(svc: OverlayService, e: Ev, open: Intent, soft: Boolean = false, bell: Boolean = false) = Intent(svc, StartScreen::class.java)
            .putExtra("id", e.id).putExtra("eventId", e.eventId).putExtra("title", e.title).putExtra("color", e.color)
            .putExtra("begin", e.begin).putExtra("end", e.end).putExtra("open", open).putExtra("soft", soft).putExtra("bell", bell)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        /**
         * Pages waiting to be shown, oldest first. The service adds to it directly as well as through the intent,
         * because Android may not launch a second full-screen intent while this page already covers the lock screen.
         * Main thread only (the service's alerts and this activity both run there).
         */
        private val queue = ArrayList<Intent>()
        private var live: StartScreen? = null
        private fun Intent.id() = getLongExtra("id", 0)
        private fun Intent.soft() = getBooleanExtra("soft", false)
        fun enqueue(i: Intent) {
            if (queue.any { it.id() == i.id() && it.soft() == i.soft() }) return
            if (!i.soft()) queue.removeAll { it.id() == i.id() && it.soft() }   // the start replaces its unanswered reminder
            queue += i
            live?.show()   // already up: refresh its "1 OF n"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); live = this; enqueue(intent); show() }
    override fun onStart() { super.onStart(); OverlayService.hide(true) }
    override fun onStop() { OverlayService.hide(false); super.onStop() }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); enqueue(intent); show() }   // queued behind the current one
    override fun onDestroy() { if (live === this) live = null; if (isFinishing) queue.clear(); super.onDestroy() }   // Back: drop the rest rather than replay them later

    private fun show() {
        val i = queue.firstOrNull() ?: return finish()
        val now = System.currentTimeMillis()
        val color = i.getIntExtra("color", GREEN) or 0xFF000000.toInt()
        val begin = i.getLongExtra("begin", 0); val end = i.getLongExtra("end", 0)
        val ev = Ev(i.id(), i.getStringExtra("title") ?: "", begin, end, false, color, eventId = i.getLongExtra("eventId", 0))
        val started = begin <= now
        val soft = i.soft()
        fun button(s: String, bg: Int, weight: Boolean = false, height: Int = 60, onClick: () -> Unit) = Button(this).apply {
            text = s; isAllCaps = false; setTextColor(Color.WHITE); textSize = if (height > 60) 22f else 18f
            background = GradientDrawable().apply { cornerRadius = dp(16).toFloat(); setColor(bg) }
            layoutParams = (if (weight) LinearLayout.LayoutParams(0, dp(height), 1f).apply { marginEnd = dp(8) }
                else LinearLayout.LayoutParams(MATCH_PARENT, dp(height))).apply { topMargin = dp(12) }
            setOnClickListener { onClick() }
        }
        val head = when {
            started && !soft -> "▶  NOW"
            started -> "NOW"
            else -> (if (i.getBooleanExtra("bell", false)) "🔔  " else "") + "IN ${dur(begin - now).uppercase()}  ·  ${hm(begin)}"
        } + if (queue.size > 1) "  ·  1 OF ${queue.size}" else ""
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_VERTICAL
            // A start is black with green; a reminder (or a look ahead) is a calmer blue, so it reads as just a heads-up.
            setBackgroundColor(if (soft) 0xFF17213A.toInt() else 0xFF0E0E10.toInt()); setPadding(dp(28), dp(48), dp(28), dp(48))
            addView(text(head, 16f, if (soft) BLUE else GREEN, bold = true).apply { letterSpacing = 0.1f })
            addView(text(ev.title, 40f, Color.WHITE, bold = true).apply { maxLines = 4; setPadding(0, dp(12), 0, dp(8)) })
            addView(text("${hm(begin)} – ${hm(end)}  ·  ${if (started) "${dur(end - now)} left" else dur(end - begin)}", 20f, 0xB3FFFFFF.toInt()))
            addView(Bar(context).apply { set(0f, color) }, LinearLayout.LayoutParams(MATCH_PARENT, dp(6)).apply { topMargin = dp(20); bottomMargin = dp(36) })
            addView(button("Open event", 0x1AFFFFFF) {
                IntentCompat.getParcelableExtra(i, "open", Intent::class.java)?.let { runCatching { startActivity(it) } }
                dismiss()
            })
            val delays = Retime.delays(ev, now)
            if (delays.isNotEmpty()) {
                addView(text("DELAY START", 13f, 0x8CFFFFFF.toInt(), bold = true).apply { letterSpacing = 0.1f; setPadding(0, dp(24), 0, 0) })
                addView(LinearLayout(context).apply {
                    delays.forEach { m -> addView(button("+${m}m", 0xFF22305A.toInt(), weight = true) {
                        act { Actions.move(this@StartScreen, ev, Retime.delayed(ev, m, System.currentTimeMillis())) }
                    }) }
                })
            }
            // Already going: Start now just says you've started. Not yet: it moves the start to now.
            fun startNow(big: Boolean) = button("Start now", 0xFF1F8F5F.toInt(), weight = !big, height = if (big) 72 else 60) {
                if (started) dismiss() else act { Actions.move(this@StartScreen, ev, Retime.startNow(System.currentTimeMillis())) }
            }
            // The big button is the likeliest next step: for a reminder that's carrying on (Dismiss), otherwise Start now.
            val reminder = soft && i.getBooleanExtra("bell", false)
            addView(LinearLayout(context).apply {
                addView(button("Skip event", 0xFF3A1F1F.toInt(), weight = true) { act { Actions.wontDo(this@StartScreen, ev) } })
                if (reminder) addView(startNow(big = false))
                else if (soft) addView(button("Dismiss", 0x1AFFFFFF, weight = true) { dismiss() })
            })
            addView(if (reminder) button("Dismiss", 0xFF2F4A86.toInt(), height = 72) { dismiss() } else startNow(big = true))
        })
    }

    private var pending: (() -> JSONObject)? = null

    /** Runs a calendar change off the main thread, says how it went, then closes. Asks for write access first if needed. */
    private fun act(change: () -> JSONObject) {
        if (!canWrite(this)) { pending = change; requestPermissions(arrayOf(Manifest.permission.WRITE_CALENDAR), 1); return }
        thread {
            val r = change()
            runOnUiThread {
                val msg = r.optString("error").takeIf { it.isNotEmpty() }?.let { "Couldn't change it: ${it.substringAfter(": ")}" }
                    ?: if (r.getString("kind") == "move") "Starts at ${hm(r.getLong("newBegin"))}" else "Skipped"
                // Started by hand: its start alert would fire straight away, for something you've just started.
                if (!r.has("error") && r.getString("kind") == "move" && r.getLong("newBegin") <= System.currentTimeMillis())
                    OverlayService.startedByHand += r.getLong("target") to r.getLong("newBegin")
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                dismiss()
            }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        val p = pending; pending = null
        if (p != null && canWrite(this)) act(p)
    }

    /** Done with the one on screen: on to the next queued start, or close. */
    private fun dismiss() {
        // Only a running widget can be buzzing; starting the service here would switch the widget on.
        if (OverlayService.running) startService(Intent(this, OverlayService::class.java).setAction(OverlayService.STOP_ALARM))
        queue.removeFirstOrNull()?.let { NotificationManagerCompat.from(this).cancel("start", it.getLongExtra("id", 0).toInt()) }
        if (queue.isEmpty()) finish() else show()
    }
}
