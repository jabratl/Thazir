package com.jabra.mgb

import android.app.Activity
import android.widget.EditText
import android.widget.LinearLayout
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * 0.0.328: "Save to which folder?" for bookmarks. Walks the folder tree (folders are paths "A/B", see Bookmarks):
 * choose the folder you are in, open a sub folder, go up, or make a new folder (sub folder of the folder you are in).
 * done(path) gets "" for no folder. Used when saving a bookmark and for Move.
 */
object BookmarkFolderPicker {
    fun pick(act: Activity, title: String, start: String = "", done: (String) -> Unit) {
        if (act.isFinishing || act.isDestroyed) return
        val cur = start
        val kids = Bookmarks.childFolders(cur)
        val labels = ArrayList<String>()
        val acts = ArrayList<() -> Unit>()
        labels.add(if (cur.isEmpty()) "Save here (no folder)" else "Save here"); acts.add { done(cur) }
        if (cur.isNotEmpty()) { labels.add("Up"); acts.add { pick(act, title, cur.substringBeforeLast('/', ""), done) } }
        for (k in kids) { labels.add(Bookmarks.shortName(k) + "  >"); acts.add { pick(act, title, k, done) } }
        labels.add("New folder..."); acts.add { askName(act, cur) { p -> done(p) } }
        MaterialAlertDialogBuilder(act)
            .setTitle(title + (if (cur.isEmpty()) "" else ": " + cur.replace("/", " > ")))
            .setItems(labels.toTypedArray()) { _, i -> acts[i]() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Name dialog; creates the folder under [parent] ("" = top level) and gives its path to done. */
    fun askName(act: Activity, parent: String, done: (String) -> Unit) {
        val box = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Util.dp(act, 24), Util.dp(act, 8), Util.dp(act, 24), 0)
        }
        val et = EditText(act).apply { hint = "Folder name"; setSingleLine() }
        box.addView(et, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        MaterialAlertDialogBuilder(act)
            .setTitle(if (parent.isEmpty()) "New folder" else "New folder in " + Bookmarks.shortName(parent))
            .setView(box)
            .setPositiveButton("Create") { _, _ ->
                val n = et.text.toString().replace("/", " ").trim()
                if (n.isNotEmpty()) { val p = Bookmarks.addFolder(if (parent.isEmpty()) n else "$parent/$n"); if (p.isNotEmpty()) done(p) }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Ask the folder, then save the bookmark there (already saved = just removed by the caller, so this is only for new ones). */
    fun save(act: Activity, title: String, url: String, after: () -> Unit = {}) {
        pick(act, "Save bookmark to") { f ->
            Bookmarks.addBookmark(title, url)
            if (f.isNotEmpty()) Bookmarks.setFolder(url, f)
            Util.toast(act, if (f.isEmpty()) "Bookmark added" else "Saved in " + Bookmarks.shortName(f))
            after()
        }
    }
}
