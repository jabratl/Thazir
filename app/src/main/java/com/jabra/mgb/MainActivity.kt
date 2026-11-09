package com.jabra.mgb

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageView
import android.widget.FrameLayout
import android.view.WindowManager
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.progressindicator.LinearProgressIndicator

/** S2: browser screen (URL bar, progress, back/forward/reload). Tabs live in TabManager (S3). */
class MainActivity : AppCompatActivity(), TabManager.Listener {

    private lateinit var tabs: TabManager
    private lateinit var urlBar: EditText
    private lateinit var btnHome: ImageView // S22
    private lateinit var btnBack: ImageView // A1: optional Back arrow in the bar
    private lateinit var btnFwd: ImageView // A1: optional Forward arrow in the bar
    private lateinit var btnTabs: MaterialButton
    private lateinit var btnShield: ImageView // S68: Privacy Guard
    private lateinit var btnNewTab: ImageView // 0.0.319: "+" next to the address bar
    private lateinit var btnEngine: TextView // S69: search engine badge
    private lateinit var progress: LinearProgressIndicator
    private lateinit var backCb: OnBackPressedCallback
    private lateinit var floating: FloatingDl // S11
    private var lastNight = 0
    private var siteWin = false // 0.0.247 (A7): this window is a site window (own task for one social site)
    private var shared = true // 0.0.247 (A7): this window owns the app-wide helpers (false = a site window while the main window lives)

    // S29 FIX: the saved theme must be set BEFORE AppCompat reads it (attachBaseContext). Before, the first start
    // after an update used the system theme for the home screen (white tiles on dark) until Light/Dark was toggled.
    override fun attachBaseContext(newBase: Context) {
        try { UiMode.apply(newBase) } catch (e: Exception) { /* keep default */ }
        super.attachBaseContext(newBase)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        siteWin = SiteWindow.isSite(intent); shared = !siteWin || !SiteWindow.mainUp // 0.0.247 (A7)
        if (siteWin) { SiteWindow.live++; SiteWindow.used = true } else SiteWindow.mainUp = true
        UiMode.apply(this) // S20: saved theme (System / Light / Dark), before the Activity is created
        AdBlock.init(this) // S19
        DlRules.init(this) // D2
        FilterList.init(this) // S74: EasyList rules load in the background
        Favicons.init(this) // S45
        SiteConfig.init(this) // S29
        ClosedTabs.init(this) // S29
        super.onCreate(savedInstanceState)
        noisyOn() // 0.0.310
        AccentTheme.apply(this) // 0.0.289: accent overlay (before the layout is inflated)
        setContentView(R.layout.activity_main)
        if (AppearancePrefs.compactBar(this)) findViewById<View>(R.id.bottom_bar)?.layoutParams?.height = Util.dp(this, 44) // A11: compact bar
        if (AppearancePrefs.barTop(this)) { // A4: bar position Top = move the pill to index 0, margins top 8 / bottom 6
            val bb = findViewById<View>(R.id.bottom_bar)
            val bp = bb.parent as? ViewGroup
            val lp = bb.layoutParams as? ViewGroup.MarginLayoutParams
            if (bp != null && lp != null) {
                bp.removeView(bb)
                lp.setMargins(Util.dp(this, 10), Util.dp(this, 8), Util.dp(this, 10), Util.dp(this, 6))
                bp.addView(bb, 0, lp)
            }
        }
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE) // S22: bottom bar rides above the keyboard
        lastNight = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        Orient.apply(this) // S35: phones keep the browser in portrait (setting: Rotate browser with phone)
        CrashLog.install(this) // FIX: save crash trace for the next start
        CloudSync.init(this) // 0.0.311: Google sign-in launcher (before STARTED)
        BrowserExtras.init(this) // S14 (A7b: every window registers its own launchers, focus() picks the one in front): file chooser + permission launchers (before STARTED)

