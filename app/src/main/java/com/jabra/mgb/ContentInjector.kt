package com.jabra.mgb

import android.content.Context
import android.util.Log
import android.webkit.WebView
import androidx.webkit.ScriptHandler
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.SecureRandom

/**
 * S5: injects the V77 extension scripts into every page, like manifest "content_scripts".
 *  - reads assets/ext/manifest.json (V77 as is) and builds one document-start script per entry
 *  - world MAIN  -> plain scripts (hook.js ...); default world -> content.js etc. run in one private
 *    function scope that receives the `chrome` shim as a parameter (the page never sees `chrome`)
 *  - all frames / run_at (start, end, idle) / match patterns (turned into origin rules) are honoured
 *  - attachExt(): for extension pages (popup, queue ...) served from EXT_ORIGIN: defines window.chrome
 */
object ContentInjector {
    const val EXT_ORIGIN = "https://appassets.androidplatform.net"
    const val EXT_BASE = "$EXT_ORIGIN/ext/"
    const val EXT_ID = "mgbextensionidmgbextensionid0"
    private const val TAG = "MGB.inject"

    lateinit var token: String
        private set

    /** S3: per-extension bridge token. V77 keeps the plain token; an installed extension gets a derived one, so it cannot speak as another. */
    fun tokenFor(id: String): String {
        if (id == EXT_ID) return token
        val d = java.security.MessageDigest.getInstance("SHA-256").digest((token + ":" + id).toByteArray())
        return d.joinToString("") { "%02x".format(it) }
    }

    private class Entry(val origins: Set<String>, val script: String, val noPriv: Boolean = false) // 0.0.432 (S7): noPriv = manifest incognito "not_allowed"

    private var inited = false
    private var warned = false
    private lateinit var shim: String
    private val entries = ArrayList<Entry>()
    private var extScript = ""
    private var ytScript = "" // 0.0.105: YouTube ad blocker (shim/yt-adblock.js)
    private var ymScript = "" // 0.0.296: Settings > Media for YouTube (shim/yt-media.js)
    private val YT_ORIGINS = setOf("https://www.youtube.com", "https://m.youtube.com", "https://music.youtube.com", "https://www.youtube-nocookie.com") // 0.0.300: + embeds
    private var v77Cfg = "" // S4: cfg of the built-in extension pages (dispatcher in extPageScript)

    // S2: content scripts of installed extensions (files/exts/<id>/), cached per extension
    private class ExtCache(val sig: String, val entries: List<Entry>)
    private val extCache = HashMap<String, ExtCache>()

    private fun read(ctx: Context, path: String): String =
        ExtUpdater.readText(path) ?: ctx.assets.open(path).bufferedReader().use { it.readText() } // S16: hot ext files first

    @Synchronized fun init(ctx: Context) {
        if (inited) return
        inited = true
        val app = ctx.applicationContext
        ExtUpdater.init(app)
        ExtRegistry.init(app) // S4: installed extensions must be known at start (content scripts, /ext/<id>/ files), not only after Settings > Extensions was opened
        ExtSafety.sweep(app) // S8: an installed manifest v2 / nativeMessaging extension (older install) is turned off
        ChromeBridge.init(app)
        val b = ByteArray(16).also { SecureRandom().nextBytes(it) }
        token = b.joinToString("") { "%02x".format(it) }
        shim = read(app, "shim/chrome-shim.js").trim().trimEnd(';')
        try { ytScript = app.assets.open("shim/yt-adblock.js").bufferedReader().use { it.readText() } } catch (ex: Exception) { Log.e(TAG, "yt-adblock.js missing", ex) }
        try { ymScript = app.assets.open("shim/yt-media.js").bufferedReader().use { it.readText() } } catch (ex: Exception) { Log.e(TAG, "yt-media.js missing", ex) }
        val manifest = JSONObject(read(app, "ext/manifest.json"))

        fun cfg(kind: String) = JSONObject()
            .put("token", token).put("kind", kind).put("base", EXT_BASE)
            .put("id", EXT_ID).put("manifest", manifest).toString()

        val cs = manifest.optJSONArray("content_scripts") ?: JSONArray()
        for (i in 0 until cs.length()) {
            try {
                val e = cs.getJSONObject(i)
                val files = e.getJSONArray("js")
                val src = StringBuilder()
                for (j in 0 until files.length()) {
                    val f = files.getString(j)
                    src.append("\n/* ").append(f).append(" */\n").append(read(app, "ext/$f")).append("\n;\n")
                }
                val origins = originsOf(e.optJSONArray("matches"))
                if (origins.isEmpty()) continue
                entries.add(
                    Entry(
                        origins,
                        wrap(
                            src.toString(), e.optString("world") == "MAIN",
                            e.optString("run_at", "document_idle"), e.optBoolean("all_frames", false), cfg("content")
                        )
                    )
                )
            } catch (ex: Exception) {
                Log.e(TAG, "content_scripts[$i] skipped", ex)
            }
        }
        v77Cfg = cfg("ext")
        extScript = "(function(){try{var c=" + shim + "(window," + cfg("ext") + ");" +
            "if(c)Object.defineProperty(window,'chrome',{value:c,configurable:true,writable:true});}catch(e){}})();"
    }

