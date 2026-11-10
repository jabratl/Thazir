package com.jabra.mgb

import android.text.InputType
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import androidx.fragment.app.FragmentActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.json.JSONArray
import org.json.JSONObject
import java.text.DateFormat
import java.util.Date

/**
 * 0.0.391 (PW5): extra fields of a saved login. All are OPTIONAL: an old mgb_pw.json (keys h / u / p only) loads exactly as before.
 *  n = note (Vault-encrypted), c = saved time, m = password changed time, l = last filled time, f = favourite,
 *  hist = up to 5 replaced passwords [{p: Vault-encrypted, t: time replaced}].
 * Keys are written only when set, so a login without extras is saved exactly like 0.0.389.
 * Notes and old passwords are shown only after the screen lock (PasswordStore.guard), like the password itself.
 * This file only formats / reads data and builds small dialogs; saving stays in PasswordStore.
 */
object PwMore {
    private const val MAX_HIST = 5

    // ---------------------------------------------------------------- file format

    fun fromJson(o: JSONObject): PasswordStore.Entry {
        val e = PasswordStore.Entry(o.optString("h"), o.optString("u"), o.optString("p"))
        try {
            e.notes = o.optString("n")
            e.created = o.optLong("c")
            e.modified = o.optLong("m")
            e.lastUsed = o.optLong("l")
            e.fav = o.optBoolean("f", false)
            e.totp = o.optString("t") // 0.0.392
            val a = o.optJSONArray("hist")
            if (a != null) {
                for (i in 0 until minOf(a.length(), MAX_HIST)) {
                    val x = a.optJSONObject(i) ?: continue
                    val p = x.optString("p")
                    if (p.isNotEmpty()) e.hist.add(PasswordStore.Hist(p, x.optLong("t")))
                }
            }
        } catch (x: Exception) { }
        return e
    }

    fun toJson(e: PasswordStore.Entry): JSONObject {
        val o = JSONObject().put("h", e.host).put("u", e.user).put("p", e.enc)
        try {
            if (e.notes.isNotEmpty()) o.put("n", e.notes)
            if (e.created > 0) o.put("c", e.created)
            if (e.modified > 0) o.put("m", e.modified)
            if (e.lastUsed > 0) o.put("l", e.lastUsed)
            if (e.fav) o.put("f", true)
            if (e.totp.isNotEmpty()) o.put("t", e.totp) // 0.0.392
            if (e.hist.isNotEmpty()) {
                val a = JSONArray()
                for (h in e.hist) a.put(JSONObject().put("p", h.enc).put("t", h.at))
                o.put("hist", a)
            }
        } catch (x: Exception) { }
        return o
    }

    // ---------------------------------------------------------------- keep extras when a login is saved again

    /** [ne] = the new entry, [prev] = the login it replaces (or null for a brand new login). */
    fun onPut(ne: PasswordStore.Entry, prev: PasswordStore.Entry?, pass: String) {
        val now = System.currentTimeMillis()
        if (prev == null) { ne.created = now; ne.modified = now; return }
        ne.totp = prev.totp
        ne.notes = prev.notes; ne.created = prev.created; ne.modified = prev.modified; ne.lastUsed = prev.lastUsed; ne.fav = prev.fav
        ne.hist.addAll(prev.hist)
        val oldPass = Vault.decrypt(prev.enc)
        if (oldPass != null && oldPass != pass) {
            ne.hist.add(0, PasswordStore.Hist(prev.enc, now))
            while (ne.hist.size > MAX_HIST) ne.hist.removeAt(ne.hist.size - 1)
            ne.modified = now
        }
    }

    // ---------------------------------------------------------------- encrypted backup (.mgbpw): extras travel as plain text INSIDE the encrypted file

    fun addBackup(o: JSONObject, e: PasswordStore.Entry) {
        if (e.notes.isNotEmpty()) { val n = Vault.decrypt(e.notes); if (!n.isNullOrEmpty()) o.put("n", n) }
        if (e.created > 0) o.put("c", e.created)
        if (e.modified > 0) o.put("m", e.modified)
        if (e.lastUsed > 0) o.put("l", e.lastUsed)
        if (e.fav) o.put("f", true)
        if (e.totp.isNotEmpty()) { val t = Vault.decrypt(e.totp); if (!t.isNullOrEmpty()) o.put("t", t) } // 0.0.392: plain inside the encrypted backup
        if (e.hist.isNotEmpty()) {
            val a = JSONArray()
            for (h in e.hist) { val p = Vault.decrypt(h.enc) ?: continue; a.put(JSONObject().put("p", p).put("t", h.at)) }
            if (a.length() > 0) o.put("hist", a)
        }
    }

