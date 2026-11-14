package com.jabra.mgb

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.CookieManager
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.util.WeakHashMap

/**
 * S6: chrome.tabs / chrome.runtime messaging / chrome.alarms (+ harmless stubs) on top of ChromeBridge.
 *  - runtime.sendMessage: content frame or extension page -> every other extension page that listens
 *    (background engine, popup ...). First non-empty answer wins. Content frames may only send the
 *    message types content.js really uses (CONTENT_MSG).
 *  - tabs.sendMessage: engine/page -> frames of one tab (frameId 0 = main frame).
 *  - tabs.* handlers + tab events (called by TabManager).
 * N1 (Novel grabber): scripting.executeScript (func source text / files, main frame, 3 min limit) + cookies.* (WebView CookieManager).
 * NOT here: scripting.registerContentScripts, downloads (S7),
 * DNR + webRequest (S8), offscreen (S9), extension page UI (S10).
 */
object TabsApi {
    private const val TAG = "MGB.tabs"
    private const val NO_RECEIVER = "Could not establish connection. Receiving end does not exist."
    private const val PREFS = "mgb_engine"

    private val main = Handler(Looper.getMainLooper())
    private var tm: TabManager? = null
    private var app: Context? = null
    private var inited = false
    @Volatile private var engineReady = false
    private val missedTabs = HashSet<Int>() // 0.0.166: tabs that loaded before the engine was ready (guarded by itself)
    private val listening = WeakHashMap<ChromeBridge.Ctx, HashSet<String>>()
    private val frameIds = WeakHashMap<ChromeBridge.Ctx, Int>()
    private var nextFrame = 1
    private val SCHEME = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:")

    /** Message types content.js sends (defence in depth: a page frame can never ask for anything else). */
    private val CONTENT_MSG = setOf(
        "batch-ask-answer", "batch-episodes", "batch-stop", "dom-items", "download-all", "dup-check",
        "get-state", "job-cancel", "job-pause", "job-remove", "jobs-get", "notify", "open-manga",
        "native-panel", "open-options", "open-panel", "open-player", "play-scan", "reanalyze", "rescan",
        "schedule-job", "site-probe", "start-job", "subs-all", "subs-capture", "video-info", "whoami"
    )

    var onNativePanel: (() -> Unit)? = null // MGB S51: set by MainActivity

    fun attach(t: TabManager?) { tm = t }

    /** 0.0.430 (S5): the tab manager for ExtApi2. */
    fun manager(): TabManager? = tm

    /** 0.0.112 (S2): the tab on screen (ExtTray: badge of that tab, tab argument of action.onClicked). */
    fun activeTab(): TabManager.Tab? = tm?.active