    private fun wrap(src: String, main: Boolean, runAt: String, allFrames: Boolean, cfgJson: String, pre: String = "", idle: Boolean = false): String {
        val sb = StringBuilder("(function(){try{\n")
        if (!allFrames) sb.append("if(window.top!==window)return;\n")
        // S6: never run page scripts inside extension pages that are open as tabs
        sb.append("if(location.origin==='").append(EXT_ORIGIN).append("')return;\n")
        sb.append(pre) // S2: url filter + css of an installed extension (empty for V77)
        if (main) {
            sb.append("var __run=function(){try{\n").append(src).append("\n}catch(e){}};\n")
        } else {
            sb.append("var chrome=").append(shim).append("(window,").append(cfgJson).append(");if(!chrome)return;\n")
            sb.append("var __run=function(){try{(function(chrome){\n").append(src)
                .append("\n})(chrome);}catch(e){try{chrome.__mgbLog('content: '+(e&&e.stack||e));}catch(_){}}};\n")
        }
        when (runAt) {
            "document_start" -> sb.append("__run();\n")
            "document_end" -> sb.append("if(document.readyState==='loading')document.addEventListener('DOMContentLoaded',__run,{once:true});else __run();\n")
            else -> if (idle) { // 0.0.428 (S3): Chrome idle = after load (or 1.5 s), installed extensions only, ExtTheme switch
                sb.append("var __g=function(){if(__g.d)return;__g.d=1;setTimeout(__run,0)};if(document.readyState==='complete')__g();else{window.addEventListener('load',__g,{once:true});setTimeout(__g,1500)}\n")
            } else sb.append("var __go=function(){setTimeout(__run,0)};if(document.readyState==='loading')document.addEventListener('DOMContentLoaded',__go,{once:true});else __go();\n")
        }
        return sb.append("}catch(e){}})();").toString()
    }

    /** S2: manifest match pattern -> JS regex source (full url check, the origin rule alone ignores the path). */
    fun patRegex(p: String): String? { // T7: shared with Userscripts
        if (p == "<all_urls>") return "^https?://.*$"
        val m = PATTERN.find(p) ?: return null
        val scheme = if (m.groupValues[1] == "*") "https?" else m.groupValues[1]
        val host = m.groupValues[2]
        val path = m.groupValues[3].ifEmpty { "/*" }
        fun esc(t: String) = t.replace(Regex("[.+?^\${}()|\\[\\]\\\\]")) { "\\" + it.value }
        fun glob(t: String) = t.split("*").joinToString(".*") { esc(it) }
        val h = when {
            host == "*" -> "[^/]*"
            host.startsWith("*.") -> "([^/]*\\.)?" + esc(host.substring(2))
            else -> esc(host)
        }
        return "^" + scheme + "://" + h + glob(path) + "(#.*)?$"
    }

    /** 0.0.118 (S6): match_origin_as_fallback ignores the path: same patterns with the path replaced by a wildcard. */
    private fun originOnlyRegexList(arr: JSONArray?): JSONArray {
        val out = JSONArray()
        if (arr != null) for (i in 0 until arr.length()) {
            val p = arr.optString(i)
            val m = PATTERN.find(p)
            val q = if (p == "<all_urls>" || m == null) p else m.groupValues[1] + "://" + m.groupValues[2] + "/*"
            patRegex(q)?.let { out.put(it) }
        }
        return out
    }

    private fun regexList(arr: JSONArray?): JSONArray {
        val out = JSONArray()
        if (arr != null) for (i in 0 until arr.length()) patRegex(arr.optString(i))?.let { out.put(it) }
        return out
    }

    private fun extFile(dir: File, rel: String): File? {
        if (rel.isBlank() || rel.contains("..")) return null
        val f = File(dir, rel.trimStart('/'))
        val ok = try { f.canonicalPath.startsWith(dir.canonicalPath + File.separator) } catch (e: Exception) { false }
        return if (ok && f.isFile && f.length() <= 2_000_000L) f else null
    }

    // 0.0.108: chrome.i18n messages (_locales) of an installed extension, cached per extension + manifest signature
    private val i18nCache = HashMap<String, Pair<String, JSONObject>>()

    private fun readMessages(dir: File, lang: String, into: JSONObject) {
        if (lang.isBlank() || lang.contains("..") || lang.contains('/') || lang.contains('\\')) return
        val f = extFile(dir, "_locales/$lang/messages.json") ?: return
        val j = try { JSONObject(f.readText(Charsets.UTF_8).removePrefix("\uFEFF")) } catch (_: Exception) { return }
        for (k in j.keys().asSequence().toList()) {
            val e = j.optJSONObject(k) ?: continue
            val o = JSONObject().put("m", e.optString("message", ""))
            val ph = e.optJSONObject("placeholders")
            if (ph != null) {
                val p = JSONObject()
                for (n in ph.keys().asSequence().toList()) p.put(n.lowercase(), ph.optJSONObject(n)?.optString("content", "") ?: "")
                o.put("p", p)
            }
            into.put(k.lowercase(), o)
        }
    }

