package com.jabra.mgb

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.os.Handler
import android.os.Looper

/**
 * 0.0.371 (N3): AUTO-RESUME of native downloads that were cut by the app being killed (CRASH) or by a lost network.
 * Pref dl_auto_resume in SharedPreferences "mgb_ui", default OFF (= old behaviour: the user presses Resume).
 * Triggers: ~4 s after app start, and ~2.5 s after the network comes back (default network callback).
 * Only items that can really continue (state interrupted + canResume, http) and whose error is a "try again" kind:
 *   - plain items (tab link / blob): CRASH, NETWORK_FAILED, NETWORK_TIMEOUT, NETWORK_DISCONNECTED, NETWORK_SERVER_DOWN
 *   - extension items (grabber, chrome.downloads): ONLY CRASH (for network errors the extension decides itself: fallback / retry)
 * Never: USER_CANCELED, SERVER_* (403 / 404 / 429 ...), FILE_* errors. Max 3 automatic tries per item per app run.
 * DlRules (Wi-Fi only / metered / battery / storage) are respected. Nothing here can throw: every call is in try / catch.
 */
object DlAutoResume {
    private const val PREFS = "mgb_ui"
    private const val MAX_TRIES = 3
    private val NET = setOf("NETWORK_FAILED", "NETWORK_TIMEOUT", "NETWORK_DISCONNECTED", "NETWORK_SERVER_DOWN")
    private val main = Handler(Looper.getMainLooper())
    private val tries = HashMap<Int, Int>()
    private var started = false
    private val job = Runnable { resumeNow() }

    fun enabled(c: Context): Boolean = try { c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("dl_auto_resume", false) } catch (e: Exception) { false }

    /** Called once from DownloadsApi.init (after the saved items are loaded). Cheap: it only registers a callback. */
    fun start() {
        if (started) return
        started = true
        try {
            main.postDelayed(job, 4000L)
            val cm = DownloadsApi.app.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
            cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    main.removeCallbacks(job)
                    main.postDelayed(job, 2500L)
                }
            })
        } catch (e: Exception) { /* no callback = only the start trigger */ }
    }

    private fun resumeNow() {
        try {
            val c = DownloadsApi.app
            if (!enabled(c)) return
            if (!DlRules.canRun(c)) { Diag.i("autoresume", "held by download rules"); return }
            var n = 0
            for (i in DownloadsApi.interruptedList()) {
                if (!i.url.startsWith("http", true) || !i.canResume) continue
                val ok = if (i.plain) (i.error == "CRASH" || i.error in NET) else i.error == "CRASH"
                if (!ok) continue
                val t = tries[i.id] ?: 0
                if (t >= MAX_TRIES) continue
                tries[i.id] = t + 1
                DownloadsApi.change(i) { state = "in_progress"; paused = false; error = ""; end = 0L }
                Downloader.start(i)
                n++
            }
            if (n > 0) Diag.i("autoresume", "resumed $n download(s)")
        } catch (e: Exception) { try { Diag.w("autoresume", e.javaClass.simpleName) } catch (x: Exception) { } }
    }
}
