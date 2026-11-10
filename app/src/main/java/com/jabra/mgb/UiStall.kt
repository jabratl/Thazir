package com.jabra.mgb

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 0.0.398: main thread stall watcher (ANR hint). A daemon thread posts a tiny Runnable to the main Handler
 * every second. If it did not run within 2.5 s, the top 6 frames of the main thread are logged (Diag tag "stall"),
 * so "the app sticks" shows WHERE it sticks (for example an image decode on the UI thread).
 *
 * Runs only while the app is in the foreground (MainActivity.onStart -> start, onStop -> stop).
 * Read only: it never touches the UI, a setting or a file. Every call is in try / catch.
 * Events live in the Diag ring (saved to disk, survive a crash); the report shows the last 20.
 */
object UiStall {
    private const val PING_MS = 1000L
    private const val LIMIT_MS = 2500L
    private const val POLL_MS = 250L
    private const val SHOW = 20
    @Volatile private var gen = 0

    fun start() {
        try {
            val g = ++gen // an older loop sees a new number and ends
            Thread({ loop(g) }, "mgb-stall").apply { isDaemon = true }.start()
        } catch (_: Throwable) { }
    }

    fun stop() { gen++ }

    private fun loop(g: Int) {
        try {
            val h = Handler(Looper.getMainLooper())
            while (gen == g) {
                val ran = AtomicBoolean(false)
                val t0 = SystemClock.uptimeMillis()
                h.post { ran.set(true) }
                var logged = false
                while (gen == g && !ran.get()) {
                    Thread.sleep(POLL_MS)
                    if (!logged && !ran.get() && SystemClock.uptimeMillis() - t0 >= LIMIT_MS) {
                        logged = true
                        Diag.w("stall", "main thread stuck for 2.5 s or more", mainStack()) // logged now: a kill would lose it otherwise
                    }
                }
                if (logged && ran.get()) Diag.i("stall", "main thread free again after " + (SystemClock.uptimeMillis() - t0) + " ms")
                val rest = PING_MS - (SystemClock.uptimeMillis() - t0)
                if (rest > 0 && gen == g) Thread.sleep(rest)
            }
        } catch (_: Throwable) { }
    }

    private fun mainStack(): String = try {
        (Looper.getMainLooper().thread.stackTrace.take(6)).joinToString("\n") { "at $it" }
    } catch (_: Throwable) { "" }

    /** Report section "UI stalls". Never throws. */
    fun section(now: Long): DiagReport.Section {
        val ev = Diag.recent(SHOW * 2, "stall", false)
        val stuck = ev.filter { it.level == "W" }.takeLast(SHOW)
        if (stuck.isEmpty()) return DiagReport.Section("stalls", "Device", "UI stalls", DiagReport.INFO,
            listOf("No stall of 2.5 s or more seen (only watched while the app is open on screen)"))
        val fmt = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US)
        val lines = ArrayList<String>()
        lines.add("Stalls logged: " + stuck.size + " (last " + SHOW + " kept)")
        for (e in stuck.takeLast(3)) {
            lines.add("  " + fmt.format(Date(e.ts)) + " " + e.msg + (if (e.n > 1) " (x" + e.n + ")" else ""))
            for (l in e.extra.lines().map { it.trim() }.filter { it.isNotEmpty() }.take(6)) lines.add("      " + Diag.mask(l.take(160)))
        }
        for (e in ev.filter { it.level == "I" }.takeLast(2)) lines.add("  " + fmt.format(Date(e.ts)) + " " + e.msg)
        val recent = stuck.last().ts >= now - 10 * 60 * 1000L
        return DiagReport.Section("stalls", "Device", "UI stalls", if (recent) DiagReport.WARN else DiagReport.INFO, lines)
    }
}
