package com.jabra.mgb

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.TextView
import com.google.android.material.progressindicator.LinearProgressIndicator

/**
 * S10: V77 popup, queue, manga, history and options pages as full-screen windows (title bar, back, reload, close)
 * instead of browser tabs. One window at a time, shown over the browser (a Dialog, so no manifest entry is needed).
 *  - same chrome shim as every extension page (ContentInjector.attachExt), served by EngineHost.ext()
 *  - remote files go through EngineHost.proxyFor (CORS-free, header rules), like the engine and the S9 hidden document
 *  - no tab id (like a Chrome popup): tabs.query still sees the real browser tab, so popup.js works on the active page
 *  - TabsApi sends tabs.create / runtime.openOptionsPage for these pages here; any other page or website link
 *    opened from a window becomes a normal tab and the window closes.
 *  - used by: TabsApi, OffscreenHost (getContexts lists it as POPUP), TabManager (closeAll on destroy)
 */
object ExtScreen {
    private const val TAG = "MGB.screen"
    private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT

    private val PAGES = mapOf(
        "popup.html" to "Media Grabber",
        "queue.html" to "Queue",
        "manga.html" to "Manga",
        "history.html" to "History",
        "options.html" to "Settings",
        "novel/studio.html" to "Novel grabber" // N1
    )

    private var current: Screen? = null
    private var undoSnack: com.google.android.material.snackbar.Snackbar? = null // S12: only one "Closed X" bar at a time

    /** S4: id of an installed extension for https://appassets.androidplatform.net/ext/<id>/..., else null. */
    fun extIdOf(url: String): String? {
        if (!url.startsWith(ContentInjector.EXT_BASE)) return null
        val p = url.removePrefix(ContentInjector.EXT_BASE)
        val seg = p.substringBefore('/')
        return if (p.contains('/') && ExtRegistry.isValidId(seg) && ExtRegistry.get(seg) != null) seg else null
    }

    /** "queue.html" for https://appassets.androidplatform.net/ext/queue.html?x=1, "<id>/popup.html" for an installed extension's .html page, null for any other url. */
    fun pageOf(url: String): String? {
        if (!url.startsWith(ContentInjector.EXT_BASE)) return null
        val p = url.removePrefix(ContentInjector.EXT_BASE).substringBefore('?').substringBefore('#')
        if (p in PAGES) return p
        val id = extIdOf(url) ?: return null
        val rel = p.substringAfter('/')
        val info = ExtRegistry.get(id) ?: return null
        return if (info.enabled && rel.endsWith(".html", true) && ExtRegistry.file(id, rel) != null) "$id/$rel" else null
    }

    /** S4: open popup / options / any .html page of an installed extension in the window. */
    fun openExtPage(host: Context, id: String, rel: String): Boolean =
        open(host, ContentInjector.EXT_BASE + id + "/" + rel.trimStart('/'))

    /** S4: open another window page from inside a window (popup -> options page). Replaces the current window. */
    fun openSibling(url: String): Boolean {
        val a = current?.host() ?: return false
        return open(a, url)
    }

    fun isScreenUrl(url: String): Boolean = pageOf(url) != null

    /** Main thread. host must be the browser Activity. false = could not open (caller falls back to a tab). */
    fun open(host: Context, url: String): Boolean {
        val act = host as? Activity ?: return false
        if (act.isFinishing || act.isDestroyed) return false
        val page = pageOf(url) ?: return false
        closeAll()
        return try {
            val s = Screen(act, url, page, PAGES[page] ?: extIdOf(url)?.let { ExtRegistry.get(it)?.name } ?: "")
            current = s
            s.show()
            true
        } catch (e: Exception) {
            Log.e(TAG, "open failed", e)
            current = null
            false
        }
    }

    fun closeAll() {
        val s = current ?: return
        current = null
        try { s.dismiss() } catch (_: Exception) { }
    }

    fun isScreen(w: WebView): Boolean = current?.web === w

    /** N2: hide the window (it stays alive) and show a "Back" pill, so a page of the browser can be used (Novel grabber: pick chapter text). */
    fun tuck() { current?.tuck() }
    fun untuck() { current?.untuck() }

    /** Url the window shows now ("" if w is not a window WebView). */
    fun urlOf(w: WebView): String = if (current?.web === w) (w.url ?: "") else ""

    fun status(): String = "\nExtension window: " + (current?.let { it.web?.url?.substringAfterLast('/') } ?: "none")

