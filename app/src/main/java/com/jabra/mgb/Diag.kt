package com.jabra.mgb

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.util.ArrayDeque
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * S82: one place for every native error / event, so a problem can be explained without adb.
 *
 * - Ring buffer: newest [MAX] events, saved to filesDir/mgb_diag.jsonl (survives a crash / restart).
 * - Everything is masked BEFORE it is stored: URLs keep host + path only (query values dropped, token-like
 *   path parts replaced), cookies / tokens / passwords / JWT / e-mails never reach the log.
 * - Safe to call from any thread, never throws, never blocks (disk write is debounced on a background thread).
 *
 * Tags used by the hooks (S83): tab, page, sniffer, download, engine, novel, manga, crash, app.
 * Levels: E = error, W = warning, I = info.
 * Report building (S84) and UI (S85) read this through [recent] / [dump] / [countSince].
 */
object Diag {
    const val MAX = 500
    private const val FILE = "mgb_diag.jsonl"
    private const val MAX_MSG = 400
    private const val MAX_EXTRA = 800
    private const val DUP_WINDOW_MS = 5000L

    data class Event(
        val ts: Long,
        val level: String,
        val tag: String,
        val msg: String,
        val extra: String,
        val n: Int // how many identical events were folded into this one (>= 1)
    )

    private val lock = Any()
    private val buf = ArrayDeque<Event>(MAX + 8)
    private var file: File? = null
    private var inited = false

