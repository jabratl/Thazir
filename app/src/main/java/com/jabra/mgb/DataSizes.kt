package com.jabra.mgb

import android.content.Context
import java.io.File

/**
 * 0.0.393: where the "User data" of the app sits. One line per top-level folder of the app data dir, then the biggest entries inside
 * files/ and app_webview/, then files/dl (unfinished downloads) with the part that nothing can resume. Read only, never throws.
 */
object DataSizes {
    private const val MAX_FILES = 300000

    private fun size(f: File, budget: IntArray): Long {
        if (budget[0] <= 0) return 0L
        budget[0]--
        return try {
            if (f.isFile) f.length() else {
                var t = 0L
                f.listFiles()?.forEach { t += size(it, budget) }
                t
            }
        } catch (e: Exception) { 0L }
    }

    private fun mb(b: Long): String = if (b >= 1048576L * 1024L) String.format("%.2f GB", b / 1073741824.0) else String.format("%.1f MB", b / 1048576.0)

    private fun top(dir: File, label: String, n: Int, out: MutableList<String>) {
        val kids = try { dir.listFiles() } catch (e: Exception) { null } ?: return
        val rows = kids.map { it to size(it, intArrayOf(MAX_FILES)) }.sortedByDescending { it.second }.take(n)
        for ((f, s) in rows) if (s >= 1048576L) out.add("  $label/" + f.name + (if (f.isDirectory) "/" else "") + ": " + mb(s))
    }

    fun lines(ctx: Context): List<String> {
        val out = ArrayList<String>()
        try {
            val budget = intArrayOf(MAX_FILES)
            val root = ctx.filesDir.parentFile ?: return listOf("data dir not readable")
            val rows = (root.listFiles() ?: emptyArray()).map { it to size(it, budget) }.sortedByDescending { it.second }
            out.add("App data total: " + mb(rows.sumOf { it.second }) + " (" + root.name + ")")
            for ((f, s) in rows) out.add(f.name + (if (f.isDirectory) "/" else "") + ": " + mb(s))
            top(ctx.filesDir, "files", 8, out)
            top(File(root, "app_webview"), "app_webview", 5, out)
            val dl = File(ctx.filesDir, "dl")
            val dlFiles = dl.listFiles()?.filter { it.isFile } ?: emptyList()
            out.add("files/dl: " + dlFiles.size + " file(s), " + mb(dlFiles.sumOf { it.length() }) + ", leftovers nobody can resume: " + mb(DlLeftovers.bytes()))
            val ext = ctx.getExternalFilesDir(null)
            if (ext != null) out.add("External app folder (Android/data): " + mb(size(ext, intArrayOf(MAX_FILES))))
            if (budget[0] <= 0) out.add("(stopped counting after $MAX_FILES files, sizes may be low)")
        } catch (e: Exception) { out.add("size check failed: " + (e.message ?: e.javaClass.simpleName)) }
        return out
    }
}
