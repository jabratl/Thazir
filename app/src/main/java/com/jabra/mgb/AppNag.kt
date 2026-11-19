package com.jabra.mgb

import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * APP NAG (0.0.416): two small helpers for sites that push you into their app (TikTok, Instagram, Reddit, Facebook, X ...).
 *  1. Auto app-open block: a page that opens a social app by itself (a script / redirect, NO tap: request.hasGesture() == false) with an app link
 *     (tiktok://, fb://, instagram://, intent:// or market:// of a known social app) is stopped and you stay in the browser. A link you TAP still opens the app.
 *     Only the apps of SocialProfiles are matched (never payment / banking / other apps). Independent of the Social mode switch. Pref app_nag_block, default ON.
 *  2. intent:// fallback: when an intent:// link finds no app, its own browser_fallback_url (http / https only) is opened in the same tab
 *     (Chrome does the same) instead of only showing "No app to open this link".
 * Hooks: TabManager.shouldOverrideUrlLoading (before the scheme check) and TabManager.openExternal. Rollback: remove those two hooks (+ this file + the Settings row).
 */
object AppNag {
    private const val KEY = "app_nag_block"
    private fun sp(c: Context) = c.getSharedPreferences("mgb_ui", Context.MODE_PRIVATE)
    fun on(c: Context): Boolean = try { sp(c).getBoolean(KEY, true) } catch (_: Throwable) { true }
    fun setOn(c: Context, v: Boolean) { try { sp(c).edit().putBoolean(KEY, v).apply() } catch (_: Throwable) { } }

    /** true = this address (opened without a tap) would launch a known social app: do not open it. */
    fun blocksAuto(ctx: Context, u: Uri): Boolean {
        if (!on(ctx)) return false
        val s = u.scheme?.lowercase() ?: return false
        if (s == "http" || s == "https") return false
        if (s in SocialProfiles.allSchemes) return true
        if (s == "intent" || s == "market") {
            val t = u.toString()
            return SocialProfiles.allIntentPkgs.any { t.contains(it) }
        }
        return false
    }

    private val BROWSER_PKGS = setOf(
        "com.android.chrome", "com.chrome.beta", "com.chrome.dev", "com.chrome.canary", "com.google.android.apps.chrome",
        "org.mozilla.firefox", "org.mozilla.firefox_beta", "org.mozilla.fenix", "org.mozilla.focus", "org.mozilla.klar",
        "com.brave.browser", "com.brave.browser_beta", "com.brave.browser_nightly", "com.microsoft.emmx",
        "com.opera.browser", "com.opera.mini.native", "com.opera.gx", "com.sec.android.app.sbrowser", "com.vivaldi.browser",
        "com.duckduckgo.mobile.android", "com.kiwibrowser.browser", "com.UCMobile.intl", "com.uc.browser.en",
        "com.mi.globalbrowser", "com.android.browser", "com.yandex.browser", "com.huawei.browser"
    )
    private val BROWSER_SCHEMES = setOf(
        "googlechrome", "googlechromes", "googlechrome-x-callback", "firefox", "firefox-focus", "brave", "opera-http", "opera-https",
        "microsoft-edge", "microsoft-edge-http", "microsoft-edge-https", "ucbrowser", "samsunginternet"
    )

    /**
     * 0.0.479 (ad hardening): true = this NON-http(s) address must not be handed to Android.
     *  - no tap (script / redirect / timer, also when the page comes back to the front): ALWAYS blocked, whatever app it names
     *  - a pop-up tab: ALWAYS blocked (a real pop-up never needs to start an app)
     *  - even with a tap: a link that only pushes you into ANOTHER BROWSER (googlechrome://, intent:// with a browser package, or intent://
     *    with http(s) data and no package) is blocked; tap-hijack ads use exactly this. Normal app links with a tap (maps, YouTube, tel) still work.
     * Uses the same switch as the social app block (app_nag_block, default ON).
     */
    fun blocksExternal(ctx: Context, url: String, hasGesture: Boolean, popup: Boolean): Boolean {
        if (!on(ctx)) return false
        val u = try { Uri.parse(url) } catch (_: Throwable) { return false }
        val s = u.scheme?.lowercase() ?: return false
        if (s == "http" || s == "https") return false
        if (popup || !hasGesture) return true
        if (s in BROWSER_SCHEMES) return true
        if (s == "intent") {
            val i = try { Intent.parseUri(url, Intent.URI_INTENT_SCHEME) } catch (_: Throwable) { return true }
            val pkg = i.`package`
            if (pkg != null && pkg in BROWSER_PKGS) return true
            val ds = i.data?.scheme?.lowercase()
            if (pkg == null && (ds == "http" || ds == "https")) return true
        }
        return false
    }

    /** The browser_fallback_url of an intent:// link when it is a http / https address, else null. */
    fun fallbackUrl(url: String): String? {
        if (!url.startsWith("intent:", true)) return null
        return try {
            val fb = Intent.parseUri(url, Intent.URI_INTENT_SCHEME).getStringExtra("browser_fallback_url")
            if (fb != null && (fb.startsWith("https://", true) || fb.startsWith("http://", true))) fb else null
        } catch (_: Throwable) { null }
    }
}
