package io.github.bosthecoder.glance

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.DigestInputStream
import java.security.MessageDigest

/** "Check for updates": the latest GitHub release, installed through a PackageInstaller session. Call off the main thread. */
object Update {
    /** The session's result comes back to MainActivity as an intent with this action. */
    const val ACTION = "io.github.bosthecoder.glance.INSTALL_STATUS"
    private const val LATEST = "https://api.github.com/repos/BosTheCoder/glance/releases/latest"

    /** [sha256]: the asset's hex digest from GitHub, if the API gave one. */
    data class Release(val tag: String, val apk: String?, val size: Long, val sha256: String?)

    private fun open(url: String) = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 10_000; readTimeout = 20_000
        setRequestProperty("User-Agent", "Glance-Android")   // GitHub's API rejects requests without one
    }

    fun latest(): Release {
        val c = open(LATEST).apply { setRequestProperty("Accept", "application/vnd.github+json") }
        try {
            if (c.responseCode != 200) throw IOException("GitHub answered ${c.responseCode}")
            val j = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
            val assets = j.getJSONArray("assets")
            val apk = (0 until assets.length()).map { assets.getJSONObject(it) }.firstOrNull { it.optString("name") == "Glance.apk" }
            val digest = apk?.optString("digest")?.takeIf { it.startsWith("sha256:") }?.removePrefix("sha256:")
            return Release(j.getString("tag_name"), apk?.getString("browser_download_url"), apk?.optLong("size", -1L) ?: -1L, digest)
        } finally {
            c.disconnect()
        }
    }

    fun version(ctx: Context): String {
        val pm = ctx.packageManager
        val info = if (Build.VERSION.SDK_INT >= 33) pm.getPackageInfo(ctx.packageName, PackageManager.PackageInfoFlags.of(0))
            else @Suppress("DEPRECATION") pm.getPackageInfo(ctx.packageName, 0)
        return info.versionName ?: "0"
    }

    /**
     * Streams the release's Glance.apk straight into an install session, checks its SHA-256 against GitHub's
     * digest (when there is one) and commits it. Android then asks the
     * user to confirm (STATUS_PENDING_USER_ACTION), which MainActivity handles when [ACTION] comes back.
     */
    fun install(ctx: Context, r: Release) {
        val installer = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            .apply { setAppPackageName(ctx.packageName) }
        val id = installer.createSession(params)
        try {
            installer.openSession(id).use { session ->
                val c = open(r.apk ?: throw IOException("the release has no Glance.apk"))
                try {
                    if (c.responseCode != 200) throw IOException("download failed (${c.responseCode})")
                    val sha = MessageDigest.getInstance("SHA-256")
                    DigestInputStream(c.inputStream, sha).use { input ->
                        session.openWrite("Glance.apk", 0, r.size).use { out -> input.copyTo(out); session.fsync(out) }
                    }
                    // Nothing installs until commit, so a mismatch here just abandons the session.
                    val got = sha.digest().joinToString("") { "%02x".format(it) }
                    if (r.sha256 != null && !got.equals(r.sha256, ignoreCase = true))
                        throw IOException("the download doesn't match the release's SHA-256, so it wasn't installed")
                } finally {
                    c.disconnect()
                }
                // Mutable: the installer adds EXTRA_STATUS to it. The intent is explicit, as Android 14 requires for that.
                val status = PendingIntent.getActivity(ctx, id, Intent(ctx, MainActivity::class.java).setAction(ACTION),
                    PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
                // Installing stops the running widget; BootReceiver brings it back once the new version is in.
                Prefs(ctx).sp.edit().putBoolean("restartAfterUpdate", OverlayService.running).apply()
                session.commit(status.intentSender)
            }
        } catch (e: Exception) {
            installer.abandonSession(id)
            throw e
        }
    }
}
