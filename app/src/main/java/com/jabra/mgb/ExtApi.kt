package com.jabra.mgb

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject

/**
 * S5 + S6: chrome.* for INSTALLED extensions (the built-in V77 keeps its own handlers in TabsApi / ChromeBridge).
 *  - gate(): which api an extension page / content script may call (permission from its manifest, host check for cookies).
 *    ChromeBridge asks it for every message of a non-V77 extension. Unknown / V77-only apis answer "Not supported in MGB".
 *  - runtime.sendMessage between the pieces of ONE extension (content script, popup, options, worker); wakes the worker.
 *  - alarms (own timers, saved), notifications (system notification, toast if not allowed), contextMenus (saved; shown by ExtMenus in link / image menus and the tray page menu, 0.0.116),
 *    permissions (read-only view of the manifest), tab events with url / title removed when the extension may not see them.
 *  - sync(): called after install / update / toggle / remove: stops workers, drops alarms, fires runtime.onInstalled.
 * Handlers are registered as "ext.<api>"; ChromeBridge prefers them for a non-V77 extension and falls back to the plain one.
 */
object ExtApi {
    private const val PREFS = "mgb_ext_bg"
    private const val CH = "mgb_ext"
    private const val NO_RECEIVER = "Could not establish connection. Receiving end does not exist."

    private val CONTENT_OK = Regex("^(runtime\\.(sendMessage|connect)|port\\.(post|disconnect)|storage\\.(local|sync|session)\\.[A-Za-z]+)$") // 0.0.118: storage.session only after setAccessLevel (see gate) // 0.0.111: + ports
    private val EXT_OK = Regex(
        "^(runtime\\.(sendMessage|openOptionsPage|connect|getContexts|reload)|port\\.(post|disconnect)|event\\.add|storage\\.(local|sync|session)\\.[A-Za-z]+" +
            "|tabs\\.(sendMessage|connect|create|update|getCurrent|remove|get|query|reload|executeScript|duplicate|move|goBack|goForward|getZoom|setZoom|getZoomSettings|setZoomSettings|highlight|discard|captureVisibleTab)" +
            "|windows\\.(create|get|getCurrent|update|getAll|getLastFocused|remove)|search\\.query|browsingData\\.[A-Za-z]+|webNavigation\\.(getFrame|getAllFrames)|action\\.[A-Za-z]+|alarms\\.[A-Za-z]+|notifications\\.[A-Za-z]+" +
            "|contextMenus\\.[A-Za-z]+|scripting\\.(executeScript|insertCSS|registerContentScripts|updateContentScripts|unregisterContentScripts|getRegisteredContentScripts)|cookies\\.[A-Za-z]+|permissions\\.[A-Za-z]+|commands\\.getAll" +
            "|declarativeNetRequest\\.(updateSessionRules|updateDynamicRules|getSessionRules|getDynamicRules|getEnabledRulesets|updateEnabledRulesets|isRegexSupported|setExtensionActionOptions))$"
    )
    private val sessionOpen = HashSet<String>() // 0.0.118 (S6): extensions that called storage.session.setAccessLevel({accessLevel:"TRUSTED_AND_UNTRUSTED_CONTEXTS"}) (memory only, like the data)
    private val NEEDS = mapOf(
        "storage" to "storage", "alarms" to "alarms", "notifications" to "notifications",
        "contextMenus" to "contextMenus", "scripting" to "scripting", "cookies" to "cookies",
        "browsingData" to "browsingData", "search" to "search", "webNavigation" to "webNavigation" // 0.0.431 (S6)
    )

    private val main = Handler(Looper.getMainLooper())
    private var app: Context? = null
    private var inited = false
    private val lastEn = HashMap<String, Boolean>()
    private val mem = HashMap<String, HashSet<String>>() // events the worker listened to in its last run (wake rule)
    private val menus = HashMap<String, LinkedHashMap<String, JSONObject>>()
    private var menuSeq = 0
    private var noteSeq = 0

    private fun prefs() = app?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ---------- permissions ----------

    fun hostPats(info: ExtRegistry.Info): List<String> =
        info.hostPermissions + info.permissions.filter { it.contains("://") || it == "<all_urls>" }

    fun hostPats(id: String): List<String> = ExtRegistry.get(id)?.let { hostPats(it) } ?: emptyList()

    fun hostAllows(info: ExtRegistry.Info, url: String): Boolean = ContentInjector.urlAllowed(hostPats(info), url)

    /** tabs url / title are visible with the "tabs" permission or a host permission matching that tab. */
    private fun canSee(info: ExtRegistry.Info, url: String): Boolean = "tabs" in info.permissions || hostAllows(info, url)

    private fun scrub(o: JSONObject): JSONObject {
        val c = JSONObject(o.toString())
        for (k in listOf("url", "pendingUrl", "title", "favIconUrl")) c.remove(k)
        return c
    }

    /** A tab object as this extension may see it (V77 sees everything). */
    fun tabFor(xid: String, o: JSONObject): JSONObject {
        if (xid == ContentInjector.EXT_ID) return o
        val info = ExtRegistry.get(xid) ?: return scrub(o)
        return if (canSee(info, o.optString("url", "")) || ("activeTab" in info.permissions && o.optBoolean("active", false))) o else scrub(o) // S15: activeTab shows the tab on screen
    }

