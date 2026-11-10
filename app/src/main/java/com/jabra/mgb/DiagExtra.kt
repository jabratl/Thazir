package com.jabra.mgb

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import androidx.annotation.RequiresApi
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 0.0.397: two Report center sections that show WHY the app died, from things the phone already keeps.
 *  - "Last crash": CrashLog.showLast keeps a copy of the crash text (filesDir/mgb_crash_last.txt) before it
 *    deletes the file, so the report can show it any time later.
 *  - "App exits": ActivityManager.getHistoricalProcessExitReasons (Android 11+), shows kills a Java stack
 *    trace never shows (low memory, not responding, killed by the system).
 *
 * Read only. Nothing here changes any setting, file or flow. No new permission, no network.
 * Called from DiagReport.build on a WORKER thread. Every part is wrapped: a failure = a line "not readable".
 */
object DiagExtra {
    const val CRASH_COPY = "mgb_crash_last.txt"   // written by CrashLog.saveCopy
    private const val CRASH_FILE = "mgb_crash.txt" // written by the uncaught handler (CrashLog)
    private const val DAY = 24 * 60 * 60 * 1000L

    class Bundle(
        val crash: DiagReport.Section,
        val exits: DiagReport.Section,
        val lowMem24h: Int,   // exits with LOW_MEMORY in the last 24 h (for the rule)
        val anr24h: Int       // exits with ANR in the last 24 h (for the rule)
    )

    private inline fun <T> safe(def: T, f: () -> T): T = try { f() } catch (e: Throwable) { def }

    fun empty(): Bundle = Bundle(
        DiagReport.Section("lastcrash", "Device", "Last crash", DiagReport.INFO, listOf("not measured")),
        DiagReport.Section("exits", "Device", "App exits", DiagReport.INFO, listOf("not measured")),
        0, 0)

    /** Worker thread. Never throws. */
    fun collect(ctx: Context): Bundle {
        val now = System.currentTimeMillis()
        val crash = safe(DiagReport.Section("lastcrash", "Device", "Last crash", DiagReport.INFO, listOf("not readable"))) { lastCrash(ctx, now) }
        var low = 0
        var anr = 0
        val exits = safe(DiagReport.Section("exits", "Device", "App exits", DiagReport.INFO, listOf("not readable"))) {
            val r = exitSection(ctx, now)
            low = r.second; anr = r.third
            r.first
        }
        return Bundle(crash, exits, low, anr)
    }

    // ------------------------------------------------------------------ 1. last crash

