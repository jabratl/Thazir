package com.jabra.mgb

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.core.widget.NestedScrollView
import android.widget.TextView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.json.JSONArray
import org.json.JSONObject

/**
 * S53: V77 tools inside the native Files detected panel (panel bottom bar > TOOLS).
 *  - Episodes / chapters batch: list comes from content.js (storage.local mgNativeCh), select / filter / range,
 *    start = V77 message batch-episodes (same as the Grabber page list), live status from storage.local mgBatchLive, Stop = batch-stop.
 *  - All subtitles (.srt / .vtt / .txt) = V77 subs-all, subtitles from the player = V77 subs-capture.
 *  - Play & scan = V77 play-scan, Open full Grabber (manga pages, jobs) = V77 open-panel in the page.
 * V77 does all the work; this only asks and shows.
 */
object NativeBatch {
    private val main = Handler(Looper.getMainLooper())
    private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

    class Ep(val url: String, val label: String)
    class Found(val book: String, val eps: List<Ep>)

    private fun noFrag(u: String) = u.substringBefore('#')

    /** Episode / chapter list the page script found for this page (null = none). */
    fun found(ref: String): Found? {
        try {
            val o = ChromeBridge.local.get("mgNativeCh").optJSONObject("mgNativeCh") ?: return null
            if (noFrag(o.optString("url")) != noFrag(ref)) return null
            val a = o.optJSONArray("chapters") ?: return null
            val list = ArrayList<Ep>()
            for (i in 0 until a.length()) {
                val c = a.optJSONObject(i) ?: continue
                val u = c.optString("url")
                if (u.isNotEmpty()) list.add(Ep(u, c.optString("label").ifBlank { u }))
            }
            if (list.isEmpty()) return null
            return Found(o.optString("book").ifBlank { "video" }, list)
        } catch (e: Exception) {
            Diag.e("novel", "mgNativeCh unreadable", e) // S83
            return null
        }
    }

    class Mg(val n: Int, val site: String)

    /** S55: manga / gallery info the page script published (storage.local mgNativeMg); null = not a manga page. */
    fun manga(ref: String): Mg? {
        try {
            val o = ChromeBridge.local.get("mgNativeMg").optJSONObject("mgNativeMg") ?: return null
            if (noFrag(o.optString("url")) != noFrag(ref)) return null
            if (!o.optBoolean("has", false)) return null
            return Mg(o.optInt("n", 0), o.optString("site"))
        } catch (e: Exception) {
            Diag.e("manga", "mgNativeMg unreadable", e) // S83
            return null
        }
    }

    /** S55: V77 does the work: how = cbz / zip / pdf / epub / read / view (pages screen). */
    fun grab(act: Activity, tabId: Int, how: String) {
        EngineApi.send(JSONObject().put("type", "grab-pages").put("tabId", tabId).put("how", how))
        Util.toast(act, "Loading all pages...")
    }

    private fun mangaMenu(act: Activity, tabId: Int, closePanel: () -> Unit) {
        val how = arrayOf("cbz", "zip", "pdf", "epub", "read", "view")
        MaterialAlertDialogBuilder(act).setTitle("Manga pages")
            .setItems(arrayOf("Save as CBZ", "Save as ZIP", "Save as PDF", "Save as EPUB", "Read", "Pick pages")) { _, i ->
                grab(act, tabId, how[i])
                closePanel()
            }
            .setNegativeButton("Cancel", null).show()
    }

    // ---- S58: batch options (quality = V77 maxHeight, if missing = batch-episodes miss, parallel = V77 batchParallel) ----
    private val MISS = arrayOf("near", "low", "high", "ask", "skip")
    private val MISS_NAMES = arrayOf("Closest quality", "Lower quality", "Higher quality", "Ask me for each episode", "Skip the episode")

