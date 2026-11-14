package com.jabra.mgb

import android.content.Context
import android.content.Intent
import android.util.Base64
import android.net.Uri
import android.provider.DocumentsContract
import org.json.JSONArray
import org.json.JSONObject
import java.io.FileInputStream
import java.io.IOException
import java.io.OutputStream
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * V2: the vault container (storage + state). No UI. Crypto is in [VaultCrypto]; the old [Vault] object is untouched.
 *
 * The container is a folder the user picked with the system folder picker (SAF tree uri). It lives OUTSIDE the app, so it survives app
 * updates and uninstall. Layout (see VaultCrypto for the byte formats):
 *   vault.hdr      JSON header with the wrapped keys
 *   index.enc      encrypted JSON {v, items:[...], folders:[...]}; index.bak = previous good copy; index.tmp only while saving
 *   d/<16hex>.bin  encrypted blobs (saved pages, files); names are random ids, titles live only inside the encrypted index
 *
 * Items are plain JSONObjects (unknown fields are kept when saved). Agreed fields: id, type "bm"|"hist"|"page"|"file", title, url, time,
 * folder, size, mime, blob ("<id>.bin" or ""), orig (JSONObject: name, rel). V4+ wrap them in a list UI.
 *
 * Threading: everything that touches the folder BLOCKS, call it off the main thread. state() and lock() are cheap.
 * State: NONE (no folder chosen) | FOLDER_MISSING (folder / permission / header gone: never auto-creates a new vault) | LOCKED | UNLOCKED.
 */
object VaultStore {
    enum class State { NONE, FOLDER_MISSING, LOCKED, UNLOCKED }
    class Res(val ok: Boolean, val msg: String = "", val recovery: String = "")

    private const val PREFS = "mgb_vault"
    private const val HDR = "vault.hdr"
    private const val IDX = "index.enc"
    private const val IDX_TMP = "index.tmp"
    private const val IDX_BAK = "index.bak"
    private const val HDR_BAK = "vault.hdr.bak" // V11: previous header while a new one is being written
    private const val DATA = "d"
    const val INDEX_V = 1
    private const val BIN = "application/octet-stream"
    private val DIR = DocumentsContract.Document.MIME_TYPE_DIR

