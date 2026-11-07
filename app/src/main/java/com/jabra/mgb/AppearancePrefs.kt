package com.jabra.mgb

import android.content.Context
import java.io.File

/**
 * APPEARANCE A2: ALL ap_* prefs of the Appearance plan (file mgb_ui, so Backup.kt already saves them).
 * Later steps (A3-A8) only call these getters / setters. Defaults = today's behaviour.
 * Existing keys reused elsewhere: night (UiMode), dark_pages (UiMode), hide_bar (MainActivity scroll).
 */
object AppearancePrefs {
    const val K_BAR_POS = "ap_bar_pos"
    const val K_HOME_ACTION = "ap_home_action"
    const val K_FULL_URL = "ap_full_url"
    const val K_SWIPE_FS = "ap_swipe_fs"
    const val K_SWIPE_TABS = "ap_swipe_tabs"
    const val K_SWIPE_NAV = "ap_swipe_nav" // 0.0.294: edge swipe = page back / forward (default ON)
    const val K_NEWTAB = "ap_bar_newtab" // 0.0.319: "+" new tab button next to the address bar (default ON)
    const val K_SWIPE_SIDE = "ap_swipe_side" // 0.0.293: swipe the bar left / right = previous / next tab (default ON)
    const val K_WP_DIM = "ap_wp_dim"
    const val K_UNDO_TOAST = "ap_undo_toast"
    const val K_START_PAGE = "ap_start_page"
    const val K_BAR_COMPACT = "ap_bar_compact" // A11
    const val K_NAV_BACK = "ap_nav_back" // A1
    const val K_NAV_FWD = "ap_nav_fwd" // A1
    const val K_NAV_HIDE = "ap_nav_hide" // A1
    const val K_ZOOM_DEF = "ap_zoom_def" // A2
    const val K_FORCE_ZOOM = "ap_force_zoom" // A3
    const val K_TOUCHPAD = "ap_touchpad" // A3
    const val K_SIMPLE_HINT = "ap_simple_hint" // A4

    val BAR_POS = listOf("bottom", "top")
    val HOME_ACTIONS = listOf("home", "back", "newtab", "refresh", "bookmarks", "extensions")

    private fun sp(c: Context) = c.getSharedPreferences("mgb_ui", Context.MODE_PRIVATE)

    /** "bottom" (default) or "top". */
    fun barPos(c: Context): String = sp(c).getString(K_BAR_POS, "bottom").let { if (it in BAR_POS) it!! else "bottom" }
    fun setBarPos(c: Context, v: String) { if (v in BAR_POS) sp(c).edit().putString(K_BAR_POS, v).apply() }
    fun barTop(c: Context): Boolean = barPos(c) == "top"

    /** home | back | newtab | refresh | bookmarks | extensions (default home). */
    fun homeAction(c: Context): String = sp(c).getString(K_HOME_ACTION, "home").let { if (it in HOME_ACTIONS) it!! else "home" }
    fun setHomeAction(c: Context, v: String) { if (v in HOME_ACTIONS) sp(c).edit().putString(K_HOME_ACTION, v).apply() }

    /** A3: icon + name of a home-button action (bar button, preview and chips use the same table). */
    fun homeIconAndLabel(action: String): Pair<Int, String> = when (action) {
        "back" -> Pair(R.drawable.ic_back, "Back")
        "newtab" -> Pair(R.drawable.ic_add, "New tab")
        "refresh" -> Pair(R.drawable.ic_reload, "Refresh")
        "bookmarks" -> Pair(R.drawable.ic_bookmark, "Bookmarks")
        "extensions" -> Pair(R.drawable.ic_ext, "Extensions")
        else -> Pair(R.drawable.ic_home, "Home")
    }

    fun fullUrl(c: Context): Boolean = sp(c).getBoolean(K_FULL_URL, false)
    fun setFullUrl(c: Context, on: Boolean) { sp(c).edit().putBoolean(K_FULL_URL, on).apply() }

    fun swipeFs(c: Context): Boolean = sp(c).getBoolean(K_SWIPE_FS, false)
    fun setSwipeFs(c: Context, on: Boolean) { sp(c).edit().putBoolean(K_SWIPE_FS, on).apply() }

