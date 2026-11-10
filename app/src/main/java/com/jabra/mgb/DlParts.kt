package com.jabra.mgb

import android.content.Context
import android.os.SystemClock
import android.os.StatFs
import okhttp3.Call
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLongArray
import java.util.concurrent.atomic.AtomicReference

/**
 * 0.0.357 (S1): "Download in parts" (1DM style) for the NATIVE Downloader.
 * ONE file is cut into N byte ranges; N connections fetch them at the same time (HTTP Range);
 * all parts are written into one preallocated file (files/dl/ID.pp). When every byte is there the
 * file is renamed to the old ID.part, so finish() / publish() in DownloadService.kt run unchanged.
 *
 * run(item) returns TRUE  = whole file is complete in Downloader.partFile(id) (caller goes on to finish()).
 *            FALSE = parts mode not used or failed (switch OFF, POST, no Range, size unknown, small file,
 *                    low disk, any error): the caller runs the OLD single connection code, unchanged.
 *            throws IOException = user paused / cancelled (Downloader.run stops silently on that).
 * Switch pref dp_on is OFF by default.
 * 0.0.358 (S2): state file ID.pp.json (saved ~1 s + on pause) => pause / resume / process death continue every part from its own offset;
 *   changed size / validator = start again; adaptive fewer connections (429 / 403 / 200 / 5xx); network loss keeps the state (Resume button).
 */
object DlParts {
    private const val TAG = "dlparts"
    private const val MAX_WORKERS = 16          // global cap over all running downloads
    private const val MB = 1024L * 1024L
    private val N_ALLOWED = intArrayOf(2, 4, 6, 8, 16)

    private val used = AtomicInteger(0)
    private val runs = ConcurrentHashMap<Int, Run>()
    /** Thrown to Downloader.run as a plain IOException (state files are kept, item stays resumable). */
    private class KeepIo(val io: IOException) : Exception()
    private class HttpStatus(val code: Int) : IOException("HTTP $code")
    private const val SOFT = 1   // server refuses / too many connections: fewer connections, then single connection
    private const val HARD = 2   // parts mode cannot work for this file: single connection at once
    private const val NET = 3    // network lost: keep state, item becomes interrupted + resumable

    private val exec: ExecutorService by lazy {
        Executors.newCachedThreadPool(ThreadFactory { r -> Thread(r, "dlparts").also { it.isDaemon = true } })
    }

    /** Live state of one parts download. n parts (fixed layout); a round runs w workers that take the unfinished parts. */
    private class Run(val item: DlItem, val total: Long, val n: Int) {
        val starts = LongArray(n)
        val ends = LongArray(n)
        val done = AtomicLongArray(n)
        val calls: MutableSet<Call> = ConcurrentHashMap.newKeySet<Call>()
        val next = AtomicInteger(0)
        @Volatile var stop = false
        @Volatile var kind = 0
        @Volatile var netEx: IOException? = null
        val err = AtomicReference<String?>(null)
        // 0.0.368 (F1): HTTP code + Retry-After (seconds) of the failure, and finished parts of this round (slow start)
        @Volatile var code = 0
        @Volatile var ra = 0L
        val fin = AtomicInteger(0)

        fun halt() {
            stop = true
            for (c in calls) { try { c.cancel() } catch (e: Exception) { } }
        }
        fun fail(why: String, k: Int, ex: IOException? = null, c: Int = 0, retryAfter: Long = 0L) {
            if (err.compareAndSet(null, why)) { kind = k; netEx = ex; code = c; ra = retryAfter }
            halt()
        }
        /** New round after a soft failure (all workers of the old round are finished). */
        fun reset() { stop = false; kind = 0; netEx = null; code = 0; ra = 0L; fin.set(0); err.set(null); calls.clear(); next.set(0) }
        fun sum(): Long { var t = 0L; for (i in 0 until n) t += done.get(i); return t }

        // 0.0.359 (S3): speed meter (bytes per second, smoothed). tick() is called by the progress loop only.
        @Volatile var speed = 0L
        private var mT = 0L
        private var mB = 0L
        fun tick(now: Long) {
            val b = sum()
            if (mT == 0L) { mT = now; mB = b; return }
            val dt = now - mT
            if (dt < 500L) return
            val inst = ((b - mB).coerceAtLeast(0L) * 1000L) / dt
            speed = if (speed == 0L) inst else (speed * 7L + inst * 3L) / 10L
            mT = now; mB = b
        }
    }

    private class Probe(val code: Int, val total: Long, val vld: String, val ct: String, val cd: String?, val fu: String, val enc: String)

