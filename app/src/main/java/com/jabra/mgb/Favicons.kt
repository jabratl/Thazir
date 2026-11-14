package com.jabra.mgb

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import java.io.File
import java.io.FileOutputStream

/**
 * S45: real site icons. WebChromeClient.onReceivedIcon -> one small PNG per host in filesDir/favicons.
 * Used by the home tiles, the "Jump back in" chips and the tab switcher. Private tabs never save an icon.
 */
object Favicons {
    private lateinit var dir: File
    private val mem = LruCache<String, Bitmap>(80)
    private val none = HashSet<String>() // hosts we looked up and found nothing for (cleared on save)

    fun init(ctx: Context) {
        dir = File(ctx.applicationContext.filesDir, "favicons").also { it.mkdirs() }
    }

    private fun file(host: String) = File(dir, host.lowercase().replace(Regex("[^a-z0-9.-]"), "_") + ".png")

    fun save(host: String, icon: Bitmap) {
        if (!::dir.isInitialized || host.isBlank() || icon.width < 8 || icon.height < 8) return
        try {
            val s = 96
            val bmp = if (icon.width > s || icon.height > s) {
                val r = s.toFloat() / maxOf(icon.width, icon.height)
                Bitmap.createScaledBitmap(icon, maxOf(1, (icon.width * r).toInt()), maxOf(1, (icon.height * r).toInt()), true)
            } else icon
            FileOutputStream(file(host)).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            synchronized(none) { none.remove(host) }
            mem.put(host.lowercase(), bmp)
        } catch (e: Exception) { /* icons are optional */ }
    }

    fun get(host: String): Bitmap? {
        if (!::dir.isInitialized || host.isBlank()) return null
        val k = host.lowercase()
        mem.get(k)?.let { return it }
        synchronized(none) { if (none.contains(k)) return null }
        return try {
            val f = file(k)
            val b = if (f.exists()) BitmapFactory.decodeFile(f.absolutePath) else null
            if (b != null) mem.put(k, b) else synchronized(none) { none.add(k) }
            b
        } catch (e: Exception) { null }
    }

    // S78: icons for hosts we never visited since S45 (Bookmarks / History lists). One try per host per run,
    // https://<host>/favicon.ico only (no third-party icon service), max 300 KB, 2 threads, 150 hosts per run.
    private val tried = HashSet<String>()
    private val waiters = HashMap<String, MutableList<(Bitmap) -> Unit>>()
    private val pool = java.util.concurrent.Executors.newFixedThreadPool(2)
    private var budget = 150
    private val hostOk = Regex("[a-z0-9-]+(\\.[a-z0-9-]+)+")

    /** Calls done on the main thread when an icon was downloaded and saved. Does nothing if we have one or tried already. */
    fun fetch(host: String, done: (Bitmap) -> Unit) {
        if (!::dir.isInitialized) return
        val k = host.lowercase()
        if (!hostOk.matches(k) || get(k) != null) return
        synchronized(tried) {
            val w = waiters[k]
            if (w != null) { w.add(done); return }
            if (tried.contains(k) || budget <= 0) return
            tried.add(k)
            budget--
            waiters[k] = mutableListOf(done)
        }
        pool.execute {
            val bmp = download(k)
            val list = synchronized(tried) { waiters.remove(k) }
            if (bmp != null) {
                save(k, bmp)
                android.os.Handler(android.os.Looper.getMainLooper()).post { list?.forEach { it(bmp) } }
            }
        }
    }

    private fun download(k: String): Bitmap? {
        return try {
            val c = java.net.URL("https://$k/favicon.ico").openConnection() as java.net.HttpURLConnection
            c.connectTimeout = 5000
            c.readTimeout = 5000
            c.instanceFollowRedirects = true
            c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) Thazir")
            if (c.responseCode != 200) { c.disconnect(); return null }
            val bos = java.io.ByteArrayOutputStream()
            val buf = ByteArray(8192)
            var total = 0
            c.inputStream.use { ins ->
                while (true) {
                    val n = ins.read(buf)
                    if (n < 0) break
                    total += n
                    if (total > 300000) return null
                    bos.write(buf, 0, n)
                }
            }
            c.disconnect()
            val b = bos.toByteArray()
            BitmapFactory.decodeByteArray(b, 0, b.size)
        } catch (e: Exception) { null }
    }

    fun clear() {
        try { dir.listFiles()?.forEach { it.delete() } } catch (e: Exception) { }
        mem.evictAll()
        synchronized(none) { none.clear() }
        synchronized(tried) { tried.clear() } // S78
    }
}
