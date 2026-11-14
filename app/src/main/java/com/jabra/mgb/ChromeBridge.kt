package com.jabra.mgb

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.WebView
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebViewCompat
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.WeakHashMap
import java.util.concurrent.Executors

/**
 * S4: native side of the chrome.* shim.
 *  - receives {t,i,a,p} JSON from every frame that runs the shim (WebMessageListener "MGBBridge")
 *  - routes `a` (e.g. "storage.local.get") to a registered handler; later steps add theirs with on()/onSync()
 *  - pushes events to frames with emit() and asks a frame to answer a runtime message with request()
 *  - owns chrome.storage (local/sync persisted in filesDir, session in memory)
 * Security: every message must carry the per-launch token (page scripts do not know it).
 * Frames on EXT_ORIGIN are trusted (Kind.EXT); every other origin is Kind.CONTENT and may only
 * use runtime.sendMessage and storage.local/sync.
 */
object ChromeBridge {
    enum class Kind { CONTENT, EXT }

    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private val ctxs = ArrayList<Ctx>()
    private val tabIds = WeakHashMap<WebView, Int>()
    private val handlers = HashMap<String, (Call) -> Unit>()
    private val replies = HashMap<Int, (Any?) -> Unit>()
    private val replyOwner = HashMap<Int, String>() // S3: request id -> extension that may answer it
    private var nextReq = 1
    private var logged = 0
    private var inited = false
    private val CONTENT_OK = Regex("^(runtime\\.sendMessage|storage\\.(local|sync)\\.[A-Za-z]+)$")
    private const val MAX_CTX_PER_WEB = 128

