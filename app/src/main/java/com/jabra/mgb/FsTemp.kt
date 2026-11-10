package com.jabra.mgb

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import java.io.File

/**
 * 0.0.395: app_webview/Default/File System = the browser's "file system" storage of all sites and extensions. The Media Grabber
 * (downloader.js) keeps the segments of a stream there (OPFS folder mg-seg) until the saved file is written. Segments of failed, cancelled
 * or closed-too-early downloads stayed for days: a report showed 4.18 GB in File System/000. Android never counts it as cache.
 *
 * The folder can only be removed safely while no WebView of this app runs, so it is done at the very start of a cold app start
 * (MainActivity.onCreate, first call in the process): when the user asked for it (Settings) or when it is over AUTO_BYTES.
 * Cookies, logins, localStorage, IndexedDB and Service Workers are other folders and are not touched.
 */
object FsTemp {
    private const val PREFS = "mgb_fstemp"
    private const val AUTO_BYTES = 1024L * 1024L * 1024L
    private var ran = false

    private fun dir(c: Context) = File(c.applicationContext.filesDir.parentFile, "app_webview/Default/File System")

    private fun size(f: File, budget: IntArray): Long {
        if (budget[0]-- <= 0) return 0L
        return try { if (f.isFile) f.length() else (f.listFiles() ?: emptyArray()).sumOf { size(it, budget) } } catch (e: Exception) { 0L }
    }

    fun bytes(c: Context): Long = try { size(dir(c), intArrayOf(300000)) } catch (e: Exception) { 0L }

    fun pending(c: Context): Boolean = try { c.getSharedPreferences(PREFS, 0).getBoolean("wipe", false) } catch (e: Exception) { false }

    fun label(c: Context): String {
        val b = bytes(c)
        return (if (b <= 0L) "None" else EngineApi.fmtSize(b)) + (if (pending(c)) " (clears at next start)" else "")
    }

    /** Call first thing in MainActivity.onCreate. Only the first call in a process does anything. Never throws. */
    fun wipeIfDue(c: Context) {
        if (ran) return
        ran = true
        try {
            val d = dir(c)
            if (!d.exists()) { c.getSharedPreferences(PREFS, 0).edit().putBoolean("wipe", false).apply(); return }
            val asked = pending(c)
            val b = if (asked) 0L else bytes(c)
            if (asked || b >= AUTO_BYTES) {
                val n = bytes(c)
                d.deleteRecursively()
                c.getSharedPreferences(PREFS, 0).edit().putBoolean("wipe", false).apply()
                try { Diag.i("fstemp", (if (asked) "asked" else "auto") + ": removed File System " + EngineApi.fmtSize(n)) } catch (e: Exception) { }
            }
        } catch (e: Exception) { }
    }

    fun dialog(act: Activity, done: () -> Unit) {
        val b = bytes(act)
        if (b <= 0L && !pending(act)) { Util.toast(act, "No grabber temporary data"); return }
        AlertDialog.Builder(act)
            .setTitle("Clear grabber temporary data?")
            .setMessage(EngineApi.fmtSize(b) + " of temporary stream pieces kept by the browser (File System). It is removed the next time Thazir starts: close Thazir completely (remove it from recent apps) and open it again. Logins, cookies and finished downloads are not touched. Unsaved stream recordings that could still be recovered are lost.")
            .setPositiveButton("Clear at next start") { _, _ ->
                act.getSharedPreferences(PREFS, 0).edit().putBoolean("wipe", true).apply()
                Util.toast(act, "Close Thazir and open it again"); done()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
