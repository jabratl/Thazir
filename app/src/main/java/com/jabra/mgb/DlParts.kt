package com.jabra.mgb

import android.content.Context
import android.os.StatFs
import okhttp3.Call
import okhttp3.Response
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
 * Switch pref dp_on is OFF by default. S1 has no resume: pause / process death = start again from 0 (S2).
 */
object DlParts {
    private const val TAG = "dlparts"
    private const val MAX_WORKERS = 16          // global cap over all running downloads
    private const val MB = 1024L * 1024L
    private val N_ALLOWED = intArrayOf(2, 4, 6, 8, 16)

    private val used = AtomicInteger(0)
    private val runs = ConcurrentHashMap<Int, Run>()
    private val exec: ExecutorService by lazy {
        Executors.newCachedThreadPool(ThreadFactory { r -> Thread(r, "dlparts").also { it.isDaemon = true } })
    }

    /** Live state of one parts download. */
    private class Run(val item: DlItem, val total: Long, val n: Int) {
        val starts = LongArray(n)
        val ends = LongArray(n)
        val done = AtomicLongArray(n)
        val calls: MutableSet<Call> = ConcurrentHashMap.newKeySet<Call>()
        @Volatile var stop = false
        val err = AtomicReference<String?>(null)

        fun halt() {
            stop = true
            for (c in calls) { try { c.cancel() } catch (e: Exception) { } }
        }
        fun fail(why: String) { err.compareAndSet(null, why); halt() }
        fun sum(): Long { var t = 0L; for (i in 0 until n) t += done.get(i); return t }
    }

    // ---------- prefs (SharedPreferences mgb_ui) ----------

    private fun sp() = DownloadsApi.app.getSharedPreferences("mgb_ui", Context.MODE_PRIVATE)

    /** Default OFF (Rule 0). Flip later = change false to true here. */
    fun enabled(): Boolean = try { sp().getBoolean("dp_on", false) } catch (e: Exception) { false }

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

    // ---------- control ----------

    fun abort(item: DlItem) { try { runs[item.id]?.halt() } catch (e: Exception) { } }

    private fun userStop(item: DlItem): Boolean = item.paused || item.state != "in_progress"

    private fun reserve(want: Int): Int {
        while (true) {
            val cur = used.get()
            val k = minOf(want, MAX_WORKERS - cur)
            if (k <= 0) return 0
            if (used.compareAndSet(cur, cur + k)) return k
        }
    }

    private fun skip(why: String): Boolean {
        Diag.i(TAG, "single connection: $why")
        return false
    }

    // ---------- entry ----------

    fun run(item: DlItem): Boolean {
        if (!enabled()) return false
        val u = item.url
        if (!(u.startsWith("http://", true) || u.startsWith("https://", true)) || item.method == "POST") return false
        return try {
            runParts(item)
        } catch (e: Exception) {
            if (userStop(item)) throw IOException("dlparts stopped")
            Diag.w(TAG, "fallback to single connection: " + e.javaClass.simpleName + " " + (e.message ?: "").take(80))
            false
        }
    }

