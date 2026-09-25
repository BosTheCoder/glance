package io.github.bosthecoder.glance

import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.database.ContentObserver
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.media.AudioAttributes
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.CalendarContract
import android.text.TextUtils
import android.util.Log
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.graphics.Insets
import androidx.dynamicanimation.animation.FloatValueHolder
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.hypot
import kotlin.math.roundToInt

// Same accents as the Windows app: green = starting, amber = heads-up, blue = reminder.
const val GREEN = 0xFF3DDC97.toInt()
const val AMBER = 0xFFF5A623.toInt()
const val BLUE = 0xFF7AA2FF.toInt()
const val GLASS = 0xCC141414.toInt()     // Windows "Graphite"
const val RIM = 0x1FFFFFFF               // 12% white
const val CARD = 0x1CFFFFFF

fun Context.dp(v: Number) = (v.toFloat() * resources.displayMetrics.density).roundToInt()
fun day(ms: Long): LocalDate = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).toLocalDate()
fun fmt(ms: Long, pattern: String): String =
    DateTimeFormatter.ofPattern(pattern, Locale.UK).format(Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()))
fun hm(ms: Long) = fmt(ms, "HH:mm")

fun Context.text(s: String, sp: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
    text = s; textSize = sp; setTextColor(color); maxLines = 1; ellipsize = TextUtils.TruncateAt.END
    if (bold) typeface = Typeface.DEFAULT_BOLD
}

fun Context.glass(radiusDp: Int) = GradientDrawable().apply {
    cornerRadius = dp(radiusDp).toFloat(); setColor(GLASS); setStroke(dp(1), RIM)
}

/** Thin time-left bar: event colour over a faint track. */
class Bar(ctx: Context) : View(ctx) {
    var progress = 0f; var color = Color.WHITE
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    fun set(progress: Float, color: Int) { this.progress = progress.coerceIn(0f, 1f); this.color = color; invalidate() }
    override fun onDraw(c: Canvas) {
        val r = height / 2f
        p.color = 0x33FFFFFF; c.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), r, r, p)
        p.color = color; c.drawRoundRect(0f, 0f, width * progress, height.toFloat(), r, r, p)
    }
}

/** ScrollView that stops growing at [maxH]. */
class MaxScroll(ctx: Context, var maxH: Int) : ScrollView(ctx) {
    override fun onMeasure(w: Int, h: Int) = super.onMeasure(w, MeasureSpec.makeMeasureSpec(maxH, MeasureSpec.AT_MOST))
}

/** Foreground service that owns the floating pill/card window. */
class OverlayService : Service() {
    companion object {
        var running = false; private set
        fun start(ctx: Context) {
            try {
                ContextCompat.startForegroundService(ctx, Intent(ctx, OverlayService::class.java))
            } catch (e: IllegalStateException) {   // ForegroundServiceStartNotAllowedException (API 31+) is one
                Log.w("Glance", "Not allowed to start the widget right now", e)
            }
        }
        fun stop(ctx: Context) { ctx.stopService(Intent(ctx, OverlayService::class.java)) }
    }

    private lateinit var wm: WindowManager
    /** Window context (API 30+): what the docs say to build a non-activity window from. The service itself on older phones. */
    private lateinit var ui: Context
    private val io = Executors.newSingleThreadExecutor()   // calendar provider queries run here, never on the main thread
    private lateinit var prefs: Prefs
    private val h = Handler(Looper.getMainLooper())
    private val tracker = AlertTracker()
    private var events = emptyList<Ev>()
    private var banner: Alert? = null
    private var bannerUntil = 0L
    private var expanded = false
    private var full = false        // View = Full: the card is always shown, never tucked or collapsed
    private var tucked = false
    private var right = true
    private var pillY = 0
    private val xSpring by lazy {
        SpringAnimation(FloatValueHolder()).apply {
            spring = SpringForce().setStiffness(SpringForce.STIFFNESS_MEDIUM).setDampingRatio(SpringForce.DAMPING_RATIO_LOW_BOUNCY)
            addUpdateListener { _, v, _ -> lp.x = v.roundToInt(); update() }
        }
    }

