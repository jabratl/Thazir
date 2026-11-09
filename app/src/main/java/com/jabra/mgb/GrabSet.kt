package com.jabra.mgb

import android.app.Activity
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.widget.SwitchCompat
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.json.JSONArray
import org.json.JSONObject

/**
 * S62: the V77 options that no native screen had (panel TOOLS > Grabber settings).
 * V77 keys in chrome.storage.sync: enabled, siteModules, siteAdapters, fullTitles, bgDownload, jobWindow, autoClose,
 * overlayCorner, pillSide, theme, extraExts, siteFormats, siteRules, msOffSites, disabledSites (array).
 * Also: copy / clear the debug log (storage.local mgLog), copy / paste all settings as JSON, reset all settings.
 */
object GrabSet {
    private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

    private fun str(k: String, def: String): String = try { ChromeBridge.sync.get(k).optString(k, def) } catch (e: Exception) { def }
    private fun bool(k: String, def: Boolean): Boolean = try { ChromeBridge.sync.get(k).optBoolean(k, def) } catch (e: Exception) { def }
    private fun put(k: String, v: Any) { try { ChromeBridge.storageSet("sync", JSONObject().put(k, v)) } catch (e: Exception) { /* engine not ready */ } }
    private fun lines(k: String): String = try {
        val o = ChromeBridge.sync.get(k).opt(k)
        if (o is JSONArray) (0 until o.length()).joinToString("\n") { o.optString(it) } else (o as? String) ?: ""
    } catch (e: Exception) { "" }

