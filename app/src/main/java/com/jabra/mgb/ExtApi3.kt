package com.jabra.mgb

import android.app.Activity
import android.net.Uri
import android.os.Handler
import android.os.Looper
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.json.JSONArray
import org.json.JSONObject
import java.text.DateFormat
import java.util.Date

/**
 * 0.0.431 (S6): second group of chrome.* for INSTALLED extensions (handlers are "ext.<api>": the built-in Media Grabber is untouched).
 *  - webNavigation (main frame only): onBeforeNavigate, onCommitted, onDOMContentLoaded, onCompleted, onErrorOccurred, onHistoryStateUpdated,
 *    onReferenceFragmentUpdated + getFrame / getAllFrames. Fired by TabManager hooks (started / finished / history / failed). Needs the
 *    "webNavigation" permission (events only go to extensions that have it, and wake a stopped worker through ExtApi.emitExt like other events).
 *    Private tabs, Home, extension pages and non-http pages send nothing. URL filters of addListener are applied in the shim.
 *  - runtime.reload = restart of the extension's worker.
 *  - search.query = default search engine in the current / a new tab (permission "search").
 *  - browsingData.* (permission "browsingData") mapped to ClearData behind a confirm dialog that always asks the person.
 */
object ExtApi3 {
    private val main = Handler(Looper.getMainLooper())
    private val last = HashMap<Int, String>() // tabId -> last address that was reported
    private val busy = HashSet<Int>() // tabs between page start and page finish
    private var asking = false

    private fun ui(b: () -> Unit) { if (Looper.myLooper() == Looper.getMainLooper()) b() else main.post { b() } }

    private fun eligible(t: TabManager.Tab, url: String?): Boolean =
        !t.priv && !t.isExt && !t.home && url != null && (url.startsWith("http://") || url.startsWith("https://"))

    private fun detail(t: TabManager.Tab, url: String): JSONObject = JSONObject()
        .put("tabId", t.id).put("url", url).put("frameId", 0).put("parentFrameId", -1).put("processId", -1)
        .put("timeStamp", System.currentTimeMillis().toDouble()).put("documentLifecycle", "active").put("frameType", "outermost_frame")

    private fun fire(event: String, d: JSONObject) {
        val ev = "webNavigation.$event"
        for (info in ExtRegistry.enabled()) {
            if ("webNavigation" !in info.permissions) continue // no permission: no event, no wake
            ExtApi.emitExt(info.id, ev, JSONArray().put(JSONObject(d.toString())), ExtApi.remembers(info.id, ev))
        }
    }

    private fun noFragment(u: String) = u.substringBefore('#')

    // ---------- hooks (TabManager) ----------

    fun started(t: TabManager.Tab, url: String?) {
        try {
            if (!eligible(t, url)) return
            busy.add(t.id); last[t.id] = url!!
            fire("onBeforeNavigate", detail(t, url))
            fire("onCommitted", detail(t, url).put("transitionType", "link").put("transitionQualifiers", JSONArray()))
        } catch (_: Exception) { }
    }

    fun finished(t: TabManager.Tab, url: String?) {
        try {
            busy.remove(t.id)
            if (!eligible(t, url)) return
            last[t.id] = url!!
            fire("onDOMContentLoaded", detail(t, url))
            fire("onCompleted", detail(t, url))
        } catch (_: Exception) { }
    }

    /** In-page address change (pushState / fragment). Normal page loads are skipped (busy) and reloads too. */
    fun history(t: TabManager.Tab, url: String?, isReload: Boolean) {
        try {
            if (isReload || busy.contains(t.id) || !eligible(t, url)) return
            val prev = last[t.id]
            if (prev == url) return
            last[t.id] = url!!
            val frag = prev != null && noFragment(prev) == noFragment(url) && prev.substringAfter('#', "") != url.substringAfter('#', "")
            fire(if (frag) "onReferenceFragmentUpdated" else "onHistoryStateUpdated", detail(t, url).put("transitionType", "link").put("transitionQualifiers", JSONArray()))
        } catch (_: Exception) { }
    }

