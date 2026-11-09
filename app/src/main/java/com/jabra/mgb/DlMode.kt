package com.jabra.mgb

import android.app.Activity
import android.content.Context
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.json.JSONObject

/**
 * S54: one switch for the download buttons, so the V77 Grabber and the native downloader do not pile up on a page.
 * Modes (prefs mgb_ui dl_mode): 0 Smart (default), 1 Grabber only, 2 Native only, 3 Manual (the single switches below decide).
 * A mode writes the single switches: V77 showPill / nativePanel / showOverlay (chrome.storage.sync) and
 * native_btn / video_btn (prefs mgb_ui). Changing one single switch by hand moves the mode to Manual.
 * Smart also hides the in-page video button (WebVideo) while the Grabber page button is showing on that page.
 */
object DlMode {
    const val SMART = 0
    const val GRABBER = 1
    const val NATIVE = 2
    const val MANUAL = 3

    @Volatile var smartOn = false // read by WebVideo.Bridge.smart() from the page

    private fun prefs(c: Context) = c.getSharedPreferences("mgb_ui", Context.MODE_PRIVATE)

    fun mode(c: Context): Int = prefs(c).getInt("dl_mode", -1)

    // ---- S57: per-site override (prefs mgb_ui dl_site_modes = { host: 0 Smart | 1 Grabber only | 2 Native only }) ----
    @Volatile private var siteCache: Map<String, Int> = emptyMap()

    private fun siteKey(host: String) = host.lowercase().removePrefix("www.")

    private fun siteJson(c: Context): JSONObject = try { JSONObject(prefs(c).getString("dl_site_modes", "{}") ?: "{}") } catch (e: Exception) { JSONObject() }

    fun loadSites(c: Context) {
        val o = siteJson(c)
        val m = HashMap<String, Int>()
        val it = o.keys()
        while (it.hasNext()) { val k = it.next(); m[k] = o.optInt(k, -1) }
        siteCache = m
    }

    /** -1 = this site follows the global mode. */
    fun siteMode(host: String): Int {
        val h = siteKey(host)
        for ((k, v) in siteCache) if (v >= 0 && (h == k || h.endsWith(".$k"))) return v
        return -1
    }

    fun smartFor(host: String): Boolean = siteMode(host).let { if (it >= 0) it == SMART else smartOn }

    fun setSite(c: Context, host: String, m: Int) {
        val o = siteJson(c)
        val k = siteKey(host)
        if (m < 0) o.remove(k) else o.put(k, m)
        prefs(c).edit().putString("dl_site_modes", o.toString()).apply()
        loadSites(c)
        try { ChromeBridge.storageSet("sync", JSONObject().put("mgbSiteModes", JSONObject(o.toString()))) } catch (e: Exception) { /* engine not ready */ }
    }

    fun siteLabel(c: Context, host: String): String = when (siteMode(host)) {
        SMART -> "Smart"
        GRABBER -> "Grabber only"
        NATIVE -> "Native only"
        else -> "Follow global (" + label(c) + ")"
    }

    fun siteDialog(act: Activity, host: String, done: () -> Unit) {
        val names = arrayOf("Follow the global mode (" + label(act) + ")", "Smart", "Grabber only", "Native only")
        MaterialAlertDialogBuilder(act)
            .setTitle("Buttons on " + siteKey(host))
            .setSingleChoiceItems(names, siteMode(host) + 1) { d, i ->
                setSite(act, host, i - 1)
                Util.toast(act, "Saved for this site. Reload the page")
                d.dismiss()
                done()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    fun label(c: Context): String = when (mode(c)) {
        GRABBER -> "Grabber only"
        NATIVE -> "Native only"
        MANUAL -> "Manual"
        else -> "Smart"
    }

    /** First start with this version: Smart, written once. */
    fun init(c: Context) {
        loadSites(c) // S57
        if (mode(c) == -1) apply(c, SMART) else smartOn = mode(c) == SMART
    }

    /** Changing a single switch by hand = Manual. */
    fun manual(c: Context) {
        prefs(c).edit().putInt("dl_mode", MANUAL).apply()
        smartOn = false
    }

    fun apply(c: Context, m: Int) {
        prefs(c).edit().putInt("dl_mode", m).apply()
        smartOn = m == SMART
        if (m == MANUAL) return
        val pill = m != NATIVE
        val panel = m == SMART
        val overlay = m == GRABBER
        val nativeBtn = m == NATIVE
        val videoBtn = m != GRABBER
        try {
            ChromeBridge.storageSet("sync", JSONObject().put("showPill", pill).put("nativePanel", panel).put("showOverlay", overlay))
        } catch (e: Exception) { /* engine not ready: the page switches stay as they were */ }
        prefs(c).edit().putBoolean("native_btn", nativeBtn).putBoolean("video_btn", videoBtn).apply()
    }

    fun dialog(act: Activity, done: () -> Unit) {
        val names = arrayOf(
            "Smart (recommended)\nOne Grabber button per page, opens the Files detected panel. The video button hides itself when the Grabber button shows. No double buttons.",
            "Grabber only\nMedia Grabber (V82) button and the button on video players. Native buttons off.",
            "Native only\nNative video button and the native download button. Grabber page buttons off.",
            "Manual\nYou switch each button yourself (below)."
        )
        val cur = mode(act).let { if (it < 0) SMART else it }
        MaterialAlertDialogBuilder(act)
            .setTitle("Download buttons")
            .setSingleChoiceItems(names, cur) { d, i ->
                apply(act, i)
                Util.toast(act, "Done. Reload open pages")
                d.dismiss()
                done()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
