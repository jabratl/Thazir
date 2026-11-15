package com.jabra.mgb

import android.app.Activity
import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.fragment.app.FragmentActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * V11: Vault settings page (full-screen Dialog opened from the vault home, rebuilt from object state so rotation is safe).
 *  LOCKING          auto-lock delay, quick unlock, hide from recents
 *  KEYS             change passphrase (only the passphrase wrap in vault.hdr changes), new recovery key (shown once)
 *  CHECKS           Verify vault (quick = header, crypto self-test, index, every file present; full = also reads every file back),
 *                   failed-attempt wait (info), index size + "remove old history"
 *  Lock now.
 * The crypto work is in VaultStore (changePass / regenRecovery / confirmPass; they call VaultCrypto.rewrapPass / newRecovery directly), the wait after wrong tries is in VaultStore too.
 * No passphrase, key or item title is ever put in a toast or a log.
 */
object VaultSettings {
    private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

    private val main = Handler(Looper.getMainLooper())
    private val bg = Executors.newSingleThreadExecutor()
    private var dlg: Dialog? = null
    private var host: FrameLayout? = null
    private var act: Activity? = null
    private var onClose: (() -> Unit)? = null
    private var screen = ""
    @Volatile private var busy = false
    private val cancel = AtomicBoolean(false)

    private val DELAYS = longArrayOf(0L, 60_000L, 120_000L, 300_000L, 900_000L)
    private val DELAY_NAMES = arrayOf("When I leave the app", "After 1 minute", "After 2 minutes", "After 5 minutes", "After 15 minutes")

    private fun dp(a: Context, v: Int) = Util.dp(a, v)
    private fun lp(top: Int = 0, w: Int = MATCH, h: Int = WRAP) = LinearLayout.LayoutParams(w, h).apply { topMargin = top }
    private fun chars(e: EditText): CharArray { val s = e.text; return CharArray(s.length) { s[it] } }

    // ---------- open / close ----------

    /** closed runs when the page goes away (VaultScreen rebuilds its home or shows the unlock page). */
    fun open(a: Activity, closed: () -> Unit) {
        if (a.isFinishing || a.isDestroyed || dlg?.isShowing == true) return
        if (!VaultStore.isUnlocked()) { Util.toast(a, "Open the vault first"); return }
        act = a; onClose = closed; busy = false; cancel.set(false)
        val d = Dialog(a, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        val h = FrameLayout(a)
        h.setBackgroundColor(a.getColor(R.color.mg_bg))
        host = h
        d.setContentView(h, ViewGroup.LayoutParams(MATCH, MATCH))
        d.window?.apply {
            setBackgroundDrawable(ColorDrawable(a.getColor(R.color.mg_bg)))
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
        applyFlag(a)
        d.setOnKeyListener { _, k, e ->
            if (k == KeyEvent.KEYCODE_BACK && e.action == KeyEvent.ACTION_UP) { back(); true } else false
        }
        d.setOnDismissListener {
            if (dlg === d) {
                cancel.set(true); dlg = null; host = null; screen = ""
                val f = onClose; onClose = null; f?.invoke()
            }
        }
        dlg = d
        try { d.show() } catch (e: Exception) { dlg = null; return }
        mainPage()
    }

    /** VaultScreen.refresh: a locked vault closes the page, except while a NEW recovery key is on screen (it must not be lost). */
    fun refresh() {
        if (dlg?.isShowing != true || VaultStore.isUnlocked() || screen == "showkey") return
        cancel.set(true)
        dlg?.dismiss()
    }

    private fun applyFlag(a: Activity) {
        dlg?.window?.let { w ->
            if (VaultStore.hideRecents(a)) w.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
            else w.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }

    private fun back() {
        val a = act ?: return
        when {
            screen == "verifying" -> cancel.set(true)
            busy -> Unit
            screen == "showkey" -> Util.toast(a, "Tick \"I saved my new recovery key\" first")
            screen == "main" -> dlg?.dismiss()
            else -> mainPage()
        }
    }

    // ---------- view helpers ----------

    private fun page(a: Activity, build: (LinearLayout) -> Unit) {
        val h = host ?: return
        h.removeAllViews()
        val sv = ScrollView(a).apply { isFillViewport = true }
        val c = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(a, 20), dp(a, 20), dp(a, 20), dp(a, 28)) }
        build(c)
        sv.addView(c, FrameLayout.LayoutParams(MATCH, WRAP))
        val w = minOf(a.resources.displayMetrics.widthPixels, dp(a, 560))
        h.addView(sv, FrameLayout.LayoutParams(w, MATCH, Gravity.CENTER_HORIZONTAL))
    }

    private fun bar(a: Activity, c: LinearLayout, title: String, onBack: () -> Unit) {
        val top = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        top.addView(ImageView(a).apply {
            setImageResource(R.drawable.ic_back); setColorFilter(a.getColor(R.color.mg_text)); setPadding(dp(a, 10), dp(a, 10), dp(a, 10), dp(a, 10))
            contentDescription = "Back"; isClickable = true; isFocusable = true; setOnClickListener { onBack() }
        }, LinearLayout.LayoutParams(dp(a, 44), dp(a, 44)))
        val t = VaultScreen.tv(a, title, 20f, R.color.mg_text, true)
        ViewCompat.setAccessibilityHeading(t, true)
        top.addView(t, LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginStart = dp(a, 6) })
        c.addView(top, lp())
    }