    fun failed(t: TabManager.Tab, url: String?, error: String) {
        try {
            busy.remove(t.id)
            if (!eligible(t, url)) return
            fire("onErrorOccurred", detail(t, url!!).put("error", error.take(100)))
        } catch (_: Exception) { }
    }

    // ---------- handlers ----------

    private fun reg(api: String, h: (ChromeBridge.Call, TabManager) -> Unit) {
        ChromeBridge.on("ext.$api") { c ->
            ui {
                try {
                    val m = TabsApi.manager()
                    if (m == null) c.fail("No browser window") else h(c, m)
                } catch (e: Exception) { Diag.w("engine", "api " + c.api + " threw", e.message ?: e.toString()); c.fail(e.message ?: e.toString()) }
            }
        }
    }

    private fun frame(t: TabManager.Tab, withId: Boolean): JSONObject {
        val o = JSONObject().put("errorOccurred", false).put("url", t.url).put("parentFrameId", -1)
            .put("documentLifecycle", "active").put("frameType", "outermost_frame")
        if (withId) o.put("frameId", 0).put("processId", -1)
        return o
    }

    fun register() {
        reg("webNavigation.getFrame") { c, m ->
            val d = c.args.optJSONObject(0) ?: JSONObject()
            val t = m.byId(d.optInt("tabId", -1))?.takeIf { it.id > 0 && eligible(it, it.url) }
            c.ok(if (t != null && d.optInt("frameId", 0) == 0) frame(t, false) else null)
        }
        reg("webNavigation.getAllFrames") { c, m ->
            val d = c.args.optJSONObject(0) ?: JSONObject()
            val t = m.byId(d.optInt("tabId", -1))?.takeIf { eligible(it, it.url) }
            c.ok(if (t == null) null else JSONArray().put(frame(t, true)))
        }

        ChromeBridge.on("ext.runtime.reload") { c ->
            val id = c.extId
            c.ok(null)
            ui {
                ExtBackground.stop(id, "reload")
                if (ExtBackground.has(id)) main.postDelayed({ ExtBackground.ensure(id) { } }, 400)
            }
        }

        reg("search.query") { c, m ->
            val q = c.args.optJSONObject(0) ?: JSONObject()
            val text = q.optString("text", "").trim()
            if (text.isEmpty() || text.length > 2000) { c.fail("Invalid value for argument 1. Property 'text': Value must be a non-empty string."); return@reg }
            val url = Util.searchBase + Uri.encode(text)
            val disp = q.optString("disposition", "CURRENT_TAB")
            val want = if (q.has("tabId")) m.byId(q.optInt("tabId", -1)) else m.active
            val cur = want?.takeIf { disp == "CURRENT_TAB" && !it.priv && !it.isExt && !it.home && !it.suspended }
            if (q.has("tabId") && want == null) { c.fail("No tab with id: ${q.opt("tabId")}."); return@reg }
            if (cur != null) { cur.pending = null; cur.url = url; cur.web.loadUrl(url) }
            else if (m.newTab(url, 0, true) == null) { c.fail("Too many tabs"); return@reg }
            ExtScreen.closeAll() // the page must not stay hidden behind an extension window
            c.ok(null)
        }

        for (name in listOf("remove", "removeCache", "removeCookies", "removeDownloads", "removeFileSystems", "removeFormData", "removeHistory",
            "removeIndexedDB", "removeLocalStorage", "removePasswords", "removeServiceWorkers", "removeWebSQL", "removeAppcache")) {
            reg("browsingData.$name") { c, m -> browsing(c, m, name) }
        }
        reg("browsingData.settings") { c, _ ->
            val all = JSONObject().put("cache", true).put("cookies", true).put("downloads", true).put("history", true).put("indexedDB", true)
                .put("localStorage", true).put("serviceWorkers", true).put("cacheStorage", true).put("fileSystems", true).put("webSQL", true)
            c.ok(JSONObject().put("options", JSONObject().put("since", 0.0)).put("dataToRemove", all).put("dataRemovalPermitted", JSONObject(all.toString())))
        }
    }

