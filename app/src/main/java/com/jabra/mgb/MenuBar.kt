package com.jabra.mgb

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import androidx.core.widget.NestedScrollView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog

/**
 * 0.0.335: the pinned icon bar at the bottom of the 3-dot menu (like Brave).
 *  - 5 slots. Each slot holds one action from CATALOG. Saved in prefs mgb_menu_custom key "bar" (ids joined by "|").
 *  - Hold a slot for 1.5 seconds -> small "Edit shortcut" pop up -> tap it -> picker with all actions.
 *    Picking an action that already sits in another slot swaps the two. "Reset buttons" restores DEFAULT.
 *  - Customise menu "Reset to default" clears the whole prefs file, so it also resets this bar.
 * The action code itself stays in MainActivity.showMenu (needs the tabs); here only id / label / icon.
 */
object MenuBar {
    class Entry(val id: String, val label: String, val icon: Int)

    const val SLOTS = 5
    const val HOLD_MS = 1500L // 0.0.339: was 3000
    private const val PREFS = "mgb_menu_custom"
    private const val K_BAR = "bar"

    val CATALOG = listOf(
        Entry("back", "Back", R.drawable.ic_back), Entry("forward", "Forward", R.drawable.ic_forward),
        Entry("bookmark", "Bookmark this page", R.drawable.ic_bookmark_border), Entry("ext", "Extensions", R.drawable.ic_ext),
        Entry("reload", "Reload / Stop", R.drawable.ic_reload), Entry("home", "Home", R.drawable.ic_home),
        Entry("share", "Share page", R.drawable.ic_share), Entry("downloads", "Downloads", R.drawable.ic_download),
        Entry("history", "History", R.drawable.ic_history), Entry("bookmarks", "Bookmarks", R.drawable.ic_bookmark),
        Entry("newtab", "New tab", R.drawable.ic_add), Entry("private", "New private tab", R.drawable.ic_lock),
        Entry("find", "Find on page", R.drawable.ic_search), Entry("desktop", "Desktop site", R.drawable.ic_desktop),
        Entry("settings", "Settings", R.drawable.ic_settings)
    )
    val DEFAULT = listOf("back", "forward", "bookmark", "settings", "reload") // 0.0.339: Settings in slot 4 (was Extensions); Extensions stays in the picker

    private fun sp(c: Context) = c.getSharedPreferences(PREFS, 0)

    /** the 5 saved ids; anything invalid (wrong size, unknown id, duplicate) = DEFAULT. */
    fun ids(c: Context): List<String> {
        val l = sp(c).getString(K_BAR, "").orEmpty().split("|").filter { it.isNotEmpty() }
        val ok = l.size == SLOTS && l.toSet().size == SLOTS && l.all { id -> CATALOG.any { it.id == id } }
        return if (ok) l else DEFAULT
    }

    fun entry(id: String): Entry = CATALOG.firstOrNull { it.id == id } ?: CATALOG[0]

    /** puts [id] into [slot]; when [id] is already in another slot the two swap places. */
    fun set(c: Context, slot: Int, id: String) {
        val l = ids(c).toMutableList()
        if (slot !in l.indices) return
        val old = l.indexOf(id)
        if (old >= 0) l[old] = l[slot]
        l[slot] = id
        sp(c).edit().putString(K_BAR, l.joinToString("|")).apply()
    }

    fun resetBar(c: Context) { sp(c).edit().remove(K_BAR).apply() }

