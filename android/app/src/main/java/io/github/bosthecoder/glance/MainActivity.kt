package io.github.bosthecoder.glance

import android.Manifest
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.CheckBox
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.Switch
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.net.toUri
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import kotlin.concurrent.thread

/** Setup: permissions, which calendars, a few settings, and Start/Stop. Rebuilt on every resume. */
class MainActivity : ComponentActivity() {
    private lateinit var prefs: Prefs
    private lateinit var list: LinearLayout
    private val askPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { build() }
    private val overlaySettings = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { build() }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Dark page, so light status/nav bar icons whatever the system theme.
        enableEdgeToEdge(SystemBarStyle.dark(Color.TRANSPARENT), SystemBarStyle.dark(Color.TRANSPARENT))
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val pad = dp(20)
        val page = ScrollView(this).apply { setBackgroundColor(0xFF0E0E10.toInt()); addView(list) }
        // Edge to edge: keep the content clear of the status bar, nav bar and any camera cutout.
        ViewCompat.setOnApplyWindowInsetsListener(page) { _, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            list.setPadding(b.left + pad, b.top + pad, b.right + pad, b.bottom + pad)
            WindowInsetsCompat.CONSUMED
        }
        setContentView(page)
    }

    override fun onResume() { super.onResume(); build() }

    private fun openOverlaySettings() =
        overlaySettings.launch(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, "package:$packageName".toUri()))

    private fun build() {
        list.removeAllViews()
        list.addView(text("Glance", 28f, Color.WHITE, bold = true))
        list.addView(text("What's on now and next, floating over your apps.", 14f, 0x99FFFFFF.toInt()).apply { maxLines = 3 })

        header("Permissions")
        permission("Calendar access", Cal.granted(this)) { askPermission.launch(Manifest.permission.READ_CALENDAR) }
        permission("Display over other apps", canOverlay(this)) { openOverlaySettings() }
        if (Build.VERSION.SDK_INT >= 33)
            permission("Notifications", canNotify(this)) { askPermission.launch(Manifest.permission.POST_NOTIFICATIONS) }

        header("Calendars")
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        list.addView(box)
        thread {   // provider query: keep it off the main thread
            val cals = Cal.calendars(this)
            val chosen = prefs.chosenCalendars(this).toMutableSet()
            runOnUiThread { calendars(box, cals, chosen) }
        }

        header("Settings")
        choice("View", listOf("Compact", "Full"), { it }, prefs.view) { prefs.view = it }
        choice("Up next", (1..7).toList(), { "$it" }, prefs.nextCount) { prefs.nextCount = it }
        choice("Items at the side", (1..5).toList(), { "$it" }, prefs.dockCount) { prefs.dockCount = it }
        choice("Opacity at the side", listOf(25, 50, 75, 100), { "$it%" }, prefs.dockOpacity) { prefs.dockOpacity = it }
        choice("Idle opacity", listOf(25, 50, 75, 100), { "$it%" }, prefs.idleOpacity) { prefs.idleOpacity = it }
        choice("Heads-up before a change", listOf(2, 5, 10), { "$it min" }, prefs.headsUp) { prefs.headsUp = it }
        toggle("Vibrate on reminders", prefs.vibrate) { prefs.vibrate = it }
        toggle("Start when the phone boots", prefs.onBoot) { prefs.onBoot = it }
        list.addView(button("Reset size", 0xFF1A1A1E.toInt()) { prefs.resetSize() }.apply {
            (layoutParams as LinearLayout.LayoutParams).topMargin = dp(10)
        })

        list.addView(View(this), LinearLayout.LayoutParams(1, dp(20)))
        val running = OverlayService.running
        list.addView(button(if (running) "Stop floating widget" else "Start floating widget", if (running) 0xFF3A1F1F.toInt() else 0xFF1D4D3A.toInt()) {
            when {
                running -> OverlayService.stop(this)
                !canOverlay(this) -> openOverlaySettings()
                else -> OverlayService.start(this)
            }
            list.postDelayed({ build() }, 300)
        })
        list.addView(text("Tap the pill to expand it (Full view: drag the card by its clock). Throw it at an edge to dock it as a side strip, tap the strip to bring it back. Long-press for this screen.", 12f, 0x80FFFFFF.toInt()).apply {
            maxLines = 3; setPadding(0, dp(10), 0, 0)
        })
    }

    private fun calendars(box: LinearLayout, cals: List<Cal.Info>, chosen: MutableSet<Long>) {
        if (cals.isEmpty()) box.addView(text(if (Cal.granted(this)) "No calendars on this phone." else "Grant calendar access to pick calendars.", 14f, 0x99FFFFFF.toInt()))
        var account: String? = null
        for (c in cals) {
            if (c.account != account) { account = c.account; box.addView(text(c.account, 12f, 0x80FFFFFF.toInt()).apply { setPadding(0, dp(8), 0, 0) }) }
            box.addView(CheckBox(this).apply {
                text = c.name; setTextColor(Color.WHITE); textSize = 15f
                buttonTintList = ColorStateList.valueOf(c.color or 0xFF000000.toInt())
                isChecked = c.id in chosen
                setOnCheckedChangeListener { _, on -> if (on) chosen += c.id else chosen -= c.id; prefs.calendars = chosen.toSet() }
            })
        }
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

    private fun <T> choice(label: String, options: List<T>, show: (T) -> String, current: T, pick: (T) -> Unit) {
        list.addView(text(label, 14f, Color.WHITE).apply { setPadding(0, dp(8), 0, 0) })
        list.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }.also { it.addView(RadioGroup(this).apply {
            orientation = RadioGroup.HORIZONTAL
            for (o in options) addView(RadioButton(context).apply {
                text = show(o); setTextColor(Color.WHITE); id = View.generateViewId()
                buttonTintList = ColorStateList.valueOf(BLUE)
                layoutParams = RadioGroup.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginEnd = dp(12) }
                isChecked = o == current
                setOnCheckedChangeListener { _, on -> if (on) pick(o) }
            })
        }) })
    }

    private fun toggle(label: String, on: Boolean, set: (Boolean) -> Unit) = list.addView(Switch(this).apply {
        text = label; setTextColor(Color.WHITE); textSize = 14f; isChecked = on; gravity = Gravity.CENTER_VERTICAL
        setPadding(0, dp(10), 0, dp(4))
        setOnCheckedChangeListener { _, v -> set(v) }
    })
}
