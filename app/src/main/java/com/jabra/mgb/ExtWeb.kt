package com.jabra.mgb

import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.WebView
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.ScriptHandler
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebViewCompat
import org.json.JSONArray
import org.json.JSONObject
import java.util.WeakHashMap

/**
 * 0.0.433 (S8): manifest "externally_connectable" (web page -> installed extension) + manifest "commands".
 *
 * A web page of a listed origin gets chrome.runtime.sendMessage(extId, msg, cb) and chrome.runtime.connect(extId) and NOTHING else.
 * The extension receives runtime.onMessageExternal / runtime.onConnectExternal (sender = {url, origin, tab, frameId}).
 * SECURITY (R8): own listener "MGBExt" installed only for the origins of the manifests (WebView origin rules) and checked AGAIN here
 * against the origin the WebView reports (never a value from JS); no token, no extension id leaks to a page that is not listed (every
 * refusal says "Receiving end does not exist"); JSON only, 1 MB cap, 60 messages / connects per minute per page, max 100 open ports;
 * wildcard-only patterns are not accepted (ExtSafety.externalPatterns); never in private tabs. The built-in Media Grabber is not touched.
 * Not supported: other EXTENSIONS as senders (externally_connectable.ids), subframe ids.
 */
object ExtWeb {
    private const val TAG = "MGB.extweb"
    private const val NAME = "MGBExt"
    private const val MAX_MSG = 1_000_000
    private const val NO_RECV = "Could not establish connection. Receiving end does not exist."
    private val main = Handler(Looper.getMainLooper())

    // ---------- origins ----------

    private val PAT = Regex("^(\\*|https?)://([^/]*)(/.*)?$")
    private class Conn(val sig: String, val pats: List<String>)
    private val cache = HashMap<String, Conn>()

    /** Safe externally_connectable.matches of an installed extension (cached per manifest file). */
    @Synchronized private fun patterns(info: ExtRegistry.Info): List<String> {
        val mf = java.io.File(info.dir, "manifest.json")
        if (!mf.isFile || mf.length() > 2_000_000L) return emptyList()
        val sig = "${mf.lastModified()}:${mf.length()}"
        cache[info.id]?.let { if (it.sig == sig) return it.pats }
        val out = ArrayList<String>()
        try {
            val ec = JSONObject(mf.readText().removePrefix("\uFEFF")).optJSONObject("externally_connectable")
            val arr = ec?.optJSONArray("matches")
            val raw = ArrayList<String>()
            if (arr != null) for (i in 0 until arr.length()) raw.add(arr.optString(i))
            out.addAll(ExtSafety.externalPatterns(raw))
        } catch (e: Exception) { Log.e(TAG, "manifest ${info.id}", e) }
        cache[info.id] = Conn(sig, out)
        return out
    }

    /** WebView origin rules of every enabled extension that has externally_connectable. */
    private fun allOrigins(): Set<String> {
        val out = LinkedHashSet<String>()
        try {
            for (info in ExtRegistry.enabled()) {
                if (!ExtRegistry.isValidId(info.id)) continue
                for (p in patterns(info)) {
                    val m = PAT.find(p) ?: continue
                    val schemes = if (m.groupValues[1] == "*") listOf("http", "https") else listOf(m.groupValues[1])
                    for (s in schemes) out.add("$s://${m.groupValues[2]}")
                }
            }
        } catch (e: Exception) { Log.e(TAG, "origins", e) }
        return out
    }

    /** origin = "https://host[:port]" as reported by the WebView. The path of a pattern is ignored. */
    private fun originAllowed(info: ExtRegistry.Info, origin: String): Boolean {
        for (p in patterns(info)) {
            val m = PAT.find(p) ?: continue
            val rx = ContentInjector.patRegex(m.groupValues[1] + "://" + m.groupValues[2] + "/*") ?: continue
            try { if (Regex(rx).containsMatchIn("$origin/")) return true } catch (_: Exception) { }
        }
        return false
    }

    // ---------- page side ----------

