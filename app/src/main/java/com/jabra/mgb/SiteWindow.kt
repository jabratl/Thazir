package com.jabra.mgb

import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.net.Uri

/**
 * OWN WINDOW PER SITE (0.0.247, A7, experimental). Switch sm_win (default OFF) in mgb_ui.
 * ON: the pinned home-screen shortcut (P6) and the Open button of the Social sites page start MainActivity as its own document task
 * (FLAG_ACTIVITY_NEW_DOCUMENT, one per site), so Recent apps shows one card per site (site name + letter icon). That window starts with
 * only that site (no session restore) and never saves the session, so the main browser tabs are not touched.
 * App-wide helpers follow the window in front (A7b): BrowserExtras.focus (file chooser, permission asks), DlFly, tabsRef, onNativePanel,
 * AppearanceScreen.onChange are re-bound in MainActivity.bindWindow on every onResume. TabsApi (extension bridge) and GroupStrip stay with the main
 * window (site windows have no group strip; extensions do not see site window tabs).
 */
object SiteWindow {
    const val ACTION = "com.jabra.mgb.SITE_WINDOW"
    private const val EXTRA = "mgb_site_id"
    private const val KEY = "sm_win"

    /** true while a normal (main) MainActivity is alive; false when only site windows run. */
    @Volatile var mainUp = false
    /** number of site windows alive (MainActivity.onResume re-points tabsRef only when this is > 0). */
    @Volatile var live = 0
    /** true once any site window was created in this process (main window then re-binds the shared helpers on every onResume). */
    @Volatile var used = false

    private fun sp(c: Context) = c.getSharedPreferences("mgb_ui", Context.MODE_PRIVATE)
    fun on(c: Context): Boolean = sp(c).getBoolean(KEY, false)
    fun setOn(c: Context, v: Boolean) { sp(c).edit().putBoolean(KEY, v).apply() }

    fun isSite(intent: Intent?): Boolean = intent?.action == ACTION
    private fun siteId(intent: Intent?): String? = intent?.getStringExtra(EXTRA)

