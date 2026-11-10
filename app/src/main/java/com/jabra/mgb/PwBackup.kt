package com.jabra.mgb

import android.text.InputType
import android.util.Base64
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.FragmentActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * 0.0.387 (PW1): encrypted password backup (.mgbpw) + restore, and the "Sites never saved" list.
 * The file is JSON: {v, kdf, it, salt, iv, ct}. Key = PBKDF2(backup password), cipher = AES-256-GCM.
 * Restore only ADDS logins that are missing; a login that already exists (same site + user name) is never overwritten.
 * The CSV import / export in PasswordStore is unchanged.
 */
object PwBackup {
    private const val ITER = 200_000
    private val rnd = SecureRandom()

    private fun kdf(): String = try { SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256"); "PBKDF2WithHmacSHA256" } catch (e: Exception) { "PBKDF2WithHmacSHA1" } // API 24-25 only have SHA1

    private fun keyOf(pw: CharArray, salt: ByteArray, kdfName: String, it: Int): SecretKeySpec {
        val f = SecretKeyFactory.getInstance(kdfName)
        return SecretKeySpec(f.generateSecret(PBEKeySpec(pw, salt, it, 256)).encoded, "AES")
    }

    private fun seal(plain: String, pw: CharArray): String {
        val salt = ByteArray(16).also { rnd.nextBytes(it) }
        val iv = ByteArray(12).also { rnd.nextBytes(it) }
        val k = kdf()
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, keyOf(pw, salt, k, ITER), GCMParameterSpec(128, iv))
        val ct = c.doFinal(plain.toByteArray(Charsets.UTF_8))
        return JSONObject().put("v", 1).put("app", "thazir-passwords").put("kdf", k).put("it", ITER)
            .put("salt", Base64.encodeToString(salt, Base64.NO_WRAP)).put("iv", Base64.encodeToString(iv, Base64.NO_WRAP))
            .put("ct", Base64.encodeToString(ct, Base64.NO_WRAP)).toString()
    }

    /** null = wrong password or a damaged file. */
    private fun open(text: String, pw: CharArray): String? = try {
        val o = JSONObject(text.trim())
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE,
            keyOf(pw, Base64.decode(o.getString("salt"), Base64.NO_WRAP), o.getString("kdf"), o.getInt("it").coerceIn(1000, 2_000_000)),
            GCMParameterSpec(128, Base64.decode(o.getString("iv"), Base64.NO_WRAP)))
        String(c.doFinal(Base64.decode(o.getString("ct"), Base64.NO_WRAP)), Charsets.UTF_8)
    } catch (e: Exception) { null }

    private fun field(act: FragmentActivity, hint: String) = EditText(act).apply {
        this.hint = hint
        setSingleLine()
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
    }

    /** Menu / Settings: make an encrypted backup file. Asks the screen lock, then a backup password (twice). */
    fun export(act: FragmentActivity) {
        try {
            if (PasswordStore.all(act).isEmpty()) { Util.toast(act, "No saved passwords yet"); return }
            PasswordStore.guard(act, "Backup passwords") { askNewPassword(act) }
        } catch (e: Exception) { Util.toast(act, "Could not start the backup") }
    }

    private fun askNewPassword(act: FragmentActivity) {
        val p1 = field(act, "Backup password (min 8 characters)")
        val p2 = field(act, "Backup password again")
        val col = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Util.dp(act, 20), Util.dp(act, 8), Util.dp(act, 20), 0)
            addView(TextView(act).apply { text = "Write this password down. Without it the file cannot be opened, and it cannot be recovered."; textSize = 13f; setTextColor(act.getColor(R.color.mg_text_dim)) })
            addView(p1); addView(p2)
        }
        val dlg = MaterialAlertDialogBuilder(act).setTitle("Encrypted backup").setView(col)
            .setPositiveButton("Save file", null).setNegativeButton("Cancel", null).create()
        dlg.show()
        dlg.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val a = p1.text.toString()
            if (a.length < 8) { Util.toast(act, "Use at least 8 characters"); return@setOnClickListener }
            if (a != p2.text.toString()) { Util.toast(act, "The two passwords are not the same"); return@setOnClickListener }
            dlg.dismiss()
            Util.toast(act, "Working...")
            Thread {
                val text = try {
                    val arr = JSONArray()
                    for (e in PasswordStore.all(act)) {
                        val p = Vault.decrypt(e.enc) ?: continue
                        val o = JSONObject().put("h", e.host).put("u", e.user).put("p", p)
                        try { PwMore.addBackup(o, e) } catch (x: Exception) { } // 0.0.391: notes, dates, favourite, old passwords (old restore ignores them)
                        arr.put(o)
                    }
                    if (arr.length() == 0) null else seal(arr.toString(), a.toCharArray())
                } catch (e: Exception) { null }
                act.runOnUiThread {
                    if (text == null) Util.toast(act, "Could not make the backup (no password could be read)")
                    else BrowserExtras.saveBackup("mgb-passwords.mgbpw") { text }
                }
            }.start()
        }
    }

    /** Menu / Settings: pick a .mgbpw file, ask its password, ADD the missing logins. */
    fun restore(act: FragmentActivity, done: () -> Unit = { }) {
        try {
            BrowserExtras.loadBackup { text ->
                if (text == null) { Util.toast(act, "Could not read the file"); return@loadBackup }
                if (!text.trimStart().startsWith("{")) { Util.toast(act, "This is not a Thazir password backup"); return@loadBackup }
                val p = field(act, "Backup password")
                val col = LinearLayout(act).apply { setPadding(Util.dp(act, 20), Util.dp(act, 8), Util.dp(act, 20), 0); addView(p, LinearLayout.LayoutParams(-1, -2)) }
                val dlg = MaterialAlertDialogBuilder(act).setTitle("Restore passwords").setView(col)
                    .setPositiveButton("Restore", null).setNegativeButton("Cancel", null).create()
                dlg.show()
                dlg.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                    val pw = p.text.toString().toCharArray()
                    dlg.dismiss()
                    Util.toast(act, "Working...")
                    Thread {
                        val plain = open(text, pw)
                        act.runOnUiThread {
                            if (plain == null) { Util.toast(act, "Wrong backup password, or the file is damaged"); return@runOnUiThread }
                            try {
                                val a = JSONArray(plain)
                                var added = 0; var skipped = 0
                                for (i in 0 until a.length()) {
                                    val o = a.optJSONObject(i) ?: continue
                                    if (PasswordStore.addIfMissing(act, o.optString("h"), o.optString("u"), o.optString("p"), o)) added++ else skipped++
                                }
                                Util.toast(act, "$added logins added, $skipped already there (not changed)")
                                done()
                            } catch (e: Exception) { Util.toast(act, "The backup content could not be read") }
                        }
                    }.start()
                }
            }
        } catch (e: Exception) { Util.toast(act, "Could not open the file picker") }
    }

    /** Settings > Passwords > "Sites never saved": see the list, tap a site to allow saving there again. */
    fun neverList(act: FragmentActivity) {
        try {
            val hosts = PasswordStore.neverHosts(act).sorted()
            if (hosts.isEmpty()) { Util.toast(act, "No sites in the list"); return }
            MaterialAlertDialogBuilder(act).setTitle("Sites never saved (tap to allow again)")
                .setItems(hosts.toTypedArray()) { _, i ->
                    PasswordStore.removeNever(act, hosts[i])
                    Util.toast(act, "${hosts[i]}: Thazir will ask to save again")
                }
                .setNegativeButton("Close", null).show()
        } catch (e: Exception) { Util.toast(act, "Could not show the list") }
    }
}
