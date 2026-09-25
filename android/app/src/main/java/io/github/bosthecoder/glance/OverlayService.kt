package io.github.bosthecoder.glance

import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.app.PendingIntent
import android.app.Service
import android.content.ActivityNotFoundException
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.database.ContentObserver
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
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
import android.view.ScaleGestureDetector
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.graphics.Insets
import androidx.dynamicanimation.animation.FlingAnimation
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
const val DOCK_W = 112          // dp: the side strip's readable width, plus the gesture-zone margin on its edge side
const val DOCK_FLING = 800      // dp/s: a release faster than this is a throw, and docks

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

/** The card's resize grip: two short diagonal lines in its bottom corner, mirrored for a card on the left edge. */
class Grip(ctx: Context) : View(ctx) {
    var onLeftEdge = false; set(v) { field = v; invalidate() }
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x66FFFFFF; strokeWidth = ctx.dp(1.5f).toFloat(); strokeCap = Paint.Cap.ROUND }
    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat(); val inset = context.dp(5).toFloat()
        for (k in listOf(context.dp(8), context.dp(14))) {
            // Bottom-left corner (card on the right edge) by default; flip x for the bottom-right one.
            fun x(v: Float) = if (onLeftEdge) w - v else v
            c.drawLine(x(inset), h - inset - k, x(inset + k), h - inset, p)
        }
    }
}

/** Lays its children out left to right and wraps onto new lines, so every all-day chip shows without scrolling. */
class WrapLayout(ctx: Context, private val gapX: Int, private val gapY: Int) : ViewGroup(ctx) {
    /** Positions each visible child; returns the height used. Shared by measure and layout so they agree. */
    private fun flow(maxW: Int, place: Boolean): Int {
        var x = 0; var y = 0; var lineH = 0
        for (i in 0 until childCount) {
            val c = getChildAt(i).takeIf { it.visibility != GONE } ?: continue
            if (x > 0 && x + c.measuredWidth > maxW) { x = 0; y += lineH + gapY; lineH = 0 }
            if (place) c.layout(paddingLeft + x, paddingTop + y, paddingLeft + x + c.measuredWidth, paddingTop + y + c.measuredHeight)
            x += c.measuredWidth + gapX; lineH = maxOf(lineH, c.measuredHeight)
        }
        return y + lineH
    }