    /** { key(lowercase): {m: message, p: {placeholder: content}} } : default_locale first, then en, then the phone language on top. */
    private fun messagesFor(id: String, dir: File, manifest: JSONObject): JSONObject {
        val mf = File(dir, "manifest.json")
        val sig = "${mf.lastModified()}:${mf.length()}:${java.util.Locale.getDefault()}"
        i18nCache[id]?.let { if (it.first == sig) return it.second }
        val out = JSONObject()
        try {
            if (File(dir, "_locales").isDirectory) {
                val loc = java.util.Locale.getDefault()
                val langs = LinkedHashSet<String>()
                val def = manifest.optString("default_locale", "")
                if (def.isNotEmpty()) langs.add(def)
                langs.add("en")
                langs.add(loc.language)
                langs.add(loc.language + "_" + loc.country)
                langs.add(loc.language + "-" + loc.country)
                for (l in langs) readMessages(dir, l, out)
                if (out.toString().length > 600_000) { Log.w(TAG, "$id: messages too big, i18n off"); out.keys().asSequence().toList().forEach { out.remove(it) } }
            }
        } catch (ex: Exception) { Log.e(TAG, "$id messages", ex) }
        i18nCache[id] = Pair(sig, out)
        return out
    }

    /** 0.0.108: css of an extension points to its files as chrome-extension://__MSG_@@extension_id__/x (or the real id) -> served url. */
    private fun fixExtUrls(id: String, css: String): String =
        css.replace("chrome-extension://__MSG_@@extension_id__/", EXT_BASE + id + "/").replace("chrome-extension://$id/", EXT_BASE + id + "/")

    /** 0.0.433 (S8): "__MSG_key__" -> text of the extension's _locales (anything else, or an unknown key, is returned as is). */
    fun i18nText(id: String, dir: File, manifest: JSONObject, s: String): String {
        if (!s.startsWith("__MSG_") || !s.endsWith("__") || s.length <= 8) return s
        val e = messagesFor(id, dir, manifest).optJSONObject(s.substring(6, s.length - 2).lowercase()) ?: return s
        return e.optString("m", "").ifEmpty { s }
    }

    private fun buildExt(id: String, dir: File, mf: File, extra: List<JSONObject> = emptyList()): List<Entry> {
        val manifest = JSONObject(mf.readText())
        // 0.0.115 (S3): manifest content_scripts + scripts added with chrome.scripting.registerContentScripts (same build path)
        val cs = JSONArray()
        manifest.optJSONArray("content_scripts")?.let { for (i in 0 until it.length()) cs.put(it.get(i)) }
        for (x in extra) cs.put(x)
        if (cs.length() == 0) return emptyList()
        val cfgJson = JSONObject().put("token", tokenFor(id)).put("kind", "content").put("base", EXT_BASE + id + "/")
            .put("id", id).put("manifest", manifest).put("messages", messagesFor(id, dir, manifest)).toString()
        val out = ArrayList<Entry>()
        for (i in 0 until cs.length()) {
            try {
                val e = cs.getJSONObject(i)
                val js = e.optJSONArray("js") ?: JSONArray()
                val css = e.optJSONArray("css") ?: JSONArray()
                val src = StringBuilder()
                for (j in 0 until js.length()) {
                    val f = extFile(dir, js.optString(j))
                    if (f == null) { Log.w(TAG, "$id: js missing/too big: ${js.optString(j)}"); continue }
                    src.append("\n/* ").append(js.optString(j)).append(" */\n").append(f.readText()).append("\n;\n")
                }
                val cssSrc = StringBuilder()
                for (j in 0 until css.length()) extFile(dir, css.optString(j))?.let { cssSrc.append(fixExtUrls(id, it.readText())).append("\n") }
                if (src.isEmpty() && cssSrc.isEmpty()) continue
                val matches = e.optJSONArray("matches")
                val fb = e.optBoolean("match_origin_as_fallback", false)
                val ab = e.optBoolean("match_about_blank", false) && !fb // 0.0.118 (S6): Chrome ignores match_about_blank when match_origin_as_fallback is true
                // such a frame has an about: / data: / blob: url, the origin rule of the WebView may not match it: the script checks the url itself
                val origins = if (fb || ab) setOf("*") else originsOf(matches)
                if (origins.isEmpty()) continue
                val pre = StringBuilder()
                pre.append("var __u=location.href;var __m=").append(regexList(matches)).append(";var __x=")
                    .append(regexList(e.optJSONArray("exclude_matches"))).append(";")
                if (fb || ab) {
                    // 0.0.118 (S6): frame without a real url -> use the url of the frame that created it (origin, else the parent origin)
                    pre.append("if(/^(about:|data:|blob:|filesystem:)/.test(__u)){")
                    pre.append(if (fb) "" else "if(!/^about:(blank|srcdoc)/.test(__u))return;")
                    pre.append("var __b=location.origin;if(!__b||__b==='null'){try{__b=(location.ancestorOrigins&&location.ancestorOrigins[0])||''}catch(_){__b=''}}")
                    pre.append("if(!/^https?:\\/\\//.test(__b))return;__u=__b+'/';")
                    if (fb) pre.append("__m=").append(originOnlyRegexList(matches)).append(";")
                    pre.append("}\n")
                }
                pre.append("if(!__m.some(function(r){return new RegExp(r).test(__u)})||__x.some(function(r){return new RegExp(r).test(__u)}))return;\n")
                // 0.0.432 (S7): include_globs / exclude_globs (checked after matches, like Chrome); no such key = the script text is unchanged
                val gi = ExtManifest.globs(e.optJSONArray("include_globs"))
                val gx = ExtManifest.globs(e.optJSONArray("exclude_globs"))
                if (gi.length() > 0 || gx.length() > 0) {
                    pre.append("var __gi=").append(gi).append(",__gx=").append(gx).append(",__gt=function(r){return new RegExp(r).test(__u)};")
                    pre.append("if((__gi.length&&!__gi.some(__gt))||__gx.some(__gt))return;\n")
                }
                if (cssSrc.isNotEmpty()) {
                    pre.append("try{var __s=document.createElement('style');__s.textContent=").append(JSONObject.quote(cssSrc.toString()))
                        .append(";(document.head||document.documentElement).appendChild(__s);}catch(_){}\n")
                }
                val main = e.optString("world") == "MAIN" || src.isEmpty()
                out.add(Entry(origins, wrap(src.toString(), main, e.optString("run_at", "document_idle"),
                    e.optBoolean("all_frames", false), cfgJson, pre.toString(), ExtTheme.idleOn), ExtManifest.noPrivate(manifest)))
            } catch (ex: Exception) {
                Log.e(TAG, "$id content_scripts[$i] skipped", ex)
            }
        }
        return out
    }

