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

    /** S25: the 3 top pills. selected: 0 Bookmarks, 1 History, 2 Downloads. pick(i) is called on tap. */
    fun pills(act: Activity, selected: Int, pick: (Int) -> Unit): View {
        fun dp(v: Int) = Util.dp(act, v)
        fun round(color: Int, r: Int) = android.graphics.drawable.GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(r).toFloat()
        }
        val seg = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            background = round(act.getColor(R.color.mg_surface_variant), 22)
            setPadding(dp(3), dp(3), dp(3), dp(3))
        }
        val labels = arrayOf("Bookmarks", "History", "Downloads")
        for (i in labels.indices) {
            val on = i == selected
            seg.addView(TextView(act).apply {
                text = labels[i]
                gravity = Gravity.CENTER
                textSize = 13f
                maxLines = 1
                setTextColor(act.getColor(if (on) R.color.mg_on_primary else R.color.mg_text))
                if (on) background = round(act.getColor(R.color.mg_primary), 19)
                setOnClickListener { pick(i) }
            }, LinearLayout.LayoutParams(0, dp(38), 1f))
        }
        return seg
    }

    fun open(host: Activity, mode: Mode, tabs: TabManager) {
        if (host.isFinishing || host.isDestroyed) return
        closeAll()
        try {
            tabsRef = tabs // S25
            val s = Screen(host, mode, tabs)
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

    private class Screen(
        private val act: Activity,
        private var mode: Mode, // S23: switchable from the top pills
        private val tabs: TabManager
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

        init {
            setOnDismissListener { if (current === this) current = null }
        }

        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)
            build()
            window?.let {
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
            root.addView(header(), LinearLayout.LayoutParams(MATCH, dp(56)))
            root.addView(searchBox(), LinearLayout.LayoutParams(MATCH, dp(44)).apply {
                marginStart = dp(16); marginEnd = dp(16); topMargin = dp(4); bottomMargin = dp(8)
            })

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
            root.addView(sv, LinearLayout.LayoutParams(MATCH, 0, 1f))

            list = items
            empty = emptyView
            setContentView(root)
            render()
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
            background = round(act.getColor(R.color.mg_surface_variant), 22)
            setPadding(dp(16), 0, dp(16), 0)
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
                if (i == 2) { dismiss(); DownloadsScreen.open(act) }
                else {
                    val m = if (i == 0) Mode.BOOKMARKS else Mode.HISTORY
                    if (m != mode) { mode = m; query = ""; folder = null; build() }
                }
            }
            h.addView(seg, LinearLayout.LayoutParams(0, dp(44), 1f).apply { marginStart = dp(4); marginEnd = dp(4) })
            // 3-dot: Import / Export (bookmarks, S18) or Clear (history)
            h.addView(ImageView(act).apply {
                setImageResource(R.drawable.ic_more)
                setPadding(dp(10), dp(10), dp(10), dp(10))
                contentDescription = "More"
                setOnClickListener {
                    val labels = if (mode == Mode.BOOKMARKS) arrayOf("Select", "New folder", "Import bookmarks", "Export bookmarks") else arrayOf("Select", "Clear history")
                    MaterialAlertDialogBuilder(act).setItems(labels) { _, which ->
                        if (which == 0) { selecting = true; picked.clear(); build() } // S27
                        else if (mode == Mode.BOOKMARKS) {
                            when (which) {
                                1 -> BookmarkFolderPicker.askName(act, folder ?: "") { render() } // 0.0.328: inside an open folder = sub folder
                                2 -> BrowserExtras.importBookmarks { render() }
                                else -> BrowserExtras.exportBookmarks()
                            }
                        } else askClear()
                    }.show()
                }
            }, LinearLayout.LayoutParams(dp(44), dp(48)))
            return h
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
                .setMessage("Clear all browsing history?")
                .setPositiveButton("Clear") { _, _ -> Bookmarks.clearHistory(); render() }
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
                if (open != null && q.isEmpty()) { // S24: folder title
                    box.addView(TextView(act).apply {
                        text = open.replace("/", " > ")
                        setTextColor(act.getColor(R.color.mg_text))
                        textSize = 16f
                        setTypeface(typeface, android.graphics.Typeface.BOLD)
                        setPadding(dp(16), dp(4), dp(16), dp(8))
                    })
                }
                if (q.isEmpty()) { // 0.0.328: folders of this level (top level or the sub folders of the open folder)
                    for (f in Bookmarks.childFolders(open ?: "")) {
                        box.addView(folderRow(f, all.count { Bookmarks.under(it.folder, f) }))
                        count++
                    }
                }
                val items = when {
                    q.isNotEmpty() -> all.filter { it.title.contains(q, true) || it.url.contains(q, true) }
                    open == null -> all.filter { it.folder.isEmpty() }
                    else -> all.filter { it.folder == open }
                }
                for (b in items) { box.addView(row(b, Util.host(b.url))); visible.add(keyOf(b)) }
                count += items.size
            } else {
                val fmt = java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT)
                val all = Bookmarks.history().take(300)
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
                minimumHeight = dp(56)
                setPadding(dp(16), dp(8), dp(16), dp(8))
                layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
                val out = TypedValue()
                if (act.theme.resolveAttribute(android.R.attr.selectableItemBackground, out, true)) {
                    foreground = act.getDrawable(out.resourceId)
                }
            }
            r.addView(ImageView(act).apply {
                setImageResource(R.drawable.ic_folder)
                setColorFilter(act.getColor(R.color.mg_text_dim))
            }, LinearLayout.LayoutParams(dp(24), dp(24)))
            r.addView(TextView(act).apply {
                text = Bookmarks.shortName(name)
                setTextColor(act.getColor(R.color.mg_text))
                textSize = 15f
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                setPadding(dp(16), 0, dp(8), 0)
            }, LinearLayout.LayoutParams(0, WRAP, 1f))
            r.addView(TextView(act).apply {
                text = n.toString()
                setTextColor(act.getColor(R.color.mg_text_dim))
                textSize = 13f
            })
            r.setOnClickListener { folder = name; render() }
            r.setOnLongClickListener {
                MaterialAlertDialogBuilder(act)
                    .setTitle(name)
                    .setItems(arrayOf("Delete folder and sub folders (bookmarks are kept)")) { _, _ ->
                        Bookmarks.removeFolder(name)
                        render()
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
                setPadding(dp(16), dp(8), dp(16), dp(8))
                layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
                val out = TypedValue()
                if (act.theme.resolveAttribute(android.R.attr.selectableItemBackground, out, true)) {
                    foreground = act.getDrawable(out.resourceId)
                }
                if (isOn) setBackgroundColor(act.getColor(R.color.mg_surface))
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
