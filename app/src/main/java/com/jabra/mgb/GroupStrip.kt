package com.jabra.mgb

import android.app.Activity
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * S13: Brave-like group strip above the bottom bar. Shown while the active tab is in a group:
 * [^ collapse] [one round chip per tab of the group, active one has a ring + red x] [+ new tab in this group].
 * Collapsed = one small pill "name - n". Groups themselves live in TabSwitcher; this only draws and calls TabManager.
 */
object GroupStrip {
    private var act: Activity? = null
    private var tm: TabManager? = null
    private var row: LinearLayout? = null
    private var chips: LinearLayout? = null
    private var scroll: HorizontalScrollView? = null
    private var arrow: ImageView? = null
    private var hasInfo = false
    private var sig = ""
    private val collapsed = HashSet<String>()
    private var lastCur = -1 // S15: pop animation when the active chip changes
    private var lastGroup = "" // 0.0.364 (P3): chips that are new since the last build slide in
    private var lastIds: Set<Int> = emptySet()
    private var animNext = false // 0.0.364: collapse / expand = chips pop in
    private fun still() = act?.let { TabPrefs.reduce(it) } ?: true
    private val POP = android.view.animation.PathInterpolator(0.05f, 0.7f, 0.1f, 1f)

    private fun dp(v: Int) = Util.dp(act!!, v)

    /** 0.0.363 (P2): the visible strip row (UndoBar sits above it), null when hidden. */
    fun topView(): View? = row?.takeIf { it.visibility == View.VISIBLE }

