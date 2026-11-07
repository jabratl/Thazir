package com.jabra.mgb

import org.json.JSONObject

/**
 * S11/S12: native code talks to V77 the way its popup does: runtime messages to the engine
 * (get-state, start-job, job-pause, job-cancel, job-remove, jobs-clear) and direct reads of the
 * storage V77 keeps its jobs (session: mgJobs) and saved files (local: mgHist) in.
 * Sender url = popup.html, so V77 treats msg.tabId as the page to work on (same as the popup does).
 */
object EngineApi {
    private fun engineCtx(): ChromeBridge.Ctx? =
        ChromeBridge.contexts().firstOrNull { it.kind == ChromeBridge.Kind.EXT && EngineHost.isEngine(it.web) }

    private fun sender(): JSONObject = JSONObject().put("id", ContentInjector.EXT_ID)
        .put("origin", ContentInjector.EXT_ORIGIN).put("url", ContentInjector.EXT_BASE + "popup.html")

    /** Main thread. cb gets the answer object, or null (no answer / engine not running). */
    fun ask(msg: JSONObject, cb: (JSONObject?) -> Unit) {
        val c = engineCtx()
        if (c == null) { cb(null); return }
        ChromeBridge.request(c, msg, sender()) { r -> cb(r as? JSONObject) }
    }

    fun send(msg: JSONObject) {
        if (msg.optString("key").startsWith("nat:")) { DownloadsApi.plainControl(msg); return } // 0.0.325: plain download rows
        if (DlRules.hold(msg)) return // D2: rules checked before a download starts
        val c = engineCtx() ?: return
        ChromeBridge.request(c, msg, sender()) { }
    }

    /** All jobs V77 knows (running, done, failed), newest first. */
    fun jobs(): List<JSONObject> {
        val o = try { ChromeBridge.session.get("mgJobs").optJSONObject("mgJobs") } catch (e: Exception) { null }
        val out = ArrayList<JSONObject>()
        if (o != null) for (k in o.keys()) o.optJSONObject(k)?.let { out.add(it) }
        try { out.addAll(DownloadsApi.plainJobs()) } catch (e: Exception) { } // 0.0.325: tab link / blob downloads (not V77 jobs)
        return out.sortedByDescending { it.optLong("updated") }
    }

    /** Saved files, newest first (V77 keeps the browser download id in "id"). */
    fun history(): List<JSONObject> {
        val a = try { ChromeBridge.local.get("mgHist").optJSONArray("mgHist") } catch (e: Exception) { null }
            ?: return emptyList()
        val out = ArrayList<JSONObject>()
        for (i in 0 until a.length()) a.optJSONObject(i)?.let { out.add(it) }
        return out.sortedByDescending { it.optLong("time") }
    }

    fun fmtSize(b: Long): String = when {
        b <= 0 -> ""
        b >= 1L shl 30 -> String.format("%.2f GB", b / 1073741824.0)
        b >= 1L shl 20 -> String.format("%.1f MB", b / 1048576.0)
        else -> String.format("%d KB", maxOf(1L, b / 1024))
    }

    fun fmtDur(s: Double): String {
        val t = s.toLong()
        if (t <= 0) return ""
        val h = t / 3600
        val m = (t % 3600) / 60
        val sec = t % 60
        return if (h > 0) String.format("%d:%02d:%02d", h, m, sec) else String.format("%d:%02d", m, sec)
    }
}
