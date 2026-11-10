package com.jabra.mgb

import android.app.Activity
import android.content.Context
import android.app.Dialog
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.text.TextUtils
import android.util.Log
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.PopupWindow
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import android.view.ViewConfiguration
import android.view.animation.OvershootInterpolator
import kotlin.math.abs
import kotlin.math.hypot

/**
 * S31 + S32: Thazir tab switcher (full-screen Dialog). Own design, soft plum gradient background.
 * Top switch: Tabs | Private | Groups. 2-column cards with a page thumbnail (taken when you leave a tab or open the
 * switcher; a colour avatar until one exists), title, host, close. Select mode (menu or long press): tick cards, then
 * Group / Ungroup / Close. Groups live in memory only (lost when the app restarts); private tabs cannot be grouped.
 */
object TabSwitcher {
    private const val TAG = "Thazir.switcher"
    private var current: Screen? = null
    private val thumbs = HashMap<Int, Bitmap>()
    private val groups = LinkedHashMap<String, MutableSet<Int>>()
    private val colors = HashMap<String, Int>() // S43: group colour (palette value); missing = colour from the name

    private val PALETTE = intArrayOf(
        0xFFB0B4BC.toInt(), 0xFF6C8CF0.toInt(), 0xFFF06C7C.toInt(), 0xFFF5C26B.toInt(), 0xFF7FD67F.toInt(),
        0xFFF28BB5.toInt(), 0xFFB57CE8.toInt(), 0xFF5CC8F0.toInt(), 0xFFF59A5B.toInt(), 0xFF4DC7A8.toInt()
    )

    private fun colorOf(name: String): Int = colors[name] ?: PALETTE[(abs(name.hashCode()) % (PALETTE.size - 1)) + 1]

    /** S41: groups saved with the session. indexOf = tab id -> position in the saved tab list. S43: v2 also keeps colours. */
    fun exportGroups(indexOf: Map<Int, Int>): String {
        val g = org.json.JSONObject()
        val cc = org.json.JSONObject()
        for ((n, ids) in groups) {
            val a = org.json.JSONArray()
            for (id in ids) indexOf[id]?.let { a.put(it) }
            if (a.length() > 0) {
                g.put(n, a)
                colors[n]?.let { cc.put(n, it) }
            }
        }
        return org.json.JSONObject().put("v", 2).put("g", g).put("c", cc).toString()
    }

    fun importGroups(json: String, idAt: (Int) -> Int?) {
        try {
            val o = org.json.JSONObject(json)
            val v2 = o.optInt("v") == 2 && o.optJSONObject("g") != null // old files: the object itself is name -> indexes
            val g = if (v2) o.getJSONObject("g") else o
            val cc = if (v2) o.optJSONObject("c") else null
            groups.clear()
            colors.clear()
            for (n in g.keys()) {
                val a = g.getJSONArray(n)
                val s = LinkedHashSet<Int>()
                for (i in 0 until a.length()) idAt(a.getInt(i))?.let { s.add(it) }
                if (s.isNotEmpty()) {
                    groups[n] = s
                    if (cc != null && cc.has(n)) colors[n] = cc.getInt(n)
                }
            }
        } catch (e: Exception) { Log.e(TAG, "groups restore", e) }
    }

    /** S40: long press menu "Open in new tab in group": pick a group (or make one), the new tab joins it. */
    /** 0.0.379 (Chrome): link menu "Open in new tab in group". Opener tab already in a group = the new tab joins it, right after the opener.
     *  Opener not in a group = a NEW group (auto name + colour) holds the opener AND the new tab. No dialog. */
    fun openInGroupHere(ctx: Context, tm: TabManager, url: String, openerId: Int) {
        try {
            if (tm.byId(openerId) == null) return
            val t = tm.newTab(url, openerId, false) ?: return
            var name = groups.entries.firstOrNull { openerId in it.value }?.key
            if (name == null) {
                var n = groups.size + 1
                while (groups.containsKey("Group $n")) n++
                val nm = "Group $n"
                colors[nm] = PALETTE[(groups.size % (PALETTE.size - 1)) + 1]
                groups[nm] = LinkedHashSet<Int>().apply { add(openerId) }
                name = nm
            }
            addAfter(name, t.id, openerId)
            Util.toast(ctx, "Opened in group $name")
            onChanged?.invoke()
        } catch (e: Exception) { /* the tab is open anyway; grouping is a bonus */ }
    }

    fun hasGroups(): Boolean = groups.isNotEmpty()

    fun openInGroup(ctx: Context, tm: TabManager, url: String, openerId: Int) {
        fun open(name: String) {
            val t = tm.newTab(url, openerId, false) ?: return
            groups.getOrPut(name) { LinkedHashSet() }.add(t.id)
            Util.toast(ctx, "Opened in group $name")
            onChanged?.invoke() // S13
        }
        val names = groups.keys.toList()
        val et = android.widget.EditText(ctx).apply {
            hint = "Group name"
            setSingleLine()
            setText(if (names.isEmpty()) "Group 1" else "")
        }
        val pad = Util.dp(ctx, 20)
        val box = android.widget.FrameLayout(ctx).apply { setPadding(pad, Util.dp(ctx, 8), pad, 0); addView(et) }
        val b = MaterialAlertDialogBuilder(ctx).setTitle("Open in group")
        if (names.isEmpty()) {
            b.setView(box).setPositiveButton("Open") { _, _ -> et.text.toString().trim().take(30).takeIf { it.isNotEmpty() }?.let { open(it) } }
        } else {
            val all = names + "New group..."
            b.setItems(all.toTypedArray()) { _, i ->
                if (i < names.size) open(names[i])
                else MaterialAlertDialogBuilder(ctx).setTitle("New group").setView(box)
                    .setPositiveButton("Open") { _, _ ->
                        et.text.toString().trim().take(30).takeIf { it.isNotEmpty() && !groups.containsKey(it) }?.let { open(it) }
                    }.setNegativeButton("Cancel", null).show()
            }
        }
        b.setNegativeButton("Cancel", null).show()
    }

    /** Small picture of the page in [t] (only possible while its WebView is attached, i.e. the active tab). */
    fun snap(t: TabManager.Tab) {
        try {
            if (t.home || t.isExt) return
            val w = t.web
            if (w.width <= 0 || w.height <= 0) return
            val s = 0.4f
            val b = Bitmap.createBitmap((w.width * s).toInt().coerceAtLeast(1), (w.height * s).toInt().coerceAtLeast(1), Bitmap.Config.RGB_565)
            val c = Canvas(b)
            c.drawColor(Color.WHITE)
            c.scale(s, s)
            w.draw(c)
            val keep = if (t.priv) blurred(b) else b // 0.0.327: private tab pictures are blurred, the sharp one is dropped at once
            thumbs[t.id]?.recycle()
            thumbs[t.id] = keep
        } catch (e: Throwable) { Log.w(TAG, "snap failed", e) }
    }

    /** 0.0.327: strong soft blur for a private tab picture (shrink to ~1/14, grow back with smoothing; works on every Android version). */
    private fun blurred(src: Bitmap): Bitmap {
        return try {
            val sw = (src.width / 14).coerceAtLeast(2); val sh = (src.height / 14).coerceAtLeast(2)
            val small = Bitmap.createScaledBitmap(src, sw, sh, true)
            val big = Bitmap.createScaledBitmap(small, src.width, src.height, true)
            if (small !== big) small.recycle()
            if (src !== big) src.recycle()
            big
        } catch (e: Throwable) { Log.w(TAG, "blur failed", e); src }
    }

    fun show(act: Activity, tm: TabManager) {
        if (act.isFinishing || act.isDestroyed) return
        UndoBar.hide(false)
        try { current?.dismiss() } catch (_: Exception) { }
        try {
            tm.active?.let { snap(it) }
            val s = Screen(act, tm)
            current = s
            s.show()
        } catch (e: Exception) {
            Log.e(TAG, "open failed", e)
            current = null
        }
    }

    private fun groupOf(t: TabManager.Tab): String? = groups.entries.firstOrNull { t.id in it.value }?.key

    /** S12: bottom group strip (GroupStrip.kt) reads / joins groups through these. */
    var onChanged: (() -> Unit)? = null
    class GroupInfo(val name: String, val color: Int, val ids: List<Int>)
    fun groupInfoOf(id: Int): GroupInfo? {
        val e = groups.entries.firstOrNull { id in it.value } ?: return null
        return GroupInfo(e.key, colorOf(e.key), e.value.toList())
    }
    /** 0.0.363 (P2, Chrome): a new tab of a group goes right after the tab it was opened from, not at the end. */
    fun joinGroupOf(newId: Int, refId: Int) {
        val e = groups.entries.firstOrNull { refId in it.value } ?: return
        addAfter(e.key, newId, refId)
    }

    /** 0.0.363: put [newId] into the existing group [name] right after [afterId] (end when null / not in the group). */
    fun addAfter(name: String, newId: Int, afterId: Int?) {
        val g = groups[name] ?: return
        val l = ArrayList<Int>(g)
        l.remove(newId)
        val at = if (afterId == null) -1 else l.indexOf(afterId)
        l.add(if (at < 0) l.size else at + 1, newId)
        g.clear(); g.addAll(l)
    }

    /** 0.0.363 (P2): one group saved whole (name, colour, tab order). Shared by the switcher Undo and the main browser Undo. */
    class GroupSnap(val name: String, val color: Int?, val ids: List<Int>)

    /** Every group that holds one of [ids] (plus group [plus] when it exists), with order + colour. */
    private fun snapGroups(ids: Set<Int>, plus: String? = null): List<GroupSnap> =
        groups.entries.filter { e -> e.key == plus || e.value.any { it in ids } }.map { GroupSnap(it.key, colors[it.key], it.value.toList()) }

    /** Puts the saved groups back exactly (order + colour). [removeName] = a group that did not exist before and must go. */
    private fun restoreGroups(list: List<GroupSnap>, removeName: String?, alive: (Int) -> Boolean) {
        val names = list.map { it.name }.toSet()
        val all = HashSet<Int>()
        for (s in list) all.addAll(s.ids)
        for ((n, g) in groups) if (n !in names) g.removeAll(all)
        for (s in list) {
            val keep = s.ids.filter(alive)
            if (keep.isEmpty()) { groups.remove(s.name); continue }
            groups[s.name] = LinkedHashSet<Int>(keep)
            s.color?.let { colors[s.name] = it }
        }
        if (removeName != null && removeName !in names) { groups.remove(removeName); colors.remove(removeName) }
        onChanged?.invoke()
    }

    /**
     * 0.0.363 (P2): close one tab from the main browser (group strip x, Back) with an Undo bar.
     * Undo opens the page again, same place in the tab list, same group, same place in the group, same colour.
     * Private / home / extension tabs close as before (no bar: nothing to reopen).
     */
    fun closeWithUndo(act: Activity, tm: TabManager, t: TabManager.Tab) {
        val url = t.web.url ?: t.url
        val keep = !t.priv && !t.isExt && !t.home && url.startsWith("http")
        val idx = tm.allTabs().indexOf(t)
        val only = tm.allTabs().size == 1
        val g = if (keep) snapGroups(setOf(t.id)).firstOrNull { t.id in it.ids } else null
        val gi = g?.ids?.indexOf(t.id) ?: -1
        tm.close(t)
        if (!keep) return
        UndoBar.show(act, "Tab closed") {
            val nt = tm.newTab(url, 0, true, false) ?: return@show
            ClosedTabs.remove(url)
            tm.moveTo(nt, idx)
            if (g != null) {
                val cur = ArrayList<Int>(groups[g.name]?.filter { tm.byId(it) != null } ?: emptyList())
                cur.add(if (gi < 0) cur.size else gi.coerceIn(0, cur.size), nt.id)
                groups[g.name] = LinkedHashSet<Int>(cur)
                g.color?.let { if (!colors.containsKey(g.name)) colors[g.name] = it }
            }
            if (only) tm.allTabs().filter { it.home && !it.priv }.forEach { h -> if (tm.allTabs().size > 1) tm.close(h) } // the Home tab made by closing the last one
            onChanged?.invoke()
        }
    }

    /** 0.0.365: drops ids of closed tabs from every group (empty groups + their colours go too). */
    fun cleanStale(tm: TabManager) {
        val ids = tm.allTabs().map { it.id }.toSet()
        for (g in groups.values) g.retainAll(ids)
        groups.entries.removeAll { it.value.isEmpty() }
        colors.keys.retainAll(groups.keys)
    }

    /** T1: put tabs into group [name] (created when missing); they leave any other group. */
    fun groupTabs(name: String, ids: Collection<Int>) {
        val set = ids.toSet()
        for (g in groups.values) g.removeAll(set)
        groups.getOrPut(name) { LinkedHashSet() }.addAll(set)
        onChanged?.invoke()
    }

