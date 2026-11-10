package com.jabra.mgb

import android.Manifest
import android.app.Service
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.MediaStore
import android.util.Base64
import android.webkit.CookieManager
import android.webkit.MimeTypeMap
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.annotation.RequiresApi
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.URLDecoder
import java.util.TreeMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * S7: the transfer part of chrome.downloads.
 *  - http(s): OkHttp, cookies shared with the WebView, Range resume (part file in files/dl), redirects
 *  - data: decoded in memory
 *  - blob: read in chunks out of the page that owns the blob (evaluateJavascript), because a blob url
 *    only exists inside its own page
 *  - finished file is published to the public Downloads folder (MediaStore on Android 10+)
 */
object Downloader {
    private val main = Handler(Looper.getMainLooper())
    // 0.0.370 (N2): same fixed 3 threads by default; size follows pref dl_max_jobs (applied at every start())
    private val pool = ThreadPoolExecutor(3, 3, 30L, TimeUnit.SECONDS, LinkedBlockingQueue<Runnable>()).apply { allowCoreThreadTimeOut(true) }
    private fun applyPool() {
        try {
            val n = DlLimit.maxJobs()
            if (n == pool.corePoolSize) return
            if (n > pool.maximumPoolSize) { pool.maximumPoolSize = n; pool.corePoolSize = n } else { pool.corePoolSize = n; pool.maximumPoolSize = n }
        } catch (e: Exception) { }
    }
    private var ua: String? = null

    /** Silent stop (code == null: pause or cancel) or a failure with a chrome.downloads error code. */
    private class Stop(val code: String?) : Exception()

    private class Pub(val path: String, val uri: String)

