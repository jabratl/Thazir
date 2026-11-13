package com.jabra.mgb

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.webkit.ProfileStore
import androidx.webkit.WebViewFeature
import java.util.WeakHashMap

/**
 * SOCIAL MODE (0.0.224; 0.0.225: sites come from SocialProfiles.kt, per-site switch sm_s_<id>; 0.0.226-227: banner words + CSS per site, more rows; 0.0.229: media blur via SocialBlur): Facebook, Messenger chat and Instagram in a normal tab, without the Desktop site switch.
 *
 * Why it is needed: Facebook's mobile site has no chat (it sends you to the Messenger app), and Facebook / Instagram show
 * "Open app" banners and app redirects. The real chat only opens for a desktop user agent, but a desktop page is tiny on a phone.
 *
 * What it does (only when the switch is ON, key sm_on in mgb_ui, default OFF = old behaviour):
 *  - Chat pages (facebook.com/messages, messenger.com): desktop user agent + a "width=device-width" viewport, so the chat
 *    fits the phone. Every other Facebook page and Instagram: a mobile user agent without the WebView marker ("; wv").
 *    The user agent follows the address of each page, so tapping the chat icon switches by itself (and back).
 *  - Any other site: a user agent set by this file is put back to the default.
 *  - A tab the person put in Desktop site mode by hand is never touched.
 *  - Links to the Facebook / Messenger / Instagram apps (fb://, fb-messenger://, instagram://, intent:// of those apps)
 *    on a social page are not opened: you stay in the browser.
 *  - A small script hides the "Open app / Use the app / Get Messenger" banner (fixed bar, found by its text).
 * Not possible in a browser: background notifications / push (that is what the real Lite app has).
 * Hooks in TabManager: shouldOverrideUrlLoading (ensure, blockApp), onPageStarted (ensure), onProgressChanged + onPageFinished (inject),
 * doUpdateVisitedHistory (onRoute; 0.0.237 U3: INTERSTITIAL_JS in inject, 0.0.235: in-page navigation such as pushState on Facebook / Instagram / Reddit / X).
 * 0.0.236: a per-site "Site fix" (extra banner texts + CSS, SocialProfiles.fixWords / fixCss) is added to the built-in ones.
 */
object SocialMode {
    private const val KEY = "sm_on"
    private fun sp(c: Context) = c.getSharedPreferences("mgb_ui", Context.MODE_PRIVATE)
    fun on(c: Context): Boolean = sp(c).getBoolean(KEY, false)
    fun setOn(c: Context, v: Boolean) { sp(c).edit().putBoolean(KEY, v).apply() }

