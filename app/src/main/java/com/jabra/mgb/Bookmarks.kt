package com.jabra.mgb

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

/**
 * S13: bookmarks (also the speed dial tiles) and browsing history, stored as JSON in filesDir.
 *  - mgb_bookmarks.json: seeded with a few sites on the first run, then fully user controlled
 *  - mgb_history.json: newest first, max MAX_HISTORY entries, same url twice in a row = one entry
 * All calls are cheap and safe from the main thread; the disk write runs on one background thread.
 */
object Bookmarks {
    class Item(val title: String, val url: String, val time: Long, val folder: String = "") // S24: folder "" = no folder
    /** S28: one home screen tile. folder != "" = shortcut to that bookmark folder, else a site (title, url). */
    class Home(val title: String, val url: String, val folder: String = "")

    private const val MAX_HISTORY = 500
    private const val F_BM = "mgb_bookmarks.json"
    private const val F_HIST = "mgb_history.json"
    private const val F_FOL = "mgb_folders.json" // S24: folder names (also empty ones)
    private const val F_HOME = "mgb_home.json" // S28: what the home screen shows (pinned, not all bookmarks)

    private var dir: File? = null
    private val bm = ArrayList<Item>()
    private val hist = ArrayList<Item>()
    private val fol = ArrayList<String>() // S24
    private val hm = ArrayList<Home>() // S28
    private val io = Executors.newSingleThreadExecutor()

    @Synchronized
    fun init(ctx: Context) {
        if (dir != null) return
        val d = ctx.applicationContext.filesDir
        dir = d
        val fb = File(d, F_BM)
        if (fb.exists()) read(fb, bm) else {
            val now = System.currentTimeMillis()
            bm.add(Item("Google", "https://www.google.com", now))
            bm.add(Item("YouTube", "https://www.youtube.com", now))
            bm.add(Item("Wikipedia", "https://www.wikipedia.org", now))
            bm.add(Item("GitHub", "https://github.com", now))
            saveAsync(F_BM, bm)
        }
        read(File(d, F_HIST), hist)
        try { // S24
            val ff = File(d, F_FOL)
            if (ff.exists()) {
                val arr = JSONArray(ff.readText())
                for (i in 0 until arr.length()) arr.optString(i).trim().let { if (it.isNotEmpty() && it !in fol) fol.add(it) }
            }
        } catch (e: Exception) { /* broken file: no folders */ }
        loadHome() // S28
    }

