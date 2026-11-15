package com.jabra.mgb

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * 0.0.471: the last [MAX] crashes the app knows about, kept by the app itself (Android keeps only a few exit records and drops old ones).
 * One line per crash in filesDir/mgb_crash_hist.jsonl: time, reason, signal, host that was loading, app version, WebView version, first native library.
 * Written by CrashReport.check (at the start after a crash), read by the Report center ("Crash history"). Host only, never a full address; a private tab is never in it.
 * The WebView version is the one installed when the crash was found (normally the same as at the crash). Never throws.
 */
object CrashHistory {
    private const val FILE = "mgb_crash_hist.jsonl"
    private const val MAX = 20
    private const val DAY = 24L * 60 * 60 * 1000

    private fun read(f: File): MutableList<JSONObject> {
        val out = ArrayList<JSONObject>()
        try { if (f.exists()) f.forEachLine { l -> try { if (l.isNotBlank()) out.add(JSONObject(l)) } catch (_: Throwable) { } } } catch (_: Throwable) { }
        return out
    }

    private fun webView(): String = try {
        if (android.os.Build.VERSION.SDK_INT >= 26) android.webkit.WebView.getCurrentWebViewPackage()?.versionName ?: "?" else "?"
    } catch (_: Throwable) { "?" }

    /** [ts] = time of the crash (0 = now). [host] = "" when unknown. [lib] = first native frame library ("" for a Java crash). */
    fun add(ctx: Context, ts: Long, reason: String, signal: Int?, host: String, lib: String) {
        try {
            val f = File(ctx.applicationContext.filesDir, FILE)
            val t = if (ts > 0L) ts else System.currentTimeMillis()
            val rows = read(f)
            if (rows.any { Math.abs(it.optLong("t") - t) < 3000L }) return // the same crash shown twice
            rows.add(0, JSONObject().put("t", t).put("r", reason.take(40)).put("s", signal ?: -1).put("h", host.take(80))
                .put("v", BuildConfig.VERSION_NAME).put("w", webView()).put("l", lib.take(60)))
            while (rows.size > MAX) rows.removeAt(rows.size - 1)
            val tmp = File(f.parentFile, FILE + ".tmp")
            tmp.bufferedWriter().use { w -> for (o in rows) { w.write(o.toString()); w.write("\n") } }
            if (!tmp.renameTo(f)) { f.writeText(tmp.readText()); tmp.delete() }
        } catch (_: Throwable) { }
    }

    /** Report center section "Crash history" (worker thread is fine). */
    fun section(ctx: Context): DiagReport.Section {
        try {
            val rows = read(File(ctx.applicationContext.filesDir, FILE))
            if (rows.isEmpty()) return DiagReport.Section("crashhist", "Device", "Crash history", DiagReport.INFO,
                listOf("No crash recorded yet (the list starts when a crash is found at the next start)"))
            val now = System.currentTimeMillis()
            val lines = ArrayList<String>()
            val last7 = rows.count { now - it.optLong("t") <= 7 * DAY }
            lines.add("${rows.size} crash(es) recorded, $last7 in the last 7 days")
            val byW = LinkedHashMap<String, Int>()
            for (o in rows) { val w = o.optString("w", "?"); byW[w] = (byW[w] ?: 0) + 1 }
            lines.add("By WebView version: " + byW.entries.joinToString(", ") { it.key + " x" + it.value })
            val byH = LinkedHashMap<String, Int>()
            for (o in rows) { val h = o.optString("h", ""); if (h.isNotEmpty()) byH[h] = (byH[h] ?: 0) + 1 }
            if (byH.isNotEmpty()) lines.add("By site: " + byH.entries.sortedByDescending { it.value }.take(5).joinToString(", ") { it.key + " x" + it.value })
            lines.add("Newest first:")
            for (o in rows.take(10)) {
                val sig = o.optInt("s", -1)
                lines.add("  " + DiagExtra.fmtTime(o.optLong("t")) + " " + o.optString("r") + (if (sig >= 0) " (signal $sig)" else "") +
                    (o.optString("h").let { if (it.isNotEmpty()) " | $it" else "" }) + " | app " + o.optString("v") + " | WebView " + o.optString("w") +
                    (o.optString("l").let { if (it.isNotEmpty()) " | $it" else "" }))
            }
            return DiagReport.Section("crashhist", "Device", "Crash history", if (last7 >= 3) DiagReport.WARN else DiagReport.INFO, lines)
        } catch (_: Throwable) {
            return DiagReport.Section("crashhist", "Device", "Crash history", DiagReport.INFO, listOf("not readable"))
        }
    }
}
