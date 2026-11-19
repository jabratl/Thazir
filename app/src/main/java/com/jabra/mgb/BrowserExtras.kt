package com.jabra.mgb

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * S14: things a normal browser needs besides pages.
 *  - file chooser: MainActivity owns the ActivityResult launchers (init in onCreate); tabs and extension
 *    windows (ExtScreen is a Dialog and cannot get activity results) both call chooseFile()
 *  - web permissions (camera, microphone, location): ask the user first (Allow / Block), then the Android permission.
 *    A permission that is not declared in the manifest is refused with a toast (see HANDOFF: extra manifest)
 *  - desktop user agent
 * Extension pages (appassets.androidplatform.net) never get camera / microphone / location.
 */
object BrowserExtras {
    private var act: ComponentActivity? = null
    private var fileLauncher: ActivityResultLauncher<Intent>? = null
    private var permLauncher: ActivityResultLauncher<Array<String>>? = null
    private var pendingFile: ValueCallback<Array<Uri>>? = null
    private var pendingPerm: ((Map<String, Boolean>) -> Unit)? = null
    private var dUa: String? = null
    private var exportLauncher: ActivityResultLauncher<String>? = null // S18
    private var importLauncher: ActivityResultLauncher<Array<String>>? = null // S18
    private var pendingImport: ((Int) -> Unit)? = null // S18
    private var backupOut: ActivityResultLauncher<String>? = null // S29
    private var backupIn: ActivityResultLauncher<Array<String>>? = null // S29
    private var backupText: (() -> String)? = null // S29
    private var backupDone: ((String?) -> Unit)? = null // S29
    private var csvOut: ActivityResultLauncher<String>? = null // S40
    private var csvText: String? = null // S40
    private var treeLauncher: ActivityResultLauncher<Uri?>? = null // S49
    private var treeDone: (() -> Unit)? = null // S49
    private var vaultTreeLauncher: ActivityResultLauncher<Uri?>? = null // V2
    private var vaultTreeDone: ((Uri) -> Unit)? = null // V2
    private var extLauncher: ActivityResultLauncher<Array<String>>? = null // S1 (multi-extension)
    private var extDone: ((Uri) -> Unit)? = null // S1
    private var imgLauncher: ActivityResultLauncher<Array<String>>? = null // A7 (wallpaper)
    private var imgDone: ((Uri) -> Unit)? = null // A7

    /** Remembered Allow / Block answers for this run: "origin|kinds" -> answer. */
    private val decided = HashMap<String, Boolean>()
    /** 0.0.478: same for Incognito tabs, kept apart so a normal tab never reuses an Incognito answer; wiped with the Incognito data (TabManager.purgePrivate). */
    private val decidedPriv = HashMap<String, Boolean>()
    fun clearPrivDecisions() { decidedPriv.clear() }

    /** Viewport script used by desktop mode (runs when a page has finished loading). */
    const val DESKTOP_JS = "(function(){var m=document.querySelector('meta[name=viewport]');" +
        "if(!m){m=document.createElement('meta');m.name='viewport';(document.head||document.documentElement).appendChild(m);}" +
        "m.setAttribute('content','width=1024');})();"

