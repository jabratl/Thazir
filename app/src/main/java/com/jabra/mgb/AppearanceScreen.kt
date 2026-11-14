package com.jabra.mgb

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.res.ColorStateList
import android.widget.SeekBar
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import com.google.android.material.snackbar.Snackbar

/**
 * APPEARANCE A2: full-screen Appearance dialog (same look as Settings: 56 dp header + back, scroll column).
 * Root page = THEME section (3 mini-phone tiles + Dark pages switch). Page stack: "root" only for now;
 * A3-A8 add pages by extending fill() and the helpers below (sectionLabel / card / toggleRow / tileChoice / chipChoice).
 * Entry: Settings > General > Appearance. Back = Settings again. A theme tile recreates the Activity and this screen reopens.
 */
object AppearanceScreen {
    private const val TAG = "MGB.appearance"
    private var current: Screen? = null
    @Volatile private var reopen = false
    private var lastTab = "look" // 0.0.313: the tab you were on comes back after a recreate (theme, accent, bar position)

    /** A3: set by MainActivity; called after a change that the main screen must show now (bar icon, full URL). */
    var onChange: (() -> Unit)? = null

    fun open(host: Activity) {
        if (host.isFinishing || host.isDestroyed) return
        closeAll()
        try {
            val s = Screen(host)
            current = s
            s.show()
        } catch (e: Exception) {
            Log.e(TAG, "open failed", e)
            current = null
        }
    }

    fun closeAll() {
        val s = current ?: return
        current = null
        try { s.dismiss() } catch (_: Exception) { }
    }

    /** Set right before act.recreate(): the new Activity opens this screen again (called from MainActivity.onCreate). */
    fun markReopen() { reopen = true; SettingsScreen.closeAll() } // V18: Settings may sit under this screen; close it before the Activity is recreated

    fun reopenPending(act: Activity) {
        if (!reopen) return
        reopen = false
        open(act)
    }

    /** Colours of one mode, read through a configuration context so a Light tile is light even when the app is dark. */
    private class Palette(val bg: Int, val surface: Int, val outline: Int, val text: Int, val primary: Int)

    private class Chip(val label: String, val icon: Int?)

