package com.jabra.mgb

import android.content.Context
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

    fun begin(ctx: Context) {
        try {
            val sp = ctx.applicationContext.getSharedPreferences(P, Context.MODE_PRIVATE)
            safe = sp.getBoolean("run", false)
            sp.edit().putBoolean("run", true).commit()
        } catch (_: Throwable) { }
    }

    fun end(ctx: Context) {
        try { ctx.applicationContext.getSharedPreferences(P, Context.MODE_PRIVATE).edit().putBoolean("run", false).commit() } catch (_: Throwable) { }
    }
}
