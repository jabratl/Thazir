package com.jabra.mgb

import android.app.Dialog
import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import androidx.core.widget.NestedScrollView
import androidx.fragment.app.FragmentActivity
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.security.SecureRandom
import kotlin.math.ln

/**
 * 0.0.389 (PW3): new password screens. Every part has a switch and a fallback to the old dialog, any error = the old way:
 *  - open(): full screen list (search, sort A-Z / Newest, site icon, detail sheet). Old AlertDialog list = fallback.
 *  - saveSheet(): bottom sheet with an editable user name and an eye toggle. Old save dialog = fallback.
 *  - chip(): small "Fill login" card near the bar instead of the Snackbar. Old Snackbar = fallback.
 *  - generatorSheet(): length, A-Z, 0-9, symbols, strength bar; Copy clears the clipboard after 60 s. Old generator = fallback.
 *  - privFill: allow FILL (never save) in private tabs, default OFF.
 * Switches (SharedPreferences "mgb_pw"): newui (ON), chip (ON), privfill (OFF). No password is logged or stored here.
 * "Last used" sort and notes need the extra fields of PW5; "Newest" = order of saving.
 */
object PwScreen {
    private const val PREF = "mgb_pw"
    private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
    private val hh = Handler(Looper.getMainLooper())

    private fun p(c: Context) = c.getSharedPreferences(PREF, Context.MODE_PRIVATE)
    private fun flag(c: Context, k: String, def: Boolean) = try { p(c).getBoolean(k, def) } catch (e: Exception) { def }
    fun on(c: Context) = flag(c, "newui", true)
    fun chipOn(c: Context) = flag(c, "chip", true)
    fun privFill(c: Context) = flag(c, "privfill", false)
    fun flip(c: Context, key: String, def: Boolean) { try { p(c).edit().putBoolean(key, !flag(c, key, def)).apply() } catch (e: Exception) { } }

    private fun dp(c: Context, v: Int) = Util.dp(c, v)

