package com.jabra.mgb

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * 0.0.438: "ALLOW IN PRIVATE" per installed extension (like Chrome / Kiwi: OFF by default).
 * Pref "ext_priv" in mgb_ui = JSON { "<extension id>": 1 }  (absent = not allowed).
 *  - Not allowed: no content scripts in private tabs (ContentInjector) AND the extension never sees a private tab
 *    through chrome.tabs (get / query / events / sendMessage / connect / update / remove / reload / scripting).
 *  - Allowed: the old behaviour (scripts run, tabs visible, tab.incognito = true).
 *  - The manifest "incognito": "not_allowed" always wins (switch shown as blocked by the extension).
 *  - The built-in Media Grabber (ContentInjector.EXT_ID) is never touched.
 * Takes effect on the next page load of a private tab (ContentInjector.refresh re-adds the scripts at once).
 */
object ExtPriv {
    const val KEY = "ext_priv"
    @Volatile private var app: Context? = null
    @Volatile private var cache: JSONObject? = null

    fun bind(c: Context) { if (app == null) app = c.applicationContext }

    private fun sp(c: Context) = c.applicationContext.getSharedPreferences("mgb_ui", Context.MODE_PRIVATE)

    private fun load(c: Context): JSONObject {
        cache?.let { return it }
        val o = try { JSONObject(sp(c).getString(KEY, "{}") ?: "{}") } catch (_: Throwable) { JSONObject() }
        cache = o
        return o
    }

    /** The user switch only (ignores the manifest). */
    fun userAllows(c: Context, id: String): Boolean = load(c).optInt(id, 0) == 1

    /** manifest incognito not_allowed. */
    fun manifestBlocks(c: Context, id: String): Boolean = try {
        ExtManifest.noPrivate(JSONObject(File(File(File(c.filesDir, "exts"), id), "manifest.json").readText()))
    } catch (_: Throwable) { false }

    /** May this extension run in / see private tabs? */
    fun allowed(c: Context, id: String): Boolean =
        id == ContentInjector.EXT_ID || (userAllows(c, id) && !manifestBlocks(c, id))

    /** Used by the tabs API: true = the extension may see this tab. */
    fun sees(xid: String, t: TabManager.Tab): Boolean {
        if (!t.priv || xid == ContentInjector.EXT_ID) return true
        val c = app ?: return false
        return allowed(c, xid)
    }

    fun set(c: Context, id: String, on: Boolean) {
        if (id.isEmpty()) return
        val o = JSONObject(load(c).toString())
        if (on) o.put(id, 1) else o.remove(id)
        cache = o
        sp(c).edit().apply { if (o.length() == 0) remove(KEY) else putString(KEY, o.toString()) }.apply()
    }

    fun forget(c: Context, id: String) = set(c, id, false)

    fun changedCount(c: Context): Int = load(c).length()

    /** Settings reset wrote the pref directly: drop the memory copy. */
    fun reload() { cache = null }
}
