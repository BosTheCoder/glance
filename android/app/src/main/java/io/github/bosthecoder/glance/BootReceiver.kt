package io.github.bosthecoder.glance

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Brings the widget back after a reboot, if the user asked for that and it still has its permissions. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED && Prefs(ctx).onBoot && canOverlay(ctx) && Cal.granted(ctx))
            OverlayService.start(ctx)
    }
}
