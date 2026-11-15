package com.jabra.mgb

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import androidx.annotation.RequiresApi
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 0.0.397: two Report center sections that show WHY the app died, from things the phone already keeps.
 *  - "Last crash": CrashLog.showLast keeps a copy of the crash text (filesDir/mgb_crash_last.txt) before it
 *    deletes the file, so the report can show it any time later.
 *  - "App exits": ActivityManager.getHistoricalProcessExitReasons (Android 11+), shows kills a Java stack
 *    trace never shows (low memory, not responding, killed by the system).
 *
 * Read only. Nothing here changes any setting, file or flow. No new permission, no network.
 * Called from DiagReport.build on a WORKER thread. Every part is wrapped: a failure = a line "not readable".
 */
object DiagExtra {
    const val CRASH_COPY = "mgb_crash_last.txt"   // written by CrashLog.saveCopy
    private const val CRASH_FILE = "mgb_crash.txt" // written by the uncaught handler (CrashLog)
    private const val DAY = 24 * 60 * 60 * 1000L

    class Bundle(
        val crash: DiagReport.Section,
        val exits: DiagReport.Section,
        val lowMem24h: Int,   // exits with LOW_MEMORY in the last 24 h (for the rule)
        val anr24h: Int,      // exits with ANR in the last 24 h (for the rule)
        val exts: DiagReport.Section = DiagReport.Section("exts", "Browser", "Extensions", DiagReport.INFO, listOf("not measured")) // 0.0.401
    )

    private inline fun <T> safe(def: T, f: () -> T): T = try { f() } catch (e: Throwable) { def }

    fun empty(): Bundle = Bundle(
        DiagReport.Section("lastcrash", "Device", "Last crash", DiagReport.INFO, listOf("not measured")),
        DiagReport.Section("exits", "Device", "App exits", DiagReport.INFO, listOf("not measured")),
        0, 0)

    /** Worker thread. Never throws. */
    fun collect(ctx: Context): Bundle {
        val now = System.currentTimeMillis()
        val crash = safe(DiagReport.Section("lastcrash", "Device", "Last crash", DiagReport.INFO, listOf("not readable"))) { lastCrash(ctx, now) }
        var low = 0
        var anr = 0
        val exits = safe(DiagReport.Section("exits", "Device", "App exits", DiagReport.INFO, listOf("not readable"))) {
            val r = exitSection(ctx, now)
            low = r.second; anr = r.third
            r.first
        }
        val exts = safe(DiagReport.Section("exts", "Browser", "Extensions", DiagReport.INFO, listOf("not readable"))) { extensions(ctx, now) } // 0.0.401
        return Bundle(crash, exits, low, anr, exts)
    }

    // ------------------------------------------------------------------ 1. last crash

