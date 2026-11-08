package com.jabra.mgb

import android.app.Activity
import android.app.Dialog
import android.content.ClipData
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.DragEvent
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/**
 * 0.0.123 (S14): "Customise menu" (like Brave). Full screen with a switch per row of the 3-dot menu.
 *  - Sections: Main menu, Quick tiles, Extensions (pin an installed extension into the menu), Page actions.
 *  - The key of a row is its label (MenuSheet.Item.key / Quick.key). Hidden keys are saved in prefs mgb_menu_custom "off".
 *    Pinned extension ids are saved in "pin". Default = everything shown, no extension pinned (the menu looks as before the update).
 *  - Only Customise menu can not be hidden (locked ON), so the user can never lock himself out. 0.0.339: Settings + How to use can be hidden (Settings stays in the pinned bar by default).
 *  - 0.0.341: rows can be dragged (hold a row): up / down = order (prefs ordG / ordP), onto a quick tile = replaces that tile (prefs qt). "Show quick tiles" switch = qoff.
 *  - A switch saves at once and does not rebuild the screen (smooth). The menu reads the prefs each time it opens.
 */
object MenuCustom {

    const val CUSTOMISE = "Customise menu"
    private const val PREFS = "mgb_menu_custom"
    private const val K_OFF = "off"
    private const val K_PIN = "pin"
    private const val SEP = "|"

    /** key = label shown in the menu (also the saved key). label = text in this screen if it differs. */
    class Entry(val key: String, val icon: Int, val locked: Boolean = false, val label: String = key)

    val QUICK = listOf(
        Entry("Bookmarks", R.drawable.ic_bookmark), Entry("History", R.drawable.ic_history),
        Entry("Downloads", R.drawable.ic_download), Entry("Grabber", R.drawable.ic_play)
    )
    val GENERAL = listOf(
        Entry("Command Center", R.drawable.ic_search), Entry("Media Library", R.drawable.ic_play), // 0.0.343: part of the order; 0.0.344: switchable like the other rows
        Entry("New private tab", R.drawable.ic_lock), Entry("Bookmark all tabs", R.drawable.ic_bookmark_border),
        Entry("Passwords", R.drawable.ic_lock), Entry("Vault", R.drawable.ic_vault), Entry("Recently closed tabs", R.drawable.ic_history), Entry("Saved pages", R.drawable.ic_folder), // T3
        Entry("Novel grabber", R.drawable.ic_book), Entry("Queue", R.drawable.ic_list),
        Entry("How to use", R.drawable.ic_info), Entry("Report center", R.drawable.ic_info), Entry("Settings", R.drawable.ic_settings),
        Entry(CUSTOMISE, R.drawable.ic_edit, true)
    )
    val PAGE = listOf(
        Entry("Find on page", R.drawable.ic_search), Entry("Desktop site", R.drawable.ic_desktop, false, "Desktop / Mobile site"),
        Entry("Clone tab", R.drawable.ic_copy), // T1
        Entry("Translate page", R.drawable.ic_search), Entry("Reader view", R.drawable.ic_list),
        Entry("Text size", R.drawable.ic_edit), Entry("Detected files", R.drawable.ic_download),
        Entry("Page info", R.drawable.ic_info), Entry("View page source", R.drawable.ic_code), Entry("Save page", R.drawable.ic_save), Entry("Add to home screen", R.drawable.ic_home), Entry("Install app", R.drawable.ic_add), Entry("Userscripts", R.drawable.ic_code), // T2, T3, T8
        Entry("Fill password", R.drawable.ic_lock),
        Entry("Email alias", R.drawable.ic_share), Entry("Privacy Guard", R.drawable.ic_shield)
    )

    private fun sp(c: Context) = c.getSharedPreferences(PREFS, 0)
    private fun list(c: Context, k: String): MutableSet<String> =
        sp(c).getString(k, "").orEmpty().split(SEP).filter { it.isNotEmpty() }.toMutableSet()
    private fun save(c: Context, k: String, s: Set<String>) { sp(c).edit().putString(k, s.joinToString(SEP)).apply() }
    private fun isLocked(key: String) = GENERAL.any { it.key == key && it.locked }

