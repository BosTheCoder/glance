package io.github.bosthecoder.glance

import android.app.Activity
import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.media.AudioAttributes
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.widget.Button
import android.widget.LinearLayout
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.net.toUri
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

/**
 * An alarm another app raised (docs/android.md, "Alarms from other apps"). [level]: needs, urgent or critical.
 * The companion is pure (no Android), so AlarmTest runs on the JVM. Windows' Alarm record must match it.
 */
data class Alarm(val id: String, val level: String, val title: String, val body: String, val open: String) {
    companion object {
        private val LEVELS = listOf("needs", "urgent", "critical")

        /** Only a tailnet URL: anyone can post to an ntfy topic, but only Kelvin's tailnet can serve the alarm behind it. */
        fun trusted(url: String) = runCatching { URI(url.trim()) }.getOrNull()
            ?.let { it.scheme == "https" && it.host?.lowercase()?.endsWith(".ts.net") == true } ?: false

        /** The fetched JSON, or null if it isn't a well-formed alarm. [open] is kept only if it's https. */
        fun parse(json: String): Alarm? = runCatching {
            JSONObject(json).run {
                val open = optString("open")
                Alarm(optString("id"), optString("level"), optString("title"), optString("body"), if (open.startsWith("https://")) open else "")
            }
        }.getOrNull()?.takeIf { it.id.isNotEmpty() && it.level in LEVELS }

        /** [seen] plus [id], keeping the last [keep]; null if it was already there: it has fired before. */
        fun remember(seen: List<String>, id: String, keep: Int = 100): List<String>? = if (id in seen) null else (seen + id).takeLast(keep)
    }
}

/**
 * Listens, always (from the widget's service), to the alarm topic of each account, plus the sync topic for "alarm:<id>"
 * answers from Windows. An alarm message is only a URL: fetched from the tailnet, and only a 200 with a well-formed
 * alarm fires, so a fake one can't.
 */
object Alarms {
    private val main = Handler(Looper.getMainLooper())

    fun listen(ctx: Context): () -> Unit {
        var alarmTopics = emptySet<String>()   // listener thread only
        return Relay.listen({
            val accounts = Relay.accounts(ctx)
            alarmTopics = accounts.map(Sync::alarmTopic).toSet()
            alarmTopics.toList() + accounts.map(Sync::topic)
        }) { topic, msg, _ ->
            if (topic in alarmTopics) arrived(ctx, msg)
            else if (msg.startsWith("alarm:")) main.post { AlarmScreen.answered(ctx, msg.removePrefix("alarm:")) }
        }
    }

    /** On the listener thread. */
    private fun arrived(ctx: Context, url: String) {
        if (!Alarm.trusted(url)) { Log.w("Glance", "alarm ignored, not a tailnet https URL: $url"); return }
        val a = runCatching {
            (URL(url.trim()).openConnection() as HttpURLConnection).run {
                connectTimeout = 10_000; readTimeout = 10_000; instanceFollowRedirects = false
                try { if (responseCode == 200) Alarm.parse(inputStream.bufferedReader().readText()) else null } finally { disconnect() }
            }
        }.onFailure { Log.w("Glance", "alarm fetch failed: $url", it) }.getOrNull() ?: return
        val prefs = Prefs(ctx)
        prefs.alarmsSeen = Alarm.remember(prefs.alarmsSeen, a.id) ?: return
        main.post { fire(ctx, a) }
    }

