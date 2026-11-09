package com.jabra.mgb

import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import com.google.android.material.bottomsheet.BottomSheetDialog

/**
 * 0.0.348: UI parts for the Bookmarks screen (the look of the reference video):
 * breadcrumb path, sort button + popup, 3-dot popup menu with icons, card rows, folder tile,
 * "Add site to folder", "Manage start page" sheet, "Lock data vault".
 * Colours only from R.color.mg_*. Used by BookmarksScreen (hooks only).
 */
object BmUi {
    class Entry(val label: String, val icon: Int, val selected: Boolean = false, val onClick: () -> Unit)

    private fun dp(a: Activity, v: Int) = Util.dp(a, v)
    private val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    private val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

    fun card(a: Activity, fill: Int, r: Int, stroke: Int = a.getColor(R.color.mg_outline)): GradientDrawable =
        GradientDrawable().apply { setColor(fill); cornerRadius = dp(a, r).toFloat(); setStroke(dp(a, 1), stroke) }

    /** Rounded row background with a ripple that follows the corners. on = selected row (violet tint + violet outline). */
    fun rippleCard(a: Activity, on: Boolean = false): android.graphics.drawable.Drawable {
        val prim = a.getColor(R.color.mg_primary)
        val fill = if (on) ColorUtils.blendARGB(a.getColor(R.color.mg_surface), prim, 0.16f) else a.getColor(R.color.mg_surface)
        val base = card(a, fill, 16, if (on) prim else a.getColor(R.color.mg_outline))
        val mask = GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = dp(a, 16).toFloat() }
        return RippleDrawable(ColorStateList.valueOf(ColorUtils.setAlphaComponent(prim, 0x30)), base, mask)
    }

    fun cardParams(a: Activity) = LinearLayout.LayoutParams(MATCH, WRAP).apply {
        setMargins(dp(a, 16), dp(a, 4), dp(a, 16), dp(a, 4))
    }

    /** Violet-tinted rounded tile with the two-tone folder icon. */
    fun folderTile(a: Activity): View {
        val prim = a.getColor(R.color.mg_primary)
        return ImageView(a).apply {
            setImageResource(R.drawable.ic_bm_folder)
            setColorFilter(prim)
            setPadding(dp(a, 7), dp(a, 7), dp(a, 7), dp(a, 7))
            background = GradientDrawable().apply {
                setColor(ColorUtils.setAlphaComponent(prim, 0x26))
                cornerRadius = dp(a, 12).toFloat()
            }
        }
    }

    /** Small rounded count badge. */
    fun countPill(a: Activity, n: Int): View = TextView(a).apply {
        text = n.toString()
        textSize = 12f
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        setTextColor(a.getColor(R.color.mg_text_dim))
        gravity = Gravity.CENTER
        minWidth = dp(a, 26)
        setPadding(dp(a, 8), dp(a, 3), dp(a, 8), dp(a, 3))
        background = GradientDrawable().apply { setColor(a.getColor(R.color.mg_surface_variant)); cornerRadius = dp(a, 20).toFloat() }
    }

    fun chevron(a: Activity, size: Int = 20): View = ImageView(a).apply {
        setImageResource(R.drawable.ic_chevron)
        setColorFilter(a.getColor(R.color.mg_text_dim))
    }.also { it.layoutParams = LinearLayout.LayoutParams(dp(a, size), dp(a, size)) }

    // ---------- popup menu (anchored, rounded, icons on the right) ----------

    fun menu(a: Activity, anchor: View, entries: List<Entry>) {
        if (a.isFinishing || a.isDestroyed) return
        val col = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(a, 6), 0, dp(a, 6))
        }
        var pw: PopupWindow? = null
        for (e in entries) {
            val row = LinearLayout(a).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(a, 46)
                setPadding(dp(a, 16), 0, dp(a, 16), 0)
                setOnClickListener { pw?.dismiss(); e.onClick() }
            }
            val tc = if (e.selected) a.getColor(R.color.mg_primary) else a.getColor(R.color.mg_text)
            row.addView(TextView(a).apply {
                text = e.label
                textSize = 14f
                setTextColor(tc)
                if (e.selected) setTypeface(typeface, android.graphics.Typeface.BOLD)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }, LinearLayout.LayoutParams(0, WRAP, 1f))
            if (e.icon != 0) row.addView(ImageView(a).apply {
                setImageResource(e.icon)
                setColorFilter(if (e.selected) a.getColor(R.color.mg_primary) else a.getColor(R.color.mg_text_dim))
            }, LinearLayout.LayoutParams(dp(a, 20), dp(a, 20)).apply { marginStart = dp(a, 20) })
            col.addView(row, LinearLayout.LayoutParams(MATCH, WRAP))
        }
        val sv = ScrollView(a).apply { addView(col, ViewGroup.LayoutParams(MATCH, WRAP)) }
        val maxH = (a.resources.displayMetrics.heightPixels * 0.7f).toInt()
        sv.measure(View.MeasureSpec.makeMeasureSpec(a.resources.displayMetrics.widthPixels - dp(a, 32), View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        val w = maxOf(sv.measuredWidth, dp(a, 210))
        val h = minOf(sv.measuredHeight, maxH)
        pw = PopupWindow(sv, w, h, true).apply {
            setBackgroundDrawable(card(a, a.getColor(R.color.mg_surface_variant), 16))
            elevation = dp(a, 10).toFloat()
            isOutsideTouchable = true
        }
        pw?.showAsDropDown(anchor, 0, -dp(a, 4), Gravity.END)
    }

    // ---------- section bar: breadcrumb (or label) + sort button ----------

    /** folder == null: "BOOKMARKS" label. Else: home chip > chip > chip (last = violet). results = search is on. */
    fun bar(a: Activity, folder: String?, results: Boolean, goTo: (String?) -> Unit, onSortChanged: () -> Unit): View {
        val row = LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(a, 16), dp(a, 4), dp(a, 8), dp(a, 4))
        }
        if (folder != null && !results) {
            val hs = HorizontalScrollView(a).apply { isHorizontalScrollBarEnabled = false; overScrollMode = View.OVER_SCROLL_NEVER }
            val line = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            line.addView(ImageView(a).apply {
                setImageResource(R.drawable.ic_bm_home)
                setColorFilter(a.getColor(R.color.mg_text))
                setPadding(dp(a, 7), dp(a, 7), dp(a, 7), dp(a, 7))
                background = GradientDrawable().apply { setColor(a.getColor(R.color.mg_surface_variant)); cornerRadius = dp(a, 16).toFloat() }
                contentDescription = "All bookmarks"
                setOnClickListener { goTo(null) }
            }, LinearLayout.LayoutParams(dp(a, 32), dp(a, 32)))
            val parts = folder.split('/')
            var path = ""
            for ((i, p) in parts.withIndex()) {
                path = if (path.isEmpty()) p else "$path/$p"
                val here = path
                val last = i == parts.size - 1
                line.addView(ImageView(a).apply {
                    setImageResource(R.drawable.ic_chevron)
                    setColorFilter(a.getColor(R.color.mg_text_dim))
                }, LinearLayout.LayoutParams(dp(a, 18), dp(a, 18)).apply { marginStart = dp(a, 2); marginEnd = dp(a, 2) })
                line.addView(TextView(a).apply {
                    text = p
                    textSize = 12f
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                    maxWidth = dp(a, 170)
                    gravity = Gravity.CENTER
                    val prim = a.getColor(R.color.mg_primary)
                    setTextColor(if (last) prim else a.getColor(R.color.mg_text))
                    if (last) setTypeface(typeface, android.graphics.Typeface.BOLD)
                    setPadding(dp(a, 12), 0, dp(a, 12), 0)
                    background = GradientDrawable().apply {
                        setColor(if (last) ColorUtils.setAlphaComponent(prim, 0x26) else a.getColor(R.color.mg_surface_variant))
                        cornerRadius = dp(a, 16).toFloat()
                    }
                    setOnClickListener { goTo(here) }
                }, LinearLayout.LayoutParams(WRAP, dp(a, 32)))
            }
            hs.addView(line, ViewGroup.LayoutParams(WRAP, WRAP))
            hs.post { hs.fullScroll(View.FOCUS_RIGHT) }
            row.addView(hs, LinearLayout.LayoutParams(0, WRAP, 1f))
        } else {
            row.addView(TextView(a).apply {
                text = if (results) "RESULTS" else "BOOKMARKS"
                textSize = 11f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                letterSpacing = 0.08f
                setTextColor(a.getColor(R.color.mg_primary))
            }, LinearLayout.LayoutParams(0, WRAP, 1f))
        }
        val sort = ImageView(a).apply {
            setImageResource(R.drawable.ic_bm_sort)
            setColorFilter(a.getColor(R.color.mg_text_dim))
            setPadding(dp(a, 10), dp(a, 10), dp(a, 10), dp(a, 10))
            contentDescription = "Sort"
            setOnClickListener {
                menu(a, this, BmSort.MENU.map { (name, m) -> Entry(name, 0, BmSort.mode == m) { BmSort.mode = m; onSortChanged() } })
            }
        }
        row.addView(sort, LinearLayout.LayoutParams(dp(a, 44), dp(a, 44)).apply { marginStart = dp(a, 4) })
        return row
    }

    // ---------- actions of the 3-dot menu ----------

    fun lockVault(a: Activity) {
        if (!VaultStore.isUnlocked()) { Util.toast(a, "Data vault is already locked"); return }
        val app = a.applicationContext
        Thread { VaultStore.lock(app); a.runOnUiThread { Util.toast(a, "Data vault locked") } }.start()
    }

    /** "Add site to folder": title + address, goes into the open folder, or asks for one. */
    fun addSite(a: Activity, openFolder: String?, done: () -> Unit) {
        val box = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(a, 24), dp(a, 8), dp(a, 24), 0) }
        val t = EditText(a).apply { hint = "Title (optional)"; setSingleLine() }
        val u = EditText(a).apply { hint = "Address, e.g. example.com"; setSingleLine(); inputType = android.text.InputType.TYPE_TEXT_VARIATION_URI or android.text.InputType.TYPE_CLASS_TEXT }
        box.addView(t, LinearLayout.LayoutParams(MATCH, WRAP))
        box.addView(u, LinearLayout.LayoutParams(MATCH, WRAP))
        com.google.android.material.dialog.MaterialAlertDialogBuilder(a)
            .setTitle(if (openFolder == null) "Add site" else "Add site to " + Bookmarks.shortName(openFolder))
            .setView(box)
            .setPositiveButton("Add") { _, _ ->
                var url = u.text.toString().trim()
                if (url.isEmpty()) { Util.toast(a, "Address is empty"); return@setPositiveButton }
                if (!url.contains("://")) url = "https://$url"
                val title = t.text.toString().trim()
                fun put(f: String) {
                    Bookmarks.addBookmark(title, url)
                    if (f.isNotEmpty()) Bookmarks.setFolder(url, f)
                    Util.toast(a, if (f.isEmpty()) "Site added" else "Added to " + Bookmarks.shortName(f))
                    done()
                }
                if (openFolder != null) put(openFolder) else BookmarkFolderPicker.pick(a, "Add to folder", "") { f -> put(f) }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** "Manage start page": tick the bookmarks / top folders that show on the home screen (Bookmarks.addHome / removeHome). */
    fun manageStart(a: Activity, tile: (String) -> View) {
        val d = BottomSheetDialog(a)
        val root = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(a, 14), dp(a, 16), dp(a, 14), dp(a, 18))
            background = GradientDrawable().apply {
                setColor(a.getColor(R.color.mg_bg))
                val r = dp(a, 24).toFloat(); cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
            }
        }
        root.addView(TextView(a).apply { text = "Manage start page"; textSize = 20f; setTypeface(typeface, android.graphics.Typeface.BOLD); setTextColor(a.getColor(R.color.mg_text)) })
        root.addView(TextView(a).apply { text = "Tick what shows on the home screen"; textSize = 12f; setTextColor(a.getColor(R.color.mg_text_dim)); setPadding(0, dp(a, 2), 0, dp(a, 10)) })
        val list = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        fun mark(on: Boolean) = ImageView(a).apply {
            if (on) {
                setImageResource(R.drawable.ic_check)
                setColorFilter(a.getColor(R.color.mg_on_primary))
                setPadding(dp(a, 3), dp(a, 3), dp(a, 3), dp(a, 3))
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(a.getColor(R.color.mg_primary)) }
            } else background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setStroke(dp(a, 2), a.getColor(R.color.mg_outline)) }
        }
        fun line(icon: View, name: String, on: Boolean, flip: () -> Unit): View {
            var state = on
            val r = LinearLayout(a).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(a, 56)
                setPadding(dp(a, 12), dp(a, 8), dp(a, 14), dp(a, 8))
                background = rippleCard(a)
            }
            r.addView(icon, LinearLayout.LayoutParams(dp(a, 36), dp(a, 36)).apply { marginEnd = dp(a, 12) })
            r.addView(TextView(a).apply { text = name; textSize = 15f; maxLines = 1; ellipsize = TextUtils.TruncateAt.END; setTextColor(a.getColor(R.color.mg_text)) }, LinearLayout.LayoutParams(0, WRAP, 1f))
            var m = mark(state)
            r.addView(m, LinearLayout.LayoutParams(dp(a, 22), dp(a, 22)))
            r.setOnClickListener {
                flip(); state = !state
                r.removeView(m); m = mark(state); r.addView(m, LinearLayout.LayoutParams(dp(a, 22), dp(a, 22)))
            }
            return r.also { it.layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(a, 3); bottomMargin = dp(a, 3) } }
        }
        for (f in Bookmarks.childFolders("")) {
            list.addView(line(folderTile(a), f, Bookmarks.home().any { it.folder == f }) {
                if (Bookmarks.home().any { it.folder == f }) Bookmarks.removeHomeFolder(f) else Bookmarks.addHomeFolder(f)
            })
        }
        for (b in Bookmarks.bookmarks()) {
            list.addView(line(tile(b.url), b.title.ifBlank { Util.host(b.url) }, Bookmarks.isHome(b.url)) {
                if (Bookmarks.isHome(b.url)) Bookmarks.removeHome(b.url) else Bookmarks.addHome(b.title, b.url)
            })
        }
        if (list.childCount == 0) list.addView(TextView(a).apply { text = "No bookmarks yet"; setTextColor(a.getColor(R.color.mg_text_dim)); setPadding(0, dp(a, 24), 0, dp(a, 24)); gravity = Gravity.CENTER })
        root.addView(ScrollView(a).apply { addView(list, ViewGroup.LayoutParams(MATCH, WRAP)) }, LinearLayout.LayoutParams(MATCH, 0, 1f))
        d.setContentView(root)
        (root.parent as? View)?.setBackgroundColor(Color.TRANSPARENT)
        d.setOnShowListener { d.behavior.state = com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED; d.behavior.skipCollapsed = true }
        d.show()
    }
}
