package com.jabra.mgb

import android.Manifest
import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * 0.0.334: "this version keeps crashing, go back?" hint. OWNER ONLY: does nothing unless DevMode is on. CrashLog's crash handler calls record(); every crash of the
 * same version (APK build + hot ext bundle) is kept for 24 h.
 *  - 2 or more crashes: a notification "Thazir keeps crashing" opens the revert page on GitHub. It works even when
 *    the app crashes at start and can never show a dialog. Needs the notification permission (else nothing is shown).
 *  - When the app does start: maybeShow() (called by MainActivity after CrashLog.showLast) shows a dialog with
 *    "Older versions" (VersionHistory) / "Not now" / "Don't ask again". It comes back after 2 more crashes.
 * Everything is wrapped in try / catch: the hint must never cause a crash itself. Nothing is installed or reverted
 * automatically; the revert build is made by the GitHub workflow.
 */
object CrashHint {
    private const val P = "mgb_crashhint"
    private const val CH = "mgb_crash"
    private const val ID = 7005
    private const val DAY = 24L * 3600 * 1000

    private fun key(ctx: Context): String {
        val ext = try { ctx.getSharedPreferences("mgb_ext", Context.MODE_PRIVATE).getString("dir", "") ?: "" } catch (_: Throwable) { "" }
        return "${BuildConfig.VERSION_CODE}|$ext"
    }

    private fun times(raw: String, now: Long): List<Long> =
        raw.split(",").mapNotNull { it.toLongOrNull() }.filter { val d = now - it; d >= 0L && d < DAY }

    /** Called from the uncaught exception handler. Never throws. */
    fun record(ctx: Context) {
        try {
            val app = ctx.applicationContext
            if (!DevMode.on(app)) return // owner only (Settings > About > Version, 7 taps)
            val sp = app.getSharedPreferences(P, Context.MODE_PRIVATE)
            val k = key(app)
            val now = System.currentTimeMillis()
            val same = sp.getString("k", "") == k
            val kept = (if (same) times(sp.getString("t", "") ?: "", now) else emptyList<Long>()).takeLast(9) + now
            val e = sp.edit().putString("k", k).putString("t", kept.joinToString(","))
            if (!same) e.remove("s").remove("off")
            e.commit() // the process is about to die: apply() may not be written
            if (kept.size >= 2) notifyCrashing(app)
        } catch (_: Throwable) { }
    }

    private fun notifyCrashing(c: Context) {
        try {
            if (BuildConfig.GH_REPO.isEmpty()) return
            if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(c, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
            val nm = c.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CH) == null)
                nm.createNotificationChannel(NotificationChannel(CH, "Crash alerts", NotificationManager.IMPORTANCE_DEFAULT))
            val i = Intent(Intent.ACTION_VIEW, Uri.parse(VersionHistory.actionsUrl())).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val pi = PendingIntent.getActivity(c, 5, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val b = NotificationCompat.Builder(c, CH)
                .setSmallIcon(R.drawable.ic_info)
                .setContentTitle("Thazir keeps crashing")
                .setContentText("Tap to open GitHub: Run workflow, revert_to = previous")
                .setStyle(NotificationCompat.BigTextStyle().bigText("This version crashed more than once. Tap to open GitHub, then Run workflow with revert_to = previous (or a version tag from Settings > About > Version history). When the build is green, update the app."))
                .setContentIntent(pi)
                .setAutoCancel(true)
            nm.notify(ID, b.build())
        } catch (_: Throwable) { }
    }

    /** MainActivity.onCreate, after CrashLog.showLast. The app started, so the notification is no longer needed. */
    fun maybeShow(act: Activity) {
        try {
            try { (act.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(ID) } catch (_: Throwable) { }
            if (act.isFinishing || !DevMode.on(act)) return
            val sp = act.getSharedPreferences(P, Context.MODE_PRIVATE)
            val k = key(act)
            if (sp.getString("k", "") != k || sp.getBoolean("off", false)) return
            val count = times(sp.getString("t", "") ?: "", System.currentTimeMillis()).size
            var shown = sp.getInt("s", 0)
            if (count < shown) shown = 0
            if (count < 2 || count < shown + 2) return
            sp.edit().putInt("s", count).apply()
            MaterialAlertDialogBuilder(act)
                .setTitle("Thazir keeps crashing")
                .setMessage(
                    "This version (${BuildConfig.VERSION_NAME}, build ${ExtUpdater.currentBuild()}) crashed $count times in the last 24 hours.\n\n" +
                        "You can go back to an older version. The revert build is made on GitHub (a few minutes), then you update the app as usual."
                )
                .setPositiveButton("Older versions") { _, _ -> VersionHistory.open(act) }
                .setNeutralButton("Not now", null)
                .setNegativeButton("Don't ask again") { _, _ -> sp.edit().putBoolean("off", true).apply() }
                .show()
        } catch (_: Throwable) { }
    }
}