    private fun runMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block)
    }

    /** One frame that runs the shim. */
    class Ctx(val kind: Kind, val web: WebView, val origin: String, val isMainFrame: Boolean, val proxy: JavaScriptReplyProxy) {
        /** S3: extension ids whose shim has talked from this frame (several extensions can share one frame). */
        val ids = HashSet<String>()
        val tabId: Int get() = tabIds[web] ?: -1
        fun post(s: String) = runMain {
            try { proxy.postMessage(s) } catch (e: Exception) { drop(this) }
        }
    }

    /** One request from a frame. Handlers must call ok() or fail() exactly once (any thread). */
    class Call(val ctx: Ctx, val api: String, val args: JSONArray, private val id: Int, val extId: String = ContentInjector.EXT_ID) {
        private var done = false
        fun ok(result: Any? = null) {
            if (done) return
            done = true
            ctx.post(JSONObject().put("i", id).put("x", extId).put("r", result ?: JSONObject.NULL).toString())
        }
        fun fail(msg: String) {
            if (done) return
            done = true
            ctx.post(JSONObject().put("i", id).put("x", extId).put("err", msg).toString())
        }
    }

    // ---------- registry (used by S6..S10) ----------

    fun on(api: String, h: (Call) -> Unit) { handlers[api] = h }

    /** Convenience: return the result (JSONObject/JSONArray/String/Number/Boolean/null) or throw. */
    fun onSync(api: String, h: (Call) -> Any?) {
        handlers[api] = { c ->
            try { c.ok(h(c)) } catch (e: Exception) { c.fail(e.message ?: e.toString()) }
        }
    }

    fun bindTab(web: WebView, tabId: Int) { tabIds[web] = tabId }

    fun unbind(web: WebView) {
        tabIds.remove(web)
        for (c in ctxs.filter { it.web === web }) drop(c) // 0.0.111: ports of these frames are closed
        ctxs.removeAll { it.web === web }
    }

    /** 0.0.111 (S1): a frame went away (page left, WebView gone, shim said bye): ExtApi closes its ports. */
    var onCtxGone: ((Ctx) -> Unit)? = null
    private fun drop(c: Ctx) {
        if (ctxs.remove(c)) try { onCtxGone?.invoke(c) } catch (e: Exception) { Log.e("MGB.bridge", "ctxGone", e) }
    }

    fun contexts(): List<Ctx> = ctxs.toList()

    /** Push an event ("tabs.onRemoved", "storage.onChanged"...) to all frames that pass `filter`. */
    fun emit(event: String, args: JSONArray, extId: String = ContentInjector.EXT_ID, filter: (Ctx) -> Boolean = { true }) {
        val s = JSONObject().put("e", event).put("x", extId).put("p", args).toString()
        runMain { for (c in ctxs.toList()) if (filter(c)) c.post(s) }
    }

    /** Deliver runtime.onMessage to one frame; cb gets the response (null if none / timeout). */
    fun request(ctx: Ctx, message: Any?, sender: JSONObject, extId: String = ContentInjector.EXT_ID, event: String = "runtime.onMessage", cb: (Any?) -> Unit) { // 0.0.433 (S8): event = runtime.onMessageExternal
        runMain {
            if (extId !in ctx.ids) { cb(null); return@runMain } // S3: that extension has no shim in this frame
            val id = nextReq++
            replies[id] = cb
            replyOwner[id] = extId
            main.postDelayed({ replyOwner.remove(id); replies.remove(id)?.invoke(null) }, 30_000)
            ctx.post(
                JSONObject().put("e", event).put("x", extId).put("q", id)
                    .put("p", JSONArray().put(message ?: JSONObject.NULL).put(sender)).toString()
            )
        }
    }

    // ---------- WebView hookup (called by ContentInjector) ----------

    private val listener = object : WebViewCompat.WebMessageListener {
        override fun onPostMessage(
            view: WebView, message: WebMessageCompat, sourceOrigin: Uri,
            isMainFrame: Boolean, replyProxy: JavaScriptReplyProxy
        ) = onMessage(view, message.data, sourceOrigin, isMainFrame, replyProxy)
    }

    fun install(web: WebView, origins: Set<String>) {
        WebViewCompat.addWebMessageListener(web, "MGBBridge", origins, listener)
    }

    private fun onMessage(view: WebView, data: String?, origin: Uri, isMain: Boolean, proxy: JavaScriptReplyProxy) {
        if (data == null) return
        val m = try { JSONObject(data) } catch (_: Exception) { return }
        // S3: x = extension id of the shim that sent it (absent = V77); token is per extension
        val xid = m.optString("x", "").ifEmpty { ContentInjector.EXT_ID }
        if (m.optString("t") != ContentInjector.tokenFor(xid)) return
        if (xid != ContentInjector.EXT_ID && ExtRegistry.get(xid)?.enabled != true) return
        var ctx = ctxs.firstOrNull { it.proxy === proxy }
        if (ctx == null) {
            val kind = if (origin.toString() == ContentInjector.EXT_ORIGIN) Kind.EXT else Kind.CONTENT
            ctx = Ctx(kind, view, origin.toString(), isMain, proxy)
            ctxs.add(ctx)
            val same = ctxs.filter { it.web === view }
            if (same.size > MAX_CTX_PER_WEB) drop(same.first())
        }
        ctx.ids.add(xid)
        ExtBackground.touch(view) // S5: a call from a worker keeps it alive
        val api = m.optString("a")
        val args = m.optJSONArray("p") ?: JSONArray()
        when (api) {
            "__hello" -> return
            "__bye" -> { drop(ctx); return }
            "__reply" -> {
                val rid = args.optInt(0)
                if (replyOwner[rid] == xid) { replyOwner.remove(rid); replies.remove(rid)?.invoke(args.opt(1)) }
                return
            }
            "__log" -> { if (logged++ < 80) Log.d("MGB.shim", args.optString(0).take(600)); return }
            "__miss" -> { if (xid != ContentInjector.EXT_ID && ctx.kind == Kind.EXT) ExtGaps.record(xid, args.optString(0)); return } // 0.0.429 (S4): a stub of the shim was called
        }
        val call = Call(ctx, api, args, m.optInt("i"), xid)
        // S6: an installed extension is checked per api + manifest permission (ExtApi.gate); V77 keeps the old rule
        val optPage = xid != ContentInjector.EXT_ID && ctx.kind == Kind.EXT && api == "runtime.openOptionsPage"
        if (xid != ContentInjector.EXT_ID) {
            val err = ExtApi.gate(ctx, xid, api, args)
            if (err != null) { call.fail(err); return }
        } else if (ctx.kind == Kind.CONTENT && !CONTENT_OK.matches(api)) { call.fail("Not allowed: $api"); return }
        if (optPage) {
            val rel = ExtRegistry.get(xid)?.options
            if (rel == null) { call.fail("This extension has no options page"); return }
            main.post {
                val inTab = ExtRegistry.get(xid)?.optionsInTab == true // 0.0.432 (S7): options_ui.open_in_tab = a tab
                if (inTab && TabsApi.manager()?.newTab(ContentInjector.EXT_BASE + xid + "/" + rel.trimStart('/'), 0, true) != null) call.ok(null)
                else if (ExtScreen.openSibling(ContentInjector.EXT_BASE + xid + "/" + rel.trimStart('/'))) call.ok(null)
                else call.fail("Could not open the options page")
            }
            return
        }
        if (xid != ContentInjector.EXT_ID && api == "runtime.sendMessage") {
            // S5: ExtApi registers "ext.runtime.sendMessage" (content script / popup / options / worker of the same extension)
            val eh = handlers["ext.runtime.sendMessage"]
            if (eh == null) call.fail("Could not establish connection. Receiving end does not exist.") else eh(call)
            return
        }
        // S6: "ext.<api>" = version of a handler that knows which extension is calling (alarms, notifications ...)
        val h = (if (xid != ContentInjector.EXT_ID) handlers["ext.$api"] else null) ?: handlers[api]
        if (h == null) { if (xid != ContentInjector.EXT_ID && ctx.kind == Kind.EXT) ExtGaps.record(xid, api); call.fail("Not implemented: $api"); return } // 0.0.429 (S4)
        try { h(call) } catch (e: Exception) { call.fail(e.message ?: e.toString()) }
    }

    // ---------- storage ----------

    class Area(private val file: File?) {
        private var data = JSONObject()
        private var scheduled = false

        init {
            if (file != null && file.exists()) {
                try { data = JSONObject(file.readText()) } catch (_: Exception) { data = JSONObject() }
            }
        }

        @Synchronized fun get(keys: Any?): JSONObject {
            val out = JSONObject()
            when (keys) {
                null, JSONObject.NULL -> for (k in data.keys()) out.put(k, data.get(k))
                is String -> if (data.has(keys)) out.put(keys, data.get(keys))
                is JSONArray -> for (i in 0 until keys.length()) {
                    val k = keys.optString(i)
                    if (data.has(k)) out.put(k, data.get(k))
                }
                is JSONObject -> for (k in keys.keys()) out.put(k, if (data.has(k)) data.get(k) else keys.get(k))
            }
            return out
        }

        /** Returns the chrome "changes" object ({key:{oldValue,newValue}}). */
        @Synchronized fun set(items: JSONObject): JSONObject {
            val ch = JSONObject()
            for (k in items.keys()) {
                val nv = items.get(k)
                val had = data.has(k)
                val ov = if (had) data.get(k) else null
                if (had && ov.toString() == nv.toString()) continue
                val c = JSONObject().put("newValue", nv)
                if (had) c.put("oldValue", ov)
                ch.put(k, c)
                data.put(k, nv)
            }
            if (ch.length() > 0) persist()
            return ch
        }

        @Synchronized fun remove(keys: Any?): JSONObject {
            val ch = JSONObject()
            val list = ArrayList<String>()
            when (keys) {
                is String -> list.add(keys)
                is JSONArray -> for (i in 0 until keys.length()) list.add(keys.optString(i))
            }
            for (k in list) if (data.has(k)) {
                ch.put(k, JSONObject().put("oldValue", data.get(k)))
                data.remove(k)
            }
            if (ch.length() > 0) persist()
            return ch
        }

        @Synchronized fun clear(): JSONObject {
            val ch = JSONObject()
            for (k in data.keys().asSequence().toList()) ch.put(k, JSONObject().put("oldValue", data.get(k)))
            data = JSONObject()
            if (ch.length() > 0) persist()
            return ch
        }

        @Synchronized fun bytes(keys: Any?): Int {
            val sel = get(keys)
            var n = 0
            for (k in sel.keys()) n += k.length + sel.get(k).toString().length
            return n
        }

        private fun persist() {
            if (file == null || scheduled) return
            scheduled = true
            main.postDelayed({ flush() }, 400)
        }

        fun flush() {
            val f = file ?: return
            val snap = synchronized(this) { scheduled = false; data.toString() }
            io.execute {
                try {
                    val tmp = File(f.parentFile, f.name + ".tmp")
                    tmp.writeText(snap)
                    if (!tmp.renameTo(f)) { f.writeText(snap); tmp.delete() }
                } catch (e: Exception) { Log.e("MGB", "storage write failed", e) }
            }
        }
    }

    lateinit var local: Area
        private set
    lateinit var sync: Area
        private set
    lateinit var session: Area
        private set

    fun area(name: String): Area = when (name) { "local" -> local; "sync" -> sync; else -> session }

    // S3: every installed extension has its own areas: filesDir/exts_data/<id>/{local,sync}.json (session = memory).
    // Kept outside exts/<id>/ so an update (folder swap) keeps the data; removeExtData() wipes it.
    private val extAreas = HashMap<String, Area>()
    private lateinit var dataRoot: File
    private const val QUOTA_LOCAL = 10L * 1024 * 1024
    private const val QUOTA_SYNC = 102_400L

    fun area(extId: String, name: String): Area {
        if (extId == ContentInjector.EXT_ID) return area(name)
        if (!ExtRegistry.isValidId(extId)) throw IllegalArgumentException("bad extension id")
        return synchronized(extAreas) {
            extAreas.getOrPut("$extId/$name") {
                if (name == "session") Area(null)
                else Area(File(File(dataRoot, extId).also { it.mkdirs() }, "$name.json"))
            }
        }
    }

    /** Call after an extension is removed: deletes its storage (files + memory). */
    fun removeExtData(ctx: Context, extId: String) {
        init(ctx)
        if (!ExtRegistry.isValidId(extId) || extId == ContentInjector.EXT_ID) return
        synchronized(extAreas) { extAreas.keys.removeAll { it.startsWith("$extId/") } }
        try { File(dataRoot, extId).deleteRecursively() } catch (e: Exception) { Log.e("MGB", "ext data wipe failed", e) }
    }

    /** Set storage values from native code (e.g. S15/S16) and notify all frames. */
    fun storageSet(areaName: String, items: JSONObject, extId: String = ContentInjector.EXT_ID) =
        fire(areaName, area(extId, areaName).set(items), extId)

    private fun fire(areaName: String, changes: JSONObject, extId: String = ContentInjector.EXT_ID) {
        if (changes.length() > 0) emit("storage.onChanged", JSONArray().put(changes).put(areaName), extId)
    }

    private fun checkQuota(extId: String, n: String, a: Area, items: JSONObject) {
        if (extId == ContentInjector.EXT_ID) return
        val limit = when (n) { "local" -> QUOTA_LOCAL; "sync" -> QUOTA_SYNC; else -> QUOTA_LOCAL }
        var add = 0L
        for (k in items.keys()) add += k.length + items.get(k).toString().length
        if (a.bytes(null) + add > limit) throw IllegalStateException("QUOTA_BYTES quota exceeded")
    }

    @Synchronized fun init(ctx: Context) {
        if (inited) return
        inited = true
        val dir = ctx.applicationContext.filesDir
        dataRoot = File(dir, "exts_data")
        local = Area(File(dir, "ext_storage_local.json"))
        sync = Area(File(dir, "ext_storage_sync.json"))
        session = Area(null)
        for (n in listOf("local", "sync", "session")) {
            onSync("storage.$n.get") { c -> area(c.extId, n).get(c.args.opt(0)) }
            onSync("storage.$n.set") { c ->
                val a = area(c.extId, n)
                val items = c.args.optJSONObject(0) ?: JSONObject()
                checkQuota(c.extId, n, a, items)
                fire(n, a.set(items), c.extId); null
            }
            onSync("storage.$n.remove") { c -> fire(n, area(c.extId, n).remove(c.args.opt(0)), c.extId); null }
            onSync("storage.$n.clear") { c -> fire(n, area(c.extId, n).clear(), c.extId); null }
            onSync("storage.$n.getBytesInUse") { c -> area(c.extId, n).bytes(c.args.opt(0)) }
        }
        onSync("event.add") { null } // frames announce the events they listen to; S6+ may use it
    }
}