    private val io = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "mgb-diag").apply { isDaemon = true }
    }
    private val flushPending = AtomicBoolean(false)

    // ---------------------------------------------------------------- setup

    /** Call once at start (CrashLog.install does it). Older events from the file go BEFORE any event logged earlier. */
    fun init(ctx: Context) {
        synchronized(lock) {
            if (inited) return
            inited = true
            file = File(ctx.applicationContext.filesDir, FILE)
            val old = ArrayList<Event>()
            try {
                val f = file!!
                if (f.exists()) {
                    f.forEachLine { line ->
                        parse(line)?.let { old.add(it) }
                    }
                }
            } catch (_: Throwable) { }
            val fresh = ArrayList(buf)
            buf.clear()
            for (e in old) buf.addLast(e)
            for (e in fresh) buf.addLast(e)
            while (buf.size > MAX) buf.removeFirst()
        }
    }

    // ---------------------------------------------------------------- logging

    fun e(tag: String, msg: String, extra: String? = null) = log("E", tag, msg, extra)
    fun w(tag: String, msg: String, extra: String? = null) = log("W", tag, msg, extra)
    fun i(tag: String, msg: String, extra: String? = null) = log("I", tag, msg, extra)

    /** Error with the top of the stack trace (first 8 lines) as extra. */
    fun e(tag: String, msg: String, t: Throwable) = log("E", tag, msg + ": " + t.javaClass.simpleName + " " + (t.message ?: ""), stackTop(t))

    fun log(level: String, tag: String, msg: String, extra: String? = null) {
        try {
            val now = System.currentTimeMillis()
            val m = mask(msg).take(MAX_MSG)
            val x = if (extra.isNullOrEmpty()) "" else mask(extra).take(MAX_EXTRA)
            val t = tag.take(16)
            synchronized(lock) {
                val last = buf.peekLast()
                if (last != null && last.level == level && last.tag == t && last.msg == m && now - last.ts <= DUP_WINDOW_MS) {
                    buf.removeLast()
                    buf.addLast(last.copy(ts = now, n = last.n + 1))
                } else {
                    buf.addLast(Event(now, level, t, m, x, 1))
                    while (buf.size > MAX) buf.removeFirst()
                }
            }
            scheduleFlush()
        } catch (_: Throwable) { }
    }

    /** Uncaught exception: written to disk right now (the process is about to die). Used by CrashLog. */
    fun crash(threadName: String, t: Throwable) {
        try {
            log("E", "crash", "uncaught on $threadName: " + t.javaClass.simpleName + " " + (t.message ?: ""), stackTop(t, 12))
            flushNow()
        } catch (_: Throwable) { }
    }

    // ---------------------------------------------------------------- reading

    /** Newest last. [tag] null = all tags. [errorsOnly] keeps level E and W. */
    fun recent(n: Int = 50, tag: String? = null, errorsOnly: Boolean = false): List<Event> = synchronized(lock) {
        val out = ArrayList<Event>()
        val it = buf.descendingIterator()
        while (it.hasNext() && out.size < n) {
            val e = it.next()
            if (tag != null && e.tag != tag) continue
            if (errorsOnly && e.level == "I") continue
            out.add(e)
        }
        out.reverse()
        out
    }

    fun size(): Int = synchronized(lock) { buf.size }

    /** How many events (counting folded repeats) with this tag since [sinceMs] (epoch). [level] null = any. */
    fun countSince(tag: String, sinceMs: Long, level: String? = null): Int = synchronized(lock) {
        var c = 0
        for (e in buf) if (e.ts >= sinceMs && e.tag == tag && (level == null || e.level == level)) c += e.n
        c
    }

    fun clear() {
        synchronized(lock) { buf.clear() }
        flushNow()
    }

    /** Plain text lines for a report: "12-03 14:05:09 E download: msg  [x3]" + indented extra. */
    fun dump(n: Int = 100, tag: String? = null, errorsOnly: Boolean = false): String {
        val sb = StringBuilder()
        val fmt = java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.US)
        for (e in recent(n, tag, errorsOnly)) {
            sb.append(fmt.format(java.util.Date(e.ts))).append(' ').append(e.level).append(' ')
                .append(e.tag).append(": ").append(e.msg)
            if (e.n > 1) sb.append("  [x").append(e.n).append(']')
            sb.append('\n')
            if (e.extra.isNotEmpty()) {
                for (l in e.extra.lines()) sb.append("    ").append(l).append('\n')
            }
        }
        return sb.toString()
    }

    // ---------------------------------------------------------------- disk

    private fun scheduleFlush() {
        if (file == null) return
        if (flushPending.compareAndSet(false, true)) {
            try {
                io.schedule({ flushPending.set(false); flushNow() }, 1500, TimeUnit.MILLISECONDS)
            } catch (_: Throwable) { flushPending.set(false) }
        }
    }

    private fun flushNow() {
        var f: File? = null
        var snap: List<Event> = emptyList()
        synchronized(lock) {
            f = file
            snap = ArrayList(buf)
        }
        val target = f ?: return
        try {
            val tmp = File(target.parentFile, FILE + ".tmp")
            tmp.bufferedWriter().use { w ->
                for (e in snap) { w.write(toJson(e)); w.write("\n") }
            }
            if (!tmp.renameTo(target)) { target.writeText(tmp.readText()); tmp.delete() }
        } catch (_: Throwable) { }
    }

    private fun toJson(e: Event): String = JSONObject()
        .put("t", e.ts).put("l", e.level).put("g", e.tag).put("m", e.msg).put("x", e.extra).put("n", e.n)
        .toString()

    private fun parse(line: String): Event? = try {
        if (line.isBlank()) null else {
            val o = JSONObject(line)
            Event(o.getLong("t"), o.optString("l", "I"), o.optString("g", "app"), o.optString("m"), o.optString("x"), o.optInt("n", 1).coerceAtLeast(1))
        }
    } catch (_: Throwable) { null }

    // ---------------------------------------------------------------- stack

    private fun stackTop(t: Throwable, lines: Int = 8): String {
        val sb = StringBuilder()
        var c: Throwable? = t
        var depth = 0
        while (c != null && depth < 3) {
            if (depth > 0) sb.append("Caused by: ").append(c.javaClass.name).append(": ").append(c.message ?: "").append('\n')
            val st = c.stackTrace
            for (k in 0 until minOf(lines, st.size)) sb.append("at ").append(st[k].toString()).append('\n')
            c = c.cause
            depth++
        }
        return sb.toString().trimEnd()
    }

    // ---------------------------------------------------------------- masking

    private val URL_RE = Regex("""(?i)\b(?:https?|wss?|ftp|blob:https?)://[^\s"'<>)\]}]+""")
    private val BEARER_RE = Regex("""(?i)\bBearer\s+[A-Za-z0-9._~+/=\-]+""")
    private val JWT_RE = Regex("""eyJ[A-Za-z0-9_\-]{8,}\.[A-Za-z0-9_\-]{8,}\.?[A-Za-z0-9_\-]*""")
    private val KEY_RE = Regex(
        """(?i)\b(authorization|set-cookie|cookie|access[_-]?token|refresh[_-]?token|id[_-]?token|token|api[_-]?key|apikey|secret|password|passwd|pwd|session(?:id)?|sid|signature|sig|auth|key)\b(["']?\s*[:=]\s*)("[^"]*"|'[^']*'|[^\s,;&]+)"""
    )
    // whole header line: "Cookie: a=1; b=2" / "Authorization: ..." -> value gone completely
    private val HEADER_RE = Regex("""(?im)\b(cookie|set-cookie|authorization)\b(["']?\s*:\s*).*$""")
    private val EMAIL_RE = Regex("""[A-Za-z0-9._%+\-]+@[A-Za-z0-9.\-]+\.[A-Za-z]{2,}""")
    private val BLOB_RE = Regex("""[A-Za-z0-9+/_\-]{40,}={0,2}""")
    private val UUID_RE = Regex("""(?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}""")
    private val SAFE_NAME = Regex("""[A-Za-z0-9_.\-]{1,20}""")

    /** Masks free text: every URL -> host + path only, then secrets, e-mails and long token-like blobs. */
    fun mask(s: String): String {
        if (s.isEmpty()) return s
        var r = URL_RE.replace(s) { maskUrl(it.value) }
        r = BEARER_RE.replace(r, "Bearer ***")
        r = JWT_RE.replace(r, "JWT***")
        r = HEADER_RE.replace(r) { it.groupValues[1] + it.groupValues[2] + "***" }
        r = KEY_RE.replace(r) { it.groupValues[1] + it.groupValues[2] + "***" }
        r = EMAIL_RE.replace(r, "***@***")
        r = BLOB_RE.replace(r, "{blob}")
        return r
    }

    /**
     * "https://user:pw@cdn.site.com/v/ab12cd34ef56gh78ij90kl12mn34/seg1.ts?token=abc&exp=9#x"
     *  -> "https://cdn.site.com/v/{id}/seg1.ts?[token,exp]"
     * Host + path stay (needed to see WHICH site / kind of file), query VALUES and fragment are dropped,
     * only the parameter NAMES are kept (a missing / expired "token" or "exp" name is a useful hint).
     */
    fun maskUrl(url: String): String {
        try {
            val scheme = url.substringBefore("://", "")
            if (scheme.isEmpty()) return url.take(120)
            var rest = url.substringAfter("://")
            rest = rest.substringBefore('#')
            val query = if (rest.contains('?')) rest.substringAfter('?') else ""
            rest = rest.substringBefore('?')
            val host0 = rest.substringBefore('/')
            val path0 = if (rest.contains('/')) "/" + rest.substringAfter('/') else ""
            val host = host0.substringAfterLast('@') // drop user:password@
            val path = path0.split('/').joinToString("/") { seg -> if (looksLikeToken(seg)) "{id}" else seg }
            val names = if (query.isEmpty()) "" else {
                val list = query.split('&').map { it.substringBefore('=') }
                    .filter { SAFE_NAME.matches(it) }.distinct().take(6)
                "?[" + list.joinToString(",") + "]" + if (query.count { it == '&' } >= 6) "+" else ""
            }
            return "$scheme://$host$path$names".take(300)
        } catch (_: Throwable) {
            return "{url}"
        }
    }

    /** Long random-looking path part (hex / base64 / id). Slugs with words and file names are kept. */
    private fun looksLikeToken(seg: String): Boolean {
        if (UUID_RE.matches(seg)) return true
        if (seg.length < 20) return false
        if (seg.indexOf('.') in 1 until seg.length - 1) {
            // file name with an extension: judge only the part before the last dot
            return looksLikeToken(seg.substringBeforeLast('.'))
        }
        var digits = 0
        var letters = 0
        for (ch in seg) {
            if (ch.isDigit()) digits++ else if (ch.isLetter()) letters++ else if (ch != '-' && ch != '_' && ch != '=' && ch != '%' && ch != '+') return false
        }
        if (digits == 0 || letters == 0) return digits >= 12
        if (seg.length >= 32 && !seg.contains('-')) return true
        return digits.toDouble() / seg.length >= 0.2 && !seg.contains('-')
    }
}