    private fun sec(a: Activity, c: LinearLayout, t: String) {
        val v = VaultScreen.tv(a, t, 11f, R.color.mg_primary).apply { typeface = Typeface.DEFAULT_BOLD; letterSpacing = 0.08f }
        ViewCompat.setAccessibilityHeading(v, true)
        c.addView(v, lp(dp(a, 24)))
    }

    private fun card(a: Activity) = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; background = VaultScreen.box(a) }

    private fun div(a: Activity) = View(a).apply { setBackgroundColor(a.getColor(R.color.mg_outline)) }

    private fun addDiv(a: Activity, card: LinearLayout) {
        card.addView(div(a), LinearLayout.LayoutParams(MATCH, dp(a, 1)).apply { marginStart = dp(a, 16) })
    }

    private fun row(a: Activity, title: String, value: String, click: () -> Unit): View {
        val r = LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(a, 16), dp(a, 10), dp(a, 12), dp(a, 10)); minimumHeight = dp(a, 56)
            isClickable = true; isFocusable = true
            contentDescription = if (value.isEmpty()) title else "$title, $value"
            setOnClickListener { click() }
        }
        val ta = a.obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackground))
        r.foreground = ta.getDrawable(0)
        ta.recycle()
        val tx = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        tx.addView(VaultScreen.tv(a, title, 15f, R.color.mg_text))
        if (value.isNotEmpty()) tx.addView(VaultScreen.tv(a, value, 12f, R.color.mg_text_dim).apply { setLineSpacing(0f, 1.1f) })
        r.addView(tx, LinearLayout.LayoutParams(0, WRAP, 1f))
        r.addView(ImageView(a).apply { setImageResource(R.drawable.ic_chevron); setColorFilter(a.getColor(R.color.mg_text_dim)); importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }, LinearLayout.LayoutParams(dp(a, 20), dp(a, 20)))
        return r
    }

    private fun info(a: Activity, title: String, value: String): View {
        val r = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(a, 16), dp(a, 12), dp(a, 16), dp(a, 12)) }
        r.contentDescription = "$title. $value"
        r.addView(VaultScreen.tv(a, title, 15f, R.color.mg_text))
        r.addView(VaultScreen.tv(a, value, 12f, R.color.mg_text_dim).apply { setLineSpacing(0f, 1.1f) }, lp(dp(a, 2)))
        return r
    }

    private fun delayName(ms: Long): String {
        val i = DELAYS.indexOf(ms)
        return if (i >= 0) DELAY_NAMES[i] else "After " + (ms / 60_000L) + " min"
    }

    // ---------- main page ----------

    private fun mainPage() {
        val a = act ?: return
        screen = "main"
        page(a) { c ->
            bar(a, c, "Vault settings") { dlg?.dismiss() }

            sec(a, c, "LOCKING")
            val k1 = card(a)
            k1.addView(row(a, "Auto-lock", delayName(VaultStore.autoLockMs(a))) { pickDelay() })
            if (a is FragmentActivity && AppLock.available(a)) {
                addDiv(a, k1)
                k1.addView(VaultScreen.switchRow(a, "Quick unlock", "Fingerprint or screen lock, this phone only", VaultStore.quickOn(a)) { on ->
                    if (on) { if (!VaultStore.quickEnable(a)) Util.toast(a, "Quick unlock could not be turned on") } else VaultStore.quickDisable(a)
                })
            }
            addDiv(a, k1)
            k1.addView(VaultScreen.switchRow(a, "Hide from recents", "Blank preview and no screenshots", VaultStore.hideRecents(a)) { on ->
                VaultStore.setHideRecents(a, on); applyFlag(a)
            })
            c.addView(k1, lp(dp(a, 8)))

            sec(a, c, "KEYS")
            val k2 = card(a)
            k2.addView(row(a, "Change passphrase", "Only the lock on the key changes. Nothing is re-encrypted.") { passPage() })
            addDiv(a, k2)
            k2.addView(row(a, "New recovery key", "The old recovery key stops working") { recoveryPage() })
            c.addView(k2, lp(dp(a, 8)))

            sec(a, c, "CHECKS")
            val k3 = card(a)
            k3.addView(row(a, "Verify vault", "Header, index and every file") { verifyPage() })
            addDiv(a, k3)
            val tries = VaultStore.failCount(a)
            k3.addView(info(a, "Wait after wrong tries",
                "After 5 wrong passphrases in a row the vault makes you wait 30 seconds, doubling each time up to 15 minutes. Wrong tries now: $tries."))
            addDiv(a, k3)
            val sz = VaultStore.indexBytes().toLong()
            val big = sz > 5_000_000L
            k3.addView(row(a, "List size: " + Util.fmtBytes(sz), if (big) "Large. Remove old history to keep opening fast." else "Remove old history from the vault") { trimDialog() })
            c.addView(k3, lp(dp(a, 8)))

            c.addView(VaultScreen.tv(a, "Your vault lives in the folder \"" + VaultScreen.folderLabel(a) + "\". App updates and uninstalling keep it. Quick unlock belongs to this phone and is forgotten after a reinstall: then use the passphrase or the recovery key.", 12f, R.color.mg_text_dim).apply { setLineSpacing(0f, 1.15f) }, lp(dp(a, 16)))
            c.addView(VaultScreen.btn(a, "Lock now", false) {
                bg.execute { VaultStore.lock(a); main.post { dlg?.dismiss() } }
            }, lp(dp(a, 16), MATCH, dp(a, 48)))
        }
    }

    private fun pickDelay() {
        val a = act ?: return
        MaterialAlertDialogBuilder(a).setTitle("Auto-lock")
            .setSingleChoiceItems(DELAY_NAMES, DELAYS.indexOf(VaultStore.autoLockMs(a))) { d, w ->
                VaultStore.setAutoLockMs(a, DELAYS[w]); d.dismiss(); if (dlg?.isShowing == true && screen == "main") mainPage()
            }.setNegativeButton("Cancel", null).show()
    }

    // ---------- change passphrase ----------

    private fun passPage() {
        val a = act ?: return
        screen = "pass"
        page(a) { c ->
            bar(a, c, "Change passphrase") { mainPage() }
            c.addView(VaultScreen.tv(a, "Your files are not touched. Only the lock on the vault key is replaced, so this takes a few seconds. Your recovery key keeps working.", 14f, R.color.mg_text_dim).apply { setLineSpacing(0f, 1.15f) }, lp(dp(a, 12)))
            val cur = VaultScreen.field(a, "Current passphrase", true)
            val n1 = VaultScreen.field(a, "New passphrase (at least 8 characters)", true)
            val n2 = VaultScreen.field(a, "Repeat new passphrase", true)
            val err = VaultScreen.tv(a, "", 13f, R.color.mg_error).apply { visibility = View.GONE }
            c.addView(cur, lp(dp(a, 20), MATCH, dp(a, 52)))
            c.addView(n1, lp(dp(a, 12), MATCH, dp(a, 52)))
            c.addView(n2, lp(dp(a, 12), MATCH, dp(a, 52)))
            c.addView(err, lp(dp(a, 8)))
            lateinit var go: MaterialButton
            go = VaultScreen.btn(a, "Change passphrase") {
                val o = chars(cur); val x = chars(n1); val y = chars(n2)
                val msg = when {
                    o.isEmpty() -> "Type your current passphrase."
                    x.size < 8 -> "Use at least 8 characters."
                    !x.contentEquals(y) -> "The two new passphrases are different."
                    else -> ""
                }
                VaultCrypto.wipe(y)
                if (msg.isNotEmpty()) { err.text = msg; err.visibility = View.VISIBLE; VaultCrypto.wipe(o); VaultCrypto.wipe(x); return@btn }
                err.visibility = View.GONE
                busy = true; go.isEnabled = false; go.text = "Changing (a few seconds)..."
                bg.execute {
                    val r = try { VaultStore.changePass(a, o, x) } catch (e: Exception) { VaultStore.Res(false, "The passphrase could not be changed. The old one still works.") }
                    VaultCrypto.wipe(x)
                    main.post {
                        busy = false
                        if (dlg?.isShowing != true) return@post
                        if (r.ok) { cur.setText(""); n1.setText(""); n2.setText(""); notice("Passphrase changed", "Use the new passphrase from now on. Quick unlock and your recovery key still work.") }
                        else { go.isEnabled = true; go.text = "Change passphrase"; err.text = r.msg; err.visibility = View.VISIBLE }
                    }
                }
            }
            c.addView(go, lp(dp(a, 20), MATCH, dp(a, 48)))
            if (a is FragmentActivity && VaultStore.quickOn(a) && AppLock.available(a)) {
                c.addView(VaultScreen.link(a, "Forgot passphrase and recovery key? Reset with fingerprint / screen lock") { resetPage() }, lp(dp(a, 8), MATCH, dp(a, 48)))
            }
            c.addView(VaultScreen.link(a, "Cancel") { mainPage() }, lp(dp(a, 8), MATCH, dp(a, 48)))
        }
    }

    // ---------- 0.0.453: reset passphrase + recovery key with fingerprint / screen lock ----------

    private fun resetPage() {
        val a = act as? FragmentActivity ?: return
        screen = "reset"
        page(a) { c ->
            bar(a, c, "Reset passphrase and recovery key") { passPage() }
            c.addView(VaultScreen.tv(a, "Use this only if you lost both. Your files are not touched. A NEW passphrase and a NEW recovery key replace the old ones at once (the old ones stop working). Your fingerprint or screen lock is asked first. The new recovery key is shown once: save it.", 14f, R.color.mg_text_dim).apply { setLineSpacing(0f, 1.15f) }, lp(dp(a, 12)))
            val n1 = VaultScreen.field(a, "New passphrase (at least 8 characters)", true)
            val n2 = VaultScreen.field(a, "Repeat new passphrase", true)
            val err = VaultScreen.tv(a, "", 13f, R.color.mg_error).apply { visibility = View.GONE }
            c.addView(n1, lp(dp(a, 20), MATCH, dp(a, 52)))
            c.addView(n2, lp(dp(a, 12), MATCH, dp(a, 52)))
            c.addView(err, lp(dp(a, 8)))
            lateinit var go: MaterialButton
            go = VaultScreen.btn(a, "Reset with fingerprint / screen lock") {
                val x = chars(n1); val y = chars(n2)
                val msg = when {
                    x.size < 8 -> "Use at least 8 characters."
                    !x.contentEquals(y) -> "The two new passphrases are different."
                    else -> ""
                }
                VaultCrypto.wipe(y)
                if (msg.isNotEmpty()) { err.text = msg; err.visibility = View.VISIBLE; VaultCrypto.wipe(x); return@btn }
                err.visibility = View.GONE
                Vault.unlock(a, "Reset vault passphrase") {
                    busy = true; go.isEnabled = false; go.text = "Working (a few seconds)..."
                    bg.execute {
                        val r = try { VaultStore.resetSecrets(a, x) } catch (e: Exception) { VaultStore.Res(false, "Could not reset. Nothing was changed.") }
                        main.post {
                            busy = false
                            if (dlg?.isShowing != true) return@post
                            if (r.ok) { n1.setText(""); n2.setText(""); showKey(r.recovery) }
                            else { go.isEnabled = true; go.text = "Reset with fingerprint / screen lock"; err.text = r.msg; err.visibility = View.VISIBLE }
                        }
                    }
                }
            }
            c.addView(go, lp(dp(a, 20), MATCH, dp(a, 48)))
            c.addView(VaultScreen.link(a, "Cancel") { passPage() }, lp(dp(a, 8), MATCH, dp(a, 48)))
        }
    }

    private fun notice(title: String, body: String) {
        val a = act ?: return
        screen = "notice"
        page(a) { c ->
            bar(a, c, title) { mainPage() }
            c.addView(VaultScreen.tv(a, body, 14f, R.color.mg_text).apply { setLineSpacing(0f, 1.15f) }, lp(dp(a, 16)))
            c.addView(VaultScreen.btn(a, "Back to settings") { mainPage() }, lp(dp(a, 24), MATCH, dp(a, 48)))
        }
    }

    // ---------- new recovery key ----------

    private fun recoveryPage() {
        val a = act ?: return
        screen = "recovery"
        page(a) { c ->
            bar(a, c, "New recovery key") { mainPage() }
            c.addView(VaultScreen.tv(a, "A new key replaces the old one at once: the old key will no longer open the vault. Type your passphrase to continue. The new key is shown once.", 14f, R.color.mg_text_dim).apply { setLineSpacing(0f, 1.15f) }, lp(dp(a, 12)))
            val cur = VaultScreen.field(a, "Passphrase", true)
            val err = VaultScreen.tv(a, "", 13f, R.color.mg_error).apply { visibility = View.GONE }
            c.addView(cur, lp(dp(a, 20), MATCH, dp(a, 52)))
            c.addView(err, lp(dp(a, 8)))
            lateinit var go: MaterialButton
            go = VaultScreen.btn(a, "Create new recovery key") {
                val o = chars(cur)
                if (o.isEmpty()) { err.text = "Type your passphrase."; err.visibility = View.VISIBLE; return@btn }
                err.visibility = View.GONE
                busy = true; go.isEnabled = false; go.text = "Working (a few seconds)..."
                bg.execute {
                    val r = try { VaultStore.regenRecovery(a, o) } catch (e: Exception) { VaultStore.Res(false, "The recovery key could not be changed. The old one still works.") }
                    main.post {
                        busy = false
                        if (dlg?.isShowing != true) return@post
                        if (r.ok) { cur.setText(""); showKey(r.recovery) }
                        else { go.isEnabled = true; go.text = "Create new recovery key"; err.text = r.msg; err.visibility = View.VISIBLE }
                    }
                }
            }
            c.addView(go, lp(dp(a, 20), MATCH, dp(a, 48)))
            c.addView(VaultScreen.link(a, "Cancel") { mainPage() }, lp(dp(a, 8), MATCH, dp(a, 48)))
        }
    }

    private fun showKey(key: String) {
        val a = act ?: return
        screen = "showkey"
        page(a) { c ->
            val t = VaultScreen.tv(a, "Save your new recovery key", 22f, R.color.mg_text, true)
            ViewCompat.setAccessibilityHeading(t, true)
            c.addView(t, lp(dp(a, 8)))
            c.addView(VaultScreen.tv(a, "This key is already active and is shown once. Write it down or keep it somewhere safe. The old key no longer works.", 14f, R.color.mg_text_dim).apply { setLineSpacing(0f, 1.15f) }, lp(dp(a, 8)))
            val groups = key.split("-")
            val shown = groups.take(4).joinToString("-") + "\n" + groups.drop(4).joinToString("-")
            val box = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; background = VaultScreen.box(a); setPadding(dp(a, 16), dp(a, 16), dp(a, 16), dp(a, 16)) }
            box.addView(VaultScreen.tv(a, shown, 18f, R.color.mg_text).apply { typeface = Typeface.MONOSPACE; setTextIsSelectable(true); gravity = Gravity.CENTER; setLineSpacing(0f, 1.2f) }, lp())
            c.addView(box, lp(dp(a, 20)))
            val r = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL }
            r.addView(VaultScreen.btn(a, "Copy", false) {
                val cm = a.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("Vault recovery key", key))
                Util.toast(a, "Copied. Paste it somewhere safe, then clear the clipboard.")
            }, LinearLayout.LayoutParams(0, dp(a, 48), 1f).apply { marginEnd = dp(a, 6) })
            r.addView(VaultScreen.btn(a, "Share", false) {
                try { a.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, key), "Save recovery key")) } catch (e: Exception) { Util.toast(a, "Nothing can receive it") }
            }, LinearLayout.LayoutParams(0, dp(a, 48), 1f).apply { marginStart = dp(a, 6) })
            c.addView(r, lp(dp(a, 12)))
            val saved = MaterialSwitch(a).apply { text = "I saved my new recovery key"; setTextColor(a.getColor(R.color.mg_text)); textSize = 14f }
            c.addView(saved, lp(dp(a, 16)))
            val go = VaultScreen.btn(a, "Done") { if (VaultStore.isUnlocked()) mainPage() else dlg?.dismiss() }
            go.isEnabled = false
            saved.setOnCheckedChangeListener { _, on -> go.isEnabled = on }
            c.addView(go, lp(dp(a, 20), MATCH, dp(a, 48)))
        }
    }

    // ---------- verify ----------

    private fun verifyPage() {
        val a = act ?: return
        screen = "verifypick"
        page(a) { c ->
            bar(a, c, "Verify vault") { mainPage() }
            c.addView(VaultScreen.tv(a, "Quick check: key file, crypto self-test, the list, and that every saved page and file is in the folder.\n\nFull check: also reads every file back and proves it is not damaged. This can take a while for big files.", 14f, R.color.mg_text_dim).apply { setLineSpacing(0f, 1.15f) }, lp(dp(a, 12)))
            c.addView(VaultScreen.btn(a, "Quick check") { runVerify(false) }, lp(dp(a, 20), MATCH, dp(a, 48)))
            c.addView(VaultScreen.btn(a, "Full check", false) { runVerify(true) }, lp(dp(a, 8), MATCH, dp(a, 48)))
        }
    }

    private fun runVerify(full: Boolean) {
        val a = act ?: return
        if (busy) return
        busy = true; cancel.set(false)
        screen = "verifying"
        lateinit var status: TextView
        page(a) { c ->
            bar(a, c, "Checking the vault") { cancel.set(true) }
            status = VaultScreen.tv(a, "Starting...", 14f, R.color.mg_text_dim)
            c.addView(status, lp(dp(a, 16)))
            c.addView(ProgressBar(a, null, android.R.attr.progressBarStyleHorizontal).apply { isIndeterminate = true }, lp(dp(a, 16)))
            c.addView(VaultScreen.btn(a, "Cancel", false) { cancel.set(true); status.text = "Stopping..." }, lp(dp(a, 20), MATCH, dp(a, 48)))
        }
        bg.execute {
            val rep = try { verify(a, full) { t -> main.post { if (busy && screen == "verifying") status.text = t } } } catch (e: Exception) { listOf("The check stopped unexpectedly. Nothing was changed.") }
            main.post { busy = false; if (dlg?.isShowing == true) report(rep) }
        }
    }

    /** BLOCKING. Read-only on the vault (only flushes a pending index save). Counts only, no titles. */
    private fun verify(a: Activity, full: Boolean, say: (String) -> Unit): List<String> {
        val out = ArrayList<String>()
        if (!VaultStore.isUnlocked()) return listOf("The vault is locked. Nothing was checked.")
        say("Key file and crypto...")
        out.add(if (VaultStore.currentHeader() != null) "Key file: OK" else "Key file: FAILED to load")
        val st = VaultCrypto.selfTest() // null = OK, else a short fixed reason
        out.add(if (st == null) "Crypto self-test: OK" else "Crypto self-test: FAILED ($st)")
        say("List...")
        try { VaultStore.flush(a) } catch (e: Exception) { }
        out.add(if (VaultStore.isSaved()) "List: saved, " + Util.fmtBytes(VaultStore.indexBytes().toLong()) else "List: could not be saved yet (it will retry), FAILED")
        val list = VaultStore.items().filter { it.optString("blob").isNotEmpty() }
        var missing = 0; var bad = 0; var done = 0
        for (o in list) {
            if (cancel.get()) { out.add("Stopped: the check was cancelled."); break }
            if (!VaultStore.isUnlocked()) { out.add("Stopped: the vault was locked."); break }
            done++
            say((if (full) "Reading file " else "Checking file ") + done + " of " + list.size + "...")
            val id = o.optString("blob")
            if (!VaultStore.blobExists(a, id)) { missing++; continue }
            if (full && !readBack(a, id) && !cancel.get()) bad++
        }
        out.add("Pages and files: " + done + " of " + list.size + " checked, " + (if (missing == 0) "none missing" else "$missing MISSING from the folder"))
        if (full) out.add(if (bad == 0) "Read back: no damaged file found" else "$bad file(s) could not be read back (damaged or unreadable)")
        out.add("Bookmarks " + VaultStore.count("bm") + ", history " + VaultStore.count("hist") + ", pages " + VaultStore.count("page") + ", files " + VaultStore.count("file"))
        return out
    }

    private fun readBack(a: Context, id: String): Boolean {
        val r = try { VaultStore.openBlobIn(a, id) } catch (e: Exception) { null } ?: return false
        return try {
            var n = 0L
            r.copyTo(object : OutputStream() {
                override fun write(b: Int) { n++ }
                override fun write(b: ByteArray, off: Int, len: Int) { if (cancel.get()) throw IOException("cancelled"); n += len }
            })
            n == r.plainSize
        } catch (e: Exception) { false } finally { r.close() }
    }

    private fun report(lines: List<String>) {
        val a = act ?: return
        screen = "report"
        page(a) { c ->
            bar(a, c, "Vault check") { mainPage() }
            for (l in lines) {
                val bad = l.contains("FAILED") || l.contains("MISSING") || l.contains("could not")
                c.addView(VaultScreen.tv(a, l, 14f, if (bad) R.color.mg_error else R.color.mg_text).apply { setLineSpacing(0f, 1.1f) }, lp(dp(a, 10)))
            }
            c.addView(VaultScreen.btn(a, "Back to settings") { mainPage() }, lp(dp(a, 24), MATCH, dp(a, 48)))
        }
    }

    // ---------- index size: remove old history ----------

    private fun trimDialog() {
        val a = act ?: return
        if (VaultStore.count("hist") == 0) { Util.toast(a, "There is no history in the vault"); return }
        val names = arrayOf("Older than 1 year", "Older than 90 days", "Older than 30 days")
        val days = longArrayOf(365L, 90L, 30L)
        MaterialAlertDialogBuilder(a).setTitle("Remove old history").setItems(names) { _, w ->
            val cutoff = System.currentTimeMillis() - days[w] * 86_400_000L
            val n = VaultStore.items("hist").count { it.optLong("time") < cutoff }
            if (n == 0) { Util.toast(a, "No history that old"); return@setItems }
            MaterialAlertDialogBuilder(a).setTitle("Remove $n history entries?")
                .setMessage("They are deleted from the vault and cannot be restored.")
                .setPositiveButton("Remove") { _, _ ->
                    bg.execute {
                        val k = VaultStore.trimHistory(a, cutoff)
                        main.post { Util.toast(a, "$k removed"); if (dlg?.isShowing == true && screen == "main") mainPage() }
                    }
                }.setNegativeButton("Cancel", null).show()
        }.setNegativeButton("Cancel", null).show()
    }
}
