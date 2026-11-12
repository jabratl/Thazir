package com.jabra.mgb

import android.annotation.SuppressLint
import android.content.Context
import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Message
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.webkit.ProfileStore
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import org.json.JSONArray
import org.json.JSONObject

/**
 * S3: owns all tabs (one WebView each), the tab switcher sheet, popup (window.open) handling
 * and session save/restore. `ctx` must be the Activity.
 * S13: a "home" tab (Tab.home, url HOME_PAGE) shows the native speed dial (HomeView) over a blank WebView;
 * visited pages are written to Bookmarks history.
 * S14: desktop mode per tab, fullscreen video, file chooser / permissions (BrowserExtras), plain link downloads.
 */
class TabManager(
    private val ctx: Context,
    private val container: FrameLayout,
    private val listener: Listener
) {
    interface Listener {
        fun onTabsChanged()
        fun onActiveChanged(tab: Tab?)
        fun onPageInfo(tab: Tab)
    }

    class Tab(val id: Int, var web: WebView, val openerId: Int) { // STEP E: web is replaced when the tab is suspended
        /** STEP E: last time this tab was shown or left (saved in the session, drives Suspend and the Older section). */
        @Volatile var lastActive: Long = System.currentTimeMillis()
        /** STEP E: suspended = its WebView was freed; the page (and history) comes back from savedState / pending when the tab is opened. */
        var suspended: Boolean = false
        var savedState: android.os.Bundle? = null
        var url: String = ""
        /** 0.0.234 (P9): WebView profile name (social_<id>) or null = shared profile. Kept when the tab is suspended / cloned. */
        var socialProfile: String? = null
        /** 0.0.106: opened by window.open / target=_blank: its first pages are checked against the ad lists. */
        var popup: Boolean = false
        var openerUrl: String? = null
        var title: String = ""
        /** S41: HTTPS-only: the http address we upgraded (guards a redirect loop, used for the fallback question). */
        var httpsFrom: String? = null
        var httpsAllow: String? = null
        var progress: Int = 100
        /** Restored tabs are loaded lazily, the first time they are selected. */
        var pending: String? = null
        /** S13: speed dial is shown over this tab until the first navigation. */
        var home: Boolean = false
        /** 0.0.130: Home was opened over a live page of this tab: that page (and its back/forward list) stays under the speed dial. */
        var keepUrl: String? = null
        /** 0.0.130: a Home entry sits ahead of the page we came back to (forward goes to Home again). */
        var homeFwd: Boolean = false
        /** S14: desktop user agent + wide viewport (not saved in the session). */
        var desktop: Boolean = false
        /** 0.0.109: page of an installed extension: desktop user agent + wide viewport (extensions like WebToEpub refuse the mobile one). */
        var extWide: Boolean = false
        /** S26: private tab: own WebView profile (cookies / storage), no history, not saved in the session. */
        var priv: Boolean = false
        /** T3: shows a saved page (.mht from our saved_pages folder): offline flags (PageSave.mark), no ad / https / per-site hooks. */
        var savedPage: Boolean = false
        /** 0.0.296: pinned tab: first in the list, no close X, no swipe-away; saved in the session (key pn). */
        var pinned: Boolean = false
        val loading: Boolean get() = progress < 100
        /** Extension page (downloader.html, player.html ...) served by EngineHost. */
        val isExt: Boolean get() = url.startsWith(ContentInjector.EXT_ORIGIN)
    }

    companion object {
        const val HOME = "https://www.google.com"
        const val PRIVATE_PROFILE = "mgb_private" // S26
        /** S13: marker url of a speed-dial tab (never loaded into the WebView). */
        const val HOME_PAGE = "about:home"
        const val MAX_TABS = 30
        /** STEP E: a tab not opened for this long sits in the "Older" section of the switcher. */
        const val OLD_MS = 7L * 24 * 60 * 60 * 1000
        private const val SUSPEND_KEY = "tab_suspend_min" // prefs mgb_ui: 0 Off, 5, 15
        fun suspendMin(c: Context): Int = c.getSharedPreferences("mgb_ui", Context.MODE_PRIVATE).getInt(SUSPEND_KEY, 0)
        fun setSuspendMin(c: Context, m: Int) { c.getSharedPreferences("mgb_ui", Context.MODE_PRIVATE).edit().putInt(SUSPEND_KEY, m).apply() }
        fun suspendLabel(c: Context): String = suspendMin(c).let { if (it <= 0) "Off" else "After $it min" }
        private const val PREFS = "mgb_session"
        private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
        private var nextId = 1 // never reused while the app runs (the engine keys its state by tab id)
    }

    private val tabs = ArrayList<Tab>()
    private var homeView: HomeView? = null // S13
    /** S28: set by MainActivity: tap on the home search pill focuses the address bar. */
    var onHomeSearch: (() -> Unit)? = null
    private var customView: android.view.View? = null // S14: fullscreen video
    private var customHolder: FrameLayout? = null
    private var customCb: WebChromeClient.CustomViewCallback? = null
    private var fsPortrait = true // S29: orientation before fullscreen video

    init { Bookmarks.init(ctx); ClosedTabs.init(ctx) } // S13, S29

    // ---------- STEP E: suspend background tabs ----------
    private val sweeper = android.os.Handler(android.os.Looper.getMainLooper())
    private val sweepRun = object : Runnable {
        override fun run() {
            try { sweep() } catch (e: Throwable) { android.util.Log.w("Thazir.suspend", "sweep failed", e) }
            sweeper.postDelayed(this, 60_000L)
        }
    }
    init { sweeper.postDelayed(sweepRun, 60_000L) }

    /** Older section: not the open tab, not private, not opened for 7 days. */
    fun isOld(t: Tab): Boolean = t !== active && !t.priv && System.currentTimeMillis() - t.lastActive > OLD_MS

    private fun sweep() {
        val min = suspendMin(ctx)
        if (min <= 0) return
        if (DownloadsApi.active().isNotEmpty() || BgKeep.engineBusy()) return // a download is running: suspend nothing (0.0.366: engine jobs too)
        val now = System.currentTimeMillis()
        for (t in tabs.toList()) {
            if (t === active || t.priv || t.home || t.isExt || t.suspended || t.pending != null || t.loading) continue
            if (now - t.lastActive < min * 60_000L) continue
            if (!t.url.startsWith("http")) continue
            if (TabSwitcher.groupInfoOf(t.id) != null) continue // grouped tabs stay alive
            if (SocialKeep.keep(ctx, tabs, t)) continue // 0.0.252 (D1): social tabs stay alive (switch sm_keep)
            val js = "(function(){var m=document.querySelectorAll('video,audio');if(!m.length)return 0;for(var i=0;i<m.length;i++)if(!m[i].paused&&!m[i].ended)return 1;return 2})()"
            try {
                t.web.evaluateJavascript(js) { r ->
                    val v = r?.trim('"') ?: "0"
                    val music = (ctx.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager).isMusicActive
                    val playing = v == "1" || (v == "2" && music) // plays media (or may play inside a frame)
                    if (!playing && tabs.contains(t) && t !== active && !t.suspended && !t.loading) doSuspend(t)
                }
            } catch (_: Throwable) { }
        }
    }

    /** Frees the WebView of a background tab. Keeps: url, title, thumbnail (TabSwitcher.thumbs), page history (saveState). */
    private fun doSuspend(t: Tab) {
        val old = t.web
        val u = old.url ?: t.url
        if (!u.startsWith("http")) return
        val b = android.os.Bundle()
        try { old.saveState(b) } catch (_: Throwable) { }
        val fresh = createWebView(false, t.socialProfile)
        fresh.webViewClient = client(t)
        fresh.webChromeClient = chrome(t)
        fresh.setOnLongClickListener(longPress(t))
        if (t.desktop) { // same as setDesktop, without the reload
            fresh.settings.userAgentString = BrowserExtras.desktopUa(ctx)
            fresh.settings.useWideViewPort = true
            fresh.settings.loadWithOverviewMode = true
        }
        UiMode.applyPage(fresh, UiMode.darkPages(ctx))
        (old.parent as? ViewGroup)?.removeView(old)
        old.stopLoading()
        ChromeBridge.unbind(old)
        old.webChromeClient = null
        old.destroy()
        t.web = fresh
        ChromeBridge.bindTab(fresh, t.id)
        t.url = u
        t.savedState = if (b.isEmpty) null else b
        t.pending = u
        t.suspended = true
        android.util.Log.i("Thazir.suspend", "suspended tab " + t.id)
    }

    private fun resume(t: Tab) {
        t.suspended = false
        val b = t.savedState
        t.savedState = null
        if (b != null) {
            val ok = try { t.web.restoreState(b) != null } catch (_: Throwable) { false }
            if (ok) t.pending = null // restored with its history; otherwise pending reloads the url
        }
    }

    var active: Tab? = null
        private set
    val count: Int get() = tabs.size

    /** The browser Activity (S10: extension windows are shown over it). */
    fun host(): Context = ctx

    fun all(): List<Tab> = tabs.toList()
    fun byId(id: Int): Tab? = tabs.firstOrNull { it.id == id }

    /** S20: Dark pages switch changed (takes effect on the next page load). */
    fun applyDarkPages() {
        val on = UiMode.darkPages(ctx)
        for (t in tabs) UiMode.applyPage(t.web, on && !t.isExt)
    }

    // ---------- tabs ----------

    /** T1: lazy = the page loads only when the tab is first selected (url waits in pending, like a restored tab). */
    fun newTab(url: String?, openerId: Int = 0, select: Boolean = true, priv: Boolean = false, lazy: Boolean = false, social: String? = null): Tab? {
        if (tabs.size >= MAX_TABS) {
            Util.toast(ctx, "Too many tabs")
            return null
        }
        // S26: a tab opened from a private tab stays private
        val isPriv = priv || (openerId != 0 && tabs.any { it.id == openerId && it.priv })
        if (isPriv && tabs.none { it.priv }) purgePrivate() // drop data left from an earlier run
        // 0.0.234 (P9): own login profile = explicit name (clone / restore), else the opener's, else by address
        val sProf = if (isPriv) null else (social ?: tabs.firstOrNull { it.id == openerId }?.socialProfile ?: SocialMode.profileName(ctx, url))
        val web = createWebView(isPriv, sProf)
        val tab = Tab(nextId++, web, openerId)
        tab.priv = isPriv
        tab.socialProfile = sProf
        web.webViewClient = client(tab)
        web.webChromeClient = chrome(tab)
        web.setOnLongClickListener(longPress(tab)) // S21
        tabs.add(tab)
        ChromeBridge.bindTab(web, tab.id)
        if (url != null) tab.url = url
        if (url != null && ExtScreen.extIdOf(url) != null) { // 0.0.109
            tab.extWide = true
            web.settings.userAgentString = BrowserExtras.desktopUa(ctx)
            web.settings.useWideViewPort = true
            web.settings.loadWithOverviewMode = true
        }
        UiMode.applyPage(web, UiMode.darkPages(ctx) && !tab.isExt) // S20
        if (url == HOME_PAGE) tab.home = true // S13: speed dial, nothing to load
        TabsApi.onCreated(tab) // S6
        if (select) switchTo(tab)
        if (lazy && url != null && !tab.home && url.startsWith("http")) { // T1
            tab.pending = url
            tab.title = Util.host(url)
        } else if (url != null && !tab.home) web.loadUrl(url)
        listener.onTabsChanged()
        return tab
    }

    /**
     * T1: copy of [src] placed right after it: same page, same Back / Forward list, same desktop mode, same group.
     * A suspended tab copies its saved state; a tab that never loaded (pending) copies only the address.
     */
    /** T3: open a saved page (file:// address inside saved_pages) in a new tab with the offline flags. */
    fun openSaved(url: String, title: String): Tab? {
        val t = newTab(null, 0, false) ?: return null
        PageSave.mark(t)
        t.url = url
        t.title = title
        switchTo(t)
        t.web.loadUrl(url)
        return t
    }

    fun cloneTab(src: Tab, select: Boolean = true): Tab? {
        if (src.isExt) return null
        if (src.home) return newTab(HOME_PAGE, 0, select, src.priv)
        val u = src.pending ?: (if (src.suspended) null else src.web.url) ?: src.url
        if (!u.startsWith("http")) return null
        val b: android.os.Bundle? = when {
            src.suspended -> src.savedState?.let { android.os.Bundle(it) }
            src.pending != null -> null
            else -> android.os.Bundle().also { try { src.web.saveState(it) } catch (_: Throwable) { } }
        }
        val t = newTab(null, 0, false, src.priv, false, src.socialProfile) ?: return null
        t.url = u
        t.title = src.title
        if (src.desktop) { // same as setDesktop, without the reload
            t.desktop = true
            t.web.settings.userAgentString = BrowserExtras.desktopUa(ctx)
            t.web.settings.useWideViewPort = true
            t.web.settings.loadWithOverviewMode = true
        }
        val ok = b != null && !b.isEmpty && try { t.web.restoreState(b) != null } catch (_: Throwable) { false }
        if (!ok) t.pending = u // no history to copy: the page loads when the clone is opened
        moveTo(t, tabs.indexOf(src) + 1)
        if (!src.priv) TabSwitcher.joinGroupOf(t.id, src.id)
        if (select) switchTo(t)
        listener.onTabsChanged()
        return t
    }

    // ---- 0.0.302: Background play when the user changes tab. A detached WebView is hidden by Android and its sound stops,
    // so a tab that plays sound stays attached as a 1 px view (behind the new tab) until the sound ends or the tab is opened / closed.
    private val bgHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val bgViews = HashSet<Tab>()
    private val bgCheck = object : Runnable {
        override fun run() {
            val iter = bgViews.iterator()
            val audible = MediaPrefs.audible(ctx)
            while (iter.hasNext()) {
                val t = iter.next()
                if (t === active || !tabs.contains(t) || !audible) { if (t !== active) try { container.removeView(t.web) } catch (_: Exception) { }; iter.remove() }
            }
            if (bgViews.isNotEmpty()) bgHandler.postDelayed(this, 5000)
        }
    }
    private fun keepPlaying(t: Tab): Boolean {
        if (!MediaPrefs.bg(ctx) || t.priv || t.home || t.isExt || !MediaPrefs.audible(ctx)) return false
        val h = SiteConfig.hostOf(t.web.url ?: t.url)
        if (h != "youtube.com" && !h.endsWith(".youtube.com") && h != "youtu.be") return false
        t.web.layoutParams = FrameLayout.LayoutParams(1, 1)
        t.web.alpha = 0.01f
        t.web.onResume(); t.web.resumeTimers()
        bgViews.add(t)
        bgHandler.removeCallbacks(bgCheck); bgHandler.postDelayed(bgCheck, 5000)
        return true
    }

    fun switchTo(tab: Tab) {
        if (active === tab) return
        exitFullscreen() // S14
        active?.let { it.lastActive = System.currentTimeMillis(); TabSwitcher.snap(it); if (!keepPlaying(it)) container.removeView(it.web); it.web.animate().cancel(); it.web.alpha = 1f; it.web.scaleX = 1f; it.web.scaleY = 1f; it.web.alpha = if (bgViews.contains(it)) 0.01f else 1f } // S32: thumbnail of the tab we leave (0.0.302: a tab that plays sound stays attached, 1 px)
        active = tab
        tab.lastActive = System.currentTimeMillis() // STEP E
        if (tab.suspended) resume(tab) // STEP E: history comes back, or pending reloads the page below
        (tab.web.parent as? ViewGroup)?.removeView(tab.web) // 0.0.302: it may still be attached as a 1 px background-play view
        tab.web.alpha = 1f
        container.addView(tab.web, FrameLayout.LayoutParams(MATCH, MATCH))
        if (!TabPrefs.reduce(ctx)) { // S21f: not with Reduce motion
            tab.web.alpha = 0f // S21c: soft cross-fade when a tab becomes active
            tab.web.scaleX = 0.97f; tab.web.scaleY = 0.97f // S21d: new / switched page grows in a little
            tab.web.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(190).setInterpolator(android.view.animation.DecelerateInterpolator(1.5f)).start()
        }
        tab.web.requestFocus()
        TabsApi.onActivated(tab) // S6
        tab.pending?.let { tab.pending = null; tab.web.loadUrl(it) }
        syncHome() // S13
        listener.onActiveChanged(tab)
    }

    /**
     * 0.0.293: Chrome-style bar swipe. dir +1 = next tab, -1 = previous (same kind only: normal <-> normal, private <-> private; Home / extension tabs count).
     * The page slides in from the side we are moving to. Returns false at the first / last tab (caller shows a small bounce).
     */
    fun switchRel(dir: Int): Boolean {
        val cur = active ?: return false
        val same = tabs.filter { it.priv == cur.priv }
        val i = same.indexOf(cur)
        val to = same.getOrNull(i + dir) ?: return false
        cur.web.animate().cancel(); cur.web.translationX = 0f // a fast second swipe: the tab we leave is not left shifted
        switchTo(to)
        if (!TabPrefs.reduce(ctx)) {
            to.web.translationX = container.width * 0.25f * dir
            to.web.animate().translationX(0f) // joins the fade / grow animation that switchTo just started (no cancel)
        }
        return true
    }

    fun close(tab: Tab) {
        val idx = tabs.indexOf(tab)
        if (idx < 0) return
        val wasActive = active === tab
        if (wasActive) exitFullscreen() // S14
        if (!tab.priv && !tab.isExt && !tab.home) { // S29: recently closed tabs
            val cu = tab.web.url ?: tab.url
            if (cu.startsWith("http")) ClosedTabs.add(tab.title, cu)
        }
        tabs.removeAt(idx)
        TabsApi.onRemoved(tab) // S6
        bgViews.remove(tab)
        if (wasActive || tab.web.parent === container) { // 0.0.302: also a 1 px background-play view
            container.removeView(tab.web)
        }
        if (wasActive) {
            active = null
        }
        tab.web.stopLoading()
        ChromeBridge.unbind(tab.web)
        tab.web.webChromeClient = null
        container.post {
            tab.web.destroy()
            if (tab.priv && tabs.none { it.priv }) purgePrivate() // S26: last private tab closed = wipe its data
        }
        if (wasActive) {
            val next = tabs.firstOrNull { it.id == tab.openerId }
                ?: tabs.getOrNull(minOf(idx, tabs.size - 1))
            if (next != null) switchTo(next) else newTab(HOME_PAGE) // S13
        }
        listener.onTabsChanged()
    }

    fun destroyAll() {
        sweeper.removeCallbacks(sweepRun) // STEP E
        exitFullscreen() // S14
        ExtScreen.closeAll() // S10
        BookmarksScreen.closeAll() // S13
        homeView?.let { container.removeView(it) }
        homeView = null
        for (t in tabs) {
            TabsApi.onRemoved(t) // S6
            container.removeView(t.web)
            ChromeBridge.unbind(t.web)
            t.web.destroy()
        }
        tabs.clear()
        active = null
    }

    // ---------- session ----------

    fun saveSession() {
        val arr = JSONArray()
        val idx = HashMap<Int, Int>() // S41: tab id -> position in the saved list (groups)
        val ts = JSONArray() // STEP E: last opened time of each saved tab (same order)
        val tp = JSONArray() // 0.0.276 (C5): login profile name of each saved tab (same order), "" = none
        val pn = JSONArray() // 0.0.296: pinned flag of each saved tab (same order)
        var activeIdx = 0
        for (t in tabs) {
            if (t.priv) continue // S26: private tabs are never saved
            val u = if (t.home) HOME_PAGE else (t.web.url ?: t.url)
            if (u.isBlank() || u == "about:blank" || u.startsWith(ContentInjector.EXT_ORIGIN)) continue
            if (t === active) activeIdx = arr.length()
            idx[t.id] = arr.length()
            arr.put(u)
            ts.put(t.lastActive)
            tp.put(t.socialProfile ?: "")
            pn.put(if (t.pinned) 1 else 0)
        }
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("tabs", arr.toString())
            .putString("ts", ts.toString())
            .putString("tp", tp.toString())
            .putString("pn", pn.toString())
            .putString("groups", TabSwitcher.exportGroups(idx))
            .putInt("active", activeIdx)
            .apply()
    }

    /** Returns false when there is nothing to restore (caller opens the home page). */
    fun restoreSession(): Boolean {
        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val raw = sp.getString("tabs", null) ?: return false
        val arr = try { JSONArray(raw) } catch (_: Exception) { return false }
        if (arr.length() == 0) return false
        val act = sp.getInt("active", 0).coerceIn(0, arr.length() - 1)
        val ts = try { JSONArray(sp.getString("ts", "[]") ?: "[]") } catch (_: Exception) { JSONArray() } // STEP E
        val tp = try { JSONArray(sp.getString("tp", "[]") ?: "[]") } catch (_: Exception) { JSONArray() } // 0.0.276 (C5)
        val pn = try { JSONArray(sp.getString("pn", "[]") ?: "[]") } catch (_: Exception) { JSONArray() } // 0.0.296
        var target: Tab? = null
        val ids = ArrayList<Int>()
        for (i in 0 until arr.length()) {
            val u = arr.getString(i)
            val keep = tp.optString(i, "").takeIf { it.startsWith("social_") && it.endsWith("_2") && SocialMode.sepSupported() } // 0.0.276 (C5): only Account 2 names are kept as saved
            val t = newTab(null, 0, false, false, false, keep ?: SocialMode.profileName(ctx, u)) ?: break // 0.0.234 (P9)
            ids.add(t.id)
            t.url = u
            t.pinned = pn.optInt(i, 0) == 1 // 0.0.296
            ts.optLong(i, 0L).let { if (it > 0L) t.lastActive = it } // STEP E
            if (u == HOME_PAGE) t.home = true else t.pending = u // S13
            if (PageSave.isSaved(ctx, u)) PageSave.mark(t) // T3: a saved page reloads from its file with the offline flags
            if (i == act) target = t
        }
        sp.getString("groups", null)?.let { g -> TabSwitcher.importGroups(g) { i -> ids.getOrNull(i) } } // S41
        val sel = target ?: tabs.firstOrNull() ?: return false
        switchTo(sel)
        return true
    }

    // ---------- WebView ----------

    @SuppressLint("SetJavaScriptEnabled")
    /** S26: delete the private WebView profile (cookies, storage, cache). No-op when multi profile is not supported. */
    private fun purgePrivate() {
        try {
            if (WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) {
                ProfileStore.getInstance().deleteProfile(PRIVATE_PROFILE)
            }
        } catch (e: Exception) { /* profile still in use or missing: ignore */ }
    }

    private fun createWebView(priv: Boolean = false, social: String? = null): WebView {
        val w = WebView(ctx)
        if (priv) { // S26: must happen before settings / first load
            if (WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) {
                try { WebViewCompat.setProfile(w, PRIVATE_PROFILE) } catch (e: Exception) { Util.toast(ctx, "Private tab: cookies are shared with normal tabs") }
            } else Util.toast(ctx, "Private tab: this WebView shares cookies with normal tabs")
        } else if (social != null) { // 0.0.234 (P9): own cookies per social site, before settings / first load
            try { WebViewCompat.setProfile(w, social) } catch (e: Exception) { Diag.w("social", "profile $social: " + (e.message ?: ""), "") }
        }
        w.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            javaScriptCanOpenWindowsAutomatically = true
            setSupportMultipleWindows(true)
            builtInZoomControls = true
            displayZoomControls = false
            useWideViewPort = true
            loadWithOverviewMode = true
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        }
        val cm = CookieManager.getInstance()
        cm.setAcceptCookie(true)
        cm.setAcceptThirdPartyCookies(w, true)
        w.setOnScrollChangeListener { _, _, y, _, oy -> onScroll?.invoke(y - oy) } // S33: hide bar on scroll
        PullRefresh.attach(w, container) { w.reload() } // S45: pull down at the top = reload
        ContentInjector.attach(ctx, w) // S5: V77 content scripts + chrome shim (before first load)
        Userscripts.attach(ctx, w, priv) // T7: userscripts (before first load; also for a WebView made again after suspend)
        if (!priv || PwScreen.privFill(ctx)) w.addJavascriptInterface(PasswordStore.Bridge(this), "MGBPW") // S30: save-login hook (before first load)
        if (!priv) w.addJavascriptInterface(WebVideo.Bridge(this), "MGBVID") // S45: in-page video button
        w.addJavascriptInterface(SocialApp.Bridge(this, w), "MGBSOC") // 0.0.305: share sheet / keep screen on / haptic tick (social pages only call it)
        if (!priv) w.addJavascriptInterface(WebNotify.Bridge(this, w), "MGBNOT") // 0.0.284: website notifications
        // S14: plain download links (and blob:/data: links) in a tab go to the native downloader
        w.setDownloadListener { url, ua, disp, mime, _ ->
            try {
                val item = DownloadsApi.startPlain(url, ua, disp, mime, w.url, w)
                if (item == null) Util.toast(ctx, "Cannot download this link")
                else Util.toast(ctx, "Downloading " + item.rel.substringAfterLast('/'))
            } catch (e: Exception) {
                Util.toast(ctx, "Download failed to start")
            }
        }
        return w
    }

    private fun client(tab: Tab) = object : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
            val u = request?.url ?: return false
            // S6: a web page may not navigate to the extension origin (only our own code opens those)
            if (u.toString().startsWith(ContentInjector.EXT_ORIGIN) && !tab.isExt) return true
            // 0.0.106: a script / server redirect (no tap) to an ad host is cancelled; an ad pop-up tab is closed
            if (!tab.isExt && request.isForMainFrame && !request.hasGesture() &&
                AdBlock.blocksPage(u.toString(), if (tab.popup) tab.openerUrl else tab.url)) {
                Util.toast(ctx, "Redirect blocked")
                if (tab.popup) container.post { close(tab) }
                return true
            }
            // 0.0.224: Social mode: user agent follows the page (chat = desktop, feed / Instagram = mobile); app links stay in the browser
            if (!tab.isExt && request.isForMainFrame && u.scheme == "https" && view != null && SocialMode.ensure(ctx, view, u.toString(), tab.desktop)) { view.loadUrl(u.toString()); return true }
            if (!tab.isExt && request.isForMainFrame && SocialMode.blockApp(ctx, tab.url, u)) { Util.toast(ctx, "Staying in the browser"); return true }
            if (!tab.isExt && request.isForMainFrame && MediaPrefs.blockYtApp(ctx, u)) { Util.toast(ctx, "Staying in the browser"); return true } // 0.0.296: Media > Open YouTube links in Thazir
            // 0.0.271 (D4): a tapped link that leaves a social site asks: new tab / here / cancel (pref OFF = false)
            if (!tab.isExt && !tab.popup && request.isForMainFrame && view != null && SocialExt.intercept(ctx, tab.url, u, request.hasGesture(), view) { s -> newTab(s, tab.id, true) }) return true
            // S41: HTTPS-only mode
            if (!tab.isExt && request.isForMainFrame && u.scheme == "http" && HttpsOnly.on(ctx) && tab.httpsAllow != u.host && tab.httpsFrom != u.toString()) {
                val up = HttpsOnly.upgrade(u.toString())
                if (up != null) { tab.httpsFrom = u.toString(); view?.loadUrl(up); return true }
            }
            // S39: a link / redirect to a video file or stream opens the built-in player (not Chromium's media page)
            if (!tab.isExt && request.isForMainFrame && playLink(tab, u.toString())) return true
            // T8: a https .user.js link asks to install the userscript instead of showing its text
            if (!tab.isExt && !tab.priv && request.isForMainFrame && Userscripts.isUserJs(u.toString()) && ctx is android.app.Activity) {
                Userscripts.offerInstall(ctx, u.toString()); return true
            }
            when (u.scheme?.lowercase()) {
                null, "http", "https", "about", "data", "blob", "file", "javascript" -> return false
            }
            if (request.isForMainFrame) openExternal(u.toString())
            return true
        }

        // S6: extension pages (https://appassets.androidplatform.net/ext/...) are served from assets
        override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
            val r = request ?: return null
            if (tab.savedPage) return null // T3
            if (!r.url.toString().startsWith(ContentInjector.EXT_ORIGIN)) {
                // S8: extension page -> network goes through the engine proxy (CORS + header rules); web page -> sniff
                if (tab.isExt) {
                    val xid = ExtScreen.extIdOf(tab.url)
                    // S8: an installed extension page only gets the proxy (CORS-free + its header rules) for the sites in its host permissions; others load as a normal page would
                    if (xid != null && !ExtSafety.proxyAllowed(xid, r.url.toString())) return null
                    return EngineHost.proxyFor(r, tab.id, xid ?: ContentInjector.EXT_ID) // S7: rules of the extension that owns this page
                }
                // 0.0.119 (S5): webRequest events + declarativeNetRequest block / redirect of installed extensions (normal tabs only)
                if (!tab.priv) HeaderModes.pageRequest(r, tab.id, tab.url)?.let { return it }
                if (AdBlock.shouldBlock(r, tab.url)) return AdBlock.emptyResponse() // S19
                Sniffer.onRequest(tab, r)
                SocialSave2.intercept(ctx, tab.url, r)?.let { return it } // 0.0.266 (C7): no video files on mobile data (pref OFF = null)
                SocialNsfw.intercept(ctx, tab.url, r)?.let { return it } // 0.0.232 (P8a): experimental picture check (pref OFF / no model = null)
                return null
            }
            if (!r.isForMainFrame && !tab.isExt) return EngineHost.extPublic(r.url, tab.url, r.requestHeaders?.get("Referer")) // 0.0.108: only web_accessible_resources of installed extensions
            return EngineHost.ext(r.url)
        }

        override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: android.webkit.WebResourceError?) {
            super.onReceivedError(view, request, error)
            val r = request ?: return
            if (!tab.isExt && !tab.priv) HeaderModes.pageError(r, tab.id, tab.url, error?.description?.toString() ?: "net::ERR_FAILED") // 0.0.119 (S5): webRequest.onErrorOccurred
            if (r.isForMainFrame) Diag.w("page", "load error " + (error?.errorCode ?: 0) + " " + (error?.description ?: ""), if (tab.priv) "(private tab)" else r.url.toString()) // S83
            SocialError.show(ctx, view, r, error?.errorCode ?: 0, tab.isExt) // 0.0.263 (D5)
            val from = tab.httpsFrom ?: return
            if (!r.isForMainFrame || r.url.scheme != "https" || android.net.Uri.parse(from).host != r.url.host) return
            container.post { // S41: no https on this site: ask once
                com.google.android.material.dialog.MaterialAlertDialogBuilder(ctx)
                    .setTitle("No secure connection")
                    .setMessage(r.url.host + " does not open with https. Open the unsecured http page?")
                    .setPositiveButton("Open http") { _, _ -> tab.httpsAllow = r.url.host; tab.web.loadUrl(from) }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        }

        override fun onReceivedHttpError(view: WebView?, request: WebResourceRequest?, errorResponse: WebResourceResponse?) { // S83
            super.onReceivedHttpError(view, request, errorResponse)
            if (request?.isForMainFrame == true) Diag.w("page", "http " + (errorResponse?.statusCode ?: 0), if (tab.priv) "(private tab)" else request.url.toString())
        }

        override fun onRenderProcessGone(view: WebView?, detail: android.webkit.RenderProcessGoneDetail?): Boolean { // S83: same result as the default (false), only logged
            Diag.e("tab", if (detail?.didCrash() == true) "tab crashed (render process)" else "tab killed by the system (low memory?)", if (tab.priv) "(private tab)" else tab.url)
            return false
        }

        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
            if (tab.popup && !tab.isExt && url != null && AdBlock.blocksPage(url, tab.openerUrl)) { // 0.0.106: ad pop-up
                Util.toast(ctx, "Pop-up blocked")
                container.post { close(tab) }
                return
            }
            if (view != null && !view.canGoForward() && url != tab.url) tab.homeFwd = false // 0.0.130: a new page cuts the Home entry ahead
            tab.url = url ?: tab.url
            tab.progress = 0
            if (tab.home) { // S13
                val kept = tab.keepUrl // 0.0.130: same page loading under Home keeps Home; another address leaves it
                if (kept == null || (url != null && url != kept)) { tab.home = false; tab.keepUrl = null; tab.homeFwd = false; syncHome() }
            }
            if (!tab.isExt && view != null) { // S41: text size of this site
                val z = TextZoom.get(ctx, Util.host(url ?: ""))
                if (view.settings.textZoom != z) view.settings.textZoom = z
                SocialDark.apply(ctx, view, url) // 0.0.275 (C8): dark page per social site (no site set = returns at once)
            }
            if (!tab.isExt && !tab.savedPage && view != null) { // S29: per-site switches (T3: not for a saved page, JS stays off)
                val jsOff = SiteConfig.has(url, SiteConfig.JS_OFF)
                if (view.settings.javaScriptEnabled == jsOff) view.settings.javaScriptEnabled = !jsOff
                if (SiteConfig.has(url, SiteConfig.DESKTOP) && !tab.desktop) container.post { setDesktop(tab, true) }
            }
            if (!tab.isExt && view != null) YtAds.flag(view, url) // 0.0.105
            if (!tab.isExt && view != null) SiteMute.flag(view, url) // 0.0.296: Mute site
            if (!tab.isExt && !tab.savedPage && view != null) WebNotify.inject(view) // 0.0.284
            if (!tab.isExt && !tab.savedPage && view != null && url != null && SocialMode.ensure(ctx, view, url, tab.desktop)) container.post { view.stopLoading(); view.loadUrl(url) } // 0.0.224
            TabsApi.onUpdated(tab, JSONObject().put("status", "loading").put("url", tab.url)) // S6
            listener.onPageInfo(tab)
        }

        override fun onPageFinished(view: WebView?, url: String?) {
            tab.url = url ?: tab.url
            tab.progress = 100
            tab.popup = false // 0.0.106: the pop-up passed the check
            if (!tab.isExt && !tab.savedPage && view != null) WebNotify.inject(view) // 0.0.284
            if (tab.desktop && !tab.isExt) view?.evaluateJavascript(BrowserExtras.DESKTOP_JS, null) // S14
            if (tab.extWide && tab.isExt) view?.evaluateJavascript(BrowserExtras.DESKTOP_JS, null) // 0.0.109
            if (!tab.isExt && view != null) EngineHost.noteUa(url ?: tab.url, view.settings.userAgentString) // 0.0.110: extension requests to this site use this user agent
            if (!tab.isExt && (!tab.priv || PwScreen.privFill(ctx)) && tab.url.startsWith("http")) view?.evaluateJavascript(PwSmart.hookJs(ctx), null) // S30 (0.0.388: smart hook, old hook = fallback)
            if (!tab.isExt && !tab.priv && tab.url.startsWith("http") && WebVideo.onFor(ctx, tab.url)) view?.evaluateJavascript(WebVideo.JS, null) // S45
            if (!tab.isExt) YtAds.flag(view, tab.url) // 0.0.105
            if (!tab.isExt) SiteMute.flag(view, tab.url) // 0.0.296
            if (!tab.isExt) CosmeticFilter.inject(ctx, view, tab.url) // S42
            if (!tab.isExt) SocialMode.inject(ctx, view, tab.url, tab.desktop) // 0.0.224
            // S13: browsing history (web pages only, not extension pages)
            if (!tab.isExt && !tab.priv && (tab.url.startsWith("http://") || tab.url.startsWith("https://")))
                Bookmarks.addHistory(view?.title ?: tab.title, tab.url)
            if (!tab.isExt && !tab.savedPage && view != null && AppearancePrefs.forceZoom(ctx) && tab.url.startsWith("http") &&
                !ZoomTools.optedOut(ctx, Util.host(tab.url))) view.evaluateJavascript(ZoomTools.FORCE_JS, null) // A3: Force enable zoom
            SimpleHint.check(ctx, tab, this@TabManager) // A4: returns at once when the switch is OFF
            TabsApi.onUpdated(tab, JSONObject().put("status", "complete")) // S6
            listener.onPageInfo(tab)
        }

        override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
            tab.url = url ?: tab.url
            if (!tab.isExt && !tab.savedPage && view != null && url != null && SocialMode.onRoute(ctx, view, url, tab.desktop)) container.post { view.stopLoading(); view.loadUrl(url) } // 0.0.235 (U1)
            listener.onPageInfo(tab)
        }
    }

    private fun chrome(tab: Tab) = object : WebChromeClient() {
        override fun onProgressChanged(view: WebView?, newProgress: Int) {
            tab.progress = newProgress
            if (!tab.isExt && newProgress in 35..99) CosmeticFilter.inject(ctx, view, tab.url) // S42: early, so the banner does not flash
            if (!tab.isExt && newProgress in 35..99) SocialMode.inject(ctx, view, tab.url, tab.desktop) // 0.0.224: early viewport for the chat
            listener.onPageInfo(tab)
        }

        // S45: real favicon per host (not for private tabs / extension pages)
        override fun onReceivedIcon(view: WebView?, icon: android.graphics.Bitmap?) {
            if (icon == null || tab.isExt || tab.priv) return
            val u = view?.url ?: tab.url
            if (u.startsWith("http", true)) Favicons.save(Util.host(u), icon)
        }

        override fun onReceivedTitle(view: WebView?, title: String?) {
            tab.title = title ?: ""
            TabsApi.onUpdated(tab, JSONObject().put("title", tab.title)) // S6
            listener.onPageInfo(tab)
        }

        // window.open / target=_blank: only on a real tap (blocks script popups)
        override fun onCreateWindow(
            view: WebView?, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message?
        ): Boolean {
            if (!isUserGesture || resultMsg == null) return false
            val t = newTab(null, tab.id, true) ?: return false
            val transport = resultMsg.obj as WebView.WebViewTransport
            t.popup = true; t.openerUrl = tab.url // 0.0.106
            transport.webView = t.web
            resultMsg.sendToTarget()
            return true
        }

        override fun onCloseWindow(window: WebView?) {
            tabs.firstOrNull { it.web === window }?.let { close(it) }
        }

        // S14: fullscreen video
        override fun onShowCustomView(view: android.view.View?, callback: CustomViewCallback?) {
            if (view == null) { callback?.onCustomViewHidden(); return }
            showFullscreen(view, callback)
        }

        override fun onHideCustomView() { exitFullscreen() }

        // S14: <input type=file>
        override fun onShowFileChooser(
            webView: WebView?, filePathCallback: ValueCallback<Array<Uri>>?, fileChooserParams: FileChooserParams?
        ): Boolean = BrowserExtras.chooseFile(filePathCallback, fileChooserParams)

        // S14: camera / microphone / location (asks the user first)
        override fun onPermissionRequest(request: PermissionRequest?) {
            if (request == null) return
            BrowserExtras.webPermission(request)
        }

        override fun onGeolocationPermissionsShowPrompt(origin: String?, callback: GeolocationPermissions.Callback?) {
            BrowserExtras.geoPermission(origin, callback)
        }
    }

    private fun openExternal(url: String) {
        try {
            val i = if (url.startsWith("intent:")) Intent.parseUri(url, Intent.URI_INTENT_SCHEME)
            else Intent(Intent.ACTION_VIEW, Uri.parse(url))
            i.addCategory(Intent.CATEGORY_BROWSABLE)
            i.component = null
            i.selector = null
            ctx.startActivity(i)
        } catch (_: Exception) {
            Util.toast(ctx, "No app to open this link")
        }
    }

    // ---------- S39: video / audio links go to the built-in player ----------

    /** True (and the player starts) when url is a direct media file / playlist and the setting is ON. The tab is left as it is. */
    fun playLink(tab: Tab?, url: String): Boolean {
        if (!WebPlay.isMediaUrl(url) || !WebPlay.linksEnabled(ctx)) return false
        val ref = tab?.url?.takeIf { it.startsWith("http") }
        return WebPlay.play(ctx, url, null, ref, tab?.web?.settings?.userAgentString)
    }

    // ---------- S21: long press on a link / image, copy, share ----------

    private fun longPress(tab: Tab) = View.OnLongClickListener { v ->
        val w = v as? WebView ?: return@OnLongClickListener false
        if (tab.isExt || tab.home) return@OnLongClickListener false
        val r = w.hitTestResult
        val type = r.type
        val extra = r.extra
        when (type) {
            WebView.HitTestResult.SRC_ANCHOR_TYPE, WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE -> {
                val h = android.os.Handler(android.os.Looper.getMainLooper(), android.os.Handler.Callback { m ->
                    val link = m.data?.getString("url") ?: extra
                    val img = if (type == WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE) m.data?.getString("src") else null
                    showLinkMenu(tab, link, img, m.data?.getString("title"))
                    true
                })
                w.requestFocusNodeHref(h.obtainMessage())
                true
            }
            WebView.HitTestResult.IMAGE_TYPE -> {
                if (extra != null) showLinkMenu(tab, null, extra)
                true
            }
            else -> false
        }
    }

    private fun showLinkMenu(tab: Tab, link: String?, img: String?, text: String? = null) {
        val l = link ?: ""
        val i = img ?: ""
        val rows = ArrayList<MenuSheet.Item>()
        val ua = tab.web.settings.userAgentString
        val ref = tab.url.takeIf { it.startsWith("http") }
        if (l.startsWith("http")) {
            rows.add(MenuSheet.Item("Open in new tab", R.drawable.ic_add) { newTab(l, tab.id) })
            rows.add(MenuSheet.Item("Preview page", R.drawable.ic_search) { PagePreview.show(ctx, l) { newTab(l, tab.id) } }) // S42
            if (!tab.priv) rows.add(MenuSheet.Item("Open in new tab in group", R.drawable.ic_folder) { TabSwitcher.openInGroupHere(ctx, this, l, tab.id) }) // 0.0.379: Chrome way, this tab joins too
            if (!tab.priv && TabSwitcher.hasGroups()) rows.add(MenuSheet.Item("Open in another group...", R.drawable.ic_folder) { TabSwitcher.openInGroup(ctx, this, l, tab.id) }) // 0.0.379: the old chooser
            rows.add(MenuSheet.Item("Open in New Private Tab", R.drawable.ic_lock) { newTab(l, 0, true, true) })
            if (WebPlay.isMediaUrl(l)) rows.add(MenuSheet.Item("Play in player", R.drawable.ic_play) { WebPlay.play(ctx, l, null, ref, ua) })
            if (!tab.priv) rows.add(MenuSheet.Item(if (Bookmarks.isBookmarked(l)) "Remove bookmark" else "Bookmark link",
                if (Bookmarks.isBookmarked(l)) R.drawable.ic_bookmark else R.drawable.ic_bookmark_border) {
                if (Bookmarks.isBookmarked(l)) { val gone = Bookmarks.item(l); Bookmarks.removeBookmark(l); val hv = (ctx as? android.app.Activity)?.window?.decorView; if (gone != null && hv != null) BmUndo.bookmarks(hv, listOf(gone)) else Util.toast(ctx, "Bookmark removed") } // 0.0.332
                else if (ctx is android.app.Activity) BookmarkFolderPicker.save(ctx, text?.trim().orEmpty(), l) // 0.0.328: ask the folder
                else { Bookmarks.addBookmark(text?.trim().orEmpty(), l); Util.toast(ctx, "Bookmark added") }
            })
            rows.add(MenuSheet.Item("Copy link", R.drawable.ic_list) { copyText(l) })
            if (!text.isNullOrBlank()) rows.add(MenuSheet.Item("Copy text", R.drawable.ic_edit) { copyText(text.trim()) })
            if (LinkGrab.menuOk(l)) rows.add(MenuSheet.Item("Download video / file (smart)", R.drawable.ic_download) { LinkGrab.run(ctx, l) }) // 0.0.410
            rows.add(MenuSheet.Item("Download link", R.drawable.ic_download) { downloadImage(tab, l) })
            rows.add(MenuSheet.Item("Share...", R.drawable.ic_share) { shareText(l) })
        }
        if (i.startsWith("http") || i.startsWith("data:")) {
            if (i.startsWith("http")) {
                rows.add(MenuSheet.Item("Open image in new tab", R.drawable.ic_add) { newTab(i, tab.id) })
                rows.add(MenuSheet.Item("Copy image address", R.drawable.ic_list) { copyText(i) })
            }
            rows.add(MenuSheet.Item("Download image", R.drawable.ic_download) { downloadImage(tab, i) })
            if (i.startsWith("http")) rows.add(MenuSheet.Item("Share image address", R.drawable.ic_share) { shareText(i) })
            if (i.startsWith("http") && SocialNsfw.on(ctx) && SocialMode.on(ctx) && SocialProfiles.of(tab.url) != null) { // 0.0.273 (B7)
                rows.add(MenuSheet.Item("This picture is fine", R.drawable.ic_check) {
                    val was = SocialNsfw.markFine(ctx, i)
                    Util.toast(ctx, if (was) "Marked as fine. Reload the page to see it clear." else "Marked as fine. The AI check will skip it.")
                })
            }
        }
        rows.addAll(ExtMenus.rows(tab, l.ifEmpty { null }, i.ifEmpty { null }, null)) // 0.0.116 (S4): chrome.contextMenus of installed extensions
        if (rows.isEmpty()) return
        val shown = if (l.isNotEmpty()) l else i
        MenuSheet.showLink(ctx, text?.trim().orEmpty().ifBlank { if (l.isEmpty()) "Image" else "" }, shown, rows)
    }

    private fun downloadImage(tab: Tab, url: String) {
        try {
            val item = DownloadsApi.startPlain(url, tab.web.settings.userAgentString, "", "application/octet-stream", tab.url, tab.web)
            if (item == null) Util.toast(ctx, "Cannot download this image")
            else Util.toast(ctx, "Downloading " + item.rel.substringAfterLast('/'))
        } catch (e: Exception) {
            Util.toast(ctx, "Download failed to start")
        }
    }

    fun copyText(s: String) {
        (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager)
            .setPrimaryClip(android.content.ClipData.newPlainText("MGB", SocialClean.maybe(ctx, s))) // 0.0.259 (C4)
        Util.toast(ctx, "Copied")
    }

    fun shareText(s: String) {
        try {
            ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, SocialClean.maybe(ctx, s)), null)) // 0.0.259 (C4)
        } catch (e: Exception) {
            Util.toast(ctx, "Cannot share")
        }
    }

    // ---------- S14: desktop mode + fullscreen video ----------

    /** Desktop site on / off for one tab: user agent, wide viewport, reload. Not for extension pages or the speed dial. */
    fun setDesktop(tab: Tab, on: Boolean) {
        if (tab.isExt || tab.home) return
        tab.desktop = on
        val s = tab.web.settings
        s.userAgentString = if (on) BrowserExtras.desktopUa(ctx) else null // null = WebView default
        s.useWideViewPort = true
        s.loadWithOverviewMode = true
        val p = tab.pending
        if (p != null) { tab.pending = null; tab.web.loadUrl(p) } else tab.web.reload()
    }

    val inFullscreen: Boolean get() = customView != null

    private fun showFullscreen(v: android.view.View, cb: WebChromeClient.CustomViewCallback?) {
        val act = ctx as? Activity
        if (customView != null || act == null) { cb?.onCustomViewHidden(); return }
        val holder = FrameLayout(act)
        holder.setBackgroundColor(Color.BLACK)
        holder.addView(v, FrameLayout.LayoutParams(MATCH, MATCH))
        (act.window.decorView as ViewGroup).addView(holder, FrameLayout.LayoutParams(MATCH, MATCH))
        fsPortrait = Orient.isPortrait(act) // S29
        Orient.forVideo(act) // S35: browser is portrait-locked; fullscreen video may rotate
        customView = v
        customHolder = holder
        customCb = cb
        val c = WindowCompat.getInsetsController(act.window, act.window.decorView)
        c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        c.hide(WindowInsetsCompat.Type.systemBars())
    }

    /** Leave fullscreen video (Back button, tab switch, page asked for it). Does nothing when not fullscreen. */
    fun exitFullscreen() {
        val v = customView ?: return
        val holder = customHolder
        val cb = customCb
        customView = null
        customHolder = null
        customCb = null
        if (holder != null) {
            holder.removeView(v)
            (holder.parent as? ViewGroup)?.removeView(holder)
        }
        (ctx as? Activity)?.let {
            WindowCompat.getInsetsController(it.window, it.window.decorView).show(WindowInsetsCompat.Type.systemBars())
            Orient.restore(it, fsPortrait) // S29 FIX: browser stayed landscape after the video
        }
        cb?.onCustomViewHidden()
    }

    // ---------- S13: speed dial ----------

    /** Show the speed dial over the active tab when it is a home tab, hide it otherwise. */
    fun syncHome() {
        var hv = homeView
        if (hv != null) container.removeView(hv)
        val a = active
        if (a == null || !a.home) return
        if (hv == null) {
            hv = HomeView(
                ctx,
                onOpen = { openUrl(it) },
                onOpenNew = { newTab(it, 0, true, active?.priv == true) }, // S26
                onBookmarks = { (ctx as? android.app.Activity)?.let { BookmarksScreen.open(it, BookmarksScreen.Mode.BOOKMARKS, this) } },
                onHistory = { (ctx as? android.app.Activity)?.let { BookmarksScreen.open(it, BookmarksScreen.Mode.HISTORY, this) } }
            )
            homeView = hv
        }
        hv.priv = a.priv // S26
        hv.onSearch = { onHomeSearch?.invoke() } // S28
        hv.showTop()
        container.addView(hv, FrameLayout.LayoutParams(MATCH, MATCH))
        hv.refresh()
    }

    /** The tab is about to navigate: drop the speed dial now (not when the page starts). */
    fun leaveHome(t: Tab) {
        if (!t.home) return
        t.home = false
        t.keepUrl = null // 0.0.130
        t.homeFwd = false
        if (t === active) syncHome()
    }

    // ---------- 0.0.130: Home inside the same tab ----------

    private fun hasPage(t: Tab): Boolean { val u = t.web.url; return !u.isNullOrBlank() && u != "about:blank" && !u.startsWith("about:") }

    /** Home button: the speed dial opens in THIS tab; the page stays under it (back / forward still reach it). */
    fun goHome() {
        val t = active
        if (t == null || t.isExt) { newTab(HOME_PAGE, 0, true, t?.priv == true); return }
        if (t.home) return
        toHome(t)
    }

    private fun toHome(t: Tab) {
        if (hasPage(t)) {
            TabSwitcher.snap(t) // thumbnail of the page we leave
            t.keepUrl = t.web.url
            t.web.stopLoading()
            t.web.evaluateJavascript("document.querySelectorAll('video,audio').forEach(function(m){try{m.pause()}catch(e){}})", null)
        } else t.keepUrl = null
        t.homeFwd = false
        t.home = true
        if (t === active) syncHome()
        listener.onPageInfo(t)
    }

    /** From Home back to the page that was open before (true = handled). */
    private fun showKept(t: Tab): Boolean {
        if (!t.home || t.keepUrl == null) return false
        t.home = false
        t.keepUrl = null
        t.homeFwd = true
        if (t === active) syncHome()
        listener.onPageInfo(t)
        return true
    }

    fun canBack(t: Tab): Boolean = (t.home && t.keepUrl != null) || t.web.canGoBack()
    fun canFwd(t: Tab): Boolean = !t.home && (t.web.canGoForward() || t.homeFwd)
    fun back(t: Tab) { if (!showKept(t)) t.web.goBack() }
    fun fwd(t: Tab) {
        if (t.home) return
        if (t.web.canGoForward()) t.web.goForward() else if (t.homeFwd) { val u = t.web.url; if (u != null) { toHome(t); t.homeFwd = false } }
    }

    /** Load url in the active tab (an extension page tab gets a new tab instead). */
    fun openUrl(url: String) {
        val t = active
        if (t != null && !t.isExt && playLink(t, url)) return // S39
        if (t == null || t.isExt) { newTab(url); return }
        t.pending = null
        t.url = url
        leaveHome(t)
        t.web.loadUrl(url)
        listener.onPageInfo(t)
    }

    // ---------- tab switcher ----------

    fun allTabs(): List<Tab> = tabs.toList() // S31
    /** 0.0.296: Pin tab / Unpin tab. Pinned tabs sit first (a new pin goes behind the older pins; an unpinned tab goes right behind the pins). */
    fun setPinned(t: Tab, on: Boolean) {
        if (t.pinned == on) return
        t.pinned = on
        moveTo(t, tabs.count { it.pinned && it !== t })
        saveSession()
        listener.onTabsChanged()
    }

    fun moveTo(t: Tab, index: Int) { // S21b: Undo puts a reopened tab back at its old place
        val i = tabs.indexOf(t)
        if (i < 0) return
        tabs.removeAt(i)
        tabs.add(index.coerceIn(0, tabs.size), t)
    }

    /** S33: vertical scroll of any tab WebView (positive = page moves up, finger swipes up). */
    var onScroll: ((Int) -> Unit)? = null
    /** S45: tap on the in-page video button (address of the video or "" / blob:, page title). Set by MainActivity. */
    var onWebVideo: ((String, String) -> Unit)? = null

    fun showSwitcher() {
        (ctx as? android.app.Activity)?.let { TabSwitcher.show(it, this); return } // S31: Thazir switcher; old sheet below is the fallback
        val dlg = BottomSheetDialog(ctx)
        val v = LayoutInflater.from(ctx).inflate(R.layout.sheet_tabs, null)
        val list = v.findViewById<LinearLayout>(R.id.tabs_list)
        val title = v.findViewById<TextView>(R.id.tabs_title)
        v.findViewById<View>(R.id.tabs_new).setOnClickListener {
            newTab(HOME_PAGE) // S13
            dlg.dismiss()
        }
        fun render() {
            list.removeAllViews()
            title.text = "Tabs (${tabs.size})"
            for (t in tabs.toList()) list.addView(row(t, dlg) { render() })
        }
        render()
        dlg.setContentView(v)
        dlg.behavior.skipCollapsed = true
        dlg.show()
        dlg.behavior.state = BottomSheetBehavior.STATE_EXPANDED
    }

    private fun titleOf(t: Tab): String =
        if (t.home) (if (t.priv) "Private home" else "Home") else t.title.ifBlank { Util.host(t.url) }.ifBlank { "New tab" }

    private fun row(t: Tab, dlg: BottomSheetDialog, onChanged: () -> Unit): View {
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = Util.dp(ctx, 60)
            setPadding(Util.dp(ctx, 16), Util.dp(ctx, 6), Util.dp(ctx, 4), Util.dp(ctx, 6))
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
            if (t === active) setBackgroundColor(ctx.getColor(R.color.mg_surface_variant))
            val out = TypedValue()
            if (ctx.theme.resolveAttribute(android.R.attr.selectableItemBackground, out, true)) {
                foreground = ctx.getDrawable(out.resourceId)
            }
            setOnClickListener { switchTo(t); dlg.dismiss() }
        }
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
        }
        col.addView(TextView(ctx).apply {
            text = titleOf(t)
            setTextColor(ctx.getColor(R.color.mg_text))
            textSize = 15f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        })
        col.addView(TextView(ctx).apply {
            text = (if (t.priv) "Private · " else "") + (if (t.home) "Speed dial" else Util.host(t.url)) // S13, S26
            setTextColor(ctx.getColor(R.color.mg_text_dim))
            textSize = 12f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        })
        val x = TextView(ctx).apply {
            text = "×"
            textSize = 26f
            gravity = Gravity.CENTER
            setTextColor(ctx.getColor(R.color.mg_text_dim))
            layoutParams = LinearLayout.LayoutParams(Util.dp(ctx, 48), Util.dp(ctx, 48))
            setOnClickListener { close(t); onChanged() }
        }
        row.addView(col)
        row.addView(x)
        return row
    }
}
