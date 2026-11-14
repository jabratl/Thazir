package com.jabra.mgb

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.widget.NestedScrollView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import org.json.JSONObject
import java.io.File

/**
 * S10: Thazir Extensions tray. Opened from the 3-dot menu (puzzle button in the bottom row).
 *  - bottom sheet, 4-column grid of the extensions that are ON: icon (manifest icon, else a colour tile with the first letter) + 2-line name
 *  - tap = popup page of the extension (else options page, else a toast) in the extension window; the tray closes first
 *  - "Manage Extensions" = ExtListScreen (add / on-off / remove), header button does the same
 *  - Nothing is installed or changed here; it only reads ExtRegistry and opens existing screens
 */
object ExtTray {
    private const val TAG = "MGB.exttray"
    private const val COLS = 4

    fun show(act: Activity) {
        if (act.isFinishing || act.isDestroyed) return
        try {
            ExtRegistry.init(act)
            build(act)
        } catch (e: Exception) {
            Log.e(TAG, "show failed", e)
            Util.toast(act, "Could not open Extensions")
        }
    }

    private fun build(act: Activity) {
        val dlg = BottomSheetDialog(act)
        fun dp(v: Int) = Util.dp(act, v)
        fun round(color: Int, r: Int) = GradientDrawable().apply { setColor(color); cornerRadius = dp(r).toFloat() }
        val cText = act.getColor(R.color.mg_text)
        val cDim = act.getColor(R.color.mg_text_dim)
        val cCard = act.getColor(R.color.mg_surface)
        val cLine = act.getColor(R.color.mg_outline)
        val cAccent = act.getColor(R.color.mg_primary)

        val col = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(14))
        }
        col.addView(View(act).apply { background = round(cLine, 3) },
            LinearLayout.LayoutParams(dp(36), dp(4)).apply { gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = dp(12) })

        val head = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(TextView(act).apply {
            text = "Thazir Extensions"
            textSize = 17f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(cAccent)
            setPadding(dp(8), 0, 0, 0)
        }, LinearLayout.LayoutParams(0, WRAP_H, 1f))
        head.addView(ImageView(act).apply {
            setImageResource(R.drawable.ic_settings)
            setColorFilter(cText)
            scaleType = ImageView.ScaleType.CENTER
            contentDescription = "Manage extensions"
            setOnClickListener { dlg.dismiss(); ExtListScreen.open(act) }
        }, LinearLayout.LayoutParams(dp(44), dp(44)))
        col.addView(head, LinearLayout.LayoutParams(MATCH_W, dp(44)))

        val list = ordered(act, ExtRegistry.all().filter { it.enabled })
        val grid = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            background = round(cCard, 22)
            setPadding(dp(6), dp(10), dp(6), dp(10))
        }
        if (list.isEmpty()) {
            grid.addView(TextView(act).apply {
                text = if (ExtRegistry.all().isEmpty()) "No extensions yet" else "All extensions are off"
                textSize = 14f
                gravity = Gravity.CENTER
                setTextColor(cDim)
            }, LinearLayout.LayoutParams(MATCH_W, dp(96)))
        } else {
            var row: LinearLayout? = null
            val tiles = ArrayList<View>()
            list.forEachIndexed { i, e ->
                if (i % COLS == 0) {
                    row = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL }
                    grid.addView(row, LinearLayout.LayoutParams(MATCH_W, WRAP_H))
                }
                val t = tile(act, e) { dlg.dismiss(); openExt(act, e) }
                t.setOnLongClickListener { dlg.dismiss(); moveMenu(act, e); true } // S11: reorder
                tiles.add(t)
                row!!.addView(t, LinearLayout.LayoutParams(0, WRAP_H, 1f))
            }
            val rem = (COLS - list.size % COLS) % COLS // empty cells keep the last row aligned
            repeat(rem) { row!!.addView(View(act), LinearLayout.LayoutParams(0, 1, 1f)) }
            dlg.setOnShowListener {
                val ease = android.view.animation.DecelerateInterpolator(1.6f)
                tiles.forEachIndexed { i, v ->
                    v.alpha = 0f; v.scaleX = 0.85f; v.scaleY = 0.85f
                    v.animate().alpha(1f).scaleX(1f).scaleY(1f).setStartDelay(40L + i * 30L).setDuration(220).setInterpolator(ease).start()
                }
                dlg.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)?.background =
                    GradientDrawable().apply {
                        setColor(act.getColor(R.color.mg_bg))
                        val r = dp(28).toFloat()
                        cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
                    }
            }
        }
        col.addView(grid, LinearLayout.LayoutParams(MATCH_W, WRAP_H).apply { topMargin = dp(6) })

        if (ExtMenus.hasPageItems()) { // 0.0.116 (S4): menu items of extensions for the page / selected text
            col.addView(TextView(act).apply {
                text = "Page menu (extensions)"
                textSize = 15f
                gravity = Gravity.CENTER
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setTextColor(cAccent)
                background = round(cCard, 24)
                isClickable = true
                isFocusable = true
                setOnClickListener { dlg.dismiss(); ExtMenus.showPage(act) }
            }, LinearLayout.LayoutParams(MATCH_W, dp(48)).apply { topMargin = dp(12) })
        }
        col.addView(TextView(act).apply {
            text = "Manage Extensions"
            textSize = 15f
            gravity = Gravity.CENTER
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(cAccent)
            background = round(cCard, 24)
            isClickable = true
            isFocusable = true
            setOnClickListener { dlg.dismiss(); ExtListScreen.open(act) }
        }, LinearLayout.LayoutParams(MATCH_W, dp(48)).apply { topMargin = dp(12) })
        col.addView(TextView(act).apply {
            text = "Powered by Thazir"
            textSize = 11f
            gravity = Gravity.CENTER
            setTextColor(cDim)
            setPadding(0, dp(10), 0, 0)
        })

        val sv = NestedScrollView(act)
        sv.overScrollMode = View.OVER_SCROLL_NEVER
        sv.addView(col, ViewGroup.LayoutParams(MATCH_W, WRAP_H))
        dlg.setContentView(sv)
        dlg.behavior.skipCollapsed = true
        dlg.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        dlg.show()
    }

    private const val MATCH_W = ViewGroup.LayoutParams.MATCH_PARENT
    private const val WRAP_H = ViewGroup.LayoutParams.WRAP_CONTENT

    // ---------- S11: tray order (saved ids, newest installs go last) ----------
    private const val PREFS = "mgb_ext_tray"

    private fun order(act: Activity): List<String> =
        act.getSharedPreferences(PREFS, 0).getString("order", "").orEmpty().split(',').filter { it.isNotEmpty() }

    fun ordered(act: Activity, l: List<ExtRegistry.Info>): List<ExtRegistry.Info> {
        val o = order(act)
        return l.sortedBy { val i = o.indexOf(it.id); if (i < 0) Int.MAX_VALUE else i }
    }

    /** S12: Manage list drag-drop saves the full order here. */
    fun saveOrder(act: Activity, ids: List<String>) {
        act.getSharedPreferences(PREFS, 0).edit().putString("order", ids.joinToString(",")).apply()
    }

    private fun move(act: Activity, e: ExtRegistry.Info, to: Int) { // to: 0 first, -1 earlier, 1 later
        val ids = ordered(act, ExtRegistry.all().filter { it.enabled }).map { it.id }.toMutableList()
        val i = ids.indexOf(e.id)
        if (i < 0) return
        ids.removeAt(i)
        val n = if (to == 0) 0 else (i + to).coerceIn(0, ids.size)
        ids.add(n, e.id)
        act.getSharedPreferences(PREFS, 0).edit().putString("order", ids.joinToString(",")).apply()
    }

    private fun moveMenu(act: Activity, e: ExtRegistry.Info) {
        val items = arrayOf("Move to first", "Move earlier", "Move later")
        com.google.android.material.dialog.MaterialAlertDialogBuilder(act)
            .setTitle(e.name)
            .setItems(items) { _, w -> move(act, e, when (w) { 0 -> 0; 1 -> -1; else -> 1 }); show(act) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    fun openExt(act: Activity, e: ExtRegistry.Info) { // 0.0.123: public, the pinned rows of Customise menu use it
        // 0.0.112 (S2): like Chrome: popup (manifest or chrome.action.setPopup) first, else action.onClicked, else the options page
        val tab = TabsApi.activeTab()
        val v = ExtApi.actionView(e.id, tab?.id ?: -1)
        if (!v.enabled) { Util.toast(act, e.name + " is turned off for this page"); return }
        val page = v.popup
        if (page != null) {
            if (!ExtScreen.openExtPage(act, e.id, page)) Util.toast(act, "Could not open " + e.name)
            return
        }
        // 0.0.113: a stopped / never started worker is started first (its listeners are not known before it ran)
        ExtApi.actionClickedWake(e.id, tab) { sent ->
            if (!sent) {
                val opt = e.options
                if (opt == null) { EngineHost.addLog("ext button: " + e.name + " has no popup and no action.onClicked listener"); Util.toast(act, e.name + " has no popup") }
                else if (!ExtManifest.openOptions(act, e)) Util.toast(act, "Could not open " + e.name) // 0.0.432 (S7)
            }
        }
    }

    private fun tile(act: Activity, e: ExtRegistry.Info, onTap: () -> Unit): View {
        fun dp(v: Int) = Util.dp(act, v)
        val t = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(2), dp(6), dp(2), dp(6))
            isClickable = true
            isFocusable = true
            contentDescription = e.name
            setOnClickListener { onTap() }
        }
        val size = dp(48)
        val bmp = iconOf(e)
        if (bmp != null) {
            t.addView(ImageView(act).apply {
                setImageBitmap(bmp)
                scaleType = ImageView.ScaleType.FIT_CENTER
            }, LinearLayout.LayoutParams(size, size))
        } else {
            t.addView(TextView(act).apply { // no icon: colour tile from the id, first letter of the name
                text = e.name.trim().take(1).uppercase().ifEmpty { "?" }
                textSize = 20f
                gravity = Gravity.CENTER
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setTextColor(0xFFFFFFFF.toInt())
                background = GradientDrawable().apply {
                    setColor(tint(e.id))
                    cornerRadius = dp(14).toFloat()
                }
            }, LinearLayout.LayoutParams(size, size))
        }
        val n = ExtBackground.errorCount(e.id) // S13: red badge when the worker had errors
        val av = ExtApi.actionView(e.id, TabsApi.activeTab()?.id ?: -1) // 0.0.112 (S2): badge text / colour / title / enabled from chrome.action
        if (n > 0 || av.text.isNotEmpty()) {
            val first = t.getChildAt(0)
            t.removeViewAt(0)
            val fl = android.widget.FrameLayout(act)
            fl.addView(first, android.widget.FrameLayout.LayoutParams(size, size))
            if (n > 0) fl.addView(TextView(act).apply {
                text = if (n > 9) "9+" else n.toString()
                textSize = 9f
                gravity = Gravity.CENTER
                setTextColor(0xFFFFFFFF.toInt())
                background = GradientDrawable().apply { setColor(act.getColor(R.color.mg_error)); cornerRadius = dp(9).toFloat() }
                contentDescription = "$n errors"
            }, android.widget.FrameLayout.LayoutParams(dp(18), dp(18), Gravity.TOP or Gravity.END))
            if (av.text.isNotEmpty()) fl.addView(TextView(act).apply {
                text = av.text
                textSize = 9f
                maxLines = 1
                gravity = Gravity.CENTER
                setPadding(dp(4), 0, dp(4), 0)
                minWidth = dp(16)
                val lum = (0.299 * android.graphics.Color.red(av.color) + 0.587 * android.graphics.Color.green(av.color) + 0.114 * android.graphics.Color.blue(av.color)) / 255.0
                setTextColor(av.textColor ?: (if (lum > 0.6) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()))
                background = GradientDrawable().apply { setColor(av.color); cornerRadius = dp(8).toFloat() }
            }, android.widget.FrameLayout.LayoutParams(android.widget.FrameLayout.LayoutParams.WRAP_CONTENT, dp(16), Gravity.BOTTOM or Gravity.END))
            t.addView(fl, 0, LinearLayout.LayoutParams(size, size))
        }
        if (av.title.isNotEmpty()) t.contentDescription = av.title
        if (!av.enabled) t.alpha = 0.4f
        t.addView(TextView(act).apply {
            text = e.name
            textSize = 11f
            gravity = Gravity.CENTER
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            setTextColor(act.getColor(R.color.mg_text))
        }, LinearLayout.LayoutParams(MATCH_W, WRAP_H).apply { topMargin = dp(6) })
        return t
    }

    fun tint(id: String): Int {
        val h = ((id.hashCode() and 0x7fffffff) % 360).toFloat()
        return android.graphics.Color.HSVToColor(floatArrayOf(h, 0.55f, 0.72f))
    }

    /** Biggest png / jpg / webp icon from the manifest (icons, else action / browser_action default_icon). null = none usable (svg etc.). */
    fun iconOf(e: ExtRegistry.Info): Bitmap? {
        return try {
            val m = JSONObject(File(e.dir, "manifest.json").readText(Charsets.UTF_8).removePrefix("﻿"))
            var icons = m.optJSONObject("icons")
            if (icons == null) {
                val a = m.optJSONObject("action") ?: m.optJSONObject("browser_action")
                val d = a?.opt("default_icon")
                icons = if (d is JSONObject) d else if (d is String) JSONObject().put("48", d) else null
            }
            if (icons == null) return null
            val keys = icons.keys().asSequence().sortedByDescending { it.toIntOrNull() ?: 0 }.toList()
            for (k in keys) {
                val rel = icons.optString(k).trimStart('/')
                if (rel.isBlank() || rel.contains("..") || rel.endsWith(".svg", true)) continue
                val f = File(e.dir, rel)
                if (!f.isFile || f.length() > 2_000_000) continue
                val o = BitmapFactory.Options().apply { inSampleSize = 1 }
                val b = BitmapFactory.decodeFile(f.path, o)
                if (b != null) return b
            }
            null
        } catch (ex: Exception) {
            null
        }
    }
}
