package com.jabra.mgb

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
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
 * 0.0.456: the Hub = the 3 x 3 popup behind the Hub button (Appearance > Hub button).
 *  - floating card, same width as the bar, above a bottom bar / below a top bar.
 *  - a tile = 52 dp chip (radius 16) + 24 dp icon + 12 sp label. Tap = close, then run. A disabled tile is dim (alpha .38) and does nothing.
 *  - active (accent) tile = chip in colorPrimary (Privacy Guard while it guards the page).
 * The actions themselves live in MainActivity.hubRun (needs the tabs); here only look + layout.
 * 0.0.458 (S3): the 9 tiles are customisable. Slots saved in mgb_ui key ap_hub_slots (ids joined by "|", so Backup carries it).
 *  Hold a tile 1.5 s -> picker with all actions (an action already used in another slot swaps places). Invalid / missing = DEFAULT.
 * 0.0.459 (S4): short enter animation (skipped with Reduce motion) + press feedback on a tile.
 */
object HubSheet {
    private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

    /** [state] 0 = normal, 1 = accent (on). */
    class Tile(val id: String, val label: String, val icon: Int, val enabled: Boolean = true, val state: Int = 0) { var slot = -1 } // 0.0.463: slot = position in the saved 9 (tiles can be left out, the hold-to-edit still edits the right slot)

    /** 0.0.458: one action the Hub can hold. The code behind an id lives in MainActivity.hubTile / hubRun. */
    class Entry(val id: String, val label: String, val icon: Int)

    const val SLOTS = 9
    const val HOLD_MS = 1500L // same hold time as MenuBar
    const val KEY = "ap_hub_slots"

    val CATALOG = listOf(
        Entry("home", "Home action", R.drawable.ic_home), Entry("newtab", "New tab", R.drawable.ic_add),
        Entry("private", "New Incognito tab", R.drawable.ic_incognito), Entry("back", "Back", R.drawable.ic_back),
        Entry("forward", "Forward", R.drawable.ic_forward), Entry("reload", "Reload / Stop", R.drawable.ic_reload),
        Entry("engine", "Search engine", R.drawable.ic_search), Entry("shield", "Privacy Guard", R.drawable.ic_shield),
        Entry("bookmarks", "Bookmarks", R.drawable.ic_bookmark), Entry("history", "History", R.drawable.ic_history),
        Entry("downloads", "Downloads", R.drawable.ic_download), Entry("ext", "Extensions", R.drawable.ic_ext),
        Entry("share", "Share page", R.drawable.ic_share), Entry("find", "Find on page", R.drawable.ic_search),
        Entry("desktop", "Desktop site", R.drawable.ic_desktop), Entry("settings", "Settings", R.drawable.ic_settings),
        Entry("bookmark", "Bookmark this page", R.drawable.ic_bookmark_border)
    )
    val DEFAULT = listOf("home", "newtab", "private", "bookmarks", "history", "downloads", "ext", "engine", "find") // 0.0.463: back / forward / reload live in the 3-dot menu + edge swipe, the shield sits in the address field: no duplicates

    private fun sp(c: Context) = c.getSharedPreferences("mgb_ui", Context.MODE_PRIVATE)

    /** the 9 saved ids; anything invalid (wrong size, unknown id, duplicate) = DEFAULT. */
    fun ids(c: Context): List<String> {
        val l = sp(c).getString(KEY, "").orEmpty().split("|").filter { it.isNotEmpty() }
        val ok = l.size == SLOTS && l.toSet().size == SLOTS && l.all { id -> CATALOG.any { it.id == id } }
        return if (ok) l else DEFAULT
    }

    fun isCustom(c: Context): Boolean = ids(c) != DEFAULT

    /** puts [id] into [slot]; when [id] is already in another slot the two swap places. */
    fun set(c: Context, slot: Int, id: String) {
        val l = ids(c).toMutableList()
        if (slot !in l.indices) return
        val old = l.indexOf(id)
        if (old >= 0) l[old] = l[slot]
        l[slot] = id
        sp(c).edit().putString(KEY, l.joinToString("|")).apply()
    }

    fun reset(c: Context) { sp(c).edit().remove(KEY).apply() }

    private var current: PopupWindow? = null