    private fun ui(b: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) b() else main.post { b() }
    }

    /** Register a handler that runs on the main thread; exceptions become chrome errors. */
    private fun handle(api: String, h: (ChromeBridge.Call) -> Unit) {
        ChromeBridge.on(api) { c ->
            ui {
                try { h(c) } catch (e: Exception) { Diag.w("engine", "api " + c.api + " threw", e.message ?: e.toString()); c.fail(e.message ?: e.toString()) } // S83
            }
        }
    }

    // ---------- helpers ----------

    fun tabJson(t: TabManager.Tab): JSONObject {
        val m = tm
        val o = JSONObject()
            .put("id", t.id)
            .put("index", m?.all()?.indexOf(t) ?: 0)
            .put("windowId", 1)
            .put("active", m?.active === t)
            .put("highlighted", m?.active === t)
            .put("pinned", false)
            .put("incognito", t.priv)
            .put("audible", false)
            .put("discarded", false)
            .put("status", if (t.loading) "loading" else "complete")
            .put("url", t.url)
            .put("title", t.title)
            .put("favIconUrl", "")
        if (t.loading) o.put("pendingUrl", t.url)
        if (t.openerId > 0) o.put("openerTabId", t.openerId)
        return o
    }

    /** 0.0.109: caller is a page of an installed extension that is open as a TAB -> the web page it was opened for (opener, else the first normal tab). null = not that case. */
    fun sourceTabFor(c: ChromeBridge.Call): TabManager.Tab? {
        if (c.extId == ContentInjector.EXT_ID) return null
        val m = tm ?: return null
        val me = if (c.ctx.tabId > 0) m.byId(c.ctx.tabId) else null
        if (me == null || !me.isExt) return null
        return (m.byId(me.openerId)?.takeIf { !it.isExt } ?: m.all().lastOrNull { !it.isExt && !it.home })?.takeIf { ExtPriv.sees(c.extId, it) }
    }

    private fun frameIdOf(c: ChromeBridge.Ctx): Int {
        if (c.isMainFrame) return 0
        return synchronized(frameIds) { frameIds.getOrPut(c) { nextFrame++ } }
    }

    fun listens(c: ChromeBridge.Ctx, ev: String): Boolean =
        synchronized(listening) { listening[c]?.contains(ev) == true }

    /** The `sender` object a receiving listener gets. */
    fun senderOf(c: ChromeBridge.Ctx, extId: String = ContentInjector.EXT_ID): JSONObject {
        val s = JSONObject().put("id", extId).put("origin", c.origin)
        val tab = if (c.tabId > 0) tm?.byId(c.tabId) else null
        if (tab != null) s.put("tab", ExtApi.tabFor(extId, tabJson(tab)))
        s.put("frameId", frameIdOf(c))
        val own = extId != ContentInjector.EXT_ID
        val url = when {
            c.kind == ChromeBridge.Kind.EXT ->
                if (EngineHost.isEngine(c.web)) ContentInjector.EXT_BASE + "_engine.html"
                else if (own && ExtBackground.idOf(c.web) == extId) ExtBackground.urlOf(extId) // S5: worker
                else if (own && ExtScreen.isScreen(c.web)) ExtScreen.urlOf(c.web) // S6: popup / options window
                else if (c.isMainFrame && tab != null && tab.url.startsWith(ContentInjector.EXT_BASE)) tab.url
                else ContentInjector.EXT_BASE + (if (own) "$extId/" else "")
            c.isMainFrame && tab != null -> tab.url
            else -> c.origin + "/"
        }
        return s.put("url", url)
    }

    /** Send to several frames; the first non-empty reply wins, null when all are empty. */
    fun fanOut(targets: List<ChromeBridge.Ctx>, msg: Any?, sender: JSONObject, extId: String = ContentInjector.EXT_ID, event: String = "runtime.onMessage", done: (Any?) -> Unit) {
        if (targets.isEmpty()) { done(null); return }
        var left = targets.size
        var finished = false
        for (t in targets) {
            val held = ExtBackground.hold(t.web) // S5: a worker that has to answer is not stopped as idle
            ChromeBridge.request(t, msg, sender, extId, event) { r ->
                if (held) ExtBackground.release(t.web)
                if (finished) return@request
                left--
                if (r != null && r != JSONObject.NULL) { finished = true; done(r) }
                else if (left <= 0) { finished = true; done(null) }
            }
        }
    }

    private fun glob(p: String, s: String): Boolean {
        if (p == "<all_urls>") return true
        return Regex(p.split("*").joinToString(".*") { Regex.escape(it) }).matches(s)
    }

    private fun vis(c: ChromeBridge.Call, t: TabManager.Tab?): TabManager.Tab? = t?.takeIf { ExtPriv.sees(c.extId, it) } // 0.0.438: private tabs only for "Allow in private"

    private fun query(q: JSONObject, xid: String = ContentInjector.EXT_ID): JSONArray {
        val out = JSONArray()
        val m = tm ?: return out
        val urls: List<String>? = when (val u = q.opt("url")) {
            is String -> listOf(u)
            is JSONArray -> (0 until u.length()).map { u.optString(it) }
            else -> null
        }
        for (t in m.all()) {
            if (!ExtPriv.sees(xid, t)) continue
            if (q.has("active") && (m.active === t) != q.optBoolean("active")) continue
            if (q.has("status") && (if (t.loading) "loading" else "complete") != q.optString("status")) continue
            if (q.has("title") && !glob(q.optString("title"), t.title)) continue
            if (urls != null && urls.none { glob(it, t.url) }) continue
            out.put(tabJson(t))
        }
        return out
    }

    private fun fullUrl(u: String, extId: String = ContentInjector.EXT_ID): String =
        if (u.isBlank()) TabManager.HOME
        else if (SCHEME.containsMatchIn(u)) u
        else if (extId != ContentInjector.EXT_ID) ContentInjector.EXT_BASE + extId + "/" + u.trimStart('/') // S6: relative = file of that extension
        else ContentInjector.EXT_BASE + u.trimStart('/')

    /** S10: a link opened from an extension window becomes a normal tab. */
    fun openTab(url: String) = ui { tm?.newTab(url, 0, true) }

    // ---------- events (called by TabManager) ----------

    private fun emitTabs(event: String, tab: TabManager.Tab?, vararg args: Any?) {
        val a = JSONArray()
        for (x in args) a.put(x ?: JSONObject.NULL)
        ChromeBridge.emit(event, a) { it.kind == ChromeBridge.Kind.EXT }
        ExtApi.tabsEvent(event, tab, args.toList()) // S6: installed extensions (own events, url / title only where allowed)
    }

    fun onCreated(t: TabManager.Tab) = emitTabs("tabs.onCreated", t, tabJson(t))
    fun onActivated(t: TabManager.Tab) =
        emitTabs("tabs.onActivated", t, JSONObject().put("tabId", t.id).put("windowId", 1))
    fun onRemoved(t: TabManager.Tab) =
        emitTabs("tabs.onRemoved", t, t.id, JSONObject().put("windowId", 1).put("isWindowClosing", false))
    fun onUpdated(t: TabManager.Tab, change: JSONObject) = emitTabs("tabs.onUpdated", t, t.id, change, tabJson(t))

    // ---------- init ----------

    fun init(ctx: Context) {
        if (inited) return
        inited = true
        app = ctx.applicationContext
        ExtPriv.bind(ctx)
        loadAlarms()

        // frames announce which events they listen to (shim: addListener on an ext page)
        ChromeBridge.onSync("event.add") { c ->
            val n = c.args.optString(0)
            if (n.isNotEmpty()) synchronized(listening) { listening.getOrPut(c.ctx) { HashSet() }.add(n) }
            if (n.isNotEmpty()) ExtApi.onListen(c, n) // S5: remembers what wakes a stopped worker
            if (n.isNotEmpty()) ExtApi.onListenFilter(c, n) // 0.0.119 (S5): webRequest filters
            null
        }

        handle("runtime.sendMessage") { c ->
            val msg = c.args.opt(0)
            if (c.ctx.kind == ChromeBridge.Kind.CONTENT) {
                val type = (msg as? JSONObject)?.optString("type") ?: ""
                if (type !in CONTENT_MSG) { c.fail("Not allowed: message type"); return@handle }
                if (type == "native-panel") { ui { onNativePanel?.invoke() }; c.ok(null); return@handle } // MGB S51: handled here, never forwarded
            }
            val targets = ChromeBridge.contexts().filter {
                it.kind == ChromeBridge.Kind.EXT && it !== c.ctx && listens(it, "runtime.onMessage")
            }
            if (targets.isEmpty()) { c.fail(NO_RECEIVER); return@handle }
            fanOut(targets, msg, senderOf(c.ctx)) { r -> c.ok(r) }
        }

        handle("tabs.sendMessage") { c ->
            val id = c.args.optInt(0, -1)
            val msg = c.args.opt(1)
            val opts = c.args.optJSONObject(2)
            val t = vis(c, tm?.byId(id))
            var targets: List<ChromeBridge.Ctx> =
                if (t == null) emptyList() else ChromeBridge.contexts().filter { it.web === t.web && (c.extId == ContentInjector.EXT_ID || c.extId in it.ids) }
            if (opts != null && opts.has("frameId")) {
                val f = opts.optInt("frameId")
                targets = if (f == 0) targets.filter { it.isMainFrame }.takeLast(1)
                else targets.filter { !it.isMainFrame && frameIdOf(it) == f }
            }
            if (targets.isEmpty()) { c.fail(NO_RECEIVER); return@handle }
            fanOut(targets, msg, senderOf(c.ctx, c.extId), c.extId) { r -> c.ok(r) }
        }

        handle("tabs.connect") { c -> // 0.0.111 (S1): long-lived port to the content scripts of one tab (args: tabId, portId, name, frameId|null)
            if (c.extId == ContentInjector.EXT_ID) { c.fail("Not implemented: tabs.connect"); return@handle }
            val t = vis(c, tm?.byId(c.args.optInt(0, -1)))
            var targets: List<ChromeBridge.Ctx> =
                if (t == null) emptyList() else ChromeBridge.contexts().filter { it.web === t.web && it !== c.ctx && c.extId in it.ids }
            if (!c.args.isNull(3)) {
                val f = c.args.optInt(3)
                targets = if (f == 0) targets.filter { it.isMainFrame }.takeLast(1)
                else targets.filter { !it.isMainFrame && frameIdOf(it) == f }
            }
            ExtApi.openPort(c, targets)
        }

        handle("tabs.get") { c ->
            val t = vis(c, tm?.byId(c.args.optInt(0, -1)))
            if (t == null) c.fail("No tab with id: ${c.args.opt(0)}.") else c.ok(ExtApi.tabFor(c.extId, tabJson(t)))
        }

        handle("tabs.getCurrent") { c ->
            val t = if (c.ctx.tabId > 0) vis(c, tm?.byId(c.ctx.tabId)) else null
            c.ok(if (t == null) null else ExtApi.tabFor(c.extId, tabJson(t)))
        }

        handle("tabs.query") { c ->
            val q = c.args.optJSONObject(0) ?: JSONObject()
            val src = if (q.optBoolean("active", false)) sourceTabFor(c) else null
            if (src == null) c.ok(ExtApi.tabsFor(c.extId, query(q, c.extId)))
            else { // 0.0.109: asked from an extension page that is open as a tab: "active" = the page it was opened for, not the extension tab itself
                val q2 = JSONObject(q.toString()); q2.remove("active")
                val all = query(q2, c.extId); val out = JSONArray()
                for (i in 0 until all.length()) { val o = all.optJSONObject(i); if (o != null && o.optInt("id") == src.id) out.put(o) }
                c.ok(ExtApi.tabsFor(c.extId, out))
            }
        }

        handle("tabs.create") { c ->
            val m = tm
            if (m == null) { c.fail("No browser window"); return@handle }
            val o = c.args.optJSONObject(0) ?: JSONObject()
            val own = c.extId != ContentInjector.EXT_ID // 0.0.109: installed extension (V77 keeps its windows)
            val opener = if (o.has("openerTabId")) o.optInt("openerTabId")
                else if (own && c.ctx.tabId <= 0) (m.active?.takeIf { !it.isExt }?.id ?: 0) // from a popup window: the page under it is the source
                else maxOf(c.ctx.tabId, 0)
            val u = fullUrl(o.optString("url", ""), c.extId)
            // S39: V77 "Play" (player.html?url=...) = built-in native player, not the Chromium page player
            if (WebPlay.fromExtPlayerUrl(m.host(), u, (m.byId(opener) ?: m.active)?.web?.settings?.userAgentString)) {
                c.ok(JSONObject().put("id", -1).put("windowId", 1).put("index", 0).put("active", true).put("url", u).put("status", "complete"))
                return@handle
            }
            // S10: popup / queue / manga / history / options open as a full-screen window, not as a tab
            if (!own && ExtScreen.isScreenUrl(u) && ExtScreen.open(m.host(), u)) {
                c.ok(JSONObject().put("id", -1).put("windowId", 1).put("index", 0).put("active", true).put("url", u).put("status", "complete"))
                return@handle
            }
            val t = m.newTab(u, opener, o.optBoolean("active", true))
            if (t == null) c.fail("Too many tabs") else {
                if (o.optBoolean("active", true)) ExtScreen.closeAll() // a new tab must not stay hidden behind a window
                c.ok(ExtApi.tabFor(c.extId, tabJson(t)))
            }
        }

        handle("tabs.update") { c ->
            val m = tm
            if (m == null) { c.fail("No browser window"); return@handle }
            val first = c.args.opt(0)
            val props = (if (first is JSONObject) first else c.args.optJSONObject(1)) ?: JSONObject()
            val t = vis(c, if (first is JSONObject || first == null || first == JSONObject.NULL) m.active else m.byId(c.args.optInt(0, -1)))
            if (t == null) { c.fail("No tab with id: $first."); return@handle }
            if (props.has("url")) {
                val u = fullUrl(props.optString("url"), c.extId)
                t.pending = null
                t.url = u
                t.web.loadUrl(u)
            }
            if (props.optBoolean("active", false)) {
                m.switchTo(t)
                if (ExtScreen.urlOf(c.ctx.web).contains("/novel/")) ExtScreen.tuck() // N2: picker needs the page on screen
            }
            c.ok(ExtApi.tabFor(c.extId, tabJson(t)))
        }

        handle("tabs.remove") { c ->
            val m = tm
            val a = c.args.opt(0)
            val ids = ArrayList<Int>()
            if (a is JSONArray) {
                for (i in 0 until a.length()) ids.add(a.optInt(i))
            } else {
                ids.add(c.args.optInt(0, -1))
            }
            if (m != null) for (id in ids) vis(c, m.byId(id))?.let { m.close(it) }
            c.ok(null)
        }

        handle("tabs.reload") { c ->
            val m = tm
            val a = c.args.opt(0)
            val t = vis(c, if (a is Number) m?.byId(a.toInt()) else m?.active)
            if (t == null) { c.fail("No tab to reload"); return@handle }
            val p = t.pending
            if (p != null) { t.pending = null; t.web.loadUrl(p) } else t.web.reload()
            c.ok(null)
        }

        // ---- N1: chrome.scripting.executeScript -> evaluateJavascript in the tab, result polled back ----
        handle("scripting.executeScript") { c -> runScript(c) }
        // S6: chrome.scripting.insertCSS = a <style> element in the tab (css text or files of the extension)
        handle("scripting.insertCSS") { c ->
            val o = c.args.optJSONObject(0) ?: JSONObject()
            val sb = StringBuilder(o.optString("css", ""))
            val files = o.optJSONArray("files")
            if (files != null) for (i in 0 until files.length()) sb.append('\n').append(readExtText(c.extId, files.optString(i).trimStart('/')) ?: "")
            val inj = JSONObject().put("target", o.optJSONObject("target") ?: JSONObject())
                .put("func", "function(css){var s=document.createElement('style');s.textContent=css;(document.head||document.documentElement).appendChild(s);}")
                .put("args", JSONArray().put(sb.toString()))
            runScript(c, inj)
        }

        // ---- N1: chrome.cookies on top of the WebView CookieManager (name/value only: Android gives no flags) ----
        ChromeBridge.onSync("cookies.getAll") { c ->
            val o = c.args.optJSONObject(0) ?: JSONObject()
            val u = cookieUrl(o)
            if (u == null) JSONArray() else cookieList(u, o.optString("name").ifEmpty { null })
        }
        ChromeBridge.onSync("cookies.get") { c ->
            val o = c.args.optJSONObject(0) ?: JSONObject()
            val u = cookieUrl(o)
            if (u == null) null else cookieList(u, o.optString("name").ifEmpty { null }).optJSONObject(0)
        }
        ChromeBridge.onSync("cookies.set") { c ->
            val o = c.args.optJSONObject(0) ?: JSONObject()
            val u = o.optString("url")
            if (u.isEmpty()) throw IllegalArgumentException("cookies.set: url required")
            val sb = StringBuilder(o.optString("name")).append('=').append(o.optString("value"))
            sb.append("; Path=").append(o.optString("path", "/"))
            if (o.optString("domain").isNotEmpty()) sb.append("; Domain=").append(o.optString("domain"))
            if (o.optBoolean("secure", false)) sb.append("; Secure")
            if (o.has("expirationDate")) sb.append("; Max-Age=").append(maxOf(0L, (o.optDouble("expirationDate") - System.currentTimeMillis() / 1000.0).toLong()))
            CookieManager.getInstance().setCookie(u, sb.toString())
            o.optString("name").let { n -> cookieList(u, n).optJSONObject(0) }
        }
        ChromeBridge.onSync("cookies.remove") { c ->
            val o = c.args.optJSONObject(0) ?: JSONObject()
            val u = o.optString("url")
            if (u.isNotEmpty()) CookieManager.getInstance().setCookie(u, o.optString("name") + "=; Path=/; Max-Age=0")
            JSONObject().put("url", u).put("name", o.optString("name")).put("storeId", "0")
        }
        ChromeBridge.onSync("cookies.getAllCookieStores") { JSONArray().put(JSONObject().put("id", "0").put("tabIds", JSONArray())) }

        handle("runtime.openOptionsPage") { c ->
            val u = ContentInjector.EXT_BASE + "options.html"
            val m = tm
            if (m != null && !ExtScreen.open(m.host(), u)) m.newTab(u, 0, true) // S10: window, tab as fallback
            c.ok(null)
        }
        // runtime.getContexts + offscreen.*: OffscreenHost (S9)

        // windows: one window; chrome.windows.create opens a tab
        handle("windows.create") { c ->
            val m = tm
            if (m == null) { c.fail("No browser window"); return@handle }
            val o = c.args.optJSONObject(0) ?: JSONObject()
            val t = m.newTab(fullUrl(o.optString("url", ""), c.extId), 0, o.optBoolean("focused", true))
            if (t == null) { c.fail("Too many tabs"); return@handle }
            c.ok(windowJson().put("type", o.optString("type", "normal")).put("tabs", JSONArray().put(ExtApi.tabFor(c.extId, tabJson(t)))))
        }
        ChromeBridge.onSync("windows.get") { windowJson() }
        ChromeBridge.onSync("windows.getCurrent") { windowJson() }
        handle("windows.update") { c -> // N2: {focused:true} = bring the tucked extension window back
            if (c.args.optJSONObject(1)?.optBoolean("focused", false) == true) ExtScreen.untuck()
            c.ok(windowJson())
        }

        // alarms
        handle("alarms.create") { c ->
            val first = c.args.opt(0)
            val name = if (first is String) first else ""
            val info = (if (first is String) c.args.optJSONObject(1) else c.args.optJSONObject(0)) ?: JSONObject()
            val period = (info.optDouble("periodInMinutes", 0.0) * 60000).toLong()
            val now = System.currentTimeMillis()
            val at = when {
                info.has("when") -> info.optLong("when")
                info.has("delayInMinutes") -> now + (info.optDouble("delayInMinutes") * 60000).toLong()
                period > 0 -> now + period
                else -> now
            }
            alarms.remove(name)?.task?.let { main.removeCallbacks(it) }
            val a = Alarm(name, at, period)
            alarms[name] = a
            saveAlarms()
            if (engineReady) arm(a)
            c.ok(null)
        }
        ChromeBridge.onSync("alarms.get") { c -> alarms[c.args.optString(0, "")]?.let { alarmJson(it) } }
        ChromeBridge.onSync("alarms.getAll") { JSONArray().also { arr -> for (a in alarms.values) arr.put(alarmJson(a)) } }
        ChromeBridge.onSync("alarms.clear") { c -> clearAlarm(c.args.optString(0, "")) }
        ChromeBridge.onSync("alarms.clearAll") {
            for (n in alarms.keys.toList()) clearAlarm(n)
            true
        }

        // cosmetic / not-applicable APIs: accept and ignore so V77 never fails on them
        for (api in listOf(
            "action.setBadgeText", "action.setBadgeBackgroundColor", "action.setIcon", "action.setTitle",
            "sidePanel.open", "sidePanel.setOptions", "sidePanel.setPanelBehavior",
            "contextMenus.update", "contextMenus.remove", "contextMenus.removeAll",
            "notifications.create", "notifications.clear", "notifications.update"
        )) ChromeBridge.onSync(api) { null }
        ChromeBridge.onSync("contextMenus.create") { c -> c.args.optJSONObject(0)?.optString("id") ?: "" }
        ChromeBridge.onSync("commands.getAll") { JSONArray() }
        ChromeBridge.onSync("permissions.contains") { true }
        ChromeBridge.onSync("permissions.request") { true }
        ChromeBridge.onSync("permissions.getAll") {
            JSONObject().put("permissions", JSONArray()).put("origins", JSONArray().put("<all_urls>"))
        }
    }

    // ---------- N1: executeScript / cookies helpers ----------

    private var nextXid = 1

    /** S6: text of an extension file: built-in assets for V77, files/exts/<id>/ (safe path) for an installed extension. */
    private fun readExtText(xid: String, rel: String): String? {
        if (xid == ContentInjector.EXT_ID) return app?.assets?.open("ext/$rel")?.bufferedReader()?.use { it.readText() }
        val f = ExtRegistry.file(xid, rel) ?: return null
        return if (f.length() <= 5_000_000L) f.readText() else null
    }

    private fun runScript(c: ChromeBridge.Call, inj: JSONObject = c.args.optJSONObject(0) ?: JSONObject()) {
        val tid = inj.optJSONObject("target")?.optInt("tabId", -1) ?: -1
        val t = vis(c, tm?.byId(tid))
        if (t == null) { c.fail("No tab with id: $tid."); return }
        if (c.extId != ContentInjector.EXT_ID) {
            // S6: an installed extension needs a host permission for the page (activeTab = the tab that is on screen)
            val info = ExtRegistry.get(c.extId)
            val ok = info != null && (ExtApi.hostAllows(info, t.url) || ("activeTab" in info.permissions && (tm?.active === t || sourceTabFor(c)?.id == t.id)))
            if (!ok) { c.fail("Cannot access contents of the page. Extension manifest must request permission to access this host."); return }
        }
        // 0.0.118 (S6): only the main frame can be reached (evaluateJavascript); a request for other frames fails like Chrome instead of running in the wrong frame
        val fids = inj.optJSONObject("target")?.optJSONArray("frameIds")
        if (fids != null && fids.length() > 0 && (0 until fids.length()).none { fids.optInt(it, -1) == 0 }) { c.fail("No frame with id " + fids.optInt(0) + " in tab " + tid + "."); return }
        val fn = inj.optString("func", "")
        val files = inj.optJSONArray("files")
        val body: String = when {
            fn.isNotEmpty() -> "(" + fn + ").apply(null," + (inj.optJSONArray("args") ?: JSONArray()).toString() + ")"
            files != null && files.length() > 0 -> {
                val sb = StringBuilder()
                for (i in 0 until files.length()) {
                    val p = files.optString(i).trimStart('/')
                    try { sb.append(readExtText(c.extId, p) ?: throw java.io.FileNotFoundException(p)).append("\n;") }
                    catch (e: Exception) { Diag.w("novel", "executeScript: could not load file " + p); c.fail("Could not load file: $p"); return } // S83
                }
                "(function(){" + (if (c.extId != ContentInjector.EXT_ID) ContentInjector.injectPrelude(c.extId) ?: "" else "") + sb + "\n})()" // S16: chrome for the injected file
            }
            else -> { c.fail("executeScript: func or files required"); return }
        }
        val id = nextXid++
        val start = "(function(){var R=window.__mgbx=window.__mgbx||{};" +
            "Promise.resolve().then(function(){return " + body + "}).then(" +
            "function(v){R[" + id + "]={r:v===undefined?null:v}}," +
            "function(e){R[" + id + "]={e:String(e&&e.message||e)}});return 1})()"
        val poll = "(function(){var R=window.__mgbx;if(!R)return 'gone';var x=R[" + id + "];if(!x)return null;delete R[" + id + "];" +
            "try{return JSON.stringify(x)}catch(e){return JSON.stringify({e:'result is not serializable'})}})()"
        val deadline = System.currentTimeMillis() + 180_000L
        fun pollOnce() {
            if (vis(c, tm?.byId(tid)) == null) { c.fail("The tab was closed."); return }
            t.web.evaluateJavascript(poll) { v ->
                var s: String? = null
                if (v != null && v != "null") s = try { JSONTokener(v).nextValue() as? String } catch (e: Exception) { null }
                when {
                    s == "gone" -> { Diag.w("novel", "executeScript: page reloaded before the script finished"); c.fail("The page was reloaded before the script finished.") } // S83
                    s != null -> try {
                        val x = JSONObject(s)
                        if (x.has("e")) { Diag.w("novel", "executeScript error", x.optString("e")); c.fail(x.optString("e")) } // S83
                        else c.ok(JSONArray().put(JSONObject().put("frameId", 0).put("result", if (x.isNull("r")) JSONObject.NULL else x.get("r"))))
                    } catch (e: Exception) { c.fail("Bad script result") }
                    System.currentTimeMillis() > deadline -> { Diag.w("novel", "executeScript timed out (180 s)"); c.fail("Script timed out.") } // S83
                    else -> main.postDelayed({ pollOnce() }, 200)
                }
            }
        }
        t.web.evaluateJavascript(start) { pollOnce() }
    }

    private fun cookieUrl(o: JSONObject): String? {
        val u = o.optString("url")
        if (u.isNotEmpty()) return u
        val d = o.optString("domain").trimStart('.')
        return if (d.isNotEmpty()) "https://$d/" else null
    }

    private fun cookieList(url: String, name: String?): JSONArray {
        val out = JSONArray()
        val raw = (try { CookieManager.getInstance().getCookie(url) } catch (e: Exception) { null }) ?: return out
        val host = try { android.net.Uri.parse(url).host ?: "" } catch (e: Exception) { "" }
        for (part in raw.split(";")) {
            val kv = part.trim()
            if (kv.isEmpty()) continue
            val i = kv.indexOf('=')
            val n = if (i >= 0) kv.substring(0, i) else kv
            val v = if (i >= 0) kv.substring(i + 1) else ""
            if (name != null && name != n) continue
            out.put(JSONObject().put("name", n).put("value", v).put("domain", host).put("hostOnly", true).put("path", "/")
                .put("secure", url.startsWith("https")).put("httpOnly", false).put("session", true).put("storeId", "0").put("sameSite", "unspecified"))
        }
        return out
    }

    private fun windowJson(): JSONObject = JSONObject()
        .put("id", 1).put("focused", true).put("incognito", false).put("type", "normal")
        .put("state", "normal").put("alwaysOnTop", false)

    // ---------- alarms ----------

    private class Alarm(val name: String, var whenMs: Long, val periodMs: Long) {
        var task: Runnable? = null
    }

    private val alarms = LinkedHashMap<String, Alarm>()

    private fun alarmJson(a: Alarm): JSONObject {
        val o = JSONObject().put("name", a.name).put("scheduledTime", a.whenMs)
        if (a.periodMs > 0) o.put("periodInMinutes", a.periodMs / 60000.0)
        return o
    }

    private fun arm(a: Alarm) {
        a.task?.let { main.removeCallbacks(it) }
        val r = Runnable { fire(a) }
        a.task = r
        main.postDelayed(r, (a.whenMs - System.currentTimeMillis()).coerceAtLeast(0L))
    }

    private fun fire(a: Alarm) {
        if (alarms[a.name] !== a) return
        ChromeBridge.emit("alarms.onAlarm", JSONArray().put(alarmJson(a))) { it.kind == ChromeBridge.Kind.EXT }
        if (a.periodMs > 0) {
            val now = System.currentTimeMillis()
            while (a.whenMs <= now) a.whenMs += a.periodMs
            saveAlarms()
            arm(a)
        } else {
            alarms.remove(a.name)
            saveAlarms()
        }
    }

    private fun clearAlarm(name: String): Boolean {
        val a = alarms.remove(name) ?: return false
        a.task?.let { main.removeCallbacks(it) }
        saveAlarms()
        return true
    }

    private fun saveAlarms() {
        val ctx = app ?: return
        val arr = JSONArray()
        for (a in alarms.values) arr.put(JSONObject().put("n", a.name).put("w", a.whenMs).put("p", a.periodMs))
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("alarms", arr.toString()).apply()
    }

    private fun loadAlarms() {
        val ctx = app ?: return
        val raw = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("alarms", null) ?: return
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                alarms[o.getString("n")] = Alarm(o.getString("n"), o.getLong("w"), o.optLong("p", 0L))
            }
        } catch (e: Exception) { Log.e(TAG, "alarms load", e) }
    }

    // ---------- engine start (called by EngineHost when the page has loaded) ----------

    fun isEngineReady(): Boolean = engineReady

    /** 0.0.166: Sniffer calls this for every request that reached the engine too early (it was dropped). */
    fun noteMissed(tabId: Int) { synchronized(missedTabs) { missedTabs.add(tabId) } }

    /** 0.0.166: the page on screen loaded before the engine was ready -> its videos were never seen. Reload it once. */
    private fun reloadIfMissed() {
        val t = tm?.active ?: return
        val hit = synchronized(missedTabs) { val h = t.id in missedTabs; missedTabs.clear(); h }
        if (!hit || !t.url.startsWith("http")) return
        EngineHost.addLog("engine: page loaded before the engine was ready, reloading it once")
        try { t.web.reload() } catch (_: Exception) { }
    }

    fun engineStarted() {
        main.postDelayed({ deliverStart(0) }, 300)
    }

    private fun deliverStart(attempt: Int) {
        val ctx = app ?: return
        val eng = ChromeBridge.contexts().firstOrNull {
            EngineHost.isEngine(it.web) && it.kind == ChromeBridge.Kind.EXT && listens(it, "runtime.onStartup")
        }
        if (eng == null) {
            if (attempt < 20) main.postDelayed({ deliverStart(attempt + 1) }, 300)
            else EngineHost.addLog("engine: background.js did not register onStartup (see log above)")
            return
        }
        val ver = try {
            JSONObject(ctx.assets.open("ext/manifest.json").bufferedReader().use { it.readText() }).optString("version")
        } catch (e: Exception) { "" }
        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val prev = sp.getString("ext_version", null)
        if (prev != ver) {
            sp.edit().putString("ext_version", ver).apply()
            val info = JSONObject().put("reason", if (prev == null) "install" else "update")
            if (prev != null) info.put("previousVersion", prev)
            ChromeBridge.emit("runtime.onInstalled", JSONArray().put(info)) { it === eng }
        }
        ChromeBridge.emit("runtime.onStartup", JSONArray()) { it === eng }
        engineReady = true
        for (a in alarms.values) arm(a)
        EngineHost.addLog("engine: background.js started (ext $ver)")
        main.postDelayed({ reloadIfMissed() }, 400) // 0.0.166
    }

    fun status(): String {
        val eng = ChromeBridge.contexts().firstOrNull { EngineHost.isEngine(it.web) }
        return "\nBackground script: " + (if (engineReady) "started" else "not started") +
            "\nListens runtime.onMessage: " + (eng != null && listens(eng, "runtime.onMessage")) +
            "\nTabs: " + (tm?.count ?: 0) + "   Alarms: " + alarms.size
    }
}
