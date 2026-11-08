package com.jabra.mgb

import android.app.Activity
import android.app.Dialog
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.view.ViewCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * V14/V15: "How to use" guide for new users. Full-screen page: Quick start + 6 basic and 11 advanced topics, each with
 * numbered steps, a tip and real examples (V15). Plain text only (no images), mg_* colours, dark and light, 560dp max width.
 * Entry points: 3-dot menu, Command Center, Settings > About, and a one-time offer on the first start.
 * State lives in this object (openIdx), the Dialog is rebuilt from scratch on every open, so rotation is safe.
 */
object HowToUse {
    private class Topic(val icon: Int, val title: String, val sum: String, val steps: List<String>, val tip: String = "",
        val examples: List<String> = emptyList(), val adv: Boolean = false)

    private val QUICK = listOf(
        "Type a web address or a search in the bar at the top and press Go.",
        "Tap the 3-dot menu for everything else: bookmarks, downloads, Vault, Settings.",
        "Tap the tab counter to switch tabs. Menu > New private tab leaves no history."
    )

    // Examples that start with "CODE:" are shown in a monospace box. Advanced topics (adv = true) come after the basics.
    private val TOPICS = listOf(
        Topic(R.drawable.ic_screen, "Browse and tabs", "Open, switch and close tabs",
            listOf(
                "Type a site or a search in the address bar. Thazir uses the search engine you chose in Settings > General.",
                "Tap the tab counter to see all tabs and groups. Open one by tapping it.",
                "Close a tab with its X. If Settings > Tabs and motion has swipe on, swipe the tab card instead.",
                "Closed one by mistake? Menu > Recently closed tabs brings it back.",
                "Menu > Bookmark all tabs saves every open web tab into one folder."
            ), "Menu > New private tab keeps no history and uses separate cookies.",
            listOf(
                "Type: weather today - it runs a search. Type: example.com - it opens that site.",
                "Planning a trip with 8 tabs open: Menu > Bookmark all tabs, name the folder Trip, Save. Close the tabs and open the folder later."
            )),
        Topic(R.drawable.ic_search, "Menu and Command Center", "Find any action fast",
            listOf(
                "Open the 3-dot menu. The top part is for the app, the lower part is for the page you are on.",
                "Page actions: Find on page, Desktop site, Reader view, Translate page, Text size, Page info, Save page.",
                "Menu > Command Center: type a few letters to find an action, an open tab or a bookmark.",
                "Menu > Customise menu lets you hide rows you never use.",
                "The round icon bar at the bottom of the menu stays in place while the list scrolls. Hold one icon for 3 seconds, tap Edit shortcut and pick what that button does (15 actions, or Reset all 5 buttons). Picking an action that sits in another slot swaps the two. A short tap still runs the action.",
                "Address bar: the shield sits inside the right end of the field, and the small tune icon at the start opens the Site sheet (soft green = https, red open lock = plain http).",
                "Good to know: on a Social site the shield hides to give the address room, and it may come back only after the next page load or tab switch. On a small landscape screen the menu list is short but scrolls. The old green https lock is now the tune icon. Customise menu > Reset to default also resets the 5 buttons."
            ), "Command Center is the fastest way when you do not remember where something is.",
            listOf(
                "In Command Center type: private - New private tab appears. Type: dark - Appearance appears. Type: help - this guide appears.",
                "A mobile page looks cramped? Menu > Desktop site. A long article is hard to read? Menu > Reader view."
            )),
        Topic(R.drawable.ic_bookmark, "Bookmarks and history", "Save pages, find them again",
            listOf(
                "On a page, use the bookmark button in the menu bar to save it. Tap it again to remove it.",
                "Menu > Bookmarks shows your saved pages and folders. Menu > History shows where you have been.",
                "Select rows in the list to open, move or delete many at once.",
                "Settings > Data and backup can clear history, cookies and cache."
            ), "",
            listOf("You found a recipe: tap the bookmark button. Next week: Menu > Bookmarks, tap the recipe.")),
        Topic(R.drawable.ic_download, "Downloads and Grabber", "Get videos, files and pages",
            listOf(
                "When a page has a file or video, Menu > Detected files lists what Thazir found.",
                "Menu > Downloads shows progress, finished files and lets you open, share, rename or delete them.",
                "Menu > Grabber and Menu > Queue help with bigger or many downloads.",
                "Menu > Media Library collects detected, downloaded and saved media in one place.",
                "Novel grabber (in the menu) turns web novels into EPUB, DOCX or TXT."
            ), "Settings > Downloads has quality, folder and speed options. See Download settings in Advanced.",
            listOf(
                "Open a page with a video. Tap the Grabber button on the page, pick a quality, download. Watch it in Menu > Downloads.",
                "A download failed? Menu > Queue shows the failed job. Menu > Report center explains why."
            )),
        Topic(R.drawable.ic_save, "Saved pages", "Read without internet",
            listOf(
                "Open a page, then Menu > Save page. It is stored on your phone.",
                "Menu > Saved pages lists them. Tap one to open it offline.",
                "Use the row menu to share, rename or delete a saved page."
            ), "Private tabs cannot be saved on purpose.",
            listOf("Before a flight: open the articles you want, Menu > Save page on each. In the air: Menu > Saved pages.")),
        Topic(R.drawable.ic_play, "Video and audio player", "Built-in player with gestures",
            listOf(
                "Open a video or audio file from Downloads or the Media Library and it plays in the built-in player.",
                "Double-tap the left or right side to jump back or forward. Hold to speed up.",
                "Slide on the screen for volume and brightness, if gestures are on.",
                "Settings > Player: gestures, speed, default fit, quality and subtitles."
            ), "",
            listOf(
                "Watching a long lecture: hold the screen to speed up while you hold it, release to return to normal.",
                "Want the same speed every time? Settings > Player > Keep speed ON."
            )),
        Topic(R.drawable.ic_shield, "Privacy and security", "Blocking, locking, passwords",
            listOf(
                "Menu > Privacy Guard shows ads and trackers blocked on this page and lets you switch it.",
                "Settings > Privacy and security: app lock, HTTPS only, permissions.",
                "Menu > Passwords stores logins. On a login page use Menu > Fill password.",
                "Menu > Email alias makes a throw-away address for a site."
            ), "App lock asks for your fingerprint or screen lock when you come back to the app.",
            listOf("Share your phone with family: Settings > Privacy and security > App lock ON, Lock after 1 minute."), false),
        Topic(R.drawable.ic_settings, "Settings and look", "Make Thazir yours",
            listOf(
                "Menu > Settings. Tap a card to open it. Use the search box at the top to jump to a setting.",
                "Menu > Settings > Appearance changes theme, address bar and home page.",
                "Reduce motion (Tabs and motion) turns animations off.",
                "Each card has Reset to default. It only resets the settings of that card."
            ), "",
            listOf("Cannot find a setting? Search for: animation - Reduce motion appears. Search for: dark - theme options appear.")),
        Topic(R.drawable.ic_edit, "Accessibility", "Zoom, simplified view, arrows",
            listOf(
                "Menu > Settings > Accessibility (or Appearance > Accessibility) sets the default zoom for every site. The preview shows the size.",
                "Use - and + or drag the slider. Each step is 10 %. Reset to 100 % appears when the size is not 100.",
                "Saved zoom for sites lists the sites that have their own size. Tap one to change it, X to follow the default again.",
                "Show Text size in main menu turns the page-action row on or off.",
                "Appearance > Customize address bar > Navigation arrows adds Back and Forward inside the bar.",
                "Force enable zoom lets you pinch zoom on sites that block it. Reload the page. Site settings > Force zoom turns it off for one site.",
                "Simplified view for web pages shows a small pill on article pages. Tap it for Reader view. Press and hold it to hide it on that site.",
                "Swipe between pages using a touchpad: two fingers sideways = Back or Forward (needs a touchpad that sends horizontal scroll).",
                "Captions opens the Android caption style (size, colour, background)."
            ), "",
            listOf(
                "Text is small everywhere: set the default zoom to 130 %. A site you saved at 100 % stays at 100 %.",
                "You use Back often: switch on the Back arrow, and Hide when not available to keep the bar tidy.",
                "A shop page will not zoom: switch on Force enable zoom, reload, then pinch."
            )),
        Topic(R.drawable.ic_code, "Userscripts", "Small scripts that run on a site",
            listOf(
                "Open Settings > Extensions > Userscripts (or Menu > Userscripts on a page).",
                "Tap Add (paste code) and paste a script. It must start with a header between ==UserScript== lines.",
                "The header needs an @match (or @include) line. Without it the script would never run and Thazir tells you so.",
                "Use @exclude to skip pages, @run-at to choose when it runs, @noframes to skip embedded frames.",
                "Settings > Extensions has Run userscripts (master switch) and Userscripts in private tabs (separate switch)."
            ), "Only paste scripts you trust. A script can read and change the pages it matches.",
            listOf(
                "CODE:// ==UserScript==\n// @name  Dark example\n// @match https://example.com/*\n// ==/UserScript==\ndocument.body.style.background = '#111';",
                "@match https://example.com/* runs on every page of example.com. @match *://*/* would run on every site - avoid that unless you mean it."
            ), true),
        Topic(R.drawable.ic_shield, "Ad blocker and filters", "Fine control over blocking",
            listOf(
                "Settings > Privacy and security > Ad blocker turns blocking on or off.",
                "Hosts and filters shows how many rules are loaded.",
                "Hide ad spaces on pages removes the empty boxes ads leave behind. Hide cookie banners removes consent pop-ups.",
                "Sites with ads allowed is your allow list for sites that break or that you want to support.",
                "On any page, Menu > Privacy Guard shows what was blocked there."
            ), "If a page looks broken, try allowing that one site before turning the blocker off for everything.",
            listOf(
                "A shop will not load its pay button: open Privacy Guard on that page, or add the shop in Sites with ads allowed, then reload.",
                "A news site keeps a blank gap where an ad was: turn on Hide ad spaces on pages."
            ), true),
        Topic(R.drawable.ic_download, "Download settings", "Quality, folder and speed",
            listOf(
                "Downloads: with two or more running downloads, Pause all, Resume all and Stop all appear above the list (they also stop the joining of video and audio and queued downloads). A failed download, for example with an expired link, has a New link button that looks for a fresh link in its episode or source page. ",
                "Audio only: in Files detected tap Download on a stream and choose Audio only, or switch on Settings > Downloads > Audio only downloads (also for batches). It works when the stream has a separate audio track. For a saved video, Downloads > file menu > Extract audio (.m4a) copies its AAC audio (no MP3, no re-encoding). ",
                "Social sites (Settings > General, OFF by default; the page lists every site with its own switch and an Open button): Facebook, Messenger chat and Instagram open in a normal tab without Desktop site. Chat pages get a phone-fitting desktop layout, the rest a mobile one, and links to the Facebook / Instagram apps stay in the browser. Unread numbers show on tab cards and a Data saver switch stops video autoplay. Also Reddit, X, Pinterest, Threads, Bluesky, TikTok, LinkedIn, Tumblr (mobile page, app links and Open-app bars blocked) and Discord, WhatsApp Web, Telegram Web, Snapchat Web (phone-fitting desktop layout; Discord voice may not work; WhatsApp Web needs the phone app to link). Google login is often blocked in a browser tab: use email and password. Quick start: Command Center > type the site name. Social sites page > Sensitive media: blur pictures and videos until you tap them (Flagged only = the site's own NSFW / sensitive tag, All = everything), with a Reveal all button and a blur strength slider. Social sites page > Home: pin a site to the phone home screen (tap = opens it in a Social tab) or add tiles to the browser Home page. Social sites page > Logins: keep each social site's login separate from normal tabs (new tabs only, needs a recent WebView). Share Facebook and Messenger login (OFF by default) makes both use one login. Social sites page > Page layout: switch a site between Auto, Phone page and Desktop fit when it looks wrong. Why does a site look wrong? shows the settings of every site and can copy them. Forget logins removes the saved login of one site or of all sites. Social sites page > Keep social tabs alive (OFF by default): up to 4 social tabs are not freed by Suspend old tabs, so they come back without a reload (uses more memory). Social sites page > Home > Long-press shortcuts (OFF by default): long-press the Thazir icon to open up to 4 social sites. Social sites page > Calmer touch (OFF by default): no tap flash and no scroll chaining on social pages. Social sites page > Full screen on social sites (OFF by default): on a social page the address bar and phone bars hide; tap the small handle or press Back to show them. Social sites page > Skip \"Use the app\" screens (ON by default): a full-screen \"Use the app / Chats on mobile browsers are not available\" layer is dismissed by itself with its \"Staying in the browser\" / \"Not now\" button. Match phone bars to the site (ON by default): on a social page the status bar and navigation bar take the colour of the site. Ask before closing a social tab (ON by default): on the first page of a social site the first Back only shows a hint, the second Back closes the tab. Splash when opening from a shortcut (ON by default): a home-screen shortcut shows the site letter and name for a moment while the page loads. Chat comfort: Chat size (100 / 125 / 150 / 175 %) makes chat sites bigger on the phone, and Keep message box above keyboard scrolls the box you tap into view. Bigger buttons (OFF by default, experimental): Reddit, X and LinkedIn action buttons get a larger tap area. Social sites page > Site fix: if a site shows an Open-app bar or an element again after the site changed, type the button text and / or a line of CSS for that site (no new app version needed; applies from the next page load; Clear removes it). Moving inside a page (for example Facebook feed to chat) now re-checks the browser identity and the page scripts by itself. Open social links from other apps (OFF by default): a link to a social site sent from another app can open in a Social tab. Turn it ON, then press Choose links Thazir opens and allow the sites in Android settings (Android decides, Thazir cannot force it). Own window per site (OFF by default, experimental): the home-screen shortcut and the Open button start each site in its own window, so Recent apps shows one card per site. That window shows only the site and is not saved with your other tabs; uploads, camera and permission questions work in each window. Extra site fixes (ON by default): on Instagram, Reddit, X and LinkedIn more app prompts (Not now bars) and sign-up layers are hidden; switch it OFF if a page looks wrong. Warm up when opening a site (ON by default): the site address is looked up a moment earlier, a small speed gain only. Clean copied and shared links (OFF by default): when you copy or share a link, tracking parts such as fbclid, utm_ and igsh are removed from the copied text. Lock social tabs (OFF by default): a social tab asks for fingerprint, face or screen lock when you come back after the chosen time (Immediately, 1 or 5 minutes); it needs a screen lock on the phone and is not used while App lock is ON. Time limit for social sites (Off by default): choose 15 / 30 / 60 / 120 minutes per day; the time is counted while a social tab is in front, and a reminder shows at the limit (Keep going or Leave app), nothing is closed. App-like error screen (OFF by default): on a social site with no connection a No connection page with a Try again button shows instead of the system error page. Focus mode (OFF by default): on Facebook, Instagram, Reddit, X, Pinterest and LinkedIn Reels, promoted posts and suggestion boxes are hidden; site changes can break a rule, Site fix can add more. Data saver plus (OFF by default): on mobile data, video files are not downloaded on social sites (Wi-Fi is not touched). Social dock (OFF by default): on a social tab a slim row above the bottom bar shows one letter icon per site; tap goes to that site's tab or opens it; a red number is the unread count. Ask before leaving a social site (OFF by default): a tapped link to another site shows a sheet with Open in new tab / Open here / Cancel. Video in a small window (OFF by default, Android 8+): while a social video is full screen, Home puts it in a floating picture-in-picture window. Site fixes: copy / paste (Extras) moves your Site fixes to another phone as text. Blur per site: on the blur card each site can follow the general choice or have its own (Off / Flagged only / All until tap). The row Why does a site look wrong? now shows per site how often the page scripts started, and the button Check open pages asks the open pages which scripts ran (read-only; Copy it for a bug report). Account per site (Privacy > Logins, needs separate logins): a site can use Account 1 or Account 2 for new tabs, so two accounts can be open side by side; each logs in once. Dark page per site (Extras): each site can follow the app setting Dark pages or be forced Dark / Original (needs a dark app theme). With Calmer touch ON, tapping the dock icon of the site you are on scrolls the page to the top. Muted words: posts with your words are hidden on Facebook, Instagram, Reddit, X and LinkedIn; the words stay on the phone. No background notifications in a browser. ",
                "Link inbox (Settings > Downloads, OFF by default): share a video link to MGB, or copy one and come back, and a card asks Download this link? Open loads it in a new tab where the Files detected panel finds the video. ",
                "Download rules (Settings > Downloads) hold a new download until you are on Wi-Fi, off metered data or charged, or stop it when storage is low. Batch: folder and number saves a batch as Show/01 - title. ",
                "Settings > Downloads > Download quality sets the best video height you want by default, up to 8K. Preferred video format picks MP4 or WebM when a stream offers both (no conversion).",
                "Subtitles and file names: choose subtitle languages and the format, for example en and SRT. Subtitles now keep their real name (Title.en.srt, no .txt added). The row Fix old subtitle names renames old Title.en.srt.txt files, and the player also finds those old files next to a video.",
                "Convert .ts to .mp4 turns streaming pieces into a normal video file when a download finishes.",
                "Download folder lets you pick a folder (for example on an SD card). Sub-folders keeps files in sub-folders instead of one long list.",
                "Download engine (Settings > Grabber and buttons > Engine) sets how many downloads run at a time, retries, speed limits and live recording."
            ), "Changes apply to new downloads. Files already saved are not changed.",
            listOf(
                "Shared Wi-Fi at home: Settings > Grabber and buttons > Download engine > 1 at a time, speed limit 500 KB/s.",
                "Small phone storage: Download folder > pick a folder on the SD card.",
                "Lecture videos with captions: Subtitles and file names > languages: en, format: SRT."
            ), true),
        Topic(R.drawable.ic_history, "Scheduled downloads and Followed manga", "Let Thazir do it later",
            listOf(
                "Scheduled downloads (Settings > Downloads): pick a job and a time. Thazir starts it at that time.",
                "Live streams scheduled this way are recorded until the Stop recording after limit set in Download engine (Settings > Grabber and buttons).",
                "Followed manga: follow a series page and Thazir checks it for new chapters on its own, every few hours.",
                "Open Followed manga to see unread chapters and check now."
            ), "Scheduled jobs need the phone to be on at that time.",
            listOf(
                "A 3 GB movie: schedule it for 03:00 so it does not use your daytime data or Wi-Fi.",
                "A weekly manga: Follow its page. Tomorrow open Followed manga and read what is unread."
            ), true),
        Topic(R.drawable.ic_list, "Site settings and permissions", "Rules for one website",
            listOf(
                "Settings > Privacy and security > Site permissions lists sites that have asked for camera, microphone or location, with your answer.",
                "Site settings keeps choices per site: switches, text size, download buttons and permissions.",
                "Open a site in the list to change or reset only that site."
            ), "",
            listOf(
                "A video-call site keeps asking for the camera: open it in Site permissions and set it to Allow.",
                "One blog is too small to read: Site settings > that site > Text size larger. Other sites stay as they are."
            ), true),
        Topic(R.drawable.ic_lock, "Passwords and email alias", "Logins without repeating yourself",
            listOf(
                "Menu > Passwords stores logins on this phone. Use Import passwords (file or paste) to bring them from another app.",
                "On a login page: Menu > Fill password.",
                "Password generator makes strong passwords. Export passwords (CSV) makes a plain file - keep it private.",
                "Email alias: add your addy.io API key in Settings > Passwords and email, then Create email alias."
            ), "A CSV export is not encrypted. Delete it after you use it.",
            listOf(
                "Signing up on a new shop: Menu > Email alias > create. Use that address. Your real email stays private.",
                "Moving from a browser that exports CSV: Settings > Passwords and email > Import passwords > choose the file."
            ), true),
        Topic(R.drawable.ic_vault, "Vault", "Encrypted copy of your data",
            listOf(
                "Menu > Vault > Create. Pick a folder you can find again (make a new one, for example ThazirVault).",
                "Choose a passphrase. Write down the recovery key shown once and keep it safe.",
                "Use Move to vault or Copy to vault in Bookmarks, History, Saved pages and Downloads.",
                "Before you uninstall: Vault > Exit vault / Prepare for uninstall, then choose what to keep.",
                "On a new install: Vault > I already have a vault, pick the same folder and unlock."
            ), "Android deletes app data when you uninstall. Your vault stays only because it lives in your own folder.",
            listOf(
                "Bookmarks > select rows > More > Move to vault. They leave the browser list and appear in Vault > Bookmarks.",
                "Hide private bookmarks: in Vault > Bookmarks press and hold one, tap Hide. Or pick a folder chip and tap Hide folder. They disappear from the list until you tap Show hidden.",
                "Select many: press and hold a bookmark, then tap Folders... and tick folders one by one (or All folders). Tap Hide: choose only the selected bookmarks, or the bookmarks with their folders.",
                "Changing phone: before you leave, Vault > Prepare for uninstall > Keep all. On the new phone install Thazir, Vault > I already have a vault."
            ), true),
        Topic(R.drawable.ic_save, "Backup and restore", "Move to a new phone safely",
            listOf(
                "Settings > Data and backup > Export data: tick what to include (bookmarks, history, settings and more) and save the file.",
                "Import data on the new phone and choose that file.",
                "Auto backup (daily) keeps a fresh copy for you.",
                "The Vault is not in this file. It stays in its own folder."
            ), "Keep the backup file somewhere safe. It holds your browsing data.",
            listOf("New phone: old phone Export data > send the file to yourself. New phone Import data. Then unlock the Vault from the same folder."), true),
        Topic(R.drawable.ic_ext, "Extensions and extras", "Add more features",
            listOf(
                "Open the 3-dot menu and tap the extensions icon to open the Extensions tray.",
                "Settings > Extensions: add, turn on or off and remove them.",
                "Menu > Report center helps you collect details when something is wrong, so you can copy or share them."
            ), "",
            listOf("A page misbehaves: Menu > Report center > copy the report and send it to the developer.")
            , true)
    )