    // ---------- browsingData ----------

    private val KEY_OF = mapOf(
        "removeCache" to "cache", "removeCookies" to "cookies", "removeDownloads" to "downloads", "removeFileSystems" to "fileSystems",
        "removeFormData" to "formData", "removeHistory" to "history", "removeIndexedDB" to "indexedDB", "removeLocalStorage" to "localStorage",
        "removePasswords" to "passwords", "removeServiceWorkers" to "serviceWorkers", "removeWebSQL" to "webSQL", "removeAppcache" to "appcache"
    )

    private fun browsing(c: ChromeBridge.Call, m: TabManager, name: String) {
        val opt = c.args.optJSONObject(0) ?: JSONObject()
        val data = if (name == "remove") (c.args.optJSONObject(1) ?: JSONObject()) else JSONObject().put(KEY_OF[name] ?: "", true)
        if ((opt.optJSONArray("origins")?.length() ?: 0) > 0 || (opt.optJSONArray("excludeOrigins")?.length() ?: 0) > 0) {
            c.fail("Deleting data for single websites is not supported in Thazir."); return
        }
        if (data.optBoolean("passwords", false)) { c.fail("Deleting saved passwords is not supported in Thazir."); return }
        val on = BooleanArray(7) // ClearData order: history, cookies + site data, cache, closed tabs, download list, site settings, passwords
        for (k in listOf("cookies", "localStorage", "indexedDB", "serviceWorkers", "fileSystems", "webSQL", "cacheStorage")) if (data.optBoolean(k, false)) on[1] = true
        if (data.optBoolean("history", false)) on[0] = true
        if (data.optBoolean("cache", false)) on[2] = true
        if (data.optBoolean("downloads", false)) on[4] = true
        if (on.none { it }) { c.ok(null); return } // nothing Thazir can map (formData, appcache ...): nothing to delete
        val act = m.host() as? Activity
        if (act == null || act.isFinishing) { c.fail("No browser window"); return }
        if (asking) { c.fail("Another request is waiting for an answer."); return }
        val since = opt.optDouble("since", 0.0).toLong()
        val span = if (since <= 0L) 0L else maxOf(60_000L, System.currentTimeMillis() - since)
        val what = ArrayList<String>()
        if (on[0]) what.add("Browsing history")
        if (on[1]) what.add("Cookies and site data")
        if (on[2]) what.add("Cached files")
        if (on[4]) what.add("Download history (list only)")
        val ext = ExtRegistry.get(c.extId)?.name ?: "An extension"
        val msg = ext + " wants to delete:\n\n" + what.joinToString("\n") { "• $it" } + "\n\nTime range: " +
            (if (span == 0L) "all time" else "since " + DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(since))) +
            (if (span != 0L && (on[1] || on[2])) "\n\nCookies, site data and cached files are always deleted for all time (Android limit)." else "")
        asking = true
        var answered = false
        fun done(ok: Boolean) {
            if (answered) return
            answered = true; asking = false
            if (ok) { try { ClearData.apply(act, m, span, on); c.ok(null) } catch (e: Exception) { c.fail("Could not delete everything") } }
            else c.fail("The user denied this request.")
        }
        try {
            MaterialAlertDialogBuilder(act).setTitle("Delete browsing data?").setMessage(msg)
                .setNegativeButton("Cancel") { _, _ -> done(false) }
                .setPositiveButton("Delete") { _, _ -> done(true) }
                .setOnCancelListener { done(false) }
                .setOnDismissListener { done(false) }
                .show()
        } catch (e: Exception) { asking = false; c.fail("No browser window") }
    }
}