    private val PAGE_JS = """
(function(){try{
var B=window.MGBExt;if(!B||typeof B.postMessage!=='function')return;
if(location.protocol!=='https:'&&location.protocol!=='http:')return;
var NR='Could not establish connection. Receiving end does not exist.',MAX=1000000,seq=0,pend={},ports={},lastError;
function okId(i){return typeof i==='string'&&i.length===32&&!/[^a-p]/.test(i);}
function send(o){var s=JSON.stringify(o);if(s.length>MAX)throw new Error('Message length exceeded maximum allowed length.');B.postMessage(s);}
function ev(){var s=[];return{addListener:function(f){if(typeof f==='function'&&s.indexOf(f)<0)s.push(f);},removeListener:function(f){var i=s.indexOf(f);if(i>=0)s.splice(i,1);},hasListener:function(f){return s.indexOf(f)>=0;},hasListeners:function(){return s.length>0;},_f:function(a){s.slice().forEach(function(f){try{f.apply(null,a);}catch(e){}});}};}
B.onmessage=function(e){var m;try{m=JSON.parse(e.data);}catch(x){return;}
if(!m)return;
if(m.e){var p=ports[m.c];if(!p)return;
if(m.e==='port.msg'){p.onMessage._f([m.m,p]);}
else if(m.e==='port.disc'){p._dead=true;delete ports[m.c];if(m.err)lastError={message:String(m.err)};p.onDisconnect._f([p]);lastError=undefined;}
return;}
var c=pend[m.i];if(!c)return;delete pend[m.i];
if(m.err)c[1](new Error(String(m.err)));else c[0](m.r===null?undefined:m.r);};
function sendMessage(){var a=[].slice.call(arguments),cb=null;
if(a.length&&typeof a[a.length-1]==='function')cb=a.pop();
var pr=new Promise(function(res,rej){
if(!okId(a[0])){rej(new Error('runtime.sendMessage() called from a webpage must specify an Extension ID (string) for its first argument.'));return;}
if(a.length<2){rej(new Error('Invalid arguments to runtime.sendMessage.'));return;}
var i=++seq;pend[i]=[res,rej];
try{send({t:'msg',i:i,x:a[0],m:a[1]===undefined?null:a[1]});}catch(x){delete pend[i];rej(x);}
});
if(!cb)return pr;
pr.then(function(r){lastError=undefined;try{cb(r);}catch(x){}},function(x){lastError={message:String((x&&x.message)||x)};try{cb();}catch(y){}lastError=undefined;});
}
function connect(id,info){
if(!okId(id))throw new Error('runtime.connect() called from a webpage must specify an Extension ID (string) for its first argument.');
info=info||{};var c=++seq;
var p={name:String(info.name||''),onMessage:ev(),onDisconnect:ev(),_dead:false};
p.postMessage=function(m){if(p._dead)throw new Error('Attempting to use a disconnected port object');send({t:'post',c:c,m:m===undefined?null:m});};
p.disconnect=function(){if(p._dead)return;p._dead=true;delete ports[c];try{send({t:'disc',c:c});}catch(x){}};
ports[c]=p;
try{send({t:'conn',c:c,x:id,n:p.name});}catch(x){p._dead=true;delete ports[c];throw x;}
return p;}
var rt={sendMessage:sendMessage,connect:connect};
Object.defineProperty(rt,'lastError',{get:function(){return lastError;},enumerable:true});
var ch=window.chrome;
if(!ch||typeof ch!=='object'){try{Object.defineProperty(window,'chrome',{value:{runtime:rt},configurable:true,writable:true});}catch(x){}}
else if(!ch.runtime){try{ch.runtime=rt;}catch(x){}}
try{window.addEventListener('pagehide',function(){try{B.postMessage(JSON.stringify({t:'bye'}));}catch(x){}});}catch(x){}
}catch(e){}})();
"""

    /** Called for every tab WebView (not private ones) from ContentInjector.addExtScripts. Re-run after an extension change (refresh): the old listener is removed first. */
    fun attach(web: WebView, keep: ArrayList<ScriptHandler>) {
        val origins = allOrigins()
        try { WebViewCompat.removeWebMessageListener(web, NAME) } catch (_: Exception) { }
        if (origins.isEmpty()) return
        WebViewCompat.addWebMessageListener(web, NAME, origins, listener)
        keep.add(WebViewCompat.addDocumentStartJavaScript(web, PAGE_JS, origins))
    }

    // ---------- native side ----------

    private val listener = object : WebViewCompat.WebMessageListener {
        override fun onPostMessage(view: WebView, message: WebMessageCompat, sourceOrigin: Uri, isMainFrame: Boolean, replyProxy: JavaScriptReplyProxy) {
            try { handle(view, message.data, sourceOrigin.toString(), isMainFrame, replyProxy) } catch (e: Exception) { Log.e(TAG, "message", e) }
        }
    }

    private val hits = WeakHashMap<JavaScriptReplyProxy, ArrayList<Long>>()

