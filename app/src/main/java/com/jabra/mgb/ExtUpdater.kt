package com.jabra.mgb

import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.webkit.MimeTypeMap
import android.webkit.WebResourceResponse
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipInputStream

/**
 * S16: hot update of the extension files (V77 bundle = assets/ext) without a new APK.
 *  - CI attaches MGB-ext-b<run>-n<hash>.zip to every release. <hash> = fingerprint of everything that is NOT
 *    the ext folder (Kotlin, res, shim, manifests). Same hash as this app (BuildConfig.NATIVE_HASH) = the
 *    new ext files work with the installed native code, so only the ext zip is needed.
 *  - Downloaded zip is unpacked to filesDir/ext_hot/b<run>. It is used from the NEXT app start only
 *    (the running engine and injected scripts keep the old files, so nothing is mixed).
 *  - ContentInjector.read and EngineHost.page ask this object first, then fall back to the built-in assets.
 *  - A newer APK (versionCode >= bundle build) or a different hash drops the hot files automatically.
 */
object ExtUpdater {
    private const val PREFS = "mgb_ext"
    private const val KEY_DIR = "dir"
    private val NAME = Regex("-ext-b(\\d+)-n([0-9a-f]{8,64})\\.zip$")
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    @Volatile private var active: File? = null
    @Volatile private var activeBuild = 0
    private var inited = false
    private lateinit var root: File
    private lateinit var prefs: SharedPreferences

    @Synchronized fun init(ctx: Context) {
        if (inited) return
        inited = true
        val app = ctx.applicationContext
        root = File(app.filesDir, "ext_hot")
        prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val name = prefs.getString(KEY_DIR, "") ?: ""
        var keep = ""
        if (name.isNotEmpty() && BuildConfig.NATIVE_HASH.isNotEmpty()) {
            val d = File(root, name)
            val meta = readMeta(d)
            if (meta != null && meta.first > BuildConfig.VERSION_CODE && meta.second == BuildConfig.NATIVE_HASH &&
                File(d, "manifest.json").isFile
            ) {
                active = d
                activeBuild = meta.first
                keep = name
            }
        }
        if (keep.isEmpty()) prefs.edit().remove(KEY_DIR).apply()
        root.listFiles()?.forEach { if (it.name != keep) it.deleteRecursively() }
    }

    private fun readMeta(d: File): Pair<Int, String>? = try {
        val j = JSONObject(File(d, "_bundle.json").readText())
        Pair(j.getInt("build"), j.getString("native"))
    } catch (e: Exception) {
        null
    }

    // ---------- serving (used by ContentInjector and EngineHost) ----------

    internal fun safeFile(d: File, rel: String): File? { // S0: reused by ExtRegistry
        if (rel.isEmpty() || rel.contains("..") || rel.contains('\\')) return null
        val f = File(d, rel)
        return if (f.isFile && f.canonicalPath.startsWith(d.canonicalPath + File.separator)) f else null
    }

    /** assetPath like "ext/content.js" (same string as the assets path). null = use the built-in asset. */
    fun readText(assetPath: String): String? {
        val d = active ?: return null
        if (!assetPath.startsWith("ext/")) return null
        val f = safeFile(d, assetPath.substring(4)) ?: return null
        return try { f.readText() } catch (e: Exception) { null }
    }

    /** path relative to the ext folder, for example "popup.html". null = use the built-in asset. */
    fun open(path: String): WebResourceResponse? {
        val d = active ?: return null
        val f = safeFile(d, path) ?: return null
        return try {
            val mime = mimeOf(f.name)
            val enc = if (mime.startsWith("text/") || mime == "application/json") "utf-8" else null
            WebResourceResponse(mime, enc, FileInputStream(f))
        } catch (e: Exception) {
            null
        }
    }

    private fun mimeOf(name: String): String {
        val x = name.substringAfterLast('.', "").lowercase()
        return when (x) {
            "html", "htm" -> "text/html"
            "js", "mjs" -> "text/javascript"
            "css" -> "text/css"
            "json" -> "application/json"
            "svg" -> "image/svg+xml"
            "wasm" -> "application/wasm"
            "txt" -> "text/plain"
            else -> MimeTypeMap.getSingleton().getMimeTypeFromExtension(x) ?: "application/octet-stream"
        }
    }

    // ---------- update logic (used by UpdateManager) ----------

    /** Build number of the release's ext zip if it fits this app's native code, else -1. */
    fun matchBuild(assetName: String): Int {
        if (BuildConfig.NATIVE_HASH.isEmpty() || assetName.isEmpty()) return -1
        val m = NAME.find(assetName) ?: return -1
        if (m.groupValues[2] != BuildConfig.NATIVE_HASH) return -1
        return m.groupValues[1].toIntOrNull() ?: -1
    }

