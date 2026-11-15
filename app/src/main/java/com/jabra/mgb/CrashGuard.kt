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
 */
object CrashGuard {
    private const val P = "mgb_guard"
    @Volatile var safe = false
        private set

    // 0.0.468: sites that were loading when the app died (native WebView crash). Such a site is "risky": popups are refused and the extra
    // page scripts of the app are not injected while it loads (see isRisky). The page itself still opens and works.
    private const val K_RUN = "run"
    private const val K_LAST = "last"
    private const val K_BAD = "bad"
    private const val MAX_BAD = 40
    @Volatile private var sp: SharedPreferences? = null
    @Volatile private var bad: Set<String> = emptySet()
    @Volatile private var lastNoted = ""

    fun begin(ctx: Context) {
        try {
            val p = ctx.applicationContext.getSharedPreferences(P, Context.MODE_PRIVATE)
            sp = p
            safe = p.getBoolean(K_RUN, false)
            val set = LinkedHashSet<String>(p.getStringSet(K_BAD, emptySet()) ?: emptySet())
            val ed = p.edit()
            if (safe) { // last run ended in the foreground = crash: the site that was loading is the suspect
                val h = p.getString(K_LAST, "") ?: ""
                if (h.isNotEmpty()) {
                    set.remove(h); set.add(h) // newest last
                    while (set.size > MAX_BAD) set.remove(set.first())
                    ed.putStringSet(K_BAD, HashSet(set))
                    try { Diag.w("guard", "crash while loading", h) } catch (_: Throwable) { }
                }
            }
            bad = set
            ed.remove(K_LAST).putBoolean(K_RUN, true).commit()
            lastNoted = ""
        } catch (_: Throwable) { }
    }

    fun end(ctx: Context) {
        try {
            lastNoted = ""
            ctx.applicationContext.getSharedPreferences(P, Context.MODE_PRIVATE).edit().putBoolean(K_RUN, false).remove(K_LAST).commit()
        } catch (_: Throwable) { }
    }

    private fun norm(host: String?): String = (host ?: "").trim().lowercase().removePrefix("www.")

    /** A page of a NORMAL tab starts loading (never call it for a private tab: the host would be written to disk). Written only when the host changes. */
    fun note(host: String?) {
        try {
            val h = norm(host)
            if (h.isEmpty() || h == lastNoted) return
            lastNoted = h
            sp?.edit()?.putString(K_LAST, h)?.commit()
        } catch (_: Throwable) { }
    }

    /** true = this host (or a sub domain of it) was loading at an earlier native crash. */
    fun isRisky(host: String?): Boolean {
        val h = norm(host)
        if (h.isEmpty()) return false
        val b = bad
        if (b.isEmpty()) return false
        return b.any { h == it || h.endsWith(".$it") }
    }

    fun riskyHosts(): List<String> = bad.toList()

    /** Forget the suspects (so a fixed WebView / page gets the normal behaviour again). */
    fun clearRisky() {
        try { bad = emptySet(); sp?.edit()?.remove(K_BAD)?.commit() } catch (_: Throwable) { }
    }
}