    private fun prefs(c: android.content.Context) = c.getSharedPreferences("mgb_ui", android.content.Context.MODE_PRIVATE)
    private fun missOf(c: android.content.Context): String = (prefs(c).getString("nb_miss", "near") ?: "near").let { if (it in MISS) it else "near" }
    private fun parOf(): Int = try { ChromeBridge.sync.get("batchParallel").optInt("batchParallel", 1).coerceIn(1, 4) } catch (e: Exception) { 1 }
    private fun boolOf(k: String, def: Boolean): Boolean = try { ChromeBridge.sync.get(k).optBoolean(k, def) } catch (e: Exception) { def }
    private fun intOf(k: String, def: Int): Int = try { ChromeBridge.sync.get(k).optInt(k, def) } catch (e: Exception) { def }
    private fun delayText(): String = intOf("requestDelayMs", 0).let { if (it <= 0) "None" else (it / 1000f).toString().removeSuffix(".0") + " s" }
    private fun qOf(): String = DetectedPanel.maxQ().let { if (it > 0) "up to ${it}p" else "Best" }

    fun optionsText(act: Activity): String =
        "Quality: " + qOf() + "   ·   if missing: " + MISS_NAMES[MISS.indexOf(missOf(act))].lowercase() + "   ·   " + parOf() + " at a time   ▸"

    private fun choice(act: Activity, title: String, names: Array<String>, cur: Int, pick: (Int) -> Unit, back: () -> Unit) {
        MaterialAlertDialogBuilder(act).setTitle(title)
            .setSingleChoiceItems(names, cur) { d, i -> pick(i); d.dismiss(); back() }
            .setNegativeButton("Back") { _, _ -> back() }.show()
    }

    private fun batchOptions(act: Activity, changed: () -> Unit) {
        val items = arrayOf(
            "Video quality:  " + qOf(),
            "If the quality is missing:  " + MISS_NAMES[MISS.indexOf(missOf(act))],
            "Episodes searched at the same time:  " + parOf(),
            "Skip chapters already downloaded:  " + (if (boolOf("skipDone", true)) "On" else "Off"),
            "Pause between page requests:  " + delayText()
        )
        val again = { changed(); batchOptions(act, changed) }
        MaterialAlertDialogBuilder(act).setTitle("Batch options")
            .setItems(items) { _, i ->
                when (i) {
                    0 -> {
                        val v = intArrayOf(0, 4320, 2160, 1440, 1080, 720, 480, 360)
                        val n = arrayOf("Best available", "Up to 4320p (8K)", "Up to 2160p (4K)", "Up to 1440p", "Up to 1080p", "Up to 720p", "Up to 480p", "Up to 360p")
                        choice(act, "Video quality (best up to)", n, v.indexOf(DetectedPanel.maxQ()).let { if (it < 0) 0 else it },
                            { k -> ChromeBridge.storageSet("sync", JSONObject().put("maxHeight", v[k])) }, again)
                    }
                    1 -> choice(act, "If the quality is missing", MISS_NAMES, MISS.indexOf(missOf(act)),
                        { k -> prefs(act).edit().putString("nb_miss", MISS[k]).apply() }, again)
                    2 -> choice(act, "Episodes at the same time", arrayOf("1 (in order, safest)", "2", "3", "4"), parOf() - 1,
                        { k -> ChromeBridge.storageSet("sync", JSONObject().put("batchParallel", k + 1)) }, again)
                    3 -> { ChromeBridge.storageSet("sync", JSONObject().put("skipDone", !boolOf("skipDone", true))); again() }
                    else -> {
                        val dv = intArrayOf(0, 500, 1000, 2000, 5000)
                        choice(act, "Pause between page requests", arrayOf("None", "0.5 s", "1 s", "2 s", "5 s"), dv.indexOf(intOf("requestDelayMs", 0)).let { if (it < 0) 0 else it },
                            { k -> ChromeBridge.storageSet("sync", JSONObject().put("requestDelayMs", dv[k])) }, again)
                    }
                }
            }
            .setPositiveButton("Done") { _, _ -> changed() }.show()
    }

