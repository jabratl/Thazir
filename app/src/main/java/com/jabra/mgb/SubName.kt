package com.jabra.mgb

import androidx.core.widget.NestedScrollView
import android.app.Activity
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.widget.SwitchCompat
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * S59: V77 subtitle and file naming options as one native screen (panel TOOLS > Subtitles and file names).
 * Every row reads / writes a V77 setting in chrome.storage.sync (autoSubs, subFmt, subLangs, subCheck, subFallback,
 * libraryMode, nameTemplate, subfolder, cleanTitles, titleStrip, titleSource, padChapters). V77 does the real work.
 * A live example shows the file name the current settings give.
 */
object SubName {
    private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

    private fun str(k: String, def: String): String = try { ChromeBridge.sync.get(k).optString(k, def) } catch (e: Exception) { def }
    private fun bool(k: String, def: Boolean): Boolean = try { ChromeBridge.sync.get(k).optBoolean(k, def) } catch (e: Exception) { def }
    private fun put(k: String, v: Any) { try { ChromeBridge.storageSet("sync", JSONObject().put(k, v)) } catch (e: Exception) { /* engine not ready */ } }

    private val LANGS = linkedMapOf(
        "en" to "English", "bn" to "Bangla", "hi" to "Hindi", "ar" to "Arabic", "es" to "Spanish", "fr" to "French",
        "de" to "German", "pt" to "Portuguese", "id" to "Indonesian", "ru" to "Russian", "tr" to "Turkish",
        "ja" to "Japanese", "ko" to "Korean", "zh" to "Chinese"
    )
    private val FMT = arrayOf("srt", "vtt", "txt", "orig")
    private val FMT_NAMES = arrayOf("SRT (works everywhere)", "VTT", "TXT (text only)", "Original file")
    private val TEMPLATES = arrayOf("{title}", "{title} [{res}]", "{series} - {sxe}", "{series} - {sxe} - {title}", "{site} - {title}", "{date} {title}")
    private val FOLDERS = arrayOf("", "{series}", "{series}/Season {season}", "{site}/{series}", "{site}")
    private val SOURCES = arrayOf("auto", "page", "schema", "h1")
    private val SOURCE_NAMES = arrayOf("Automatic", "Tab title", "Page data (schema)", "Big heading (h1)")

    /** Example file name for the current settings (all values are made up). */
    private fun example(): String {
        val now = Date()
        val map = mapOf(
            "title" to "Naruto Episode 5", "series" to "Naruto", "book" to "Naruto", "chapter" to "Chapter 005", "episode" to "05",
            "season" to "01", "sxe" to "S01E05", "index" to "5", "site" to "example.com", "name" to "master", "res" to "1080p",
            "date" to SimpleDateFormat("yyyy-MM-dd", Locale.US).format(now), "time" to SimpleDateFormat("HH-mm", Locale.US).format(now),
            "kind" to "video", "lang" to "en"
        )
        fun fill(t: String) = Regex("\\{(\\w+)\\}").replace(t) { map[it.groupValues[1]] ?: "" }
        if (str("libraryMode", "off") == "plex") return "Naruto/Season 01/Naruto - S01E05.mp4"
        val tpl = fill(str("nameTemplate", "{title}").ifBlank { "{title}" })
        val dir = fill(str("subfolder", "")).trim('/')
        return (if (dir.isNotEmpty()) "$dir/" else "") + tpl + ".mp4"
    }