    fun swipeTabs(c: Context): Boolean = sp(c).getBoolean(K_SWIPE_TABS, false)
    fun newTabBtn(c: Context): Boolean = sp(c).getBoolean(K_NEWTAB, true)
    fun setNewTabBtn(c: Context, on: Boolean) { sp(c).edit().putBoolean(K_NEWTAB, on).apply() }
    fun swipeNav(c: Context): Boolean = sp(c).getBoolean(K_SWIPE_NAV, true)
    fun setSwipeNav(c: Context, on: Boolean) { sp(c).edit().putBoolean(K_SWIPE_NAV, on).apply() }
    fun swipeSide(c: Context): Boolean = sp(c).getBoolean(K_SWIPE_SIDE, true)
    fun setSwipeSide(c: Context, on: Boolean) { sp(c).edit().putBoolean(K_SWIPE_SIDE, on).apply() }
    fun setSwipeTabs(c: Context, on: Boolean) { sp(c).edit().putBoolean(K_SWIPE_TABS, on).apply() }

    /** Wallpaper dim 0..80 (default 35). */
    fun wpDim(c: Context): Int = sp(c).getInt(K_WP_DIM, 35).coerceIn(0, 80)
    fun setWpDim(c: Context, v: Int) { sp(c).edit().putInt(K_WP_DIM, v.coerceIn(0, 80)).apply() }

    /** Wallpaper file (exists = wallpaper on). Not part of the backup. */
    fun wallpaperFile(c: Context): File = File(c.filesDir, "wallpaper.jpg")
    fun hasWallpaper(c: Context): Boolean = wallpaperFile(c).exists()

    fun undoToast(c: Context): Boolean = sp(c).getBoolean(K_UNDO_TOAST, true)
    fun setUndoToast(c: Context, on: Boolean) { sp(c).edit().putBoolean(K_UNDO_TOAST, on).apply() }

    /** ON = a fresh home tab at every launch (restored tabs stay). OFF = today's behaviour. */
    /** A5: snackbars anchor above the bar only when it is at the bottom; in top mode they sit at the screen bottom. */
    fun snackAnchor(a: android.app.Activity): android.view.View? =
        if (barTop(a)) null else a.findViewById<android.view.View>(R.id.bottom_bar)

    /** A11: slimmer bar (44 dp instead of 52 dp), applied at start. Default OFF = today's bar. */
    fun compactBar(c: Context): Boolean = sp(c).getBoolean(K_BAR_COMPACT, false)
    fun setCompactBar(c: Context, on: Boolean) { sp(c).edit().putBoolean(K_BAR_COMPACT, on).apply() }

    /** A1: Back / Forward arrows inside the bar pill (default OFF = today's bar). navHide = hide an unavailable arrow instead of dimming it. */
    fun navBack(c: Context): Boolean = sp(c).getBoolean(K_NAV_BACK, false)
    fun setNavBack(c: Context, on: Boolean) { sp(c).edit().putBoolean(K_NAV_BACK, on).apply() }
    fun navFwd(c: Context): Boolean = sp(c).getBoolean(K_NAV_FWD, false)
    fun setNavFwd(c: Context, on: Boolean) { sp(c).edit().putBoolean(K_NAV_FWD, on).apply() }
    fun navHide(c: Context): Boolean = sp(c).getBoolean(K_NAV_HIDE, false)
    fun setNavHide(c: Context, on: Boolean) { sp(c).edit().putBoolean(K_NAV_HIDE, on).apply() }

    /** A2: default text zoom 50..250 in steps of 10 (default 100 = as before). */
    fun zoomDef(c: Context): Int = (sp(c).getInt(K_ZOOM_DEF, 100).coerceIn(50, 250) / 10) * 10
    fun setZoomDef(c: Context, v: Int) { sp(c).edit().putInt(K_ZOOM_DEF, (v.coerceIn(50, 250) / 10) * 10).apply() }

    /** A3 / A4: Force enable zoom, touchpad swipe, simplified-view pill. All default OFF. */
    fun forceZoom(c: Context): Boolean = sp(c).getBoolean(K_FORCE_ZOOM, false)
    fun setForceZoom(c: Context, on: Boolean) { sp(c).edit().putBoolean(K_FORCE_ZOOM, on).apply() }
    fun touchpad(c: Context): Boolean = sp(c).getBoolean(K_TOUCHPAD, false)
    fun setTouchpad(c: Context, on: Boolean) { sp(c).edit().putBoolean(K_TOUCHPAD, on).apply() }
    fun simpleHint(c: Context): Boolean = sp(c).getBoolean(K_SIMPLE_HINT, false)
    fun setSimpleHint(c: Context, on: Boolean) { sp(c).edit().putBoolean(K_SIMPLE_HINT, on).apply() }

    fun startPage(c: Context): Boolean = sp(c).getBoolean(K_START_PAGE, false)
    fun setStartPage(c: Context, on: Boolean) { sp(c).edit().putBoolean(K_START_PAGE, on).apply() }
}
