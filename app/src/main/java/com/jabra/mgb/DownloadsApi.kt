package com.jabra.mgb

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.lang.ref.WeakReference
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.locks.ReentrantLock

/** FileProvider with its own class name, so it can never clash with another provider in the manifest. */
class MgbFileProvider : FileProvider()

/** One download (same fields chrome.downloads.DownloadItem has, plus a few private ones). */
class DlItem(val id: Int, val url: String) {
    @Volatile var finalUrl = url
    var referrer = ""
    var rel = ""            // relative path below the Downloads folder, e.g. "MGB/Title.mp4"
    var filename = ""       // full display path once known
    var uri = ""            // content:// uri (Android 10+) or absolute path
    var mime = ""
    @Volatile var state = "in_progress"
    @Volatile var paused = false
    @Volatile var canResume = false
    var error = ""
    @Volatile var received = 0L
    @Volatile var total = -1L
    var fileSize = -1L
    var start = System.currentTimeMillis()
    var end = 0L
    var exists = true
    var validator = ""
    var conflict = "uniquify"
    var headers = JSONArray()
    var method = "GET"
    var body: String? = null
    var src: WeakReference<WebView>? = null          // page that owns a blob: url
    val lock = ReentrantLock()
    @Volatile var call: okhttp3.Call? = null
    @Volatile var plain = false                      // 0.0.325: started by a tab link / blob (DownloadListener); listed in the Downloads queue
}

/**
 * S7: chrome.downloads on top of ChromeBridge. Items live here (persisted in prefs mgb_downloads);
 * the transfer itself is Downloader (DownloadService.kt). S8 hook: headerProvider.
 */
object DownloadsApi {
    private const val PREFS = "mgb_downloads"
    private const val CH_RUN = "mgb_dl"
    private const val CH_DONE = "mgb_dl_done"
    private const val MAX_ITEMS = 600

    lateinit var app: Context
        private set
    private val items = LinkedHashMap<Int, DlItem>()
    private var nextId = 1
    private var inited = false
    private val main = Handler(Looper.getMainLooper())
    private var top: WeakReference<Activity>? = null

    /** S8 hook: extra request headers (Referer, Origin, Cookie...) for a download url. */
    var headerProvider: ((String) -> Map<String, String>?)? = null

    private fun handle(api: String, h: (ChromeBridge.Call) -> Unit) {
        ChromeBridge.on(api) { c ->
            main.post {
                try { h(c) } catch (e: Exception) { c.fail(e.message ?: e.toString()) }
            }
        }
    }

    // ---------- small helpers ----------

