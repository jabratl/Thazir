package com.jabra.mgb

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.app.Activity
import android.graphics.Color
import android.os.Looper
import android.webkit.WebView
import androidx.core.view.WindowCompat
import java.lang.ref.WeakReference
import java.util.WeakHashMap

/**
 * SOCIAL SYSTEM BAR COLOUR (0.0.240, A2): on a social page the status bar + navigation bar take the colour of the site
 * (meta theme-color, read after the page loads; fallback = the app background mg_bg). Switch "Match phone bars to the site"
 * (sm_bars, default ON; only does anything while Social sites + the site switch are ON). A non-social page, another tab,
 * Desktop-by-hand or switch OFF puts the original bar colours back. The page script only READS one meta tag.
 * Hooks: SocialMode.inject (read colour, page loads + in-page routes) and MainActivity onPageInfo / onActiveChanged (apply).
 *
 * 0.0.424: PAGE THEME-COLOR for every normal web page (switch "page_tc" in mgb_ui, default OFF, Appearance > Look). Same machinery, so
 * there is ONE owner of the window bar colours (no second class that could save a wrong "original"). Order in sync(): social site
 * (as before) > page theme-color > original colours. Only the bar that touches the page is tinted: bar at the bottom = status bar,
 * bar at the top = navigation bar (the other side touches the address pill and keeps the app colour). Not for the speed dial,
 * extension pages, saved pages. A light theme-color on a page the browser itself darkens (Dark pages) is ignored (white bar over a dark page).
 * Also fixed: the saved "original" colours are forgotten when the Activity was recreated (theme / accent change), so a bar is never
 * restored to the colour of the previous theme; an animation is never restarted for the same target.
 */
object SocialBars {
    private var act: WeakReference<Activity>? = null
    private val colors = WeakHashMap<WebView, Int>() // colour read from the page (per WebView)
    private val asked = WeakHashMap<WebView, Long>()
    private var saved = false
    private var oStatus = 0
    private var oNav = 0
    private var oLightS = false
    private var oLightN = false
    private var shownS = 0
    private var shownN = 0
    private var anim: ValueAnimator? = null

    /** Picks the theme-color meta whose media query matches (dark / light), turns any CSS colour into rgb(...) with the browser itself. */
    private const val JS = "(function(){try{var m=document.querySelectorAll('meta[name=theme-color]'),v='';" +
        "for(var i=0;i<m.length;i++){var md=m[i].getAttribute('media');if(!md||(window.matchMedia&&matchMedia(md).matches)){v=m[i].getAttribute('content')||'';break;}}" +
        "if(!v)return '';var e=document.createElement('i');e.style.color=v;(document.body||document.documentElement).appendChild(e);" +
        "var c=getComputedStyle(e).color;e.remove();return c||'';}catch(x){return '';}})()"

    private fun sp(c: android.content.Context) = c.applicationContext.getSharedPreferences("mgb_ui", android.content.Context.MODE_PRIVATE)

    /** 0.0.424: switch "Page colour on the phone bars" (default OFF). */
    fun pageOn(c: android.content.Context): Boolean = sp(c).getBoolean("page_tc", false)
    fun setPageOn(c: android.content.Context, on: Boolean) { sp(c).edit().putBoolean("page_tc", on).apply() }

    /** Called from SocialMode.inject (social pages only). Reads the site colour (throttled 1 s per page) and applies it when this is the active tab. */
    fun read(ctx: android.content.Context, view: WebView?) {
        if (view == null || !(SocialMode.barsOn(ctx) || pageOn(ctx))) return
        val now = System.currentTimeMillis()
        if (now - (asked[view] ?: 0L) < 1000L) return
        asked[view] = now
        view.evaluateJavascript(JS) { raw ->
            val c = parse(raw)
            if (c != null) colors[view] = c else colors.remove(view)
            val a = act?.get() ?: return@evaluateJavascript
            val t = BookmarksScreen.tabsRef?.active
            if (t != null && t.web === view) sync(a, t)
        }
    }

    /** 0.0.424: normal web page finished / navigated inside the page. Reads now and once more after 1.3 s (single page sites set the meta late). Switch OFF = returns at once. */
    fun readPage(ctx: android.content.Context, view: WebView?) {
        if (view == null || !pageOn(ctx)) return
        read(ctx, view)
        try { view.postDelayed({ try { read(ctx, view) } catch (_: Throwable) { } }, 1300L) } catch (_: Throwable) { }
    }

    /** Switch just changed: read the active page now and apply (or restore). */
    fun refresh(a: Activity, tab: TabManager.Tab?) {
        try { if (tab != null && !tab.isExt && pageOn(a)) { asked.remove(tab.web); read(a, tab.web) } } catch (_: Throwable) { }
        sync(a, tab)
    }

