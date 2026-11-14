package com.jabra.mgb

import android.app.Activity
import android.app.Dialog
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebStorage
import android.animation.ValueAnimator
import android.transition.ChangeBounds
import android.transition.Fade
import android.transition.TransitionSet
import android.transition.TransitionManager
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.EditText
import android.text.Editable
import android.text.TextWatcher
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import androidx.core.graphics.ColorUtils
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar

/**
 * S27: native Settings screen (full-screen Dialog, no manifest entry). The V77 options page stays reachable
 * as "Grabber settings".
 * S46: categories are collapsed cards (accordion, one open at a time). Inside a category the rows are
 * grouped by small sub labels, ON/OFF rows use a switch. Open category is kept while a toggle rebuilds.
 * S48: search box under the title (filters rows by label, matching categories open); a category with more
 * than SUB_FOLD rows gets collapsible sub groups.
 */
object SettingsScreen {
    private const val TAG = "MGB.settings"
    private var current: Screen? = null

    fun open(host: Activity, query: String = "") { // 0.0.440: query = text put in the search box (Command Center)
        if (host.isFinishing || host.isDestroyed) return
        closeAll()
        try {
            val s = Screen(host)
            current = s
            s.show()
            if (query.isNotBlank()) s.preSearch(query)
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

    /** V18: true while the Settings dialog is on screen (sub screens open on top of it, Back returns here). */
    fun isShowing(): Boolean = current?.isShowing == true

    private class Screen(private val act: Activity) :
        Dialog(act, com.google.android.material.R.style.Theme_Material3_DayNight_NoActionBar) {

        private val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        private val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
        private var body: LinearLayout? = null
        private var sv: ScrollView? = null
        private var openCat: String? = null // S46
        private var query = "" // S48 search text
        // 0.0.136 (Step A): big categories open as their own page inside this Dialog
        private var subPage: String? = null
        private var savedY = 0
        private var forceY = -1
        private var titleTv: TextView? = null
        private var sboxView: View? = null
        private var searchEdit: EditText? = null
        private val BIG = mapOf("downloads" to "Downloads", "grabber" to "Grabber and buttons", "player" to "Player") // 0.0.312: Player is a page too (all three sit in one hub card)
        private val NO_RECENT = setOf("Forget removed links")
        private var flashLabel: String? = null // Step B: row that was just toggled
        /** Step C: the ONLY mgb_ui keys each card's "Reset to default" may remove. Nothing else is ever touched. */
        private val RESET = mapOf(
            "general" to listOf("night", "accent", "surf", "dark_pages", "dark_sites", "page_tc", "engine", "tr_lang", "hide_bar", "pull_refresh", "rotate_browser",
                "ap_bar_pos", "ap_home_action", "ap_full_url", "ap_swipe_fs", "ap_swipe_tabs", "ap_swipe_side", "ap_swipe_nav", "ap_wp_dim", "ap_undo_toast", "url_suggest", "ap_start_page", "ap_bar_compact", "ap_nav_back", "ap_nav_fwd", "ap_nav_hide", "ap_zoom_def", "ap_force_zoom", "ap_touchpad", "ap_simple_hint", "sm_on", "sm_sep", "sm_int", "sm_full", "sm_bars", "sm_back", "sm_splash", "sm_cs", "sm_kb", "sm_big", "sm_links", "sm_win", "sm_dfix", "sm_pre", "sm_keep", "sm_dyn", "sm_touch", "sm_fbshare", "sm_clean", "sm_lock", "sm_lockd", "sm_err", "sm_focus", "sm_ext", "sm_pip", "sm_dock", "sm_save2", "sm_mute", "sm_tl", "sm_t_day", "sm_t_sec") + MediaPrefs.KEYS + Orbit.KEYS + SiteMute.KEY + SocialProfiles.all.map { "sm_s_" + it.id } + SocialProfiles.all.map { "sm_x_" + it.id } + SocialProfiles.all.map { "sm_ua_" + it.id } + SocialBlur.keys + SocialBadge.keys + SocialNsfw.keys + SocialDark.keys + SocialSlot.keys + listOf("swipe_close", "close_confirm", "reduce_motion", "tab_suspend_min", ExtFit.KEY, ExtFit.KEY_W, ExtTheme.KEY, ExtCssCompat.KEY, ExtDark.KEY, ExtPriv.KEY), // A9, A11, A1, A2: + Appearance keys; 0.0.312: + Tabs keys (Tabs card merged into Browser)
            "downloads" to listOf("auto_diag", "dr_wifi", "dr_metered", "dr_batt", "dr_store", "li_on", "dp_on", "dp_n", "dp_min_mb", "dl_limit_kbps", "dl_max_jobs", "dl_auto_resume", "dp_tail", DownloadAsk.KEY), // D1, D2: download rules
            "grabber" to listOf("web_play", "v77_backup", "v77_prev_mode", "dl_mode", "native_btn", "video_btn"),
            "player" to listOf("pl_gestures", "pl_hold_speed", "pl_hold_slide", "pl_dtap_sec", "pl_seek_sens", "pl_vol_boost", "pl_bright", "pl_fit_default",
                "pl_speed_keep", "pl_speed_last", "pl_speeds", "pl_quality_cap", "pl_repeat", "pl_sub_size", "pl_sub_bg", "pl_sub_pos", "sub_size", "sub_color", "bg_audio"),
            "privacy" to listOf("https_only", "hide_ad_space", "hide_cookie", "priv_secure"),
            "data" to listOf("auto_backup")
        )
        private val RESTART = setOf("Theme", "Rotate browser with phone", "Extra native download button") // Step C: restart icon
        /** 0.0.443: plain switches (one pref flip, no dialog, no sign-in) that get an Undo bar. Add a label only for a row whose onClick is just a toggle. */
        private val UNDO_OK = setOf(
            "Hide bar while scrolling", "Pull down to refresh", "Address suggestions", "Reddit 18+ without the app", "Block auto app-open",
            "Swipe a tab card to close it", "Ask before Close all", "Auto Diagnose pill", "Sub-folders in the chosen folder", "Link inbox",
            "Play video links in built-in player", "HTTPS-only mode", "Hide ad spaces on pages", "Hide cookie banners", "Smarter login detection",
            "New password screens", "Fill chip near the bar", "Password health card", "Player gestures", "Slide while holding", "Keep speed"
        )
        private val RESTART_BAR = setOf("Rotate browser with phone", "Extra native download button") // Snackbar (Theme recreates itself)
        private var flashAt = 0L
        private var valFlashLabel: String? = null // 0.0.280: row whose value text just changed (fades in once)
        private var valFlashAt = 0L
        /** Step B: one short hint line for rows that have none (text only, kept under about 34 characters). */
        private val HINTS = mapOf(
            "Theme" to "Light, dark or follow phone",
            "Appearance" to "Theme and dark pages",
            "Accessibility" to "Zoom, captions, simplified view",
            "Dark pages" to "Darken websites (reload page)",
            "Search engine" to "Used in the address bar",
            "Translate to" to "Language for page translation",
            "Hide bar while scrolling" to "Bottom bar hides on scroll",
            "Pull down to refresh" to "Swipe down to reload the page",
            "Rotate browser with phone" to "Landscape follows the phone",
            "Swipe a tab card to close it" to "In the tab switcher",
            "Convert .ts to .mp4" to "Merge HLS pieces into MP4",
            "Sub-folders in the chosen folder" to "Use sub-folders for new files",
            "Play video links in built-in player" to "Open video links in MGB player",
            "Video button on pages" to "Video button shown on web pages",
            "Background audio in player" to "Keep playing with screen off",
            "Button mode" to "Which download buttons to show",
            "Grabber button on pages" to "Floating Grabber button",
            "Grabber button opens Files detected panel" to "Opens the native detected list",
            "Grabber button on video players" to "Button on top of videos",
            "Forget removed links" to "Brings back hidden links",
            "Extra native download button" to "Second button, native downloader",
            "App lock" to "Ask for fingerprint or PIN",
            "Lock after" to "Time before the app locks",
            "Hide private tabs in app preview" to "Blank Recents picture, no screenshots",
            "Lock private tabs" to "Fingerprint or PIN for private tabs",
            "Close all private tabs" to "Closes every private tab now",
            "Site permissions" to "Camera, mic, location per site",
            "Site settings" to "Permissions, JS, desktop, text size per site",
            "HTTPS-only mode" to "Prefer secure https pages",
            "Ad blocker" to "Blocks ads and trackers",
            "Hide ad spaces on pages" to "Removes empty ad boxes",
            "Sites with ads allowed" to "Pages where ads stay on",
            "Hide cookie banners" to "Hides cookie consent popups",
            "Auto backup (daily)" to "Saves a backup file each day",
            "Export data" to "Save bookmarks and settings",
            "Import data" to "Restore from a backup file",
            "Recently closed tabs" to "Reopen tabs you closed",
            "Saved pages" to "Pages kept for offline",
            "Clear browsing history" to "Deletes the visited pages list",
            "Clear cookies and site data" to "Logs you out of sites",
            "Clear cache" to "Frees stored page files",
            "Saved passwords" to "View and edit saved logins",
            "Import passwords (file or paste)" to "Add logins from CSV or text",
            "Export passwords (CSV)" to "Save logins to a CSV file",
            "Password generator" to "Make a strong random password",
            "addy.io API key" to "Needed to create aliases",
            "Create email alias" to "New hidden email address",
            "My email aliases" to "List, edit or delete aliases",
            "Check for updates" to "Look for a newer version",
            "Version history" to "What changed in each version",
            "Engine status" to "Shows the web engine state",
            "Hosts and filters" to "Extra ad block lists and rules", // 0.0.441: hints for rows that had none
            "Encrypted backup of passwords" to "Save logins in a locked file",
            "Restore passwords from backup" to "Load a locked password file",
            "Sites never saved" to "Sites where no login is offered",
            "Smarter login detection" to "Finds login forms on more sites",
            "Run userscripts" to "Master switch for all userscripts",
            "Userscripts in private tabs" to "Let userscripts run in private tabs"
        )
        /** S21f: hidden search words per row label (the row is found by these too). */
        private val KW = mapOf(
            "Theme" to "dark light night mode appearance colour",
            "Appearance" to "theme dark light night look colour style wallpaper bar top bottom toggle dark pages home button swipe fullscreen undo start page menu",
            "Accessibility" to "zoom text size font bigger larger smaller read default saved sites preview force pinch touchpad captions subtitles simplified reader article",
            "Default browser" to "links open http https set app phone settings",
            "Dark pages" to "dark mode night websites black",
            "Search engine" to "google bing duckduckgo default address bar",
            "Translate to" to "language translation",
            "Hide bar while scrolling" to "bottom bar toolbar fullscreen immersive",
            "Pull down to refresh" to "reload swipe",
            "Address suggestions" to "autocomplete url bar omnibox history bookmarks recommend type search",
            "Rotate browser with phone" to "landscape portrait auto rotate orientation",
            "Swipe a tab card to close it" to "gesture tab switcher remove",
            "Ask before Close all" to "confirm dialog tabs",
            "Reduce motion" to "animation animations effects fade transitions speed",
            "Download folder" to "storage location save path sd card",
            "App downloader" to "download in parts parallel connections threads segments multi part faster speed limit queue resume smart tail 1dm range native",
            "Download quality" to "resolution 1080 720 best height",
            "Convert .ts to .mp4" to "hls merge remux",
            "Subtitles and file names" to "srt vtt naming template language",
            "Play video links in built-in player" to "external player exoplayer",
            "Background audio in player" to "music screen off",
            "Player gestures" to "swipe hold pinch zoom brightness volume seek video player",
            "Hold speed" to "long press fast forward 2x video player",
            "Slide while holding" to "hold speed change slide video player",
            "Double tap seconds" to "skip forward back 5 10 15 20 video player",
            "Seek swipe speed" to "slow fast sensitivity horizontal swipe video player",
            "Volume boost" to "louder over 100 amplify loudness video player",
            "Saved brightness" to "screen light system video player",
            "Default fit" to "fill stretch crop aspect picture size video player",
            "Keep speed" to "remember playback speed next video player",
            "Default quality" to "resolution height 1080 720 480 video player",
            "App lock" to "fingerprint biometric pin password security",
            "Hide private tabs in app preview" to "incognito recents screenshot blank preview secure",
            "Lock private tabs" to "incognito biometric fingerprint pin unlock return",
            "Close all private tabs" to "incognito close wipe clear",
            "Site permissions" to "camera microphone location mic",
            "Suspend background tabs" to "memory ram free sleep inactive tabs unload",
            "Site settings" to "site host website per-site javascript desktop zoom text size permissions reset",
            "HTTPS-only mode" to "secure ssl http upgrade",
            "Hosts and filters" to "adblock block host rules filter list allow whitelist easylist custom test url",
            "Ad blocker" to "ads popup pop-up tracker adblock block",
            "Userscripts" to "tampermonkey greasemonkey script userscript js inject add paste match include",
            "Run userscripts" to "userscripts master switch off on",
            "Userscripts in private tabs" to "incognito private userscript",
            "Hide ad spaces on pages" to "ads empty space cosmetic",
            "Hide cookie banners" to "consent gdpr popup cookies",
            "Auto backup (daily)" to "save restore",
            "Export data" to "backup save file bookmarks",
            "Import data" to "restore backup file",
            "Recently closed tabs" to "reopen history undo",
            "Saved pages" to "saved pages offline mht save page copy archive",
            "Clear browsing history" to "delete history privacy",
            "Clear cookies and site data" to "logout delete storage",
            "Clear cache" to "delete temporary files storage space",
            "Saved passwords" to "logins credentials vault",
            "Password generator" to "random strong",
            "Create email alias" to "addy.io mask spam",
            "Extensions" to "addons plugins chrome crx zip",
            "How to use" to "help guide tutorial tour start new user learn",
            "Check for updates" to "upgrade version new release",
            "Version history" to "revert rollback downgrade older previous release",
            "Novel grabber" to "epub docx web2epub book",
            "Report center" to "diagnose problem error crash log"
        )

        private val SUB_FOLD = 14 // S48: rows per category before sub groups fold
        private val NOFOLD = setOf("privacy") // 0.0.320: 19 rows after the 0.0.312 merge made its groups fold; short clear groups read better than hidden ones
        /** 0.0.312: these two switches already sit in the quick row at the top, so the lists do not show them a second time. Searching still finds them. */
        private val DUP = setOf("Hide bar while scrolling", "Reduce motion")
        private var rowTint: Int? = null // 0.0.313: colour of the icon tiles of the category being built

        /** 0.0.313: one row = one soft tile. First / last tile of a group get big corners, the ones between small corners (Chrome Material 3 Expressive look). */
        private fun tileBg(first: Boolean, last: Boolean): GradientDrawable {
            val big = dp(18).toFloat()
            val small = dp(6).toFloat()
            val top = if (first) big else small
            val bot = if (last) big else small
            return GradientDrawable().apply {
                setColor(ColorUtils.blendARGB(act.getColor(R.color.mg_surface), act.getColor(R.color.mg_text), 0.06f))
                cornerRadii = floatArrayOf(top, top, top, top, bot, bot, bot, bot)
            }
        }

        private fun tile(row: View, first: Boolean, last: Boolean): View = FrameLayout(act).apply {
            background = tileBg(first, last)
            clipToOutline = true
            addView(row, FrameLayout.LayoutParams(MATCH, WRAP))
        }

        private fun tileLp() = LinearLayout.LayoutParams(MATCH, WRAP).apply { marginStart = dp(8); marginEnd = dp(8); topMargin = dp(2) }

        private fun dd(items: List<Any>): List<Any> {
            if (query.isNotBlank()) return items
            val kept = ArrayList<Any>()
            for (x in items) if (!(x is Row && (x.label in DUP || (x.label == "Lock after" && !AppLock.on(act))))) kept.add(x) // 0.0.324: "Lock after" only while App lock is ON
            val out = ArrayList<Any>()
            for (i in kept.indices) {
                val x = kept[i]
                if (x is Sub && (i == kept.lastIndex || kept[i + 1] is Sub)) continue // no empty group label
                out.add(x)
            }
            return out
        }
        private val cats = LinkedHashMap<String, Pair<View, View>>() // key -> (content, chevron)

        private fun dp(v: Int) = Util.dp(act, v)

        init {
            setOnDismissListener { if (current === this) current = null }
        }

        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)
            build()
            window?.let {
                it.setLayout(MATCH, MATCH)
                it.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
                it.setBackgroundDrawable(ColorDrawable(act.getColor(R.color.mg_bg)))
                if (!TabPrefs.reduce(act)) it.setWindowAnimations(R.style.MgbSettingsWindow) // V13: soft rise + fade, Reduce motion = none
            }
        }

        /** V13: the body glides in from the side (forward) or back; no motion when Reduce motion is ON. */
        private fun glide(forward: Boolean) {
            val col = body ?: return
            if (TabPrefs.reduce(act)) return
            col.animate().cancel()
            col.alpha = 0f
            col.translationX = dp(if (forward) 28 else -28).toFloat()
            col.animate().alpha(1f).translationX(0f).setDuration(240)
                .setInterpolator(android.view.animation.PathInterpolator(0.2f, 0f, 0f, 1f)).start()
        }

        private fun round(color: Int, r: Int) = GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(r).toFloat()
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
                text = "Settings"
                textSize = 22f // 0.0.129: left aligned larger title
                gravity = Gravity.CENTER_VERTICAL or Gravity.START
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                setTextColor(act.getColor(R.color.mg_text))
                setPadding(dp(6), 0, 0, 0)
                titleTv = this
            }, LinearLayout.LayoutParams(0, WRAP, 1f))
            root.addView(head, LinearLayout.LayoutParams(MATCH, dp(56)))

            // S48: search box (outside the ScrollView so it survives fill())
            val sbox = LinearLayout(act).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = round(act.getColor(R.color.mg_surface), 28).apply { // 0.0.129: pill; S5: same hairline as the cards
                    setStroke(dp(1), ColorUtils.setAlphaComponent(act.getColor(R.color.mg_outline), 0x99))
                }
                setPadding(dp(14), 0, dp(6), 0)
            }
            sbox.addView(ImageView(act).apply {
                setImageResource(R.drawable.ic_search)
                setColorFilter(act.getColor(R.color.mg_text_dim))
            }, LinearLayout.LayoutParams(dp(20), dp(20)).apply { marginEnd = dp(10) })
            val clear = ImageView(act).apply {
                setImageResource(R.drawable.ic_close)
                setColorFilter(act.getColor(R.color.mg_text_dim))
                setPadding(dp(10), dp(10), dp(10), dp(10))
                contentDescription = "Clear search"
                visibility = View.GONE
            }
            val edit = EditText(act).apply {
                hint = "Search settings"
                textSize = 15f
                setSingleLine(true)
                background = null
                imeOptions = EditorInfo.IME_ACTION_SEARCH
                setTextColor(act.getColor(R.color.mg_text))
                setHintTextColor(act.getColor(R.color.mg_text_dim))
                setPadding(0, 0, 0, 0)
                addTextChangedListener(object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) { }
                    override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) { }
                    override fun afterTextChanged(s: Editable?) {
                        query = s?.toString() ?: ""
                        clear.visibility = if (query.isEmpty()) View.GONE else View.VISIBLE
                        if (query.isNotBlank() && subPage != null) { subPage = null; applyPage() }
                        fill()
                        sv?.scrollTo(0, 0)
                    }
                })
                setOnEditorActionListener { v, _, _ ->
                    v.clearFocus()
                    (act.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
                        ?.hideSoftInputFromWindow(v.windowToken, 0)
                    true
                }
            }
            searchEdit = edit
            sboxView = sbox
            clear.setOnClickListener { edit.setText("") }
            sbox.addView(edit, LinearLayout.LayoutParams(0, dp(48), 1f))
            sbox.addView(clear, LinearLayout.LayoutParams(dp(40), dp(40)))
            root.addView(sbox, LinearLayout.LayoutParams(MATCH, dp(48)).apply {
                marginStart = dp(16); marginEnd = dp(16); topMargin = dp(2); bottomMargin = dp(8)
            })

            val col = LinearLayout(act).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16), dp(4), dp(16), dp(32))
            }
            body = col
            val sv = ScrollView(act)
            this.sv = sv
            sv.addView(col, ViewGroup.LayoutParams(MATCH, WRAP))
            sv.isVerticalScrollBarEnabled = false // 0.0.285
            val hair = View(act).apply { setBackgroundColor(act.getColor(R.color.mg_outline)) }
            root.addView(hair, LinearLayout.LayoutParams(MATCH, dp(1))) // 0.0.285: fades in while the list is scrolled
            SettingsFx.hairline(act, sv, hair)
            root.addView(sv, LinearLayout.LayoutParams(MATCH, 0, 1f))
            setContentView(root)
            fill()
            SettingsFx.rise(act, col) // 0.0.285: cards glide in one after another
        }

        private class Row(val icon: Int, val label: String, val value: String = "", val hint: String = "", val onClick: (() -> Unit)? = null)

        private class Sub(val title: String)

        private fun switchFor(on: Boolean) = ThazirSwitch(act).apply { // APPEARANCE A1
            isChecked = on
            isClickable = false
            isFocusable = false
        }

        /** 0.0.440: Command Center opened Settings for one setting: type its name in the search box (the normal search then shows it). */
        fun preSearch(q: String) { searchEdit?.setText(q) }

        private fun subLabel(title: String) = TextView(act).apply {
            text = title.uppercase()
            textSize = 11f
            letterSpacing = 0.08f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL) // 0.0.129: accent group label
            setTextColor(act.getColor(R.color.mg_primary))
            setPadding(dp(18), dp(14), dp(16), dp(4))
        }

        /** 0.0.442: what OFF does, added to the hint of a switch row when the hint is short (max 60 chars) and does not say it already. */
        private val OFFTXT = mapOf(
            "Hide bar while scrolling" to "OFF = bar always stays",
            "Pull down to refresh" to "OFF = use the reload button",
            "Address suggestions" to "OFF = no suggestions",
            "Video button on pages" to "OFF = no video button",
            "Grabber button on pages" to "OFF = no floating button",
            "Grabber button on video players" to "OFF = no button on videos",
            "Extra native download button" to "OFF = no second button",
            "Player gestures" to "OFF = no swipe or hold gestures",
            "Keep speed" to "OFF = speed resets for each video",
            "App lock" to "OFF = app opens without asking",
            "HTTPS-only mode" to "OFF = http pages open as they are",
            "Hide private tabs in app preview" to "OFF = Recents shows the page",
            "Lock private tabs" to "OFF = private tabs open freely",
            "Ad blocker" to "OFF = ads and trackers load",
            "Hide ad spaces on pages" to "OFF = empty ad boxes stay",
            "Hide cookie banners" to "OFF = cookie popups show",
            "Auto backup (daily)" to "OFF = no daily file",
            "Run userscripts" to "OFF = no userscript runs",
            "Userscripts in private tabs" to "OFF = not in private tabs",
            "Smarter login detection" to "OFF = basic detection",
            "Sub-folders in the chosen folder" to "OFF = all files in one folder"
        )

        private fun hintOf(r: Row): String { // Step B; 0.0.442: + what OFF does
            val base = r.hint.ifEmpty { HINTS[r.label] ?: "" }
            val off = OFFTXT[r.label]
            return if (off != null && (r.value == "ON" || r.value == "OFF") && base.length <= 60 && !base.contains("OFF")) (if (base.isEmpty()) off else "$base. $off") else base
        }

        /** 0.0.280: a finger on a row / header makes it 2 % smaller for a moment (ripple stays). Reduce motion = nothing. Never eats the touch. */
        private fun pressFx(v: View) {
            if (TabPrefs.reduce(act)) return
            v.setOnTouchListener { view, ev ->
                when (ev.actionMasked) {
                    android.view.MotionEvent.ACTION_DOWN -> view.animate().scaleX(0.98f).scaleY(0.98f).setDuration(90).start()
                    android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> view.animate().scaleX(1f).scaleY(1f).setDuration(140).start()
                }
                false
            }
        }

        private fun rowView(r: Row): View {
            val line = LinearLayout(act).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(52)
                setPadding(dp(12), dp(6), dp(14), dp(6)) // 0.0.313: tiles are inset 8 dp, so less padding inside
                if (r.onClick != null) {
                    isClickable = true
                    isFocusable = true
                    val out = TypedValue()
                    if (act.theme.resolveAttribute(android.R.attr.selectableItemBackground, out, true)) {
                        foreground = act.getDrawable(out.resourceId)
                    }
                    pressFx(this) // 0.0.280
                    setOnClickListener {
                        if (r.value == "ON" || r.value == "OFF") it.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK) // S21f
                        if (r.value.isNotEmpty() && r.value != "ON" && r.value != "OFF") { valFlashLabel = r.label; valFlashAt = android.os.SystemClock.uptimeMillis() } // 0.0.280
                        if (r.value.isNotEmpty() && r.label !in NO_RECENT) noteRecent(r.label) // Step A
                        if (r.value == "ON" || r.value == "OFF") { flashLabel = r.label; flashAt = android.os.SystemClock.uptimeMillis() } // Step B
                        r.onClick.invoke()
                        if (r.label in UNDO_OK && (r.value == "ON" || r.value == "OFF")) { // 0.0.443: Undo = the same toggle once more
                            val was = r.value
                            BmUndo.show(findViewById<View>(android.R.id.content), SettingsIndex.name(r.label) + " turned " + (if (was == "ON") "OFF" else "ON")) { r.onClick.invoke() }
                        }
                        if (r.label in RESTART_BAR) restartBar() // Step C
                    }
                }
            }
            val tn = rowTint
            if (tn != null) { // 0.0.313: icon in a small tile of the category colour
                val ib = FrameLayout(act).apply {
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.RECTANGLE
                        cornerRadius = dp(10).toFloat()
                        setColor(ColorUtils.setAlphaComponent(tn, 0x26))
                    }
                }
                ib.addView(ImageView(act).apply {
                    setImageResource(r.icon)
                    setColorFilter(tn)
                }, FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER))
                line.addView(ib, LinearLayout.LayoutParams(dp(34), dp(34)).apply { marginEnd = dp(14) })
            } else {
                line.addView(ImageView(act).apply {
                    setImageResource(r.icon)
                    setColorFilter(act.getColor(R.color.mg_text_dim))
                }, LinearLayout.LayoutParams(dp(22), dp(22)).apply { marginEnd = dp(16) })
            }
            val labelBox = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
            labelBox.addView(TextView(act).apply {
                text = SettingsIndex.name(r.label) // 0.0.441: friendlier name, label stays the key
                textSize = 15f
                maxLines = 2
                setTextColor(act.getColor(R.color.mg_text))
            })
            val hintText = hintOf(r)
            if (hintText.isNotEmpty()) labelBox.addView(TextView(act).apply { // S64: one line of help under the title
                text = hintText
                textSize = 12f
                maxLines = 3
                setTextColor(act.getColor(R.color.mg_text_dim))
            })
            line.addView(labelBox, LinearLayout.LayoutParams(0, WRAP, 1f))
            if (r.label in RESTART) line.addView(ImageView(act).apply { // Step C: restart needed
                setImageResource(R.drawable.ic_reload)
                setColorFilter(act.getColor(R.color.mg_text_dim))
                alpha = 0.7f
                contentDescription = "Restart needed"
            }, LinearLayout.LayoutParams(dp(16), dp(16)).apply { marginStart = dp(6) })
            if (r.value == "ON" || r.value == "OFF") {
                val sw = switchFor(r.value == "ON")
                line.addView(sw, LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = dp(8) })
                // Step B: the row just toggled gets a short highlight that fades, and the thumb slides
                if (flashLabel == r.label && android.os.SystemClock.uptimeMillis() - flashAt < 1500 && !TabPrefs.reduce(act)) {
                    flashLabel = null
                    val d = ColorDrawable(act.getColor(R.color.mg_primary))
                    d.alpha = 0x40
                    line.background = d
                    ValueAnimator.ofInt(0x40, 0).setDuration(700).apply {
                        addUpdateListener { d.alpha = it.animatedValue as Int }
                        addListener(object : android.animation.AnimatorListenerAdapter() {
                            override fun onAnimationEnd(a: android.animation.Animator) { line.background = null }
                        })
                    }.start()
                    val on = r.value == "ON"
                    sw.isChecked = !on
                    sw.post { sw.isChecked = on }
                }
            } else if (r.value.isNotEmpty()) {
                val vt = TextView(act).apply {
                    text = r.value
                    textSize = 13f
                    setPadding(dp(8), 0, 0, 0)
                    setTextColor(act.getColor(if (r.value.startsWith("ON")) R.color.mg_primary else R.color.mg_text_dim))
                }
                line.addView(vt)
                // 0.0.280: the value that just changed slides up 6 dp and fades in (180 ms), once. Reduce motion = nothing.
                if (valFlashLabel == r.label && android.os.SystemClock.uptimeMillis() - valFlashAt < 1500 && !TabPrefs.reduce(act)) {
                    valFlashLabel = null
                    vt.alpha = 0f
                    vt.translationY = dp(6).toFloat()
                    vt.animate().alpha(1f).translationY(0f).setDuration(180).start()
                }
            }
            return line
        }

        /** S46: one collapsible category card. items = Row or Sub (small group label). */
        private fun category(key: String, icon: Int, title: String, subtitle: String, items: List<Any>) {
            val col = body ?: return
            // S48: search filter. A title match keeps the whole category, else only rows whose label matches.
            val q = query.trim()
            val searching = q.isNotEmpty()
            val sub = !searching && subPage == key // Step A: this category is the open page
            if (!searching && subPage != null && !sub) return
            val link = !searching && !sub && BIG.containsKey(key) // Step A: big category = one row that opens a page
            if (link) return // 0.0.312: the hub card (Downloads and player) draws these links
            var shown = dd(items) // 0.0.312
            if (searching && !title.contains(q, true)) {
                val out = ArrayList<Any>()
                var pend: Sub? = null
                for (item in items) {
                    when (item) {
                        is Sub -> pend = item
                        is Row -> if (item.label.contains(q, true) || SettingsIndex.name(item.label).contains(q, true) || hintOf(item).contains(q, true) || (KW[item.label]?.contains(q, true) == true)) { // S21f: search keywords
                            pend?.let { out.add(it) }
                            pend = null
                            out.add(item)
                        }
                    }
                }
                if (out.isEmpty()) return
                shown = out
            }
            if (!searching) cardNames[key] = shown.filterIsInstance<Row>().filter { it.label != "Reset to default" && (it.value == "ON" || it.value == "OFF" || it.value.isNotEmpty()) }.map { SettingsIndex.name(it.label) } // 0.0.442: for the Reset dialog
            val fold = !searching && !sub && key !in NOFOLD && shown.count { it is Row } > SUB_FOLD // 0.0.320: privacy keeps plain group labels
            val isOpen = searching || sub || (!link && openCat == key)
            val card = LinearLayout(act).apply {
                orientation = LinearLayout.VERTICAL
                background = round(act.getColor(R.color.mg_surface), 20).apply { // 0.0.129: hairline outline
                    setStroke(dp(1), ColorUtils.setAlphaComponent(act.getColor(R.color.mg_outline), 0x99))
                }
            }
            val chev = ImageView(act).apply {
                setImageResource(R.drawable.ic_chevron)
                setColorFilter(act.getColor(R.color.mg_text_dim))
                rotation = if (isOpen) 90f else 0f
            }
            val content = LinearLayout(act).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, 0, 0, dp(8))
                visibility = if (isOpen) View.VISIBLE else View.GONE
            }
            val nightR = (act.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES
            rowTint = TabPrefs.tint(key, nightR) // 0.0.313: no long dividers any more, rows are tiles with gaps
            var box: LinearLayout? = null // S48: current folded sub group
            var firstSub = true
            var prevRow = false // 0.0.285: thin divider between two rows
            val list: List<Any> = if (link) emptyList() else shown
            for ((idx, item) in list.withIndex()) {
                when (item) {
                    is Sub -> {
                        prevRow = false
                        if (fold) {
                            // 0.0.439: group header = tinted row with row count + chevron (was a tiny "▸ TITLE" text); smooth open / close
                            var n = 0
                            for (j in idx + 1 until list.size) { if (list[j] is Sub) break; if (list[j] is Row) n++ }
                            val b = LinearLayout(act).apply {
                                orientation = LinearLayout.VERTICAL
                                visibility = if (firstSub) View.VISIBLE else View.GONE
                            }
                            val gTint = TabPrefs.tint(key, nightR)
                            val arrow = ImageView(act).apply {
                                setImageResource(R.drawable.ic_chevron)
                                setColorFilter(act.getColor(R.color.mg_text_dim))
                                rotation = if (firstSub) 90f else 0f
                            }
                            val gh = LinearLayout(act).apply {
                                orientation = LinearLayout.HORIZONTAL
                                gravity = Gravity.CENTER_VERTICAL
                                minimumHeight = dp(48)
                                setPadding(dp(14), dp(6), dp(12), dp(6))
                                background = round(ColorUtils.setAlphaComponent(gTint, 0x1F), 14)
                                isClickable = true
                                isFocusable = true
                                contentDescription = item.title + ", " + n + " settings"
                                val out2 = TypedValue()
                                if (act.theme.resolveAttribute(android.R.attr.selectableItemBackground, out2, true)) foreground = act.getDrawable(out2.resourceId)
                                addView(TextView(act).apply {
                                    text = item.title
                                    textSize = 14f
                                    typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                                    setTextColor(act.getColor(R.color.mg_text))
                                }, LinearLayout.LayoutParams(0, WRAP, 1f))
                                addView(TextView(act).apply {
                                    text = n.toString()
                                    textSize = 12f
                                    setTextColor(act.getColor(R.color.mg_text_dim))
                                }, LinearLayout.LayoutParams(WRAP, WRAP).apply { marginEnd = dp(8) })
                                addView(arrow, LinearLayout.LayoutParams(dp(20), dp(20)))
                            }
                            gh.setOnClickListener {
                                val open = b.visibility != View.VISIBLE
                                val calm2 = TabPrefs.reduce(act)
                                if (!calm2) try {
                                    val ease = android.view.animation.PathInterpolator(0.2f, 0f, 0f, 1f)
                                    TransitionManager.beginDelayedTransition(content, TransitionSet().apply {
                                        ordering = TransitionSet.ORDERING_TOGETHER
                                        addTransition(ChangeBounds().apply { duration = 220; interpolator = ease })
                                        addTransition(Fade().apply { duration = 160; interpolator = ease })
                                    })
                                } catch (_: Exception) { }
                                b.visibility = if (open) View.VISIBLE else View.GONE
                                arrow.animate().cancel()
                                if (calm2) arrow.rotation = if (open) 90f else 0f else arrow.animate().rotation(if (open) 90f else 0f).setDuration(200).start()
                                androidx.core.view.ViewCompat.setStateDescription(gh, if (open) "Expanded" else "Collapsed")
                            }
                            androidx.core.view.ViewCompat.setStateDescription(gh, if (firstSub) "Expanded" else "Collapsed")
                            content.addView(gh, LinearLayout.LayoutParams(MATCH, WRAP).apply { marginStart = dp(8); marginEnd = dp(8); topMargin = dp(if (firstSub) 4 else 8); bottomMargin = dp(2) })
                            content.addView(b, LinearLayout.LayoutParams(MATCH, WRAP))
                            box = b
                            firstSub = false
                        } else {
                            content.addView(subLabel(item.title), LinearLayout.LayoutParams(MATCH, WRAP))
                        }
                    }
                    is Row -> {
                        val first = idx == 0 || list[idx - 1] !is Row
                        val last = idx == list.lastIndex || list[idx + 1] !is Row
                        prevRow = true
                        (box ?: content).addView(tile(rowView(item), first, last), tileLp())
                    }
                }
            }
            if (!link && !searching && RESET.containsKey(key)) { // Step C: Reset to default, last row of the card
                content.addView(tile(rowView(Row(R.drawable.ic_reload, "Reset to default", "", "Only the settings of this card") { resetCard(key, title) }), true, true),
                    tileLp().apply { topMargin = dp(8) })
            }
            val header = LinearLayout(act).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(16), dp(12), dp(14), dp(12))
                isClickable = true
                isFocusable = true
                contentDescription = "$title, $subtitle" // S5
                if (!link) androidx.core.view.ViewCompat.setStateDescription(this, if (isOpen) "Expanded" else "Collapsed") // S5
                val out = TypedValue()
                if (act.theme.resolveAttribute(android.R.attr.selectableItemBackground, out, true)) {
                    foreground = act.getDrawable(out.resourceId)
                }
                pressFx(this) // 0.0.280
                setOnClickListener { if (link) openPage(key) else toggle(key, card) }
            }
            val night = (act.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES
            val tint = TabPrefs.tint(key, night) // Step B: own soft tint per category
            SettingsFx.ring(card, tint, isOpen && !link, act) // 0.0.285: open card = own colour ring, closed = clearer hairline
            val badge = FrameLayout(act).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE // 0.0.129: squircle badge
                    cornerRadius = dp(13).toFloat()
                    setColor(ColorUtils.setAlphaComponent(tint, 0x2B))
                }
            }
            badge.addView(ImageView(act).apply {
                setImageResource(icon)
                setColorFilter(tint)
            }, FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER))
            header.addView(badge, LinearLayout.LayoutParams(dp(42), dp(42)).apply { marginEnd = dp(14) })
            val texts = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
            texts.addView(TextView(act).apply {
                text = title
                textSize = 16f
                maxLines = 1
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setTextColor(act.getColor(R.color.mg_text))
            })
            texts.addView(TextView(act).apply {
                text = subtitle
                textSize = 12f
                maxLines = 1
                setTextColor(act.getColor(R.color.mg_text_dim))
            })
            header.addView(texts, LinearLayout.LayoutParams(0, WRAP, 1f))
            header.addView(chev, LinearLayout.LayoutParams(dp(24), dp(24)))
            if (!sub) card.addView(header, LinearLayout.LayoutParams(MATCH, WRAP))
            if (!link) card.addView(content, LinearLayout.LayoutParams(MATCH, WRAP))
            col.addView(card, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(12) })
            if (!link) cats[key] = Pair(content, chev)
        }

        /** P13: single choice dialog for the Player card. set(index) saves, then the card is rebuilt. */
        private fun pickPl(title: String, labels: Array<String>, cur: Int, set: (Int) -> Unit) {
            MaterialAlertDialogBuilder(act).setTitle(title)
                .setSingleChoiceItems(labels, cur) { d, i -> set(i); d.dismiss(); fill() }
                .setNegativeButton("Cancel", null).show()
        }

        // ---- Step C: utility rows ----
        private val cardNames = HashMap<String, List<String>>() // 0.0.442: settings shown in each card (names for the Reset dialog)

        private fun resetCard(key: String, title: String) {
            val keys = RESET[key] ?: return
            confirm("Reset \"$title\" to default? Only the settings of this card change" + (cardNames[key]?.takeIf { it.isNotEmpty() }?.let { n -> ", for example: " + n.take(6).joinToString(", ") + (if (n.size > 6) " and " + (n.size - 6) + " more" else "") } ?: "") + ". Bookmarks, passwords and files are not touched.", "Reset") {
                try {
                    val themeBefore = UiMode.mode(act)
                    val lookBefore = AccentTheme.id(act) + "|" + SurfaceStyle.id(act) // 0.0.423: accent / surface reset needs a restart too
                    val barBefore = AppearancePrefs.barPos(act) // A9: bar position needs a restart
                    if (key == "grabber" && V77Alt.on(act)) V77Alt.set(act, false) // restores the saved mode first
                    val ed = act.getSharedPreferences("mgb_ui", android.content.Context.MODE_PRIVATE).edit()
                    for (k in keys) ed.remove(k)
                    ed.apply()
                    if (ExtPriv.KEY in keys) { ExtPriv.reload(); try { ContentInjector.refresh(act) } catch (_: Throwable) { } } // 0.0.438
                    if (key == "grabber") DlMode.apply(act, DlMode.SMART) // writes the default button switches
                    if (key == "general") {
                        Orient.apply(act)
                        UiMode.apply(act)
                        AccentTheme.reload(); SurfaceStyle.reload() // 0.0.423: the cached choice was stale after the reset (accent stayed until restart)
                        BookmarksScreen.tabsRef?.applyDarkPages()
                        SocialBars.refresh(act, BookmarksScreen.tabsRef?.active) // 0.0.424: page colour switch is OFF again = original bars
                    }
                    Util.toast(act, "$title reset to default")
                    if (key == "general" && (themeBefore != 0 || barBefore != "bottom" || lookBefore != AccentTheme.id(act) + "|" + SurfaceStyle.id(act))) { dismiss(); UiMode.smoothRecreate(act) } else fill() // 0.0.422
                } catch (e: Exception) {
                    Log.e(TAG, "reset failed", e)
                    Util.toast(act, "Could not reset")
                }
            }
        }

        /** 0.0.312: Downloads, Grabber and Player in ONE card (each row opens its page, same pages as before). */
        private fun hub() {
            val col = body ?: return
            val night = (act.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES
            val tint = TabPrefs.tint("downloads", night)
            rowTint = tint // 0.0.313
            val card = LinearLayout(act).apply {
                orientation = LinearLayout.VERTICAL
                background = round(act.getColor(R.color.mg_surface), 20).apply {
                    setStroke(dp(1), ColorUtils.setAlphaComponent(act.getColor(R.color.mg_outline), 0x99))
                }
            }
            val header = LinearLayout(act).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(16), dp(12), dp(14), dp(8))
                contentDescription = "Downloads and player"
            }
            val badge = FrameLayout(act).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dp(13).toFloat()
                    setColor(ColorUtils.setAlphaComponent(tint, 0x2B))
                }
            }
            badge.addView(ImageView(act).apply {
                setImageResource(R.drawable.ic_download)
                setColorFilter(tint)
            }, FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER))
            header.addView(badge, LinearLayout.LayoutParams(dp(42), dp(42)).apply { marginEnd = dp(14) })
            val texts = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
            texts.addView(TextView(act).apply {
                text = "Downloads and player"
                textSize = 16f
                maxLines = 1
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setTextColor(act.getColor(R.color.mg_text))
            })
            texts.addView(TextView(act).apply {
                text = "Library, Grabber, video player"
                textSize = 12f
                maxLines = 1
                setTextColor(act.getColor(R.color.mg_text_dim))
            })
            header.addView(texts, LinearLayout.LayoutParams(0, WRAP, 1f))
            card.addView(header, LinearLayout.LayoutParams(MATCH, WRAP))
            val links = listOf(
                Row(R.drawable.ic_download, "Downloads", "", "Files, queue, quality, folder, rules") { openPage("downloads") },
                Row(R.drawable.ic_settings, "Grabber and buttons", "", "Engine, download buttons, V82 backup") { openPage("grabber") },
                Row(R.drawable.ic_pl_speed, "Player", "", "Gestures, speed, defaults") { openPage("player") }
            )
            for (i in links.indices) {
                val line = rowView(links[i]) as LinearLayout
                line.addView(ImageView(act).apply {
                    setImageResource(R.drawable.ic_chevron)
                    setColorFilter(act.getColor(R.color.mg_text_dim))
                }, LinearLayout.LayoutParams(dp(20), dp(20)).apply { marginStart = dp(8) })
                card.addView(tile(line, i == 0, i == links.lastIndex), tileLp())
            }
            card.addView(View(act), LinearLayout.LayoutParams(MATCH, dp(8)))
            SettingsFx.ring(card, tint, false, act)
            col.addView(card, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(12) })
        }

        private fun isDefaultBrowser(): Boolean = try {
            val i = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("http://example.com"))
            act.packageManager.resolveActivity(i, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName == act.packageName
        } catch (_: Exception) { false }

        /** Android 10+: the "Default apps" page of the phone, else the app details page. */
        private fun openDefaultBrowser() {
            val details = android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                android.net.Uri.parse("package:" + act.packageName))
            val first = if (android.os.Build.VERSION.SDK_INT >= 29)
                android.content.Intent(android.provider.Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS) else details
            try { act.startActivity(first) } catch (e: Exception) {
                try { act.startActivity(details) } catch (e2: Exception) { Util.toast(act, "Could not open phone settings") }
            }
        }

        private fun restartBar() {
            try {
                val v = findViewById<View>(android.R.id.content) ?: return
                Snackbar.make(v, "Restart needed to apply this fully", 8000) // 0.0.443: 8 s (was about 3 s), theme colours
                    .setAction("Apply now") { dismiss(); UiMode.smoothRecreate(act) } // 0.0.422
                    .setBackgroundTint(act.getColor(R.color.mg_surface_variant))
                    .setTextColor(act.getColor(R.color.mg_text))
                    .setActionTextColor(act.getColor(R.color.mg_primary))
                    .show()
            } catch (_: Exception) { }
        }

        // ---- Step A: sub-pages and Recent ----
        private fun applyPage() {
            titleTv?.text = subPage?.let { BIG[it] } ?: "Settings"
            sboxView?.visibility = if (subPage == null) View.VISIBLE else View.GONE
        }

        private fun openPage(key: String) {
            savedY = sv?.scrollY ?: 0
            subPage = key
            forceY = 0
            applyPage()
            fill()
            glide(true) // V13
        }

        /** Back order: sub-page -> Settings -> close. */
        private fun goBack() {
            if (subPage != null) {
                subPage = null
                forceY = savedY
                applyPage()
                fill()
                glide(false) // V13
            } else dismiss()
        }

        override fun onBackPressed() { goBack() }

        private fun recents(prefs: android.content.SharedPreferences): List<String> =
            (prefs.getString("recent_settings", "") ?: "").split("\n").filter { it.isNotBlank() }

        private fun noteRecent(label: String) {
            try {
                val prefs = act.getSharedPreferences("mgb_ui", android.content.Context.MODE_PRIVATE)
                val list = ArrayList<String>()
                list.add(label)
                for (x in recents(prefs)) if (x != label && list.size < 5) list.add(x)
                prefs.edit().putString("recent_settings", list.joinToString("\n")).apply()
            } catch (_: Exception) { }
        }

        private fun recentRow(prefs: android.content.SharedPreferences): View? {
            val list = recents(prefs)
            if (list.isEmpty()) return null
            val box = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
            // S10+: header row = RECENT label + Clear (removes only the recent_settings list, nothing else)
            val head = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            head.addView(TextView(act).apply {
                text = "RECENT"
                textSize = 11f
                letterSpacing = 0.08f
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setTextColor(act.getColor(R.color.mg_primary))
                setPadding(dp(4), 0, 0, 0)
            }, LinearLayout.LayoutParams(0, WRAP, 1f))
            head.addView(TextView(act).apply {
                text = "Clear"
                textSize = 12f
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setTextColor(act.getColor(R.color.mg_primary))
                gravity = Gravity.CENTER
                minimumHeight = dp(44)
                setPadding(dp(12), 0, dp(8), 0)
                isClickable = true
                contentDescription = "Clear recent settings"
                setOnClickListener {
                    it.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
                    try { prefs.edit().remove("recent_settings").apply() } catch (_: Exception) { }
                    fill()
                }
            }, LinearLayout.LayoutParams(WRAP, WRAP))
            box.addView(head, LinearLayout.LayoutParams(MATCH, WRAP))
            val hs = HorizontalScrollView(act).apply { isHorizontalScrollBarEnabled = false }
            val chips = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL }
            for (l in list) chips.addView(TextView(act).apply {
                text = l
                textSize = 13f
                maxLines = 1
                setTextColor(act.getColor(R.color.mg_text))
                setPadding(dp(14), dp(8), dp(14), dp(8))
                background = round(act.getColor(R.color.mg_surface), 20).apply {
                    setStroke(dp(1), ColorUtils.setAlphaComponent(act.getColor(R.color.mg_outline), 0x99))
                }
                isClickable = true
                setOnClickListener { searchEdit?.setText(l) } // shows that setting through search
            }, LinearLayout.LayoutParams(WRAP, WRAP).apply { marginEnd = dp(8) })
            hs.addView(chips)
            box.addView(hs, LinearLayout.LayoutParams(MATCH, WRAP))
            return box
        }

        /** S46: open one category, close the others (animated). */
        private fun toggle(key: String, card: View) {
            val col = body ?: return
            if (query.isNotBlank()) return // S48: all matches stay open while searching
            val opening = openCat != key
            val calm = TabPrefs.reduce(act) // Step B: Reduce motion = no slide, no rotate animation
            if (!calm) try {
                // V13: one transition owns the layout change: cards glide (bounds) while the content fades (no flying slide)
                val ease = android.view.animation.PathInterpolator(0.2f, 0f, 0f, 1f)
                val fadeIn = Fade(Fade.IN).apply { duration = 200; startDelay = 80; interpolator = ease }
                val fadeOut = Fade(Fade.OUT).apply { duration = 110; interpolator = ease }
                for ((_, v) in cats) { fadeIn.addTarget(v.first); fadeOut.addTarget(v.first) }
                val ts = TransitionSet().apply {
                    ordering = TransitionSet.ORDERING_TOGETHER
                    addTransition(ChangeBounds().apply { duration = 280; interpolator = ease })
                    addTransition(fadeOut)
                    addTransition(fadeIn)
                }
                TransitionManager.beginDelayedTransition(col, ts)
            } catch (_: Exception) { }
            openCat = if (opening) key else null
            for ((k, v) in cats) {
                val show = k == openCat
                SettingsFx.ring(v.first.parent as? View, SettingsFx.tintOf(act, k), show, act) // 0.0.285
                v.first.visibility = if (show) View.VISIBLE else View.GONE
                val deg = if (show) 90f else 0f
                v.second.animate().cancel()
                if (calm) v.second.rotation = deg else v.second.animate().rotation(deg).setDuration(260).setInterpolator(android.view.animation.PathInterpolator(0.2f, 0f, 0f, 1f)).start()
            }
            if (opening) (cats[key]?.first as? ViewGroup)?.let { SettingsFx.rows(act, it) } // 0.0.285: rows follow one after another
            if (opening) card.postDelayed({
                sv?.smoothScrollTo(0, (card.top - dp(8)).coerceAtLeast(0))
            }, if (calm) 0L else 300L)
        }

        private fun hideBar(): Boolean =
            act.getSharedPreferences("mgb_ui", android.content.Context.MODE_PRIVATE).getBoolean("hide_bar", false)

        // MGB S51: V77 settings (chrome.storage.sync) read / written from native
        private fun v77On(key: String, def: Boolean): Boolean =
            try { ChromeBridge.sync.get(key).optBoolean(key, def) } catch (e: Exception) { def }

        private fun v77Set(key: String, v: Boolean) { ChromeBridge.storageSet("sync", org.json.JSONObject().put(key, v)) }

        private fun nativeBtn(): Boolean =
            act.getSharedPreferences("mgb_ui", android.content.Context.MODE_PRIVATE).getBoolean("native_btn", false)

        /** S64: one-line summaries of V77 settings for the rows. */
        private fun syncStr(k: String, def: String): String = try { ChromeBridge.sync.get(k).optString(k, def) } catch (e: Exception) { def }
        private fun syncInt(k: String, def: Int): Int = try { ChromeBridge.sync.get(k).optInt(k, def) } catch (e: Exception) { def }
        private fun subSummary(): String =
            if (!v77On("autoSubs", true)) "OFF" else syncStr("subLangs", "en").ifBlank { "any" } + " · " + syncStr("subFmt", "srt").uppercase()
        private fun engSummary(): String {
            val sp = syncInt("speedLimitKBps", 0)
            return syncInt("maxJobs", 2).toString() + " at a time · " + (if (sp <= 0) "no limit" else if (sp >= 1024) "${sp / 1024} MB/s" else "$sp KB/s")
        }

        private fun ext(page: String) {
            if (!ExtScreen.open(act, ContentInjector.EXT_BASE + page)) Util.toast(act, "Could not open $page")
        }

        private fun confirm(msg: String, yes: String, run: () -> Unit) {
            MaterialAlertDialogBuilder(act)
                .setMessage(msg)
                .setPositiveButton(yes) { _, _ -> run() }
                .setNegativeButton("Cancel", null)
                .show()
        }

        /** (Re)build the categories, used at start and after a toggle. S46: accordion + sub groups. */
        /** S21f: four quick switches above the categories (hidden while searching). */
        private fun quickRow(prefs: android.content.SharedPreferences): View {
            val row = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL }
            fun chip(icon: Int, label: String, on: Boolean, tap: () -> Unit) {
                val c = LinearLayout(act).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                    setPadding(dp(4), dp(12), dp(4), dp(10))
                    background = round(if (on) ColorUtils.setAlphaComponent(act.getColor(R.color.mg_primary), 0x2A) else act.getColor(R.color.mg_surface), 18).apply {
                        setStroke(dp(1), if (on) act.getColor(R.color.mg_primary) else ColorUtils.setAlphaComponent(act.getColor(R.color.mg_outline), 0x99))
                    }
                    isClickable = true
                    setOnClickListener { it.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK); noteRecent(if (label == "Hide bar on scroll") "Hide bar while scrolling" else label); tap(); fill() }
                }
                c.addView(ImageView(act).apply {
                    setImageResource(icon)
                    setColorFilter(act.getColor(if (on) R.color.mg_primary else R.color.mg_text_dim))
                }, LinearLayout.LayoutParams(dp(22), dp(22)))
                c.addView(TextView(act).apply {
                    text = label
                    textSize = 11f
                    gravity = Gravity.CENTER
                    maxLines = 2
                    setPadding(0, dp(6), 0, 0)
                    setTextColor(act.getColor(if (on) R.color.mg_primary else R.color.mg_text))
                })
                row.addView(c, LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginStart = dp(3); marginEnd = dp(3) })
            }
            chip(R.drawable.ic_shield, "Ad blocker", AdBlock.enabled) { AdBlock.toggle() }
            chip(R.drawable.ic_theme, "Dark pages", UiMode.darkPages(act)) {
                UiMode.setDarkPages(act, !UiMode.darkPages(act))
                BookmarksScreen.tabsRef?.applyDarkPages()
            }
            chip(R.drawable.ic_screen, "Hide bar on scroll", prefs.getBoolean("hide_bar", false)) { prefs.edit().putBoolean("hide_bar", !prefs.getBoolean("hide_bar", false)).apply() }
            chip(R.drawable.ic_play, "Reduce motion", TabPrefs.reduceSwitch(act)) { TabPrefs.set(act, "reduce_motion", !TabPrefs.reduceSwitch(act)) }
            return row
        }

        private fun fill() {
            val col = body ?: return
            val keepY = if (forceY >= 0) forceY else (sv?.scrollY ?: 0)
            forceY = -1
            col.removeAllViews()
            cats.clear()
            val tabs = BookmarksScreen.tabsRef
            val prefs = act.getSharedPreferences("mgb_ui", android.content.Context.MODE_PRIVATE)
            val fa = act as? androidx.fragment.app.FragmentActivity

            if (query.trim().isEmpty() && subPage == null) {
                col.addView(Orbit.block(act) { fill() }, LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(8) }) // 0.0.311: Your Orbit (Passport + Smart switches)
                val rec = recentRow(prefs) // Step A
                col.addView(quickRow(prefs), LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = if (rec != null) dp(12) else 0 }) // S21f
                rec?.let { col.addView(it, LinearLayout.LayoutParams(MATCH, WRAP)) } // 0.0.312: Backup card is gone (Passport > Back up now and Data and backup have it)
            }
            category("general", R.drawable.ic_settings, "Browser", "Look, search, tabs, pages", listOf( // 0.0.312: General + Tabs merged
                Sub("Look and feel"),
                Row(R.drawable.ic_theme, "Appearance", UiMode.label(act)) { AppearanceScreen.open(act) }, // APPEARANCE A2; V18: Settings stays underneath, Back returns to it
                Row(R.drawable.ic_edit, "Accessibility", AppearancePrefs.zoomDef(act).takeIf { it != 100 }?.let { "$it %" } ?: "") { AccessScreen.open(act) }, // A2; V18: Settings stays underneath
                Sub("Browsing"),
                Row(R.drawable.ic_search, "Search engine", Util.engineName(act)) {
                    val names = Util.ENGINES.map { it.first }
                    MaterialAlertDialogBuilder(act).setTitle("Search engine")
                        .setItems(names.toTypedArray()) { _, i -> Util.setEngine(act, names[i]); fill() }.show()
                },
                Row(R.drawable.ic_search, "Translate to", Translate.langName(act)) { Translate.pickLang(act) { fill() } }, // S41
                Row(R.drawable.ic_screen, "Default browser", if (isDefaultBrowser()) "Default" else "", "Make MGB open web links") { openDefaultBrowser() }, // Step C
                Sub("Page and screen"), // 0.0.322: Browsing group was 8 mixed rows
                Row(R.drawable.ic_screen, "Hide bar while scrolling", if (hideBar()) "ON" else "OFF") {
                    prefs.edit().putBoolean("hide_bar", !hideBar()).apply()
                    fill()
                },
                Row(R.drawable.ic_reload, "Pull down to refresh", if (PullRefresh.enabled(act)) "ON" else "OFF") { // S45
                    PullRefresh.setEnabled(act, !PullRefresh.enabled(act))
                    fill()
                },
                Row(R.drawable.ic_search, "Address suggestions", if (UrlSuggest.enabled(act)) "ON" else "OFF", "Matching bookmarks and history while you type (not in private tabs)") { // 0.0.377
                    UrlSuggest.setEnabled(act, !UrlSuggest.enabled(act))
                    fill()
                },
                Row(R.drawable.ic_screen, "Rotate browser with phone", if (Orient.rotateBrowser(act)) "ON" else "OFF") { // S35
                    Orient.setRotateBrowser(act, !Orient.rotateBrowser(act))
                    Orient.apply(act)
                    fill()
                },
                Sub("Sites and media"), // 0.0.322
                Row(R.drawable.ic_desktop, "Social sites", if (SocialMode.on(act)) "ON" else "OFF", "Facebook, Reddit, X, Discord and more fit the phone. Switch per site") { SocialScreen.open(act) { fill() } }, // 0.0.224
                Row(R.drawable.ic_play, "Media", "DRM, background play, YouTube") { MediaScreen.open(act) }, // 0.0.296
                Row(R.drawable.ic_close, "Block auto app-open", if (AppNag.on(act)) "ON" else "OFF", "A page that opens TikTok / Instagram / Reddit / Facebook app by itself stays in the browser. A link you tap still opens the app") { // 0.0.416
                    AppNag.setOn(act, !AppNag.on(act))
                    fill()
                },
                Row(R.drawable.ic_desktop, "Reddit 18+ without the app", if (RedditMature.on(act)) "ON" else "OFF", "Mature Content wall on reddit.com opens the same post on old.reddit.com") { // 0.0.416
                    RedditMature.setOn(act, !RedditMature.on(act))
                    fill()
                },
                Sub("Tabs"),
                Row(R.drawable.ic_list, "Swipe a tab card to close it", if (TabPrefs.swipeClose(act)) "ON" else "OFF") {
                    TabPrefs.set(act, "swipe_close", !TabPrefs.swipeClose(act))
                    fill()
                },
                Row(R.drawable.ic_close, "Ask before Close all", if (TabPrefs.confirmClose(act)) "ON" else "OFF", "OFF = tabs close at once and Undo brings them back") {
                    TabPrefs.set(act, "close_confirm", !TabPrefs.confirmClose(act))
                    fill()
                },
                Row(R.drawable.ic_list, "Suspend background tabs", TabManager.suspendLabel(act), "Frees memory, tab reloads when opened") { // STEP E
                    val mins = intArrayOf(0, 5, 15)
                    MaterialAlertDialogBuilder(act).setTitle("Suspend background tabs")
                        .setSingleChoiceItems(arrayOf("Off", "After 5 min", "After 15 min"), mins.indexOf(TabManager.suspendMin(act)).coerceAtLeast(0)) { d, i ->
                            TabManager.setSuspendMin(act, mins[i]); d.dismiss(); fill()
                        }.setNegativeButton("Cancel", null).show()
                },
                Sub("Motion"),
                Row(R.drawable.ic_play, "Reduce motion", if (TabPrefs.reduceSwitch(act)) "ON" else "OFF", "Turns off the tab fade, close, count and download animations") {
                    TabPrefs.set(act, "reduce_motion", !TabPrefs.reduceSwitch(act))
                    fill()
                }
            ))
            if (query.isBlank() && subPage == null) hub() // 0.0.312
            category("downloads", R.drawable.ic_download, "Downloads", "Library, quality, files", listOf( // S64: arranged by what you do
                Sub("Library"),
                Row(R.drawable.ic_download, "Downloads", "", "Files saved on this phone") { DownloadsScreen.open(act) }, // V18
                Row(R.drawable.ic_book, "Novel grabber", "", "Web novels and chapters to EPUB, DOCX, TXT") { ext("novel/studio.html") }, // N1
                Row(R.drawable.ic_list, "Queue", "", "Running, waiting and failed jobs") { ext("queue.html") },
                Row(R.drawable.ic_history, "Download history", "", "Everything Grabber saved") { ext("history.html") },
                Row(R.drawable.ic_bookmark, "Followed manga", Follow.count().let { if (it > 0) "$it" else "" }, "Alerts for new chapters") { Follow.open(act, "", null) { u -> dismiss(); BookmarksScreen.tabsRef?.openUrl(u) } },
                Row(R.drawable.ic_history, "Scheduled downloads", Sched.count().let { if (it > 0) "$it" else "" }, "Start at a time you choose") { Sched.open(act) { emptyList() } },
                Sub("Problems and reports"), // 0.0.322: diagnose rows no longer sit inside Library
                Row(R.drawable.ic_info, "Report center", "", "Why something does not work: report, copy, share") { DiagScreen.open(act, null, -1) }, // S85, V18
                Row(R.drawable.ic_list, "Report log size", DiagScreen.logSizeText(), "Events kept for the report (no page text)"), // S85
                Row(R.drawable.ic_close, "Clear report log", "", "Delete the diagnose log and the Grabber log") { DiagScreen.askClearLog(act) { fill() } }, // S85
                Row(R.drawable.ic_info, "Auto Diagnose pill", if (AutoDiag.on(act)) "ON" else "OFF", "Small Diagnose button after a tab crash, a download that fails twice or an empty grabber") { // S87.2
                    AutoDiag.setOn(act, !AutoDiag.on(act))
                    fill()
                },
                Sub("Quality and files"),
                Row(R.drawable.ic_download, "Download quality", DetectedPanel.maxQLabel(), "Used when you press Download or Download all") { DetectedPanel.maxQDialog(act) { fill() } },
                Row(R.drawable.ic_download, "Preferred video format", QualityPrefs.containerLabel(), "MP4 or WebM when a stream offers both. Picks, does not convert") { QualityPrefs.containerDialog(act) { fill() } },
                Row(R.drawable.ic_pl_cc, "Subtitles and file names", subSummary(), "Languages, format, Plex / Jellyfin names") { SubName.open(act) },
                Row(R.drawable.ic_download, "Convert .ts to .mp4", if (DetectedPanel.convertOn()) "ON" else "OFF") { DetectedPanel.convertDialog(act) { fill() } },
                Row(R.drawable.ic_folder, "Download folder", DownloadFolder.label(act), "Where new files are saved") { DownloadFolder.showDialog(act) { fill() } },
                Row(R.drawable.ic_edit, "Ask before download", DownloadAsk.label(act), "A sheet with the file name (rename), size and folder before a download from a page starts. Optional") { DownloadAsk.setOn(act, !DownloadAsk.on(act)); fill() }, // 0.0.425
                Row(R.drawable.ic_download, "Leftover download files", DlLeftovers.label(), "Unfinished parts of removed or failed downloads. Tap to delete") { DlLeftovers.dialog(act) { fill() } }, // 0.0.393
                Row(R.drawable.ic_download, "Grabber temporary data", FsTemp.label(act), "Stream pieces kept by the browser (File System). Over 1 GB is cleared at app start") { FsTemp.dialog(act) { fill() } }, // 0.0.395
                Row(R.drawable.ic_folder, "Sub-folders in the chosen folder", if (DownloadFolder.subfolders(act)) "ON" else "OFF") {
                    DownloadFolder.setSubfolders(act, !DownloadFolder.subfolders(act))
                    fill()
                },
                Row(R.drawable.ic_download, "Audio only downloads", if (QualityPrefs.audioOnlyOn()) "ON" else "OFF", "Streams with a separate audio track. Also in batches") { QualityPrefs.toggleAudioOnly(act) { fill() } },
                Row(R.drawable.ic_folder, "Batch: folder and number", if (NameIndex.on()) "ON" else "OFF", "Show folder and 01 - title for batches") { NameIndex.toggle(act) { fill() } },
                Sub("Downloader"), // 0.0.376: app downloader + rules + link inbox in their own group
                Row(R.drawable.ic_download, "Link inbox", if (LinkInbox.on(act)) "ON" else "OFF", "Copy a Facebook / Pinterest / Reddit / Discord / video link, come back here: card with Download (also Share > this app)") { LinkInbox.setOn(act, !LinkInbox.on(act)); fill() },
                Row(R.drawable.ic_download, "App downloader", DlPartsUi.summary(act), "For files (tab link, blob, grabber file): parts, speed limit, queue, resume") { DlPartsUi.open(act) { fill() } }, // 0.0.359 S3
                Row(R.drawable.ic_download, "Download rules", DlRules.summary(act), "Wi-Fi only, battery, storage. Checked before a download starts") { DlRules.dialog(act) { fill() } }
            ))
            category("grabber", R.drawable.ic_settings, "Grabber and buttons", "Engine, buttons, player", listOf(
                Sub("Engine"),
                Row(R.drawable.ic_settings, "Download engine", engSummary(), "For streams (HLS / DASH): speed, retries, limits, live recording") { DlEngine.open(act) },
                Row(R.drawable.ic_settings, "Grabber settings", "", "Sites, theme, backup, reset") { GrabSet.open(act) },
                Row(R.drawable.ic_screen, "All V82 options page", "", "The full original settings page") { ext("options.html") },
                Sub("Player and buttons"),
                Row(R.drawable.ic_play, "Play video links in built-in player", if (WebPlay.linksEnabled(act)) "ON" else "OFF") { // S39
                    WebPlay.setLinksEnabled(act, !WebPlay.linksEnabled(act))
                    fill()
                },
                Row(R.drawable.ic_play, "Video button on pages", if (WebVideo.on(act)) "ON" else "OFF") { // S45
                    DlMode.manual(act); WebVideo.setOn(act, !WebVideo.on(act))
                    Util.toast(act, "Reload the page")
                    fill()
                },
                Sub("Download buttons"), // MGB S51
                Row(R.drawable.ic_download, "Button mode", DlMode.label(act)) { DlMode.dialog(act) { fill() } }, // S54
                Row(R.drawable.ic_download, "Grabber button on pages", if (v77On("showPill", true)) "ON" else "OFF") {
                    DlMode.manual(act); v77Set("showPill", !v77On("showPill", true)) // S54
                    Util.toast(act, "Applies at once on open pages")
                    fill()
                },
                Row(R.drawable.ic_download, "Grabber button opens Files detected panel", if (v77On("nativePanel", true)) "ON" else "OFF") {
                    DlMode.manual(act); v77Set("nativePanel", !v77On("nativePanel", true))
                    fill()
                },
                Row(R.drawable.ic_play, "Grabber button on video players", if (v77On("showOverlay", true)) "ON" else "OFF") {
                    DlMode.manual(act); v77Set("showOverlay", !v77On("showOverlay", true))
                    Util.toast(act, "Reload the page")
                    fill()
                },
                Row(R.drawable.ic_trash, "Forget removed links", "${DetectedPanel.rmCount(act)} saved") {
                    confirm("Show every link you removed from the Files detected list again?", "Forget") {
                        DetectedPanel.rmForget(act)
                        fill()
                    }
                },
                Row(R.drawable.ic_download, "Extra native download button", if (nativeBtn()) "ON" else "OFF") {
                    DlMode.manual(act); prefs.edit().putBoolean("native_btn", !nativeBtn()).apply()
                    Util.toast(act, "Changes in a few seconds")
                    fill()
                },
                Sub("Backup downloader"), // S65; 0.0.323: moved last
                Row(R.drawable.ic_play, "V82 Grabber (backup)", if (V77Alt.on(act)) "ON" else "OFF", "Original Media Grabber instead of the native downloader, for when it is under maintenance") {
                    V77Alt.set(act, !V77Alt.on(act))
                    Util.toast(act, if (V77Alt.on(act)) "V82 Grabber ON. Reload open pages" else "Native downloader is main again. Reload open pages")
                    fill()
                }
            ))
            category("player", R.drawable.ic_pl_speed, "Player", "Gestures, speed, defaults", listOf( // P13
                Sub("Gestures"),
                Row(R.drawable.ic_pl_fwd10, "Player gestures", if (PlayerPrefs.getBool(act, PlayerPrefs.K_GESTURES, true)) "ON" else "OFF", "Swipe, hold, pinch (taps stay)") {
                    PlayerPrefs.putBool(act, PlayerPrefs.K_GESTURES, !PlayerPrefs.getBool(act, PlayerPrefs.K_GESTURES, true))
                    fill()
                },
                Row(R.drawable.ic_pl_speed, "Hold speed", PlayerSpeed.fmt(PlayerPrefs.getFloat(act, PlayerPrefs.K_HOLD_SPEED, 2f)), "Long press = fast play") {
                    val v = floatArrayOf(1.5f, 2f, 2.5f, 3f, 4f, 5f)
                    val now = PlayerPrefs.getFloat(act, PlayerPrefs.K_HOLD_SPEED, 2f)
                    pickPl("Hold speed", Array(v.size) { PlayerSpeed.fmt(v[it]) }, v.indexOfFirst { Math.abs(it - now) < 0.01f }) { PlayerPrefs.putFloat(act, PlayerPrefs.K_HOLD_SPEED, v[it]) }
                },
                Row(R.drawable.ic_pl_speed, "Slide while holding", if (PlayerPrefs.getBool(act, PlayerPrefs.K_HOLD_SLIDE, true)) "ON" else "OFF", "Slide up or down to change") {
                    PlayerPrefs.putBool(act, PlayerPrefs.K_HOLD_SLIDE, !PlayerPrefs.getBool(act, PlayerPrefs.K_HOLD_SLIDE, true))
                    fill()
                },
                Row(R.drawable.ic_pl_fwd10, "Double tap seconds", PlayerGestures.dtapSec(act).toString() + " s", "Skip on left or right tap") {
                    val v = intArrayOf(5, 10, 15, 20)
                    pickPl("Double tap seconds", Array(v.size) { v[it].toString() + " s" }, v.indexOf(PlayerGestures.dtapSec(act))) { PlayerPrefs.putInt(act, PlayerPrefs.K_DTAP_SEC, v[it]) }
                },
                Row(R.drawable.ic_pl_fwd10, "Seek swipe speed", arrayOf("Slow", "Normal", "Fast")[PlayerPrefs.getInt(act, PlayerPrefs.K_SEEK_SENS, 1).coerceIn(0, 2)], "Swipe left or right to seek") {
                    pickPl("Seek swipe speed", arrayOf("Slow", "Normal", "Fast"), PlayerPrefs.getInt(act, PlayerPrefs.K_SEEK_SENS, 1).coerceIn(0, 2)) { PlayerPrefs.putInt(act, PlayerPrefs.K_SEEK_SENS, it) }
                },
                Row(R.drawable.ic_pl_volume, "Volume boost", PlayerGestures.boostCap(act).let { if (it > 0) "$it%" else "Off" }, "Extra volume, can distort") {
                    val v = intArrayOf(0, 25, 50, 75, 100)
                    pickPl("Volume boost", Array(v.size) { if (v[it] == 0) "Off" else v[it].toString() + "%" }, v.indexOf(PlayerGestures.boostCap(act))) { PlayerPrefs.putInt(act, PlayerPrefs.K_VOL_BOOST, v[it]) }
                },
                Row(R.drawable.ic_pl_bright, "Saved brightness", PlayerGestures.savedBrightness(act).let { if (it >= 0f) (it * 100).toInt().toString() + "%" else "System" }, "Tap to follow the phone again") {
                    PlayerPrefs.remove(act, PlayerPrefs.K_BRIGHT)
                    Util.toast(act, "Brightness follows the phone")
                    fill()
                },
                Sub("Defaults"),
                Row(R.drawable.ic_pl_fit, "Default fit", arrayOf("Fit", "Fill", "Stretch")[PlayerGestures.fitDefault(act)], "Picture size when it opens") {
                    pickPl("Default fit", arrayOf("Fit", "Fill", "Stretch"), PlayerGestures.fitDefault(act)) { PlayerPrefs.putInt(act, PlayerPrefs.K_FIT_DEFAULT, it) }
                },
                Row(R.drawable.ic_pl_speed, "Keep speed", if (PlayerPrefs.getBool(act, PlayerPrefs.K_SPEED_KEEP, false)) "ON" else "OFF", "Same speed for the next video") {
                    PlayerPrefs.putBool(act, PlayerPrefs.K_SPEED_KEEP, !PlayerPrefs.getBool(act, PlayerPrefs.K_SPEED_KEEP, false))
                    fill()
                },
                Row(R.drawable.ic_pl_quality, "Default quality", PlayerPrefs.getInt(act, PlayerPrefs.K_QUALITY_CAP, 0).let { if (it > 0) it.toString() + "p" else "Auto" }, "Highest video height") {
                    val v = intArrayOf(0, 1080, 720, 480, 360)
                    pickPl("Default quality", Array(v.size) { if (v[it] == 0) "Auto" else v[it].toString() + "p" }, v.indexOf(PlayerPrefs.getInt(act, PlayerPrefs.K_QUALITY_CAP, 0))) { PlayerPrefs.putInt(act, PlayerPrefs.K_QUALITY_CAP, v[it]) }
                },
                Row(R.drawable.ic_play, "Background audio in player", if (PlayerService.on(act)) "ON" else "OFF") { // S45
                    PlayerService.setOn(act, !PlayerService.on(act))
                    fill()
                },
            ))
            category("privacy", R.drawable.ic_shield, "Privacy and security", "Lock, passwords, ads, permissions", listOf( // 0.0.312: + Passwords and email
                Sub("Security"),
                Row(R.drawable.ic_lock, "App lock", if (AppLock.on(act)) "ON" else "OFF", if (AppLock.on(act)) "Locks ${AppLock.delayLabel(act).lowercase()}" else "") { // S45; 0.0.324: real switch, delay in the hint
                    fa?.let { AppLock.toggle(it) { fill() } }
                },
                Row(R.drawable.ic_lock, "Lock after", AppLock.delayLabel(act)) { AppLock.pickDelay(act) { fill() } }, // S45
                Row(R.drawable.ic_vault, "Vault", VaultScreen.summary(act)) { VaultScreen.open(act) }, // V3
                Row(R.drawable.ic_lock, "HTTPS-only mode", if (HttpsOnly.on(act)) "ON" else "OFF") { // S41; 0.0.320: moved up under Security
                    HttpsOnly.set(act, !HttpsOnly.on(act))
                    fill()
                },
                Sub("Private tabs"), // 0.0.331
                Row(R.drawable.ic_lock, "Hide private tabs in app preview", if (PrivateTabs.secureOn(act)) "ON" else "OFF", "Blank picture in Recents while a private tab is open (Android 12 and older: after you leave the app)") {
                    PrivateTabs.setSecure(act, !PrivateTabs.secureOn(act))
                    (act as? MainActivity)?.syncPrivateSecure()
                    fill()
                },
                Row(R.drawable.ic_lock, "Allow screenshots in private tabs", if (PrivateTabs.shotsOn(act)) "ON" else "OFF", "Screenshots and screen recording work in private tabs. OFF = blocked. WhatsApp Web always allows them") { // 0.0.342
                    PrivateTabs.setShots(act, !PrivateTabs.shotsOn(act))
                    (act as? MainActivity)?.syncPrivateSecure()
                    fill()
                },
                Row(R.drawable.ic_lock, "Lock private tabs", if (PrivateTabs.lockOn(act)) "ON" else "OFF", "Fingerprint or PIN when you come back to a private tab") {
                    fa?.let { PrivateTabs.toggleLock(it) { fill() } }
                },
                Row(R.drawable.ic_trash, "Close all private tabs", "${tabs?.allTabs()?.count { it.priv } ?: 0} open") {
                    tabs?.let { t -> t.allTabs().filter { it.priv }.forEach { t.close(it) } }
                    fill()
                },
                Sub("Permissions"), // 0.0.320
                Row(R.drawable.ic_shield, "Site permissions", "${SitePerms.count(act)} saved") { // S45
                    fa?.let { SitePerms.screen(it) { fill() } }
                },
                Row(R.drawable.ic_list, "Site settings", "${SiteSettings.count(act)} sites") { // STEP D
                    SiteSettings.open(act) { fill() }
                },
                Row(R.drawable.ic_shield, "Device permissions", "", "Camera, microphone, notifications, location, install apps") { DevicePerms.open(act) }, // 0.0.281; 0.0.312: moved here from General
                Sub("Ads and cookies"),
                Row(R.drawable.ic_shield, "Ad blocker", if (AdBlock.enabled) "ON" else "OFF", if (AdBlock.enabled) "${AdBlock.blocked} blocked so far" else "") { // 0.0.324: real switch, count in the hint
                    Util.toast(act, if (AdBlock.toggle()) "Ad blocker ON (reload the page)" else "Ad blocker OFF (reload the page)")
                    fill()
                },
                Row(R.drawable.ic_list, "Hosts and filters", "${FilterList.ruleCount} rules") { // T6
                    AdblockManager.open(act, tabs?.active?.url?.let { SiteConfig.hostOf(it) }) { fill() }
                },
                Row(R.drawable.ic_shield, "Hide ad spaces on pages", if (CosmeticFilter.adsOn(act)) "ON" else "OFF") { // S42
                    CosmeticFilter.setAds(act, !CosmeticFilter.adsOn(act))
                    Util.toast(act, "Reload the page")
                    fill()
                },
                Row(R.drawable.ic_list, "Sites with ads allowed") { CosmeticFilter.allowList(act) { fill() } }, // S42
                Row(R.drawable.ic_shield, "Hide cookie banners", if (CosmeticFilter.cookieOn(act)) "ON" else "OFF") { // S42
                    CosmeticFilter.setCookie(act, !CosmeticFilter.cookieOn(act))
                    Util.toast(act, "Reload the page")
                    fill()
                },
                Sub("Passwords"),
                Row(R.drawable.ic_lock, "Saved passwords") { fa?.let { PasswordStore.screen(it) } },
                Row(R.drawable.ic_download, "Import passwords (file or paste)") { fa?.let { PasswordStore.importChoice(it) } },
                Row(R.drawable.ic_share, "Export passwords (CSV)") { fa?.let { PasswordStore.exportCsv(it) } },
                Row(R.drawable.ic_lock, "Password generator") { fa?.let { PasswordStore.generatorDialog(it) } },
                Row(R.drawable.ic_share, "Encrypted backup of passwords") { fa?.let { PwBackup.export(it) } }, // 0.0.387
                Row(R.drawable.ic_download, "Restore passwords from backup") { fa?.let { PwBackup.restore(it) } }, // 0.0.387
                Row(R.drawable.ic_list, "Sites never saved") { fa?.let { PwBackup.neverList(it) } }, // 0.0.387
                Row(R.drawable.ic_lock, "Smarter login detection", if (PwSmart.on(act)) "ON" else "OFF") { // 0.0.388
                    PwSmart.set(act, !PwSmart.on(act))
                    Util.toast(act, "Reload the page")
                    fill()
                },
                Row(R.drawable.ic_lock, "New password screens", if (PwScreen.on(act)) "ON" else "OFF", "Full screen list, save sheet, generator sheet. OFF = the old dialogs") { // 0.0.389
                    PwScreen.flip(act, "newui", true)
                    fill()
                },
                Row(R.drawable.ic_lock, "Fill chip near the bar", if (PwScreen.chipOn(act)) "ON" else "OFF", "OFF = the old bar at the bottom") { // 0.0.389
                    PwScreen.flip(act, "chip", true)
                    fill()
                },
                Row(R.drawable.ic_lock, "Fill in private tabs", if (PwScreen.privFill(act)) "ON" else "OFF", "Fills saved logins in private tabs opened from now on. Nothing is ever saved from a private tab") { // 0.0.389
                    PwScreen.flip(act, "privfill", false)
                    Util.toast(act, "Applies to private tabs opened from now on")
                    fill()
                },
                Row(R.drawable.ic_lock, "Password health card", if (PwHealth.on(act)) "ON" else "OFF", "Weak and reused password check on the Passwords screen. Works only on this phone. OFF = no card") { // 0.0.390
                    PwHealth.flip(act)
                    fill()
                },
                Sub("Email alias"),
                Row(R.drawable.ic_share, "addy.io API key", if (EmailAlias.hasKey(act)) "set" else "not set") { EmailAlias.keyDialog(act) { fill() } },
                Row(R.drawable.ic_add, "Create email alias") { EmailAlias.create(act, "") },
                Row(R.drawable.ic_list, "My email aliases") { EmailAlias.manage(act) }
            ))
            category("data", R.drawable.ic_folder, "Data and backup", "Backup, history, cookies, cache", listOf(
                Sub("Backup"),
                Row(R.drawable.ic_history, "Auto backup (daily)", if (Backup.autoOn(act)) "ON" else "OFF") { // S42
                    Backup.setAuto(act, !Backup.autoOn(act))
                    if (Backup.autoOn(act)) Util.toast(act, "Saves Downloads/mgb-auto-backup.json once a day")
                    fill()
                },
                Row(R.drawable.ic_share, "Export data") { Backup.exportData(act) },
                Row(R.drawable.ic_download, "Import data") { Backup.importData(act) },
                Sub("Saved and recent"),
                Row(R.drawable.ic_history, "Recently closed tabs") { Backup.closedTabs(act, tabs) }, // V18
                Row(R.drawable.ic_folder, "Saved pages", "${PageSave.count(act)} pages") { tabs?.let { SavedPages.open(act, it) } }, // T4, V18
                Sub("Clear data"), // 0.0.323: saved items are not "clear" actions
                Row(R.drawable.ic_trash, "Delete browsing data...", "", "Choose time range and what to delete") { // 0.0.382
                    try { ClearData.show(act, tabs) } catch (e: Exception) { Util.toast(act, "Could not open") }
                },
                Row(R.drawable.ic_trash, "Clear browsing history") {
                    if (!ClearData.tryShow(act, tabs, 0)) // 0.0.386: opens Delete browsing data with History ticked; the old direct action is the fallback
                    confirm("Clear all browsing history?", "Clear") { Bookmarks.clearHistory(); Util.toast(act, "History cleared") }
                },
                Row(R.drawable.ic_trash, "Clear cookies and site data") {
                    if (!ClearData.tryShow(act, tabs, 1)) // 0.0.386
                    confirm("Log out of sites and clear site data in normal tabs?", "Clear") {
                        try {
                            CookieManager.getInstance().removeAllCookies(null)
                            CookieManager.getInstance().flush()
                            WebStorage.getInstance().deleteAllData()
                            Util.toast(act, "Cookies and site data cleared")
                        } catch (e: Exception) { Util.toast(act, "Could not clear") }
                    }
                },
                Row(R.drawable.ic_trash, "Clear cache") {
                    if (!ClearData.tryShow(act, tabs, 2)) // 0.0.386
                    try {
                        tabs?.active?.web?.clearCache(true)
                        Util.toast(act, "Cache cleared")
                    } catch (e: Exception) { Util.toast(act, "Could not clear") }
                }
            ))
            category("extensions", R.drawable.ic_folder, "Extensions", "Add, turn on or off, remove", listOf( // S1
                Row(R.drawable.ic_folder, "Extensions", ExtListScreen.summary(act), "Add .zip or .crx files") { ExtListScreen.open(act) }, // V18
                Row(R.drawable.ic_folder, "Match page theme", ExtTheme.label(ExtTheme.mode(act)), "Dark site, dark extension panel (reload page)") { // 0.0.428 (S3)
                    ExtTheme.cycle(act); ContentInjector.refresh(act)
                    Util.toast(act, "Reload the page")
                    fill()
                },
                Row(R.drawable.ic_folder, "CSS compatibility", ExtCssCompat.label(ExtCssCompat.mode(act)), "Fixes new CSS on an old WebView (" + ExtProbe.webView().substringAfterLast(' ') + "), reload page") { // 0.0.435 (S10)
                    ExtCssCompat.cycle(act); ContentInjector.refresh(act)
                    Util.toast(act, "Reload the page")
                    fill()
                },
                Row(R.drawable.ic_folder, "Compatibility self-test", ExtSelfTest.summary(), "Adds a temporary test extension, checks chrome.* support, removes it") { // 0.0.436 (S11)
                    ExtSelfTest.start(act) { fill() }
                },
                Sub("Userscripts"), // 0.0.323
                Row(R.drawable.ic_code, "Userscripts", "${Userscripts.count(act)} scripts", "Your own scripts on pages") { UserscriptsScreen.open(act) { fill() } }, // T7
                Row(R.drawable.ic_code, "Run userscripts", if (Userscripts.masterOn(act)) "ON" else "OFF") { // T7
                    Userscripts.setMaster(act, !Userscripts.masterOn(act)); Userscripts.refreshAll(act)
                    Util.toast(act, "Reload the page")
                    fill()
                },
                Row(R.drawable.ic_code, "Userscripts in private tabs", if (Userscripts.privOn(act)) "ON" else "OFF") { // T7
                    Userscripts.setPriv(act, !Userscripts.privOn(act)); Userscripts.refreshAll(act)
                    Util.toast(act, "Reload the page")
                    fill()
                }
            ))
            category("about", R.drawable.ic_info, "About", "Updates, engine, version", listOf(
                Row(R.drawable.ic_info, "How to use", "", "A short guide for new users") { HowToUse.open(act) }, // V14, V18
                Row(R.drawable.ic_reload, "Check for updates") { UpdateManager.check(act, false) },
                Row(R.drawable.ic_reload, "Version history", "", "What changed in each version") { VersionHistory.open(act) }, // 0.0.334
                Row(R.drawable.ic_info, "Engine status") { EngineHost.showStatus(act) },
                Row(R.drawable.ic_info, "Version", "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})") { DevMode.tap(act) } // 0.0.334: 7 taps = developer mode
            ))
            if (query.isNotBlank() && col.childCount == 0) { // S48
                col.addView(TextView(act).apply {
                    text = "No setting found for \"" + query.trim() + "\""
                    textSize = 14f
                    gravity = Gravity.CENTER
                    setTextColor(act.getColor(R.color.mg_text_dim))
                }, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(40) })
                // 0.0.443: next step instead of a dead end
                fun helpBtn(label: String, go: () -> Unit) = TextView(act).apply {
                    text = label
                    textSize = 14f
                    gravity = Gravity.CENTER
                    minimumHeight = dp(48)
                    setPadding(dp(16), dp(10), dp(16), dp(10))
                    setTextColor(act.getColor(R.color.mg_primary))
                    background = round(ColorUtils.setAlphaComponent(act.getColor(R.color.mg_primary), 0x1F), 14)
                    isClickable = true
                    isFocusable = true
                    setOnClickListener { go() }
                }
                tabs?.let { tm -> col.addView(helpBtn("Search in Command Center") { dismiss(); CommandCenter.show(act, tm) }, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(16); marginStart = dp(24); marginEnd = dp(24) }) }
                col.addView(helpBtn("Open How to use") { HowToUse.open(act) }, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(8); marginStart = dp(24); marginEnd = dp(24) })
            }
            sv?.post { sv?.scrollTo(0, keepY) }
        }
    }
}
