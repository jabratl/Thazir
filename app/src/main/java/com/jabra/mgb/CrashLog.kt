package com.jabra.mgb

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.ScrollView
import android.widget.TextView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/**
 * FIX tool: saves the stack trace of an uncaught crash to filesDir/mgb_crash.txt, and the next start shows it
 * in a dialog (Copy / Close), so a crash can be reported without adb. The normal crash still happens afterwards.
 */
object CrashLog {
    private const val F = "mgb_crash.txt"

    fun install(ctx: Context) {
        Diag.init(ctx) // S82: event log starts together with the crash handler
        val file = File(ctx.applicationContext.filesDir, F)
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            Diag.crash(t.name, e) // S82: same crash also goes to the event log (masked, saved to disk now)
            CrashHint.record(ctx) // 0.0.334: crash counter for the "keeps crashing, revert?" hint (never throws)
            try {
                val sw = StringWriter()
                e.printStackTrace(PrintWriter(sw))
                file.writeText("MGB ${BuildConfig.VERSION_NAME} thread=${t.name}\n" + sw.toString().take(6000))
            } catch (_: Throwable) { }
            prev?.uncaughtException(t, e)
        }
    }

    fun showLast(act: Activity) {
        val file = File(act.applicationContext.filesDir, F)
        if (!file.exists()) return
        val text = try { file.readText() } catch (e: Exception) { "" }
        DiagExtra.saveCrashCopy(act.applicationContext, text, try { file.lastModified() } catch (e: Exception) { 0L }) // 0.0.397: the Report center shows it later
        file.delete()
        if (text.isBlank() || act.isFinishing) return
        val tv = TextView(act).apply {
            this.text = text
            textSize = 11f
            typeface = android.graphics.Typeface.MONOSPACE
            setTextIsSelectable(true)
            setTextColor(act.getColor(R.color.mg_text))
            setPadding(Util.dp(act, 16), Util.dp(act, 8), Util.dp(act, 16), Util.dp(act, 8))
        }
        val sv = ScrollView(act)
        sv.addView(tv)
        MaterialAlertDialogBuilder(act)
            .setTitle("Last crash")
            .setView(sv)
            .setPositiveButton("Copy") { _, _ ->
                (act.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                    .setPrimaryClip(ClipData.newPlainText("MGB crash", text))
                Util.toast(act, "Copied")
            }
            .setNeutralButton("Full report") { _, _ -> DiagScreen.open(act) } // S86: events + device + rules
            .setNegativeButton("Close", null)
            .show()
    }
}
