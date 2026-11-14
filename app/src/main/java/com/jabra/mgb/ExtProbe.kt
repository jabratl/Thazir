package com.jabra.mgb

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONObject
import org.json.JSONTokener

/**
 * 0.0.426 (S1): EXTENSION COMPAT PROBE. Measures, changes nothing.
 * Runs only when the Report center is built (DiagReport.build). Three facts for the report:
 *  1. WebView package + version (WebView.getCurrentWebViewPackage)
 *  2. CSS features some extensions use (color-mix, oklch, nesting, @layer, @property, :has, dvh, inset, backdrop-filter, container-type),
 *     asked from a throw-away WebView (created, used once, destroyed). Result kept for the rest of the process.
 *  3. Page theme of the CURRENT tab as an extension content script would see it (html class, data-theme, prefers-color-scheme,
 *     body colours, readyState) + the Thazir side (app dark, Dark pages, site rule, Always / Never dark extensions).
 * Every part is wrapped: a failure = a line "not measured". Private tabs and extension tabs are never read.
 */
object ExtProbe {
    private const val CSS_TIMEOUT_MS = 2500L
    private const val SNAP_TIMEOUT_MS = 1500L

    val FEATURES = listOf("color-mix", "oklch", "nesting", "layer", "property", "has", "dvh", "inset", "backdrop-filter", "container-type")

    private val CSS_JS = "(function(){var r={};function t(k,f){try{r[k]=!!f()}catch(e){r[k]=false}}" +
        "t('color-mix',function(){return CSS.supports('color','color-mix(in srgb,red 50%,blue)')});" +
        "t('oklch',function(){return CSS.supports('color','oklch(0.5 0.1 200)')});" +
        "t('nesting',function(){var s=new CSSStyleSheet();s.replaceSync('.a{&.b{color:red}}');return !!(s.cssRules[0]&&s.cssRules[0].cssRules&&s.cssRules[0].cssRules.length===1)});" +
        "t('layer',function(){return typeof CSSLayerBlockRule==='function'});" +
        "t('property',function(){return typeof CSS.registerProperty==='function'});" +
        "t('has',function(){return CSS.supports('selector(:has(a))')});" +
        "t('dvh',function(){return CSS.supports('height','1dvh')});" +
        "t('inset',function(){return CSS.supports('inset','0')});" +
        "t('backdrop-filter',function(){return CSS.supports('backdrop-filter','blur(1px)')||CSS.supports('-webkit-backdrop-filter','blur(1px)')});" +
        "t('container-type',function(){return CSS.supports('container-type','inline-size')});" +
        "return JSON.stringify(r)})()"

    private const val THEME_JS = "(function(){try{var d=document.documentElement,b=document.body,cs=b?getComputedStyle(b):null;" +
        "return JSON.stringify({cls:String(d.className||'').slice(0,80),th:d.getAttribute('data-theme')||'',mq:matchMedia('(prefers-color-scheme: dark)').matches," +
        "bg:cs?cs.backgroundColor:'',fg:cs?cs.color:'',rs:document.readyState,sch:getComputedStyle(d).colorScheme||''})}catch(e){return JSON.stringify({err:String(e).slice(0,60)})}})()"

    @Volatile private var css: Map<String, Boolean>? = null
    @Volatile private var snapLines: List<String> = listOf("Page theme: not measured")

    private val main = Handler(Looper.getMainLooper())

    // ------------------------------------------------------------------ facts for the report

    fun webView(): String = try {
        WebView.getCurrentWebViewPackage()?.let { it.packageName + " " + it.versionName } ?: "unknown"
    } catch (_: Throwable) { "unknown" }

    fun webViewMajor(): Int = try { webView().substringAfterLast(' ').substringBefore('.').toInt() } catch (_: Throwable) { 0 }

    /** Names of the CSS features the WebView does not have (empty = all there, or not measured yet). */
    fun cssMissing(): List<String> = css?.filter { !it.value }?.keys?.toList() ?: emptyList()

    // ------------------------------------------------------------------ measuring (main thread)

