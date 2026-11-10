package com.jabra.mgb

import android.content.Context

/**
 * 0.0.370 (N2): NATIVE download control. One shared speed limiter (all parts + single connections together) and the
 * max number of downloads that run at the same time. Prefs in SharedPreferences "mgb_ui":
 *   dl_limit_kbps = 0 (default, OFF = no limit, old behaviour) or KB per second
 *   dl_max_jobs   = 3 (default = the old fixed pool of 3)
 * Everything is wrapped in try / catch: a problem here means "no limit" / "3 jobs", never a failed download.
 */
object DlLimit {
    val LIMITS = intArrayOf(0, 256, 512, 1024, 2048, 5120)
    val JOBS = intArrayOf(1, 2, 3, 4, 6)

    private fun sp() = DownloadsApi.app.getSharedPreferences("mgb_ui", Context.MODE_PRIVATE)

    @Volatile private var cached = 0
    @Volatile private var cachedAt = 0L
    private var nextAt = 0L                          // System.nanoTime() when the shared "pipe" is free again

    /** KB per second, 0 = no limit. Re-read from prefs at most once per second (hot path). */
    fun kbps(): Int {
        val now = System.nanoTime()
        if (now - cachedAt > 1_000_000_000L) {
            cached = try { sp().getInt("dl_limit_kbps", 0).coerceAtLeast(0) } catch (e: Exception) { 0 }
            cachedAt = now
        }
        return cached
    }

    fun maxJobs(): Int = try { sp().getInt("dl_max_jobs", 3).let { if (it in 1..8) it else 3 } } catch (e: Exception) { 3 }

    /** Call after n bytes were read. Sleeps (in short steps) so that all downloads together stay under the limit. Returns early when stop() is true. */
    fun throttle(n: Int, stop: () -> Boolean) {
        try {
            val k = kbps()
            if (k <= 0 || n <= 0) return
            val cost = n.toLong() * 1_000_000_000L / (k.toLong() * 1024L)
            var wait: Long
            synchronized(this) {
                val now = System.nanoTime()
                val start = if (nextAt > now) nextAt else now
                nextAt = start + cost
                wait = start - now
            }
            if (wait > 3_000_000_000L) wait = 3_000_000_000L   // never block one read for long (limit changed / many threads)
            while (wait > 0L) {
                if (stop()) return
                val step = minOf(wait, 200_000_000L)
                Thread.sleep(step / 1_000_000L, (step % 1_000_000L).toInt())
                wait -= step
            }
        } catch (e: Exception) { /* no limit on any problem */ }
    }
}