    /** 0.0.234 (P9): "Keep social logins separate" (default OFF). ON = a NEW tab on a social site gets its own WebView profile social_<id> (own cookies). */
    private const val K_SEP = "sm_sep"
    fun sepOn(c: Context): Boolean = sp(c).getBoolean(K_SEP, false)
    fun setSepOn(c: Context, v: Boolean) { sp(c).edit().putBoolean(K_SEP, v).apply() }
    /** false = this WebView has no multi-profile (screen shows "Not supported on this phone"). */
    fun sepSupported(): Boolean = try { WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE) } catch (_: Throwable) { false }
    /** Profile name for a tab that opens [url] now, or null = normal shared profile (switch OFF, Social OFF, site OFF, not a social site, no support). */
    fun profileName(c: Context, url: String?): String? {
        if (url == null || !on(c) || !sepOn(c) || !sepSupported()) return null
        val p = SocialProfiles.of(url) ?: return null
        if (!SocialProfiles.siteOn(c, p.id)) return null
        return pname(c, p.id)
    }
    /** 0.0.256 (B3): "Share Facebook and Messenger login" (default OFF). ON = Messenger tabs use the Facebook profile (social_facebook), so one login covers both. Only matters with "Keep social logins separate" ON. */
    private const val K_SHARE = "sm_fbshare"
    fun shareOn(c: Context): Boolean = sp(c).getBoolean(K_SHARE, false)
    fun setShareOn(c: Context, v: Boolean) { sp(c).edit().putBoolean(K_SHARE, v).apply() }
    private fun pname(c: Context, id: String): String = if (id == "messenger" && shareOn(c)) "social_facebook" + SocialSlot.suffix(c, "facebook") else "social_" + id + SocialSlot.suffix(c, id) // 0.0.276 (C5): + account slot
    /** 0.0.276 (C5): every profile name a site can have (current one, slot 1 and slot 2), so forget / hasProfile see both accounts. */
    private fun allNames(c: Context, id: String): Set<String> = setOf(pname(c, id), "social_" + id).flatMap { listOf(it, it.removeSuffix("_2"), it.removeSuffix("_2") + "_2") }.toSet()
    /** 0.0.251 (B2): "Forget social logins". DONE = profile deleted, CLEARED = profile was in use by an open tab, so its cookies + site data were wiped instead, NONE = nothing saved, UNSUPPORTED = no multi-profile. */
    enum class Forgot { DONE, CLEARED, NONE, UNSUPPORTED }
    /** true when site [id] has saved login data (its own profile, or the shared Facebook one). */
    fun hasProfile(c: Context, id: String): Boolean = try {
        sepSupported() && allNames(c, id).any { ProfileStore.getInstance().getProfile(it) != null }
    } catch (_: Throwable) { false }
    private fun forgetName(name: String): Forgot {
        return try {
            val store = ProfileStore.getInstance()
            val pr = store.getProfile(name)
            if (pr == null) Forgot.NONE
            else try {
                if (store.deleteProfile(name)) Forgot.DONE else Forgot.NONE
            } catch (_: IllegalStateException) { // a tab is still using the profile: wipe it instead of deleting
                pr.cookieManager.removeAllCookies(null)
                pr.cookieManager.flush()
                pr.webStorage.deleteAllData()
                Forgot.CLEARED
            }
        } catch (_: Throwable) { Forgot.NONE }
    }
    /** With the Facebook / Messenger share ON, forgetting either one removes the shared login (and an old separate Messenger profile). */
    fun forget(c: Context, id: String): Forgot {
        if (!sepSupported()) return Forgot.UNSUPPORTED
        var best = Forgot.NONE
        for (n in allNames(c, id)) {
            val r = forgetName(n)
            if (r == Forgot.DONE || (r == Forgot.CLEARED && best == Forgot.NONE)) best = r
        }
        return best
    }
    /** Forget every social site; returns how many sites had saved data. */
    fun forgetAll(c: Context): Int {
        var n = 0
        for (p in SocialProfiles.all) { val had = hasProfile(c, p.id); val r = forget(c, p.id); if (had && (r == Forgot.DONE || r == Forgot.CLEARED)) n++ }
        return n
    }
    /** Cookie header for [u] from the profile that a tab on [tabUrl] uses (SocialNsfw re-downloads pictures with the page's cookies). */
    fun cookies(c: Context, tabUrl: String, u: String): String? = try {
        val cm = profileName(c, tabUrl)?.let { ProfileStore.getInstance().getProfile(it)?.cookieManager }
        (cm ?: CookieManager.getInstance()).getCookie(u)
    } catch (_: Throwable) { null }

    /** 0.0.237 (U3): "Skip Use-the-app screens" (default ON; only does something while Social sites is ON). */
    private const val K_INT = "sm_int"
    fun intOn(c: Context): Boolean = sp(c).getBoolean(K_INT, true)
    fun setIntOn(c: Context, v: Boolean) { sp(c).edit().putBoolean(K_INT, v).apply() }

    /** 0.0.238 (A1): "Full screen on social sites" (default OFF), used by SocialFull. */
    private const val K_FULL = "sm_full"
    fun fullOn(c: Context): Boolean = sp(c).getBoolean(K_FULL, false)
    fun setFullOn(c: Context, v: Boolean) { sp(c).edit().putBoolean(K_FULL, v).apply() }
    /** 0.0.254 (D2): "Calmer touch on social pages" (default OFF): CSS only, no tap flash + no scroll chaining. */
    private const val K_TOUCH = "sm_touch"
    private const val TOUCH_CSS = "html,body,*{-webkit-tap-highlight-color:transparent!important}html,body{overscroll-behavior:contain!important}"
    fun touchOn(c: Context): Boolean = sp(c).getBoolean(K_TOUCH, false)
    fun setTouchOn(c: Context, v: Boolean) { sp(c).edit().putBoolean(K_TOUCH, v).apply() }
    private const val K_BARS = "sm_bars" // 0.0.240 (A2): phone bar colour follows the site (default ON)
    fun barsOn(c: Context): Boolean = sp(c).getBoolean(K_BARS, true)
    fun setBarsOn(c: Context, v: Boolean) { sp(c).edit().putBoolean(K_BARS, v).apply() }
    private const val K_BACK = "sm_back" // 0.0.241 (A3): first page of a social tab asks before closing (default ON)
    fun backOn(c: Context): Boolean = sp(c).getBoolean(K_BACK, true)
    fun setBackOn(c: Context, v: Boolean) { sp(c).edit().putBoolean(K_BACK, v).apply() }
    private const val K_SPLASH = "sm_splash" // 0.0.242 (A4): splash when a pinned shortcut opens the browser (default ON)
    fun splashOn(c: Context): Boolean = sp(c).getBoolean(K_SPLASH, true)
    fun setSplashOn(c: Context, v: Boolean) { sp(c).edit().putBoolean(K_SPLASH, v).apply() }
    // 0.0.243 (A9): chat size for CHAT sites (Facebook chat, Messenger, Discord, WhatsApp / Telegram Web, Snapchat); 100 = old behaviour byte for byte
    private const val K_CS = "sm_cs"
    val chatSizes = listOf(100, 125, 150, 175)
    fun chatScale(c: Context): Int = sp(c).getInt(K_CS, 100).let { if (it in chatSizes) it else 100 }
    fun setChatScale(c: Context, v: Int) { sp(c).edit().putInt(K_CS, if (v in chatSizes) v else 100).apply() }
    // 0.0.244 (A8): keep the focused message box above the soft keyboard on CHAT sites (default ON)
    private const val K_KB = "sm_kb"
    fun kbOn(c: Context): Boolean = sp(c).getBoolean(K_KB, true)
    fun setKbOn(c: Context, v: Boolean) { sp(c).edit().putBoolean(K_KB, v).apply() }
    // 0.0.245 (A5): bigger touch targets on Reddit / X / LinkedIn (default OFF: selectors are assumptions)
    private const val K_BIG = "sm_big"
    private const val K_DFIX = "sm_dfix" // 0.0.249 (A10): built-in extra site fixes (default ON)
    fun dfixOn(c: Context): Boolean = sp(c).getBoolean(K_DFIX, true)
    fun setDfixOn(c: Context, v: Boolean) { sp(c).edit().putBoolean(K_DFIX, v).apply() }
    fun bigOn(c: Context): Boolean = sp(c).getBoolean(K_BIG, false)
    fun setBigOn(c: Context, v: Boolean) { sp(c).edit().putBoolean(K_BIG, v).apply() }

    /** Per-site switch (0.0.228, P4): thin wrappers over SocialProfiles (key sm_s_<id>, not set = ON). */
    fun siteOn(c: Context, id: String): Boolean = SocialProfiles.siteOn(c, id)
    fun setSiteOn(c: Context, id: String, v: Boolean) { SocialProfiles.setSiteOn(c, id, v) }

    enum class Kind { NONE, FEED, CHAT, INSTA }

    /** Start pages for the shortcuts (Command Center). */
    const val FB_HOME = "https://m.facebook.com/"
    const val FB_CHAT = "https://www.facebook.com/messages/"
    const val IG_HOME = "https://www.instagram.com/"
    const val IG_DM = "https://www.instagram.com/direct/inbox/"

    /** Which kind of page this address is (table: SocialProfiles). Only https pages count. Ignores the per-site switch. */
    fun kind(url: String?): Kind {
        val p = SocialProfiles.of(url) ?: return Kind.NONE
        val path = try { Uri.parse(url).path ?: "" } catch (_: Exception) { "" }
        return p.kindOf(path)
    }

    /** Same as [kind] but NONE when the person switched this site off (SocialProfiles.siteOn). */
    private fun kindFor(ctx: Context, url: String?): Kind {
        val p = SocialProfiles.of(url) ?: return Kind.NONE
        if (!SocialProfiles.siteOn(ctx, p.id)) return Kind.NONE
        val k = kind(url)
        if (k == Kind.NONE) return k
        return when (uaMode(ctx, p.id)) { 1 -> Kind.FEED; 2 -> Kind.CHAT; else -> k } // 0.0.257 (U4): per-site override
    }
    /** 0.0.257 (U4): page layout per site. 0 = Auto (as the site profile says), 1 = Phone page, 2 = Desktop fit (like the chat sites). Key sm_ua_<id>. */
    val uaNames = listOf("Auto", "Phone page", "Desktop fit")
    fun uaMode(c: Context, id: String): Int = sp(c).getInt("sm_ua_" + id, 0).coerceIn(0, 2)
    fun setUaMode(c: Context, id: String, v: Int) { sp(c).edit().apply { if (v <= 0) remove("sm_ua_" + id) else putInt("sm_ua_" + id, v) }.apply() }

    // WebViews whose user agent this file changed (so a page of another site gets the default back, nothing else is reset)
    private val applied = WeakHashMap<WebView, Boolean>()
    private var mUa: String? = null

    /** Default WebView user agent without the "; wv" marker and "Version/4.0": looks like Chrome on Android to Facebook / Instagram. */
    private fun mobileUa(ctx: Context): String {
        mUa?.let { return it }
        val base = try { WebSettings.getDefaultUserAgent(ctx) }
        catch (e: Exception) { "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36" }
        val ua = base.replace("; wv)", ")").replace("Version/4.0 ", "")
        mUa = ua
        return ua
    }

    private class UaPlan(val ua: String?, val kind: Kind)
    private val uaPending = WeakHashMap<WebView, String>() // view -> newest address waiting for the deferred switch
    private val uaHandler by lazy { android.os.Handler(android.os.Looper.getMainLooper()) }

    /** Pure check, changes nothing: what would have to change for this address? null = nothing to do. */
    private fun planFor(ctx: Context, view: WebView, url: String, handDesktop: Boolean): UaPlan? {
        if (handDesktop || !on(ctx) || !url.startsWith("http", true)) return null
        val k = kindFor(ctx, url)
        if (k == Kind.NONE) return if (applied.containsKey(view)) UaPlan(null, k) else null
        val want = if (k == Kind.CHAT) BrowserExtras.desktopUa(ctx) else mobileUa(ctx)
        val cur = try { view.settings.userAgentString } catch (_: Throwable) { return null }
        return if (cur == want) null else UaPlan(want, k)
    }

    private fun commitPlan(view: WebView, p: UaPlan) {
        val s = view.settings
        if (p.ua == null) { applied.remove(view); s.userAgentString = null; return } // default again
        s.userAgentString = p.ua
        if (p.kind == Kind.CHAT) { s.useWideViewPort = true; s.loadWithOverviewMode = true } // the viewport script then makes the chat fit; feed / Instagram keep their own mobile viewport
        applied[view] = true
    }

    /**
     * Sets the user agent for this address. true = it changes and this function loads the address again by itself (the caller must NOT
     * load it again and must cancel the current navigation). false = nothing to do (switch OFF, Desktop site chosen by hand, already right, not a social page).
     * 0.0.414 (CRASH FIX): WebSettings.setUserAgentString used to run right here, inside shouldOverrideUrlLoading / onPageStarted / doUpdateVisitedHistory,
     * i.e. in the middle of a Chromium navigation callback: native crash (SIGSEGV in libwebviewchromium.so, AwSettings.setUserAgentString <- ensure <- shouldOverrideUrlLoading).
     * Now the switch + reload are posted to the main looper and run after the callback has returned.
     */
    fun ensure(ctx: Context, view: WebView, url: String, handDesktop: Boolean): Boolean {
        if (planFor(ctx, view, url, handDesktop) == null) return false
        val already = uaPending.containsKey(view)
        uaPending[view] = url // newest address wins
        if (already) return true
        val app = ctx.applicationContext
        uaHandler.post {
            val target = uaPending.remove(view) ?: return@post
            try {
                planFor(app, view, target, handDesktop)?.let { commitPlan(view, it) }
                view.stopLoading()
                view.loadUrl(target)
            } catch (e: Throwable) { /* WebView already destroyed (tab closed): nothing to do */ }
        }
        return true
    }

    /** true = this link opens a Facebook / Messenger / Instagram app and the tab is on a social page: do not open it. */
    fun blockApp(ctx: Context, tabUrl: String, u: Uri): Boolean {
        if (!on(ctx) || kindFor(ctx, tabUrl) == Kind.NONE) return false
        val s = u.scheme?.lowercase() ?: return false
        if (s in SocialProfiles.allSchemes) return true
        if (s == "intent" || s == "market") {
            val t = u.toString()
            return SocialProfiles.allIntentPkgs.any { t.contains(it) }
        }
        return false
    }

    // Viewport for the chat page: desktop pages have none, so WebView would draw them 980 px wide and tiny.
    private const val VIEWPORT_JS = "(function(){try{if(window.__mgSv)return;window.__mgSv=1;" +
        "var c='width=device-width, initial-scale=1';" +
        "var fix=function(){var m=document.querySelector('meta[name=viewport]');" +
        "if(!m){m=document.createElement('meta');m.name='viewport';(document.head||document.documentElement).appendChild(m);}" +
        "if(m.getAttribute('content')!==c)m.setAttribute('content',c);};" +
        "fix();var o=new MutationObserver(fix);o.observe(document.documentElement,{childList:true,subtree:true});" +
        "setTimeout(function(){o.disconnect();},8000);}catch(e){}})();"

    // 0.0.243 (A9): same as VIEWPORT_JS but the page is laid out (device width / scale) CSS px wide and shown at that zoom, so text + buttons are bigger.
    // %S% = scale (1.25 / 1.5 / 1.75). screen.width = device width in CSS px; re-checked on resize (rotation).
    private const val VIEWPORT_SCALED_JS = "(function(){try{if(window.__mgSv)return;window.__mgSv=1;var S=%S%;" +
        "var f=function(){return 'width='+Math.round((screen.width||window.innerWidth)/S)+', initial-scale='+S;};" +
        "var fix=function(){var c=f();var m=document.querySelector('meta[name=viewport]');" +
        "if(!m){m=document.createElement('meta');m.name='viewport';(document.head||document.documentElement).appendChild(m);}" +
        "if(m.getAttribute('content')!==c)m.setAttribute('content',c);};" +
        "fix();var o=new MutationObserver(fix);o.observe(document.documentElement,{childList:true,subtree:true});" +
        "setTimeout(function(){o.disconnect();},8000);window.addEventListener('resize',fix);}catch(e){}})();"

    // 0.0.244 (A8): when a text box gets focus (never a password box) scroll it to the middle of the shrunken page after the keyboard is up. Guard window.__mgSk.
    private const val KEYBOARD_JS = "(function(){try{if(window.__mgSk)return;window.__mgSk=1;" +
        "var ok=function(t){if(!t)return false;if(t.tagName==='TEXTAREA')return true;if(t.tagName==='INPUT')return t.type!=='password';return !!t.isContentEditable;};" +
        "var go=function(t){try{t.scrollIntoView({block:'center',behavior:'auto'});}catch(x){}};" +
        "document.addEventListener('focusin',function(e){var t=e.target;if(!ok(t))return;setTimeout(function(){go(t)},300);setTimeout(function(){go(t)},700);},true);" +
        "if(window.visualViewport){visualViewport.addEventListener('resize',function(){var a=document.activeElement;if(ok(a))setTimeout(function(){go(a)},100);});}" +
        "}catch(e){}})();"

    private fun viewportJs(ctx: Context, again: Boolean): String {
        val sc = chatScale(ctx)
        val js = if (sc == 100) VIEWPORT_JS else VIEWPORT_SCALED_JS.replace("%S%", (sc / 100.0).toString())
        return if (again) "window.__mgSv=0;" + js else js
    }

    // "Open app" banner: a button or link with that text inside a small fixed / sticky bar. Looks for 40 s, then stops.
    // 0.0.226: the app names in the text come from the site row (SocialProfiles.Profile.appWords).
    // 0.0.235 (U1): again = true restarts the 40 s scan after an in-page route change (counter window.__mgSbN, timer window.__mgSbI).
    // 0.0.236 (U2): phrases = extra whole button texts typed by the person (Site fix), compared in lower case.
    private val bannerCache = HashMap<String, String>()
    private fun bannerJs(words: List<String>, phrases: List<String> = emptyList(), again: Boolean = false): String {
        val w = words.filter { it.matches(Regex("[a-z0-9]+")) }
        val key = w.joinToString("|") + "#" + phrases.joinToString("|") + "#" + again
        return bannerCache.getOrPut(key) {
            val names = if (w.isEmpty()) "" else "( " + w.joinToString("| ") + ")?"
            val ex = org.json.JSONArray(phrases).toString()
            "(function(){try{" +
            "var re=/^(open|use|get|install|continue in|open in)( the)?" + names + "( app)?$/i;var ex=" + ex + ";" +
            "var scan=function(){var l=document.querySelectorAll('a,button,[role=button]');" +
            "for(var i=0;i<l.length;i++){var e=l[i];var t=(e.textContent||'').trim();if(t.length>40||!(re.test(t)||ex.indexOf(t.toLowerCase())>=0))continue;" +
            "var p=e;for(var d=0;d<8&&p&&p!==document.body;d++,p=p.parentElement){var cs=getComputedStyle(p);" +
            "if(cs.position==='fixed'||cs.position==='sticky'){var r=p.getBoundingClientRect();" +
            "if(r.height<window.innerHeight*0.4&&(p.innerText||'').length<200){p.style.setProperty('display','none','important');}break;}}}};" +
            "var tick=function(){scan();if(++window.__mgSbN>40){clearInterval(window.__mgSbI);window.__mgSbI=0;}};" +
            (if (again) "if(window.__mgSb){window.__mgSbN=0;scan();if(!window.__mgSbI)window.__mgSbI=setInterval(tick,1000);return;}"
             else "if(window.__mgSb)return;") +
            "window.__mgSb=1;window.__mgSbN=0;scan();window.__mgSbI=setInterval(tick,1000);}catch(e){}})();"
        }
    }

    // 0.0.237 (U3): full-screen "Use the app / Chats on mobile browsers are not available" screen: clicks "Staying in the browser" / "Not now" for you.
    // Only when ALL are true: a big fixed / dialog layer (>= 40 % of the screen), its text names an app / says not available in browsers,
    // it has no password field, and the button text is one of the dismiss texts (a bare "Continue" is never clicked). Max 3 clicks, scans 40 s.
    private const val INTERSTITIAL_JS =
        "(function(){try{" +
        "if(window.__mgSi)return;window.__mgSi=1;" +
        "var warn=/(use|get|open|go to|install|download) (the |our )?([a-z]+ )?app|not available (on|in) (mobile )?(web )?browsers?|chats? on mobile browsers|only available (in|on) (the )?app|isn.t available (on|in)|keep chatting/i;" +
        "var skip=/^(staying in the browser|stay in (the )?browser|continue in (the )?browser|continue on (the )?(web|mobile web|browser)|continue to (the )?(mobile )?(web)?site|use (the )?(mobile )?(web|browser|site)|not now|maybe later|no,? thanks|skip)$/i;" +
        "var n=0,clicks=0;" +
        "var big=function(e){var p=e;for(var d=0;d<10&&p&&p!==document.body;d++,p=p.parentElement){var cs=getComputedStyle(p);" +
        "if(cs.position==='fixed'||cs.position==='sticky'||p.getAttribute('role')==='dialog'||p.getAttribute('aria-modal')==='true'){var r=p.getBoundingClientRect();" +
        "if(r.width*r.height>=window.innerWidth*window.innerHeight*0.4)return p;}}return null;};" +
        "var scan=function(){if(clicks>=3)return;var l=document.querySelectorAll('a,button,[role=button]');" +
        "for(var i=0;i<l.length;i++){var e=l[i];if(e.__mgi)continue;var t=(e.textContent||'').trim();if(t.length>40||!skip.test(t))continue;" +
        "var o=big(e);if(!o)continue;if(o.querySelector('input[type=password]'))continue;" +
        "if(!warn.test((o.innerText||'').slice(0,600)))continue;" +
        "e.__mgi=1;clicks++;e.click();return;}};" +
        "scan();var iv=setInterval(function(){scan();if(++n>40||clicks>=3)clearInterval(iv);},1000);" +
        "}catch(e){}})();"

    // Site CSS (0.0.226): one <style> element, guarded by its id, no loop.
    private fun cssJs(css: String): String {
        val q = css.replace("\\", "\\\\").replace("'", "\\'")
        return "(function(){try{if(document.getElementById('__mgSc'))return;var s=document.createElement('style');s.id='__mgSc';" +
            "s.textContent='" + q + "';(document.head||document.documentElement).appendChild(s);}catch(e){}})();"
    }

    /** Runs the page scripts for this address (each one guards itself, so calling it many times is fine). */
    fun inject(ctx: Context, view: WebView?, url: String?, handDesktop: Boolean, again: Boolean = false) {
        if (view == null || handDesktop || !on(ctx)) return
        val p = SocialProfiles.of(url)
        when (kindFor(ctx, url)) {
            Kind.CHAT -> { // 0.0.243 (A9): chat size; 0.0.244 (A8): keyboard scroll
                view.evaluateJavascript(viewportJs(ctx, again), null)
                if (kbOn(ctx)) view.evaluateJavascript(KEYBOARD_JS, null)
            }
            Kind.FEED, Kind.INSTA -> view.evaluateJavascript(bannerJs(p?.appWords ?: emptyList(), if (p != null) (if (dfixOn(ctx)) SocialProfiles.extraWords(p.id) else emptyList()) + SocialProfiles.fixWords(ctx, p.id) else emptyList(), again), null)
            Kind.NONE -> return
        }
        if (p != null) SocialProbe.note(p.id, kindFor(ctx, url).name.lowercase(), view) // 0.0.277 (U5): live script counter
        if (intOn(ctx)) view.evaluateJavascript(if (again) "window.__mgSi=0;" + INTERSTITIAL_JS else INTERSTITIAL_JS, null) // 0.0.237 (U3)
        val css = ((p?.css ?: "") + " " + (if (p != null && touchOn(ctx)) TOUCH_CSS else "") + " " + (if (p != null && bigOn(ctx)) SocialProfiles.bigCss(p.id) else "") + " " + (if (p != null && dfixOn(ctx)) SocialProfiles.extraCss(p.id) else "") + " " + (if (p != null && SocialFocus.on(ctx)) SocialFocus.css(p.id) else "") + " " + (if (p != null) SocialProfiles.fixCss(ctx, p.id) else "")).trim() // 0.0.236 (U2): built-in + 0.0.245 (A5) big targets + Site fix (last = wins)
        if (css.isNotEmpty()) view.evaluateJavascript(cssJs(css), null)
        SocialApp.inject(ctx, view, url) // 0.0.305: share sheet, keep screen on, haptic tick
        SocialReels.inject(ctx, view, p?.id) // 0.0.302: Instagram reels viewer
        SocialBlur.inject(ctx, view, p?.id) // 0.0.229 (P5): sensitive-media blur (mode OFF = only removes an old blur)
        SocialMute.inject(ctx, view, p?.id) // 0.0.267 (C6): word mute (no words = nothing)
        SocialBadge.injectSaver(ctx, view) // 0.0.231 (P7): data saver (switch OFF = nothing)
        SocialBars.read(ctx, view) // 0.0.240 (A2): site colour for the phone bars (switch OFF = returns at once)
    }

    // ---- 0.0.235 (U1): in-page navigation (history.pushState / popstate): no page load, so ensure() and the page scripts did not run ----
    private val lastKey = WeakHashMap<WebView, String>()
    private val lastRe = WeakHashMap<WebView, Long>()
    private val flips = WeakHashMap<WebView, ArrayList<Long>>()
    private fun routeKey(url: String): String = try { val u = Uri.parse(url); (u.host ?: "") + (u.path ?: "") } catch (_: Exception) { url }

    /**
     * Called from TabManager.doUpdateVisitedHistory (every address change, also in-page ones). true = the user agent changed (Facebook feed -> chat,
     * Instagram feed -> /direct/ ...): the caller loads the address again. false = nothing to load; when only the path changed, the banner / viewport
     * scripts are started again (blur, data saver and CSS guard themselves). Loop guard: at most 2 user agent flips per 15 s per tab, then this does nothing.
     */
    fun onRoute(ctx: Context, view: WebView, url: String, handDesktop: Boolean): Boolean {
        if (handDesktop || !on(ctx) || !url.startsWith("http", true)) return false
        val now = SystemClock.uptimeMillis()
        val key = routeKey(url)
        val prev = lastKey.put(view, key)
        val fl = flips.getOrPut(view) { ArrayList() }
        fl.removeAll { now - it > 15000L }
        if (fl.size >= 2) return false
        if (ensure(ctx, view, url, false)) { fl.add(now); return true }
        if (prev == null || prev == key) return false
        if (kindFor(ctx, url) == Kind.NONE) return false
        if (now - (lastRe[view] ?: 0L) < 1200L) return false
        lastRe[view] = now
        inject(ctx, view, url, false, true)
        return false
    }

    /** Command Center shortcut: switch Social mode ON (the person asked for a social page) and open it in a new tab. */
    fun open(ctx: Context, tabs: TabManager, url: String) {
        if (!on(ctx)) { setOn(ctx, true); Util.toast(ctx, "Social sites ON (Settings > General)") }
        SocialPreload.warm(ctx, url) // 0.0.250 (A11): DNS warm-up (switch sm_pre)
        tabs.newTab(url)
    }
}