    /** "\"rgb(12, 34, 56)\"" or "\"rgba(...)\"" -> opaque colour; transparent / empty -> null. */
    private fun parse(raw: String?): Int? {
        val s = (raw ?: "").trim().trim('"')
        val m = Regex("""rgba?\(\s*(\d+)[ ,]+(\d+)[ ,]+(\d+)(?:[ ,/]+([\d.]+))?\s*\)""").find(s) ?: return null
        val al = m.groupValues[4].toDoubleOrNull() ?: 1.0
        if (al < 0.5) return null
        return Color.rgb(m.groupValues[1].toInt().coerceIn(0, 255), m.groupValues[2].toInt().coerceIn(0, 255), m.groupValues[3].toInt().coerceIn(0, 255))
    }

    /** Colour the page asks for, or null (switch OFF, no page colour, speed dial / extension / saved page, or a light colour on a page we darken). */
    private fun pageColor(a: Activity, tab: TabManager.Tab?): Int? {
        if (tab == null || !pageOn(a) || tab.isExt || tab.home || tab.savedPage) return null
        if (!tab.url.startsWith("http://") && !tab.url.startsWith("https://")) return null
        val c = colors[tab.web] ?: return null
        if (UiMode.appDark(a) && SiteDark.effective(a, tab.url) && luminance(c) > 0.5) return null
        return c
    }

    /** Active tab changed / page info changed. Safe to call often. */
    fun sync(a: Activity, tab: TabManager.Tab?) {
        if (Looper.myLooper() != Looper.getMainLooper()) return
        if (act?.get() !== a) { anim?.cancel(); anim = null; saved = false } // 0.0.424: new Activity (recreate) = new window with its own original colours
        act = WeakReference(a)
        val id = if (tab == null || tab.isExt || tab.savedPage || tab.desktop || !SocialMode.on(a) || !SocialMode.barsOn(a)) null else SocialProfiles.of(tab.url)?.id
        if (id != null && tab != null && SocialProfiles.siteOn(a, id)) {
            val c = colors[tab.web] ?: a.getColor(R.color.mg_bg)
            apply(a, c, c)
            return
        }
        val pc = pageColor(a, tab)
        if (pc == null) { restore(a); return }
        if (AppearancePrefs.barTop(a)) apply(a, null, pc) else apply(a, pc, null) // the bar next to the page: status bar (pill at the bottom) / navigation bar (pill at the top)
    }

    /** s / n = colour for the status / navigation bar, null = its original colour. */
    private fun apply(a: Activity, s: Int?, n: Int?) {
        try {
            val w = a.window
            val ic = WindowCompat.getInsetsController(w, w.decorView)
            if (!saved) {
                saved = true
                oStatus = w.statusBarColor; oNav = w.navigationBarColor
                oLightS = ic.isAppearanceLightStatusBars; oLightN = ic.isAppearanceLightNavigationBars
                shownS = oStatus; shownN = oNav
            }
            val ts = s ?: oStatus
            val tn = n ?: oNav
            ic.isAppearanceLightStatusBars = if (s != null) luminance(s) > 0.5 else oLightS
            ic.isAppearanceLightNavigationBars = if (n != null) luminance(n) > 0.5 else oLightN
            if (ts == shownS && tn == shownN) return // same target (a running fade keeps going)
            paint(a, ts, tn, !TabPrefs.reduce(a))
        } catch (_: Exception) { }
    }

    private fun restore(a: Activity) {
        if (!saved) return
        try {
            val w = a.window
            val ic = WindowCompat.getInsetsController(w, w.decorView)
            ic.isAppearanceLightStatusBars = oLightS; ic.isAppearanceLightNavigationBars = oLightN
            if (shownS != oStatus || shownN != oNav) paint(a, oStatus, oNav, !TabPrefs.reduce(a))
        } catch (_: Exception) { }
        // "saved" stays true: the originals belong to this window and only change with a recreate (handled in sync)
    }

    /** 200 ms colour fade of both bars from what is on screen now (instant with Reduce motion). */
    private fun paint(a: Activity, ts: Int, tn: Int, animate: Boolean) {
        anim?.cancel(); anim = null
        val w = a.window
        val fs = w.statusBarColor
        val fn = w.navigationBarColor
        shownS = ts; shownN = tn
        if (!animate) { w.statusBarColor = ts; w.navigationBarColor = tn; return }
        val ev = ArgbEvaluator()
        anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 200
            addUpdateListener { v ->
                val f = v.animatedFraction
                w.statusBarColor = ev.evaluate(f, fs, ts) as Int
                w.navigationBarColor = ev.evaluate(f, fn, tn) as Int
            }
            start()
        }
    }

    private fun luminance(c: Int): Double = (0.299 * Color.red(c) + 0.587 * Color.green(c) + 0.114 * Color.blue(c)) / 255.0
}
