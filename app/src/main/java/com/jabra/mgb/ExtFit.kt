package com.jabra.mgb

import android.content.Context
import android.webkit.WebView
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.util.WeakHashMap

/**
 * 0.0.427 (S2): PAGE WIDTH OF INSTALLED EXTENSION PAGES (popup, options, extension tab). Built-in Media Grabber pages are never touched.
 * Before: a popup without <meta viewport> was laid out at about 980 px and shrunk to the 480 dp window (tiny text, empty area).
 * Pref "ext_fit" in mgb_ui = JSON { "<extension id>": 1 | 2 }  (absent = Auto).
 *  - AUTO (0): the page is laid out at the phone width. After it loads it is measured (max 3 times, 1.5 s in all); if the content
 *    is wider than the window (a fixed desktop popup) the viewport is set once to the measured width (max 900) so the page is
 *    scaled to fit. The width is kept per extension version + page (pref "ext_fit_w") so the next open has no jump.
 *  - PHONE (1): always the phone width.
 *  - DESKTOP (2): old behaviour (wide page scaled down).
 * A page that has its own <meta viewport> is never changed. Popup HEIGHT: the window wraps to the content (40 % to 80 % of the screen, min 240 dp).
 * Mechanism: a document-start script (EXT_ORIGIN, only /ext/<id>/... of an installed extension) adds <meta name=viewport data-mgb-fit>.
 * Takes effect when the page is opened again (popup: close and open it; extension tab: reload).
 */
object ExtFit {
    const val AUTO = 0
    const val PHONE = 1
    const val DESKTOP = 2
    const val KEY = "ext_fit"
    const val KEY_W = "ext_fit_w"
    private const val MAX_W = 900
    private const val MAX_CACHE = 60
    private val DELAYS = longArrayOf(300L, 500L, 700L) // 300 + 500 + 700 = 1.5 s after the page finished

    private fun sp(c: Context) = c.applicationContext.getSharedPreferences("mgb_ui", Context.MODE_PRIVATE)

    private fun load(c: Context, key: String): JSONObject =
        try { JSONObject(sp(c).getString(key, "{}") ?: "{}") } catch (_: Throwable) { JSONObject() }

    fun mode(c: Context, id: String): Int = load(c, KEY).optInt(id, AUTO).coerceIn(AUTO, DESKTOP)

    fun set(c: Context, id: String, v: Int) {
        if (id.isEmpty()) return
        val o = load(c, KEY)
        if (v == PHONE || v == DESKTOP) o.put(id, v) else o.remove(id)
        sp(c).edit().apply { if (o.length() == 0) remove(KEY) else putString(KEY, o.toString()) }.apply()
        if (v != AUTO) dropWidths(c, id)
    }

    /** Tap order: Auto -> Phone -> Desktop -> Auto. Returns the new value. */
    fun cycle(c: Context, id: String): Int {
        val n = when (mode(c, id)) { AUTO -> PHONE; PHONE -> DESKTOP; else -> AUTO }
        set(c, id, n)
        return n
    }

    fun label(v: Int): String = when (v) { PHONE -> "Phone width"; DESKTOP -> "Desktop width"; else -> "Auto (fits the page)" }

    /** Extension removed: forget its choice and its measured widths. */
    fun forget(c: Context, id: String) { set(c, id, AUTO); dropWidths(c, id) }

    /** Number of extensions that are not on Auto (Report center "changed from default"). */
    fun changedCount(c: Context): Int = load(c, KEY).length()

    private fun dropWidths(c: Context, id: String) {
        try {
            val o = load(c, KEY_W)
            val gone = ArrayList<String>()
            val it = o.keys()
            while (it.hasNext()) { val k = it.next(); if (k.startsWith("$id@")) gone.add(k) }
            if (gone.isEmpty()) return
            gone.forEach { o.remove(it) }
            sp(c).edit().apply { if (o.length() == 0) remove(KEY_W) else putString(KEY_W, o.toString()) }.apply()
        } catch (_: Throwable) { }
    }

    /** "<id>@<version>/<page>" for an installed extension page address, else null. */
    private fun keyOf(url: String): String? {
        val id = ExtScreen.extIdOf(url) ?: return null
        val ver = ExtRegistry.get(id)?.version ?: return null
        val rel = url.removePrefix(ContentInjector.EXT_BASE + id + "/").substringBefore('?').substringBefore('#')
        return "$id@$ver/$rel"
    }

    private fun saveWidth(c: Context, key: String, w: Int): Boolean {
        return try {
            val o = load(c, KEY_W)
            if (o.optInt(key, 0) == w) return false
            if (o.length() >= MAX_CACHE && !o.has(key)) { // keep the newest: drop one old entry
                val first = o.keys().asSequence().firstOrNull()
                if (first != null) o.remove(first)
            }
            o.put(key, w)
            sp(c).edit().putString(KEY_W, o.toString()).apply()
            true
        } catch (_: Throwable) { false }
    }