    /** S2: entries of every enabled extension. One broken extension never affects the others. */
    @Synchronized private fun extEntries(ctx: Context): List<Entry> {
        val out = ArrayList<Entry>()
        val live = HashSet<String>()
        val all = try { ExtRegistry.enabled() } catch (e: Exception) { Log.e(TAG, "registry", e); return out }
        ExtTheme.sync(ctx) // 0.0.428 (S3)
        for (info in all) {
            val id = info.id
            try {
                if (!ExtRegistry.isValidId(id)) continue
                val dir = File(File(ctx.filesDir, "exts"), id)
                val mf = File(dir, "manifest.json")
                if (!mf.isFile) continue
                live.add(id)
                val sig = "${info.version}:${mf.lastModified()}:${mf.length()}:${dir.lastModified()}:r${regVer[id] ?: 0}:t${ExtTheme.sig(ctx)}" // 0.0.428: + theme idle rule
                val c = extCache[id]
                if (c != null && c.sig == sig) { out.addAll(c.entries); continue }
                val built = buildExt(id, dir, mf, regOf(ctx, id).values.map { regToCs(it) })
                extCache[id] = ExtCache(sig, built)
                out.addAll(built)
            } catch (ex: Exception) {
                Log.e(TAG, "ext $id skipped", ex)
            }
        }
        extCache.keys.retainAll(live)
        return out
    }

    // ---------- 0.0.115 (S3): chrome.scripting.registerContentScripts / updateContentScripts / unregisterContentScripts / getRegisteredContentScripts ----------
    // Stored in Chrome format per extension: files/ext_reg/<id>.json (persistAcrossSessions=false stays in memory). Applied on the NEXT page load of a tab.

    private val regs = HashMap<String, LinkedHashMap<String, JSONObject>>() // xid -> script id -> script
    private val regVer = HashMap<String, Int>() // changes whenever the list of an extension changes (cache signature)

    private fun regFile(ctx: Context, id: String) = File(File(ctx.filesDir, "ext_reg"), "$id.json")

    @Synchronized private fun regOf(ctx: Context, id: String): LinkedHashMap<String, JSONObject> = regs.getOrPut(id) {
        val m = LinkedHashMap<String, JSONObject>()
        try {
            val f = regFile(ctx, id)
            if (f.isFile) {
                val a = JSONArray(f.readText())
                for (i in 0 until a.length()) {
                    val o = a.optJSONObject(i) ?: continue
                    val k = o.optString("id")
                    if (k.isNotEmpty()) m[k] = o
                }
            }
        } catch (e: Exception) { Log.e(TAG, "$id registered scripts: load failed", e) }
        m
    }

    private fun regChanged(ctx: Context, id: String) {
        regVer[id] = (regVer[id] ?: 0) + 1
        try {
            val keep = JSONArray()
            for (o in regOf(ctx, id).values) if (o.optBoolean("persistAcrossSessions", true)) keep.put(o)
            val f = regFile(ctx, id)
            if (keep.length() == 0) { f.delete(); return }
            f.parentFile?.mkdirs()
            f.writeText(keep.toString())
        } catch (e: Exception) { Log.e(TAG, "$id registered scripts: save failed", e) }
    }

