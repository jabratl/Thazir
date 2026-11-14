package com.jabra.mgb

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.widget.EditText
import android.widget.FrameLayout
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * S40: "Bookmark all tabs". One dialog: how many tabs, a folder name (default "Tabs 02 Nov 17:05"), Save.
 * Only normal web tabs (http / https) count: not private tabs, not the speed dial, not extension pages.
 * A page that is already bookmarked is skipped (Bookmarks.addBookmark ignores duplicates).
 */
object TabBookmarks {
    fun list(tm: TabManager): List<Pair<String, String>> {
        val seen = HashSet<String>()
        val out = ArrayList<Pair<String, String>>()
        for (t in tm.allTabs()) {
            if (t.priv || t.isExt || t.home) continue
            val u = (t.pending ?: t.url)
            if (!(u.startsWith("http://") || u.startsWith("https://")) || !seen.add(u)) continue
            out.add((t.title.ifBlank { Util.host(u) }) to u)
        }
        return out
    }

    /** S41: one message with every open web tab (title + address). */
    fun shareAll(ctx: Context, tm: TabManager) {
        val items = list(tm)
        if (items.isEmpty()) { Util.toast(ctx, "No web tabs to share"); return }
        tm.shareText(items.joinToString("\n\n") { it.first + "\n" + it.second })
    }

    fun dialog(ctx: Context, tm: TabManager) {
        val items = list(tm)
        if (items.isEmpty()) { Util.toast(ctx, "No web tabs to bookmark"); return }
        val fresh = items.count { !Bookmarks.isBookmarked(it.second) }
        val et = EditText(ctx).apply {
            setText("Tabs " + SimpleDateFormat("dd MMM HH:mm", Locale.getDefault()).format(Date()))
            inputType = InputType.TYPE_CLASS_TEXT
            setSelectAllOnFocus(true)
            setSingleLine()
        }
        val pad = Util.dp(ctx, 20)
        val box = FrameLayout(ctx).apply { setPadding(pad, Util.dp(ctx, 8), pad, 0); addView(et) }
        MaterialAlertDialogBuilder(ctx)
            .setTitle("Bookmark all tabs")
            .setMessage(
                if (fresh == 0) "All ${items.size} tabs are already in your bookmarks."
                else "$fresh new ${if (fresh == 1) "bookmark" else "bookmarks"} (${items.size - fresh} already saved are skipped). Folder name:"
            )
            .setView(box)
            .setPositiveButton("Save") { _, _ ->
                val n = save(items, et.text.toString())
                Util.toast(ctx, if (n == 0) "Nothing new to save" else "$n bookmarks saved")
            }
            .setNegativeButton("Cancel", null)
            .setNeutralButton("To vault") { _, _ -> toVault(ctx, items, et.text.toString()) } // V12c
            .show()
    }

    /** V12c: seal these tabs as vault bookmarks (copy only, the browser bookmarks are not touched). Needs the vault open. */
    private fun toVault(ctx: Context, items: List<Pair<String, String>>, folderName: String) {
        if (!VaultStore.isUnlocked()) { Util.toast(ctx, "Open the vault first (menu > Vault)"); return }
        val folder = folderName.trim()
        val now = System.currentTimeMillis()
        Util.toast(ctx, "Sealing...")
        Thread {
            val src = items.mapIndexed { i, p -> VaultItems.Src(VaultItems.E(p.first, p.second, now + i, folder), { }) }
            val r = try { VaultItems.addToVault(ctx, "bm", src, false) } catch (e: Exception) { VaultItems.Result(false, msg = "Something went wrong. Nothing was changed.") }
            Handler(Looper.getMainLooper()).post { Util.toast(ctx, VaultItems.describe("bm", r, false)) }
        }.start()
    }

    /** Returns how many bookmarks were added. An empty new folder is removed again. */
    fun save(items: List<Pair<String, String>>, folderName: String): Int {
        val existed = Bookmarks.folders().any { it.equals(folderName.trim(), true) }
        val f = Bookmarks.addFolder(folderName)
        var n = 0
        for ((t, u) in items) {
            if (Bookmarks.isBookmarked(u)) continue
            Bookmarks.addBookmark(t, u)
            if (f.isNotEmpty()) Bookmarks.setFolder(u, f)
            n++
        }
        if (n == 0 && f.isNotEmpty() && !existed) Bookmarks.removeFolder(f)
        return n
    }