    fun applyBackup(ne: PasswordStore.Entry, o: JSONObject) {
        val n = o.optString("n")
        if (n.isNotBlank()) ne.notes = Vault.encrypt(n.take(2000))
        ne.created = o.optLong("c"); ne.modified = o.optLong("m"); ne.lastUsed = o.optLong("l")
        ne.fav = o.optBoolean("f", false)
        val t = o.optString("t")
        if (t.isNotBlank() && Totp.parse(t) != null) ne.totp = Vault.encrypt(t.take(500)) // 0.0.392
        val a = o.optJSONArray("hist")
        if (a != null) {
            for (i in 0 until minOf(a.length(), MAX_HIST)) {
                val x = a.optJSONObject(i) ?: continue
                val p = x.optString("p")
                if (p.isNotEmpty()) ne.hist.add(PasswordStore.Hist(Vault.encrypt(p.take(500)), x.optLong("t")))
            }
        }
    }

    // ---------------------------------------------------------------- detail screen extras

    private fun date(t: Long): String = DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(t))
    private fun favText(e: PasswordStore.Entry) = if (e.fav) "\u2605 Favourite" else "\u2606 Favourite"
    private fun noteText(e: PasswordStore.Entry) = if (e.notes.isEmpty()) "Add note" else "Note"

    /** Added to the login detail sheet (called from PwScreen.detail inside try / catch): dates, favourite, note, old passwords. */
    fun detailExtras(act: FragmentActivity, col: LinearLayout, e: PasswordStore.Entry, changed: () -> Unit) {
        val cDim = act.getColor(R.color.mg_text_dim)
        val wrap = ViewGroup.LayoutParams.WRAP_CONTENT
        val parts = ArrayList<String>()
        if (e.created > 0) parts.add("Saved " + date(e.created))
        if (e.modified > 0 && e.modified != e.created) parts.add("Password changed " + date(e.modified))
        if (e.lastUsed > 0) parts.add("Last filled " + date(e.lastUsed))
        if (parts.isNotEmpty()) {
            col.addView(PwScreen.label(act, parts.joinToString("\n"), 12f, cDim), LinearLayout.LayoutParams(wrap, wrap).apply { topMargin = Util.dp(act, 16) })
        }
        if (e.hist.isNotEmpty()) {
            val link = PwScreen.label(act, "Old passwords (" + e.hist.size + ")", 14f, act.getColor(R.color.mg_primary), true)
            link.setPadding(0, Util.dp(act, 10), 0, Util.dp(act, 4))
            link.setOnClickListener { PasswordStore.guard(act, "Old passwords") { histDialog(act, e) } }
            col.addView(link, LinearLayout.LayoutParams(wrap, wrap))
        }
        val row = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, Util.dp(act, 16), 0, 0) }
        val fav = PwScreen.button(act, favText(e), false) { }
        fav.setOnClickListener {
            if (PasswordStore.setFav(act, e, !e.fav)) { fav.text = favText(e); changed() } else Util.toast(act, "Could not save")
        }
        val note = PwScreen.button(act, noteText(e), false) { }
        note.setOnClickListener { PasswordStore.guard(act, "Open note") { notesDialog(act, e) { note.text = noteText(e); changed() } } }
        row.addView(fav, LinearLayout.LayoutParams(0, wrap, 1f))
        row.addView(note, LinearLayout.LayoutParams(0, wrap, 1f).apply { marginStart = Util.dp(act, 8) })
        col.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, wrap))
        val tb = PwScreen.button(act, if (e.totp.isEmpty()) "Add 2FA code" else "2FA code", false) { }
        tb.setOnClickListener {
            PasswordStore.guard(act, "2FA code") {
                try {
                    if (e.totp.isEmpty()) totpAdd(act, e) { tb.text = "2FA code"; changed() }
                    else totpShow(act, e) { tb.text = "Add 2FA code"; changed() }
                } catch (x: Exception) { Util.toast(act, "Could not open the 2FA code") }
            }
        }
        col.addView(tb, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, wrap).apply { topMargin = Util.dp(act, 8) })
    }

    // ---------------------------------------------------------------- 0.0.392 (PW6a): 2FA codes

    private fun totpAdd(act: FragmentActivity, e: PasswordStore.Entry, saved: () -> Unit) {
        val et = EditText(act).apply { hint = "Secret key or otpauth:// link"; setSingleLine(); inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD }
        val note = android.widget.TextView(act).apply { text = "Paste the setup key the site shows next to its QR code. It is kept encrypted on this phone."; textSize = 13f; setTextColor(act.getColor(R.color.mg_text_dim)) }
        val box = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setPadding(Util.dp(act, 20), Util.dp(act, 8), Util.dp(act, 20), 0); addView(note); addView(et, LinearLayout.LayoutParams(-1, -2)) }
        val dlg = MaterialAlertDialogBuilder(act).setTitle("2FA code for " + e.host).setView(box)
            .setPositiveButton("Save", null).setNegativeButton("Cancel", null).create()
        PwScreen.secure(dlg.window)
        dlg.show()
        dlg.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val t = et.text.toString().trim().take(500)
            if (Totp.parse(t) == null) { Util.toast(act, "This is not a valid 2FA secret"); return@setOnClickListener }
            if (PasswordStore.setTotp(act, e, t)) { dlg.dismiss(); saved() } else Util.toast(act, "Could not save")
        }
    }

    /** Live code with a countdown; Copy uses the sensitive clipboard (cleared after 60 s). Remove asks again. */
    private fun totpShow(act: FragmentActivity, e: PasswordStore.Entry, removed: () -> Unit) {
        val cfg = Totp.parse(Vault.decrypt(e.totp) ?: "") ?: run { Util.toast(act, "Cannot read this 2FA secret"); return }
        val codeTv = android.widget.TextView(act).apply { textSize = 34f; typeface = android.graphics.Typeface.MONOSPACE; gravity = android.view.Gravity.CENTER; setTextColor(act.getColor(R.color.mg_text)) }
        val leftTv = android.widget.TextView(act).apply { textSize = 13f; gravity = android.view.Gravity.CENTER; setTextColor(act.getColor(R.color.mg_text_dim)) }
        val box = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setPadding(Util.dp(act, 20), Util.dp(act, 16), Util.dp(act, 20), 0); addView(codeTv); addView(leftTv) }
        val h = android.os.Handler(android.os.Looper.getMainLooper())
        var cur = ""
        var live = true
        val tick = object : Runnable {
            override fun run() {
                if (!live) return
                val now = System.currentTimeMillis()
                cur = Totp.code(cfg, now)
                val half = cur.length / 2
                codeTv.text = cur.substring(0, half) + " " + cur.substring(half)
                leftTv.text = Totp.secondsLeft(cfg, now).toString() + " s left"
                h.postDelayed(this, 1000L)
            }
        }
        val dlg = MaterialAlertDialogBuilder(act).setTitle("2FA code: " + e.host).setView(box)
            .setPositiveButton("Copy", null).setNeutralButton("Remove", null).setNegativeButton("Close", null).create()
        PwScreen.secure(dlg.window)
        dlg.setOnDismissListener { live = false; h.removeCallbacksAndMessages(null) }
        dlg.show()
        tick.run()
        dlg.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener { PasswordStore.copySecret(act, "2FA code", cur) }
        dlg.getButton(android.app.AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
            MaterialAlertDialogBuilder(act).setMessage("Remove the 2FA secret of " + e.host + " from this phone? You need the site's setup key to add it again.")
                .setPositiveButton("Remove") { _, _ -> if (PasswordStore.setTotp(act, e, "")) { dlg.dismiss(); removed() } else Util.toast(act, "Could not save") }
                .setNegativeButton("Cancel", null).show()
        }
    }

    private fun notesDialog(act: FragmentActivity, e: PasswordStore.Entry, saved: () -> Unit) {
        val cur = if (e.notes.isEmpty()) "" else (Vault.decrypt(e.notes) ?: run { Util.toast(act, "Cannot read this note"); return })
        val et = EditText(act).apply {
            hint = "Note (kept encrypted)"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 3; maxLines = 8; setText(cur); setSelection(cur.length)
        }
        val box = LinearLayout(act).apply { setPadding(Util.dp(act, 20), Util.dp(act, 8), Util.dp(act, 20), 0); addView(et, LinearLayout.LayoutParams(-1, -2)) }
        val dlg = MaterialAlertDialogBuilder(act).setTitle("Note for " + e.host).setView(box)
            .setPositiveButton("Save", null).setNegativeButton("Cancel", null).create()
        PwScreen.secure(dlg.window)
        dlg.show()
        dlg.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            if (PasswordStore.setNotes(act, e, et.text.toString().trim().take(2000))) { dlg.dismiss(); saved() }
            else Util.toast(act, "Could not save the note")
        }
    }

    /** Old passwords are never shown, only copied (clipboard is cleared after 60 s). Useful when a site refused the change. */
    private fun histDialog(act: FragmentActivity, e: PasswordStore.Entry) {
        val items = e.hist.map { "Replaced " + (if (it.at > 0) date(it.at) else "earlier") + " (tap to copy)" }.toTypedArray()
        MaterialAlertDialogBuilder(act).setTitle("Old passwords").setItems(items) { _, i ->
            val v = e.hist.getOrNull(i)?.let { Vault.decrypt(it.enc) }
            if (v == null) Util.toast(act, "Cannot read this password") else PasswordStore.copySecret(act, "Old password", v)
        }.setNegativeButton("Close", null).show()
    }
}
