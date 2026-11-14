package com.jabra.mgb

import android.app.Activity
import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import androidx.core.widget.NestedScrollView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog

/**
 * S7: Thazir Command Center. One search box that runs browser actions and jumps to open tabs, bookmarks and history.
 * Local registry only: nothing typed is parsed as a script, every command calls an existing screen/handler, and no command
 * deletes or clears anything. Prefixes: ">" actions, "@" tabs, "*" bookmarks, "#" history. Enter runs the highlighted (first) row.
 * No new preferences, no stored recent actions (nothing to leak from private browsing).
 */
object CommandCenter {
    private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

    private enum class Scope { ALL, ACTIONS, TABS, BOOKMARKS, HISTORY }

    private class Cmd(val icon: Int, val title: String, val sub: String, val kw: String, val run: () -> Unit)

    private var shown: BottomSheetDialog? = null

    fun show(act: Activity, tabs: TabManager) {
        if (act.isFinishing || act.isDestroyed) return
        shown?.let { try { it.dismiss() } catch (_: Exception) {} }
        try {
            build(act, tabs)
        } catch (e: Exception) {
            android.util.Log.e("CommandCenter", "show failed", e)
            Util.toast(act, "Could not open Command Center")
        }
    }

    private fun build(act: Activity, tabs: TabManager) {
        val cText = act.getColor(R.color.mg_text)
        val cDim = act.getColor(R.color.mg_text_dim)
        val cPrimary = act.getColor(R.color.mg_primary)
        val cCard = act.getColor(R.color.mg_surface)
        val cVar = act.getColor(R.color.mg_surface_variant)
        val cLine = act.getColor(R.color.mg_outline)
        fun dp(v: Int) = Util.dp(act, v)
        val priv = tabs.active?.priv == true
        val d = BottomSheetDialog(act)
        shown = d
        d.setOnDismissListener { if (shown === d) shown = null }

        fun hideIme(v: View) {
            (act.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(v.windowToken, 0)
        }

        val root = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(16), dp(14), dp(18))
            minimumHeight = dp(340) // keeps the sheet from jumping while results change
        }
        root.addView(TextView(act).apply {
            text = "Command Center"
            setTextColor(cText); setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f); typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(6), 0, dp(6), 0)
        })
        root.addView(TextView(act).apply {
            text = "Run an action or jump to a tab, bookmark or page."
            setTextColor(cDim); setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(dp(6), dp(2), dp(6), dp(10))
        })

        // search field
        val field = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(48)
            setPadding(dp(14), 0, dp(6), 0)
            background = GradientDrawable().apply { setColor(cCard); cornerRadius = dp(16).toFloat(); setStroke(dp(1), cLine) }
        }
        field.addView(ImageView(act).apply {
            setImageResource(R.drawable.ic_search); setColorFilter(cDim)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(dp(20), dp(20)).apply { marginEnd = dp(10) })
        val input = EditText(act).apply {
            background = null
            hint = "Search commands, tabs, bookmarks"
            setHintTextColor(cDim); setTextColor(cText); setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            maxLines = 1; isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            imeOptions = EditorInfo.IME_ACTION_GO or (if (priv) EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING else 0)
            contentDescription = "Command Center search"
            setPadding(0, dp(10), 0, dp(10))
        }
        field.addView(input, LinearLayout.LayoutParams(0, WRAP, 1f))
        val clear = ImageView(act).apply {
            setImageResource(R.drawable.ic_close); setColorFilter(cDim)
            setPadding(dp(10), dp(10), dp(10), dp(10))
            contentDescription = "Clear search"
            visibility = View.GONE
            val tv = TypedValue()
            if (act.theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, tv, true)) setBackgroundResource(tv.resourceId)
            setOnClickListener { input.setText("") }
        }
        field.addView(clear, LinearLayout.LayoutParams(dp(40), dp(40)))
        root.addView(field, LinearLayout.LayoutParams(MATCH, WRAP))
        root.addView(TextView(act).apply {
            text = ">  actions     @  tabs     *  bookmarks     #  history"
            setTextColor(cDim); setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setPadding(dp(8), dp(8), dp(8), dp(2))
        })

        val list = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        root.addView(list, LinearLayout.LayoutParams(MATCH, WRAP))
        val scroll = NestedScrollView(act).apply { addView(root) }

        fun exec(c: Cmd) {
            hideIme(input)
            d.dismiss()
            try { c.run() } catch (e: Exception) {
                android.util.Log.e("CommandCenter", "command failed: ${c.title}", e)
                Util.toast(act, "Could not run that command")
            }
        }

        fun header(text: String) = TextView(act).apply {
            this.text = text.uppercase()
            setTextColor(cPrimary); setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f); typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.08f
            setPadding(dp(8), dp(14), 0, dp(6))
        }

        fun row(c: Cmd, first: Boolean): View {
            val r = LinearLayout(act).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(52)
                setPadding(dp(10), dp(6), dp(10), dp(6))
                if (first) background = GradientDrawable().apply { setColor(cVar); cornerRadius = dp(14).toFloat() }
                else {
                    val tv = TypedValue()
                    if (act.theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)) setBackgroundResource(tv.resourceId)
                }
                contentDescription = if (c.sub.isBlank()) c.title else "${c.title}, ${c.sub}"
                isClickable = true; isFocusable = true
                setOnClickListener { exec(c) }
            }
            val badge = FrameLayout(act).apply {
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(ColorUtils.setAlphaComponent(cPrimary, 0x26)) }
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            badge.addView(ImageView(act).apply { setImageResource(c.icon); setColorFilter(cPrimary) }, FrameLayout.LayoutParams(dp(18), dp(18), Gravity.CENTER))
            r.addView(badge, LinearLayout.LayoutParams(dp(36), dp(36)).apply { marginEnd = dp(12) })
            val texts = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
            texts.addView(TextView(act).apply {
                text = c.title; setTextColor(cText); setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                maxLines = 1; ellipsize = TextUtils.TruncateAt.END
            })
            if (c.sub.isNotBlank()) texts.addView(TextView(act).apply {
                text = c.sub; setTextColor(cDim); setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                maxLines = 1; ellipsize = TextUtils.TruncateAt.END
            })
            r.addView(texts, LinearLayout.LayoutParams(0, WRAP, 1f))
            if (first) r.addView(TextView(act).apply {
                text = "↵"; setTextColor(cDim); setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            })
            return r
        }

        // ---- registry (built fresh each time so enabled/disabled state is current) ----
        val cur = tabs.active
        val canPage = cur != null && !cur.home && !cur.isExt && cur.url.startsWith("http")
        val actions = ArrayList<Cmd>()
        fun reg(icon: Int, title: String, sub: String, kw: String, f: () -> Unit) { actions.add(Cmd(icon, title, sub, kw, f)) }
        reg(R.drawable.ic_add, "New tab", "Open the speed dial in a new tab", "open create blank", { tabs.newTab(TabManager.HOME_PAGE) })
        reg(R.drawable.ic_lock, "New private tab", "No history, separate cookies", "incognito secret", { tabs.newTab(TabManager.HOME_PAGE, 0, true, true) })
        reg(R.drawable.ic_screen, "Tab switcher", "See all open tabs and groups", "tabs overview grid", { tabs.showSwitcher() })
        reg(R.drawable.ic_home, "Home", "Go to the speed dial", "start page", { tabs.goHome() })
        reg(R.drawable.ic_desktop, "Facebook", "Mobile feed, no Desktop site needed", "fb social lite", { SocialMode.open(act, tabs, SocialMode.FB_HOME) }) // 0.0.224
        reg(R.drawable.ic_desktop, "Messenger chat", "Facebook messages that fit the phone", "fb chat message social", { SocialMode.open(act, tabs, SocialMode.FB_CHAT) })
        reg(R.drawable.ic_desktop, "Instagram", "Feed in the browser", "ig insta social", { SocialMode.open(act, tabs, SocialMode.IG_HOME) })
        reg(R.drawable.ic_desktop, "Instagram messages", "Direct messages in the browser", "ig insta dm chat social", { SocialMode.open(act, tabs, SocialMode.IG_DM) })
        reg(R.drawable.ic_desktop, "Reddit (old.reddit)", "Light classic layout, no app nag", "reddit old social", { SocialMode.open(act, tabs, "https://old.reddit.com/") }) // 0.0.226
        for (sp in SocialProfiles.all) if (sp.sub.isNotEmpty()) reg(R.drawable.ic_desktop, sp.label, sp.sub, sp.kw, { SocialMode.open(act, tabs, sp.startUrl) }) // 0.0.226-227: one row per table entry
        if (cur != null) reg(R.drawable.ic_history, "Recently closed tabs", "Reopen a tab you closed", "undo restore reopen", { Backup.closedTabs(act, tabs) })
        if (canPage) {
            reg(R.drawable.ic_reload, "Reload page", Util.host(cur!!.url), "refresh", { cur.web.reload() })
            reg(R.drawable.ic_search, "Find on page", "Search the text of this page", "find search text", { FindBar.open(act, tabs.active?.web) })
            if (!Bookmarks.isBookmarked(cur.url)) reg(R.drawable.ic_bookmark_border, "Bookmark this page", Util.host(cur.url), "add save star", {
                tabs.active?.let { t ->
                    if (t.url.startsWith("http")) {
                        BookmarkFolderPicker.save(act, t.title.ifBlank { Util.host(t.url) }, t.url) // 0.0.328
                    }
                }
            })
            reg(R.drawable.ic_copy, "Copy page link", Util.host(cur.url), "address url clipboard", { tabs.active?.let { tabs.copyText(it.url) } })
            reg(R.drawable.ic_share, "Share page", Util.host(cur.url), "send link", { tabs.active?.let { if (it.url.startsWith("http")) tabs.shareText(it.url) } })
            if (PrivacyGuard.state(cur) != 0) reg(R.drawable.ic_shield, "Privacy Guard", if (AdBlock.enabled) "ON · ${AdBlock.blocked} blocked" else "OFF", "ads trackers blocker shield", {
                PrivacyGuard.show(act, tabs) {}
            })
        }
        reg(R.drawable.ic_bookmark, "Bookmarks", "Open your bookmarks", "favorites", { BookmarksScreen.open(act, BookmarksScreen.Mode.BOOKMARKS, tabs) })
        reg(R.drawable.ic_history, "History", "Open browsing history", "visited past", { BookmarksScreen.open(act, BookmarksScreen.Mode.HISTORY, tabs) })
        reg(R.drawable.ic_download, "Downloads", "Open the downloads screen", "files queue", { DownloadsScreen.open(act) })
        reg(R.drawable.ic_folder, "Saved pages", "Pages saved for offline reading", "offline mht", { SavedPages.open(act, tabs) })
        reg(R.drawable.ic_ext, "Extensions", "Open the extensions tray", "addons plugins", { ExtTray.show(act) })
        reg(R.drawable.ic_vault, "Vault", VaultScreen.summary(act), "secure private encrypted sealed safe", { VaultScreen.open(act) }) // V12c
        reg(R.drawable.ic_info, "How to use", "A short guide for new users", "help guide tutorial tour start learn", { HowToUse.open(act) }) // V14
        reg(R.drawable.ic_lock, "Passwords", "Saved logins", "credentials login", { (act as? androidx.fragment.app.FragmentActivity)?.let { PasswordStore.screen(it) } })
        reg(R.drawable.ic_edit, "Accessibility", "Default zoom and saved site zoom", "text size zoom bigger larger font read", { AccessScreen.open(act) }) // A2
        reg(R.drawable.ic_theme, "Appearance", "Theme, address bar, home and gestures", "dark light wallpaper look", { AppearanceScreen.open(act) })
        reg(R.drawable.ic_settings, "Settings", "All browser settings", "preferences options", { SettingsScreen.open(act) })
        // 0.0.440: every settings row by name (only while typing, so the empty list stays short). Opens Settings with the name in its search box.
        for ((label, card, grp) in SettingsIndex.ROWS) if (actions.none { it.title.equals(label, true) }) reg(R.drawable.ic_settings, label, "Settings · " + card, grp + " setting option", { SettingsScreen.open(act, label) })

        fun score(tokens: List<String>, title: String, extra: String): Int {
            val t = title.lowercase(); val k = extra.lowercase()
            var s = 0
            for (tok in tokens) {
                s += when {
                    t.startsWith(tok) -> 0
                    t.contains(" $tok") -> 1
                    t.contains(tok) -> 2
                    k.contains(tok) -> 3
                    else -> return -1
                }
            }
            return s
        }

        fun looksLikeUrl(q: String) = !q.contains(' ') && (q.contains('.') || q.startsWith("http", true) || q.startsWith("localhost", true))

        fun go(q: String) {
            var target = Util.toUrlOrSearch(q)
            val upgraded = if (HttpsOnly.on(act)) HttpsOnly.upgrade(target) else null
            val httpFrom = if (upgraded != null) target else null
            if (upgraded != null) target = upgraded
            val t = tabs.active ?: tabs.newTab(null) ?: return
            if (!t.isExt && tabs.playLink(t, target)) return
            tabs.leaveHome(t)
            t.httpsFrom = httpFrom
            t.url = target
            t.web.loadUrl(target)
        }

        var firstCmd: Cmd? = null

        fun render(raw: String) {
            list.removeAllViews()
            var q = raw.trim()
            var scope = Scope.ALL
            if (q.isNotEmpty()) when (q[0]) {
                '>' -> { scope = Scope.ACTIONS; q = q.substring(1).trim() }
                '@' -> { scope = Scope.TABS; q = q.substring(1).trim() }
                '*' -> { scope = Scope.BOOKMARKS; q = q.substring(1).trim() }
                '#' -> { scope = Scope.HISTORY; q = q.substring(1).trim() }
            }
            val tokens = q.lowercase().split(' ').filter { it.isNotEmpty() }
            val searching = tokens.isNotEmpty()
            val sections = ArrayList<Pair<String, List<Cmd>>>()

            if (scope == Scope.ALL || scope == Scope.ACTIONS) {
                val hits = if (!searching) actions.filter { !it.sub.startsWith("Settings · ") }.take(if (scope == Scope.ACTIONS) 30 else 8)
                else actions.mapNotNull { c -> score(tokens, c.title, c.kw + " " + c.sub).let { s -> if (s < 0) null else s to c } }
                    .sortedBy { it.first }.map { it.second }.take(if (scope == Scope.ACTIONS) 30 else 8)
                if (hits.isNotEmpty()) sections.add("Actions" to hits)
            }
            if (scope == Scope.ALL || scope == Scope.TABS) {
                val limit = if (scope == Scope.TABS) 30 else if (searching) 8 else 5
                val all = tabs.allTabs().filter { it !== tabs.active }
                val hits = (if (!searching) all.sortedByDescending { it.lastActive }.map { 0 to it }
                else all.mapNotNull { t -> score(tokens, t.title.ifBlank { Util.host(t.url) }, t.url).let { s -> if (s < 0) null else s to t } }.sortedBy { it.first })
                    .take(limit).map { it.second }.map { t ->
                        val name = if (t.home) "Home" else t.title.ifBlank { Util.host(t.url).ifBlank { "New tab" } }
                        val where = if (t.home) "Speed dial" else Util.host(t.url)
                        Cmd(if (t.priv) R.drawable.ic_lock else R.drawable.ic_screen, name,
                            if (t.priv) "Private tab" + (if (where.isNotBlank()) " · $where" else "") else where, "", { tabs.switchTo(t) })
                    }
                if (hits.isNotEmpty()) sections.add("Open tabs" to hits)
            }
            val bmUrls = HashSet<String>()
            if ((scope == Scope.ALL && searching) || scope == Scope.BOOKMARKS) {
                val limit = if (scope == Scope.BOOKMARKS) 30 else 6
                val src = Bookmarks.bookmarks()
                val hits = (if (!searching) src.sortedByDescending { it.time }.map { 0 to it }
                else src.mapNotNull { b -> score(tokens, b.title.ifBlank { b.url }, b.url).let { s -> if (s < 0) null else s to b } }.sortedBy { it.first })
                    .take(limit).map { it.second }
                hits.forEach { bmUrls.add(it.url) }
                val cmds = hits.map { b -> Cmd(R.drawable.ic_bookmark, b.title.ifBlank { Util.host(b.url) }, Util.host(b.url), "", { tabs.openUrl(b.url) }) }
                if (cmds.isNotEmpty()) sections.add("Bookmarks" to cmds)
            }
            // History is hidden while a private tab is in front (nothing from the normal profile shows up beside private browsing).
            if (!priv && ((scope == Scope.ALL && searching) || scope == Scope.HISTORY)) {
                val limit = if (scope == Scope.HISTORY) 30 else 5
                val seen = HashSet<String>()
                val src = Bookmarks.history()
                val hits = (if (!searching) src.sortedByDescending { it.time }.map { 0 to it }
                else src.mapNotNull { h -> score(tokens, h.title.ifBlank { h.url }, h.url).let { s -> if (s < 0) null else s to h } }.sortedWith(compareBy<Pair<Int, Bookmarks.Item>>({ it.first }, { -it.second.time })))
                    .map { it.second }.filter { it.url !in bmUrls && seen.add(it.url) }.take(limit)
                val cmds = hits.map { h -> Cmd(R.drawable.ic_history, h.title.ifBlank { Util.host(h.url) }, Util.host(h.url), "", { tabs.openUrl(h.url) }) }
                if (cmds.isNotEmpty()) sections.add("History" to cmds)
            }
            if (scope == Scope.ALL && searching) {
                val g = Cmd(R.drawable.ic_open, if (looksLikeUrl(q)) "Open $q" else "Search for “$q”",
                    if (looksLikeUrl(q)) "Go to this address" else "With your search engine", "", { go(q) })
                if (looksLikeUrl(q)) sections.add(0, "Go to" to listOf(g)) else sections.add("Web" to listOf(g))
            }

            firstCmd = sections.firstOrNull()?.second?.firstOrNull()
            if (sections.isEmpty()) {
                list.addView(TextView(act).apply {
                    text = if (searching) "No matches for “$q”" else "Nothing here yet"
                    setTextColor(cDim); setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f); gravity = Gravity.CENTER
                    setPadding(dp(8), dp(36), dp(8), dp(36))
                    contentDescription = text
                }, LinearLayout.LayoutParams(MATCH, WRAP))
                return
            }
            var isFirst = true
            for ((title, cmds) in sections) {
                list.addView(header(title))
                for (c in cmds) {
                    list.addView(row(c, isFirst && searching), LinearLayout.LayoutParams(MATCH, WRAP))
                    isFirst = false
                }
            }
        }

        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val t = s?.toString().orEmpty()
                clear.visibility = if (t.isEmpty()) View.GONE else View.VISIBLE
                render(t)
                scroll.scrollTo(0, 0)
            }
        })
        input.setOnEditorActionListener { _, _, _ ->
            firstCmd?.let { exec(it) }
            true
        }
        render("")

        d.setContentView(scroll)
        d.behavior.skipCollapsed = true
        d.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        d.setOnShowListener {
            d.behavior.state = BottomSheetBehavior.STATE_EXPANDED
            input.requestFocus()
            input.post { (act.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(input, InputMethodManager.SHOW_IMPLICIT) }
        }
        d.show()
    }
}
