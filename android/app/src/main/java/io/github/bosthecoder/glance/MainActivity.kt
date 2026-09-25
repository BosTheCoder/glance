package io.github.bosthecoder.glance

import android.Manifest
import android.content.Intent
import android.content.pm.PackageInstaller
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
import androidx.core.content.IntentCompat
import androidx.core.net.toUri
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import kotlin.concurrent.thread

/** Setup: permissions, which calendars, a few settings, and Start/Stop. Rebuilt on every resume. */
class MainActivity : ComponentActivity() {
    private lateinit var prefs: Prefs
    private lateinit var list: LinearLayout
    private val askPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { build() }
    /** Location for travel times; a running widget restarts its foreground service to pick up the location type. */
    private val askLocation = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (OverlayService.running && canLocate(this)) OverlayService.start(this)
        build()
    }
    private val overlaySettings = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { build() }
    /** Back from "Install unknown apps": carry on with the update if it's now allowed. */
    private val unknownSources = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        build(); release?.let { if (packageManager.canRequestPackageInstalls()) install(it) }
    }

    private var release: Update.Release? = null   // a newer release with an APK, once a check finds one
    private var updateMsg: String? = null
    private var updating = false
    private lateinit var updateBox: LinearLayout

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
        installStatus(intent)
        check(quiet = true)   // so the button already says "Update to vX" if there is one
    }

    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); installStatus(intent) }

    /** The install session's result (see [Update.install]). */
    private fun installStatus(intent: Intent?) {
        if (intent?.action != Update.ACTION) return
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            // Android wants the user to confirm; we're on screen, so show its dialog now.
            PackageInstaller.STATUS_PENDING_USER_ACTION ->
                IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java)?.let { startActivity(it) }
            PackageInstaller.STATUS_SUCCESS -> updateMsg = "Updated. Open Glance and tap Start if the widget has gone."
            else -> updateMsg = "Update didn't install: ${intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "unknown reason"}"
        }
        if (::updateBox.isInitialized) renderUpdate()
    }

    /** Ask GitHub for the latest release. [quiet]: only speak up if there's an update. */
    private fun check(quiet: Boolean) {
        if (!quiet) { updateMsg = "Checking…"; renderUpdate() }
        thread {
            val result = runCatching { Update.latest() }
            runOnUiThread {
                val mine = Update.version(this)
                result.onSuccess { r ->
                    when {
                        isNewer(r.tag, mine) && r.apk != null -> { release = r; updateMsg = null }
                        isNewer(r.tag, mine) -> if (!quiet) updateMsg = "${r.tag} is out, but its release has no Glance.apk"
                        else -> if (!quiet) updateMsg = "You're on the latest (v$mine)"
                    }
                }.onFailure { if (!quiet) updateMsg = "Couldn't check for updates: ${it.message ?: "no connection?"}" }
                if (::updateBox.isInitialized) renderUpdate()
            }
        }
    }

    private fun install(r: Update.Release) {
        // Sideloaded apps need the user's one-off OK to install APKs (PackageManager.canRequestPackageInstalls).
        if (!packageManager.canRequestPackageInstalls()) {
            updateMsg = "Android needs your OK once: switch on \"Allow from this source\" for Glance, then come back."
            renderUpdate()
            unknownSources.launch(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, "package:$packageName".toUri()))
            return
        }
        updating = true; updateMsg = "Downloading ${r.tag}…"; renderUpdate()
        thread {
            val result = runCatching { Update.install(applicationContext, r) }
            runOnUiThread {
                updating = false
                updateMsg = result.fold({ "Confirm the update when Android asks." }, { "Update failed: ${it.message}" })
                renderUpdate()
            }
        }
    }

    private fun renderUpdate() {
        updateBox.removeAllViews()
        val r = release
        updateBox.addView(button(if (r != null) "Update to ${r.tag}" else "Check for updates", if (r != null) 0xFF1D4D3A.toInt() else 0xFF1A1A1E.toInt()) {
            if (updating) return@button
            if (r != null) install(r) else check(quiet = false)
        })
        updateMsg?.let { updateBox.addView(text(it, 12f, 0x99FFFFFF.toInt()).apply { maxLines = 4 }) }
    }

    override fun onResume() {
        super.onResume()
        // The widget can only use your location while this screen has been seen since it started (Android's while-in-use rule),
        // so opening Glance re-promotes it (see OverlayService.onStartCommand).
        if (OverlayService.running && canLocate(this)) OverlayService.start(this)
        build()
    }

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
        permission("Location (for travel times)", canLocate(this)) {
            askLocation.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
        }

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
        choice("Opacity when active", listOf(50, 75, 90, 100), { "$it%" }, prefs.activeOpacity) { prefs.activeOpacity = it }
        choice("Idle opacity", listOf(25, 50, 75, 100), { "$it%" }, prefs.idleOpacity) { prefs.idleOpacity = it }
        choice("Fade after", listOf(3, 5, 10, 30), { "$it s" }, prefs.fadeAfter) { prefs.fadeAfter = it }
        choice("Heads-up before a change", listOf(2, 5, 10), { "$it min" }, prefs.headsUp) { prefs.headsUp = it }
        toggle("Vibrate on reminders and heads-ups", prefs.vibrate) { prefs.vibrate = it }
        list.addView(button("Pop-up when events start: sound and vibration", 0xFF1A1A1E.toInt()) {
            OverlayService.startsChannel(this)   // the settings page needs the channel to exist
            startActivity(Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, packageName).putExtra(Settings.EXTRA_CHANNEL_ID, OverlayService.STARTS))
        }.apply { (layoutParams as LinearLayout.LayoutParams).topMargin = dp(10) })
        toggle("Travel times", prefs.travel) { prefs.travel = it }
        choice("Get-ready time", listOf(0, 3, 5, 10), { "$it min" }, prefs.travelBuffer) { prefs.travelBuffer = it }
        toggle("Start when the phone boots", prefs.onBoot) { prefs.onBoot = it }
        list.addView(button("Reset size", 0xFF1A1A1E.toInt()) { prefs.resetSize() }.apply {
            (layoutParams as LinearLayout.LayoutParams).topMargin = dp(10)
        })

        header("Updates")
        updateBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        list.addView(updateBox)
        renderUpdate()

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
        list.addView(text("Tap the pill to expand it; drag the open card by its clock. It stays wherever you drop it. Pinch the pill to resize it: sideways for width, up and down for Up next rows. Flick it at an edge to dock it as a side strip, tap the strip to bring it back. Long-press anywhere for this screen; hold a travel time for its train.", 12f, 0x80FFFFFF.toInt()).apply {
            maxLines = 8; setPadding(0, dp(10), 0, 0)
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