    /** hold [v] for 1.5 s (not a normal long press) -> "Edit shortcut" pop up. A short tap keeps working. [onChanged] runs after a new action was picked. */
    fun attachEdit(v: View, slot: Int, onChanged: () -> Unit) {
        val h = Handler(Looper.getMainLooper())
        val slop = ViewConfiguration.get(v.context).scaledTouchSlop
        var fired = false
        var dx = 0f
        var dy = 0f
        val run = Runnable {
            fired = true
            v.isPressed = false
            v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            popup(v, slot, onChanged)
        }
        v.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { fired = false; dx = e.rawX; dy = e.rawY; h.postDelayed(run, HOLD_MS); false }
                MotionEvent.ACTION_MOVE -> {
                    if (Math.hypot((e.rawX - dx).toDouble(), (e.rawY - dy).toDouble()) > slop) h.removeCallbacks(run)
                    false
                }
                MotionEvent.ACTION_UP -> { h.removeCallbacks(run); val f = fired; fired = false; if (f) v.isPressed = false; f } // consumed = no click after the hold
                MotionEvent.ACTION_CANCEL -> { h.removeCallbacks(run); fired = false; false }
                else -> false
            }
        }
    }

    private fun popup(anchor: View, slot: Int, onChanged: () -> Unit) {
        val ctx = anchor.context
        fun dp(n: Int) = Util.dp(ctx, n)
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), 0, dp(18), 0)
            background = GradientDrawable().apply {
                setColor(ctx.getColor(R.color.mg_surface)); cornerRadius = dp(16).toFloat(); setStroke(dp(1), ctx.getColor(R.color.mg_outline))
            }
            elevation = dp(6).toFloat()
            minimumHeight = dp(48)
        }
        row.addView(ImageView(ctx).apply { setImageResource(R.drawable.ic_edit); setColorFilter(ctx.getColor(R.color.mg_primary)) }, LinearLayout.LayoutParams(dp(20), dp(20)).apply { marginEnd = dp(10) })
        row.addView(TextView(ctx).apply { text = "Edit shortcut"; textSize = 15f; setTextColor(ctx.getColor(R.color.mg_text)); maxLines = 1 })
        val w = PopupWindow(row, ViewGroup.LayoutParams.WRAP_CONTENT, dp(48), true)
        w.isOutsideTouchable = true
        w.elevation = dp(6).toFloat()
        row.setOnClickListener { w.dismiss(); picker(ctx, slot, onChanged) }
        row.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        w.showAsDropDown(anchor, (anchor.width - row.measuredWidth) / 2, -(anchor.height + dp(48) + dp(8)))
    }

    private fun picker(ctx: Context, slot: Int, onChanged: () -> Unit) {
        val dlg = BottomSheetDialog(ctx)
        fun dp(n: Int) = Util.dp(ctx, n)
        val cText = ctx.getColor(R.color.mg_text)
        val cDim = ctx.getColor(R.color.mg_text_dim)
        val cAccent = ctx.getColor(R.color.mg_primary)
        val ripple = ctx.obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackground)).let { val d = it.getDrawable(0); it.recycle(); d }
        val cur = ids(ctx)[slot]
        val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(16), dp(14), dp(18)) }
        col.addView(TextView(ctx).apply { text = "Edit shortcut"; textSize = 20f; typeface = Typeface.DEFAULT_BOLD; setTextColor(cText) })
        col.addView(TextView(ctx).apply { text = "Button ${slot + 1} of $SLOTS: choose what it does"; textSize = 12f; setTextColor(cDim); setPadding(0, dp(2), 0, dp(10)) })
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply { setColor(ctx.getColor(R.color.mg_surface)); cornerRadius = dp(20).toFloat(); setStroke(dp(1), ctx.getColor(R.color.mg_outline)) }
            setPadding(0, dp(6), 0, dp(6))
        }
        fun rowOf(icon: Int, label: String, current: Boolean, onTap: () -> Unit) = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), 0, dp(16), 0)
            background = ripple?.constantState?.newDrawable()
            isClickable = true
            setOnClickListener { dlg.dismiss(); onTap() }
            addView(ImageView(ctx).apply { setImageResource(icon); setColorFilter(if (current) cAccent else cDim) }, LinearLayout.LayoutParams(dp(22), dp(22)).apply { marginEnd = dp(16) })
            addView(TextView(ctx).apply { text = label; textSize = 16f; setTextColor(cText); maxLines = 1 }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            if (current) addView(TextView(ctx).apply {
                text = "Current"; textSize = 12f; setTextColor(cAccent)
                background = GradientDrawable().apply { setColor(ColorUtils.setAlphaComponent(cAccent, 0x26)); cornerRadius = dp(12).toFloat() }
                setPadding(dp(10), dp(3), dp(10), dp(3))
            })
        }
        for (e in CATALOG) card.addView(rowOf(e.icon, e.label, e.id == cur) { set(ctx, slot, e.id); onChanged() }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)))
        col.addView(card, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val reset = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply { setColor(ctx.getColor(R.color.mg_surface)); cornerRadius = dp(20).toFloat(); setStroke(dp(1), ctx.getColor(R.color.mg_outline)) }
            setPadding(0, dp(6), 0, dp(6))
        }
        reset.addView(rowOf(R.drawable.ic_reload, "Reset all 5 buttons", false) { resetBar(ctx); onChanged() }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)))
        col.addView(reset, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) })
        val sv = NestedScrollView(ctx)
        sv.overScrollMode = View.OVER_SCROLL_NEVER
        sv.addView(col, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        dlg.setContentView(sv)
        dlg.setOnShowListener {
            dlg.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)?.background = GradientDrawable().apply {
                setColor(ctx.getColor(R.color.mg_bg)); val r = dp(28).toFloat(); cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
            }
        }
        dlg.behavior.skipCollapsed = true
        dlg.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        dlg.show()
    }
}