    // ---------- prefs (SharedPreferences mgb_ui) ----------

    private fun sp() = DownloadsApi.app.getSharedPreferences("mgb_ui", Context.MODE_PRIVATE)

    /** Default OFF (Rule 0). Flip later = change false to true here. */
    fun enabled(): Boolean = try { sp().getBoolean("dp_on", false) } catch (e: Exception) { false }

    /** 0.0.372 (N4): parts for this item: its own pn (2..16) first, else the global setting. */
    private fun nParts(item: DlItem): Int = if (item.pn in N_ALLOWED) item.pn else nParts()

    private fun nParts(): Int = try { sp().getInt("dp_n", 4).let { if (it in N_ALLOWED) it else 4 } } catch (e: Exception) { 4 }

    private fun minBytes(): Long {
        val mb = try { sp().getInt("dp_min_mb", 8) } catch (e: Exception) { 8 }
        return maxOf(1L, mb.toLong()) * MB
    }

    // ---------- files ----------

    private fun ppFile(id: Int): File = File(Downloader.partFile(id).parentFile, "$id.pp")

    /** Deletes the parts data file (and the S2 state file). Never touches the old .part file. */
    fun clean(id: Int) {
        try { ppFile(id).delete() } catch (e: Exception) { }
        try { File(Downloader.partFile(id).parentFile, "$id.pp.json").delete() } catch (e: Exception) { }
    }

    private fun stateFile(id: Int): File = File(Downloader.partFile(id).parentFile, "$id.pp.json")

    /** True when a parts download of this id can continue (state file + data file). */
    fun hasState(id: Int): Boolean = try { stateFile(id).exists() && ppFile(id).exists() } catch (e: Exception) { false }

    // ---------- control ----------

    fun abort(item: DlItem) { try { runs[item.id]?.halt() } catch (e: Exception) { } }

    /** 0.0.359 (S3): fraction 0..1 of every part of a RUNNING parts download. null = this id is not running in parts mode. */
    fun snapshot(id: Int): List<Double>? {
        val r = runs[id] ?: return null
        return List(r.n) { i ->
            val len = r.ends[i] - r.starts[i] + 1L
            if (len <= 0L) 1.0 else (r.done.get(i).toDouble() / len).coerceIn(0.0, 1.0)
        }
    }

    /** 0.0.359 (S3): smoothed bytes per second of a running parts download (0 = unknown). */
    fun speed(id: Int): Long = runs[id]?.speed ?: 0L

    private fun userStop(item: DlItem): Boolean = item.paused || item.state != "in_progress"

    private fun reserve(want: Int): Int {
        while (true) {
            val cur = used.get()
            val k = minOf(want, MAX_WORKERS - cur)
            if (k <= 0) return 0
            if (used.compareAndSet(cur, cur + k)) return k
        }
    }

    /** 0.0.368 (F1): waits ms in 250 ms steps; false = stopped early because the user paused / cancelled. */
    private fun waitMs(item: DlItem, ms: Long): Boolean {
        var left = ms
        while (left > 0L) {
            if (userStop(item)) return false
            val step = minOf(250L, left)
            try { Thread.sleep(step) } catch (ie: InterruptedException) { return false }
            left -= step
        }
        return !userStop(item)
    }

    private fun skip(why: String): Boolean {
        Diag.i(TAG, "single connection: $why")
        return false
    }

    // ---------- entry ----------

    fun run(item: DlItem): Boolean {
        if (!enabled()) return false
        if (item.pn == 1) return false                   // 0.0.372 (N4): "Single connection" chosen for this download
        val u = item.url
        if (!(u.startsWith("http://", true) || u.startsWith("https://", true)) || item.method == "POST") return false
        return try {
            runParts(item)
        } catch (k: KeepIo) {
            throw k.io                                   // network lost: state kept, item becomes interrupted + resumable
        } catch (e: Exception) {
            if (userStop(item)) throw IOException("dlparts stopped")
            Diag.w(TAG, "fallback to single connection: " + e.javaClass.simpleName + " " + (e.message ?: "").take(80))
            false
        }
    }

    // ---------- state file (S2) ----------

    private class St(val total: Long, val vld: String, val starts: LongArray, val ends: LongArray, val done: LongArray)

