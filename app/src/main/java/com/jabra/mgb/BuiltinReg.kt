package com.jabra.mgb

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 0.0.349: chrome.scripting.registerContentScripts / updateContentScripts / unregisterContentScripts /
 * getRegisteredContentScripts for the BUILT-IN grabber engine (ContentInjector.EXT_ID).
 *
 * Why: the engine (assets/ext/background.js syncNoMse) registers siteNoMse.js for the sites in
 * Settings > "MediaSource off". Installed extensions have these calls (ExtApi "ext.scripting.*"), the built-in
 * engine had none, so the call ended in "Not implemented: scripting.registerContentScripts" and the list did nothing.
 *
 * Safe by design:
 *  - ONLY the files in ALLOWED can be registered (the engine's own shipped script), only world MAIN. Nothing else can be injected.
 *  - Installed extensions are not touched: their "ext.scripting.*" handlers win in ChromeBridge, these plain names
 *    answer only the built-in engine (extId == EXT_ID); everyone else still gets "Not implemented" as before.
 *  - Every failure is an exception text for the engine (it only logs it); the page and the old features never see it.
 *  - Empty list (the default) = nothing is injected anywhere.
 * Injection itself is ContentInjector.builtinRegEntries (applies on the NEXT page load of a tab).
 */
object BuiltinReg {
    private const val TAG = "MGB.builtinReg"
    private val ALLOWED = setOf("siteNoMse.js")

    private val reg = LinkedHashMap<String, JSONObject>() // script id -> script (Chrome format)
    private var loaded = false

    private fun file(ctx: Context) = File(File(ctx.filesDir, "ext_reg"), "builtin.json")

    @Synchronized private fun load(ctx: Context) {
        if (loaded) return
        loaded = true
        try {
            val f = file(ctx)
            if (f.isFile) {
                val a = JSONArray(f.readText())
                for (i in 0 until a.length()) {
                    val o = a.optJSONObject(i) ?: continue
                    val k = o.optString("id")
                    if (k.isNotEmpty()) reg[k] = o
                }
            }
        } catch (e: Exception) { Log.e(TAG, "load failed", e) }
    }

    private fun save(ctx: Context) {
        try {
            val keep = JSONArray()
            for (o in reg.values) if (o.optBoolean("persistAcrossSessions", true)) keep.put(o)
            val f = file(ctx)
            if (keep.length() == 0) { f.delete(); return }
            f.parentFile?.mkdirs()
            f.writeText(keep.toString())
        } catch (e: Exception) { Log.e(TAG, "save failed", e) }
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

    /** One script as the engine gave it -> checked + only the known keys. Throws IllegalArgumentException with a Chrome-like text. */
    private fun clean(o: JSONObject): JSONObject {
        val k = o.opt("id")
        if (k !is String || k.isEmpty()) throw IllegalArgumentException("Script must have a non-empty 'id'.")
        if (k.startsWith("_")) throw IllegalArgumentException("Script ID '$k' must not start with '_'.")
        val matches = strList(o.opt("matches"), "matches")
        if (matches.length() == 0) throw IllegalArgumentException("Script '$k' must specify 'matches'.")
        for (i in 0 until matches.length()) {
            val p = matches.getString(i)
            if (ContentInjector.patRegex(p) == null) throw IllegalArgumentException("Invalid match pattern '$p' in script '$k'.")
        }
        val js = strList(o.opt("js"), "js")
        if (js.length() == 0) throw IllegalArgumentException("Script '$k' must specify at least one 'js' file.")
        for (i in 0 until js.length()) {
            if (js.getString(i) !in ALLOWED) throw IllegalArgumentException("Could not load javascript '" + js.getString(i) + "' for script '$k'.")
        }
        val runAt = if (o.has("runAt") && !o.isNull("runAt")) o.optString("runAt") else "document_idle"
        if (runAt != "document_start" && runAt != "document_end" && runAt != "document_idle") throw IllegalArgumentException("Invalid value for 'runAt'.")
        val world = if (o.has("world") && !o.isNull("world")) o.optString("world") else "ISOLATED"
        if (world != "MAIN") throw IllegalArgumentException("Invalid value for 'world'.") // built-in engine: MAIN only (siteNoMse.js)
        return JSONObject().put("id", k).put("matches", matches).put("js", js).put("runAt", runAt).put("world", world)
            .put("allFrames", o.optBoolean("allFrames", false))
            .put("persistAcrossSessions", o.optBoolean("persistAcrossSessions", true))
    }

    @Synchronized fun add(ctx: Context, arr: JSONArray?) {
        if (arr == null) throw IllegalArgumentException("Invalid argument: scripts must be an array.")
        load(ctx)
        val seen = HashSet<String>()
        val fresh = ArrayList<JSONObject>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: throw IllegalArgumentException("Invalid script at index $i.")
            val c = clean(o)
            val k = c.getString("id")
            if (k in reg || !seen.add(k)) throw IllegalArgumentException("Duplicate script ID '$k'")
            fresh.add(c)
        }
        if (reg.size + fresh.size > 20) throw IllegalArgumentException("Too many registered content scripts.")
        for (c in fresh) reg[c.getString("id")] = c
        save(ctx)
    }

    @Synchronized fun update(ctx: Context, arr: JSONArray?) {
        if (arr == null) throw IllegalArgumentException("Invalid argument: scripts must be an array.")
        load(ctx)
        val upd = LinkedHashMap<String, JSONObject>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: throw IllegalArgumentException("Invalid script at index $i.")
            val k = o.optString("id")
            val old = reg[k] ?: throw IllegalArgumentException("Content script with ID '$k' does not exist.")
            val merged = JSONObject(old.toString())
            val keys = o.keys()
            while (keys.hasNext()) { val key = keys.next(); merged.put(key, o.get(key)) }
            upd[k] = clean(merged)
        }
        for ((k, v) in upd) reg[k] = v
        save(ctx)
    }

    /** ids null = all. An unknown id fails the whole call (same as Chrome). */
    @Synchronized fun remove(ctx: Context, ids: JSONArray?) {
        load(ctx)
        if (ids == null) reg.clear()
        else {
            for (i in 0 until ids.length()) { val k = ids.optString(i); if (k !in reg) throw IllegalArgumentException("Nonexistent script ID '$k'") }
            for (i in 0 until ids.length()) reg.remove(ids.optString(i))
        }
        save(ctx)
    }

    @Synchronized fun list(ctx: Context, ids: JSONArray?): JSONArray {
        load(ctx)
        val want = HashSet<String>()
        if (ids != null) for (i in 0 until ids.length()) want.add(ids.optString(i))
        val out = JSONArray()
        for (o in reg.values) if (ids == null || o.optString("id") in want) out.put(JSONObject(o.toString()))
        return out
    }

    /** Copy for ContentInjector (injection side). */
    @Synchronized fun all(ctx: Context): List<JSONObject> {
        load(ctx)
        return reg.values.map { JSONObject(it.toString()) }
    }

    /** Called once from ExtApi.registerHandlers. [ctx] gives the app context, [changed] makes open tabs pick the change up on their next page load. */
    fun install(ctx: () -> Context?, changed: () -> Unit) {
        fun app(): Context = ctx() ?: throw IllegalStateException("Not ready")
        fun builtin(c: ChromeBridge.Call) {
            if (c.extId != ContentInjector.EXT_ID) throw IllegalStateException("Not implemented: " + c.api)
        }
        ChromeBridge.onSync("scripting.registerContentScripts") { c ->
            builtin(c); add(app(), c.args.optJSONArray(0)); changed(); null
        }
        ChromeBridge.onSync("scripting.updateContentScripts") { c ->
            builtin(c); update(app(), c.args.optJSONArray(0)); changed(); null
        }
        ChromeBridge.onSync("scripting.unregisterContentScripts") { c ->
            builtin(c); remove(app(), c.args.optJSONObject(0)?.optJSONArray("ids")); changed(); null
        }
        ChromeBridge.onSync("scripting.getRegisteredContentScripts") { c ->
            builtin(c); list(app(), c.args.optJSONObject(0)?.optJSONArray("ids"))
        }
    }
}