    fun tools(act: Activity, tabId: Int, ref: String, subCount: Int, shownCount: Int, downloadAll: (() -> Unit)?, shownJobs: () -> List<JSONObject>, openUrl: (String) -> Unit, closePanel: () -> Unit) {
        val f = found(ref)
        val mg = manga(ref)
        val page = ArrayList<ToolsSheet.Item>()
        if (mg != null) page.add(ToolsSheet.Item(R.drawable.ic_screen, "Manga pages" + (if (mg.n > 0) " (${mg.n})" else ""), "Save as CBZ, ZIP, PDF or EPUB, read, or pick pages") { mangaMenu(act, tabId, closePanel) })
        if (f != null) page.add(ToolsSheet.Item(R.drawable.ic_list, "Episodes / chapters (${f.eps.size})", "Select, filter by range and download many at once") { episodes(act, tabId, f) })
        if (shownCount > 0 && downloadAll != null) page.add(ToolsSheet.Item(R.drawable.ic_download, "Download all ($shownCount files)", "Every file shown now, best quality") { downloadAll() })
        page.add(ToolsSheet.Item(R.drawable.ic_add, "Paste links (batch)", "Download from a list of page links") { pasteLinks(act, tabId, ref) })
        page.add(ToolsSheet.Item(R.drawable.ic_theme, "Buttons on this site", DlMode.siteLabel(act, Util.host(ref))) { DlMode.siteDialog(act, Util.host(ref)) {} })

        val auto = ArrayList<ToolsSheet.Item>()
        auto.add(ToolsSheet.Item(R.drawable.ic_bookmark, "Followed manga", Follow.count().let { if (it > 0) "$it followed, new chapter alerts" else "Follow this page for new chapter alerts" }) { Follow.open(act, ref, f, openUrl) })
        auto.add(ToolsSheet.Item(R.drawable.ic_history, "Scheduled downloads", Sched.count().let { if (it > 0) "$it waiting" else "Start downloads at a time you choose" }) { Sched.open(act, shownJobs) })

        val subs = ArrayList<ToolsSheet.Item>()
        if (subCount > 0) subs.add(ToolsSheet.Item(R.drawable.ic_pl_cc, "All subtitles of this page ($subCount)", "Save as SRT, VTT or TXT") { subFormat(act, tabId) })
        subs.add(ToolsSheet.Item(R.drawable.ic_pl_cc, "Subtitles from the player", "Read the subtitle the player is showing") { subsCapture(act, tabId) })

        val set = listOf(
            ToolsSheet.Item(R.drawable.ic_edit, "Subtitles and file names", "Languages, format, Plex / Jellyfin names") { SubName.open(act) },
            ToolsSheet.Item(R.drawable.ic_settings, "Download engine", "Speed, retries, limits, live recording") { DlEngine.open(act) },
            ToolsSheet.Item(R.drawable.ic_settings, "Grabber settings", "Buttons, sites, backup, reset") { GrabSet.open(act) }
        )
        val more = listOf(
            ToolsSheet.Item(R.drawable.ic_search, "Play and scan", "Find videos hidden behind the player") {
                EngineApi.send(JSONObject().put("type", "play-scan").put("tabId", tabId))
                Util.toast(act, "Scanning... open Detected files again in a few seconds")
            },
            ToolsSheet.Item(R.drawable.ic_book, "Novel grabber", "Chapters or a whole novel to EPUB, DOCX, TXT") { // N1
                if (!ExtScreen.open(act, ContentInjector.EXT_BASE + "novel/studio.html")) Util.toast(act, "Could not open Novel grabber")
                closePanel()
            },
            ToolsSheet.Item(R.drawable.ic_info, "Diagnose this page", "Why does it not work? Report, copy or share") { // S85
                closePanel()
                DiagScreen.open(act, null, tabId)
            },
            ToolsSheet.Item(R.drawable.ic_share, "Open full Grabber", "Pick pages, job list and the V82 page") {
                EngineApi.send(JSONObject().put("type", "open-panel").put("tabId", tabId))
                closePanel()
            }
        )
        ToolsSheet.show(act, "Tools", Util.host(ref), listOf(
            ToolsSheet.Group("This page", page), ToolsSheet.Group("Automatic", auto), ToolsSheet.Group("Subtitles", subs),
            ToolsSheet.Group("Settings", set), ToolsSheet.Group("More", more)
        ))
    }