    /** Reads ID.pp.json. null = missing / broken / does not fit this item (caller starts again from 0). */
    private fun readState(item: DlItem): St? {
        try {
            val f = stateFile(item.id)
            if (!f.exists() || !ppFile(item.id).exists()) return null
            val o = JSONObject(f.readText())
            if (o.optInt("v") != 1 || o.optString("url") != item.url) return null
            val total = o.optLong("total", -1L)
            val arr = o.optJSONArray("parts") ?: return null
            val n = arr.length()
            if (total <= 0L || n < 1 || n > MAX_WORKERS * 4 || ppFile(item.id).length() != total) return null
            val s = LongArray(n); val e = LongArray(n); val d = LongArray(n)
            for (i in 0 until n) {
                val p = arr.getJSONObject(i)
                s[i] = p.getLong("s"); e[i] = p.getLong("e"); d[i] = p.getLong("d")
                if (s[i] > e[i] || d[i] < 0L || d[i] > e[i] - s[i] + 1L) return null
                if (i == 0 && s[i] != 0L) return null
                if (i > 0 && s[i] != e[i - 1] + 1L) return null
            }
            if (e[n - 1] != total - 1L) return null
            return St(total, o.optString("validator"), s, e, d)
        } catch (ex: Exception) { return null }
    }

    /** Writes the state AFTER the bytes were written (done counters only grow after ch.write). tmp + rename = never half a file. */
    private fun saveState(r: Run, vld: String) {
        try {
            val arr = JSONArray()
            for (i in 0 until r.n) arr.put(JSONObject().put("s", r.starts[i]).put("e", r.ends[i]).put("d", r.done.get(i)))
            val o = JSONObject().put("v", 1).put("url", r.item.url).put("total", r.total).put("validator", vld).put("n", r.n).put("parts", arr)
            val f = stateFile(r.item.id)
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(o.toString())
            if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
        } catch (e: Exception) { }
    }

    private fun probe(item: DlItem): Probe {
        val pr = Downloader.open(item, 0L, 0L)
        try {
            val total = pr.header("Content-Range")?.substringAfter('/', "")?.trim()?.toLongOrNull() ?: -1L
            return Probe(
                pr.code, total,
                pr.header("ETag") ?: pr.header("Last-Modified") ?: "",
                pr.header("Content-Type")?.substringBefore(';')?.trim()?.lowercase() ?: "",
                pr.header("Content-Disposition"),
                pr.request.url.toString(),
                pr.header("Content-Encoding") ?: ""
            )
        } finally { pr.close() }
    }