    fun attach(a: Activity, t: TabManager) {
        act = a; tm = t; sig = ""; hasInfo = false
        val bar = a.findViewById<View>(R.id.bottom_bar) ?: return
        val parent = bar.parent as? ViewGroup ?: return
        val r = LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            setPadding(dp(4), 0, dp(4), 0)
            background = GradientDrawable().apply {
                setColor(a.getColor(R.color.mg_surface))
                setStroke(dp(1), a.getColor(R.color.mg_outline))
                cornerRadius = dp(22).toFloat()
            }
        }
        val up = ImageView(a).apply {
            setImageResource(R.drawable.ic_chevron)
            setPadding(dp(6), dp(6), dp(6), dp(6))
            contentDescription = "Collapse group"
            setOnClickListener { openPanel() } // S15: tap = group panel with all tabs (Brave style)
            setOnLongClickListener { toggle(); true } // old collapse / expand moved to long press
        }
        val sv = HorizontalScrollView(a).apply { isHorizontalScrollBarEnabled = false; overScrollMode = View.OVER_SCROLL_NEVER }
        val box = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        sv.addView(box, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val plus = ImageView(a).apply {
            setImageResource(R.drawable.ic_add)
            setPadding(dp(6), dp(6), dp(6), dp(6))
            contentDescription = "New tab in group"
            setOnClickListener { addTab() }
        }
        r.addView(up, LinearLayout.LayoutParams(dp(36), dp(36)))
        r.addView(sv, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        r.addView(plus, LinearLayout.LayoutParams(dp(36), dp(36)))
        parent.addView(r, parent.indexOfChild(bar), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)).apply {
            setMargins(dp(10), dp(6), dp(10), 0)
        })
        row = r; chips = box; scroll = sv; arrow = up
        refresh()
    }

    /** Rebuilds the strip when something it shows changed (cheap: skipped when the signature is the same). */
    fun refresh() {
        val m = tm ?: return
        val cur = m.active
        val info = if (cur != null && !cur.priv) TabSwitcher.groupInfoOf(cur.id) else null
        hasInfo = info != null && cur != null
        sync()
        if (info == null || cur == null) { sig = ""; return }
        val tabsIn = info.ids.mapNotNull { m.byId(it) }
        val s = info.name + "|" + info.color + "|" + cur.id + "|" + (info.name in collapsed) + "|" +
            tabsIn.joinToString(",") { val h = Util.host(it.url); "${it.id}:$h:${Favicons.get(h) != null}" }
        if (s == sig) return
        sig = s
        build(info, tabsIn, cur)
    }

    /** Only visibility: follows the bottom bar (it hides while scrolling) and whether the active tab is in a group. */
    fun sync() {
        val a = act ?: return
        val r = row ?: return
        val barOn = a.findViewById<View>(R.id.bottom_bar)?.visibility == View.VISIBLE
        val v = if (hasInfo && barOn) View.VISIBLE else View.GONE
        if (r.visibility != v) {
            r.visibility = v
            if (v == View.VISIBLE && !TabPrefs.reduce(a)) { // S15: strip slides up softly (0.0.364: not with Reduce motion)
                r.alpha = 0f; r.translationY = dp(14).toFloat()
                r.animate().alpha(1f).translationY(0f).setInterpolator(android.view.animation.PathInterpolator(0.05f, 0.7f, 0.1f, 1f)).setDuration(260).start()
            }
        }
    }

    /** S15: ^ opens the group as a panel above the strip: all tabs of the group as cards. */
    private fun openPanel() {
        val a = act ?: return
        val m = tm ?: return
        val cur = m.active ?: return
        val info = TabSwitcher.groupInfoOf(cur.id) ?: return
        TabSwitcher.showGroup(a, m, info.name, arrow)
    }

    private fun toggle() {
        val cur = tm?.active ?: return
        val info = TabSwitcher.groupInfoOf(cur.id) ?: return
        val go = { if (!collapsed.remove(info.name)) collapsed.add(info.name); animNext = true; sig = ""; refresh() }
        val b = chips
        if (b != null && b.childCount > 0 && !still()) { // 0.0.364 (P3): old chips shrink away, then the new state pops in
            b.animate().alpha(0f).scaleX(0.85f).setDuration(110).setInterpolator(android.view.animation.AccelerateInterpolator(1.2f)).withEndAction {
                b.alpha = 1f; b.scaleX = 1f
                go()
            }.start()
        } else go()
    }

    private fun addTab() {
        val m = tm ?: return
        val cur = m.active ?: return
        if (TabSwitcher.groupInfoOf(cur.id) == null) return
        val t = m.newTab(TabManager.HOME_PAGE) ?: return
        TabSwitcher.joinGroupOf(t.id, cur.id) // 0.0.364: the new chip slides in (build() sees a new id)
        sig = ""; refresh()
    }

    private fun disc(color: Int, ring: Boolean, size: Int) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
        if (ring) setStroke(dp(2), act!!.getColor(R.color.mg_text))
        setSize(size, size)
    }

    private fun build(info: TabSwitcher.GroupInfo, tabsIn: List<TabManager.Tab>, cur: TabManager.Tab) {
        val a = act ?: return
        val box = chips ?: return
        box.removeAllViews()
        val isCollapsed = info.name in collapsed
        arrow?.rotation = if (isCollapsed) 90f else -90f
        arrow?.contentDescription = if (isCollapsed) "Expand group" else "Collapse group"
        val anim = !still()
        val grow = animNext
        animNext = false
        val same = lastGroup == info.name
        if (isCollapsed) {
            lastGroup = info.name; lastIds = tabsIn.map { it.id }.toSet()
            val p = TextView(a).apply {
                text = info.name + " \u00B7 " + tabsIn.size
                setTextColor(0xFFFFFFFF.toInt()); textSize = 13f; maxLines = 1
                setPadding(dp(14), 0, dp(14), 0); gravity = Gravity.CENTER
                background = GradientDrawable().apply { setColor(info.color); cornerRadius = dp(16).toFloat() }
                setOnClickListener { toggle() }
            }
            box.addView(p, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(32)))
            if (anim && grow) { p.alpha = 0f; p.scaleX = 0.6f; p.scaleY = 0.6f; p.animate().alpha(1f).scaleX(1f).scaleY(1f).setInterpolator(POP).setDuration(280).start() }
            return
        }
        var activeChip: View? = null
        for (t in tabsIn) {
            val on = t === cur
            val f = FrameLayout(a)
            val d = FrameLayout(a).apply { background = disc(info.color, on, dp(34)) }
            val host = Util.host(t.url)
            val ic = if (t.home) null else Favicons.get(host)
            if (ic != null) {
                d.addView(ImageView(a).apply { setImageBitmap(ic); setPadding(dp(7), dp(7), dp(7), dp(7)) },
                    FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            } else {
                val letter = (if (t.home) "+" else host.firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()?.toString() ?: "?")
                d.addView(TextView(a).apply { text = letter; setTextColor(0xFFFFFFFF.toInt()); textSize = 14f; gravity = Gravity.CENTER },
                    FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            }
            d.contentDescription = t.title.ifBlank { host }
            d.setOnClickListener { if (!on) tm?.switchTo(t) }
            f.addView(d, FrameLayout.LayoutParams(dp(34), dp(34), Gravity.CENTER))
            if (on) {
                activeChip = f
                val x = TextView(a).apply {
                    text = "\u00D7"; setTextColor(0xFFFFFFFF.toInt()); textSize = 11f; gravity = Gravity.CENTER
                    background = disc(a.getColor(R.color.mg_error), false, dp(16))
                    contentDescription = "Close tab"
                    setOnClickListener { // 0.0.363 (P2): Undo bar; 0.0.364 (P3): the chip shrinks out first
                        val go = { tm?.let { m -> TabSwitcher.closeWithUndo(a, m, t) }; Unit }
                        if (still()) go() else { isEnabled = false; f.animate().alpha(0f).scaleX(0.3f).scaleY(0.3f).setDuration(150).setInterpolator(android.view.animation.AccelerateInterpolator(1.2f)).withEndAction { go() }.start() }
                    }
                }
                f.addView(x, FrameLayout.LayoutParams(dp(16), dp(16), Gravity.TOP or Gravity.END))
            }
            box.addView(f, LinearLayout.LayoutParams(dp(40), dp(40)))
            if (anim && (grow || (same && t.id !in lastIds)) && !on) { // 0.0.364: new chip slides in; expand = chips pop one by one
                val i = box.childCount - 1
                f.alpha = 0f; f.scaleX = 0.5f; f.scaleY = 0.5f
                if (!grow) f.translationX = dp(24).toFloat()
                f.animate().alpha(1f).scaleX(1f).scaleY(1f).translationX(0f).setStartDelay(if (grow) minOf(i, 8) * 25L else 0L).setInterpolator(POP).setDuration(300).start()
            }
        }
        lastGroup = info.name; lastIds = tabsIn.map { it.id }.toSet()
        val ac = activeChip
        if (ac != null && lastCur != cur.id && anim) { // S15: ring pops when another tab of the group becomes active
            ac.scaleX = 0.7f; ac.scaleY = 0.7f
            ac.animate().scaleX(1f).scaleY(1f).setInterpolator(android.view.animation.OvershootInterpolator(2.2f)).setDuration(280).start()
        }
        lastCur = cur.id
        if (ac != null) scroll?.post { scroll?.smoothScrollTo((ac.left - dp(60)).coerceAtLeast(0), 0) }
    }
}
