package com.jabra.mgb

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Handler
import android.os.Looper
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors

/**
 * 0.0.430 (S5): chrome.tabs / chrome.windows members that were missing, for INSTALLED extensions only
 * (handlers are "ext.<api>", so the built-in Media Grabber keeps its own handlers untouched).
 *  tabs: duplicate, move, goBack, goForward, getZoom, setZoom, getZoomSettings, setZoomSettings, highlight, discard, captureVisibleTab
 *  windows: getAll, getLastFocused, remove (Thazir has one window, id 1)
 * Rules: private tabs are invisible here (like "No tab with id"); errors use Chrome's wording; permission = same as tabs.get
 * (url / title only where ExtApi.tabFor allows); captureVisibleTab needs activeTab or a host permission of the tab on screen.
 */
object ExtApi2 {
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private const val MAX_SIDE = 4096
    private const val MAX_DATA = 14_000_000 // chars of the data URL

    private fun ui(b: () -> Unit) { if (Looper.myLooper() == Looper.getMainLooper()) b() else main.post { b() } }

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

    private fun isNone(a: Any?) = a == null || a == JSONObject.NULL

    /** Tab an extension may touch (private tabs do not exist for it). */
    private fun tabOf(m: TabManager, id: Int): TabManager.Tab? = m.byId(id)?.takeIf { !it.priv }

    /** First argument = tabId, or missing = the tab on screen. null = no such tab. */
    private fun tabArg(c: ChromeBridge.Call, m: TabManager): TabManager.Tab? {
        val a = c.args.opt(0)
        return if (isNone(a) || a is JSONObject) m.active?.takeIf { !it.priv } else tabOf(m, c.args.optInt(0, -1))
    }

    private fun noTab(c: ChromeBridge.Call) = c.fail("No tab with id: ${c.args.opt(0)}.")

    private fun json(c: ChromeBridge.Call, t: TabManager.Tab): JSONObject = ExtApi.tabFor(c.extId, TabsApi.tabJson(t))

    private fun win(c: ChromeBridge.Call, m: TabManager, populate: Boolean): JSONObject {
        val w = JSONObject().put("id", 1).put("focused", true).put("incognito", false).put("type", "normal")
            .put("state", "normal").put("alwaysOnTop", false)
        if (populate) {
            val a = JSONArray()
            for (t in m.all()) if (!t.priv) a.put(TabsApi.tabJson(t))
            w.put("tabs", ExtApi.tabsFor(c.extId, a))
        }
        return w
    }