    private class Screen(
        private val act: Activity,
        private val startUrl: String,
        private val page: String,
        private val fallbackTitle: String
    ) : Dialog(act, com.google.android.material.R.style.Theme_Material3_DayNight_NoActionBar) {
        var web: WebView? = null
        var tucked = false
        // shouldInterceptRequest runs on a background thread, where WebView.getUrl() throws: keep the url here (written on the main thread)
        @Volatile private var curUrl: String? = null
        private var pill: android.view.View? = null
        private var box: FrameLayout? = null
        private var titleView: TextView? = null
        private var bar: LinearProgressIndicator? = null

        init {
            setOnDismissListener { cleanup(); undoBar() }
        }

        fun host(): Activity = act

        @SuppressLint("SetJavaScriptEnabled")
        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)
            setContentView(R.layout.screen_ext_page)
            window?.let {
                it.setLayout(MATCH, MATCH)
                it.setBackgroundDrawable(ColorDrawable(act.getColor(R.color.mg_bg)))
                it.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            }
            val container = findViewById<FrameLayout>(R.id.xs_container)
            box = container
            titleView = findViewById<TextView>(R.id.xs_title).also { it.text = fallbackTitle }
            bar = findViewById(R.id.xs_progress)
            findViewById<android.view.View>(R.id.xs_back).setOnClickListener { goBackOrClose() }
            findViewById<android.view.View>(R.id.xs_reload).setOnClickListener { web?.reload() }
            findViewById<android.view.View>(R.id.xs_close).setOnClickListener { dismiss() }

            // the popup is a narrow page: keep it centered on tablets / wide screens
            val popupOf = extIdOf(startUrl)?.let { ExtRegistry.get(it)?.popup?.trimStart('/') }
            if (page == "popup.html" || (popupOf != null && page == extIdOf(startUrl) + "/" + popupOf)) {
                val lp = container.layoutParams as FrameLayout.LayoutParams
                lp.width = minOf(act.resources.displayMetrics.widthPixels, Util.dp(act, 480))
                lp.gravity = Gravity.CENTER_HORIZONTAL
                container.layoutParams = lp
            }
            // S14: popup of an INSTALLED extension = bottom panel (rounded top, dimmed page behind, tap outside closes), like the Thazir tray
            if (popupOf != null && page == extIdOf(startUrl) + "/" + popupOf) {
                window?.let {
                    it.setLayout(minOf(act.resources.displayMetrics.widthPixels, Util.dp(act, 480)), (act.resources.displayMetrics.heightPixels * 0.8f).toInt()) // 0.0.120 (S7): panel is as narrow as its content on wide screens
                    it.setGravity(Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
                    it.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                    it.setDimAmount(0.45f)
                    if (!TabPrefs.reduce(act)) it.setWindowAnimations(R.style.MgbPopupWindow) // 0.0.422: slide up / down, Reduce motion = none
                    val r = Util.dp(act, 24).toFloat()
                    it.setBackgroundDrawable(android.graphics.drawable.GradientDrawable().apply {
                        setColor(act.getColor(R.color.mg_bg))
                        cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
                    })
                }
                setCanceledOnTouchOutside(true)
            }

            val w = WebView(act)
            web = w
            w.settings.apply {
                if (popupOf != null && page == extIdOf(startUrl) + "/" + popupOf) { // S15: a popup made for a wide Chrome popup is scaled to fit instead of cut at the right
                    useWideViewPort = true
                    loadWithOverviewMode = true
                }
                javaScriptEnabled = true
                domStorageEnabled = true
                allowFileAccess = false
                allowContentAccess = false
                mediaPlaybackRequiresUserGesture = false
            }
            w.webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                    val b = bar ?: return
                    b.progress = newProgress
                    b.visibility = if (newProgress in 1..99) android.view.View.VISIBLE else android.view.View.GONE
                }

                override fun onReceivedTitle(view: WebView?, title: String?) {
                    if (!title.isNullOrBlank() && !title.contains("androidplatform")) titleView?.text = title
                }

                override fun onConsoleMessage(m: ConsoleMessage): Boolean {
                    val line = "ui: ${m.message()}  (${m.sourceId()?.substringAfterLast('/')}:${m.lineNumber()})"
                    EngineHost.addLog(line)
                    if (m.messageLevel() == ConsoleMessage.MessageLevel.ERROR) Diag.e(if ((m.sourceId() ?: "").contains("/novel/")) "novel" else if ((m.sourceId() ?: "").contains("/manga")) "manga" else "engine", line) // S83
                    Log.d(TAG, line.take(500))
                    return true
                }

                override fun onCloseWindow(window: WebView?) { dismiss() } // window.close() in a page

