package com.jabra.mgb

import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.provider.MediaStore
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** S29: recently closed tabs (max 30, saved in mgb_closed.json). */
object ClosedTabs {
    class Entry(val title: String, val url: String, val time: Long)

    private const val F = "mgb_closed.json"
    private const val MAX = 30
    private var dir: File? = null
    private val items = ArrayList<Entry>()

    @Synchronized
    fun init(ctx: Context) {
        if (dir != null) return
        val d = ctx.applicationContext.filesDir
        dir = d
        try {
            val f = File(d, F)
            if (f.exists()) {
                val a = JSONArray(f.readText())
                for (i in 0 until a.length()) {
                    val o = a.optJSONObject(i) ?: continue
                    val u = o.optString("u")
                    if (u.startsWith("http")) items.add(Entry(o.optString("t"), u, o.optLong("w")))
                }
            }
        } catch (e: Exception) { /* broken file: start empty */ }
    }

    @Synchronized
    fun toJson(): JSONArray {
        val a = JSONArray()
        for (x in items) a.put(JSONObject().put("t", x.title).put("u", x.url).put("w", x.time))
        return a
    }

    private fun save() {
        val d = dir ?: return
        val text = toJson().toString()
        Thread { try { File(d, F).writeText(text) } catch (e: Exception) { /* keep running */ } }.start()
    }

    @Synchronized fun list(): List<Entry> = items.toList()

    @Synchronized
    fun add(title: String, url: String) {
        if (!url.startsWith("http")) return
        items.removeAll { it.url == url }
        items.add(0, Entry(title.ifBlank { Util.host(url) }, url, System.currentTimeMillis()))
        while (items.size > MAX) items.removeAt(items.size - 1)
        save()
    }

    @Synchronized
    fun remove(url: String) {
        if (items.removeAll { it.url == url }) save()
    }

    @Synchronized
    fun clear() {
        items.clear()
        save()
    }

    /** Adds entries from a backup (no duplicates). Returns how many were new. */
    @Synchronized
    fun merge(a: JSONArray): Int {
        var n = 0
        for (i in 0 until a.length()) {
            val o = a.optJSONObject(i) ?: continue
            val u = o.optString("u")
            if (!u.startsWith("http") || items.any { it.url == u }) continue
            items.add(Entry(o.optString("t").ifBlank { Util.host(u) }, u, o.optLong("w")))
            n++
        }
        items.sortByDescending { it.time }
        while (items.size > MAX) items.removeAt(items.size - 1)
        if (n > 0) save()
        return n
    }
}

/**
 * S29: Export / Import data. One JSON file with the parts the user ticks:
 * bookmarks + folders, home pins, history, settings (prefs mgb_ui, mgb_adblock, mgb_site), recently closed tabs.
 * Import merges lists (no duplicates) and overwrites settings.
 */
object Backup {
    private val NAMES = arrayOf("Bookmarks and folders", "Home pins", "Browsing history", "Settings (theme, ad blocker, sites)", "Recently closed tabs")
    private val SETTINGS = arrayOf("mgb_ui", "mgb_adblock", "mgb_site", "mgb_userscripts", "mgb_us_values") // T7, T9

    // S42: automatic backup. Once a day when the app starts, all five parts, one file that is replaced each time.
    private const val AUTO_NAME = "mgb-auto-backup.json"
    fun autoOn(ctx: Context): Boolean = ctx.getSharedPreferences("mgb_ui", Context.MODE_PRIVATE).getBoolean("auto_backup", false)
    fun setAuto(ctx: Context, on: Boolean) { ctx.getSharedPreferences("mgb_ui", Context.MODE_PRIVATE).edit().putBoolean("auto_backup", on).apply() }

    fun auto(act: Activity) {
        val p = act.getSharedPreferences("mgb_ui", Context.MODE_PRIVATE)
        if (!p.getBoolean("auto_backup", false)) return
        val now = System.currentTimeMillis()
        if (now - p.getLong("auto_backup_t", 0L) < 24L * 3600L * 1000L) return
        val text = try { build(act, Orbit.parts(act)) } catch (e: Exception) { return }
        p.edit().putLong("auto_backup_t", now).apply()
        if (CloudSync.signedIn(act)) CloudSync.upload(act, text, false) { } // 0.0.311: also to Google Drive, never shows a Google screen
        val app = act.applicationContext
        Thread { try { writeAuto(app, text) } catch (e: Exception) { /* next start tries again after 24 h */ } }.start()
    }

    private fun writeAuto(ctx: Context, text: String) {
        if (Build.VERSION.SDK_INT >= 29) {
            val r = ctx.contentResolver
            val coll = MediaStore.Downloads.EXTERNAL_CONTENT_URI
            try {
                r.delete(coll, MediaStore.MediaColumns.DISPLAY_NAME + "=? AND " + MediaStore.MediaColumns.RELATIVE_PATH + " LIKE ?", arrayOf(AUTO_NAME, "Download/%"))
            } catch (e: Exception) { /* old copy stays, new one gets a (1) name */ }
            val v = ContentValues()
            v.put(MediaStore.MediaColumns.DISPLAY_NAME, AUTO_NAME)
            v.put(MediaStore.MediaColumns.MIME_TYPE, "application/json")
            v.put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/")
            val u = r.insert(coll, v) ?: return
            r.openOutputStream(u)?.use { it.write(text.toByteArray(Charsets.UTF_8)) }
        } else {
            val d = ctx.getExternalFilesDir(null) ?: return
            File(d, AUTO_NAME).writeText(text)
        }
    }

