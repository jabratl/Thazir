package com.jabra.mgb

import android.content.Context
import android.webkit.WebView
import org.json.JSONObject

/**
 * 0.0.424: DARK MODE PER EXTENSION (installed extensions only; the built-in Media Grabber pages are never touched).
 * Pref "ext_dark" in mgb_ui = JSON { "<extension id>": 1 | 2 }  (absent = Auto).
 *  - AUTO (0): exactly the 0.0.421 behaviour (dark app = dark popup through the WebView's own darkening, light app = light).
 *  - FORCE (1, "Always dark"): the popup / options / extension tab is dark even in a light app, and even when its own style is light.
 *    Done with a small CSS filter (invert + hue-rotate, pictures and video turned back), so it does not depend on the WebView
 *    version or on the app theme. The WebView's own darkening is switched OFF for that page so it is never darkened twice.
 *  - ORIGINAL (2, "Never dark"): the page keeps its own colours even in a dark app (for a popup that is already styled, or breaks when darkened).
 * Takes effect when the extension page is opened (popup: close and open it again; extension tab: reload).
 */
object ExtDark {
    const val AUTO = 0
    const val FORCE = 1
    const val ORIGINAL = 2
    const val KEY = "ext_dark"

    /** Idempotent: one <style id=mgb-force-dark>. The page paints white first so the inversion becomes near black. */
    const val CSS_JS = "(function(){try{if(document.getElementById('mgb-force-dark'))return;var s=document.createElement('style');s.id='mgb-force-dark';" +
        "s.textContent='html{background:#fff!important;filter:invert(1) hue-rotate(180deg)!important;}" +
        "img,picture,video,canvas,svg image{filter:invert(1) hue-rotate(180deg)!important;}';" +
        "(document.head||document.documentElement).appendChild(s);}catch(e){}})()"

    private fun sp(c: Context) = c.applicationContext.getSharedPreferences("mgb_ui", Context.MODE_PRIVATE)

    private fun load(c: Context): JSONObject =
        try { JSONObject(sp(c).getString(KEY, "{}") ?: "{}") } catch (_: Throwable) { JSONObject() }

    fun mode(c: Context, id: String): Int = load(c).optInt(id, AUTO).coerceIn(AUTO, ORIGINAL)

    fun set(c: Context, id: String, v: Int) {
        if (id.isEmpty()) return
        val o = load(c)
        if (v == FORCE || v == ORIGINAL) o.put(id, v) else o.remove(id)
        sp(c).edit().apply { if (o.length() == 0) remove(KEY) else putString(KEY, o.toString()) }.apply()
    }

    /** Tap order: Auto -> Always dark -> Never dark -> Auto. Returns the new value. */
    fun cycle(c: Context, id: String): Int {
        val n = when (mode(c, id)) { AUTO -> FORCE; FORCE -> ORIGINAL; else -> AUTO }
        set(c, id, n)
        return n
    }

    fun label(v: Int): String = when (v) { FORCE -> "Always dark"; ORIGINAL -> "Never dark"; else -> "Auto (follows the app)" }

    /** Extension removed: forget its choice. */
    fun forget(c: Context, id: String) = set(c, id, AUTO)

    /** Mode for an extension page address (an installed extension), AUTO for built-in pages / anything else. */
    fun modeOfUrl(c: Context, url: String?): Int {
        if (url == null) return AUTO
        val id = ExtScreen.extIdOf(url) ?: return AUTO
        return mode(c, id)
    }

    /** Page loaded (ExtScreen window + extension tab): adds the CSS filter when this extension is on Always dark. */
    fun onLoaded(c: Context, view: WebView?, url: String?) {
        if (view == null) return
        try { if (modeOfUrl(c, url) == FORCE) view.evaluateJavascript(CSS_JS, null) } catch (_: Throwable) { }
    }
}
