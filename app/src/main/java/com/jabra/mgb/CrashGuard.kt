package com.jabra.mgb

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper

/**
 * 0.0.451: crash-loop guard for NATIVE crashes (WebView / Chromium SIGSEGV). CrashLog only sees Java exceptions, a native crash kills the process
 * with no trace of its own, so: begin() writes run = true (commit, on disk at once); end() (MainActivity.onStop = the app goes to the background
 * normally, also swipe away from recents) writes run = false. A start that finds run = true means the last run ended in the foreground without
 * onStop = it crashed (or was force stopped). Then [safe] is true for this run: restoreSession keeps the tabs but does NOT load any of them
 * and opens a fresh Home tab, so a page that crashes the WebView can not crash the app again at every start.
 * Everything is in try / catch: the guard must never cause a crash itself.
 * 0.0.471: risky sites expire after 30 days; 2 render-process crashes of one site within 24 h also make it risky; Report center lists both.
 */
object CrashGuard {
    private const val P = "mgb_guard"
    @Volatile var safe = false
        private set

    // 0.0.468: sites that were loading when the app died (native WebView crash). Such a site is "risky": popups are refused and the extra
    // page scripts of the app are not injected while it loads (see isRisky). The page itself still opens and works.
    private const val K_RUN = "run"
    private const val K_LAST = "last"
    private const val K_CTX = "ctx"      // 0.0.470: one line about the app state when that page started (tabs, pop-ups, RAM ...)
    private const val K_LT = "lt"        // 0.0.470: time of that page start
    private const val K_BAD = "bad"      // 0.0.471: StringSet of "host|time added" (older versions: just "host")
    private const val K_RLOG = "rlog"    // 0.0.471: StringSet of "host|time" = render process crashes (didCrash) of the last 24 h
    private const val MAX_BAD = 40
    private const val EXPIRE_MS = 30L * 24 * 60 * 60 * 1000   // 0.0.471: a risky site is forgotten after 30 days
    private const val RENDER_WINDOW_MS = 24L * 60 * 60 * 1000
    private const val RENDER_LIMIT = 2                          // 0.0.471: this many render crashes in the window = risky
    @Volatile private var sp: SharedPreferences? = null
    @Volatile private var bad: Map<String, Long> = emptyMap()
    @Volatile private var lastNoted = ""

    private fun loadBad(p: SharedPreferences, now: Long): LinkedHashMap<String, Long> {
        val out = LinkedHashMap<String, Long>()
        val raw = p.getStringSet(K_BAD, emptySet()) ?: emptySet()
        val list = ArrayList<Pair<String, Long>>()
        for (s in raw) {
            val i = s.lastIndexOf('|')
            if (i < 0) list.add(s to now) // old format: the 30 days start now
            else list.add(s.substring(0, i) to (s.substring(i + 1).toLongOrNull() ?: now))
        }
        list.sortBy { it.second }
        for ((h, t) in list) if (h.isNotEmpty() && now - t <= EXPIRE_MS) out[h] = t
        return out
    }

    private fun saveBad(ed: SharedPreferences.Editor, m: Map<String, Long>) {
        val set = HashSet<String>()
        for ((h, t) in m) set.add("$h|$t")
        ed.putStringSet(K_BAD, set)
    }

    fun begin(ctx: Context) {
        try {
            val p = ctx.applicationContext.getSharedPreferences(P, Context.MODE_PRIVATE)
            sp = p
            safe = p.getBoolean(K_RUN, false)
            val now = System.currentTimeMillis()
            val set = loadBad(p, now) // also drops the expired ones and upgrades the old format
            val ed = p.edit()
            if (safe) { // last run ended in the foreground = crash: the site that was loading is the suspect
                val h = p.getString(K_LAST, "") ?: ""
                if (h.isNotEmpty()) {
                    set.remove(h); set[h] = now // newest last
                    while (set.size > MAX_BAD) set.remove(set.keys.first())
                    ed.putString("lc_host", h).putString("lc_ctx", p.getString(K_CTX, "") ?: "").putLong("lc_time", p.getLong(K_LT, 0L)) // shown in Report center > Crash guard
                    try { Diag.w("guard", "crash while loading", h) } catch (_: Throwable) { }
                }
            }
            saveBad(ed, set)
            bad = set
            ed.remove(K_LAST).remove(K_CTX).remove(K_LT).putBoolean(K_RUN, true).commit()
            lastNoted = ""
        } catch (_: Throwable) { }
    }

    fun end(ctx: Context) {
        try {
            lastNoted = ""
            ctx.applicationContext.getSharedPreferences(P, Context.MODE_PRIVATE).edit().putBoolean(K_RUN, false).remove(K_LAST).remove(K_CTX).remove(K_LT).commit()
        } catch (_: Throwable) { }
    }

    private fun norm(host: String?): String = (host ?: "").trim().lowercase().removePrefix("www.")