    /** true = the row is shown in the menu. Locked rows are always shown. */
    fun on(c: Context, key: String): Boolean = isLocked(key) || key !in list(c, K_OFF)
    fun setOn(c: Context, key: String, on: Boolean) {
        if (isLocked(key)) return
        val s = list(c, K_OFF)
        if (on) s.remove(key) else s.add(key)
        save(c, K_OFF, s)
    }
    fun pinned(c: Context, id: String): Boolean = id in list(c, K_PIN)
    fun setPinned(c: Context, id: String, on: Boolean) {
        val s = list(c, K_PIN)
        if (on) s.add(id) else s.remove(id)
        save(c, K_PIN, s)
    }
    fun reset(c: Context) { sp(c).edit().clear().apply() }

    // ---------- 0.0.341: row order + the 4 quick tile slots ----------
    // Any row of Main menu / Page actions can sit in a tile (tile = slot key). The key leaves the list while it is in a tile,
    // and comes back to its own list (same place when both swap inside one list) when another key takes the tile.
    private const val K_QT = "qt"
    private const val K_QOFF = "qoff"
    private fun ordKey(id: Char) = if (id == 'P') "ordP" else "ordG"
    private fun allG(): List<Entry> = QUICK + GENERAL // QUICK first: the 4 old tiles have no row of their own until a tile gives them up
    private fun defaultOrder(id: Char): List<String> = if (id == 'P') PAGE.map { it.key } else allG().map { it.key }
    fun entryOf(key: String): Entry? = allG().firstOrNull { it.key == key } ?: PAGE.firstOrNull { it.key == key }
    fun homeOf(key: String): Char = if (PAGE.any { it.key == key }) 'P' else 'G'
    fun tileable(key: String): Boolean = key != CUSTOMISE && entryOf(key) != null

    /** the 4 keys in the tile slots; anything invalid (size, duplicate, unknown) = the 4 old tiles. */
    fun quickKeys(c: Context): List<String> {
        val l = sp(c).getString(K_QT, "").orEmpty().split(SEP).filter { it.isNotEmpty() }
        return if (l.size == 4 && l.toSet().size == 4 && l.all { tileable(it) }) l else QUICK.map { it.key }
    }
    fun quickShown(c: Context): Boolean = !sp(c).getBoolean(K_QOFF, false)
    fun setQuickShown(c: Context, on: Boolean) { sp(c).edit().putBoolean(K_QOFF, !on).apply() }

    /** all keys of list [id] ('G' main menu, 'P' page actions) in the saved order; new keys of a later update go to the end. */
    fun order(c: Context, id: Char): List<String> {
        val def = defaultOrder(id)
        val saved = sp(c).getString(ordKey(id), "").orEmpty().split(SEP).filter { it in def }.distinct()
        return saved + def.filter { it !in saved }
    }
    private fun saveOrder(c: Context, id: Char, l: List<String>) { sp(c).edit().putString(ordKey(id), l.joinToString(SEP)).apply() }

    /** keys the menu shows for list [id]: in order, not sitting in a tile, switched on. */
    fun menuKeys(c: Context, id: Char): List<String> {
        val q = quickKeys(c)
        return order(c, id).filter { it !in q && on(c, it) }
    }

    /** [key] takes the place of [target] in list [id]. */
    fun move(c: Context, id: Char, key: String, target: String) {
        val l = order(c, id).toMutableList()
        val t = l.indexOf(target)
        val s = l.indexOf(key)
        if (t < 0 || s < 0 || s == t) return
        l.removeAt(s)
        l.add(t.coerceAtMost(l.size), key)
        saveOrder(c, id, l)
    }

    /** [key] goes into tile [slot]. A key that is already in a tile swaps the two tiles; a key from a list sends the old tile key back. */
    fun toTile(c: Context, slot: Int, key: String) {
        if (slot !in 0..3 || !tileable(key)) return
        val q = quickKeys(c).toMutableList()
        val at = q.indexOf(key)
        if (at >= 0) { q[at] = q[slot]; q[slot] = key }
        else {
            val old = q[slot]
            q[slot] = key
            if (homeOf(old) == homeOf(key)) { // same list: the old tile key takes the place of the key that left
                val id = homeOf(key)
                val l = order(c, id).toMutableList()
                val i = l.indexOf(old)
                val j = l.indexOf(key)
                if (i >= 0 && j >= 0) { l[i] = key; l[j] = old; saveOrder(c, id, l) }
            }
        }
        sp(c).edit().putString(K_QT, q.joinToString(SEP)).apply()
    }