    // ------------------------------------------------------------------ the page script

    /**
     * Document-start script for EXT_ORIGIN pages. null = nothing to do. The extension table (modes, widths, versions) is
     * embedded when the script is built; installed extensions only (built-in pages and unknown ids return at once).
     */
    fun script(c: Context): String? {
        return try {
            val all = ExtRegistry.all()
            if (all.isEmpty()) return null
            val v = JSONObject()
            for (e in all) v.put(e.id, e.version)
            val cfg = JSONObject().put("m", load(c, KEY)).put("w", load(c, KEY_W)).put("v", v)
            "(function(){try{var m=location.pathname.match(/^\\/ext\\/([a-p]{32})\\/(.*)\$/);if(!m)return;var C=" + cfg.toString() + ";" +
                "var id=m[1];if(!C.v[id])return;var md=C.m[id]||0;if(md===2)return;var w='device-width';" +
                "if(md===0){var k=id+'@'+C.v[id]+'/'+m[2];if(C.w[k])w=C.w[k];}" +
                "var e=document.createElement('meta');e.name='viewport';e.content='width='+w;e.setAttribute('data-mgb-fit','1');" +
                "(document.head||document.documentElement).appendChild(e);}catch(x){}})();"
        } catch (_: Throwable) { null }
    }

    /** Popup / options window WebView: add the viewport script. false = not possible (the caller keeps the old wide page). */
    fun attach(c: Context, web: WebView): Boolean {
        return try {
            if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) return false
            val s = script(c) ?: return false
            WebViewCompat.addDocumentStartJavaScript(web, s, setOf(ContentInjector.EXT_ORIGIN))
            true
        } catch (_: Throwable) { false }
    }

    // ------------------------------------------------------------------ measuring

    private const val MEASURE_JS = "(function(){var d=document.documentElement,b=document.body;" +
        "return [Math.max(d.scrollWidth,b?b.scrollWidth:0),Math.max(d.scrollHeight,b?b.scrollHeight:0),window.innerWidth," +
        "document.querySelector('meta[name=viewport]:not([data-mgb-fit])')?1:0]})()"

    private fun applyJs(w: Int) = "(function(){var m=document.querySelector('meta[name=viewport][data-mgb-fit]');" +
        "if(!m){if(document.querySelector('meta[name=viewport]'))return;m=document.createElement('meta');m.name='viewport';m.setAttribute('data-mgb-fit','1');(document.head||document.documentElement).appendChild(m);}" +
        "m.content='width=$w';})()"

    private val gens = WeakHashMap<WebView, Int>()

    /**
     * Main thread, from onPageFinished of an extension window / extension tab. Does nothing for built-in pages.
     * onContentHeight (popup window only) gets the content height in device pixels after every pass.
     */
    fun onLoaded(c: Context, web: WebView?, url: String?, onContentHeight: ((Int) -> Unit)?) {
        if (web == null || url == null) return
        try {
            val id = ExtScreen.extIdOf(url) ?: return
            val key = keyOf(url) ?: return
            val md = mode(c, id)
            val gen = (gens[web] ?: 0) + 1
            gens[web] = gen
            schedule(c.applicationContext, web, key, md, gen, 0, onContentHeight)
        } catch (_: Throwable) { }
    }

    private fun schedule(c: Context, web: WebView, key: String, md: Int, gen: Int, n: Int, onH: ((Int) -> Unit)?) {
        web.postDelayed({ pass(c, web, key, md, gen, n, onH) }, DELAYS[n])
    }

    private fun pass(c: Context, web: WebView, key: String, md: Int, gen: Int, n: Int, onH: ((Int) -> Unit)?) {
        try {
            if (gens[web] != gen || !web.isAttachedToWindow) return
            web.evaluateJavascript(MEASURE_JS) { raw ->
                try {
                    if (gens[web] != gen || !web.isAttachedToWindow) return@evaluateJavascript
                    val a = JSONTokener(raw ?: "null").nextValue() as? JSONArray ?: return@evaluateJavascript
                    val sw = a.optInt(0)
                    val sh = a.optInt(1)
                    val iw = a.optInt(2)
                    val own = a.optInt(3)
                    if (md == AUTO && own == 0 && iw > 0 && sw > iw + 8) {
                        val target = minOf(sw, MAX_W)
                        if (target > iw) {
                            web.evaluateJavascript(applyJs(target), null)
                            if (saveWidth(c, key, target)) ContentInjector.refresh(c) // open extension tabs get the new width on their next load
                        }
                    }
                    if (onH != null && iw > 0 && web.width > 0 && sh > 0) onH((sh * (web.width.toFloat() / iw)).toInt())
                } catch (_: Throwable) { }
            }
            if (n + 1 < DELAYS.size) schedule(c, web, key, md, gen, n + 1, onH)
        } catch (_: Throwable) { }
    }
}
