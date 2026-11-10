package com.jabra.mgb

import android.app.Activity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.json.JSONObject

/**
 * 0.0.372 (N4): "Details" of one native download row: Properties text + "Parts for this download" (Auto / Single / 2 / 4 / 6 / 8 / 16).
 * The choice is stored on the item (DlItem.pn, 0 = Auto = the global setting). It is read when the download (re)starts from byte 0;
 * a download that already runs in parts keeps its layout. Global switch "Download in parts" must be ON for 2..16 (OFF = single, as before).
 */
object DlProps {
    private val VALUES = intArrayOf(0, 1, 2, 4, 6, 8, 16)
    private fun label(v: Int): String = when (v) { 0 -> "Auto (global setting)"; 1 -> "Single connection"; else -> "$v parts" }

    private fun idOf(j: JSONObject): Int = j.optString("key").removePrefix("nat:").toIntOrNull() ?: -1

    fun open(act: Activity, j: JSONObject) {
        try {
            val id = idOf(j)
            if (id < 0) return
            val pn = j.optInt("pn", 0)
            val tot = j.optLong("total", -1L)
            val rec = j.optLong("rec", 0L)
            val sb = StringBuilder()
            sb.append("Name: ").append(j.optString("title")).append('\n')
            sb.append("Link: ").append(j.optString("url")).append('\n')
            sb.append("Size: ").append(if (tot > 0) EngineApi.fmtSize(tot) else "unknown").append('\n')
            sb.append("Downloaded: ").append(EngineApi.fmtSize(rec)).append('\n')
            sb.append("Parts: ").append(label(pn))
            j.optJSONArray("parts")?.let { if (it.length() > 0) sb.append(" (running with ").append(it.length()).append(")") }
            sb.append('\n')
            sb.append("Can resume: ").append(if (j.optBoolean("resume")) "yes" else "no").append('\n')
            val vld = j.optString("validator")
            if (vld.isNotEmpty()) sb.append("Validator: ").append(vld.take(60)).append('\n')
            val path = j.optString("path")
            if (path.isNotEmpty()) sb.append("Save path: ").append(path)
            MaterialAlertDialogBuilder(act).setTitle("Details")
                .setMessage(sb.toString().trimEnd())
                .setPositiveButton("Parts for this download") { _, _ -> pick(act, id, pn) }
                .setNegativeButton("Close", null).show()
        } catch (e: Exception) { }
    }

    private fun pick(act: Activity, id: Int, cur: Int) {
        try {
            MaterialAlertDialogBuilder(act).setTitle("Parts for this download")
                .setSingleChoiceItems(VALUES.map { label(it) }.toTypedArray(), VALUES.indexOf(cur).coerceAtLeast(0)) { dd, i ->
                    DownloadsApi.setParts(id, VALUES[i])
                    dd.dismiss()
                }
                .setNegativeButton("Cancel", null).show()
        } catch (e: Exception) { }
    }
}
