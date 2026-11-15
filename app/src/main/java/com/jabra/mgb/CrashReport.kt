package com.jabra.mgb

import android.Manifest
import android.app.Activity
import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.widget.ScrollView
import android.widget.TextView
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.File

/**
 * 0.0.455: crash report that shows itself.
 *  1. showIfNeeded(): the FIRST thing a start does (before tabs load): if the last run ended in a crash (Java crash file, or Android's exit record
 *     says crash / native crash / ANR / start failure) one dialog shows time, reason, versions, Java or native stack and the last events, with Copy.
 *     Once per crash. Android 11+ gives the exact reason; older phones only get "the last run ended unexpectedly".
 *  2. notifyCrash(): a Java crash also posts a notification at crash time (tap = app opens = the dialog). Native crashes cannot, no code runs then.
 *  3. Switch: prefs mgb_crashrep on (default ON), Settings > Problems and reports. OFF = old behaviour (CrashLog.showLast).
 * Everything is in try / catch: this code must never cause a crash.
 */
object CrashReport {
    private const val P = "mgb_crashrep"
    private const val CH = "mgb_crash"
    private const val NID = 7455
    private const val FILE = "mgb_crash.txt"
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var shown = false

    private fun sp(c: Context) = c.applicationContext.getSharedPreferences(P, Context.MODE_PRIVATE)
    fun on(c: Context): Boolean = try { sp(c).getBoolean("on", true) } catch (_: Throwable) { true }
    fun setOn(c: Context, v: Boolean) { try { sp(c).edit().putBoolean("on", v).apply() } catch (_: Throwable) { } }

    // ------------------------------------------------------------------ 3. notification at crash time (Java crashes)