    /** true if the host permission pattern `have` covers the match pattern `want` (path part ignored, like the origin rule). */
    private fun covers(have: String, want: String): Boolean {
        if (have == "<all_urls>") return true
        val h = PATTERN.find(have) ?: return false
        if (want == "<all_urls>") return h.groupValues[1] == "*" && h.groupValues[2] == "*"
        val w = PATTERN.find(want) ?: return false
        if (h.groupValues[1] != "*" && h.groupValues[1] != w.groupValues[1]) return false
        val hh = h.groupValues[2]
        val wh = w.groupValues[2]
        return when {
            hh == "*" -> true
            wh == "*" -> false
            hh.startsWith("*.") -> wh == hh.substring(2) || wh.endsWith(hh.substring(1))
            else -> hh == wh
        }
    }

    private fun strList(v: Any?, name: String): JSONArray {
        val out = JSONArray()
        if (v == null || v == JSONObject.NULL) return out
        if (v !is JSONArray) throw IllegalArgumentException("Invalid value for '$name'.")
        for (i in 0 until v.length()) {
            val x = v.opt(i)
            if (x !is String) throw IllegalArgumentException("Invalid value for '$name'.")
            out.put(x)
        }
        return out
    }

    /** One script as the extension gave it -> checked + only the known keys. Throws IllegalArgumentException with a Chrome-like text. */
    private fun regClean(ctx: Context, xid: String, o: JSONObject, hostPats: List<String>): JSONObject {
        val k = o.opt("id")
        if (k !is String || k.isEmpty()) throw IllegalArgumentException("Script must have a non-empty 'id'.")
        if (k.startsWith("_")) throw IllegalArgumentException("Script ID '$k' must not start with '_'.")
        val matches = strList(o.opt("matches"), "matches")
        if (matches.length() == 0) throw IllegalArgumentException("Script '$k' must specify 'matches'.")
        for (i in 0 until matches.length()) {
            val p = matches.getString(i)
            if (patRegex(p) == null) throw IllegalArgumentException("Invalid match pattern '$p' in script '$k'.")
            if (hostPats.none { covers(it, p) }) throw IllegalArgumentException("Cannot access contents of '$p': the extension has no host permission for it.")
        }
        val excl = strList(o.opt("excludeMatches"), "excludeMatches")
        for (i in 0 until excl.length()) if (patRegex(excl.getString(i)) == null) throw IllegalArgumentException("Invalid match pattern '" + excl.getString(i) + "' in script '$k'.")
        val js = strList(o.opt("js"), "js")
        val css = strList(o.opt("css"), "css")
        if (js.length() == 0 && css.length() == 0) throw IllegalArgumentException("Script '$k' must specify at least one 'js' or 'css' file.")
        val dir = File(File(ctx.filesDir, "exts"), xid)
        for (i in 0 until js.length()) if (extFile(dir, js.getString(i)) == null) throw IllegalArgumentException("Could not load javascript '" + js.getString(i) + "' for script '$k'.")
        for (i in 0 until css.length()) if (extFile(dir, css.getString(i)) == null) throw IllegalArgumentException("Could not load css '" + css.getString(i) + "' for script '$k'.")
        val runAt = if (o.has("runAt") && !o.isNull("runAt")) o.optString("runAt") else "document_idle"
        if (runAt != "document_start" && runAt != "document_end" && runAt != "document_idle") throw IllegalArgumentException("Invalid value for 'runAt'.")
        val world = if (o.has("world") && !o.isNull("world")) o.optString("world") else "ISOLATED"
        if (world != "ISOLATED" && world != "MAIN") throw IllegalArgumentException("Invalid value for 'world'.")
        val c = JSONObject().put("id", k).put("matches", matches).put("runAt", runAt).put("world", world)
            .put("allFrames", o.optBoolean("allFrames", false))
            .put("matchOriginAsFallback", o.optBoolean("matchOriginAsFallback", false))
            .put("persistAcrossSessions", o.optBoolean("persistAcrossSessions", true))
        if (excl.length() > 0) c.put("excludeMatches", excl)
        if (js.length() > 0) c.put("js", js)
        if (css.length() > 0) c.put("css", css)
        return c
    }

    /** Chrome format -> manifest content_scripts format (what buildExt reads). */
    private fun regToCs(o: JSONObject): JSONObject = JSONObject()
        .put("matches", o.optJSONArray("matches") ?: JSONArray())
        .put("exclude_matches", o.optJSONArray("excludeMatches") ?: JSONArray())
        .put("js", o.optJSONArray("js") ?: JSONArray())
        .put("css", o.optJSONArray("css") ?: JSONArray())
        .put("all_frames", o.optBoolean("allFrames", false))
        .put("match_origin_as_fallback", o.optBoolean("matchOriginAsFallback", false)) // 0.0.118 (S6)
        .put("run_at", o.optString("runAt", "document_idle"))
        .put("world", o.optString("world", "ISOLATED"))

