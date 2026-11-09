package com.jabra.mgb

import android.app.Activity
import android.os.Build
import android.webkit.WebView

/**
 * 0.0.366: downloads keep going when Thazir is left (Home button, screen off, lock).
 *  - busy(): a native download (not paused) or a V77 engine job (state "run", not paused, updated in the last 10 min) is running
 *  - onPause / onStop (MainActivity): when busy, start DownloadService (wake lock + Wi-Fi lock + notification) and keep the page in
 *    front running (an extension page such as downloader / manga runs its own download code; Android would freeze it)
 * Everything is try / catch: a failure here never stops the normal flow.
 */
object BgKeep {
    private const val STALE_MS = 10 * 60 * 1000L

    /** Main thread. */
    fun busy(): Boolean {
        try { if (DownloadsApi.active().isNotEmpty()) return true } catch (_: Exception) { }
        return engineBusy()
    }

    fun engineBusy(): Boolean {
        val o = try { ChromeBridge.session.get("mgJobs").optJSONObject("mgJobs") } catch (_: Exception) { null } ?: return false
        val now = System.currentTimeMillis()
        for (k in o.keys()) {
            val j = o.optJSONObject(k) ?: continue
            if (j.optString("state") == "run" && !j.optBoolean("paused") && now - j.optLong("updated", now) < STALE_MS) return true
        }
        return false
    }

    /** Activity.onPause. Returns true when the page must NOT be paused (a download needs it). */
    fun onPause(a: Activity, web: WebView?): Boolean {
        if (a.isFinishing || a.isChangingConfigurations || !busy()) return false
        DownloadService.ensure(a.applicationContext) // started while Thazir is still in front = Android allows it
        keepPage(web)
        return true
    }

    /** Activity.onStop: Android hides the WebView window here and the page stops; run it again. */
    fun onStop(a: Activity, web: WebView?) {
        if (a.isChangingConfigurations || !busy()) return
        DownloadService.ensure(a.applicationContext)
        keepPage(web)
    }

    private fun keepPage(web: WebView?) {
        if (web == null) return
        try {
            if (Build.VERSION.SDK_INT >= 26) web.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false) // important even when not visible
            web.post { try { web.onResume(); web.resumeTimers() } catch (_: Exception) { } }
        } catch (_: Exception) { }
    }
}