    private fun fire(ctx: Context, a: Alarm) {
        if (a.level != "critical" && System.currentTimeMillis() < Prefs(ctx).pausedUntil) return   // Pause never mutes critical
        channels(ctx)
        val n = NotificationCompat.Builder(ctx, a.level)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle(a.title).setContentText(a.body).setStyle(NotificationCompat.BigTextStyle().bigText(a.body))
            .setAutoCancel(true)
        if (a.open.isNotEmpty()) n.setContentIntent(PendingIntent.getActivity(ctx, a.id.hashCode(),
            Intent(Intent.ACTION_VIEW, a.open.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE))
        if (a.level == "critical") {
            // Like an alarm clock: the page turns the screen on over the lock screen, and sounds until it's answered.
            val i = AlarmScreen.intent(ctx, a)
            val page = PendingIntent.getActivity(ctx, a.id.hashCode(), i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            n.setCategory(NotificationCompat.CATEGORY_ALARM).setOngoing(true).setContentIntent(page).setFullScreenIntent(page, true)
            AlarmScreen.enqueue(i)
            if (ctx.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == false) runCatching { ctx.startActivity(i) }
        }
        // Urgent keeps sounding (the channel's sound and buzz, through Do Not Disturb) until it's tapped or swiped away.
        val built = n.build().apply { if (a.level == "urgent") flags = flags or Notification.FLAG_INSISTENT }
        try { NotificationManagerCompat.from(ctx).notify("alarm", a.id.hashCode(), built) } catch (_: SecurityException) {}
    }

    /**
     * One channel per level. Created on every alarm, because Android only applies bypass-Do-Not-Disturb to an existing
     * channel once Glance has notification-policy access (the setup screen's Grant).
     */
    private fun channels(ctx: Context) = ctx.getSystemService(NotificationManager::class.java).createNotificationChannels(listOf(
        NotificationChannel("needs", "Alarms: needs you", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "Another app needs you, when you get a moment"
        },
        NotificationChannel("urgent", "Alarms: urgent", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Keeps sounding, through Do Not Disturb, until you tap or swipe it"
            setBypassDnd(true); enableVibration(true)
            setSound(Settings.System.DEFAULT_NOTIFICATION_URI, AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT).build())
        },
        NotificationChannel("critical", "Alarms: critical", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Fills the screen and sounds like an alarm clock until you answer it"
            setBypassDnd(true); setSound(null, null); enableVibration(false)   // the page sounds and buzzes itself
        },
    ))
}

/**
 * The red full-screen page for a critical alarm: title, body, Open and Dismiss. Over the lock screen with the screen
 * on (manifest), the alarm sound and buzz on repeat until one is pressed. Back does nothing. Several queue up.
 * Answering tells Windows ("alarm:<id>" on the sync topic), and an answer from Windows closes it here.
 */
class AlarmScreen : Activity() {
    companion object {
        fun intent(ctx: Context, a: Alarm) = Intent(ctx, AlarmScreen::class.java)
            .putExtra("id", a.id).putExtra("title", a.title).putExtra("body", a.body).putExtra("open", a.open)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        /** Main thread only. */
        private val queue = ArrayList<Intent>()
        private var live: AlarmScreen? = null
        private val done = HashSet<String>()   // answered: a full-screen intent arriving late mustn't bring it back
        private fun Intent.id() = getStringExtra("id") ?: ""

        fun enqueue(i: Intent) { if (i.id() !in done && queue.none { it.id() == i.id() }) queue += i; live?.show() }

        /** Answered here or on Windows: its page and notification go. */
        fun answered(ctx: Context, id: String) {
            done += id
            NotificationManagerCompat.from(ctx).cancel("alarm", id.hashCode())
            if (queue.removeAll { it.id() == id }) live?.show()
        }
    }

    private var ringtone: Ringtone? = null
    private val vibrator by lazy {
        if (Build.VERSION.SDK_INT >= 31) getSystemService(VibratorManager::class.java).defaultVibrator
        else @Suppress("DEPRECATION") getSystemService(Vibrator::class.java)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        live = this; enqueue(intent); show()
        // Alarm usage: what an alarm clock uses, so it sounds in silent mode and through Do Not Disturb.
        ringtone = RingtoneManager.getRingtone(this, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM))?.apply {
            audioAttributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build()
            if (Build.VERSION.SDK_INT >= 28) isLooping = true
            play()
        }
        val buzz = VibrationEffect.createWaveform(longArrayOf(0, 800, 400), 0)
        if (Build.VERSION.SDK_INT >= 33) vibrator.vibrate(buzz, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ALARM))
        else @Suppress("DEPRECATION") vibrator.vibrate(buzz, AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build())
    }
    override fun onStart() { super.onStart(); OverlayService.hide(true) }
    override fun onStop() { OverlayService.hide(false); super.onStop() }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); enqueue(intent) }
    override fun onDestroy() { ringtone?.stop(); vibrator.cancel(); if (live === this) live = null; super.onDestroy() }
    @Deprecated("Back does nothing: only Open or Dismiss stops the alarm") override fun onBackPressed() {}

    private fun show() {
        val i = queue.firstOrNull() ?: return finish()
        val open = i.getStringExtra("open") ?: ""
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(0xFF7A0E14.toInt()); setPadding(dp(28), dp(48), dp(28), dp(48))
            addView(text("⏰  ALARM" + if (queue.size > 1) "  ·  1 OF ${queue.size}" else "", 16f, 0xFFFF8A8A.toInt(), bold = true).apply { letterSpacing = 0.1f })
            addView(text(i.getStringExtra("title") ?: "", 40f, Color.WHITE, bold = true).apply { maxLines = 4; setPadding(0, dp(12), 0, dp(8)) })
            addView(text(i.getStringExtra("body") ?: "", 20f, 0xCCFFFFFF.toInt()).apply { maxLines = 8; setPadding(0, 0, 0, dp(36)) })
            if (open.isNotEmpty()) addView(button("Open", 0xFFD02B35.toInt(), 72) {
                runCatching { startActivity(Intent(Intent.ACTION_VIEW, open.toUri())) }
                answer(i.id())
            })
            addView(button("Dismiss", 0x33FFFFFF, 60) { answer(i.id()) })
        })
    }

    private fun answer(id: String) { Relay.send(applicationContext, "alarm:$id"); answered(this, id) }

    private fun button(s: String, bg: Int, height: Int, onClick: () -> Unit) = Button(this).apply {
        text = s; isAllCaps = false; setTextColor(Color.WHITE); textSize = if (height > 60) 22f else 18f
        background = GradientDrawable().apply { cornerRadius = dp(16).toFloat(); setColor(bg) }
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, dp(height)).apply { topMargin = dp(12) }
        setOnClickListener { onClick() }
    }
}