    /** registerContentScripts: all or nothing. Throws IllegalArgumentException (shown as chrome.runtime.lastError / rejected promise). */
    @Synchronized fun regAdd(ctx: Context, xid: String, arr: JSONArray?, hostPats: List<String>) {
        if (arr == null) throw IllegalArgumentException("Invalid argument: scripts must be an array.")
        val m = regOf(ctx, xid)
        val seen = HashSet<String>()
        val fresh = ArrayList<JSONObject>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: throw IllegalArgumentException("Invalid script at index $i.")
            val c = regClean(ctx, xid, o, hostPats)
            val k = c.getString("id")
            if (k in m || !seen.add(k)) throw IllegalArgumentException("Duplicate script ID '$k'")
            fresh.add(c)
        }
        if (m.size + fresh.size > 500) throw IllegalArgumentException("Too many registered content scripts.")
        for (c in fresh) m[c.getString("id")] = c
        regChanged(ctx, xid)
    }

    /** updateContentScripts: every id must exist; the given keys replace the old ones. */
    @Synchronized fun regUpdate(ctx: Context, xid: String, arr: JSONArray?, hostPats: List<String>) {
        if (arr == null) throw IllegalArgumentException("Invalid argument: scripts must be an array.")
        val m = regOf(ctx, xid)
        val upd = LinkedHashMap<String, JSONObject>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: throw IllegalArgumentException("Invalid script at index $i.")
            val k = o.optString("id")
            val old = m[k] ?: throw IllegalArgumentException("Content script with ID '$k' does not exist.")
            val merged = JSONObject(old.toString())
            val keys = o.keys()
            while (keys.hasNext()) { val key = keys.next(); merged.put(key, o.get(key)) }
            upd[k] = regClean(ctx, xid, merged, hostPats)
        }
        for ((k, v) in upd) m[k] = v
        regChanged(ctx, xid)
    }

    /** unregisterContentScripts: ids null = all. An unknown id fails the whole call. */
    @Synchronized fun regRemove(ctx: Context, xid: String, ids: JSONArray?) {
        val m = regOf(ctx, xid)
        if (ids == null) m.clear()
        else {
            for (i in 0 until ids.length()) { val k = ids.optString(i); if (k !in m) throw IllegalArgumentException("Nonexistent script ID '$k'") }
            for (i in 0 until ids.length()) m.remove(ids.optString(i))
        }
        regChanged(ctx, xid)
    }

    /** getRegisteredContentScripts: ids null = all. */
    @Synchronized fun regList(ctx: Context, xid: String, ids: JSONArray?): JSONArray {
        val want = HashSet<String>()
        if (ids != null) for (i in 0 until ids.length()) want.add(ids.optString(i))
        val out = JSONArray()
        for (o in regOf(ctx, xid).values) if (ids == null || o.optString("id") in want) out.put(JSONObject(o.toString()))
        return out
    }

    /** The extension was removed: its registered scripts go with it. */
    @Synchronized fun regForget(ctx: Context, xid: String) {
        regs.remove(xid)
        regVer.remove(xid)
        try { regFile(ctx, xid).delete() } catch (_: Exception) { }
    }

    private val PATTERN = Regex("^(\\*|https?)://([^/]*)(/.*)?$")

    /** manifest match patterns -> WebView origin rules (path part is ignored). */
    private fun originsOf(arr: JSONArray?): Set<String> {
        val out = LinkedHashSet<String>()
        if (arr == null) return out
        for (i in 0 until arr.length()) {
            val p = arr.getString(i)
            if (p == "<all_urls>") return setOf("*")
            val m = PATTERN.find(p) ?: continue
            val scheme = m.groupValues[1]
            val host = m.groupValues[2]
            if (host == "*") return setOf("*")
            val schemes = if (scheme == "*") listOf("http", "https") else listOf(scheme)
            for (s in schemes) out.add("$s://$host")
        }
        return out
    }

    private fun supported(): Boolean {
        val ok = WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT) &&
            WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)
        if (!ok && !warned) { warned = true; Log.e(TAG, "WebView too old: extension scripts disabled") }
        return ok
    }

    /** S4: true if url is allowed by manifest host patterns (<all_urls>, or a scheme + host + path pattern). Same matcher as content scripts. */
    fun urlAllowed(patterns: List<String>, url: String): Boolean {
        for (p in patterns) {
            if (p == "<all_urls>") return url.startsWith("http://") || url.startsWith("https://")
            val rx = patRegex(p) ?: continue
            try { if (Regex(rx).containsMatchIn(url)) return true } catch (_: Exception) { }
        }
        return false
    }

    /**
     * S4: window.chrome for extension origin pages. One script, chosen at run time by the page path:
     * /ext/<id>/... of an enabled installed extension -> that extension's cfg (own token, id, base, manifest);
     * /ext/<id>/... of an unknown or disabled one -> no chrome at all; any other /ext/... page -> V77 cfg (as before).
     * Built when a WebView is attached (a toggle / install applies to windows and tabs opened afterwards).
     */
    private fun extPageScript(ctx: Context): String {
        val cfgs = JSONObject()
        try {
            for (info in ExtRegistry.enabled()) {
                try {
                    if (!ExtRegistry.isValidId(info.id)) continue
                    val mf = File(info.dir, "manifest.json")
                    if (!mf.isFile || mf.length() > 2_000_000L) continue
                    val man = JSONObject(mf.readText())
                    cfgs.put(info.id, JSONObject().put("token", tokenFor(info.id)).put("kind", "ext").put("gaps", true) // 0.0.429 (S4): installed extensions get the API safety net
                        .put("base", EXT_BASE + info.id + "/").put("id", info.id).put("manifest", man)
                        .put("messages", messagesFor(info.id, info.dir, man))) // 0.0.108: chrome.i18n
                } catch (ex: Exception) { Log.e(TAG, "ext page cfg ${info.id} skipped", ex) }
            }
        } catch (ex: Exception) { Log.e(TAG, "registry", ex) }
        if (cfgs.length() == 0 && ExtRegistry.all().isEmpty()) return extScript
        return "(function(){try{var m=/^\\/ext\\/([a-p]{32})\\//.exec(location.pathname);var C=" + cfgs.toString() + ";var f;" +
            "if(m){f=C[m[1]];if(!f)return;}else{f=" + v77Cfg + ";}" +
            "var c=" + shim + "(window,f);" +
            "if(c)Object.defineProperty(window,'chrome',{value:c,configurable:true,writable:true});}catch(e){}})();"
    }

    /**
     * S16: scripting.executeScript(files) of an installed extension: "var chrome=...;" so the injected file can use chrome.*
     * (same cfg as its content scripts). null = no chrome (unknown / disabled / bad id).
     */
    fun injectPrelude(id: String): String? {
        return try {
            if (!ExtRegistry.isValidId(id)) return null
            val info = ExtRegistry.get(id) ?: return null
            val mf = File(info.dir, "manifest.json")
            if (!mf.isFile || mf.length() > 2_000_000L) return null
            val man = JSONObject(mf.readText())
            val cfg = JSONObject().put("token", tokenFor(id)).put("kind", "content").put("base", EXT_BASE + id + "/")
                .put("id", id).put("manifest", man).put("messages", messagesFor(id, info.dir, man)).toString()
            "var chrome=" + shim + "(window," + cfg + ");\n"
        } catch (e: Exception) { Log.e(TAG, "injectPrelude $id", e); null }
    }

    // 0.0.108: script handles of installed extensions per tab WebView, so install / enable / disable / remove / update
    // reach tabs that are already open (before, only tabs created afterwards saw a change)
    private val extHandles = java.util.WeakHashMap<WebView, ArrayList<ScriptHandler>>()
    private val privWebs = java.util.WeakHashMap<WebView, Boolean>() // 0.0.432 (S7): private tab WebViews (incognito "not_allowed" skips them)

    private fun addScript(web: WebView, script: String, origins: Set<String>, keep: ArrayList<ScriptHandler>?) {
        val h = try {
            WebViewCompat.addDocumentStartJavaScript(web, script, origins)
        } catch (ex: Exception) {
            // an origin rule the WebView refuses (odd host pattern): the script has its own full url check, so "*" is safe
            if (origins.size == 1 && origins.first() == "*") throw ex
            WebViewCompat.addDocumentStartJavaScript(web, script, setOf("*"))
        }
        keep?.add(h)
    }

    /**
     * 0.0.349: entries for the scripts the built-in engine registered (BuiltinReg: only siteNoMse.js, world MAIN).
     * The script gets its own full url check: if the WebView refuses an origin rule, addScript falls back to "*",
     * and this script (it removes MediaSource) must never run on a site that is not in the list.
     * A bad entry is skipped, the others and every old feature are not affected.
     */
    private fun builtinRegEntries(ctx: Context): List<Entry> {
        val out = ArrayList<Entry>()
        val app = ctx.applicationContext
        for (o in BuiltinReg.all(app)) {
            try {
                val js = o.optJSONArray("js") ?: continue
                val src = StringBuilder()
                for (j in 0 until js.length()) {
                    val f = js.getString(j)
                    src.append("\n/* ").append(f).append(" */\n").append(read(app, "ext/$f")).append("\n;\n")
                }
                if (src.isEmpty()) continue
                val matches = o.optJSONArray("matches")
                val origins = originsOf(matches)
                if (origins.isEmpty()) continue
                val pre = StringBuilder()
                pre.append("var __u=location.href;var __m=").append(regexList(matches)).append(";")
                pre.append("if(!__m.some(function(r){return new RegExp(r).test(__u)}))return;\n")
                out.add(Entry(origins, wrap(src.toString(), true, o.optString("runAt", "document_start"),
                    o.optBoolean("allFrames", false), "{}", pre.toString())))
            } catch (ex: Exception) {
                Log.e(TAG, "builtin reg entry skipped", ex)
            }
        }
        return out
    }

    private fun addExtScripts(ctx: Context, web: WebView, priv: Boolean = false) {
        val keep = ArrayList<ScriptHandler>()
        val list = extEntries(ctx).filter { !(priv && it.noPriv) } // 0.0.432 (S7): extensions with incognito not_allowed do not run in private tabs
        // 0.0.428 (S3): theme hint first (page world, only on origins of installed extensions' content scripts), so it runs before their scripts
        try {
            val oo = LinkedHashSet<String>()
            for (e in list) oo.addAll(e.origins)
            ExtTheme.script(ctx, oo)?.let { addScript(web, it, oo, keep) }
            // 0.0.434 (S9): CSS compat for an old WebView (null on a modern WebView = nothing added), before the extension scripts
            if (oo.isNotEmpty()) ExtCssCompat.script(ctx)?.let { addScript(web, it, oo, keep) }
        } catch (ex: Exception) { Log.e(TAG, "theme hint failed", ex) }
        // S2: installed extensions (a failing entry is skipped, others still run)
        for (e in list) {
            try { addScript(web, e.script, e.origins, keep) } catch (ex: Exception) { Log.e(TAG, "ext entry failed", ex) }
        }
        // 0.0.349: scripts the built-in grabber engine registered itself (siteNoMse.js for the "MediaSource off" sites); empty list = nothing happens
        try {
            for (e in builtinRegEntries(ctx)) {
                try { addScript(web, e.script, e.origins, keep) } catch (ex: Exception) { Log.e(TAG, "builtin reg entry failed", ex) }
            }
        } catch (ex: Exception) { Log.e(TAG, "builtin reg failed", ex) }
        // S6: extension pages opened as tabs (tabs.create) get window.chrome too
        try { addScript(web, extPageScript(ctx), setOf(EXT_ORIGIN), keep) } catch (ex: Exception) { Log.e(TAG, "ext page script failed", ex) }
        // 0.0.427 (S2): viewport for pages of installed extensions opened as tabs (null = no installed extension)
        try { ExtFit.script(ctx)?.let { addScript(web, it, setOf(EXT_ORIGIN), keep) } } catch (ex: Exception) { Log.e(TAG, "ext fit script failed", ex) }
        // 0.0.434 (S9): CSS compat for extension pages opened as tabs (only when installed extensions exist and the WebView is old / switch On)
        try { if (list.isNotEmpty()) ExtCssCompat.script(ctx)?.let { addScript(web, it, setOf(EXT_ORIGIN), keep) } } catch (ex: Exception) { Log.e(TAG, "ext css compat failed", ex) }
        // 0.0.433 (S8): externally_connectable (a listed web page may message the extension); never in private tabs
        try { if (!priv) ExtWeb.attach(web, keep) } catch (ex: Exception) { Log.e(TAG, "ext web failed", ex) }
        synchronized(extHandles) { extHandles[web] = keep }
    }

    /** 0.0.108: call (UI thread) after an extension was added / removed / switched / updated: open tabs get the new scripts on their next page load. */
    fun refresh(ctx: Context) {
        if (!inited || !supported()) return
        val webs = synchronized(extHandles) { extHandles.keys.toList() }
        for (w in webs) {
            try {
                val old = synchronized(extHandles) { extHandles.remove(w) } ?: continue
                for (h in old) try { h.remove() } catch (_: Exception) { }
                addExtScripts(ctx, w, synchronized(extHandles) { privWebs[w] == true })
            } catch (ex: Exception) { Log.e(TAG, "refresh failed", ex) }
        }
    }

    /** Browser tab WebView: call before the first loadUrl. */
    fun attach(ctx: Context, web: WebView, priv: Boolean = false) {
        init(ctx)
        if (!supported()) return
        if (priv) synchronized(extHandles) { privWebs[web] = true } // 0.0.432 (S7)
        try {
            ChromeBridge.install(web, setOf("*"))
            if (ytScript.isNotEmpty()) try { WebViewCompat.addDocumentStartJavaScript(web, ytScript, YT_ORIGINS) } catch (ex: Exception) { Log.e(TAG, "yt script failed", ex) }
            if (ymScript.isNotEmpty()) try { WebViewCompat.addDocumentStartJavaScript(web, ymScript, YT_ORIGINS) } catch (ex: Exception) { Log.e(TAG, "yt media script failed", ex) } // 0.0.296
            for (e in entries) WebViewCompat.addDocumentStartJavaScript(web, e.script, e.origins)
            addExtScripts(ctx, web, priv)
        } catch (e: Exception) {
            Log.e(TAG, "attach failed", e)
        }
    }

    /** Keeps only chrome.runtime (S9): what a Chrome offscreen document really has. V77 offscreen-shim.js adds the rest. */
    private const val OFFSCREEN_STRIP =
        "\n(function(){try{var c=window.chrome;if(!c)return;Object.keys(c).forEach(function(k){" +
            "if(k!=='runtime'&&k!=='__mgbLog'){try{delete c[k];}catch(e){}}});}catch(e){}})();"

    /** Extension page / engine WebView (S6, S10): trusted origin only, window.chrome is defined. offscreen = S9 document. */
    fun attachExt(ctx: Context, web: WebView, offscreen: Boolean = false) {
        init(ctx)
        if (!supported()) return
        try {
            ChromeBridge.install(web, setOf(EXT_ORIGIN))
            WebViewCompat.addDocumentStartJavaScript(web, if (offscreen) extScript + OFFSCREEN_STRIP else extPageScript(ctx), setOf(EXT_ORIGIN))
        } catch (e: Exception) {
            Log.e(TAG, "attachExt failed", e)
        }
    }
}
