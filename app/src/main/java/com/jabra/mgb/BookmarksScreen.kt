package com.jabra.mgb

import android.app.Activity
import android.app.Dialog
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.text.TextUtils
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * S13: full-screen list of bookmarks or browsing history (Dialog, no manifest entry, like DownloadsScreen).
 *  - tap = open in the current tab, long press = open in new tab / remove
 *  - history: "Clear" in the title bar (asks first)
 * Opening goes through TabManager.openUrl / newTab, so it works from any tab.
 */
object BookmarksScreen {
    private const val TAG = "MGB.bmscreen"
    private var current: Screen? = null

    enum class Mode { BOOKMARKS, HISTORY }

    /** S25: set by MainActivity so DownloadsScreen can jump back here. Cleared in onDestroy. */
    var tabsRef: TabManager? = null

    /** 0.0.366: last selected pill, so the next screen slides the indicator from there. -1 = fresh open (no slide). */
    var lastPill = -1
    /** 0.0.366: true only while one screen hands over to the next (keeps lastPill). */
    private var switching = false

    /** 0.0.366: Pills = rounded track + ONE indicator that slides between the 3 labels (was: pill redrawn, so it jumped). */
    class PillBar(act: Activity) : android.widget.FrameLayout(act) {
        var moveTo: (Int) -> Unit = {}
    }

    /** 0.0.366: shared slide / fade used by both screens for the body under the header. dir +1 = from the right, -1 = from the left. */
    fun enter(act: Activity, v: View, dir: Int) {
        if (dir == 0 || TabPrefs.reduce(act)) return
        v.alpha = 0f
        v.translationX = dir * Util.dp(act, 36).toFloat()
        v.animate().alpha(1f).translationX(0f).setDuration(260)
            .setInterpolator(android.view.animation.DecelerateInterpolator(1.8f)).start()
    }