    fun tabsFor(xid: String, arr: JSONArray): JSONArray {
        if (xid == ContentInjector.EXT_ID) return arr
        val out = JSONArray()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i)
            if (o != null) out.put(tabFor(xid, o))
        }
        return out
    }

    /** null = allowed, else the error text for chrome.runtime.lastError. */
    fun gate(ctx: ChromeBridge.Ctx, xid: String, api: String, args: JSONArray): String? {
        val info = ExtRegistry.get(xid)
        if (info == null || !info.enabled) return "Extension is turned off"
        if (ctx.kind == ChromeBridge.Kind.CONTENT && !CONTENT_OK.matches(api)) return "Not allowed: $api"
        // 0.0.118 (S6): storage.session is for trusted contexts (popup, worker, options) unless the extension opened it for content scripts
        if (ctx.kind == ChromeBridge.Kind.CONTENT && api.startsWith("storage.session.") &&
            (api == "storage.session.setAccessLevel" || synchronized(sessionOpen) { xid !in sessionOpen })) return "Access to storage is not allowed from this context."
        if (ctx.kind == ChromeBridge.Kind.EXT && !EXT_OK.matches(api)) {
            Diag.w("ext", "api not supported", info.name + ": " + api) // S16: so the Report center shows which chrome.* call an extension needs
            ExtGaps.record(xid, api) // 0.0.429 (S4)
            return "Not supported in MGB: $api"
        }
        // S7: declarativeNetRequest (or ...WithHostAccess, which still needs a matching host permission to act)
        if (api.startsWith("declarativeNetRequest.") && "declarativeNetRequest" !in info.permissions &&
            "declarativeNetRequestWithHostAccess" !in info.permissions) return "Needs the \"declarativeNetRequest\" permission: $api"
        val need = NEEDS[api.substringBefore('.')]
        if (need != null && need !in info.permissions) return "Needs the \"$need\" permission: $api"
        if (api.startsWith("cookies.") && api != "cookies.getAllCookieStores") {
            val o = args.optJSONObject(0)
            var u = o?.optString("url", "") ?: ""
            if (u.isEmpty()) {
                val d = (o?.optString("domain", "") ?: "").trimStart('.')
                if (d.isNotEmpty()) u = "https://$d/"
            }
            if (u.isNotEmpty() && !hostAllows(info, u)) return "No host permission for $u"
        }
        return null
    }

    // ---------- init ----------

    fun init(ctx: Context) {
        if (inited) return
        inited = true
        app = ctx.applicationContext
        ExtGaps.init(ctx) // 0.0.429 (S4)
        registerHandlers()
        ChromeBridge.onCtxGone = { ctxGone(it) } // 0.0.111
        loadAlarms()
        doSync(true)
    }

    /** After install / update / toggle / remove (ExtListScreen). */
    fun sync(ctx: Context) {
        if (!inited) init(ctx) else doSync(false)
        ContentInjector.refresh(ctx) // 0.0.108: open tabs get the changed content scripts
    }

    private fun doSync(first: Boolean) {
        val a = app ?: return
        val sp = a.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val ids = HashSet<String>()
        for (info in ExtRegistry.all()) {
            val id = info.id
            ids.add(id)
            val prev = lastEn[id]
            lastEn[id] = info.enabled
            if (!info.enabled) {
                if (prev != false) {
                    ExtBackground.stop(id, "turned off")
                    cancelAlarms(id)
                    menus.remove(id)
                }
                continue
            }
            if (prev == false) ExtBackground.reset(id)
            val pv = sp.getString("ver_$id", null)
            if (pv != info.version) {
                sp.edit().putString("ver_$id", info.version).apply()
                if (pv != null) { ExtBackground.stop(id, "updated"); ExtBackground.reset(id) }
                if (ExtBackground.has(id)) fireInstalled(id, if (pv == null) "install" else "update", pv)
            }
        }
        for (id in lastEn.keys.toList()) {
            if (id in ids) continue
            lastEn.remove(id)
            ExtBackground.stop(id, "removed")
            ExtBackground.reset(id)
            cancelAlarms(id)
            menus.remove(id)
            HeaderModes.forget(id) // S7: header rules of the removed extension
            synchronized(wr) { wr.remove(id) }; prefs()?.edit()?.remove("wrf_$id")?.apply() // 0.0.119 (S5): webRequest filters
            ContentInjector.regForget(a, id) // 0.0.115 (S3): registered content scripts of the removed extension
            mem.remove(id)
            sp.edit().remove("ver_$id").remove("mem_$id").remove("menus_$id").apply()
        }
        if (first) main.postDelayed({ startupWake() }, 1500)
    }

    private fun isWorkerCtx(c: ChromeBridge.Ctx, id: String): Boolean =
        c.kind == ChromeBridge.Kind.EXT && ExtBackground.idOf(c.web) == id

    private fun fireInstalled(id: String, reason: String, prev: String?) {
        ExtBackground.ensure(id) { ok ->
            if (ok) {
                val o = JSONObject().put("reason", reason)
                if (prev != null) o.put("previousVersion", prev)
                ChromeBridge.emit("runtime.onInstalled", JSONArray().put(o), id) {
                    isWorkerCtx(it, id) && TabsApi.listens(it, "runtime.onInstalled")
                }
            }
        }
    }

    /** App start: a worker that listened to runtime.onStartup last time is started now (others stay lazy). */
    private fun startupWake() {
        for (info in ExtRegistry.enabled()) {
            val id = info.id
            if (!ExtBackground.has(id) || !remembers(id, "runtime.onStartup")) continue
            ExtBackground.ensure(id) { ok ->
                if (ok) ChromeBridge.emit("runtime.onStartup", JSONArray(), id) {
                    isWorkerCtx(it, id) && TabsApi.listens(it, "runtime.onStartup")
                }
            }
        }
    }

    // ---------- listener memory (which events wake a stopped worker) ----------

    private fun memOf(id: String): HashSet<String> = mem.getOrPut(id) {
        val s = HashSet<String>()
        try {
            val raw = prefs()?.getString("mem_$id", null)
            if (raw != null) {
                val arr = JSONArray(raw)
                for (i in 0 until arr.length()) s.add(arr.optString(i))
            }
        } catch (_: Exception) { }
        s
    }

    private fun persistMem(id: String) {
        val arr = JSONArray()
        for (e in memOf(id)) arr.put(e)
        prefs()?.edit()?.putString("mem_$id", arr.toString())?.apply()
    }

    fun remembers(id: String, ev: String): Boolean = ev in memOf(id)

    // ---------- webRequest (0.0.119, S5): observe only (MV3 has no blocking). Filters of the listeners are remembered like mem_<id> ----------

    private val wr = HashMap<String, HashMap<String, JSONArray>>() // xid -> event -> filters ({urls, types, tabId})
    private var wrSeq = 0

    private fun wrOf(id: String): HashMap<String, JSONArray> = synchronized(wr) {
        wr.getOrPut(id) {
            val m = HashMap<String, JSONArray>()
            try {
                val raw = prefs()?.getString("wrf_$id", null)
                if (raw != null) { val o = JSONObject(raw); for (k in o.keys()) m[k] = o.getJSONArray(k) }
            } catch (_: Exception) { }
            m
        }
    }

    private fun wrSave(id: String) {
        val o = JSONObject()
        for ((k, v) in wrOf(id)) o.put(k, v)
        prefs()?.edit()?.putString("wrf_$id", o.toString())?.apply()
    }

    /** event.add of a worker with a webRequest event: args = [name, filter, extraInfoSpec]. */
    fun onListenFilter(c: ChromeBridge.Call, ev: String) {
        if (!ev.startsWith("webRequest.") || c.extId == ContentInjector.EXT_ID || !isWorkerCtx(c.ctx, c.extId)) return
        val info = ExtRegistry.get(c.extId) ?: return
        if ("webRequest" !in info.permissions) return
        val f = c.args.optJSONObject(1) ?: JSONObject()
        synchronized(wr) {
            val arr = wrOf(c.extId).getOrPut(ev) { JSONArray() }
            if (arr.length() < 50) { arr.put(f); wrSave(c.extId) }
        }
    }

    /** True when some enabled extension has webRequest listeners (cheap check before building event data). */
    fun webRequestActive(): Boolean {
        for (info in ExtRegistry.enabled()) {
            if ("webRequest" !in info.permissions) continue
            if (synchronized(wr) { wrOf(info.id).isNotEmpty() }) return true
        }
        return false
    }

    private fun wrFilterOk(f: JSONObject, url: String, type: String, tabId: Int): Boolean {
        val urls = f.optJSONArray("urls")
        if (urls != null && urls.length() > 0) {
            val l = ArrayList<String>()
            for (i in 0 until urls.length()) l.add(urls.optString(i))
            if (!ContentInjector.urlAllowed(l, url)) return false
        }
        val types = f.optJSONArray("types")
        if (types != null && types.length() > 0) {
            var ok = false
            for (i in 0 until types.length()) if (types.optString(i) == type) ok = true
            if (!ok) return false
        }
        if (f.has("tabId") && f.optInt("tabId", -1) != tabId) return false
        return true
    }

    /** One request of a web page -> the extensions that listen (permission webRequest + host permission for the url). */
    fun webRequest(event: String, url: String, tabId: Int, type: String, method: String, initiator: String?, headers: Map<String, String>?, error: String?) {
        var d: JSONObject? = null
        for (info in ExtRegistry.enabled()) {
            if ("webRequest" !in info.permissions) continue
            val fl = synchronized(wr) { wrOf(info.id)[event] } ?: continue
            if (!hostAllows(info, url)) continue
            var ok = false
            for (i in 0 until fl.length()) { val f = fl.optJSONObject(i); if (f != null && wrFilterOk(f, url, type, tabId)) { ok = true; break } }
            if (!ok) continue
            if (d == null) {
                d = JSONObject().put("requestId", "mgb" + (++wrSeq)).put("url", url).put("method", method).put("frameId", 0)
                    .put("parentFrameId", -1).put("tabId", tabId).put("type", type).put("timeStamp", System.currentTimeMillis().toDouble())
                if (initiator != null) d.put("initiator", initiator)
                if (headers != null && (event == "webRequest.onBeforeSendHeaders" || event == "webRequest.onSendHeaders")) {
                    val ha = JSONArray()
                    for ((k, v) in headers) ha.put(JSONObject().put("name", k).put("value", v))
                    d.put("requestHeaders", ha)
                }
                if (error != null) d.put("error", error)
            }
            emitExt(info.id, event, JSONArray().put(d), remembers(info.id, event))
        }
    }

    /** A new run of the worker begins: its listener list is rebuilt. */
    fun bgStarted(id: String) {
        synchronized(wr) { wr[id] = HashMap() }
        wrSave(id)
        mem[id] = HashSet()
        persistMem(id)
    }

    /** event.add from a frame (TabsApi calls this). */
    fun onListen(c: ChromeBridge.Call, ev: String) {
        if (c.extId == ContentInjector.EXT_ID || !isWorkerCtx(c.ctx, c.extId)) return
        if (memOf(c.extId).add(ev)) persistMem(c.extId)
    }

    // ---------- events ----------

    /** Send an event to the pages of one extension that listen to it; wake = start its worker first if it is stopped. */
    fun emitExt(xid: String, event: String, args: JSONArray, wake: Boolean) {
        fun go() {
            ChromeBridge.emit(event, args, xid) { it.kind == ChromeBridge.Kind.EXT && xid in it.ids && TabsApi.listens(it, event) }
        }
        if (wake && ExtBackground.has(xid) && !ExtBackground.running(xid)) ExtBackground.ensure(xid) { go() } else go()
    }

    /** tabs.onCreated / onUpdated ... for every enabled extension; url / title are removed where it may not see them. */
    fun tabsEvent(event: String, tab: TabManager.Tab?, args: List<Any?>) {
        for (info in ExtRegistry.enabled()) {
            val xid = info.id
            val see = tab == null || canSee(info, tab.url)
            val a = JSONArray()
            for (x in args) a.put(if (x is JSONObject && !see) scrub(x) else (x ?: JSONObject.NULL))
            emitExt(xid, event, a, remembers(xid, event))
        }
    }

    // ---------- messaging inside one extension ----------

    private fun send(c: ChromeBridge.Call) {
        val xid = c.extId
        val msg = c.args.opt(0)
        fun go() {
            val targets = ChromeBridge.contexts().filter {
                it.kind == ChromeBridge.Kind.EXT && it !== c.ctx && xid in it.ids && TabsApi.listens(it, "runtime.onMessage")
            }
            if (targets.isEmpty()) { c.fail(NO_RECEIVER); return }
            TabsApi.fanOut(targets, msg, TabsApi.senderOf(c.ctx, xid), xid) { r -> c.ok(r) }
        }
        if (ExtBackground.has(xid)) ExtBackground.ensure(xid) { go() } else go()
    }

    // ---------- action button (S2, 0.0.112): badge, title, popup, enabled, onClicked. The button lives in ExtTray ----------

    private class Act {
        var text: String? = null; var color: Int? = null; var textColor: Int? = null
        var title: String? = null; var popup: String? = null; var enabled: Boolean? = null
    }
    private val acts = HashMap<String, Act>() // xid -> values for every tab
    private val actTabs = HashMap<String, LinkedHashMap<Int, Act>>() // xid -> tabId -> values for that tab only
    private val DEF_BADGE = 0xFF5F6368.toInt()

    /** What the button of one extension shows / does for one tab (tabId <= 0 = no tab). popup null = no popup. */
    class ActionView(val text: String, val color: Int, val textColor: Int?, val title: String, val popup: String?, val enabled: Boolean)

    fun actionView(xid: String, tabId: Int): ActionView = synchronized(acts) {
        val info = ExtRegistry.get(xid)
        val g = acts[xid]
        val t = if (tabId > 0) actTabs[xid]?.get(tabId) else null
        val popup = t?.popup ?: g?.popup ?: info?.popup
        ActionView(t?.text ?: g?.text ?: "", t?.color ?: g?.color ?: DEF_BADGE, t?.textColor ?: g?.textColor,
            t?.title ?: g?.title ?: (info?.name ?: ""), popup?.ifEmpty { null }, t?.enabled ?: g?.enabled ?: true)
    }

    private fun colorOf(v: Any?): Int? = try {
        when (v) {
            is String -> {
                val x = v.trim()
                android.graphics.Color.parseColor(if (Regex("^#[0-9a-fA-F]{3}$").matches(x)) "#" + x.substring(1).map { "$it$it" }.joinToString("") else x)
            }
            is JSONArray -> android.graphics.Color.argb(if (v.length() > 3) v.optInt(3) else 255, v.optInt(0), v.optInt(1), v.optInt(2))
            else -> null
        }
    } catch (e: Exception) { null }

    private fun tabIdOf(v: Any?): Int = when (v) {
        is Number -> v.toInt()
        is JSONObject -> if (v.has("tabId") && !v.isNull("tabId")) v.optInt("tabId", -1) else -1
        else -> -1
    }

    private fun actSet(xid: String, tabId: Int, f: (Act) -> Unit) = synchronized(acts) {
        val a = if (tabId > 0) {
            val m = actTabs.getOrPut(xid) { LinkedHashMap() }
            if (m.size > 200 && !m.containsKey(tabId)) m.remove(m.keys.first())
            m.getOrPut(tabId) { Act() }
        } else acts.getOrPut(xid) { Act() }
        f(a)
    }

    private fun popupRel(xid: String, v: Any?): String {
        val s = (v?.toString() ?: "").trim()
        return s.removePrefix(ContentInjector.EXT_BASE + xid + "/").trimStart('/')
    }

    private fun hasClickListener(xid: String): Boolean =
        remembers(xid, "action.onClicked") ||
            ChromeBridge.contexts().any { it.kind == ChromeBridge.Kind.EXT && xid in it.ids && TabsApi.listens(it, "action.onClicked") }

    /** ExtTray: the button was tapped and the extension has no popup. true = action.onClicked was sent (a stopped worker is started first). */
    fun actionClicked(xid: String, tab: TabManager.Tab?): Boolean {
        if (!hasClickListener(xid)) return false
        val a = JSONArray().put(if (tab != null) tabFor(xid, TabsApi.tabJson(tab)) else JSONObject.NULL)
        emitExt(xid, "action.onClicked", a, true)
        return true
    }

    /** 0.0.113: like actionClicked, but a worker that is stopped or never ran (no listener remembered yet) is started first and asked again. cb(true) = onClicked was sent. cb runs on the main thread. */
    fun actionClickedWake(xid: String, tab: TabManager.Tab?, cb: (Boolean) -> Unit) {
        if (hasClickListener(xid)) { cb(actionClicked(xid, tab)); return }
        if (!ExtBackground.has(xid)) { cb(false); return }
        ExtBackground.ensure(xid) { ok -> cb(ok && hasClickListener(xid) && actionClicked(xid, tab)) }
    }

    // ---------- long-lived ports: runtime.connect / tabs.connect (S1, 0.0.111) ----------
    // The shim makes the port id and sends port.post / port.disconnect with it; Kotlin only routes by id.
    // from = frame that connected, targets = frames of the SAME extension that got runtime.onConnect.

    private class Port(val id: String, val xid: String, val name: String, val from: ChromeBridge.Ctx) {
        var targets: List<ChromeBridge.Ctx> = emptyList()
        var ready = false
        val queue = ArrayList<Any>()
        val held = ArrayList<ChromeBridge.Ctx>()
    }

    private val ports = HashMap<String, Port>()

    private fun portUi(b: () -> Unit) { if (Looper.myLooper() == Looper.getMainLooper()) b() else main.post { b() } }

    private fun portEvent(to: ChromeBridge.Ctx, xid: String, event: String, args: JSONArray) =
        to.post(JSONObject().put("e", event).put("x", xid).put("p", args).toString())

    private fun portRelease(p: Port, only: ChromeBridge.Ctx? = null) {
        for (t in p.held.filter { only == null || it === only }) { p.held.remove(t); ExtBackground.release(t.web) }
    }

    /** err goes to the side that did not close it; skip = the frame that closed it (gets nothing). */
    private fun portClose(p: Port, err: String?, skip: ChromeBridge.Ctx?) {
        if (ports.remove(p.id) == null) return
        portRelease(p)
        val a = JSONArray().put(p.id)
        if (err != null) a.put(err)
        if (p.from !== skip) portEvent(p.from, p.xid, "port.disc", a)
        for (t in p.targets) if (t !== skip) portEvent(t, p.xid, "port.disc", JSONArray().put(p.id))
    }

    private fun portTargetGone(p: Port, ctx: ChromeBridge.Ctx, err: String?) {
        p.targets = p.targets.filter { it !== ctx }
        portRelease(p, ctx)
        if (p.targets.isEmpty() && p.ready) portClose(p, err, ctx)
    }

    private fun portAttach(p: Port, targets: List<ChromeBridge.Ctx>) {
        if (ports[p.id] !== p) return // closed while the worker was starting
        if (targets.isEmpty()) { portClose(p, NO_RECEIVER, null); return }
        p.targets = targets
        val info = JSONObject().put("id", p.id).put("name", p.name).put("sender", TabsApi.senderOf(p.from, p.xid))
        for (t in targets) {
            if (ExtBackground.hold(t.web)) p.held.add(t) // an open port keeps its worker alive
            portEvent(t, p.xid, "runtime.onConnect", JSONArray().put(info))
        }
        p.ready = true
        for (m in p.queue) for (t in targets) portEvent(t, p.xid, "port.msg", JSONArray().put(p.id).put(m))
        p.queue.clear()
    }

    private fun newPort(c: ChromeBridge.Call): Port? {
        val pid = c.args.optString(0)
        val name = if (c.api == "tabs.connect") c.args.optString(2) else c.args.optString(1)
        val pidOk = if (c.api == "tabs.connect") c.args.optString(1) else pid
        if (pidOk.isEmpty() || pidOk.length > 80 || ports.containsKey(pidOk) || ports.size > 200) { c.fail("Invalid port"); return null }
        val p = Port(pidOk, c.extId, name.take(200), c.ctx)
        ports[pidOk] = p
        c.ok(null) // like chrome: connect never fails here; "no receiver" arrives as onDisconnect with lastError
        return p
    }

    /** tabs.connect (TabsApi finds the frames of the tab); call on the main thread. */
    fun openPort(c: ChromeBridge.Call, targets: List<ChromeBridge.Ctx>) {
        val p = newPort(c) ?: return
        portAttach(p, targets)
    }

    /** ChromeBridge: a frame went away. */
    fun ctxGone(ctx: ChromeBridge.Ctx) = portUi {
        for (p in ports.values.toList()) {
            if (p.from === ctx) portClose(p, null, ctx)
            else if (p.targets.any { it === ctx }) portTargetGone(p, ctx, null)
        }
    }

    // ---------- alarms ----------

    private class Alarm(val xid: String, val name: String, var whenMs: Long, val periodMs: Long) {
        var task: Runnable? = null
    }

    private val alarms = LinkedHashMap<String, Alarm>()

    private fun akey(x: String, n: String) = "$x|$n"

    private fun alarmJson(a: Alarm): JSONObject {
        val o = JSONObject().put("name", a.name).put("scheduledTime", a.whenMs)
        if (a.periodMs > 0) o.put("periodInMinutes", a.periodMs / 60000.0)
        return o
    }

    private fun arm(a: Alarm) {
        a.task?.let { main.removeCallbacks(it) }
        val r = Runnable { fireAlarm(a) }
        a.task = r
        main.postDelayed(r, (a.whenMs - System.currentTimeMillis()).coerceAtLeast(0L))
    }

    private fun fireAlarm(a: Alarm) {
        if (alarms[akey(a.xid, a.name)] !== a) return
        val info = ExtRegistry.get(a.xid)
        if (info == null || !info.enabled) { cancelAlarms(a.xid); return }
        emitExt(a.xid, "alarms.onAlarm", JSONArray().put(alarmJson(a)), true)
        if (a.periodMs > 0) {
            val now = System.currentTimeMillis()
            while (a.whenMs <= now) a.whenMs += a.periodMs
            saveAlarms()
            arm(a)
        } else {
            alarms.remove(akey(a.xid, a.name))
            saveAlarms()
        }
    }

    private fun cancelAlarms(xid: String) {
        val mine = alarms.values.filter { it.xid == xid }
        if (mine.isEmpty()) return
        for (a in mine) {
            a.task?.let { main.removeCallbacks(it) }
            alarms.remove(akey(a.xid, a.name))
        }
        saveAlarms()
    }

    private fun clearAlarm(xid: String, name: String): Boolean {
        val a = alarms.remove(akey(xid, name)) ?: return false
        a.task?.let { main.removeCallbacks(it) }
        saveAlarms()
        return true
    }

    private fun saveAlarms() {
        val arr = JSONArray()
        for (a in alarms.values) arr.put(JSONObject().put("x", a.xid).put("n", a.name).put("w", a.whenMs).put("p", a.periodMs))
        prefs()?.edit()?.putString("alarms", arr.toString())?.apply()
    }

    private fun loadAlarms() {
        val raw = prefs()?.getString("alarms", null) ?: return
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val x = o.getString("x")
                if (!ExtRegistry.isValidId(x) || ExtRegistry.get(x)?.enabled != true) continue
                val a = Alarm(x, o.getString("n"), o.getLong("w"), o.optLong("p", 0L))
                alarms[akey(x, a.name)] = a
                arm(a)
            }
        } catch (_: Exception) { }
    }

    // ---------- notifications ----------

    private fun noteId(xid: String, id: String): Int = "$xid|$id".hashCode()

    private fun showNote(xid: String, id: String, o: JSONObject) {
        val a = app ?: return
        val name = ExtRegistry.get(xid)?.name ?: "Extension"
        val title = o.optString("title", name)
        val msg = o.optString("message", "")
        val allowed = Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(a, "android.permission.POST_NOTIFICATIONS") == PackageManager.PERMISSION_GRANTED
        if (!allowed) { Util.toast(a, "$name: $title $msg".trim()); return }
        try {
            val nm = a.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(NotificationChannel(CH, "Extensions", NotificationManager.IMPORTANCE_DEFAULT))
            val icon = if (a.applicationInfo.icon != 0) a.applicationInfo.icon else android.R.drawable.ic_dialog_info
            val b = NotificationCompat.Builder(a, CH)
                .setSmallIcon(icon).setContentTitle(title).setContentText(msg)
                .setStyle(NotificationCompat.BigTextStyle().bigText(msg)).setSubText(name).setAutoCancel(true)
            nm.notify(noteId(xid, id), b.build())
        } catch (e: Exception) {
            Diag.w("engine", "ext notification failed", e.message)
        }
    }

    // ---------- context menus (0.0.116, S4): saved per extension (menus_<id> in prefs), shown by ExtMenus ----------

    /** The items of one extension in creation order; loaded from prefs on first use (a worker restart / app restart keeps them, like Chrome). */
    private fun menuMap(xid: String): LinkedHashMap<String, JSONObject> = menus.getOrPut(xid) {
        val m = LinkedHashMap<String, JSONObject>()
        try {
            val raw = prefs()?.getString("menus_$xid", null)
            if (raw != null) {
                val a = JSONArray(raw)
                for (i in 0 until a.length()) {
                    val o = a.optJSONObject(i) ?: continue
                    val k = o.optString("id")
                    if (k.isNotEmpty()) m[k] = o
                }
            }
        } catch (_: Exception) { }
        m
    }

    private fun saveMenus(xid: String) {
        val a = JSONArray()
        menus[xid]?.values?.forEach { a.put(it) }
        prefs()?.edit()?.putString("menus_$xid", a.toString())?.apply()
    }

    /** For ExtMenus: the items an extension created (copies are not needed, ExtMenus only reads). */
    fun menuItems(xid: String): List<JSONObject> = synchronized(menus) { menuMap(xid).values.toList() }

    /** A checkbox flips, a radio becomes checked (the other radios of the same parent are cleared). info gets checked / wasChecked, parentMenuItemId. Saved. */
    fun menuToggle(xid: String, id: String, info: JSONObject) {
        synchronized(menus) {
            val m = menuMap(xid)
            val o = m[id] ?: return
            val pid = o.optString("parentId", "")
            if (pid.isNotEmpty()) info.put("parentMenuItemId", pid)
            val type = o.optString("type", "normal")
            if (type != "checkbox" && type != "radio") return
            val was = o.optBoolean("checked", false)
            info.put("wasChecked", was)
            if (type == "checkbox") o.put("checked", !was)
            else {
                for (x in m.values) if (x !== o && x.optString("type") == "radio" && x.optString("parentId", "") == pid) x.put("checked", false)
                o.put("checked", true)
            }
            info.put("checked", o.optBoolean("checked", false))
            saveMenus(xid)
        }
    }

    /** ExtMenus: tell the extension an item was clicked (a stopped worker is started first). */
    fun menuClicked(xid: String, id: String, info: JSONObject, tab: JSONObject?) {
        val inf = ExtRegistry.get(xid) ?: return
        val t = if (tab == null) JSONObject.NULL else tabFor(inf.id, tab)
        emitExt(xid, "contextMenus.onClicked", JSONArray().put(info.put("menuItemId", id)).put(t), true)
    }

    // ---------- handlers ----------

    private fun strs(o: JSONObject?, key: String): List<String> {
        val a = o?.optJSONArray(key) ?: return emptyList()
        return (0 until a.length()).map { a.optString(it) }
    }

    private fun registerHandlers() {
        ChromeBridge.on("ext.runtime.sendMessage") { c -> send(c) }
        ExtApi3.register() // 0.0.431 (S6): webNavigation, runtime.reload, search.query, browsingData
        ExtApi2.register() // 0.0.430 (S5): tabs.duplicate / move / goBack / goForward / zoom / highlight / discard / captureVisibleTab, windows.getAll / getLastFocused / remove
        ChromeBridge.onSync("storage.session.setAccessLevel") { c -> // 0.0.118 (S6)
            val lv = c.args.optJSONObject(0)?.optString("accessLevel", "") ?: ""
            if (lv != "TRUSTED_CONTEXTS" && lv != "TRUSTED_AND_UNTRUSTED_CONTEXTS") throw IllegalArgumentException("Invalid value for 'accessLevel'.")
            synchronized(sessionOpen) { if (lv == "TRUSTED_CONTEXTS") sessionOpen.remove(c.extId) else sessionOpen.add(c.extId) }
            null
        }

        // 0.0.112 (S2): action button (installed extensions only; V77 keeps its no-op handlers in TabsApi)
        ChromeBridge.onSync("ext.action.setBadgeText") { c ->
            val d = c.args.optJSONObject(0); val t = d?.opt("text")
            actSet(c.extId, tabIdOf(d)) { it.text = if (t == null || t == JSONObject.NULL) "" else t.toString().take(4) }
            null
        }
        ChromeBridge.onSync("ext.action.setBadgeBackgroundColor") { c ->
            val d = c.args.optJSONObject(0); val col = colorOf(d?.opt("color"))
            if (col != null) actSet(c.extId, tabIdOf(d)) { it.color = col }
            null
        }
        ChromeBridge.onSync("ext.action.setBadgeTextColor") { c ->
            val d = c.args.optJSONObject(0); val col = colorOf(d?.opt("color"))
            if (col != null) actSet(c.extId, tabIdOf(d)) { it.textColor = col }
            null
        }
        ChromeBridge.onSync("ext.action.setTitle") { c ->
            val d = c.args.optJSONObject(0); val t = d?.opt("title")
            actSet(c.extId, tabIdOf(d)) { it.title = if (t == null || t == JSONObject.NULL) null else t.toString().take(200) }
            null
        }
        ChromeBridge.onSync("ext.action.setIcon") { null } // the tray keeps the manifest icon
        ChromeBridge.onSync("ext.action.setPopup") { c ->
            val d = c.args.optJSONObject(0)
            actSet(c.extId, tabIdOf(d)) { it.popup = popupRel(c.extId, d?.opt("popup")) } // "" = no popup (button sends onClicked)
            null
        }
        ChromeBridge.onSync("ext.action.getBadgeText") { c -> actionView(c.extId, tabIdOf(c.args.optJSONObject(0))).text }
        ChromeBridge.onSync("ext.action.getBadgeBackgroundColor") { c ->
            val k = actionView(c.extId, tabIdOf(c.args.optJSONObject(0))).color
            JSONArray().put(android.graphics.Color.red(k)).put(android.graphics.Color.green(k)).put(android.graphics.Color.blue(k)).put(android.graphics.Color.alpha(k))
        }
        ChromeBridge.onSync("ext.action.getBadgeTextColor") { c ->
            val k = actionView(c.extId, tabIdOf(c.args.optJSONObject(0))).textColor ?: 0xFFFFFFFF.toInt()
            JSONArray().put(android.graphics.Color.red(k)).put(android.graphics.Color.green(k)).put(android.graphics.Color.blue(k)).put(android.graphics.Color.alpha(k))
        }
        ChromeBridge.onSync("ext.action.getTitle") { c -> actionView(c.extId, tabIdOf(c.args.optJSONObject(0))).title }
        ChromeBridge.onSync("ext.action.getPopup") { c ->
            val p = actionView(c.extId, tabIdOf(c.args.optJSONObject(0))).popup
            if (p == null) "" else ContentInjector.EXT_BASE + c.extId + "/" + p.trimStart('/')
        }
        ChromeBridge.onSync("ext.action.enable") { c -> actSet(c.extId, tabIdOf(c.args.opt(0))) { it.enabled = true }; null }
        ChromeBridge.onSync("ext.action.disable") { c -> actSet(c.extId, tabIdOf(c.args.opt(0))) { it.enabled = false }; null }
        ChromeBridge.onSync("ext.action.isEnabled") { c -> actionView(c.extId, tabIdOf(c.args.opt(0))).enabled }
        ChromeBridge.onSync("ext.action.openPopup") { c ->
            val p = actionView(c.extId, TabsApi.activeTab()?.id ?: -1).popup ?: throw IllegalStateException("This extension has no popup")
            main.post { ExtScreen.openSibling(ContentInjector.EXT_BASE + c.extId + "/" + p.trimStart('/')) }
            null
        }

        // 0.0.115 (S3): registered content scripts (installed extensions only; they apply on the next page load of a tab)
        fun regDone() { val a = app; if (a != null) main.post { ContentInjector.refresh(a) } }
        fun idsOf(c: ChromeBridge.Call): JSONArray? = c.args.optJSONObject(0)?.optJSONArray("ids")
        ChromeBridge.onSync("ext.scripting.registerContentScripts") { c ->
            ContentInjector.regAdd(app ?: throw IllegalStateException("Not ready"), c.extId, c.args.optJSONArray(0), hostPats(c.extId)); regDone(); null
        }
        ChromeBridge.onSync("ext.scripting.updateContentScripts") { c ->
            ContentInjector.regUpdate(app ?: throw IllegalStateException("Not ready"), c.extId, c.args.optJSONArray(0), hostPats(c.extId)); regDone(); null
        }
        ChromeBridge.onSync("ext.scripting.unregisterContentScripts") { c ->
            ContentInjector.regRemove(app ?: throw IllegalStateException("Not ready"), c.extId, idsOf(c)); regDone(); null
        }
        ChromeBridge.onSync("ext.scripting.getRegisteredContentScripts") { c ->
            ContentInjector.regList(app ?: throw IllegalStateException("Not ready"), c.extId, idsOf(c))
        }
        // 0.0.349: same 4 calls for the BUILT-IN grabber engine (plain names; installed extensions keep the "ext." ones above). Failing here changes nothing else.
        try { BuiltinReg.install({ app }) { regDone() } } catch (e: Exception) { android.util.Log.e("MGB.builtinReg", "install failed", e) }

        // 0.0.111 (S1): ports
        ChromeBridge.on("ext.runtime.connect") { c -> portUi {
            val p = newPort(c)
            if (p != null) {
                val xid = c.extId
                fun go() = portAttach(p, ChromeBridge.contexts().filter {
                    it.kind == ChromeBridge.Kind.EXT && it !== c.ctx && xid in it.ids && TabsApi.listens(it, "runtime.onConnect")
                })
                if (ExtBackground.has(xid)) ExtBackground.ensure(xid) { go() } else go()
            }
        } }
        ChromeBridge.on("ext.port.post") { c -> portUi {
            val p = ports[c.args.optString(0)]
            if (p != null && p.xid == c.extId) {
                val m: Any = c.args.opt(1) ?: JSONObject.NULL
                val a = JSONArray().put(p.id).put(m)
                if (c.ctx === p.from) { if (p.ready) { for (t in p.targets) portEvent(t, p.xid, "port.msg", a) } else p.queue.add(m) }
                else if (p.targets.any { it === c.ctx }) portEvent(p.from, p.xid, "port.msg", a)
            }
            c.ok(null)
        } }
        ChromeBridge.on("ext.port.disconnect") { c -> portUi {
            val p = ports[c.args.optString(0)]
            if (p != null && p.xid == c.extId) {
                if (c.ctx === p.from) portClose(p, null, c.ctx)
                else if (p.targets.any { it === c.ctx }) portTargetGone(p, c.ctx, if (c.args.optBoolean(1, false)) NO_RECEIVER else null)
            }
            c.ok(null)
        } }


        // permissions: read-only view of the manifest (no run-time grants on Android)
        ChromeBridge.onSync("ext.permissions.getAll") { c ->
            val info = ExtRegistry.get(c.extId)
            JSONObject()
                .put("permissions", JSONArray(info?.permissions?.filter { !it.contains("://") && it != "<all_urls>" } ?: emptyList<String>()))
                .put("origins", JSONArray(info?.let { hostPats(it) } ?: emptyList<String>()))
        }
        ChromeBridge.onSync("ext.permissions.contains") { c ->
            val info = ExtRegistry.get(c.extId)
            val o = c.args.optJSONObject(0)
            if (info == null) false
            else {
                val perms = strs(o, "permissions").all { it in info.permissions }
                val origins = strs(o, "origins").all { it in hostPats(info) }
                perms && origins
            }
        }
        ChromeBridge.onSync("ext.permissions.request") { false }

        // alarms
        ChromeBridge.onSync("ext.alarms.create") { c ->
            val first = c.args.opt(0)
            val name = if (first is String) first else ""
            val info = (if (first is String) c.args.optJSONObject(1) else c.args.optJSONObject(0)) ?: JSONObject()
            var period = (info.optDouble("periodInMinutes", 0.0) * 60000).toLong()
            if (period > 0 && period < 30_000L) period = 30_000L
            val now = System.currentTimeMillis()
            val at = when {
                info.has("when") -> info.optLong("when")
                info.has("delayInMinutes") -> now + (info.optDouble("delayInMinutes") * 60000).toLong()
                period > 0 -> now + period
                else -> now
            }
            alarms.remove(akey(c.extId, name))?.task?.let { main.removeCallbacks(it) }
            val a = Alarm(c.extId, name, at, period)
            alarms[akey(c.extId, name)] = a
            saveAlarms()
            arm(a)
            null
        }
        ChromeBridge.onSync("ext.alarms.get") { c -> alarms[akey(c.extId, c.args.optString(0, ""))]?.let { alarmJson(it) } }
        ChromeBridge.onSync("ext.alarms.getAll") { c ->
            val arr = JSONArray()
            for (a in alarms.values) if (a.xid == c.extId) arr.put(alarmJson(a))
            arr
        }
        ChromeBridge.onSync("ext.alarms.clear") { c -> clearAlarm(c.extId, c.args.optString(0, "")) }
        ChromeBridge.onSync("ext.alarms.clearAll") { c ->
            cancelAlarms(c.extId)
            true
        }

        // notifications (no onClicked: a tap does not come back to the extension yet)
        ChromeBridge.onSync("ext.notifications.create") { c ->
            val first = c.args.opt(0)
            val given = if (first is String) first else ""
            val o = (if (first is String) c.args.optJSONObject(1) else c.args.optJSONObject(0)) ?: JSONObject()
            val id = if (given.isEmpty()) "n" + (++noteSeq) else given
            showNote(c.extId, id, o)
            id
        }
        ChromeBridge.onSync("ext.notifications.clear") { c ->
            val a = app
            if (a != null) (a.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(noteId(c.extId, c.args.optString(0, "")))
            true
        }
        ChromeBridge.onSync("ext.notifications.update") { false }

        // context menus
        ChromeBridge.onSync("ext.contextMenus.create") { c ->
            val o = c.args.optJSONObject(0) ?: JSONObject()
            synchronized(menus) {
                val m = menuMap(c.extId)
                if (m.size >= 100) throw IllegalStateException("Too many context menu items")
                var id = o.optString("id", "")
                if (id.isEmpty()) { do { id = "menu" + (++menuSeq) } while (m.containsKey(id)); o.put("id", id) }
                if (m.containsKey(id)) throw IllegalArgumentException("Cannot create item with duplicate id $id")
                val pid = o.optString("parentId", "")
                if (pid.isNotEmpty() && !m.containsKey(pid)) throw IllegalArgumentException("Cannot find menu item with id $pid")
                m[id] = JSONObject(o.toString())
                saveMenus(c.extId)
                id
            }
        }
        ChromeBridge.onSync("ext.contextMenus.update") { c ->
            synchronized(menus) {
                val id = c.args.optString(0, "")
                val cur = menuMap(c.extId)[id] ?: throw IllegalArgumentException("Cannot find menu item with id $id")
                val p = c.args.optJSONObject(1)
                if (p != null) for (k in p.keys().asSequence().toList()) if (k != "id") cur.put(k, p.get(k))
                saveMenus(c.extId)
            }
            null
        }
        ChromeBridge.onSync("ext.contextMenus.remove") { c ->
            synchronized(menus) {
                val m = menuMap(c.extId)
                val id = c.args.optString(0, "")
                if (!m.containsKey(id)) throw IllegalArgumentException("Cannot find menu item with id $id")
                m.remove(id)
                var again = true // children go with their parent
                while (again) { again = false; for (k in m.keys.toList()) { val pp = m[k]?.optString("parentId", "") ?: ""; if (pp.isNotEmpty() && !m.containsKey(pp)) { m.remove(k); again = true } } }
                saveMenus(c.extId)
            }
            null
        }
        ChromeBridge.onSync("ext.contextMenus.removeAll") { c ->
            synchronized(menus) { menuMap(c.extId).clear(); saveMenus(c.extId) }
            null
        }
    }
}
