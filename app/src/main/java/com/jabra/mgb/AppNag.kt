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

    /** The browser_fallback_url of an intent:// link when it is a http / https address, else null. */
    fun fallbackUrl(url: String): String? {
        if (!url.startsWith("intent:", true)) return null
        return try {
            val fb = Intent.parseUri(url, Intent.URI_INTENT_SCHEME).getStringExtra("browser_fallback_url")
            if (fb != null && (fb.startsWith("https://", true) || fb.startsWith("http://", true))) fb else null
        } catch (_: Throwable) { null }
    }
}
