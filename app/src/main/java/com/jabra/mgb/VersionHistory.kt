package com.jabra.mgb

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * 0.0.334: Settings > About > Version history. Lists the last releases of BuildConfig.GH_REPO (tag, date, notes) and
 * helps to revert: copies the release tag and opens the "Build APK" page on GitHub (Run workflow > revert_to).
 * The app never builds or installs anything by itself here; the revert build is made by the GitHub workflow, then
 * "Check for updates" installs it like any update. GitHub cannot pre-fill the workflow input from a link, so the tag
 * is copied to the clipboard for pasting. Repo must be public (same as the updater).
 */
object VersionHistory {
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    private class Rel(val tag: String, val build: Int, val date: String, val notes: String)

    fun actionsUrl(): String = "https://github.com/${BuildConfig.GH_REPO}/actions/workflows/build.yml"

    fun open(act: Activity) {
        if (BuildConfig.GH_REPO.isEmpty()) {
            Util.toast(act, "Update source is not set in this build")
            return
        }
        Util.toast(act, "Loading versions…")
        io.execute {
            val res: Any = try { fetch() } catch (e: Exception) { e }
            main.post {
                if (act.isFinishing || act.isDestroyed) return@post
                when (res) {
                    is List<*> -> showList(act, res.filterIsInstance<Rel>())
                    is Exception -> Util.toast(act, "Could not load versions: ${res.message}")
                }
            }
        }
    }

    private fun fetch(): List<Rel> {
        val c = URL("https://api.github.com/repos/${BuildConfig.GH_REPO}/releases?per_page=20").openConnection() as HttpURLConnection
        c.connectTimeout = 15_000
        c.readTimeout = 30_000
        c.setRequestProperty("User-Agent", "MGB-updater")
        c.setRequestProperty("Accept", "application/vnd.github+json")
        try {
            when (val code = c.responseCode) {
                200 -> {}
                404 -> throw Exception("no releases found (repo must be public)")
                403 -> throw Exception("GitHub rate limit, try again later")
                else -> throw Exception("GitHub HTTP $code")
            }
            val arr = JSONArray(c.inputStream.bufferedReader().use { it.readText() })
            val out = ArrayList<Rel>()
            for (i in 0 until arr.length()) {
                val j = arr.getJSONObject(i)
                if (j.optBoolean("draft")) continue
                val tag = j.optString("tag_name")
                val build = Regex("-b(\\d+)$").find(tag)?.groupValues?.get(1)?.toIntOrNull() ?: continue
                val date = j.optString("published_at").take(10)
                val notes = j.optString("body").trim().take(1200)
                out.add(Rel(tag, build, date, notes))
            }
            out.sortByDescending { it.build }
            return out
        } finally {
            c.disconnect()
        }
    }

    private fun running(): Int = ExtUpdater.currentBuild()

    private fun showList(act: Activity, list: List<Rel>) {
        if (list.isEmpty()) {
            Util.toast(act, "No releases found")
            return
        }
        val labels = Array(list.size) { k ->
            val r = list[k]
            var s = r.tag + "  •  " + r.date
            if (r.notes.startsWith("REVERT:")) s += "  (revert)"
            if (r.build == running()) s += "  ◀ running now"
            s
        }
        MaterialAlertDialogBuilder(act)
            .setTitle("Version history")
            .setItems(labels) { _, which -> showOne(act, list[which], list) }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun showOne(act: Activity, r: Rel, list: List<Rel>) {
        val isRunning = r.build == running()
        val msg = StringBuilder()
        msg.append(r.tag).append("  •  ").append(r.date)
        if (isRunning) msg.append("\nThis is the version running now.")
        msg.append("\n\n").append(if (r.notes.isBlank()) "No notes." else r.notes)
        val b = MaterialAlertDialogBuilder(act)
            .setTitle("Build ${r.build}")
            .setMessage(msg.toString())
            .setNegativeButton("Back") { _, _ -> showList(act, list) }
        if (!isRunning && DevMode.on(act)) b.setPositiveButton(if (r.build < running()) "Revert to this" else "Use this version") { _, _ -> confirm(act, r) }
        b.show()
    }

    private fun confirm(act: Activity, r: Rel) {
        MaterialAlertDialogBuilder(act)
            .setTitle("Revert to ${r.tag}")
            .setMessage(
                "1. The tag is copied.\n" +
                    "2. GitHub opens: Run workflow, paste the tag into revert_to, Run.\n" +
                    "3. When the build is green, come back: Settings > About > Check for updates > Update.\n\n" +
                    "Needs a GitHub login with write access. Tip: make a Backup first."
            )
            .setPositiveButton("Copy tag and open GitHub") { _, _ -> copyAndOpen(act, r.tag) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Copies [text] (tag or "previous") and opens the Build APK workflow page. */
    fun copyAndOpen(act: Activity, text: String) {
        try {
            (act.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                .setPrimaryClip(ClipData.newPlainText("MGB revert", text))
            Util.toast(act, "Copied: $text")
        } catch (_: Exception) { }
        try {
            act.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(actionsUrl())))
        } catch (_: Exception) {
            Util.toast(act, "No app can open the link")
        }
    }
}

/**
 * 0.0.334: "Developer mode" switch for the owner. Off by default: the revert button and the crash notification / dialog
 * only matter to someone with write access to the GitHub repo. Turn on / off: tap Settings > About > Version 7 times.
 */
object DevMode {
    private const val P = "mgb_dev"
    private var taps = 0
    private var last = 0L

    fun on(ctx: Context): Boolean =
        try { ctx.applicationContext.getSharedPreferences(P, Context.MODE_PRIVATE).getBoolean("on", false) } catch (_: Throwable) { false }

    fun tap(act: Activity) {
        val now = System.currentTimeMillis()
        if (now - last > 2000L) taps = 0
        last = now
        taps++
        if (taps >= 7) {
            taps = 0
            val v = !on(act)
            act.getSharedPreferences(P, Context.MODE_PRIVATE).edit().putBoolean("on", v).apply()
            Util.toast(act, if (v) "Developer mode ON" else "Developer mode OFF")
        } else if (taps >= 4) {
            Util.toast(act, "${7 - taps} more taps for developer mode")
        }
    }
}
