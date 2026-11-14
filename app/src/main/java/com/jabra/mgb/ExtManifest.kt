package com.jabra.mgb

import android.app.Activity
import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * 0.0.432 (S7) + 0.0.433 (S8): small manifest helpers for INSTALLED extensions (the built-in Media Grabber does not use them).
 *  - globRx / globs: content_scripts include_globs / exclude_globs (applied inside the injected wrapper, the WebView origin rule cannot do globs)
 *  - noPrivate: manifest "incognito": "not_allowed" -> no content scripts / page bridge in private tabs
 *  - openOptions: options_ui.open_in_tab true = tab, else the extension window (as before)
 *  - commands: manifest "commands" (shortcut text + description), run by a tap
 */
object ExtManifest {
    private const val TAG = "MGB.manifest"

    /** Chrome glob (* = any text, ? = one character) -> JS regex source for the whole url. null = unusable glob (ignored). */
    fun globRx(g: String): String? {
        if (g.isEmpty() || g.length > 1000) return null
        val sb = StringBuilder("^")
        for (ch in g) {
            when (ch) {
                '*' -> sb.append(".*")
                '?' -> sb.append('.')
                else -> {
                    if ("\\^$.|+()[]{}/".indexOf(ch) >= 0) sb.append('\\')
                    sb.append(ch)
                }
            }
        }
        return sb.append('$').toString()
    }

    /** Valid globs of a manifest array as JS regex sources (max 50). Anything that is not a string is skipped with a log line. */
    fun globs(arr: JSONArray?): JSONArray {
        val out = JSONArray()
        if (arr == null) return out
        for (i in 0 until arr.length()) {
            val g = arr.opt(i)
            val rx = if (g is String) globRx(g) else null
            if (rx == null) { android.util.Log.w(TAG, "glob ignored: $g"); continue }
            if (out.length() >= 50) break
            out.put(rx)
        }
        return out
    }

    /** manifest "incognito": "not_allowed" = the extension does not run in private tabs. spanning (default) / split keep the old behaviour. */
    fun noPrivate(manifest: JSONObject): Boolean = manifest.optString("incognito", "") == "not_allowed"

    // ---------- options page ----------

    /** Opens the options page like Chrome: options_ui.open_in_tab = true -> a tab; otherwise the extension window. Main thread. */
    fun openOptions(host: Context, info: ExtRegistry.Info): Boolean {
        val rel = info.options ?: return false
        val url = ContentInjector.EXT_BASE + info.id + "/" + rel.trimStart('/')
        if (info.optionsInTab) {
            val tm = TabsApi.manager()
            if (tm != null) {
                try { if (tm.newTab(url, 0, true) != null) return true } catch (e: Exception) { android.util.Log.e(TAG, "options tab", e) }
            }
        }
        return ExtScreen.openExtPage(host, info.id, rel)
    }

    // ---------- commands ----------

    private fun cmdObj(name: String, o: JSONObject?): JSONObject {
        val sk = o?.opt("suggested_key")
        val key = when (sk) {
            is String -> sk
            is JSONObject -> sk.optString("default", "").ifEmpty { sk.optString("windows", "") }
            else -> ""
        }
        var desc = o?.optString("description", "") ?: ""
        if (name == "_execute_action" || name == "_execute_browser_action" || name == "_execute_page_action") {
            if (desc.isEmpty()) desc = "Open the extension"
        }
        return JSONObject().put("name", name).put("description", desc).put("shortcut", key)
    }

    /** [{name, description, shortcut}] from the manifest (description may be a __MSG_ text: shown as is when it cannot be resolved). */
    fun commands(info: ExtRegistry.Info): JSONArray {
        val out = JSONArray()
        try {
            val mf = java.io.File(info.dir, "manifest.json")
            if (!mf.isFile || mf.length() > 2_000_000L) return out
            val m = JSONObject(mf.readText().removePrefix("\uFEFF"))
            val c = m.optJSONObject("commands") ?: return out
            val names = c.keys().asSequence().toList()
            for (n in names) {
                if (out.length() >= 50) break
                val o = cmdObj(n, c.optJSONObject(n))
                val d = o.optString("description", "")
                if (d.startsWith("__MSG_") && d.endsWith("__")) o.put("description", ContentInjector.i18nText(info.id, info.dir, m, d))
                out.put(o)
            }
        } catch (e: Exception) { android.util.Log.e(TAG, "commands ${info.id}", e) }
        return out
    }

    /** A command was tapped (details screen): _execute_action = open the popup (or fire action.onClicked), others = commands.onCommand. Main thread. */
    fun runCommand(act: Activity, info: ExtRegistry.Info, name: String) {
        if (!info.enabled) { Util.toast(act, info.name + " is turned off"); return }
        val tab = TabsApi.activeTab()?.takeIf { !it.priv }
        if (name == "_execute_action" || name == "_execute_browser_action" || name == "_execute_page_action") {
            val v = ExtApi.actionView(info.id, tab?.id ?: -1)
            val page = v.popup
            if (page != null) { if (!ExtScreen.openExtPage(act, info.id, page)) Util.toast(act, "Could not open " + info.name); return }
            ExtApi.actionClickedWake(info.id, tab) { sent -> if (!sent) Util.toast(act, info.name + " has no popup") }
            return
        }
        val args = JSONArray().put(name).put(if (tab != null) ExtApi.tabFor(info.id, TabsApi.tabJson(tab)) else JSONObject.NULL)
        ExtApi.emitExt(info.id, "commands.onCommand", args, true)
        Util.toast(act, "Command sent: $name")
    }
}
