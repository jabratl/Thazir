package com.jabra.mgb

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import androidx.core.content.ContextCompat

/**
 * 0.0.296: Settings > Media (Brave style). Keys live in "mgb_ui", all inside Reset / Backup.
 *  md_drm      Widevine DRM: the page may ask for protected media (default ON). Playing only; the downloader still skips DRM items (it reads the manifest, not this switch).
 *  md_bg       Background play: YouTube keeps playing when the tab / app changes or the screen is off (default ON).
 *  md_ytlinks  Stay in Thazir: YouTube pages cannot jump to the YouTube app (default ON).
 *  md_ytrec / md_ytdist / md_ytshorts  hide up-next + end cards / sign-in, menu, comments, video info / Shorts (default OFF).
 */
object MediaPrefs {
    const val DRM = "md_drm"; const val BG = "md_bg"; const val YTLINKS = "md_ytlinks"; const val NOISY = "md_noisy"
    const val YTREC = "md_ytrec"; const val YTDIST = "md_ytdist"; const val YTSHORTS = "md_ytshorts"
    val KEYS = listOf(DRM, BG, YTLINKS, NOISY, YTREC, YTDIST, YTSHORTS)
    private val DEF_ON = setOf(DRM, BG, YTLINKS, NOISY)

    private fun sp(c: Context) = c.getSharedPreferences("mgb_ui", Context.MODE_PRIVATE)
    fun get(c: Context, k: String): Boolean = sp(c).getBoolean(k, k in DEF_ON)
    fun set(c: Context, k: String, on: Boolean) { sp(c).edit().putBoolean(k, on).apply() }
    fun drm(c: Context) = get(c, DRM)
    fun bg(c: Context) = get(c, BG)

    /** JS object read by shim/yt-media.js (set by YtAds.flag on every youtube.com page start / finish). */
    fun ytJs(c: Context): String =
        "window.__mgbYm={bg:" + (if (bg(c)) 1 else 0) + ",rec:" + (if (get(c, YTREC)) 1 else 0) + ",dist:" + (if (get(c, YTDIST)) 1 else 0) +
            ",sh:" + (if (get(c, YTSHORTS)) 1 else 0) + ",away:" + (if (away) 1 else 0) + "};window.__mgbYmApply&&window.__mgbYmApply();"

    /** YouTube app links (vnd.youtube:, intent: for com.google.android.youtube) stay in the tab. */
    fun blockYtApp(c: Context, u: Uri): Boolean {
        if (!get(c, YTLINKS)) return false
        val s = u.scheme?.lowercase() ?: return false
        if (s == "vnd.youtube" || s == "youtube") return true
        if (s == "intent") { val t = u.toString(); return t.contains("com.google.android.youtube") }
        return false
    }

    /** 0.0.302: true while Thazir is not in front and a page was playing (read by yt-media.js: a pause now is not the user's). */
    @Volatile var away = false

    // ---- background play: keep the process alive while sound plays and the app is not in front
    fun audible(c: Context): Boolean = try { (c.getSystemService(Context.AUDIO_SERVICE) as AudioManager).isMusicActive } catch (_: Exception) { false }

    /** Activity.onPause: sound is playing and the pref is ON = small foreground service (notification) so Android does not freeze the page. */
    fun onPause(a: Activity) {
        if (!bg(a) || a.isFinishing || a.isChangingConfigurations || !audible(a)) return
        away = true
        stopPending?.let { stopH.removeCallbacks(it) }; stopPending = null // 0.0.317: a late stop from a quick Home / back must not kill this start
        try { ContextCompat.startForegroundService(a, Intent(a, WebAudioService::class.java)) } catch (_: Exception) { }
    }

    /** 0.0.302: Activity.onStop. Android hides the WebView window here and the page stops; keep it running and tell the page it is "away". */
    fun onStop(a: Activity, web: android.webkit.WebView?) {
        if (!away || web == null) return
        try { web.onResume(); web.resumeTimers() } catch (_: Exception) { }
        try { web.evaluateJavascript("window.__mgbYm&&(window.__mgbYm.away=1);window.__mgbYmKeep&&window.__mgbYmKeep();", null) } catch (_: Exception) { }
    }

    fun onResume(a: Activity, web: android.webkit.WebView? = null) {
        away = false
        try { web?.evaluateJavascript("window.__mgbYm&&(window.__mgbYm.away=0);", null) } catch (_: Exception) { }
        // 0.0.317: stop a moment later, so a stop can never overtake startForegroundService() (ForegroundServiceDidNotStartInTimeException)
        val app = a.applicationContext
        stopPending?.let { stopH.removeCallbacks(it) }
        val r = Runnable { stopPending = null; try { app.stopService(Intent(app, WebAudioService::class.java)) } catch (_: Exception) { } }
        stopPending = r
        stopH.postDelayed(r, 800)
    }

    private val stopH = android.os.Handler(android.os.Looper.getMainLooper())
    private var stopPending: Runnable? = null
}