    fun show(act: Activity, bar: View, tiles: List<Tile>, run: (String) -> Unit, onEdited: () -> Unit = { }) {
        try { current?.dismiss() } catch (_: Exception) { }
        val cText = act.getColor(R.color.mg_text)
        val cDim = act.getColor(R.color.mg_text_dim)
        val cPrimary = act.getColor(R.color.mg_primary)
        val cOnPrimary = act.getColor(R.color.mg_on_primary)

        val card = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Util.dp(act, 12), Util.dp(act, 12), Util.dp(act, 12), Util.dp(act, 8))
            background = GradientDrawable().apply {
                setColor(act.getColor(R.color.mg_surface))
                cornerRadius = Util.dp(act, 22).toFloat()
                setStroke(Util.dp(act, 1), act.getColor(R.color.mg_outline))
            }
            elevation = Util.dp(act, 4).toFloat()
        }
        val pop = PopupWindow(card, bar.width.coerceAtLeast(Util.dp(act, 240)), WRAP, true).apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            isOutsideTouchable = true
            elevation = Util.dp(act, 4).toFloat()
        }
        val ripple = TypedValue()
        val hasRipple = act.theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, ripple, true)

        val rows = (tiles.size + 2) / 3
        for (r in 0 until rows) {
            val row = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; weightSum = 3f }
            for (c in 0 until 3) {
                val t = tiles.getOrNull(r * 3 + c)
                val cell = LinearLayout(act).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER_HORIZONTAL
                    setPadding(0, Util.dp(act, 4), 0, Util.dp(act, 6))
                }
                if (t != null) {
                    val on = t.state == 1
                    val chip = ImageView(act).apply { // 0.0.463: minimal: plain icon, no chip behind it; active = accent icon
                        setImageResource(t.icon)
                        setColorFilter(if (on) cPrimary else cText)
                        scaleType = ImageView.ScaleType.CENTER_INSIDE
                        setPadding(Util.dp(act, 12), Util.dp(act, 12), Util.dp(act, 12), Util.dp(act, 12))
                    }
                    cell.addView(chip, LinearLayout.LayoutParams(Util.dp(act, 48), Util.dp(act, 48)))
                    cell.addView(TextView(act).apply {
                        text = t.label
                        textSize = 11.5f
                        setTextColor(if (on) cPrimary else cDim)
                        gravity = Gravity.CENTER
                        setSingleLine(true)
                        setPadding(0, 0, 0, Util.dp(act, 2))
                    }, LinearLayout.LayoutParams(MATCH, WRAP))
                    cell.isClickable = true
                    cell.contentDescription = t.label
                    if (hasRipple) cell.setBackgroundResource(ripple.resourceId)
                    cell.alpha = if (t.enabled) 1f else 0.38f
                    cell.setOnClickListener {
                        if (!t.enabled) return@setOnClickListener
                        it.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
                        pop.dismiss()
                        bar.post { try { run(t.id) } catch (_: Exception) { } }
                    }
                    attachHold(act, cell, chip, if (t.slot >= 0) t.slot else r * 3 + c, pop, bar, onEdited)
                }
                row.addView(cell, LinearLayout.LayoutParams(0, WRAP, 1f))
            }
            card.addView(row, LinearLayout.LayoutParams(MATCH, WRAP))
        }

        card.measure(
            View.MeasureSpec.makeMeasureSpec(pop.width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val loc = IntArray(2)
        bar.getLocationOnScreen(loc)
        val gap = Util.dp(act, 8)
        val y = if (AppearancePrefs.barTop(act)) loc[1] + bar.height + gap else loc[1] - card.measuredHeight - gap
        try {
            pop.showAtLocation(bar, Gravity.NO_GRAVITY, loc[0], y.coerceAtLeast(0))
            if (!TabPrefs.reduce(act)) { // 0.0.459: short fade + slide from the bar side
                card.alpha = 0f
                card.translationY = (if (AppearancePrefs.barTop(act)) -1 else 1) * Util.dp(act, 8).toFloat()
                card.animate().alpha(1f).translationY(0f).setDuration(140).start()
            }
            current = pop
            pop.setOnDismissListener { if (current === pop) current = null }
        } catch (_: Exception) { }
    }

    /** 0.0.458: hold a tile 1.5 s -> picker (same pattern as MenuBar.attachEdit). A short tap keeps working. 0.0.459: the chip dips a little while pressed. */
    private fun attachHold(act: Activity, cell: View, chip: View, slot: Int, pop: PopupWindow, bar: View, onEdited: () -> Unit) {
        val h = Handler(Looper.getMainLooper())
        val slop = ViewConfiguration.get(act).scaledTouchSlop
        val calm = TabPrefs.reduce(act)
        var fired = false
        var dx = 0f
        var dy = 0f
        val run = Runnable {
            fired = true
            cell.isPressed = false
            cell.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            pop.dismiss()
            bar.post { try { picker(act, slot, onEdited) } catch (_: Exception) { } }
        }
        fun dip(down: Boolean) { if (!calm) chip.animate().scaleX(if (down) 0.92f else 1f).scaleY(if (down) 0.92f else 1f).setDuration(80).start() }
        cell.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { fired = false; dx = e.rawX; dy = e.rawY; h.postDelayed(run, HOLD_MS); dip(true); false }
                MotionEvent.ACTION_MOVE -> {
                    if (Math.hypot((e.rawX - dx).toDouble(), (e.rawY - dy).toDouble()) > slop) { h.removeCallbacks(run); dip(false) }
                    false
                }
                MotionEvent.ACTION_UP -> { h.removeCallbacks(run); dip(false); val f = fired; fired = false; f } // consumed = no click after the hold
                MotionEvent.ACTION_CANCEL -> { h.removeCallbacks(run); dip(false); fired = false; false }
                else -> false
            }
        }
    }

    /** 0.0.458: choose the action of tile [slot] (1..9). Picking an action used in another slot swaps the two. */
    private fun picker(ctx: Activity, slot: Int, onEdited: () -> Unit) {
        val dlg = BottomSheetDialog(ctx)
        fun dp(n: Int) = Util.dp(ctx, n)
        val cText = ctx.getColor(R.color.mg_text)
        val cDim = ctx.getColor(R.color.mg_text_dim)
        val cAccent = ctx.getColor(R.color.mg_primary)
        val ripple = ctx.obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackground)).let { val d = it.getDrawable(0); it.recycle(); d }
        val cur = ids(ctx)[slot]
        val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(16), dp(14), dp(18)) }
        col.addView(TextView(ctx).apply { text = "Edit Hub tile"; textSize = 20f; typeface = Typeface.DEFAULT_BOLD; setTextColor(cText) })
        col.addView(TextView(ctx).apply { text = "Tile ${slot + 1} of $SLOTS: choose what it does"; textSize = 12f; setTextColor(cDim); setPadding(0, dp(2), 0, dp(10)) })
        fun cardBg() = GradientDrawable().apply { setColor(ctx.getColor(R.color.mg_surface)); cornerRadius = dp(20).toFloat(); setStroke(dp(1), ctx.getColor(R.color.mg_outline)) }
        val card = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; background = cardBg(); setPadding(0, dp(6), 0, dp(6)) }
        fun rowOf(icon: Int, label: String, current: Boolean, onTap: () -> Unit) = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), 0, dp(16), 0)
            background = ripple?.constantState?.newDrawable()
            isClickable = true
            setOnClickListener { dlg.dismiss(); onTap() }
            addView(ImageView(ctx).apply { setImageResource(icon); setColorFilter(if (current) cAccent else cDim) }, LinearLayout.LayoutParams(dp(22), dp(22)).apply { marginEnd = dp(16) })
            addView(TextView(ctx).apply { text = label; textSize = 16f; setTextColor(cText); maxLines = 1 }, LinearLayout.LayoutParams(0, WRAP, 1f))
            if (current) addView(TextView(ctx).apply {
                text = "Current"; textSize = 12f; setTextColor(cAccent)
                background = GradientDrawable().apply { setColor(ColorUtils.setAlphaComponent(cAccent, 0x26)); cornerRadius = dp(12).toFloat() }
                setPadding(dp(10), dp(3), dp(10), dp(3))
            })
        }
        for (e in CATALOG) card.addView(rowOf(e.icon, e.label, e.id == cur) { set(ctx, slot, e.id); onEdited() }, LinearLayout.LayoutParams(MATCH, dp(52)))
        col.addView(card, LinearLayout.LayoutParams(MATCH, WRAP))
        val resetCard = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; background = cardBg(); setPadding(0, dp(6), 0, dp(6)) }
        resetCard.addView(rowOf(R.drawable.ic_reload, "Reset all 9 tiles", false) { reset(ctx); onEdited() }, LinearLayout.LayoutParams(MATCH, dp(52)))
        col.addView(resetCard, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(12) })
        val sv = NestedScrollView(ctx)
        sv.overScrollMode = View.OVER_SCROLL_NEVER
        sv.addView(col, ViewGroup.LayoutParams(MATCH, WRAP))
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