    /** Called from the uncaught-exception handler. Needs the notification permission on Android 13+, otherwise nothing is posted. */
    fun notifyCrash(ctx: Context, t: Throwable) {
        try {
            if (!on(ctx)) return
            val app = ctx.applicationContext
            if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(app, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
            val nm = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CH) == null) nm.createNotificationChannel(NotificationChannel(CH, "Crash report", NotificationManager.IMPORTANCE_HIGH))
            val open = app.packageManager.getLaunchIntentForPackage(app.packageName)?.apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP }
            val pi = if (open != null) PendingIntent.getActivity(app, 0, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT) else null
            val text = Diag.mask((t.javaClass.simpleName + ": " + (t.message ?: "")).take(160))
            val n = NotificationCompat.Builder(app, CH)
                .setSmallIcon(R.drawable.ic_info).setContentTitle("Thazir crashed").setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text + "\nTap to open Thazir and see the full report."))
                .setPriority(NotificationCompat.PRIORITY_HIGH).setAutoCancel(true).setContentIntent(pi).build()
            nm.notify(NID, n)
        } catch (_: Throwable) { }
    }

    // ------------------------------------------------------------------ 1 + 2. dialog at start

    private class ExitPart(val ts: Long, val reason: String, val status: Int?, val desc: String, val imp: String, val nativeLines: List<String>)

    /** Call right after CrashGuard.begin() and BEFORE the tabs are restored. */
    fun showIfNeeded(act: Activity) {
        try {
            if (!on(act)) return
            val s = sp(act)
            if (s.getLong("start", 0L) == 0L) s.edit().putLong("start", System.currentTimeMillis()).apply() // exit records older than the first run of this feature are ignored
            check(act, 0)
        } catch (_: Throwable) { }
    }

    private fun check(act: Activity, attempt: Int) {
        if (shown || act.isFinishing || act.isDestroyed) return
        val app = act.applicationContext
        val s = sp(app)
        val file = File(app.filesDir, FILE)
        val javaText = try { if (file.exists()) file.readText() else "" } catch (_: Throwable) { "" }
        val javaTime = try { if (file.exists()) file.lastModified() else 0L } catch (_: Throwable) { 0L }
        val since = maxOf(s.getLong("last", 0L), s.getLong("start", 0L))
        val part: ExitPart? = if (Build.VERSION.SDK_INT >= 30) { try { exitPart(app, since) } catch (_: Throwable) { null } } else null
        if (javaText.isBlank() && part == null) {
            if (CrashGuard.safe && Build.VERSION.SDK_INT >= 30 && attempt < 2) { // Android writes the exit record a moment after the process is gone
                main.postDelayed({ try { check(act, attempt + 1) } catch (_: Throwable) { } }, if (attempt == 0) 2500L else 5000L)
                return
            }
            if (CrashGuard.safe && Build.VERSION.SDK_INT < 30) show(act, "Thazir stopped unexpectedly", "The last run ended without a normal close (a crash, or a force stop).\nThis phone is older than Android 11, so Android gives no exact reason.\n\n" + header(), 0L, false)
            return
        }
        val ts = if (part != null && part.ts > 0L) part.ts else javaTime
        val title = when {
            part?.reason?.startsWith("ANR") == true -> "Thazir stopped responding"
            else -> "Thazir crashed last time"
        }
        val b = StringBuilder()
        b.append(header()).append('\n')
        if (ts > 0L) b.append("When: ").append(DiagExtra.fmtTime(ts)).append('\n')
        if (part != null) {
            b.append("Reason: ").append(part.reason)
            if (part.status != null) b.append(" (signal ").append(part.status).append(')')
            b.append('\n')
            if (part.desc.isNotEmpty()) b.append("Android says: ").append(Diag.mask(part.desc.take(200))).append('\n')
            b.append("App was: ").append(part.imp).append('\n')
        }
        if (javaText.isNotBlank()) b.append("\n--- Java crash ---\n").append(javaText.take(4500)).append('\n')
        if (part != null && part.nativeLines.isNotEmpty()) b.append("\n--- Native crash ---\n").append(part.nativeLines.joinToString("\n")).append('\n')
        val ev = try { Diag.mask(Diag.dump(15)) } catch (_: Throwable) { "" }
        if (ev.isNotBlank()) b.append("\n--- Last events ---\n").append(ev.take(2500))
        if (javaText.isNotBlank()) DiagExtra.saveCrashCopy(app, javaText, javaTime) // the Report center keeps it too
        try { // 0.0.471: the app keeps its own list of crashes (Report center > Crash history)
            val lib = part?.nativeLines?.firstNotNullOfOrNull { Regex("#0\\s+(\\S+)\\s+\\+0x").find(it)?.groupValues?.get(1) } ?: ""
            CrashHistory.add(app, ts, part?.reason ?: "CRASH (Java)", part?.status, CrashGuard.lastCrashHost(), lib)
        } catch (_: Throwable) { }
        show(act, title, b.toString(), ts, javaText.isNotBlank())
    }

    private fun header(): String {
        val sb = StringBuilder()
        sb.append("Thazir ").append(BuildConfig.VERSION_NAME).append('\n')
        sb.append("Android ").append(Build.VERSION.RELEASE).append(" (API ").append(Build.VERSION.SDK_INT).append("), ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append('\n')
        try {
            if (Build.VERSION.SDK_INT >= 26) android.webkit.WebView.getCurrentWebViewPackage()?.let { sb.append("WebView: ").append(it.packageName).append(' ').append(it.versionName).append('\n') }
        } catch (_: Throwable) { }
        return sb.toString()
    }

    @RequiresApi(30)
    private fun exitPart(app: Context, since: Long): ExitPart? {
        val am = app.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return null
        val list = am.getHistoricalProcessExitReasons(app.packageName, 0, 8)
        if (list.isNullOrEmpty()) return null
        val bad = setOf(ApplicationExitInfo.REASON_CRASH, ApplicationExitInfo.REASON_CRASH_NATIVE, ApplicationExitInfo.REASON_ANR, ApplicationExitInfo.REASON_INITIALIZATION_FAILURE)
        val e = list.firstOrNull { it.processName == app.packageName && it.timestamp > since && it.reason in bad } ?: return null
        val nat = if (e.reason == ApplicationExitInfo.REASON_CRASH_NATIVE) { try { DiagExtra.nativeCrashLines(app, list) } catch (_: Throwable) { emptyList() } } else emptyList()
        val sig = if (e.reason == ApplicationExitInfo.REASON_CRASH_NATIVE || e.reason == ApplicationExitInfo.REASON_SIGNALED) e.status else null
        return ExitPart(e.timestamp, DiagExtra.reasonName(e.reason), sig, (e.description ?: "").trim(), DiagExtra.importanceName(e.importance), nat)
    }

    private fun show(act: Activity, title: String, body: String, ts: Long, hasFile: Boolean) {
        if (shown || act.isFinishing) return
        shown = true
        val app = act.applicationContext
        val tv = TextView(act).apply {
            text = body
            textSize = 11f
            typeface = android.graphics.Typeface.MONOSPACE
            setTextIsSelectable(true)
            setTextColor(act.getColor(R.color.mg_text))
            setPadding(Util.dp(act, 16), Util.dp(act, 8), Util.dp(act, 16), Util.dp(act, 8))
        }
        val sv = ScrollView(act)
        sv.addView(tv)
        MaterialAlertDialogBuilder(act)
            .setTitle(title)
            .setView(sv)
            .setPositiveButton("Copy") { _, _ ->
                (act.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Thazir crash", body))
                Util.toast(act, "Copied")
            }
            .setNeutralButton("Full report") { _, _ -> DiagScreen.open(act) }
            .setNegativeButton("Close", null)
            .setOnDismissListener {
                // handled only now: if the app dies before the person sees this, the report comes again next start
                try {
                    sp(app).edit().putLong("last", maxOf(ts, System.currentTimeMillis() - 1000L)).apply()
                    if (hasFile) File(app.filesDir, FILE).delete()
                    (app.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager)?.cancel(NID)
                } catch (_: Throwable) { }
            }
            .show()
    }
}
