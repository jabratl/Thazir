package com.jabra.mgb

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** 0.0.314: shared state of the app update download (in memory; the dialog only watches it). */
object UpdState {
    const val IDLE = 0
    const val RUN = 1
    const val DONE = 2
    const val FAIL = 3
    @Volatile var state = IDLE
    @Volatile var name = ""
    @Volatile var done = 0L
    @Volatile var total = -1L
    @Volatile var msg = ""
    @Volatile var file: File? = null
    /** main thread only; set by the update dialog while it is open */
    var listener: (() -> Unit)? = null
    private val h = Handler(Looper.getMainLooper())

    fun push() { h.post { listener?.invoke() } }
    fun begin(n: String, size: Long) { name = n; done = 0L; total = size; msg = "Starting…"; file = null; state = RUN; push() }
    fun progress(d: Long, t: Long, m: String) { done = d; total = t; msg = m; push() }
    fun finish(f: File) { file = f; msg = "Downloaded"; state = DONE; push() }
    fun fail(m: String) { msg = m; state = FAIL; push() }
    fun idle() { state = IDLE; push() }
}

/**
 * 0.0.314: downloads the app update in a foreground service (type dataSync) with a CPU wake lock, so it keeps going
 * when the screen turns off, the dialog closes or Thazir goes to the background. Resumes from the .part file
 * after a lost connection (HTTP Range) and shows progress + Cancel in a notification.
 */
class UpdateService : Service() {
    private class Fatal(m: String) : Exception(m)
    private class Cancel : Exception()