    /** Call in Activity.onCreate (launchers must be registered before the Activity is STARTED). */
    fun init(a: ComponentActivity) {
        pendingFile?.onReceiveValue(null)
        pendingFile = null
        pendingPerm = null
        act = a
        fileLauncher = a.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
            val cb = pendingFile
            pendingFile = null
            cb?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(r.resultCode, r.data))
        }
        permLauncher = a.registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { m ->
            val f = pendingPerm
            pendingPerm = null
            f?.invoke(m)
        }
        // S18: bookmark export / import (system file picker). Registered after the two above (their keys stay the same).
        exportLauncher = a.registerForActivityResult(ActivityResultContracts.CreateDocument("text/html")) { uri ->
            if (uri != null) writeBookmarks(a, uri)
        }
        importLauncher = a.registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            val cb = pendingImport
            pendingImport = null
            if (uri != null) readBookmarks(a, uri, cb)
        }
        // S29: data backup (JSON). Registered last so the keys of the launchers above stay the same.
        backupOut = a.registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
            val supplier = backupText
            backupText = null
            if (uri != null && supplier != null) {
                val text = try { supplier() } catch (e: Exception) { null }
                Thread {
                    val ok = text != null && try {
                        a.contentResolver.openOutputStream(uri, "wt")?.use { it.write(text.toByteArray(Charsets.UTF_8)) } != null
                    } catch (e: Exception) { false }
                    a.runOnUiThread { Util.toast(a, if (ok) "Backup saved" else "Backup failed") }
                }.start()
            }
        }
        backupIn = a.registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            val cb = backupDone
            backupDone = null
            if (uri == null) return@registerForActivityResult
            Thread {
                val text = try {
                    val bytes = a.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    if (bytes != null && bytes.size <= 20 * 1024 * 1024) String(bytes, Charsets.UTF_8) else null
                } catch (e: Exception) { null }
                a.runOnUiThread { cb?.invoke(text) }
            }.start()
        }
        // S40: CSV file (password export). Registered after the launchers above.
        csvOut = a.registerForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
            val text = csvText
            csvText = null
            if (uri != null && text != null) {
                Thread {
                    val ok = try {
                        a.contentResolver.openOutputStream(uri, "wt")?.use { it.write(text.toByteArray(Charsets.UTF_8)) } != null
                    } catch (e: Exception) { false }
                    a.runOnUiThread { Util.toast(a, if (ok) "File saved" else "Could not save the file") }
                }.start()
            }
        }
        // S49: download folder picker. Registered last so the keys of the launchers above stay the same.
        treeLauncher = a.registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            val cb = treeDone
            treeDone = null
            if (uri != null) {
                DownloadFolder.onPicked(a, uri)
                cb?.invoke()
            }
        }
        // S1: extension file picker (.zip / .crx). Registered last so the keys of the launchers above stay the same.
        extLauncher = a.registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            val cb = extDone
            extDone = null
            if (uri != null) cb?.invoke(uri)
        }
        // A7: wallpaper image picker. Registered last so the keys of the launchers above stay the same.
        imgLauncher = a.registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            val cb = imgDone
            imgDone = null
            if (uri != null) cb?.invoke(uri)
        }
        // V2: vault folder picker. Registered last so the keys of the launchers above stay the same. Does NOT touch DownloadFolder.
        vaultTreeLauncher = a.registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            val cb = vaultTreeDone
            vaultTreeDone = null
            if (uri != null) cb?.invoke(uri)
        }
        slots[a] = Slot(a, fileLauncher, permLauncher, exportLauncher, importLauncher, backupOut, backupIn, csvOut, treeLauncher, vaultTreeLauncher, extLauncher, imgLauncher) // 0.0.248 (A7b)
    }

    /** 0.0.248 (A7b): launchers of one window. Callbacks only use shared state (pendingFile ...), so a window swap = swap these fields. */
    private class Slot(
        val act: ComponentActivity,
        val file: ActivityResultLauncher<Intent>?, val perm: ActivityResultLauncher<Array<String>>?,
        val export: ActivityResultLauncher<String>?, val imp: ActivityResultLauncher<Array<String>>?,
        val backupOut: ActivityResultLauncher<String>?, val backupIn: ActivityResultLauncher<Array<String>>?,
        val csvOut: ActivityResultLauncher<String>?, val tree: ActivityResultLauncher<Uri?>?,
        val vaultTree: ActivityResultLauncher<Uri?>?, val ext: ActivityResultLauncher<Array<String>>?,
        val img: ActivityResultLauncher<Array<String>>?
    )
    private val slots = HashMap<ComponentActivity, Slot>()

    /** The window in front (MainActivity.onResume with a site window around) takes over file chooser, permission asks and dialogs. */
    fun focus(a: ComponentActivity) {
        val s = slots[a] ?: return
        act = s.act; fileLauncher = s.file; permLauncher = s.perm; exportLauncher = s.export; importLauncher = s.imp
        backupOut = s.backupOut; backupIn = s.backupIn; csvOut = s.csvOut; treeLauncher = s.tree
        vaultTreeLauncher = s.vaultTree; extLauncher = s.ext; imgLauncher = s.img
    }

    /** MainActivity.onDestroy: forget this window (no leak); another living window takes over. */
    fun release(a: ComponentActivity) {
        slots.remove(a)
        if (act === a) slots.keys.firstOrNull()?.let { focus(it) }
    }

    /** V2: let the user pick the vault folder (system folder picker). The result goes to [done] only, no download setting changes. */
    fun pickVaultFolder(done: (Uri) -> Unit) {
        val l = vaultTreeLauncher
        val a = act
        if (l == null || a == null) return
        vaultTreeDone = done
        try { l.launch(null) } catch (e: Exception) { vaultTreeDone = null; Util.toast(a, "No folder picker found") }
    }

    /** A7: let the user pick an image (home wallpaper). */
    fun pickImage(done: (Uri) -> Unit) {
        val l = imgLauncher
        val a = act
        if (l == null || a == null) return
        imgDone = done
        try { l.launch(arrayOf("image/*")) } catch (e: Exception) { imgDone = null; Util.toast(a, "No file picker found") }
    }

    /** S1: let the user pick an extension file (.zip / .crx). The .crx mime type is unreliable, so any file is allowed. */
    fun pickExtension(done: (Uri) -> Unit) {
        val l = extLauncher
        val a = act
        if (l == null || a == null) return
        extDone = done
        try {
            l.launch(arrayOf("application/zip", "application/x-chrome-extension", "application/octet-stream", "*/*"))
        } catch (e: Exception) { extDone = null; Util.toast(a, "No file picker found") }
    }

    /** S49: let the user pick the download folder (system folder picker). */
    fun pickFolder(done: () -> Unit) {
        val l = treeLauncher
        val a = act
        if (l == null || a == null) return
        treeDone = done
        try { l.launch(null) } catch (e: Exception) { treeDone = null; Util.toast(a, "No folder picker found") }
    }

    /** S40: save text as a .csv file chosen by the user. */
    fun saveCsv(name: String, text: String) {
        val l = csvOut
        val a = act
        if (l == null || a == null) return
        csvText = text
        try { l.launch(name) } catch (e: Exception) { csvText = null; Util.toast(a, "No file picker found") }
    }

    // ---------- S29: data backup ----------

    fun saveBackup(name: String, text: () -> String) {
        val l = backupOut
        val a = act
        if (l == null || a == null) return
        backupText = text
        try { l.launch(name) } catch (e: Exception) { backupText = null; Util.toast(a, "No file picker found") }
    }

    fun loadBackup(cb: (String?) -> Unit) {
        val l = backupIn
        val a = act
        if (l == null || a == null) return
        backupDone = cb
        try { l.launch(arrayOf("*/*")) } catch (e: Exception) { backupDone = null; Util.toast(a, "No file picker found") }
    }

    // ---------- S18: bookmarks import / export ----------

    fun exportBookmarks() {
        val l = exportLauncher
        val a = act
        if (l == null || a == null) return
        try { l.launch("mgb-bookmarks.html") } catch (e: Exception) { Util.toast(a, "No file picker found") }
    }

    /** onDone(added) runs on the main thread after the file was read. */
    fun importBookmarks(onDone: ((Int) -> Unit)?) {
        val l = importLauncher
        val a = act
        if (l == null || a == null) return
        pendingImport = onDone
        try { l.launch(arrayOf("*/*")) } catch (e: Exception) { pendingImport = null; Util.toast(a, "No file picker found") }
    }

    private fun writeBookmarks(a: ComponentActivity, uri: Uri) {
        val text = Bookmarks.exportHtml()
        val n = Bookmarks.bookmarks().size
        Thread {
            val ok = try {
                a.contentResolver.openOutputStream(uri, "wt")?.use { it.write(text.toByteArray(Charsets.UTF_8)) } != null
            } catch (e: Exception) { false }
            a.runOnUiThread { Util.toast(a, if (ok) "Exported $n bookmarks" else "Export failed") }
        }.start()
    }

    private fun readBookmarks(a: ComponentActivity, uri: Uri, cb: ((Int) -> Unit)?) {
        Thread {
            var added = -1
            try {
                val bytes = a.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                if (bytes != null && bytes.size <= 5 * 1024 * 1024) added = Bookmarks.importText(String(bytes, Charsets.UTF_8))
            } catch (e: Exception) { added = -1 }
            a.runOnUiThread {
                Util.toast(a, when {
                    added < 0 -> "Could not read this file"
                    added == 0 -> "No new bookmarks found in this file"
                    else -> "Imported $added bookmarks"
                })
                if (added >= 0) cb?.invoke(added)
            }
        }.start()
    }

    // ---------- file chooser ----------

    /** WebChromeClient.onShowFileChooser for tabs and ExtScreen windows. Returns true = we answer the callback. */
    fun chooseFile(cb: ValueCallback<Array<Uri>>?, p: WebChromeClient.FileChooserParams?): Boolean {
        if (cb == null) return false
        val l = fileLauncher
        if (p == null || l == null) {
            cb.onReceiveValue(null)
            return true
        }
        pendingFile?.onReceiveValue(null) // a second chooser replaces the first
        pendingFile = cb
        try {
            l.launch(p.createIntent())
        } catch (e: Exception) {
            pendingFile = null
            cb.onReceiveValue(null)
            act?.let { Util.toast(it, "No file picker found") }
        }
        return true
    }

    // ---------- web permissions ----------

    fun webPermission(req: PermissionRequest, priv: Boolean = false) {
        val a = act
        val origin = req.origin.toString()
        if (a == null || origin.startsWith(ContentInjector.EXT_ORIGIN)) { req.deny(); return }
        val needed = ArrayList<String>()
        val names = ArrayList<String>()
        val web = ArrayList<String>()
        for (r in req.resources) {
            when (r) {
                PermissionRequest.RESOURCE_VIDEO_CAPTURE -> { needed.add(Manifest.permission.CAMERA); names.add("camera"); web.add(r) }
                PermissionRequest.RESOURCE_AUDIO_CAPTURE -> { needed.add(Manifest.permission.RECORD_AUDIO); names.add("microphone"); web.add(r) }
            }
        }
        val drm = MediaPrefs.drm(a) && req.resources.contains(PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID) // 0.0.296: Widevine, no prompt (it is not camera / mic); the downloader skips DRM items by itself
        if (web.isEmpty()) { if (drm) req.grant(arrayOf(PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID)) else req.deny(); return }
        val what = names.joinToString(" and ")
        ask(a, origin, what, priv) { ok ->
            if (!ok) req.deny()
            else withAndroidPermission(a, needed) { granted ->
                if (granted) req.grant((if (drm) web + PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID else web).toTypedArray()) else req.deny()
            }
        }
    }

    fun geoPermission(origin: String?, cb: GeolocationPermissions.Callback?, priv: Boolean = false) {
        if (cb == null) return
        val a = act
        if (origin == null || a == null || origin.startsWith(ContentInjector.EXT_ORIGIN)) {
            if (origin != null) cb.invoke(origin, false, false)
            return
        }
        ask(a, origin, "location", priv) { ok ->
            if (!ok) cb.invoke(origin, false, false)
            else withAndroidPermission(a, listOf(Manifest.permission.ACCESS_FINE_LOCATION)) { granted ->
                cb.invoke(origin, granted, false)
            }
        }
    }

    private fun ask(a: ComponentActivity, origin: String, what: String, priv: Boolean = false, answer: (Boolean) -> Unit) {
        val key = "$origin|$what"
        val host = Util.host(origin)
        SitePerms.getFor(a, host, what, priv)?.let { answer(it); return } // S45: remembered for this site (0.0.478: an Incognito tab also sees its own memory-only answers)
        if (SitePerms.blockedByDefault(a, what)) { answer(false); return } // 0.0.282: default = Blocked
        val dec = if (priv) decidedPriv else decided // 0.0.478
        dec[key]?.let { answer(it); return }
        if (a.isFinishing || a.isDestroyed) { answer(false); return }
        var done = false
        var remember = true
        fun finish(ok: Boolean) {
            if (done) return
            done = true
            dec[key] = ok
            if (remember) SitePerms.setFor(a, host, what, ok, priv) // S45 (0.0.478: in Incognito this stays in memory)
            answer(ok)
        }
        MaterialAlertDialogBuilder(a)
            .setTitle("Allow $host to use your $what?")
            .setMultiChoiceItems(arrayOf(if (priv) "Remember until Incognito tabs close" else "Remember for this site"), booleanArrayOf(true)) { _, _, on -> remember = on }
            .setPositiveButton("Allow") { _, _ -> finish(true) }
            .setNegativeButton("Block") { _, _ -> finish(false) }
            .setOnCancelListener { done = true; answer(false) } // dismissed: refuse this time, ask again next time
            .show()
    }

    @Suppress("DEPRECATION")
    private fun declared(a: Context): Set<String> = try {
        a.packageManager.getPackageInfo(a.packageName, PackageManager.GET_PERMISSIONS)
            .requestedPermissions?.toSet() ?: emptySet()
    } catch (e: Exception) { emptySet() }

    private fun withAndroidPermission(a: ComponentActivity, perms: List<String>, result: (Boolean) -> Unit) {
        val dec = declared(a)
        if (perms.any { it !in dec }) {
            Util.toast(a, "This build does not include that permission yet")
            result(false)
            return
        }
        val missing = perms.filter { ContextCompat.checkSelfPermission(a, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) { result(true); return }
        val l = permLauncher
        if (l == null) { result(false); return }
        pendingPerm?.invoke(emptyMap()) // an older question that never got an answer
        pendingPerm = { m -> result(missing.all { m[it] == true }) }
        try {
            l.launch(missing.toTypedArray())
        } catch (e: Exception) {
            pendingPerm = null
            result(false)
        }
    }

    // ---------- desktop mode ----------

    fun desktopUa(ctx: Context): String {
        dUa?.let { return it }
        val base = try { WebSettings.getDefaultUserAgent(ctx) }
        catch (e: Exception) { "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36" }
        val ua = base
            .replace(Regex("\\(Linux; Android[^)]*\\)"), "(X11; Linux x86_64)")
            .replace("Version/4.0 ", "")
            .replace(" Mobile", "")
        dUa = ua
        return ua
    }
}