    /** S25: the 3 top pills. selected: 0 Bookmarks, 1 History, 2 Downloads. pick(i) is called on tap. */
    fun pills(act: Activity, selected: Int, pick: (Int) -> Unit): PillBar {
        fun dp(v: Int) = Util.dp(act, v)
        fun round(color: Int, r: Int) = android.graphics.drawable.GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(r).toFloat()
        }
        val reduce = TabPrefs.reduce(act)
        val from = if (lastPill in 0..2 && !reduce) lastPill else selected
        lastPill = selected
        val seg = PillBar(act).apply {
            background = round(act.getColor(R.color.mg_surface_variant), 22)
            setPadding(dp(3), dp(3), dp(3), dp(3))
        }
        val ind = View(act).apply { background = round(act.getColor(R.color.mg_primary), 19) }
        seg.addView(ind, android.widget.FrameLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT))
        val row = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL }
        val labels = arrayOf("Bookmarks", "History", "Downloads")
        val tv = ArrayList<TextView>()
        var cur = from
        for (i in labels.indices) {
            val t = TextView(act).apply {
                text = labels[i]
                gravity = Gravity.CENTER
                textSize = 13f
                maxLines = 1
                setTextColor(act.getColor(if (i == cur) R.color.mg_on_primary else R.color.mg_text))
                setOnClickListener { pick(i) }
            }
            tv.add(t)
            row.addView(t, LinearLayout.LayoutParams(0, dp(38), 1f))
        }
        seg.addView(row, android.widget.FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        var segW = 0
        seg.moveTo = { i ->
            if (i != cur) {
                val old = cur
                cur = i
                lastPill = i
                if (segW > 0 && !reduce) {
                    ind.animate().translationX(i * segW.toFloat()).setDuration(280)
                        .setInterpolator(android.view.animation.DecelerateInterpolator(1.8f)).start()
                    for (j in tv.indices) {
                        val to = act.getColor(if (j == i) R.color.mg_on_primary else R.color.mg_text)
                        if (j == i || j == old) android.animation.ObjectAnimator.ofArgb(tv[j], "textColor", tv[j].currentTextColor, to).setDuration(220).start()
                    }
                } else {
                    ind.translationX = i * segW.toFloat()
                    for (j in tv.indices) tv[j].setTextColor(act.getColor(if (j == i) R.color.mg_on_primary else R.color.mg_text))
                }
            }
        }
        seg.addOnLayoutChangeListener { _, l, _, r, _, _, _, _, _ ->
            val w = (r - l - dp(6)) / 3
            if (w > 0 && w != segW) {
                val first = segW == 0
                segW = w
                ind.layoutParams = (ind.layoutParams as android.widget.FrameLayout.LayoutParams).apply { width = w }
                ind.translationX = cur * w.toFloat()
                if (first && from != selected) { // slide from the previous screen's pill
                    cur = from
                    for (j in tv.indices) tv[j].setTextColor(act.getColor(if (j == cur) R.color.mg_on_primary else R.color.mg_text))
                    seg.post { seg.moveTo(selected) }
                }
            }
        }
        return seg
    }

    /** 0.0.366: DownloadsScreen hands over: take the open screen out of `current` (it is dismissed after the new one is shown). */
    internal fun detach(): Dialog? { val s = current; current = null; return s }
    internal fun hasNone() = current == null

    /** 0.0.366: dismiss a screen that was replaced; no exit animation, lastPill stays. */
    internal fun finishHandoff(prev: Dialog?) {
        if (prev == null) return
        prev.window?.setWindowAnimations(0)
        prev.window?.decorView?.postDelayed({
            switching = true
            try { prev.dismiss() } catch (_: Exception) { }
            switching = false
        }, 90)
    }

    fun open(host: Activity, mode: Mode, tabs: TabManager, smooth: Boolean = false) {
        if (host.isFinishing || host.isDestroyed) return
        val prev = if (smooth) DownloadsScreen.detach() else null // 0.0.366: Downloads > Bookmarks / History without a gap
        if (!smooth || prev == null) closeAll()
        try {
            tabsRef = tabs // S25
            val s = Screen(host, mode, tabs, if (prev != null) -1 else 0)
            current = s
            s.show()
            if (prev != null) DownloadsScreen.finishHandoff(prev)
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

    private class Screen(
        private val act: Activity,
        private var mode: Mode, // S23: switchable from the top pills
        private val tabs: TabManager,
        private var enterDir: Int = 0 // 0.0.366: slide direction of the body when handed over from Downloads
    ) : Dialog(act, com.google.android.material.R.style.Theme_Material3_DayNight_NoActionBar) {

        private var list: LinearLayout? = null
        private var empty: TextView? = null
        private var query = "" // S23: search text
        private var folder: String? = null // S24: open folder (null = top level)
        // S27: multi-select (bookmarks and history). Key = url (bookmarks) or "time|url" (history).
        private var selecting = false
        private val picked = LinkedHashSet<String>()
        private val visible = ArrayList<String>()
        private var selTitle: TextView? = null
        private val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        private val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

        private fun dp(v: Int) = Util.dp(act, v)

        private var pillBar: PillBar? = null
        private var bodyHost: android.widget.FrameLayout? = null

        init {
            setOnDismissListener { if (current === this) current = null; if (!switching) lastPill = -1 }
        }

        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)
            build()
            window?.let {
                if (enterDir != 0) it.setWindowAnimations(0) // 0.0.366: handed over, no dialog pop
                it.setLayout(MATCH, MATCH)
                it.setBackgroundDrawable(ColorDrawable(act.getColor(R.color.mg_bg)))
                it.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            }
        }

        /** S23: (re)build the whole screen: top pills + search + list. Called at start and when the mode changes. */
        private fun build() {
            val root = LinearLayout(act).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(act.getColor(R.color.mg_bg))
            }
            pillBar = null
            root.addView(header(), LinearLayout.LayoutParams(MATCH, dp(56)))
            val host = android.widget.FrameLayout(act)
            bodyHost = host
            val body = buildBody()
            host.addView(body, android.widget.FrameLayout.LayoutParams(MATCH, MATCH))
            root.addView(host, LinearLayout.LayoutParams(MATCH, 0, 1f))
            setContentView(root)
            render()
            if (enterDir != 0) { enter(act, body, enterDir); enterDir = 0 }
        }

        /** 0.0.366: search + filter + list of the current mode (the part that changes when a pill is tapped). Sets list / empty. */
        private fun buildBody(): LinearLayout {
            val body = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
            body.addView(searchBox(), LinearLayout.LayoutParams(MATCH, dp(44)).apply {
                marginStart = dp(16); marginEnd = dp(16); topMargin = dp(4); bottomMargin = dp(8)
            })
            if (mode == Mode.HISTORY) body.addView(HistoryFilter.row(act) { refreshBody() }, LinearLayout.LayoutParams(MATCH, WRAP)) // 0.0.330

            val box = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
            val emptyView = TextView(act).apply {
                text = if (mode == Mode.BOOKMARKS) "No bookmarks yet" else "No history yet"
                setTextColor(act.getColor(R.color.mg_text_dim))
                textSize = 14f
                gravity = Gravity.CENTER
                setPadding(dp(16), dp(48), dp(16), dp(16))
                visibility = View.GONE
            }
            box.addView(emptyView, LinearLayout.LayoutParams(MATCH, WRAP))
            val items = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
            box.addView(items, LinearLayout.LayoutParams(MATCH, WRAP))
            val sv = ScrollView(act)
            sv.addView(box, ViewGroup.LayoutParams(MATCH, WRAP))
            body.addView(sv, LinearLayout.LayoutParams(MATCH, 0, 1f))
            list = items
            empty = emptyView
            return body
        }

        /** 0.0.366: filter changed: rebuild only the body (header + pills stay, no flash). */
        private fun refreshBody() {
            val host = bodyHost ?: return build()
            host.removeAllViews()
            host.addView(buildBody(), android.widget.FrameLayout.LayoutParams(MATCH, MATCH))
            render()
        }

        /** 0.0.366: Bookmarks <> History: header and pills stay, the indicator slides, the old list slides / fades out and the new one in. */
        private fun switchMode(m: Mode) {
            if (m == mode) return
            val dir = if (m.ordinal > mode.ordinal) 1 else -1
            mode = m; query = ""; folder = null
            val host = bodyHost
            val bar = pillBar
            if (host == null || bar == null || TabPrefs.reduce(act)) { build(); return }
            bar.moveTo(m.ordinal)
            val old = host.getChildAt(host.childCount - 1)
            val nb = buildBody()
            host.addView(nb, android.widget.FrameLayout.LayoutParams(MATCH, MATCH))
            render()
            enter(act, nb, dir)
            val d = dp(36).toFloat()
            old?.animate()?.alpha(0f)?.translationX(-dir * d)?.setDuration(170)
                ?.setInterpolator(android.view.animation.AccelerateInterpolator(1.2f))
                ?.withEndAction { host.removeView(old) }?.start()
        }

        private fun round(color: Int, r: Int) = android.graphics.drawable.GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(r).toFloat()
        }

        private fun searchBox(): View = android.widget.EditText(act).apply {
            hint = if (mode == Mode.BOOKMARKS) "Search bookmarks" else "Search history"
            setText(query)
            setSingleLine()
            textSize = 15f
            setTextColor(act.getColor(R.color.mg_text))
            setHintTextColor(act.getColor(R.color.mg_text_dim))
            background = BmUi.card(act, act.getColor(R.color.mg_surface_variant), 22) // 0.0.348
            setPadding(dp(16), 0, dp(16), 0)
            act.getDrawable(R.drawable.ic_search)?.mutate()?.let { d -> // 0.0.348: search icon
                d.setTint(act.getColor(R.color.mg_text_dim)); d.setBounds(0, 0, dp(20), dp(20))
                setCompoundDrawablesRelative(d, null, null, null); compoundDrawablePadding = dp(10)
            }
            addTextChangedListener(object : android.text.TextWatcher {
                override fun afterTextChanged(e: android.text.Editable?) { query = e?.toString().orEmpty(); render() }
                override fun beforeTextChanged(t: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(t: CharSequence?, a: Int, b: Int, c: Int) {}
            })
        }

        @Suppress("OVERRIDE_DEPRECATION")
        override fun onBackPressed() { // S24, S27
            if (selecting) endSelect() else if (folder != null) { folder = folder!!.substringBeforeLast('/', "").ifEmpty { null }; render() } else dismiss()
        }

        private fun header(): View = if (selecting) selHeader() else normalHeader()

        private fun normalHeader(): View {
            val h = LinearLayout(act).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(4), 0, dp(4), 0)
            }
            h.addView(ImageView(act).apply {
                setImageResource(R.drawable.ic_back)
                setPadding(dp(10), dp(10), dp(10), dp(10))
                contentDescription = "Back"
                setOnClickListener { if (folder != null) { folder = folder!!.substringBeforeLast('/', "").ifEmpty { null }; render() } else dismiss() } // S24, 0.0.328: one level up
            }, LinearLayout.LayoutParams(dp(44), dp(48)))
            // S23/S25: segmented pills Bookmarks | History | Downloads (shared with DownloadsScreen)
            val seg = pills(act, if (mode == Mode.BOOKMARKS) 0 else 1) { i ->
                if (i == 2) DownloadsScreen.open(act, smooth = true) // 0.0.366: handover, no dismiss gap; the new screen slides the indicator
                else switchMode(if (i == 0) Mode.BOOKMARKS else Mode.HISTORY)
            }
            pillBar = seg
            h.addView(seg, LinearLayout.LayoutParams(0, dp(44), 1f).apply { marginStart = dp(4); marginEnd = dp(4) })
            // 3-dot: Import / Export (bookmarks, S18) or Clear (history)
            h.addView(ImageView(act).apply {
                setImageResource(R.drawable.ic_more)
                setPadding(dp(10), dp(10), dp(10), dp(10))
                contentDescription = "More"
                setOnClickListener {
                    if (mode == Mode.BOOKMARKS) { bookmarksMenu(this); return@setOnClickListener } // 0.0.348
                    val labels = if (mode == Mode.BOOKMARKS) arrayOf("Select", "New folder", "Import bookmarks", "Export bookmarks", "Sort by...") else arrayOf("Select", "Clear history")
                    MaterialAlertDialogBuilder(act).setItems(labels) { _, which ->
                        if (which == 0) { selecting = true; picked.clear(); build() } // S27
                        else if (mode == Mode.BOOKMARKS) {
                            when (which) {
                                1 -> BookmarkFolderPicker.askName(act, folder ?: "") { render() } // 0.0.328: inside an open folder = sub folder
                                2 -> BrowserExtras.importBookmarks { render() }
                                3 -> BrowserExtras.exportBookmarks()
                                else -> BmSort.pick(act) { render() } // 0.0.332
                            }
                        } else askClear()
                    }.show()
                }
            }, LinearLayout.LayoutParams(dp(44), dp(48)))
            return h
        }

        /** 0.0.348: popup menu of the Bookmarks 3-dot button (icons on the right). */
        private fun bookmarksMenu(anchor: View) {
            BmUi.menu(act, anchor, listOf(
                BmUi.Entry("Select", R.drawable.ic_bm_select) { selecting = true; picked.clear(); build() },
                BmUi.Entry("Lock data vault", R.drawable.ic_bm_vault_lock) { BmUi.lockVault(act) },
                BmUi.Entry("New folder", R.drawable.ic_bm_folder_add) { BookmarkFolderPicker.askName(act, folder ?: "") { render() } },
                BmUi.Entry("Import bookmarks", R.drawable.ic_bm_import) { BrowserExtras.importBookmarks { render() } },
                BmUi.Entry("Export bookmarks", R.drawable.ic_bm_export) { BrowserExtras.exportBookmarks() },
                BmUi.Entry("Manage start page", R.drawable.ic_bm_home) { BmUi.manageStart(act) { u -> favTile(u) } },
                BmUi.Entry("Add site to folder", R.drawable.ic_bm_site_add) { BmUi.addSite(act, folder) { render() } }
            ))
        }

        // ---------- S27: multi-select ----------

        private fun keyOf(b: Bookmarks.Item) = if (mode == Mode.BOOKMARKS) b.url else b.time.toString() + "|" + b.url
        private fun urlOfKey(k: String) = if (mode == Mode.BOOKMARKS) k else k.substringAfter('|')

        private fun endSelect() { selecting = false; picked.clear(); build() }

        private fun toggle(k: String) {
            if (!picked.remove(k)) picked.add(k)
            if (picked.isEmpty()) { endSelect(); return }
            selTitle?.text = picked.size.toString() + " selected"
            render()
        }

        private fun toggleAll() {
            if (visible.isNotEmpty() && picked.containsAll(visible)) picked.removeAll(visible.toSet()) else picked.addAll(visible)
            if (picked.isEmpty()) { endSelect(); return }
            selTitle?.text = picked.size.toString() + " selected"
            render()
        }

        private fun selHeader(): View {
            fun icon(res: Int, desc: String, on: () -> Unit) = ImageView(act).apply {
                setImageResource(res)
                setColorFilter(act.getColor(R.color.mg_text))
                setPadding(dp(10), dp(10), dp(10), dp(10))
                contentDescription = desc
                setOnClickListener { on() }
            }
            val h = LinearLayout(act).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(4), 0, dp(4), 0)
                setBackgroundColor(act.getColor(R.color.mg_surface))
            }
            h.addView(icon(R.drawable.ic_close, "Cancel") { endSelect() }, LinearLayout.LayoutParams(dp(44), dp(48)))
            val t = TextView(act).apply {
                text = picked.size.toString() + " selected"
                setTextColor(act.getColor(R.color.mg_text))
                textSize = 16f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setPadding(dp(8), 0, 0, 0)
            }
            selTitle = t
            h.addView(t, LinearLayout.LayoutParams(0, WRAP, 1f))
            h.addView(TextView(act).apply {
                text = "All"
                textSize = 14f
                gravity = Gravity.CENTER
                setTextColor(act.getColor(R.color.mg_primary))
                setPadding(dp(12), 0, dp(12), 0)
                setOnClickListener { toggleAll() }
            }, LinearLayout.LayoutParams(WRAP, dp(48)))
            if (mode == Mode.BOOKMARKS) h.addView(icon(R.drawable.ic_folder, "Move") { moveSelected() }, LinearLayout.LayoutParams(dp(44), dp(48)))
            h.addView(icon(R.drawable.ic_trash, "Remove") { removeSelected() }, LinearLayout.LayoutParams(dp(44), dp(48)))
            h.addView(icon(R.drawable.ic_more, "More") { // S28
                val labels = (if (mode == Mode.BOOKMARKS) arrayOf("Pin to home", "Open in new tabs") else arrayOf("Open in new tabs")) + arrayOf("Move to vault", "Copy to vault") // V4b
                MaterialAlertDialogBuilder(act).setItems(labels) { _, w ->
                    when (labels[w]) {
                        "Pin to home" -> pinSelected()
                        "Open in new tabs" -> openSelected()
                        else -> toVault(labels[w] == "Move to vault")
                    }
                }.show()
            }, LinearLayout.LayoutParams(dp(44), dp(48)))
            return h
        }

        private fun removeSelected() {
            val n = picked.size
            if (n == 0) return
            MaterialAlertDialogBuilder(act)
                .setMessage("Remove " + n + (if (n == 1) " item?" else " items?"))
                .setPositiveButton("Remove") { _, _ ->
                    val gone = ArrayList<Bookmarks.Item>() // 0.0.332: kept for Undo
                    for (k in picked.toList()) {
                        if (mode == Mode.BOOKMARKS) Bookmarks.item(k)?.let { gone.add(it) }
                        else Bookmarks.history().firstOrNull { it.time.toString() == k.substringBefore('|') && it.url == k.substringAfter('|') }?.let { gone.add(it) }
                    }
                    if (mode == Mode.BOOKMARKS) BmUndo.bookmarks(window?.decorView, gone) { render() } else BmUndo.history(window?.decorView, gone) { render() }
                    for (k in picked.toList()) {
                        if (mode == Mode.BOOKMARKS) Bookmarks.removeBookmark(k)
                        else Bookmarks.removeHistory(k.substringBefore('|').toLongOrNull() ?: 0L, k.substringAfter('|'))
                    }
                    endSelect()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        private fun moveSelected() {
            if (picked.isEmpty()) return
            BookmarkFolderPicker.pick(act, "Move " + picked.size + " to") { f -> // 0.0.328: folder tree
                for (u in picked.toList()) Bookmarks.setFolder(u, f)
                endSelect()
            }
        }

        /** S28: show the picked bookmarks on the home screen. */
        private fun pinSelected() {
            var n = 0
            val all = Bookmarks.bookmarks()
            for (u in picked.toList()) {
                val b = all.firstOrNull { it.url == u } ?: continue
                if (!Bookmarks.isHome(u)) { Bookmarks.addHome(b.title, u); n++ }
            }
            Util.toast(act, if (n == 0) "Already on home" else "Pinned $n to home")
            endSelect()
        }

        /** V4b: move / copy the picked rows into the vault. Originals are removed only after VaultItems verified the vault copy. */
        private fun toVault(move: Boolean) {
            if (picked.isEmpty()) return
            if (!VaultStore.isUnlocked()) { Util.toast(act, "Open the vault first (menu > Vault)"); return }
            val type = if (mode == Mode.BOOKMARKS) "bm" else "hist"
            val keys = picked.toSet()
            Util.toast(act, "Sealing...")
            Thread {
                val src = (if (type == "bm") VaultItems.bmSources() else VaultItems.histSources())
                    .filter { keys.contains(if (type == "bm") it.e.url else it.e.time.toString() + "|" + it.e.url) }
                val r = try { VaultItems.addToVault(act, type, src, move) } catch (e: Exception) { VaultItems.Result(false, msg = "Something went wrong. Nothing was removed.") }
                act.runOnUiThread {
                    Util.toast(act, VaultItems.describe(type, r, move))
                    if (r.ok && isShowing) endSelect()
                }
            }.start()
        }

        private fun openSelected() {
            val urls = picked.map { urlOfKey(it) }.take(10)
            if (urls.isEmpty()) return
            for (u in urls) tabs.newTab(u)
            if (picked.size > 10) Util.toast(act, "Opened the first 10")
            dismiss()
        }

        private fun askClear() {
            MaterialAlertDialogBuilder(act)
                .setMessage(if (HistoryFilter.active) "Clear ALL browsing history? This includes rows hidden by the filter." else "Clear all browsing history?") // 0.0.330
                .setPositiveButton("Clear") { _, _ -> val old = Bookmarks.history(); Bookmarks.clearHistory(); render(); BmUndo.history(window?.decorView, old) { render() } } // 0.0.332
                .setNegativeButton("Cancel", null)
                .show()
        }

        private fun render() {
            val box = list ?: return
            box.removeAllViews()
            val q = query.trim()
            var count = 0
            visible.clear() // S27
            if (mode == Mode.BOOKMARKS) {
                val all = Bookmarks.bookmarks()
                val open = folder
                box.addView(BmUi.bar(act, open, q.isNotEmpty(), { p -> folder = p; render() }, { render() }), LinearLayout.LayoutParams(MATCH, WRAP)) // 0.0.348: breadcrumb + sort (was folder title S24)
                if (q.isEmpty()) { // 0.0.328: folders of this level (top level or the sub folders of the open folder)
                    for (f in BmSort.folders(Bookmarks.childFolders(open ?: ""), all)) { // 0.0.348
                        box.addView(folderRow(f, all.count { Bookmarks.under(it.folder, f) }))
                        count++
                    }
                }
                val items = when {
                    q.isNotEmpty() -> all.filter { it.title.contains(q, true) || it.url.contains(q, true) }
                    open == null -> all.filter { it.folder.isEmpty() }
                    else -> all.filter { it.folder == open }
                }
                for (b in BmSort.apply(items)) { box.addView(row(b, Util.host(b.url))); visible.add(keyOf(b)) } // 0.0.332: Sort
                count += items.size
            } else {
                val fmt = java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT)
                val all = (if (HistoryFilter.active) HistoryFilter.apply(Bookmarks.history()) else Bookmarks.history()).take(300) // 0.0.330
                empty?.text = if (HistoryFilter.active || q.isNotEmpty()) "No history matches" else "No history yet"
                val items = if (q.isEmpty()) all else all.filter { it.title.contains(q, true) || it.url.contains(q, true) } // S23
                for (b in items) { box.addView(row(b, Util.host(b.url) + " · " + fmt.format(java.util.Date(b.time)))); visible.add(keyOf(b)) }
                count = items.size
            }
            empty?.visibility = if (count == 0) View.VISIBLE else View.GONE
            if (count > 0) box.addView(TextView(act).apply {
                text = if (selecting) "Tap to select or unselect" else "Long press an item to select"
                setTextColor(act.getColor(R.color.mg_text_dim))
                textSize = 12f
                gravity = Gravity.CENTER
                setPadding(dp(16), dp(16), dp(16), dp(24))
            })
        }

        /** S24: one folder line (icon, name, count). Tap = open, long press = delete folder. */
        private fun folderRow(name: String, n: Int): View {
            val r = LinearLayout(act).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(60)
                setPadding(dp(12), dp(8), dp(12), dp(8))
                layoutParams = BmUi.cardParams(act) // 0.0.348: card row
                background = BmUi.rippleCard(act)
            }
            r.addView(BmUi.folderTile(act), LinearLayout.LayoutParams(dp(36), dp(36)))
            r.addView(TextView(act).apply {
                text = Bookmarks.shortName(name)
                setTextColor(act.getColor(R.color.mg_text))
                textSize = 15f
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                setPadding(dp(14), 0, dp(8), 0)
            }, LinearLayout.LayoutParams(0, WRAP, 1f))
            r.addView(BmUi.countPill(act, n)) // 0.0.348
            r.addView(BmUi.chevron(act))
            r.setOnClickListener { folder = name; render() }
            r.setOnLongClickListener {
                MaterialAlertDialogBuilder(act)
                    .setTitle(name)
                    .setItems(arrayOf("Rename folder", "Delete folder and sub folders (bookmarks are kept)")) { _, w ->
                        if (w == 1) { val snap = Bookmarks.folderSnap(name); Bookmarks.removeFolder(name); render(); BmUndo.folder(window?.decorView, name, snap) { render() } } // 0.0.332
                        else { // 0.0.329
                            val et = EditText(act).apply { setText(Bookmarks.shortName(name)); setSingleLine(); setSelection(text.length) }
                            val bx = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(8), dp(24), 0); addView(et, LinearLayout.LayoutParams(MATCH, WRAP)) }
                            MaterialAlertDialogBuilder(act).setTitle("Rename folder").setView(bx)
                                .setPositiveButton("Rename") { _, _ ->
                                    val r = Bookmarks.renameFolder(name, et.text.toString())
                                    if (r.isEmpty()) Util.toast(act, "Name is empty or already used")
                                    else { val cur = folder; if (cur != null && Bookmarks.under(cur, name)) folder = r + cur.substring(name.length); render() }
                                }.setNegativeButton("Cancel", null).show()
                        }
                    }
                    .show()
                true
            }
            return r
        }

        /** S24: ask for a folder name, create it, then done(name). */
        private fun askNewFolder(done: (String) -> Unit) {
            val box = LinearLayout(act).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(24), dp(8), dp(24), 0)
            }
            val et = EditText(act).apply { hint = "Folder name"; setSingleLine() }
            box.addView(et, LinearLayout.LayoutParams(MATCH, WRAP))
            MaterialAlertDialogBuilder(act)
                .setTitle("New folder")
                .setView(box)
                .setPositiveButton("Create") { _, _ ->
                    val n = Bookmarks.addFolder(et.text.toString())
                    if (n.isNotEmpty()) done(n)
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        private fun askMove(b: Bookmarks.Item) {
            BookmarkFolderPicker.pick(act, "Move to", b.folder) { f -> Bookmarks.setFolder(b.url, f); render() } // 0.0.328
        }

        /** S78: site icon (saved by the tabs or downloaded once from the site) on a soft tile; first letter until then. */
        private fun favTile(url: String): View {
            val host = Util.host(url)
            val hue = (kotlin.math.abs(host.hashCode()) % 360).toFloat()
            val tint = android.graphics.Color.HSVToColor(floatArrayOf(hue, 0.45f, 0.85f))
            val box = android.widget.FrameLayout(act)
            box.background = android.graphics.drawable.GradientDrawable().apply {
                setColor(androidx.core.graphics.ColorUtils.blendARGB(act.getColor(R.color.mg_surface), tint, 0.25f))
                cornerRadius = dp(10).toFloat()
            }
            val letter = TextView(act).apply {
                text = host.firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()?.toString() ?: "?"
                textSize = 15f
                gravity = Gravity.CENTER
                setTextColor(act.getColor(R.color.mg_text))
            }
            val iv = ImageView(act).apply {
                scaleType = ImageView.ScaleType.FIT_CENTER
                setPadding(dp(6), dp(6), dp(6), dp(6))
                visibility = View.GONE
            }
            box.addView(letter, android.widget.FrameLayout.LayoutParams(MATCH, MATCH))
            box.addView(iv, android.widget.FrameLayout.LayoutParams(MATCH, MATCH))
            fun showIcon(bm: android.graphics.Bitmap) {
                iv.setImageBitmap(bm)
                iv.visibility = View.VISIBLE
                letter.visibility = View.GONE
            }
            val have = Favicons.get(host)
            if (have != null) showIcon(have) else Favicons.fetch(host) { bm -> showIcon(bm) }
            return box
        }

        private fun row(b: Bookmarks.Item, sub: String): View {
            val key = keyOf(b)
            val isOn = selecting && picked.contains(key)
            val r = LinearLayout(act).apply {
                orientation = LinearLayout.HORIZONTAL
                minimumHeight = dp(60)
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), dp(8), dp(14), dp(8))
                layoutParams = BmUi.cardParams(act) // 0.0.348: card row
                background = BmUi.rippleCard(act, isOn)
            }
            if (selecting) { // S27: round check box
                r.addView(ImageView(act).apply {
                    if (isOn) {
                        setImageResource(R.drawable.ic_check)
                        setColorFilter(act.getColor(R.color.mg_on_primary))
                        setPadding(dp(3), dp(3), dp(3), dp(3))
                        background = android.graphics.drawable.GradientDrawable().apply {
                            shape = android.graphics.drawable.GradientDrawable.OVAL
                            setColor(act.getColor(R.color.mg_primary))
                        }
                    } else {
                        background = android.graphics.drawable.GradientDrawable().apply {
                            shape = android.graphics.drawable.GradientDrawable.OVAL
                            setStroke(dp(2), act.getColor(R.color.mg_outline))
                        }
                    }
                }, LinearLayout.LayoutParams(dp(22), dp(22)).apply { marginEnd = dp(14) })
            }
            r.addView(favTile(b.url), LinearLayout.LayoutParams(dp(34), dp(34)).apply { marginEnd = dp(14) }) // S78
            val col = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
            col.addView(TextView(act).apply {
                text = b.title.ifBlank { Util.host(b.url) }
                setTextColor(act.getColor(R.color.mg_text))
                textSize = 15f
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            })
            col.addView(TextView(act).apply {
                text = sub
                setTextColor(act.getColor(R.color.mg_text_dim))
                textSize = 12f
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            })
            r.addView(col, LinearLayout.LayoutParams(0, WRAP, 1f))
            r.setOnClickListener { if (selecting) toggle(key) else { tabs.openUrl(b.url); dismiss() } }
            r.setOnLongClickListener { // S27: long press starts selecting
                if (selecting) toggle(key) else { selecting = true; picked.clear(); picked.add(key); build() }
                true
            }
            return r
        }
    }
}