    private val jar = object : CookieJar {
        override fun loadForRequest(url: HttpUrl): List<Cookie> {
            val raw = try { CookieManager.getInstance().getCookie(url.toString()) } catch (e: Exception) { null }
            if (raw.isNullOrEmpty()) return emptyList()
            val out = ArrayList<Cookie>()
            for (part in raw.split(";")) {
                val i = part.indexOf('=')
                if (i <= 0) continue
                try {
                    out.add(Cookie.Builder().name(part.substring(0, i).trim()).value(part.substring(i + 1).trim())
                        .hostOnlyDomain(url.host).build())
                } catch (e: Exception) { /* odd cookie: skip */ }
            }
            return out
        }

        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            try { for (c in cookies) CookieManager.getInstance().setCookie(url.toString(), c.toString()) } catch (e: Exception) { }
        }
    }

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS)
            .followRedirects(true).followSslRedirects(true).retryOnConnectionFailure(true)
            .cookieJar(jar).build()
    }

    fun partFile(id: Int): File {
        val d = File(DownloadsApi.app.filesDir, "dl")
        d.mkdirs()
        return File(d, "$id.part")
    }

    fun start(item: DlItem) {
        DownloadService.ensure(DownloadsApi.app)
        applyPool()
        pool.execute { run(item) }
    }

    fun abort(item: DlItem) { try { item.call?.cancel() } catch (e: Exception) { }; DlParts.abort(item) } // 0.0.357: DlParts.abort never throws

    private fun checkStop(item: DlItem) {
        if (item.paused || item.state != "in_progress") throw Stop(null)
    }

    private fun run(item: DlItem) {
        item.lock.lock()
        try {
            if (item.state != "in_progress" || item.paused) return
            try {
                val u = item.url
                when {
                    u.startsWith("blob:", true) -> runBlob(item)
                    u.startsWith("data:", true) -> runData(item)
                    else -> runHttp(item)
                }
                finish(item)
            } catch (s: Stop) {
                if (s.code == "FILE_FAILED") why(s)
                if (s.code != null) fail(item, s.code)
            } catch (e: IOException) {
                if (item.paused || item.state != "in_progress") return
                fail(item, ioCode(e))
            } catch (e: Exception) {
                if (item.paused || item.state != "in_progress") return
                why(e)
                fail(item, "FILE_FAILED")
            }
        } finally {
            item.call = null
            item.lock.unlock()
        }
    }

    /** 0.0.353: FILE_FAILED is thrown from ~12 places; this logs WHICH one (class, message, file:line). Log only, never changes the flow. */
    private fun why(t: Throwable) {
        try {
            val f = t.stackTrace.firstOrNull()
            Diag.w("dlwhy", "FILE_FAILED " + t.javaClass.simpleName + " " + (t.message ?: "").take(100) + (if (f != null) " @" + f.fileName + ":" + f.lineNumber else ""))
        } catch (e: Throwable) { }
    }

    private fun ioCode(e: IOException): String {
        val m = e.message ?: ""
        return when {
            m.contains("ENOSPC") || m.contains("No space", true) -> "FILE_NO_SPACE"
            e is SocketTimeoutException -> "NETWORK_TIMEOUT"
            else -> "NETWORK_FAILED"
        }
    }

    private fun fail(item: DlItem, code: String) {
        Diag.w("download", "failed " + code, item.url) // S83
        DownloadsApi.change(item) {
            state = "interrupted"; error = code; paused = false; end = System.currentTimeMillis()
            canResume = canResume && (partFile(id).exists() || DlParts.hasState(id)) && url.startsWith("http", true) // 0.0.358: parts state counts
        }
        DownloadsApi.notifyFail(item)
    }

    // ---------- http ----------

    private fun userAgent(): String {
        ua?.let { return it }
        val v = try { WebSettings.getDefaultUserAgent(DownloadsApi.app) } catch (e: Exception) { "Mozilla/5.0 (Linux; Android) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36" }
        ua = v
        return v
    }

    // 0.0.357: private -> internal + 2 optional params (defaults = old behaviour). rangeEnd >= 0: "bytes=have-rangeEnd" (DlParts); onCall != null: the Call goes to the callback, not to item.call.
    internal fun open(item: DlItem, have: Long, rangeEnd: Long = -1L, onCall: ((okhttp3.Call) -> Unit)? = null): Response {
        val h = TreeMap<String, String>(String.CASE_INSENSITIVE_ORDER)
        h["User-Agent"] = userAgent()
        h["Accept"] = "*/*"
        h["Accept-Language"] = "en-US,en;q=0.9"
        DownloadsApi.headerProvider?.invoke(item.url)?.let { h.putAll(it) }
        for (n in 0 until item.headers.length()) {
            val o = item.headers.optJSONObject(n) ?: continue
            val name = o.optString("name")
            if (name.isNotEmpty() && !name.equals("host", true) && !name.equals("content-length", true)) h[name] = o.optString("value")
        }
        val rb = try { Request.Builder().url(item.url) } catch (e: IllegalArgumentException) { throw Stop("NETWORK_INVALID_REQUEST") }
        for ((k, v) in h) rb.header(k, v)
        if (have > 0 || rangeEnd >= 0) {
            rb.header("Range", if (rangeEnd >= 0) "bytes=$have-$rangeEnd" else "bytes=$have-")
            if (item.validator.isNotEmpty()) rb.header("If-Range", item.validator)
        }
        if (item.method == "POST") {
            val ct = h["Content-Type"]?.toMediaTypeOrNull()
            rb.post((item.body ?: "").toRequestBody(ct))
        } else rb.get()
        val call = client.newCall(rb.build())
        if (onCall != null) onCall(call) else item.call = call
        return call.execute()
    }

    private fun httpErr(code: Int): String = when (code) {
        401 -> "SERVER_UNAUTHORIZED"
        403 -> "SERVER_FORBIDDEN"
        404, 410 -> "SERVER_BAD_CONTENT"
        416 -> "SERVER_NO_RANGE"
        else -> "SERVER_FAILED"
    }

    internal fun nameFromResponse(url: String, cd: String?, ct: String): String {
        var name = ""
        if (cd != null) {
            Regex("filename\\*\\s*=\\s*[^']*'[^']*'([^;]+)", RegexOption.IGNORE_CASE).find(cd)?.let {
                name = try { URLDecoder.decode(it.groupValues[1].trim(), "UTF-8") } catch (e: Exception) { "" }
            }
            if (name.isEmpty()) Regex("filename\\s*=\\s*\"?([^\";]+)\"?", RegexOption.IGNORE_CASE).find(cd)?.let { name = it.groupValues[1].trim() }
        }
        if (name.isEmpty()) {
            val path = url.substringBefore('?').substringBefore('#').trimEnd('/').substringAfterLast('/')
            name = try { URLDecoder.decode(path.replace("+", "%2B"), "UTF-8") } catch (e: Exception) { path }
        }
        if (name.isEmpty()) name = "download"
        if (!name.contains('.')) {
            val ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(ct)
            if (!ext.isNullOrEmpty()) name = "$name.$ext"
        }
        return DownloadsApi.cleanRel(name).ifEmpty { "download" }
    }

    /**
     * 0.0.381: the name made before the request ends with ".bin" (generic type, no real name in the link) but the server answer
     * has a better one (Content-Disposition / type, e.g. app.apk) = use it. Any other name is returned unchanged. Never throws.
     */
    internal fun betterThanBin(rel: String, url: String, cd: String?, ct: String): String {
        return try {
            if (!rel.endsWith(".bin", true)) return rel
            val n = nameFromResponse(url, cd, ct)
            if (n.isNotEmpty() && !n.endsWith(".bin", true) && n.substringAfterLast('.', "").isNotEmpty()) {
                val dir = rel.substringBeforeLast('/', "")
                if (dir.isEmpty()) n else "$dir/${n.substringAfterLast('/')}"
            } else rel
        } catch (e: Exception) { rel }
    }

    private fun runHttp(item: DlItem) {
        // 0.0.357: parts mode first (switch dp_on, ON by default since 0.0.375). true = whole file is in partFile; false / error = old code below.
        try { if (DlParts.run(item)) return } catch (e: IOException) { throw e } catch (e: Exception) { Diag.w("dlparts", "hook " + e.javaClass.simpleName) }
        val part = partFile(item.id)
        var have = if (part.exists()) part.length() else 0L
        var tries = 0
        while (true) {
            val resp = open(item, have)
            try {
                checkStop(item)
                val code = resp.code
                if (code == 416 && have > 0) {
                    val tot = resp.header("Content-Range")?.substringAfter('/', "")?.toLongOrNull()
                    if (tot != null && tot == have) {
                        DownloadsApi.change(item) { total = tot; received = have }
                        return
                    }
                    part.delete(); have = 0
                    if (++tries > 2) throw Stop("SERVER_NO_RANGE")
                    continue
                }
                if (code !in 200..299) { Diag.w("download", "http " + code, item.url); throw Stop(httpErr(code)) }
                val append = code == 206 && have > 0
                if (!append) have = 0                       // server ignored the range: start again
                val body = resp.body ?: throw Stop("SERVER_BAD_CONTENT")
                val len = body.contentLength()
                val tot = if (len >= 0) len + have else -1L
                val ct = resp.header("Content-Type")?.substringBefore(';')?.trim()?.lowercase() ?: ""
                val vld = resp.header("ETag") ?: resp.header("Last-Modified") ?: ""
                val rangeOk = code == 206 || resp.header("Accept-Ranges")?.contains("bytes", true) == true
                val finalUrl = resp.request.url.toString()
                val cd = resp.header("Content-Disposition")
                val startAt = have
                DownloadsApi.change(item) {
                    this.finalUrl = finalUrl
                    total = tot
                    received = startAt
                    if (vld.isNotEmpty() && !vld.startsWith("W/")) validator = vld
                    if (mime.isEmpty() && ct.isNotEmpty() && ct != "application/octet-stream") mime = ct
                    canResume = rangeOk
                    if (rel.isEmpty()) rel = nameFromResponse(finalUrl, cd, ct) else rel = betterThanBin(rel, finalUrl, cd, ct) // 0.0.381
                }
                var got = startAt
                FileOutputStream(part, append).use { out ->
                    val buf = ByteArray(65536)
                    val src = body.byteStream()
                    while (true) {
                        val n = src.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        got += n
                        item.received = got
                        DlLimit.throttle(n) { item.paused || item.state != "in_progress" } // 0.0.370 (N2)
                        checkStop(item)
                    }
                }
                val done = got
                DownloadsApi.change(item) { total = done; received = done }
                return
            } finally {
                resp.close()
            }
        }
    }

    // ---------- data: ----------

    private fun runData(item: DlItem) {
        val u = item.url
        val comma = u.indexOf(',')
        if (comma < 0) throw Stop("NETWORK_INVALID_REQUEST")
        val meta = u.substring(5, comma)
        val data = u.substring(comma + 1)
        val bytes = if (meta.contains(";base64", true)) Base64.decode(Uri.decode(data), Base64.DEFAULT)
        else URLDecoder.decode(data.replace("+", "%2B"), "UTF-8").toByteArray(Charsets.UTF_8)
        val mt = meta.substringBefore(';').trim().lowercase()
        partFile(item.id).writeBytes(bytes)
        val n = bytes.size.toLong()
        DownloadsApi.change(item) {
            if (mime.isEmpty() && mt.isNotEmpty()) mime = mt
            total = n; received = n; canResume = false
            if (rel.isEmpty()) rel = "download"
        }
    }

    // ---------- blob: ----------

    /** Run JS in the page and wait for the answer. Returns the JS string result, or null for null/non-string. */
    private fun evalSync(web: WebView, js: String, ms: Long): String? {
        val latch = CountDownLatch(1)
        val out = AtomicReference<String?>(null)
        main.post {
            try { web.evaluateJavascript(js) { v -> out.set(v); latch.countDown() } }
            catch (e: Exception) { latch.countDown() }
        }
        if (!latch.await(ms, TimeUnit.MILLISECONDS)) throw Stop("FILE_FAILED")
        val v = out.get() ?: return null
        if (v == "null" || v.isEmpty()) return null
        return try { JSONArray("[$v]").opt(0) as? String } catch (e: Exception) { null }
    }

    private fun runBlob(item: DlItem) {
        val web = item.src?.get() ?: throw Stop("FILE_FAILED")
        val part = partFile(item.id)
        var off = if (part.exists()) part.length() else 0L
        val k = JSONObject.quote(item.id.toString())
        evalSync(web, "(function(u,k){var s=window.__mgb=window.__mgb||{};if(s[k])return 1;var o=s[k]={b:null,e:null,r:null};" +
            "fetch(u).then(function(r){return r.blob()}).then(function(b){o.b=b},function(e){o.e=String(e)});return 1})(" +
            JSONObject.quote(item.url) + "," + k + ")", 10000)
        var size = -1L
        var type = ""
        val t0 = System.currentTimeMillis()
        while (size < 0) {
            checkStop(item)
            val r = evalSync(web, "(function(k){var o=(window.__mgb||{})[k];return o?(o.b?(o.b.size+'|'+o.b.type):(o.e?'!'+o.e:'')):null})($k)", 10000)
            if (r == null || r.startsWith("!")) throw Stop("FILE_FAILED")
            if (r.isEmpty()) {
                if (System.currentTimeMillis() - t0 > 60000) throw Stop("FILE_FAILED")
                Thread.sleep(50)
            } else {
                size = r.substringBefore('|').toLongOrNull() ?: throw Stop("FILE_FAILED")
                type = r.substringAfter('|', "")
            }
        }
        val total = size
        val mt = type.lowercase()
        DownloadsApi.change(item) {
            this.total = total; received = off; canResume = true
            if (mime.isEmpty() && mt.isNotEmpty()) mime = mt
            if (rel.isEmpty()) rel = "download"
        }
        val chunk = 393216L   // multiple of 3, so every chunk is clean base64
        FileOutputStream(part, off > 0).use { out ->
            while (off < size) {
                checkStop(item)
                val len = minOf(chunk, size - off)
                evalSync(web, "(function(k,a,n){var o=window.__mgb[k];o.r=null;o.b.slice(a,a+n).arrayBuffer().then(function(ab){" +
                    "var u=new Uint8Array(ab),s='';for(var i=0;i<u.length;i+=8192)s+=String.fromCharCode.apply(null,u.subarray(i,i+8192));" +
                    "o.r=btoa(s)},function(e){o.e=String(e)});return 1})($k,$off,$len)", 10000)
                var data: String? = null
                val t1 = System.currentTimeMillis()
                while (data == null) {
                    checkStop(item)
                    Thread.sleep(25)
                    val r = evalSync(web, "(function(k){var o=window.__mgb[k];if(o.e)return '!'+o.e;var r=o.r;if(r===null)return null;o.r=null;return 'D'+r})($k)", 10000)
                    if (r != null) {
                        if (r.startsWith("!")) throw Stop("FILE_FAILED")
                        data = r.substring(1)
                    } else if (System.currentTimeMillis() - t1 > 120000) throw Stop("FILE_FAILED")
                }
                val bytes = Base64.decode(data, Base64.DEFAULT)
                if (bytes.isEmpty()) throw Stop("FILE_FAILED")
                out.write(bytes)
                off += bytes.size
                item.received = off
            }
        }
        try { evalSync(web, "(function(k){try{delete window.__mgb[k]}catch(e){}return 1})($k)", 5000) } catch (e: Stop) { }
    }

    // ---------- publish ----------

    private fun finish(item: DlItem) {
        val part = partFile(item.id)
        if (!part.exists()) throw Stop("FILE_FAILED")
        checkStop(item)
        val size = part.length()
        val pub = try { publish(item, part) } catch (e: Stop) { throw e } catch (e: Exception) {
            val m = e.message ?: ""
            why(e)
            throw Stop(if (m.contains("ENOSPC") || m.contains("No space", true)) "FILE_NO_SPACE" else "FILE_FAILED")
        }
        part.delete()
        DownloadsApi.change(item) {
            state = "complete"; paused = false; error = ""; canResume = false; exists = true
            end = System.currentTimeMillis()
            filename = pub.path; uri = pub.uri; fileSize = size; received = size; total = size
        }
        DownloadsApi.notifyDone(item)
    }

    /**
     * 0.0.279: Android (MediaStore and the folder picker) makes the file name agree with the given type and ADDS an extension when they
     * disagree: Title.en.srt with type text/plain became Title.en.srt.txt. So the type is taken from the extension of the name
     * (unknown extension = application/octet-stream = the name is kept as it is). A name without an extension keeps the given type.
     */
    internal fun safeMime(name: String, mime: String): String {
        val fallback = mime.ifEmpty { "application/octet-stream" }
        if (!name.contains('.')) return fallback
        val e = name.substringAfterLast('.').lowercase()
        if (e.isEmpty()) return fallback
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(e) ?: "application/octet-stream"
    }

    private fun publish(item: DlItem, part: File): Pub {
        val rel = item.rel.ifEmpty { "download" }
        val dir = rel.substringBeforeLast('/', "")
        val name = rel.substringAfterLast('/')
        val ext = name.substringAfterLast('.', "").lowercase()
        val mime = safeMime(name, item.mime.ifEmpty { MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream" }) // 0.0.279: no more Title.en.srt.txt
        // S49: a folder picked by the user (Settings > Downloads > Download folder) comes first; if it fails, use Downloads
        DownloadFolder.treeUri(DownloadsApi.app)?.let { tree ->
            try {
                val r = DownloadFolder.publish(DownloadsApi.app, tree, item, part, dir, name, mime)
                return Pub(r.first, r.second)
            } catch (e: Exception) {
                android.util.Log.e("MGB.dl", "picked folder failed, using Downloads", e)
            }
        }
        return if (Build.VERSION.SDK_INT >= 29) publishQ(item, part, dir, name, mime) else publishLegacy(item, part, rel, name)
    }

    @RequiresApi(29)
    private fun publishQ(item: DlItem, part: File, dir: String, name: String, mime: String): Pub {
        val cr = DownloadsApi.app.contentResolver
        val base = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        val relPath = "Download/" + (if (dir.isEmpty()) "" else "$dir/")
        if (item.conflict == "overwrite") {
            cr.query(base, arrayOf(MediaStore.MediaColumns._ID),
                "${MediaStore.MediaColumns.DISPLAY_NAME}=? AND ${MediaStore.MediaColumns.RELATIVE_PATH}=?",
                arrayOf(name, relPath), null)?.use { c ->
                while (c.moveToNext()) cr.delete(ContentUris.withAppendedId(base, c.getLong(0)), null, null)
            }
        }
        val v = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relPath)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = cr.insert(base, v) ?: throw IOException("MediaStore insert failed")
        try {
            val os = cr.openOutputStream(uri) ?: throw IOException("cannot open output")
            os.use { o -> part.inputStream().use { it.copyTo(o, 65536) } }
            cr.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        } catch (e: Exception) {
            try { cr.delete(uri, null, null) } catch (ignored: Exception) { }
            throw e
        }
        var real = name
        cr.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) real = it.getString(0) ?: name
        }
        val shown = DownloadsApi.publicDir().absolutePath + "/" + (if (dir.isEmpty()) "" else "$dir/") + real
        return Pub(shown, uri.toString())
    }

    private fun publishLegacy(item: DlItem, part: File, rel: String, name: String): Pub {
        val app = DownloadsApi.app
        val granted = ContextCompat.checkSelfPermission(app, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        val base = if (granted) DownloadsApi.publicDir()
        else app.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: app.filesDir
        var target = File(base, rel)
        target.parentFile?.mkdirs()
        if (item.conflict != "overwrite") {
            val stem = name.substringBeforeLast('.')
            val dotExt = if (name.contains('.')) "." + name.substringAfterLast('.') else ""
            var n = 1
            while (target.exists()) { target = File(target.parentFile, "$stem ($n)$dotExt"); n++ }
        }
        part.inputStream().use { i -> target.outputStream().use { o -> i.copyTo(o, 65536) } }
        try { MediaScannerConnection.scanFile(app, arrayOf(target.absolutePath), null, null) } catch (e: Exception) { }
        return Pub(target.absolutePath, target.absolutePath)
    }
}