    fun register() {
        reg("tabs.duplicate") { c, m ->
            val src = tabOf(m, c.args.optInt(0, -1))
            if (src == null) { noTab(c); return@reg }
            val t = m.cloneTab(src, false)
            if (t == null) c.fail("Cannot duplicate this tab.") else c.ok(json(c, t))
        }

        reg("tabs.move") { c, m ->
            val a = c.args.opt(0)
            val ids = if (a is JSONArray) (0 until a.length()).map { a.optInt(it, -1) } else listOf(c.args.optInt(0, -1))
            val list = ArrayList<TabManager.Tab>()
            for (id in ids) { val t = tabOf(m, id); if (t == null) { c.fail("No tab with id: $id."); return@reg }; list.add(t) }
            val to = c.args.optJSONObject(1)?.optInt("index", -1) ?: -1
            val out = JSONArray()
            for ((k, t) in list.withIndex()) {
                val from = m.all().indexOf(t)
                m.moveTab(t, if (to < 0) m.count else to + k)
                val now = m.all().indexOf(t)
                if (now != from) ExtApi.tabsEvent("tabs.onMoved", t, listOf(t.id, JSONObject().put("windowId", 1).put("fromIndex", from).put("toIndex", now)))
                out.put(json(c, t))
            }
            c.ok(if (a is JSONArray) out else out.opt(0))
        }

        reg("tabs.goBack") { c, m ->
            val t = tabArg(c, m)
            if (t == null) { noTab(c); return@reg }
            if (t.suspended || t.pending != null || !m.canBack(t)) c.fail("Cannot find a previous page in history.") else { m.back(t); c.ok(null) }
        }
        reg("tabs.goForward") { c, m ->
            val t = tabArg(c, m)
            if (t == null) { noTab(c); return@reg }
            if (t.suspended || t.pending != null || !m.canFwd(t)) c.fail("Cannot find a next page in history.") else { m.fwd(t); c.ok(null) }
        }

        // zoom = the tab's text size (Thazir has no page zoom): 1.0 = 100 %, resets on the next navigation like Chrome's per-tab zoom
        reg("tabs.getZoom") { c, m ->
            val t = tabArg(c, m)
            if (t == null || t.suspended) { if (t == null) noTab(c) else c.fail("Cannot access this tab."); return@reg }
            c.ok(m.zoomOf(t))
        }
        reg("tabs.setZoom") { c, m ->
            val one = c.args.length() <= 1
            val t = if (one) m.active?.takeIf { !it.priv } else tabOf(m, c.args.optInt(0, -1))
            val f = c.args.optDouble(if (one) 0 else 1, Double.NaN)
            if (t == null) { noTab(c); return@reg }
            if (f.isNaN() || (f != 0.0 && (f < 0.25 || f > 5.0))) { c.fail("Zoom factor must be 0 (default) or between 0.25 and 5."); return@reg }
            if (t.suspended) { c.fail("Cannot access this tab."); return@reg }
            m.setZoomOf(t, f)
            c.ok(null)
        }
        reg("tabs.getZoomSettings") { c, m ->
            if (tabArg(c, m) == null) noTab(c)
            else c.ok(JSONObject().put("mode", "automatic").put("scope", "per-tab").put("defaultZoomFactor", 1.0))
        }
        reg("tabs.setZoomSettings") { c, m ->
            val one = c.args.length() <= 1
            val t = if (one) m.active?.takeIf { !it.priv } else tabOf(m, c.args.optInt(0, -1))
            val mode = (c.args.optJSONObject(if (one) 0 else 1) ?: JSONObject()).optString("mode", "automatic")
            if (t == null) noTab(c)
            else if (mode != "automatic" && mode != "manual" && mode != "disabled") c.fail("Invalid value for argument. Property 'mode': Value must be one of: automatic, manual, disabled.")
            else c.ok(null) // accepted, not stored: setZoom always works
        }

        reg("tabs.highlight") { c, m ->
            val h = c.args.optJSONObject(0) ?: JSONObject()
            val v = h.opt("tabs")
            val idx = if (v is JSONArray) v.optInt(0, -1) else if (v is Number) v.toInt() else -1
            val t = m.all().getOrNull(idx)
            if (t == null || t.priv) c.fail("No tab at index: $idx.") else { m.switchTo(t); c.ok(win(c, m, true)) }
        }

        reg("tabs.discard") { c, m ->
            val t = tabArg(c, m)
            if (t == null) { noTab(c); return@reg }
            if (m.discardTab(t)) c.ok(json(c, t)) else c.fail("Cannot discard this tab.")
        }

        reg("tabs.captureVisibleTab") { c, m -> capture(c, m) }

        reg("windows.getAll") { c, m ->
            val o = c.args.optJSONObject(0) ?: JSONObject()
            val types = o.optJSONArray("windowTypes")
            val normal = types == null || (0 until types.length()).any { types.optString(it) == "normal" }
            c.ok(JSONArray().also { if (normal) it.put(win(c, m, o.optBoolean("populate", false))) })
        }
        reg("windows.getLastFocused") { c, m -> c.ok(win(c, m, (c.args.optJSONObject(0) ?: JSONObject()).optBoolean("populate", false))) }
        reg("windows.remove") { c, _ -> c.fail("Cannot close the only browser window.") } // Thazir has one window: honest error, nothing is closed
    }

    // ---------- captureVisibleTab ----------

    private fun capture(c: ChromeBridge.Call, m: TabManager) {
        val a0 = c.args.opt(0)
        val opt = (if (a0 is JSONObject) a0 else c.args.optJSONObject(1)) ?: JSONObject()
        val t = m.active
        val info = ExtRegistry.get(c.extId)
        if (t == null || info == null) { c.fail("No active tab"); return }
        val denied = "Cannot access contents of the page. Extension manifest must request permission to access the respective host."
        if (t.priv || t.home || t.suspended || t.pending != null || !t.url.startsWith("http")) { c.fail(denied); return }
        if ("activeTab" !in info.permissions && !ExtApi.hostAllows(info, t.url)) { c.fail(denied); return }
        val w = t.web
        val vw = w.width; val vh = w.height
        if (vw <= 0 || vh <= 0) { c.fail("Could not capture the page."); return }
        val sc = minOf(1f, MAX_SIDE.toFloat() / maxOf(vw, vh))
        val bw = maxOf(1, (vw * sc).toInt()); val bh = maxOf(1, (vh * sc).toInt())
        val png = opt.optString("format", "jpeg") == "png"
        val q = opt.optInt("quality", 92).coerceIn(0, 100)
        val bm = try { Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888) } catch (_: Throwable) { c.fail("Could not capture the page."); return }
        try {
            val cv = Canvas(bm)
            cv.scale(sc, sc)
            w.draw(cv) // only the page: popups / dialogs of the extension are separate windows
        } catch (_: Throwable) { bm.recycle(); c.fail("Could not capture the page."); return }
        io.execute {
            try {
                val out = ByteArrayOutputStream()
                bm.compress(if (png) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, if (png) 100 else q, out)
                val url = "data:image/" + (if (png) "png" else "jpeg") + ";base64," + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
                if (url.length > MAX_DATA) c.fail("The image is too large.") else c.ok(url)
            } catch (e: Throwable) { c.fail("Could not capture the page.") }
            finally { bm.recycle() }
        }
    }
}
