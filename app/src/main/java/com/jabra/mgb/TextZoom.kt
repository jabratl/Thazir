package com.jabra.mgb

import android.app.Activity
import android.content.Context
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * S41: text size per site (50% - 250%). Saved in prefs mgb_zoom (host -> percent), applied in TabManager.onPageStarted
 * through WebSettings.textZoom. Menu > Text size.
 */
object TextZoom {
    private fun sp(ctx: Context) = ctx.getSharedPreferences("mgb_zoom", Context.MODE_PRIVATE)

    /** A2: a site without its own size uses the default zoom (Accessibility, default 100 = as before). */
    fun get(ctx: Context, host: String): Int = if (host.isEmpty()) 100 else sp(ctx).getInt(host, AppearancePrefs.zoomDef(ctx))

    /** A2: the key is removed only when v equals the default, so a site can be pinned to 100 while the default is 150. */
    fun set(ctx: Context, host: String, v: Int) { // STEP D: public for Site settings
        if (host.isEmpty()) return
        if (v == AppearancePrefs.zoomDef(ctx)) sp(ctx).edit().remove(host).apply() else sp(ctx).edit().putInt(host, v).apply()
    }

    /** A2: forget this site's own size (it follows the default again). */
    fun reset(ctx: Context, host: String) { if (host.isNotEmpty()) sp(ctx).edit().remove(host).apply() }

    /** A2: every saved site size (host -> percent). Only Int values of mgb_zoom. */
    fun all(ctx: Context): Map<String, Int> = sp(ctx).all.filterValues { it is Int }.mapValues { it.value as Int }

    /** A2: removes only the mgb_zoom keys. */
    fun resetAll(ctx: Context) { sp(ctx).edit().clear().apply() }

    /** A2: re-applies the size to the page that is open now (default zoom changed, site size reset). */
    fun applyActive(ctx: Context) {
        val t = BookmarksScreen.tabsRef?.active ?: return
        if (t.isExt || !t.url.startsWith("http")) return
        val z = get(ctx, Util.host(t.url))
        if (t.web.settings.textZoom != z) t.web.settings.textZoom = z
    }

    fun dialog(act: Activity, tabs: TabManager) {
        val t = tabs.active
        if (t == null || t.isExt || !t.url.startsWith("http")) { Util.toast(act, "Open a web page first"); return }
        val host = Util.host(t.url)
        val old = if (t.priv && t.privZoom > 0) t.privZoom else get(act, host) // 0.0.478
        val tv = TextView(act).apply { textSize = 18f; setTextColor(act.getColor(R.color.mg_text)); text = "$old%" }
        val bar = SeekBar(act).apply { max = 20; progress = ((old - 50) / 10).coerceIn(0, 20) }
        bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, v: Int, fromUser: Boolean) {
                val z = 50 + v * 10
                tv.text = "$z%"
                t.web.settings.textZoom = z
            }
            override fun onStartTrackingTouch(s: SeekBar?) { }
            override fun onStopTrackingTouch(s: SeekBar?) { }
        })
        val pad = Util.dp(act, 24)
        val col = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, Util.dp(act, 8), pad, 0)
            addView(tv); addView(bar)
        }
        MaterialAlertDialogBuilder(act)
            .setTitle("Text size · $host")
            .setView(col)
            .setPositiveButton("Save") { _, _ ->
                if (t.priv) { t.privZoom = 50 + bar.progress * 10; Util.toast(act, "Applied to this Incognito tab only") } // 0.0.478: nothing is saved
                else { set(act, host, 50 + bar.progress * 10); Util.toast(act, "Saved for $host") }
            }
            .setNeutralButton("Reset") { _, _ -> if (t.priv) t.privZoom = 0 else reset(act, host); t.web.settings.textZoom = get(act, host) } // A2: back to the default zoom
            .setNegativeButton("Cancel") { _, _ -> t.web.settings.textZoom = old }
            .setOnCancelListener { t.web.settings.textZoom = old }
            .show()
    }
}
