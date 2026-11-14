package com.jabra.mgb

import android.animation.ValueAnimator
import android.app.Activity
import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import android.text.InputType
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.FragmentActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import java.util.concurrent.Executors

/**
 * V3: Vault shell UI. One full-screen Dialog that swaps pages by state:
 *   NONE -> intro + folder picker -> passphrase -> recovery key (shown once) -> home
 *   FOLDER_MISSING -> pick the same folder again
 *   LOCKED -> unlock (passphrase, quick unlock, recovery key)
 *   UNLOCKED -> home: seal ring, four category tiles (empty until V4-V7), quick-unlock + hide-from-recents switches, Lock now.
 * All folder / crypto work runs on a background thread (VaultStore blocks). Keys and passphrases are never shown in toasts or logs.
 * Picking an existing vault folder on a fresh install lands on the unlock page (restore of items into the browser is V10).
 */
object VaultScreen {
    private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

    private val main = Handler(Looper.getMainLooper())
    private val bg = Executors.newSingleThreadExecutor()
    private var dlg: Dialog? = null
    private var host: FrameLayout? = null
    private var act: Activity? = null
    private var screen = ""
    private var offerRestore = false // V10: set when an existing vault was opened from its folder; the restore page opens after unlock

    /** Short state text for the menu / settings row. */
    fun summary(c: Context): String = when (VaultStore.state(c)) {
        VaultStore.State.NONE -> "Not set up"
        VaultStore.State.FOLDER_MISSING -> "Folder missing"
        VaultStore.State.LOCKED -> "Locked"
        VaultStore.State.UNLOCKED -> "Open"
    }

    fun open(a: Activity) {
        if (a.isFinishing || a.isDestroyed) return
        if (dlg?.isShowing == true) return
        act = a
        val d = Dialog(a, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        val h = FrameLayout(a)
        h.setBackgroundColor(a.getColor(R.color.mg_bg))
        host = h
        d.setContentView(h, ViewGroup.LayoutParams(MATCH, MATCH))
        d.window?.apply {
            setBackgroundDrawable(ColorDrawable(a.getColor(R.color.mg_bg)))
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            if (VaultStore.hideRecents(a)) setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        }
        d.setOnDismissListener { if (dlg === d) { dlg = null; host = null; screen = "" } }
        dlg = d
        try { d.show() } catch (e: Exception) { dlg = null; return }
        message("Checking vault...")
        bg.execute {
            val st = VaultStore.check(a)
            main.post { if (dlg === d) render(st) }
        }
    }

    /** MainActivity.onStart calls this after VaultStore.onAppStart: if the vault auto-locked while the screen is open, show the unlock page. */
    fun refresh() {
        VaultItems.refresh() // V4: a locked vault must not leave its list open
        VaultExit.refresh() // V9
        VaultRestore.refresh() // V10
        VaultSettings.refresh() // V11
        if (dlg?.isShowing != true) return
        if (screen == "home" && !VaultStore.isUnlocked()) unlockView()
    }

    private fun close() { dlg?.dismiss() }

    /** V4: closes the list dialog and the vault dialog (used after "open in tab"). */
    fun closeAll() { VaultItems.closeAll(); close() }

    private fun render(st: VaultStore.State) {
        when (st) {
            VaultStore.State.NONE -> setupIntro()
            VaultStore.State.FOLDER_MISSING -> folderMissing()
            VaultStore.State.LOCKED -> unlockView()
            VaultStore.State.UNLOCKED -> home(false)
        }
    }

    // ---------- small view helpers (same look as ToolsSheet / AppLock) ----------

    internal fun dp(a: Context, v: Int) = Util.dp(a, v)
    private fun col(a: Context, id: Int) = a.getColor(id)

    internal fun box(a: Context, radius: Int = 20, fill: Int = R.color.mg_surface) = GradientDrawable().apply {
        setColor(col(a, fill)); cornerRadius = dp(a, radius).toFloat(); setStroke(dp(a, 1), col(a, R.color.mg_outline))
    }

    internal fun tv(a: Context, t: String, sp: Float, color: Int, medium: Boolean = false) = TextView(a).apply {
        text = t; setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, sp); setTextColor(col(a, color))
        if (medium) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }

    internal fun lp(top: Int = 0, w: Int = MATCH, h: Int = WRAP) = LinearLayout.LayoutParams(w, h).apply { topMargin = top }

    private fun dash(a: Context): View = View(a).apply {
        background = GradientDrawable().apply { setColor(col(a, R.color.mg_primary)); cornerRadius = dp(a, 2).toFloat() }
    }

    internal fun btn(a: Context, t: String, filled: Boolean = true, onClick: () -> Unit): MaterialButton {
        val b = if (filled) MaterialButton(a) else MaterialButton(a, null, com.google.android.material.R.attr.materialButtonOutlinedStyle)
        b.text = t; b.isAllCaps = false; b.cornerRadius = dp(a, 14); b.insetTop = 0; b.insetBottom = 0
        if (!filled) { b.strokeColor = ColorStateList.valueOf(col(a, R.color.mg_outline)); b.setTextColor(col(a, R.color.mg_primary)) }
        b.setOnClickListener { onClick() }
        return b
    }

    internal fun link(a: Context, t: String, onClick: () -> Unit): MaterialButton {
        val b = MaterialButton(a)
        b.text = t; b.isAllCaps = false; b.insetTop = 0; b.insetBottom = 0; b.elevation = 0f; b.stateListAnimator = null
        b.backgroundTintList = ColorStateList.valueOf(Color.TRANSPARENT)
        b.setTextColor(col(a, R.color.mg_primary))
        b.setOnClickListener { onClick() }
        return b
    }

    internal fun field(a: Context, hint: String, password: Boolean): EditText = EditText(a).apply {
        this.hint = hint
        setHintTextColor(col(a, R.color.mg_text_dim)); setTextColor(col(a, R.color.mg_text)); textSize = 16f
        inputType = if (password) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        isSingleLine = true
        background = box(a, 14)
        setPadding(dp(a, 14), 0, dp(a, 14), 0)
        minHeight = dp(a, 52)
    }

    private fun text(e: EditText): CharArray { val s = e.text; return CharArray(s.length) { s[it] } }

