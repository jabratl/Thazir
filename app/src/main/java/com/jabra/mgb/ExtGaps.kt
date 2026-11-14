package com.jabra.mgb

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.text.format.DateUtils
import org.json.JSONArray
import org.json.JSONObject

/**
 * 0.0.429 (S4): log of chrome.* calls that installed extensions made and Thazir does not support.
 * Sources: the shim stubs ("__miss" message), ExtApi.gate ("Not supported in MGB") and ChromeBridge ("Not implemented").
 * Only api names are stored (never arguments or page data): per extension + api a counter and the last time, max 200 rows.
 * Own prefs file (mgb_ext_gaps) so Backup / Reset of the settings are not touched. Rows of removed extensions are not shown.
 */
object ExtGaps {
    private const val PREFS = "mgb_ext_gaps"
    private const val KEY = "list"
    private const val MAX = 200
    private val API = Regex("^[A-Za-z0-9_.]{1,80}$")

    private class Gap(val ext: String, val api: String, var n: Int, var last: Long)

    private val list = ArrayList<Gap>() // guarded by itself
    private var app: Context? = null
    private var saving = false
    private val main = Handler(Looper.getMainLooper())

    fun init(ctx: Context) {
        synchronized(list) {
            if (app != null) return
            app = ctx.applicationContext
            try {
                val arr = JSONArray(app!!.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "[]"))
                for (i in 0 until minOf(arr.length(), MAX)) {
                    val o = arr.optJSONObject(i) ?: continue
                    val x = o.optString("x"); val a = o.optString("a")
                    if (ExtRegistry.isValidId(x) && API.matches(a)) list.add(Gap(x, a, o.optInt("n", 1).coerceAtLeast(1), o.optLong("t")))
                }
            } catch (_: Exception) { list.clear() }
        }
    }

    /** Count one unsupported call. Safe from any thread; ignores the built-in extension and bad names. */
    fun record(extId: String, api: String) {
        if (extId == ContentInjector.EXT_ID || !ExtRegistry.isValidId(extId) || !API.matches(api)) return
        synchronized(list) {
            if (app == null) return
            val now = System.currentTimeMillis()
            val g = list.firstOrNull { it.ext == extId && it.api == api }
            if (g != null) { g.n++; g.last = now }
            else {
                list.add(Gap(extId, api, 1, now))
                if (list.size > MAX) { list.sortBy { it.last }; list.removeAt(0) } // oldest dropped
            }
            if (!saving) { saving = true; main.postDelayed({ save() }, 3000) }
        }
    }

    private fun save() {
        val snap = synchronized(list) {
            saving = false
            JSONArray().also { a -> for (g in list) a.put(JSONObject().put("x", g.ext).put("a", g.api).put("n", g.n).put("t", g.last)) }.toString()
        }
        try { app?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.edit()?.putString(KEY, snap)?.apply() } catch (_: Exception) { }
    }

    /** Rows of extensions that are still installed, most called first. */
    private fun rows(): List<Gap> = synchronized(list) {
        list.filter { ExtRegistry.get(it.ext) != null }.sortedWith(compareBy<Gap>({ it.ext }, { -it.n }))
    }

    /** "Name: api (n)" for the Report center verdict (the 3 most called). */
    fun top(max: Int): List<String> =
        rows().sortedByDescending { it.n }.take(max).map { (ExtRegistry.get(it.ext)?.name ?: it.ext) + ": " + it.api + " (" + it.n + ")" }

    fun section(): DiagReport.Section {
        val all = rows()
        if (all.isEmpty()) return DiagReport.Section("extgaps", "Browser", "Extension API gaps", DiagReport.INFO, listOf("No unsupported chrome.* call recorded"))
        val now = System.currentTimeMillis()
        val lines = ArrayList<String>()
        lines.add("chrome.* calls that extensions made and Thazir does not support (the extension keeps running, that call fails):")
        var shown = 0
        var cur = ""
        for (g in all) {
            if (shown >= 40) break
            if (g.ext != cur) { cur = g.ext; lines.add((ExtRegistry.get(g.ext)?.name ?: g.ext) + ":"); }
            lines.add("  " + g.api + "  x" + g.n + "  " + DateUtils.getRelativeTimeSpanString(g.last, now, DateUtils.MINUTE_IN_MILLIS))
            shown++
        }
        if (all.size > shown) lines.add("... and " + (all.size - shown) + " more")
        return DiagReport.Section("extgaps", "Browser", "Extension API gaps", DiagReport.WARN, lines)
    }
}