    private class Screen(private val act: Activity) :
        Dialog(act, com.google.android.material.R.style.Theme_Material3_DayNight_NoActionBar) {

        private val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        private val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
        private var body: LinearLayout? = null
        private var titleTv: TextView? = null
        private var page = lastTab // 0.0.313: "look" | "bars" | "home" are tabs, "wp" is the Wallpaper page
        private var tabsBar: LinearLayout? = null
        private var previewBox: LinearLayout? = null // 0.0.313: live address bar preview, stays on screen while the Bars tab scrolls
        private var prevUrl: TextView? = null // A3: live preview of the address bar
        private var prevHome: ImageView? = null
        private var prevBack: ImageView? = null // A1: arrows in the preview bar
        private var prevFwd: ImageView? = null
        private var prevBar: View? = null // A11: live height of the preview bar

        private fun dp(v: Int) = Util.dp(act, v)

        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)
            build()
            window?.let {
                it.setLayout(MATCH, MATCH)
                it.setBackgroundDrawable(ColorDrawable(act.getColor(R.color.mg_bg)))
            }
        }

        private fun round(color: Int, r: Int, strokeDp: Float = 0f, stroke: Int = 0) = GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(r).toFloat()
            if (strokeDp > 0f) setStroke((strokeDp * act.resources.displayMetrics.density).toInt(), stroke)
        }

        private fun build() {
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
                setOnClickListener { goBack() }
            }, LinearLayout.LayoutParams(dp(44), dp(48)))
            head.addView(TextView(act).apply {
                text = "Appearance"
                textSize = 22f
                gravity = Gravity.CENTER_VERTICAL or Gravity.START
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                setTextColor(act.getColor(R.color.mg_text))
                setPadding(dp(6), 0, 0, 0)
                titleTv = this
            }, LinearLayout.LayoutParams(0, WRAP, 1f))
            root.addView(head, LinearLayout.LayoutParams(MATCH, dp(56)))
            val tb = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(2), dp(16), dp(6)) }
            tabsBar = tb
            root.addView(tb, LinearLayout.LayoutParams(MATCH, WRAP))
            val pb = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE; setPadding(dp(16), 0, dp(16), dp(4)) }
            previewBox = pb
            root.addView(pb, LinearLayout.LayoutParams(MATCH, WRAP))

            val col = LinearLayout(act).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16), 0, dp(16), dp(32))
            }
            body = col
            root.addView(ScrollView(act).apply { isFillViewport = false; addView(col, MATCH, WRAP) },
                LinearLayout.LayoutParams(MATCH, 0, 1f))
            setContentView(root)
            fill()
        }

        private fun openPage(key: String, title: String) {
            page = key
            titleTv?.text = title
            fill()
            (body?.parent as? ScrollView)?.scrollTo(0, 0)
        }

        /** Back order: Wallpaper page -> Home and tabs tab -> Settings. */
        private fun goBack() {
            if (page == "wp") {
                page = "home"
                lastTab = "home"
                titleTv?.text = "Appearance"
                fill()
            } else {
                dismiss()
                if (!SettingsScreen.isShowing()) SettingsScreen.open(act) // V18: Settings is still underneath when we came from it
            }
        }

        override fun onBackPressed() { goBack() }

        // ---------------- pages ----------------

        private fun fill() {
            val col = body ?: return
            col.removeAllViews()
            buildTabs(page != "wp")
            previewBox?.removeAllViews()
            previewBox?.visibility = View.GONE
            when (page) {
                "bars" -> barsTab(col)
                "home" -> homeTab(col)
                "wp" -> wallpaperPage(col)
                else -> lookTab(col)
            }
        }

        /** 0.0.313: three tabs instead of one long page. Same settings, grouped by what you want to change. */
        private fun buildTabs(show: Boolean) {
            val tb = tabsBar ?: return
            tb.removeAllViews()
            tb.visibility = if (show) View.VISIBLE else View.GONE
            if (!show) return
            val pill = LinearLayout(act).apply {
                orientation = LinearLayout.HORIZONTAL
                background = round(act.getColor(R.color.mg_surface), 22, 1f, ColorUtils.setAlphaComponent(act.getColor(R.color.mg_outline), 0x99))
                setPadding(dp(4), dp(4), dp(4), dp(4))
            }
            val tabs = listOf("look" to "Look", "bars" to "Bars", "home" to "Home and tabs")
            for ((key, label) in tabs) {
                val on = page == key
                pill.addView(TextView(act).apply {
                    text = label
                    textSize = 13f
                    gravity = Gravity.CENTER
                    maxLines = 1
                    typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                    setTextColor(act.getColor(if (on) R.color.mg_primary else R.color.mg_text_dim))
                    if (on) background = round(ColorUtils.setAlphaComponent(act.getColor(R.color.mg_primary), 0x2A), 18)
                    isClickable = true
                    contentDescription = label + (if (on) ", selected" else "")
                    setOnClickListener {
                        if (page != key) {
                            tick(this)
                            page = key
                            lastTab = key
                            fill()
                            (body?.parent as? ScrollView)?.scrollTo(0, 0)
                        }
                    }
                }, LinearLayout.LayoutParams(0, dp(36), 1f))
            }
            tb.addView(pill, LinearLayout.LayoutParams(MATCH, WRAP))
        }

        /** Rebuilds the tab but keeps the scroll position (used when one switch changes the look of another). */
        private fun refreshKeep() {
            val sv = body?.parent as? ScrollView ?: return
            val y = sv.scrollY
            sv.post { fill(); sv.post { sv.scrollTo(0, y) } }
        }

        /** 0.0.313: Look = theme, dark pages, accent colour, accessibility. */
        private fun lookTab(col: LinearLayout) {
            col.addView(sectionLabel("Theme"))
            val themeCard = card()
            val lightP = palette(false)
            val darkP = palette(true)
            tileChoice(themeCard, listOf("System", "Light", "Dark"),
                listOf(PhoneView(act, lightP, darkP), PhoneView(act, lightP, null), PhoneView(act, darkP, null)),
                UiMode.mode(act)) { i ->
                if (UiMode.setMode(act, i)) {
                    AppearanceScreen.markReopen()
                    dismiss()
                    UiMode.smoothRecreate(act) // 0.0.421
                }
            }
            toggleRow(themeCard, R.drawable.ic_theme, "Dark pages", "Darken websites that have no dark style (reload the page)",
                { UiMode.darkPages(act) }) { on ->
                UiMode.setDarkPages(act, on)
                BookmarksScreen.tabsRef?.applyDarkPages()
                Util.toast(act, if (on) "Dark pages ON (reload the page)" else "Dark pages OFF (reload the page)")
            }
            col.addView(themeCard, LinearLayout.LayoutParams(MATCH, WRAP))

            col.addView(sectionLabel("Surfaces")) // 0.0.423: Soft / Classic white (light) / True black (dark)
            val surfCard = card()
            val sl = palette(false)
            val sd = palette(true)
            val ids = listOf(SurfaceStyle.SOFT, SurfaceStyle.CLASSIC, SurfaceStyle.BLACK)
            tileChoice(surfCard, listOf("Soft", "Classic white", "True black"),
                listOf(PhoneView(act, sl, null),
                    PhoneView(act, Palette(0xFFF8F8FC.toInt(), 0xFFFFFFFF.toInt(), 0xFFD9DCE7.toInt(), sl.text, sl.primary), null),
                    PhoneView(act, Palette(0xFF000000.toInt(), 0xFF0E0F14.toInt(), 0xFF2A2D3A.toInt(), sd.text, sd.primary), null)),
                ids.indexOf(SurfaceStyle.id(act)).coerceAtLeast(0)) { i ->
                if (ids[i] != SurfaceStyle.id(act)) {
                    SurfaceStyle.set(act, ids[i])
                    Util.toast(act, when (ids[i]) {
                        SurfaceStyle.CLASSIC -> "Classic white shows in Light mode"
                        SurfaceStyle.BLACK -> "True black shows in Dark mode"
                        else -> "Soft: light tint follows the accent colour"
                    })
                    AppearanceScreen.markReopen()
                    dismiss()
                    UiMode.smoothRecreate(act)
                }
            }
            col.addView(surfCard, LinearLayout.LayoutParams(MATCH, WRAP))

            col.addView(sectionLabel("Accent colour")) // 0.0.289
            val accentCard = card()
            accentPicker(accentCard)
            col.addView(accentCard, LinearLayout.LayoutParams(MATCH, WRAP))

            col.addView(sectionLabel("Accessibility")) // A2
            val accCard = card()
            navRow(accCard, R.drawable.ic_edit, "Accessibility", AppearancePrefs.zoomDef(act).takeIf { it != 100 }?.let { "$it %" } ?: "") {
                dismiss(); AccessScreen.open(act) { AppearanceScreen.open(act) }
            }
            col.addView(accCard, LinearLayout.LayoutParams(MATCH, WRAP))
        }

        /** 0.0.313: Home and tabs = wallpaper, menu, undo toast, start page (was: root page + "Tabs and start page" page). */
        private fun homeTab(col: LinearLayout) {
            col.addView(sectionLabel("Home page")) // A7
            val wpCard = card()
            navRow(wpCard, R.drawable.ic_image, "Wallpaper", if (AppearancePrefs.hasWallpaper(act)) "Custom image" else "None") { openPage("wp", "Wallpaper") }
            col.addView(wpCard, LinearLayout.LayoutParams(MATCH, WRAP))

            col.addView(sectionLabel("Menu and tabs")) // A8
            val menuCard = card()
            val all = MenuCustom.QUICK + MenuCustom.GENERAL + MenuCustom.PAGE
            navRow(menuCard, R.drawable.ic_edit, "Customize menu", "${all.count { MenuCustom.on(act, it.key) }} of ${all.size} shown") { MenuCustom.open(act) }
            col.addView(menuCard, LinearLayout.LayoutParams(MATCH, WRAP))
            col.addView(sectionLabel("Tabs"))
            val c = card()
            toggleRow(c, R.drawable.ic_list, "Show undo toast", "\"Closed ... Undo\" bar after closing or grouping tabs",
                { AppearancePrefs.undoToast(act) }) { on -> AppearancePrefs.setUndoToast(act, on) }
            toggleRow(c, R.drawable.ic_home, "Show start page", "Open a fresh home tab at every launch (your other tabs stay)",
                { AppearancePrefs.startPage(act) }) { on -> AppearancePrefs.setStartPage(act, on) }
            col.addView(c, LinearLayout.LayoutParams(MATCH, WRAP))
        }

        private var wpScrimV: View? = null // A7: dim layer of the page preview

        /** A7: Wallpaper page = preview, Choose image, Remove, Dim slider. */
        private fun wallpaperPage(col: LinearLayout) {
            col.addView(sectionLabel("Preview"))
            val pc = card()
            val frame = FrameLayout(act).apply { background = round(act.getColor(R.color.mg_bg), 16, 1f, act.getColor(R.color.mg_outline)); clipToOutline = true }
            val bmp = Wallpaper.load(act)
            if (bmp != null) {
                frame.addView(ImageView(act).apply { scaleType = ImageView.ScaleType.CENTER_CROP; setImageBitmap(bmp) }, FrameLayout.LayoutParams(MATCH, MATCH))
            } else {
                frame.addView(TextView(act).apply {
                    text = "No wallpaper"; textSize = 13f; gravity = Gravity.CENTER
                    setTextColor(act.getColor(R.color.mg_text_dim))
                }, FrameLayout.LayoutParams(MATCH, MATCH))
            }
            val scrim = View(act)
            wpScrimV = scrim
            frame.addView(scrim, FrameLayout.LayoutParams(MATCH, MATCH))
            updateWpScrim(bmp != null)
            pc.addView(frame, LinearLayout.LayoutParams(MATCH, dp(160)).apply { setMargins(dp(12), dp(2), dp(12), dp(6)) })
            col.addView(pc, LinearLayout.LayoutParams(MATCH, WRAP))

            col.addView(sectionLabel("Image"))
            val ic = card()
            navRow(ic, R.drawable.ic_image, "Choose image", "") {
                BrowserExtras.pickImage { uri ->
                    Util.toast(act, "Saving wallpaper...")
                    Thread {
                        val ok = try { Wallpaper.save(act, uri) } catch (_: Throwable) { false }
                        act.runOnUiThread {
                            try { if (ok) { Wallpaper.notifyChanged(); if (page == "wp") fill() } else Util.toast(act, "Could not use this image") } catch (_: Throwable) { } // 0.0.395: never crash here
                        }
                    }.start()
                }
            }
            if (AppearancePrefs.hasWallpaper(act)) {
                navRow(ic, R.drawable.ic_close, "Remove wallpaper", "") {
                    Wallpaper.remove(act); Wallpaper.notifyChanged(); fill()
                }
            }
            col.addView(ic, LinearLayout.LayoutParams(MATCH, WRAP))

            col.addView(sectionLabel("Dim"))
            val dc = card()
            val tint = ColorStateList.valueOf(act.getColor(R.color.mg_toggle_on))
            dc.addView(SeekBar(act).apply {
                max = 80
                progress = AppearancePrefs.wpDim(act)
                progressTintList = tint
                thumbTintList = tint
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                        if (!fromUser) return
                        AppearancePrefs.setWpDim(act, p)
                        updateWpScrim(AppearancePrefs.hasWallpaper(act)) // 0.0.395: no bitmap work while the slider moves
                        Wallpaper.notifyChanged()
                    }
                    override fun onStartTrackingTouch(sb: SeekBar?) { }
                    override fun onStopTrackingTouch(sb: SeekBar?) { }
                })
            }, LinearLayout.LayoutParams(MATCH, dp(48)).apply { setMargins(dp(12), 0, dp(12), 0) })
            dc.addView(TextView(act).apply {
                text = "Dim layer in the page colour keeps the tiles readable. Wallpaper is not included in backup."
                textSize = 12f
                setTextColor(act.getColor(R.color.mg_text_dim))
                setPadding(dp(16), 0, dp(16), dp(8))
            }, LinearLayout.LayoutParams(MATCH, WRAP))
            col.addView(dc, LinearLayout.LayoutParams(MATCH, WRAP))
        }

        private fun updateWpScrim(has: Boolean) {
            wpScrimV?.setBackgroundColor(if (has) ColorUtils.setAlphaComponent(act.getColor(R.color.mg_bg), AppearancePrefs.wpDim(act) * 255 / 100) else 0)
        }

        /** 0.0.313: Bars tab = live preview (pinned above the list), bar position, home button, arrows, behaviour. */
        private fun barsTab(col: LinearLayout) {
            val prevCard = card()
            prevCard.addView(barPreview(), LinearLayout.LayoutParams(MATCH, WRAP))
            previewBox?.let { it.addView(prevCard, LinearLayout.LayoutParams(MATCH, WRAP)); it.visibility = View.VISIBLE }

            col.addView(sectionLabel("Bar position")) // A4
            val posCard = card()
            val lp = palette((act.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES)
            val posKeys = AppearancePrefs.BAR_POS
            tileChoice(posCard, posKeys.map { if (it == "top") "Top" else "Bottom" },
                posKeys.map { PhoneView(act, lp, null, it == "top") }, posKeys.indexOf(AppearancePrefs.barPos(act))) { i ->
                if (AppearancePrefs.barPos(act) != posKeys[i]) {
                    AppearancePrefs.setBarPos(act, posKeys[i])
                    fill()
                    try {
                        Snackbar.make(findViewById<View>(android.R.id.content), "Restart needed to move the bar", Snackbar.LENGTH_INDEFINITE)
                            .setAction("Apply now") { AppearanceScreen.markReopen(); dismiss(); UiMode.smoothRecreate(act) }
                            .show()
                    } catch (_: Exception) { }
                }
            }
            col.addView(posCard, LinearLayout.LayoutParams(MATCH, WRAP))

            col.addView(sectionLabel("Home button"))
            val chipCard = card()
            val acts = AppearancePrefs.HOME_ACTIONS
            val chips = acts.map { a -> val ic = AppearancePrefs.homeIconAndLabel(a); Chip(ic.second, ic.first) }
            chipChoice(chipCard, chips, acts.indexOf(AppearancePrefs.homeAction(act))) { i ->
                AppearancePrefs.setHomeAction(act, acts[i])
                AppearanceScreen.onChange?.invoke()
                fill()
            }
            col.addView(chipCard, LinearLayout.LayoutParams(MATCH, WRAP))

            col.addView(sectionLabel("Navigation arrows")) // A1
            val navCard = card()
            toggleRow(navCard, R.drawable.ic_back, "Back and Forward buttons", "Arrows in the address bar, hold one for the page list. Home button stays", // A5, 0.0.313: was on the Appearance page
                { AppearancePrefs.navBack(act) || AppearancePrefs.navFwd(act) }) { on ->
                AppearancePrefs.setNavBack(act, on)
                AppearancePrefs.setNavFwd(act, on)
                AppearanceScreen.onChange?.invoke()
                updatePreview()
                refreshKeep()
            }
            toggleRow(navCard, R.drawable.ic_back, "Back arrow", "Show Back inside the address bar",
                { AppearancePrefs.navBack(act) }) { on ->
                AppearancePrefs.setNavBack(act, on)
                AppearanceScreen.onChange?.invoke()
                updatePreview()
                refreshKeep()
            }
            toggleRow(navCard, R.drawable.ic_forward, "Forward arrow", "Show Forward inside the address bar",
                { AppearancePrefs.navFwd(act) }) { on ->
                AppearancePrefs.setNavFwd(act, on)
                AppearanceScreen.onChange?.invoke()
                updatePreview()
                refreshKeep()
            }
            toggleRow(navCard, R.drawable.ic_screen, "Hide when not available", "Hide an arrow instead of dimming it",
                { AppearancePrefs.navHide(act) }) { on ->
                AppearancePrefs.setNavHide(act, on)
                AppearanceScreen.onChange?.invoke()
                updatePreview()
            }
            col.addView(navCard, LinearLayout.LayoutParams(MATCH, WRAP))

            col.addView(sectionLabel("Behaviour"))
            val behCard = card()
            val ui = act.getSharedPreferences("mgb_ui", Context.MODE_PRIVATE)
            toggleRow(behCard, R.drawable.ic_screen, "Compact bar", "Slimmer bar (44 dp). Restart to apply",
                { AppearancePrefs.compactBar(act) }) { on ->
                AppearancePrefs.setCompactBar(act, on)
                updatePreview()
                applySnack("Restart needed to resize the bar")
            }
            toggleRow(behCard, R.drawable.ic_screen, "Auto-hide bar", "Bar hides when you scroll down",
                { ui.getBoolean("hide_bar", false) }) { on ->
                ui.edit().putBoolean("hide_bar", on).apply()
            }
            toggleRow(behCard, R.drawable.ic_search, "Show full URL", "https://... instead of the site name only",
                { AppearancePrefs.fullUrl(act) }) { on ->
                AppearancePrefs.setFullUrl(act, on)
                AppearanceScreen.onChange?.invoke()
                updatePreview()
            }
            val up = AppearancePrefs.barTop(act) // A6: directions follow the bar position
            toggleRow(behCard, R.drawable.ic_add, "New tab button", "Show a + next to the address bar: tap = new tab, hold = new private tab",
                { AppearancePrefs.newTabBtn(act) }) { on -> AppearancePrefs.setNewTabBtn(act, on); AppearanceScreen.onChange?.invoke() }
            toggleRow(behCard, R.drawable.ic_screen, "Swipe for fullscreen", "Swipe the bar " + (if (up) "up" else "down") + " to hide the bar and system bars",
                { AppearancePrefs.swipeFs(act) }) { on -> AppearancePrefs.setSwipeFs(act, on) }
            toggleRow(behCard, R.drawable.ic_list, "Swipe to view tabs", "Swipe the bar " + (if (up) "down" else "up") + " to open the tab switcher",
                { AppearancePrefs.swipeTabs(act) }) { on -> AppearancePrefs.setSwipeTabs(act, on) }
            toggleRow(behCard, R.drawable.ic_tab_rows, "Swipe between tabs", "Swipe the bar left or right to go to the next or previous tab",
                { AppearancePrefs.swipeSide(act) }) { on -> AppearancePrefs.setSwipeSide(act, on) }
            toggleRow(behCard, R.drawable.ic_back, "Swipe back / forward", "Swipe from the left screen edge to go back, from the right edge to go forward",
                { AppearancePrefs.swipeNav(act) }) { on -> AppearancePrefs.setSwipeNav(act, on); EdgeSwipe.attach(act) }
            col.addView(behCard, LinearLayout.LayoutParams(MATCH, WRAP))
        }

        private fun updatePreview() {
            prevUrl?.text = if (AppearancePrefs.fullUrl(act)) "https://example.com/watch?v=12" else "example.com"
            prevBar?.let { b -> b.layoutParams?.let { lp -> lp.height = dp(if (AppearancePrefs.compactBar(act)) 44 else 52); b.layoutParams = lp } } // A11
            prevHome?.setImageResource(AppearancePrefs.homeIconAndLabel(AppearancePrefs.homeAction(act)).first)
            prevBack?.visibility = if (AppearancePrefs.navBack(act)) View.VISIBLE else View.GONE // A1: preview shows the arrows (Forward dimmed = no forward page)
            prevFwd?.visibility = if (AppearancePrefs.navFwd(act) && !AppearancePrefs.navHide(act)) View.VISIBLE else View.GONE
        }

        /** Mini copy of the bottom bar pill (home action icon, address, tabs, menu), built from views. */
        private fun barPreview(): View {
            val bar = LinearLayout(act).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = round(act.getColor(R.color.mg_bg), 26, 1f, act.getColor(R.color.mg_outline))
                setPadding(dp(4), 0, dp(4), 0)
            }
            val home = ImageView(act).apply {
                setColorFilter(act.getColor(R.color.mg_text))
                setPadding(dp(12), dp(12), dp(12), dp(12))
            }
            prevHome = home
            bar.addView(home, LinearLayout.LayoutParams(dp(44), dp(44)))
            fun arrow(icon: Int, dim: Boolean) = ImageView(act).apply { // A1
                setImageResource(icon)
                setColorFilter(act.getColor(R.color.mg_text))
                alpha = if (dim) 0.38f else 1f
                setPadding(dp(9), dp(9), dp(9), dp(9))
                visibility = View.GONE
            }
            prevBack = arrow(R.drawable.ic_back, false)
            prevFwd = arrow(R.drawable.ic_forward, true)
            bar.addView(prevBack, LinearLayout.LayoutParams(dp(36), dp(44)))
            bar.addView(prevFwd, LinearLayout.LayoutParams(dp(36), dp(44)))
            val url = TextView(act).apply {
                textSize = 15f
                setSingleLine(true)
                ellipsize = android.text.TextUtils.TruncateAt.END
                setTextColor(act.getColor(R.color.mg_text))
                setPadding(dp(6), 0, dp(6), 0)
            }
            prevUrl = url
            bar.addView(url, LinearLayout.LayoutParams(0, WRAP, 1f))
            bar.addView(TextView(act).apply {
                text = "1"
                textSize = 12f
                gravity = Gravity.CENTER
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setTextColor(act.getColor(R.color.mg_text))
                background = round(0, 7, 2f, act.getColor(R.color.mg_text_dim))
            }, LinearLayout.LayoutParams(dp(24), dp(24)).apply { marginEnd = dp(10) })
            bar.addView(ImageView(act).apply {
                setImageResource(R.drawable.ic_more)
                setColorFilter(act.getColor(R.color.mg_text))
                setPadding(dp(10), dp(10), dp(10), dp(10))
            }, LinearLayout.LayoutParams(dp(40), dp(40)))
            updatePreview()
            val box = LinearLayout(act).apply { setPadding(dp(8), dp(4), dp(8), dp(6)) }
            prevBar = bar
            box.addView(bar, LinearLayout.LayoutParams(MATCH, dp(if (AppearancePrefs.compactBar(act)) 44 else 52)))
            return box
        }

        // ---------------- helpers (A3-A8 reuse these) ----------------

        private fun sectionLabel(t: String) = TextView(act).apply {
            text = t.uppercase()
            textSize = 11f
            letterSpacing = 0.08f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(act.getColor(R.color.mg_primary))
            setPadding(dp(8), dp(16), 0, dp(6))
            // A11: Thazir signature = small violet dash before every section label
            setCompoundDrawablesWithIntrinsicBounds(GradientDrawable().apply {
                setColor(act.getColor(R.color.mg_toggle_on)); cornerRadius = dp(2).toFloat(); setSize(dp(4), dp(12))
            }, null, null, null)
            compoundDrawablePadding = dp(8)
        }

        /** A11: soft tick on a choice (the phone's haptic setting decides if it is felt). */
        private fun tick(v: View) { v.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK) }

        /** A11: press = tiny shrink, release = back. Skipped when Reduce motion is ON. Does not eat the click. */
        private fun pressFx(v: View) {
            if (TabPrefs.reduce(act)) return
            v.setOnTouchListener { x, e ->
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> x.animate().scaleX(0.96f).scaleY(0.96f).setDuration(120).start()
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> x.animate().scaleX(1f).scaleY(1f).setDuration(160).start()
                }
                false
            }
        }

        private fun applySnack(msg: String) {
            try {
                Snackbar.make(findViewById<View>(android.R.id.content), msg, Snackbar.LENGTH_INDEFINITE)
                    .setAction("Apply now") { AppearanceScreen.markReopen(); dismiss(); UiMode.smoothRecreate(act) }
                    .show()
            } catch (_: Exception) { }
        }

        private fun card(): LinearLayout = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            background = round(act.getColor(R.color.mg_surface), 24, 1f, ColorUtils.setAlphaComponent(act.getColor(R.color.mg_outline), 0x99))
            setPadding(dp(4), dp(10), dp(4), dp(6))
        }

        private fun rippleBg(v: View) {
            val tv = TypedValue()
            if (act.theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)) v.setBackgroundResource(tv.resourceId)
        }

        /** One switch row: icon + label + small hint + ThazirSwitch. Row click toggles; get / set talk to the prefs. */
        private fun toggleRow(parent: LinearLayout, icon: Int, label: String, hint: String?, get: () -> Boolean, set: (Boolean) -> Unit) {
            val sw = ThazirSwitch(act).apply { isChecked = get(); isClickable = false; isFocusable = false }
            val row = LinearLayout(act).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(52)
                setPadding(dp(18), dp(6), dp(16), dp(6))
                isClickable = true
                rippleBg(this)
            }
            row.addView(ImageView(act).apply {
                setImageResource(icon)
                setColorFilter(act.getColor(R.color.mg_text_dim))
            }, LinearLayout.LayoutParams(dp(22), dp(22)).apply { marginEnd = dp(16) })
            val txt = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
            txt.addView(TextView(act).apply { text = label; textSize = 15f; setTextColor(act.getColor(R.color.mg_text)) })
            if (!hint.isNullOrBlank()) txt.addView(TextView(act).apply {
                text = hint; textSize = 12f; setTextColor(act.getColor(R.color.mg_text_dim)); setPadding(0, dp(2), 0, 0)
            })
            row.addView(txt, LinearLayout.LayoutParams(0, WRAP, 1f))
            row.addView(sw, LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = dp(8) })
            row.setOnClickListener {
                tick(it) // A11
                set(!get())
                sw.isChecked = get()
            }
            parent.addView(row, LinearLayout.LayoutParams(MATCH, WRAP))
        }

        /** Single choice as preview tiles (100 x 120 dp, radius 16): selected = 2 dp mg_toggle_on ring + check dot, else 1 dp outline. */
        private fun tileChoice(parent: LinearLayout, labels: List<String>, previews: List<View>, selected: Int, onPick: (Int) -> Unit) {
            val rowV = LinearLayout(act).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(0, dp(4), 0, dp(8))
            }
            for (i in labels.indices) {
                val on = i == selected
                val colV = LinearLayout(act).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER_HORIZONTAL
                    isClickable = true
                    setOnClickListener { tick(this); onPick(i) } // A11
                    pressFx(this)
                }
                val frame = FrameLayout(act).apply {
                    background = round(0, 16, if (on) 2f else 1f,
                        if (on) act.getColor(R.color.mg_toggle_on) else act.getColor(R.color.mg_outline))
                }
                frame.addView(previews[i], FrameLayout.LayoutParams(dp(100), dp(120)))
                if (on) {
                    val dot = FrameLayout(act).apply { background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(act.getColor(R.color.mg_toggle_on)) } }
                    dot.addView(ImageView(act).apply {
                        setImageResource(R.drawable.ic_check)
                        setColorFilter(act.getColor(R.color.mg_toggle_thumb))
                        setPadding(dp(3), dp(3), dp(3), dp(3))
                    }, FrameLayout.LayoutParams(dp(20), dp(20)))
                    frame.addView(dot, FrameLayout.LayoutParams(dp(20), dp(20), Gravity.TOP or Gravity.END).apply { topMargin = dp(6); marginEnd = dp(6) })
                }
                colV.addView(frame, LinearLayout.LayoutParams(dp(100), dp(120)))
                colV.addView(TextView(act).apply {
                    text = labels[i]
                    textSize = 13f
                    gravity = Gravity.CENTER
                    setTextColor(act.getColor(if (on) R.color.mg_text else R.color.mg_text_dim))
                    if (on) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                    setPadding(0, dp(8), 0, 0)
                }, LinearLayout.LayoutParams(dp(100), WRAP))
                rowV.addView(colV, LinearLayout.LayoutParams(0, WRAP, 1f))
            }
            parent.addView(rowV, LinearLayout.LayoutParams(MATCH, WRAP))
        }

        /** 0.0.289: swatches for the accent colour. Tap = save + recreate (same as the Theme tiles); Appearance reopens. */
        private fun accentPicker(parent: LinearLayout) {
            val flow = FlowLayout(act, dp(4)).apply { setPadding(dp(8), dp(4), dp(8), dp(8)) }
            val curId = AccentTheme.id(act)
            for (a in AccentTheme.ALL) {
                val on = a.id == curId
                val v = LinearLayout(act).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER_HORIZONTAL
                    isClickable = true
                    contentDescription = a.label + (if (on) ", selected" else "")
                    setPadding(dp(4), dp(6), dp(4), dp(4))
                    setOnClickListener {
                        tick(this)
                        if (!on) {
                            AccentTheme.set(act, a.id)
                            AppearanceScreen.markReopen()
                            dismiss()
                            UiMode.smoothRecreate(act) // 0.0.421
                        }
                    }
                    pressFx(this)
                }
                val sw = FrameLayout(act).apply {
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL; setColor(act.getColor(a.color))
                        if (on) setStroke(dp(3), act.getColor(R.color.mg_surface))
                    }
                }
                val ring = FrameLayout(act).apply {
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL; setColor(0)
                        setStroke(dp(2), if (on) act.getColor(a.color) else act.getColor(R.color.mg_outline))
                    }
                    setPadding(dp(3), dp(3), dp(3), dp(3))
                }
                ring.addView(sw, FrameLayout.LayoutParams(dp(38), dp(38)))
                if (on) sw.addView(ImageView(act).apply {
                    setImageResource(R.drawable.ic_check)
                    setColorFilter(act.getColor(R.color.acc_on))
                    setPadding(dp(8), dp(8), dp(8), dp(8))
                }, FrameLayout.LayoutParams(dp(38), dp(38)))
                v.addView(ring, LinearLayout.LayoutParams(dp(50), dp(50)))
                v.addView(TextView(act).apply {
                    text = a.label; textSize = 11f; gravity = Gravity.CENTER; maxLines = 1
                    setTextColor(act.getColor(if (on) R.color.mg_text else R.color.mg_text_dim))
                    if (on) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                    setPadding(0, dp(4), 0, 0)
                }, LinearLayout.LayoutParams(dp(56), WRAP))
                flow.addView(v, ViewGroup.LayoutParams(WRAP, WRAP))
            }
            parent.addView(flow, LinearLayout.LayoutParams(MATCH, WRAP))
            parent.addView(TextView(act).apply {
                text = "Changes buttons, switches and highlights in light and dark. Violet is the default."
                textSize = 11f; setTextColor(act.getColor(R.color.mg_text_dim)); setPadding(dp(12), 0, dp(12), dp(6))
            }, LinearLayout.LayoutParams(MATCH, WRAP))
        }

        /** Single choice as a wrap of chips (icon + label, radius 20 dp, 44 dp high). Not used before A3. */
        private fun chipChoice(parent: LinearLayout, chips: List<Chip>, selected: Int, onPick: (Int) -> Unit) {
            val flow = FlowLayout(act, dp(8)).apply { setPadding(dp(12), dp(4), dp(12), dp(8)) }
            for (i in chips.indices) {
                val on = i == selected
                val c = LinearLayout(act).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    minimumHeight = dp(44)
                    setPadding(dp(14), 0, dp(16), 0)
                    isClickable = true
                    background = round(0, 20, if (on) 2f else 1f,
                        if (on) act.getColor(R.color.mg_toggle_on) else act.getColor(R.color.mg_outline))
                    setOnClickListener { tick(this); onPick(i) } // A11
                    pressFx(this)
                }
                chips[i].icon?.let { ic ->
                    c.addView(ImageView(act).apply {
                        setImageResource(ic)
                        setColorFilter(act.getColor(if (on) R.color.mg_toggle_on else R.color.mg_text_dim))
                    }, LinearLayout.LayoutParams(dp(18), dp(18)).apply { marginEnd = dp(8) })
                }
                c.addView(TextView(act).apply {
                    text = chips[i].label
                    textSize = 14f
                    setTextColor(act.getColor(if (on) R.color.mg_text else R.color.mg_text_dim))
                })
                flow.addView(c, ViewGroup.LayoutParams(WRAP, WRAP))
            }
            parent.addView(flow, LinearLayout.LayoutParams(MATCH, WRAP))
        }

        /** A3: row that opens a page: icon + label + value + chevron. */
        private fun navRow(parent: LinearLayout, icon: Int, label: String, value: String, onClick: () -> Unit) {
            val row = LinearLayout(act).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(52)
                setPadding(dp(18), dp(6), dp(12), dp(6))
                isClickable = true
                rippleBg(this)
                setOnClickListener { onClick() }
            }
            row.addView(ImageView(act).apply {
                setImageResource(icon)
                setColorFilter(act.getColor(R.color.mg_text_dim))
            }, LinearLayout.LayoutParams(dp(22), dp(22)).apply { marginEnd = dp(16) })
            row.addView(TextView(act).apply { text = label; textSize = 15f; setTextColor(act.getColor(R.color.mg_text)) },
                LinearLayout.LayoutParams(0, WRAP, 1f))
            row.addView(TextView(act).apply {
                text = value; textSize = 13f; setTextColor(act.getColor(R.color.mg_primary))
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            }, LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = dp(8) })
            row.addView(ImageView(act).apply {
                setImageResource(R.drawable.ic_chevron)
                setColorFilter(act.getColor(R.color.mg_text_dim))
            }, LinearLayout.LayoutParams(dp(18), dp(18)).apply { marginStart = dp(4) })
            parent.addView(row, LinearLayout.LayoutParams(MATCH, WRAP))
        }

        private fun palette(night: Boolean): Palette {
            val cfg = Configuration(act.resources.configuration)
            cfg.uiMode = (cfg.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                (if (night) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO)
            val x: Context = act.createConfigurationContext(cfg)
            return Palette(x.getColor(R.color.mg_bg), x.getColor(R.color.mg_surface), x.getColor(R.color.mg_outline),
                x.getColor(R.color.mg_text), x.getColor(R.color.mg_primary))
        }
    }

    /** Mini phone drawn in code: speed-dial squares + the bottom pill bar. right != null = left half / right half (System tile). */
    private class PhoneView(ctx: Context, private val left: Palette, private val right: Palette?, private val barTop: Boolean = false) : View(ctx) {
        private val d = ctx.resources.displayMetrics.density
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)

        override fun onDraw(c: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            c.save()
            val clip = Path()
            clip.addRoundRect(RectF(0f, 0f, w, h), 14f * d, 14f * d, Path.Direction.CW)
            c.clipPath(clip)
            if (right == null) {
                drawPal(c, left, w, h)
            } else {
                c.save(); c.clipRect(0f, 0f, w / 2f, h); drawPal(c, left, w, h); c.restore()
                c.save(); c.clipRect(w / 2f, 0f, w, h); drawPal(c, right, w, h); c.restore()
            }
            c.restore()
        }

        private fun drawPal(c: Canvas, pal: Palette, w: Float, h: Float) {
            p.style = Paint.Style.FILL; p.color = pal.bg
            c.drawRect(0f, 0f, w, h, p)
            // title line
            p.color = ColorUtils.setAlphaComponent(pal.text, 0x66)
            val off = if (barTop) 18f * d else 0f // A4: bar drawn at the top, content moves down
            c.drawRoundRect(RectF(w / 2f - 22f * d, 14f * d + off, w / 2f + 22f * d, 18f * d + off), 2f * d, 2f * d, p)
            // 2 x 3 speed-dial squares
            val s = 18f * d
            val gap = 8f * d
            val x0 = (w - (3f * s + 2f * gap)) / 2f
            for (r in 0 until 2) for (k in 0 until 3) {
                val l = x0 + k * (s + gap)
                val t = 30f * d + off + r * (s + gap)
                p.style = Paint.Style.FILL; p.color = pal.surface
                c.drawRoundRect(RectF(l, t, l + s, t + s), 5f * d, 5f * d, p)
                p.style = Paint.Style.STROKE; p.strokeWidth = 1f * d; p.color = pal.outline
                c.drawRoundRect(RectF(l, t, l + s, t + s), 5f * d, 5f * d, p)
            }
            // bottom pill bar
            val bar = if (barTop) RectF(10f * d, 12f * d, w - 10f * d, 28f * d) else RectF(10f * d, h - 28f * d, w - 10f * d, h - 12f * d)
            p.style = Paint.Style.FILL; p.color = pal.surface
            c.drawRoundRect(bar, 8f * d, 8f * d, p)
            p.style = Paint.Style.STROKE; p.strokeWidth = 1f * d; p.color = pal.outline
            c.drawRoundRect(bar, 8f * d, 8f * d, p)
            p.style = Paint.Style.FILL; p.color = pal.primary
            c.drawCircle(bar.left + 10f * d, bar.centerY(), 2.5f * d, p)
            p.color = ColorUtils.setAlphaComponent(pal.text, 0x66)
            c.drawRoundRect(RectF(bar.left + 20f * d, bar.centerY() - 1.5f * d, bar.left + 44f * d, bar.centerY() + 1.5f * d), 1.5f * d, 1.5f * d, p)
        }
    }

    /** Minimal wrapping row layout for the chips. */
    private class FlowLayout(ctx: Context, private val gap: Int) : ViewGroup(ctx) {
        override fun onMeasure(wSpec: Int, hSpec: Int) {
            val maxW = MeasureSpec.getSize(wSpec) - paddingLeft - paddingRight
            var x = 0
            var y = 0
            var rowH = 0
            for (i in 0 until childCount) {
                val v = getChildAt(i)
                if (v.visibility == View.GONE) continue
                v.measure(MeasureSpec.makeMeasureSpec(maxW, MeasureSpec.AT_MOST), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
                if (x > 0 && x + v.measuredWidth > maxW) { x = 0; y += rowH + gap; rowH = 0 }
                x += v.measuredWidth + gap
                rowH = maxOf(rowH, v.measuredHeight)
            }
            setMeasuredDimension(MeasureSpec.getSize(wSpec), y + rowH + paddingTop + paddingBottom)
        }

        override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
            val maxW = r - l - paddingLeft - paddingRight
            var x = 0
            var y = 0
            var rowH = 0
            for (i in 0 until childCount) {
                val v = getChildAt(i)
                if (v.visibility == View.GONE) continue
                if (x > 0 && x + v.measuredWidth > maxW) { x = 0; y += rowH + gap; rowH = 0 }
                v.layout(paddingLeft + x, paddingTop + y, paddingLeft + x + v.measuredWidth, paddingTop + y + v.measuredHeight)
                x += v.measuredWidth + gap
                rowH = maxOf(rowH, v.measuredHeight)
            }
        }
    }
}