    fun publicDir(): File = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)

    fun cleanRel(raw: String): String {
        val bad = Regex("[\\u0000-\\u001f:*?\"<>|]")
        val out = ArrayList<String>()
        for (p0 in raw.replace('\\', '/').split('/')) {
            var p = p0.replace(bad, "_").trim().trimEnd('.', ' ')
            if (p.isEmpty() || p == "." || p == "..") continue
            if (p.length > 120) {
                val dot = p.lastIndexOf('.')
                p = if (dot > 0 && p.length - dot <= 8) p.substring(0, 120 - (p.length - dot)) + p.substring(dot) else p.take(120)
            }
            out.add(p)
        }
        return out.take(8).joinToString("/")
    }

    private fun iso(t: Long): String {
        val f = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
        f.timeZone = TimeZone.getTimeZone("UTC")
        return f.format(Date(t))
    }

    private fun displayPath(i: DlItem): String =
        if (i.filename.isNotEmpty()) i.filename else publicDir().absolutePath + "/" + i.rel

    private fun checkExists(i: DlItem): Boolean {
        if (i.state != "complete") return true
        if (i.uri.isEmpty()) return false
        return try {
            if (i.uri.startsWith("content:")) {
                val u = Uri.parse(i.uri)
                // S49: files in a picked folder are document uris (column document_id), Downloads files are media uris (_id)
                val col = if (u.authority == "media") "_id" else android.provider.DocumentsContract.Document.COLUMN_DOCUMENT_ID
                app.contentResolver.query(u, arrayOf(col), null, null, null)?.use { it.count > 0 } ?: false
            }
            else File(i.uri).exists()
        } catch (e: Exception) { false }
    }

    fun toJson(i: DlItem, withExists: Boolean = true): JSONObject {
        val o = JSONObject()
            .put("id", i.id).put("url", i.url).put("finalUrl", i.finalUrl).put("referrer", i.referrer)
            .put("filename", displayPath(i)).put("incognito", false).put("danger", "safe")
            .put("mime", i.mime).put("startTime", iso(i.start))
            .put("state", i.state).put("paused", i.paused).put("canResume", i.canResume)
            .put("bytesReceived", i.received).put("totalBytes", i.total)
            .put("fileSize", if (i.state == "complete") i.fileSize else i.total)
            .put("exists", if (withExists) checkExists(i) else i.exists)
        if (i.end > 0) o.put("endTime", iso(i.end))
        if (i.error.isNotEmpty()) o.put("error", i.error)
        return o
    }

    private fun emit(event: String, vararg args: Any?) {
        val a = JSONArray()
        for (x in args) a.put(x ?: JSONObject.NULL)
        main.post { ChromeBridge.emit(event, a) { it.kind == ChromeBridge.Kind.EXT } }
    }

    // ---------- store ----------

    private fun toStore(i: DlItem): JSONObject = JSONObject()
        .put("id", i.id).put("url", i.url).put("finalUrl", i.finalUrl).put("rel", i.rel)
        .put("filename", i.filename).put("uri", i.uri).put("mime", i.mime).put("state", i.state)
        .put("paused", i.paused).put("canResume", i.canResume).put("error", i.error)
        .put("received", i.received).put("total", i.total).put("fileSize", i.fileSize)
        .put("start", i.start).put("end", i.end).put("validator", i.validator)
        .put("conflict", i.conflict).put("headers", i.headers).put("method", i.method)
        .put("body", i.body ?: JSONObject.NULL).put("plain", i.plain)

    private fun fromStore(o: JSONObject): DlItem {
        val i = DlItem(o.getInt("id"), o.getString("url"))
        i.finalUrl = o.optString("finalUrl", i.url); i.rel = o.optString("rel"); i.filename = o.optString("filename")
        i.uri = o.optString("uri"); i.mime = o.optString("mime"); i.state = o.optString("state", "interrupted")
        i.paused = o.optBoolean("paused"); i.canResume = o.optBoolean("canResume"); i.error = o.optString("error")
        i.received = o.optLong("received"); i.total = o.optLong("total", -1); i.fileSize = o.optLong("fileSize", -1)
        i.start = o.optLong("start"); i.end = o.optLong("end"); i.validator = o.optString("validator")
        i.conflict = o.optString("conflict", "uniquify"); i.headers = o.optJSONArray("headers") ?: JSONArray()
        i.method = o.optString("method", "GET"); i.body = if (o.isNull("body")) null else o.optString("body")
        i.plain = o.optBoolean("plain")
        return i
    }

    private fun save() {
        val arr = JSONArray()
        synchronized(items) { for (i in items.values) arr.put(toStore(i)) }
        app.getSharedPreferences(PREFS, 0).edit().putString("items", arr.toString()).putInt("next", nextId).apply()
    }

    private fun load() {
        val p = app.getSharedPreferences(PREFS, 0)
        var maxId = 0
        try {
            val arr = JSONArray(p.getString("items", "[]"))
            for (n in 0 until arr.length()) {
                val i = fromStore(arr.getJSONObject(n))
                val http = i.url.startsWith("http", true)
                val part = Downloader.partFile(i.id)
                if (i.state == "in_progress") {
                    if (!(http && i.paused)) {   // a paused http download stays paused, everything else was cut by the process death
                        i.state = "interrupted"; i.error = "CRASH"; i.paused = false
                    }
                    i.canResume = http && part.exists()
                    if (!http) part.delete()
                }
                if (i.state != "in_progress" && i.state != "interrupted" && part.exists()) part.delete()
                items[i.id] = i
                if (i.id > maxId) maxId = i.id
            }
        } catch (e: Exception) { /* broken prefs: start empty */ }
        nextId = maxOf(p.getInt("next", 1), maxId + 1)
    }

    /** Change an item and tell the extension about it (chrome.downloads.onChanged delta). */
    fun change(item: DlItem, block: DlItem.() -> Unit) {
        val d = JSONObject()
        synchronized(items) {
            val st = item.state; val pa = item.paused; val er = item.error; val fn = item.filename
            val mi = item.mime; val tb = item.total; val fs = item.fileSize; val ex = item.exists
            val cr = item.canResume; val en = item.end
            item.block()
            fun diff(name: String, a: Any, b: Any) { if (a != b) d.put(name, JSONObject().put("previous", a).put("current", b)) }
            diff("state", st, item.state); diff("paused", pa, item.paused); diff("error", er, item.error)
            diff("filename", fn, item.filename); diff("mime", mi, item.mime); diff("totalBytes", tb, item.total)
            diff("fileSize", fs, item.fileSize); diff("exists", ex, item.exists); diff("canResume", cr, item.canResume)
            if (en != item.end) d.put("endTime", JSONObject().put("previous", if (en > 0) iso(en) else "").put("current", iso(item.end)))
            if (d.length() > 0) { d.put("id", item.id); save() }
        }
        if (d.length() > 0) emit("downloads.onChanged", d)
    }

    private fun newItem(url: String): DlItem = synchronized(items) {
        val n = DlItem(nextId++, url)
        items[n.id] = n
        DlFly.fire() // S21e: badge flies to the menu button
        if (items.size > MAX_ITEMS) {   // forget the oldest finished ones
            val drop = items.values.filter { it.state != "in_progress" }.take(items.size - MAX_ITEMS)
            for (d in drop) { items.remove(d.id); Downloader.partFile(d.id).delete() }
        }
        n
    }

    /**
     * S14: a plain link / file download started by a browser tab (WebView DownloadListener). Main thread.
     * Saved as Downloads/<name>; Referer, User-Agent and Cookie of the page are sent. src = the page WebView (needed for blob: urls).
     * Returns null when the url scheme is not supported.
     */
    private val KEEP_EXT = setOf("srt", "vtt", "ass", "ssa", "ttml", "dfxp", "sub", "sbv", "lrc", "smi")

    /**
     * 0.0.279: URLUtil.guessFileName throws away the real extension when the server says text/plain for a subtitle (Title.en.srt became
     * Title.txt). For subtitle extensions the name the site gave is kept; everything else = the old guess.
     */
    private fun plainName(url: String, disposition: String?, mime: String?): String {
        val g = android.webkit.URLUtil.guessFileName(url, disposition, mime)
        val raw = try { android.webkit.URLUtil.guessFileName(url, disposition, null) } catch (e: Exception) { g }
        return if (raw.substringAfterLast('.', "").lowercase() in KEEP_EXT) raw else g
    }

    fun startPlain(url: String, userAgent: String?, disposition: String?, mime: String?, referer: String?, src: WebView?): DlItem? {
        if (!listOf("http://", "https://", "data:", "blob:").any { url.startsWith(it, true) }) return null
        val item = newItem(url)
        item.plain = true // 0.0.325: shows in Downloads > Downloading (EngineApi.jobs)
        item.rel = cleanRel(plainName(url, disposition, mime))
        if (!mime.isNullOrBlank()) item.mime = mime
        val http = url.startsWith("http", true)
        val h = JSONArray()
        if (http) {
            if (!referer.isNullOrBlank() && referer.startsWith("http", true))
                h.put(JSONObject().put("name", "Referer").put("value", referer))
            if (!userAgent.isNullOrBlank()) h.put(JSONObject().put("name", "User-Agent").put("value", userAgent))
            val ck = try { android.webkit.CookieManager.getInstance().getCookie(url) } catch (e: Exception) { null }
            if (!ck.isNullOrBlank()) h.put(JSONObject().put("name", "Cookie").put("value", ck))
        }
        item.headers = h
        item.canResume = http
        if (url.startsWith("blob:", true) && src != null) item.src = WeakReference(src)
        save()
        emit("downloads.onCreated", toJson(item, false))
        askPerms()
        Downloader.start(item)
        return item
    }

    /**
     * 0.0.325: plain downloads (tab link / blob / data) as V77-job-shaped JSON (key nat:<id>) so the Downloads screen, Media screen
     * and report list them while they run. Newest 12 only. Thread safe.
     */
    fun plainJobs(): List<JSONObject> {
        val list = synchronized(items) { items.values.filter { it.plain && it.state != "complete" }.sortedByDescending { maxOf(it.start, it.end) }.take(12) }
        return list.map { i ->
            val st = when (i.state) { "in_progress" -> "run"; "complete" -> "done"; else -> "fail" }
            val name = (if (i.filename.isNotEmpty()) i.filename else i.rel).substringAfterLast('/').ifBlank { i.url.substringAfterLast('/').take(60) }
            val rec = i.received; val tot = i.total
            val text = when (st) {
                "run" -> if (tot > 0) EngineApi.fmtSize(rec) + " / " + EngineApi.fmtSize(tot) else EngineApi.fmtSize(rec)
                "done" -> ""
                else -> if (i.error == "USER_CANCELED") "Canceled" else i.error.ifBlank { "Failed" }
            }
            JSONObject().put("key", "nat:" + i.id).put("title", name).put("state", st).put("paused", i.paused && st == "run")
                .put("pct", if (tot > 0) (rec.toDouble() / tot).coerceIn(0.0, 1.0) else 0.0).put("text", text)
                .put("url", i.url).put("native", true).put("canResume", i.canResume && st == "fail").put("updated", if (st == "run") System.currentTimeMillis() else maxOf(i.start, i.end))
        }
    }

    /** 0.0.325: Pause / Resume / Cancel / Remove for a plain download row (same messages the V77 rows send). Main thread. */
    fun plainControl(msg: JSONObject) {
        val id = msg.optString("key").removePrefix("nat:").toIntOrNull() ?: return
        val i = synchronized(items) { items[id] } ?: return
        when (msg.optString("type")) {
            "job-pause" -> if (msg.optBoolean("on", true)) {
                if (i.state == "in_progress" && !i.paused) { change(i) { paused = true }; Downloader.abort(i) }
            } else if (i.state == "in_progress" && i.paused) {
                change(i) { paused = false }; Downloader.start(i)
            } else if (i.state == "interrupted" && i.canResume) {
                change(i) { state = "in_progress"; paused = false; error = ""; end = 0L }; Downloader.start(i)
            }
            "job-cancel" -> if (i.state == "in_progress") {
                change(i) { state = "interrupted"; error = "USER_CANCELED"; paused = false; canResume = false; end = System.currentTimeMillis() }
                Downloader.abort(i)
                Downloader.partFile(i.id).delete()
            }
            "job-remove" -> if (i.state != "in_progress") { i.plain = false; save() } // leaves the queue only; file + chrome.downloads item stay
        }
    }

    fun active(): List<DlItem> = synchronized(items) { items.values.filter { it.state == "in_progress" && !it.paused } }

    /** S45: downloads the user paused (still resumable, notification stays). */
    fun pausedList(): List<DlItem> = synchronized(items) { items.values.filter { it.state == "in_progress" && it.paused } }

    /** S45: notification buttons. Run on the main thread. */
    fun pauseAll() {
        for (i in active()) { change(i) { paused = true }; Downloader.abort(i) }
    }

    fun resumeAll() {
        for (i in pausedList()) { change(i) { paused = false }; Downloader.start(i) }
    }

    fun cancelAll() {
        val all = synchronized(items) { items.values.filter { it.state == "in_progress" } }
        for (i in all) {
            change(i) { state = "interrupted"; error = "USER_CANCELED"; paused = false; canResume = false; end = System.currentTimeMillis() }
            Downloader.abort(i)
            Downloader.partFile(i.id).delete()
        }
    }

    // ---------- notifications ----------

    @SuppressLint("MissingPermission")
    private fun notifyItem(item: DlItem, ok: Boolean) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ActivityCompat.checkSelfPermission(app, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        try {
            val name = displayPath(item).substringAfterLast('/')
            val b = NotificationCompat.Builder(app, CH_DONE)
                .setSmallIcon(if (ok) android.R.drawable.stat_sys_download_done else android.R.drawable.stat_notify_error)
                .setContentTitle(if (ok) "Download complete" else "Download failed")
                .setContentText(name)
                .setAutoCancel(true)
            val open = openIntent(item)
            if (ok && open != null) {
                b.setContentIntent(PendingIntent.getActivity(app, item.id, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            }
            NotificationManagerCompat.from(app).notify(10000 + item.id, b.build())
        } catch (e: Exception) { /* notifications are optional */ }
    }

    fun notifyDone(item: DlItem) { notifyItem(item, true); if (item.plain) addSaved(item) }

    /**
     * 0.0.326: a finished plain download goes into the Saved files list (storage.local mgHist, same entry V77 writes:
     * id, title, src, time, size), so Open / Share / Show / Rename / Vault / Remove work like for V77 files and it stays after a restart.
     */
    private fun addSaved(i: DlItem) {
        main.post {
            try {
                val a = ChromeBridge.local.get("mgHist").optJSONArray("mgHist") ?: JSONArray()
                for (k in 0 until a.length()) if (a.optJSONObject(k)?.optInt("id") == i.id) return@post
                var ref = ""
                for (k in 0 until i.headers.length()) i.headers.optJSONObject(k)?.let { if (it.optString("name").equals("Referer", true)) ref = it.optString("value") }
                val src = if (ref.startsWith("http", true)) ref else if (i.url.startsWith("http", true)) i.url else ""
                val name = (if (i.filename.isNotEmpty()) i.filename else i.rel).substringAfterLast('/')
                val e = JSONObject().put("id", i.id).put("title", name).put("src", src).put("time", i.end.takeIf { it > 0 } ?: System.currentTimeMillis()).put("size", maxOf(i.fileSize, 0L))
                val out = JSONArray().put(e)
                for (k in 0 until minOf(a.length(), 499)) a.optJSONObject(k)?.let { out.put(it) }
                ChromeBridge.storageSet("local", JSONObject().put("mgHist", out))
            } catch (e: Exception) { }
        }
    }
    fun notifyFail(item: DlItem) = notifyItem(item, false)

    /** Notification shown while the foreground service runs. */
    fun progressNote(): android.app.Notification {
        val act = active()
        val pz = pausedList()
        val allPaused = act.isEmpty() && pz.isNotEmpty()
        val shown = if (allPaused) pz else act
        var got = 0L; var tot = 0L; var known = true
        for (i in shown) { got += i.received; if (i.total > 0) tot += i.total else known = false }
        val b = NotificationCompat.Builder(app, CH_RUN)
            .setSmallIcon(if (allPaused) android.R.drawable.ic_media_pause else android.R.drawable.stat_sys_download)
            .setOngoing(true).setOnlyAlertOnce(true)
            .setContentTitle(
                if (allPaused) (if (pz.size <= 1) "Download paused" else "${pz.size} downloads paused")
                else if (act.size <= 1) "Downloading" else "Downloading ${act.size} files"
            )
        if (shown.size == 1) b.setContentText(displayPath(shown[0]).substringAfterLast('/'))
        if (known && tot > 0 && shown.isNotEmpty()) b.setProgress(100, (got * 100 / tot).toInt().coerceIn(0, 100), false)
        else if (!allPaused) b.setProgress(0, 0, true)
        // S45: Pause / Resume and Cancel buttons (handled in DownloadService.onStartCommand)
        if (allPaused) b.addAction(android.R.drawable.ic_media_play, "Resume", svcIntent(DownloadService.ACT_RESUME, 1))
        else if (act.isNotEmpty()) b.addAction(android.R.drawable.ic_media_pause, "Pause", svcIntent(DownloadService.ACT_PAUSE, 2))
        if (shown.isNotEmpty()) b.addAction(android.R.drawable.ic_menu_close_clear_cancel, "Cancel", svcIntent(DownloadService.ACT_CANCEL, 3))
        app.packageManager.getLaunchIntentForPackage(app.packageName)?.let {
            b.setContentIntent(PendingIntent.getActivity(app, 0, it, PendingIntent.FLAG_IMMUTABLE))
        }
        return b.build()
    }

    private fun svcIntent(action: String, code: Int): PendingIntent =
        PendingIntent.getService(
            app, code, Intent(app, DownloadService::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

    // ---------- opening files ----------

    private fun contentUri(item: DlItem): Uri? {
        if (item.state != "complete" || item.uri.isEmpty()) return null
        return try {
            if (item.uri.startsWith("content:")) Uri.parse(item.uri)
            else FileProvider.getUriForFile(app, app.packageName + ".dlfiles", File(item.uri))
        } catch (e: Exception) { null }
    }

    private fun openIntent(item: DlItem): Intent? {
        val u = contentUri(item) ?: return null
        return Intent(Intent.ACTION_VIEW).setDataAndType(u, item.mime.ifEmpty { "*/*" })
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    // ---------- S28: built-in player ----------

    private val MEDIA_EXT = setOf("mp4", "m4v", "mkv", "webm", "mov", "3gp", "ts", "mp3", "m4a", "aac", "ogg", "opus", "wav", "flac")

    private fun isMedia(i: DlItem): Boolean {
        val m = i.mime.lowercase()
        if (m.startsWith("video/") || m.startsWith("audio/")) return true
        return i.rel.substringAfterLast('.', "").lowercase() in MEDIA_EXT
    }

    /** Plays a finished video / audio download in PlayerScreen. false = not media or not complete (caller opens it the old way). */
    fun playIfMedia(a: Activity, id: Int): Boolean {
        val i = synchronized(items) { items[id] } ?: return false
        if (!isMedia(i)) return false
        val u = contentUri(i) ?: return false
        PlayerScreen.open(a, u, i.rel.substringAfterLast('/').ifEmpty { "Video" })
        return true
    }

    // ---------- S65: Open / Show from the Downloads screen (never silent) ----------

    /** Open a finished file: built-in player for media, other apps for the rest. Tells the user when it cannot. */
    fun openFile(a: Activity, id: Int) {
        val i = synchronized(items) { items[id] }
        if (i == null) { Util.toast(a, "This file is no longer in the downloads list"); return }
        if (i.state != "complete") { Util.toast(a, "Download is not complete"); return }
        if (!checkExists(i)) { Util.toast(a, "File was deleted or moved"); return }
        if (playIfMedia(a, id)) return
        val intent = openIntent(i)
        if (intent == null) { Util.toast(a, "Cannot open this file"); return }
        try { a.startActivity(intent) } catch (e: Exception) { Util.toast(a, "No app can open this file") }
    }

    /** D4: content uri of a finished file (null = not complete / not found). */
    fun sourceUri(id: Int): Uri? = synchronized(items) { items[id] }?.let { contentUri(it) }

    /** S66: share a finished file with another app. */
    fun shareFile(a: Activity, id: Int) {
        val i = synchronized(items) { items[id] }
        val u = if (i == null) null else contentUri(i)
        if (i == null || u == null || !checkExists(i)) { Util.toast(a, "File not found"); return }
        val send = Intent(Intent.ACTION_SEND).setType(i.mime.ifEmpty { "*/*" }).putExtra(Intent.EXTRA_STREAM, u)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try { a.startActivity(Intent.createChooser(send, "Share").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)) }
        catch (e: Exception) { Util.toast(a, "Cannot share this file") }
    }

    /** S66: delete the file from the phone (same as chrome.downloads.removeFile). false = nothing to delete. */
    fun deleteFile(id: Int): Boolean {
        val i = synchronized(items) { items[id] } ?: return false
        if (i.state != "complete") return false
        try {
            if (i.uri.startsWith("content:")) {
                val u = Uri.parse(i.uri)
                if (u.authority == "media") app.contentResolver.delete(u, null, null)
                else android.provider.DocumentsContract.deleteDocument(app.contentResolver, u)
            } else File(i.uri).delete()
        } catch (e: Exception) { /* already gone */ }
        change(i) { exists = false }
        return true
    }

    /** V7: a finished download for the vault (null when unknown or not complete). Read-only handle, nothing is changed. */
    fun itemOf(id: Int): DlItem? = synchronized(items) { items[id] }?.takeIf { it.state == "complete" }

    /** Folder that holds the file as a document id (for example primary:Download/MGB) plus a short label. */
    private fun folderOf(i: DlItem): Pair<String, String> {
        val u = if (i.uri.startsWith("content:")) Uri.parse(i.uri) else null
        if (u != null && u.authority == "com.android.externalstorage.documents") {
            try {
                val id = android.provider.DocumentsContract.getDocumentId(u)
                val parent = if (id.contains('/')) id.substringBeforeLast('/') else id.substringBefore(':') + ":"
                return Pair(parent, parent.substringAfter(':').ifEmpty { "Storage" })
            } catch (e: Exception) { /* use rel below */ }
        }
        val dir = i.rel.replace('\\', '/').substringBeforeLast('/', "")
        val base = Environment.DIRECTORY_DOWNLOADS
        val path = if (dir.isEmpty()) base else "$base/$dir"
        return Pair("primary:$path", path)
    }

    /** S18: readable folder of a saved file, e.g. /storage/emulated/0/Download/MGB/ ("" when the file is unknown). */
    fun folderPath(id: Int): String {
        val i = synchronized(items) { items[id] } ?: return ""
        val f = folderOf(i)
        return if (f.first.startsWith("primary:")) "/storage/emulated/0/" + f.second.trim('/') + "/" else f.second
    }

    /** S18: rename a finished file on the phone. Returns the new display name, or null when it did not work. */
    fun renameFile(id: Int, newName: String): String? {
        val i = synchronized(items) { items[id] } ?: return null
        val nn = newName.trim().replace('/', '_').replace('\\', '_')
        if (nn.isEmpty() || i.state != "complete" || !checkExists(i)) return null
        var newUri = i.uri
        try {
            if (i.uri.startsWith("content:")) {
                val u = Uri.parse(i.uri)
                if (u.authority == "media") {
                    val cv = android.content.ContentValues().apply { put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, nn) }
                    if (app.contentResolver.update(u, cv, null, null) < 1) return null
                } else {
                    newUri = (android.provider.DocumentsContract.renameDocument(app.contentResolver, u, nn) ?: return null).toString()
                }
            } else {
                val f = File(i.uri)
                val t = File(f.parentFile, nn)
                if (t.exists() || !f.renameTo(t)) return null
                newUri = t.absolutePath
            }
        } catch (e: Exception) { return null }
        fun swap(p: String) = if (p.contains('/')) p.substringBeforeLast('/') + "/" + nn else nn
        change(i) { uri = newUri; if (filename.isNotEmpty()) filename = swap(filename); if (rel.isNotEmpty()) rel = swap(rel) }
        return nn
    }

    /** Show = open the folder of the file. Falls back to the system Downloads app, then to a message with the path. */
    fun showFile(c: Context, id: Int) {
        val i = synchronized(items) { items[id] }
        val nt = Intent.FLAG_ACTIVITY_NEW_TASK
        if (i != null) {
            val f = folderOf(i)
            try {
                val dirUri = android.provider.DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", f.first)
                c.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(dirUri, android.provider.DocumentsContract.Document.MIME_TYPE_DIR).addFlags(nt))
                Util.toast(c, "Folder: " + f.second)
                return
            } catch (e: Exception) { /* no file manager takes folders: try the Downloads app */ }
        }
        try {
            c.startActivity(Intent(android.app.DownloadManager.ACTION_VIEW_DOWNLOADS).addFlags(nt))
            if (i != null) Util.toast(c, "Folder: " + folderOf(i).second)
        } catch (e: Exception) {
            Util.toast(c, if (i != null) "Saved in " + folderOf(i).second else "This file is no longer in the downloads list")
        }
    }

    // ---------- permissions (asked once, on the first download) ----------

    private fun askPerms() {
        val a = top?.get() ?: return
        val p = app.getSharedPreferences(PREFS, 0)
        if (p.getBoolean("asked", false)) return
        val need = ArrayList<String>()
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(app, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) need.add(Manifest.permission.POST_NOTIFICATIONS)
        if (Build.VERSION.SDK_INT < 29 &&
            ContextCompat.checkSelfPermission(app, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        ) need.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        if (need.isEmpty()) return
        p.edit().putBoolean("asked", true).apply()
        ActivityCompat.requestPermissions(a, need.toTypedArray(), 7001)
    }

    // ---------- query ----------

    private fun matches(i: DlItem, q: JSONObject): Boolean {
        if (q.has("id") && q.optInt("id") != i.id) return false
        if (q.has("url") && q.optString("url") != i.url) return false
        if (q.has("urlRegex") && !Regex(q.optString("urlRegex")).containsMatchIn(i.url)) return false
        if (q.has("filename") && q.optString("filename") != displayPath(i)) return false
        if (q.has("filenameRegex") && !Regex(q.optString("filenameRegex")).containsMatchIn(displayPath(i))) return false
        if (q.has("state") && q.optString("state") != i.state) return false
        if (q.has("paused") && q.optBoolean("paused") != i.paused) return false
        if (q.has("mime") && q.optString("mime") != i.mime) return false
        if (q.has("error") && q.optString("error") != i.error) return false
        if (q.has("exists") && q.optBoolean("exists") != checkExists(i)) return false
        val words = q.optJSONArray("query")
        if (words != null) {
            val hay = (i.url + " " + displayPath(i)).lowercase()
            for (n in 0 until words.length()) {
                val w = words.optString(n).lowercase()
                if (w.startsWith("-")) { if (w.length > 1 && hay.contains(w.substring(1))) return false }
                else if (!hay.contains(w)) return false
            }
        }
        return true
    }

    private fun find(q: JSONObject): List<DlItem> =
        synchronized(items) { items.values.filter { matches(it, q) } }

    private fun byId(c: ChromeBridge.Call): DlItem =
        synchronized(items) { items[c.args.optInt(0, -1)] } ?: throw IllegalArgumentException("Invalid download id.")

    // ---------- init ----------

    fun init(ctx: Context) {
        if (inited) return
        inited = true
        app = ctx.applicationContext
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = app.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(CH_RUN, "Downloads in progress", NotificationManager.IMPORTANCE_LOW))
            nm.createNotificationChannel(NotificationChannel(CH_DONE, "Finished downloads", NotificationManager.IMPORTANCE_DEFAULT))
        }
        synchronized(items) { load() }
        (app as Application).registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(a: Activity) { top = WeakReference(a) }
            override fun onActivityPaused(a: Activity) { if (top?.get() === a) top = null }
            override fun onActivityCreated(a: Activity, s: Bundle?) {}
            override fun onActivityStarted(a: Activity) {}
            override fun onActivityStopped(a: Activity) {}
            override fun onActivitySaveInstanceState(a: Activity, s: Bundle) {}
            override fun onActivityDestroyed(a: Activity) {}
        })

        handle("downloads.download") { c ->
            val o = c.args.optJSONObject(0) ?: JSONObject()
            val url = o.optString("url")
            val ok = listOf("http://", "https://", "data:", "blob:").any { url.startsWith(it, true) }
            if (!ok) { c.fail("Invalid URL"); return@handle }
            val item = newItem(url)
            item.rel = cleanRel(o.optString("filename"))
            item.conflict = o.optString("conflictAction", "uniquify")
            item.method = o.optString("method", "GET").uppercase()
            if (o.has("body") && !o.isNull("body")) item.body = o.optString("body")
            item.headers = o.optJSONArray("headers") ?: JSONArray()
            item.canResume = url.startsWith("http", true)
            if (url.startsWith("blob:", true)) item.src = WeakReference(c.ctx.web)
            save()
            emit("downloads.onCreated", toJson(item, false))
            askPerms()
            Downloader.start(item)
            c.ok(item.id)
        }

        handle("downloads.search") { c ->
            val q = c.args.optJSONObject(0) ?: JSONObject()
            var list = find(q)
            if (q.has("limit")) list = list.take(q.optInt("limit", 1000))
            val arr = JSONArray()
            for (i in list) arr.put(toJson(i))
            c.ok(arr)
        }

        handle("downloads.pause") { c ->
            val i = byId(c)
            if (i.state != "in_progress") { c.fail("Download must be in progress."); return@handle }
            change(i) { paused = true }
            Downloader.abort(i)
            c.ok(null)
        }

        handle("downloads.resume") { c ->
            val i = byId(c)
            if (i.state == "in_progress" && i.paused) {
                change(i) { paused = false }
                Downloader.start(i)
            } else if (i.state == "interrupted" && i.canResume) {
                change(i) { state = "in_progress"; paused = false; error = ""; end = 0L }
                Downloader.start(i)
            } else { c.fail("Download must be paused or resumable."); return@handle }
            c.ok(null)
        }

        handle("downloads.cancel") { c ->
            val i = byId(c)
            if (i.state == "in_progress" || (i.state == "interrupted" && i.canResume)) {
                change(i) { state = "interrupted"; error = "USER_CANCELED"; paused = false; canResume = false; end = System.currentTimeMillis() }
                Downloader.abort(i)
                Downloader.partFile(i.id).delete()
            }
            c.ok(null)
        }

        handle("downloads.show") { c ->
            showFile(top?.get() ?: app, c.args.optInt(0, -1)) // S65: opens the folder, never silent
            c.ok(null)
        }
        ChromeBridge.onSync("downloads.showDefaultFolder") {
            try { app.startActivity(Intent(android.app.DownloadManager.ACTION_VIEW_DOWNLOADS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (e: Exception) { }
            null
        }

        handle("downloads.open") { c ->
            val i = byId(c)
            val pa = top?.get() // S28: media opens in the built-in player
            if (pa != null && i.state == "complete" && isMedia(i) && contentUri(i) != null) {
                pa.runOnUiThread { playIfMedia(pa, i.id) }
                c.ok(null)
                return@handle
            }
            val intent = openIntent(i)
            if (intent == null) { c.fail("Download is not complete."); return@handle }
            try { app.startActivity(intent) } catch (e: Exception) { c.fail("No app can open this file."); return@handle }
            c.ok(null)
        }

        handle("downloads.removeFile") { c ->
            val i = byId(c)
            if (i.state != "complete") { c.fail("Download must be complete."); return@handle }
            try {
                if (i.uri.startsWith("content:")) {
                    val u = Uri.parse(i.uri)
                    if (u.authority == "media") app.contentResolver.delete(u, null, null)
                    else android.provider.DocumentsContract.deleteDocument(app.contentResolver, u) // S49
                }
                else File(i.uri).delete()
            } catch (e: Exception) { /* already gone */ }
            change(i) { exists = false }
            c.ok(null)
        }

        handle("downloads.erase") { c ->
            val q = c.args.optJSONObject(0) ?: JSONObject()
            val gone = JSONArray()
            for (i in find(q)) {
                if (i.state == "in_progress") continue
                synchronized(items) { items.remove(i.id) }
                Downloader.partFile(i.id).delete()
                gone.put(i.id)
                emit("downloads.onErased", i.id)
            }
            save()
            c.ok(gone)
        }

        // harmless stubs
        for (api in listOf("downloads.acceptDanger", "downloads.setShelfEnabled", "downloads.setUiOptions",
            "downloads.getFileIcon", "downloads.drag")) ChromeBridge.onSync(api) { null }
    }
}