    private fun fmtTime(ts: Long): String = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(ts))

    private fun ago(ms: Long): String {
        val m = maxOf(0L, ms) / 60000L
        return if (m < 60) "$m min ago" else if (m < 60 * 48) "${m / 60} h ${m % 60} min ago" else "${m / 1440} days ago"
    }

    private fun lastCrash(ctx: Context, now: Long): DiagReport.Section {
        val dir = ctx.applicationContext.filesDir
        val fresh = File(dir, CRASH_FILE)     // a crash the dialog has not shown yet (newest)
        val copy = File(dir, CRASH_COPY)      // a crash the dialog already showed
        val src = if (fresh.exists()) fresh else if (copy.exists()) copy else null
        if (src == null) return DiagReport.Section("lastcrash", "Device", "Last crash", DiagReport.INFO,
            listOf("No crash saved yet (a crash is saved when it happens; the copy stays here after the dialog is closed)"))
        val raw = src.readText()
        val rows = raw.lines()
        var i = 0
        var ts = src.lastModified()
        if (rows.isNotEmpty() && rows[0].startsWith("#time=")) { ts = rows[0].removePrefix("#time=").trim().toLongOrNull() ?: ts; i = 1 }
        val head = rows.getOrNull(i) ?: ""
        i++
        val ver = if (head.startsWith("MGB ")) head.removePrefix("MGB ").substringBefore(" thread=").trim() else "?"
        val thr = if (head.contains("thread=")) head.substringAfter("thread=").trim() else "?"
        val stack = rows.drop(i).map { it.trim() }.filter { it.isNotEmpty() }
        val show = ArrayList<String>(stack.take(12))
        // the line with com.jabra.mgb is what matters: if it is not in the first 12, add up to 3 of them
        if (show.none { it.contains("com.jabra.mgb") }) show.addAll(stack.drop(12).filter { it.contains("com.jabra.mgb") }.take(3))
        val recent = ts > 0 && now - ts <= DAY
        val lines = ArrayList<String>()
        lines.add("Time: " + fmtTime(ts) + " (" + ago(now - ts) + ")")
        lines.add("App version: $ver")
        lines.add("Thread: $thr")
        if (show.isEmpty()) lines.add("No stack saved") else for (l in show) lines.add("  " + Diag.mask(l.take(200)))
        return DiagReport.Section("lastcrash", "Device", "Last crash", if (recent) DiagReport.BAD else DiagReport.INFO, lines)
    }

    /** Called by CrashLog.showLast BEFORE it deletes the crash file. Never throws. */
    fun saveCrashCopy(ctx: Context, text: String, crashTime: Long) {
        try {
            if (text.isBlank()) return
            val t = if (crashTime > 0) crashTime else System.currentTimeMillis()
            File(ctx.applicationContext.filesDir, CRASH_COPY).writeText("#time=$t\n" + text.take(6000))
        } catch (_: Throwable) { }
    }

    // ------------------------------------------------------------------ 2. why the OS ended the app

    /** Returns (section, low memory count in 24 h, ANR count in 24 h). */
    private fun exitSection(ctx: Context, now: Long): Triple<DiagReport.Section, Int, Int> {
        fun sec(level: String, lines: List<String>) = DiagReport.Section("exits", "Device", "App exits", level, lines)
        if (Build.VERSION.SDK_INT < 30) return Triple(sec(DiagReport.INFO,
            listOf("Needs Android 11 or newer (this phone: API " + Build.VERSION.SDK_INT + ")")), 0, 0)
        return exits30(ctx, now)
    }

    @RequiresApi(30)
    private fun exits30(ctx: Context, now: Long): Triple<DiagReport.Section, Int, Int> {
        fun sec(level: String, lines: List<String>) = DiagReport.Section("exits", "Device", "App exits", level, lines)
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            ?: return Triple(sec(DiagReport.INFO, listOf("ActivityManager not available")), 0, 0)
        val list = am.getHistoricalProcessExitReasons(ctx.packageName, 0, 10)
        if (list.isNullOrEmpty()) return Triple(sec(DiagReport.INFO, listOf("Android has no exit record yet")), 0, 0)
        var low = 0; var anr = 0; var crash = 0
        val rows = ArrayList<String>()
        for (e in list) {
            val recent = now - e.timestamp <= DAY
            if (recent) when (e.reason) {
                ApplicationExitInfo.REASON_LOW_MEMORY -> low++
                ApplicationExitInfo.REASON_ANR -> anr++
                ApplicationExitInfo.REASON_CRASH, ApplicationExitInfo.REASON_CRASH_NATIVE -> crash++
            }
            val proc = e.processName.removePrefix(ctx.packageName).ifEmpty { "main" }.trimStart(':')
            val b = StringBuilder()
            b.append("  ").append(fmtTime(e.timestamp)).append(' ').append(reasonName(e.reason))
            if (e.reason == ApplicationExitInfo.REASON_SIGNALED || e.reason == ApplicationExitInfo.REASON_CRASH_NATIVE) b.append(" (signal ").append(e.status).append(')')
            b.append(", was ").append(importanceName(e.importance)).append(", process ").append(proc)
            if (e.rss > 0 || e.pss > 0) b.append(", memory ").append(if (e.rss > 0) "rss " + e.rss / 1024 + " MB" else "pss " + e.pss / 1024 + " MB")
            val d = (e.description ?: "").trim()
            if (d.isNotEmpty()) b.append(": ").append(Diag.mask(d.take(120)))
            rows.add(b.toString())
        }
        val head = ArrayList<String>()
        head.add("Last 24 h: low memory $low, not responding (ANR) $anr, crash $crash")
        head.add("Newest first (Android keeps only a few):")
        val level = if (low + anr >= 3) DiagReport.WARN else DiagReport.INFO
        return Triple(sec(level, head + rows), low, anr)
    }

    @RequiresApi(30)
    private fun reasonName(r: Int): String = when (r) {
        ApplicationExitInfo.REASON_ANR -> "ANR (app did not respond)"
        ApplicationExitInfo.REASON_CRASH -> "CRASH"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "CRASH_NATIVE"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "LOW_MEMORY"
        ApplicationExitInfo.REASON_SIGNALED -> "SIGNALED"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "EXCESSIVE_RESOURCE_USAGE"
        ApplicationExitInfo.REASON_USER_REQUESTED -> "USER_REQUESTED (swiped away / force stop)"
        ApplicationExitInfo.REASON_USER_STOPPED -> "USER_STOPPED"
        ApplicationExitInfo.REASON_EXIT_SELF -> "EXIT_SELF"
        ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "DEPENDENCY_DIED"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "INITIALIZATION_FAILURE"
        ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "PERMISSION_CHANGE"
        ApplicationExitInfo.REASON_OTHER -> "OTHER"
        ApplicationExitInfo.REASON_UNKNOWN -> "UNKNOWN"
        14 -> "FREEZER"
        15 -> "PACKAGE_STATE_CHANGE"
        16 -> "PACKAGE_UPDATED"
        else -> "REASON_$r"
    }

    private fun importanceName(i: Int): String = when {
        i <= 100 -> "foreground"
        i <= 130 -> "foreground service"
        i <= 200 -> "visible"
        i <= 230 -> "perceptible"
        i <= 300 -> "service"
        i <= 400 -> "cached (background)"
        else -> "gone"
    }
}
