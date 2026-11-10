package com.jabra.mgb

import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.view.Gravity
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebStorage
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import androidx.fragment.app.FragmentActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * 0.0.383: Chrome's "Delete data" for ONE site (site sheet, lock icon). NEW file; SiteSheet only adds the button (1 try / catch hook).
 * Deletes: cookies (also ones of the parent domain that this page sends), localStorage / IndexedDB / WebSQL of the site origin
 * (http + https), and optionally the site's history rows. Limits (Android WebView): cookies with an unusual Path that this page
 * does not send, and subdomain storage (m.site.com vs site.com) are not reached; the cache cannot be cut per site.
 * Saved passwords and permission answers are NOT touched (own places).
 */
object SiteData {
    private val EPOCH = "Thu, 01 Jan 1970 00:00:00 GMT"

    /** The button for the sheet. onDone = what to do after a delete (the sheet closes itself). */
    fun button(act: FragmentActivity, tab: TabManager.Tab, onDone: () -> Unit): View? {
        val url = tab.url
        if (!url.startsWith("http://", true) && !url.startsWith("https://", true)) return null // home / internal pages: nothing to delete
        val host = Util.host(url)
        fun dp(v: Int) = Util.dp(act, v)
        val err = act.getColor(R.color.mg_error)
        return TextView(act).apply {
            text = "Delete data for this site"
            textSize = 14f; gravity = Gravity.CENTER; minimumHeight = dp(48)
            setTextColor(err)
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.BOLD)
            background = GradientDrawable().apply { setColor(ColorUtils.blendARGB(act.getColor(R.color.mg_surface), err, 0.12f)); cornerRadius = dp(16).toFloat() }
            isClickable = true
            setOnClickListener { ask(act, tab, host, onDone) }
        }
    }

    private fun ask(act: FragmentActivity, tab: TabManager.Tab, host: String, onDone: () -> Unit) {
        fun dp(v: Int) = Util.dp(act, v)
        val n = try { Bookmarks.history().count { Util.host(it.url) == host } } catch (e: Exception) { 0 }
        val col = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(22), dp(8), dp(22), 0) }
        col.addView(TextView(act).apply {
            text = "Deletes cookies and site data of $host (you will be logged out). Saved passwords and permissions stay."
            setTextColor(act.getColor(R.color.mg_text)); textSize = 14f
        })
        val hist = CheckBox(act).apply { text = "Also delete its history ($n)"; setTextColor(act.getColor(R.color.mg_text)); isEnabled = n > 0 }
        col.addView(hist)
        MaterialAlertDialogBuilder(act)
            .setTitle("Delete data for this site?")
            .setView(col)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Delete") { _, _ ->
                val ok = wipe(tab.url)
                if (hist.isChecked) try { wipeHistory(host) } catch (e: Exception) { }
                Util.toast(act, if (ok) "Data deleted for $host" else "Could not delete everything")
                try { tab.web.reload() } catch (e: Exception) { }
                try { onDone() } catch (e: Exception) { }
            }
            .show()
    }

    /** Cookies + web storage of this page's site. Returns false if a step failed. Never throws. */
    fun wipe(pageUrl: String): Boolean {
        var ok = true
        try {
            val u = Uri.parse(pageUrl)
            val h = u.host ?: return false
            val cm = CookieManager.getInstance()
            // every domain this page may share cookies with: a.b.com, b.com (not the bare suffix)
            val labels = h.split('.')
            val domains = ArrayList<String>()
            for (i in 0 until maxOf(1, labels.size - 1)) domains.add(labels.drop(i).joinToString("."))
            val origins = listOf("https://$h", "http://$h")
            for (o in origins) {
                val raw = try { cm.getCookie("$o/") } catch (e: Exception) { null } ?: continue
                for (part in raw.split(';')) {
                    val name = part.substringBefore('=').trim()
                    if (name.isEmpty()) continue
                    val exp = "=; Max-Age=0; Expires=$EPOCH; Path=/"
                    try {
                        cm.setCookie("$o/", name + exp)
                        for (d in domains) { cm.setCookie("$o/", "$name$exp; Domain=$d"); cm.setCookie("$o/", "$name$exp; Domain=.$d") }
                    } catch (e: Exception) { ok = false }
                }
            }
            try { cm.flush() } catch (e: Exception) { }
            for (o in origins) try { WebStorage.getInstance().deleteOrigin(o) } catch (e: Exception) { ok = false }
        } catch (e: Exception) { ok = false }
        return ok
    }

    private fun wipeHistory(host: String) {
        Bookmarks.removeHistoryMany(Bookmarks.history().filter { Util.host(it.url) == host })
    }
}