    /** S56: list of pasted page links -> the same V77 batch-episodes as the episode list. */
    private fun pasteLinks(act: Activity, tabId: Int, ref: String) {
        val et = EditText(act).apply {
            hint = "One page link per line"
            minLines = 6
            gravity = Gravity.TOP or Gravity.START
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
            setTextColor(act.getColor(R.color.mg_text))
            setHintTextColor(act.getColor(R.color.mg_text_dim))
        }
        try {
            val cm = act.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            val t = cm.primaryClip?.getItemAt(0)?.coerceToText(act)?.toString() ?: ""
            if (t.contains("http")) et.setText(t)
        } catch (e: Exception) { /* no clipboard access */ }
        val pad = Util.dp(act, 20)
        val box = LinearLayout(act).apply { setPadding(pad, Util.dp(act, 8), pad, 0); addView(et, LinearLayout.LayoutParams(MATCH, WRAP)) }
        MaterialAlertDialogBuilder(act).setTitle("Paste links").setView(box)
            .setPositiveButton("Next") { _, _ ->
                val urls = Regex("https?://[^\\s\"'<>]+").findAll(et.text.toString()).map { it.value }.distinct().take(300).toList()
                if (urls.isEmpty()) { Util.toast(act, "No link found"); return@setPositiveButton }
                val eps = urls.mapIndexed { i, u ->
                    val slug = try { java.net.URLDecoder.decode(u.substringBefore('#').substringBefore('?').trimEnd('/').substringAfterLast('/'), "UTF-8") } catch (e: Exception) { "" }
                    val nm = slug.replace('-', ' ').replace('_', ' ').trim().take(60)
                    Ep(u, if (nm.isNotBlank()) nm else "Link ${i + 1}")
                }
                episodes(act, tabId, Found(found(ref)?.book ?: "video", eps), true)
            }
            .setNegativeButton("Cancel", null).show()
    }

    private fun subFormat(act: Activity, tabId: Int) {
        val fm = arrayOf("srt", "vtt", "txt")
        MaterialAlertDialogBuilder(act).setTitle("Save all subtitles as")
            .setItems(arrayOf(".srt", ".vtt", ".txt")) { _, i ->
                EngineApi.ask(JSONObject().put("type", "subs-all").put("tabId", tabId).put("fmt", fm[i])) { r ->
                    val n = r?.optInt("n", 0) ?: 0
                    Util.toast(act, if (n > 0) "$n subtitle file(s) saved" else "No subtitle saved")
                }
            }
            .setNegativeButton("Cancel", null).show()
    }

    private fun subsCapture(act: Activity, tabId: Int) {
        Util.toast(act, "Reading subtitles from the player...")
        EngineApi.ask(JSONObject().put("type", "subs-capture").put("tabId", tabId).put("fmt", "srt")) { r ->
            val n = r?.optInt("n", 0) ?: 0
            val err = r?.optString("err") ?: ""
            Util.toast(act, if (n > 0) "$n subtitle file(s) saved" else if (err.isNotEmpty()) err else "No subtitles found in the player")
        }
    }