    fun exportData(act: Activity) {
        val sel = Orbit.parts(act) // 0.0.311: Passport switches pre-select the parts
        MaterialAlertDialogBuilder(act)
            .setTitle("Export data")
            .setMultiChoiceItems(NAMES, sel) { _, i, on -> sel[i] = on }
            .setPositiveButton("Export") { _, _ ->
                if (sel.any { it }) { BrowserExtras.saveBackup("mgb-backup.json") { build(act, sel) }; Util.toast(act, "The Vault is not in this file: it stays in its own folder") } // V12c
                else Util.toast(act, "Nothing selected")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun build(act: Activity, sel: BooleanArray): String {
        val o = Bookmarks.backupJson(sel[0], sel[1], sel[2])
        o.put("mgb", 1).put("app", BuildConfig.VERSION_NAME).put("time", System.currentTimeMillis())
        if (sel[3]) {
            val all = JSONObject()
            for (name in SETTINGS) {
                val p = act.getSharedPreferences(name, Context.MODE_PRIVATE)
                val e = JSONObject()
                for ((k, v) in p.all) {
                    val t = when (v) {
                        is Boolean -> "b"
                        is Int -> "i"
                        is Long -> "l"
                        is Float -> "f"
                        is String -> "s"
                        else -> null
                    } ?: continue
                    e.put(k, JSONObject().put("t", t).put("v", v))
                }
                all.put(name, e)
            }
            o.put("settings", all)
        }
        if (sel[4]) o.put("closed", ClosedTabs.toJson())
        return o.toString(2)
    }

    /** 0.0.311: the parts switched on in Passport, as text (Google Drive save). */
    fun passportJson(act: Activity): String = build(act, Orbit.parts(act))

    /** 0.0.290: the whole backup (all five parts) as text, for Cloud backup. */
    fun cloudJson(act: Activity): String = build(act, booleanArrayOf(true, true, true, true, true))

    fun importData(act: Activity) { BrowserExtras.loadBackup { text -> importText(act, text) } }

    /** 0.0.290: was the body of importData; also used by Cloud backup > Restore. */
    fun importText(act: Activity, text: String?) {
        run {
            val o = try { if (text == null) null else JSONObject(text) } catch (e: Exception) { null }
            if (o == null || o.optInt("mgb") != 1) {
                Util.toast(act, "This is not a Thazir backup file")
                return@run
            }
            val has = booleanArrayOf(
                o.has("bookmarks") || o.has("folders"), o.has("home"), o.has("history"), o.has("settings"), o.has("closed")
            )
            val idx = has.indices.filter { has[it] }
            if (idx.isEmpty()) {
                Util.toast(act, "This backup is empty")
                return@run
            }
            val labels = idx.map { NAMES[it] }.toTypedArray()
            val checked = BooleanArray(idx.size) { true }
            MaterialAlertDialogBuilder(act)
                .setTitle("Import data")
                .setMultiChoiceItems(labels, checked) { _, i, on -> checked[i] = on }
                .setPositiveButton("Import") { _, _ ->
                    val want = BooleanArray(5)
                    for (j in idx.indices) want[idx[j]] = checked[j]
                    applyBackup(act, o, want)
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    private fun applyBackup(act: Activity, o: JSONObject, want: BooleanArray) {
        val parts = ArrayList<String>()
        if (want[0] || want[1] || want[2]) parts.add(Bookmarks.backupMerge(o, want[0], want[1], want[2]))
        if (want[4]) o.optJSONArray("closed")?.let { parts.add(ClosedTabs.merge(it).toString() + " closed tabs") }
        var recreate = false
        if (want[3]) {
            val before = UiMode.mode(act)
            val accBefore = AccentTheme.id(act) // 0.0.291
            val all = o.optJSONObject("settings")
            if (all != null) {
                for (name in SETTINGS) {
                    val e = all.optJSONObject(name) ?: continue
                    val ed = act.getSharedPreferences(name, Context.MODE_PRIVATE).edit()
                    for (k in e.keys()) {
                        val x = e.optJSONObject(k) ?: continue
                        when (x.optString("t")) {
                            "b" -> ed.putBoolean(k, x.optBoolean("v"))
                            "i" -> ed.putInt(k, x.optInt("v"))
                            "l" -> ed.putLong(k, x.optLong("v"))
                            "f" -> ed.putFloat(k, x.optDouble("v").toFloat())
                            "s" -> ed.putString(k, x.optString("v"))
                        }
                    }
                    ed.apply()
                }
                AdBlock.init(act)
                SiteConfig.init(act)
                parts.add("settings")
                AccentTheme.reload() // 0.0.291: the restored accent is read again
                recreate = UiMode.mode(act) != before || AccentTheme.id(act) != accBefore
            }
        }
        BookmarksScreen.tabsRef?.syncHome()
        Util.toast(act, "Imported: " + parts.joinToString(", "))
        if (recreate) {
            UiMode.apply(act)
            UiMode.smoothRecreate(act) // 0.0.422
        }
    }

    /** Menu / Settings: list of recently closed tabs, tap = reopen in a new tab. */
    fun closedTabs(act: Activity, tabs: TabManager?) {
        val list = ClosedTabs.list()
        if (list.isEmpty()) {
            Util.toast(act, "No recently closed tabs")
            return
        }
        val labels = list.map { it.title.take(40) + "  |  " + Util.host(it.url) }.toTypedArray()
        MaterialAlertDialogBuilder(act)
            .setTitle("Recently closed tabs")
            .setItems(labels) { _, i ->
                val e = list[i]
                ClosedTabs.remove(e.url)
                tabs?.newTab(e.url)
            }
            .setPositiveButton("Clear list") { _, _ -> ClosedTabs.clear() }
            .setNegativeButton("Close", null)
            .show()
    }
}
