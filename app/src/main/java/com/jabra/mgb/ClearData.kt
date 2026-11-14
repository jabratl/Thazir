package com.jabra.mgb

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import androidx.fragment.app.FragmentActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.json.JSONArray
import org.json.JSONObject

/**
 * 0.0.382: Chrome-style "Delete browsing data" (time range + what to delete). NEW file; the old dialog (TabSwitcher.deleteData)
 * and the old Settings rows stay as the fallback. Only history can be cut by time (Android WebView cannot delete cookies / cache /
 * site data for a time range), so those parts are always "all time" and the dialog says so. Passwords need the screen lock.
 * Download history = the Saved files LIST only; the files in Downloads stay.
 */
object ClearData {
    private val RANGES = arrayOf("Last hour", "Last 24 hours", "Last 7 days", "Last 4 weeks", "All time")
    private val MS = longArrayOf(3_600_000L, 86_400_000L, 7 * 86_400_000L, 28 * 86_400_000L, 0L)

    /** Opens the dialog. Throws only before it is shown (caller then uses the old dialog). */
    /** 0.0.386: true = dialog opened; false = caller keeps its old action. preset = the one box ticked first (-1 = the usual defaults). */
    fun tryShow(act: Activity, tm: TabManager?, preset: Int): Boolean = try { show(act, tm, preset); true } catch (e: Exception) { false }

    private fun dirSize(f: java.io.File, depth: Int = 0): Long {
        if (depth > 8) return 0L
        var n = 0L
        try { f.listFiles()?.forEach { n += if (it.isDirectory) dirSize(it, depth + 1) else it.length() } } catch (e: Exception) { }
        return n
    }

    fun show(act: Activity, tm: TabManager?, preset: Int = -1) {
        fun dp(v: Int) = Util.dp(act, v)
        val cText = act.getColor(R.color.mg_text)
        val cDim = act.getColor(R.color.mg_text_dim)
        val col = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(22), dp(8), dp(22), dp(4)) }
        col.addView(TextView(act).apply { text = "Time range"; setTextColor(cDim); textSize = 13f })
        val spin = Spinner(act)
        spin.adapter = ArrayAdapter(act, android.R.layout.simple_spinner_dropdown_item, RANGES)
        spin.setSelection(RANGES.size - 1)
        col.addView(spin, LinearLayout.LayoutParams(-1, -2))
        val pwN = try { PasswordStore.all(act).size } catch (e: Exception) { 0 }
        // 0.0.385: counts / size next to each name (display only; any error = no number)
        fun cnt(f: () -> Int): String = try { val n = f(); " ($n)" } catch (e: Exception) { "" }
        val names = arrayOf("Browsing history" + cnt { Bookmarks.history().size }, "Cookies and site data", "Cached files",
            "Recently closed tabs" + cnt { ClosedTabs.list().size },
            "Download history (list only)" + cnt { ChromeBridge.local.get("mgHist").optJSONArray("mgHist")?.length() ?: 0 },
            "Site settings (permissions)" + cnt { SitePerms.count(act) }, "Saved passwords ($pwN)")
        val on = if (preset in 0..6) BooleanArray(7) { it == preset } else booleanArrayOf(true, false, true, true, false, false, false)
        val boxes = ArrayList<CheckBox>()
        for (i in names.indices) {
            val cb = CheckBox(act).apply { text = names[i]; setTextColor(cText); isChecked = on[i]; textSize = 15f }
            cb.setOnCheckedChangeListener { _, v -> on[i] = v }
            boxes.add(cb)
            col.addView(cb, LinearLayout.LayoutParams(-1, -2))
        }
        // cache size: read on a worker thread, the label is filled in when ready (no number if the folder is not found)
        try {
            val cacheDir = java.io.File(act.cacheDir, "WebView")
            if (cacheDir.exists()) Thread {
                val sz = dirSize(cacheDir)
                if (sz > 0) act.runOnUiThread { try { boxes[2].text = "Cached files (" + EngineApi.fmtSize(sz) + ")" } catch (e: Exception) { } }
            }.start()
        } catch (e: Exception) { }
        val note = TextView(act).apply { setTextColor(cDim); textSize = 12f; setPadding(0, dp(8), 0, 0) }
        fun upd(pos: Int) {
            note.text = if (pos == RANGES.size - 1) "Saved passwords ask for your screen lock." else
                "Only history follows the time range. Cookies, site data and cached files are always deleted for all time (Android limit)."
        }
        upd(RANGES.size - 1)
        spin.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: android.widget.AdapterView<*>?, v: View?, pos: Int, id: Long) { upd(pos) }
            override fun onNothingSelected(p: android.widget.AdapterView<*>?) {}
        }
        col.addView(note)
        val sv = ScrollView(act).apply { addView(col) }
        MaterialAlertDialogBuilder(act)
            .setTitle("Delete browsing data")
            .setView(sv)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Delete") { _, _ -> run(act, tm, MS[spin.selectedItemPosition], on) }
            .show()
    }

    /** 0.0.431 (S6): chrome.browsingData (ExtApi3) runs the same deletion after its own confirm dialog. on = the 7 boxes of the dialog. */
    fun apply(act: Activity, tm: TabManager?, span: Long, on: BooleanArray) = run(act, tm, span, on)

    private fun run(act: Activity, tm: TabManager?, span: Long, on: BooleanArray) {
        var fail = false
        fun step(f: () -> Unit) { try { f() } catch (e: Exception) { fail = true; try { Diag.w("cleardata", e.javaClass.simpleName) } catch (x: Exception) { } } }
        if (on[0]) step {
            if (span == 0L) Bookmarks.clearHistory()
            else { val from = System.currentTimeMillis() - span; Bookmarks.removeHistoryMany(Bookmarks.history().filter { it.time >= from }) }
        }
        if (on[1]) step {
            android.webkit.CookieManager.getInstance().removeAllCookies(null)
            android.webkit.CookieManager.getInstance().flush()
            android.webkit.WebStorage.getInstance().deleteAllData()
        }
        if (on[2]) step { tm?.active?.web?.clearCache(true) }
        if (on[3]) step { ClosedTabs.clear() }
        if (on[4]) step { Handler(Looper.getMainLooper()).post { try { ChromeBridge.storageSet("local", JSONObject().put("mgHist", JSONArray())) } catch (e: Exception) { } } }
        if (on[5]) step { SitePerms.clearAll(act) }
        Util.toast(act, if (fail) "Could not delete everything" else "Browsing data deleted")
        if (on[6]) step {
            if (act is FragmentActivity) PasswordStore.wipeAll(act) { Util.toast(act, "Saved passwords deleted") }
            else Util.toast(act, "Cannot ask for unlock here")
        }
    }
}