    override fun onMeasure(w: Int, h: Int) {
        val maxW = (if (MeasureSpec.getMode(w) == MeasureSpec.UNSPECIFIED) Int.MAX_VALUE else MeasureSpec.getSize(w)) - paddingLeft - paddingRight
        val childW = MeasureSpec.makeMeasureSpec(maxOf(0, maxW), MeasureSpec.AT_MOST)   // a chip wider than a line ellipsizes
        for (i in 0 until childCount) getChildAt(i).measure(childW, MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
        setMeasuredDimension(MeasureSpec.getSize(w), flow(maxW, false) + paddingTop + paddingBottom)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) { flow(r - l - paddingLeft - paddingRight, true) }
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
    private var comingAt = -1L      // the change time the heads-up banner last fired for: once per change
    private var expanded = false
    private var full = false        // View = Full: the card is always shown, never collapsed
    private var docked = false      // shrunk to the side strip on the [right] edge
    private var faded = false       // untouched for 3 s: at the idle (or side) opacity
    private var right = true
    private var pillY = 0
    private val xSpring by lazy {
        SpringAnimation(FloatValueHolder()).apply {
            spring = SpringForce()
            addUpdateListener { _, v, _ -> lp.x = v.roundToInt(); update() }
        }
    }
    /** Carries a throw's vertical momentum while docking; saves y where it stops. */
    private val yFling by lazy {
        FlingAnimation(FloatValueHolder()).apply {
            friction = 1.5f
            addUpdateListener { _, v, _ -> lp.y = v.roundToInt(); update() }
            addEndListener { _, _, _, _ -> prefs.y = lp.y }
        }
    }

    private lateinit var root: FrameLayout
    private lateinit var lp: WindowManager.LayoutParams
    private lateinit var pill: LinearLayout
    private lateinit var pillBg: GradientDrawable
    private lateinit var cardBg: GradientDrawable
    private lateinit var dock: LinearLayout
    private lateinit var grip: Grip
    private lateinit var dockBg: GradientDrawable
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
    private val fadeR = Runnable { fade() }
    private val reloadR = Runnable { reload() }
    private val observer = object : ContentObserver(h) {
        override fun onChange(selfChange: Boolean) = soon()   // sync arrives in bursts
    }
    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        when (key) {
            "y", "right", "docked" -> {}
            "cardWidth", "listHeight" -> if (!gripping) { scroll.maxH = listH(); showForm(); update() }
            "view" -> applyMode()
            "idleOpacity", "dockOpacity" -> if (faded) { faded = false; fade() }
            else -> soon()
        }
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
        if (::lp.isInitialized) { xSpring.cancel(); yFling.cancel() }
        contentResolver.unregisterContentObserver(observer)
        prefs.sp.unregisterOnSharedPreferenceChangeListener(prefListener)
        if (::root.isInitialized && root.isAttachedToWindow) wm.removeView(root)
        super.onDestroy()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (!::root.isInitialized) return
        if (expanded) collapse()
        scroll.maxH = listH()
        showForm()
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
            addView(bannerText, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { marginStart = dp(6) })
        }
        dot = GradientDrawable().apply { shape = GradientDrawable.OVAL }
        title = text("", 14f, Color.WHITE, bold = true)
        countdown = text("", 12f, 0xB3FFFFFF.toInt())
        val row = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(View(context).apply { background = dot }, LinearLayout.LayoutParams(dp(7), dp(7)).apply { marginEnd = dp(8) })
            addView(title, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
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
        scroll = MaxScroll(this, listH()).apply { isVerticalScrollBarEnabled = false; addView(body) }
        card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; background = cardBg; visibility = View.GONE
            setPadding(dp(16), dp(12), dp(16), dp(14))
            addView(clock, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)); addView(scroll)
        }

        dockBg = GradientDrawable().apply { setColor(GLASS); setStroke(dp(1), RIM) }
        dock = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; background = dockBg; visibility = View.GONE }
        grip = Grip(this).apply { visibility = View.GONE; setOnTouchListener { _, e -> resize(e) } }

        root = object : FrameLayout(this) {
            // Full view: any touch, even one the agenda scroll takes, wakes the card and restarts the idle timer.
            override fun dispatchTouchEvent(e: MotionEvent): Boolean {
                if (full && !docked) when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> { h.removeCallbacks(fadeR); if (faded) wake() }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> scheduleFade()
                }
                return super.dispatchTouchEvent(e)
            }
        }.apply {
            addView(pill, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            addView(card, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            addView(dock, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            addView(grip, FrameLayout.LayoutParams(dp(28), dp(28)))
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

    private fun cardW() = prefs.cardWidth.takeIf { it > 0 }?.let { dp(it).coerceIn(dp(220), maxOf(dp(220), maxCardW())) }
        ?: if (full) minOf((W * 0.85f).toInt(), dp(360)) else (W * 0.85f).toInt()
    /** The pill's width cap (titles ellipsize inside it): the pinch's, or 60% of the screen. */
    /** The pill's set width (from a pinch, live or saved), or null to size it to its content as before. */
    private fun pillW(): Int? = pinchW ?: prefs.pillWidth.takeIf { it > 0 }?.let { dp(it).coerceIn(dp(160), maxOf(dp(160), maxCardW())) }
    private fun maxCardW() = edges().let { W - it.left - it.right }
    /** The agenda's max height: the grip's, kept on screen, or 60% of the screen. */
    private fun listH() = prefs.listHeight.takeIf { it > 0 }?.let { dp(it).coerceIn(dp(120), maxOf(dp(120), edges().let { e -> H - e.top - e.bottom - dp(120) })) }
        ?: ((H * 0.6f).toInt() - dp(48))

    /** Side strip width: the readable part plus the back-gesture zone on its edge, so every touch on the text is ours. */
    private fun stripW(): Int { val e = edges(); return dp(DOCK_W) + if (right) e.right else e.left }

    /** Grip in the card's bottom corner on the side away from its edge. */
    private fun placeGrip() {
        grip.onLeftEdge = !right
        grip.layoutParams = (grip.layoutParams as FrameLayout.LayoutParams).apply { gravity = Gravity.BOTTOM or if (right) Gravity.LEFT else Gravity.RIGHT }
    }

    /** Show the pill, the card or the side strip. The alert banner lives in the pill or card, whichever is up. */
    private fun showForm() {
        val form = when { docked -> dock; full || expanded -> card; else -> pill }
        (bannerRow.parent as? ViewGroup)?.removeView(bannerRow)
        if (form === card) card.addView(bannerRow, 0) else pill.addView(bannerRow, 0)
        for (v in listOf(pill, card, dock)) v.visibility = if (v === form) View.VISIBLE else View.GONE
        lp.width = when { docked -> stripW(); expanded || full -> cardW(); else -> pillW() ?: WRAP_CONTENT }
        grip.visibility = if (form === card) View.VISIBLE else View.GONE
        placeGrip()
        if (docked) {
            // Flush to the edge: round only the inward corners, pad the edge side clear of the gesture zone.
            val r = dp(14).toFloat(); val gesture = lp.width - dp(DOCK_W)
            dockBg.cornerRadii = if (right) floatArrayOf(r, r, 0f, 0f, 0f, 0f, r, r) else floatArrayOf(0f, 0f, r, r, r, r, 0f, 0f)
            dock.setPadding(dp(10) + if (right) 0 else gesture, dp(8), dp(10) + if (right) gesture else 0, dp(9))
        }
    }

    /** Compact (pill, expands on tap) or Full (the card, always), docked or not. Re-run when the setting changes. */
    private fun applyMode() {
        full = prefs.view == "Full"; docked = prefs.docked
        expanded = false; faded = false
        xSpring.cancel(); yFling.cancel(); h.removeCallbacks(fadeR)
        showForm()
        scroll.scrollTo(0, 0)
        lp.gravity = side(); lp.x = 0
        root.alpha = 1f
        update(); render(); scheduleFade()
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
        Plan.coming(events, now, prefs.headsUp)?.let {
            if (it.at != comingAt) {
                comingAt = it.at
                // A blue reminder already on screen for the same event says it; just pulse and buzz.
                alert(it, now, show = (banner as? Alert.Reminder)?.ev != it.ev)
            }
        }
        banner?.let {
            if (it is Alert.Starting && now >= bannerUntil || it is Alert.Reminder && now >= it.ev.begin || it is Alert.Coming && now >= it.at) banner = null
        }
        render()
        // Wake for the next start/end/reminder/heads-up edge, and at least every 30 s for the countdown.
        var next = now + 30_000
        Plan.nextMoment(events, now, prefs.headsUp)?.let { next = minOf(next, it) }
        if (banner is Alert.Starting) next = minOf(next, bannerUntil)
        h.postDelayed(tickR, (next - now).coerceAtLeast(500))
    }

    private fun alert(a: Alert, now: Long, show: Boolean = true) {
        if (show) banner = a
        if (a is Alert.Starting) bannerUntil = now + 20_000
        if (a is Alert.Starting || a is Alert.Coming) {
            val sx = PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.04f)
            val sy = PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 1.04f)
            val al = PropertyValuesHolder.ofFloat(View.ALPHA, 1f, 0.7f)
            ObjectAnimator.ofPropertyValuesHolder(listOf(pill, card, dock).first { it.visibility == View.VISIBLE }, sx, sy, al).apply {
                duration = 260; repeatCount = if (a is Alert.Starting) 3 else 1; repeatMode = ValueAnimator.REVERSE; start()   // two pulses, or one
            }
        }
        if (a !is Alert.Starting && prefs.vibrate) {
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

        // A set pill width is the pill's real width: rows fill it and ellipsize. Auto-size caps titles at 60% instead.
        title.maxWidth = if (pillW() != null) Int.MAX_VALUE else (W * 0.6f).toInt() - dp(110)
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
        bannerBell.visibility = if (b is Alert.Reminder || b is Alert.Coming) View.VISIBLE else View.GONE
        bannerBell.setImageResource(if (b is Alert.Coming) R.drawable.ic_clock else R.drawable.ic_bell)
        bannerBell.setColorFilter(if (b is Alert.Coming) AMBER else BLUE)
        bannerText.maxWidth = if (pillW() != null) Int.MAX_VALUE else title.maxWidth + dp(40)
        bannerText.text = when (b) {
            is Alert.Starting -> "Now: ${b.ev.title}"
            is Alert.Reminder -> "${b.ev.title} · ${whenText(b.ev.begin, now)}"
            is Alert.Coming -> "${if (b.starting) "Next" else "Ending"}: ${b.ev.title} · in ${dur(b.at - now)}"
            null -> ""
        }
        val rim = when { b is Alert.Starting -> GREEN; b is Alert.Reminder -> BLUE; heads || b is Alert.Coming -> AMBER; else -> RIM }
        pillBg.setStroke(dp(1), rim); cardBg.setStroke(dp(1), rim); dockBg.setStroke(dp(1), rim)

        nextBox.removeAllViews()
        if (!full) Plan.next(events, now, pinchCount ?: prefs.nextCount).forEach { nextBox.addView(nextRow(it, now)) }

        // An alert brings it to full opacity (docked too: it stays docked); otherwise it fades after 3 s.
        if (alerting()) { h.removeCallbacks(fadeR); if (faded) wake() }
        else if (!faded && !expanded && !touching) scheduleFade()
        if (docked) renderDock(now, heads, b)
        else if (expanded || full) renderCard(now, cur, up)
    }

    /** Side strip: what's on now (title, time left, bar), then what's next, [Prefs.dockCount] rows in all. */
    private fun renderDock(now: Long, heads: Boolean, b: Alert?) {
        dock.removeAllViews()
        val rows = Plan.strip(events, now, prefs.dockCount)
        if (rows.isEmpty()) dock.addView(ui.text(if (Cal.granted(this)) "Free" else "No calendar access", 12.5f, 0xCCFFFFFF.toInt(), bold = true))
        rows.forEachIndexed { i, e ->
            val on = e.begin <= now
            val starts = if (e.begin - now < 12 * 60 * MIN) "in ${dur(e.begin - now)}" else fmt(e.begin, "EEE HH:mm")
            var sub = if (on) "${dur(e.end - now)} left" else "$starts · ${dur(e.end - e.begin)}"
            var subColor = if (heads && i == 0) AMBER else 0xB3FFFFFF.toInt()
            if (b?.ev == e) when (b) {   // the alert's own row says so, in the alert colour
                is Alert.Starting -> { sub = "▶ Now"; subColor = GREEN }
                is Alert.Reminder -> { sub = "🔔 ${whenText(e.begin, now)}"; subColor = BLUE }
                is Alert.Coming -> subColor = AMBER
            }
            dock.addView(ui.text(e.title, if (on) 12.5f else 12f, if (on) Color.WHITE else 0xE6FFFFFF.toInt(), bold = on).apply {
                if (i > 0) setPadding(0, dp(6), 0, 0)
            })
            dock.addView(ui.text(sub, 11f, subColor))
            if (on) dock.addView(Bar(ui).apply { set((now - e.begin).toFloat() / (e.end - e.begin), e.color or 0xFF000000.toInt()) },
                LinearLayout.LayoutParams(MATCH_PARENT, dp(3)).apply { topMargin = dp(4) })
        }
    }

    /** One "Up next" line in the pill: dot, start time, title. */
    /** Pressed-state feedback for a tappable event, clipped to a rounded rect. */
    private fun ripple(radiusDp: Int = 6) = RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), null,
        GradientDrawable().apply { cornerRadius = dp(radiusDp).toFloat(); setColor(Color.WHITE) })

    /**
     * Opens the event in the calendar app (Calendar Provider's documented ACTION_VIEW on the event's URI; the
     * begin/end extras pick the occurrence of a repeating one). Allowed from the overlay: Glance holds SYSTEM_ALERT_WINDOW.
     */
    private fun openEvent(e: Ev) {
        // All-day times were moved to local midnight for display; the provider keeps them at UTC midnight.
        fun t(ms: Long) = if (e.allDay) day(ms).atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli() else ms
        val i = Intent(Intent.ACTION_VIEW, ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, e.eventId))
            .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, t(e.begin))
            .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, t(e.end))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            startActivity(i)
            collapse()   // Compact: don't leave the card over the event
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(ui, "No calendar app to open this event", Toast.LENGTH_SHORT).show()
        }
    }

    private fun nextRow(e: Ev, now: Long) = LinearLayout(ui).apply {
        gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(5), 0, 0)
        addView(View(context).apply { background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(e.color or 0xFF000000.toInt()) } },
            LinearLayout.LayoutParams(dp(6), dp(6)).apply { marginEnd = dp(8) })
        addView(ui.text(if (day(e.begin) == day(now)) hm(e.begin) else fmt(e.begin, "EEE HH:mm"), 12f, 0x99FFFFFF.toInt()),
            LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginEnd = dp(8) })
        addView(ui.text(e.title, 12.5f, Color.WHITE).apply { maxWidth = title.maxWidth }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        addView(length(e))
    }

    /** Muted "30m" / "1h 15m" at the end of an upcoming row. The title before it takes weight 1, so it ellipsizes first. */
    private fun length(e: Ev) = ui.text(dur(e.end - e.begin), 12f, 0x80FFFFFF.toInt()).apply {
        layoutParams = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = dp(8) }
    }

    private fun label(s: String, top: Int = 0) = ui.text(s, 11f, 0x8CFFFFFF.toInt()).apply {
        letterSpacing = 0.06f; setPadding(0, dp(top), 0, dp(2))
    }

    private fun row(e: Ev) = LinearLayout(ui).apply {
        gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(3), 0, dp(3))
        background = ripple(); setOnClickListener { openEvent(e) }
        addView(View(context).apply { background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(e.color or 0xFF000000.toInt()) } },
            LinearLayout.LayoutParams(dp(8), dp(8)).apply { marginEnd = dp(8) })
        addView(ui.text(if (e.allDay) "all day" else hm(e.begin), 12f, 0x99FFFFFF.toInt()), LinearLayout.LayoutParams(dp(48), WRAP_CONTENT))
        addView(ui.text(e.title, 13f, Color.WHITE), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        if (!e.allDay) addView(length(e))
    }

    private fun nowCard(e: Ev, now: Long) = LinearLayout(ui).apply {
        orientation = LinearLayout.VERTICAL
        background = GradientDrawable().apply { cornerRadius = dp(8).toFloat(); setColor(CARD) }
        setPadding(dp(10), dp(7), dp(10), dp(9))
        foreground = ripple(8); setOnClickListener { openEvent(e) }
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
        addView(View(context).apply { background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(e.color or 0xFF000000.toInt()) } },
            LinearLayout.LayoutParams(dp(6), dp(6)).apply { marginEnd = dp(6) })
        addView(ui.text(label, 11.5f, Color.WHITE), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
    }

    private fun renderCard(now: Long, cur: List<Ev>, up: List<Ev>) {
        val today = day(now)
        clock.text = "${hm(now)}  ·  ${fmt(now, "EEE d MMM")}"
        body.removeAllViews()

        val allDay = events.filter { it.allDay && it.begin <= now && it.end > now }
        if (allDay.isNotEmpty()) body.addView(WrapLayout(ui, dp(5), dp(5)).apply {
            setPadding(0, 0, 0, dp(8))
            allDay.forEach { addView(chip(it, today), ViewGroup.LayoutParams(WRAP_CONTENT, WRAP_CONTENT)) }
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

    /**
     * Spring lp.x to [target]; retargeting mid-flight keeps the current velocity. [velocity] (px/s, in lp.x's
     * direction) starts it with a throw's momentum; [gentle] is the slow, no-bounce glide used for docking.
     */
    private fun animX(target: Int, velocity: Float = 0f, gentle: Boolean = false) {
        xSpring.spring.setStiffness(if (gentle) SpringForce.STIFFNESS_LOW else SpringForce.STIFFNESS_MEDIUM)
            .setDampingRatio(if (gentle) SpringForce.DAMPING_RATIO_NO_BOUNCY else SpringForce.DAMPING_RATIO_LOW_BOUNCY)
        if (!xSpring.isRunning) xSpring.setStartValue(lp.x.toFloat()).setStartVelocity(velocity)
        xSpring.animateToFinalPosition(target.toFloat())
    }

    private fun scheduleFade() { h.removeCallbacks(fadeR); h.postDelayed(fadeR, 3000) }

    /** Untouched: fade to the idle opacity, or the side opacity when docked. It never moves on its own. */
    private fun fade() {
        if (expanded || touching || alerting()) return
        faded = true
        root.animate().alpha((if (docked) prefs.dockOpacity else prefs.idleOpacity) / 100f).setDuration(300).start()
    }

    private fun wake() {
        faded = false
        root.animate().alpha(1f).setDuration(150).start()
    }

    /** Shrink to the side strip on [toRight]'s edge and glide there, carrying the throw's momentum. */
    private fun dock(toRight: Boolean, vx: Float, vy: Float) {
        val w = root.width
        val left = if (right) W - lp.x - w else lp.x
        right = toRight; docked = true
        prefs.right = right; prefs.docked = true
        showForm(); lp.gravity = side()
        lp.x = if (right) W - left - w else left   // how far the released view was from the dock edge
        render()
        dock.measure(View.MeasureSpec.makeMeasureSpec(lp.width, View.MeasureSpec.EXACTLY), View.MeasureSpec.UNSPECIFIED)
        val maxY = clampY(Int.MAX_VALUE, dock.measuredHeight)
        lp.y = lp.y.coerceIn(0, maxY)
        update()
        animX(0, velocity = if (right) -vx else vx, gentle = true)
        yFling.cancel()
        yFling.setMinValue(0f).setMaxValue(maxY.toFloat()).setStartValue(lp.y.toFloat()).setStartVelocity(vy).start()
        fade()   // side opacity straight away, unless an alert is showing
    }

    /** Back to the pill (Compact) or card (Full) on the same side and at the same y. [spring]: pop out from the edge. */
    private fun undock(spring: Boolean) {
        docked = false; prefs.docked = false
        xSpring.cancel(); yFling.cancel()
        showForm(); lp.gravity = side()
        lp.x = if (spring) -dp(DOCK_W) else 0
        wake(); render(); update()
        if (spring) animX(0)
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
            placeGrip()
        }
        lp.y = clampY(lp.y, root.height)
        animX(0)
        prefs.y = lp.y; prefs.right = right
    }

    private fun expand() {
        expanded = true
        h.removeCallbacks(fadeR); xSpring.cancel()
        pillY = lp.y
        showForm()
        lp.gravity = side(); lp.x = 0   // on the pill's edge, so the grip has an inward side
        lp.y = minOf(lp.y, (H * 0.35f).toInt())
        root.alpha = 1f
        render(); update()
    }

    private fun collapse() {
        if (!expanded) return
        expanded = false
        showForm()
        scroll.scrollTo(0, 0)
        lp.gravity = side(); lp.x = 0; lp.y = pillY
        update(); render()
    }

    // ---------- resize ----------

    private var gripping = false
    private var gripX = 0f
    private var gripY = 0f
    private var gripW = 0
    private var gripH = 0

    /**
     * The corner grip: inward = wider, down = taller list. The card stays on its edge (x is measured from it).
     * Starts from the card's actual size and saves dp on release. Consumes every touch, so no drag or collapse.
     */
    private fun resize(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                gripping = true; gripX = e.rawX; gripY = e.rawY; gripW = root.width; gripH = scroll.height
                h.removeCallbacks(fadeR); xSpring.cancel()
            }
            MotionEvent.ACTION_MOVE -> {
                val inward = (if (right) gripX - e.rawX else e.rawX - gripX).toInt()
                lp.width = (gripW + inward).coerceIn(dp(220), maxOf(dp(220), maxCardW()))
                val room = edges().let { H - it.top - it.bottom } - lp.y - (root.height - scroll.height)   // keeps the card on screen
                scroll.maxH = (gripH + (e.rawY - gripY).toInt()).coerceIn(dp(120), maxOf(dp(120), room))
                scroll.requestLayout(); update()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val d = resources.displayMetrics.density
                prefs.cardWidth = (lp.width / d).roundToInt(); prefs.listHeight = (scroll.maxH / d).roundToInt()
                gripping = false
                if (!expanded) scheduleFade()
            }
        }
        return true
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
    private var velocity: VelocityTracker? = null
    private var lastMove = 0L
    private val flingV by lazy { dp(DOCK_FLING).toFloat() }

    private val longR = Runnable {
        longFired = true
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** VelocityTracker in screen coordinates: the window itself moves under the finger. */
    private fun track(e: MotionEvent) {
        val c = MotionEvent.obtain(e); c.setLocation(e.rawX, e.rawY); velocity?.addMovement(c); c.recycle()
    }

    // ---------- pinch (the pill) ----------

    private var pinched = false          // this touch became a pinch: no drag, tap, throw or snap until every finger is up
    private var pinchW: Int? = null      // live values while pinching; saved to prefs when it ends
    private var pinchCount: Int? = null
    private var pinchStartW = 0
    private var pinchStartCount = 1
    private var spanX0 = 0f
    private var spanY0 = 0f

    /** Pinch the pill: spreading sideways widens it, spreading up/down adds an Up next row per row height. */
    private val pinch by lazy {
        ScaleGestureDetector(ui, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(d: ScaleGestureDetector): Boolean {
                pinched = true; dragging = false
                h.removeCallbacks(longR); xSpring.cancel(); yFling.cancel()
                spanX0 = d.currentSpanX; spanY0 = d.currentSpanY
                pinchStartW = root.width; pinchStartCount = prefs.nextCount   // from the pill's actual width
                return true
            }
            override fun onScale(d: ScaleGestureDetector): Boolean {
                pinchW = (pinchStartW + (d.currentSpanX - spanX0).toInt()).coerceIn(dp(160), maxOf(dp(160), maxCardW()))
                lp.width = pinchW!!; update()
                pinchCount = (pinchStartCount + ((d.currentSpanY - spanY0) / dp(22)).toInt()).coerceIn(1, 7)
                render()
                return true
            }
            override fun onScaleEnd(d: ScaleGestureDetector) {
                pinchW?.let { prefs.pillWidth = (it / resources.displayMetrics.density).roundToInt() }
                pinchCount?.let { prefs.nextCount = it }
                pinchW = null; pinchCount = null
            }
        }).apply { isQuickScaleEnabled = false }   // double-tap-and-drag would fight tap and drag
    }

    private fun onTouch(e: MotionEvent): Boolean {
        if (e.actionMasked == MotionEvent.ACTION_OUTSIDE) { collapse(); return false }
        if (expanded) return false
        if (!docked && !full) pinch.onTouchEvent(e)
        if (pinched) {
            if (e.actionMasked == MotionEvent.ACTION_UP || e.actionMasked == MotionEvent.ACTION_CANCEL) {
                pinched = false; touching = false
                velocity?.recycle(); velocity = null
                snap()                          // in case a drag moved it before the second finger landed
                scheduleFade()
            }
            return true
        }
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                touching = true; dragging = false; longFired = false; pinched = false
                downX = e.rawX; downY = e.rawY
                velocity?.recycle(); velocity = VelocityTracker.obtain(); track(e)
                h.removeCallbacks(fadeR)
                if (faded) wake()
                h.postDelayed(longR, ViewConfiguration.getLongPressTimeout().toLong())
            }
            MotionEvent.ACTION_MOVE -> {
                track(e); lastMove = e.eventTime
                if (!dragging && !longFired && hypot(e.rawX - downX, e.rawY - downY) > slop) {
                    dragging = true; h.removeCallbacks(longR); xSpring.cancel(); yFling.cancel()
                    startX = lp.x; startY = lp.y; downX = e.rawX; downY = e.rawY
                }
                // Docked, a drag slides the strip along its edge until it's pulled inward: then it undocks and follows.
                if (dragging && docked && (if (right) downX - e.rawX else e.rawX - downX) > dp(24)) {
                    undock(spring = false)
                    startX = lp.x; downX = e.rawX
                }
                if (dragging) {
                    val dx = (e.rawX - downX).toInt()
                    if (!docked) lp.x = startX + if (right) -dx else dx
                    lp.y = startY + (e.rawY - downY).toInt()
                    update()
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                touching = false
                h.removeCallbacks(longR)
                val tap = e.actionMasked == MotionEvent.ACTION_UP
                // Velocity from the MOVE samples (the docs say it reads 0 once UP is added); a pause before lifting is no throw.
                var vx = 0f; var vy = 0f
                velocity?.let {
                    if (tap && e.eventTime - lastMove < 100) {
                        it.computeCurrentVelocity(1000, ViewConfiguration.get(this).scaledMaximumFlingVelocity.toFloat())
                        vx = it.xVelocity; vy = it.yVelocity
                    }
                    it.recycle()
                }
                velocity = null
                when {
                    dragging && docked -> { lp.y = clampY(lp.y, root.height); update(); prefs.y = lp.y }
                    dragging -> {
                        val w = root.width
                        val to = dockSide(if (right) W - lp.x - w else lp.x, w, W, vx, flingV)
                        if (to != null) dock(to == Side.RIGHT, vx, vy) else snap()
                    }
                    !tap || longFired -> {}
                    docked -> { root.performClick(); undock(spring = true) }   // tap the strip: back out
                    banner != null -> { root.performClick(); banner = null; render() }   // tap dismisses the alert
                    else -> { root.performClick(); if (!full) expand() }
                }
                if (!expanded && !faded) scheduleFade()
            }
        }
        return true
    }
}
