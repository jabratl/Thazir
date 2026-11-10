package com.jabra.mgb

import android.content.Context
import androidx.fragment.app.FragmentActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.json.JSONObject

/**
 * S45: site permission manager. Camera / microphone / location answers are remembered per site
 * (prefs mgb_perm: "host|what" -> 1 allow / 0 block) when "Remember for this site" is ticked in the question.
 * Settings > Privacy > Site permissions lists them; tap one to Allow / Block / Reset it.
 * The Android permission (system dialog) is still asked separately the first time.
 */
object SitePerms {
    private const val PREFS = "mgb_perm"
    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun key(host: String, what: String) = host.lowercase() + "|" + what

    fun get(c: Context, host: String, what: String): Boolean? {
        val k = key(host, what)
        val p = prefs(c)
        return if (p.contains(k)) p.getInt(k, 0) == 1 else null
    }

    fun set(c: Context, host: String, what: String, ok: Boolean?) {
        val e = prefs(c).edit()
        if (ok == null) e.remove(key(host, what)) else e.putInt(key(host, what), if (ok) 1 else 0)
        e.apply()
    }

    private class Entry(val host: String, val what: String, val ok: Boolean)

    private fun all(c: Context): List<Entry> {
        val out = ArrayList<Entry>()
        for ((k, v) in prefs(c).all) {
            val i = k.indexOf('|')
            if (i <= 0 || v !is Int) continue
            out.add(Entry(k.substring(0, i), k.substring(i + 1), v == 1))
        }
        return out.sortedWith(compareBy({ it.host }, { it.what }))
    }

    fun count(c: Context) = all(c).size

    /** 0.0.382: forget every saved site answer (Delete browsing data > Site settings). Default Ask / Blocked switches stay. */
    fun clearAll(c: Context) { prefs(c).edit().clear().apply() }

    /** 0.0.282: saved answers as (host, what, allowed) for the new sheet. */
    fun entries(c: Context): List<Triple<String, String, Boolean>> = all(c).map { Triple(it.host, it.what, it.ok) }

    /** 0.0.282: default for sites without a saved answer. false (default) = ask, as before; true = refuse without asking. */
    fun blockedByDefault(c: Context, what: String): Boolean {
        val p = c.getSharedPreferences("mgb_perm_def", Context.MODE_PRIVATE)
        return what.split(" and ").any { p.getInt(it, 1) == 0 }
    }

    fun setDefaultBlock(c: Context, what: String, block: Boolean) {
        val e = c.getSharedPreferences("mgb_perm_def", Context.MODE_PRIVATE).edit()
        if (block) e.putInt(what, 0) else e.remove(what)
        e.apply()
    }

    fun screen(act: FragmentActivity, done: () -> Unit = {}) {
        if (true) { SitePermsUi.show(act, done); return } // 0.0.282: Brave-style sheet; the old list below is kept as is
        val list = all(act)
        if (list.isEmpty()) {
            MaterialAlertDialogBuilder(act).setTitle("Site permissions")
                .setMessage("Nothing saved yet. When a site asks for camera, microphone or location, tick \"Remember for this site\" and it will be listed here.")
                .setPositiveButton("OK", null).show()
            return
        }
        val labels = list.map { it.host + "\n" + it.what + " · " + (if (it.ok) "Allowed" else "Blocked") }.toTypedArray()
        MaterialAlertDialogBuilder(act).setTitle("Site permissions")
            .setItems(labels) { _, i -> edit(act, list[i], done) }
            .setNeutralButton("Reset all") { _, _ ->
                MaterialAlertDialogBuilder(act).setMessage("Forget all saved site permissions?")
                    .setPositiveButton("Reset") { _, _ -> prefs(act).edit().clear().apply(); done() }
                    .setNegativeButton("Cancel", null).show()
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun edit(act: FragmentActivity, e: Entry, done: () -> Unit) {
        MaterialAlertDialogBuilder(act).setTitle(e.host + " · " + e.what)
            .setItems(arrayOf("Allow", "Block", "Reset (ask again)")) { _, i ->
                set(act, e.host, e.what, when (i) { 0 -> true; 1 -> false; else -> null })
                done()
                screen(act, done)
            }.show()
    }
}
