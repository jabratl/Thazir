package com.jabra.mgb

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * 0.0.428 (S3): PAGE THEME PARITY FOR CONTENT SCRIPTS (installed extensions only; the built-in Media Grabber is never touched).
 * Problem: Thazir darkens a light page itself (WebView darkening) but the page still reports prefers-color-scheme = light, so a site
 * that themes itself (ChatGPT "System") stays light and its extension panels read "light" -> white card, white text.
 * Pref "ext_theme" in mgb_ui: absent = AUTO, 1 = OFF (old behaviour), 2 = ON.
 *  - AUTO: only when the app is dark AND Thazir shows this page dark (Dark pages ON, or the site rule says Dark):
 *    matchMedia('(prefers-color-scheme: dark)') answers true (light query: false), so the site switches to its real dark theme.
 *  - ON: the same whenever the app is dark, whatever the Dark pages setting says.
 *  - OFF: nothing changes.
 *  The script (assets/shim/theme-hint.js) is added at document start, in the page world, ONLY on the origins of installed extensions' content scripts.
 *  Also (same switch, only when the app is dark): run_at document_idle waits for the page "load" event or 1.5 s, whichever is first
 *  (Chrome idle meaning), so a script that reads the page theme once sees the final theme.
 * Takes effect on the next page load (reload the page).
 */
object ExtTheme {
    const val AUTO = 0
    const val OFF = 1
    const val ON = 2
    const val KEY = "ext_theme"

    /** Read by ContentInjector.wrap for installed extensions (set in ContentInjector.extEntries before the scripts are built). */
    @Volatile var idleOn = false

    private var tpl: String? = null

    private fun sp(c: Context) = c.applicationContext.getSharedPreferences("mgb_ui", Context.MODE_PRIVATE)

    fun mode(c: Context): Int = sp(c).getInt(KEY, AUTO).let { if (it == OFF || it == ON) it else AUTO }

    fun set(c: Context, v: Int) {
        sp(c).edit().apply { if (v == OFF || v == ON) putInt(KEY, v) else remove(KEY) }.apply()
    }

    /** Tap order: Auto -> On -> Off -> Auto. Returns the new value. */
    fun cycle(c: Context): Int {
        val n = when (mode(c)) { AUTO -> ON; ON -> OFF; else -> AUTO }
        set(c, n)
        return n
    }

    fun label(v: Int): String = when (v) { ON -> "On"; OFF -> "Off"; else -> "Auto" }

    /** Part of the content script cache signature: changes when the idle rule changes. */
    fun sig(c: Context): String = mode(c).toString() + (if (UiMode.appDark(c)) "d" else "l")

    /** Called by ContentInjector.extEntries before it builds scripts. */
    fun sync(c: Context) {
        idleOn = try { val m = mode(c); m == ON || (m == AUTO && UiMode.appDark(c)) } catch (_: Throwable) { false }
    }

    /** null = nothing to add (Off, light app, no template, error). origins = union of the content script origins of installed extensions. */
    fun script(c: Context, origins: Set<String>): String? {
        return try {
            val m = mode(c)
            if (m == OFF || origins.isEmpty() || !UiMode.appDark(c)) return null
            val t = tpl ?: c.applicationContext.assets.open("shim/theme-hint.js").bufferedReader().use { it.readText() }.also { tpl = it }
            val rules = try { JSONObject(sp(c).getString(SiteDark.KEY, "{}") ?: "{}") } catch (_: Throwable) { JSONObject() }
            val cfg = JSONObject().put("m", m).put("ad", true).put("dp", UiMode.darkPages(c)).put("r", rules).put("o", JSONArray(origins.toList()))
            t.replace("/*CFG*/null", cfg.toString())
        } catch (_: Throwable) { null }
    }
}