    internal fun fmtTime(ts: Long): String = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(ts))

    private fun ago(ms: Long): String {
        val m = maxOf(0L, ms) / 60000L
        return if (m < 60) "$m min ago" else if (m < 60 * 48) "${m / 60} h ${m % 60} min ago" else "${m / 1440} days ago"
    }

    private fun lastCrash(ctx: Context, now: Long): DiagReport.Section {
        val dir = ctx.applicationContext.filesDir
        val fresh = File(dir, CRASH_FILE)     // a crash the dialog has not shown yet (newest)
        val copy = File(dir, CRASH_COPY)      // a crash the dialog already showed
        val src = if (fresh.exists()) fresh else if (copy.exists()) copy else null
        if (src == null) return DiagReport.Section("lastcrash", "Device", "Last crash", DiagReport.INFO,
            listOf("No crash saved yet (a crash is saved when it happens; the copy stays here after the dialog is closed)"))
        val raw = src.readText()
        val rows = raw.lines()
        var i = 0
        var ts = src.lastModified()
        if (rows.isNotEmpty() && rows[0].startsWith("#time=")) { ts = rows[0].removePrefix("#time=").trim().toLongOrNull() ?: ts; i = 1 }
        val head = rows.getOrNull(i) ?: ""
        i++
        val ver = if (head.startsWith("MGB ")) head.removePrefix("MGB ").substringBefore(" thread=").trim() else "?"
        val thr = if (head.contains("thread=")) head.substringAfter("thread=").trim() else "?"
        val stack = rows.drop(i).map { it.trim() }.filter { it.isNotEmpty() }
        val show = ArrayList<String>(stack.take(12))
        // the line with com.jabra.mgb is what matters: if it is not in the first 12, add up to 3 of them
        if (show.none { it.contains("com.jabra.mgb") }) show.addAll(stack.drop(12).filter { it.contains("com.jabra.mgb") }.take(3))
        val recent = ts > 0 && now - ts <= DAY
        val lines = ArrayList<String>()
        lines.add("Time: " + fmtTime(ts) + " (" + ago(now - ts) + ")")
        lines.add("App version: $ver")
        lines.add("Thread: $thr")
        if (show.isEmpty()) lines.add("No stack saved") else for (l in show) lines.add("  " + Diag.mask(l.take(200)))
        return DiagReport.Section("lastcrash", "Device", "Last crash", if (recent) DiagReport.BAD else DiagReport.INFO, lines)
    }

    /** Called by CrashLog.showLast BEFORE it deletes the crash file. Never throws. */
    fun saveCrashCopy(ctx: Context, text: String, crashTime: Long) {
        try {
            if (text.isBlank()) return
            val t = if (crashTime > 0) crashTime else System.currentTimeMillis()
            File(ctx.applicationContext.filesDir, CRASH_COPY).writeText("#time=$t\n" + text.take(6000))
        } catch (_: Throwable) { }
    }

    // ------------------------------------------------------------------ 5. battery optimisation + notifications (0.0.399)

    class Power(val section: DiagReport.Section, val notifOff: Boolean, val activeDownloads: Int, val battOptimised: Boolean = false)

    /** Main thread is fine (2 cheap system calls). Never throws. Read only: no permission is asked. */
    fun power(ctx: Context): Power {
        val notifOn: Boolean? = safe(null) { androidx.core.app.NotificationManagerCompat.from(ctx).areNotificationsEnabled() }
        val optimised: Boolean? = safe(null) {
            val pm = ctx.getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
            if (pm == null) null else !pm.isIgnoringBatteryOptimizations(ctx.packageName)
        }
        val active = safe(0) { DownloadsApi.keepIds().size }
        val warn = notifOn == false && active > 0
        val lines = ArrayList<String>()
        lines.add("Notifications: " + (if (notifOn == null) "unknown" else if (notifOn) "allowed" else "OFF"))
        lines.add("Battery optimisation for MGB: " + (if (optimised == null) "unknown" else if (optimised) "ON (Android may stop long downloads in the background)" else "off (MGB is exempt)"))
        lines.add("Running or resumable downloads: $active")
        if (warn) lines.add("Downloads exist but notifications are off: progress will not show in the notification bar")
        return Power(DiagReport.Section("power", "Device", "Battery and notifications", if (warn) DiagReport.WARN else DiagReport.INFO, lines), notifOn == false, active, optimised == true)
    }

    // ------------------------------------------------------------------ 6. per-download errors (0.0.400)

    private fun size(b: Long): String = if (b >= 1L shl 30) String.format(Locale.US, "%.2f GB", b / 1073741824.0) else String.format(Locale.US, "%.1f MB", b / 1048576.0)

    /** Last 10 interrupted downloads: id, error code, HOST only (never the full url), size, time. Uses DownloadsApi.interruptedList() (read only). */
    fun downloadErrors(now: Long): DiagReport.Section {
        val all = DownloadsApi.interruptedList().sortedByDescending { if (it.end > 0) it.end else it.start }
        if (all.isEmpty()) return DiagReport.Section("dlerrors", "Download", "Download errors", DiagReport.INFO, listOf("No interrupted download in the list"))
        val failed = all.filter { it.error != "USER_CANCELED" }
        val fmt = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US)
        val lines = ArrayList<String>()
        lines.add("Interrupted: " + all.size + " (" + failed.size + " failed, " + (all.size - failed.size) + " cancelled by you). Newest 10:")
        for (i in all.take(10)) {
            val host = safe("?") { val u = i.finalUrl.ifEmpty { i.url }; if (u.startsWith("blob:")) "blob" else (android.net.Uri.parse(u).host ?: "?") }
            val sz = if (i.fileSize > 0) size(i.fileSize) else if (i.received > 0) "got " + size(i.received) + (if (i.total > 0) " of " + size(i.total) else "") else "no data"
            val t = if (i.end > 0) i.end else i.start
            lines.add("  #" + i.id + " " + fmt.format(Date(t)) + " " + Diag.mask(i.error.ifBlank { "?" }.take(40)) + ", " + Diag.mask(host.take(60)) + ", " + sz + (if (i.canResume) ", can resume" else ""))
        }
        val recent = failed.any { (if (it.end > 0) it.end else it.start) >= now - 10 * 60 * 1000L }
        return DiagReport.Section("dlerrors", "Download", "Download errors", if (recent) DiagReport.WARN else DiagReport.INFO, lines)
    }

    // ------------------------------------------------------------------ 7. extensions (0.0.401)

    /** Folder size with a cap (files counted), so a huge store cannot make the report slow. Worker thread only. */
    private fun dirBytes(d: File, left: IntArray): Long {
        var sum = 0L
        val kids = d.listFiles() ?: return 0L
        for (k in kids) {
            if (left[0] <= 0) break
            left[0]--
            sum += if (k.isDirectory) dirBytes(k, left) else k.length()
        }
        return sum
    }

    /**
     * Installed extensions: name, short id, version, on / off, storage size (filesDir/exts_data/<id>) and the last
     * problem. Problems are NOT kept in a new place: ExtBackground / ExtApi / ExtScreen / ExtSafety / ExtListScreen
     * already write them to the Diag ring (tag "ext", extra = "<name>: <detail>", saved to disk), so the newest one per
     * extension is read from there. Page-side injection errors (ContentInjector) are not kept anywhere (open item).
     */
    private fun extensions(ctx: Context, now: Long): DiagReport.Section {
        val all = ExtRegistry.all()
        if (all.isEmpty()) return DiagReport.Section("exts", "Browser", "Extensions", DiagReport.INFO, listOf("No extension installed"))
        val ev = Diag.recent(200, "ext", false)
        val last = HashMap<String, Diag.Event>()
        for (e in ev) { // oldest first, so the newest wins; the longest matching name owns the event
            val owner = all.filter { e.extra.startsWith(it.name) && (e.extra.length == it.name.length || e.extra[it.name.length] == ':' || e.extra[it.name.length] == ' ') }
                .maxByOrNull { it.name.length } ?: continue
            last[owner.id] = e
        }
        val root = File(ctx.applicationContext.filesDir, "exts_data")
        val fmt = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US)
        val lines = ArrayList<String>()
        lines.add("Installed: " + all.size + ", on: " + all.count { it.enabled })
        var recentErr = false
        for (x in all) {
            val bytes = safe(-1L) { val d = File(root, x.id); if (d.isDirectory) dirBytes(d, intArrayOf(20000)) else 0L }
            val b = StringBuilder("  " + Diag.mask(x.name.take(40)) + " (" + x.id.take(8) + ") v" + x.version.take(16) + ", " + (if (x.enabled) "on" else "off") +
                ", storage " + (if (bytes < 0) "?" else size(bytes)))
            val e = last[x.id]
            if (e == null) b.append(", no problem logged") else {
                if (e.ts >= now - 10 * 60 * 1000L) recentErr = true
                b.append(", last problem ").append(fmt.format(Date(e.ts))).append(": ").append(Diag.mask(e.msg.take(60)))
                val more = e.extra.removePrefix(x.name).trimStart(':', ' ').take(120)
                if (more.isNotEmpty()) b.append(" - ").append(Diag.mask(more))
            }
            lines.add(b.toString())
        }
        return DiagReport.Section("exts", "Browser", "Extensions", if (recentErr) DiagReport.WARN else DiagReport.INFO, lines)
    }

    // ------------------------------------------------------------------ 2. why the OS ended the app

    /** Returns (section, low memory count in 24 h, ANR count in 24 h). */
    private fun exitSection(ctx: Context, now: Long): Triple<DiagReport.Section, Int, Int> {
        fun sec(level: String, lines: List<String>) = DiagReport.Section("exits", "Device", "App exits", level, lines)
        if (Build.VERSION.SDK_INT < 30) return Triple(sec(DiagReport.INFO,
            listOf("Needs Android 11 or newer (this phone: API " + Build.VERSION.SDK_INT + ")")), 0, 0)
        return exits30(ctx, now)
    }

    @RequiresApi(30)
    private fun exits30(ctx: Context, now: Long): Triple<DiagReport.Section, Int, Int> {
        fun sec(level: String, lines: List<String>) = DiagReport.Section("exits", "Device", "App exits", level, lines)
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            ?: return Triple(sec(DiagReport.INFO, listOf("ActivityManager not available")), 0, 0)
        val list = am.getHistoricalProcessExitReasons(ctx.packageName, 0, 10)
        if (list.isNullOrEmpty()) return Triple(sec(DiagReport.INFO, listOf("Android has no exit record yet")), 0, 0)
        var low = 0; var anr = 0; var crash = 0
        val rows = ArrayList<String>()
        for (e in list) {
            val recent = now - e.timestamp <= DAY
            if (recent) when (e.reason) {
                ApplicationExitInfo.REASON_LOW_MEMORY -> low++
                ApplicationExitInfo.REASON_ANR -> anr++
                ApplicationExitInfo.REASON_CRASH, ApplicationExitInfo.REASON_CRASH_NATIVE -> crash++
            }
            val proc = e.processName.removePrefix(ctx.packageName).ifEmpty { "main" }.trimStart(':')
            val b = StringBuilder()
            b.append("  ").append(fmtTime(e.timestamp)).append(' ').append(reasonName(e.reason))
            if (e.reason == ApplicationExitInfo.REASON_SIGNALED || e.reason == ApplicationExitInfo.REASON_CRASH_NATIVE) b.append(" (signal ").append(e.status).append(')')
            b.append(", was ").append(importanceName(e.importance)).append(", process ").append(proc)
            if (e.rss > 0 || e.pss > 0) b.append(", memory ").append(if (e.rss > 0) "rss " + e.rss / 1024 + " MB" else "pss " + e.pss / 1024 + " MB")
            val d = (e.description ?: "").trim()
            if (d.isNotEmpty()) b.append(": ").append(Diag.mask(d.take(120)))
            rows.add(b.toString())
        }
        val head = ArrayList<String>()
        head.add("Last 24 h: low memory $low, not responding (ANR) $anr, crash $crash")
        head.add("Newest first (Android keeps only a few):")
        val level = if (low + anr >= 3) DiagReport.WARN else DiagReport.INFO
        val nativeStack = try { nativeCrashLines(ctx, list) } catch (_: Throwable) { listOf("Native crash stack: not readable") }
        return Triple(sec(level, head + rows + nativeStack), low, anr)
    }

    // ------------------------------------------------------------------ 2b. native crash stack (0.0.445)

    private class Fld(val num: Int, val v: Long, val s: Int, val e: Int)

    /** Minimal protobuf reader: the top-level fields of b[s0, e0). Throws IllegalStateException on bad data. */
    private fun pbFields(b: ByteArray, s0: Int, e0: Int): List<Fld> {
        val out = ArrayList<Fld>()
        var p = s0
        fun vi(): Long {
            var r = 0L
            var sh = 0
            while (true) {
                if (p >= e0 || sh > 63) throw IllegalStateException("pb")
                val c = b[p++].toInt() and 0xff
                r = r or ((c and 0x7f).toLong() shl sh)
                if ((c and 0x80) == 0) return r
                sh += 7
            }
        }
        while (p < e0) {
            val tag = vi()
            val num = (tag ushr 3).toInt()
            when ((tag and 7L).toInt()) {
                0 -> out.add(Fld(num, vi(), 0, 0))
                1 -> { if (p + 8 > e0) throw IllegalStateException("pb"); p += 8 }
                5 -> { if (p + 4 > e0) throw IllegalStateException("pb"); p += 4 }
                2 -> {
                    val n = vi()
                    if (n < 0 || p + n > e0) throw IllegalStateException("pb")
                    out.add(Fld(num, 0, p, p + n.toInt()))
                    p += n.toInt()
                }
                else -> throw IllegalStateException("pb")
            }
        }
        return out
    }

    /** Android 12+ keeps a native crash as a protobuf tombstone (AOSP tombstone.proto): signal, abort message, crashing thread frames. */
    private fun tombstoneLines(b: ByteArray): List<String> {
        fun str(f: Fld) = String(b, f.s, f.e - f.s, Charsets.UTF_8)
        val top = pbFields(b, 0, b.size)
        val tid = top.firstOrNull { it.num == 6 }?.v ?: -1L
        val out = ArrayList<String>()
        top.firstOrNull { it.num == 10 }?.let { f ->
            val sig = pbFields(b, f.s, f.e)
            val name = sig.firstOrNull { it.num == 2 }?.let { str(it) } ?: ""
            val code = sig.firstOrNull { it.num == 4 }?.let { str(it) } ?: ""
            val hasAddr = sig.firstOrNull { it.num == 8 }?.v == 1L
            val addr = sig.firstOrNull { it.num == 9 }?.v // 0.0.470: signal_info.fault_address
            out.add(("signal: $name $code" + (if (hasAddr && addr != null) " fault addr 0x" + java.lang.Long.toHexString(addr) else "")).trim())
        }
        top.firstOrNull { it.num == 14 }?.let { out.add("abort message: " + str(it).take(300)) }
        top.firstOrNull { it.num == 20 }?.let { out.add("process uptime: " + it.v + " s") } // 0.0.470: tombstone.process_uptime
        for (c in top.filter { it.num == 15 }) {
            pbFields(b, c.s, c.e).firstOrNull { it.num == 1 }?.let { out.add("cause: " + str(it).take(300)) }
        }
        for (t in top.filter { it.num == 16 }) {
            val entry = pbFields(b, t.s, t.e) // map entry: key = 1, value = Thread = 2
            val thr = entry.firstOrNull { it.num == 2 } ?: continue
            val tf = pbFields(b, thr.s, thr.e)
            val id = tf.firstOrNull { it.num == 1 }?.v ?: -2L
            if (id != tid) continue
            out.add("crashing thread: " + (tf.firstOrNull { it.num == 2 }?.let { str(it) } ?: "?") + " (tid " + id + ")")
            var n = 0
            val seenBid = HashSet<String>()
            for (fr in tf.filter { it.num == 4 }) {
                if (n >= 40) break
                val ff = pbFields(b, fr.s, fr.e)
                // 0.0.470: BacktraceFrame (tombstone.proto): 1 rel_pc, 2 pc, 3 sp, 4 function_name, 5 function_offset (number), 6 file_name, 7 file_map_offset, 8 build_id.
                // Before, field 5 was read as the file name (it is a number), so every frame lost its library name.
                val fn = ff.firstOrNull { it.num == 4 }?.let { str(it) } ?: ""
                val fo = ff.firstOrNull { it.num == 5 }?.v ?: 0L
                val file = ff.firstOrNull { it.num == 6 && it.e > it.s }?.let { str(it) } ?: ""
                val bid = ff.firstOrNull { it.num == 8 && it.e > it.s }?.let { str(it) } ?: ""
                val off = ff.firstOrNull { it.num == 1 }?.v ?: 0L
                val lib = file.substringAfterLast('/')
                val sb = StringBuilder("  #").append(n).append(' ').append(lib).append(" +0x").append(java.lang.Long.toHexString(off))
                if (fn.isNotEmpty()) { sb.append(' ').append(fn); if (fo != 0L) sb.append("+0x").append(java.lang.Long.toHexString(fo)) }
                if (bid.isNotEmpty() && seenBid.add(lib + bid)) sb.append("  [build-id ").append(bid.take(40)).append(']') // once per library
                out.add(sb.toString())
                n++
            }
        }
        return out
    }

    /** Older Android keeps the tombstone as text: keep the signal line, abort message and the "#NN pc" frames. */
    private fun textTombstoneLines(b: ByteArray): List<String> {
        val keep = Regex("^\\s*(signal \\d+|Abort message|Cause:|\\*\\*\\* \\*\\*\\*|pid:|#\\d+ pc )")
        return String(b, Charsets.UTF_8).lineSequence().filter { keep.containsMatchIn(it) }.take(60).toList()
    }

    /** The stack of the newest native crash (signal 11 ...) of the MAIN process. Read only, capped at 2 MB, never throws. */
    @RequiresApi(30)
    internal fun nativeCrashLines(ctx: Context, list: List<ApplicationExitInfo>): List<String> {
        val e = list.firstOrNull { it.reason == ApplicationExitInfo.REASON_CRASH_NATIVE && it.processName == ctx.packageName }
            ?: return emptyList()
        val head = "Native crash stack (newest main process crash, " + fmtTime(e.timestamp) + "):"
        val bytes = try {
            e.traceInputStream?.use { ins ->
                val buf = java.io.ByteArrayOutputStream()
                val tmp = ByteArray(16384)
                while (buf.size() < 2_000_000) {
                    val n = ins.read(tmp)
                    if (n < 0) break
                    buf.write(tmp, 0, n)
                }
                buf.toByteArray()
            }
        } catch (_: Throwable) { null }
        if (bytes == null || bytes.isEmpty()) return listOf(head, "  Android kept no stack for this crash")
        var lines: List<String> = try { tombstoneLines(bytes) } catch (_: Throwable) { emptyList() }
        if (lines.none { it.startsWith("crashing thread") }) {
            val txt = try { textTombstoneLines(bytes) } catch (_: Throwable) { emptyList() }
            if (txt.isNotEmpty()) lines = txt
        }
        if (lines.isEmpty()) return listOf(head, "  stack could not be read (" + bytes.size + " bytes, format not recognised)")
        return listOf(head) + lines.take(70).map { "  " + Diag.mask(it.take(200)) }
    }

    @RequiresApi(30)
    internal fun reasonName(r: Int): String = when (r) {
        ApplicationExitInfo.REASON_ANR -> "ANR (app did not respond)"
        ApplicationExitInfo.REASON_CRASH -> "CRASH"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "CRASH_NATIVE"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "LOW_MEMORY"
        ApplicationExitInfo.REASON_SIGNALED -> "SIGNALED"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "EXCESSIVE_RESOURCE_USAGE"
        ApplicationExitInfo.REASON_USER_REQUESTED -> "USER_REQUESTED (swiped away / force stop)"
        ApplicationExitInfo.REASON_USER_STOPPED -> "USER_STOPPED"
        ApplicationExitInfo.REASON_EXIT_SELF -> "EXIT_SELF"
        ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "DEPENDENCY_DIED"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "INITIALIZATION_FAILURE"
        ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "PERMISSION_CHANGE"
        ApplicationExitInfo.REASON_OTHER -> "OTHER"
        ApplicationExitInfo.REASON_UNKNOWN -> "UNKNOWN"
        14 -> "FREEZER"
        15 -> "PACKAGE_STATE_CHANGE"
        16 -> "PACKAGE_UPDATED"
        else -> "REASON_$r"
    }

    internal fun importanceName(i: Int): String = when {
        i <= 100 -> "foreground"
        i <= 130 -> "foreground service"
        i <= 200 -> "visible"
        i <= 230 -> "perceptible"
        i <= 300 -> "service"
        i <= 400 -> "cached (background)"
        else -> "gone"
    }
}