    /**
     * Main thread. Measures what is not known yet, then calls cb once (always, also on a timeout or an error).
     * tab = the tab whose page theme is read (null / private / extension / not a web page = skipped).
     */
    fun ensure(ctx: Context, tab: TabManager.Tab?, cb: () -> Unit) {
        var done = false
        fun fin() { if (!done) { done = true; try { cb() } catch (_: Throwable) { } } }
        val afterCss = {
            try { themeSnapshot(ctx, tab) { fin() } } catch (_: Throwable) { fin() }
        }
        try {
            if (css != null) afterCss() else runCssProbe(ctx) { afterCss() }
        } catch (_: Throwable) { fin() }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun runCssProbe(ctx: Context, next: () -> Unit) {
        var done = false
        var web: WebView? = null
        fun fin() {
            if (done) return
            done = true
            val w = web
            web = null
            if (w != null) main.post { try { w.stopLoading(); w.destroy() } catch (_: Throwable) { } }
            next()
        }
        try {
            val w = WebView(ctx.applicationContext)
            web = w
            w.settings.javaScriptEnabled = true
            w.settings.allowFileAccess = false
            w.settings.allowContentAccess = false
            w.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String?) {
                    try {
                        view.evaluateJavascript(CSS_JS) { raw ->
                            try {
                                val s = JSONTokener(raw ?: "null").nextValue() as? String
                                if (s != null) {
                                    val o = JSONObject(s)
                                    val m = HashMap<String, Boolean>()
                                    for (k in FEATURES) m[k] = o.optBoolean(k, false)
                                    css = m
                                }
                            } catch (_: Throwable) { }
                            fin()
                        }
                    } catch (_: Throwable) { fin() }
                }
            }
            main.postDelayed({ fin() }, CSS_TIMEOUT_MS)
            w.loadDataWithBaseURL("https://probe.invalid/", "<!doctype html><html><body></body></html>", "text/html", "utf-8", null)
        } catch (_: Throwable) { fin() }
    }

    private fun themeSnapshot(ctx: Context, tab: TabManager.Tab?, next: () -> Unit) {
        val thazir = ArrayList<String>()
        try {
            val url = tab?.url
            thazir.add("Thazir: app dark " + yn(UiMode.appDark(ctx)) + ", Dark pages " + yn(UiMode.darkPages(ctx)) +
                (if (url != null && url.startsWith("http")) ", this page darkened " + yn(SiteDark.effective(ctx, url)) else ""))
            thazir.add("Match page theme: " + ExtTheme.label(ExtTheme.mode(ctx)))
            val forced = ExtRegistry.all().filter { ExtDark.mode(ctx, it.id) != ExtDark.AUTO }
            if (forced.isNotEmpty()) thazir.add("Extensions with own Dark mode: " + forced.joinToString(", ") { Diag.mask(it.name.take(24)) + "=" + (if (ExtDark.mode(ctx, it.id) == ExtDark.FORCE) "always" else "never") })
        } catch (_: Throwable) { thazir.add("Thazir side: not measured") }

        val web = try { tab?.web } catch (_: Throwable) { null }
        if (tab == null || web == null || tab.isExt || tab.priv || !tab.url.startsWith("http")) {
            snapLines = listOf("Page theme: " + (if (tab == null) "no tab" else if (tab.priv) "private tab (not read)" else if (tab.isExt) "extension page (not read)" else "not a web page")) + thazir
            next()
            return
        }
        var done = false
        fun fin(line: String) {
            if (done) return
            done = true
            snapLines = listOf(line) + thazir
            next()
        }
        try {
            main.postDelayed({ fin("Page theme: not measured (timeout)") }, SNAP_TIMEOUT_MS)
            web.evaluateJavascript(THEME_JS) { raw ->
                try {
                    val s = JSONTokener(raw ?: "null").nextValue() as? String
                    val o = JSONObject(s ?: "{}")
                    if (o.has("err")) fin("Page theme: not readable") else
                        fin("Page theme (current tab): html class \"" + Diag.mask(o.optString("cls")) + "\", data-theme \"" + Diag.mask(o.optString("th")) +
                            "\", prefers-dark " + o.optBoolean("mq") + ", color-scheme \"" + o.optString("sch") + "\", body " + o.optString("bg") + " / " + o.optString("fg") +
                            ", " + o.optString("rs"))
                } catch (_: Throwable) { fin("Page theme: not readable") }
            }
        } catch (_: Throwable) { fin("Page theme: not measured") }
    }

    private fun yn(b: Boolean) = if (b) "on" else "off"

    // ------------------------------------------------------------------ report section

    fun section(): DiagReport.Section {
        val lines = ArrayList<String>()
        val wv = webView()
        val major = webViewMajor()
        lines.add("WebView: $wv")
        val c = css
        val miss = cssMissing()
        if (c == null) lines.add("CSS features: not measured")
        else {
            lines.add("CSS features: " + (FEATURES.size - miss.size) + " of " + FEATURES.size + " supported" + (if (miss.isEmpty()) "" else ", missing: " + miss.joinToString(", ")))
            lines.add("  " + FEATURES.joinToString(" ") { it + "=" + (if (c[it] == true) "yes" else "NO") })
        }
        lines.addAll(snapLines)
        val warn = major in 1..119 || miss.isNotEmpty()
        if (major in 1..119) lines.add("WebView is older than 120: extensions with modern CSS may look wrong")
        return DiagReport.Section("extcompat", "Browser", "Extension compat", if (warn) DiagReport.WARN else DiagReport.INFO, lines)
    }
}