    private fun rateOk(p: JavaScriptReplyProxy, limit: Int): Boolean {
        val now = System.currentTimeMillis()
        val l = hits.getOrPut(p) { ArrayList() }
        l.removeAll { now - it > 60_000L }
        if (l.size >= limit) return false
        l.add(now)
        return true
    }

    private fun toPage(p: JavaScriptReplyProxy, o: JSONObject) {
        val s = o.toString()
        main.post { try { p.postMessage(s) } catch (_: Exception) { } }
    }

    private fun answer(p: JavaScriptReplyProxy, id: Int, r: Any?, err: String?) {
        val o = JSONObject().put("i", id)
        if (err != null) o.put("err", err) else o.put("r", r ?: JSONObject.NULL)
        if (o.toString().length > MAX_MSG) { toPage(p, JSONObject().put("i", id).put("err", "Message length exceeded maximum allowed length.")); return }
        toPage(p, o)
    }

    private fun senderOf(view: WebView, xid: String, origin: String, isMain: Boolean): JSONObject {
        val s = JSONObject().put("origin", origin).put("frameId", if (isMain) 0 else 1)
        val cur = try { view.url } catch (_: Exception) { null }
        s.put("url", if (isMain && cur != null && cur.startsWith("$origin/")) cur else "$origin/")
        try {
            val tab = TabsApi.manager()?.all()?.firstOrNull { it.web === view && !it.priv }
            if (tab != null) s.put("tab", ExtApi.tabFor(xid, TabsApi.tabJson(tab)))
        } catch (_: Exception) { }
        return s
    }

    private fun checked(xid: String, origin: String): ExtRegistry.Info? {
        if (!ExtRegistry.isValidId(xid)) return null
        val info = ExtRegistry.get(xid) ?: return null
        if (!info.enabled || !originAllowed(info, origin)) return null
        return info
    }

    private fun handle(view: WebView, data: String?, origin: String, isMain: Boolean, proxy: JavaScriptReplyProxy) {
        if (data == null || data.length > MAX_MSG + 2000) return
        if (!(origin.startsWith("https://") || origin.startsWith("http://"))) return
        val m = try { JSONObject(data) } catch (_: Exception) { return }
        when (m.optString("t")) {
            "bye" -> closeOf(proxy)
            "msg" -> {
                val id = m.optInt("i")
                if (!rateOk(proxy, 60)) { answer(proxy, id, null, "Too many messages."); return }
                val xid = m.optString("x")
                if (checked(xid, origin) == null) { answer(proxy, id, null, NO_RECV); return }
                val msg: Any = m.opt("m") ?: JSONObject.NULL
                val sender = senderOf(view, xid, origin, isMain)
                fun go() {
                    val targets = ChromeBridge.contexts().filter {
                        it.kind == ChromeBridge.Kind.EXT && xid in it.ids && TabsApi.listens(it, "runtime.onMessageExternal")
                    }
                    if (targets.isEmpty()) { answer(proxy, id, null, NO_RECV); return }
                    TabsApi.fanOut(targets, msg, sender, xid, "runtime.onMessageExternal") { r -> answer(proxy, id, r, null) }
                }
                if (ExtBackground.has(xid)) ExtBackground.ensure(xid) { go() } else go()
            }
            "conn" -> {
                val c = m.optInt("c")
                if (!rateOk(proxy, 60)) { discToPage(proxy, c, "Too many connections."); return }
                val xid = m.optString("x")
                if (checked(xid, origin) == null) { discToPage(proxy, c, NO_RECV); return }
                if (ports.size >= 100 || ports.values.count { it.proxy === proxy } >= 20) { discToPage(proxy, c, "Too many connections."); return }
                if (ports.values.any { it.proxy === proxy && it.c == c }) return
                val p = WPort("w" + (++seq) + java.lang.Long.toString(System.nanoTime(), 36), xid, m.optString("n").take(200), proxy, c, senderOf(view, xid, origin, isMain))
                ports[p.id] = p
                fun go() {
                    if (ports[p.id] !== p) return // closed while the worker was starting
                    val targets = ChromeBridge.contexts().filter {
                        it.kind == ChromeBridge.Kind.EXT && xid in it.ids && TabsApi.listens(it, "runtime.onConnectExternal")
                    }
                    if (targets.isEmpty()) { close(p, NO_RECV, null); return }
                    p.targets = targets
                    val info = JSONObject().put("id", p.id).put("name", p.name).put("sender", p.sender)
                    for (t in targets) {
                        if (ExtBackground.hold(t.web)) p.held.add(t)
                        t.post(JSONObject().put("e", "runtime.onConnectExternal").put("x", xid).put("p", JSONArray().put(info)).toString())
                    }
                    p.ready = true
                    for (q in p.queue) for (t in targets) toExt(t, xid, "port.msg", JSONArray().put(p.id).put(q))
                    p.queue.clear()
                }
                if (ExtBackground.has(xid)) ExtBackground.ensure(xid) { go() } else go()
            }
            "post" -> {
                if (!rateOk(proxy, 600)) return
                val p = ports.values.firstOrNull { it.proxy === proxy && it.c == m.optInt("c") } ?: return
                val q: Any = m.opt("m") ?: JSONObject.NULL
                if (p.ready) { for (t in p.targets) toExt(t, p.xid, "port.msg", JSONArray().put(p.id).put(q)) }
                else if (p.queue.size < 100) p.queue.add(q)
            }
            "disc" -> {
                val p = ports.values.firstOrNull { it.proxy === proxy && it.c == m.optInt("c") } ?: return
                close(p, null, proxy)
            }
        }
    }

