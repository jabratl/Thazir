package com.jabra.mgb

import android.content.Context
import android.webkit.WebView
import org.json.JSONObject

/**
 * 0.0.424: DARK PAGES EXCEPTION PER SITE (any site, not only the social ones).
 * Pref "dark_sites" in mgb_ui = JSON { "host": 1 | 2 }  (absent host = same as the app setting "Dark pages",
 * 1 = Dark here, 2 = Original here = the exception). Saved in mgb_ui, so Backup already carries it.
 * Order of authority for one page address (effective()):
 *   1. SocialDark (the per-site choice of a social site, when Social sites is ON for it)  2. this rule  3. the app switch Dark pages.
 * A rule for "example.com" also covers "m.example.com" (parent domains are walked, never a bare TLD).
 * It only switches the WebView's own algorithmic darkening (UiMode.applyPage) at the start of the next page load, so, like
 * every Dark pages setting, it needs a dark app theme to show. Extension pages are never touched (see ExtDark).
 * No rule saved = effective() is exactly UiMode.darkPages(), so nothing changes until the person uses it.
 */
object SiteDark {
    const val SAME = 0
    const val DARK = 1
    const val ORIGINAL = 2
    const val KEY = "dark_sites"

    private fun sp(c: Context) = c.applicationContext.getSharedPreferences("mgb_ui", Context.MODE_PRIVATE)

    private fun load(c: Context): JSONObject =
        try { JSONObject(sp(c).getString(KEY, "{}") ?: "{}") } catch (_: Throwable) { JSONObject() }

    private fun norm(h: String) = h.trim().lowercase().removePrefix("www.")

    /** Saved choice for exactly this host (no parent walk). */
    fun mode(c: Context, host: String): Int = load(c).optInt(norm(host), SAME).coerceIn(SAME, ORIGINAL)

    /** Choice that really applies to this host: own rule, else the nearest parent domain that has one. */
    fun modeFor(c: Context, host: String): Int {
        val o = load(c)
        if (o.length() == 0) return SAME
        var h = norm(host)
        while (h.isNotEmpty()) {
            val v = o.optInt(h, SAME)
            if (v == DARK || v == ORIGINAL) return v
            val i = h.indexOf('.')
            if (i < 0 || h.indexOf('.', i + 1) < 0) break // stop before a bare TLD (co.uk is never matched)
            h = h.substring(i + 1)
        }
        return SAME
    }

    fun set(c: Context, host: String, v: Int) {
        val h = norm(host)
        if (h.isEmpty()) return
        val o = load(c)
        if (v == DARK || v == ORIGINAL) o.put(h, v) else o.remove(h)
        sp(c).edit().apply { if (o.length() == 0) remove(KEY) else putString(KEY, o.toString()) }.apply()
    }

    /** Tap order: Same as app -> Dark -> Original -> Same as app. Returns the new value. */
    fun cycle(c: Context, host: String): Int {
        val n = when (mode(c, host)) { SAME -> DARK; DARK -> ORIGINAL; else -> SAME }
        set(c, host, n)
        return n
    }

    fun label(v: Int): String = when (v) { DARK -> "Dark"; ORIGINAL -> "Original (no dark)"; else -> "Same as app setting" }

    /** Hosts with a saved rule, sorted. */
    fun hosts(c: Context): List<String> {
        val o = load(c)
        val r = ArrayList<String>()
        val it = o.keys()
        while (it.hasNext()) { val k = it.next(); if (o.optInt(k, 0) in DARK..ORIGINAL) r.add(k) }
        return r.sorted()
    }

    fun count(c: Context): Int = hosts(c).size

    /** The one answer: should this page address be darkened? */
    fun effective(c: Context, url: String?): Boolean {
        try {
            val own = SocialDark.own(c, url)
            if (own != SocialDark.SAME) return own == SocialDark.ON
            if (url != null) {
                when (modeFor(c, SiteConfig.hostOf(url))) { DARK -> return true; ORIGINAL -> return false }
            }
        } catch (_: Throwable) { }
        return UiMode.darkPages(c)
    }

    /** TabManager.onPageStarted (not extension pages) and the Dark pages switch loop. Always sets the flag, so a tab that left an exception site goes back to the app setting. */
    fun apply(c: Context, view: WebView?, url: String?) {
        if (view == null) return
        try { UiMode.applyPage(view, effective(c, url)) } catch (_: Throwable) { }
    }
}