    private fun exampleSub(): String {
        val base = example().removeSuffix(".mp4")
        val l = str("subLangs", "en").split(',').map { it.trim() }.firstOrNull { it.isNotEmpty() } ?: "en"
        val f = str("subFmt", "srt").let { if (it == "orig") "srt" else it }
        return "$base.$l.$f"
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
            text = "Subtitles and file names"
            setTextColor(cText)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(4), 0, 0, dp(8))
        })
        val box = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        root.addView(NestedScrollView(act).apply { addView(box) }, LinearLayout.LayoutParams(MATCH, 0, 1f))
        val close = TextView(act).apply {
            text = "DONE"
            gravity = Gravity.CENTER
            setTextColor(act.getColor(R.color.mg_on_primary))
            typeface = Typeface.DEFAULT_BOLD
            background = GradientDrawable().apply { setColor(cPrimary); cornerRadius = dp(24).toFloat() }
            setOnClickListener { d.dismiss() }
        }
        root.addView(close, LinearLayout.LayoutParams(MATCH, dp(48)).apply { topMargin = dp(8) })

        fun card(): LinearLayout = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply { setColor(cCard); cornerRadius = dp(18).toFloat(); setStroke(dp(1), cLine) }
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }

        fun head(t: String) = TextView(act).apply {
            text = t.uppercase()
            setTextColor(cPrimary)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(8), dp(16), 0, dp(6))
        }

        fun rowView(title: String, value: String?, hint: String?): LinearLayout {
            val r = LinearLayout(act).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), dp(10), dp(12), dp(10))
                val tv = TypedValue()
                if (act.theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)) setBackgroundResource(tv.resourceId)
            }
            val col = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
            col.addView(TextView(act).apply { text = title; setTextColor(cText); setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f) })
            if (!hint.isNullOrBlank()) col.addView(TextView(act).apply { text = hint; setTextColor(cDim); setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f); setPadding(0, dp(2), 0, 0) })
            r.addView(col, LinearLayout.LayoutParams(0, WRAP, 1f))
            if (value != null) r.addView(TextView(act).apply {
                text = value
                setTextColor(cDim)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                gravity = Gravity.END
                maxLines = 2
                maxWidth = (act.resources.displayMetrics.widthPixels * 0.4f).toInt()
            }, LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = dp(8) })
            return r
        }

        lateinit var fill: () -> Unit
        fun again() { fill() }

        fun pick(parent: LinearLayout, title: String, value: String, hint: String?, run: () -> Unit) {
            parent.addView(rowView(title, value, hint).apply { setOnClickListener { run() } }, LinearLayout.LayoutParams(MATCH, WRAP))
        }

        fun sw(parent: LinearLayout, title: String, hint: String?, key: String, def: Boolean) {
            val r = rowView(title, null, hint)
            val s = SwitchCompat(act).apply { isChecked = bool(key, def); ThazirSwitch.tint(this) } // A10
            r.addView(s, LinearLayout.LayoutParams(WRAP, WRAP))
            val flip = { put(key, !bool(key, def)); again() }
            r.setOnClickListener { flip() }
            s.setOnClickListener { flip() }
            parent.addView(r, LinearLayout.LayoutParams(MATCH, WRAP))
        }

        fun single(title: String, names: Array<String>, cur: Int, onPick: (Int) -> Unit) {
            MaterialAlertDialogBuilder(act).setTitle(title)
                .setSingleChoiceItems(names, cur) { dd, i -> onPick(i); dd.dismiss(); again() }
                .setNegativeButton("Cancel", null).show()
        }

        fun text(title: String, msg: String, key: String, def: String) {
            val et = EditText(act).apply {
                setText(str(key, def))
                setSingleLine()
                inputType = InputType.TYPE_CLASS_TEXT
                setTextColor(cText)
                setSelection(text.length)
            }
            val wrap = LinearLayout(act).apply { setPadding(dp(20), dp(8), dp(20), 0); addView(et, LinearLayout.LayoutParams(MATCH, WRAP)) }
            MaterialAlertDialogBuilder(act).setTitle(title).setMessage(msg).setView(wrap)
                .setPositiveButton("Save") { _, _ -> put(key, et.text.toString().trim()); again() }
                .setNegativeButton("Cancel", null).show()
        }

        // template / folder: presets + own text
        fun preset(title: String, key: String, presets: Array<String>, def: String, tokens: String) {
            val cur = str(key, def)
            val names = Array(presets.size + 1) { i -> if (i < presets.size) (presets[i].ifEmpty { "(none)" }) else "Write my own..." }
            single(title, names, presets.indexOf(cur).let { if (it < 0) presets.size else it }) { i ->
                if (i < presets.size) put(key, presets[i]) else text(title, tokens, key, def)
            }
        }

        fun langDialog() {
            val cur = str("subLangs", "en").split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() }
            val codes = LANGS.keys.toList()
            val checked = BooleanArray(codes.size) { codes[it] in cur }
            val extra = cur.filter { it !in codes } // codes typed in V77 options stay
            MaterialAlertDialogBuilder(act).setTitle("Wanted subtitle languages")
                .setMultiChoiceItems(codes.map { LANGS[it] + " (" + it + ")" }.toTypedArray(), checked) { _, i, on -> checked[i] = on }
                .setPositiveButton("Save") { _, _ ->
                    val out = codes.filterIndexed { i, _ -> checked[i] } + extra
                    put("subLangs", out.joinToString(","))
                    again()
                }
                .setNeutralButton("Any (first 5)") { _, _ -> put("subLangs", ""); again() }
                .setNegativeButton("Cancel", null).show()
        }

        fun fixOld() { // 0.0.279: Title.en.srt.txt -> Title.en.srt
            MaterialAlertDialogBuilder(act).setTitle("Fix old subtitle names")
                .setMessage("Earlier versions saved some subtitles as Title.en.srt.txt. This renames them to Title.en.srt in the Downloads folder. Nothing else is touched and no file is overwritten.")
                .setPositiveButton("Fix now") { _, _ ->
                    Util.toast(act, "Working...")
                    Thread {
                        val r = SubFix.run(act)
                        act.runOnUiThread { Util.toast(act, SubFix.message(act, r)) }
                    }.start()
                }
                .setNegativeButton("Cancel", null).show()
        }

        fill = {
            box.removeAllViews()
            // ---- example ----
            val ex = card()
            ex.addView(TextView(act).apply {
                text = "Example of what you get"
                setTextColor(cDim); setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f); setPadding(dp(12), dp(8), dp(12), 0)
            })
            ex.addView(TextView(act).apply {
                text = example()
                setTextColor(cText); setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f); typeface = Typeface.DEFAULT_BOLD
                setPadding(dp(12), dp(2), dp(12), 0)
            })
            if (bool("autoSubs", true)) ex.addView(TextView(act).apply {
                text = exampleSub()
                setTextColor(cDim); setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setPadding(dp(12), dp(2), dp(12), dp(10))
            }) else ex.addView(View(act), LinearLayout.LayoutParams(MATCH, dp(10)))
            box.addView(ex, LinearLayout.LayoutParams(MATCH, WRAP))

            // ---- subtitles ----
            box.addView(head("Subtitles"))
            val sc = card()
            sw(sc, "Download subtitles with the video", "Same file name, e.g. Title.en.srt", "autoSubs", true)
            val langs = str("subLangs", "en").ifBlank { "Any (first 5 found)" }
            pick(sc, "Languages", langs, "Which languages to save") { langDialog() }
            val fi = FMT.indexOf(str("subFmt", "srt")).let { if (it < 0) 0 else it }
            pick(sc, "Format", FMT_NAMES[fi].substringBefore(" ("), "ASS, TTML, DFXP, SBV and JSON are converted") {
                single("Subtitle format", FMT_NAMES, fi) { i -> put("subFmt", FMT[i]) }
            }
            run { // 0.0.351 (V82): time shift of every SAVED subtitle file (the player has its own live delay). 0 = off = file saved as before
                val cur = try { ChromeBridge.sync.get("subDelayMs").optInt("subDelayMs", 0) } catch (e: Exception) { 0 }
                val vals = intArrayOf(-5000, -2000, -1000, -500, -250, 0, 250, 500, 1000, 2000, 5000).toMutableList().also { if (!it.contains(cur)) { it.add(cur); it.sort() } }
                fun lbl(v: Int) = if (v == 0) "Off" else (if (v > 0) "+" else "") + (v / 1000f).toString().removeSuffix(".0") + " s"
                pick(sc, "Subtitle delay (saved files)", lbl(cur), "+ = later, - = earlier. A shifted file is saved as .srt / .vtt") {
                    single("Subtitle delay (saved files)", vals.map { lbl(it) }.toTypedArray(), vals.indexOf(cur)) { i -> put("subDelayMs", vals[i]) }
                }
            }
            sw(sc, "Check inside each file", "Skips thumbnail / preview tracks and the wrong language", "subCheck", true)
            val fb = str("subFallback", "any")
            pick(sc, "If a wanted language is missing", if (fb == "none") "Save nothing" else "Save another one", null) {
                single("If a wanted language is missing", arrayOf("Save the best other one", "Save nothing"), if (fb == "none") 1 else 0) { i -> put("subFallback", if (i == 1) "none" else "any") }
            }
            pick(sc, "Fix old subtitle names", "Run", "Turns Title.en.srt.txt into Title.en.srt in Downloads") { fixOld() } // 0.0.279
            box.addView(sc, LinearLayout.LayoutParams(MATCH, WRAP))

            // ---- file names ----
            box.addView(head("File names"))
            val nc = card()
            val plex = str("libraryMode", "off") == "plex"
            run { // the library switch writes 'plex' / 'off' (not a boolean)
                val r = rowView("Plex / Jellyfin / Emby / Kodi names", null, "Show/Season 01/Show - S01E05. Replaces the template and folder below.")
                val s = SwitchCompat(act).apply { isChecked = plex; ThazirSwitch.tint(this) } // A10
                r.addView(s, LinearLayout.LayoutParams(WRAP, WRAP))
                val flip = { put("libraryMode", if (plex) "off" else "plex"); again() }
                r.setOnClickListener { flip() }
                s.setOnClickListener { flip() }
                nc.addView(r, LinearLayout.LayoutParams(MATCH, WRAP))
            }
            val tokens = "Tokens: {title} {series} {book} {chapter} {episode} {season} {sxe} {index} {site} {name} {res} {date} {time} {kind} {lang}"
            pick(nc, "Name template", str("nameTemplate", "{title}"), if (plex) "Not used while Plex names are on" else null) {
                preset("Name template", "nameTemplate", TEMPLATES, "{title}", tokens)
            }
            pick(nc, "Sub-folder", str("subfolder", "").ifEmpty { "(none)" }, if (plex) "Not used while Plex names are on" else "Inside the download folder") {
                preset("Sub-folder", "subfolder", FOLDERS, "", tokens)
            }
            sw(nc, "Remove the website name from titles", "\"Site - Naruto Episode 5\" becomes \"Naruto Episode 5\"", "cleanTitles", true)
            pick(nc, "Extra words to remove", str("titleStrip", "").ifEmpty { "(none)" }, "Site names or words, comma separated") {
                text("Extra words to remove", "Comma separated, e.g. SiteName, [HD]", "titleStrip", "")
            }
            val si = SOURCES.indexOf(str("titleSource", "auto")).let { if (it < 0) 0 else it }
            pick(nc, "Where the series name is read", SOURCE_NAMES[si], "The others stay as fallbacks") {
                single("Series name source", SOURCE_NAMES, si) { i -> put("titleSource", SOURCES[i]) }
            }
            sw(nc, "Zero-pad chapter numbers", "Chapter 5 becomes Chapter 005 so files sort correctly", "padChapters", true)
            box.addView(nc, LinearLayout.LayoutParams(MATCH, WRAP))
        }
        fill()
        d.setContentView(root)
        d.behavior.skipCollapsed = true
        d.show()
        d.behavior.state = BottomSheetBehavior.STATE_EXPANDED
    }
}