    /** Intent for the site window; the data address makes the document task unique per site (same site again = same window). */
    fun intentFor(ctx: Context, id: String): Intent =
        Intent(ctx, MainActivity::class.java).setAction(ACTION).putExtra(EXTRA, id)
            .setData(Uri.parse("mgbsite://" + id))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_DOCUMENT)

    // ---- 0.0.299 "Install app": any web page as its own window (WebApp.kt makes the shortcut) ----
    const val EXTRA_APP_URL = "mgb_app_url"
    private const val EXTRA_APP_ID = "mgb_app_id"
    private const val EXTRA_APP_NAME = "mgb_app_name"
    private const val EXTRA_APP_BAR = "mgb_app_nobar"
    private const val EXTRA_APP_THEME = "mgb_app_theme"

    /** Own document task per app id (same app again = same window), like the social site windows. */
    fun appIntent(ctx: Context, id: String, url: String, name: String, hideBar: Boolean, theme: Int): Intent =
        Intent(ctx, MainActivity::class.java).setAction(ACTION)
            .putExtra(EXTRA_APP_URL, url).putExtra(EXTRA_APP_ID, id).putExtra(EXTRA_APP_NAME, name)
            .putExtra(EXTRA_APP_BAR, hideBar).putExtra(EXTRA_APP_THEME, theme)
            .setData(Uri.parse("mgbapp://" + id))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_DOCUMENT)

    /** MainActivity.onCreate of an installed app window: one tab with the app address, own Recent apps card, bar hidden when chosen. Only the first start loads the page. */
    private fun startApp(act: Activity, tabs: TabManager, intent: Intent) {
        val url = intent.getStringExtra(EXTRA_APP_URL).orEmpty()
        if (!(url.startsWith("http://") || url.startsWith("https://"))) { tabs.newTab(TabManager.HOME_PAGE); return }
        val id = intent.getStringExtra(EXTRA_APP_ID).orEmpty()
        val name = intent.getStringExtra(EXTRA_APP_NAME).orEmpty().ifBlank { Util.host(url) }
        val icon = try { WebApp.iconFile(act, id).takeIf { it.exists() }?.let { android.graphics.BitmapFactory.decodeFile(it.absolutePath) } } catch (_: Exception) { null }
            ?: Favicons.get(Util.host(url))
        describeApp(act, name, icon, intent.getIntExtra(EXTRA_APP_THEME, 0))
        if (intent.getBooleanExtra(EXTRA_APP_BAR, false)) act.findViewById<android.view.View>(R.id.bottom_bar)?.let { b ->
            (b.layoutParams as? android.view.ViewGroup.MarginLayoutParams)?.apply { height = 0; topMargin = 0; bottomMargin = 0 } // clipped, so later "visible" calls show nothing
            b.visibility = android.view.View.INVISIBLE; b.requestLayout()
        }
        tabs.newTab(url)
    }

    @Suppress("DEPRECATION")
    private fun describeApp(act: Activity, label: String, icon: Bitmap?, theme: Int) {
        try {
            val bmp = icon?.let { Bitmap.createScaledBitmap(it, 96, 96, true) } ?: Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888).also { b ->
                val c = Canvas(b); c.drawColor(act.getColor(R.color.mg_primary))
                val t = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = act.getColor(R.color.mg_on_primary); textSize = 44f; typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL); textAlign = Paint.Align.CENTER }
                c.drawText(label.trim().take(1).uppercase(), 48f, 48f - (t.ascent() + t.descent()) / 2f, t)
            }
            act.setTaskDescription(ActivityManager.TaskDescription(label, bmp, if (theme != 0) theme else act.getColor(R.color.mg_primary)))
        } catch (e: Exception) { android.util.Log.w("Thazir.win", "app description failed", e) }
    }

    /** Start (or bring to front) the window of this site. False = could not start (caller falls back to a normal tab). */
    fun launch(act: Activity, p: SocialProfiles.Profile): Boolean = try {
        act.startActivity(intentFor(act, p.id)); true
    } catch (e: Exception) {
        android.util.Log.w("Thazir.win", "launch failed", e); false
    }

    /** MainActivity.onCreate of a site window: one tab with the site start page + Recent apps card name / icon. */
    fun start(act: Activity, tabs: TabManager, intent: Intent?, fresh: Boolean) {
        tabs.ownsSession = false // 0.0.483: a site window never writes the saved session (also not the kept Incognito list)
        if (intent != null && intent.hasExtra(EXTRA_APP_URL)) { startApp(act, tabs, intent); return } // 0.0.299 (installed app window)
        val p = siteId(intent)?.let { SocialProfiles.byId(it) }
        if (p == null) { tabs.newTab(TabManager.HOME_PAGE); return }
        if (!SocialProfiles.siteOn(act, p.id)) SocialProfiles.setSiteOn(act, p.id, true)
        describe(act, p.label)
        if (fresh) SocialSplash.show(act, p.label)
        SocialMode.open(act, tabs, p.startUrl)
    }

    /** Recent apps card: site name + violet letter icon (same look as the shortcut icon). */
    @Suppress("DEPRECATION")
    private fun describe(act: Activity, label: String) {
        try {
            val s = 96
            val bmp = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            c.drawColor(act.getColor(R.color.mg_primary))
            val t = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = act.getColor(R.color.mg_on_primary)
                textSize = 44f
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                textAlign = Paint.Align.CENTER
            }
            c.drawText(label.trim().take(1).uppercase(), s / 2f, s / 2f - (t.ascent() + t.descent()) / 2f, t)
            act.setTaskDescription(ActivityManager.TaskDescription(label, bmp))
        } catch (e: Exception) {
            android.util.Log.w("Thazir.win", "task description failed", e)
        }
    }
}