    // ---------- ports (page <-> extension pages) ----------

    private class WPort(val id: String, val xid: String, val name: String, val proxy: JavaScriptReplyProxy, val c: Int, val sender: JSONObject) {
        var targets: List<ChromeBridge.Ctx> = emptyList()
        var ready = false
        val queue = ArrayList<Any>()
        val held = ArrayList<ChromeBridge.Ctx>()
    }

    private val ports = HashMap<String, WPort>()
    private var seq = 0

    private fun toExt(t: ChromeBridge.Ctx, xid: String, event: String, args: JSONArray) =
        t.post(JSONObject().put("e", event).put("x", xid).put("p", args).toString())

    private fun discToPage(proxy: JavaScriptReplyProxy, c: Int, err: String?) {
        val o = JSONObject().put("e", "port.disc").put("c", c)
        if (err != null) o.put("err", err)
        toPage(proxy, o)
    }

    /** skipPage = the page that closed it (gets nothing); err goes to the page. Main thread. */
    private fun close(p: WPort, err: String?, skipPage: JavaScriptReplyProxy?, skipCtx: ChromeBridge.Ctx? = null) {
        if (ports.remove(p.id) == null) return
        for (t in p.held) ExtBackground.release(t.web)
        p.held.clear()
        if (p.proxy !== skipPage) discToPage(p.proxy, p.c, err)
        for (t in p.targets) if (t !== skipCtx) toExt(t, p.xid, "port.disc", JSONArray().put(p.id))
    }

    private fun closeOf(proxy: JavaScriptReplyProxy) {
        for (p in ports.values.filter { it.proxy === proxy }) close(p, null, proxy)
    }

    /** ExtApi hook (main thread): port.post / port.disconnect from an extension page. true = the port belongs to a web page, handled here. */
    fun fromExt(c: ChromeBridge.Call, disconnect: Boolean): Boolean {
        val p = ports[c.args.optString(0)] ?: return false
        if (p.xid != c.extId || p.targets.none { it === c.ctx }) return true
        if (disconnect) {
            for (t in p.targets.filter { it === c.ctx }) { p.held.remove(t); ExtBackground.release(t.web) }
            p.targets = p.targets.filter { it !== c.ctx }
            if (p.targets.isEmpty()) close(p, if (c.args.optBoolean(1, false)) NO_RECV else null, null, c.ctx)
        } else {
            val q: Any = c.args.opt(1) ?: JSONObject.NULL
            toPage(p.proxy, JSONObject().put("e", "port.msg").put("c", p.c).put("m", q))
        }
        return true
    }

    /** ExtApi.ctxGone hook: an extension page went away. */
    fun ctxGone(ctx: ChromeBridge.Ctx) {
        for (p in ports.values.toList()) {
            if (p.targets.none { it === ctx }) continue
            if (p.held.remove(ctx)) ExtBackground.release(ctx.web)
            p.targets = p.targets.filter { it !== ctx }
            if (p.targets.isEmpty() && p.ready) close(p, null, null, ctx)
        }
    }

    // ---------- commands ----------

    fun register() {
        // chrome.commands.getAll for installed extensions (manifest "commands"; the built-in keeps its own empty answer)
        ChromeBridge.onSync("ext.commands.getAll") { c -> ExtRegistry.get(c.extId)?.let { ExtManifest.commands(it) } ?: JSONArray() }
    }
}
