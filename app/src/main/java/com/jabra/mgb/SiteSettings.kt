package com.jabra.mgb

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.json.JSONObject

/**
 * Settings STEP D: one screen with every host that has a saved site setting.
 * No new storage. Reads the four existing stores and writes only through their existing setters:
 *  - SitePerms (prefs mgb_perm "host|what")        get / set
 *  - SiteConfig (prefs mgb_site, JS off / Ads OK / Desktop)  hostsWith / flags / toggle
 *  - TextZoom (prefs mgb_zoom host -> percent)      get / set
 *  - DlMode (prefs mgb_ui dl_site_modes)            setSite / siteLabel / siteDialog
 * Tap a host = edit it, "Reset this site" clears all four for that host. Changes apply at once;
 * an open page needs a reload to show them.
 */
object SiteSettings {
    private class Site(val key: String) {
        val perms = ArrayList<Pair<String, String>>() // raw host as saved, what
        var zoomRaw: String? = null
        var zoom = 100
        var dl = -1
        var flags = 0
        var dk = 0 // 0.0.424: SiteDark choice (0 = same as app)
    }

    private fun norm(h: String) = h.lowercase().removePrefix("www.")

    private fun collect(c: Context): List<Site> {
        val m = HashMap<String, Site>()
        fun site(h: String): Site = m.getOrPut(norm(h)) { Site(norm(h)) }
        try {
            for ((k, v) in c.getSharedPreferences("mgb_perm", Context.MODE_PRIVATE).all) {
                val i = k.indexOf('|')
                if (i > 0 && v is Int) site(k.substring(0, i)).perms.add(Pair(k.substring(0, i), k.substring(i + 1)))
            }
        } catch (_: Exception) { }
        for (bit in intArrayOf(SiteConfig.JS_OFF, SiteConfig.ADS_OK, SiteConfig.DESKTOP))
            for (h in SiteConfig.hostsWith(bit)) site(h).flags = SiteConfig.flags(h)
        try {
            for ((k, v) in c.getSharedPreferences("mgb_zoom", Context.MODE_PRIVATE).all)
                if (v is Int && v != 100 && k.isNotEmpty()) site(k).apply { zoomRaw = k; zoom = v }
        } catch (_: Exception) { }
        try {
            val o = JSONObject(c.getSharedPreferences("mgb_ui", Context.MODE_PRIVATE).getString("dl_site_modes", "{}") ?: "{}")
            val it = o.keys()
            while (it.hasNext()) { val k = it.next(); val v = o.optInt(k, -1); if (v >= 0) site(k).dl = v }
        } catch (_: Exception) { }
        for (h in SiteDark.hosts(c)) site(h).dk = SiteDark.mode(c, h) // 0.0.424: dark page exceptions are listed too
        return m.values.sortedBy { it.key }
    }

    /** For the Settings row value. */
    fun count(c: Context): Int = collect(c).size

    private fun permWord(c: Context, host: String, what: String): String =
        when (SitePerms.get(c, host, what)) { true -> "Allowed"; false -> "Blocked"; null -> "Ask again" }

    private fun summary(c: Context, s: Site): String {
        val p = ArrayList<String>()
        if (s.perms.isNotEmpty()) p.add(s.perms.size.toString() + (if (s.perms.size == 1) " permission" else " permissions"))
        if ((s.flags and SiteConfig.JS_OFF) != 0) p.add("JS off")
        if ((s.flags and SiteConfig.ADS_OK) != 0) p.add("Ads allowed")
        if ((s.flags and SiteConfig.DESKTOP) != 0) p.add("Desktop")
        if (s.zoomRaw != null) p.add("Text " + s.zoom + "%")
        if (s.dl >= 0) p.add(DlMode.siteLabel(c, s.key))
        if (s.dk != 0) p.add(if (s.dk == SiteDark.DARK) "Dark page" else "Original colours")
        return p.joinToString(" · ")
    }