    private fun runParts(item: DlItem): Boolean {
        val part = Downloader.partFile(item.id)
        if (part.exists() && part.length() > 0L) return skip("old .part exists, old resume goes on")
        clean(item.id)                                   // stale leftovers of this id: always start from 0 in S1
        val pp = ppFile(item.id)

        // ---- probe: one request for bytes 0-0 tells size + Range support ----
        var total = -1L
        var vld = ""
        var ct = ""
        var cd: String? = null
        var fu = item.url
        val pr = Downloader.open(item, 0L, 0L)
        try {
            if (pr.code != 206) return skip("no Range support (HTTP ${pr.code})")
            val enc = pr.header("Content-Encoding") ?: ""
            if (enc.isNotEmpty() && !enc.equals("identity", true)) return skip("Content-Encoding present")
            total = pr.header("Content-Range")?.substringAfter('/', "")?.trim()?.toLongOrNull() ?: -1L
            vld = pr.header("ETag") ?: pr.header("Last-Modified") ?: ""
            ct = pr.header("Content-Type")?.substringBefore(';')?.trim()?.lowercase() ?: ""
            cd = pr.header("Content-Disposition")
            fu = pr.request.url.toString()
        } finally {
            pr.close()
        }
        if (userStop(item)) throw IOException("dlparts stopped")
        if (total <= 0L) return skip("size unknown")
        if (total < minBytes()) return skip("file smaller than the minimum size")

        val k = reserve(nParts())
        if (k < 2) {
            if (k > 0) used.addAndGet(-k)
            return skip("worker cap reached")
        }

        var ok = false
        var raf: RandomAccessFile? = null
        var run: Run? = null
        try {
            val free = StatFs(DownloadsApi.app.filesDir.path).availableBytes
            if (free < total + 32L * MB) return skip("not enough free space")
            val tot = total
            val v2 = vld
            val ct2 = ct
            val cd2 = cd
            val fu2 = fu
            DownloadsApi.change(item) {
                this.finalUrl = fu2
                this.total = tot
                this.received = 0L
                if (v2.isNotEmpty() && !v2.startsWith("W/")) this.validator = v2
                if (this.mime.isEmpty() && ct2.isNotEmpty() && ct2 != "application/octet-stream") this.mime = ct2
                this.canResume = true
                if (this.rel.isEmpty()) this.rel = Downloader.nameFromResponse(fu2, cd2, ct2)
            }

            val r = Run(item, total, k)
            run = r
            val size = (total + k - 1) / k
            for (i in 0 until k) {
                r.starts[i] = i * size
                r.ends[i] = minOf(total - 1, (i + 1) * size - 1)
            }
            val f = RandomAccessFile(pp, "rw")
            raf = f
            f.setLength(total)
            val ch = f.channel
            runs[item.id] = r
            if (userStop(item)) r.halt()
            Diag.i(TAG, "start $k parts, ${total / 1024} KB")

            val latch = CountDownLatch(k)
            for (i in 0 until k) {
                exec.execute {
                    try { work(r, i, ch) } catch (t: Throwable) { r.fail("worker " + t.javaClass.simpleName) } finally { latch.countDown() }
                }
            }
            while (!latch.await(250, TimeUnit.MILLISECONDS)) {
                item.received = r.sum()
                if (userStop(item)) r.halt()
            }
            item.received = r.sum()
            try { f.close() } catch (e: Exception) { }
            raf = null

            if (userStop(item)) throw IOException("dlparts stopped")
            val why = r.err.get()
            if (why != null) {
                Diag.w(TAG, "fallback to single connection: $why")
                return false
            }
            if (r.sum() != total) return skip("parts incomplete")
            if (part.exists()) part.delete()
            if (!pp.renameTo(part)) return skip("rename failed")
            val done = total
            DownloadsApi.change(item) { this.total = done; this.received = done }
            ok = true
            Diag.i(TAG, "done $k parts")
            return true
        } finally {
            runs.remove(item.id)
            used.addAndGet(-k)
            if (!ok) {
                try { run?.halt() } catch (e: Exception) { }
                try { raf?.close() } catch (e: Exception) { }
                clean(item.id)
            }
        }
    }

    /** One part: bytes starts[i]..ends[i]. Network errors retry 3 times from the byte already written. */
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
                    // 200 = server ignores Range, 401 / 403 / 416 / 429 = refuses: parts mode does not work for this file
                    if (code == 200 || code == 401 || code == 403 || code == 416 || code == 429) { r.fail("part $i HTTP $code"); return }
                    throw IOException("HTTP $code")
                }
                val cr = resp.header("Content-Range") ?: ""
                if (!cr.startsWith("bytes $from-")) { r.fail("part $i bad Content-Range"); return }
                if (cr.substringAfter('/', "").trim().toLongOrNull() != r.total) { r.fail("part $i size changed"); return }
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
                }
                if (pos <= to) throw IOException("short part")
                return
            } catch (e: Exception) {
                if (r.stop) return
                if (++tries > 3) { r.fail("part $i " + e.javaClass.simpleName + " " + (e.message ?: "").take(60)); return }
                try { Thread.sleep(600L * tries) } catch (ie: InterruptedException) { return }
            } finally {
                try { resp?.close() } catch (e: Exception) { }
            }
        }
    }
}
