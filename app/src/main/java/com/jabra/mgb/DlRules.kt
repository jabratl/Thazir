package com.jabra.mgb

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.StatFs
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.json.JSONObject

/**
 * D2: download rules, checked ONLY before a download starts (EngineApi.send hook for start-job / batch-episodes).
 * Wi-Fi only, wait on metered data, wait on low battery (below 15 %, not charging), stop on low storage (below 500 MB).
 * A blocked job waits in memory, is re-checked every 15 s and sent when the rule passes. It is listed in
 * Scheduled downloads (Sched.open hook) as "Waiting for ...". A job that waits is lost when the app is closed.
 * Keys in mgb_ui (default OFF = no check at all): dr_wifi, dr_metered, dr_batt, dr_store.
 * Running downloads are not paused by these rules; Downloader / DownloadService are not touched.
 */
object DlRules {
    private const val PREFS = "mgb_ui"
    private const val LOW_BATT = 15
    private const val LOW_STORE = 500L * 1048576L
    private val KEYS = arrayOf("dr_wifi", "dr_metered", "dr_batt", "dr_store")
    private val NAMES = arrayOf("Wi-Fi only", "Wait on metered data", "Wait when battery is below 15 %", "Stop when storage is below 500 MB")

    private class Wait(val msg: JSONObject, val label: String, var why: String)

    private val waiting = ArrayList<Wait>()
    private var ctx: Context? = null
    private var releasing = false
    private var polling = false
    private val h = Handler(Looper.getMainLooper())

    fun init(c: Context) { ctx = c.applicationContext }

    private fun p(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun anyOn(c: Context): Boolean = KEYS.any { p(c).getBoolean(it, false) }

    fun summary(c: Context): String = KEYS.count { p(c).getBoolean(it, false) }.let { if (it == 0) "OFF" else "$it rule(s)" }

    /** null = go. Otherwise (text, stop): stop = true means the job is dropped, false = it waits. */
    private fun blocker(c: Context): Pair<String, Boolean>? {
        val pr = p(c)
        if (pr.getBoolean("dr_store", false)) {
            val free = try { StatFs(Environment.getExternalStorageDirectory().path).availableBytes } catch (e: Exception) { Long.MAX_VALUE }
            if (free < LOW_STORE) return Pair("Not enough free storage (below 500 MB)", true)
        }
        val cm = c.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        if (pr.getBoolean("dr_wifi", false)) {
            val caps = try { cm?.getNetworkCapabilities(cm.activeNetwork) } catch (e: Exception) { null }
            val ok = caps != null && (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))
            if (!ok) return Pair("Waiting for Wi-Fi", false)
        }
        if (pr.getBoolean("dr_metered", false) && cm?.isActiveNetworkMetered == true) return Pair("Waiting for an unmetered network", false)
        if (pr.getBoolean("dr_batt", false)) {
            val b = c.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            if (b != null) {
                val lv = b.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val sc = b.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
                val st = b.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                val charging = st == BatteryManager.BATTERY_STATUS_CHARGING || st == BatteryManager.BATTERY_STATUS_FULL
                if (lv >= 0 && sc > 0 && lv * 100 / sc < LOW_BATT && !charging) return Pair("Waiting for battery (charge the phone)", false)
            }
        }
        return null
    }

    /** 0.0.371 (N3): true = no rule blocks a download right now (used by DlAutoResume). */
    fun canRun(c: Context): Boolean = try { blocker(c) == null } catch (e: Exception) { true }

    /** Hook in EngineApi.send. true = the message was taken (held or dropped), the caller must not send it. */
    fun hold(msg: JSONObject): Boolean {
        val c = ctx ?: return false
        if (releasing) return false
        val t = msg.optString("type")
        if (t != "start-job" && t != "batch-episodes") return false
        if (!anyOn(c)) return false
        val b = blocker(c) ?: return false
        if (b.second) { Util.toast(c, b.first + ". Download not started."); return true }
        val n = msg.optJSONArray("episodes")?.length() ?: 0
        waiting.add(Wait(msg, if (n > 0) "$n episode(s)" else "1 download", b.first))
        Util.toast(c, b.first + ". The download starts by itself.")
        poll()
        return true
    }

    private fun send(w: Wait) {
        releasing = true
        try { EngineApi.send(w.msg) } finally { releasing = false }
    }

    private fun poll() {
        if (polling) return
        polling = true
        h.postDelayed(object : Runnable {
            override fun run() {
                val c = ctx
                if (c != null) {
                    val it = waiting.iterator()
                    while (it.hasNext()) {
                        val w = it.next()
                        val b = blocker(c)
                        if (b == null) { send(w); it.remove() }
                        else if (b.second) { Util.toast(c, b.first + ". A waiting download was removed."); it.remove() }
                        else w.why = b.first
                    }
                }
                if (waiting.isEmpty()) polling = false else h.postDelayed(this, 15000)
            }
        }, 15000)
    }

    /** Hook in Sched.open: adds the waiting jobs to the list. */
    fun listInto(act: Activity, names: MutableList<String>, runs: MutableList<() -> Unit>, again: () -> Unit) {
        for (w in ArrayList(waiting)) {
            names.add(w.why + "   " + w.label)
            runs.add {
                MaterialAlertDialogBuilder(act).setTitle(w.why).setMessage(w.label)
                    .setPositiveButton("Start now") { _, _ -> waiting.remove(w); send(w); again() }
                    .setNeutralButton("Remove") { _, _ -> waiting.remove(w); again() }
                    .setNegativeButton("Back") { _, _ -> again() }.show()
            }
        }
    }

    fun dialog(act: Activity, done: () -> Unit) {
        val pr = p(act)
        val on = BooleanArray(KEYS.size) { pr.getBoolean(KEYS[it], false) }
        MaterialAlertDialogBuilder(act).setTitle("Download rules")
            .setMultiChoiceItems(NAMES, on) { _, i, v -> pr.edit().putBoolean(KEYS[i], v).apply() }
            .setPositiveButton("Done") { _, _ -> done() }
            .setOnCancelListener { done() }
            .show()
    }
}