    fun open(act: Activity, done: () -> Unit = {}) {
        fun dp(n: Int) = Util.dp(act, n)
        val dlg = Dialog(act, com.google.android.material.R.style.Theme_Material3_DayNight_NoActionBar)
        val root = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(act.getColor(R.color.mg_bg))
        }
        val head = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), 0, dp(4), 0)
        }
        head.addView(ImageView(act).apply {
            setImageResource(R.drawable.ic_back)
            setColorFilter(act.getColor(R.color.mg_text))
            setPadding(dp(10), dp(10), dp(10), dp(10))
            contentDescription = "Back"
            setOnClickListener { dlg.dismiss() }
        }, LinearLayout.LayoutParams(dp(44), dp(48)))
        head.addView(TextView(act).apply {
            text = "Site settings"
            textSize = 22f
            gravity = Gravity.CENTER_VERTICAL or Gravity.START
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setTextColor(act.getColor(R.color.mg_text))
            setPadding(dp(6), 0, 0, 0)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(head, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)))

        val search = EditText(act).apply {
            hint = "Search sites"
            textSize = 15f
            setSingleLine(true)
            setTextColor(act.getColor(R.color.mg_text))
            setHintTextColor(act.getColor(R.color.mg_text_dim))
            setPadding(dp(16), 0, dp(16), 0)
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(act.getColor(R.color.mg_surface)); cornerRadius = dp(28).toFloat()
            }
        }
        root.addView(search, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)).apply {
            setMargins(dp(14), dp(2), dp(14), dp(8))
        })

        val list = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), 0, dp(14), dp(24)) }
        root.addView(ScrollView(act).apply { addView(list) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        fun refresh() {
            list.removeAllViews()
            val q = search.text.toString().trim().lowercase()
            val all = collect(act)
            val shown = all.filter { q.isEmpty() || it.key.contains(q) }
            if (shown.isEmpty()) {
                list.addView(TextView(act).apply {
                    text = if (all.isEmpty()) "No site has a saved setting yet. Permissions, JavaScript off, desktop site, text size, button mode and dark page you set for a site are listed here."
                    else "No site matches."
                    textSize = 14f
                    setTextColor(act.getColor(R.color.mg_text_dim))
                    setPadding(dp(6), dp(16), dp(6), 0)
                })
                return
            }
            for (s in shown) {
                val row = LinearLayout(act).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(16), dp(12), dp(16), dp(12))
                    background = android.graphics.drawable.GradientDrawable().apply {
                        setColor(act.getColor(R.color.mg_surface)); cornerRadius = dp(14).toFloat()
                    }
                    isClickable = true
                    setOnClickListener { edit(act, s.key, all) { done(); refresh() } }
                }
                row.addView(TextView(act).apply {
                    text = s.key
                    textSize = 16f
                    typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                    setTextColor(act.getColor(R.color.mg_text))
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                })
                row.addView(TextView(act).apply {
                    text = summary(act, s)
                    textSize = 12.5f
                    setTextColor(act.getColor(R.color.mg_text_dim))
                    maxLines = 2
                    ellipsize = android.text.TextUtils.TruncateAt.END
                })
                list.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) })
            }
        }
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) { }
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) { refresh() }
            override fun afterTextChanged(s: Editable?) { }
        })

        dlg.setContentView(root)
        dlg.window?.let {
            it.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            it.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            it.setBackgroundDrawable(ColorDrawable(act.getColor(R.color.mg_bg)))
        }
        refresh()
        dlg.show()
    }

    private fun edit(act: Activity, key: String, snapshot: List<Site>, closed: () -> Unit) {
        val s = snapshot.firstOrNull { it.key == key } ?: return
        fun dp(n: Int) = Util.dp(act, n)
        val col = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(8), dp(22), 0)
        }
        fun label(t: String) = TextView(act).apply {
            text = t.uppercase()
            textSize = 11f
            letterSpacing = 0.08f
            setTextColor(act.getColor(R.color.mg_primary))
            setPadding(0, dp(14), 0, dp(4))
        }
        // switches (SiteConfig)
        col.addView(label("Switches"))
        val names = arrayOf("JavaScript off for this site", "Allow ads here (ad blocker off)", "Always open as desktop site")
        val bits = intArrayOf(SiteConfig.JS_OFF, SiteConfig.ADS_OK, SiteConfig.DESKTOP)
        for (i in 0..2) col.addView(CheckBox(act).apply {
            text = names[i]
            setTextColor(act.getColor(R.color.mg_text))
            isChecked = (SiteConfig.flags(key) and bits[i]) != 0
            setOnCheckedChangeListener { _, on ->
                if (on != ((SiteConfig.flags(key) and bits[i]) != 0)) SiteConfig.toggle(key, bits[i])
            }
        })
        // text size (TextZoom)
        col.addView(label("Text size"))
        val zoomRaw = s.zoomRaw ?: key
        val zTv = TextView(act).apply { textSize = 16f; setTextColor(act.getColor(R.color.mg_text)); text = TextZoom.get(act, zoomRaw).toString() + "%" }
        val bar = SeekBar(act).apply {
            max = 20
            progress = ((TextZoom.get(act, zoomRaw) - 50) / 10).coerceIn(0, 20)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, v: Int, fromUser: Boolean) { zTv.text = (50 + v * 10).toString() + "%" }
                override fun onStartTrackingTouch(sb: SeekBar?) { }
                override fun onStopTrackingTouch(sb: SeekBar?) { TextZoom.set(act, zoomRaw, 50 + (sb?.progress ?: 5) * 10) }
            })
        }
        col.addView(zTv); col.addView(bar)
        // A3: Force enable zoom per site (only has an effect when Accessibility > Force enable zoom is ON)
        col.addView(label("Force zoom"))
        val fzKey = zoomRaw
        val fzTv = TextView(act).apply {
            textSize = 15f
            setTextColor(act.getColor(R.color.mg_text))
            setPadding(0, dp(6), 0, dp(6))
            fun upd() { text = if (ZoomTools.optedOut(act, fzKey)) "Off for this site" else "Follows Accessibility" }
            upd()
            isClickable = true
            setOnClickListener { ZoomTools.setOptOut(act, fzKey, !ZoomTools.optedOut(act, fzKey)); upd() }
        }
        col.addView(fzTv)
        // 0.0.424: dark page for this site (SiteDark)
        col.addView(label("Dark page"))
        val dkTv = TextView(act).apply {
            textSize = 15f
            setTextColor(act.getColor(R.color.mg_text))
            setPadding(0, dp(6), 0, dp(6))
            text = SiteDark.label(SiteDark.mode(act, key)) + (if (UiMode.appDark(act)) "" else " (needs a dark app theme)")
            isClickable = true
            setOnClickListener { SiteDark.cycle(act, key); text = SiteDark.label(SiteDark.mode(act, key)) + (if (UiMode.appDark(act)) "" else " (needs a dark app theme)") }
        }
        col.addView(dkTv)
        // button mode (DlMode)
        col.addView(label("Download buttons"))
        val dlTv = TextView(act).apply {
            textSize = 15f
            setTextColor(act.getColor(R.color.mg_text))
            setPadding(0, dp(6), 0, dp(6))
            text = DlMode.siteLabel(act, key)
            isClickable = true
            setOnClickListener { DlMode.siteDialog(act, key) { text = DlMode.siteLabel(act, key) } }
        }
        col.addView(dlTv)
        // permissions (SitePerms)
        if (s.perms.isNotEmpty()) {
            col.addView(label("Permissions"))
            for ((raw, what) in s.perms.sortedBy { it.second }) {
                val tv = TextView(act).apply {
                    textSize = 15f
                    setTextColor(act.getColor(R.color.mg_text))
                    setPadding(0, dp(6), 0, dp(6))
                    text = what + " · " + permWord(act, raw, what)
                    isClickable = true
                }
                tv.setOnClickListener {
                    MaterialAlertDialogBuilder(act).setTitle("$key · $what")
                        .setItems(arrayOf("Allow", "Block", "Reset (ask again)")) { _, i ->
                            SitePerms.set(act, raw, what, when (i) { 0 -> true; 1 -> false; else -> null })
                            tv.text = what + " · " + permWord(act, raw, what)
                        }.show()
                }
                col.addView(tv)
            }
        }
        val sv = ScrollView(act).apply { addView(col) }
        MaterialAlertDialogBuilder(act)
            .setTitle(key)
            .setView(sv)
            .setPositiveButton("Done", null)
            .setNeutralButton("Reset this site") { _, _ ->
                MaterialAlertDialogBuilder(act).setMessage("Forget every saved setting for $key (permissions, switches, text size, button mode, dark page)?")
                    .setPositiveButton("Reset") { _, _ ->
                        for (b in bits) if ((SiteConfig.flags(key) and b) != 0) SiteConfig.toggle(key, b)
                        s.zoomRaw?.let { TextZoom.reset(act, it) } // A2: back to the default zoom
                        TextZoom.reset(act, key)
                        DlMode.setSite(act, key, -1)
                        SiteDark.set(act, key, SiteDark.SAME) // 0.0.424
                        for ((raw, what) in s.perms) SitePerms.set(act, raw, what, null)
                        Util.toast(act, "Reset. Reload the page")
                        closed()
                    }
                    .setNegativeButton("Cancel", null)
                    .setOnDismissListener { closed() }
                    .show()
            }
            .setOnDismissListener { closed() }
            .show()
    }
}
