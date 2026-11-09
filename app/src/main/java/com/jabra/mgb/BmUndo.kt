package com.jabra.mgb

import android.app.Activity
import android.view.View
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar

/**
 * 0.0.332: "Removed ...  Undo" bar for bookmarks, folders and history (BookmarksScreen, Home tiles, link menu, star button).
 * Bar = Snackbar in the theme colours (mg_*), 6 s, swipe away like the tab bars. The data is put back by Bookmarks.restore*.
 * Also: BmSort, the Sort choice of the Bookmarks list (in memory only, default = added order as before).
 */
object BmUndo {
    private var last: Snackbar? = null

    fun show(host: View?, msg: String, undo: () -> Unit) {
        val v = host ?: return
        try {
            last?.dismiss()
            val c = v.context
            val bar = Snackbar.make(v, msg, 6000)
            bar.setAction("Undo") { undo() }
            bar.setBackgroundTint(c.getColor(R.color.mg_surface_variant))
            bar.setTextColor(c.getColor(R.color.mg_text))
            bar.setActionTextColor(c.getColor(R.color.mg_primary))
            last = bar
            bar.show()
            SwipeAway.snack(bar, TabPrefs.reduce(c))
            v.postDelayed({ try { bar.dismiss() } catch (_: Exception) { } }, 6500) // hard stop (accessibility services can keep a Snackbar longer)
        } catch (_: Exception) { }
    }

    fun bookmarks(host: View?, items: List<Bookmarks.Item>, after: () -> Unit = {}) {
        if (items.isEmpty()) return
        show(host, if (items.size == 1) "Bookmark removed" else "${items.size} bookmarks removed") { Bookmarks.restoreBookmarks(items); after() }
    }

    fun folder(host: View?, name: String, snap: Bookmarks.FolderSnap, after: () -> Unit = {}) {
        show(host, "Folder deleted: " + Bookmarks.shortName(name)) { Bookmarks.restoreFolder(snap); after() }
    }

    fun history(host: View?, items: List<Bookmarks.Item>, after: () -> Unit = {}) {
        if (items.isEmpty()) return
        show(host, if (items.size == 1) "History item removed" else "${items.size} history items removed") { Bookmarks.restoreHistory(items); after() }
    }
}

/** 0.0.332: Sort for the Bookmarks list (3-dot menu > Sort). Only the order of the bookmarks changes, never the stored data. */
object BmSort {
    private val NAMES = arrayOf("Added order", "Newest first", "Oldest first", "A to Z", "By site")
    /** 0.0.348: 0 manual, 1 newest, 2 oldest, 3 A-Z, 4 by site, 5 Z-A, 6 last opened. */
    var mode = 0
    /** 0.0.348: popup list of the sort button (label to mode). */
    val MENU = listOf("A-Z" to 3, "Z-A" to 5, "Newest" to 1, "Oldest" to 2, "Sort by last opened" to 6, "By site" to 4, "Sort by manual order" to 0)

    private fun lastOpened(): Map<String, Long> {
        val m = HashMap<String, Long>()
        for (h in Bookmarks.history()) if (h.time > (m[h.url] ?: 0L)) m[h.url] = h.time
        return m
    }

    fun apply(list: List<Bookmarks.Item>): List<Bookmarks.Item> = when (mode) {
        1 -> list.sortedByDescending { it.time }
        2 -> list.sortedBy { it.time }
        3 -> list.sortedBy { it.title.lowercase() }
        4 -> list.sortedWith(compareBy({ Util.host(it.url).lowercase() }, { it.title.lowercase() }))
        5 -> list.sortedByDescending { it.title.lowercase() }
        6 -> { val m = lastOpened(); list.sortedByDescending { m[it.url] ?: 0L } }
        else -> list
    }

    /** 0.0.348: folders follow the same order (by name, or by the newest / last opened bookmark inside). */
    fun folders(names: List<String>, all: List<Bookmarks.Item>): List<String> {
        val lo = if (mode == 6) lastOpened() else emptyMap()
        fun key(f: String): Long = all.filter { Bookmarks.under(it.folder, f) }.maxOfOrNull { if (mode == 6) (lo[it.url] ?: 0L) else it.time } ?: 0L
        return when (mode) {
            3 -> names.sortedBy { Bookmarks.shortName(it).lowercase() }
            5 -> names.sortedByDescending { Bookmarks.shortName(it).lowercase() }
            1, 6 -> names.sortedByDescending { key(it) }
            2 -> names.sortedBy { key(it) }
            else -> names
        }
    }

    fun pick(act: Activity, done: () -> Unit) {
        MaterialAlertDialogBuilder(act).setTitle("Sort bookmarks")
            .setSingleChoiceItems(NAMES, mode) { d, i -> mode = i; d.dismiss(); done() }
            .setNegativeButton("Cancel", null).show()
    }
}
