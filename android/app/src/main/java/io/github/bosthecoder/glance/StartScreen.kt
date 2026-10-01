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
 * straight from the service when the phone is in use. Dismiss also stops a "keep buzzing" alarm.
 */
class StartScreen : Activity() {
    companion object {
        fun intent(svc: OverlayService, e: Ev, open: Intent) = Intent(svc, StartScreen::class.java)
            .putExtra("id", e.id).putExtra("eventId", e.eventId).putExtra("title", e.title).putExtra("color", e.color)
            .putExtra("begin", e.begin).putExtra("end", e.end).putExtra("open", open)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); show() }
    override fun onStart() { super.onStart(); OverlayService.hide(true) }
    override fun onStop() { OverlayService.hide(false); super.onStop() }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); show() }   // the next start replaces it

    private fun show() {
        val i = intent
        val color = i.getIntExtra("color", GREEN) or 0xFF000000.toInt()
        val end = i.getLongExtra("end", 0)
        val ev = Ev(i.getLongExtra("id", 0), i.getStringExtra("title") ?: "", i.getLongExtra("begin", 0), end, false, color,
            eventId = i.getLongExtra("eventId", 0))
        fun button(s: String, bg: Int, weight: Boolean = false, onClick: () -> Unit) = Button(this).apply {
            text = s; isAllCaps = false; setTextColor(Color.WHITE); textSize = 18f
            background = GradientDrawable().apply { cornerRadius = dp(16).toFloat(); setColor(bg) }
            layoutParams = (if (weight) LinearLayout.LayoutParams(0, dp(60), 1f).apply { marginEnd = dp(8) }
                else LinearLayout.LayoutParams(MATCH_PARENT, dp(60))).apply { topMargin = dp(12) }
            setOnClickListener { onClick() }
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(0xFF0E0E10.toInt()); setPadding(dp(28), dp(48), dp(28), dp(48))
            addView(text("▶  NOW", 16f, GREEN, bold = true).apply { letterSpacing = 0.1f })
            addView(text(i.getStringExtra("title") ?: "", 40f, Color.WHITE, bold = true).apply { maxLines = 4; setPadding(0, dp(12), 0, dp(8)) })
            addView(text("${hm(i.getLongExtra("begin", 0))} – ${hm(end)}  ·  ${dur(end - System.currentTimeMillis())}", 20f, 0xB3FFFFFF.toInt()))
            addView(Bar(context).apply { set(0f, color) }, LinearLayout.LayoutParams(MATCH_PARENT, dp(6)).apply { topMargin = dp(20); bottomMargin = dp(36) })
            addView(button("Open event", 0xFF1A1A1E.toInt()) {
                IntentCompat.getParcelableExtra(i, "open", Intent::class.java)?.let { runCatching { startActivity(it) } }
                dismiss()
            })
            addView(text("SNOOZE", 13f, 0x8CFFFFFF.toInt(), bold = true).apply { letterSpacing = 0.1f; setPadding(0, dp(24), 0, 0) })
            addView(LinearLayout(context).apply {
                listOf(15, 30, 60).forEach { m -> addView(button(dur(m * MIN), 0xFF22305A.toInt(), weight = true) { act { Actions.snooze(this@StartScreen, ev, m) } }) }
            })
            addView(button("Won't do", 0xFF3A1F1F.toInt()) { act { Actions.wontDo(this@StartScreen, ev) } })
            addView(button("Dismiss", 0xFF1D4D3A.toInt()) { dismiss() })
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
                    ?: if (r.getString("kind") == "snooze") "Moved to ${hm(r.getLong("begin") + r.getInt("minutes") * MIN)}" else "Cancelled"
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                dismiss()
            }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        val p = pending; pending = null
        if (p != null && canWrite(this)) act(p)
    }

    private fun dismiss() {
        // Only a running widget can be buzzing; starting the service here would switch the widget on.
        if (OverlayService.running) startService(Intent(this, OverlayService::class.java).setAction(OverlayService.STOP_ALARM))
        NotificationManagerCompat.from(this).cancel("start", intent.getLongExtra("id", 0).toInt())
        finish()
    }
}
