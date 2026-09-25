package io.github.bosthecoder.glance

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Brings the widget back after a reboot (if the user asked for that) or after an in-app update (if it was running),
 * provided it still has its permissions. Both broadcasts are exempt from the background foreground-service limits:
 * https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start#background-start-restriction-exemptions
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val p = Prefs(ctx)
        val want = when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED -> p.onBoot
            Intent.ACTION_MY_PACKAGE_REPLACED -> p.sp.getBoolean("restartAfterUpdate", false).also { p.sp.edit().remove("restartAfterUpdate").apply() }
            else -> false
        }
        if (want && canOverlay(ctx) && Cal.granted(ctx)) OverlayService.start(ctx)
    }
}