    /** S15: opens group [name] directly as a panel over the page (grows out of [anchor], e.g. the strip arrow), like Brave. */
    fun showGroup(act: Activity, tm: TabManager, name: String, anchor: View?) {
        if (act.isFinishing || act.isDestroyed || !groups.containsKey(name)) return
        UndoBar.hide(false)
        try { current?.dismiss() } catch (_: Exception) { }
        try {
            tm.active?.let { snap(it) }
            val s = Screen(act, tm, name, anchor)
            current = s
            s.show()
        } catch (e: Exception) {
            Log.e(TAG, "group panel failed", e)
            current = null
        }
    }

    private class MI(val label: String, val icon: Int, val on: () -> Unit)

    private class Screen(private val act: Activity, private val tm: TabManager, private val startGroup: String? = null, private val anchor: View? = null) :
        Dialog(act, if (startGroup != null) android.R.style.Theme_Translucent_NoTitleBar else com.google.android.material.R.style.Theme_Material3_DayNight_NoActionBar) {

        private val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        private val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
        private val CARD_H = 196
        private val HEAD_H = 36
        private var mode = if (tm.active?.priv == true) 1 else 0 // 0 tabs, 1 private, 2 groups
        private var backMode = 0 // S43: where the back arrow of an open group goes
        private var openGroup: String? = null
        private var selecting = false
        private var searching = false
        private var query = ""
        private var animateNext = true
        private val picked = HashSet<Int>()
        private var shown: List<TabManager.Tab> = emptyList()
        private lateinit var top: FrameLayout
        private lateinit var grid: LinearLayout
        private lateinit var empty: TextView
        private lateinit var bar: LinearLayout
        private lateinit var searchEt: EditText
        private lateinit var scroll: ScrollView
        private val targets = ArrayList<Pair<View, Any>>() // S44: drop targets of the current grid (group card -> String, tab card -> Tab)
        private var dragView: View? = null
        private var dragShift = 0f
        private var lastX = 0f
        private var lastY = 0f
        private var hover: Pair<View, Any>? = null
        private lateinit var body: FrameLayout // S75: overlay host (drop zone, undo bar)
        private var zone: TextView? = null // S75: "Remove tab from group" drop zone (group view, while dragging)
        private var undoBar: View? = null // S75
        private var pendingUndo: Pair<String, () -> Unit>? = null // 0.0.365: the Undo that is on screen now (handed to the main browser bar when the switcher closes)
        private var gone = false // 0.0.365: the switcher is dismissed (late Undo must not draw into it)
        private val ZONE = Any()
        private lateinit var rootV: LinearLayout // S77: for the dim scrim behind the group panel
        private var panelAnim = false // S77: play the panel open animation on the next render
        private var popFrom: IntArray? = null // S77: screen centre of the group card that was tapped
        private val fromStrip = startGroup != null // S15: panel opened from the group strip (transparent window, page visible behind)
        private var closing = false // S15
        private var stripTop = -1 // S15: screen y of the strip arrow, the panel ends just above it
        private var pendingGlide: Map<Any, FloatArray>? = null // S16: card positions before a re-render, cards glide from there to their new slot
        private var liftKey: Any? = null // S16: the dropped card keeps its lifted size while it glides home, then settles
        private var bornKey: Any? = null // 0.0.364 (P3): tag of a group card made by this move (it pops in)
        private var bornFrom: FloatArray? = null // 0.0.364: screen x,y of the tab card it grows out of
        private var pulseKey: Any? = null // 0.0.364: tag of a card that gets a small pulse after a merge / close
        private var panelFrom: IntArray? = null // 0.0.364: centre of the group card the panel grew from (close shrinks back there)
        private var panelClosing = false // 0.0.364
        private val still get() = TabPrefs.reduce(act) // 0.0.364 (P3): Reduce motion = no decorative animation
        private fun ms(v: Long): Long = if (still) 0L else v
        private val EASE = android.view.animation.PathInterpolator(0.2f, 0f, 0f, 1f)
        private val POP = android.view.animation.PathInterpolator(0.05f, 0.7f, 0.1f, 1f)

        private fun dp(v: Int) = Util.dp(act, v)
        private fun c(res: Int) = act.getColor(res)
        private val night get() = (act.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        private val cardW get() = (act.resources.displayMetrics.widthPixels - dp(40)) / 2

        init {
            setOnDismissListener { // S13: groups may have changed
                if (current === this) current = null
                gone = true
                val pu = if (undoBar != null) pendingUndo else null // 0.0.365: Undo still showing when the switcher closes
                pendingUndo = null
                cleanStale(tm) // 0.0.365: dead tab ids leave the groups now (not only when the switcher draws)
                onChanged?.invoke()
                if (pu != null) act.window.decorView.post { // 0.0.365: same Undo, now on the main browser bar (skipped when another switcher / panel opened at once)
                    if (current == null && !act.isFinishing && !act.isDestroyed) UndoBar.show(act, pu.first) { pu.second(); onChanged?.invoke() }
                }
            }
        }

        /** 0.0.295 (Chrome look): card fill = quiet container tone; the open / picked tab = same tone mixed with the accent ("tonal"). */
        private fun tonal(tint: Int = c(R.color.mg_primary), a: Float = 0.28f): Int =
            androidx.core.graphics.ColorUtils.blendARGB(c(R.color.mg_surface_variant), tint, a)

        private fun round(color: Int, r: Int, stroke: Int = 0, strokeColor: Int = 0) = GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(r).toFloat()
            if (stroke > 0) setStroke(dp(stroke), strokeColor)
        }

        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)
            if (startGroup != null) { // S15: start inside the open group panel
                mode = 2; openGroup = startGroup; backMode = 0; panelAnim = true
                anchor?.let { a -> val l = IntArray(2); a.getLocationOnScreen(l); popFrom = intArrayOf(l[0] + a.width / 2, l[1] + a.height / 2); stripTop = l[1] }
            }
            build()
            window?.let {
                it.setLayout(MATCH, MATCH)
                it.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
                if (fromStrip) {
                    it.setWindowAnimations(0)
                    it.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
                } else {
                    it.setWindowAnimations(android.R.style.Animation_Dialog)
                    val g = if (night) intArrayOf(0xFF2B2347.toInt(), 0xFF0A0C10.toInt()) else intArrayOf(0xFFECE6F8.toInt(), 0xFFFFFFFF.toInt())
                    it.setBackgroundDrawable(GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, g))
                }
            }
        }

        /** S15: panel from the strip shrinks back into the arrow before it goes away. */
        override fun dismiss() {
            if (!fromStrip || closing || !isShowing || still) { super.dismiss(); return } // 0.0.364: Reduce motion = close at once
            closing = true
            val ease = android.view.animation.AccelerateInterpolator(1.4f)
            for (v in listOf<View>(top, body)) v.animate().alpha(0f).scaleX(0.4f).scaleY(0.4f).setInterpolator(ease).setDuration(190).start()
            val sc = rootV.background
            if (sc != null) android.animation.ValueAnimator.ofInt(255, 0).apply { duration = 190; addUpdateListener { sc.alpha = it.animatedValue as Int } }.start()
            rootV.postDelayed({ try { super@Screen.dismiss() } catch (_: Exception) { } }, 200)
        }

        // back: search -> select -> open group -> close the switcher
        @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
        override fun onBackPressed() {
            when {
                searching -> endSearch()
                selecting -> endSelect(true)
                mode == 2 && openGroup != null -> closeGroupView()
                else -> dismiss()
            }
        }

        // ---- data

        private fun clean() {
            val ids = tm.allTabs().map { it.id }.toSet()
            for (g in groups.values) g.retainAll(ids)
            groups.entries.removeAll { it.value.isEmpty() }
            colors.keys.retainAll(groups.keys)
            thumbs.keys.retainAll(ids)
            if (openGroup != null && !groups.containsKey(openGroup)) { openGroup = null; mode = backMode }
        }

        private fun matches(t: TabManager.Tab): Boolean {
            val q = query.trim().lowercase()
            if (q.isEmpty()) return true
            return t.title.lowercase().contains(q) || (t.pending ?: t.url).lowercase().contains(q)
        }

        private fun listFor(): List<TabManager.Tab> {
            val all = tm.allTabs()
            val g = openGroup
            val base = when {
                mode == 1 -> all.filter { it.priv }
                mode == 2 && g != null -> (groups[g] ?: emptySet<Int>()).mapNotNull { id -> all.firstOrNull { !it.priv && it.id == id } } // S77: group order
                mode == 2 -> emptyList()
                query.isNotBlank() -> all.filter { !it.priv } // searching: flat list, groups included
                else -> all.filter { !it.priv && groupOf(it) == null }
            }
            return base.filter { matches(it) }
        }

        private fun urlOf(t: TabManager.Tab): String? {
            if (t.priv || t.isExt || t.home) return null
            val u = t.pending ?: t.url
            return if (u.startsWith("http")) u else null
        }

        private fun addFav(list: List<TabManager.Tab>) {
            var n = 0
            for (t in list) {
                val u = urlOf(t) ?: continue
                if (!Bookmarks.isBookmarked(u)) { Bookmarks.addBookmark(t.title.ifBlank { Util.host(u) }, u); n++ }
            }
            Util.toast(act, if (n == 0) "Nothing new to add" else n.toString() + (if (n == 1) " bookmark added" else " bookmarks added"))
        }

        private fun shareTabs(list: List<TabManager.Tab>) {
            val txt = list.mapNotNull { t -> urlOf(t)?.let { (t.title.ifBlank { Util.host(it) }) + "\n" + it } }.joinToString("\n\n")
            if (txt.isEmpty()) Util.toast(act, "No web tabs to share") else tm.shareText(txt)
        }

        // ---- build

        private fun build() {
            val root = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
            rootV = root
            top = FrameLayout(act)
            root.addView(top, LinearLayout.LayoutParams(MATCH, dp(64)))
            grid = LinearLayout(act).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), dp(4), dp(12), dp(16))
            }
            val sv = ScrollView(act)
            scroll = sv
            sv.clipChildren = false
            grid.clipChildren = false
            grid.clipToPadding = false // S16 FIX: grid has padding, so the default clipToPadding cut a dragged card at the row bottom
            sv.addView(grid, ViewGroup.LayoutParams(MATCH, WRAP))
            val bodyFrame = FrameLayout(act)
            body = bodyFrame
            bodyFrame.addView(sv, FrameLayout.LayoutParams(MATCH, MATCH))
            empty = TextView(act).apply {
                gravity = Gravity.CENTER
                textSize = 15f
                setTextColor(c(R.color.mg_text_dim))
                visibility = View.GONE
            }
            bodyFrame.addView(empty, FrameLayout.LayoutParams(MATCH, MATCH))
            root.addView(bodyFrame, LinearLayout.LayoutParams(MATCH, 0, 1f))
            bar = LinearLayout(act).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(10), dp(4), dp(10), dp(4))
                background = round(c(R.color.mg_surface), 30, 1, c(R.color.mg_outline)) // S72: floating pill bar
                elevation = dp(6).toFloat()
            }
            root.addView(bar, LinearLayout.LayoutParams(MATCH, WRAP).apply { setMargins(dp(14), dp(4), dp(14), dp(12)) })
            setContentView(root)
            render()
        }

        private fun icon(res: Int, desc: String, tint: Int = c(R.color.mg_text), on: (View) -> Unit) = ImageView(act).apply {
            setImageResource(res)
            setColorFilter(tint)
            scaleType = ImageView.ScaleType.CENTER
            contentDescription = desc
            setOnClickListener { on(it) }
            press(this)
        }

        /** Soft press feedback: the view shrinks a little while the finger is down. Clicks still work (listener returns false). */
        private fun press(v: View) {
            v.setOnTouchListener { x, e ->
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> x.animate().scaleX(0.96f).scaleY(0.96f).setDuration(80).start()
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> x.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
                }
                false
            }
        }

        private fun labelBtn(res: Int, label: String, on: () -> Unit): View {
            val col = LinearLayout(act).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setOnClickListener { on() }
            }
            col.addView(ImageView(act).apply { setImageResource(res); setColorFilter(c(R.color.mg_text)) }, LinearLayout.LayoutParams(dp(24), dp(24)))
            col.addView(TextView(act).apply { text = label; textSize = 11f; setTextColor(c(R.color.mg_text_dim)) })
            return col
        }

        private fun segItem(label: String, icon: Int, sel: Boolean, on: () -> Unit): View {
            val row = LinearLayout(act).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                setPadding(dp(10), 0, dp(10), 0) // 0.0.296: tighter so the + pill and the menu fit at 360 dp
                background = if (sel) round(if (mode == 1) c(R.color.mg_primary) else c(R.color.mg_bg), 20) else null
                setOnClickListener { on() }
            }
            val fg = if (sel && mode == 1) c(R.color.mg_on_primary) else if (sel) c(R.color.mg_text) else c(R.color.mg_text_dim)
            row.addView(ImageView(act).apply { setImageResource(icon); setColorFilter(fg) }, LinearLayout.LayoutParams(dp(18), dp(18)).apply { marginEnd = dp(5) })
            row.addView(TextView(act).apply {
                text = label
                textSize = 14f
                setTextColor(fg)
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            })
            return row
        }

        private fun go(m: Int) { mode = m; openGroup = null; query = ""; searching = false; animateNext = true; endSelect(false); render() }

        // ---- popup menu (replaces the plain list dialogs)

        private fun popup(anchor: View, items: List<MI>, yOff: Int = 0) {
            val col = LinearLayout(act).apply {
                orientation = LinearLayout.VERTICAL
                background = round(c(R.color.mg_surface), 16, 1, c(R.color.mg_outline))
                setPadding(dp(6), dp(8), dp(6), dp(8))
            }
            val pw = PopupWindow(col, dp(236), WRAP, true)
            pw.elevation = dp(12).toFloat()
            pw.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            for (mi in items) {
                val row = LinearLayout(act).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(12), dp(12), dp(12), dp(12))
                    setOnClickListener { pw.dismiss(); mi.on() }
                }
                row.addView(ImageView(act).apply { setImageResource(mi.icon); setColorFilter(c(R.color.mg_text)) }, LinearLayout.LayoutParams(dp(20), dp(20)).apply { marginEnd = dp(14) })
                row.addView(TextView(act).apply { text = mi.label; textSize = 15f; setTextColor(c(R.color.mg_text)) })
                col.addView(row, LinearLayout.LayoutParams(MATCH, WRAP))
            }
            try { pw.showAsDropDown(anchor, 0, yOff, Gravity.END) } catch (e: Exception) { Log.w(TAG, "popup failed", e) }
        }

        // ---- top bar

        private fun drawTop() {
            if (searching && this::searchEt.isInitialized && searchEt.parent === top) return // keep the keyboard while typing
            top.removeAllViews()
            if (selecting) {
                top.addView(icon(R.drawable.ic_close, "Cancel") { endSelect(true) }, FrameLayout.LayoutParams(dp(48), dp(48), Gravity.START or Gravity.CENTER_VERTICAL).apply { marginStart = dp(8) })
                top.addView(TextView(act).apply {
                    text = if (picked.isEmpty()) "Select items" else picked.size.toString() + (if (picked.size == 1) " item" else " items")
                    textSize = 18f
                    setTextColor(c(R.color.mg_text))
                    typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                }, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER))
                val end = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
                end.addView(TextView(act).apply {
                    text = if (picked.size == shown.size && shown.isNotEmpty()) "None" else "All"
                    textSize = 15f
                    setTextColor(c(R.color.mg_primary))
                    gravity = Gravity.CENTER
                    setOnClickListener { toggleAll() }
                }, LinearLayout.LayoutParams(dp(52), dp(48)))
                end.addView(icon(R.drawable.ic_more, "Menu") { selectMenu(it) }, LinearLayout.LayoutParams(dp(48), dp(48)))
                top.addView(end, FrameLayout.LayoutParams(WRAP, dp(48), Gravity.END or Gravity.CENTER_VERTICAL).apply { marginEnd = dp(4) })
                return
            }
            if (searching) {
                top.addView(icon(R.drawable.ic_back, "Close search") { endSearch() }, FrameLayout.LayoutParams(dp(48), dp(48), Gravity.START or Gravity.CENTER_VERTICAL).apply { marginStart = dp(8) })
                searchEt = EditText(act).apply {
                    hint = "Search tabs"
                    setSingleLine(true)
                    inputType = InputType.TYPE_CLASS_TEXT
                    imeOptions = EditorInfo.IME_ACTION_SEARCH
                    textSize = 15f
                    setTextColor(c(R.color.mg_text))
                    setHintTextColor(c(R.color.mg_text_dim))
                    background = round(c(R.color.mg_surface_variant), 22)
                    setPadding(dp(16), 0, dp(16), 0)
                    setText(query)
                    setSelection(query.length)
                    addTextChangedListener(object : TextWatcher {
                        override fun beforeTextChanged(s: CharSequence?, st: Int, c1: Int, a: Int) {}
                        override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c2: Int) {}
                        override fun afterTextChanged(s: Editable?) {
                            query = s?.toString().orEmpty()
                            shown = listFor()
                            renderList()
                        }
                    })
                }
                top.addView(searchEt, FrameLayout.LayoutParams(MATCH, dp(44), Gravity.CENTER_VERTICAL).apply { marginStart = dp(60); marginEnd = dp(16) })
                searchEt.post {
                    searchEt.requestFocus()
                    (act.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)?.showSoftInput(searchEt, InputMethodManager.SHOW_IMPLICIT)
                }
                return
            }
            val g = openGroup
            if (mode == 2 && g != null) { // inside a group: back arrow, colour dot + name, menu
                top.addView(icon(R.drawable.ic_back, "Back") { closeGroupView() }, FrameLayout.LayoutParams(dp(48), dp(48), Gravity.START or Gravity.CENTER_VERTICAL).apply { marginStart = dp(8) })
                val ttl = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
                ttl.addView(View(act).apply { background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(colorOf(g)) } }, LinearLayout.LayoutParams(dp(14), dp(14)).apply { marginEnd = dp(10) })
                ttl.addView(TextView(act).apply {
                    text = g
                    textSize = 18f
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                    setTextColor(c(R.color.mg_text))
                    typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                })
                top.addView(ttl, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.START or Gravity.CENTER_VERTICAL).apply { marginStart = dp(60); marginEnd = dp(64) })
                top.addView(icon(R.drawable.ic_more, "Group menu") { groupMenu(it, g, true) }, FrameLayout.LayoutParams(dp(48), dp(48), Gravity.END or Gravity.CENTER_VERTICAL).apply { marginEnd = dp(8) })
                return
            }
            val all = tm.allTabs()
            top.addView(ImageView(act).apply { // 0.0.296: Chrome "+" pill, tonal, top left = New tab
                setImageResource(R.drawable.ic_add)
                setColorFilter(c(R.color.mg_primary))
                scaleType = ImageView.ScaleType.CENTER
                background = round(tonal(), 20)
                contentDescription = "New tab"
                setOnClickListener { it.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK); newTab(mode == 1) }
                press(this)
            }, FrameLayout.LayoutParams(dp(56), dp(40), Gravity.START or Gravity.CENTER_VERTICAL).apply { marginStart = dp(12) })
            val seg = LinearLayout(act).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                background = round(c(R.color.mg_surface_variant), 24)
                setPadding(dp(4), dp(4), dp(4), dp(4))
            }
            seg.addView(segItem(all.count { !it.priv }.toString(), R.drawable.ic_list, mode == 0) { go(0) }, LinearLayout.LayoutParams(WRAP, dp(40)))
            seg.addView(segItem(all.count { it.priv }.toString(), R.drawable.ic_lock, mode == 1) { go(1) }, LinearLayout.LayoutParams(WRAP, dp(40)))
            seg.addView(segItem(groups.size.toString(), R.drawable.ic_folder, mode == 2) { go(2) }, LinearLayout.LayoutParams(WRAP, dp(40)))
            top.addView(seg, FrameLayout.LayoutParams(WRAP, dp(48), Gravity.CENTER))
            top.addView(icon(R.drawable.ic_more, "Menu") { menu(it) }, FrameLayout.LayoutParams(dp(48), dp(48), Gravity.END or Gravity.CENTER_VERTICAL).apply { marginEnd = dp(8) })
        }

        private fun drawBar() {
            bar.removeAllViews()
            if (selecting) {
                bar.addView(View(act), LinearLayout.LayoutParams(0, 1, 1f))
                if (mode == 0 || (mode == 2 && openGroup == null)) bar.addView(labelBtn(R.drawable.ic_folder, "Group") { askGroup(picked.toSet()) }, LinearLayout.LayoutParams(dp(80), dp(52)))
                if (mode == 2) bar.addView(labelBtn(R.drawable.ic_folder, "Ungroup") { ungroup() }, LinearLayout.LayoutParams(dp(80), dp(52)))
                bar.addView(labelBtn(R.drawable.ic_trash, "Close") { closePicked() }, LinearLayout.LayoutParams(dp(80), dp(52)))
                bar.addView(View(act), LinearLayout.LayoutParams(0, 1, 1f))
                return
            }
            bar.addView(icon(R.drawable.ic_back, "Done") { dismiss() }, LinearLayout.LayoutParams(dp(56), dp(52)))
            val lv = TabPrefs.listView(act) // 0.0.281: grid <-> list, the icon shows what a tap switches to
            bar.addView(icon(if (lv) R.drawable.ic_tab_grid else R.drawable.ic_tab_rows, if (lv) "Show cards" else "Show list") { TabPrefs.set(act, "tab_list_view", !lv); animateNext = true; render() }, LinearLayout.LayoutParams(dp(56), dp(52)))
            bar.addView(LinearLayout(act).apply { // 0.0.296: Chrome "Search your tabs" field in the bar (the + moved to the top left)
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = round(c(R.color.mg_surface_variant), 22)
                setPadding(dp(14), 0, dp(14), 0)
                contentDescription = "Search your tabs"
                setOnClickListener { startSearch() }
                press(this)
                addView(ImageView(act).apply { setImageResource(R.drawable.ic_search); setColorFilter(c(R.color.mg_text_dim)) }, LinearLayout.LayoutParams(dp(18), dp(18)).apply { marginEnd = dp(8) })
                addView(TextView(act).apply { text = "Search your tabs"; textSize = 14f; setTextColor(c(R.color.mg_text_dim)); maxLines = 1 })
            }, LinearLayout.LayoutParams(0, dp(44), 1f).apply { marginStart = dp(4); marginEnd = dp(4) })
            bar.addView(icon(R.drawable.ic_trash, "Close all") { closeAll() }, LinearLayout.LayoutParams(dp(56), dp(52)))
        }

        private fun render() {
            if (gone) return // 0.0.365: Undo run from the main browser bar after the switcher closed
            hideUndo() // S75: any new state makes an old Undo stale
            clean()
            if (fromStrip && openGroup == null) { if (!closing) dismiss(); return } // S15: group gone (deleted / emptied)
            shown = listFor()
            picked.retainAll(shown.map { it.id }.toSet())
            drawTop()
            drawBar()
            renderList()
            applyPanel() // S77
        }

        /** S77: an open group is a rounded panel over a dimmed switcher (like Brave); it grows out of the tapped group card. */
        private fun applyPanel() {
            val panel = mode == 2 && openGroup != null
            val r = dp(22).toFloat()
            val surf = c(R.color.mg_surface)
            rootV.setBackgroundColor(if (panel) 0x66000000 else Color.TRANSPARENT)
            if (fromStrip) { // S15: tap outside the panel closes it; the panel itself swallows taps
                rootV.setOnClickListener { dismiss() }
                top.isClickable = true; body.isClickable = true
                bar.visibility = if (panel) View.GONE else View.VISIBLE
                if (panel && panelAnim && !still) { val sc = rootV.background; sc.alpha = 0; android.animation.ValueAnimator.ofInt(0, 255).apply { duration = 260; addUpdateListener { sc.alpha = it.animatedValue as Int } }.start() }
            }
            val tl = top.layoutParams as LinearLayout.LayoutParams
            val bl = body.layoutParams as LinearLayout.LayoutParams
            val m = if (panel) dp(12) else 0
            tl.setMargins(m, if (panel) dp(8) else 0, m, 0)
            val bm = if (fromStrip && panel && stripTop > 0) maxOf(dp(6), act.window.decorView.height - stripTop + dp(10)) else if (panel) dp(6) else 0 // S15: end above the strip
            bl.setMargins(m, 0, m, bm)
            top.layoutParams = tl
            body.layoutParams = bl
            val tbg = GradientDrawable().apply { setColor(surf); cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f) }
            val bbg = GradientDrawable().apply { setColor(surf); cornerRadii = floatArrayOf(0f, 0f, 0f, 0f, r, r, r, r) }
            top.background = if (panel) tbg else null
            body.background = if (panel) bbg else null
            if (panel && panelAnim) panelFrom = popFrom // 0.0.364: where the close shrinks back to
            if (panel && panelAnim && !still) {
                val pf = popFrom
                val rl = IntArray(2)
                rootV.getLocationOnScreen(rl)
                val start = if (fromStrip) 0.3f else if (pf != null) 0.45f else 0.94f
                val views = listOf<View>(top, body)
                for ((i, v) in views.withIndex()) {
                    v.alpha = 0f
                    v.scaleX = start
                    v.scaleY = start
                    if (pf != null) {
                        v.pivotX = (pf[0] - rl[0] - m).toFloat()
                        v.pivotY = (pf[1] - rl[1] - dp(8) - (if (i == 0) 0 else dp(64))).toFloat()
                    }
                    v.animate().alpha(1f).scaleX(1f).scaleY(1f).setInterpolator(if (fromStrip || pf != null) POP else android.view.animation.DecelerateInterpolator(1.8f)).setDuration(if (fromStrip || pf != null) 320L else 260L).start()
                }
                if (pf != null || fromStrip) { // 0.0.364: corners start as round as the card (16dp as seen) and relax to 22dp while the panel grows
                    val r0 = dp(16) / start
                    android.animation.ValueAnimator.ofFloat(r0, r).apply {
                        duration = 320; interpolator = EASE
                        addUpdateListener { val x = it.animatedValue as Float; tbg.cornerRadii = floatArrayOf(x, x, x, x, 0f, 0f, 0f, 0f); bbg.cornerRadii = floatArrayOf(0f, 0f, 0f, 0f, x, x, x, x) }
                    }.start()
                }
            }
            panelAnim = false
            popFrom = null
        }

        private fun groupNames(): List<String> {
            val q = query.trim().lowercase()
            return groups.keys.filter { q.isEmpty() || it.lowercase().contains(q) }
        }

        private fun renderList() {
            grid.removeAllViews()
            targets.clear()
            val anim = animateNext
            animateNext = false
            if (mode == 2 && openGroup == null) { // list of groups
                pendingGlide = null
                bornKey = null; pulseKey = null; bornFrom = null // 0.0.364
                val names = groupNames()
                empty.text = if (groups.isEmpty()) "No groups yet.\nLong press a tab > Add tab to group." else "No match."
                empty.visibility = if (names.isEmpty()) View.VISIBLE else View.GONE
                val views = names.map { groupRow(it) }
                for (v in views) grid.addView(v, LinearLayout.LayoutParams(MATCH, dp(76)).apply { setMargins(dp(4), dp(4), dp(4), dp(4)) })
                if (anim) enter(views)
                return
            }
            val cards = ArrayList<View>()
            val listV = TabPrefs.listView(act) // 0.0.281
            if (mode == 0 && query.isBlank() && !selecting) groupNames().forEach { n -> val v = if (listV) groupRow(n) else groupCard(n); v.tag = "g:" + n; cards.add(v); targets.add(v to n) } // groups sit in the tab grid first
            val olderOn = mode == 0 && query.isBlank() && !selecting // STEP E: "Older" = not opened for 7 days
            val older = if (olderOn) shown.filter { tm.isOld(it) } else emptyList()
            val oldCards = ArrayList<View>()
            shown.forEach { t -> if (t in older) return@forEach; val v = if (listV) row(t) else card(t); cards.add(v); targets.add(v to t) } // S77: inside a group a tab card = reorder target
            older.forEach { t -> val v = if (listV) row(t) else card(t); oldCards.add(v); targets.add(v to t) }
            empty.text = when {
                query.isNotBlank() -> "No tab matches."
                mode == 1 -> "No private tabs.\nTap + to open one."
                else -> "No tabs here."
            }
            empty.visibility = if (cards.isEmpty() && oldCards.isEmpty()) View.VISIBLE else View.GONE
            layoutCards(cards)
            if (oldCards.isNotEmpty()) { // STEP E
                grid.addView(TextView(act).apply {
                    text = "OLDER · " + oldCards.size + " (not opened for 7 days)"
                    textSize = 12f
                    letterSpacing = 0.06f
                    setTextColor(c(R.color.mg_text_dim))
                }, LinearLayout.LayoutParams(MATCH, WRAP).apply { setMargins(dp(8), dp(16), dp(8), dp(4)) })
                layoutCards(oldCards)
            }
            val pg = pendingGlide
            pendingGlide = null
            if (anim) enter(cards + oldCards) else if (pg != null) glide(pg) // S16
            effects(anim && pg == null) // 0.0.364 (P3)
        }

        /** 0.0.364 (P3): a new group card pops in (grows out of the tab card, name chip pops), a merged / re-opened group card pulses. */
        private fun effects(afterEnter: Boolean) {
            val bk = bornKey; val pk = pulseKey; val bf = bornFrom
            bornKey = null; pulseKey = null; bornFrom = null
            if (still || (bk == null && pk == null)) return
            fun find(k: Any?): View? = if (k == null) null else targets.firstOrNull { it.first.tag == k }?.first
            if (afterEnter) { // cards fade in with a stagger: pulse after they landed
                if (pk != null) grid.postDelayed({ find(pk)?.let { pulse(it) } }, 380)
                return
            }
            grid.viewTreeObserver.addOnPreDrawListener(object : android.view.ViewTreeObserver.OnPreDrawListener {
                override fun onPreDraw(): Boolean {
                    val o = grid.viewTreeObserver
                    if (o.isAlive) o.removeOnPreDrawListener(this)
                    find(bk)?.let { popIn(it, bf) }
                    if (pk != null) find(pk)?.let { v -> v.postDelayed({ pulse(v) }, 240) }
                    return true
                }
            })
        }

        private fun pulse(v: View) {
            if (still || !v.isAttachedToWindow) return
            v.animate().cancel()
            v.animate().scaleX(1.07f).scaleY(1.07f).setDuration(90).setInterpolator(EASE).withEndAction {
                v.animate().scaleX(1f).scaleY(1f).setInterpolator(OvershootInterpolator(2.2f)).setDuration(240).start()
            }.start()
        }

        private fun popIn(v: View, from: FloatArray?) {
            val l = IntArray(2)
            v.getLocationOnScreen(l)
            v.alpha = 0f; v.scaleX = 0.4f; v.scaleY = 0.4f
            if (from != null) { v.translationX = from[0] - l[0]; v.translationY = from[1] - l[1] }
            v.translationZ = dp(10).toFloat()
            (v.parent as? View)?.translationZ = dp(10).toFloat()
            v.animate().alpha(1f).scaleX(1f).scaleY(1f).translationX(0f).translationY(0f).setInterpolator(POP).setDuration(340)
                .withEndAction { v.translationZ = 0f; (v.parent as? View)?.translationZ = 0f }.start()
            val chip = (v as? FrameLayout)?.takeIf { it.childCount > 1 }?.getChildAt(1) ?: v // the head row with the colour dot + name
            if (chip !== v) {
                chip.pivotX = dp(18).toFloat(); chip.scaleX = 0.5f; chip.scaleY = 0.5f; chip.alpha = 0f
                chip.animate().scaleX(1f).scaleY(1f).alpha(1f).setStartDelay(180).setInterpolator(OvershootInterpolator(2.4f)).setDuration(280).start()
            }
        }

        /** S16: screen position of every tab card now (includes the drag offset), used as the start of the glide after a re-render. */
        private fun snapPos(): Map<Any, FloatArray> {
            val m = HashMap<Any, FloatArray>()
            val l = IntArray(2)
            for (p in targets) { val k = p.first.tag ?: continue; p.first.getLocationOnScreen(l); m[k] = floatArrayOf(l[0].toFloat(), l[1].toFloat()) }
            return m
        }

        /** S16: FLIP glide. Before the next draw every card that existed before is moved back to its old spot and eases into the new one. */
        private fun glide(prev: Map<Any, FloatArray>) {
            val lk = liftKey
            liftKey = null
            if (still) return // 0.0.364: Reduce motion = cards jump to the new place
            val vo = grid.viewTreeObserver
            vo.addOnPreDrawListener(object : android.view.ViewTreeObserver.OnPreDrawListener {
                override fun onPreDraw(): Boolean {
                    val o = grid.viewTreeObserver
                    if (o.isAlive) o.removeOnPreDrawListener(this)
                    val l = IntArray(2)
                    for (p in targets) {
                        val v = p.first
                        val a = prev[v.tag ?: continue] ?: continue
                        v.getLocationOnScreen(l)
                        val dx = a[0] - l[0]
                        val dy = a[1] - l[1]
                        val lifted = v.tag == lk
                        if (!lifted && abs(dx) < 1f && abs(dy) < 1f) continue
                        v.translationX = if (lifted) dx + v.width * 0.04f else dx // lifted: the old rect was scaled 1.08 about its centre
                        v.translationY = if (lifted) dy + v.height * 0.04f else dy
                        val an = v.animate().translationX(0f).translationY(0f).setStartDelay(0).setDuration(250) // 0.0.364: 250 ms
                            .setInterpolator(EASE)
                        if (lifted) { // above the other cards while it travels, then lands
                            v.scaleX = 1.08f; v.scaleY = 1.08f
                            v.translationZ = dp(12).toFloat()
                            (v.parent as? View)?.translationZ = dp(10).toFloat()
                            an.scaleX(1f).scaleY(1f).withEndAction { v.translationZ = 0f; (v.parent as? View)?.translationZ = 0f }
                        }
                        an.start()
                    }
                    return true
                }
            })
        }

        private fun enter(views: List<View>) {
            if (still) return // 0.0.364
            for ((i, v) in views.withIndex()) {
                v.alpha = 0f
                v.translationY = dp(16).toFloat()
                v.scaleX = 0.9f; v.scaleY = 0.9f // S72 / S15: a little deeper start, softer finish
                v.animate().alpha(1f).translationY(0f).scaleX(1f).scaleY(1f).setInterpolator(android.view.animation.PathInterpolator(0.05f, 0.7f, 0.1f, 1f))
                    .setDuration(320).setStartDelay((minOf(i, 10) * 30).toLong()).start()
            }
        }

        private fun layoutCards(cards: List<View>) {
            if (TabPrefs.listView(act)) { // 0.0.281: one column of rows
                for (v in cards) grid.addView(v, LinearLayout.LayoutParams(MATCH, dp(if (v.tag is String) 76 else 64)).apply { setMargins(dp(4), dp(4), dp(4), dp(4)) })
                return
            }
            var i = 0
            while (i < cards.size) {
                val line = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; clipChildren = false }
                for (k in 0..1) {
                    val v = cards.getOrNull(i + k)
                    val lp = LinearLayout.LayoutParams(0, dp(CARD_H), 1f).apply { setMargins(dp(4), dp(4), dp(4), dp(4)) }
                    line.addView(v ?: View(act), lp)
                }
                grid.addView(line, LinearLayout.LayoutParams(MATCH, WRAP))
                i += 2
            }
        }

        // ---- actions

        private fun endSelect(redraw: Boolean) { selecting = false; picked.clear(); if (redraw) render() }

        private fun startSelect(first: Int?) {
            selecting = true
            picked.clear()
            if (first != null) picked.add(first)
            render()
        }

        private fun toggleAll() {
            if (picked.size == shown.size) picked.clear() else { picked.clear(); shown.forEach { picked.add(it.id) } }
            render()
        }

        private fun startSearch() { searching = true; query = ""; render() }

        private fun endSearch() {
            (act.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)?.hideSoftInputFromWindow(top.windowToken, 0)
            searching = false
            query = ""
            animateNext = true
            render()
        }

        private fun openGroupView(name: String, from: View? = null) {
            popFrom = from?.let { val l = IntArray(2); it.getLocationOnScreen(l); intArrayOf(l[0] + it.width / 2, l[1] + it.height / 2) } // S77
            panelAnim = true
            backMode = if (mode == 2) 2 else 0
            mode = 2
            openGroup = name
            query = ""
            searching = false
            animateNext = true
            render()
        }

        private fun closeGroupView() {
            if (fromStrip) { dismiss(); return }
            val g = openGroup
            if (still || g == null || panelClosing || mode != 2) { finishClose(g); return }
            // 0.0.364 (P3): the panel shrinks back into the group card, the scrim fades
            panelClosing = true
            val pf = panelFrom
            val rl = IntArray(2)
            rootV.getLocationOnScreen(rl)
            val m = dp(12)
            for ((i, v) in listOf<View>(top, body).withIndex()) {
                if (pf != null) { v.pivotX = (pf[0] - rl[0] - m).toFloat(); v.pivotY = (pf[1] - rl[1] - dp(8) - (if (i == 0) 0 else dp(64))).toFloat() }
                v.animate().alpha(0f).scaleX(if (pf != null) 0.45f else 0.94f).scaleY(if (pf != null) 0.45f else 0.94f).setInterpolator(android.view.animation.AccelerateInterpolator(1.3f)).setDuration(200).start()
            }
            val sc = rootV.background
            if (sc != null) android.animation.ValueAnimator.ofInt(255, 0).apply { duration = 200; addUpdateListener { sc.alpha = it.animatedValue as Int } }.start()
            rootV.postDelayed({ if (panelClosing) finishClose(g) }, 210)
        }

        private fun finishClose(g: String?) {
            panelClosing = false
            if (g != null) pulseKey = "g:$g" // the group card pulses once the grid is back
            for (v in listOf<View>(top, body)) { v.animate().cancel(); v.alpha = 1f; v.scaleX = 1f; v.scaleY = 1f }
            rootV.background?.alpha = 255
            openGroup = null; mode = backMode; animateNext = true; endSelect(false); render()
        }

        private fun newTab(p: Boolean) {
            val before = tm.active?.id // 0.0.363 (P2): the new tab goes right after the active tab of the group
            val t = tm.newTab(TabManager.HOME_PAGE, 0, true, p)
            val g = openGroup
            if (t != null && !p && mode == 2 && g != null) addAfter(g, t.id, before)
            dismiss()
        }

        private fun menu(anchor: View) {
            val items = ArrayList<MI>()
            val onList = !(mode == 2 && openGroup == null)
            items.add(MI("New tab", R.drawable.ic_add) { newTab(false) })
            items.add(MI("New private tab", R.drawable.ic_lock) { newTab(true) })
            if (mode != 1) items.add(MI("New tab group", R.drawable.ic_folder) { newGroup() }) // S75
            if (onList) items.add(MI("Select tabs", R.drawable.ic_check) { startSelect(null) })
            if (mode != 1 && onList) items.add(MI("Bookmark all tabs", R.drawable.ic_bookmark_border) { TabBookmarks.dialog(act, tm) }) // S40
            if (mode != 1 && onList) items.add(MI("Share all tabs", R.drawable.ic_share) { TabBookmarks.shareAll(act, tm) }) // S41
            if (mode != 1 && onList) { // T1: copy the links / open links from the clipboard
                val grp = if (mode == 2) openGroup else null
                items.add(MI(if (grp != null) "Copy group links" else "Copy all tab links", R.drawable.ic_copy) {
                    TabBookmarks.copyLinks(act, if (grp != null) (groups[grp] ?: emptySet<Int>()).mapNotNull { tm.byId(it) } else tm.allTabs())
                })
            }
            if (onList) items.add(MI("Open links from clipboard", R.drawable.ic_paste) { // T1
                TabBookmarks.fromClipboard(act, tm, mode == 1, if (mode == 2) openGroup else null, mode != 1 && openGroup == null) { render() }
            })
            if (onList) items.add(MI("Reload all tabs", R.drawable.ic_reload) { reloadAll() }) // 0.0.378
            if (onList) dupTabs().let { d -> if (d.isNotEmpty()) items.add(MI("Close duplicates (" + d.size + ")", R.drawable.ic_close) { doCloseList(d) }) } // 0.0.287: Undo comes from doCloseList
            if (onList) items.add(MI(if (mode == 1) "Close all private tabs" else "Close all tabs", R.drawable.ic_trash) { closeAll() })
            items.add(MI("Delete browsing data", R.drawable.ic_trash) { deleteData() }) // S76
            items.add(MI("Settings", R.drawable.ic_settings) { dismiss(); SettingsScreen.open(act) }) // S76
            popup(anchor, items)
        }

        /** S76: choose what to delete (history, cookies and site data, cache, recently closed tabs). */
        private fun deleteData() {
            val names = arrayOf("Browsing history", "Cookies and site data", "Cache", "Recently closed tabs")
            val on = booleanArrayOf(true, false, true, true)
            MaterialAlertDialogBuilder(act)
                .setTitle("Delete browsing data")
                .setMultiChoiceItems(names, on) { _, i, v -> on[i] = v }
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Delete") { _, _ ->
                    try {
                        if (on[0]) Bookmarks.clearHistory()
                        if (on[1]) {
                            android.webkit.CookieManager.getInstance().removeAllCookies(null)
                            android.webkit.CookieManager.getInstance().flush()
                            android.webkit.WebStorage.getInstance().deleteAllData()
                        }
                        if (on[2]) tm.active?.web?.clearCache(true)
                        if (on[3]) ClosedTabs.clear()
                        Util.toast(act, "Browsing data deleted")
                    } catch (e: Exception) { Util.toast(act, "Could not delete everything") }
                }
                .show()
        }

        private fun selectMenu(anchor: View) {
            val sel = shown.filter { it.id in picked }
            val items = ArrayList<MI>()
            items.add(MI(if (picked.size == shown.size && shown.isNotEmpty()) "Deselect all" else "Select all", R.drawable.ic_check) { toggleAll() })
            items.add(MI("Close tabs", R.drawable.ic_close) { closePicked() })
            if (mode != 1) items.add(MI("Add to group", R.drawable.ic_folder) { askGroup(picked.toSet()) })
            if (mode == 2) items.add(MI("Ungroup tabs", R.drawable.ic_folder) { ungroup() })
            if (mode != 1) items.add(MI("Add Favorite", R.drawable.ic_bookmark_border) { addFav(sel) })
            if (mode != 1) items.add(MI("Share tabs", R.drawable.ic_share) { shareTabs(sel) })
            if (mode != 1) items.add(MI("Copy links", R.drawable.ic_copy) { TabBookmarks.copyLinks(act, sel) }) // T1
            items.add(MI(if (sel.size == 1) "Clone tab" else "Clone tabs", R.drawable.ic_copy) { // T1
                var n = 0
                for (x in sel) { if (x.isExt) continue; if (tm.cloneTab(x, false) == null) break; n++ }
                endSelect(false)
                render()
                if (n > 0) Util.toast(act, if (n == 1) "Tab cloned" else "$n tabs cloned")
            })
            popup(anchor, items)
        }

        private fun tabMenu(anchor: View, t: TabManager.Tab) {
            val items = ArrayList<MI>()
            items.add(MI("Select tab", R.drawable.ic_check) { startSelect(t.id) })
            if (!t.priv) items.add(MI(if (groupOf(t) != null) "Move to group" else "Add to group", R.drawable.ic_folder) { askGroup(setOf(t.id)) })
            if (!t.priv && groupOf(t) != null) items.add(MI("Remove from group", R.drawable.ic_close) { pullOut(t.id) }) // 0.0.362 (Undo comes from pullOut)
            if (!t.isExt) items.add(MI("Clone tab", R.drawable.ic_copy) { // T1
                if (tm.cloneTab(t, false) != null) { Util.toast(act, "Tab cloned"); render() }
            })
            if (urlOf(t) != null) items.add(MI("Add Favorite", R.drawable.ic_bookmark_border) { addFav(listOf(t)) })
            if (urlOf(t) != null) items.add(MI("Share", R.drawable.ic_share) { shareTabs(listOf(t)) })
            urlOf(t)?.let { u -> items.add(MI("Install app", R.drawable.ic_add) { WebApp.install(act, if (t.suspended) null else t.web, u, t.title) }) } // 0.0.299
            urlOf(t)?.let { u -> items.add(MI("Add to home screen", R.drawable.ic_home) { HomeShortcut.add(act, u, t.title) }) } // 0.0.298
            if (!t.isExt && !t.priv) items.add(MI(if (t.pinned) "Unpin tab" else "Pin tab", R.drawable.ic_pin) { tm.setPinned(t, !t.pinned); animateNext = true; render() }) // 0.0.296
            val mh = if (t.home || t.isExt) "" else Util.host(t.url)
            if (mh.isNotBlank()) items.add(MI(if (SiteMute.isMuted(act, mh)) "Unmute site" else "Mute site", R.drawable.ic_mute) { SiteMute.toggle(act, tm, mh); render() }) // 0.0.296
            // 0.0.378: Close other tabs / Close tabs after this (this list only, pinned tabs stay, Undo + "Ask before Close all" come from closeList)
            val others = shown.filter { it !== t && !it.pinned }
            val ix = shown.indexOf(t)
            val after = if (ix >= 0) shown.drop(ix + 1).filter { !it.pinned } else emptyList()
            if (others.isNotEmpty()) items.add(MI("Close other tabs", R.drawable.ic_close) { closeList(others) })
            if (after.isNotEmpty() && after.size < others.size) items.add(MI("Close tabs after this", R.drawable.ic_close) { closeList(after) })
            items.add(MI("Close tab", R.drawable.ic_close) { closeAnimated(anchor, t) })
            popup(anchor, items, -anchor.height + dp(44))
        }

        private fun groupMenu(anchor: View, name: String, inView: Boolean) {
            val items = ArrayList<MI>()
            items.add(MI("New tab in group", R.drawable.ic_add) { // 0.0.362
                val before = tm.active?.id // 0.0.363 (P2)
                val nt = tm.newTab(TabManager.HOME_PAGE, 0, true, false)
                if (nt != null) addAfter(name, nt.id, before)
                dismiss()
            })
            if (inView) items.add(MI("Select tabs", R.drawable.ic_check) { startSelect(null) })
            items.add(MI("Copy group links", R.drawable.ic_copy) { // T1
                TabBookmarks.copyLinks(act, (groups[name] ?: emptySet<Int>()).mapNotNull { tm.byId(it) })
            })
            items.add(MI("Edit group name", R.drawable.ic_edit) { renameGroup(name) })
            items.add(MI("Edit group colour", R.drawable.ic_theme) { recolor(name) })
            items.add(MI("Close group", R.drawable.ic_close) { closeGroup(name) })
            items.add(MI("Delete group", R.drawable.ic_trash) { deleteGroup(name) })
            popup(anchor, items, if (inView) 0 else -anchor.height + dp(44))
        }

        private fun closeAnimated(v: View, t: TabManager.Tab, dir: Float = 0f) {
            // S21b: Quetta-like close = card shrinks + fades, neighbours glide in, "Closed <title>  Undo" bar
            val all = tm.allTabs()
            val idx = all.indexOf(t)
            val url = t.web.url ?: t.url
            val keep = !t.priv && !t.isExt && !t.home && url.startsWith("http")
            val title = t.title.ifBlank { Util.host(url) }.let { if (it.length > 26) it.take(25) + "\u2026" else it }
            val gName = groupOf(t)
            val gIdx = gName?.let { groups[it]?.toList()?.indexOf(t.id) } ?: -1
            val gCol = gName?.let { colors[it] }
            val onlyOne = all.size == 1
            (if (dir != 0f) v.animate().translationX(dir * v.width * 1.1f).alpha(0f).setInterpolator(EASE).setDuration(ms(220)) // 0.0.364: fade + slide, one curve
            else v.animate().alpha(0f).scaleX(0.7f).scaleY(0.7f).setInterpolator(EASE).setDuration(ms(200))).withEndAction {
                pendingGlide = snapPos() // S16: the cards after it slide into the gap
                tm.close(t)
                render()
                if (keep) undo("Closed $title") {
                    val nt = tm.newTab(url, 0, false, false) ?: return@undo
                    ClosedTabs.remove(url)
                    tm.moveTo(nt, idx)
                    if (gName != null) {
                        val l = ArrayList(groups[gName] ?: emptySet<Int>())
                        l.add(gIdx.coerceIn(0, l.size), nt.id)
                        groups[gName] = LinkedHashSet<Int>(l)
                        if (gCol != null && !colors.containsKey(gName)) colors[gName] = gCol
                    }
                    if (onlyOne) tm.allTabs().firstOrNull { it.home && !it.priv && it !== nt }?.let { tm.close(it) } // the Home tab made by closing the last tab
                    pendingGlide = null
                    render()
                }
            }.start()
        }

        // ---- groups

        private fun nextColor(): Int = PALETTE[(groups.size % (PALETTE.size - 1)) + 1]

        /** Colour circles (2 rows) + optional name field. [initName] null = colour only. */
        private fun groupDialog(title: String, initName: String?, initColor: Int, ok: String, onCancel: () -> Unit = {}, done: (String, Int) -> Unit) {
            var sel = PALETTE.indexOf(initColor).let { if (it >= 0) it else 1 }
            val dots = ArrayList<View>()
            fun paint() {
                for ((i, v) in dots.withIndex()) v.background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(PALETTE[i])
                    if (i == sel) setStroke(dp(3), c(R.color.mg_text))
                }
            }
            val box = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(22), dp(8), dp(22), 0) }
            for (r in 0..1) {
                val row = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
                for (k in 0..4) {
                    val i = r * 5 + k
                    val d = View(act).apply { setOnClickListener { sel = i; paint() } }
                    dots.add(d)
                    row.addView(d, LinearLayout.LayoutParams(dp(36), dp(36)).apply { setMargins(dp(7), dp(7), dp(7), dp(7)) })
                }
                box.addView(row, LinearLayout.LayoutParams(MATCH, WRAP))
            }
            var et: EditText? = null
            if (initName != null) {
                val field = EditText(act).apply {
                    setText(initName)
                    inputType = InputType.TYPE_CLASS_TEXT
                    setSingleLine(true)
                    setSelectAllOnFocus(true)
                }
                et = field
                box.addView(field, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(10) })
            }
            paint()
            MaterialAlertDialogBuilder(act).setTitle(title).setView(box)
                .setPositiveButton(ok) { _, _ -> done(et?.text?.toString()?.trim()?.take(40).orEmpty(), PALETTE[sel]) }
                .setNegativeButton("Cancel") { _, _ -> onCancel() }
                .setOnCancelListener { onCancel() }
                .show()
        }

        private fun askGroup(ids: Set<Int>) {
            if (ids.isEmpty()) { Util.toast(act, "Tick some tabs first"); return }
            val names = groups.keys.toList()
            val newGroup = {
                groupDialog("New tab group", "Group " + (groups.size + 1), nextColor(), "Save") { n, col ->
                    val name = n.ifBlank { "Group " + (groups.size + 1) }
                    if (!groups.containsKey(name)) colors[name] = col
                    addTo(name, ids)
                }
            }
            if (names.isEmpty()) { newGroup(); return }
            groupPicker(ids, names, newGroup)
        }

        /** 0.0.362 (Chrome look): "Add to group" sheet = New group row on top, then every group with its colour + tab count. Built fresh on each open (rotation safe). */
        private fun groupPicker(ids: Set<Int>, names: List<String>, onNew: () -> Unit) {
            val dlg = com.google.android.material.bottomsheet.BottomSheetDialog(act)
            val col = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(16), dp(14), dp(18)) }
            col.addView(TextView(act).apply {
                text = if (ids.size == 1) "Add tab to group" else "Add " + ids.size + " tabs to group"
                textSize = 20f; setTextColor(c(R.color.mg_text)); typeface = Typeface.DEFAULT_BOLD
                setPadding(dp(6), 0, dp(6), dp(10))
            }, LinearLayout.LayoutParams(MATCH, WRAP))
            fun addRow(lead: View, label: String, info: String?, on: () -> Unit) {
                val r = LinearLayout(act).apply {
                    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                    minimumHeight = dp(56); setPadding(dp(8), dp(6), dp(8), dp(6))
                    background = round(Color.TRANSPARENT, 14)
                    setOnClickListener { dlg.dismiss(); on() }
                }
                press(r)
                r.addView(lead, LinearLayout.LayoutParams(dp(36), dp(36)).apply { marginEnd = dp(14) })
                val t = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
                t.addView(TextView(act).apply { text = label; textSize = 16f; maxLines = 1; ellipsize = TextUtils.TruncateAt.END; setTextColor(c(R.color.mg_text)) })
                if (info != null) t.addView(TextView(act).apply { text = info; textSize = 12f; setTextColor(c(R.color.mg_text_dim)) })
                r.addView(t, LinearLayout.LayoutParams(0, WRAP, 1f))
                col.addView(r, LinearLayout.LayoutParams(MATCH, WRAP))
            }
            val plus = FrameLayout(act).apply { background = round(tonal(c(R.color.mg_primary), 0.35f), 18) }
            plus.addView(ImageView(act).apply { setImageResource(R.drawable.ic_add); setColorFilter(c(R.color.mg_primary)) }, FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER))
            addRow(plus, "New group", null) { onNew() }
            for (n in names) {
                if (groups[n]?.containsAll(ids) == true) continue // already there
                val k = groups[n]?.size ?: 0
                val dot = View(act).apply { background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(colorOf(n)) } }
                addRow(dot, n, k.toString() + (if (k == 1) " tab" else " tabs")) { addTo(n, ids) }
            }
            val sv = ScrollView(act).apply { addView(col) }
            dlg.setContentView(sv)
            dlg.show()
        }

        private fun addTo(name: String, ids: Set<Int>) {
            endSelect(false) // 0.0.362 (Chrome): stay on the grid, cards glide, Undo bar (moveInto)
            pendingGlide = snapPos()
            bornFrom = ids.firstNotNullOfOrNull { pendingGlide?.get(it) } // 0.0.364: new group card grows out of the first tab card
            moveInto(name, ids)
        }

        private fun ungroup() {
            val ids = HashSet(picked)
            val snap = LinkedHashMap<String, List<Int>>() // 0.0.362: whole group order + colour, for Undo
            val cols = HashMap<String, Int>()
            for ((n, g) in groups) if (g.any { it in ids }) { snap[n] = g.toList(); colors[n]?.let { cols[n] = it } }
            for (g in groups.values) g.removeAll(ids)
            endSelect(false)
            render()
            if (snap.isNotEmpty()) undo((if (ids.size == 1) "1 tab" else ids.size.toString() + " tabs") + " ungrouped") {
                for ((n, l) in snap) { groups[n] = LinkedHashSet<Int>(l); cols[n]?.let { colors[n] = it } }
                animateNext = false
                render()
            }
        }

        private fun renameGroup(old: String) {
            groupDialog("Edit group name", old, colorOf(old), "Save") { n, col ->
                val nn = n.ifBlank { old }
                if (nn != old && groups.containsKey(nn)) { Util.toast(act, "A group with this name exists"); return@groupDialog }
                val ids = groups.remove(old) ?: return@groupDialog
                groups[nn] = ids
                colors.remove(old)
                colors[nn] = col
                if (openGroup == old) openGroup = nn
                render()
            }
        }

        private fun recolor(name: String) {
            groupDialog("Group colour", null, colorOf(name), "Save") { _, col -> colors[name] = col; render() }
        }

        private fun closeGroup(name: String) {
            val ids = groups[name] ?: return
            closeList(tm.allTabs().filter { it.id in ids }, "Group closed") // 0.0.362
        }

        private fun deleteGroup(name: String) {
            val ids = groups[name]?.toList() ?: emptyList() // 0.0.362: Undo brings the group back (same tabs, order, colour)
            val col = colors[name]
            groups.remove(name)
            colors.remove(name)
            if (openGroup == name) { openGroup = null; mode = backMode }
            render()
            if (ids.isEmpty()) return
            if (!AppearancePrefs.undoToast(act)) Util.toast(act, "Group deleted, tabs kept")
            undo("Group deleted") {
                if (!groups.containsKey(name)) {
                    for (g in groups.values) g.removeAll(ids.toSet())
                    groups[name] = LinkedHashSet<Int>(ids)
                    if (col != null) colors[name] = col
                }
                animateNext = false
                render()
            }
        }

        private fun closeList(list: List<TabManager.Tab>, msg: String? = null) {
            if (list.isEmpty()) return
            if (TabPrefs.confirmClose(act)) { // S21f: Settings > Tabs > Ask before Close all
                MaterialAlertDialogBuilder(act)
                    .setMessage("Close " + list.size + " tab" + (if (list.size == 1) "" else "s") + "?")
                    .setPositiveButton("Close") { _, _ -> doCloseList(list, msg) }
                    .setNegativeButton("Cancel", null)
                    .show()
            } else doCloseList(list, msg)
        }

        private fun doCloseList(list: List<TabManager.Tab>, msg: String? = null) {
            // S21d: no confirm dialog: close at once and offer Undo (http tabs come back at the same place and group)
            class Snap(val url: String, val idx: Int, val g: String?, val col: Int?, val gi: Int = -1)
            val all = tm.allTabs()
            val snaps = ArrayList<Snap>()
            for (t in list) {
                val url = t.web.url ?: t.url
                if (t.priv || t.isExt || t.home || !url.startsWith("http")) continue
                val g = groupOf(t)
                snaps.add(Snap(url, all.indexOf(t), g, g?.let { colors[it] }, g?.let { groups[it]?.toList()?.indexOf(t.id) } ?: -1))
            }
            snaps.sortBy { it.idx }
            val n = list.size
            val onlyAll = n == all.size
            for (t in list) tm.close(t)
            endSelect(false)
            render()
            if (snaps.isNotEmpty()) undo(msg ?: ((if (n == 1) "1 tab" else "$n tabs") + " closed")) {
                val back = HashMap<String, ArrayList<Pair<Int, Int>>>() // 0.0.362: group -> (old place in the group, new tab id)
                for (s in snaps) {
                    val nt = tm.newTab(s.url, 0, false, false) ?: continue
                    ClosedTabs.remove(s.url)
                    tm.moveTo(nt, s.idx)
                    if (s.g != null) {
                        back.getOrPut(s.g) { ArrayList() }.add(Pair(s.gi, nt.id))
                        if (s.col != null && !colors.containsKey(s.g)) colors[s.g] = s.col
                    }
                }
                for ((gn, l) in back) {
                    val cur = ArrayList(groups[gn] ?: emptySet<Int>())
                    for ((gi, id) in l.sortedBy { it.first }) cur.add(if (gi < 0) cur.size else gi.coerceIn(0, cur.size), id)
                    groups[gn] = LinkedHashSet<Int>(cur)
                }
                if (onlyAll) tm.allTabs().filter { it.home && !it.priv }.forEach { h -> if (tm.allTabs().size > 1) tm.close(h) } // the Home tab made by closing everything
                pendingGlide = null
                render()
            }
        }

        /** 0.0.287: extra tabs that show the same page as an earlier one (same address, # part and trailing / ignored). The open tab is the one kept. */
        private fun dupTabs(): List<TabManager.Tab> {
            fun norm(t: TabManager.Tab): String = (t.web.url ?: t.url).substringBefore('#').trimEnd('/')
            val seen = HashMap<String, TabManager.Tab>()
            val out = ArrayList<TabManager.Tab>()
            val cur = tm.active
            for (t in shown) {
                if (t.home || t.isExt || !(t.web.url ?: t.url).startsWith("http")) continue
                val k = norm(t)
                val first = seen[k]
                if (first == null) { seen[k] = t; continue }
                if (t === cur) { out.add(first); seen[k] = t } else out.add(t)
            }
            return out
        }

        /** 0.0.378: reload every open page of this kind (normal or private), one after the other (150 ms apart). Sleeping / home / extension tabs are left alone. */
        private fun reloadAll() {
            val list = tm.allTabs().filter { (if (mode == 1) it.priv else !it.priv) && !it.home && !it.isExt && !it.suspended && it.pending == null && (it.web.url ?: it.url).startsWith("http") }
            if (list.isEmpty()) { Util.toast(act, "No open pages to reload"); return }
            val h = android.os.Handler(android.os.Looper.getMainLooper())
            list.forEachIndexed { i, t ->
                h.postDelayed({ try { if (!t.suspended && tm.allTabs().contains(t)) t.web.reload() } catch (e: Exception) { } }, i * 150L)
            }
            Util.toast(act, "Reloading " + list.size + " tab" + (if (list.size == 1) "" else "s"))
        }

        private fun closeAll() = closeList(shown)
        private fun closePicked() = closeList(shown.filter { it.id in picked })


        // ---- S44: drag a tab onto a group (or onto another tab = new group)

        private fun canDrag() = (mode == 0 || (mode == 2 && openGroup != null)) && query.isBlank() && !selecting && !searching // S75: also inside a group

        private val scroller = object : Runnable {
            override fun run() {
                val d = dragView ?: return
                val loc = IntArray(2)
                scroll.getLocationOnScreen(loc)
                val edge = dp(90)
                val dy = when {
                    lastY < loc[1] + edge -> -dp(9)
                    lastY > loc[1] + scroll.height - edge -> dp(9)
                    else -> 0
                }
                if (dy != 0) {
                    val before = scroll.scrollY
                    scroll.scrollBy(0, dy)
                    val moved = scroll.scrollY - before
                    if (moved != 0) { dragShift += moved; d.translationY += moved; updateHover(d) }
                }
                scroll.postDelayed(this, 16)
            }
        }

        private fun hitTarget(rx: Float, ry: Float, self: View): Pair<View, Any>? {
            val loc = IntArray(2)
            for (p in targets) {
                val v = p.first
                if (v === self) continue
                v.getLocationOnScreen(loc)
                if (rx >= loc[0] && rx <= loc[0] + v.width && ry >= loc[1] && ry <= loc[1] + v.height) return p
            }
            return null
        }

        private fun ring(v: View) {
            v.animate().scaleX(1.06f).scaleY(1.06f).setDuration(ms(110)).start()
            if (v === zone) v.background = round(tint(), 16, 2, c(R.color.mg_primary)) // S75
            (v as? FrameLayout)?.foreground = round(Color.TRANSPARENT, 18, 3, c(R.color.mg_primary))
        }

        private fun unring(v: View) {
            v.animate().scaleX(1f).scaleY(1f).setDuration(ms(110)).start()
            if (v === zone) v.background = round(Color.TRANSPARENT, 16, 2, c(R.color.mg_primary)) // S75
            (v as? FrameLayout)?.foreground = null
        }

        private fun updateHover(self: View) {
            val tg = hitTarget(lastX, lastY, self)
            if (tg?.first === hover?.first) return
            hover?.first?.let { unring(it) }
            hover = tg
            tg?.first?.let { ring(it) }
        }

        private fun dragTouch(box: View, t: TabManager.Tab) {
            var downX = 0f
            var downY = 0f
            var armed = false
            var dragging = false
            var swiping = false // S21c: swipe a card sideways = close it
            val slop = ViewConfiguration.get(act).scaledTouchSlop
            val lift = Runnable {
                armed = true
                box.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                if (canDrag()) {
                    dragging = true
                    dragView = box
                    dragShift = 0f
                    (box.parent as? View)?.translationZ = dp(10).toFloat() // this row draws above the other rows
                    box.parent?.requestDisallowInterceptTouchEvent(true)
                    box.translationZ = dp(12).toFloat()
                    box.animate().scaleX(1.08f).scaleY(1.08f).alpha(0.94f).setDuration(150).setInterpolator(android.view.animation.PathInterpolator(0.2f, 0f, 0f, 1f)).start() // S16
                    if (openGroup != null) showZone() // S75
                    scroll.post(scroller)
                } else if (!selecting) tabMenu(box, t)
            }
            fun settle(v: View) {
                v.animate().translationX(0f).translationY(0f).scaleX(1f).scaleY(1f).alpha(1f)
                    .setInterpolator(OvershootInterpolator(0.9f)).setDuration(ms(260)) // S16
                    .withEndAction { v.translationZ = 0f; (v.parent as? View)?.translationZ = 0f }.start()
            }
            box.setOnTouchListener { v, e ->
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downX = e.rawX; downY = e.rawY; lastX = downX; lastY = downY
                        armed = false; dragging = false; swiping = false
                        v.animate().scaleX(0.96f).scaleY(0.96f).setDuration(80).start()
                        v.postDelayed(lift, ViewConfiguration.getLongPressTimeout().toLong())
                        false
                    }
                    MotionEvent.ACTION_MOVE -> {
                        lastX = e.rawX; lastY = e.rawY
                        if (dragging) {
                            v.translationX = e.rawX - downX
                            v.translationY = e.rawY - downY + dragShift
                            updateHover(v)
                            true
                        } else if (swiping) {
                            val dx = e.rawX - downX
                            v.translationX = dx
                            v.alpha = 1f - minOf(1f, abs(dx) / (v.width * 0.9f))
                            true
                        } else if (!armed && !selecting && abs(e.rawX - downX) > slop * 2 && abs(e.rawX - downX) > abs(e.rawY - downY) * 1.6f && TabPrefs.swipeClose(act)) {
                            swiping = true
                            v.removeCallbacks(lift)
                            v.parent?.requestDisallowInterceptTouchEvent(true)
                            v.animate().cancel()
                            v.animate().scaleX(1f).scaleY(1f).setDuration(100).start()
                            true
                        } else {
                            if (!armed && (abs(e.rawX - downX) > slop || abs(e.rawY - downY) > slop)) {
                                v.removeCallbacks(lift)
                                v.animate().scaleX(1f).scaleY(1f).setDuration(100).start()
                            }
                            armed // after the menu opened (no drag) swallow the rest
                        }
                    }
                    MotionEvent.ACTION_UP -> {
                        v.removeCallbacks(lift)
                        if (swiping) {
                            swiping = false
                            val dx = e.rawX - downX
                            if (abs(dx) > v.width * 0.35f && !t.pinned) { // 0.0.296: a pinned tab does not swipe away
                                v.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                                closeAnimated(v, t, if (dx > 0) 1f else -1f)
                            } else settle(v)
                            true
                        } else if (dragging) {
                            dragging = false
                            dragView = null
                            scroll.removeCallbacks(scroller)
                            val tg = hover
                            hover = null
                            if (tg != null) dropOn(v, t, tg)
                            else if (hypot(e.rawX - downX, e.rawY - downY) < slop * 2) { settle(v); tabMenu(v, t) }
                            else settle(v)
                            hideZone() // S75
                            true
                        } else {
                            if (!armed) v.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
                            armed // armed = the menu is open: no click
                        }
                    }
                    MotionEvent.ACTION_CANCEL -> {
                        v.removeCallbacks(lift)
                        if (swiping) { swiping = false; settle(v) }
                        if (dragging) {
                            dragging = false
                            dragView = null
                            scroll.removeCallbacks(scroller)
                            hover?.first?.let { unring(it) }
                            hover = null
                            hideZone() // S75
                            settle(v)
                        } else v.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
                        false
                    }
                    else -> false
                }
            }
        }

        /** The dragged card flies into the target and shrinks away, then the lists are rebuilt. */
        private fun dropOn(v: View, t: TabManager.Tab, tg: Pair<View, Any>) {
            val over = tg.second
            if (over is TabManager.Tab && openGroup != null) { // S16: reorder = no fly-away, the dropped card and its neighbours glide to the new order
                pendingGlide = snapPos()
                liftKey = t.id
                reorder(t.id, over.id)
                return
            }
            val a = IntArray(2)
            val b = IntArray(2)
            v.getLocationOnScreen(a)
            tg.first.getLocationOnScreen(b)
            val dx = (b[0] + tg.first.width / 2f) - (a[0] + v.width / 2f)
            val dy = (b[1] + tg.first.height / 2f) - (a[1] + v.height / 2f)
            v.animate().translationX(v.translationX + dx).translationY(v.translationY + dy).scaleX(0.3f).scaleY(0.3f).alpha(0f)
                .setInterpolator(EASE).setDuration(ms(200)).withEndAction { // 0.0.364 (P3): shrinks INTO the target
                    pendingGlide = snapPos() // S16: the remaining cards close the gap smoothly
                    val target = tg.second
                    if (target is TabManager.Tab) bornFrom = pendingGlide?.get(target.id) // 0.0.364: a new group grows out of the target tab card
                    if (target is String) pulseKey = "g:$target"
                    if (target === ZONE) pullOut(t.id) // S75
                    else if (target is String) moveInto(target, setOf(t.id))
                    else if (target is TabManager.Tab && openGroup != null) reorder(t.id, target.id) // S77
                    else if (target is TabManager.Tab) {
                        groupDialog("New tab group", "Group " + (groups.size + 1), nextColor(), "Save", { render() }) { n, col ->
                            val name = n.ifBlank { "Group " + (groups.size + 1) }
                            if (!groups.containsKey(name)) colors[name] = col
                            moveInto(name, setOf(t.id, target.id))
                        }
                    } else render()
                }.start()
        }

        /** Puts the tabs into a group and stays on the tab grid (no jump into the group). S75: Undo bar instead of a toast. */
        private fun moveInto(name: String, ids: Set<Int>) {
            val snaps = snapGroups(ids, name) // 0.0.363 (P2): whole old groups (order + colour), not only the moved ids
            val hadGroup = groups.containsKey(name)
            for (g in groups.values) g.removeAll(ids)
            groups.getOrPut(name) { LinkedHashSet() }.addAll(ids)
            if (!hadGroup) bornKey = "g:$name" else pulseKey = "g:$name" // 0.0.364 (P3)
            animateNext = false
            render()
            val gl = if (name.length > 18) name.take(17) + "\u2026" else name
            undo((if (ids.size == 1) "1 tab" else ids.size.toString() + " tabs") + " added to " + gl) { // 0.0.362: count of tabs added, not group size
                restoreGroups(snaps, if (!hadGroup) name else null) { tm.byId(it) != null }
                animateNext = false
                render()
            }
        }

        // ---- S75: pull a tab out of a group (drop zone + undo bar, like the Brave switcher)

        /** S77: drop a tab on another tab inside a group = it takes that place (order is saved with the session). */
        private fun reorder(id: Int, over: Int) {
            val g = groups[openGroup ?: return] ?: return
            val list = g.toMutableList()
            val oi = list.indexOf(id)
            val ti = list.indexOf(over)
            if (oi < 0 || ti < 0 || oi == ti) { render(); return }
            list.removeAt(oi)
            val at = list.indexOf(over)
            list.add(if (oi < ti) at + 1 else at, id)
            g.clear()
            g.addAll(list)
            animateNext = false
            render()
        }

        private fun tint(): Int = (c(R.color.mg_primary) and 0x00FFFFFF) or 0x33000000

        private fun showZone() {
            if (zone != null) return
            val z = TextView(act).apply {
                text = "Remove tab from group"
                gravity = Gravity.CENTER
                textSize = 14f
                setTextColor(c(R.color.mg_primary))
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                background = round(Color.TRANSPARENT, 16, 2, c(R.color.mg_primary))
                elevation = dp(2).toFloat()
                alpha = 0f
                translationY = dp(24).toFloat()
            }
            body.addView(z, FrameLayout.LayoutParams(MATCH, dp(56), Gravity.BOTTOM).apply { setMargins(dp(16), 0, dp(16), dp(12)) })
            z.animate().alpha(1f).translationY(0f).setInterpolator(android.view.animation.DecelerateInterpolator(1.6f)).setDuration(200).start()
            zone = z
            targets.add(z to ZONE)
        }

        private fun hideZone() {
            val z = zone ?: return
            zone = null
            targets.removeAll { it.first === z }
            z.animate().alpha(0f).translationY(dp(24).toFloat()).setDuration(160).withEndAction { body.removeView(z) }.start()
        }

        private fun pullOut(id: Int) {
            val name = groups.entries.firstOrNull { id in it.value }?.key
            val col = if (name != null) colors[name] else null
            for (g in groups.values) g.remove(id)
            animateNext = false
            render()
            if (name == null) return
            undo("Tab removed from group") {
                groups.getOrPut(name) { LinkedHashSet() }.add(id)
                if (col != null) colors[name] = col
                animateNext = false
                render()
            }
        }

        private fun undo(msg: String, onUndo: () -> Unit) {
            hideUndo()
            if (!AppearancePrefs.undoToast(act)) return // A8: "Show undo toast" OFF = the close / group change already happened, just no bar
            val b = SwipeAway.Bar(act, TabPrefs.reduce(act)).apply { // 0.0.281: swipe the bar away
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(16), 0, dp(4), 0)
                background = round(c(R.color.mg_surface_variant), 16, 1, c(R.color.mg_outline))
                elevation = dp(8).toFloat()
                alpha = 0f
                translationY = dp(20).toFloat()
            }
            b.addView(TextView(act).apply {
                text = msg
                textSize = 14f
                setTextColor(c(R.color.mg_text))
            }, LinearLayout.LayoutParams(0, WRAP, 1f))
            b.addView(TextView(act).apply {
                text = "Undo"
                textSize = 14f
                setTextColor(c(R.color.mg_primary))
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setPadding(dp(14), dp(14), dp(14), dp(14))
                setOnClickListener { hideUndo(); onUndo() }
            }, LinearLayout.LayoutParams(WRAP, WRAP))
            body.addView(b, FrameLayout.LayoutParams(MATCH, dp(52), Gravity.BOTTOM).apply { setMargins(dp(14), 0, dp(14), dp(12)) })
            b.animate().alpha(1f).translationY(0f).setInterpolator(android.view.animation.DecelerateInterpolator(1.6f)).setDuration(200).start()
            undoBar = b
            pendingUndo = Pair(msg, onUndo) // 0.0.365
            b.onGone = { if (undoBar === b) hideUndo() }
            val autoHide = object : Runnable { // 0.0.281: not while a finger is dragging the bar
                override fun run() { if (undoBar !== b) return; if (b.busy) b.postDelayed(this, 1500) else hideUndo(true) }
            }
            b.postDelayed(autoHide, 5000)
        }

        private fun hideUndo(anim: Boolean = false) {
            val b = undoBar ?: return
            undoBar = null
            pendingUndo = null // 0.0.365
            if (anim) b.animate().alpha(0f).translationY(dp(20).toFloat()).setDuration(160).withEndAction { body.removeView(b) }.start()
            else body.removeView(b)
        }

        private fun newGroup() {
            groupDialog("New tab group", "Group " + (groups.size + 1), nextColor(), "Save") { n, col ->
                val name = n.ifBlank { "Group " + (groups.size + 1) }
                if (groups.containsKey(name)) { Util.toast(act, "A group with this name exists"); return@groupDialog }
                colors[name] = col
                val t = tm.newTab(TabManager.HOME_PAGE, 0, true, false)
                if (t != null) groups[name] = LinkedHashSet<Int>().apply { add(t.id) }
                dismiss()
            }
        }

        // ---- cards

        private fun avatar(t: TabManager.Tab, sizeDp: Int, sp: Float): View {
            val host = if (t.home) "" else Util.host(t.url)
            val seed = if (t.home) "thazir" else host
            val hue = (abs(seed.hashCode()) % 360).toFloat()
            val letter = if (t.home) "T" else (host.firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()?.toString() ?: "?")
            val fav = if (t.home || t.priv) null else Favicons.get(host) // S45
            if (fav != null) return ImageView(act).apply {
                setImageBitmap(fav)
                scaleType = ImageView.ScaleType.FIT_CENTER
                val pd = dp(sizeDp) / 6
                setPadding(pd, pd, pd, pd)
                background = GradientDrawable().apply { shape = GradientDrawable.RECTANGLE; cornerRadius = dp(sizeDp) / 4f; setColor(Color.argb(235, 255, 255, 255)) } // 0.0.295: rounded square
                layoutParams = ViewGroup.LayoutParams(dp(sizeDp), dp(sizeDp))
            }
            return TextView(act).apply {
                text = letter
                gravity = Gravity.CENTER
                textSize = sp
                setTextColor(Color.WHITE)
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.HSVToColor(floatArrayOf(hue, 0.5f, 0.72f))) }
                layoutParams = ViewGroup.LayoutParams(dp(sizeDp), dp(sizeDp))
            }
        }

        /** Page picture scaled to the width [wPx] (top of the page), or a big coloured letter while there is no picture yet. */
        private fun previewOf(t: TabManager.Tab, wPx: Int, bigLetter: Boolean): View {
            val th = thumbs[t.id]
            if (th != null && !t.home) {
                return ImageView(act).apply {
                    setImageBitmap(th)
                    scaleType = ImageView.ScaleType.MATRIX
                    imageMatrix = Matrix().apply { val sc = wPx.toFloat() / th.width; setScale(sc, sc) }
                }
            }
            val f = FrameLayout(act)
            f.setBackgroundColor(c(R.color.mg_surface)) // 0.0.295: the card around it is the variant tone
            val s = if (bigLetter) 56 else 18
            f.addView(avatar(t, s, if (bigLetter) 26f else 9f), FrameLayout.LayoutParams(dp(s), dp(s), Gravity.CENTER))
            return f
        }

        private fun card(t: TabManager.Tab): View {
            val host = if (t.home) "" else Util.host(t.url)
            val title = if (t.home) (if (t.priv) "Private home" else "Home") else t.title.ifBlank { host }.ifBlank { "New tab" }
            val isCur = t === tm.active
            val hi = isCur && !selecting
            val on = selecting && t.id in picked
            val box = FrameLayout(act).apply {
                background = if (on || hi) round(tonal(), 16, 3, c(R.color.mg_primary)) else round(c(R.color.mg_surface_variant), 16) // 0.0.295: Chrome look = open / picked tab tonal + accent ring, others plain card, no outline
                clipToOutline = true
                contentDescription = if (hi) "$title, current tab" else title // S4
                setOnClickListener {
                    if (selecting) { if (on) picked.remove(t.id) else picked.add(t.id); render() }
                    else { tm.switchTo(t); dismiss() }
                }
            }
            box.tag = t.id // S16: key for the glide animation
            dragTouch(box, t) // S44: long press = lift the card (drag into a group) or the menu
            val pv = FrameLayout(act).apply { background = round(c(R.color.mg_surface), 12); clipToOutline = true } // 0.0.295: page picture inset 4 dp with round corners (Chrome)
            pv.addView(previewOf(t, cardW - dp(8), true), FrameLayout.LayoutParams(MATCH, MATCH))
            box.addView(pv, FrameLayout.LayoutParams(MATCH, MATCH).apply { setMargins(dp(4), dp(HEAD_H), dp(4), dp(4)) })
            val fg = c(R.color.mg_text) // 0.0.295: same title colour on every card, the tonal fill shows which one is open
            val head = LinearLayout(act).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(10), 0, dp(4), 0) // 0.0.295: header is transparent, the card colour shows
            }
            head.addView(avatar(t, 18, 9f), LinearLayout.LayoutParams(dp(18), dp(18)).apply { marginEnd = dp(6) })
            head.addView(TextView(act).apply {
                text = title
                textSize = 13f
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                setTextColor(fg)
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            }, LinearLayout.LayoutParams(0, WRAP, 1f))
            if (!t.home && !t.isExt && SiteMute.isMuted(act, host)) head.addView(ImageView(act).apply { setImageResource(R.drawable.ic_mute); setColorFilter(c(R.color.mg_text_dim)); contentDescription = "Muted" }, LinearLayout.LayoutParams(dp(16), dp(16)).apply { marginEnd = dp(4) }) // 0.0.296
            val unread = if (t.home) 0 else SocialBadge.count(act, t.url, t.title) // 0.0.231 (P7): unread number from the page title
            if (unread > 0) {
                head.addView(TextView(act).apply {
                    gravity = Gravity.CENTER
                    text = SocialBadge.label(unread)
                    textSize = 10f
                    typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                    setTextColor(c(R.color.mg_on_primary))
                    background = round(c(R.color.mg_primary), 9)
                    minWidth = dp(18)
                    setPadding(dp(5), 0, dp(5), 0)
                    contentDescription = "$unread unread"
                }, LinearLayout.LayoutParams(WRAP, dp(18)).apply { marginEnd = dp(4) })
            }
            if (selecting) {
                head.addView(TextView(act).apply {
                    gravity = Gravity.CENTER
                    text = if (on) "✓" else ""
                    textSize = 13f
                    setTextColor(c(R.color.mg_on_primary))
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        if (on) setColor(c(R.color.mg_primary)) else { setColor(0x33000000); setStroke(dp(2), c(R.color.mg_text_dim)) }
                    }
                }, LinearLayout.LayoutParams(dp(22), dp(22)).apply { marginEnd = dp(6) })
            } else {
                head.addView(ImageView(act).apply {
                    setImageResource(if (t.pinned) R.drawable.ic_pin else R.drawable.ic_close) // 0.0.296: pinned = pin icon, no X
                    setColorFilter(fg)
                    scaleType = ImageView.ScaleType.CENTER
                    contentDescription = "Close $title" // S4
                    if (!t.pinned) setOnClickListener { closeAnimated(box, t) }
                }, LinearLayout.LayoutParams(dp(HEAD_H), dp(HEAD_H))) // S4: 36dp touch target (was 30)
            }
            box.addView(head, FrameLayout.LayoutParams(MATCH, dp(HEAD_H), Gravity.TOP))
            // 0.0.295: the Thazir rail + host chip on the picture are gone (Chrome has neither); group colour lives on the group card
            return box
        }

        /** 0.0.281: list view row. Same click / drag / close behaviour as card(); adds the Thazir rail, host + lock cue, asleep / age text. */
        private fun row(t: TabManager.Tab): View {
            val host = if (t.home) "" else Util.host(t.url)
            val title = if (t.home) (if (t.priv) "Private home" else "Home") else t.title.ifBlank { host }.ifBlank { "New tab" }
            val hi = t === tm.active && !selecting
            val on = selecting && t.id in picked
            val fill = if (on || hi) tonal() else c(R.color.mg_surface_variant) // 0.0.295
            val box = FrameLayout(act).apply {
                background = if (on || hi) round(fill, 16, 3, c(R.color.mg_primary)) else round(fill, 16)
                contentDescription = if (hi) "$title, current tab" else title
                setOnClickListener {
                    if (selecting) { if (on) picked.remove(t.id) else picked.add(t.id); render() }
                    else { tm.switchTo(t); dismiss() }
                }
            }
            box.tag = t.id
            dragTouch(box, t)
            val line = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(10), 0, dp(4), 0) }
            line.addView(avatar(t, 32, 14f), LinearLayout.LayoutParams(dp(32), dp(32)).apply { marginEnd = dp(12) })
            val col = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
            col.addView(TextView(act).apply {
                text = title; textSize = 14f; maxLines = 1; ellipsize = TextUtils.TruncateAt.END
                setTextColor(c(R.color.mg_text))
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            })
            val days = ((System.currentTimeMillis() - t.lastActive) / 86400000L).toInt()
            val extra = (if (t.suspended) " · asleep" else "") + (if (days >= 1 && t !== tm.active) " · ${days}d" else "")
            col.addView(TextView(act).apply {
                text = (if (t.home) "Thazir home" else host) + extra
                textSize = 12f; maxLines = 1; ellipsize = TextUtils.TruncateAt.END
                setTextColor(c(R.color.mg_text_dim))
                if (!t.home && !t.priv && (t.url.startsWith("https://") || t.url.startsWith("http://"))) {
                    val ok = t.url.startsWith("https://")
                    val d = androidx.core.content.ContextCompat.getDrawable(act, if (ok) R.drawable.ic_lock else R.drawable.ic_pl_lock_open)?.mutate()
                    if (d != null) { d.setBounds(0, 0, dp(11), dp(11)); d.setTint(c(if (ok) R.color.mg_success else R.color.mg_error)); setCompoundDrawablesRelative(d, null, null, null); compoundDrawablePadding = dp(4) }
                }
            })
            line.addView(col, LinearLayout.LayoutParams(0, WRAP, 1f))
            val unread = if (t.home) 0 else SocialBadge.count(act, t.url, t.title)
            if (unread > 0) {
                line.addView(TextView(act).apply {
                    gravity = Gravity.CENTER; text = SocialBadge.label(unread); textSize = 10f
                    typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                    setTextColor(c(R.color.mg_on_primary)); background = round(c(R.color.mg_primary), 9)
                    minWidth = dp(18); setPadding(dp(5), 0, dp(5), 0); contentDescription = "$unread unread"
                }, LinearLayout.LayoutParams(WRAP, dp(18)).apply { marginEnd = dp(4) })
            }
            if (selecting) {
                line.addView(TextView(act).apply {
                    gravity = Gravity.CENTER; text = if (on) "✓" else ""; textSize = 13f
                    setTextColor(c(R.color.mg_on_primary))
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        if (on) setColor(c(R.color.mg_primary)) else { setColor(0x33000000); setStroke(dp(2), c(R.color.mg_text_dim)) }
                    }
                }, LinearLayout.LayoutParams(dp(22), dp(22)).apply { marginEnd = dp(10) })
            } else {
                line.addView(ImageView(act).apply {
                    setImageResource(if (t.pinned) R.drawable.ic_pin else R.drawable.ic_close); setColorFilter(c(R.color.mg_text)) // 0.0.296
                    scaleType = ImageView.ScaleType.CENTER; contentDescription = "Close $title"
                    if (!t.pinned) setOnClickListener { closeAnimated(box, t) }
                }, LinearLayout.LayoutParams(dp(44), dp(44)))
            }
            box.addView(line, FrameLayout.LayoutParams(MATCH, MATCH))
            return box
        }

        /** 2 x 2 pictures of the first tabs; the 4th cell shows "+N" when there are more than 4. */
        private fun miniGrid(list: List<TabManager.Tab>, wPx: Int, gapDp: Int): View {
            val gap = dp(gapDp)
            val cell = ((wPx - gap * 3) / 2).coerceAtLeast(1)
            val col = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setPadding(gap, gap, gap, gap) }
            for (r in 0..1) {
                val row = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL }
                for (k in 0..1) {
                    val idx = r * 2 + k
                    val t = list.getOrNull(idx)
                    val v: View = when {
                        t == null -> View(act)
                        idx == 3 && list.size > 4 -> TextView(act).apply {
                            text = "+" + (list.size - 3)
                            gravity = Gravity.CENTER
                            textSize = 13f
                            setTextColor(c(R.color.mg_text_dim))
                            background = round(c(R.color.mg_surface_variant), 8)
                        }
                        else -> FrameLayout(act).apply {
                            background = round(c(R.color.mg_surface_variant), 8)
                            clipToOutline = true
                            addView(previewOf(t, cell, false), FrameLayout.LayoutParams(MATCH, MATCH))
                        }
                    }
                    row.addView(v, LinearLayout.LayoutParams(0, MATCH, 1f).apply { setMargins(gap / 2, gap / 2, gap / 2, gap / 2) })
                }
                col.addView(row, LinearLayout.LayoutParams(MATCH, 0, 1f))
            }
            return col
        }

        private fun groupCard(name: String): View {
            val ids = groups[name] ?: emptySet<Int>()
            val inside = tm.allTabs().filter { it.id in ids }
            val box = FrameLayout(act).apply {
                background = round(tonal(colorOf(name), 0.22f), 16) // 0.0.295: Chrome group card = container tinted with the group colour
                clipToOutline = true
                contentDescription = "Group $name, ${inside.size} tabs" // S4
                setOnClickListener { v -> openGroupView(name, v) }
                setOnLongClickListener { v -> v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS); groupMenu(v, name, false); true }
            }
            press(box)
            box.addView(miniGrid(inside, cardW, 6), FrameLayout.LayoutParams(MATCH, MATCH).apply { topMargin = dp(HEAD_H) })
            val head = LinearLayout(act).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(10), 0, dp(10), 0)
            }
            head.addView(View(act).apply { background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(colorOf(name)) } }, LinearLayout.LayoutParams(dp(14), dp(14)).apply { marginEnd = dp(8) })
            head.addView(TextView(act).apply {
                text = name
                textSize = 13f
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                setTextColor(c(R.color.mg_text))
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            }, LinearLayout.LayoutParams(0, WRAP, 1f))
            head.addView(TextView(act).apply {
                text = inside.size.toString()
                textSize = 12f
                setTextColor(c(R.color.mg_text_dim))
            })
            box.addView(head, FrameLayout.LayoutParams(MATCH, dp(HEAD_H), Gravity.TOP))
            return box
        }

        private fun groupRow(name: String): View {
            val ids = groups[name] ?: emptySet<Int>()
            val inside = tm.allTabs().filter { it.id in ids }
            val row = LinearLayout(act).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = round(tonal(colorOf(name), 0.22f), 16) // 0.0.295
                setPadding(dp(10), dp(8), dp(4), dp(8))
                setOnClickListener { v -> openGroupView(name, v) }
            }
            press(row)
            val mini = FrameLayout(act).apply { background = round(c(R.color.mg_surface_variant), 12); clipToOutline = true }
            mini.addView(miniGrid(inside, dp(58), 3), FrameLayout.LayoutParams(MATCH, MATCH))
            row.addView(mini, LinearLayout.LayoutParams(dp(58), dp(58)).apply { marginEnd = dp(14) })
            val col = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
            val line = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            line.addView(View(act).apply { background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(colorOf(name)) } }, LinearLayout.LayoutParams(dp(12), dp(12)).apply { marginEnd = dp(8) })
            line.addView(TextView(act).apply {
                text = name
                textSize = 15f
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                setTextColor(c(R.color.mg_text))
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            })
            col.addView(line)
            col.addView(TextView(act).apply {
                text = inside.size.toString() + (if (inside.size == 1) " tab" else " tabs")
                textSize = 12f
                setTextColor(c(R.color.mg_text_dim))
            })
            row.addView(col, LinearLayout.LayoutParams(0, WRAP, 1f))
            row.addView(icon(R.drawable.ic_more, "Group menu") { groupMenu(it, name, false) }, LinearLayout.LayoutParams(dp(44), dp(44)))
            return row
        }
    }
}