                // S14: file inputs inside extension windows (Dialog cannot get activity results: MainActivity owns the launcher)
                override fun onShowFileChooser(
                    webView: WebView?, filePathCallback: android.webkit.ValueCallback<Array<android.net.Uri>>?,
                    fileChooserParams: FileChooserParams?
                ): Boolean = BrowserExtras.chooseFile(filePathCallback, fileChooserParams)
            }
            w.webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                    super.onPageStarted(view, url, favicon)
                    if (!url.isNullOrEmpty()) curUrl = url
                }

                override fun onPageFinished(view: WebView, url: String?) {
                    super.onPageFinished(view, url)
                    ExtDark.onLoaded(act, view, url ?: curUrl ?: startUrl) // 0.0.424: extension on Always dark
                }

                override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
                    super.doUpdateVisitedHistory(view, url, isReload)
                    if (!url.isNullOrEmpty()) curUrl = url
                }

                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                    EngineHost.ext(request.url)?.let { return it }
                    // S4: pages of an installed extension only get the CORS-free proxy for the sites in its host permissions
                    val xid = extIdOf(curUrl ?: startUrl)
                    if (xid != null && !ExtSafety.proxyAllowed(xid, request.url.toString())) return null // S8: same rule as extension pages in a tab
                    return EngineHost.proxyFor(request, -1, xid ?: ContentInjector.EXT_ID) // S7: header rules of that extension
                }

                // S8: crash isolation. A render process that dies in an installed extension's window closes only that window
                // (default = the app is killed). Built-in pages keep the old behaviour.
                override fun onRenderProcessGone(view: WebView, detail: android.webkit.RenderProcessGoneDetail): Boolean {
                    val xid = extIdOf(view.url ?: startUrl) ?: return false
                    if (view !== web) return true // an old, already closed window
                    Diag.w("ext", "extension page crashed", (ExtRegistry.get(xid)?.name ?: xid.take(8)) + (if (detail.didCrash()) " (render process crashed)" else " (killed by the system)"))
                    Util.toast(act, "The extension page stopped working and was closed")
                    dismiss()
                    return true
                }

                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    if (!request.isForMainFrame) return false
                    val u = request.url.toString()
                    if (u.startsWith(ContentInjector.EXT_BASE)) {
                        if (isScreenUrl(u)) return false // another window page: stay here
                        leaveFor(u) // downloader.html, player.html ...: normal tab
                        return true
                    }
                    when (request.url.scheme?.lowercase()) {
                        "http", "https" -> { leaveFor(u); return true }
                        "blob", "data", "about" -> return false
                    }
                    return true // anything else is ignored
                }
            }
            UiMode.applyExt(act, w, startUrl) // 0.0.421 / 0.0.424: per-extension dark choice: dark app = dark popup, app-coloured backdrop (no white flash)
            ContentInjector.attachExt(act, w) // window.chrome + bridge (before the first load)
            container.addView(w, FrameLayout.LayoutParams(MATCH, MATCH))
            w.loadUrl(startUrl)
        }

        fun tuck() {
            if (tucked) return
            tucked = true
            hide()
            val root = act.findViewById<ViewGroup>(android.R.id.content) ?: return
            val tv = TextView(act).apply {
                text = "\u2039  Back to $fallbackTitle"
                setTextColor(android.graphics.Color.WHITE)
                textSize = 14f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                setPadding(Util.dp(act, 18), Util.dp(act, 12), Util.dp(act, 18), Util.dp(act, 12))
                background = android.graphics.drawable.GradientDrawable(
                    android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT,
                    intArrayOf(act.getColor(R.color.mg_primary), 0xFF8B5CFF.toInt())
                ).apply { cornerRadius = Util.dp(act, 24).toFloat() }
                elevation = Util.dp(act, 8).toFloat()
                setOnClickListener { untuck() }
            }
            root.addView(tv, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = Util.dp(act, 96) })
            pill = tv
        }

        private fun dropPill() {
            pill?.let { (it.parent as? ViewGroup)?.removeView(it) }
            pill = null
        }

        fun untuck() {
            if (!tucked) return
            tucked = false
            dropPill()
            try { show() } catch (_: Exception) { }
        }

        private fun leaveFor(url: String) {
            if (WebPlay.fromExtPlayerUrl(act, url, null)) { dismiss(); return } // S39
            TabsApi.openTab(url)
            dismiss()
        }

        private fun goBackOrClose() {
            val w = web
            if (w != null && w.canGoBack()) w.goBack() else dismiss()
        }

        @Suppress("OVERRIDE_DEPRECATION")
        override fun onBackPressed() { goBackOrClose() }

        /** S11: "Closed X  Undo" bar after the window of an installed extension closes (not when another window replaced it). */
        private fun undoBar() {
            val name = extIdOf(startUrl)?.let { ExtRegistry.get(it)?.name } ?: return
            val url = startUrl
            val root = act.findViewById<android.view.View>(android.R.id.content) ?: return
            root.post {
                if (current != null || act.isFinishing || act.isDestroyed) return@post
                try { undoSnack?.dismiss() } catch (_: Exception) { }
                val bar = com.google.android.material.snackbar.Snackbar.make(root, "Closed $name", 2500)
                    .setAction("Undo") { open(act, url) }
                undoSnack = bar
                bar.show()
                SwipeAway.snack(bar, TabPrefs.reduce(act)) // 0.0.281: swipe the bar away
                // S12: hard stop, so the bar can never stay (a11y services / queued bars can make Snackbar longer than asked)
                root.postDelayed({ try { bar.dismiss() } catch (_: Exception) { } }, 3000)
            }
        }

        private fun cleanup() {
            dropPill()
            tucked = false
            val w = web ?: return
            web = null
            if (current === this) current = null
            ChromeBridge.unbind(w)
            try { w.stopLoading() } catch (_: Exception) { }
            w.webChromeClient = null
            val c = box
            c?.removeView(w)
            val destroy = Runnable { try { w.destroy() } catch (e: Exception) { Log.e(TAG, "destroy", e) } }
            if (c != null) c.post(destroy) else destroy.run()
        }
    }
}
