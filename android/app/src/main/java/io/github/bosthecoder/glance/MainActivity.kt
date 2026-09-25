package io.github.bosthecoder.glance

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.Switch

/** Setup: permissions, which calendars, a few settings, and Start/Stop. Rebuilt on every resume. */
class MainActivity : Activity() {
    private lateinit var prefs: Prefs
    private lateinit var list: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val pad = dp(20)
        setContentView(ScrollView(this).apply {
            setBackgroundColor(0xFF0E0E10.toInt())
            addView(list)
            // targetSdk 35 draws edge to edge, so keep clear of the status and nav bars.
            @Suppress("DEPRECATION")
            setOnApplyWindowInsetsListener { v, i ->
                list.setPadding(pad, i.systemWindowInsetTop + pad, pad, i.systemWindowInsetBottom + pad); i
            }
        })
    }

    override fun onResume() { super.onResume(); build() }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) = build()

    private fun build() {
        list.removeAllViews()
        list.addView(text("Glance", 28f, Color.WHITE, bold = true))
        list.addView(text("What's on now and next, floating over your apps.", 14f, 0x99FFFFFF.toInt()).apply { maxLines = 3 })

        header("Permissions")
        permission("Calendar access", Cal.granted(this)) { requestPermissions(arrayOf(Manifest.permission.READ_CALENDAR), 1) }
        permission("Display over other apps", canOverlay(this)) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
        if (Build.VERSION.SDK_INT >= 33)
            permission("Notifications", canNotify(this)) { requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2) }

        header("Calendars")
        val cals = Cal.calendars(this)
        val chosen = prefs.chosenCalendars(this).toMutableSet()
        if (cals.isEmpty()) list.addView(text(if (Cal.granted(this)) "No calendars on this phone." else "Grant calendar access to pick calendars.", 14f, 0x99FFFFFF.toInt()))
        var account: String? = null
        for (c in cals) {
            if (c.account != account) { account = c.account; list.addView(text(c.account, 12f, 0x80FFFFFF.toInt()).apply { setPadding(0, dp(8), 0, 0) }) }
            list.addView(CheckBox(this).apply {
                text = c.name; setTextColor(Color.WHITE); textSize = 15f
                buttonTintList = ColorStateList.valueOf(c.color or 0xFF000000.toInt())
                isChecked = c.id in chosen
                setOnCheckedChangeListener { _, on -> if (on) chosen += c.id else chosen -= c.id; prefs.calendars = chosen.toSet() }
            })
        }

        header("Settings")
        choice("Idle opacity", listOf(25, 50, 75, 100), { "$it%" }, prefs.idleOpacity) { prefs.idleOpacity = it }
        choice("Heads-up before a change", listOf(2, 5, 10), { "$it min" }, prefs.headsUp) { prefs.headsUp = it }
        toggle("Vibrate on reminders", prefs.vibrate) { prefs.vibrate = it }
        toggle("Start when the phone boots", prefs.onBoot) { prefs.onBoot = it }

        list.addView(View(this), LinearLayout.LayoutParams(1, dp(20)))
        val running = OverlayService.running
        list.addView(button(if (running) "Stop floating widget" else "Start floating widget", if (running) 0xFF3A1F1F.toInt() else 0xFF1D4D3A.toInt()) {
            when {
                running -> OverlayService.stop(this)
                !canOverlay(this) -> startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                else -> OverlayService.start(this)
            }
            list.postDelayed({ build() }, 300)
        })
        list.addView(text("Tap the pill to expand it, drag it to move, long-press for this screen.", 12f, 0x80FFFFFF.toInt()).apply {
            maxLines = 3; setPadding(0, dp(10), 0, 0)
        })
    }

    private fun header(s: String) = list.addView(text(s.uppercase(), 12f, 0x8CFFFFFF.toInt(), bold = true).apply {
        letterSpacing = 0.08f; setPadding(0, dp(24), 0, dp(6))
    })

    private fun button(s: String, bg: Int, onClick: () -> Unit) = Button(this).apply {
        text = s; isAllCaps = false; setTextColor(Color.WHITE); textSize = 15f
        background = GradientDrawable().apply { cornerRadius = dp(12).toFloat(); setColor(bg) }
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, dp(48)).apply { bottomMargin = dp(8) }
        setOnClickListener { onClick() }
    }

    private fun permission(name: String, ok: Boolean, grant: () -> Unit) =
        list.addView(button(if (ok) "✓  $name" else "Grant: $name", if (ok) 0xFF1A1A1E.toInt() else 0xFF22305A.toInt()) { if (!ok) grant() })

    private fun choice(label: String, options: List<Int>, show: (Int) -> String, current: Int, pick: (Int) -> Unit) {
        list.addView(text(label, 14f, Color.WHITE).apply { setPadding(0, dp(8), 0, 0) })
        list.addView(RadioGroup(this).apply {
            orientation = RadioGroup.HORIZONTAL
            for (o in options) addView(RadioButton(context).apply {
                text = show(o); setTextColor(Color.WHITE); id = View.generateViewId()
                buttonTintList = ColorStateList.valueOf(BLUE)
                layoutParams = RadioGroup.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginEnd = dp(12) }
                isChecked = o == current
                setOnCheckedChangeListener { _, on -> if (on) pick(o) }
            })
        })
    }

    private fun toggle(label: String, on: Boolean, set: (Boolean) -> Unit) = list.addView(Switch(this).apply {
        text = label; setTextColor(Color.WHITE); textSize = 14f; isChecked = on; gravity = Gravity.CENTER_VERTICAL
        setPadding(0, dp(10), 0, dp(4))
        setOnCheckedChangeListener { _, v -> set(v) }
    })
}