    private fun page(a: Activity, build: (LinearLayout) -> Unit) {
        val h = host ?: return
        h.removeAllViews()
        val sv = ScrollView(a).apply { isFillViewport = true }
        val c = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(a, 20), dp(a, 28), dp(a, 20), dp(a, 28)) }
        build(c)
        sv.addView(c, FrameLayout.LayoutParams(MATCH, WRAP))
        val w = minOf(a.resources.displayMetrics.widthPixels, dp(a, 560))
        h.addView(sv, FrameLayout.LayoutParams(w, MATCH, Gravity.CENTER_HORIZONTAL))
    }

    private fun message(t: String) {
        val a = act ?: return
        screen = "msg"
        page(a) { c ->
            c.gravity = Gravity.CENTER
            c.addView(tv(a, t, 15f, R.color.mg_text_dim))
        }
    }

    private fun head(a: Activity, c: LinearLayout, t: String, sub: String) {
        c.addView(dash(a), lp(0, dp(a, 18), dp(a, 3)))
        c.addView(tv(a, t, 24f, R.color.mg_text, true).also { ViewCompat.setAccessibilityHeading(it, true) }, lp(dp(a, 10)))
        if (sub.isNotEmpty()) c.addView(tv(a, sub, 14f, R.color.mg_text_dim).apply { setLineSpacing(0f, 1.15f) }, lp(dp(a, 8)))
    }

    internal fun folderLabel(c: Context): String {
        val t = VaultStore.treeUri(c) ?: return "Vault folder"
        return try { DocumentsContract.getTreeDocumentId(t).substringAfterLast(':').substringAfterLast('/').ifEmpty { "Phone storage" } } catch (e: Exception) { "Vault folder" }
    }

    // ---------- seal ring ----------

    private class Seal(c: Context, private val icon: android.graphics.drawable.Drawable?) : View(c) {
        var p = 1f
            set(v) { field = v; invalidate() }
        private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeWidth = Util.dp(c, 6).toFloat() }
        private val cPrimary = c.getColor(R.color.mg_primary)
        private val cTrack = c.getColor(R.color.mg_outline)
        private val r = RectF()
        override fun onDraw(cv: Canvas) {
            val s = ring.strokeWidth
            r.set(s, s, width - s, height - s)
            ring.color = cTrack
            cv.drawOval(r, ring)
            ring.color = cPrimary
            cv.drawArc(r, -90f, 360f * p, false, ring)
            icon?.let {
                val sz = (width * 0.38f).toInt()
                val l = (width - sz) / 2; val t = (height - sz) / 2
                it.setBounds(l, t, l + sz, t + sz); it.draw(cv)
            }
        }
    }

    private fun seal(a: Activity, p: Float): Seal {
        val ic = a.getDrawable(R.drawable.ic_vault)?.mutate()
        ic?.setTint(col(a, R.color.mg_primary))
        return Seal(a, ic).also { it.p = p; it.contentDescription = "Vault" }
    }

    // ---------- NONE: setup ----------

    private fun setupIntro() {
        val a = act ?: return
        screen = "intro"
        page(a) { c ->
            head(a, c, "Create your vault", "Keep bookmarks, history, saved pages and downloaded files in one encrypted folder that you choose.")
            c.addView(tv(a, "The folder stays on your phone after app updates and even after you uninstall Thazir. Before uninstalling you can choose what to keep and what to delete forever.", 14f, R.color.mg_text_dim).apply { setLineSpacing(0f, 1.15f) }, lp(dp(a, 12)))
            c.addView(tv(a, "Tip: create a new folder (for example ThazirVault) and pick it. Android does not allow the main Downloads or Documents folder.", 13f, R.color.mg_text_dim).apply { setLineSpacing(0f, 1.15f) }, lp(dp(a, 12)))
            c.addView(btn(a, "Choose vault folder") { pickFolder(a) }, lp(dp(a, 24), MATCH, dp(a, 48)))
            c.addView(tv(a, "Reinstalled Thazir? Your old vault folder still has everything.", 13f, R.color.mg_text_dim).apply { setLineSpacing(0f, 1.15f) }, lp(dp(a, 20)))
            c.addView(btn(a, "I already have a vault", false) { pickExisting(a) }, lp(dp(a, 8), MATCH, dp(a, 48)))
            c.addView(link(a, "Close") { close() }, lp(dp(a, 8), MATCH, dp(a, 48)))
        }
    }

    private fun pickFolder(a: Activity) {
        BrowserExtras.pickVaultFolder { uri ->
            if (dlg?.isShowing != true) return@pickVaultFolder
            message("Checking folder...")
            bg.execute {
                val ok = VaultStore.bind(a, uri)
                val st = if (ok) VaultStore.check(a) else VaultStore.State.NONE
                main.post {
                    if (dlg?.isShowing != true) return@post
                    when {
                        !ok -> { Util.toast(a, VaultStore.lastError); render(VaultStore.state(a)) }
                        st == VaultStore.State.LOCKED -> { Util.toast(a, "Found an existing vault in this folder"); offerRestore = true; unlockView() }
                        else -> setupPass()
                    }
                }
            }
        }
    }

    /** V10: "I already have a vault". Checks the picked folder first; only a folder that holds vault.hdr is remembered, nothing is created. */
    private fun pickExisting(a: Activity) {
        BrowserExtras.pickVaultFolder { uri ->
            if (dlg?.isShowing != true) return@pickVaultFolder
            message("Looking for your vault...")
            bg.execute {
                val found = VaultStore.probe(a, uri)
                val ok = found == 1 && VaultStore.bind(a, uri)
                val st = if (ok) VaultStore.check(a) else VaultStore.State.NONE
                main.post {
                    if (dlg?.isShowing != true) return@post
                    when {
                        found == 2 -> { Util.toast(a, "This vault was made by a newer Thazir. Update the app first."); setupIntro() }
                        found == 0 -> { Util.toast(a, "No vault found directly in this folder. Pick the folder that holds vault.hdr. Nothing was created."); setupIntro() }
                        found != 1 -> { Util.toast(a, "This folder cannot be read. Pick another one."); setupIntro() }
                        !ok -> { Util.toast(a, VaultStore.lastError); setupIntro() }
                        st == VaultStore.State.LOCKED -> { offerRestore = true; unlockView() }
                        else -> { Util.toast(a, "This vault cannot be opened. Pick the folder again."); render(VaultStore.state(a)) }
                    }
                }
            }
        }
    }

    /** V10: after a successful unlock: home, then (once, only for a vault opened from an existing folder) the restore page. */
    private fun unlocked(a: Activity) {
        home(true)
        if (offerRestore) { offerRestore = false; VaultRestore.open(a) { if (dlg?.isShowing == true && VaultStore.isUnlocked()) home(false) } }
    }

    private fun strength(p: String): String {
        var k = 0
        if (p.any { it.isLowerCase() }) k++
        if (p.any { it.isUpperCase() }) k++
        if (p.any { it.isDigit() }) k++
        if (p.any { !it.isLetterOrDigit() }) k++
        return when {
            p.length < 8 -> "Too short: use at least 8 characters"
            p.length >= 14 || (p.length >= 10 && k >= 3) -> "Strong"
            else -> "OK: longer is stronger"
        }
    }

    private fun setupPass() {
        val a = act ?: return
        screen = "pass"
        page(a) { c ->
            head(a, c, "Choose a passphrase", "It protects everything in the vault. Thazir cannot reset it. If you forget it, only the recovery key (next step) can open the vault.")
            val p1 = field(a, "Passphrase", true)
            val p2 = field(a, "Repeat passphrase", true)
            val hint = tv(a, "", 12f, R.color.mg_text_dim)
            val err = tv(a, "", 13f, R.color.mg_error).apply { visibility = View.GONE }
            p1.doAfterTextChanged { hint.text = if (it.isNullOrEmpty()) "" else strength(it.toString()) }
            c.addView(p1, lp(dp(a, 20), MATCH, dp(a, 52)))
            c.addView(hint, lp(dp(a, 4)))
            c.addView(p2, lp(dp(a, 12), MATCH, dp(a, 52)))
            c.addView(err, lp(dp(a, 8)))
            lateinit var go: MaterialButton
            go = btn(a, "Create vault") {
                val x = text(p1); val y = text(p2)
                val msg = when {
                    x.size < 8 -> "Use at least 8 characters."
                    !x.contentEquals(y) -> "The two passphrases are different."
                    else -> ""
                }
                if (msg.isNotEmpty()) { err.text = msg; err.visibility = View.VISIBLE; VaultCrypto.wipe(x); VaultCrypto.wipe(y); return@btn }
                VaultCrypto.wipe(y)
                err.visibility = View.GONE
                go.isEnabled = false; go.text = "Creating (about a second)..."
                bg.execute {
                    val r = VaultStore.create(a, x) // wipes x
                    main.post {
                        if (dlg?.isShowing != true) return@post
                        if (r.ok) { p1.setText(""); p2.setText(""); recoveryView(r.recovery) }
                        else { go.isEnabled = true; go.text = "Create vault"; err.text = r.msg; err.visibility = View.VISIBLE }
                    }
                }
            }
            c.addView(go, lp(dp(a, 20), MATCH, dp(a, 48)))
            c.addView(link(a, "Choose another folder") { pickFolder(a) }, lp(dp(a, 8), MATCH, dp(a, 48)))
        }
    }

    private fun recoveryView(key: String) {
        val a = act ?: return
        screen = "recovery"
        page(a) { c ->
            head(a, c, "Save your recovery key", "This is the only way to open the vault if you forget the passphrase or reinstall Thazir. It is shown once. Write it down or store it in a safe place.")
            val groups = key.split("-")
            val shown = groups.take(4).joinToString("-") + "\n" + groups.drop(4).joinToString("-")
            val card = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; background = box(a); setPadding(dp(a, 16), dp(a, 16), dp(a, 16), dp(a, 16)) }
            card.addView(tv(a, shown, 18f, R.color.mg_text).apply { typeface = Typeface.MONOSPACE; setTextIsSelectable(true); gravity = Gravity.CENTER; setLineSpacing(0f, 1.2f) }, lp())
            c.addView(card, lp(dp(a, 20)))
            val row = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL }
            row.addView(btn(a, "Copy", false) {
                val cm = a.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("Vault recovery key", key))
                Util.toast(a, "Copied. Paste it somewhere safe, then clear the clipboard.")
            }, LinearLayout.LayoutParams(0, dp(a, 48), 1f).apply { marginEnd = dp(a, 6) })
            row.addView(btn(a, "Share", false) {
                try { a.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, key), "Save recovery key")) } catch (e: Exception) { Util.toast(a, "Nothing can receive it") }
            }, LinearLayout.LayoutParams(0, dp(a, 48), 1f).apply { marginStart = dp(a, 6) })
            c.addView(row, lp(dp(a, 12)))

            val canQuick = a is FragmentActivity && AppLock.available(a)
            val sw = MaterialSwitch(a).apply { text = "Open with fingerprint or screen lock (this phone only)"; setTextColor(col(a, R.color.mg_text)); textSize = 14f }
            if (canQuick) c.addView(sw, lp(dp(a, 16)))
            val saved = MaterialSwitch(a).apply { text = "I saved my recovery key"; setTextColor(col(a, R.color.mg_text)); textSize = 14f }
            c.addView(saved, lp(dp(a, 12)))
            val go = btn(a, "Continue") {
                if (canQuick && sw.isChecked && !VaultStore.quickEnable(a)) Util.toast(a, "Quick unlock could not be turned on")
                home(true)
            }
            go.isEnabled = false
            saved.setOnCheckedChangeListener { _, on -> go.isEnabled = on }
            c.addView(go, lp(dp(a, 20), MATCH, dp(a, 48)))
        }
    }

    // ---------- FOLDER_MISSING ----------

    private fun folderMissing() {
        val a = act ?: return
        screen = "missing"
        page(a) { c ->
            head(a, c, "Vault folder not found", VaultStore.lastError.ifEmpty { "The folder or its permission is gone. Pick the same folder again to continue." })
            c.addView(tv(a, "Nothing was deleted. Thazir will never create a new empty vault by itself.", 13f, R.color.mg_text_dim), lp(dp(a, 10)))
            c.addView(btn(a, "Pick the folder again") { pickFolder(a) }, lp(dp(a, 24), MATCH, dp(a, 48)))
            c.addView(link(a, "Forget this folder") {
                bg.execute { VaultStore.unbind(a); main.post { if (dlg?.isShowing == true) setupIntro() } }
            }, lp(dp(a, 8), MATCH, dp(a, 48)))
            c.addView(link(a, "Close") { close() }, lp(0, MATCH, dp(a, 48)))
        }
    }

    // ---------- LOCKED ----------

    private fun unlockView() {
        val a = act ?: return
        screen = "unlock"
        page(a) { c ->
            val ring = seal(a, 0.75f).also { it.contentDescription = "Vault is locked" }
            c.addView(ring, lp(0, dp(a, 96), dp(a, 96)).apply { gravity = Gravity.CENTER_HORIZONTAL })
            c.addView(tv(a, "Vault is locked", 22f, R.color.mg_text, true).apply { gravity = Gravity.CENTER }, lp(dp(a, 16)))
            c.addView(tv(a, "Folder: " + folderLabel(a), 13f, R.color.mg_text_dim).apply { gravity = Gravity.CENTER }, lp(dp(a, 4)))
            val pw = field(a, "Passphrase", true)
            val err = tv(a, "", 13f, R.color.mg_error).apply { visibility = View.GONE }
            c.addView(pw, lp(dp(a, 24), MATCH, dp(a, 52)))
            c.addView(err, lp(dp(a, 8)))
            val waitMs = VaultStore.lockoutLeftMs(a) // V11
            if (waitMs > 0L) { err.text = "Too many wrong tries. Wait " + VaultStore.waitText(waitMs) + " and try again."; err.visibility = View.VISIBLE }
            lateinit var go: MaterialButton
            go = btn(a, "Unlock") {
                val x = text(pw)
                if (x.isEmpty()) return@btn
                go.isEnabled = false; go.text = "Unlocking..."
                bg.execute {
                    val r = VaultStore.unlock(a, x) // wipes x
                    main.post {
                        if (dlg?.isShowing != true) return@post
                        if (r.ok) { pw.setText(""); unlocked(a) }
                        else { go.isEnabled = true; go.text = "Unlock"; err.text = r.msg; err.visibility = View.VISIBLE }
                    }
                }
            }
            c.addView(go, lp(dp(a, 12), MATCH, dp(a, 48)))
            if (a is FragmentActivity && VaultStore.quickOn(a) && AppLock.available(a)) {
                c.addView(btn(a, "Use fingerprint or screen lock", false) { quick(a, err) }, lp(dp(a, 8), MATCH, dp(a, 48)))
            }
            c.addView(link(a, "Use recovery key instead") { recoveryUnlock() }, lp(dp(a, 8), MATCH, dp(a, 48)))
            c.addView(link(a, "Close") { close() }, lp(0, MATCH, dp(a, 48)))
        }
    }

    private fun quick(a: FragmentActivity, err: TextView) {
        Vault.unlock(a, "Open vault") {
            bg.execute {
                val k = VaultStore.quickKey(a)
                val r = if (k == null) VaultStore.Res(false, "Quick unlock is not set up on this phone. Use your passphrase.")
                else VaultStore.unlockWithKey(a, k).also { VaultCrypto.wipe(k) }
                if (k == null) VaultStore.quickDisable(a)
                main.post {
                    if (dlg?.isShowing != true) return@post
                    if (r.ok) unlocked(a) else { err.text = r.msg; err.visibility = View.VISIBLE }
                }
            }
        }
    }

    private fun recoveryUnlock() {
        val a = act ?: return
        screen = "recunlock"
        page(a) { c ->
            head(a, c, "Use recovery key", "Type the 32-character key you saved when you created the vault. Dashes and spaces do not matter.")
            val f = field(a, "XXXX-XXXX-XXXX-XXXX-XXXX-XXXX-XXXX-XXXX", false)
            val err = tv(a, "", 13f, R.color.mg_error).apply { visibility = View.GONE }
            c.addView(f, lp(dp(a, 20), MATCH, dp(a, 52)))
            c.addView(err, lp(dp(a, 8)))
            lateinit var go: MaterialButton
            go = btn(a, "Unlock with recovery key") {
                val k = f.text.toString()
                go.isEnabled = false; go.text = "Unlocking..."
                bg.execute {
                    val r = VaultStore.unlockRecovery(a, k)
                    main.post {
                        if (dlg?.isShowing != true) return@post
                        if (r.ok) { f.setText(""); unlocked(a) }
                        else { go.isEnabled = true; go.text = "Unlock with recovery key"; err.text = r.msg; err.visibility = View.VISIBLE }
                    }
                }
            }
            c.addView(go, lp(dp(a, 12), MATCH, dp(a, 48)))
            c.addView(link(a, "Back") { unlockView() }, lp(dp(a, 8), MATCH, dp(a, 48)))
        }
    }

    // ---------- UNLOCKED: home ----------

    private fun tile(a: Activity, icon: Int, title: String, type: String): View {
        val n = VaultStore.count(type)
        val b = VaultStore.bytes(type)
        val info = (if (n == 1) "1 item" else "$n items") + (if (b > 0) " - " + Util.fmtBytes(b) else "")
        val t = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL; background = box(a); setPadding(dp(a, 16), dp(a, 16), dp(a, 16), dp(a, 16)); minimumHeight = dp(a, 96)
            isClickable = true; isFocusable = true
            contentDescription = "$title, $info"
            setOnClickListener {
                VaultItems.open(a, type) { if (dlg?.isShowing == true && VaultStore.isUnlocked()) home(false) } // V4/V5/V6/V7
            }
        }
        t.addView(ImageView(a).apply { setImageResource(icon); setColorFilter(col(a, R.color.mg_primary)) }, LinearLayout.LayoutParams(dp(a, 24), dp(a, 24)))
        t.addView(tv(a, title, 15f, R.color.mg_text, true), lp(dp(a, 10)))
        t.addView(tv(a, info, 12f, R.color.mg_text_dim), lp(dp(a, 2)))
        return t
    }

    internal fun switchRow(a: Activity, title: String, info: String, on: Boolean, change: (Boolean) -> Unit): View {
        val r = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(a, 16), dp(a, 10), dp(a, 12), dp(a, 10)); minimumHeight = dp(a, 56) }
        val tx = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        tx.addView(tv(a, title, 15f, R.color.mg_text))
        tx.addView(tv(a, info, 12f, R.color.mg_text_dim))
        r.addView(tx, LinearLayout.LayoutParams(0, WRAP, 1f))
        val sw = MaterialSwitch(a)
        sw.isChecked = on
        sw.contentDescription = title
        sw.setOnCheckedChangeListener { _, v -> change(v) }
        r.addView(sw)
        return r
    }

    private fun home(animate: Boolean) {
        val a = act ?: return
        screen = "home"
        page(a) { c ->
            val top = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            top.addView(ImageView(a).apply {
                setImageResource(R.drawable.ic_back); setColorFilter(col(a, R.color.mg_text)); setPadding(dp(a, 10), dp(a, 10), dp(a, 10), dp(a, 10))
                contentDescription = "Close vault screen"; isClickable = true; isFocusable = true; setOnClickListener { close() }
            }, LinearLayout.LayoutParams(dp(a, 44), dp(a, 44)))
            top.addView(tv(a, "Vault", 20f, R.color.mg_text, true), LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginStart = dp(a, 6) })
            top.addView(ImageView(a).apply {
                setImageResource(R.drawable.ic_lock); setColorFilter(col(a, R.color.mg_primary)); setPadding(dp(a, 10), dp(a, 10), dp(a, 10), dp(a, 10))
                contentDescription = "Lock vault now"; isClickable = true; isFocusable = true; setOnClickListener { lockNow(a) }
            }, LinearLayout.LayoutParams(dp(a, 44), dp(a, 44)))
            c.addView(top, lp())

            val ring = seal(a, if (animate) 0.75f else 1f).also { it.contentDescription = "Vault is open and sealed" }
            c.addView(ring, lp(dp(a, 12), dp(a, 120), dp(a, 120)).apply { gravity = Gravity.CENTER_HORIZONTAL })
            c.addView(tv(a, "Sealed", 18f, R.color.mg_text, true).apply { gravity = Gravity.CENTER }, lp(dp(a, 12)))
            c.addView(tv(a, "Folder: " + folderLabel(a), 13f, R.color.mg_text_dim).apply { gravity = Gravity.CENTER }, lp(dp(a, 2)))
            if (animate) {
                ring.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                if (TabPrefs.reduce(a)) ring.p = 1f
                else ValueAnimator.ofFloat(0.75f, 1f).apply { duration = 250; addUpdateListener { ring.p = it.animatedValue as Float } }.start()
            }

            c.addView(tv(a, "CONTENTS", 11f, R.color.mg_primary).apply { typeface = Typeface.DEFAULT_BOLD; letterSpacing = 0.08f; ViewCompat.setAccessibilityHeading(this, true) }, lp(dp(a, 24)))
            val r1 = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL }
            r1.addView(tile(a, R.drawable.ic_bookmark, "Bookmarks", "bm"), LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginEnd = dp(a, 6) })
            r1.addView(tile(a, R.drawable.ic_history, "History", "hist"), LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginStart = dp(a, 6) })
            c.addView(r1, lp(dp(a, 8)))
            val r2 = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL }
            r2.addView(tile(a, R.drawable.ic_save, "Saved pages", "page"), LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginEnd = dp(a, 6) })
            r2.addView(tile(a, R.drawable.ic_download, "Files", "file"), LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginStart = dp(a, 6) })
            c.addView(r2, lp(dp(a, 12)))

            c.addView(tv(a, "SECURITY", 11f, R.color.mg_primary).apply { typeface = Typeface.DEFAULT_BOLD; letterSpacing = 0.08f; ViewCompat.setAccessibilityHeading(this, true) }, lp(dp(a, 24)))
            val card = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; background = box(a) }
            if (a is FragmentActivity && AppLock.available(a)) {
                card.addView(switchRow(a, "Quick unlock", "Fingerprint or screen lock, this phone only", VaultStore.quickOn(a)) { on ->
                    if (on) { if (!VaultStore.quickEnable(a)) Util.toast(a, "Quick unlock could not be turned on") } else VaultStore.quickDisable(a)
                })
            }
            card.addView(switchRow(a, "Hide from recents", "Blank preview and no screenshots", VaultStore.hideRecents(a)) { on ->
                VaultStore.setHideRecents(a, on)
                dlg?.window?.let { w -> if (on) w.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE) else w.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
            })
            c.addView(card, lp(dp(a, 8)))
            c.addView(btn(a, "Vault settings", false) { // V11
                VaultSettings.open(a) {
                    if (dlg?.isShowing == true) { if (VaultStore.isUnlocked()) { applySecure(a); home(false) } else unlockView() }
                }
            }, lp(dp(a, 20), MATCH, dp(a, 48)))
            c.addView(btn(a, "Lock now", false) { lockNow(a) }, lp(dp(a, 8), MATCH, dp(a, 48)))
            c.addView(btn(a, "Restore into the browser", false) { // V10
                VaultRestore.open(a) { if (dlg?.isShowing == true && VaultStore.isUnlocked()) home(false) }
            }, lp(dp(a, 8), MATCH, dp(a, 48)))
            c.addView(btn(a, "Exit vault / Prepare for uninstall", false) { // V9
                VaultExit.open(a) { if (dlg?.isShowing == true) bg.execute { val st = VaultStore.check(a); main.post { if (dlg?.isShowing == true) render(st) } } }
            }, lp(dp(a, 8), MATCH, dp(a, 48)))
        }
    }

    /** V11: re-applies "hide from recents" to this dialog after the settings page changed it. */
    private fun applySecure(a: Activity) {
        dlg?.window?.let { w ->
            if (VaultStore.hideRecents(a)) w.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
            else w.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }

    private fun lockNow(a: Activity) {
        bg.execute { VaultStore.lock(a); main.post { if (dlg?.isShowing == true) unlockView() } }
    }
}
