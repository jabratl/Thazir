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
    private var mode = 0

    fun apply(list: List<Bookmarks.Item>): List<Bookmarks.Item> = when (mode) {
        1 -> list.sortedByDescending { it.time }
        2 -> list.sortedBy { it.time }
        3 -> list.sortedBy { it.title.lowercase() }
        4 -> list.sortedWith(compareBy({ Util.host(it.url).lowercase() }, { it.title.lowercase() }))
        else -> list
    }

    fun pick(act: Activity, done: () -> Unit) {
        MaterialAlertDialogBuilder(act).setTitle("Sort bookmarks")
            .setSingleChoiceItems(NAMES, mode) { d, i -> mode = i; d.dismiss(); done() }
            .setNegativeButton("Cancel", null).show()
    }
}
