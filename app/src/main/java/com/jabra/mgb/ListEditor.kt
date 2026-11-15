package com.jabra.mgb

import android.app.Activity
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import androidx.core.widget.NestedScrollView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog

/**
 * 0.0.461: one list sheet with "select and delete", used by Recently closed tabs (Backup.closedTabs) and meant for the home page "Recently visited".
 *  - tap a row = open it (normal mode) or tick / untick it (select mode)
 *  - hold a row, or tap "Select" = select mode: tick rows, then Delete (n) / All / Done
 *  - Clear all (only when [onClear] is given) empties the whole list at once
 * The callbacks get the index in the list that was passed in, so the caller keeps its own list and maps the index to its own item.
 */
object ListEditor {
    class Row(val title: String, val sub: String)

    private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

    fun show(
        act: Activity, title: String, rows: List<Row>, emptyMsg: String,
        onOpen: (Int) -> Unit, onDelete: (List<Int>) -> Unit, onClear: (() -> Unit)? = null
    ) {
        if (rows.isEmpty()) { Util.toast(act, emptyMsg); return }
        fun dp(n: Int) = Util.dp(act, n)
        val cText = act.getColor(R.color.mg_text)
        val cDim = act.getColor(R.color.mg_text_dim)
        val cAccent = act.getColor(R.color.mg_primary)
        val cError = act.getColor(R.color.mg_error)
        val ripple = act.obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackground)).let { val d = it.getDrawable(0); it.recycle(); d }
        val items = rows.withIndex().map { it.index to it.value }.toMutableList()
        val sel = HashSet<Int>()
        var selecting = false
        val dlg = BottomSheetDialog(act)

        val col = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(16), dp(14), dp(12)) }
        val head = TextView(act).apply { text = title; textSize = 20f; typeface = Typeface.DEFAULT_BOLD; setTextColor(cText) }
        val sub = TextView(act).apply { textSize = 12f; setTextColor(cDim); setPadding(0, dp(2), 0, dp(10)) }
        val card = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply { setColor(act.getColor(R.color.mg_surface)); cornerRadius = dp(20).toFloat(); setStroke(dp(1), act.getColor(R.color.mg_outline)) }
            setPadding(0, dp(6), 0, dp(6))
        }
        val bar = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END or Gravity.CENTER_VERTICAL; setPadding(0, dp(8), 0, 0) }
        col.addView(head)
        col.addView(sub)
        col.addView(card, LinearLayout.LayoutParams(MATCH, WRAP))
        col.addView(bar, LinearLayout.LayoutParams(MATCH, WRAP))
        val sv = NestedScrollView(act)
        sv.overScrollMode = View.OVER_SCROLL_NEVER
        sv.addView(col, ViewGroup.LayoutParams(MATCH, WRAP))

        fun button(text: String, color: Int, onTap: () -> Unit) = TextView(act).apply {
            this.text = text; textSize = 14f; typeface = Typeface.DEFAULT_BOLD; setTextColor(color)
            gravity = Gravity.CENTER
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = ripple?.constantState?.newDrawable()
            isClickable = true
            setOnClickListener { onTap() }
        }

        lateinit var refresh: () -> Unit
        fun toggle(oi: Int) { if (!sel.add(oi)) sel.remove(oi); refresh() }

        refresh = {
            sub.text = if (selecting) "${sel.size} selected" else "${items.size} items. Tap to open, hold to select."
            card.removeAllViews()
            for ((oi, row) in items) {
                val r = LinearLayout(act).apply {
                    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(16), dp(8), dp(16), dp(8))
                    minimumHeight = dp(56)
                    background = ripple?.constantState?.newDrawable()
                    isClickable = true
                    setOnClickListener { if (selecting) toggle(oi) else { dlg.dismiss(); onOpen(oi) } }
                    setOnLongClickListener {
                        if (!selecting) { selecting = true; sel.add(oi); performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS); refresh() } else toggle(oi)
                        true
                    }
                }
                if (selecting) r.addView(CheckBox(act).apply { isChecked = oi in sel; isClickable = false; isFocusable = false; buttonTintList = android.content.res.ColorStateList.valueOf(cAccent) },
                    LinearLayout.LayoutParams(WRAP, WRAP).apply { marginEnd = dp(8) })
                val t = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
                t.addView(TextView(act).apply { text = row.title.ifBlank { row.sub }; textSize = 15f; setTextColor(cText); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
                if (row.sub.isNotBlank() && row.title.isNotBlank()) t.addView(TextView(act).apply { text = row.sub; textSize = 12f; setTextColor(cDim); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
                r.addView(t, LinearLayout.LayoutParams(0, WRAP, 1f))
                card.addView(r, LinearLayout.LayoutParams(MATCH, WRAP))
            }
            bar.removeAllViews()
            if (selecting) {
                bar.addView(button("Delete (${sel.size})", if (sel.isEmpty()) ColorUtils.setAlphaComponent(cError, 0x66) else cError) {
                    if (sel.isNotEmpty()) {
                        onDelete(sel.sorted())
                        items.removeAll { it.first in sel }
                        sel.clear(); selecting = false
                        if (items.isEmpty()) dlg.dismiss() else refresh()
                    }
                })
                bar.addView(button("All", cAccent) { if (sel.size == items.size) sel.clear() else { sel.clear(); sel.addAll(items.map { it.first }) }; refresh() })
                bar.addView(button("Done", cDim) { selecting = false; sel.clear(); refresh() })
            } else {
                bar.addView(button("Select", cAccent) { selecting = true; refresh() })
                if (onClear != null) bar.addView(button("Clear all", cError) { onClear(); dlg.dismiss() })
            }
        }
        refresh()
        dlg.setContentView(sv)
        dlg.setOnShowListener {
            dlg.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)?.background = GradientDrawable().apply {
                setColor(act.getColor(R.color.mg_bg)); val r = dp(28).toFloat(); cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
            }
        }
        dlg.behavior.skipCollapsed = true
        dlg.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        dlg.show()
    }
}
