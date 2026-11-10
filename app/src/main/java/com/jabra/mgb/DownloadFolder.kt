package com.jabra.mgb

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.File
import java.io.IOException

/**
 * S49: the user can pick any folder as the download location (system folder picker, Storage Access Framework).
 * Default (nothing picked) = the public Downloads folder as before. A picked folder is remembered with a persisted
 * permission; Downloader.publish() calls publish() here first and falls back to Downloads if the folder is gone.
 */
object DownloadFolder {
    private const val PREFS = "mgb_dlfolder"

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** The picked folder, or null (default Downloads) when none or when the permission was lost. */
    fun treeUri(c: Context): Uri? {
        val s = prefs(c).getString("tree", null) ?: return null
        val u = try { Uri.parse(s) } catch (e: Exception) { return null }
        val ok = try { c.contentResolver.persistedUriPermissions.any { it.uri == u && it.isWritePermission } } catch (e: Exception) { false }
        return if (ok) u else null
    }

    /** 0.0.397: NONE = nothing was picked, OK = picked and still allowed, LOST = picked but the permission is gone (files go to Downloads). treeUri() is unchanged. */
    enum class Status { NONE, OK, LOST }

    fun status(c: Context): Status {
        val s = prefs(c).getString("tree", null) ?: return Status.NONE
        val u = try { Uri.parse(s) } catch (e: Exception) { return Status.LOST }
        val ok = try { c.contentResolver.persistedUriPermissions.any { it.uri == u && it.isWritePermission } } catch (e: Exception) { false }
        return if (ok) Status.OK else Status.LOST
    }

    /** Put files into sub-folders (like MGB/...) inside the picked folder. Default OFF: files land straight in it. */
    fun subfolders(c: Context) = prefs(c).getBoolean("sub", false)

    fun setSubfolders(c: Context, on: Boolean) { prefs(c).edit().putBoolean("sub", on).apply() }

    private fun rootDoc(tree: Uri): Uri = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))

    fun label(c: Context): String {
        val t = treeUri(c) ?: return "Downloads"
        var name: String? = null
        try {
            c.contentResolver.query(rootDoc(t), arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) name = it.getString(0)
            }
        } catch (e: Exception) { }
        val n = name ?: DocumentsContract.getTreeDocumentId(t).substringAfterLast(':').substringAfterLast('/')
        return n.ifEmpty { "Phone storage" }.let { if (it.length > 20) it.take(19) + "…" else it }
    }

    fun onPicked(c: Context, uri: Uri) {
        val old = prefs(c).getString("tree", null)
        try {
            c.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        } catch (e: Exception) {
            Util.toast(c, "This folder cannot be used, pick another one")
            return
        }
        prefs(c).edit().putString("tree", uri.toString()).apply()
        if (old != null && old != uri.toString()) release(c, old)
        Util.toast(c, "Downloads will be saved in: " + label(c))
    }

    private fun release(c: Context, s: String) {
        try {
            c.contentResolver.releasePersistableUriPermission(Uri.parse(s), Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        } catch (e: Exception) { }
    }

    fun reset(c: Context) {
        prefs(c).getString("tree", null)?.let { release(c, it) }
        prefs(c).edit().remove("tree").apply()
    }

    /** Settings > Downloads > Download folder. */
    fun showDialog(act: Activity, changed: () -> Unit) {
        val custom = treeUri(act) != null
        MaterialAlertDialogBuilder(act)
            .setTitle("Download folder")
            .setMessage(
                "Now: " + label(act) + "\n\n" + if (custom) "Files are saved straight into this folder (turn on sub-folders to keep MGB/... folders)."
                else "Files go to the Downloads folder of the phone, in sub-folders the grabber asks for (like MGB/)."
            )
            .setPositiveButton("Choose folder...") { _, _ -> BrowserExtras.pickFolder { changed() } }
            .setNeutralButton("Use default") { _, _ -> reset(act); changed() }
            .setNegativeButton("Close", null)
            .show()
    }

    // ---------- writing a finished file into the picked folder ----------

    private class Child(val uri: Uri, val isDir: Boolean)

    private fun findChild(c: Context, tree: Uri, parent: Uri, name: String): Child? {
        val kids = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getDocumentId(parent))
        c.contentResolver.query(kids, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE), null, null, null)?.use { q ->
            while (q.moveToNext()) {
                if (q.getString(1) == name) {
                    return Child(DocumentsContract.buildDocumentUriUsingTree(tree, q.getString(0)), q.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR)
                }
            }
        }
        return null
    }

    private fun childDir(c: Context, tree: Uri, parent: Uri, name: String): Uri {
        val ex = findChild(c, tree, parent, name)
        if (ex != null && ex.isDir) return ex.uri
        return DocumentsContract.createDocument(c.contentResolver, parent, DocumentsContract.Document.MIME_TYPE_DIR, name)
            ?: throw IOException("cannot create folder $name")
    }

    /** Returns (text shown as the file path, document uri). Throws when the folder cannot be written. */
    fun publish(c: Context, tree: Uri, item: DlItem, part: File, dir: String, name: String, mime: String): Pair<String, String> {
        val cr = c.contentResolver
        var parent = rootDoc(tree)
        var shownDir = ""
        if (subfolders(c) && dir.isNotEmpty()) {
            for (seg in dir.split('/')) {
                if (seg.isEmpty()) continue
                parent = childDir(c, tree, parent, seg)
                shownDir += "$seg/"
            }
        }
        var finalName = name
        val ex = findChild(c, tree, parent, name)
        if (ex != null) {
            if (item.conflict == "overwrite" && !ex.isDir) {
                try { DocumentsContract.deleteDocument(cr, ex.uri) } catch (e: Exception) { }
            }
            if (findChild(c, tree, parent, name) != null) {
                val stem = name.substringBeforeLast('.')
                val dotExt = if (name.contains('.')) "." + name.substringAfterLast('.') else ""
                var n = 1
                while (findChild(c, tree, parent, "$stem ($n)$dotExt") != null && n < 1000) n++
                finalName = "$stem ($n)$dotExt"
            }
        }
        val doc = DocumentsContract.createDocument(cr, parent, mime, finalName) ?: throw IOException("cannot create file")
        try {
            val os = cr.openOutputStream(doc, "w") ?: throw IOException("cannot open output")
            os.use { o -> part.inputStream().use { it.copyTo(o, 65536) } }
        } catch (e: Exception) {
            try { DocumentsContract.deleteDocument(cr, doc) } catch (ignored: Exception) { }
            throw e
        }
        var real = finalName
        try {
            cr.query(doc, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) real = it.getString(0) ?: finalName
            }
        } catch (e: Exception) { }
        return Pair(label(c) + "/" + shownDir + real, doc.toString())
    }
}
