package com.jabra.mgb

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 0.0.402: Report center extras (plan items 8, 9, 10, 11). Read only, no new permission, no network.
 *  8  bySite()      "Errors by site": events of the Diag ring grouped by host (host only, never a full url).
 *  9  trendLines()  keeps the last 5 app-data totals (prefs mgb_diag_trend) and shows the change since the last report.
 *  10 changed()     "Changed from default" for the settings whose default is known in code.
 *  11 redact()      when switched on (button under Copy / Share / Save), links are cut to the host and e-mails hidden.
 * Every function is called inside safe() / try-catch by its caller.
 */
object DiagMore {
    private const val UI = "mgb_ui"
    private const val HOUR_MS = 60L * 60 * 1000

    private fun ui(c: Context) = c.getSharedPreferences(UI, Context.MODE_PRIVATE)

    // ---------------------------------------------------------------- 8. errors by site

    private val HOST = Regex("""https?://([^/\s?#:]+)""")

    private fun label(e: Diag.Event): String {
        val w = e.msg.trim()
        return when {
            w.startsWith("http ") -> w.take(8)
            w.startsWith("load error") -> "load error " + w.removePrefix("load error ").substringBefore(' ')
            else -> e.tag + ": " + w.take(30)
        }
    }

    fun bySite(now: Long): DiagReport.Section {
        val per = LinkedHashMap<String, LinkedHashMap<String, Int>>()
        for (e in Diag.recent(300, null, true)) {
            val m = HOST.find(e.extra) ?: HOST.find(e.msg) ?: continue // private tab events carry no url: skipped
            val host = m.groupValues[1].lowercase().take(60)
            val lab = per.getOrPut(host) { LinkedHashMap() }
            val k = label(e)
            lab[k] = (lab[k] ?: 0) + maxOf(1, e.n)
        }
        if (per.isEmpty()) return DiagReport.Section("sites", "Browser", "Errors by site", DiagReport.INFO, listOf("No error or warning with a site address"))
        val rows = per.entries.sortedByDescending { it.value.values.sum() }.take(8)
        val lines = ArrayList<String>()
        lines.add("Errors and warnings grouped by site (host only, newest 300 events):")
        for ((host, labs) in rows) {
            lines.add("  " + Diag.mask(host) + " (" + labs.values.sum() + "): " + labs.entries.sortedByDescending { it.value }.take(3).joinToString(", ") { Diag.mask(it.key) + " x" + it.value })
        }
        val worst = rows.first().value.values.sum()
        return DiagReport.Section("sites", "Browser", "Errors by site", if (worst >= 5) DiagReport.WARN else DiagReport.INFO, lines)
    }

    // ---------------------------------------------------------------- 9. size trend

    private fun mb(b: Long): String { val a = Math.abs(b); return if (a >= 1L shl 30) String.format(Locale.US, "%.2f GB", a / 1073741824.0) else String.format(Locale.US, "%.1f MB", a / 1048576.0) }

    /** Worker thread, AFTER DataSizes.lines (it fills DataSizes.lastTotal). Empty list = nothing to show. */
    fun trendLines(ctx: Context): List<String> {
        val total = DataSizes.lastTotal
        if (total < 0) return emptyList()
        val sp = ctx.getSharedPreferences("mgb_diag_trend", Context.MODE_PRIVATE)
        val old = ArrayList<Pair<Long, Long>>()
        for (p in (sp.getString("t", "") ?: "").split(',')) {
            val a = p.split(':')
            if (a.size == 2) { val t = a[0].toLongOrNull(); val b = a[1].toLongOrNull(); if (t != null && b != null) old.add(t to b) }
        }
        val now = System.currentTimeMillis()
        if (old.isNotEmpty() && now - old.last().first <= 60_000L) old.removeAt(old.size - 1) // refresh within a minute = same report
        val prev = old.lastOrNull()
        old.add(now to total)
        while (old.size > 5) old.removeAt(0)
        sp.edit().putString("t", old.joinToString(",") { it.first.toString() + ":" + it.second }).apply()
        val lines = ArrayList<String>()
        if (prev == null) lines.add("Change since the last report: first measurement (kept from now on)") else {
            val d = total - prev.second
            val h = (now - prev.first) / HOUR_MS
            lines.add("Change since the last report (" + (if (h < 48) "$h h ago" else "${h / 24} days ago") + "): " +
                (if (Math.abs(d) < 1048576L) "no change" else (if (d > 0) "+" else "-") + mb(d)) + (if (d > 500L * 1048576L) "  <- grew a lot" else ""))
        }
        val f = SimpleDateFormat("MM-dd", Locale.US)
        lines.add("Last " + old.size + " totals: " + old.joinToString(", ") { f.format(Date(it.first)) + " " + mb(it.second) })
        return lines
    }

    // ---------------------------------------------------------------- 10. changed from default

    /** Only settings whose default is known in code are checked. */
    fun changed(ctx: Context): List<String> {
        val up = ui(ctx)
        val ch = ArrayList<String>()
        var n = 0
        fun chk(name: String, cur: Any, def: Any) { n++; if (cur != def) ch.add("  $name = $cur (default $def)") }
        chk("Ad blocker", AdBlock.enabled, true)
        chk("HTTPS-only", up.getBoolean("https_only", false), false)
        chk("Download in parts", up.getBoolean("dp_on", true), true)
        chk("Parts per download", up.getInt("dp_n", 4), 4)
        chk("Parts only for files over (MB)", up.getInt("dp_min_mb", 8), 8)
        chk("Speed limit (KB/s, 0 = off)", up.getInt("dl_limit_kbps", 0), 0)
        chk("Downloads at the same time", up.getInt("dl_max_jobs", 3), 3)
        chk("Auto-resume", up.getBoolean("dl_auto_resume", false), false)
        chk("Smart tail", up.getBoolean("dp_tail", false), false)
        chk("Ask before download", up.getBoolean(DownloadAsk.KEY, false), false)
        chk("Download sub-folders", DownloadFolder.subfolders(ctx), false)
        chk("Extensions with own page width", ExtFit.changedCount(ctx), 0) // 0.0.427
        chk("Match page theme", ExtTheme.label(ExtTheme.mode(ctx)), "Auto") // 0.0.428
        chk("Extension CSS compatibility", ExtCssCompat.label(ExtCssCompat.mode(ctx)), "Auto") // 0.0.434
        return if (ch.isEmpty()) listOf("Changed from default: nothing ($n settings checked)") else listOf("Changed from default ($n settings checked):") + ch
    }

    // ---------------------------------------------------------------- 11. redact when sharing

    fun redactOn(ctx: Context): Boolean = try { ui(ctx).getBoolean("diag_redact", false) } catch (_: Throwable) { false }
    fun setRedact(ctx: Context, on: Boolean) { try { ui(ctx).edit().putBoolean("diag_redact", on).apply() } catch (_: Throwable) { } }

    private val URL = Regex("""https?://[^\s)"'<>]+""")
    private val EMAIL = Regex("""[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}""")

    /** Links -> scheme + host + "/..." (no path, no query); e-mails hidden. Never throws: on error the text is returned as it was. */
    fun redact(t: String): String = try {
        val r = URL.replace(t) { m ->
            val v = m.value
            val scheme = v.substringBefore("://")
            val host = v.substringAfter("://").substringBefore('/').substringBefore('?').substringBefore('#').substringAfterLast('@')
            "$scheme://$host/..."
        }
        EMAIL.replace(r, "***@***")
    } catch (_: Throwable) { t }
}
