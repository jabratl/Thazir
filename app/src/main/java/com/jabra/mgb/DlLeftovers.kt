package com.jabra.mgb

import android.app.Activity
import android.app.AlertDialog
import java.io.File

/**
 * 0.0.393: files/dl holds ID.part / ID.pp / ID.pp.json of unfinished downloads (counted as "User data" in Android Settings).
 * Anything there that no live item can resume (removed, failed, cancelled, left by a crash) is a leftover and can be deleted.
 * Files of running, paused and resumable-interrupted downloads are never touched.
 */
object DlLeftovers {
    private fun dir(): File = File(DownloadsApi.app.filesDir, "dl")

    private fun idOf(f: File): Int? = f.name.substringBefore('.').toIntOrNull()

    /** Files nobody can resume. */
    fun leftovers(): List<File> {
        val keep = DownloadsApi.keepIds()
        val all = try { dir().listFiles() } catch (e: Exception) { null } ?: return emptyList()
        return all.filter { it.isFile && (idOf(it)?.let { id -> id !in keep } ?: true) }
    }

    fun bytes(): Long = leftovers().sumOf { try { it.length() } catch (e: Exception) { 0L } }

    /** Deletes the leftovers, returns the bytes freed. */
    fun sweep(): Long {
        var freed = 0L
        for (f in leftovers()) {
            val n = try { f.length() } catch (e: Exception) { 0L }
            if (try { f.delete() } catch (e: Exception) { false }) freed += n
        }
        return freed
    }

    fun sweepAsync() {
        Thread {
            try {
                val n = sweep()
                if (n > 0) Diag.i("dlleft", "startup sweep freed " + EngineApi.fmtSize(n))
            } catch (e: Exception) { }
        }.also { it.isDaemon = true }.start()
    }

    fun label(): String = try { val b = bytes(); if (b <= 0L) "None" else EngineApi.fmtSize(b) } catch (e: Exception) { "?" }

    fun dialog(act: Activity, done: () -> Unit) {
        val b = bytes()
        if (b <= 0L) { Util.toast(act, "No leftover download files"); return }
        AlertDialog.Builder(act)
            .setTitle("Delete leftover files?")
            .setMessage(EngineApi.fmtSize(b) + " of unfinished data from removed or failed downloads. Running, paused and resumable downloads are kept. Finished files in Downloads are not touched.")
            .setPositiveButton("Delete") { _, _ ->
                Thread {
                    val n = sweep()
                    act.runOnUiThread { Util.toast(act, "Freed " + EngineApi.fmtSize(n)); done() }
                }.start()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