    /** A page of a NORMAL tab starts loading (never call it for a private tab: the host would be written to disk). Written only when the host changes. [state] = one line of app state. */
    fun note(host: String?, state: String = "") {
        try {
            val h = norm(host)
            if (h.isEmpty() || h == lastNoted) return
            lastNoted = h
            sp?.edit()?.putString(K_LAST, h)?.putString(K_CTX, state.take(300))?.putLong(K_LT, System.currentTimeMillis())?.commit()
        } catch (_: Throwable) { }
    }

    /** true = this host (or a sub domain of it) was loading at an earlier native crash (or its render process crashed twice in 24 h). */
    fun isRisky(host: String?): Boolean {
        val h = norm(host)
        if (h.isEmpty()) return false
        val b = bad
        if (b.isEmpty()) return false
        return b.keys.any { h == it || h.endsWith(".$it") }
    }

    fun riskyHosts(): List<String> = bad.keys.toList()

    /** 0.0.471: mark a host risky now (used by [rendererCrash]). */
    fun addRisky(host: String?) {
        try {
            val h = norm(host)
            if (h.isEmpty()) return
            val m = LinkedHashMap(bad)
            m.remove(h); m[h] = System.currentTimeMillis()
            while (m.size > MAX_BAD) m.remove(m.keys.first())
            bad = m
            val ed = sp?.edit() ?: return
            saveBad(ed, m)
            ed.commit()
        } catch (_: Throwable) { }
    }

    /**
     * 0.0.471: the WebView render process of a NORMAL tab died (didCrash = a real crash, not a kill for memory). Counts the crashes of this host in the last 24 h;
     * at [RENDER_LIMIT] the host becomes risky. Returns the count. Never throws.
     */
    fun rendererCrash(host: String?, didCrash: Boolean): Int {
        try {
            val h = norm(host)
            val p = sp
            if (h.isEmpty() || !didCrash || p == null) return 0
            val now = System.currentTimeMillis()
            val rows = ArrayList<Pair<String, Long>>()
            for (s in p.getStringSet(K_RLOG, emptySet()) ?: emptySet()) {
                val i = s.lastIndexOf('|')
                if (i <= 0) continue
                val t = s.substring(i + 1).toLongOrNull() ?: continue
                if (now - t <= RENDER_WINDOW_MS) rows.add(s.substring(0, i) to t)
            }
            rows.add(h to now)
            rows.sortBy { it.second }
            while (rows.size > 60) rows.removeAt(0)
            val set = HashSet<String>()
            for ((x, t) in rows) set.add("$x|$t")
            p.edit().putStringSet(K_RLOG, set).apply()
            val n = rows.count { it.first == h }
            if (n >= RENDER_LIMIT && !isRisky(h)) addRisky(h)
            return n
        } catch (_: Throwable) { return 0 }
    }

    /** Host that was loading at the crash BEFORE this run (only meaningful when [safe] is true). */
    fun lastCrashHost(): String = try { if (safe) (sp?.getString("lc_host", "") ?: "") else "" } catch (_: Throwable) { "" }

    /** Forget the suspects (so a fixed WebView / page gets the normal behaviour again). */
    fun clearRisky() {
        try { bad = emptyMap(); sp?.edit()?.remove(K_BAD)?.remove(K_RLOG)?.commit() } catch (_: Throwable) { }
    }

    /** 0.0.470: Report center section "Crash guard". */
    fun section(): DiagReport.Section {
        val lines = ArrayList<String>()
        val p = sp
        val now = System.currentTimeMillis()
        val h = p?.getString("lc_host", "") ?: ""
        if (h.isEmpty()) lines.add("No crash while a page was loading is recorded")
        else {
            val t = p?.getLong("lc_time", 0L) ?: 0L
            lines.add("Last crash while loading: $h" + (if (t > 0L) " (" + DiagExtra.fmtTime(t) + ")" else ""))
            val c = p?.getString("lc_ctx", "") ?: ""
            if (c.isNotEmpty()) lines.add("App state then: $c")
        }
        val b = bad
        lines.add("Risky sites (pop-ups refused, no early scripts; forgotten after 30 days): " +
            (if (b.isEmpty()) "none" else b.entries.joinToString(", ") { it.key + " (" + maxOf(0L, (now - it.value) / 86_400_000L) + " d)" }))
        try { // render process crashes of the last 24 h, per site
            val per = LinkedHashMap<String, Int>()
            for (s in p?.getStringSet(K_RLOG, emptySet()) ?: emptySet()) {
                val i = s.lastIndexOf('|')
                if (i <= 0) continue
                val t = s.substring(i + 1).toLongOrNull() ?: continue
                if (now - t <= RENDER_WINDOW_MS) per[s.substring(0, i)] = (per[s.substring(0, i)] ?: 0) + 1
            }
            lines.add("Render process crashes, last 24 h: " + (if (per.isEmpty()) "none" else per.entries.joinToString(", ") { it.key + " x" + it.value }) + " (" + RENDER_LIMIT + " = risky)")
        } catch (_: Throwable) { }
        return DiagReport.Section("guard", "Browser", "Crash guard", if (b.isNotEmpty()) DiagReport.WARN else DiagReport.INFO, lines)
    }
}
