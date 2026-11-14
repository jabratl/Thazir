package com.jabra.mgb

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.webkit.WebView
import androidx.appcompat.app.AppCompatDelegate
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * S20: night / day.
 *  - Theme choice System / Light / Dark (saved, applied at every start before the Activity is created)
 *  - Dark pages: lets the WebView darken web pages that have no dark style (only when the app itself is dark)
 */
object UiMode {
    private const val PREFS = "mgb_ui"
    private val NAMES = arrayOf("System", "Light", "Dark")

    private fun sp(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 0 = follow system, 1 = light, 2 = dark. */
    fun mode(ctx: Context): Int = sp(ctx).getInt("night", 0).coerceIn(0, 2)

    fun label(ctx: Context): String = NAMES[mode(ctx)]

    /** Call at the start of Activity.onCreate, before super.onCreate. */
    fun apply(ctx: Context) {
        AppCompatDelegate.setDefaultNightMode(
            when (mode(ctx)) {
                1 -> AppCompatDelegate.MODE_NIGHT_NO
                2 -> AppCompatDelegate.MODE_NIGHT_YES
                else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            }
        )
    }

    /** Menu > Theme: pick System / Light / Dark. The Activity is recreated (tabs come back from the saved session). */
    fun pick(act: Activity) {
        val cur = mode(act)
        MaterialAlertDialogBuilder(act)
            .setTitle("Theme")
            .setSingleChoiceItems(NAMES, cur) { d, which ->
                d.dismiss()
                if (which != cur) {
                    sp(act).edit().putInt("night", which).apply()
                    apply(act)
                    smoothRecreate(act)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** APPEARANCE A2: the Appearance theme tiles. Saves + applies; returns true when the mode really changed (caller recreates). */
    fun setMode(ctx: Context, which: Int): Boolean {
        val w = which.coerceIn(0, 2)
        if (w == mode(ctx)) return false
        sp(ctx).edit().putInt("night", w).apply()
        apply(ctx)
        return true
    }

    fun darkPages(ctx: Context): Boolean = sp(ctx).getBoolean("dark_pages", false)

    fun setDarkPages(ctx: Context, on: Boolean) {
        sp(ctx).edit().putBoolean("dark_pages", on).apply()
    }

    /** 0.0.421: true when the app is dark right now (Activity configuration, so System mode is honoured too). */
    fun appDark(ctx: Context): Boolean =
        (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    /**
     * 0.0.421: extension pages (popup window, options, extension tab). Before, they never got dark mode: the WebView reported
     * prefers-color-scheme = light and had a white backdrop, so in a dark app every popup / page opened FULL WHITE.
     * Now: dark app = algorithmic darkening ON (pages that declare color-scheme / prefers-color-scheme keep their own style,
     * pages without any style are darkened) and the backdrop is the app background, so there is no white flash while the page loads.
     * Light app = darkening OFF, backdrop = app background (soft tint, not white). Old WebView without dark support in a dark app: white backdrop (as before).
     * 0.0.424: url = the page address; an installed extension can be Always dark / Never dark (ExtDark), Auto = everything above.
     */
    @Suppress("DEPRECATION")
    fun applyExt(ctx: Context, w: WebView, url: String? = null) {
        val m = ExtDark.modeOfUrl(ctx, url) // 0.0.424: per-extension choice (Auto for built-in pages and when no url is given)
        val appDk = appDark(ctx)
        val dark = m == ExtDark.AUTO && appDk // WebView darkening only in Auto; Always dark uses a CSS filter (ExtDark.onLoaded), Never dark = off
        var ok = false
        try {
            if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
                WebSettingsCompat.setAlgorithmicDarkeningAllowed(w.settings, dark)
                ok = true
            } else if (WebViewFeature.isFeatureSupported(WebViewFeature.FORCE_DARK)) {
                WebSettingsCompat.setForceDark(w.settings, if (dark) WebSettingsCompat.FORCE_DARK_ON else WebSettingsCompat.FORCE_DARK_OFF)
                if (dark && WebViewFeature.isFeatureSupported(WebViewFeature.FORCE_DARK_STRATEGY)) {
                    WebSettingsCompat.setForceDarkStrategy(w.settings, WebSettingsCompat.DARK_STRATEGY_PREFER_WEB_THEME_OVER_USER_AGENT_DARKENING)
                }
                ok = true
            }
        } catch (e: Exception) { /* old WebView: page stays as it is */ }
        try {
            w.setBackgroundColor(when (m) {
                ExtDark.FORCE -> 0xFF0B0D13.toInt() // dark backdrop (same as the dark mg_bg) so the page does not flash white before the filter is added
                ExtDark.ORIGINAL -> if (appDk) Color.WHITE else ctx.getColor(R.color.mg_bg)
                else -> if (dark && !ok) Color.WHITE else ctx.getColor(R.color.mg_bg)
            })
        } catch (e: Exception) { }
    }

    /** 0.0.421: Activity.recreate() with a short cross-fade (theme / accent change), instead of a hard flash. Skipped with Reduce motion. */
    @Suppress("DEPRECATION")
    fun smoothRecreate(act: Activity) {
        act.recreate()
        try { if (!TabPrefs.reduce(act)) act.overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out) } catch (e: Exception) { }
    }

    /** allowed = user switch on and not an extension page. Takes effect on the next page load. */
    fun applyPage(w: WebView, allowed: Boolean) {
        try {
            if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
                WebSettingsCompat.setAlgorithmicDarkeningAllowed(w.settings, allowed)
            }
        } catch (e: Exception) { /* old WebView: pages stay as they are */ }
    }
}
