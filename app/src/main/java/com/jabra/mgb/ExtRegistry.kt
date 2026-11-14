package com.jabra.mgb

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.concurrent.Executors

/**
 * S0: user-installed extensions (.zip / .crx) live in filesDir/exts/<id>/ (id = 32 chars a-p, like Chrome).
 *  - Install is two-phase so S1 can show a permission dialog first: prepare() unpacks into a hidden
 *    staging folder and reads the manifest, commit() moves it to exts/<id>/, discard() deletes the staging.
 *  - The enabled flag is NOT stored inside the extension folder (the extension owns that folder). It lives in
 *    prefs mgb_exts, key "off" = set of disabled ids. New installs are enabled.
 *  - Folders whose name starts with "." are staging leftovers and are removed at init.
 *  - Nothing here runs extension code. S2+ read all()/enabled() and file().
 *  - Not done here on purpose: MV2 / nativeMessaging block is in prepareSync via ExtSafety (S8); per-extension storage wipe on remove (S3).
 */
object ExtRegistry {

    class Info(
        val id: String,
        val name: String,
        val version: String,
        val manifestVersion: Int,
        val description: String,
        val permissions: List<String>,
        val hostPermissions: List<String>,
        val contentScriptCount: Int,
        val popup: String?,
        val options: String?,
        val background: String?,
        val enabled: Boolean,
        val dir: File,
        val optionsInTab: Boolean = false, // 0.0.432 (S7): options_ui.open_in_tab
        val actionTitle: String = "" // 0.0.432 (S7): action.default_title (localized)
    )

    /** Result of prepare(): unpacked but not installed yet. existing != null means "update of this one". */
    class Staged internal constructor(
        val info: Info,
        val existing: Info?,
        internal val stageRoot: File,
        internal val src: File
    )

    private const val PREFS = "mgb_exts"
    private const val KEY_OFF = "off"
    private const val MAX_FILE = 64L * 1024 * 1024
    private val ID = Regex("^[a-p]{32}$")

    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var inited = false
    private lateinit var root: File
    private lateinit var cacheDir: File
    private lateinit var prefs: SharedPreferences
    @Volatile private var items: List<Info> = emptyList()

    @Synchronized
    fun init(ctx: Context) {
        if (inited) return
        inited = true
        val app = ctx.applicationContext
        root = File(app.filesDir, "exts")
        cacheDir = File(app.cacheDir, "ext_in")
        prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        root.mkdirs()
        root.listFiles()?.forEach { if (it.name.startsWith(".")) it.deleteRecursively() }
        cacheDir.deleteRecursively()
        scan()
    }

    // ---------- read side ----------

    fun all(): List<Info> = items
    fun enabled(): List<Info> = items.filter { it.enabled }
    fun get(id: String): Info? = items.firstOrNull { it.id == id }
    fun isValidId(id: String): Boolean = ID.matches(id)

    /** Safe file inside one extension (rel like "popup.html"). null = missing, bad path or unknown id. */
    fun file(id: String, rel: String): File? {
        val e = get(id) ?: return null
        return ExtUpdater.safeFile(e.dir, rel)
    }

    // ---------- change side ----------

    @Synchronized
    fun setEnabled(id: String, on: Boolean): Boolean {
        if (get(id) == null) return false
        val off = HashSet(prefs.getStringSet(KEY_OFF, emptySet()) ?: emptySet())
        if (on) off.remove(id) else off.add(id)
        prefs.edit().putStringSet(KEY_OFF, off).apply()
        scan()
        return true
    }

    @Synchronized
    fun remove(id: String): Boolean {
        if (!ID.matches(id)) return false
        val d = File(root, id)
        val ok = !d.exists() || d.deleteRecursively()
        val off = HashSet(prefs.getStringSet(KEY_OFF, emptySet()) ?: emptySet())
        if (off.remove(id)) prefs.edit().putStringSet(KEY_OFF, off).apply()
        scan()
        return ok
    }

    /**
     * Phase 1. uri = file picked in S1 (.zip or .crx). cb runs on the main thread.
     * Failure = Result.failure(Exception(short message)); nothing is left installed or staged.
     */
    fun prepare(ctx: Context, uri: Uri, cb: (Result<Staged>) -> Unit) {
        init(ctx)
        val app = ctx.applicationContext
        io.execute {
            val r: Result<Staged> = try {
                Result.success(prepareSync(app, uri))
            } catch (e: Throwable) {
                Result.failure(Exception(e.message ?: "install failed"))
            }
            main.post { cb(r) }
        }
    }