    /** The newest ext files this process runs: built-in (= APK build) or the hot bundle. */
    fun currentBuild(): Int = maxOf(BuildConfig.VERSION_CODE, activeBuild)

    fun showDialog(a: Activity, tag: String, notes: String, url: String, name: String, size: Long, build: Int) {
        val v = LayoutInflater.from(a).inflate(R.layout.dialog_update, null)
        val progress = v.findViewById<LinearProgressIndicator>(R.id.upd_progress)
        val status = v.findViewById<TextView>(R.id.upd_status)
        v.findViewById<TextView>(R.id.upd_version).text =
            "Extension files $tag  (build $build, now ${currentBuild()})" +
                if (size > 0) "  •  ${Util.fmtBytes(size)}" else ""
        v.findViewById<TextView>(R.id.upd_notes).text =
            notes.ifBlank { "New extension files. No app install needed." }

        val cancelled = AtomicBoolean(false)
        val dlg = MaterialAlertDialogBuilder(a)
            .setTitle("Extension update")
            .setView(v)
            .setPositiveButton("Update", null)
            .setNegativeButton("Later", null)
            .create()
        dlg.setOnDismissListener { cancelled.set(true) }
        dlg.show()

        val pos = dlg.getButton(AlertDialog.BUTTON_POSITIVE)
        pos.setOnClickListener {
            pos.isEnabled = false
            progress.visibility = View.VISIBLE
            status.visibility = View.VISIBLE
            status.text = "Starting…"
            io.execute {
                try {
                    install(url, size, build, cancelled, { done, total ->
                        main.post {
                            if (total > 0) progress.progress = (done * 100 / total).toInt()
                            status.text = "Downloading ${Util.fmtBytes(done)}" +
                                if (total > 0) " / ${Util.fmtBytes(total)}" else ""
                        }
                    }, { note -> main.post { if (!cancelled.get()) status.text = note } }) // 0.0.413: "Server busy, retrying (n/6)"
                    main.post {
                        if (cancelled.get()) return@post
                        dlg.setOnDismissListener(null)
                        dlg.dismiss()
                        askClose(a, "Extension files updated to build $build.")
                    }
                } catch (e: Exception) {
                    main.post {
                        if (cancelled.get()) return@post
                        status.text = "Failed: ${e.message}"
                        pos.isEnabled = true
                        pos.text = "Retry"
                    }
                }
            }
        }
    }

    /** Manual check, nothing newer: if a hot bundle is active, offer to go back to the built-in files. */
    fun offerReset(a: Activity) {
        if (active == null) return
        MaterialAlertDialogBuilder(a)
            .setTitle("Extension files")
            .setMessage("Running updated extension files (build $activeBuild) instead of the built-in ones. Go back to the built-in files?")
            .setPositiveButton("Reset") { _, _ ->
                prefs.edit().remove(KEY_DIR).commit()
                askClose(a, "Reset done.")
            }
            .setNegativeButton("Keep", null)
            .show()
    }

    private fun askClose(a: Activity, msg: String) {
        if (a.isFinishing || a.isDestroyed) return
        MaterialAlertDialogBuilder(a)
            .setTitle("Restart needed")
            .setMessage("$msg Close Thazir now, then open it again to use the new files. (Running downloads stop if you close now.)")
            .setPositiveButton("Close MGB") { _, _ ->
                a.finishAffinity()
                main.postDelayed({ android.os.Process.killProcess(android.os.Process.myPid()) }, 800)
            }
            .setNegativeButton("Later", null)
            .show()
    }

    // ---------- download + unpack + check + switch ----------

    private fun install(url: String, size: Long, build: Int, cancelled: AtomicBoolean, onProgress: (Long, Long) -> Unit, onNote: (String) -> Unit = {}) {
        if (!inited) throw Exception("not ready")
        root.mkdirs()
        val part = File(root, "dl.part")
        val stage = File(root, "stage")
        val dirName = "b$build"
        val dest = File(root, dirName)
        try {
            download(url, size, part, cancelled, onProgress, onNote)
            stage.deleteRecursively()
            stage.mkdirs()
            unzip(part, stage)
            validate(stage, build)
            dest.deleteRecursively()
            if (!stage.renameTo(dest)) throw Exception("could not save the files")
            // active stays as it is: the new folder is used from the next start
            if (!prefs.edit().putString(KEY_DIR, dirName).commit()) throw Exception("could not save the setting")
        } finally {
            part.delete()
            stage.deleteRecursively()
        }
    }

