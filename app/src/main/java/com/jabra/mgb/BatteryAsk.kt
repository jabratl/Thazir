package com.jabra.mgb

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * 0.0.403 (plan item 12, owner said yes): ask Android to let MGB run in the background without battery optimisation,
 * so long downloads are not stopped. NEW permission: REQUEST_IGNORE_BATTERY_OPTIMIZATIONS (AndroidManifest).
 *
 * Only on a tap of the Report center button "Allow background use", and only after our own confirm dialog.
 * Never automatic, never repeated. Nothing is changed by the app itself: Android shows its own question.
 *
 * Stages, tried in order (the first that opens wins, a failed one is logged and the next is tried):
 *   1. direct request (Android's own "Allow MGB to run in the background?" dialog)
 *   2. the system list of battery-optimised apps
 *   3. the app info screen (always exists, the final fallback)
 */
object BatteryAsk {
    fun exempt(c: Context): Boolean = try {
        (c.getSystemService(Context.POWER_SERVICE) as? PowerManager)?.isIgnoringBatteryOptimizations(c.packageName) == true
    } catch (_: Throwable) { false }

    fun ask(act: Activity) {
        try {
            if (exempt(act)) { Util.toast(act, "Already allowed"); return }
            MaterialAlertDialogBuilder(act)
                .setTitle("Allow background use?")
                .setMessage("Android will ask you next. If you allow it, MGB can keep long downloads running with the screen off. " +
                    "It uses battery only while it is downloading. You can undo it any time in the phone's battery settings.")
                .setPositiveButton("Continue") { _, _ -> open(act) }
                .setNegativeButton("Not now", null)
                .show()
        } catch (e: Throwable) { Diag.w("app", "battery ask failed", e.javaClass.simpleName) }
    }

    private fun open(act: Activity) {
        val pkg = act.packageName
        val stages: List<Pair<String, () -> Intent>> = listOf(
            "direct request" to { Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$pkg")) },
            "battery list" to { Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS) },
            "app info" to { Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg")) }
        )
        for ((name, make) in stages) {
            try { act.startActivity(make().addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); return }
            catch (e: Throwable) { Diag.w("app", "battery ask stage failed: $name", e.javaClass.simpleName) }
        }
        Util.toast(act, "Cannot open the battery settings")
    }
}