    private lateinit var root: FrameLayout
    private lateinit var lp: WindowManager.LayoutParams
    private lateinit var pill: LinearLayout
    private lateinit var pillBg: GradientDrawable
    private lateinit var cardBg: GradientDrawable
    private lateinit var nextBox: LinearLayout
    private lateinit var bannerRow: LinearLayout
    private lateinit var bannerGlyph: TextView
    private lateinit var bannerBell: ImageView
    private lateinit var bannerText: TextView
    private lateinit var dot: GradientDrawable
    private lateinit var title: TextView
    private lateinit var countdown: TextView
    private lateinit var bar: Bar
    private lateinit var card: LinearLayout
    private lateinit var clock: TextView
    private lateinit var scroll: MaxScroll
    private lateinit var body: LinearLayout

    private val tickR = Runnable { reload() }   // re-query each tick too: covers midnight rollover and a missed observer
    private val tuckR = Runnable { tuck() }
    private val reloadR = Runnable { reload() }
    private val observer = object : ContentObserver(h) {
        override fun onChange(selfChange: Boolean) = soon()   // sync arrives in bursts
    }
    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        when (key) { "y", "right" -> {}; "view" -> applyMode(); else -> soon() }
    }

    private val screen: Rect get() = if (Build.VERSION.SDK_INT >= 30) wm.currentWindowMetrics.bounds
        else ui.resources.displayMetrics.let { Rect(0, 0, it.widthPixels, it.heightPixels) }
    private val W get() = screen.width()
    private val H get() = screen.height()

    /** Bars, cutout and the gesture-nav back/home zones. A pill inside them can't be touched, so keep it out. */
    private fun edges(): Insets = if (Build.VERSION.SDK_INT >= 30) Insets.toCompatInsets(
        wm.currentWindowMetrics.windowInsets.getInsets(
            WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout() or WindowInsets.Type.systemGestures()))
        else Insets.NONE

    /** lp.y counts from below the status bar (overlays fit the system bars by default), so the room is H minus both bars. */
    private fun clampY(y: Int, height: Int): Int { val e = edges(); return y.coerceIn(0, maxOf(0, H - e.top - e.bottom - height)) }

    override fun onBind(intent: Intent?) = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_STICKY

    override fun onCreate() {
        super.onCreate()
        running = true
        prefs = Prefs(this)
        if (!foreground() || !canOverlay(this)) { stopSelf(); return }
        ui = if (Build.VERSION.SDK_INT >= 30)
            createDisplayContext(getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY))
                .createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null)
        else this
        wm = ui.getSystemService(WindowManager::class.java)
        right = prefs.right
        build()
        applyMode()
        wm.addView(root, lp)
        runCatching { contentResolver.registerContentObserver(CalendarContract.CONTENT_URI, true, observer) }
        prefs.sp.registerOnSharedPreferenceChangeListener(prefListener)
        reload()
    }

    override fun onDestroy() {
        running = false
        h.removeCallbacksAndMessages(null)
        io.shutdownNow()
        if (::lp.isInitialized) xSpring.cancel()
        contentResolver.unregisterContentObserver(observer)
        prefs.sp.unregisterOnSharedPreferenceChangeListener(prefListener)
        if (::root.isInitialized && root.isAttachedToWindow) wm.removeView(root)
        super.onDestroy()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (!::root.isInitialized) return
        if (expanded) collapse()
        scroll.maxH = (H * 0.6f).toInt() - dp(48)
        if (full) lp.width = cardW()
        lp.y = clampY(lp.y, root.height)
        update()
    }

    /** False if Android refused to make this a foreground service; the caller then stops. */
    private fun foreground(): Boolean {
        NotificationManagerCompat.from(this).createNotificationChannel(
            NotificationChannelCompat.Builder("overlay", NotificationManagerCompat.IMPORTANCE_LOW).setName("Floating widget").build())
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(this, "overlay")
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle("Glance is floating")
            .setContentText("Tap for settings")
            .setContentIntent(open)
            .setOngoing(true)
            .build()
        return try {
            // ServiceCompat drops the type below API 34, where specialUse doesn't exist.
            @Suppress("InlinedApi")
            ServiceCompat.startForeground(this, 1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            true
        } catch (e: IllegalStateException) {   // ForegroundServiceStartNotAllowedException on API 31+
            Log.w("Glance", "Android refused the foreground service", e)
            false
        }
    }

    // ---------- views ----------

    private fun build() = with(ui) {
        pillBg = glass(16)
        cardBg = glass(16)
        bannerGlyph = text("▶", 12f, GREEN)
        bannerBell = ImageView(this).apply { setImageResource(R.drawable.ic_bell); setColorFilter(BLUE) }
        bannerText = text("", 13f, Color.WHITE, bold = true)
        bannerRow = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL; visibility = View.GONE; setPadding(0, 0, 0, dp(4))
            addView(bannerGlyph)
            addView(bannerBell, LinearLayout.LayoutParams(dp(14), dp(14)))
            addView(bannerText, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = dp(6) })
        }
        dot = GradientDrawable().apply { shape = GradientDrawable.OVAL }
        title = text("", 14f, Color.WHITE, bold = true)
        countdown = text("", 12f, 0xB3FFFFFF.toInt())
        val row = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(View(context).apply { background = dot }, LinearLayout.LayoutParams(dp(7), dp(7)).apply { marginEnd = dp(8) })
            addView(title)
            addView(countdown, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = dp(8) })
        }
        bar = Bar(this)
        nextBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        pill = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; background = pillBg
            setPadding(dp(12), dp(8), dp(12), dp(9))
            addView(bannerRow); addView(row)
            addView(bar, LinearLayout.LayoutParams(MATCH_PARENT, dp(3)).apply { topMargin = dp(6) })
            addView(nextBox)
        }

        clock = text("", 13f, 0x99FFFFFF.toInt()).apply {
            setPadding(0, 0, 0, dp(8)); setOnClickListener { collapse() }
            setOnTouchListener { _, e -> full && onTouch(e) }   // Full view: the clock is the drag handle
        }
        body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll = MaxScroll(this, (H * 0.6f).toInt() - dp(48)).apply { isVerticalScrollBarEnabled = false; addView(body) }
        card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; background = cardBg; visibility = View.GONE
            setPadding(dp(16), dp(12), dp(16), dp(14))
            addView(clock, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)); addView(scroll)
        }

        root = object : FrameLayout(this) {
            // Full view: any touch, even one the agenda scroll takes, wakes the card and restarts the idle timer.
            override fun dispatchTouchEvent(e: MotionEvent): Boolean {
                if (full) when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> { h.removeCallbacks(tuckR); if (tucked) untuck() }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> scheduleTuck()
                }
                return super.dispatchTouchEvent(e)
            }
        }.apply {
            addView(pill, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
            addView(card, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            setOnTouchListener { _, e -> onTouch(e) }
        }
        lp = WindowManager.LayoutParams(
            WRAP_CONTENT, WRAP_CONTENT, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = side(); x = 0; y = prefs.y.takeIf { it >= 0 } ?: (H / 4) }
    }

    private fun side() = Gravity.TOP or if (right) Gravity.RIGHT else Gravity.LEFT

    private fun cardW() = if (full) minOf((W * 0.85f).toInt(), dp(360)) else (W * 0.85f).toInt()

    /** The banner lives in whichever of pill or card is showing, so alerts show in both views. */
    private fun showCard(on: Boolean) {
        (bannerRow.parent as? ViewGroup)?.removeView(bannerRow)
        if (on) card.addView(bannerRow, 0) else pill.addView(bannerRow, 0)
        pill.visibility = if (on) View.GONE else View.VISIBLE
        card.visibility = if (on) View.VISIBLE else View.GONE
    }

    /** Compact (pill, expands on tap, tucks away) or Full (the card, always). Re-run when the setting changes. */
    private fun applyMode() {
        full = prefs.view == "Full"
        expanded = false; tucked = false
        xSpring.cancel(); h.removeCallbacks(tuckR)
        showCard(full)
        scroll.scrollTo(0, 0)
        lp.width = if (full) cardW() else WRAP_CONTENT
        lp.gravity = side(); lp.x = 0
        root.alpha = 1f
        update(); render(); scheduleTuck()
    }

    // ---------- data + alerts ----------

    private fun soon() { h.removeCallbacks(reloadR); h.postDelayed(reloadR, 500) }

    /** Query the provider on [io], then render on the main thread. */
    private fun reload() {
        io.execute {
            val fresh = runCatching { Cal.events(this, prefs.chosenCalendars(this)) }.getOrNull()
            h.post {
                if (io.isShutdown) return@post   // service destroyed while the query ran
                if (fresh != null) events = fresh
                tick()
            }
        }
    }

    private fun tick() {
        h.removeCallbacks(tickR)
        val now = System.currentTimeMillis()
        for (a in tracker.due(events, now)) alert(a, now)
        banner?.let { if (it is Alert.Starting && now >= bannerUntil || it is Alert.Reminder && now >= it.ev.begin) banner = null }
        render()
        // Wake for the next start/end/reminder/heads-up edge, and at least every 30 s for the countdown.
        var next = now + 30_000
        Plan.nextMoment(events, now, prefs.headsUp)?.let { next = minOf(next, it) }
        if (banner is Alert.Starting) next = minOf(next, bannerUntil)
        h.postDelayed(tickR, (next - now).coerceAtLeast(500))
    }

    private fun alert(a: Alert, now: Long) {
        banner = a
        if (a is Alert.Starting) {
            bannerUntil = now + 20_000
            val sx = PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.04f)
            val sy = PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 1.04f)
            val al = PropertyValuesHolder.ofFloat(View.ALPHA, 1f, 0.7f)
            ObjectAnimator.ofPropertyValuesHolder(pill, sx, sy, al).apply {
                duration = 260; repeatCount = 3; repeatMode = ValueAnimator.REVERSE; start()   // two pulses
            }
        } else if (prefs.vibrate) {
            val v = if (Build.VERSION.SDK_INT >= 31) getSystemService(VibratorManager::class.java).defaultVibrator
            else @Suppress("DEPRECATION") getSystemService(Vibrator::class.java)
            val once = VibrationEffect.createOneShot(120, VibrationEffect.DEFAULT_AMPLITUDE)
            // Background apps only vibrate with a notification, alarm or ringtone usage (Vibrator docs).
            if (Build.VERSION.SDK_INT >= 33) v.vibrate(once, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_NOTIFICATION))
            else @Suppress("DEPRECATION") v.vibrate(once, AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION).build())
        }
    }

    private fun alerting() = banner != null || Plan.headsUp(events, System.currentTimeMillis(), prefs.headsUp)

    // ---------- render ----------

    private fun freeText(up: List<Ev>, now: Long) =
        up.firstOrNull()?.takeIf { day(it.begin) == day(now) }?.let { "Free until ${hm(it.begin)}" } ?: "Free"

    private fun whenText(at: Long, now: Long) = when {
        at - now < 12 * 60 * MIN -> "in ${dur(at - now)}"
        day(at) == day(now).plusDays(1) -> "tomorrow ${hm(at)}"
        else -> fmt(at, "EEE HH:mm")
    }

    private fun render() {
        val now = System.currentTimeMillis()
        val cur = Plan.current(events, now)
        val up = Plan.upcoming(events, now)
        val heads = Plan.headsUp(events, now, prefs.headsUp)
        val e = cur.firstOrNull()

        title.maxWidth = (W * 0.6f).toInt() - dp(110)
        when {
            !Cal.granted(this) -> { title.text = "Grant calendar access"; countdown.text = ""; dot.setColor(AMBER) }
            e != null -> { title.text = e.title; countdown.text = "${dur(e.end - now)} left"; dot.setColor(e.color or 0xFF000000.toInt()) }
            else -> {
                title.text = freeText(up, now)
                countdown.text = up.firstOrNull()?.takeIf { it.begin - now < 12 * 60 * MIN }?.let { "in ${dur(it.begin - now)}" } ?: ""
                dot.setColor(0x66FFFFFF)
            }
        }
        countdown.visibility = if (countdown.text.isEmpty()) View.GONE else View.VISIBLE
        countdown.setTextColor(if (heads) AMBER else 0xB3FFFFFF.toInt())
        bar.visibility = if (e != null) View.VISIBLE else View.GONE
        if (e != null) bar.set((now - e.begin).toFloat() / (e.end - e.begin), e.color or 0xFF000000.toInt())

        val b = banner
        bannerRow.visibility = if (b == null) View.GONE else View.VISIBLE
        bannerGlyph.visibility = if (b is Alert.Starting) View.VISIBLE else View.GONE
        bannerBell.visibility = if (b is Alert.Reminder) View.VISIBLE else View.GONE
        bannerText.maxWidth = title.maxWidth + dp(40)
        bannerText.text = when (b) {
            is Alert.Starting -> "Now: ${b.ev.title}"
            is Alert.Reminder -> "${b.ev.title} · ${whenText(b.ev.begin, now)}"
            null -> ""
        }
        val rim = when { b is Alert.Starting -> GREEN; b is Alert.Reminder -> BLUE; heads -> AMBER; else -> RIM }
        pillBg.setStroke(dp(1), rim); cardBg.setStroke(dp(1), rim)

        nextBox.removeAllViews()
        if (!full) Plan.next(events, now, prefs.nextCount).forEach { nextBox.addView(nextRow(it, now)) }

        if (alerting()) { h.removeCallbacks(tuckR); if (tucked) untuck() }
        else if (!tucked && !expanded && !touching) scheduleTuck()
        if (expanded || full) renderCard(now, cur, up)
    }

    /** One "Up next" line in the pill: dot, start time, title. */
    private fun nextRow(e: Ev, now: Long) = LinearLayout(ui).apply {
        gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(5), 0, 0)
        addView(View(context).apply { background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(e.color or 0xFF000000.toInt()) } },
            LinearLayout.LayoutParams(dp(6), dp(6)).apply { marginEnd = dp(8) })
        addView(ui.text(if (day(e.begin) == day(now)) hm(e.begin) else fmt(e.begin, "EEE HH:mm"), 12f, 0x99FFFFFF.toInt()),
            LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginEnd = dp(8) })
        addView(ui.text(e.title, 12.5f, Color.WHITE).apply { maxWidth = title.maxWidth })
    }

    private fun label(s: String, top: Int = 0) = ui.text(s, 11f, 0x8CFFFFFF.toInt()).apply {
        letterSpacing = 0.06f; setPadding(0, dp(top), 0, dp(2))
    }

    private fun row(e: Ev) = LinearLayout(ui).apply {
        gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(3), 0, dp(3))
        addView(View(context).apply { background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(e.color or 0xFF000000.toInt()) } },
            LinearLayout.LayoutParams(dp(8), dp(8)).apply { marginEnd = dp(8) })
        addView(ui.text(if (e.allDay) "all day" else hm(e.begin), 12f, 0x99FFFFFF.toInt()), LinearLayout.LayoutParams(dp(48), WRAP_CONTENT))
        addView(ui.text(e.title, 13f, Color.WHITE))
    }

    private fun nowCard(e: Ev, now: Long) = LinearLayout(ui).apply {
        orientation = LinearLayout.VERTICAL
        background = GradientDrawable().apply { cornerRadius = dp(8).toFloat(); setColor(CARD) }
        setPadding(dp(10), dp(7), dp(10), dp(9))
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = dp(6) }
        addView(ui.text(e.title, 17f, Color.WHITE, bold = true))
        addView(ui.text("until ${hm(e.end)}  ·  ${dur(e.end - now)} left", 12f, 0xB3FFFFFF.toInt()))
        addView(Bar(context).apply { set((now - e.begin).toFloat() / (e.end - e.begin), e.color or 0xFF000000.toInt()) },
            LinearLayout.LayoutParams(MATCH_PARENT, dp(3)).apply { topMargin = dp(6) })
    }

    /** All-day pill; multi-day ones say which day you're on ("Trip · 2/5"). */
    private fun chip(e: Ev, today: LocalDate) = LinearLayout(ui).apply {
        val days = ChronoUnit.DAYS.between(day(e.begin), day(e.end))
        val label = if (days > 1) "${e.title}  ·  ${ChronoUnit.DAYS.between(day(e.begin), today) + 1}/$days" else e.title
        gravity = Gravity.CENTER_VERTICAL
        background = GradientDrawable().apply { cornerRadius = dp(9).toFloat(); setColor(CARD) }
        setPadding(dp(8), dp(2), dp(9), dp(3))
        layoutParams = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginEnd = dp(5) }
        addView(View(context).apply { background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(e.color or 0xFF000000.toInt()) } },
            LinearLayout.LayoutParams(dp(6), dp(6)).apply { marginEnd = dp(6) })
        addView(ui.text(label, 11.5f, Color.WHITE))
    }

    private fun renderCard(now: Long, cur: List<Ev>, up: List<Ev>) {
        val today = day(now)
        clock.text = "${hm(now)}  ·  ${fmt(now, "EEE d MMM")}"
        body.removeAllViews()

        val allDay = events.filter { it.allDay && it.begin <= now && it.end > now }
        if (allDay.isNotEmpty()) body.addView(HorizontalScrollView(ui).apply {
            isHorizontalScrollBarEnabled = false; setPadding(0, 0, 0, dp(8))
            addView(LinearLayout(context).apply { allDay.forEach { addView(chip(it, today)) } })
        })

        cur.forEach { body.addView(nowCard(it, now)) }
        if (cur.isEmpty()) body.addView(ui.text(freeText(up, now), 15f, 0xCCFFFFFF.toInt()).apply { setPadding(0, 0, 0, dp(4)) })

        val next = Plan.next(events, now, prefs.nextCount)
        next.firstOrNull()?.let {
            val t = if (it.begin - now < 12 * 60 * MIN) "IN ${dur(it.begin - now)}" else fmt(it.begin, "EEE HH:mm")
            body.addView(label("NEXT  ·  ${t.uppercase()}", top = 4)); next.forEach { e -> body.addView(row(e)) }
        }

        val later = events.filter { it.allDay && day(it.begin) > today }
        val rest = (up.drop(next.size) + later).sortedWith(compareBy({ day(it.begin) }, { !it.allDay }, { it.begin })).take(20)
        var d = today
        for (e in rest) {
            if (day(e.begin) != d) {
                d = day(e.begin)
                body.addView(label(if (d == today.plusDays(1)) "TOMORROW" else fmt(e.begin, "EEEE d MMM").uppercase(), top = 10))
            }
            body.addView(row(e))
        }
        if (rest.isEmpty()) body.addView(ui.text("Nothing else coming up", 12f, 0x80FFFFFF.toInt()).apply { setPadding(0, dp(10), 0, 0) })
    }

    // ---------- movement ----------

    private fun update() { if (root.isAttachedToWindow) wm.updateViewLayout(root, lp) }

    /** Spring lp.x to [target]; retargeting mid-flight keeps the current velocity. */
    private fun animX(target: Int) {
        if (!xSpring.isRunning) xSpring.setStartValue(lp.x.toFloat())
        xSpring.animateToFinalPosition(target.toFloat())
    }

    private fun scheduleTuck() { h.removeCallbacks(tuckR); h.postDelayed(tuckR, 3000) }

    /** Compact: slide mostly off the edge, leaving a tab. Both views fade to the idle opacity. */
    private fun tuck() {
        if (expanded || touching || alerting()) return
        tucked = true
        if (!full) {
            // The tab must stick out past the back-gesture zone, or a swipe there goes to the system instead.
            val w = root.width
            val gesture = edges().let { if (right) it.right else it.left }
            animX(-(w - minOf(w, maxOf(dp(28), (w * 0.35f).toInt(), gesture + dp(20)))))
        }
        root.animate().alpha(prefs.idleOpacity / 100f).setDuration(300).start()
    }

    private fun untuck() {
        tucked = false
        if (!full) animX(0)
        root.animate().alpha(1f).setDuration(150).start()
    }

    /** Land on whichever edge is nearer. x is measured from that edge (gravity LEFT or RIGHT), so 0 = flush. */
    private fun snap() {
        val w = root.width
        val left = if (right) W - lp.x - w else lp.x
        val toRight = left + w / 2 > W / 2
        if (toRight != right) {
            right = toRight
            lp.gravity = side()
            lp.x = if (right) W - left - w else left
        }
        lp.y = clampY(lp.y, root.height)
        animX(0)
        prefs.y = lp.y; prefs.right = right
    }

    private fun expand() {
        expanded = true
        h.removeCallbacks(tuckR); xSpring.cancel()
        pillY = lp.y
        showCard(true)
        lp.width = (W * 0.85f).toInt(); lp.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL; lp.x = 0
        lp.y = minOf(lp.y, (H * 0.35f).toInt())
        root.alpha = 1f
        render(); update()
    }

    private fun collapse() {
        if (!expanded) return
        expanded = false
        showCard(false)
        scroll.scrollTo(0, 0)
        lp.width = WRAP_CONTENT; lp.gravity = side(); lp.x = 0; lp.y = pillY
        update(); render()
    }

    // ---------- touch ----------

    private val slop by lazy { ViewConfiguration.get(this).scaledTouchSlop }
    private var downX = 0f
    private var downY = 0f
    private var startX = 0
    private var startY = 0
    private var dragging = false
    private var touching = false
    private var longFired = false
    private var wasTucked = false
    private val longR = Runnable {
        longFired = true
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun onTouch(e: MotionEvent): Boolean {
        if (e.actionMasked == MotionEvent.ACTION_OUTSIDE) { collapse(); return false }
        if (expanded) return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                touching = true; dragging = false; longFired = false; wasTucked = tucked
                downX = e.rawX; downY = e.rawY
                h.removeCallbacks(tuckR)
                if (tucked) untuck()
                h.postDelayed(longR, ViewConfiguration.getLongPressTimeout().toLong())
            }
            MotionEvent.ACTION_MOVE -> {
                if (!dragging && !longFired && hypot(e.rawX - downX, e.rawY - downY) > slop) {
                    dragging = true; h.removeCallbacks(longR); xSpring.cancel()
                    startX = lp.x; startY = lp.y; downX = e.rawX; downY = e.rawY
                }
                if (dragging) {
                    val dx = (e.rawX - downX).toInt()
                    lp.x = startX + if (right) -dx else dx
                    lp.y = startY + (e.rawY - downY).toInt()
                    update()
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                touching = false
                h.removeCallbacks(longR)
                val tap = e.actionMasked == MotionEvent.ACTION_UP
                when {
                    dragging -> snap()
                    !tap || longFired || wasTucked -> {}
                    banner != null -> { root.performClick(); banner = null; render() }   // tap dismisses the alert
                    else -> { root.performClick(); if (!full) expand() }
                }
                if (!expanded) scheduleTuck()
            }
        }
        return true
    }
}