    /** Phase 2. Moves the staged extension into exts/<id>/ (replaces an older version, keeps its on/off flag). */
    fun commit(st: Staged, cb: (Result<Info>) -> Unit) {
        io.execute {
            val r: Result<Info> = try {
                Result.success(commitSync(st))
            } catch (e: Throwable) {
                st.stageRoot.deleteRecursively()
                Result.failure(Exception(e.message ?: "install failed"))
            }
            main.post { cb(r) }
        }
    }

    fun discard(st: Staged) {
        io.execute { st.stageRoot.deleteRecursively() }
    }

    // ---------- install internals ----------

    private fun prepareSync(app: Context, uri: Uri): Staged {
        cacheDir.mkdirs()
        val raw = File(cacheDir, "in_" + System.nanoTime() + ".bin")
        val stage = File(root, ".stage_" + System.nanoTime())
        try {
            copyIn(app, uri, raw)
            val zip = File(cacheDir, "in_" + System.nanoTime() + ".zip")
            val crxId = cutHeader(raw, zip)
            val unz = File(stage, "x")
            unz.mkdirs()
            try {
                ExtUpdater.unzip(zip, unz)
            } finally {
                zip.delete()
            }
            val base = findBase(unz) ?: throw Exception("manifest.json not found in the file")
            val man = readManifest(base)
            val ver = man.optString("manifest_version")
            if (man.optString("name").isBlank() || man.optString("version").isBlank() || ver.isBlank()) {
                throw Exception("bad manifest (name / version / manifest_version missing)")
            }
            val id = crxId ?: idFromKey(man.optString("key")) ?: idFromText("name:" + man.optString("name"))
            val info = parse(id, base, man, false)
            ExtSafety.blockReason(info)?.let { throw Exception(info.name + " was not added. " + it) } // S8: manifest v2 / nativeMessaging, staging is deleted below
            return Staged(info, get(id), stage, base)
        } catch (e: Throwable) {
            stage.deleteRecursively()
            throw e
        } finally {
            raw.delete()
        }
    }

    @Synchronized
    private fun commitSync(st: Staged): Info {
        val id = st.info.id
        if (!ID.matches(id)) throw Exception("bad extension id")
        val dest = File(root, id)
        val old = File(root, ".old_$id")
        old.deleteRecursively()
        if (dest.exists() && !dest.renameTo(old)) throw Exception("cannot replace the old version")
        var moved = st.src.renameTo(dest)
        if (!moved) {
            moved = try { st.src.copyRecursively(dest, true) } catch (e: Exception) { false }
        }
        st.stageRoot.deleteRecursively()
        if (!moved) {
            dest.deleteRecursively()
            if (old.exists()) old.renameTo(dest) // put the old version back
            scan()
            throw Exception("cannot write extension files")
        }
        old.deleteRecursively()
        scan()
        return get(id) ?: throw Exception("install check failed")
    }

    private fun copyIn(app: Context, uri: Uri, out: File) {
        var total = 0L
        val ins = app.contentResolver.openInputStream(uri) ?: throw Exception("cannot open the file")
        ins.use { i ->
            out.outputStream().use { o ->
                val buf = ByteArray(32 * 1024)
                while (true) {
                    val n = i.read(buf)
                    if (n < 0) break
                    total += n
                    if (total > MAX_FILE) throw Exception("file too big (max 64 MB)")
                    o.write(buf, 0, n)
                }
            }
        }
        if (total < 22) throw Exception("not a zip / crx file")
    }

    /**
     * .zip: copied as is, returns null. .crx: the header is cut off, the zip part goes to out.
     * CRX3 (version 3): "Cr24", ver, headerLen, header, zip. The header is a protobuf; its crx_id is the Chrome id.
     * CRX2 (version 2): "Cr24", ver, keyLen, sigLen, key, sig, zip. Id = first 16 bytes of sha256(key).
     * Returns the Chrome id (32 chars a-p) when the crx gives one, else null.
     */
    private fun cutHeader(raw: File, out: File): String? {
        RandomAccessFile(raw, "r").use { f ->
            val len = f.length()
            val m = ByteArray(4)
            f.readFully(m)
            val isCrx = m[0] == 'C'.code.toByte() && m[1] == 'r'.code.toByte() && m[2] == '2'.code.toByte() && m[3] == '4'.code.toByte()
            var id: String? = null
            var start = 0L
            if (isCrx) {
                val ver = le32(f)
                if (ver == 3L) {
                    val hl = le32(f)
                    if (hl <= 0 || hl > 1024 * 1024 || 12 + hl >= len) throw Exception("bad crx header")
                    val hdr = ByteArray(hl.toInt())
                    f.readFully(hdr)
                    id = crx3Id(hdr)
                    start = 12 + hl
                } else if (ver == 2L) {
                    val kl = le32(f)
                    val sl = le32(f)
                    if (kl < 0 || sl < 0 || kl > 65536 || sl > 65536 || 16 + kl + sl >= len) throw Exception("bad crx header")
                    val key = ByteArray(kl.toInt())
                    f.readFully(key)
                    id = idFromBytes(key)
                    start = 16 + kl + sl
                } else {
                    throw Exception("unsupported crx version $ver")
                }
            } else if (!(m[0] == 'P'.code.toByte() && m[1] == 'K'.code.toByte())) {
                throw Exception("not a zip / crx file")
            }
            f.seek(start)
            val b = ByteArray(2)
            f.readFully(b)
            if (b[0] != 'P'.code.toByte() || b[1] != 'K'.code.toByte()) throw Exception("zip data not found")
            f.seek(start)
            out.outputStream().use { o ->
                val buf = ByteArray(32 * 1024)
                while (true) {
                    val n = f.read(buf)
                    if (n < 0) break
                    o.write(buf, 0, n)
                }
            }
            return id
        }
    }