    private fun ripple(c: Context): Drawable? {
        val a = c.obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackground))
        val d = a.getDrawable(0); a.recycle(); return d
    }

    internal fun round(c: Context, color: Int, r: Int, stroke: Int = 0) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(c, r).toFloat()
        if (stroke != 0) setStroke(dp(c, 1), stroke)
    }

    internal fun sheetBg(c: Context) = GradientDrawable().apply {
        setColor(c.getColor(R.color.mg_bg))
        val r = dp(c, 24).toFloat(); cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
    }

    /** Site icon on a soft tile; first letter until the icon is there. */
    internal fun tile(c: Context, host: String, size: Int): View {
        val hue = (kotlin.math.abs(host.hashCode()) % 360).toFloat()
        val tint = android.graphics.Color.HSVToColor(floatArrayOf(hue, 0.45f, 0.85f))
        val box = FrameLayout(c)
        box.background = round(c, ColorUtils.blendARGB(c.getColor(R.color.mg_surface), tint, 0.25f), 10)
        val letter = TextView(c).apply {
            text = host.firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()?.toString() ?: "?"
            textSize = if (size >= 40) 16f else 14f
            gravity = Gravity.CENTER
            setTextColor(c.getColor(R.color.mg_text))
        }
        val iv = ImageView(c).apply { scaleType = ImageView.ScaleType.FIT_CENTER; setPadding(dp(c, 6), dp(c, 6), dp(c, 6), dp(c, 6)); visibility = View.GONE }
        box.addView(letter, FrameLayout.LayoutParams(MATCH, MATCH))
        box.addView(iv, FrameLayout.LayoutParams(MATCH, MATCH))
        fun show(bm: android.graphics.Bitmap) { iv.setImageBitmap(bm); iv.visibility = View.VISIBLE; letter.visibility = View.GONE }
        try { val have = Favicons.get(host); if (have != null) show(have) else Favicons.fetch(host) { bm -> show(bm) } } catch (e: Exception) { }
        return box
    }

    internal fun label(c: Context, t: String, sp: Float, color: Int, bold: Boolean = false) = TextView(c).apply {
        text = t; textSize = sp; setTextColor(color); if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    internal fun button(c: Context, t: String, primary: Boolean, click: () -> Unit) = TextView(c).apply {
        text = t; textSize = 14f; gravity = Gravity.CENTER; maxLines = 1
        setPadding(dp(c, 14), dp(c, 10), dp(c, 14), dp(c, 10))
        if (primary) { setTextColor(c.getColor(R.color.mg_on_primary)); background = round(c, c.getColor(R.color.mg_primary), 20) }
        else { setTextColor(c.getColor(R.color.mg_text)); background = round(c, c.getColor(R.color.mg_surface), 20, c.getColor(R.color.mg_outline)) }
        setOnClickListener { click() }
    }

    internal fun secure(w: android.view.Window?) { w?.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE) }

    // ---------------------------------------------------------------- full screen list

    /** false = not opened (switch OFF or any error): the caller shows the old list. */
    fun open(act: FragmentActivity): Boolean {
        if (!on(act)) return false
        val cText = act.getColor(R.color.mg_text)
        val cDim = act.getColor(R.color.mg_text_dim)
        val dlg = Dialog(act, android.R.style.Theme_DeviceDefault_NoActionBar)
        var mode = 0 // 0.0.391: 0 A-Z, 1 Newest, 2 Last used, 3 Favourites first
        val title = label(act, "Passwords", 20f, cText, true)
        val summary = label(act, "", 13f, cDim)
        val rows = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        val search = EditText(act).apply {
            hint = "Search saved logins"; setSingleLine(); inputType = InputType.TYPE_CLASS_TEXT
            setTextColor(cText); setHintTextColor(cDim)
            setPadding(dp(act, 14), dp(act, 10), dp(act, 14), dp(act, 10))
            background = round(act, act.getColor(R.color.mg_surface), 14, act.getColor(R.color.mg_outline))
        }
        lateinit var render: () -> Unit
        val hc: PwHealth.Card? = try { if (PwHealth.on(act)) PwHealth.Card(act) { render() } else null } catch (e: Exception) { null } // 0.0.390 (PW4): any error = no card
        fun icon(res: Int, desc: String, click: () -> Unit) = ImageView(act).apply {
            setImageResource(res); setColorFilter(cText); contentDescription = desc
            setPadding(dp(act, 12), dp(act, 12), dp(act, 12), dp(act, 12)); background = ripple(act); setOnClickListener { click() }
        }
        val bar = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(act, 4), dp(act, 4), dp(act, 4), dp(act, 4))
            addView(icon(R.drawable.ic_back, "Back") { dlg.dismiss() }, LinearLayout.LayoutParams(dp(act, 48), dp(act, 48)))
            addView(title, LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginStart = dp(act, 4) })
            addView(icon(R.drawable.ic_add, "Add login") { PasswordStore.addEntry(act) { render() } }, LinearLayout.LayoutParams(dp(act, 48), dp(act, 48)))
            addView(icon(R.drawable.ic_more, "More") { moreMenu(act) { render() } }, LinearLayout.LayoutParams(dp(act, 48), dp(act, 48)))
        }
        val chips = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        fun chip(t: String, m: Int): TextView = TextView(act).apply {
            text = t; textSize = 13f; setPadding(dp(act, 14), dp(act, 6), dp(act, 14), dp(act, 6))
            setOnClickListener { mode = m; render() }
        }
        val cAz = chip("A-Z", 0); val cNew = chip("Newest", 1); val cUse = chip("Last used", 2); val cFav = chip("Favourites", 3)
        chips.addView(cAz)
        for (c in listOf(cNew, cUse, cFav)) chips.addView(c, LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = dp(act, 6) })
        render = {
            rows.removeAllViews()
            val q = search.text.toString().trim().lowercase()
            val base = when (mode) { 1 -> PasswordStore.byAge(act); 2 -> PasswordStore.byUse(act); 3 -> PasswordStore.favFirst(act); else -> PasswordStore.all(act) } // 0.0.391: + Last used, Favourites
            try { hc?.sync(base.fold(17) { a, x -> a * 31 + x.host.hashCode() + x.user.hashCode() + x.enc.hashCode() }) } catch (e: Exception) { } // 0.0.390: list changed = card asks for a new check
            val es = base.filter { q.isEmpty() || it.host.lowercase().contains(q) || it.user.lowercase().contains(q) }
            title.text = "Passwords (" + base.size + ")"
            summary.text = base.size.toString() + " logins on " + base.map { it.host }.toSet().size + " sites"
            for ((c, sel) in listOf(cAz to (mode == 0), cNew to (mode == 1), cUse to (mode == 2), cFav to (mode == 3))) {
                c.setTextColor(if (sel) act.getColor(R.color.mg_on_primary) else cText)
                c.background = round(act, if (sel) act.getColor(R.color.mg_primary) else act.getColor(R.color.mg_surface), 16)
            }
            if (es.isEmpty()) {
                rows.addView(label(act, if (q.isEmpty()) "No saved logins yet.\nLog in to a site and tap Save, or use + / More > Import." else "Nothing found.", 14f, cDim)
                    .apply { setPadding(dp(act, 8), dp(act, 28), dp(act, 8), dp(act, 28)) })
            }
            for (e in es) {
                val r = LinearLayout(act).apply {
                    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; minimumHeight = dp(act, 60)
                    setPadding(dp(act, 12), dp(act, 8), dp(act, 14), dp(act, 8))
                    layoutParams = BmUi.cardParams(act); background = BmUi.rippleCard(act)
                    setOnClickListener { detail(act, e, dlg) { render() } }
                }
                r.addView(tile(act, e.host, 40), LinearLayout.LayoutParams(dp(act, 40), dp(act, 40)).apply { marginEnd = dp(act, 12) })
                val col = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
                col.addView(label(act, (if (e.fav) "\u2605 " else "") + e.host, 16f, cText).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }) // 0.0.391: star = favourite
                col.addView(label(act, e.user.ifBlank { "(no user name)" }, 13f, cDim).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
                r.addView(col, LinearLayout.LayoutParams(0, WRAP, 1f))
                rows.addView(r)
            }
        }
        search.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) { render() }
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) { }
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) { }
        })
        val sv = ScrollView(act).apply { addView(rows, ViewGroup.LayoutParams(MATCH, WRAP)) }
        val root = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(act.getColor(R.color.mg_bg))
            setPadding(dp(act, 12), 0, dp(act, 12), 0)
            addView(bar)
            addView(search, LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(act, 8) })
            addView(summary, LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(act, 6) }) // 0.0.391: own line
            addView(HorizontalScrollView(act).apply { isHorizontalScrollBarEnabled = false; addView(chips) }, LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(act, 6) })
            try { hc?.let { addView(it.view, LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(act, 8) }) } } catch (e: Exception) { } // 0.0.390 (PW4)
            addView(sv, LinearLayout.LayoutParams(MATCH, 0, 1f))
        }
        dlg.setContentView(root)
        dlg.window?.setBackgroundDrawable(ColorDrawable(act.getColor(R.color.mg_bg)))
        dlg.window?.setLayout(MATCH, MATCH)
        secure(dlg.window) // same protection as the old list: no screenshot / recents picture
        dlg.setOnDismissListener { PasswordStore.forgetAuth() }
        render()
        dlg.show()
        return true
    }

    private fun moreMenu(act: FragmentActivity, render: () -> Unit) {
        val items = arrayOf("Generate a password", "Import from a CSV file", "Import by paste", "Export as CSV file", "Encrypted backup file", "Restore from backup file", "Sites never saved")
        MaterialAlertDialogBuilder(act).setItems(items) { _, i ->
            when (i) {
                0 -> PasswordStore.generatorDialog(act)
                1 -> PasswordStore.importFile(act) { render() }
                2 -> PasswordStore.importDialog(act)
                3 -> PasswordStore.exportCsv(act)
                4 -> PwBackup.export(act)
                5 -> PwBackup.restore(act) { render() }
                else -> PwBackup.neverList(act)
            }
        }.show()
    }

    /** One login: copy, show (screen lock), open site, edit, delete (+ Undo). */
    private fun detail(act: FragmentActivity, e: PasswordStore.Entry, parent: Dialog, changed: () -> Unit) {
        val cText = act.getColor(R.color.mg_text)
        val cDim = act.getColor(R.color.mg_text_dim)
        val d = BottomSheetDialog(act)
        val col = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL; background = sheetBg(act)
            setPadding(dp(act, 18), dp(act, 18), dp(act, 18), dp(act, 20))
        }
        val head = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(tile(act, e.host, 40), LinearLayout.LayoutParams(dp(act, 44), dp(act, 44)).apply { marginEnd = dp(act, 12) })
        head.addView(label(act, e.host, 20f, cText, true).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }, LinearLayout.LayoutParams(0, WRAP, 1f))
        col.addView(head)
        fun line(name: String, value: TextView, vararg btns: View) {
            col.addView(label(act, name, 12f, cDim), LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = dp(act, 16) })
            val r = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            r.addView(value, LinearLayout.LayoutParams(0, WRAP, 1f))
            for (b in btns) r.addView(b, LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = dp(act, 8) })
            col.addView(r)
        }
        line("User name", label(act, e.user.ifBlank { "(no user name)" }, 16f, cText).apply { setTextIsSelectable(true) },
            button(act, "Copy", false) { PasswordStore.copyPlain(act, "User name", e.user) })
        val dots = "\u2022\u2022\u2022\u2022\u2022\u2022\u2022\u2022\u2022\u2022" // length is not shown
        val pw = label(act, dots, 16f, cText).apply { typeface = Typeface.MONOSPACE }
        var shown = false
        val auto = Runnable { shown = false; pw.text = dots }
        val show = button(act, "Show", false) { }
        show.setOnClickListener {
            if (shown) { shown = false; pw.text = dots; show.text = "Show"; hh.removeCallbacks(auto); return@setOnClickListener }
            PasswordStore.guard(act, "Show password") {
                val v = Vault.decrypt(e.enc)
                if (v == null) Util.toast(act, "Cannot read this password")
                else { shown = true; pw.text = v; show.text = "Hide"; hh.postDelayed({ auto.run(); show.text = "Show" }, 30_000L) }
            }
        }
        line("Password", pw, show, button(act, "Copy", false) {
            PasswordStore.guard(act, "Copy password") {
                val v = Vault.decrypt(e.enc)
                if (v == null) Util.toast(act, "Cannot read this password") else PasswordStore.copySecret(act, "Password", v)
            }
        })
        try { PwMore.detailExtras(act, col, e, changed) } catch (x: Exception) { } // 0.0.391 (PW5): dates, favourite, note, old passwords
        val acts = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(act, 22), 0, 0) }
        fun slot(b: View, first: Boolean = false) = acts.addView(b, LinearLayout.LayoutParams(0, WRAP, 1f).apply { if (!first) marginStart = dp(act, 8) })
        slot(button(act, "Open site", false) { d.dismiss(); parent.dismiss(); (act as? MainActivity)?.openSite("https://" + e.host) }, true)
        slot(button(act, "Edit", false) { d.dismiss(); PasswordStore.editEntry(act, e, changed) })
        slot(button(act, "Delete", false) { d.dismiss(); PasswordStore.deleteEntry(act, e, parent.findViewById<View>(android.R.id.content), changed) })
        col.addView(acts)
        val sc = NestedScrollView(act).apply { addView(col, ViewGroup.LayoutParams(MATCH, WRAP)) } // NestedScrollView inside a bottom sheet (0.0.352 lesson)
        d.setContentView(sc)
        secure(d.window)
        d.setOnDismissListener { hh.removeCallbacks(auto) }
        d.show()
    }

    // ---------------------------------------------------------------- save sheet

    /** true = the sheet is shown (the caller must not show the old dialog). onSave gets the (edited) user name. */
    fun saveSheet(act: FragmentActivity, host: String, user: String, pass: String, update: Boolean, onSave: (String) -> Unit, onNever: () -> Unit): Boolean {
        if (!on(act)) return false
        val cText = act.getColor(R.color.mg_text)
        val cDim = act.getColor(R.color.mg_text_dim)
        val d = BottomSheetDialog(act)
        d.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        val col = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL; background = sheetBg(act)
            setPadding(dp(act, 18), dp(act, 18), dp(act, 18), dp(act, 20))
        }
        val head = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(tile(act, host, 40), LinearLayout.LayoutParams(dp(act, 44), dp(act, 44)).apply { marginEnd = dp(act, 12) })
        head.addView(label(act, (if (update) "Update password for " else "Save password for ") + host + "?", 18f, cText, true), LinearLayout.LayoutParams(0, WRAP, 1f))
        col.addView(head)
        val userEt = EditText(act).apply {
            hint = "User name or email"; setSingleLine(); setText(user); setTextColor(cText); setHintTextColor(cDim)
        }
        col.addView(userEt, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(act, 12) })
        val dots = "\u2022".repeat(minOf(maxOf(pass.length, 8), 16))
        val pw = label(act, dots, 16f, cText).apply { typeface = Typeface.MONOSPACE }
        var shown = false
        val eye = button(act, "Show", false) { }
        eye.setOnClickListener { shown = !shown; pw.text = if (shown) pass else dots; eye.text = if (shown) "Hide" else "Show" } // the password was typed by the user on the page just now
        val pr = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        pr.addView(pw, LinearLayout.LayoutParams(0, WRAP, 1f)); pr.addView(eye)
        col.addView(pr, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(act, 12) })
        val acts = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(act, 20), 0, 0) }
        acts.addView(button(act, "Never here", false) { onNever(); d.dismiss() }, LinearLayout.LayoutParams(0, WRAP, 1f))
        acts.addView(button(act, "Not now", false) { d.dismiss() }, LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginStart = dp(act, 8) })
        acts.addView(button(act, if (update) "Update" else "Save", true) { onSave(userEt.text.toString().trim().take(200)); d.dismiss() }, LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginStart = dp(act, 8) })
        col.addView(acts)
        val sc = NestedScrollView(act).apply { addView(col, ViewGroup.LayoutParams(MATCH, WRAP)) }
        d.setContentView(sc)
        secure(d.window)
        d.show()
        return true
    }

    // ---------------------------------------------------------------- fill chip

    private var chipView: View? = null
    private var chipHost: FrameLayout? = null
    private val chipAway = Runnable { hideChip() }

    fun hideChip() {
        try { chipView?.let { chipHost?.removeView(it) } } catch (e: Exception) { }
        chipView = null; chipHost = null
        hh.removeCallbacks(chipAway)
    }

    /** true = the chip is shown. false = the caller shows the old Snackbar. Same place rules as the address suggestions (above a bottom bar, under a top bar). */
    fun chip(act: FragmentActivity, host: String, who: String, onTap: () -> Unit): Boolean {
        if (!chipOn(act)) return false
        val root = act.findViewById<FrameLayout>(android.R.id.content) ?: return false
        val bar = act.findViewById<View>(R.id.bottom_bar) ?: return false
        if (root.height <= 0 || bar.visibility != View.VISIBLE) return false
        val rl = IntArray(2); val bl = IntArray(2)
        root.getLocationInWindow(rl); bar.getLocationInWindow(bl)
        val top = bl[1] - rl[1]
        val below = top <= root.height / 2
        val edge = if (below) top + bar.height + dp(act, 6) else root.height - top + dp(act, 6)
        if (root.height - edge < dp(act, 80)) return false
        hideChip()
        val c = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = round(act, act.getColor(R.color.mg_surface), 16, act.getColor(R.color.mg_outline))
            elevation = dp(act, 8).toFloat()
            setPadding(dp(act, 12), dp(act, 8), dp(act, 14), dp(act, 8))
            contentDescription = "Fill login $who"
            background = round(act, act.getColor(R.color.mg_surface), 16, act.getColor(R.color.mg_outline))
            setOnClickListener { hideChip(); onTap() }
        }
        c.addView(tile(act, host, 32), LinearLayout.LayoutParams(dp(act, 32), dp(act, 32)).apply { marginEnd = dp(act, 10) })
        val col = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        col.addView(label(act, who, 14f, act.getColor(R.color.mg_text)).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
        col.addView(label(act, host, 11f, act.getColor(R.color.mg_text_dim)).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
        c.addView(col, LinearLayout.LayoutParams(0, WRAP, 1f))
        c.addView(label(act, "Fill", 14f, act.getColor(R.color.mg_primary), true).apply { setPadding(dp(act, 12), 0, 0, 0) })
        val w = minOf(root.width - dp(act, 20), dp(act, 420))
        val lp = FrameLayout.LayoutParams(w, WRAP, (if (below) Gravity.TOP else Gravity.BOTTOM) or Gravity.CENTER_HORIZONTAL)
        if (below) lp.topMargin = edge else lp.bottomMargin = edge
        root.addView(c, lp)
        chipView = c; chipHost = root
        hh.postDelayed(chipAway, 12_000L)
        return true
    }

    // ---------------------------------------------------------------- generator sheet

    private fun gen(len: Int, up: Boolean, dg: Boolean, sy: Boolean): Pair<String, Int> {
        val sets = ArrayList<String>()
        sets.add("abcdefghijkmnopqrstuvwxyz")
        if (up) sets.add("ABCDEFGHJKLMNPQRSTUVWXYZ")
        if (dg) sets.add("23456789")
        if (sy) sets.add("!@#\$%^&*-_=+?")
        val r = SecureRandom()
        val every = sets.joinToString("")
        val out = ArrayList<Char>()
        for (x in sets) out.add(x[r.nextInt(x.length)])
        while (out.size < len) out.add(every[r.nextInt(every.length)])
        out.shuffle(r)
        val bits = (len * ln(every.length.toDouble()) / ln(2.0)).toInt()
        return out.joinToString("") to bits
    }

    /** true = shown. Copy uses the sensitive clipboard flag and clears after 60 s. */
    fun generatorSheet(act: FragmentActivity): Boolean {
        if (!on(act)) return false
        val cText = act.getColor(R.color.mg_text)
        val cDim = act.getColor(R.color.mg_text_dim)
        val d = BottomSheetDialog(act)
        val col = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL; background = sheetBg(act)
            setPadding(dp(act, 20), dp(act, 18), dp(act, 20), dp(act, 20))
        }
        col.addView(label(act, "Password generator", 20f, cText, true))
        val tv = label(act, "", 20f, cText).apply { typeface = Typeface.MONOSPACE; setTextIsSelectable(true); setPadding(0, dp(act, 14), 0, dp(act, 6)) }
        col.addView(tv)
        val bar = ProgressBar(act, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100 }
        val sTv = label(act, "", 12f, cDim)
        col.addView(bar, LinearLayout.LayoutParams(MATCH, dp(act, 6)))
        col.addView(sTv, LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = dp(act, 4) })
        val lenTv = label(act, "Length 16", 13f, cDim)
        val seek = SeekBar(act).apply { max = 24; progress = 8 }
        col.addView(lenTv, LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = dp(act, 12) })
        col.addView(seek)
        fun box(t: String) = CheckBox(act).apply { text = t; isChecked = true; setTextColor(cText) }
        val bUp = box("Capital letters A-Z"); val bDg = box("Digits 0-9"); val bSy = box("Symbols !@#")
        col.addView(bUp); col.addView(bDg); col.addView(bSy)
        var cur = ""
        fun make() {
            val len = 8 + seek.progress
            val (s, bits) = gen(len, bUp.isChecked, bDg.isChecked, bSy.isChecked)
            cur = s; tv.text = s; lenTv.text = "Length $len"
            bar.progress = minOf(100, bits * 100 / 110)
            sTv.text = when { bits < 45 -> "Strength: weak"; bits < 65 -> "Strength: fair"; bits < 90 -> "Strength: good"; else -> "Strength: strong" }
        }
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, v: Int, fromUser: Boolean) { if (fromUser) make() }
            override fun onStartTrackingTouch(s: SeekBar?) { }
            override fun onStopTrackingTouch(s: SeekBar?) { }
        })
        for (b in listOf(bUp, bDg, bSy)) b.setOnCheckedChangeListener { _, _ -> make() }
        val acts = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(act, 16), 0, 0) }
        acts.addView(button(act, "New", false) { make() }, LinearLayout.LayoutParams(0, WRAP, 1f))
        acts.addView(button(act, "Copy", true) { PasswordStore.copySecret(act, "Password", cur) }, LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginStart = dp(act, 8) })
        col.addView(acts)
        make()
        d.setContentView(NestedScrollView(act).apply { addView(col, ViewGroup.LayoutParams(MATCH, WRAP)) })
        secure(d.window)
        d.show()
        return true
    }
}