    // ---------- T1: copy links / open links from the clipboard ----------

    /** Web address of a tab for copying: null for private, home and extension tabs and for non-web pages. */
    fun linkOf(t: TabManager.Tab): String? {
        if (t.priv || t.isExt || t.home) return null
        val u = t.pending ?: t.url
        return if (u.startsWith("http://") || u.startsWith("https://")) u else null
    }

    /** One address per line, no duplicates. Private tabs are never copied. */
    fun copyLinks(ctx: Context, tabs: List<TabManager.Tab>) {
        val seen = LinkedHashSet<String>()
        for (t in tabs) linkOf(t)?.let { seen.add(it) }
        if (seen.isEmpty()) { Util.toast(ctx, "No web tabs to copy"); return }
        try {
            (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                .setPrimaryClip(ClipData.newPlainText("MGB links", seen.joinToString("\n")))
            Util.toast(ctx, if (seen.size == 1) "1 link copied" else "${seen.size} links copied")
        } catch (e: Exception) { Util.toast(ctx, "Could not copy") }
    }

    private val URL_RX = Regex("(?i)\\b(?:https?://|www\\.)[^\\s<>\"'\\u0000-\\u001f]+")

    /**
     * Reads the clipboard (only now, when the row is tapped) and offers the links it finds.
     * Tabs open in the background and load when first selected. [priv] = the private view: tabs stay private.
     * [intoGroup] = the group panel that is open (new tabs join it). [canGroup] = offer "New group".
     */
    fun fromClipboard(act: Activity, tm: TabManager, priv: Boolean, intoGroup: String?, canGroup: Boolean, done: () -> Unit) {
        val text = try {
            val cm = act.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val c = cm.primaryClip
            if (c != null && c.itemCount > 0) c.getItemAt(0).coerceToText(act).toString() else ""
        } catch (e: Exception) { "" }
        val found = LinkedHashSet<String>()
        for (m in URL_RX.findAll(text)) {
            var u = m.value.trimEnd('.', ',', ';', ':', '!', '?', '\'', '"', ')', ']', '}', '>')
            if (u.startsWith("www.", true)) u = "https://$u"
            if (u.length in 12..2000 && Util.host(u).isNotEmpty()) found.add(u)
            if (found.size >= 100) break
        }
        if (found.isEmpty()) { Util.toast(act, "No links on the clipboard"); return }
        val room = TabManager.MAX_TABS - tm.count
        if (room <= 0) { Util.toast(act, "Too many tabs"); return }
        val open = tm.allTabs().filter { !it.isExt && !it.home && it.priv == priv }.map { it.pending ?: it.url }.toSet()
        val list = found.toList()
        val on = BooleanArray(list.size) { list[it] !in open }
        val names = Array(list.size) { i ->
            val u = list[i]
            val shown = u.removePrefix("https://").removePrefix("http://").removePrefix("www.")
            (if (shown.length > 70) shown.take(69) + "\u2026" else shown) + (if (!on[i]) "  (already open)" else "")
        }
        fun go(group: Boolean) {
            val pick = list.filterIndexed { i, _ -> on[i] }
            if (pick.isEmpty()) { Util.toast(act, "Nothing selected"); return }
            val made = ArrayList<TabManager.Tab>()
            for (u in pick) { val t = tm.newTab(u, 0, false, priv, true) ?: break; made.add(t) }
            if (made.isEmpty()) return
            if (intoGroup != null) TabSwitcher.groupTabs(intoGroup, made.map { it.id })
            else if (group) TabSwitcher.groupTabs("Links " + SimpleDateFormat("dd MMM HH:mm", Locale.getDefault()).format(Date()), made.map { it.id })
            val left = pick.size - made.size
            Util.toast(act, (if (made.size == 1) "Opened 1 tab" else "Opened ${made.size} tabs") + (if (left > 0) ", $left left out (tab limit)" else ""))
            done()
        }
        val b = MaterialAlertDialogBuilder(act)
            .setTitle(if (list.size == 1) "1 link found" else "${list.size} links found")
            .setMultiChoiceItems(names, on) { _, i, v -> on[i] = v }
            .setPositiveButton("Open") { _, _ -> go(false) }
            .setNegativeButton("Cancel", null)
        if (canGroup) b.setNeutralButton("New group") { _, _ -> go(true) }
        b.show()
    }
}