    /** 0.0.413: a temporary server / network problem (408, 429, 5xx, connection lost) is retried up to 6 times; a real error (404, bad file) fails at once. */
    private class Busy(m: String, val waitMs: Long) : IOException(m)

    private fun download(url: String, size: Long, part: File, cancelled: AtomicBoolean, onProgress: (Long, Long) -> Unit, onNote: (String) -> Unit) {
        var attempt = 0
        while (true) {
            try {
                downloadOnce(url, size, part, cancelled, onProgress)
                return
            } catch (e: IOException) {
                if (cancelled.get()) throw Exception("cancelled")
                attempt++
                if (attempt >= 6) throw Exception(e.message ?: "connection lost")
                val busy = e is Busy
                val waitMs = if (e is Busy && e.waitMs > 0) e.waitMs else if (busy) minOf(3000L * attempt, 20_000L) else minOf(2000L * attempt, 10_000L)
                onNote((if (busy) "Server busy, retrying" else "Connection lost, retrying") + " ($attempt/6)…")
                var w = 0L
                while (w < waitMs) {
                    if (cancelled.get()) throw Exception("cancelled")
                    try { Thread.sleep(250) } catch (_: InterruptedException) { throw Exception("cancelled") }
                    w += 250
                }
            }
        }
    }

    private fun downloadOnce(url: String, size: Long, part: File, cancelled: AtomicBoolean, onProgress: (Long, Long) -> Unit) {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000
        c.readTimeout = 30_000
        c.instanceFollowRedirects = true
        c.setRequestProperty("User-Agent", "MGB-updater")
        c.setRequestProperty("Accept", "application/octet-stream")
        try {
            val code = c.responseCode
            if (code == 408 || code == 429 || code in 500..599) { // 0.0.413: temporary, retry (Retry-After seconds respected, max 30 s)
                val ra = (c.getHeaderField("Retry-After")?.trim()?.toLongOrNull() ?: 0L).coerceIn(0L, 30L) * 1000L
                throw Busy("server busy (HTTP $code)", ra)
            }
            if (code != 200) throw Exception("download HTTP $code")
            val total = if (c.contentLengthLong > 0) c.contentLengthLong else size
            c.inputStream.use { inp ->
                part.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    var last = 0L
                    while (true) {
                        if (cancelled.get()) throw Exception("cancelled")
                        val n = inp.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        val now = System.currentTimeMillis()
                        if (now - last > 150) { last = now; onProgress(done, total) }
                    }
                }
            }
            if (size > 0 && part.length() != size) throw Exception("incomplete download")
        } finally {
            c.disconnect()
        }
    }

    internal fun unzip(zip: File, dest: File) { // S0: reused by ExtRegistry
        val base = dest.canonicalPath + File.separator
        var total = 0L
        var count = 0
        ZipInputStream(FileInputStream(zip).buffered()).use { zin ->
            while (true) {
                val e = zin.nextEntry ?: break
                if (e.isDirectory) continue
                val n = e.name
                if (n.startsWith("/") || n.contains("..") || n.contains('\\')) throw Exception("bad path in zip")
                if (++count > 2000) throw Exception("too many files in zip")
                val f = File(dest, n)
                if (!f.canonicalPath.startsWith(base)) throw Exception("bad path in zip")
                f.parentFile?.mkdirs()
                f.outputStream().use { out ->
                    val buf = ByteArray(32 * 1024)
                    while (true) {
                        val r = zin.read(buf)
                        if (r < 0) break
                        total += r
                        if (total > 64L * 1024 * 1024) throw Exception("zip too big")
                        out.write(buf, 0, r)
                    }
                }
            }
        }
        if (count == 0) throw Exception("empty zip")
    }

    private fun validate(d: File, build: Int) {
        val meta = readMeta(d) ?: throw Exception("bundle info missing")
        if (meta.first != build || meta.second != BuildConfig.NATIVE_HASH) throw Exception("bundle does not fit this app")
        val m = JSONObject(File(d, "manifest.json").readText())
        if (m.optString("version").isEmpty()) throw Exception("bad manifest")
        val need = LinkedHashSet<String>(listOf("background.js", "shared.js", "sites.js"))
        val cs = m.optJSONArray("content_scripts")
        if (cs != null) {
            for (i in 0 until cs.length()) {
                val js = cs.getJSONObject(i).optJSONArray("js") ?: continue
                for (j in 0 until js.length()) need.add(js.getString(j))
            }
        }
        for (n in need) if (!File(d, n).isFile) throw Exception("missing file $n")
    }
}
