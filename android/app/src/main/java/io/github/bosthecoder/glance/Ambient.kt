package io.github.bosthecoder.glance

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.service.dreams.DreamService
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlin.concurrent.thread
import kotlin.random.Random

/**
 * Glance as the whole screen: a big clock, what's on now and next, then the rest of today and tomorrow. Black, so it
 * suits an OLED left on. Side by side in landscape (a phone on a stand), stacked in portrait. It reads the calendar
 * once a minute, on the minute, and drifts a few dp each time so nothing burns in.
 * Shown by [AmbientScreen] (opened from the app) and [AmbientDream] (Android's screen saver, while charging).
 */
class AmbientView(ctx: Context) : FrameLayout(ctx) {
    private val prefs = Prefs(ctx)
    private var events = emptyList<Ev>()
    private val clockBox = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
    private val list = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
    private val page = LinearLayout(ctx).apply { addView(clockBox); addView(list) }
    private val tickR = Runnable { tick() }
    private var wide = false

    init {
        setBackgroundColor(Color.BLACK)
        val pad = ctx.dp(32)
        setPadding(pad, pad, pad, pad)
        clipToPadding = false   // the burn-in drift moves it into the padding
        addView(page, LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.CENTER_VERTICAL))
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); tick() }
    override fun onDetachedFromWindow() { removeCallbacks(tickR); super.onDetachedFromWindow() }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        wide = w > h
        page.orientation = if (wide) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
        clockBox.layoutParams = LinearLayout.LayoutParams(if (wide) 0 else MATCH_PARENT, WRAP_CONTENT, if (wide) 1f else 0f).apply {
            gravity = Gravity.CENTER_VERTICAL; bottomMargin = if (wide) 0 else context.dp(28)
        }
        list.layoutParams = LinearLayout.LayoutParams(if (wide) 0 else MATCH_PARENT, WRAP_CONTENT, if (wide) 1.2f else 0f).apply { gravity = Gravity.CENTER_VERTICAL }
        post { render() }
    }

    private fun tick() {
        thread {
            val fresh = runCatching { Cal.events(context, prefs.chosenCalendars(context)) }.getOrNull()
            post { if (fresh != null) events = fresh; render() }
        }
        val d = context.dp(10)
        page.translationX = Random.nextInt(-d, d + 1).toFloat(); page.translationY = Random.nextInt(-d, d + 1).toFloat()
        val now = System.currentTimeMillis()
        postDelayed(tickR, 60_000 - now % 60_000 + 200)   // just after the minute turns
    }

    private fun text(s: String, sp: Float, color: Int, bold: Boolean = false, light: Boolean = false) = context.text(s, sp, color, bold).apply {
        if (light) typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
    }

    private fun dot(color: Int) = View(context).apply { background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color or 0xFF000000.toInt()) } }

    private fun render() {
        val now = System.currentTimeMillis()
        val today = day(now)
        clockBox.removeAllViews(); list.removeAllViews()
        clockBox.addView(text(hm(now), 96f, Color.WHITE, light = true).apply { includeFontPadding = false })
        clockBox.addView(text(fmt(now, "EEEE d MMMM"), 20f, 0x99FFFFFF.toInt()))
        events.filter { it.allDay && it.begin <= now && it.end > now }.forEach {
            clockBox.addView(LinearLayout(context).apply {
                gravity = Gravity.CENTER_VERTICAL; setPadding(0, context.dp(8), 0, 0)
                addView(dot(it.color), LinearLayout.LayoutParams(context.dp(8), context.dp(8)).apply { marginEnd = context.dp(8) })
                addView(text(it.title, 16f, 0xCCFFFFFF.toInt()))
            })
        }

        val cur = Plan.current(events, now)
        val up = Plan.upcoming(events, now)
        cur.forEach { e ->
            list.addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                background = GradientDrawable().apply { cornerRadius = context.dp(12).toFloat(); setColor(CARD) }
                setPadding(context.dp(16), context.dp(12), context.dp(16), context.dp(14))
                addView(text(e.title, 26f, Color.WHITE, bold = true))
                addView(text("until ${hm(e.end)}  ·  ${dur(e.end - now)} left", 16f, 0xB3FFFFFF.toInt()))
                addView(Bar(context).apply { set((now - e.begin).toFloat() / (e.end - e.begin), e.color or 0xFF000000.toInt()) },
                    LinearLayout.LayoutParams(MATCH_PARENT, context.dp(4)).apply { topMargin = context.dp(10) })
            }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = context.dp(12) })
        }
        if (cur.isEmpty()) list.addView(text(up.firstOrNull()?.takeIf { day(it.begin) == today }?.let { "Free until ${hm(it.begin)}" } ?: "Free", 26f, 0xCCFFFFFF.toInt()).apply {
            setPadding(0, 0, 0, context.dp(12))
        })

        // Next, then the rest of today and tomorrow, as many as sit comfortably on a phone.
        val heads = Plan.headsUp(events, now, prefs.headsUp)
        var d = today
        up.filter { day(it.begin) <= today.plusDays(1) }.take(if (wide) 4 else 6).forEachIndexed { i, e ->
            if (i == 0) list.addView(text(if (e.begin - now < 12 * 60 * MIN) "NEXT  ·  IN ${dur(e.begin - now).uppercase()}" else "NEXT", 14f,
                if (heads) AMBER else 0x8CFFFFFF.toInt(), bold = heads))
            else if (day(e.begin) != d) list.addView(text("TOMORROW", 14f, 0x8CFFFFFF.toInt()).apply { setPadding(0, context.dp(12), 0, 0) })
            d = day(e.begin)
            list.addView(LinearLayout(context).apply {
                gravity = Gravity.CENTER_VERTICAL; setPadding(0, context.dp(5), 0, context.dp(5))
                addView(dot(e.color), LinearLayout.LayoutParams(context.dp(10), context.dp(10)).apply { marginEnd = context.dp(12) })
                addView(text(hm(e.begin), 18f, 0x99FFFFFF.toInt()), LinearLayout.LayoutParams(context.dp(64), WRAP_CONTENT))
                addView(text(e.title, if (i == 0) 22f else 18f, Color.WHITE, bold = i == 0), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
                addView(text(dur(e.end - e.begin), 14f, 0x80FFFFFF.toInt()))
            })
        }
    }
}

/** The full screen from the app (Settings → Full screen). Keeps the screen on; back closes it. */
class AmbientScreen : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(AmbientView(this))
        WindowCompat.getInsetsController(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }
    // The floating pill would sit on top of it.
    override fun onStart() { super.onStart(); OverlayService.hide(true) }
    override fun onStop() { OverlayService.hide(false); super.onStop() }
}

/** Android's screen saver (Settings → Display → Screen saver → Glance): the same page while charging or docked. */
class AmbientDream : DreamService() {
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        isInteractive = false
        isFullscreen = true
        setContentView(AmbientView(this))
    }
}
