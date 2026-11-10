package com.jabra.mgb

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Handler
import android.os.Looper
import java.io.FileOutputStream
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * APPEARANCE A7: home-page wallpaper. The picked image is downscaled (long side <= 1920, short side <= 1080)
 * and saved as filesDir/wallpaper.jpg (file exists = wallpaper on). Not part of the backup. EXIF rotation is not applied.
 */
object Wallpaper {
    /** HomeView registers here while it is on screen; called on the UI thread after save / remove / dim change. */
    val listeners = ArrayList<() -> Unit>()
    private var cache: Bitmap? = null
    private var stamp = ""

    fun notifyChanged() {
        val run = { for (l in ArrayList(listeners)) try { l() } catch (_: Exception) { } }
        if (Looper.myLooper() == Looper.getMainLooper()) run() else Handler(Looper.getMainLooper()).post { run() }
    }

    @Volatile private var badStamp = ""   // 0.0.395: a file that could not be decoded is not tried again until it changes
    @Volatile private var busy = false

    private fun stampOf(f: java.io.File) = f.lastModified().toString() + ":" + f.length()

    /** Decodes the saved file (RGB_565 = half the memory of the old ARGB_8888). Any error = null. */
    @Synchronized private fun decodeNow(f: java.io.File, s: String): Bitmap? {
        return try {
            val o = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.RGB_565 }
            val b = BitmapFactory.decodeFile(f.absolutePath, o)
            if (b == null) { badStamp = s; cache = null; stamp = "" } else { cache = b; stamp = s }
            b
        } catch (_: Throwable) { badStamp = s; cache = null; stamp = ""; null }
    }

    /**
     * The wallpaper bitmap, or null when there is none / it cannot be read / it is still being read.
     * 0.0.395: on the UI thread a missing bitmap is read on a worker thread (no more freezes on a big wallpaper) and
     * the listeners are told when it is ready. Cached until the file changes.
     */
    fun load(c: Context): Bitmap? {
        val f = AppearancePrefs.wallpaperFile(c)
        if (!f.exists()) { cache = null; stamp = ""; return null }
        val s = stampOf(f)
        if (cache != null && s == stamp) return cache
        if (s == badStamp) return null
        if (Looper.myLooper() == Looper.getMainLooper()) {
            if (!busy) {
                busy = true
                Thread {
                    try { decodeNow(f, s) } finally { busy = false }
                    notifyChanged()
                }.also { it.isDaemon = true }.start()
            }
            return null
        }
        return decodeNow(f, s)
    }

    /** Decodes, downscales and saves the picked image. Call off the UI thread. */
    fun save(c: Context, uri: Uri): Boolean {
        return try {
            val cr = c.contentResolver
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return false
            val lng = max(bounds.outWidth, bounds.outHeight)
            val sht = min(bounds.outWidth, bounds.outHeight)
            var s = 1
            while (lng / (s * 2) >= 1920 && sht / (s * 2) >= 1080) s *= 2
            val opt = BitmapFactory.Options().apply { inSampleSize = s }
            val bm = cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opt) } ?: return false
            val k = minOf(1f, 1920f / max(bm.width, bm.height), 1080f / min(bm.width, bm.height))
            val out = if (k < 1f) Bitmap.createScaledBitmap(bm, (bm.width * k).roundToInt().coerceAtLeast(1), (bm.height * k).roundToInt().coerceAtLeast(1), true) else bm
            FileOutputStream(AppearancePrefs.wallpaperFile(c)).use { out.compress(Bitmap.CompressFormat.JPEG, 88, it) }
            if (out !== bm) try { bm.recycle() } catch (_: Throwable) { }
            cache = null; stamp = ""; badStamp = ""
            val f = AppearancePrefs.wallpaperFile(c)
            decodeNow(f, stampOf(f)) // 0.0.395: ready before the pages redraw (this runs off the UI thread)
            true
        } catch (_: Throwable) { false } // includes OutOfMemoryError for huge photos
    }

    fun remove(c: Context) {
        try { AppearancePrefs.wallpaperFile(c).delete() } catch (_: Exception) { }
        cache = null; stamp = ""
    }
}
