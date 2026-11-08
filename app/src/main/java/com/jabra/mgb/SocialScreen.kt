package com.jabra.mgb

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.text.InputType
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.content.res.ColorStateList
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * SOCIAL SITES PAGE (0.0.228, P4): full-screen page (same chrome as Appearance): master switch (SocialMode.on), one switch row per
 * site of SocialProfiles.all (key sm_s_<id>) with an "Open" pill, and a short "what does not work" text.
 * 0.0.236 (U2): card "Site fix" = per site extra banner texts + CSS (SocialProfiles.setFix, key sm_x_<id>) in a small edit dialog.
 * Entry: Settings > General > Social sites. Back = Settings again. State lives in the prefs, so a rotation just rebuilds the page.
 */
object SocialScreen {
    private const val TAG = "MGB.social"
    private var current: Screen? = null
    private var lastTab = 0 // 0.0.262 (B4): 0 Sites, 1 Privacy, 2 Extras (kept while the page is rebuilt, e.g. rotation)

    fun open(host: Activity, onClose: (() -> Unit)? = null) {
        if (host.isFinishing || host.isDestroyed) return
        closeAll()
        try {
            val s = Screen(host, onClose)
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

    /** LinearLayout that never gets wider than [cap] px (560 dp cap on tablets / landscape). */
    private class CapColumn(ctx: Context, private val cap: Int) : LinearLayout(ctx) {
        override fun onMeasure(w: Int, h: Int) {
            val size = MeasureSpec.getSize(w)
            super.onMeasure(if (size > cap) MeasureSpec.makeMeasureSpec(cap, MeasureSpec.EXACTLY) else w, h)
        }
    }

    private class Screen(private val act: Activity, private val onClose: (() -> Unit)?) :
        Dialog(act, com.google.android.material.R.style.Theme_Material3_DayNight_NoActionBar) {

        private val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        private val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
        private val siteRows = ArrayList<View>()
        private var skipClose = false

        private fun dp(v: Int) = Util.dp(act, v)

        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)
            build()
            window?.let {
                it.setLayout(MATCH, MATCH)
                it.setBackgroundDrawable(ColorDrawable(act.getColor(R.color.mg_bg)))
            }
            setOnDismissListener {
                if (current === this) current = null
                if (!skipClose) try { onClose?.invoke() } catch (_: Exception) { }
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
                setOnClickListener { dismiss() }
            }, LinearLayout.LayoutParams(dp(44), dp(48)))
            head.addView(TextView(act).apply {
                text = "Social sites"
                textSize = 22f
                gravity = Gravity.CENTER_VERTICAL or Gravity.START
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                setTextColor(act.getColor(R.color.mg_text))
                setPadding(dp(6), 0, 0, 0)
            }, LinearLayout.LayoutParams(0, WRAP, 1f))
            root.addView(head, LinearLayout.LayoutParams(MATCH, dp(56)))

            val col = CapColumn(act, dp(560)).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16), 0, dp(16), dp(32))
            }
            root.addView(ScrollView(act).apply {
                isFillViewport = false
                addView(col, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.CENTER_HORIZONTAL))
            }, LinearLayout.LayoutParams(MATCH, 0, 1f))
            setContentView(root)
            fill(col)
        }

        override fun onBackPressed() { dismiss() }

        private fun fill(col: LinearLayout) {
            siteRows.clear()
            col.removeAllViews()
            val marks = ArrayList<Pair<Int, Int>>() // 0.0.262 (B4): (child index where a group starts, group: -1 always shown, 0 Sites, 1 Privacy, 2 Extras)
            fun grp(g: Int) { marks.add(col.childCount to g) }
            grp(-1)

            col.addView(sectionLabel("Master switch"))
            val master = card()
            switchRow(master, R.drawable.ic_desktop, "Social sites", "Mobile or phone-fit page, app links and Open-app bars blocked",
                null, { SocialMode.on(act) }) { on -> SocialMode.setOn(act, on); applyDim() }
            col.addView(master, LinearLayout.LayoutParams(MATCH, WRAP))

            grp(1)
            col.addView(sectionLabel("Sensitive media"))
            val blur = blurCard()
            col.addView(blur, LinearLayout.LayoutParams(MATCH, WRAP))
            siteRows.add(blur)

            // 0.0.337: the AI picture check card was removed (heavy app, SocialNsfw is a no-op now)

            grp(2)
            col.addView(sectionLabel("Tabs and data")) // 0.0.231 (P7)
            val extra = card()
            switchRow(extra, R.drawable.ic_info, "Unread number on tab cards", "Shows the \"(3)\" of the page title in the tab list",
                null, { SocialBadge.badgeOn(act) }) { on -> SocialBadge.setBadgeOn(act, on) }
            switchRow(extra, R.drawable.ic_shield, "Data saver", "Stops video autoplay on social pages (applies from the next page load)",
                null, { SocialBadge.saveOn(act) }) { on -> SocialBadge.setSaveOn(act, on) }
            switchRow(extra, R.drawable.ic_shield, "Skip \"Use the app\" screens", "Taps \"Staying in the browser\" or \"Not now\" on a full-screen app nag (applies from the next page load)",
                null, { SocialMode.intOn(act) }) { on -> SocialMode.setIntOn(act, on) }
            switchRow(extra, R.drawable.ic_screen, "Full screen on social sites", "Hides the address bar and phone bars on social pages. Tap the small handle or press Back to show them",
                null, { SocialMode.fullOn(act) }) { on -> SocialMode.setFullOn(act, on); if (!on) SocialFull.sync(act, BookmarksScreen.tabsRef?.active) }
            switchRow(extra, R.drawable.ic_screen, "Match phone bars to the site", "Status bar and navigation bar take the colour of the social site (back to normal on other pages)",
                null, { SocialMode.barsOn(act) }) { on -> SocialMode.setBarsOn(act, on); SocialBars.sync(act, BookmarksScreen.tabsRef?.active) }
            switchRow(extra, R.drawable.ic_shield, "Ask before closing a social tab", "On the first page of a social site, Back shows \"Press Back again to close\" so one slip does not close your feed",
                null, { SocialMode.backOn(act) }) { on -> SocialMode.setBackOn(act, on) }
            switchRow(extra, R.drawable.ic_screen, "Bigger buttons (Reddit, X, LinkedIn)", "Makes the small like / reply / vote buttons at least 44 px so they are easy to hit (experimental, applies from the next page load)",
                null, { SocialMode.bigOn(act) }) { on -> SocialMode.setBigOn(act, on) }
            switchRow(extra, R.drawable.ic_screen, "Splash when opening from a shortcut", "Shows the site letter and name for a moment when a home-screen shortcut opens it (off with Reduce motion)",
                null, { SocialMode.splashOn(act) }) { on -> SocialMode.setSplashOn(act, on) }
            switchRow(extra, R.drawable.ic_open, "Open social links from other apps", "A link to a social site sent from another app opens in a Social tab. Also use the button below, Android decides who opens a link",
                null, { SocialLinks.on(act) }) { on -> SocialLinks.setOn(act, on) }
            actionRow(extra, R.drawable.ic_open, "Choose links Thazir opens (Android)", "Opens Android settings: Open by default, then allow the social sites") { SocialLinks.openAndroidSettings(act) }
            switchRow(extra, R.drawable.ic_screen, "Own window per site (experimental)", "Shortcut and Open button start each site in its own window, so Recent apps shows one card per site",
                null, { SiteWindow.on(act) }) { on -> SiteWindow.setOn(act, on) }
            switchRow(extra, R.drawable.ic_shield, "Extra site fixes (Instagram, Reddit, X, LinkedIn)", "Hides more app prompts and sign-up layers on these sites (applies from the next page load; Site fix can add more)",
                null, { SocialMode.dfixOn(act) }) { on -> SocialMode.setDfixOn(act, on) }
            switchRow(extra, R.drawable.ic_info, "Warm up when opening a site", "Looks up the site address a moment earlier so the first page starts a little faster. Nothing is sent to the site",
                null, { SocialPreload.on(act) }) { on -> SocialPreload.setOn(act, on) }
            switchRow(extra, R.drawable.ic_info, "Calmer touch on social pages", "No grey flash when you tap, and a page does not pull the whole screen when you scroll past its end. Applies from the next page load",
                null, { SocialMode.touchOn(act) }) { on -> SocialMode.setTouchOn(act, on) }
            switchRow(extra, R.drawable.ic_info, "Instagram Reels viewer", "Tapping a reel opens the full screen reels viewer (swipe up for the next reel) like the app, not the single Post page. Pull-to-reload is also off on reel pages",
                null, { SocialReels.on(act) }) { on -> SocialReels.setOn(act, on) }
            switchRow(extra, R.drawable.ic_open, "Share button opens the share sheet", "Share buttons on social sites open the Android share sheet (WhatsApp, Telegram ...) like the app, not only Copy link. Link cleaner applies",
                null, { SocialApp.on(act, SocialApp.SHARE) }) { on -> SocialApp.set(act, SocialApp.SHARE, on) } // 0.0.305
            switchRow(extra, R.drawable.ic_screen, "Keep screen on while a video plays", "The screen does not dim while a reel / video plays on a social page. Normal again when it pauses",
                null, { SocialApp.on(act, SocialApp.AWAKE) }) { on -> SocialApp.set(act, SocialApp.AWAKE, on) }
            switchRow(extra, R.drawable.ic_info, "Light vibration on Like / Save", "A small tick when you like, save or follow, or double-tap a picture, like the app",
                null, { SocialApp.on(act, SocialApp.HAPTIC) }) { on -> SocialApp.set(act, SocialApp.HAPTIC, on) }
            switchRow(extra, R.drawable.ic_screen, "Full screen on reels", "Address bar and phone bars hide while a reel page is open, like the app. Tap the small handle or press Back to show them",
                null, { SocialReels.fullOn(act) }) { on -> SocialReels.setFullOn(act, on) } // 0.0.309
            switchRow(extra, R.drawable.ic_info, "Reels with sound", "Reels / videos play with sound like the app instead of starting muted. If you mute one by hand, it stays muted until you unmute",
                null, { SocialApp.on(act, SocialApp.SOUND) }) { on -> SocialApp.set(act, SocialApp.SOUND, on) } // 0.0.306
            switchRow(extra, R.drawable.ic_info, "Auto next reel (experimental)", "When a reel ends, the viewer moves to the next reel by itself. Off by default; a site change can stop it working",
                null, { SocialApp.on(act, SocialApp.NEXT) }) { on -> SocialApp.set(act, SocialApp.NEXT, on) }
            switchRow(extra, R.drawable.ic_screen, "Pinch to zoom pictures", "Lets you pinch-zoom on social pages that lock zoom (not on chats). Applies from the next page load",
                null, { SocialApp.on(act, SocialApp.ZOOM) }) { on -> SocialApp.set(act, SocialApp.ZOOM, on) }
            switchRow(extra, R.drawable.ic_info, "Home icon scrolls to top", "Tapping the Home icon of a social site while you are already on its home page scrolls back to the top, like the app",
                null, { SocialApp.on(act, SocialApp.TOP) }) { on -> SocialApp.set(act, SocialApp.TOP, on) } // 0.0.308
            lateinit var rsTv: TextView // 0.0.307: refresh the feed when you come back
            fun rsHint() = (if (SocialQuick.resumeOn(act)) "ON, after " + SocialQuick.resumeMin(act) + " min away" else "OFF") + " (tap to change). Reloads the home feed of a social site when you return after a while, like the app. Never a chat, reel or post"
            val rsRow = actionRow(extra, R.drawable.ic_reload, "Refresh feed when you come back", rsHint()) {
                if (!SocialQuick.resumeOn(act)) SocialQuick.setResumeOn(act, true)
                else if (SocialQuick.resumeMin(act) == SocialQuick.mins.last()) { SocialQuick.setResumeOn(act, false); SocialQuick.cycleMin(act) }
                else SocialQuick.cycleMin(act)
                rsTv.text = rsHint()
            }
            rsTv = ((rsRow as LinearLayout).getChildAt(1) as LinearLayout).getChildAt(1) as TextView
            lateinit var plTv: TextView // 0.0.304: pull-to-refresh in chats
            fun plHint() = SocialPull.modeNames[SocialPull.mode(act)] + " (tap to change). Pull down to reload a page: Auto turns it off in Messenger / Instagram / other chats and in reels so scrolling to older messages does not reload. Each site can also be set from its chip (the letter in the address bar)"
            val plRow = actionRow(extra, R.drawable.ic_info, "Pull to refresh on social sites", plHint()) {
                SocialPull.setMode(act, (SocialPull.mode(act) + 1) % SocialPull.modeNames.size)
                plTv.text = plHint()
            }
            plTv = ((plRow as LinearLayout).getChildAt(1) as LinearLayout).getChildAt(1) as TextView
            switchRow(extra, R.drawable.ic_info, "Keep social tabs alive", "Social tabs (up to 4) are not freed by Suspend old tabs, so you come back to the same page. Uses more memory",
                null, { SocialKeep.on(act) }) { on -> SocialKeep.setOn(act, on) }
            switchRow(extra, R.drawable.ic_info, "Clean copied and shared links", "Removes tracking parts (fbclid, utm_, igsh, gclid ...) from a link when you copy or share it. The page itself is not changed",
                null, { SocialClean.on(act) }) { on -> SocialClean.setOn(act, on) }
            switchRow(extra, R.drawable.ic_lock, "Lock social tabs", "Asks for fingerprint / face / screen lock when a social tab is in front and you were away 1 minute or more. Needs a screen lock on the phone. Not used while App lock is ON",
                null, { SocialLock.on(act) }) { on -> SocialLock.setOn(act, on) }
            lateinit var lkTv: TextView // 0.0.265 (D7 delay)
            fun lkHint() = SocialLock.delayLabel(act) + " (tap to change). The cover shows when you come back to a social tab after this time"
            val lkRow = actionRow(extra, R.drawable.ic_lock, "Lock social tabs: ask", lkHint()) {
                SocialLock.cycleDelay(act)
                lkTv.text = lkHint()
            }
            lkTv = ((lkRow as LinearLayout).getChildAt(1) as LinearLayout).getChildAt(1) as TextView
            switchRow(extra, R.drawable.ic_shield, "Focus mode (Facebook, Instagram, Reddit, X, Pinterest, LinkedIn)", "Hides Reels, promoted posts and suggestion boxes on these sites. Applies from the next page load; site changes can break it (Site fix can add more)",
                null, { SocialFocus.on(act) }) { on -> SocialFocus.setOn(act, on) }
            switchRow(extra, R.drawable.ic_info, "App-like error screen", "On a social site without internet, shows a clean No connection page with a Try again button instead of the system error page",
                null, { SocialError.on(act) }) { on -> SocialError.setOn(act, on) }
            lateinit var tlTv: TextView // 0.0.261 (C3)
            fun tlHint() = SocialTime.label(act) + ", today about " + SocialTime.todayMin(act) + " min (tap to change). A reminder shows at the limit, nothing is closed"
            val tlRow = actionRow(extra, R.drawable.ic_info, "Time limit for social sites", tlHint()) {
                SocialTime.cycle(act)
                tlTv.text = tlHint()
            }
            tlTv = ((tlRow as LinearLayout).getChildAt(1) as LinearLayout).getChildAt(1) as TextView
            switchRow(extra, R.drawable.ic_info, "Data saver plus: no videos on mobile data", "On mobile data (or a Wi-Fi the phone marks as metered) video files are not downloaded on social sites. Wi-Fi is not touched. Some sites use video addresses that this cannot see",
                null, { SocialSave2.on(act) }) { on -> SocialSave2.setOn(act, on) }
            switchRow(extra, R.drawable.ic_info, "Social dock", "A slim row above the bottom bar on social tabs: one letter icon per site, tap = go to that site's tab (or open it), red number = unread. Hides with the bottom bar",
                null, { SocialDock.on(act) }) { on -> SocialDock.setOn(act, on) } // 0.0.272 (C1)
            switchRow(extra, R.drawable.ic_info, "Ask before leaving a social site", "A tapped link to another site shows a small sheet: Open in new tab (the feed stays), Open here or Cancel. Facebook and Instagram link wrappers are looked through",
                null, { SocialExt.on(act) }) { on -> SocialExt.setOn(act, on) } // 0.0.271 (D4)
            switchRow(extra, R.drawable.ic_info, "Video in a small window", if (SocialPip.supported(act)) "While a social video is full screen, pressing Home puts it in a small floating window (picture-in-picture). Only for social tabs" else "Not supported on this phone (needs Android 8 or newer)",
                null, { SocialPip.on(act) }) { on -> SocialPip.setOn(act, on) } // 0.0.278 (D8)
            lateinit var mtTv: TextView // 0.0.267 (C6)
            fun mtHint() = (if (SocialMute.count(act) == 0) "No words yet" else SocialMute.count(act).toString() + " word(s)") + " (tap to edit). Posts with these words are hidden on Facebook, Instagram, Reddit, X, LinkedIn. Stays on the phone"
            val mtRow = actionRow(extra, R.drawable.ic_info, "Muted words", mtHint()) {
                muteDialog { mtTv.text = mtHint() }
            }
            mtTv = ((mtRow as LinearLayout).getChildAt(1) as LinearLayout).getChildAt(1) as TextView
            lateinit var dkTv: TextView // 0.0.275 (C8)
            fun dkHint() = (if (SocialDark.siteOverrides(act) == 0) "All sites follow the app setting Dark pages" else SocialDark.siteOverrides(act).toString() + " site(s) have their own choice") + " (tap to change). Needs a dark app theme"
            val dkRow = actionRow(extra, R.drawable.ic_info, "Dark page per site", dkHint()) { darkSitesDialog { dkTv.text = dkHint() } }
            dkTv = ((dkRow as LinearLayout).getChildAt(1) as LinearLayout).getChildAt(1) as TextView
            actionRow(extra, R.drawable.ic_info, "Site fixes: copy", "Copies your Site fixes (all sites) as text, to keep or to paste on another phone") { // 0.0.269 (C8)
                val t = SocialFixIO.export(act)
                if (t == null) Util.toast(act, "No Site fix yet") else { SocialFixIO.copy(act, t); Util.toast(act, "Copied") }
            }
            actionRow(extra, R.drawable.ic_info, "Site fixes: paste", "Takes Site fixes copied from Thazir (clipboard) and applies them. Same sites are replaced. Asks first") {
                val clip = SocialFixIO.clipboardText(act)
                MaterialAlertDialogBuilder(act)
                    .setTitle("Paste Site fixes?")
                    .setMessage("The text on the clipboard will replace the Site fix of every site it lists. Other sites stay as they are.")
                    .setPositiveButton("Paste") { _, _ ->
                        val n = SocialFixIO.importText(act, clip)
                        Util.toast(act, if (n < 0) "The clipboard has no Thazir Site fix" else "Applied to " + n + " site(s). Reload the sites")
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
            col.addView(extra, LinearLayout.LayoutParams(MATCH, WRAP))
            siteRows.add(extra)

            grp(0)
            val feeds = SocialProfiles.all.filter { it.defaultKind != SocialMode.Kind.CHAT }
            val chats = SocialProfiles.all.filter { it.defaultKind == SocialMode.Kind.CHAT }
            for ((title, list) in listOf("Feed sites" to feeds, "Chat sites (desktop page, fits the phone)" to chats)) {
                if (list.isEmpty()) continue
                col.addView(sectionLabel(title))
                val c = card()
                for (p in list) {
                    rowLetter = p.label // 0.0.254 (B5): letter circle instead of the same icon
                    val row = switchRow(c, R.drawable.ic_desktop, p.label, hintOf(p), { openSite(p) },
                        { SocialProfiles.siteOn(act, p.id) }) { on -> SocialProfiles.setSiteOn(act, p.id, on) }
                    siteRows.add(row)
                }
                col.addView(c, LinearLayout.LayoutParams(MATCH, WRAP))
            }

            col.addView(sectionLabel("Chat comfort")) // 0.0.243 (A9) + 0.0.244 (A8)
            val chatC = chatCard()
            col.addView(chatC, LinearLayout.LayoutParams(MATCH, WRAP))
            siteRows.add(chatC)

            col.addView(sectionLabel("Site fix")) // 0.0.236 (U2)
            val fixC = card()
            for (p in SocialProfiles.all) {
                lateinit var hintTv: TextView
                val row = actionRow(fixC, R.drawable.ic_settings, p.label, fixHint(p)) { fixDialog(p) { hintTv.text = fixHint(p) } }
                hintTv = ((row as LinearLayout).getChildAt(1) as LinearLayout).getChildAt(1) as TextView
            }
            col.addView(fixC, LinearLayout.LayoutParams(MATCH, WRAP))
            siteRows.add(fixC)

            col.addView(sectionLabel("Page layout")) // 0.0.257 (U4)
            val uaC = card()
            for (p in SocialProfiles.all) {
                lateinit var uaTv: TextView
                fun uaHint() = SocialMode.uaNames[SocialMode.uaMode(act, p.id)] + " (tap to change)"
                val row = actionRow(uaC, R.drawable.ic_desktop, p.label, uaHint()) {
                    SocialMode.setUaMode(act, p.id, (SocialMode.uaMode(act, p.id) + 1) % SocialMode.uaNames.size)
                    uaTv.text = uaHint()
                    Util.toast(act, p.label + ": " + SocialMode.uaNames[SocialMode.uaMode(act, p.id)] + ". Reload the site")
                }
                uaTv = ((row as LinearLayout).getChildAt(1) as LinearLayout).getChildAt(1) as TextView
            }
            actionRow(uaC, R.drawable.ic_info, "Why does a site look wrong?", "Shows the settings that apply to every site. Copy it for a bug report") { summaryDialog() }
            col.addView(uaC, LinearLayout.LayoutParams(MATCH, WRAP))
            siteRows.add(uaC)

            grp(1)
            col.addView(sectionLabel("Logins")) // 0.0.234 (P9)
            val sep = card()
            val supported = SocialMode.sepSupported()
            val sepRow = switchRow(sep, R.drawable.ic_shield, "Keep social logins separate",
                if (supported) "Each site gets its own cookies, apart from normal tabs. Applies to tabs opened from now on; log in again once"
                else "Not supported on this phone",
                null, { supported && SocialMode.sepOn(act) }) { on -> if (supported) SocialMode.setSepOn(act, on) }
            if (!supported) sepRow.alpha = 0.5f else siteRows.add(sepRow)
            if (supported) { // 0.0.256 (B3)
                val shareRow = switchRow(sep, R.drawable.ic_shield, "Share Facebook and Messenger login", "One login for both. Applies to tabs opened from now on; log in once more",
                    null, { SocialMode.shareOn(act) }) { on -> SocialMode.setShareOn(act, on) }
                siteRows.add(shareRow)
            }
            if (supported) { // 0.0.276 (C5): account slot per site
                lateinit var slTv: TextView
                fun slHint() = (if (SocialSlot.overrides(act) == 0) "All sites use Account 1" else SocialSlot.overrides(act).toString() + " site(s) use Account 2") + " (tap to change). New tabs only; needs the switch above"
                val slRow = actionRow(sep, R.drawable.ic_shield, "Account per site", slHint()) { slotDialog { slTv.text = slHint() } }
                slTv = ((slRow as LinearLayout).getChildAt(1) as LinearLayout).getChildAt(1) as TextView
            }
            if (supported) { // 0.0.251 (B2): forget saved social logins (not dimmed: works even with Social sites OFF)
                actionRow(sep, R.drawable.ic_trash, "Forget logins of one site", "Pick a site, its saved login and site data are removed") { forgetPick() }
                actionRow(sep, R.drawable.ic_trash, "Forget all social logins", "Removes the saved logins of every social site") { forgetAllAsk() }
            }
            col.addView(sep, LinearLayout.LayoutParams(MATCH, WRAP))

            grp(2)
            col.addView(sectionLabel("Home"))
            val home = card() // 0.0.230 (P6): not dimmed, a shortcut turns Social sites ON when tapped
            actionRow(home, R.drawable.ic_add, "Add a site to the phone home screen", "Icon opens it in a Social tab") { SocialShortcuts.pickPin(act) }
            actionRow(home, R.drawable.ic_add, "Add tiles to the browser Home page", "Tick the sites you want") { SocialShortcuts.pickTiles(act) }
            switchRow(home, R.drawable.ic_add, "Long-press shortcuts on the Thazir icon", "Shows up to 4 sites that are switched on when you long-press the app icon",
                null, { SocialShortcuts.dynOn(act) }) { on -> SocialShortcuts.setDynOn(act, on) }
            col.addView(home, LinearLayout.LayoutParams(MATCH, WRAP))

            col.addView(sectionLabel("Good to know"))
            val info = card()
            info.addView(TextView(act).apply {
                text = "The AI picture check is experimental: it needs a model file inside the app, it can miss things or blur harmless pictures, and it does not see video.\n\n" +
                    "No push notifications or sounds, no badge on the launcher icon: a browser tab cannot receive them when it is closed. The unread number on tab cards only updates while the tab is open.\n\n" +
                    "Separate logins: a tab that is already open keeps the cookies it has; a link that leaves a social site stays in that tab's profile.\n\n" +
                    "Forget logins removes the saved login of a site that has its own login. Normal tabs keep theirs.\n\n" +
                    "\"Continue with Google\" is often blocked in a browser tab. Use email and password.\n\n" +
                    "A site may show a security check after the browser identity changes. Hiding the Open-app bars depends on the live " +
                    "site and may need a small fix when the site changes.\n\n" +
                    "Page layout: if a site looks wrong, tap its row to switch between Auto, Phone page and Desktop fit, then reload the site.\n\n" +
                    "Site fix: type a button text or a line of CSS when a site changes and an Open-app bar comes back. It is only stored on this phone.\n\n" +
                    "A site that is switched off here opens like any other page."
                textSize = 12f
                setTextColor(act.getColor(R.color.mg_text_dim))
                setPadding(dp(18), dp(8), dp(16), dp(10))
            }, LinearLayout.LayoutParams(MATCH, WRAP))
            col.addView(info, LinearLayout.LayoutParams(MATCH, WRAP))

            // 0.0.262 (B4): three tabs under the Master switch; every card above is only shown or hidden (no card code changed)
            val items = ArrayList<Pair<View, Int>>()
            for (i in 0 until col.childCount) {
                var g = -1
                for ((s, gg) in marks) if (s <= i) g = gg
                items.add(col.getChildAt(i) to g)
            }
            val tabNames = arrayOf("Sites", "Privacy", "Extras")
            val chips = ArrayList<TextView>()
            if (lastTab < 0 || lastTab >= tabNames.size) lastTab = 0
            fun showTab() {
                for ((v, g) in items) v.visibility = if (g < 0 || g == lastTab) View.VISIBLE else View.GONE
                for ((i, t) in chips.withIndex()) {
                    val sel = i == lastTab
                    t.setTextColor(act.getColor(if (sel) R.color.mg_on_primary else R.color.mg_text_dim))
                    t.background = round(act.getColor(if (sel) R.color.mg_primary else R.color.mg_surface_variant), 22)
                }
            }
            val tabBar = LinearLayout(act).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(12), 0, dp(4))
            }
            for ((i, n) in tabNames.withIndex()) {
                val t = TextView(act).apply {
                    text = n
                    textSize = 14f
                    gravity = Gravity.CENTER
                    typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                    isClickable = true
                    contentDescription = n + " tab"
                    setOnClickListener { lastTab = i; showTab() }
                }
                chips.add(t)
                tabBar.addView(t, LinearLayout.LayoutParams(0, dp(44), 1f).apply { if (i > 0) marginStart = dp(8) })
            }
            col.addView(tabBar, 2, LinearLayout.LayoutParams(MATCH, WRAP)) // after the Master switch label + card
            showTab()
            applyDim()
        }

        /** 0.0.229 (P5): blur card = 3 choices (Off / Flagged only / All until tap) + strength slider 4..24 px. Prefs: SocialBlur. */
        /** 0.0.243 (A9) chat size chips 100 / 125 / 150 / 175 % + 0.0.244 (A8) keyboard switch. Size applies from the next page load of a chat site. */
        private fun chatCard(): LinearLayout {
            val c = card()
            c.addView(TextView(act).apply {
                text = "Chat size"; textSize = 15f; setTextColor(act.getColor(R.color.mg_text))
                setPadding(dp(16), dp(12), dp(16), 0)
            }, LinearLayout.LayoutParams(MATCH, WRAP))
            val seg = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(10), dp(8), dp(10), dp(4)) }
            val chips = ArrayList<TextView>()
            fun paint() {
                val cur = SocialMode.chatScale(act)
                for ((i, t) in chips.withIndex()) {
                    val sel = SocialMode.chatSizes[i] == cur
                    t.setTextColor(act.getColor(if (sel) R.color.mg_on_primary else R.color.mg_text))
                    t.background = round(act.getColor(if (sel) R.color.mg_primary else R.color.mg_surface_variant), 14, 1f,
                        act.getColor(if (sel) R.color.mg_primary else R.color.mg_outline))
                    t.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                }
            }
            for ((i, v) in SocialMode.chatSizes.withIndex()) {
                val t = TextView(act).apply {
                    text = "$v%"; textSize = 12f; maxLines = 1; gravity = Gravity.CENTER; isClickable = true
                    contentDescription = "Chat size $v percent"
                    setPadding(dp(4), 0, dp(4), 0)
                    setOnClickListener { it.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK); SocialMode.setChatScale(act, v); paint() }
                }
                chips.add(t)
                seg.addView(t, LinearLayout.LayoutParams(0, dp(44), 1f).apply { if (i > 0) marginStart = dp(6) })
            }
            paint()
            c.addView(seg, LinearLayout.LayoutParams(MATCH, WRAP))
            c.addView(TextView(act).apply {
                text = "Bigger text and buttons in chat sites (Facebook chat, Messenger, Discord, WhatsApp Web, Telegram Web, Snapchat). 100% = as before. Applies from the next page load; a very large size can break a site layout."
                textSize = 12f; setTextColor(act.getColor(R.color.mg_text_dim)); setPadding(dp(16), dp(4), dp(16), dp(8))
            }, LinearLayout.LayoutParams(MATCH, WRAP))
            switchRow(c, R.drawable.ic_screen, "Keep message box above keyboard", "In chat sites the box you tap is scrolled into view when the keyboard opens (applies from the next page load)",
                null, { SocialMode.kbOn(act) }) { on -> SocialMode.setKbOn(act, on) }
            return c
        }

        private fun blurCard(): LinearLayout {
            val c = card()
            val seg = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(10), dp(8), dp(10), dp(4)) }
            val labels = listOf("Off", "Flagged only", "All until tap")
            val chips = ArrayList<TextView>()
            fun paint() {
                val m = SocialBlur.mode(act)
                for ((i, t) in chips.withIndex()) {
                    val sel = i == m
                    t.setTextColor(act.getColor(if (sel) R.color.mg_on_primary else R.color.mg_text))
                    t.background = round(act.getColor(if (sel) R.color.mg_primary else R.color.mg_surface_variant), 14, 1f,
                        act.getColor(if (sel) R.color.mg_primary else R.color.mg_outline))
                    t.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                }
            }
            for ((i, l) in labels.withIndex()) {
                val t = TextView(act).apply {
                    text = l
                    textSize = 12f
                    maxLines = 1
                    gravity = Gravity.CENTER
                    isClickable = true
                    contentDescription = "Blur: $l"
                    setPadding(dp(4), 0, dp(4), 0)
                    setOnClickListener {
                        it.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                        SocialBlur.setMode(act, i)
                        paint()
                    }
                }
                chips.add(t)
                seg.addView(t, LinearLayout.LayoutParams(0, dp(44), 1f).apply { if (i > 0) marginStart = dp(6) })
            }
            paint()
            c.addView(seg, LinearLayout.LayoutParams(MATCH, WRAP))
            c.addView(TextView(act).apply {
                text = "Flagged only: media the site marks as NSFW / sensitive, or whose post says so. All until tap: every picture and " +
                    "video. Tap an item to show it; Reveal all shows the rest. Small pictures (avatars) stay clear."
                textSize = 12f
                setTextColor(act.getColor(R.color.mg_text_dim))
                setPadding(dp(16), dp(4), dp(16), dp(4))
            }, LinearLayout.LayoutParams(MATCH, WRAP))
            val lab = TextView(act).apply {
                textSize = 13f
                setTextColor(act.getColor(R.color.mg_text))
                setPadding(dp(16), dp(6), dp(16), 0)
            }
            fun setLab() { lab.text = "Blur strength: " + SocialBlur.px(act) + " px" }
            setLab()
            c.addView(lab, LinearLayout.LayoutParams(MATCH, WRAP))
            val tint = ColorStateList.valueOf(act.getColor(R.color.mg_toggle_on))
            c.addView(SeekBar(act).apply {
                max = 20 // 4..24 px
                progress = SocialBlur.px(act) - 4
                progressTintList = tint
                thumbTintList = tint
                contentDescription = "Blur strength"
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                        if (!fromUser) return
                        SocialBlur.setPx(act, p + 4)
                        setLab()
                    }
                    override fun onStartTrackingTouch(sb: SeekBar?) { }
                    override fun onStopTrackingTouch(sb: SeekBar?) { }
                })
            }, LinearLayout.LayoutParams(MATCH, dp(48)).apply { setMargins(dp(12), 0, dp(12), dp(4)) })
            lateinit var bsTv: TextView // 0.0.268 (B6)
            fun bsHint() = (if (SocialBlur.siteOverrides(act) == 0) "All sites follow the choice above" else SocialBlur.siteOverrides(act).toString() + " site(s) have their own choice") + " (tap to change)"
            val bsRow = actionRow(c, R.drawable.ic_info, "Blur per site", bsHint()) { blurSitesDialog { bsTv.text = bsHint() } }
            bsTv = ((bsRow as LinearLayout).getChildAt(1) as LinearLayout).getChildAt(1) as TextView
            return c
        }

        /** 0.0.268 (B6): one row per site, tap cycles Same as above / Off / Flagged only / All until tap. Applies from the next page load of that site. */
        private fun blurSitesDialog(onDone: () -> Unit) {
            val box = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), dp(4)) }
            box.addView(TextView(act).apply {
                text = "Tap a site to change its blur. Same as above = the choice on the blur card. Reload the site after a change."
                textSize = 12f; setTextColor(act.getColor(R.color.mg_text_dim)); setPadding(0, dp(6), 0, dp(8))
            })
            for (p in SocialProfiles.all) {
                val t = TextView(act).apply {
                    textSize = 14f; setTextColor(act.getColor(R.color.mg_text)); gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(12), 0, dp(12), 0); isClickable = true
                    background = round(act.getColor(R.color.mg_surface_variant), 12, 1f, act.getColor(R.color.mg_outline))
                }
                fun paint() { t.text = p.label + ": " + SocialBlur.siteLabel(SocialBlur.siteMode(act, p.id)) }
                paint()
                t.setOnClickListener { it.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK); SocialBlur.cycleSite(act, p.id); paint(); onDone() }
                box.addView(t, LinearLayout.LayoutParams(MATCH, dp(48)).apply { bottomMargin = dp(6) })
            }
            MaterialAlertDialogBuilder(act)
                .setTitle("Blur per site")
                .setView(ScrollView(act).apply { addView(box) })
                .setPositiveButton("Done", null)
                .show()
        }

        /** 0.0.276 (C5): one row per site, tap switches Account 1 / Account 2 for tabs opened from now on. Open tabs keep their account. */
        private fun slotDialog(onDone: () -> Unit) {
            val box = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), dp(4)) }
            box.addView(TextView(act).apply {
                text = "Pick which account a NEW tab of a site uses. Account 2 has its own login, so you can sign in with a second account. Tabs that are already open keep their account. Log in once in each account."
                textSize = 12f; setTextColor(act.getColor(R.color.mg_text_dim)); setPadding(0, dp(6), 0, dp(8))
            })
            for (p in SocialProfiles.all) {
                val t = TextView(act).apply {
                    textSize = 14f; setTextColor(act.getColor(R.color.mg_text)); gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(12), 0, dp(12), 0); isClickable = true
                    background = round(act.getColor(R.color.mg_surface_variant), 12, 1f, act.getColor(R.color.mg_outline))
                }
                fun paint() { t.text = p.label + ": " + SocialSlot.label(SocialSlot.slot(act, p.id)) }
                paint()
                t.setOnClickListener { it.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK); SocialSlot.cycle(act, p.id); paint(); onDone() }
                box.addView(t, LinearLayout.LayoutParams(MATCH, dp(48)).apply { bottomMargin = dp(6) })
            }
            MaterialAlertDialogBuilder(act)
                .setTitle("Account per site")
                .setView(ScrollView(act).apply { addView(box) })
                .setPositiveButton("Done", null)
                .show()
        }

        /** 0.0.275 (C8): one row per site, tap cycles Same as app setting / Dark / Original. Applies from the next page load of that site. */
        private fun darkSitesDialog(onDone: () -> Unit) {
            val box = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), dp(4)) }
            box.addView(TextView(act).apply {
                text = "Tap a site to change how its pages are darkened. Dark = darken this site, Original = never darken it. Needs the app theme to be dark. Reload the site after a change."
                textSize = 12f; setTextColor(act.getColor(R.color.mg_text_dim)); setPadding(0, dp(6), 0, dp(8))
            })
            for (p in SocialProfiles.all) {
                val t = TextView(act).apply {
                    textSize = 14f; setTextColor(act.getColor(R.color.mg_text)); gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(12), 0, dp(12), 0); isClickable = true
                    background = round(act.getColor(R.color.mg_surface_variant), 12, 1f, act.getColor(R.color.mg_outline))
                }
                fun paint() { t.text = p.label + ": " + SocialDark.siteLabel(SocialDark.siteMode(act, p.id)) }
                paint()
                t.setOnClickListener { it.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK); SocialDark.cycleSite(act, p.id); paint(); onDone() }
                box.addView(t, LinearLayout.LayoutParams(MATCH, dp(48)).apply { bottomMargin = dp(6) })
            }
            MaterialAlertDialogBuilder(act)
                .setTitle("Dark page per site")
                .setView(ScrollView(act).apply { addView(box) })
                .setPositiveButton("Done", null)
                .show()
        }

        /** 0.0.233 (P8b): AI picture check card = switch + Low / Normal / Strict + status + counter. Prefs: SocialNsfw. Pictures only, not video. */
        private fun aiCard(): LinearLayout {
            val c = card()
            val chips = ArrayList<TextView>()
            val status = TextView(act).apply { textSize = 12f; setPadding(dp(16), dp(4), dp(16), 0) }
            val count = TextView(act).apply { textSize = 12f; setTextColor(act.getColor(R.color.mg_text_dim)); setPadding(dp(16), dp(2), dp(16), dp(6)) }
            fun paint() {
                val lv = SocialNsfw.level(act)
                val en = SocialNsfw.on(act)
                for ((i, t) in chips.withIndex()) {
                    val sel = i == lv
                    t.alpha = if (en) 1f else 0.5f
                    t.setTextColor(act.getColor(if (sel) R.color.mg_on_primary else R.color.mg_text))
                    t.background = round(act.getColor(if (sel) R.color.mg_primary else R.color.mg_surface_variant), 14, 1f,
                        act.getColor(if (sel) R.color.mg_primary else R.color.mg_outline))
                    t.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                }
                val ready = SocialNsfw.modelReady(act)
                status.text = "Model: " + SocialNsfw.status(act)
                status.setTextColor(act.getColor(if (ready) R.color.mg_text_dim else R.color.mg_error))
                count.text = SocialNsfw.blurred.get().toString() + " pictures blurred since the app started" +
                    (if (SocialNsfw.fineCount.get() > 0) ", " + SocialNsfw.fineCount.get() + " marked fine" else "") // 0.0.273 (B7)
            }
            switchRow(c, R.drawable.ic_shield, "Check pictures with AI", "A model on this phone looks at pictures on social pages and blurs the ones it rates as sensitive",
                null, { SocialNsfw.on(act) }) { on -> SocialNsfw.setOn(act, on); paint() }
            val seg = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(10), dp(8), dp(10), dp(4)) }
            for ((i, l) in SocialNsfw.levelNames.withIndex()) {
                val t = TextView(act).apply {
                    text = l
                    textSize = 12f
                    maxLines = 1
                    gravity = Gravity.CENTER
                    isClickable = true
                    contentDescription = "AI check sensitivity: $l"
                    setPadding(dp(4), 0, dp(4), 0)
                    setOnClickListener {
                        it.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                        SocialNsfw.setLevel(act, i)
                        paint()
                    }
                }
                chips.add(t)
                seg.addView(t, LinearLayout.LayoutParams(0, dp(44), 1f).apply { if (i > 0) marginStart = dp(6) })
            }
            c.addView(seg, LinearLayout.LayoutParams(MATCH, WRAP))
            c.addView(TextView(act).apply {
                text = "Sensitivity: Low blurs only clear cases, Strict blurs more (and more wrongly). Pictures only: videos are not checked. " +
                    "Not 100 % accurate. Pictures are fetched once by the app and nothing is sent anywhere else."
                textSize = 12f
                setTextColor(act.getColor(R.color.mg_text_dim))
                setPadding(dp(16), dp(4), dp(16), dp(4))
            }, LinearLayout.LayoutParams(MATCH, WRAP))
            c.addView(status, LinearLayout.LayoutParams(MATCH, WRAP))
            c.addView(count, LinearLayout.LayoutParams(MATCH, WRAP))
            paint()
            // 0.0.273 (B7): live refresh of status + counter while the page is on screen (every 1.5 s, stops when it is closed)
            count.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                private val tick = object : Runnable {
                    override fun run() { if (count.isAttachedToWindow) { paint(); count.postDelayed(this, 1500) } }
                }
                override fun onViewAttachedToWindow(v: View) { count.postDelayed(tick, 1500) }
                override fun onViewDetachedFromWindow(v: View) { count.removeCallbacks(tick) }
            })
            return c
        }

        private fun hintOf(p: SocialProfiles.Profile): String = when {
            p.sub.isNotBlank() -> p.sub
            p.id == "facebook" -> "Feed, chat pages fit the phone"
            p.id == "messenger" -> "Chat that fits the phone"
            p.id == "instagram" -> "Feed and messages without the app nag"
            else -> ""
        }

        /** Site rows look dimmed while the master switch is OFF (they can still be changed). */
        private fun applyDim() {
            val a = if (SocialMode.on(act)) 1f else 0.5f
            for (r in siteRows) r.alpha = a
        }

        /** "Open" pill: opens the site start page in a new tab (switches the site and the master switch ON). */
        private fun openSite(p: SocialProfiles.Profile) {
            val tabs = BookmarksScreen.tabsRef
            if (tabs == null) { Util.toast(act, "Open a tab first"); return }
            if (!SocialProfiles.siteOn(act, p.id)) SocialProfiles.setSiteOn(act, p.id, true)
            if (SiteWindow.on(act) && SiteWindow.launch(act, p)) { skipClose = true; SettingsScreen.closeAll(); dismiss(); return } // 0.0.247 (A7)
            SocialMode.open(act, tabs, p.startUrl)
            skipClose = true
            SettingsScreen.closeAll()
            dismiss()
        }

        /** Plain tap row (>= 56 dp): icon + label + hint + chevron-less, whole row is the touch target. */
        private fun actionRow(parent: LinearLayout, icon: Int, label: String, hint: String, onTap: () -> Unit): View {
            val row = LinearLayout(act).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(56)
                setPadding(dp(14), dp(6), dp(12), dp(6))
                isClickable = true
                rippleBg(this)
                setOnClickListener { onTap() }
            }
            row.addView(ImageView(act).apply {
                setImageResource(icon)
                setColorFilter(act.getColor(R.color.mg_text_dim))
            }, LinearLayout.LayoutParams(dp(22), dp(22)).apply { marginEnd = dp(14) })
            val txt = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
            txt.addView(TextView(act).apply { text = label; textSize = 15f; setTextColor(act.getColor(R.color.mg_text)) })
            txt.addView(TextView(act).apply {
                text = hint; textSize = 12f; setTextColor(act.getColor(R.color.mg_text_dim)); setPadding(0, dp(2), 0, 0)
            })
            row.addView(txt, LinearLayout.LayoutParams(0, WRAP, 1f))
            parent.addView(row, LinearLayout.LayoutParams(MATCH, WRAP))
            return row
        }

        /** 0.0.258 (U5): plain-text summary of the settings per site (no page is read). Copy button for a bug report. */
        private fun summaryDialog() {
            fun yn(v: Boolean) = if (v) "on" else "off"
            val sb = StringBuilder()
            sb.append("Social sites: ").append(yn(SocialMode.on(act))).append("\n")
            sb.append("Separate logins: ").append(if (SocialMode.sepSupported()) yn(SocialMode.sepOn(act)) else "not supported")
                .append(", share Facebook + Messenger: ").append(yn(SocialMode.shareOn(act))).append("\n")
            sb.append("Extra site fixes: ").append(yn(SocialMode.dfixOn(act)))
                .append(", bigger buttons: ").append(yn(SocialMode.bigOn(act)))
                .append(", skip Use-the-app screens: ").append(yn(SocialMode.intOn(act)))
                .append(", full screen: ").append(yn(SocialMode.fullOn(act)))
                .append(", calmer touch: ").append(yn(SocialMode.touchOn(act)))
                .append(", pull to refresh: ").append(SocialPull.modeNames[SocialPull.mode(act)])
                .append(", keep tabs alive: ").append(yn(SocialKeep.on(act)))
                .append(", clean links: ").append(yn(SocialClean.on(act)))
                .append(", lock: ").append(yn(SocialLock.on(act))).append(" (").append(SocialLock.delayLabel(act)).append(")")
                .append(", error screen: ").append(yn(SocialError.on(act)))
                .append(", focus mode: ").append(yn(SocialFocus.on(act)))
                .append(", data saver plus: ").append(yn(SocialSave2.on(act))).append(" (").append(SocialSave2.blocked).append(" videos blocked)")
                .append(", muted words: ").append(SocialMute.count(act))
                .append(", ask before leaving: ").append(yn(SocialExt.on(act)))
                .append(", small video window: ").append(yn(SocialPip.on(act)))
                .append(", dock: ").append(yn(SocialDock.on(act)))
                .append(", blur per site: ").append(SocialBlur.siteOverrides(act)).append(" own")
                .append(", account 2 on: ").append(SocialSlot.overrides(act)).append(" site(s)")
                .append(", dark page per site: ").append(SocialDark.siteOverrides(act)).append(" own")
                .append(", blur mode: ").append(SocialBlur.mode(act)).append(" (0 off, 1 flagged, 2 all), strength ").append(SocialBlur.px(act)).append(" px")
                .append(", AI picture check: ").append(yn(SocialNsfw.on(act))).append(" level ").append(SocialNsfw.level(act))
                .append(", model: ").append(SocialNsfw.status(act)).append(", ").append(SocialNsfw.blurred.get()).append(" pictures blurred since start")
                .append(", time limit: ").append(SocialTime.label(act)).append("\n\n")
            for (p in SocialProfiles.all) {
                sb.append(p.label).append(": ").append(yn(SocialProfiles.siteOn(act, p.id)))
                    .append(", layout ").append(SocialMode.uaNames[SocialMode.uaMode(act, p.id)])
                    .append(" (page type ").append(SocialMode.kind(p.startUrl).name.lowercase()).append(")")
                    .append(", login ").append(SocialMode.profileName(act, p.startUrl) ?: "normal")
                    .append(", own fix ").append(if (SocialProfiles.hasFix(act, p.id)) "yes" else "no")
                    .append(", ").append(SocialProbe.line(p.id)).append("\n") // 0.0.277 (U5)
            }
            val text = sb.toString().trim()
            MaterialAlertDialogBuilder(act)
                .setTitle("Settings summary")
                .setMessage(text)
                .setPositiveButton("Close", null)
                .setNegativeButton("Check open pages") { _, _ -> // 0.0.277 (U5): asks the open social pages which scripts ran (read-only)
                    SocialProbe.check { rep ->
                        MaterialAlertDialogBuilder(act)
                            .setTitle("Open social pages")
                            .setMessage(rep + "\n\nOnly pages opened since the app started are listed. Reload a site, then check again if a line says no answer.")
                            .setPositiveButton("Close", null)
                            .setNeutralButton("Copy") { _, _ ->
                                try {
                                    (act.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager)
                                        .setPrimaryClip(android.content.ClipData.newPlainText("Thazir social pages", text + "\n\n" + rep))
                                    Util.toast(act, "Copied")
                                } catch (_: Throwable) { }
                            }
                            .show()
                    }
                }
                .setNeutralButton("Copy") { _, _ ->
                    try {
                        (act.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager)
                            .setPrimaryClip(android.content.ClipData.newPlainText("Thazir social settings", text))
                        Util.toast(act, "Copied")
                    } catch (_: Throwable) { }
                }
                .show()
        }

        /** 0.0.251 (B2): list of sites, tap one = ask, then SocialMode.forget. */
        private fun forgetPick() {
            val ps = SocialProfiles.all
            val names = Array(ps.size) { i -> ps[i].label + (if (SocialMode.hasProfile(act, ps[i].id)) "  (has saved login)" else "  (nothing saved)") }
            MaterialAlertDialogBuilder(act)
                .setTitle("Forget logins of")
                .setItems(names) { _, i -> forgetOneAsk(ps[i]) }
                .setNegativeButton("Cancel", null)
                .show()
        }
        private fun forgetOneAsk(p: SocialProfiles.Profile) {
            MaterialAlertDialogBuilder(act)
                .setTitle("Forget " + p.label + " login?")
                .setMessage("You will have to log in to " + p.label + " again in the tabs that use its own login. Normal tabs are not touched. A tab that is open on it should be reloaded.")
                .setPositiveButton("Forget") { _, _ ->
                    when (SocialMode.forget(act, p.id)) {
                        SocialMode.Forgot.DONE -> Util.toast(act, p.label + " login forgotten")
                        SocialMode.Forgot.CLEARED -> Util.toast(act, p.label + " login forgotten. Reload its open tab")
                        SocialMode.Forgot.NONE -> Util.toast(act, "Nothing saved for " + p.label)
                        SocialMode.Forgot.UNSUPPORTED -> Util.toast(act, "Not supported on this phone")
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
        private fun forgetAllAsk() {
            MaterialAlertDialogBuilder(act)
                .setTitle("Forget all social logins?")
                .setMessage("Every social site that has its own login is logged out. Normal tabs are not touched. Open social tabs should be reloaded.")
                .setPositiveButton("Forget all") { _, _ ->
                    val n = SocialMode.forgetAll(act)
                    Util.toast(act, if (n == 0) "Nothing was saved" else "Forgot logins of " + n + " site" + (if (n == 1) "" else "s"))
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        private fun fixHint(p: SocialProfiles.Profile): String =
            if (SocialProfiles.hasFix(act, p.id)) "Your fix is on" else "Hide an Open-app bar or an element yourself"

        /** 0.0.236 (U2): edit dialog. Words = extra texts of the Open-app button (comma separated), CSS = one line. Save / Clear / Cancel; applies from the next page load. */
        private fun fixDialog(p: SocialProfiles.Profile, onDone: () -> Unit) {
            val box = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), dp(4)) }
            fun lab(t: String) = TextView(act).apply {
                text = t; textSize = 12f; setTextColor(act.getColor(R.color.mg_text_dim)); setPadding(0, dp(10), 0, dp(4))
            }
            fun field(h: String, v: String) = EditText(act).apply {
                hint = h; setText(v); textSize = 14f; minLines = 2; maxLines = 6
                gravity = Gravity.TOP or Gravity.START
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                setTextColor(act.getColor(R.color.mg_text)); setHintTextColor(act.getColor(R.color.mg_text_dim))
                background = round(act.getColor(R.color.mg_surface_variant), 12, 1f, act.getColor(R.color.mg_outline))
                setPadding(dp(12), dp(10), dp(12), dp(10))
            }
            box.addView(lab("Extra Open-app button texts, separated by commas. Built-in app names: " +
                (if (p.appWords.isEmpty()) "none" else p.appWords.joinToString(", ")) + "."))
            val w = field("Try the app, Open " + p.label, SocialProfiles.fixWords(act, p.id).joinToString(", "))
            box.addView(w, LinearLayout.LayoutParams(MATCH, WRAP))
            box.addView(lab("CSS to hide something (one line, no url or @import)."))
            val c = field(".promo-bar{display:none!important}", SocialProfiles.fixCss(act, p.id))
            box.addView(c, LinearLayout.LayoutParams(MATCH, WRAP))
            box.addView(lab("Applies from the next page load of this site. Empty = built-in only. Clear removes your fix."))
            MaterialAlertDialogBuilder(act)
                .setTitle(p.label + " fix")
                .setView(ScrollView(act).apply { addView(box) })
                .setPositiveButton("Save") { _, _ ->
                    SocialProfiles.setFix(act, p.id, w.text.toString(), c.text.toString())
                    Util.toast(act, "Saved. Reload the site page")
                    onDone()
                }
                .setNeutralButton("Clear") { _, _ -> SocialProfiles.clearFix(act, p.id); onDone() }
                .setNegativeButton("Cancel", null)
                .show()
        }

        /** 0.0.267 (C6): edit the muted words, one per line (max 20). Empty + Save = list removed. Applies from the next page load. */
        private fun muteDialog(onDone: () -> Unit) {
            val box = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), dp(4)) }
            box.addView(TextView(act).apply {
                text = "One word or phrase per line, up to " + SocialMute.MAX + ". Capital letters do not matter. A post with any of them is hidden."
                textSize = 12f; setTextColor(act.getColor(R.color.mg_text_dim)); setPadding(0, dp(6), 0, dp(8))
            })
            val et = EditText(act).apply {
                hint = "word"; setText(SocialMute.words(act).joinToString("\n")); textSize = 14f; minLines = 4; maxLines = 10
                gravity = Gravity.TOP or Gravity.START
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                setTextColor(act.getColor(R.color.mg_text)); setHintTextColor(act.getColor(R.color.mg_text_dim))
                background = round(act.getColor(R.color.mg_surface_variant), 12, 1f, act.getColor(R.color.mg_outline))
                setPadding(dp(12), dp(10), dp(12), dp(10))
            }
            box.addView(et, LinearLayout.LayoutParams(MATCH, WRAP))
            MaterialAlertDialogBuilder(act)
                .setTitle("Muted words")
                .setView(ScrollView(act).apply { addView(box) })
                .setPositiveButton("Save") { _, _ ->
                    SocialMute.setWords(act, et.text.toString())
                    Util.toast(act, "Saved. Reload the site page")
                    onDone()
                }
                .setNeutralButton("Clear") { _, _ -> SocialMute.setWords(act, ""); onDone() }
                .setNegativeButton("Cancel", null)
                .show()
        }

        private fun sectionLabel(t: String) = TextView(act).apply {
            text = t.uppercase()
            textSize = 11f
            letterSpacing = 0.08f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(act.getColor(R.color.mg_primary))
            setPadding(dp(8), dp(16), 0, dp(6))
            setCompoundDrawablesWithIntrinsicBounds(GradientDrawable().apply {
                setColor(act.getColor(R.color.mg_toggle_on)); cornerRadius = dp(2).toFloat(); setSize(dp(4), dp(12))
            }, null, null, null)
            compoundDrawablePadding = dp(8)
        }

        private fun card(): LinearLayout = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            background = round(act.getColor(R.color.mg_surface), 20, 1f, ColorUtils.setAlphaComponent(act.getColor(R.color.mg_outline), 0x99))
            setPadding(dp(4), dp(6), dp(4), dp(6))
        }

        private fun rippleBg(v: View) {
            val tv = TypedValue()
            if (act.theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)) v.setBackgroundResource(tv.resourceId)
        }

        /** Switch row (>= 52 dp): icon + label + hint + optional "Open" pill + ThazirSwitch. Row tap toggles. Returns the row. */
        private var rowLetter: String? = null // 0.0.254 (B5): set just before switchRow, read + cleared inside it
        private fun switchRow(parent: LinearLayout, icon: Int, label: String, hint: String, onOpen: (() -> Unit)?,
                              get: () -> Boolean, set: (Boolean) -> Unit): View {
            val sw = ThazirSwitch(act).apply { isChecked = get(); isClickable = false; isFocusable = false }
            val row = LinearLayout(act).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(56)
                setPadding(dp(14), dp(6), dp(12), dp(6))
                isClickable = true
                rippleBg(this)
            }
            val letter = rowLetter; rowLetter = null
            if (letter != null) { // 0.0.254 (B5): violet circle with the first letter of the site name (same look as the home-screen shortcut icon)
                row.addView(TextView(act).apply {
                    text = letter.trim().take(1).uppercase(); textSize = 13f
                    typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                    setTextColor(act.getColor(R.color.mg_on_primary)); gravity = Gravity.CENTER
                    background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(act.getColor(R.color.mg_primary)) }
                }, LinearLayout.LayoutParams(dp(26), dp(26)).apply { marginEnd = dp(12) })
            } else row.addView(ImageView(act).apply {
                setImageResource(icon)
                setColorFilter(act.getColor(R.color.mg_text_dim))
            }, LinearLayout.LayoutParams(dp(22), dp(22)).apply { marginEnd = dp(14) })
            val txt = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
            txt.addView(TextView(act).apply { text = label; textSize = 15f; setTextColor(act.getColor(R.color.mg_text)) })
            if (hint.isNotBlank()) txt.addView(TextView(act).apply {
                text = hint; textSize = 12f; setTextColor(act.getColor(R.color.mg_text_dim)); setPadding(0, dp(2), 0, 0)
            })
            row.addView(txt, LinearLayout.LayoutParams(0, WRAP, 1f))
            if (onOpen != null) {
                val pill = TextView(act).apply {
                    text = "Open"
                    textSize = 13f
                    typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                    setTextColor(act.getColor(R.color.mg_primary))
                    gravity = Gravity.CENTER
                    background = round(act.getColor(R.color.mg_surface_variant), 16, 1f, act.getColor(R.color.mg_outline))
                    contentDescription = "Open $label"
                }
                val touch = FrameLayout(act).apply { isClickable = true; setOnClickListener { onOpen() } }
                touch.addView(pill, FrameLayout.LayoutParams(dp(56), dp(32), Gravity.CENTER))
                row.addView(touch, LinearLayout.LayoutParams(dp(64), dp(44)).apply { marginStart = dp(6) })
            }
            row.addView(sw, LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = dp(6) })
            row.setOnClickListener {
                it.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                set(!get())
                sw.isChecked = get()
            }
            parent.addView(row, LinearLayout.LayoutParams(MATCH, WRAP))
            return row
        }
    }
}