    private var wl: PowerManager.WakeLock? = null
    private var wifi: WifiManager.WifiLock? = null
    @Volatile private var cancelled = false
    private var worker: Thread? = null
    private var lastNote = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACT_CANCEL) { cancelled = true; if (worker?.isAlive != true) stopSelf(); return START_NOT_STICKY }
        val url = intent?.getStringExtra("url")
        val name = intent?.getStringExtra("name")
        val size = intent?.getLongExtra("size", -1L) ?: -1L
        if (url.isNullOrEmpty() || name.isNullOrEmpty()) { stopSelf(); return START_NOT_STICKY }
        channels()
        try {
            ServiceCompat.startForeground(this, NOTE_RUN, note(0L, size, "Starting…"), if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0)
        } catch (e: Exception) {
            UpdState.fail("Could not start the download: ${e.message}"); stopSelf(); return START_NOT_STICKY
        }
        if (worker?.isAlive == true) return START_NOT_STICKY // already running
        cancelled = false
        locks(true)
        worker = Thread { run(url, name, size) }.also { it.start() }
        return START_NOT_STICKY
    }

    private fun run(url: String, name: String, size: Long) {
        val dir = File(cacheDir, "updates").apply { mkdirs() }
        val dest = File(dir, name)
        val part = File(dir, "$name.part")
        try {
            if (dest.exists() && (size <= 0 || dest.length() == size)) { done(dest); return }
            var attempt = 0
            while (true) {
                try {
                    fetch(url, size, part)
                    break
                } catch (e: Cancel) { throw e
                } catch (e: Fatal) { throw e
                } catch (e: Exception) {
                    if (cancelled) throw Cancel()
                    attempt++
                    if (attempt > 8) throw e
                    val busy = e.message?.startsWith("server busy") == true
                    UpdState.progress(UpdState.done, UpdState.total, (if (busy) "Server busy, retrying" else "Connection lost, retrying") + " ($attempt/8)…")
                    var w = 0
                    val waitMs = if (busy) minOf(4000 * attempt, 30000) else minOf(2000 * attempt, 15000)
                    while (w < waitMs) { if (cancelled) throw Cancel(); Thread.sleep(500); w += 500 }
                }
            }
            if (size > 0 && part.length() != size) { part.delete(); throw Fatal("incomplete download") }
            if (!part.renameTo(dest)) throw Fatal("could not save the file")
            done(dest)
        } catch (e: Cancel) {
            part.delete()
            UpdState.idle()
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        } catch (e: Exception) {
            // .part stays, so Retry continues where it stopped
            UpdState.fail(e.message ?: "download failed")
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            notifyDone("Update download stopped", "Open Thazir > Check for updates to continue.", null)
        } finally {
            locks(false)
            stopSelf()
        }
    }

    private fun fetch(url: String, size: Long, part: File) {
        var have = if (part.exists()) part.length() else 0L
        if (size > 0 && have > size) { part.delete(); have = 0L }
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 15_000
            c.readTimeout = 30_000
            c.instanceFollowRedirects = true
            c.setRequestProperty("User-Agent", "MGB-updater")
            c.setRequestProperty("Accept", "application/octet-stream")
            if (have > 0) c.setRequestProperty("Range", "bytes=$have-")
            val code = c.responseCode
            if (code == 416) { part.delete(); throw IOException("restarting") }
            // 0.0.339: GitHub / CDN hiccups (502 / 503 / 504, 429, 408) are temporary -> retry, not Fatal
            if (code == 408 || code == 429 || code in 500..599) throw IOException("server busy (HTTP $code)")
            if (code != 200 && code != 206) throw Fatal("download HTTP $code")
            val append = code == 206 && have > 0
            var got = if (append) have else 0L
            val total = if (size > 0) size else if (c.contentLengthLong > 0) got + c.contentLengthLong else -1L
            c.inputStream.use { inp ->
                FileOutputStream(part, append).use { out ->
                    val buf = ByteArray(64 * 1024)
                    var last = 0L
                    while (true) {
                        if (cancelled) throw Cancel()
                        val n = inp.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        got += n
                        val now = System.currentTimeMillis()
                        if (now - last > 400) {
                            last = now
                            val t = "Downloading ${Util.fmtBytes(got)}" + if (total > 0) " / ${Util.fmtBytes(total)}" else ""
                            UpdState.progress(got, total, t)
                            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(NOTE_RUN, note(got, total, t))
                        }
                    }
                }
            }
        } finally {
            c.disconnect()
        }
    }

    private fun done(dest: File) {
        UpdState.finish(dest)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        notifyDone("Update downloaded", "Tap to install Thazir", installIntent(this, dest))
    }

    private fun note(done: Long, total: Long, text: String): Notification {
        val open = PendingIntent.getActivity(this, 5, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val cancel = PendingIntent.getService(this, 6, Intent(this, UpdateService::class.java).setAction(ACT_CANCEL), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val b = NotificationCompat.Builder(this, CH_RUN)
            .setSmallIcon(R.drawable.ic_download).setContentTitle("Downloading Thazir update").setContentText(text)
            .setContentIntent(open).setOngoing(true).setSilent(true).setOnlyAlertOnce(true)
            .addAction(0, "Cancel", cancel)
        if (total > 0) b.setProgress(100, (done * 100 / total).toInt().coerceIn(0, 100), false) else b.setProgress(0, 0, true)
        return b.build()
    }

    private fun notifyDone(title: String, text: String, tap: Intent?) {
        val b = NotificationCompat.Builder(this, CH_DONE)
            .setSmallIcon(R.drawable.ic_download).setContentTitle(title).setContentText(text).setAutoCancel(true)
        val pi = if (tap != null) PendingIntent.getActivity(this, 7, tap, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        else PendingIntent.getActivity(this, 7, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        b.setContentIntent(pi)
        try { (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(NOTE_DONE, b.build()) } catch (_: Exception) { }
    }

    private fun channels() {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CH_RUN) == null) nm.createNotificationChannel(NotificationChannel(CH_RUN, "App update download", NotificationManager.IMPORTANCE_LOW))
        if (nm.getNotificationChannel(CH_DONE) == null) nm.createNotificationChannel(NotificationChannel(CH_DONE, "App update ready", NotificationManager.IMPORTANCE_DEFAULT))
    }

    @Suppress("DEPRECATION")
    private fun locks(on: Boolean) {
        try {
            if (on) {
                if (wl == null) wl = (getSystemService(POWER_SERVICE) as PowerManager).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "mgb:update").apply { setReferenceCounted(false) }
                wl?.acquire(30 * 60 * 1000L)
                if (wifi == null) wifi = (applicationContext.getSystemService(WIFI_SERVICE) as WifiManager)
                    .createWifiLock(if (Build.VERSION.SDK_INT >= 29) WifiManager.WIFI_MODE_FULL_LOW_LATENCY else WifiManager.WIFI_MODE_FULL_HIGH_PERF, "mgb:update").apply { setReferenceCounted(false) }
                wifi?.acquire()
            } else {
                if (wl?.isHeld == true) wl?.release()
                if (wifi?.isHeld == true) wifi?.release()
            }
        } catch (_: Exception) { }
    }

    override fun onDestroy() { locks(false); super.onDestroy() }

    companion object {
        private const val CH_RUN = "mgb_update_run"
        private const val CH_DONE = "mgb_update_done"
        private const val NOTE_RUN = 7011
        private const val NOTE_DONE = 7012
        private const val ACT_CANCEL = "com.jabra.mgb.UPDATE_CANCEL"

        fun start(a: Context, url: String, name: String, size: Long) {
            if (UpdState.state == UpdState.RUN) { if (UpdState.name != name) Util.toast(a, "Another update download is running"); return }
            UpdState.begin(name, size)
            try {
                ContextCompat.startForegroundService(a, Intent(a, UpdateService::class.java).putExtra("url", url).putExtra("name", name).putExtra("size", size))
            } catch (e: Exception) { UpdState.fail("Could not start the download: ${e.message}") }
        }

        fun cancel(a: Context) {
            try { a.startService(Intent(a, UpdateService::class.java).setAction(ACT_CANCEL)) } catch (_: Exception) { }
        }

        fun clearDone(a: Context) {
            try { (a.getSystemService(NOTIFICATION_SERVICE) as NotificationManager).cancel(NOTE_DONE) } catch (_: Exception) { }
        }

        /** Same rule as the dialog: ask for "install unknown apps" first, else open the package installer. */
        fun installIntent(a: Context, f: File): Intent {
            if (Build.VERSION.SDK_INT >= 26 && !a.packageManager.canRequestPackageInstalls())
                return Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${a.packageName}"))
            val uri = FileProvider.getUriForFile(a, "${a.packageName}.fileprovider", f)
            return Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }
}