        Util.loadEngine(this) // S33
        urlBar = findViewById(R.id.url_bar)
        urlBar.typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL) // 0.0.281: stronger address text
        btnHome = findViewById(R.id.btn_home)
        btnBack = findViewById(R.id.btn_back) // A1
        btnFwd = findViewById(R.id.btn_fwd) // A1
        btnTabs = findViewById(R.id.btn_tabs)
        btnShield = findViewById(R.id.btn_shield) // S68
        btnNewTab = findViewById(R.id.btn_newtab) // 0.0.319
        btnNewTab.setOnClickListener { // same as Chrome: a private tab opens another private tab
            it.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
            tabs.newTab(TabManager.HOME_PAGE, 0, true, tabs.active?.priv == true)
        }
        btnNewTab.setOnLongClickListener {
            it.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
            tabs.newTab(TabManager.HOME_PAGE, 0, true, true)
            true
        }
        btnEngine = findViewById(R.id.btn_engine) // S69
        progress = findViewById(R.id.progress)
        progress.trackColor = android.graphics.Color.TRANSPARENT // S21d: only the accent line, thicker
        progress.trackThickness = (3 * resources.displayMetrics.density).toInt()
        EngineHost.start(this) // S6 (A7b: safe twice, it starts once for the whole app): background.js engine (hidden WebView)
        tabs = TabManager(this, findViewById<FrameLayout>(R.id.web_container), this)
        if (shared) TabsApi.attach(tabs)
        if (shared) { GroupStrip.attach(this, tabs); SocialDock.attach(this, tabs); TabSwitcher.onChanged = { GroupStrip.refresh() } } // S13
        BookmarksScreen.tabsRef = tabs // S25
        if (shared) TabsApi.onNativePanel = { floating.openSheet() } // MGB S51: Grabber page button -> Files detected! panel
        window.decorView.post { DlMode.init(this) } // S54: Smart download buttons on first start
        tabs.onHomeSearch = { // S28
            urlBar.requestFocus()
            urlBar.selectAll()
            (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(urlBar, 0)
        }
        tabs.onWebVideo = { src, title -> // S45: in-page video button
            val t = tabs.active
            if (t != null && (src.startsWith("http://", true) || src.startsWith("https://", true)))
                WebPlay.play(this, src, title.ifBlank { null }, t.url, t.web.settings.userAgentString)
            else floating.openSheet()
        }
        val barView = findViewById<View>(R.id.bottom_bar) // S33: hide the bottom bar while scrolling down (Settings > General)
        var acc = 0
        tabs.onScroll = { dy ->
            val on = getSharedPreferences("mgb_ui", Context.MODE_PRIVATE).getBoolean("hide_bar", false)
            if (BarGestures.active) { acc = 0 } // A6: page fullscreen keeps the bar hidden
            else if (!on || urlBar.hasFocus()) { if (barView.visibility != View.VISIBLE) barView.visibility = View.VISIBLE; acc = 0 }
            else {
                if ((dy > 0) != (acc > 0)) acc = 0
                acc += dy
                if (acc > Util.dp(this, 40) && barView.visibility == View.VISIBLE) barView.visibility = View.GONE
                else if (acc < -Util.dp(this, 20) && barView.visibility != View.VISIBLE) barView.visibility = View.VISIBLE
            }
            GroupStrip.sync() // S13
            SocialDock.sync() // 0.0.272 (C1)
        }
        floating = FloatingDl(this, findViewById<FrameLayout>(R.id.web_container).parent as FrameLayout, tabs) // S11

        btnHome.setOnClickListener { homeClick() } // S22, 0.0.130: Home opens in the same tab; APPEARANCE A3: action from Appearance (default = Home)
        btnBack.setOnClickListener { tabs.active?.let { if (tabs.canBack(it)) tabs.back(it) } } // A1
        btnFwd.setOnClickListener { tabs.active?.let { if (tabs.canFwd(it)) tabs.fwd(it) } } // A1
        btnBack.setOnLongClickListener { NavHistory.show(this, tabs, false) } // A6: hold = page list
        btnFwd.setOnLongClickListener { NavHistory.show(this, tabs, true) } // A6
        applyHomeIcon() // A3
        applyNavArrows() // A1
        if (shared) AppearanceScreen.onChange = { applyHomeIcon(); applyNavArrows(); refreshUrlField(); refreshShield() } // A3: live update while the Appearance screen is open
        btnTabs.setOnClickListener { tabs.showSwitcher() }
        btnShield.setOnClickListener { PrivacyGuard.show(this, tabs) { refreshShield() } } // S68
        btnEngine.setOnClickListener { EnginePicker.show(this, btnEngine) { refreshShield() } } // S69
        findViewById<ImageView>(R.id.btn_menu).setOnClickListener { showMenu() } // S22
        if (shared) DlFly.attach(this, findViewById(R.id.btn_menu)) // S21e
        BarGestures.attach(this, tabs) // A6
        EdgeSwipe.attach(this) // 0.0.294

        urlBar.setOnEditorActionListener { _, actionId, event ->
            val go = actionId == EditorInfo.IME_ACTION_GO ||
                (event != null && event.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)
            if (go) submitUrl()
            go
        }
        urlBar.setOnFocusChangeListener { _, focused ->
            if (focused) {
                urlBar.setText(if (tabs.active?.home == true) "" else (tabs.active?.url ?: "")) // S13
                urlBar.post { urlBar.selectAll() }
            } else refreshUrlField()
            refreshShield() // S68: shield hides while typing
        }

        urlBar.setOnLongClickListener { // 0.0.287 (#5): long-press the address (not while typing) = Copy link / Paste & go / Share
            val t = tabs.active
            val u = t?.let { it.web.url ?: it.url } ?: ""
            if (urlBar.hasFocus() || t == null || t.home || !u.startsWith("http")) return@setOnLongClickListener false
            val clip = try { (getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager).primaryClip?.getItemAt(0)?.coerceToText(this)?.toString()?.trim() ?: "" } catch (_: Exception) { "" }
            MenuSheet.showLink(this, Util.host(u), u, listOf(
                MenuSheet.Item("Copy link", R.drawable.ic_copy) { tabs.copyText(u) },
                MenuSheet.Item("Paste & go", R.drawable.ic_paste, clip.isNotEmpty()) { urlBar.setText(clip); submitUrl() },
                MenuSheet.Item("Share", R.drawable.ic_share) { tabs.shareText(u) }
            ))
            true
        }

        backCb = object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val t = tabs.active
                when {
                    BarGestures.active -> BarGestures.exit(this@MainActivity) // A6
                    tabs.inFullscreen -> tabs.exitFullscreen() // S14
                    urlBar.hasFocus() -> leaveUrlBar()
                    t != null && tabs.canBack(t) -> tabs.back(t) // 0.0.130: Home counts as a page
                    t != null && SocialBack.hold(this@MainActivity, t) -> Unit // 0.0.241 (A3): first page of a social tab: press Back again
                    t != null && tabs.count > 1 -> TabSwitcher.closeWithUndo(this@MainActivity, tabs, t) // 0.0.363 (P2): Undo bar
                    else -> {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                    }
                }
            }
        }
        onBackPressedDispatcher.addCallback(this, backCb)

        if (siteWin) SiteWindow.start(this, tabs, intent, savedInstanceState == null) // 0.0.247 (A7): only this site, no session restore
        else if (!tabs.restoreSession()) tabs.newTab(TabManager.HOME_PAGE) // S13
        else if (AppearancePrefs.startPage(this)) tabs.newTab(TabManager.HOME_PAGE) // A8: restored tabs + a fresh home tab selected
        LinkInbox.fromIntent(this, tabs, intent) // D1
        if (savedInstanceState == null) SocialShortcuts.fromIntent(this, tabs, intent) // 0.0.230 (P6): home-screen shortcut
        if (savedInstanceState == null) HomeShortcut.fromIntent(this, tabs, intent) // 0.0.298: "Add to home screen" icon of a page
        if (savedInstanceState == null) SocialShortcuts.syncDynamic(this) // 0.0.253 (D6): launcher long-press shortcuts (switch sm_dyn)
        if (savedInstanceState == null) { SocialLinks.sync(this); SocialLinks.fromIntent(this, tabs, intent) } // 0.0.246 (A6): link from another app
        UpdateManager.autoCheck(this)
        CrashLog.showLast(this) // FIX
        AppLock.onCreate(this) // S45: lock screen at cold start (if ON)
        window.decorView.post { PlayerScreen.reopenPending(this) } // S35: player comes back if the Activity was recreated
        window.decorView.post { AppearanceScreen.reopenPending(this) } // APPEARANCE A2: Appearance screen comes back after a theme change
        window.decorView.postDelayed({ Backup.auto(this) }, 10000) // S42: daily auto backup (if ON)
        if (savedInstanceState == null) window.decorView.postDelayed({ HowToUse.maybeOffer(this) }, 2500) // V14: one-time welcome offer
    }

    /** APPEARANCE A3: the left bar button does what Appearance > Address bar says (default = Home, as before). */
    private fun homeClick() {
        when (AppearancePrefs.homeAction(this)) {
            "back" -> tabs.active?.let { if (tabs.canBack(it)) tabs.back(it) }
            "newtab" -> tabs.newTab(TabManager.HOME_PAGE)
            "refresh" -> reloadOrStop()
            "bookmarks" -> BookmarksScreen.open(this, BookmarksScreen.Mode.BOOKMARKS, tabs)
            "extensions" -> ExtTray.show(this)
            else -> tabs.goHome()
        }
    }

    /** APPEARANCE A3: icon + label of the left bar button follow the chosen action. */
    private fun applyHomeIcon() {
        val (icon, label) = AppearancePrefs.homeIconAndLabel(AppearancePrefs.homeAction(this))
        btnHome.setImageResource(icon)
        btnHome.contentDescription = label
    }

    /** A1: both arrows ON on a narrow screen (< 400 dp) = smaller arrows, no engine badge, url field keeps at least 96 dp. */
    private fun navTight(): Boolean =
        AppearancePrefs.navBack(this) && AppearancePrefs.navFwd(this) && resources.configuration.screenWidthDp < 400

    /** A1: show / hide the Back + Forward arrows by prefs; dim (alpha .38) or hide an unavailable arrow. No animation, so Reduce motion needs nothing. */
    private fun applyNavArrows() {
        if (!::tabs.isInitialized) return
        val cur = tabs.active
        val hide = AppearancePrefs.navHide(this)
        val w = Util.dp(this, if (navTight()) 32 else 36)
        fun one(v: ImageView, on: Boolean, can: Boolean) {
            val show = on && !(hide && !can)
            v.visibility = if (show) View.VISIBLE else View.GONE
            v.isEnabled = can
            v.alpha = if (can) 1f else 0.38f
            v.setColorFilter(getColor(R.color.mg_text))
            v.layoutParams?.let { lp -> if (lp.width != w) { lp.width = w; v.layoutParams = lp } }
        }
        one(btnBack, AppearancePrefs.navBack(this), cur != null && tabs.canBack(cur))
        one(btnFwd, AppearancePrefs.navFwd(this), cur != null && tabs.canFwd(cur))
        urlBar.minWidth = if (AppearancePrefs.navBack(this) || AppearancePrefs.navFwd(this)) Util.dp(this, 96) else 0
    }

    /** S22: reload, or stop when the page is still loading (was the top bar button). */
    private fun reloadOrStop() {
        tabs.active?.let {
            if (it.loading) {
                it.web.stopLoading()
                it.progress = 100
                onPageInfo(it)
            } else it.web.reload()
        }
    }

    /** S22: the 3-dot menu as a bottom sheet (MenuSheet). Same actions as the old PopupMenu, same ids/handlers. */
    private fun showMenu() {
        val cur = tabs.active
        val web = cur?.web
        val canMark = cur != null && !cur.home && !cur.isExt && cur.url.startsWith("http")
        val marked = cur != null && canMark && Bookmarks.isBookmarked(cur.url)
        val quickAll = listOf(
            MenuSheet.Quick(R.drawable.ic_bookmark, "Bookmarks") { BookmarksScreen.open(this, BookmarksScreen.Mode.BOOKMARKS, tabs) },
            MenuSheet.Quick(R.drawable.ic_history, "History") { BookmarksScreen.open(this, BookmarksScreen.Mode.HISTORY, tabs) },
            MenuSheet.Quick(R.drawable.ic_download, "Downloads") { DownloadsScreen.open(this) },
            MenuSheet.Quick(R.drawable.ic_play, "Grabber") { if (V77Alt.on(this)) openExt("popup.html") else floating.openSheet() } // S65: V77 only when the backup switch is ON
        )
        val canPage = cur != null && !cur.home && !cur.isExt
        fun barAct(id: String): MenuSheet.Act = when (id) { // 0.0.335: the 5 pinned icons come from MenuBar (hold one 3 s = Edit shortcut)
            "back" -> MenuSheet.Act(R.drawable.ic_back, cur != null && tabs.canBack(cur)) { cur?.let { tabs.back(it) } }
            "forward" -> MenuSheet.Act(R.drawable.ic_forward, cur != null && tabs.canFwd(cur)) { cur?.let { tabs.fwd(it) } }
            "bookmark" -> MenuSheet.Act(if (marked) R.drawable.ic_bookmark else R.drawable.ic_bookmark_border, canMark) { toggleBookmark() }
            "ext" -> MenuSheet.Act(R.drawable.ic_ext, true) { ExtTray.show(this) } // S10: Thazir Extensions tray
            "reload" -> MenuSheet.Act(if (cur?.loading == true) R.drawable.ic_close else R.drawable.ic_reload, cur != null) { reloadOrStop() }
            "home" -> MenuSheet.Act(R.drawable.ic_home, true) { tabs.goHome() }
            "share" -> MenuSheet.Act(R.drawable.ic_share, canMark) { tabs.active?.let { if (it.url.startsWith("http")) tabs.shareText(it.url) } }
            "downloads" -> MenuSheet.Act(R.drawable.ic_download, true) { DownloadsScreen.open(this) }
            "history" -> MenuSheet.Act(R.drawable.ic_history, true) { BookmarksScreen.open(this, BookmarksScreen.Mode.HISTORY, tabs) }
            "bookmarks" -> MenuSheet.Act(R.drawable.ic_bookmark, true) { BookmarksScreen.open(this, BookmarksScreen.Mode.BOOKMARKS, tabs) }
            "newtab" -> MenuSheet.Act(R.drawable.ic_add, true) { tabs.newTab(TabManager.HOME_PAGE) }
            "private" -> MenuSheet.Act(R.drawable.ic_lock, true) { tabs.newTab(TabManager.HOME_PAGE, 0, true, true) }
            "find" -> MenuSheet.Act(R.drawable.ic_search, canMark) { FindBar.open(this, tabs.active?.web) }
            "desktop" -> MenuSheet.Act(R.drawable.ic_desktop, canPage) { tabs.active?.let { tabs.setDesktop(it, !it.desktop) } }
            "settings" -> MenuSheet.Act(R.drawable.ic_settings, true) { SettingsScreen.open(this) }
            else -> MenuSheet.Act(R.drawable.ic_reload, cur != null) { reloadOrStop() }
        }
        val acts = MenuBar.ids(this).map { barAct(it) }
        // S27: general card (app level) and page card (this page)
        val generalTop = listOf(
            MenuSheet.Item("Command Center", R.drawable.ic_search, true, "Actions · tabs · bookmarks") { CommandCenter.show(this, tabs) }, // S7
            MenuSheet.Item("Media Library", R.drawable.ic_play, true, "Detected · downloads · saved") { MediaLibrary.show(this, tabs) }, // S10
            MenuSheet.Item("New private tab", R.drawable.ic_lock) { tabs.newTab(TabManager.HOME_PAGE, 0, true, true) }, // S26
            MenuSheet.Item("Bookmark all tabs", R.drawable.ic_bookmark_border) { TabBookmarks.dialog(this, tabs) }, // S40
            MenuSheet.Item("Passwords", R.drawable.ic_lock) { PasswordStore.screen(this) }, // S40
            MenuSheet.Item("Vault", R.drawable.ic_vault, true, VaultScreen.summary(this)) { VaultScreen.open(this) }, // V3
            MenuSheet.Item("Recently closed tabs", R.drawable.ic_history) { Backup.closedTabs(this, tabs) }, // S29
            MenuSheet.Item("Saved pages", R.drawable.ic_folder, true, PageSave.count(this).let { if (it > 0) it.toString() else "" }) { SavedPages.open(this, tabs) }, // T3, T4: screen
            MenuSheet.Item("Novel grabber", R.drawable.ic_book, true, "EPUB · DOCX · TXT") { openExt("novel/studio.html") }, // N1
            MenuSheet.Item("Queue", R.drawable.ic_list) { openExt("queue.html") },
            MenuSheet.Item("How to use", R.drawable.ic_info, true, "Quick guide") { HowToUse.open(this) }, // V14
            MenuSheet.Item("Report center", R.drawable.ic_info) { DiagScreen.open(this, tabs, -1) } // S85
        )
        val generalEnd = listOf( // 0.0.123 (S14): Settings + Customise menu stay last, pinned extensions sit before them
            MenuSheet.Item("Settings", R.drawable.ic_settings) { SettingsScreen.open(this) },
            MenuSheet.Item(MenuCustom.CUSTOMISE, R.drawable.ic_edit) { MenuCustom.open(this) }
        )
        val pageAll = listOf(
            MenuSheet.Item("Find on page", R.drawable.ic_search, canMark) { FindBar.open(this, tabs.active?.web) },
            MenuSheet.Item(if (cur?.desktop == true) "Mobile site" else "Desktop site", R.drawable.ic_desktop, cur != null && !cur.home && !cur.isExt, "", "Desktop site") {
                tabs.active?.let { tabs.setDesktop(it, !it.desktop) }
            },
            MenuSheet.Item("Clone tab", R.drawable.ic_copy, cur != null && !cur.isExt) { cur?.let { c -> if (tabs.cloneTab(c, true) != null) Util.toast(this, "Tab cloned") } }, // T1
            MenuSheet.Item("Translate page", R.drawable.ic_search, canMark, if (Translate.hasLang(this)) Translate.lang(this) else "") { Translate.page(this, tabs) }, // S41
            MenuSheet.Item("Reader view", R.drawable.ic_list, canMark) { tabs.active?.let { PageInfo.reader(this, it) } }, // S41
            MenuSheet.Item("Text size", R.drawable.ic_edit, canMark, cur?.let { c -> TextZoom.get(this, Util.host(c.url)).takeIf { it != 100 }?.let { "$it%" } } ?: "") { TextZoom.dialog(this, tabs) }, // S41
            MenuSheet.Item("Detected files", R.drawable.ic_download, canMark) { floating.openSheet() }, // S50
            MenuSheet.Item("Page info", R.drawable.ic_info, canMark) { PageInfo.show(this, tabs) }, // S29
            MenuSheet.Item("View page source", R.drawable.ic_code, canMark) { tabs.active?.let { PageSource.open(this, it) } }, // T2
            MenuSheet.Item("Save page", R.drawable.ic_save, canMark && cur?.priv != true) { tabs.active?.let { PageSave.save(this, it) } }, // T3
            MenuSheet.Item("Add to home screen", R.drawable.ic_home, canMark && cur?.priv != true) { tabs.active?.let { HomeShortcut.add(this, it.pending ?: it.url, it.title) } }, // 0.0.298
            MenuSheet.Item("Install app", R.drawable.ic_add, canMark && cur?.priv != true) { tabs.active?.let { t -> WebApp.install(this, if (t.suspended) null else t.web, t.pending ?: t.url, t.title) } }, // 0.0.299
            MenuSheet.Item("Userscripts", R.drawable.ic_code, canMark, cur?.let { c -> Userscripts.matchingNow(this, c.url).count { it.on }.let { n -> if (n > 0) "$n active" else "" } } ?: "") { Userscripts.pageSheet(this, tabs.active?.url ?: "") }, // T8
            MenuSheet.Item("Fill password", R.drawable.ic_lock, canMark && cur?.priv != true) { PasswordStore.fill(this, tabs) }, // S30
            MenuSheet.Item("Email alias", R.drawable.ic_share, true) { EmailAlias.create(this, cur?.url?.takeIf { canMark }?.let { Util.host(it) } ?: "") }, // S30
            MenuSheet.Item("Privacy Guard", R.drawable.ic_shield, true, if (AdBlock.enabled) "ON · ${AdBlock.blocked}" else "OFF") { // S19 + S71: opens the Privacy Guard sheet on web pages
                if (PrivacyGuard.state(tabs.active) != 0) PrivacyGuard.show(this, tabs) { refreshShield() }
                else Util.toast(this, if (AdBlock.toggle()) "Ad blocker ON (reload the page)" else "Ad blocker OFF (reload the page)")
            }
        )
        val onHome = cur?.home == true // 0.0.126: Home / private Home has no page functions, so no page header + rows
        val title = when {
            cur == null -> "Thazir"
            onHome -> ""
            else -> Util.host(cur.url).ifBlank { "New tab" }
        }
        // 0.0.341: order + quick tile slots come from MenuCustom (Customise menu: hold a row, drag). Any row can sit in a tile.
        val homeless = quickAll.map { q -> MenuSheet.Item(q.label, q.icon, true, "", q.key) { q.onClick() } } // the 4 old tiles as rows, shown when a tile gave them up
        val byKey = (homeless + generalTop + generalEnd + pageAll).associateBy { it.key }
        val pageKeys = pageAll.map { it.key }.toSet()
        val quick = if (!MenuCustom.quickShown(this)) emptyList() else MenuCustom.quickKeys(this).mapNotNull { k ->
            byKey[k]?.let { itm ->
                MenuSheet.Quick(itm.icon, itm.label, k) { if (itm.enabled && !(onHome && k in pageKeys)) itm.onClick() else Util.toast(this, "Open a web page first") }
            }
        }
        // 0.0.343: a row MenuCustom does not know (new menu row) is never lost: it stays on top, always shown
        val gItems = generalTop.filter { MenuCustom.entryOf(it.key) == null } + MenuCustom.menuKeys(this, 'G').mapNotNull { byKey[it] }
        var tail = gItems.size // Settings / Customise menu at the end keep the pinned extension rows above them
        while (tail > 0 && (gItems[tail - 1].key == "Settings" || gItems[tail - 1].key == MenuCustom.CUSTOMISE)) tail--
        val general = gItems.take(tail) + MenuCustom.pinnedRows(this) + gItems.drop(tail)
        val page = if (onHome) emptyList() else pageAll.filter { MenuCustom.entryOf(it.key) == null } + MenuCustom.menuKeys(this, 'P').mapNotNull { byKey[it] }
        val share: (() -> Unit)? = if (canMark) ({ tabs.active?.let { if (it.url.startsWith("http")) tabs.shareText(it.url) } }) else null
        MenuSheet.show(this, quick, general, title, share, page, acts, "Thazir ${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE})") { showMenu() } // 0.0.335: reopen after Edit shortcut
    }

    /** S40: open a site in a new tab (Passwords > Open site). */
    fun openSite(url: String) { tabs.newTab(url) }

    private fun openExt(page: String) {
        if (!ExtScreen.open(this, ContentInjector.EXT_BASE + page)) Util.toast(this, "Could not open $page")
    }

    /** S13: add / remove the bookmark of the page in the active tab. */
    private fun toggleBookmark() {
        val t = tabs.active ?: return
        if (t.home || t.isExt || !t.url.startsWith("http")) return
        if (Bookmarks.isBookmarked(t.url)) {
            val gone = Bookmarks.item(t.url)
            Bookmarks.removeBookmark(t.url)
            if (gone != null) BmUndo.bookmarks(window.decorView, listOf(gone)) else Util.toast(this, "Bookmark removed") // 0.0.332
        } else {
            BookmarkFolderPicker.save(this, t.title.ifBlank { Util.host(t.url) }, t.url) // 0.0.328: ask the folder
        }
    }

    // ---------- URL bar ----------

    private fun submitUrl() {
        var target = Util.toUrlOrSearch(urlBar.text.toString())
        val upgraded = if (HttpsOnly.on(this)) HttpsOnly.upgrade(target) else null // S41
        val httpFrom = if (upgraded != null) target else null
        if (upgraded != null) target = upgraded
        leaveUrlBar()
        val t = tabs.active ?: tabs.newTab(null) ?: return
        if (t.home && target == "about:blank") return // S13: empty input on the speed dial = stay
        if (!t.isExt && tabs.playLink(t, target)) return // S39: typed video link = built-in player
        tabs.leaveHome(t) // S13
        t.httpsFrom = httpFrom
        t.url = target
        t.web.loadUrl(target)
    }

    private fun leaveUrlBar() {
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(urlBar.windowToken, 0)
        tabs.active?.web?.requestFocus()
    }

    private fun refreshUrlField() {
        if (urlBar.hasFocus()) { // S3 FIX: while typing, drop the dim host/path spans so typed text stays one color
            urlBar.text?.let { e -> e.getSpans(0, e.length, android.text.style.ForegroundColorSpan::class.java).forEach { e.removeSpan(it) } }
            setUrlLock(null); return
        }
        val u = if (tabs.active?.home == true) "" else (tabs.active?.url ?: "") // S13
        val full = AppearancePrefs.fullUrl(this)
        val s = when {
            u == "about:blank" -> ""
            u.startsWith("http") -> if (full) u else Util.host(u) // A3: full URL option
            else -> u
        }
        if (urlBar.text.toString() != s) {
            if (full && u.startsWith("http") && s.isNotEmpty()) { // S3: host stays strong, the rest of a full URL is dimmed
                val h = Util.host(u)
                val i = s.indexOf(h)
                val sp = android.text.SpannableString(s)
                if (i >= 0) {
                    val dim = getColor(R.color.mg_text_dim)
                    if (i > 0) sp.setSpan(android.text.style.ForegroundColorSpan(dim), 0, i, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    if (i + h.length < s.length) sp.setSpan(android.text.style.ForegroundColorSpan(dim), i + h.length, s.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                urlBar.setText(sp)
            } else urlBar.setText(s)
        }
        setUrlLock(if (u.startsWith("https://")) true else if (u.startsWith("http://")) false else null)
    }

    /** S3: small connection cue at the start of the address field: lock = https, open lock (red) = plain http, none = home / other. */
    private fun setUrlLock(secure: Boolean?) {
        val res: Int? = when (secure) { // S3 FIX: explicit types (no platform-type inference)
            true -> R.drawable.ic_tune // 0.0.335: https = site tune icon (Brave style); plain http keeps the red open lock
            false -> R.drawable.ic_pl_lock_open
            null -> null
        }
        val d: android.graphics.drawable.Drawable? = res?.let { androidx.core.content.ContextCompat.getDrawable(this, it)?.mutate() }
        if (d != null) {
            val sz = Util.dp(this, if (secure == false) 14 else 18)
            d.setBounds(0, 0, sz, sz)
            d.setTint(if (secure == false) getColor(R.color.mg_error) else androidx.core.graphics.ColorUtils.blendARGB(getColor(R.color.mg_text_dim), getColor(R.color.mg_success), 0.6f)) // 0.0.336: https tune icon = soft green (security cue kept)
        }
        urlBar.setCompoundDrawablesRelative(d, null, null, null)
    }

    private var urlBgNormal: android.graphics.drawable.Drawable.ConstantState? = null
    private var urlBgPriv = false

    /** 0.0.287: private tab = address field in a plum tone (violet + rose mixed from theme tokens), so it is never taken for a normal tab. */
    private fun tintUrlBar(priv: Boolean) {
        if (priv == urlBgPriv) return
        if (urlBgNormal == null) urlBgNormal = urlBar.background?.constantState
        urlBgPriv = priv
        if (!priv) { urlBgNormal?.let { urlBar.background = it.newDrawable().mutate() }; return }
        val plum = androidx.core.graphics.ColorUtils.blendARGB(getColor(R.color.mg_primary), getColor(R.color.mg_error), 0.5f)
        fun shape(fill: Int, w: Int, stroke: Int) = android.graphics.drawable.GradientDrawable().apply {
            setColor(fill); cornerRadius = Util.dp(this@MainActivity, 19).toFloat(); setStroke(Util.dp(this@MainActivity, w), stroke)
        }
        val ink = Util.dp(this, 3)
        val rest = shape(androidx.core.graphics.ColorUtils.blendARGB(getColor(R.color.mg_surface_variant), plum, 0.22f), 1, androidx.core.graphics.ColorUtils.blendARGB(getColor(R.color.mg_outline), plum, 0.8f))
        val focus = shape(getColor(R.color.mg_surface), 2, plum)
        val sl = android.graphics.drawable.StateListDrawable()
        sl.addState(intArrayOf(android.R.attr.state_focused), android.graphics.drawable.InsetDrawable(focus, 0, ink, 0, ink))
        sl.addState(intArrayOf(), android.graphics.drawable.InsetDrawable(rest, 0, ink, 0, ink))
        urlBar.background = sl
    }

    override fun getResources(): android.content.res.Resources = AccentTheme.res(this, super.getResources()) // 0.0.289: accent colours

    private fun refreshAll(tab: TabManager.Tab?) {
        val loading = tab?.loading == true
        if (loading) { // S21d: smooth line, fades out when the page is done
            progress.animate().cancel()
            progress.alpha = 1f
            progress.visibility = android.view.View.VISIBLE
            progress.setProgressCompat(maxOf(tab!!.progress, 8), true)
        } else if (progress.visibility == android.view.View.VISIBLE) {
            progress.setProgressCompat(100, true)
            progress.animate().alpha(0f).setStartDelay(180).setDuration(260).withEndAction {
                progress.visibility = android.view.View.GONE
                progress.alpha = 1f
            }.start()
        }
        btnTabs.setTextColor(getColor(if (tab?.priv == true) R.color.mg_primary else R.color.mg_text)) // S26
        tintUrlBar(tab?.priv == true) // 0.0.287
        refreshUrlField()
        refreshShield()
        applyNavArrows() // A1: arrows follow tab switch, page load and history changes
    }

    /** 0.0.336: the shield sits inside the field only when there is room: no Social chip and the field is at least 140 dp wide (0 = not measured yet = ok). */
    private fun shieldRoom(): Boolean =
        findViewById<View>(R.id.btn_social).visibility != View.VISIBLE && (urlBar.width == 0 || urlBar.width >= Util.dp(this, 140))

    /** S68: shield shows on web pages only (not while typing); rose = guarding, grey = blocker off here. */
    private fun refreshShield() {
        val st = PrivacyGuard.state(tabs.active)
        val show = st != 0 && !urlBar.hasFocus() && shieldRoom()
        if (show && btnShield.visibility != View.VISIBLE) {
            btnShield.alpha = 0f; btnShield.visibility = View.VISIBLE
            btnShield.animate().alpha(1f).setDuration(160).start()
        } else if (!show) btnShield.visibility = View.GONE
        btnShield.setColorFilter(getColor(if (st == 2) R.color.mg_text_dim else R.color.mg_primary))
        urlBar.setPaddingRelative(urlBar.paddingStart, urlBar.paddingTop, Util.dp(this, if (show) 40 else 16), urlBar.paddingBottom) // 0.0.335: shield sits inside the field, keep the text clear of it
        // S69: engine badge on the home page and while typing (where the shield is not shown)
        val eng = (tabs.active?.home == true || urlBar.hasFocus()) && !navTight() // A1: no badge when both arrows squeeze the bar
        btnEngine.visibility = if (eng) View.VISIBLE else View.GONE
        if (eng) EnginePicker.styleBadge(this, btnEngine)
        SocialChip.update(this, tabs, urlBar.hasFocus()) // 0.0.286: social chip at the end of the address bar
        if (btnShield.visibility == View.VISIBLE && !shieldRoom()) { // 0.0.336: chip appeared after the shield was decided: shield steps aside
            btnShield.visibility = View.GONE
            urlBar.setPaddingRelative(urlBar.paddingStart, urlBar.paddingTop, Util.dp(this, 16), urlBar.paddingBottom)
        }
        // 0.0.319: "+" hides while typing, on a squeezed bar and when the Social chip is shown, so the address field keeps its room
        val showNew = shared && AppearancePrefs.newTabBtn(this) && !urlBar.hasFocus() && !navTight() && findViewById<View>(R.id.btn_social).visibility != View.VISIBLE
        btnNewTab.visibility = if (showNew) View.VISIBLE else View.GONE
        btnNewTab.setColorFilter(getColor(R.color.mg_text))
    }

    // ---------- TabManager.Listener ----------

    /**
     * 0.0.330 / 0.0.342: private tab = blank picture in Recents. FLAG_SECURE also blocks screenshots, so it is only used when
     * Settings > Private tabs > "Allow screenshots" is OFF, and never while a WhatsApp Web tab is in front.
     * Otherwise: Android 13+ setRecentsScreenshotEnabled(false); older phones: FLAG_SECURE only while the app is paused (onPause / onResume).
     */
    private var privSecure = false // FLAG_SECURE is set on the window now
    private var privHide = false   // Recents picture is hidden through setRecentsScreenshotEnabled
    private fun isWhatsApp(t: TabManager.Tab?): Boolean { val h = t?.let { Util.host(it.pending ?: it.url) } ?: return false; return h == "whatsapp.com" || h.endsWith(".whatsapp.com") }
    fun syncPrivateSecure() { // 0.0.331: public (Settings calls it), follows the switch Hide private tabs in app preview
        val any = PrivateTabs.secureOn(this) && (try { tabs.allTabs().any { it.priv } } catch (e: Exception) { false })
        AppLock.privateOpen = any
        val block = any && !PrivateTabs.shotsOn(this) && !isWhatsApp(tabs.active)
        AppLock.blockShots = block
        try {
            if (block != privSecure) {
                privSecure = block
                if (block) window.setFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE, android.view.WindowManager.LayoutParams.FLAG_SECURE)
                else window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE) // App lock never keeps FLAG_SECURE on this window since 0.0.333
            }
            if (any != privHide) {
                privHide = any
                if (android.os.Build.VERSION.SDK_INT >= 33) {
                    if (any) setRecentsScreenshotEnabled(false) else if (!AppLock.on(this)) setRecentsScreenshotEnabled(true)
                }
            }
        } catch (e: Exception) { }
    }

    override fun onTabsChanged() {
        syncPrivateSecure()
        val tabCount = tabs.count.toString()
        if (btnTabs.text.toString() != tabCount) { // S21c: the count pops when it changes
            btnTabs.text = tabCount
            btnTabs.animate().cancel()
            if (!TabPrefs.reduce(this)) btnTabs.scaleX = 1.35f
            if (!TabPrefs.reduce(this)) btnTabs.scaleY = 1.35f
            btnTabs.animate().scaleX(1f).scaleY(1f).setInterpolator(android.view.animation.OvershootInterpolator(2f)).setDuration(260).start()
        }
        GroupStrip.refresh() // S13
        SocialDock.refresh() // 0.0.272 (C1)
        if (!siteWin) { // 0.0.302: "Close all private tabs" notification
            PrivNotify.closer = { runOnUiThread { tabs.allTabs().filter { it.priv }.forEach { tabs.close(it) } } }
            PrivNotify.update(this, tabs)
        }
    }

    override fun onActiveChanged(tab: TabManager.Tab?) {
        SettingsScreen.closeAll() // V18: a tab was opened / switched (e.g. from Saved pages or Recently closed): Settings underneath closes
        FindBar.close() // S21
        BarGestures.exit(this) // A6: a tab change leaves page fullscreen
        syncPrivateSecure() // 0.0.342: WhatsApp tab in front = no FLAG_SECURE
        findViewById<View>(R.id.bottom_bar).visibility = View.VISIBLE // S33
        refreshAll(tab)
        SocialFull.sync(this, tab) // 0.0.238 (A1)
        SocialLock.check(this, tab) // 0.0.260 (D7)
        SocialBars.sync(this, tab) // 0.0.240 (A2)
        GroupStrip.refresh() // S13
        SocialDock.refresh() // 0.0.272 (C1)
        floating.poke() // S11
    }

    override fun onPageInfo(tab: TabManager.Tab) {
        if (tab === tabs.active) {
            syncPrivateSecure() // 0.0.342: the tab may have navigated to / away from WhatsApp Web
            refreshAll(tab)
            SocialFull.sync(this, tab) // 0.0.238 (A1): returns at once when the switch is OFF
            SocialBars.sync(this, tab) // 0.0.240 (A2)
            GroupStrip.refresh() // S13
            if (tab.progress >= 100) floating.poke() // S11
        }
    }

    // ---------- lifecycle ----------

    override fun onUserLeaveHint() { // 0.0.278 (D8): Home while a social video is full screen = small floating window
        if (::tabs.isInitialized) SocialPip.enter(this, tabs)
        super.onUserLeaveHint()
    }

    override fun onStart() {
        super.onStart()
        AppLock.onStart(this) // S45: lock again after the chosen delay
        AppLock.privStart(this, tabs.active?.priv == true) // 0.0.331: private tabs lock
        SocialLock.onStart(this, tabs.active) // 0.0.260 (D7)
        SocialQuick.onStart(this, tabs.active) // 0.0.307: refresh the feed when you come back
        SocialTime.start(this, tabs) // 0.0.261 (C3)
        VaultStore.onAppStart(this); VaultScreen.refresh() // V3: vault auto-lock
        AutoDiag.start(this, tabs) // S87.2
    }

    override fun onStop() {
        VaultStore.onAppStop(this) // V3
        AppLock.onStop(this) // S45
        AppLock.privStop(this, tabs.active?.priv == true) // 0.0.331
        SocialLock.onStop() // 0.0.260 (D7)
        SocialQuick.onStop() // 0.0.307
        SocialTime.stop() // 0.0.261 (C3)
        AutoDiag.stop() // S87.2
        AdBlock.flushTotal() // S68
        super.onStop()
        MediaPrefs.onStop(this, tabs.active?.web) // 0.0.302: background play keeps the page running after the window is hidden
        BgKeep.onStop(this, tabs.active?.web) // 0.0.366: same for a running download
    }

    override fun onNewIntent(intent: Intent) { // D1: Share while the browser is already open
        super.onNewIntent(intent)
        LinkInbox.fromIntent(this, tabs, intent)
        SocialShortcuts.fromIntent(this, tabs, intent) // 0.0.230 (P6)
        HomeShortcut.fromIntent(this, tabs, intent) // 0.0.298
        SocialLinks.fromIntent(this, tabs, intent) // 0.0.246 (A6)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) { // D1: clipboard can only be read with focus
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) LinkInbox.fromClipboard(this, tabs)
    }

    override fun onResume() {
        super.onResume()
        syncPrivateSecure() // 0.0.330
        if (!privSecure) try { window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE) } catch (e: Exception) { } // 0.0.342: pre-Android 13 pause flag off again
        backCb.isEnabled = true
        if (siteWin || SiteWindow.used) bindWindow() // 0.0.248 (A7b): the window in front owns the shared helpers
        tabs.active?.web?.onResume()
        MediaPrefs.onResume(this, tabs.active?.web) // 0.0.296: background play service ends when Thazir is in front
        floating.start() // S11
    }

    /** 0.0.248 (A7b): shared helpers follow the window in front (only runs when a site window exists in this process). */
    private fun bindWindow() {
        BookmarksScreen.tabsRef = tabs
        BrowserExtras.focus(this) // file chooser, permission asks, dialogs
        DlFly.attach(this, findViewById<View>(R.id.btn_menu)) // download fly animation
        TabsApi.onNativePanel = { floating.openSheet() }
        AppearanceScreen.onChange = { applyHomeIcon(); applyNavArrows(); refreshUrlField(); refreshShield() }
        if (!SiteWindow.mainUp) TabsApi.attach(tabs) // only site windows left: they take the extension bridge
    }

    override fun onPause() {
        floating.stop() // S11
        val dlKeep = BgKeep.onPause(this, tabs.active?.web) // 0.0.366: a running download keeps the page + a service (wake lock) alive
        if (!dlKeep && !(MediaPrefs.bg(this) && MediaPrefs.audible(this))) tabs.active?.web?.onPause() // 0.0.296: Media > Background play: a playing page is not paused
        MediaPrefs.onPause(this)
        // 0.0.342: Android 12 and older: blank Recents picture of private tabs only while the app is away (screenshots stay allowed in the app)
        if (android.os.Build.VERSION.SDK_INT < 33 && privHide && !privSecure) try { window.setFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE, android.view.WindowManager.LayoutParams.FLAG_SECURE) } catch (e: Exception) { }
        if (MediaPrefs.away) tabs.active?.web?.let { w -> w.post { try { w.onResume(); w.resumeTimers() } catch (_: Exception) { } } } // 0.0.302
        if (!siteWin) tabs.saveSession() // 0.0.247 (A7): a site window never overwrites the saved tabs
        super.onPause()
    }

    // 0.0.310: headphones unplugged / Bluetooth gone = pause the pages that play sound
    private var noisyRx: android.content.BroadcastReceiver? = null
    private fun noisyOn() {
        if (noisyRx != null) return
        val rx = object : android.content.BroadcastReceiver() {
            override fun onReceive(c: android.content.Context?, i: Intent?) {
                if (!MediaPrefs.get(this@MainActivity, MediaPrefs.NOISY)) return
                MediaPrefs.away = false // background play must not start the page again
                val js = "try{window.__mgbYm&&(window.__mgbYm.away=0);document.querySelectorAll('video,audio').forEach(function(v){try{v.pause()}catch(e){}})}catch(e){}"
                for (t in tabs.allTabs()) { if (!t.suspended) try { t.web.evaluateJavascript(js, null) } catch (_: Exception) { } }
            }
        }
        noisyRx = rx
        try { androidx.core.content.ContextCompat.registerReceiver(this, rx, android.content.IntentFilter(android.media.AudioManager.ACTION_AUDIO_BECOMING_NOISY), androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED) } catch (_: Exception) { noisyRx = null }
    }

    override fun onDestroy() {
        noisyRx?.let { try { unregisterReceiver(it) } catch (_: Exception) { } }; noisyRx = null // 0.0.310
        if (!siteWin) SiteWindow.mainUp = false else SiteWindow.live-- // 0.0.247 (A7)
        if (!siteWin && isFinishing) { PrivNotify.closer = null; PrivNotify.cancel(this) } // 0.0.302
        if (siteWin && !shared) { // site window while the main window lives: only its own things go
            floating.stop()
            BrowserExtras.release(this) // 0.0.248 (A7b)
            if (BookmarksScreen.tabsRef === tabs) BookmarksScreen.tabsRef = null
            tabs.destroyAll()
            super.onDestroy()
            return
        }
        BrowserExtras.release(this) // 0.0.248 (A7b)
        DlFly.detach() // S21e
        AppearanceScreen.onChange = null // A3
        AppLock.onDestroy() // S45
        AutoDiag.stop() // S87.2
        PlayerScreen.onHostDestroy(this) // S35
        floating.stop() // S11
        DownloadsScreen.closeAll() // S12
        BookmarksScreen.closeAll() // S13
        BookmarksScreen.tabsRef = null // S25
        TabsApi.onNativePanel = null // MGB S51
        FindBar.close() // S21
        tabs.destroyAll()
        TabsApi.attach(null)
        super.onDestroy()
    }

    // uiMode is in configChanges (no WebView reload on rotate); on light/dark switch we
    // recreate so the XML colours re-resolve. Tabs come back from the saved session.
    /** 0.0.294: edge swipe = page back / forward. The pref check is the first line inside EdgeSwipe.handle, so nothing runs when OFF. */
    override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
        if (::tabs.isInitialized && EdgeSwipe.handle(this, tabs, ev) { super.dispatchTouchEvent(it) }) return true
        return super.dispatchTouchEvent(ev)
    }

    /** A3: touchpad two-finger horizontal scroll = back / forward. The pref check comes first, so nothing runs when OFF. */
    override fun dispatchGenericMotionEvent(ev: android.view.MotionEvent): Boolean {
        if (::tabs.isInitialized && AppearancePrefs.touchpad(this) && ZoomTools.onScroll(ev, tabs)) return true
        return super.dispatchGenericMotionEvent(ev)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applyNavArrows(); refreshShield() // A1: narrow / wide rule follows rotation
        // S35 FIX: compare the colours the app really uses (resources, after AppCompat applied the saved theme),
        // not newConfig (= the system theme). With a forced Dark/Light theme the old test was true on EVERY rotation,
        // so rotating recreated the Activity: the player closed and every tab reloaded.
        val n = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        if (n != lastNight) {
            lastNight = n
            recreate()
        }
    }
}