    private val ioLock = Any()
    private val io = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "vault-io").also { it.isDaemon = true } }
    private var pending: ScheduledFuture<*>? = null

    private var dek: ByteArray? = null
    private var index: JSONObject? = null
    private var header: VaultCrypto.Header? = null
    private var dataUri: Uri? = null
    private var leftAt = 0L
    @Volatile private var dirty = false
    @Volatile private var gen = 0L // V4: bumped by every change so a flush that started earlier cannot clear "dirty" for newer changes
    @Volatile private var folderOk = true
    @Volatile var lastError = ""
        private set

    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    fun treeUri(c: Context): Uri? = prefs(c).getString("tree", null)?.let { Uri.parse(it) }
    private fun rootDoc(tree: Uri): Uri = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))

    // ---------- state ----------

    fun state(c: Context): State = when {
        treeUri(c) == null -> State.NONE
        !folderOk -> State.FOLDER_MISSING
        dek == null -> State.LOCKED
        else -> State.UNLOCKED
    }

    fun isUnlocked(): Boolean = dek != null

    /** BLOCKING. Re-checks permission + vault.hdr and updates the state. Call when the vault screen opens. */
    fun check(c: Context): State {
        val tree = treeUri(c) ?: return State.NONE
        folderOk = try {
            val perm = c.contentResolver.persistedUriPermissions.any { it.uri == tree && it.isReadPermission && it.isWritePermission }
            perm && child(c, tree, rootDoc(tree), HDR) != null
        } catch (e: Exception) { false }
        if (!folderOk) lastError = "The vault folder or its permission is gone. Pick the same folder again."
        return state(c)
    }

    // ---------- SAF helpers ----------

    private class Doc(val uri: Uri, val isDir: Boolean)

    private fun child(c: Context, tree: Uri, parent: Uri, name: String): Doc? {
        val kids = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getDocumentId(parent))
        c.contentResolver.query(
            kids,
            arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE),
            null, null, null
        )?.use { q ->
            while (q.moveToNext()) {
                if (q.getString(1) == name) return Doc(DocumentsContract.buildDocumentUriUsingTree(tree, q.getString(0)), q.getString(2) == DIR)
            }
        }
        return null
    }

    private fun makeFile(c: Context, parent: Uri, name: String): Uri =
        DocumentsContract.createDocument(c.contentResolver, parent, BIN, name) ?: throw IOException("cannot create $name")

    private fun readAll(c: Context, uri: Uri): ByteArray =
        c.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: throw IOException("cannot read")

    private fun writeAll(c: Context, uri: Uri, data: ByteArray) {
        (c.contentResolver.openOutputStream(uri, "wt") ?: throw IOException("cannot write")).use { it.write(data); it.flush() }
    }

    private fun del(c: Context, uri: Uri) {
        try { DocumentsContract.deleteDocument(c.contentResolver, uri) } catch (e: Exception) { }
    }

    private fun dataDir(c: Context): Uri {
        dataUri?.let { return it }
        val tree = treeUri(c) ?: throw IOException("no folder")
        val root = rootDoc(tree)
        val ex = child(c, tree, root, DATA)
        val u = if (ex != null && ex.isDir) ex.uri
        else DocumentsContract.createDocument(c.contentResolver, root, DIR, DATA) ?: throw IOException("cannot create data folder")
        dataUri = u
        return u
    }

    private fun key(): ByteArray = dek ?: throw IllegalStateException("vault is locked")

    private fun readHeader(c: Context): VaultCrypto.Header {
        val tree = treeUri(c) ?: throw IOException("no folder")
        val d = child(c, tree, rootDoc(tree), HDR) ?: throw IOException("vault.hdr not found")
        val h = try { VaultCrypto.Header.parse(String(readAll(c, d.uri), Charsets.UTF_8)) } catch (e: Exception) {
            // V11: a header write that was interrupted leaves the previous good header as vault.hdr.bak
            val b = child(c, tree, rootDoc(tree), HDR_BAK) ?: throw e
            VaultCrypto.Header.parse(String(readAll(c, b.uri), Charsets.UTF_8))
        }
        if (h.v > VaultCrypto.HDR_V) throw IOException("NEWER")
        return h
    }

    /** BLOCKING. V10: looks for vault.hdr in a freshly picked folder WITHOUT remembering it or creating anything.
     *  1 = vault found, 2 = made by a newer Thazir, 0 = no vault here, -1 = folder cannot be read. */
    fun probe(c: Context, tree: Uri): Int = try {
        val d = child(c.applicationContext, tree, rootDoc(tree), HDR)
        if (d == null) 0
        else if (VaultCrypto.Header.parse(String(readAll(c.applicationContext, d.uri), Charsets.UTF_8)).v > VaultCrypto.HDR_V) 2
        else 1
    } catch (e: Exception) { -1 }

    // ---------- bind / create / unlock / lock ----------

    /** Remember the folder the user picked (persistable permission). Does not create anything. */
    fun bind(c: Context, uri: Uri): Boolean {
        val app = c.applicationContext
        try {
            app.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        } catch (e: Exception) { lastError = "This folder cannot be used, pick another one."; return false }
        val old = prefs(app).getString("tree", null)
        lock(app)
        prefs(app).edit().putString("tree", uri.toString()).remove("qk").apply()
        dataUri = null; header = null; folderOk = true
        // do not release a folder the download feature also uses
        if (old != null && old != uri.toString() && old != DownloadFolder.treeUri(app)?.toString()) {
            try { app.contentResolver.releasePersistableUriPermission(Uri.parse(old), Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) } catch (e: Exception) { }
        }
        return true
    }

    /** Forget the folder (does NOT delete anything in it). */
    fun unbind(c: Context) {
        val app = c.applicationContext
        lock(app)
        prefs(app).edit().remove("tree").remove("qk").apply()
        dataUri = null; header = null; folderOk = true
    }

    /** BLOCKING. New vault in the bound folder. Refuses when a vault.hdr is already there. Returns the recovery key (show once). */
    fun create(c: Context, pass: CharArray): Res {
        val app = c.applicationContext
        val tree = treeUri(app) ?: return Res(false, "Pick a folder first.")
        var hdrUri: Uri? = null
        try {
            val root = rootDoc(tree)
            if (child(app, tree, root, HDR) != null) return Res(false, "A vault already exists in this folder. Use restore instead.")
            val cr = VaultCrypto.create(pass)
            hdrUri = makeFile(app, root, HDR)
            writeAll(app, hdrUri, cr.header.toJson().toString().toByteArray(Charsets.UTF_8))
            dataDir(app)
            val idx = JSONObject().put("v", INDEX_V).put("items", JSONArray()).put("folders", JSONArray())
            val iu = makeFile(app, root, IDX)
            writeAll(app, iu, VaultCrypto.encStr(cr.dek, idx.toString()).toByteArray(Charsets.UTF_8))
            synchronized(this) { dek = cr.dek; index = idx; header = cr.header; dirty = false }
            folderOk = true; leftAt = System.currentTimeMillis()
            return Res(true, "", cr.recovery)
        } catch (e: Exception) {
            hdrUri?.let { del(app, it) }
            lastError = "Could not create the vault: " + (e.message ?: e.javaClass.simpleName)
            return Res(false, lastError)
        } finally { VaultCrypto.wipe(pass) }
    }

    /** BLOCKING. Unlock with the passphrase. */
    fun unlock(c: Context, pass: CharArray): Res = unlockWith(c) { h -> VaultCrypto.openPass(h, pass) }.also { VaultCrypto.wipe(pass) }

    /** BLOCKING. Unlock with the recovery key (after reinstall or forgotten passphrase). */
    fun unlockRecovery(c: Context, recovery: String): Res = unlockWith(c) { h -> VaultCrypto.openRecovery(h, recovery) }

    /** BLOCKING. Unlock with a DEK from the per-install quick-unlock wrap (V3). The array is copied, caller wipes its own. */
    fun unlockWithKey(c: Context, key: ByteArray): Res = unlockWith(c, false) { h -> if (VaultCrypto.verifyDek(h, key)) key.copyOf() else null }

    private fun unlockWith(c: Context, count: Boolean = true, open: (VaultCrypto.Header) -> ByteArray?): Res {
        val app = c.applicationContext
        if (treeUri(app) == null) return Res(false, "No vault folder chosen.")
        if (count && lockoutLeftMs(app) > 0L) return Res(false, tooMany(app)) // V11: wait after wrong tries
        try {
            val h = readHeader(app)
            val k = open(h) ?: return Res(false, if (count) { noteFail(app); if (lockoutLeftMs(app) > 0L) tooMany(app) else "Wrong passphrase or key." } else "Wrong passphrase or key.")
            val idx = loadIndex(app, k)
            if (idx == null) { VaultCrypto.wipe(k); return Res(false, lastError.ifEmpty { "The vault index cannot be read." }) }
            synchronized(this) { dek = k; index = idx; header = h; dirty = false }
            folderOk = true; leftAt = System.currentTimeMillis()
            if (count) clearFails(app)
            return Res(true)
        } catch (e: Exception) {
            if (e.message == "NEWER") lastError = "This vault was made by a newer Thazir. Update the app first."
            else { folderOk = false; lastError = "Cannot open the vault folder. Pick the same folder again." }
            return Res(false, lastError)
        }
    }

    private fun loadIndex(c: Context, k: ByteArray): JSONObject? {
        val tree = treeUri(c) ?: return null
        val root = rootDoc(tree)
        for (name in arrayOf(IDX, IDX_BAK)) {
            try {
                val d = child(c, tree, root, name) ?: continue
                val o = JSONObject(VaultCrypto.decStr(k, String(readAll(c, d.uri), Charsets.UTF_8)) ?: continue)
                if (o.optInt("v", 1) > INDEX_V) { lastError = "This vault was made by a newer Thazir. Update the app first."; return null }
                return migrate(o)
            } catch (e: Exception) { /* try the backup */ }
        }
        return null
    }

    /** Schema upgrades go here (index v1 is current). Unknown fields stay because the JSON is kept whole. */
    private fun migrate(o: JSONObject): JSONObject {
        if (!o.has("items")) o.put("items", JSONArray())
        if (!o.has("folders")) o.put("folders", JSONArray())
        o.put("v", INDEX_V)
        return o
    }

    /** Wipe the key from memory. Saves unsaved changes first (blocking only when something is unsaved). */
    fun lock(c: Context?) {
        if (dirty && c != null && dek != null) { try { flush(c) } catch (e: Exception) { } }
        pending?.cancel(false)
        synchronized(this) { VaultCrypto.wipe(dek); dek = null; index = null }
        VaultPlay.revoke() // V8
        if (c != null) VaultFiles.wipeTemp(c, true) // V7: plain temp copies handed to other apps go when the vault locks
    }

    // ---------- per-install quick unlock + screen privacy (V3) ----------

    /**
     * Quick unlock: the DEK is wrapped with the Android Keystore through the existing [Vault] helper and kept in prefs "mgb_vault" (key "qk").
     * It dies with the app on uninstall, so after a reinstall the passphrase or recovery key is needed once. The caller must ask the user
     * to authenticate (Vault.unlock) before using [quickKey]; only offer it when AppLock.available() is true.
     */
    fun quickOn(c: Context): Boolean = prefs(c).getString("qk", null) != null

    fun quickEnable(c: Context): Boolean {
        val k = dek ?: return false
        return try {
            prefs(c).edit().putString("qk", Vault.encrypt(Base64.encodeToString(k, Base64.NO_WRAP))).apply()
            true
        } catch (e: Exception) { false }
    }

    fun quickDisable(c: Context) { prefs(c).edit().remove("qk").apply() }

    /** The wrapped DEK, or null. Caller wipes the array. */
    fun quickKey(c: Context): ByteArray? = try {
        prefs(c).getString("qk", null)?.let { Vault.decrypt(it) }?.let { Base64.decode(it, Base64.NO_WRAP) }
    } catch (e: Exception) { null }

    /** Blank recents preview + no screenshots while the vault screen is open (default ON). */
    fun hideRecents(c: Context): Boolean = prefs(c).getBoolean("hide_recents", true)
    fun setHideRecents(c: Context, on: Boolean) { prefs(c).edit().putBoolean("hide_recents", on).apply() }

    /** V4: true when everything changed so far is on disk (set only after flush wrote and read back the index). */
    fun isSaved(): Boolean = !dirty

    /** V5: "Send history to vault" (default off). While the vault is open, browser history moves into it when the app is left. */
    fun histAuto(c: Context): Boolean = prefs(c).getBoolean("hist_auto", false)
    fun setHistAuto(c: Context, on: Boolean) { prefs(c).edit().putBoolean("hist_auto", on).apply() }

    // ---------- auto-lock ----------

    fun autoLockMs(c: Context): Long = prefs(c).getLong("autolock_ms", 120_000L)
    fun setAutoLockMs(c: Context, ms: Long) { prefs(c).edit().putLong("autolock_ms", ms).apply() }

    /** Call from MainActivity.onStop (wired in V3). */
    fun onAppStop(c: Context) {
        VaultFiles.wipeTemp(c) // V6: decrypted saved pages never stay in cache
        if (dek == null) return
        VaultItems.sweepHistory(c) // V5: no-op unless "Send history to vault" is on; runs on its own thread
        leftAt = System.currentTimeMillis()
        if (autoLockMs(c) <= 0L) lock(c)
    }

    /** Call from MainActivity.onStart (wired in V3). Locks when the app was away longer than the delay. */
    fun onAppStart(c: Context) {
        VaultFiles.wipeTemp(c) // V6
        if (dek == null) return
        if (System.currentTimeMillis() - leftAt > autoLockMs(c)) lock(c)
    }

    // ---------- index (items + folders) ----------

    /** Read-only view: do not change the returned objects, use [updateItem]. Newest first is the caller's job. */
    @Synchronized fun items(type: String? = null): List<JSONObject> {
        val a = index?.optJSONArray("items") ?: return emptyList()
        val out = ArrayList<JSONObject>(a.length())
        for (i in 0 until a.length()) {
            val o = a.optJSONObject(i) ?: continue
            if (type == null || o.optString("type") == type) out.add(o)
        }
        return out
    }

    @Synchronized fun item(id: String): JSONObject? = items().firstOrNull { it.optString("id") == id }

    @Synchronized fun count(type: String): Int = items(type).size

    @Synchronized fun bytes(type: String): Long = items(type).sumOf { it.optLong("size", 0L) }

    /** Adds the item (id and time are filled in when missing), saves soon. Returns the id, or "" when locked. */
    fun addItem(c: Context, o: JSONObject): String {
        val id: String
        synchronized(this) {
            val a = index?.optJSONArray("items") ?: return ""
            if (o.optString("id").isEmpty()) o.put("id", VaultCrypto.newId())
            if (!o.has("time")) o.put("time", System.currentTimeMillis())
            id = o.getString("id")
            a.put(o)
        }
        saveSoon(c)
        return id
    }

    fun updateItem(c: Context, o: JSONObject): Boolean {
        var found = false
        synchronized(this) {
            val a = index?.optJSONArray("items") ?: return false
            for (i in 0 until a.length()) if (a.optJSONObject(i)?.optString("id") == o.optString("id")) { a.put(i, o); found = true; break }
        }
        if (found) saveSoon(c)
        return found
    }

    /** Removes the item and, by default, its blob. */
    fun removeItem(c: Context, id: String, withBlob: Boolean = true): Boolean {
        var gone: JSONObject? = null
        synchronized(this) {
            val a = index?.optJSONArray("items") ?: return false
            for (i in 0 until a.length()) if (a.optJSONObject(i)?.optString("id") == id) { gone = a.optJSONObject(i); a.remove(i); break }
        }
        val g = gone ?: return false
        saveSoon(c)
        if (withBlob && g.optString("blob").isNotEmpty()) deleteBlob(c, g.getString("blob"))
        return true
    }

    @Synchronized fun folders(): List<String> {
        val a = index?.optJSONArray("folders") ?: return emptyList()
        return (0 until a.length()).map { a.optString(it) }
    }

    fun addFolder(c: Context, name: String): Boolean {
        val n = name.trim()
        if (n.isEmpty()) return false
        synchronized(this) {
            val a = index?.optJSONArray("folders") ?: return false
            if (folders().contains(n)) return true
            a.put(n)
        }
        saveSoon(c)
        return true
    }

    /** V16: names of bookmark folders the user hid inside the vault (list only; the data stays sealed). */
    @Synchronized fun hiddenFolders(): Set<String> {
        val a = index?.optJSONArray("hfolders") ?: return emptySet()
        return (0 until a.length()).map { a.optString(it) }.toSet()
    }

    fun setFolderHidden(c: Context, name: String, on: Boolean): Boolean {
        val n = name.trim()
        if (n.isEmpty()) return false
        synchronized(this) {
            val idx = index ?: return false
            val old = idx.optJSONArray("hfolders")
            val keep = ArrayList<String>()
            if (old != null) for (i in 0 until old.length()) { val s = old.optString(i); if (s != n) keep.add(s) }
            if (on) keep.add(n)
            val a = org.json.JSONArray()
            for (s in keep) a.put(s)
            idx.put("hfolders", a)
        }
        saveSoon(c)
        return true
    }

    // ---------- saving the index ----------

    private fun saveSoon(c: Context) {
        gen++
        dirty = true
        val app = c.applicationContext
        pending?.cancel(false)
        pending = io.schedule(Runnable { try { flush(app) } catch (e: Exception) { } }, 600, TimeUnit.MILLISECONDS)
    }

    /** BLOCKING. Writes index.tmp, reads it back, then swaps: index.enc -> index.bak, index.tmp -> index.enc. Old data stays on any failure. */
    fun flush(c: Context) {
        synchronized(ioLock) {
            val k = dek ?: return
            val g = gen
            val text = synchronized(this) { index?.toString() } ?: return
            if (!dirty) return
            val app = c.applicationContext
            try {
                val tree = treeUri(app) ?: return
                val root = rootDoc(tree)
                val cr = app.contentResolver
                val enc = VaultCrypto.encStr(k, text)
                val tmp = child(app, tree, root, IDX_TMP)?.uri ?: makeFile(app, root, IDX_TMP)
                writeAll(app, tmp, enc.toByteArray(Charsets.UTF_8))
                if (VaultCrypto.decStr(k, String(readAll(app, tmp), Charsets.UTF_8)) != text) throw IOException("verify failed")
                child(app, tree, root, IDX_BAK)?.let { del(app, it.uri) }
                val cur = child(app, tree, root, IDX)
                if (cur != null) DocumentsContract.renameDocument(cr, cur.uri, IDX_BAK)
                val nu = try { DocumentsContract.renameDocument(cr, tmp, IDX) } catch (e: Exception) { null }
                if (nu == null) {
                    // roll back: the previous index becomes current again
                    child(app, tree, root, IDX_BAK)?.let { DocumentsContract.renameDocument(cr, it.uri, IDX) }
                    throw IOException("swap failed")
                }
                if (gen == g) dirty = false else Unit
            } catch (e: Exception) {
                lastError = "The vault could not save its list. Nothing was lost, it will retry."
                io.schedule(Runnable { try { flush(app) } catch (x: Exception) { } }, 8, TimeUnit.SECONDS)
            }
        }
    }

    // ---------- blobs ----------

    /** BLOCKING. New encrypted blob "<id>.bin". Write plain bytes to the stream, close() finishes it. Delete it with [deleteBlob] if the import fails. */
    fun openBlobOut(c: Context, id: String): VaultCrypto.BlobWriter {
        val app = c.applicationContext
        val k = key()
        val file = makeFile(app, dataDir(app), "$id.bin")
        val raw: OutputStream = app.contentResolver.openOutputStream(file, "w") ?: throw IOException("cannot write blob")
        return VaultCrypto.BlobWriter(raw, k)
    }

    /** BLOCKING. Random-access reader (seekable). Close it when done. Null when the blob is missing. */
    fun openBlobIn(c: Context, id: String): VaultCrypto.BlobReader? {
        val app = c.applicationContext
        val k = key()
        val tree = treeUri(app) ?: return null
        val d = child(app, tree, dataDir(app), "$id.bin") ?: return null
        return VaultCrypto.BlobReader(SafSource(app, d.uri), k)
    }

    fun blobExists(c: Context, id: String): Boolean {
        val app = c.applicationContext
        val tree = treeUri(app) ?: return false
        return try { child(app, tree, dataDir(app), "$id.bin") != null } catch (e: Exception) { false }
    }

    fun deleteBlob(c: Context, id: String) {
        val app = c.applicationContext
        try {
            val tree = treeUri(app) ?: return
            child(app, tree, dataDir(app), "$id.bin")?.let { del(app, it.uri) }
        } catch (e: Exception) { }
    }

    // ---------- V9: wipe ----------

    /** V9: forget every bookmark folder name in the index (used when the bookmarks category is deleted). */
    fun clearFolders(c: Context) {
        synchronized(this) { val a = index?.optJSONArray("folders") ?: return; while (a.length() > 0) a.remove(0) }
        saveSoon(c)
    }

    private fun kids(c: Context, tree: Uri, parent: Uri): List<Uri> {
        val out = ArrayList<Uri>()
        val q = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getDocumentId(parent))
        c.contentResolver.query(q, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID), null, null, null)?.use { while (it.moveToNext()) out.add(DocumentsContract.buildDocumentUriUsingTree(tree, it.getString(0))) }
        return out
    }

    /** Best effort overwrite with zeros before delete (not every provider allows "rw"; the vault key wrap is destroyed with vault.hdr anyway). */
    private fun zero(c: Context, uri: Uri) {
        try {
            val len = c.contentResolver.query(uri, arrayOf(DocumentsContract.Document.COLUMN_SIZE), null, null, null)?.use { if (it.moveToFirst()) it.getLong(0) else 0L } ?: 0L
            if (len <= 0L || len > 64L * 1048576L) return
            c.contentResolver.openOutputStream(uri, "rw")?.use { o -> val z = ByteArray(8192); var left = len; while (left > 0) { val n = minOf(left, z.size.toLong()).toInt(); o.write(z, 0, n); left -= n }; o.flush() }
        } catch (e: Exception) { }
    }

    /**
     * V9 BLOCKING. Destroys the vault in the bound folder: d/ blobs, index files, then vault.hdr LAST (zero-filled first), then forgets the folder.
     * Without vault.hdr no key can be derived, so any blob a provider failed to delete is unreadable. Returns null when vault.hdr is gone, else a user text.
     */
    fun wipeAll(c: Context): String? {
        val app = c.applicationContext
        val tree = treeUri(app) ?: return "No vault folder is set."
        pending?.cancel(false); dirty = false; gen++
        lock(app)
        try {
            val root = rootDoc(tree)
            child(app, tree, root, DATA)?.let { d -> for (k in kids(app, tree, d.uri)) del(app, k); del(app, d.uri) }
            for (n in listOf(IDX, IDX_BAK, IDX_TMP, HDR_BAK)) child(app, tree, root, n)?.let { zero(app, it.uri); del(app, it.uri) }
            child(app, tree, root, HDR)?.let { zero(app, it.uri); del(app, it.uri) }
            if (child(app, tree, root, HDR) != null) return "vault.hdr could not be removed. Delete the vault folder in your file manager."
        } catch (e: Exception) { return "The vault could not be removed completely. Delete the vault folder in your file manager." }
        unbind(app)
        return null
    }

    /** Positional reads over a SAF file descriptor (needs a file-backed provider; internal/SD storage folders are). */
    private class SafSource(c: Context, uri: Uri) : RandomSource {
        private val pfd = c.contentResolver.openFileDescriptor(uri, "r") ?: throw IOException("cannot open blob")
        private val fin = FileInputStream(pfd.fileDescriptor)
        private val ch = fin.channel
        override fun size(): Long = ch.size()
        override fun readAt(pos: Long, dst: ByteArray, off: Int, len: Int): Int = ch.read(ByteBuffer.wrap(dst, off, len), pos)
        override fun close() {
            try { fin.close() } catch (e: Exception) { }
            try { pfd.close() } catch (e: Exception) { }
        }
    }

    /** Header copy for V11 (change passphrase / recovery key writes the new header with [writeHeader]). */
    @Synchronized fun currentHeader(): VaultCrypto.Header? = header

    /** BLOCKING. Replace vault.hdr (write tmp name first is not possible in SAF, so the old text is kept and restored on failure). */
    fun writeHeader(c: Context, h: VaultCrypto.Header): Boolean {
        val app = c.applicationContext
        synchronized(ioLock) {
            val tree = treeUri(app) ?: return false
            val d = child(app, tree, rootDoc(tree), HDR) ?: return false
            val old = try { readAll(app, d.uri) } catch (e: Exception) { return false }
            // V11: keep the previous good header as vault.hdr.bak until the new one is verified (readHeader falls back to it)
            val oldOk = try { VaultCrypto.Header.parse(String(old, Charsets.UTF_8)).wrapPass.isNotEmpty() } catch (e: Exception) { false }
            if (oldOk) {
                try {
                    val root = rootDoc(tree)
                    writeAll(app, child(app, tree, root, HDR_BAK)?.uri ?: makeFile(app, root, HDR_BAK), old)
                } catch (e: Exception) { return false }
            }
            return try {
                writeAll(app, d.uri, h.toJson().toString().toByteArray(Charsets.UTF_8))
                if (VaultCrypto.Header.parse(String(readAll(app, d.uri), Charsets.UTF_8)).wrapPass.isEmpty()) throw IOException("verify")
                synchronized(this) { header = h }
                child(app, tree, rootDoc(tree), HDR_BAK)?.let { del(app, it.uri) }
                true
            } catch (e: Exception) {
                var back = false
                try { writeAll(app, d.uri, old); back = true } catch (x: Exception) { }
                if (back) child(app, tree, rootDoc(tree), HDR_BAK)?.let { del(app, it.uri) }
                false
            }
        }
    }

    // ---------- V11: failed-attempt wait, key changes, index helpers ----------

    private const val FREE_TRIES = 5
    private const val BASE_WAIT = 30_000L
    private const val MAX_WAIT = 900_000L

    /** Wrong passphrase / recovery tries in a row (reset by a correct one). */
    fun failCount(c: Context): Int = prefs(c).getInt("fails", 0)

    /**
     * Milliseconds the user still has to wait (0 = may try). Stored in prefs so it survives a restart. Inside one boot it uses the monotonic clock
     * (changing the phone clock does not help); after a reboot it falls back to the wall clock, capped at the wait that was set.
     */
    fun lockoutLeftMs(c: Context): Long {
        val p = prefs(c)
        val wait = p.getLong("lock_wait", 0L)
        if (wait <= 0L) return 0L
        val el = android.os.SystemClock.elapsedRealtime()
        val start = p.getLong("lock_start_el", -1L)
        val left = if (start in 0L..el) wait - (el - start) else minOf(wait, p.getLong("lock_until_wall", 0L) - System.currentTimeMillis())
        return maxOf(0L, left)
    }

    fun waitText(ms: Long): String { val s = (ms + 999L) / 1000L; return if (s >= 60L) ((s + 59L) / 60L).toString() + " min" else "$s s" }

    private fun tooMany(c: Context): String = "Too many wrong tries. Wait " + waitText(lockoutLeftMs(c)) + " and try again."

    private fun noteFail(c: Context) {
        val p = prefs(c)
        val n = p.getInt("fails", 0) + 1
        val e = p.edit().putInt("fails", n)
        if (n >= FREE_TRIES) {
            val wait = minOf(MAX_WAIT, BASE_WAIT shl minOf(n - FREE_TRIES, 5))
            e.putLong("lock_wait", wait).putLong("lock_start_el", android.os.SystemClock.elapsedRealtime()).putLong("lock_until_wall", System.currentTimeMillis() + wait)
        }
        e.apply()
    }

    private fun clearFails(c: Context) { prefs(c).edit().remove("fails").remove("lock_wait").remove("lock_start_el").remove("lock_until_wall").apply() }

    /** BLOCKING. Checks the passphrase of the OPEN vault (for sensitive actions). Counts wrong tries like unlock does. Wipes [pass]. */
    fun confirmPass(c: Context, pass: CharArray): Res {
        val app = c.applicationContext
        try {
            if (lockoutLeftMs(app) > 0L) return Res(false, tooMany(app))
            val h = synchronized(this) { header } ?: return Res(false, "The vault is locked.")
            val k = VaultCrypto.openPass(h, pass)
            if (k == null) { noteFail(app); return Res(false, if (lockoutLeftMs(app) > 0L) tooMany(app) else "Wrong passphrase.") }
            val same = synchronized(this) { dek }?.let { java.security.MessageDigest.isEqual(it, k) } ?: false
            VaultCrypto.wipe(k)
            if (!same) return Res(false, "The vault is locked.")
            clearFails(app)
            return Res(true)
        } catch (e: Exception) {
            return Res(false, "The passphrase could not be checked.")
        } finally { VaultCrypto.wipe(pass) }
    }

    /** True when the header read back from the folder opens with [open] and gives exactly [k]. */
    private fun readBackOk(app: Context, k: ByteArray, open: (VaultCrypto.Header) -> ByteArray?): Boolean = try {
        val k2 = open(readHeader(app))
        val same = k2 != null && java.security.MessageDigest.isEqual(k2, k)
        if (k2 != null) VaultCrypto.wipe(k2)
        same
    } catch (e: Exception) { false }

    /**
     * V11 BLOCKING. New passphrase for the open vault: only the passphrase wrap in vault.hdr is replaced (DEK, index and blobs untouched).
     * The old header is kept as vault.hdr.bak while writing, the new one is read back and opened with the new passphrase, and on any doubt the
     * old header is put back, so the old passphrase keeps working. Wipes both arrays.
     */
    fun changePass(c: Context, oldPass: CharArray, newPass: CharArray): Res {
        val app = c.applicationContext
        try {
            val chk = confirmPass(app, oldPass)
            if (!chk.ok) return chk
            val k = synchronized(this) { dek?.copyOf() } ?: return Res(false, "The vault is locked.")
            try {
                val h = synchronized(this) { header } ?: return Res(false, "The vault is locked.")
                val pw = newPass.copyOf()
                val nh = try { VaultCrypto.rewrapPass(h, k, pw) } finally { VaultCrypto.wipe(pw) }
                if (!writeHeader(app, nh)) return Res(false, "The key file could not be saved. Your old passphrase still works.")
                if (!readBackOk(app, k) { x -> VaultCrypto.openPass(x, newPass.copyOf()) }) {
                    writeHeader(app, h)
                    return Res(false, "The new passphrase could not be verified, so the old one was kept.")
                }
                return Res(true)
            } finally { VaultCrypto.wipe(k) }
        } catch (e: Exception) {
            return Res(false, "The passphrase could not be changed. The old one still works.")
        } finally { VaultCrypto.wipe(newPass) }
    }

    /** V11 BLOCKING. Replaces the recovery wrap and returns the new key text (show once). The old key stops working. Wipes [pass]. */
    fun regenRecovery(c: Context, pass: CharArray): Res {
        val app = c.applicationContext
        try {
            val chk = confirmPass(app, pass)
            if (!chk.ok) return chk
            val k = synchronized(this) { dek?.copyOf() } ?: return Res(false, "The vault is locked.")
            try {
                val h = synchronized(this) { header } ?: return Res(false, "The vault is locked.")
                val r = VaultCrypto.newRecovery(h, k)
                val nh = r.first; val key = r.second
                if (!writeHeader(app, nh)) return Res(false, "The key file could not be saved. Your old recovery key still works.")
                if (!readBackOk(app, k) { x -> VaultCrypto.openRecovery(x, key) }) {
                    writeHeader(app, h)
                    return Res(false, "The new recovery key could not be verified, so the old one was kept.")
                }
                return Res(true, "", key)
            } finally { VaultCrypto.wipe(k) }
        } catch (e: Exception) {
            return Res(false, "The recovery key could not be changed. The old one still works.")
        }
    }

    /** Approximate size of the list (index) in characters. */
    @Synchronized fun indexBytes(): Int = index?.toString()?.length ?: 0

    /** V11: removes history entries older than [cutoff] (ms). Returns how many went. */
    fun trimHistory(c: Context, cutoff: Long): Int {
        var n = 0
        synchronized(this) {
            val a = index?.optJSONArray("items") ?: return 0
            var i = a.length() - 1
            while (i >= 0) {
                val o = a.optJSONObject(i)
                if (o != null && o.optString("type") == "hist" && o.optLong("time") < cutoff) { a.remove(i); n++ }
                i--
            }
        }
        if (n > 0) saveSoon(c)
        return n
    }
}