    private fun read(f: File, out: ArrayList<Item>) {
        try {
            if (!f.exists()) return
            val arr = JSONArray(f.readText())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val u = o.optString("u")
                if (u.isNotEmpty()) out.add(Item(o.optString("t"), u, o.optLong("w"), o.optString("f")))
            }
        } catch (e: Exception) { /* broken file: start empty */ }
    }

    private fun saveAsync(name: String, list: List<Item>) {
        val d = dir ?: return
        val arr = JSONArray()
        for (x in list) arr.put(JSONObject().put("t", x.title).put("u", x.url).put("w", x.time).put("f", x.folder))
        val text = arr.toString()
        io.execute {
            try {
                val tmp = File(d, "$name.tmp")
                tmp.writeText(text)
                if (!tmp.renameTo(File(d, name))) { File(d, name).writeText(text); tmp.delete() }
            } catch (e: Exception) { /* storage full etc: keep running */ }
        }
    }

    // ---------- bookmarks ----------

    @Synchronized fun bookmarks(): List<Item> = bm.toList()

    @Synchronized fun isBookmarked(url: String): Boolean = bm.any { it.url == url }

    @Synchronized
    fun addBookmark(title: String, url: String) {
        if (url.isBlank() || bm.any { it.url == url }) return
        bm.add(Item(title.ifBlank { Util.host(url) }, url, System.currentTimeMillis()))
        saveAsync(F_BM, bm)
    }

    @Synchronized
    fun removeBookmark(url: String) {
        if (bm.removeAll { it.url == url }) saveAsync(F_BM, bm)
    }

    // ---------- 0.0.332: undo helpers (BmUndo) ----------

    /** Folder (with its sub folders) and the bookmarks inside it, taken before removeFolder. */
    class FolderSnap(val folders: List<String>, val items: List<Item>)

    @Synchronized fun item(url: String): Item? = bm.firstOrNull { it.url == url }

    /** Puts removed bookmarks back in time order; the old folder is made again. A url that exists already is skipped. */
    @Synchronized
    fun restoreBookmarks(items: List<Item>) {
        var n = 0
        for (x in items) {
            if (x.url.isBlank() || bm.any { it.url == x.url }) continue
            val fo = if (x.folder.isEmpty()) "" else addFolder(x.folder)
            val back = Item(x.title, x.url, x.time, fo)
            val at = bm.indexOfFirst { it.time > x.time }
            if (at < 0) bm.add(back) else bm.add(at, back)
            n++
        }
        if (n > 0) saveAsync(F_BM, bm)
    }

    @Synchronized fun folderSnap(name: String): FolderSnap = FolderSnap(fol.filter { under(it, name) }, bm.filter { under(it.folder, name) })

    /** Undo of removeFolder: folders again, and the bookmarks that are still outside any folder go back in. */
    @Synchronized
    fun restoreFolder(s: FolderSnap) {
        for (f in s.folders) addFolder(f)
        var n = 0
        for (x in s.items) {
            val i = bm.indexOfFirst { it.url == x.url }
            if (i >= 0 && bm[i].folder.isEmpty()) { bm[i] = Item(bm[i].title, bm[i].url, bm[i].time, x.folder); n++ }
        }
        if (n > 0) saveAsync(F_BM, bm)
    }

    @Synchronized
    fun restoreHistory(items: List<Item>) {
        var n = 0
        for (x in items) if (hist.none { it.time == x.time && it.url == x.url }) { hist.add(x); n++ }
        if (n == 0) return
        hist.sortByDescending { it.time }
        while (hist.size > MAX_HISTORY) hist.removeAt(hist.size - 1)
        saveAsync(F_HIST, hist)
    }

    // ---------- S24: folders ----------

    private fun saveFolders() {
        val d = dir ?: return
        val text = JSONArray(fol).toString()
        io.execute {
            try { File(d, F_FOL).writeText(text) } catch (e: Exception) { /* keep running */ }
        }
    }

    @Synchronized fun folders(): List<String> = fol.toList()

    /** Returns the folder name (existing one if the name is already used), or "" for a blank name. */
    @Synchronized
    fun addFolder(name: String): String {
        // 0.0.328: a sub folder is a path "A/B" (each part max 40 chars); the parents are made too
        val parts = name.split('/').map { it.trim().take(40) }.filter { it.isNotEmpty() }.take(6)
        if (parts.isEmpty()) return ""
        var path = ""
        var made = false
        for (part in parts) {
            val want = if (path.isEmpty()) part else "$path/$part"
            val have = fol.firstOrNull { it.equals(want, true) }
            if (have != null) path = have else { fol.add(want); path = want; made = true }
        }
        if (made) saveFolders()
        return path
    }

    /** 0.0.329: rename one folder (last part of the path); sub folders, bookmarks and Home pins move with it. Returns the new path, or "" when the name is empty or taken. */
    @Synchronized
    fun renameFolder(old: String, newName: String): String {
        val n = newName.replace('/', ' ').trim().take(40)
        if (n.isEmpty() || old !in fol) return ""
        val parent = old.substringBeforeLast('/', "")
        val np = if (parent.isEmpty()) n else "$parent/$n"
        if (np == old) return old
        if (fol.any { it.equals(np, true) && !it.equals(old, true) }) return ""
        fun mv(f: String) = if (f == old) np else if (f.startsWith("$old/")) np + f.substring(old.length) else f
        val f2 = fol.map { mv(it) }; fol.clear(); fol.addAll(f2)
        val b2 = bm.map { if (it.folder.isEmpty()) it else Item(it.title, it.url, it.time, mv(it.folder)) }; bm.clear(); bm.addAll(b2)
        val h2 = hm.map { if (it.folder.isEmpty()) it else Home(it.title, it.url, mv(it.folder)) }; hm.clear(); hm.addAll(h2)
        saveFolders(); saveAsync(F_BM, bm); saveHome()
        return np
    }

    /** 0.0.328: sub folder helpers (paths "A/B"). */
    fun shortName(path: String): String = path.substringAfterLast('/')
    fun under(f: String, root: String): Boolean = f == root || f.startsWith("$root/")
    fun ancestors(f: String): List<String> { val out = ArrayList<String>(); var p = f; while (p.contains('/')) { p = p.substringBeforeLast('/'); out.add(p) }; return out }
    /** Direct children of [parent] ("" = top level), as full paths. */
    @Synchronized fun childFolders(parent: String): List<String> =
        fol.filter { if (parent.isEmpty()) !it.contains('/') else it.startsWith("$parent/") && !it.substring(parent.length + 1).contains('/') }

    /** Deletes the folder; its bookmarks go back to "no folder". */
    @Synchronized
    fun removeFolder(name: String) {
        if (!fol.removeAll { under(it, name) }) return // 0.0.328: sub folders go too
        val moved = ArrayList<Item>()
        for (x in bm) moved.add(if (under(x.folder, name)) Item(x.title, x.url, x.time, "") else x)
        bm.clear()
        bm.addAll(moved)
        saveAsync(F_BM, bm)
        saveFolders()
    }

    @Synchronized
    fun setFolder(url: String, folder: String) {
        val i = bm.indexOfFirst { it.url == url }
        if (i < 0) return
        val x = bm[i]
        bm[i] = Item(x.title, x.url, x.time, folder)
        saveAsync(F_BM, bm)
    }

    // ---------- S18: import / export ----------

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private fun unesc(s: String) = s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
        .replace("&#39;", "'").replace("&#x27;", "'").replace("&amp;", "&")

    private val A_TAG = Regex("<a\\s[^>]*?href\\s*=\\s*([\"'])(.*?)\\1[^>]*>(.*?)</a>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val ANY_TAG = Regex("<[^>]*>")
    private val TOKEN = Regex("<H3[^>]*>.*?</H3>|<DL[^>]*>|</DL>|<a\\s[^>]*?href\\s*=.*?</a>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val ROOT_NAMES = setOf("bookmarks", "bookmarks bar", "bookmarks toolbar", "other bookmarks", "mobile bookmarks", "favorites", "favorites bar", "menu", "toolbar")

    /** Standard browser bookmark file (Netscape HTML): opens in Chrome, Firefox, Edge ... */
    @Synchronized
    fun exportHtml(): String {
        val sb = StringBuilder()
        sb.append("<!DOCTYPE NETSCAPE-Bookmark-file-1>\n")
        sb.append("<META HTTP-EQUIV=\"Content-Type\" CONTENT=\"text/html; charset=UTF-8\">\n")
        sb.append("<TITLE>Bookmarks</TITLE>\n<H1>Bookmarks</H1>\n<DL><p>\n")
        fun line(x: Item, pad: String) {
            sb.append(pad).append("<DT><A HREF=\"").append(esc(x.url)).append("\" ADD_DATE=\"").append(x.time / 1000).append("\">")
                .append(esc(x.title.ifBlank { x.url })).append("</A>\n")
        }
        for (x in bm) if (x.folder.isEmpty()) line(x, "    ")
        fun walk(f: String, depth: Int) { // S25 + 0.0.329: folders as nested lists, sub folders inside their folder
            val pad = "    ".repeat(depth)
            sb.append(pad).append("<DT><H3>").append(esc(shortName(f))).append("</H3>\n").append(pad).append("<DL><p>\n")
            for (x in bm) if (x.folder == f) line(x, pad + "    ")
            for (k in childFolders(f)) walk(k, depth + 1)
            sb.append(pad).append("</DL><p>\n")
        }
        for (f in childFolders("")) walk(f, 1)
        sb.append("</DL><p>\n")
        return sb.toString()
    }

    /**
     * Reads a browser bookmark HTML file, or a JSON list (own mgb_bookmarks.json style: t/u or title/url).
     * Only http(s) links, duplicates skipped, max 2000 bookmarks. Returns how many were added.
     */
    @Synchronized
    fun importText(text: String): Int {
        val found = ArrayList<Triple<String, String, String>>() // title, url, folder (S25)
        val t = text.trimStart('\uFEFF', ' ', '\n', '\r', '\t')
        if (t.startsWith("[") || t.startsWith("{")) {
            val arr = try { if (t.startsWith("[")) JSONArray(t) else JSONObject(t).optJSONArray("bookmarks") } catch (e: Exception) { null }
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val u = o.optString("u", o.optString("url"))
                    if (u.isNotEmpty()) found.add(Triple(o.optString("t", o.optString("title")), u, o.optString("f", o.optString("folder"))))
                }
            }
        } else {
            // S25: walk <H3> (folder name), <DL> / </DL> (nesting) and <A> in file order
            val stack = ArrayList<String>()
            var pending: String? = null
            for (m in TOKEN.findAll(text)) {
                val g = m.value
                when {
                    g.startsWith("<H3", true) -> pending = unesc(g.replace(ANY_TAG, "").trim())
                    g.startsWith("<DL", true) -> { // 0.0.329: stack holds folder PATHS ("A/B"); browser root names add nothing
                        val parent = stack.lastOrNull().orEmpty()
                        val pn = pending?.replace('/', '-')?.trim()
                        stack.add(if (pn.isNullOrEmpty()) parent else if (parent.isEmpty() && pn.lowercase() in ROOT_NAMES) "" else if (parent.isEmpty()) pn else "$parent/$pn")
                        pending = null
                    }
                    g.startsWith("</DL", true) -> if (stack.isNotEmpty()) stack.removeAt(stack.size - 1)
                    else -> {
                        val am = A_TAG.find(g) ?: continue
                        var f = stack.lastOrNull().orEmpty()
                        found.add(Triple(unesc(am.groupValues[3].replace(ANY_TAG, "").trim()), unesc(am.groupValues[2].trim()), f))
                    }
                }
            }
        }
        var n = 0
        val now = System.currentTimeMillis()
        for ((title, u, fo) in found) {
            if (bm.size >= 2000) break
            if (!(u.startsWith("http://") || u.startsWith("https://"))) continue
            if (bm.any { it.url == u }) continue
            val fn = if (fo.isBlank() || fol.size >= 200 && fol.none { it.equals(fo.trim(), true) }) "" else addFolder(fo)
            bm.add(Item(title.ifBlank { Util.host(u) }, u, now, fn))
            n++
        }
        if (n > 0) saveAsync(F_BM, bm)
        return n
    }

    // ---------- history ----------

    @Synchronized fun history(): List<Item> = hist.toList()

    @Synchronized
    fun addHistory(title: String, url: String) {
        if (url.isBlank()) return
        val now = System.currentTimeMillis()
        val top = hist.firstOrNull()
        if (top != null && top.url == url) hist.removeAt(0)
        hist.add(0, Item(title.ifBlank { Util.host(url) }, url, now))
        while (hist.size > MAX_HISTORY) hist.removeAt(hist.size - 1)
        saveAsync(F_HIST, hist)
    }

    @Synchronized
    fun removeHistory(time: Long, url: String) {
        if (hist.removeAll { it.time == time && it.url == url }) saveAsync(F_HIST, hist)
    }

    /** 0.0.384: remove many history rows with ONE save. Returns how many were removed. */
    @Synchronized
    fun removeHistoryMany(items: Collection<Item>): Int {
        val keys = HashSet<Pair<Long, String>>()
        for (x in items) keys.add(x.time to x.url)
        val before = hist.size
        hist.removeAll { (it.time to it.url) in keys }
        val n = before - hist.size
        if (n > 0) saveAsync(F_HIST, hist)
        return n
    }

    @Synchronized
    fun clearHistory() {
        hist.clear()
        saveAsync(F_HIST, hist)
    }

    // ---------- S28: home screen list ----------

    private fun loadHome() {
        val d = dir ?: return
        val f = File(d, F_HOME)
        if (!f.exists()) { // first run of this version: only the first 4 bookmarks, never a big import
            for (x in bm.filter { it.folder.isEmpty() }.take(4)) hm.add(Home(x.title, x.url))
            saveHome()
            return
        }
        try {
            val arr = JSONArray(f.readText())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val u = o.optString("u")
                val fo = o.optString("f")
                if (u.isNotEmpty() || fo.isNotEmpty()) hm.add(Home(o.optString("t"), u, fo))
            }
        } catch (e: Exception) { /* broken file: empty home */ }
    }

    private fun saveHome() {
        val d = dir ?: return
        val arr = JSONArray()
        for (x in hm) arr.put(JSONObject().put("t", x.title).put("u", x.url).put("f", x.folder))
        val text = arr.toString()
        io.execute {
            try { File(d, F_HOME).writeText(text) } catch (e: Exception) { /* keep running */ }
        }
    }

    @Synchronized fun home(): List<Home> = hm.toList()

    @Synchronized fun isHome(url: String): Boolean = hm.any { it.folder.isEmpty() && it.url == url }

    @Synchronized
    fun addHome(title: String, url: String) {
        if (url.isBlank() || hm.any { it.folder.isEmpty() && it.url == url }) return
        hm.add(Home(title.ifBlank { Util.host(url) }, url))
        saveHome()
    }

    @Synchronized
    fun addHomeFolder(name: String) {
        if (name.isBlank() || hm.any { it.folder == name }) return
        hm.add(Home(name, "", name))
        saveHome()
    }

    @Synchronized
    fun removeHome(url: String) {
        if (hm.removeAll { it.folder.isEmpty() && it.url == url }) saveHome()
    }

    @Synchronized
    fun removeHomeFolder(name: String) {
        if (hm.removeAll { it.folder == name }) saveHome()
    }

    // ---------- S29: backup (export / import data) ----------

    @Synchronized
    fun backupJson(withBm: Boolean, withHome: Boolean, withHist: Boolean): JSONObject {
        val o = JSONObject()
        fun arr(list: List<Item>): JSONArray {
            val a = JSONArray()
            for (x in list) a.put(JSONObject().put("t", x.title).put("u", x.url).put("w", x.time).put("f", x.folder))
            return a
        }
        if (withBm) {
            o.put("bookmarks", arr(bm))
            o.put("folders", JSONArray(fol))
        }
        if (withHome) {
            val a = JSONArray()
            for (x in hm) a.put(JSONObject().put("t", x.title).put("u", x.url).put("f", x.folder))
            o.put("home", a)
        }
        if (withHist) o.put("history", arr(hist))
        return o
    }

    /** Merges a backup into the lists (no duplicates, never deletes). Returns a short summary text. */
    @Synchronized
    fun backupMerge(o: JSONObject, withBm: Boolean, withHome: Boolean, withHist: Boolean): String {
        val out = ArrayList<String>()
        if (withBm) {
            var nb = 0
            o.optJSONArray("folders")?.let { a ->
                for (i in 0 until a.length()) {
                    val n = a.optString(i).trim().take(40)
                    if (n.isNotEmpty() && fol.none { it.equals(n, true) } && fol.size < 200) fol.add(n)
                }
            }
            o.optJSONArray("bookmarks")?.let { a ->
                for (i in 0 until a.length()) {
                    val x = a.optJSONObject(i) ?: continue
                    val u = x.optString("u")
                    if (!u.startsWith("http") || bm.size >= 5000 || bm.any { it.url == u }) continue
                    val f = x.optString("f")
                    if (f.isNotEmpty() && fol.none { it == f } && fol.size < 200) fol.add(f)
                    bm.add(Item(x.optString("t").ifBlank { Util.host(u) }, u, x.optLong("w"), f))
                    nb++
                }
            }
            saveAsync(F_BM, bm)
            saveFolders()
            out.add("$nb bookmarks")
        }
        if (withHome) {
            var nh = 0
            o.optJSONArray("home")?.let { a ->
                for (i in 0 until a.length()) {
                    val x = a.optJSONObject(i) ?: continue
                    val u = x.optString("u")
                    val f = x.optString("f")
                    if (u.isEmpty() && f.isEmpty()) continue
                    if (hm.any { it.url == u && it.folder == f }) continue
                    hm.add(Home(x.optString("t"), u, f))
                    nh++
                }
            }
            saveHome()
            out.add("$nh home pins")
        }
        if (withHist) {
            var nh = 0
            o.optJSONArray("history")?.let { a ->
                for (i in 0 until a.length()) {
                    val x = a.optJSONObject(i) ?: continue
                    val u = x.optString("u")
                    val w = x.optLong("w")
                    if (u.isEmpty() || hist.any { it.url == u && it.time == w }) continue
                    hist.add(Item(x.optString("t").ifBlank { Util.host(u) }, u, w))
                    nh++
                }
            }
            hist.sortByDescending { it.time }
            while (hist.size > MAX_HISTORY) hist.removeAt(hist.size - 1)
            saveAsync(F_HIST, hist)
            out.add("$nh history entries")
        }
        return out.joinToString(", ")
    }
}
