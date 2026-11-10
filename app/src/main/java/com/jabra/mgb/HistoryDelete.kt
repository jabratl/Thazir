package com.jabra.mgb

import android.app.Activity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.text.DateFormat
import java.util.Calendar
import java.util.Date

/**
 * 0.0.384: delete history of ONE day or ONE site (Chrome: delete by day / "Delete all from this site"). NEW file.
 * Called from the History 3-dot menu in BookmarksScreen (2 new rows). After the delete the screen shows the same Undo bar as
 * "Clear history" (BmUndo.history). Only history rows are touched; bookmarks, passwords, cookies stay.
 */
object HistoryDelete {
    private fun dayKey(t: Long): Int { val c = Calendar.getInstance(); c.timeInMillis = t; return c.get(Calendar.YEAR) * 1000 + c.get(Calendar.DAY_OF_YEAR) }

    private fun dayLabel(t: Long): String {
        val now = System.currentTimeMillis()
        return when (dayKey(t)) {
            dayKey(now) -> "Today"
            dayKey(now - 86_400_000L) -> "Yesterday"
            else -> DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(t))
        }
    }

    /** done(removed items) is called on the main thread only when something was removed. */
    fun pickDay(act: Activity, done: (List<Bookmarks.Item>) -> Unit) {
        try {
            val all = Bookmarks.history()
            val groups = LinkedHashMap<Int, ArrayList<Bookmarks.Item>>() // newest day first (history is newest first)
            for (h in all) groups.getOrPut(dayKey(h.time)) { ArrayList() }.add(h)
            if (groups.isEmpty()) { Util.toast(act, "No history yet"); return }
            val days = groups.values.take(60)
            val labels = days.map { dayLabel(it[0].time) + "  \u00B7  " + it.size }.toTypedArray()
            MaterialAlertDialogBuilder(act).setTitle("Delete history of a day")
                .setItems(labels) { _, i -> if (i in days.indices) confirm(act, "Delete ${days[i].size} history rows of ${dayLabel(days[i][0].time)}?", days[i], done) }
                .setNegativeButton("Cancel", null).show()
        } catch (e: Exception) { Util.toast(act, "Could not open") }
    }

    fun pickSite(act: Activity, done: (List<Bookmarks.Item>) -> Unit) {
        try {
            val groups = LinkedHashMap<String, ArrayList<Bookmarks.Item>>()
            for (h in Bookmarks.history()) { val k = Util.host(h.url).lowercase(); if (k.isNotEmpty()) groups.getOrPut(k) { ArrayList() }.add(h) }
            if (groups.isEmpty()) { Util.toast(act, "No history yet"); return }
            val sites = groups.entries.sortedByDescending { it.value.size }.take(80)
            val labels = sites.map { it.key + "  \u00B7  " + it.value.size }.toTypedArray()
            MaterialAlertDialogBuilder(act).setTitle("Delete history of a site")
                .setItems(labels) { _, i -> if (i in sites.indices) confirm(act, "Delete all ${sites[i].value.size} history rows of ${sites[i].key}?", sites[i].value, done) }
                .setNegativeButton("Cancel", null).show()
        } catch (e: Exception) { Util.toast(act, "Could not open") }
    }

    private fun confirm(act: Activity, msg: String, items: List<Bookmarks.Item>, done: (List<Bookmarks.Item>) -> Unit) {
        val copy = items.toList()
        MaterialAlertDialogBuilder(act).setMessage(msg)
            .setPositiveButton("Delete") { _, _ ->
                try { if (Bookmarks.removeHistoryMany(copy) > 0) done(copy) } catch (e: Exception) { Util.toast(act, "Could not delete") }
            }
            .setNegativeButton("Cancel", null).show()
    }
}
