package com.jabra.mgb

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.util.Log
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.core.widget.NestedScrollView
import androidx.fragment.app.FragmentActivity
import com.google.android.material.bottomsheet.BottomSheetDialog
import java.security.MessageDigest
import kotlin.math.ln

/**
 * 0.0.390 (PW4): password health, OFFLINE only. Nothing here uses the network.
 *  - weak: very common password, shorter than 8, almost one repeated character, easy pattern (12345678 / qwerty),
 *    same as the user name, or too simple for its length (same 45 bit line as the generator "weak").
 *  - reused: the same password on 2 or more logins (compared by SHA-256 in memory only, nothing is stored).
 *  - old (0.0.391, PW5): password not changed for 1 year or more. Needs the saved / changed date; logins saved before 0.0.391 have no date and are never called old.
 * The check runs only after the user tapped "Check" (screen lock via PasswordStore.guard), on a worker thread.
 * Decrypted passwords live only inside analyze() and are never logged, stored or shown here.
 * Switch (SharedPreferences "mgb_pw", key "health", default ON). OFF / any error = the Passwords screen works as before, without the card.
 * Limit: this is a simple score, not a dictionary check; a long lower-case word can pass. It is a hint, not a promise.
 */
object PwHealth {
    private const val PREF = "mgb_pw"
    private const val KEY = "health"
    private const val TAG = "MGB.pwh"
    private val ui = Handler(Looper.getMainLooper())

    fun on(c: Context): Boolean = try { c.getSharedPreferences(PREF, Context.MODE_PRIVATE).getBoolean(KEY, true) } catch (e: Exception) { false }
    fun flip(c: Context) { try { c.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putBoolean(KEY, !on(c)).apply() } catch (e: Exception) { } }

    class Weak(val entry: PasswordStore.Entry, val why: String)
    class Result(val weak: List<Weak>, val reused: List<List<PasswordStore.Entry>>, val unreadable: Int, val checked: Int, val old: List<PasswordStore.Entry> = emptyList())
    private const val OLD_MS = 365L * 24L * 3600L * 1000L

    private val COMMON = setOf(
        "password", "password1", "password123", "passw0rd", "p@ssw0rd", "123456", "1234567", "12345678", "123456789", "1234567890",
        "qwerty", "qwerty123", "qwertyuiop", "abc123", "abcd1234", "111111", "000000", "123123", "654321", "admin", "admin123",
        "letmein", "welcome", "welcome1", "iloveyou", "monkey", "dragon", "football", "baseball", "login", "princess",
        "sunshine", "master", "hello123", "freedom", "whatever", "trustno1", "1q2w3e4r", "1qaz2wsx", "zaq12wsx", "asdfghjkl"
    )
    private val RUNS = listOf("abcdefghijklmnopqrstuvwxyz", "qwertyuiop", "asdfghjkl", "zxcvbnm", "0123456789", "1q2w3e4r5t6y")

    private fun isRun(low: String): Boolean {
        if (low.length < 6) return false
        for (r in RUNS) if (r.contains(low) || r.reversed().contains(low)) return true
        return false
    }