    private fun runParts(item: DlItem): Boolean {
        val part = Downloader.partFile(item.id)
        if (part.exists() && part.length() > 0L) { clean(item.id); return skip("old .part exists, old resume goes on") }
        var st = readState(item)                         // S2: saved parts of an earlier run (pause / crash / network loss)

        // ---- probe: one request for bytes 0-0 tells size + Range support (+ If-Range with the old validator) ----
        var pb = probe(item)
        if (pb.code == 200 && item.validator.isNotEmpty()) {
            // server says the file changed since the first run: forget old parts + validator, ask again
            clean(item.id); st = null
            DownloadsApi.change(item) { this.validator = ""; this.received = 0L }
            pb = probe(item)
        }
        if (userStop(item)) throw IOException("dlparts stopped")
        if (pb.code != 206) { clean(item.id); return skip("no Range support (HTTP ${pb.code})") }
        if (pb.enc.isNotEmpty() && !pb.enc.equals("identity", true)) { clean(item.id); return skip("Content-Encoding present") }
        val total = pb.total
        if (total <= 0L) { clean(item.id); return skip("size unknown") }
        val vld = if (pb.vld.isNotEmpty() && !pb.vld.startsWith("W/")) pb.vld else ""
        if (st != null && (st.total != total || st.vld != vld)) {
            Diag.i(TAG, "saved parts do not fit the server file: start again")
            clean(item.id); st = null
        }
        if (st == null && total < minBytes()) { clean(item.id); return skip("file smaller than the minimum size") }
        val resuming = st != null

        val want = if (st != null) st.starts.size else nParts(item)
        val k = reserve(want)
        if (k < 1 || (!resuming && k < 2)) {
            if (k > 0) used.addAndGet(-k)
            if (resuming) throw KeepIo(IOException("too many parallel downloads"))   // state stays, Resume later
            return skip("worker cap reached")
        }

        var ok = false
        var keep = false
        var raf: RandomAccessFile? = null
        var run: Run? = null
        try {
            if (!resuming) {
                clean(item.id)
                val free = StatFs(DownloadsApi.app.filesDir.path).availableBytes
                if (free < total + 32L * MB) return skip("not enough free space")
            }
            val tot = total
            val ct2 = pb.ct
            val cd2 = pb.cd
            val fu2 = pb.fu
            DownloadsApi.change(item) {
                this.finalUrl = fu2
                this.total = tot
                if (vld.isNotEmpty()) this.validator = vld
                if (this.mime.isEmpty() && ct2.isNotEmpty() && ct2 != "application/octet-stream") this.mime = ct2
                this.canResume = true
                if (this.rel.isEmpty()) this.rel = Downloader.nameFromResponse(fu2, cd2, ct2)
            }

            val r: Run
            if (st != null) {
                r = Run(item, total, st.starts.size)
                for (i in 0 until r.n) { r.starts[i] = st.starts[i]; r.ends[i] = st.ends[i]; r.done.set(i, st.done[i]) }
            } else {
                r = Run(item, total, k)
                val size = (total + k - 1) / k
                for (i in 0 until k) {
                    r.starts[i] = i * size
                    r.ends[i] = minOf(total - 1, (i + 1) * size - 1)
                }
            }
            run = r
            val f = RandomAccessFile(ppFile(item.id), "rw")
            raf = f
            if (f.length() != total) f.setLength(total)
            val ch = f.channel
            runs[item.id] = r
            item.received = r.sum()
            r.tick(SystemClock.elapsedRealtime())
            Diag.i(TAG, (if (resuming) "resume " else "start ") + r.n + " parts with $k connections, ${total / 1024} KB, have ${r.sum() / 1024} KB")

            var w = k
            var bad = 0
            var rl = 0                                   // 0.0.368: rate-limit (429 / 503) rounds in a row without progress
            var saves = 0
            var lastSave = SystemClock.elapsedRealtime()
            if (!resuming) saveState(r, vld)
            while (true) {
                r.reset()
                if (userStop(item)) r.halt()
                val roundStart = r.sum()
                val ww = maxOf(1, minOf(w, r.n))
                // 0.0.368 (F1) slow start: 2 connections first, +1 after each part that finished without error (up to ww)
                val ramp = minOf(ww, 2)
                val active = AtomicInteger(0)
                var started = 0
                fun launch() {
                    started++
                    active.incrementAndGet()
                    exec.execute {
                        try { worker(r, ch) } catch (t: Throwable) { r.fail("worker " + t.javaClass.simpleName, HARD) } finally { active.decrementAndGet() }
                    }
                }
                for (i in 0 until ramp) launch()
                while (active.get() > 0) {
                    try { Thread.sleep(100L) } catch (ie: InterruptedException) { }
                    val allowed = minOf(ww, ramp + r.fin.get())
                    while (started < allowed && !r.stop && r.err.get() == null && r.next.get() < r.n) launch()
                    item.received = r.sum()
                    r.tick(SystemClock.elapsedRealtime())
                    if (userStop(item)) r.halt()
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastSave >= 1000L) {
                        lastSave = now
                        if (++saves % 5 == 0) { try { ch.force(false) } catch (e: Exception) { } }
                        saveState(r, vld)
                    }
                }
                item.received = r.sum()
                if (userStop(item)) {
                    // pause keeps the parts (Resume continues); cancel / error: finally{} cleans
                    if (item.paused && item.state == "in_progress") {
                        try { ch.force(false) } catch (e: Exception) { }
                        saveState(r, vld)
                        keep = true
                    }
                    throw IOException("dlparts stopped")
                }
                val why = r.err.get() ?: break
                when (r.kind) {
                    NET -> {
                        try { ch.force(false) } catch (e: Exception) { }
                        saveState(r, vld)
                        keep = true
                        Diag.w(TAG, "network problem, parts kept for Resume: $why")
                        throw KeepIo(r.netEx ?: IOException(why))
                    }
                    SOFT -> {
                        val c = r.code
                        if (c == 429 || c == 503) {
                            // 0.0.368 (F1): rate limit. Wait (Retry-After or 2 / 4 / 8 s), halve connections (min 1), keep progress.
                            if (r.sum() > roundStart) rl = 0
                            rl++
                            val raMs = if (r.ra > 0L) minOf(r.ra, 30L) * 1000L else 0L
                            if (rl > 3) {
                                val cool = if (raMs > 0L) raMs else 5000L
                                Diag.w(TAG, "fallback to single connection after ${cool / 1000} s cool-down: $why (HTTP $c)")
                                if (!waitMs(item, cool)) continue      // paused / cancelled: loop top handles it
                                return false
                            }
                            w = maxOf(1, w / 2)
                            val wt = if (raMs > 0L) raMs else minOf(8000L, 1000L shl rl)
                            Diag.w(TAG, "server limits connections ($why): now $w connections, wait ${wt / 1000} s")
                            waitMs(item, wt)
                        } else {
                            bad++
                            if (bad >= 2 || w <= 2) { Diag.w(TAG, "fallback to single connection: $why"); return false }
                            w = maxOf(2, w / 2)
                            Diag.w(TAG, "server limits connections ($why): now $w connections")
                            try { Thread.sleep(1000L) } catch (ie: InterruptedException) { }
                        }
                    }
                    else -> { Diag.w(TAG, "fallback to single connection: $why"); return false }
                }
            }
            try { f.close() } catch (e: Exception) { }
            raf = null
            if (r.sum() != total) return skip("parts incomplete")
            if (part.exists()) part.delete()
            if (!ppFile(item.id).renameTo(part)) return skip("rename failed")
            try { stateFile(item.id).delete() } catch (e: Exception) { }
            val done = total
            DownloadsApi.change(item) { this.total = done; this.received = done }
            ok = true
            Diag.i(TAG, "done ${r.n} parts")
            return true
        } finally {
            runs.remove(item.id)
            used.addAndGet(-k)
            try { run?.halt() } catch (e: Exception) { }
            try { raf?.close() } catch (e: Exception) { }
            if (!ok && !keep) clean(item.id)
        }
    }

    /** A worker takes the next unfinished part until none is left. */
    private fun worker(r: Run, ch: FileChannel) {
        while (!r.stop) {
            val i = r.next.getAndIncrement()
            if (i >= r.n) return
            work(r, i, ch)
        }
    }

    /** One part: bytes starts[i]..ends[i]. Errors retry 3 times from the byte already written; the kind of failure decides what happens next. */
    private fun work(r: Run, i: Int, ch: FileChannel) {
        var tries = 0
        while (!r.stop) {
            val from = r.starts[i] + r.done.get(i)
            val to = r.ends[i]
            if (from > to) return
            var resp: Response? = null
            try {
                resp = Downloader.open(r.item, from, to) { c -> r.calls.add(c); if (r.stop) c.cancel() }
                if (r.stop) return
                val code = resp.code
                if (code != 206) {
                    // 200 / 403 / 429 = server ignores Range or limits connections: fewer connections (SOFT); 401 / 416 = parts mode cannot work (HARD)
                    if (code == 429 || code == 503) {
                        val ra = resp.header("Retry-After")?.trim()?.toLongOrNull() ?: 0L
                        r.fail("part $i HTTP $code", SOFT, null, code, ra); return
                    }
                    if (code == 200 || code == 403) { r.fail("part $i HTTP $code", SOFT, null, code); return }
                    if (code == 401 || code == 416) { r.fail("part $i HTTP $code", HARD); return }
                    throw HttpStatus(code)
                }
                val cr = resp.header("Content-Range") ?: ""
                if (!cr.startsWith("bytes $from-")) { r.fail("part $i bad Content-Range", HARD); return }
                if (cr.substringAfter('/', "").trim().toLongOrNull() != r.total) { r.fail("part $i size changed", HARD); return }
                val body = resp.body ?: throw IOException("no body")
                val src = body.byteStream()
                val buf = ByteArray(65536)
                var pos = from
                while (pos <= to) {
                    if (r.stop) return
                    val want = minOf(buf.size.toLong(), to - pos + 1L).toInt()
                    val n = src.read(buf, 0, want)
                    if (n < 0) break
                    val bb = ByteBuffer.wrap(buf, 0, n)
                    var at = pos
                    while (bb.hasRemaining()) at += ch.write(bb, at)
                    pos += n
                    r.done.addAndGet(i, n.toLong())
                    DlLimit.throttle(n) { r.stop }               // 0.0.370 (N2): shared speed limit
                    tries = 0                              // progress made: a later drop gets fresh retries
                }
                if (pos <= to) throw IOException("short part")
                r.fin.incrementAndGet()
                return
            } catch (e: Exception) {
                if (r.stop) return
                if (++tries > 3) {
                    val why = "part $i " + e.javaClass.simpleName + " " + (e.message ?: "").take(60)
                    when {
                        e is HttpStatus && e.code >= 500 -> r.fail(why, SOFT, null, e.code)
                        e is HttpStatus -> r.fail(why, HARD)
                        e is IOException -> r.fail(why, NET, e)
                        else -> r.fail(why, HARD)
                    }
                    return
                }
                try { Thread.sleep(600L * tries) } catch (ie: InterruptedException) { return }
            } finally {
                try { resp?.close() } catch (e: Exception) { }
            }
        }
    }
}
