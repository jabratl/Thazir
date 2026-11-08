package com.jabra.mgb

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
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
        Entry("New private tab", R.drawable.ic_lock), Entry("Bookmark all tabs", R.drawable.ic_bookmark_border),
        Entry("Passwords", R.drawable.ic_lock), Entry("Recently closed tabs", R.drawable.ic_history), Entry("Saved pages", R.drawable.ic_folder), // T3
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
                get: () -> Boolean, set: (Boolean) -> Unit) {
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
            refreshers.add { busy = true; sw.isChecked = get(); busy = false }
        }

        fun entries(col: LinearLayout, l: List<Entry>) {
            val c = card()
            l.forEachIndexed { i, e ->
                if (i > 0) divider(c)
                row(c, e.icon, null, null, e.label, if (e.locked) "Always shown" else "", e.locked,
                    { on(act, e.key) }, { setOn(act, e.key, it) })
            }
            col.addView(c, LinearLayout.LayoutParams(MATCH, WRAP))
        }

        val body = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), 0, dp(12), dp(28)) }
        section(body, "Main menu")
        entries(body, GENERAL)
        section(body, "Quick tiles", "The four round shortcuts at the top of the menu.")
        entries(body, QUICK)

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

        section(body, "Page actions", "These rows only appear in the menu when the page that is open supports them.")
        entries(body, PAGE)

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