    /** null = not weak. The text is shown to the owner (no password inside). */
    internal fun weakReason(pw: String, user: String): String? {
        val low = pw.lowercase()
        if (low in COMMON) return "very common password"
        if (pw.length < 8) return "shorter than 8 characters"
        if (pw.toSet().size <= 3) return "almost the same character again and again"
        if (isRun(low)) return "easy pattern (like 12345678 or qwerty)"
        if (user.isNotBlank() && low == user.trim().lowercase()) return "same as the user name"
        var pool = 0
        if (pw.any { it in 'a'..'z' }) pool += 26
        if (pw.any { it in 'A'..'Z' }) pool += 26
        if (pw.any { it in '0'..'9' }) pool += 10
        if (pw.any { !(it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9') }) pool += 32
        val bits = pw.length * ln(pool.toDouble()) / ln(2.0)
        if (bits < 45) return "too simple for its length"
        return null
    }

    private fun sha(s: String): String {
        val d = MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8))
        return d.joinToString("") { "%02x".format(it) }
    }

    /** Blocking (many Keystore decrypts): call from a worker thread. */
    fun analyze(ctx: Context): Result {
        val es = PasswordStore.all(ctx)
        val weak = ArrayList<Weak>()
        val groups = HashMap<String, MutableList<PasswordStore.Entry>>()
        var bad = 0
        val old = ArrayList<PasswordStore.Entry>()
        val now = System.currentTimeMillis()
        for (e in es) {
            val pw = Vault.decrypt(e.enc)
            if (pw == null) { bad++; continue }
            val why = weakReason(pw, e.user)
            if (why != null) weak.add(Weak(e, why))
            groups.getOrPut(sha(pw)) { ArrayList() }.add(e)
            val since = if (e.modified > 0) e.modified else e.created
            if (since > 0 && now - since > OLD_MS) old.add(e)
        }
        val reused = groups.values.filter { it.size >= 2 }.sortedByDescending { it.size }
        return Result(weak, reused, bad, es.size - bad, old)
    }

    /** The card on top of the Passwords screen. sync(signature) = the list changed, the old result is dropped. */
    class Card(private val act: FragmentActivity, private val onFix: () -> Unit) {
        val view = LinearLayout(act)
        private var sig = 0
        private var gen = 0
        private var busy = false
        private var res: Result? = null

        init { view.orientation = LinearLayout.VERTICAL; draw() }

        fun sync(newSig: Int) {
            if (newSig == sig) return
            sig = newSig; gen++; busy = false; res = null
            draw()
        }

        private fun dp(v: Int) = Util.dp(act, v)

        private fun draw() {
            val cText = act.getColor(R.color.mg_text)
            val cDim = act.getColor(R.color.mg_text_dim)
            view.removeAllViews()
            view.background = PwScreen.round(act, act.getColor(R.color.mg_surface), 16, act.getColor(R.color.mg_outline))
            view.setPadding(dp(14), dp(12), dp(14), dp(12))
            val r = res
            val title: String
            val sub: String
            if (busy) { title = "Password health"; sub = "Checking..." }
            else if (r == null) { title = "Password health"; sub = "Find weak and reused passwords. Everything stays on this phone." }
            else if (r.weak.isEmpty() && r.reused.isEmpty() && r.old.isEmpty()) {
                title = "Password health: looks good"
                sub = "No weak or reused password found in " + r.checked + " logins" + (if (r.unreadable > 0) " (" + r.unreadable + " could not be read)" else "") + "."
            } else {
                val parts = ArrayList<String>()
                if (r.weak.isNotEmpty()) parts.add(r.weak.size.toString() + " weak")
                if (r.reused.isNotEmpty()) parts.add(r.reused.sumOf { it.size }.toString() + " logins share a password")
                if (r.old.isNotEmpty()) parts.add(r.old.size.toString() + " not changed for a year")
                if (r.unreadable > 0) parts.add(r.unreadable.toString() + " cannot be read")
                title = "Password health: needs attention"
                sub = parts.joinToString(", ")
            }
            view.addView(PwScreen.label(act, title, 16f, cText, true))
            view.addView(PwScreen.label(act, sub, 13f, cDim), LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(2) })
            if (busy) return
            val row = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END; setPadding(0, dp(10), 0, 0) }
            val needs = r != null && (r.weak.isNotEmpty() || r.reused.isNotEmpty() || r.old.isNotEmpty())
            if (needs) row.addView(PwScreen.button(act, "Review", true) { try { review(r!!) } catch (e: Exception) { Log.e(TAG, "review failed", e) } })
            row.addView(PwScreen.button(act, if (r == null) "Check" else "Check again", !needs) { check() },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { if (needs) marginStart = dp(8) })
            view.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }

        private fun check() {
            if (busy) return
            PasswordStore.guard(act, "Check password health") {
                busy = true
                val g = ++gen
                draw()
                val app = act.applicationContext
                Thread {
                    val r = try { analyze(app) } catch (e: Exception) { Log.e(TAG, "check failed", e); null }
                    ui.post {
                        if (g == gen) {
                            busy = false; res = r
                            if (r == null) Util.toast(act, "Could not check the passwords")
                            try { draw() } catch (e: Exception) { }
                        }
                    }
                }.start()
            }
        }

        /** List of the logins to fix. Tap one = the normal Edit screen (screen lock first), where Generate makes a new password. */
        private fun review(r: Result) {
            val cText = act.getColor(R.color.mg_text)
            val cDim = act.getColor(R.color.mg_text_dim)
            val d = BottomSheetDialog(act)
            val col = LinearLayout(act).apply {
                orientation = LinearLayout.VERTICAL; background = PwScreen.sheetBg(act)
                setPadding(dp(18), dp(18), dp(18), dp(20))
            }
            col.addView(PwScreen.label(act, "Password health", 20f, cText, true))
            col.addView(PwScreen.label(act, "Tap a login to change its password. Nothing leaves this phone.", 13f, cDim),
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) })
            fun head(t: String) = col.addView(PwScreen.label(act, t, 13f, cDim, true),
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(18); bottomMargin = dp(4) })
            fun row(e: PasswordStore.Entry, why: String) {
                val line = LinearLayout(act).apply {
                    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; minimumHeight = dp(56)
                    setPadding(dp(4), dp(6), dp(4), dp(6))
                    setOnClickListener { d.dismiss(); PasswordStore.editEntry(act, e) { onFix() } }
                }
                line.addView(PwScreen.tile(act, e.host, 40), LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginEnd = dp(12) })
                val t = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
                t.addView(PwScreen.label(act, e.host, 15f, cText).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
                t.addView(PwScreen.label(act, e.user.ifBlank { "(no user name)" }, 12f, cDim).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
                t.addView(PwScreen.label(act, why, 12f, cDim).apply { maxLines = 2 })
                line.addView(t, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                col.addView(line, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            }
            if (r.weak.isNotEmpty()) { head("Weak (" + r.weak.size + ")"); for (w in r.weak) row(w.entry, w.why) }
            for (g in r.reused) {
                head("Same password on " + g.size + " logins")
                for (e in g) row(e, "change it so every site has its own password")
            }
            if (r.old.isNotEmpty()) {
                head("Not changed for a year or more (" + r.old.size + ")")
                val fmt = java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM)
                for (e in r.old) row(e, "password last changed " + fmt.format(java.util.Date(if (e.modified > 0) e.modified else e.created)))
            }
            d.setContentView(NestedScrollView(act).apply { addView(col, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)) })
            PwScreen.secure(d.window)
            d.show()
        }
    }
}