    private var current: Screen? = null
    private var openIdx = -1

    fun open(host: Activity, topic: Int = -1) {
        if (host.isFinishing || host.isDestroyed) return
        try { current?.dismiss() } catch (_: Exception) { }
        openIdx = if (topic in TOPICS.indices) topic else -1
        try {
            val s = Screen(host)
            current = s
            s.setOnDismissListener { if (current === s) current = null }
            s.show()
        } catch (e: Exception) { current = null }
    }

    /** One-time offer on the very first start. Safe to call every start. */
    fun maybeOffer(act: Activity) {
        if (act.isFinishing || act.isDestroyed) return
        val p = act.getSharedPreferences("mgb_ui", android.content.Context.MODE_PRIVATE)
        if (p.getBoolean("howto_seen", false)) return
        p.edit().putBoolean("howto_seen", true).apply()
        try {
            MaterialAlertDialogBuilder(act)
                .setTitle("New to Thazir?")
                .setMessage("Take a one-minute tour of tabs, downloads, privacy and the Vault. You can find it later in Menu > How to use.")
                .setPositiveButton("Show me") { _, _ -> open(act) }
                .setNegativeButton("Not now", null)
                .show()
        } catch (_: Exception) { }
    }

    private class Screen(private val act: Activity) :
        Dialog(act, com.google.android.material.R.style.Theme_Material3_DayNight_NoActionBar) {

        private val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        private val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
        private var content: LinearLayout? = null
        private var titleTv: TextView? = null
        private var sv: ScrollView? = null

        private fun dp(v: Int) = Util.dp(act, v)
        private fun c(res: Int) = act.getColor(res)

        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)
            build()
            window?.let {
                it.setLayout(MATCH, MATCH)
                it.setBackgroundDrawable(ColorDrawable(c(R.color.mg_bg)))
                if (!TabPrefs.reduce(act)) it.setWindowAnimations(R.style.MgbSettingsWindow)
            }
            render(false)
        }

        override fun onBackPressed() { goBack() }

        private fun goBack() {
            if (openIdx >= 0) { openIdx = -1; render(true) } else dismiss()
        }

        private fun build() {
            val root = FrameLayout(act).apply { setBackgroundColor(c(R.color.mg_bg)) }
            val col = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
            val w = minOf(act.resources.displayMetrics.widthPixels, dp(560))
            val bar = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            val back = ImageView(act).apply {
                setImageResource(R.drawable.ic_back)
                setColorFilter(c(R.color.mg_text))
                contentDescription = "Back"
                setPadding(dp(12), dp(12), dp(12), dp(12))
                isClickable = true; isFocusable = true
                setOnClickListener { goBack() }
            }
            val t = VaultScreen.tv(act, "How to use", 20f, R.color.mg_text, true).also { ViewCompat.setAccessibilityHeading(it, true) }
            titleTv = t
            bar.addView(back, LinearLayout.LayoutParams(dp(48), dp(48)))
            bar.addView(t, LinearLayout.LayoutParams(0, WRAP, 1f))
            col.addView(bar, LinearLayout.LayoutParams(MATCH, dp(56)))
            val scroll = ScrollView(act).apply { isFillViewport = true }
            sv = scroll
            val body = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(8), dp(18), dp(28)) }
            content = body
            scroll.addView(body, FrameLayout.LayoutParams(MATCH, WRAP))
            col.addView(scroll, LinearLayout.LayoutParams(MATCH, 0, 1f))
            root.addView(col, FrameLayout.LayoutParams(w, MATCH, Gravity.CENTER_HORIZONTAL))
            setContentView(root)
        }

        private fun render(animate: Boolean) {
            val body = content ?: return
            body.removeAllViews()
            if (openIdx < 0) home(body) else topic(body, openIdx)
            titleTv?.text = if (openIdx < 0) "How to use" else TOPICS[openIdx].title
            sv?.scrollTo(0, 0)
            if (animate && !TabPrefs.reduce(act)) {
                body.animate().cancel()
                body.alpha = 0f
                body.translationX = dp(if (openIdx >= 0) 28 else -28).toFloat()
                body.animate().alpha(1f).translationX(0f).setDuration(240)
                    .setInterpolator(android.view.animation.PathInterpolator(0.2f, 0f, 0f, 1f)).start()
            }
        }

        private fun dash(): View = View(act).apply {
            background = GradientDrawable().apply { setColor(c(R.color.mg_primary)); cornerRadius = dp(2).toFloat() }
        }

        private fun header(text: String) = VaultScreen.tv(act, text.uppercase(), 11f, R.color.mg_primary, true).apply {
            typeface = Typeface.DEFAULT_BOLD; letterSpacing = 0.08f
            ViewCompat.setAccessibilityHeading(this, true)
        }

        private fun num(n: Int): View = TextView(act).apply {
            text = n.toString(); gravity = Gravity.CENTER
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(c(R.color.mg_on_primary)); typeface = Typeface.DEFAULT_BOLD
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(c(R.color.mg_primary)) }
        }

        private fun stepRow(n: Int, text: String): View = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(num(n), LinearLayout.LayoutParams(dp(26), dp(26)).apply { topMargin = dp(1) })
            addView(VaultScreen.tv(act, text, 15f, R.color.mg_text).apply { setLineSpacing(0f, 1.15f) },
                LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginStart = dp(12) })
        }

        private fun home(b: LinearLayout) {
            b.addView(dash(), VaultScreen.lp(dp(4), dp(18), dp(3)))
            b.addView(VaultScreen.tv(act, "Welcome to Thazir", 24f, R.color.mg_text, true), VaultScreen.lp(dp(10)))
            b.addView(VaultScreen.tv(act, "Short steps for the main things. Start with the quick start, then open any topic.", 14f, R.color.mg_text_dim)
                .apply { setLineSpacing(0f, 1.15f) }, VaultScreen.lp(dp(8)))
            b.addView(header("Quick start"), VaultScreen.lp(dp(22)))
            val card = LinearLayout(act).apply {
                orientation = LinearLayout.VERTICAL
                background = VaultScreen.box(act, 20)
                setPadding(dp(16), dp(16), dp(16), dp(16))
            }
            QUICK.forEachIndexed { i, s -> card.addView(stepRow(i + 1, s), VaultScreen.lp(if (i == 0) 0 else dp(12))) }
            b.addView(card, VaultScreen.lp(dp(10)))
            b.addView(header("Basics"), VaultScreen.lp(dp(24)))
            TOPICS.forEachIndexed { i, t -> if (!t.adv) b.addView(topicRow(i, t), VaultScreen.lp(dp(10))) }
            b.addView(header("Advanced features, with examples"), VaultScreen.lp(dp(24)))
            TOPICS.forEachIndexed { i, t -> if (t.adv) b.addView(topicRow(i, t), VaultScreen.lp(dp(10))) }
        }

        private fun topicRow(i: Int, t: Topic): View = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(64)
            background = VaultScreen.box(act, 20)
            setPadding(dp(16), dp(10), dp(12), dp(10))
            isClickable = true; isFocusable = true
            contentDescription = t.title + ". " + t.sum
            setOnClickListener { openIdx = i; render(true) }
            addView(ImageView(act).apply { setImageResource(t.icon); setColorFilter(c(R.color.mg_primary)); importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO },
                LinearLayout.LayoutParams(dp(24), dp(24)))
            val txt = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
            txt.addView(VaultScreen.tv(act, t.title, 16f, R.color.mg_text, true))
            txt.addView(VaultScreen.tv(act, t.sum, 12f, R.color.mg_text_dim), VaultScreen.lp(dp(2)))
            addView(txt, LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginStart = dp(14) })
            addView(ImageView(act).apply { setImageResource(R.drawable.ic_chevron); setColorFilter(c(R.color.mg_text_dim)); importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO },
                LinearLayout.LayoutParams(dp(20), dp(20)))
        }

        private fun topic(b: LinearLayout, i: Int) {
            val t = TOPICS[i]
            b.addView(dash(), VaultScreen.lp(dp(4), dp(18), dp(3)))
            b.addView(VaultScreen.tv(act, t.sum, 14f, R.color.mg_text_dim), VaultScreen.lp(dp(10)))
            val card = LinearLayout(act).apply {
                orientation = LinearLayout.VERTICAL
                background = VaultScreen.box(act, 20)
                setPadding(dp(16), dp(16), dp(16), dp(16))
            }
            t.steps.forEachIndexed { k, s -> card.addView(stepRow(k + 1, s), VaultScreen.lp(if (k == 0) 0 else dp(14))) }
            b.addView(card, VaultScreen.lp(dp(14)))
            if (t.tip.isNotEmpty()) {
                val tip = LinearLayout(act).apply {
                    orientation = LinearLayout.VERTICAL
                    background = VaultScreen.box(act, 16, R.color.mg_surface_variant)
                    setPadding(dp(16), dp(12), dp(16), dp(14))
                }
                tip.addView(header("Tip"))
                tip.addView(VaultScreen.tv(act, t.tip, 14f, R.color.mg_text).apply { setLineSpacing(0f, 1.15f) }, VaultScreen.lp(dp(6)))
                b.addView(tip, VaultScreen.lp(dp(12)))
            }
            if (t.examples.isNotEmpty()) {
                b.addView(header(if (t.examples.size == 1) "Example" else "Examples"), VaultScreen.lp(dp(22)))
                for (e in t.examples) {
                    val code = e.startsWith("CODE:")
                    val ex = LinearLayout(act).apply {
                        orientation = LinearLayout.VERTICAL
                        background = VaultScreen.box(act, 16, R.color.mg_surface_variant)
                        setPadding(dp(14), dp(12), dp(14), dp(12))
                    }
                    ex.addView(VaultScreen.tv(act, if (code) e.removePrefix("CODE:") else e, if (code) 12.5f else 14f, R.color.mg_text).apply {
                        if (code) { typeface = Typeface.MONOSPACE; setTextIsSelectable(true) } else setLineSpacing(0f, 1.15f)
                    })
                    b.addView(ex, VaultScreen.lp(dp(10)))
                }
            }
            val nav = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL }
            if (i > 0) nav.addView(VaultScreen.btn(act, "Previous", false) { openIdx = i - 1; render(true) },
                LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginEnd = dp(10) })
            val last = i == TOPICS.size - 1
            nav.addView(VaultScreen.btn(act, if (last) "All topics" else "Next: " + TOPICS[i + 1].title, true) {
                if (last) { openIdx = -1; render(true) } else { openIdx = i + 1; render(true) }
            }, LinearLayout.LayoutParams(0, dp(48), 1f))
            b.addView(nav, VaultScreen.lp(dp(20)))
            b.addView(VaultScreen.link(act, "All topics") { openIdx = -1; render(true) }, VaultScreen.lp(dp(6)))
        }
    }
}