    private fun episodes(act: Activity, tabId: Int, f: Found, preselect: Boolean = false) {
        val cText = act.getColor(R.color.mg_text)
        val cDim = act.getColor(R.color.mg_text_dim)
        val cPrimary = act.getColor(R.color.mg_primary)
        fun dp(v: Int) = Util.dp(act, v)
        val d = BottomSheetDialog(act)
        val root = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(12))
            layoutParams = ViewGroup.LayoutParams(MATCH, (act.resources.displayMetrics.heightPixels * 0.85f).toInt())
        }
        root.addView(TextView(act).apply {
            text = "Episodes / chapters (${f.eps.size})"
            setTextColor(cText)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            typeface = Typeface.DEFAULT_BOLD
        })
        val filter = EditText(act).apply { hint = "Filter by name"; setSingleLine(); setTextColor(cText); setHintTextColor(cDim) }
        val range = EditText(act).apply { hint = "Range, e.g. 1-12 (numbers of the list)"; setSingleLine(); setTextColor(cText); setHintTextColor(cDim) }
        val allCb = CheckBox(act).apply { text = "Select all shown"; setTextColor(cText) }
        val listBox = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        // 0.0.352: NestedScrollView (like DetectedPanel / DlEngine sheets). A plain ScrollView does not pass its scroll to the BottomSheet, so the sheet itself got the finger and slid down / closed while the list was scrolled.
        val scroll = NestedScrollView(act).apply { overScrollMode = View.OVER_SCROLL_NEVER; addView(listBox) }
        val status = TextView(act).apply { setTextColor(cDim); setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f); setPadding(0, dp(6), 0, dp(6)) }
        fun pill(t: String, bg: Int, fg: Int) = TextView(act).apply {
            text = t
            gravity = Gravity.CENTER
            setTextColor(fg)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = GradientDrawable().apply { setColor(bg); cornerRadius = dp(24).toFloat() }
        }
        val btn = pill("DOWNLOAD (0)", cPrimary, Color.WHITE)
        val stop = pill("STOP", 0xFFE53935.toInt(), Color.WHITE)
        val close = pill("CLOSE", act.getColor(R.color.mg_surface_variant), cText)

        val sel = LinkedHashSet<String>()
        var polling = false
        var building = false
        fun shown(): List<Ep> {
            val q = filter.text.toString().trim().lowercase()
            return if (q.isEmpty()) f.eps else f.eps.filter { it.label.lowercase().contains(q) }
        }
        fun upd() {
            btn.text = "DOWNLOAD (${sel.size})"
            btn.alpha = if (sel.isEmpty()) 0.4f else 1f
        }
        fun fillList() {
            building = true
            listBox.removeAllViews()
            for (e in shown()) {
                val pos = f.eps.indexOf(e) + 1
                val cb = CheckBox(act).apply {
                    text = "$pos. ${e.label}"
                    setTextColor(cText)
                    isChecked = sel.contains(e.url)
                    setOnCheckedChangeListener { _, on ->
                        if (!building) { if (on) sel.add(e.url) else sel.remove(e.url); upd() }
                    }
                }
                listBox.addView(cb, LinearLayout.LayoutParams(MATCH, WRAP))
            }
            building = false
        }
        filter.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) { fillList() }
        })
        range.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val m = Regex("^\\s*(\\d+)\\s*(?:[-–]\\s*(\\d+))?\\s*$").find(s?.toString() ?: "") ?: return
                val a = m.groupValues[1].toInt()
                val z = if (m.groupValues[2].isEmpty()) a else m.groupValues[2].toInt()
                sel.clear()
                for (e in shown()) { val p = f.eps.indexOf(e) + 1; if (p in minOf(a, z)..maxOf(a, z)) sel.add(e.url) }
                fillList()
                upd()
            }
        })
        allCb.setOnCheckedChangeListener { _, on ->
            for (e in shown()) { if (on) sel.add(e.url) else sel.remove(e.url) }
            fillList()
            upd()
        }
        close.setOnClickListener { d.dismiss() }

        val top = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        top.addView(filter, LinearLayout.LayoutParams(MATCH, WRAP))
        top.addView(range, LinearLayout.LayoutParams(MATCH, WRAP))
        val optBtn = TextView(act).apply { // S58
            setTextColor(cText)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = GradientDrawable().apply { setColor(act.getColor(R.color.mg_surface_variant)); cornerRadius = dp(14).toFloat() }
            text = optionsText(act)
        }
        optBtn.setOnClickListener { batchOptions(act) { optBtn.text = optionsText(act) } }
        top.addView(optBtn, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(6); bottomMargin = dp(2) })
        top.addView(allCb, LinearLayout.LayoutParams(MATCH, WRAP))
        root.addView(top, LinearLayout.LayoutParams(MATCH, WRAP))
        root.addView(scroll, LinearLayout.LayoutParams(MATCH, 0, 1f))
        root.addView(status, LinearLayout.LayoutParams(MATCH, WRAP))
        val bar = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL }
        bar.addView(btn, LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginEnd = dp(8) })
        bar.addView(close, LinearLayout.LayoutParams(0, WRAP, 1f))
        root.addView(bar, LinearLayout.LayoutParams(MATCH, WRAP))
        if (preselect) f.eps.forEach { sel.add(it.url) } // S56: pasted list starts fully selected
        fillList()
        upd()

        val rows = HashMap<String, TextView>()
        var askShown = ""
        var askBusy = false
        fun askQuality(q: JSONObject) {
            val qid = q.optString("id")
            val want = q.optInt("want", 0)
            val av = q.optJSONArray("avail")
            val hs = ArrayList<Int>()
            if (av != null) for (i in 0 until av.length()) hs.add(av.optInt(i))
            hs.sortWith(compareBy({ Math.abs(it - want) }, { it }))
            askBusy = true
            askShown = qid
            val rest = booleanArrayOf(false)
            fun answer(h: Int) {
                askBusy = false
                val mode = if (!rest[0]) "" else if (h == 0) "skip" else if (h < want) "low" else "high"
                EngineApi.send(JSONObject().put("type", "batch-ask-answer").put("id", qid).put("h", h).put("mode", mode))
            }
            val pad = Util.dp(act, 20)
            val box = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, Util.dp(act, 8), pad, 0) }
            val group = android.widget.RadioGroup(act)
            val vals = ArrayList<Int>()
            hs.forEachIndexed { i, h ->
                group.addView(android.widget.RadioButton(act).apply { text = h.toString() + "p"; id = 1000 + i; setTextColor(act.getColor(R.color.mg_text)) })
                vals.add(h)
            }
            group.addView(android.widget.RadioButton(act).apply { text = "Skip this episode"; id = 1000 + hs.size; setTextColor(act.getColor(R.color.mg_text)) })
            vals.add(0)
            group.check(1000)
            box.addView(group)
            val same = CheckBox(act).apply { text = "Do the same for the rest of this batch"; setTextColor(act.getColor(R.color.mg_text_dim)) }
            box.addView(same)
            MaterialAlertDialogBuilder(act).setTitle(q.optString("label") + ": " + want + "p is not available")
                .setView(box)
                .setCancelable(false)
                .setPositiveButton("OK") { _, _ ->
                    rest[0] = same.isChecked
                    answer(vals.getOrElse(group.checkedRadioButtonId - 1000) { 0 })
                }
                .show()
        }
        fun pollOnce(picks: List<Ep>) {
            val b = try { ChromeBridge.local.get("mgBatchLive").optJSONObject("mgBatchLive") } catch (e: Exception) { null } ?: return
            if (b.optInt("tab", -1) != tabId) return
            val q = b.optJSONObject("ask") // S61: "Ask me" quality question
            if (q != null && q.optString("id") != askShown && !askBusy) askQuality(q)
            val total = b.optInt("total", picks.size)
            status.text = b.optString("text") + "   (" + b.optInt("ok", 0) + "/" + total + ")"
            val eps = b.optJSONObject("eps")
            for (e in picks) {
                val st = eps?.optJSONObject(e.url)?.optString("st") ?: ""
                rows[e.url]?.text = e.label + (if (st.isNotEmpty()) "   [" + st + "]" else "")
            }
        }
        btn.setOnClickListener {
            if (polling) return@setOnClickListener
            if (sel.isEmpty()) { Util.toast(act, "Select episodes first"); return@setOnClickListener }
            val picks = f.eps.filter { sel.contains(it.url) }
            val arr = JSONArray()
            for (e in picks) arr.put(JSONObject().put("url", e.url).put("label", e.label).put("book", f.book).put("title", f.book + " - " + e.label))
            val cap = DetectedPanel.maxQ().let { if (it > 0) it.toString() else "" }
            EngineApi.send(JSONObject().put("type", "batch-episodes").put("tabId", tabId).put("cap", cap).put("miss", missOf(act)).put("episodes", arr))
            Util.toast(act, "Finding videos in ${picks.size} episode(s)...")
            // status view
            polling = true
            top.visibility = android.view.View.GONE
            listBox.removeAllViews()
            for (e in picks) {
                val t = TextView(act).apply { text = e.label; setTextColor(cText); setPadding(0, dp(6), 0, dp(6)) }
                rows[e.url] = t
                listBox.addView(t, LinearLayout.LayoutParams(MATCH, WRAP))
            }
            status.text = "Starting the batch..."
            bar.removeAllViews()
            bar.addView(stop, LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginEnd = dp(8) })
            bar.addView(close, LinearLayout.LayoutParams(0, WRAP, 1f))
            val tick = object : Runnable {
                override fun run() {
                    if (!polling) return
                    pollOnce(picks)
                    main.postDelayed(this, 2000)
                }
            }
            main.postDelayed(tick, 1500)
        }
        stop.setOnClickListener {
            EngineApi.send(JSONObject().put("type", "batch-stop").put("tabId", tabId))
            Util.toast(act, "Stopping...")
        }
        d.setOnDismissListener { polling = false }
        d.setContentView(root)
        d.behavior.skipCollapsed = true
        try { d.behavior.isDraggable = false } catch (e: Exception) { /* old behaviour: swipe-down closes */ } // 0.0.352: a scroll on the list can never drag / close the sheet; CLOSE, back and a tap outside still close it
        d.show()
        d.behavior.state = BottomSheetBehavior.STATE_EXPANDED
    }
}