    private fun le32(f: RandomAccessFile): Long {
        val b = ByteArray(4)
        f.readFully(b)
        return (b[0].toLong() and 0xff) or ((b[1].toLong() and 0xff) shl 8) or
            ((b[2].toLong() and 0xff) shl 16) or ((b[3].toLong() and 0xff) shl 24)
    }

    // CrxFileHeader: field 10002 (signed_header_data, bytes) holds SignedData whose field 1 is crx_id (16 bytes).
    private fun crx3Id(hdr: ByteArray): String? {
        try {
            val p = intArrayOf(0)
            while (p[0] < hdr.size) {
                val tag = varint(hdr, p)
                val field = (tag shr 3).toInt()
                val wire = (tag and 7).toInt()
                if (wire == 2) {
                    val l = varint(hdr, p).toInt()
                    if (l < 0 || p[0] + l > hdr.size) return null
                    if (field == 10002) {
                        val sd = hdr.copyOfRange(p[0], p[0] + l)
                        val q = intArrayOf(0)
                        while (q[0] < sd.size) {
                            val t2 = varint(sd, q)
                            if ((t2 and 7).toInt() != 2) return null
                            val l2 = varint(sd, q).toInt()
                            if (l2 < 0 || q[0] + l2 > sd.size) return null
                            if ((t2 shr 3).toInt() == 1 && l2 == 16) return nibbles(sd.copyOfRange(q[0], q[0] + 16))
                            q[0] += l2
                        }
                        return null
                    }
                    p[0] += l
                } else if (wire == 0) {
                    varint(hdr, p)
                } else {
                    return null
                }
            }
        } catch (e: Exception) { /* fall back to other id sources */ }
        return null
    }

    private fun varint(b: ByteArray, p: IntArray): Long {
        var r = 0L
        var s = 0
        while (true) {
            if (p[0] >= b.size || s > 63) throw Exception("bad varint")
            val x = b[p[0]++].toInt() and 0xff
            r = r or ((x and 0x7f).toLong() shl s)
            if (x and 0x80 == 0) return r
            s += 7
        }
    }

    private fun nibbles(b16: ByteArray): String {
        val sb = StringBuilder(32)
        for (x in b16) {
            val v = x.toInt() and 0xff
            sb.append('a' + (v shr 4)).append('a' + (v and 15))
        }
        return sb.toString()
    }

    private fun idFromBytes(b: ByteArray): String =
        nibbles(MessageDigest.getInstance("SHA-256").digest(b).copyOf(16))

    /** manifest "key" = base64 public key, same as Chrome (so the id matches the Web Store one). */
    private fun idFromKey(key: String): String? {
        if (key.isBlank()) return null
        return try {
            idFromBytes(android.util.Base64.decode(key.trim(), android.util.Base64.DEFAULT))
        } catch (e: Exception) {
            null
        }
    }

    /** No key anywhere: stable id from the name, so a newer zip of the same extension replaces the old one. */
    private fun idFromText(s: String): String = idFromBytes(s.toByteArray(Charsets.UTF_8))

    // ---------- manifest ----------

    /** manifest.json at the zip root, or inside the only top-level folder. */
    private fun findBase(d: File): File? {
        if (File(d, "manifest.json").isFile) return d
        val subs = d.listFiles()?.filter { it.isDirectory && it.name != "__MACOSX" } ?: return null
        val files = d.listFiles()?.count { it.isFile } ?: 0
        if (subs.size == 1 && files == 0 && File(subs[0], "manifest.json").isFile) return subs[0]
        return null
    }