    private fun clip(act: Activity, text: String) {
        val cm = act.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText("MGB", text))
    }

    fun open(act: Activity) {
        val cText = act.getColor(R.color.mg_text)
        val cDim = act.getColor(R.color.mg_text_dim)
        val cPrimary = act.getColor(R.color.mg_primary)
        val cCard = act.getColor(R.color.mg_surface)
        val cLine = act.getColor(R.color.mg_outline)
        fun dp(v: Int) = Util.dp(act, v)
        val d = BottomSheetDialog(act)
        val root = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(10))
            layoutParams = ViewGroup.LayoutParams(MATCH, (act.resources.displayMetrics.heightPixels * 0.9f).toInt())
        }
        root.addView(TextView(act).apply {
            text = "Grabber settings"
            setTextColor(cText); setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f); typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(4), 0, 0, dp(2))
        })
        root.addView(TextView(act).apply {
            text = "The other V82 options: buttons, downloader window, sites and backup. Some need a page reload."
            setTextColor(cDim); setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f); setPadding(dp(4), 0, 0, dp(6))
        })
        val box = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        root.addView(ScrollView(act).apply { addView(box) }, LinearLayout.LayoutParams(MATCH, 0, 1f))
        root.addView(TextView(act).apply {
            text = "DONE"; gravity = Gravity.CENTER
            setTextColor(act.getColor(R.color.mg_on_primary)); typeface = Typeface.DEFAULT_BOLD
            background = GradientDrawable().apply { setColor(cPrimary); cornerRadius = dp(24).toFloat() }
            setOnClickListener { d.dismiss() }
        }, LinearLayout.LayoutParams(MATCH, dp(48)).apply { topMargin = dp(8) })

        fun card(): LinearLayout = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply { setColor(cCard); cornerRadius = dp(18).toFloat(); setStroke(dp(1), cLine) }
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        fun head(t: String) = TextView(act).apply {
            text = t.uppercase()
            setTextColor(cPrimary); setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f); typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(8), dp(16), 0, dp(6))
        }
        fun rowView(title: String, value: String?, hint: String?): LinearLayout {
            val r = LinearLayout(act).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), dp(10), dp(12), dp(10))
                val tv = TypedValue()
                if (act.theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)) setBackgroundResource(tv.resourceId)
            }
            val col = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
            col.addView(TextView(act).apply { text = title; setTextColor(cText); setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f) })
            if (!hint.isNullOrBlank()) col.addView(TextView(act).apply { text = hint; setTextColor(cDim); setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f); setPadding(0, dp(2), 0, 0) })
            r.addView(col, LinearLayout.LayoutParams(0, WRAP, 1f))
            if (value != null) r.addView(TextView(act).apply {
                text = value; setTextColor(cPrimary); setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.END; maxLines = 2
            }, LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = dp(8) })
            return r
        }

        lateinit var fill: () -> Unit

        fun sw(parent: LinearLayout, title: String, hint: String?, key: String, def: Boolean) {
            val r = rowView(title, null, hint)
            val s = SwitchCompat(act).apply { isChecked = bool(key, def); ThazirSwitch.tint(this) } // A10
            r.addView(s, LinearLayout.LayoutParams(WRAP, WRAP))
            val flip = { put(key, !bool(key, def)); fill() }
            r.setOnClickListener { flip() }
            s.setOnClickListener { flip() }
            parent.addView(r, LinearLayout.LayoutParams(MATCH, WRAP))
        }
        /** One of a few fixed values (V77 stores the code, the screen shows the name). */
        fun pick(parent: LinearLayout, title: String, hint: String?, key: String, codes: Array<String>, names: Array<String>, def: String) {
            val cur = str(key, def).let { if (it in codes) it else def }
            parent.addView(rowView(title, names[codes.indexOf(cur)], hint).apply {
                setOnClickListener {
                    MaterialAlertDialogBuilder(act).setTitle(title)
                        .setSingleChoiceItems(names, codes.indexOf(cur)) { dd, i -> put(key, codes[i]); dd.dismiss(); fill() }
                        .setNegativeButton("Cancel", null).show()
                }
            }, LinearLayout.LayoutParams(MATCH, WRAP))
        }
        /** Free text; asArray = one entry per line saved as a list (V77 disabledSites). */
        fun text(parent: LinearLayout, title: String, hint: String?, key: String, example: String, asArray: Boolean = false) {
            val cur = if (asArray) lines(key) else str(key, "")
            val n = cur.split("\n").count { it.isNotBlank() }
            parent.addView(rowView(title, if (n == 0) "Empty" else "$n line" + (if (n == 1) "" else "s"), hint).apply {
                setOnClickListener {
                    val et = EditText(act).apply {
                        setText(cur); setHint(example); minLines = 5
                        gravity = Gravity.TOP or Gravity.START
                        inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE or android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                        setTextColor(cText); setHintTextColor(cDim)
                    }
                    val pad = dp(20)
                    val wrap = LinearLayout(act).apply { setPadding(pad, dp(8), pad, 0); addView(et, LinearLayout.LayoutParams(MATCH, WRAP)) }
                    MaterialAlertDialogBuilder(act).setTitle(title).setView(wrap)
                        .setPositiveButton("Save") { _, _ ->
                            val v = et.text.toString().trim()
                            if (asArray) {
                                val a = JSONArray()
                                v.split(Regex("\\s+")).map { it.trim().lowercase() }.filter { it.isNotEmpty() }.forEach { a.put(it) }
                                put(key, a)
                            } else put(key, v)
                            fill()
                        }
                        .setNegativeButton("Cancel", null).show()
                }
            }, LinearLayout.LayoutParams(MATCH, WRAP))
        }
        fun action(parent: LinearLayout, title: String, hint: String?, run: () -> Unit) {
            parent.addView(rowView(title, null, hint).apply { setOnClickListener { run() } }, LinearLayout.LayoutParams(MATCH, WRAP))
        }

        fill = {
            box.removeAllViews()

            box.addView(head("General"))
            val g1 = card()
            sw(g1, "Enable Media Grabber", "Master switch for detection and the buttons", "enabled", true)
            pick(g1, "Theme", "Grabber pages, pages screen and reader", "theme", arrayOf("auto", "dark", "light"), arrayOf("Auto (follow system)", "Dark", "Light"), "auto")
            sw(g1, "Always show full titles", "Wrap long names instead of cutting them", "fullTitles", false)
            box.addView(g1, LinearLayout.LayoutParams(MATCH, WRAP))

            box.addView(head("Buttons"))
            val g2 = card()
            pick(g2, "Floating button side", null, "pillSide", arrayOf("right", "left"), arrayOf("Right", "Left"), "right")
            pick(g2, "Video button corner", null, "overlayCorner", arrayOf("tl", "tr"), arrayOf("Top-left", "Top-right"), "tl")
            box.addView(g2, LinearLayout.LayoutParams(MATCH, WRAP))

            box.addView(head("Stream downloader"))
            val g3 = card()
            sw(g3, "Download streams in the background", "No extra tab opens; closing tabs cannot stop a download", "bgDownload", true)
            pick(g3, "Downloader opens in", "Used when background download is off", "jobWindow", arrayOf("tab", "window"), arrayOf("Background tab", "Small window"), "tab")
            sw(g3, "Close it when finished", null, "autoClose", true)
            box.addView(g3, LinearLayout.LayoutParams(MATCH, WRAP))

            box.addView(head("Sites"))
            val g4 = card()
            sw(g4, "Site modules", "TikTok, Douyin, Vimeo, SoundCloud, Bandcamp readers + Telegram Web button", "siteModules", true)
            sw(g4, "Gallery / chapter adapters", "Real page lists of hitomi.la, MangaDex, nhentai + script scan on other sites", "siteAdapters", true)
            text(g4, "Default manga format per site", "One per line: domain = cbz|zip|pdf|epub", "siteFormats", "example.com = pdf")
            text(g4, "Site rules (page images)", "One per line: domain = css selector of the images", "siteRules", "example.com = #reader img")
            text(g4, "Hidden sites", "No Grabber buttons on these domains (one per line)", "disabledSites", "example.com", true)
            text(g4, "Switch off MediaSource on", "One domain per line; the player falls back to a plain link. Reload the site after.", "msOffSites", "ok.ru")
            text(g4, "Extra video extensions", "Comma separated, e.g. bin,dat", "extraExts", "bin,dat")
            box.addView(g4, LinearLayout.LayoutParams(MATCH, WRAP))

            box.addView(head("Backup and help"))
            val g5 = card()
            action(g5, "Copy all settings (JSON)", "Saves every V82 setting to the clipboard") {
                try { clip(act, ChromeBridge.sync.get(null as Any?).toString()); Util.toast(act, "Settings copied") } catch (e: Exception) { Util.toast(act, "Could not read the settings") }
            }
            action(g5, "Paste settings from clipboard", "Restores settings copied before") {
                try {
                    val cm = act.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    val t = cm.primaryClip?.getItemAt(0)?.coerceToText(act)?.toString() ?: ""
                    val o = JSONObject(t)
                    if (o.length() == 0) throw Exception("empty")
                    MaterialAlertDialogBuilder(act).setTitle("Paste settings").setMessage("Replace ${o.length()} setting(s) with the ones on the clipboard?")
                        .setPositiveButton("Replace") { _, _ -> ChromeBridge.storageSet("sync", o); Util.toast(act, "Settings restored"); fill() }
                        .setNegativeButton("Cancel", null).show()
                } catch (e: Exception) { Util.toast(act, "The clipboard has no settings JSON") }
            }
            action(g5, "Copy debug log", "Which fallback worked, why a download failed. Paste it into a bug report.") {
                val a = try { ChromeBridge.local.get("mgLog").optJSONArray("mgLog") } catch (e: Exception) { null }
                val t = if (a == null) "" else (0 until a.length()).joinToString("\n") { a.optString(it) }
                if (t.isEmpty()) Util.toast(act, "The log is empty") else { clip(act, t); Util.toast(act, "Log copied") }
            }
            action(g5, "Clear debug log", null) {
                try { ChromeBridge.storageSet("local", JSONObject().put("mgLog", JSONArray())); Util.toast(act, "Log cleared") } catch (e: Exception) { /* not ready */ }
            }
            action(g5, "Reset all settings", "Every V82 option back to its default") {
                MaterialAlertDialogBuilder(act).setTitle("Reset all settings").setMessage("Reset every V82 setting to default? Followed manga, history and schedule stay.")
                    .setPositiveButton("Reset") { _, _ ->
                        try { ChromeBridge.sync.clear(); Util.toast(act, "Reset. Reload the pages."); fill() } catch (e: Exception) { Util.toast(act, "Reset failed") }
                    }
                    .setNegativeButton("Cancel", null).show()
            }
            box.addView(g5, LinearLayout.LayoutParams(MATCH, WRAP))
        }
        fill()
        d.setContentView(root)
        d.behavior.skipCollapsed = true
        d.show()
        d.behavior.state = BottomSheetBehavior.STATE_EXPANDED
    }
}