/** Keeps the process alive and shows one progress notification while downloads run. */
class DownloadService : Service() {
    private val h = Handler(Looper.getMainLooper())
    private var idle = 0
    private var wl: android.os.PowerManager.WakeLock? = null // 0.0.366: CPU stays awake with the screen off
    private var wifi: android.net.wifi.WifiManager.WifiLock? = null // 0.0.366: Wi-Fi does not sleep with the screen off
    private var locked = false

    @Suppress("DEPRECATION")
    private fun locks(on: Boolean) {
        try {
            if (on) {
                if (wl == null) wl = (getSystemService(POWER_SERVICE) as android.os.PowerManager).newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "mgb:download").apply { setReferenceCounted(false) }
                wl?.acquire(12 * 60 * 60 * 1000L) // safety limit only; released when no download runs (tick) or the service ends
                if (wifi == null) wifi = (applicationContext.getSystemService(WIFI_SERVICE) as android.net.wifi.WifiManager)
                    .createWifiLock(if (Build.VERSION.SDK_INT >= 29) android.net.wifi.WifiManager.WIFI_MODE_FULL_LOW_LATENCY else android.net.wifi.WifiManager.WIFI_MODE_FULL_HIGH_PERF, "mgb:download").apply { setReferenceCounted(false) }
                if (wifi?.isHeld != true) wifi?.acquire()
            } else {
                if (wl?.isHeld == true) wl?.release()
                if (wifi?.isHeld == true) wifi?.release()
            }
        } catch (_: Exception) { }
        locked = on
    }

    private val tick = object : Runnable {
        override fun run() {
            if (DownloadsApi.active().isEmpty() && DownloadsApi.pausedList().isEmpty() && !BgKeep.engineBusy()) { // 0.0.366: engine jobs keep it alive too
                if (++idle >= 2) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                    return
                }
            } else idle = 0
            val busy = DownloadsApi.active().isNotEmpty() || BgKeep.engineBusy() // 0.0.366: only paused = locks released (battery)
            if (busy && !locked) locks(true) else if (!busy && locked) locks(false)
            try {
                (getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager).notify(NOTE_ID, DownloadsApi.progressNote())
            } catch (e: Exception) { }
            h.postDelayed(this, 1000)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // S45: notification buttons
        when (intent?.action) {
            ACT_PAUSE -> DownloadsApi.pauseAll()
            ACT_RESUME -> DownloadsApi.resumeAll()
            ACT_CANCEL -> DownloadsApi.cancelAll()
        }
        if (intent?.action != null && DownloadsApi.active().isEmpty() && DownloadsApi.pausedList().isEmpty() && !BgKeep.engineBusy()) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
            ServiceCompat.startForeground(this, NOTE_ID, DownloadsApi.progressNote(), type)
        } catch (e: Exception) {
            stopSelf()
            return START_NOT_STICKY
        }
        idle = 0
        locks(true) // 0.0.366
        h.removeCallbacks(tick)
        h.post(tick)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        h.removeCallbacks(tick)
        locks(false) // 0.0.366
        super.onDestroy()
    }

    companion object {
        private const val NOTE_ID = 7001
        const val ACT_PAUSE = "com.jabra.mgb.DL_PAUSE"
        const val ACT_RESUME = "com.jabra.mgb.DL_RESUME"
        const val ACT_CANCEL = "com.jabra.mgb.DL_CANCEL"

        /** Start the foreground service. Android may refuse it when the app is in the background; downloads still run then. */
        fun ensure(ctx: Context) {
            try { ContextCompat.startForegroundService(ctx, Intent(ctx, DownloadService::class.java)) } catch (e: Exception) { }
        }
    }
}