    private fun readManifest(d: File): JSONObject {
        val t = File(d, "manifest.json").readText(Charsets.UTF_8).removePrefix("\uFEFF")
        return try { JSONObject(t) } catch (e: Exception) { throw Exception("manifest.json is not valid JSON") }
    }

    private fun strList(a: JSONArray?): ArrayList<String> {
        val r = ArrayList<String>()
        if (a == null) return r
        for (i in 0 until a.length()) {
            val s = a.optString(i, "")
            if (s.isNotEmpty()) r.add(s)
        }
        return r
    }

    private fun parse(id: String, d: File, m: JSONObject, on: Boolean): Info {
        val mv = m.optInt("manifest_version", 0)
        val perms = strList(m.optJSONArray("permissions"))
        val hosts = ArrayList(strList(m.optJSONArray("host_permissions")))
        if (mv == 2) {
            // MV2 keeps host patterns inside "permissions"; split them so S8 / S1 can show them as hosts
            val onlyApi = perms.filter { !(it.contains("://") || it == "<all_urls>") }
            hosts.addAll(perms.filter { it.contains("://") || it == "<all_urls>" })
            perms.clear()
            perms.addAll(onlyApi)
        }
        val action = m.optJSONObject("action") ?: m.optJSONObject("browser_action") ?: m.optJSONObject("page_action")
        val popup = clean(action?.optString("default_popup", ""))
        var opt = clean(m.optJSONObject("options_ui")?.optString("page", ""))
        val optTab = opt != null && m.optJSONObject("options_ui")?.optBoolean("open_in_tab", false) == true // 0.0.432 (S7)
        if (opt == null) opt = clean(m.optString("options_page", ""))
        val bg = m.optJSONObject("background")
        var bgFile = clean(bg?.optString("service_worker", ""))
        if (bgFile == null) {
            bgFile = clean(bg?.optString("page", "")) ?: strList(bg?.optJSONArray("scripts")).firstOrNull()
        }
        return Info(
            id = id,
            name = localized(d, m, m.optString("name")),
            version = m.optString("version"),
            manifestVersion = mv,
            description = localized(d, m, m.optString("description", "")),
            permissions = perms,
            hostPermissions = hosts,
            contentScriptCount = m.optJSONArray("content_scripts")?.length() ?: 0,
            popup = popup,
            options = opt,
            background = bgFile,
            enabled = on,
            dir = d,
            optionsInTab = optTab,
            actionTitle = localized(d, m, action?.optString("default_title", "") ?: "").trim()
        )
    }

    private fun clean(s: String?): String? {
        val t = s?.trim()?.removePrefix("/") ?: return null
        return if (t.isEmpty() || t.contains("..") || t.contains('\\')) null else t
    }

    /** "__MSG_key__" -> text from _locales/<default_locale or en>/messages.json. Anything else is returned as is. */
    private fun localized(d: File, m: JSONObject, s: String): String {
        if (!s.startsWith("__MSG_") || !s.endsWith("__") || s.length <= 8) return s
        val key = s.substring(6, s.length - 2).lowercase()
        val langs = LinkedHashSet<String>()
        val def = m.optString("default_locale", "")
        if (def.isNotEmpty()) langs.add(def)
        langs.add("en")
        for (l in langs) {
            if (l.contains("..") || l.contains('/') || l.contains('\\')) continue
            val f = ExtUpdater.safeFile(d, "_locales/$l/messages.json") ?: continue
            try {
                val j = JSONObject(f.readText(Charsets.UTF_8).removePrefix("\uFEFF"))
                val e = j.keys().asSequence().firstOrNull { it.lowercase() == key } ?: continue
                val msg = j.optJSONObject(e)?.optString("message", "") ?: ""
                if (msg.isNotEmpty()) return msg
            } catch (ex: Exception) { /* try next locale */ }
        }
        return s
    }

    /** Rebuilds the in-memory list from disk. Broken folders (no valid manifest) are skipped, not deleted. */
    private fun scan() {
        val off = prefs.getStringSet(KEY_OFF, emptySet()) ?: emptySet()
        val out = ArrayList<Info>()
        root.listFiles()?.sortedBy { it.name }?.forEach { d ->
            if (!d.isDirectory || !ID.matches(d.name)) return@forEach
            try {
                out.add(parse(d.name, d, readManifest(d), !off.contains(d.name)))
            } catch (e: Exception) { /* skip broken extension */ }
        }
        items = out.sortedBy { it.name.lowercase() }
    }
}
