package com.jabra.mgb

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.json.JSONArray
import org.json.JSONObject

/**
 * 0.0.436 (S11): COMPATIBILITY SELF-TEST.
 * Adds the temporary extension assets/selftest/ through the normal ExtRegistry path (own fixed id, never touches the user's extensions),
 * its background calls every supported chrome.* API once and writes the result to its storage.local "selftest",
 * we read it (check every 0.5 s, max 20 s), then ALWAYS remove the extension + its storage (finally).
 * Result: Settings > Extensions > "Compatibility self-test" dialog and Report center section "Extension self-test".
 * Not covered (plan leftovers): content script / popup UI checks, tab based calls (scripting.executeScript, tabs.create), ports.
 */
object ExtSelfTest {
    private const val DIR = "selftest"
    private const val POLL_MS = 500L
    private const val MAX_POLLS = 40

    @Volatile private var running = false
    @Volatile private var lines: List<String>? = null
    @Volatile private var fails = 0
    private val main = Handler(Looper.getMainLooper())

    fun summary(): String = if (running) "Running..." else lines?.firstOrNull() ?: "Not run yet"

    /** Settings entry: dialog with progress, then the result lines. */
    fun start(act: Activity, onDone: () -> Unit) {
        val dlg = MaterialAlertDialogBuilder(act).setTitle("Compatibility self-test").setMessage("Running... (about 10 seconds)").setNegativeButton("Close", null).show()
        run(act) { res ->
            try { if (dlg.isShowing) dlg.setMessage(res.joinToString("\n") + "\n\nThe same list is in Menu > Report center > Extension self-test.") } catch (_: Throwable) { }
            onDone()
        }
    }

    /** Main thread. cb always runs once, on the main thread. */
    fun run(ctx: Context, cb: (List<String>) -> Unit) {
        if (running) { cb(listOf("Self-test is already running")); return }
        running = true
        val app = ctx.applicationContext
        var id: String? = null
        var fin = false
        fun finish(res: List<String>) {
            if (fin) return
            fin = true
            try {
                id?.let {
                    try { ExtBackground.stop(it, "self-test done") } catch (_: Throwable) { }
                    ExtRegistry.remove(it)
                    ChromeBridge.removeExtData(app, it)
                    ExtGaps.forget(it)
                }
                ExtApi.sync(app)
                ContentInjector.refresh(app)
            } catch (_: Throwable) { }
            lines = res
            running = false
            cb(res)
        }
        try {
            ExtRegistry.installAsset(app, DIR) { r ->
                val info = r.getOrNull()
                if (info == null) { finish(listOf("Self-test could not start: " + (r.exceptionOrNull()?.message ?: "install failed"))); return@installAsset }
                id = info.id
                try { ExtApi.sync(app) } catch (_: Throwable) { }
                try { ExtBackground.ensure(info.id) { } } catch (_: Throwable) { }
                var n = 0
                fun poll() {
                    main.postDelayed({
                        try {
                            val s = ChromeBridge.area(info.id, "local").get("selftest").optString("selftest", "")
                            if (s.isNotEmpty()) { finish(format(s)); return@postDelayed }
                        } catch (_: Throwable) { }
                        if (++n >= MAX_POLLS) finish(listOf("Self-test: no answer from the test extension (its worker did not run)")) else poll()
                    }, POLL_MS)
                }
                poll()
            }
        } catch (_: Throwable) { finish(listOf("Self-test could not start")) }
    }

    private fun format(json: String): List<String> {
        val out = ArrayList<String>()
        var pass = 0; var fail = 0; var no = 0
        val body = ArrayList<String>()
        try {
            val arr: JSONArray = JSONObject(json).optJSONArray("r") ?: JSONArray()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val n = o.optString("n"); val m = o.optString("m")
                when (o.optString("s")) {
                    "pass" -> { pass++; body.add("PASS  $n") }
                    "nosup" -> { no++; body.add("NOT SUPPORTED  $n") }
                    else -> { fail++; body.add("FAIL  $n" + (if (m.isNotEmpty()) ": $m" else "")) }
                }
            }
        } catch (_: Throwable) { body.add("result not readable") }
        fails = fail
        val wv = try { ExtProbe.webView() } catch (_: Throwable) { "?" }
        out.add("Self-test: $pass pass, $fail fail, $no not supported (WebView $wv)")
        out.addAll(body)
        return out
    }

    fun section(): DiagReport.Section {
        val l = lines
        return if (l == null) DiagReport.Section("extselftest", "Browser", "Extension self-test", DiagReport.INFO, listOf("Not run. Settings > Extensions > Compatibility self-test."))
        else DiagReport.Section("extselftest", "Browser", "Extension self-test", if (fails > 0) DiagReport.WARN else DiagReport.INFO, l)
    }
}