    // ---------- extension rows for the menu ----------
    private val bmpCache = HashMap<String, Bitmap?>()

    private fun iconOf(e: ExtRegistry.Info): Bitmap? {
        if (bmpCache.containsKey(e.id)) return bmpCache[e.id]
        val b = try {
            ExtTray.iconOf(e)?.let { if (it.width > 96) Bitmap.createScaledBitmap(it, 96, 96 * it.height / it.width.coerceAtLeast(1), true) else it }
        } catch (ex: Exception) { null }
        bmpCache[e.id] = b
        return b
    }

    private fun listable(act: Activity): List<ExtRegistry.Info> =
        ExtTray.ordered(act, ExtRegistry.enabled().filter { it.id != ContentInjector.EXT_ID })

    /** Rows of the extensions the user pinned (menu > Customise menu > Extensions). Tap = same as the tray tile. */
    fun pinnedRows(act: Activity): List<MenuSheet.Item> {
        val pins = list(act, K_PIN)
        if (pins.isEmpty()) return emptyList()
        return listable(act).filter { it.id in pins }.map { e ->
            MenuSheet.Item(e.name, R.drawable.ic_ext, true, "", "ext:" + e.id, iconOf(e)) { ExtTray.openExt(act, e) }
        }
    }

    // ---------- the screen ----------
    fun open(act: Activity) {
        if (act.isFinishing || act.isDestroyed) return
        val dlg = Dialog(act, com.google.android.material.R.style.Theme_Material3_DayNight_NoActionBar)
        fun dp(v: Int) = Util.dp(act, v)
        fun round(color: Int, r: Int) = GradientDrawable().apply { setColor(color); cornerRadius = dp(r).toFloat() }
        val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
        val cText = act.getColor(R.color.mg_text)
        val cDim = act.getColor(R.color.mg_text_dim)
        val cBg = act.getColor(R.color.mg_bg)
        val cCard = act.getColor(R.color.mg_surface)
        val cVar = act.getColor(R.color.mg_surface_variant)
        val cLine = act.getColor(R.color.mg_outline)
        val cAccent = act.getColor(R.color.mg_primary)
        val cOnAccent = act.getColor(R.color.mg_on_primary)
        val ripple = act.obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackground)).let {
            val d = it.getDrawable(0); it.recycle(); d
        }
        val refreshers = ArrayList<() -> Unit>() // re-read the prefs into the switches (Reset)
        var busy = false

        fun section(col: LinearLayout, title: String, note: String = "") {
            col.addView(TextView(act).apply {
                text = title
                textSize = 13f
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setTextColor(cDim)
                setPadding(dp(8), dp(20), dp(8), if (note.isEmpty()) dp(8) else dp(2))
            }, LinearLayout.LayoutParams(MATCH, WRAP))
            if (note.isNotEmpty()) col.addView(TextView(act).apply {
                text = note
                textSize = 12f
                setTextColor(cDim)
                setPadding(dp(8), 0, dp(8), dp(8))
            }, LinearLayout.LayoutParams(MATCH, WRAP))
        }
        fun card() = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            background = round(cCard, 26)
            setPadding(0, dp(6), 0, dp(6))
        }
        fun divider(c: LinearLayout) = c.addView(View(act).apply { setBackgroundColor(ColorUtils.setAlphaComponent(cLine, 0x80)) },
            LinearLayout.LayoutParams(MATCH, dp(1)).apply { marginStart = dp(54); marginEnd = dp(16) })

        /** one row: icon / bitmap / letter + label (+ small line) + switch. get / set talk to the prefs. */
        fun row(c: LinearLayout, icon: Int, bmp: Bitmap?, letterOf: String?, label: String, small: String, locked: Boolean,
                get: () -> Boolean, set: (Boolean) -> Unit, refs: MutableList<() -> Unit> = refreshers): LinearLayout {
            val sw = ThazirSwitch(act).apply { // APPEARANCE A1
                isChecked = get()
                isClickable = false
                isFocusable = false
                alpha = if (locked) 0.45f else 1f
            }
            val r = LinearLayout(act).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(16), 0, dp(12), 0)
                minimumHeight = dp(52)
                background = ripple?.constantState?.newDrawable()
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    if (locked) { Util.toast(act, "$label is always shown, so you can never lock yourself out"); return@setOnClickListener }
                    it.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    sw.isChecked = !sw.isChecked
                }
            }
            sw.setOnCheckedChangeListener { _, on -> if (!busy) set(on) }
            when {
                bmp != null -> r.addView(ImageView(act).apply { setImageBitmap(bmp); scaleType = ImageView.ScaleType.FIT_CENTER },
                    LinearLayout.LayoutParams(dp(24), dp(24)).apply { marginEnd = dp(16) })
                letterOf != null -> r.addView(TextView(act).apply {
                    text = label.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "?"
                    textSize = 13f
                    typeface = Typeface.DEFAULT_BOLD
                    gravity = Gravity.CENTER
                    setTextColor(android.graphics.Color.WHITE)
                    background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(ExtTray.tint(letterOf)) }
                }, LinearLayout.LayoutParams(dp(24), dp(24)).apply { marginEnd = dp(16) })
                else -> r.addView(ImageView(act).apply { setImageResource(icon); setColorFilter(cDim) },
                    LinearLayout.LayoutParams(dp(22), dp(22)).apply { marginEnd = dp(16); marginStart = dp(1) })
            }
            val t = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
            t.addView(TextView(act).apply {
                text = label
                textSize = 16f
                setTextColor(cText)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            })
            if (small.isNotEmpty()) t.addView(TextView(act).apply { text = small; textSize = 12f; setTextColor(cDim); maxLines = 1 })
            r.addView(t, LinearLayout.LayoutParams(0, WRAP, 1f))
            r.addView(sw, LinearLayout.LayoutParams(WRAP, WRAP))
            c.addView(r, LinearLayout.LayoutParams(MATCH, WRAP))
            refs.add { busy = true; sw.isChecked = get(); busy = false }
            return r
        }

        val body = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), 0, dp(12), dp(28)) }
        // ---- 0.0.341: drag and drop. Hold a row, drag it up / down (order) or onto a quick tile (replaces that tile).
        class DragSt(val key: String, val list: Char, val tile: Int)
        var scroller: ScrollView? = null
        val rowRefs = ArrayList<() -> Unit>() // switches of the re-drawn lists (Reset re-reads them)
        val tilesHost = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        val gHost = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        val pHost = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }

        fun hl(v: View, on: Boolean, tile: Boolean) {
            if (tile) v.background = round(if (on) ColorUtils.setAlphaComponent(cAccent, 0x40) else cVar, 18)
            else v.background = if (on) round(ColorUtils.setAlphaComponent(cAccent, 0x2A), 14) else ripple?.constantState?.newDrawable()
        }
        // auto scroll while a finger is near the top / bottom edge of the list
        var dragY = -1f
        var ticking = false
        val tick = object : Runnable {
            override fun run() {
                val s = scroller
                if (s == null || dragY < 0f) { ticking = false; return }
                val loc = IntArray(2)
                s.getLocationOnScreen(loc)
                val top = loc[1].toFloat()
                val bot = top + s.height
                val zone = dp(80).toFloat()
                val step = dp(16).toFloat()
                val dy = when {
                    dragY < top + zone -> -(((top + zone - dragY) / zone).coerceAtMost(1f) * step)
                    dragY > bot - zone -> ((dragY - (bot - zone)) / zone).coerceAtMost(1f) * step
                    else -> 0f
                }
                if (dy != 0f) s.scrollBy(0, dy.toInt())
                s.postDelayed(this, 16)
            }
        }
        fun track(v: View, e: DragEvent) {
            val l = IntArray(2)
            v.getLocationOnScreen(l)
            dragY = l[1] + e.y
            if (!ticking) { ticking = true; scroller?.post(tick) }
        }
        var redraw: () -> Unit = {}

        /** v can be picked up: long press starts the drag. */
        fun dragSource(v: View, key: String, list: Char, tile: Int) {
            v.setOnLongClickListener {
                it.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                if (Build.VERSION.SDK_INT >= 24) it.startDragAndDrop(ClipData.newPlainText("k", key), View.DragShadowBuilder(it), DragSt(key, list, tile), 0)
                true
            }
        }
        /** v accepts a drop: tile >= 0 = quick tile slot, else the row [rowKey] of list [list]. */
        fun dropTarget(v: View, tile: Int, rowKey: String?, list: Char) {
            fun ok(st: DragSt): Boolean = if (tile >= 0) (if (st.tile >= 0) st.tile != tile else tileable(st.key))
                else if (st.key == rowKey) false else if (st.tile >= 0) tileable(rowKey ?: "") else st.list == list
            v.setOnDragListener { _, e ->
                val st = e.localState as? DragSt
                when (e.action) {
                    DragEvent.ACTION_DRAG_STARTED -> {
                        if (st != null && (st.key == rowKey || (tile >= 0 && st.tile == tile))) v.alpha = 0.35f
                        st != null
                    }
                    DragEvent.ACTION_DRAG_ENTERED -> { if (st != null && ok(st)) hl(v, true, tile >= 0); true }
                    DragEvent.ACTION_DRAG_LOCATION -> { track(v, e); true }
                    DragEvent.ACTION_DRAG_EXITED -> { hl(v, false, tile >= 0); true }
                    DragEvent.ACTION_DROP -> {
                        hl(v, false, tile >= 0)
                        dragY = -1f
                        if (st != null && ok(st)) {
                            if (tile >= 0) toTile(act, tile, st.key)
                            else if (st.tile >= 0) toTile(act, st.tile, rowKey ?: "")
                            else move(act, list, st.key, rowKey ?: "")
                            v.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                            body.post { redraw() }
                            true
                        } else false
                    }
                    DragEvent.ACTION_DRAG_ENDED -> { v.alpha = 1f; hl(v, false, tile >= 0); dragY = -1f; true }
                    else -> true
                }
            }
        }

        fun listCard(host: LinearLayout, id: Char, q: List<String>) {
            val c = card()
            order(act, id).filter { it !in q }.mapNotNull { k -> entryOf(k) }.forEachIndexed { i, e ->
                if (i > 0) divider(c)
                val r = row(c, e.icon, null, null, e.label, if (e.locked) "Always shown" else "", e.locked,
                    { on(act, e.key) }, { setOn(act, e.key, it) }, rowRefs)
                dragSource(r, e.key, id, -1)
                dropTarget(r, -1, e.key, id)
            }
            host.addView(c, LinearLayout.LayoutParams(MATCH, WRAP))
        }

        fun drawAll() {
            dragY = -1f
            rowRefs.clear()
            tilesHost.removeAllViews(); gHost.removeAllViews(); pHost.removeAllViews()
            val q = quickKeys(act)
            val tc = card()
            row(tc, R.drawable.ic_list, null, null, "Show quick tiles", "", false, { quickShown(act) }, { setQuickShown(act, it) }, rowRefs)
            divider(tc)
            val strip = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(8), dp(8), dp(8), dp(8)) }
            q.forEachIndexed { slot, k ->
                val e = entryOf(k)
                val t = LinearLayout(act).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                    background = round(cVar, 18)
                    setPadding(dp(2), dp(12), dp(2), dp(10))
                    isClickable = true
                    setOnClickListener { Util.toast(act, "Hold a tile or a row, then drag") }
                }
                t.addView(ImageView(act).apply { setImageResource(e?.icon ?: R.drawable.ic_list); setColorFilter(cAccent) }, LinearLayout.LayoutParams(dp(24), dp(24)))
                t.addView(TextView(act).apply {
                    text = e?.label ?: k
                    textSize = 12f
                    setTextColor(cText)
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    gravity = Gravity.CENTER
                    setPadding(dp(4), dp(6), dp(4), 0)
                }, LinearLayout.LayoutParams(MATCH, WRAP))
                dragSource(t, k, homeOf(k), slot)
                dropTarget(t, slot, null, homeOf(k))
                strip.addView(t, LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginStart = dp(3); marginEnd = dp(3) })
            }
            tc.addView(strip, LinearLayout.LayoutParams(MATCH, WRAP))
            tilesHost.addView(tc, LinearLayout.LayoutParams(MATCH, WRAP))
            listCard(gHost, 'G', q)
            listCard(pHost, 'P', q)
        }
        redraw = { drawAll() }
        section(body, "Quick tiles", "The four round shortcuts at the top of the menu. Hold a row below and drag it onto a tile to put it there; the old tile goes back to its list. Hold a tile to swap it with another.")
        body.addView(tilesHost, LinearLayout.LayoutParams(MATCH, WRAP))
        section(body, "Main menu", "Hold a row, then drag it up or down to change the order.")
        body.addView(gHost, LinearLayout.LayoutParams(MATCH, WRAP))

        section(body, "Extensions", "Pin an installed extension to the menu. Tap it there to open its popup. Only turned-on extensions are listed.")
        val exts = listable(act)
        val ec = card()
        if (exts.isEmpty()) {
            ec.addView(TextView(act).apply {
                text = "No extension installed yet. Once you install one it shows up here."
                textSize = 14f
                setTextColor(cDim)
                setPadding(dp(16), dp(12), dp(16), dp(12))
            }, LinearLayout.LayoutParams(MATCH, WRAP))
        } else exts.forEachIndexed { i, e ->
            if (i > 0) divider(ec)
            val b = iconOf(e)
            row(ec, R.drawable.ic_ext, b, if (b == null) e.id else null, e.name, "v" + e.version, false,
                { pinned(act, e.id) }, { setPinned(act, e.id, it) })
        }
        body.addView(ec, LinearLayout.LayoutParams(MATCH, WRAP))

        section(body, "Page actions", "These rows only appear in the menu when the page that is open supports them. Hold a row and drag it to change the order.")
        body.addView(pHost, LinearLayout.LayoutParams(MATCH, WRAP))
        drawAll()

        body.addView(TextView(act).apply {
            text = "Reset to default"
            textSize = 14f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            gravity = Gravity.CENTER
            setTextColor(cAccent)
            background = round(ColorUtils.setAlphaComponent(cAccent, 0x26), 24)
            isClickable = true
            isFocusable = true
            setOnClickListener {
                it.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                reset(act)
                refreshers.forEach { f -> f() }
                drawAll()
                Util.toast(act, "Menu reset")
            }
        }, LinearLayout.LayoutParams(MATCH, dp(48)).apply { topMargin = dp(24) })

        // header: back, title, close
        val head = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), 0, dp(4), 0)
        }
        head.addView(ImageView(act).apply {
            setImageResource(R.drawable.ic_back)
            setColorFilter(cText)
            setPadding(dp(10), dp(10), dp(10), dp(10))
            contentDescription = "Back"
            background = ripple?.constantState?.newDrawable()
            setOnClickListener { dlg.dismiss() }
        }, LinearLayout.LayoutParams(dp(44), dp(48)))
        head.addView(TextView(act).apply {
            text = CUSTOMISE
            textSize = 20f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(cText)
            maxLines = 1
        }, LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginStart = dp(6) })
        head.addView(ImageView(act).apply {
            setImageResource(R.drawable.ic_close)
            setColorFilter(cText)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            contentDescription = "Close"
            background = ripple?.constantState?.newDrawable()
            setOnClickListener { dlg.dismiss() }
        }, LinearLayout.LayoutParams(dp(48), dp(48)))

        val sv = ScrollView(act).apply {
            overScrollMode = View.OVER_SCROLL_NEVER
            isVerticalScrollBarEnabled = false
            clipToPadding = false
        }
        scroller = sv
        sv.addView(body, ViewGroup.LayoutParams(MATCH, WRAP))
        val column = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        column.addView(head, LinearLayout.LayoutParams(MATCH, dp(56)))
        column.addView(sv, LinearLayout.LayoutParams(MATCH, 0, 1f))

        // wide screens (1280 px / landscape): centred column, max 560 dp
        val maxW = dp(560)
        val root = FrameLayout(act).apply { setBackgroundColor(cBg) }
        root.addView(column, FrameLayout.LayoutParams(
            minOf(act.resources.displayMetrics.widthPixels, maxW), MATCH, Gravity.CENTER_HORIZONTAL))
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, ins ->
            val b = ins.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            v.setPadding(b.left, b.top, b.right, b.bottom)
            ins
        }

        dlg.setContentView(root)
        dlg.window?.let {
            it.setLayout(MATCH, MATCH)
            it.setBackgroundDrawable(ColorDrawable(cBg))
        }
        dlg.setOnShowListener { // soft entrance
            column.alpha = 0f
            column.translationY = dp(18).toFloat()
            column.animate().alpha(1f).translationY(0f).setDuration(220).setInterpolator(DecelerateInterpolator(1.6f)).start()
        }
        dlg.show()
    }
}
