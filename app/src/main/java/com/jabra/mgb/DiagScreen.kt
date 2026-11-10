package com.jabra.mgb

import android.app.Activity
import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.File

/**
 * S85: Diagnose part 4/5 = the native report screen ("Report center").
 * Full-screen window (same style as Bookmarks / Downloads): Verdict card on top, then fold cards
 * Video / Novel / Manga / Browser / Download / Device, and buttons Copy, Share, Save .txt.
 * Data = DiagReport (S84). Light + dark through the mg_* colours, no horizontal scroll (all text wraps).
 *
 * Entry points: Tools sheet "Diagnose this page" (NativeBatch), 3-dot menu "Report center" (MainActivity),
 * Settings > Downloads "Report center" (open report, log size, clear log).
 */
object DiagScreen {
    private const val TAG = "DiagScreen"
    private val GROUPS = listOf("Video", "Novel", "Manga", "Browser", "Download", "Device")

    private var current: Screen? = null

    /** tabId < 0 = the active tab. [tabs] null = the shared one from BookmarksScreen.tabsRef. */
    fun open(host: Activity, tabs: TabManager? = null, tabId: Int = -1, focusText: String? = null, focusUrl: String = "") {
        if (host.isFinishing || host.isDestroyed) return
        closeAll()
        try {
            val s = Screen(host, tabs ?: BookmarksScreen.tabsRef, tabId, focusText, focusUrl) // S86: focus = one failed download ("Why?")
            current = s
            s.show()
        } catch (e: Exception) {
            Log.e(TAG, "open failed", e)
            current = null
        }
    }

    fun closeAll() {
        val s = current ?: return
        current = null
        try { s.dismiss() } catch (_: Exception) { }
    }

    // ------------------------------------------------------------ settings helpers (S85 entry 3)

    /** One line for Settings: how many events the log holds. */
    fun logSizeText(): String {
        val n = Diag.size()
        val f = Diag.dump(Diag.MAX).length
        return if (n == 0) "empty" else "$n events, " + (if (f >= 1024) "${f / 1024} KB" else "$f B")
    }

    /** Ask, then clear the S82 event log and the V77 log (mgLog). */
    fun askClearLog(act: Activity, done: () -> Unit) {
        MaterialAlertDialogBuilder(act)
            .setTitle("Clear log")
            .setMessage("Delete the diagnose log (${logSizeText()}) and the Grabber log? Nothing else is touched.")
            .setPositiveButton("Clear") { _, _ ->
                try { Diag.clear() } catch (_: Throwable) { }
                try { ChromeBridge.local.remove("mgLog") } catch (_: Throwable) { }
                Util.toast(act, "Log cleared")
                done()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ------------------------------------------------------------ file save

    /** Saves [text] as a .txt in the chosen download folder (or Downloads). Returns a short place label, or null on failure. */
    private fun saveTxt(c: Context, text: String): String? {
        val name = "mgb-report-" + java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US).format(java.util.Date()) + ".txt"
        val bytes = text.toByteArray(Charsets.UTF_8)
        try {
            val tree = DownloadFolder.treeUri(c)
            if (tree != null) {
                val root = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
                val doc = DocumentsContract.createDocument(c.contentResolver, root, "text/plain", name)
                if (doc != null) {
                    c.contentResolver.openOutputStream(doc)?.use { it.write(bytes) }
                    return DownloadFolder.label(c) + "/" + name
                }
            }
        } catch (e: Throwable) { Log.w(TAG, "tree save failed", e) }
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                val cv = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, name)
                    put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                    put(MediaStore.Downloads.RELATIVE_PATH, "Download/")
                }
                val uri = c.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv)
                if (uri != null) {
                    c.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                    return "Downloads/$name"
                }
            } else {
                val dir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)
                dir.mkdirs()
                File(dir, name).writeBytes(bytes)
                return "Downloads/$name"
            }
        } catch (e: Throwable) { Log.w(TAG, "downloads save failed", e) }
        try { // last resort: the app's own folder (always writable)
            val dir = c.getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS) ?: c.filesDir
            dir.mkdirs()
            File(dir, name).writeBytes(bytes)
            return "App folder/$name"
        } catch (e: Throwable) { Log.w(TAG, "app save failed", e) }
        return null
    }

    // ------------------------------------------------------------ the window

    private class Screen(
        private val act: Activity,
        private val tabs: TabManager?,
        private val tabId: Int,
        private val focusText: String? = null,
        private val focusUrl: String = ""
    ) : Dialog(act, com.google.android.material.R.style.Theme_Material3_DayNight_NoActionBar) {

        private val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        private val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
        private fun dp(v: Int) = Util.dp(act, v)

        private var report: DiagReport.Report? = null
        private var body: LinearLayout? = null
        private val open = HashSet<String>() // groups the person opened by hand
        private var seeded = false
        private var buildId = 0

        init { setOnDismissListener { if (current === this) current = null } }

        private val night: Boolean
            get() = (act.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES

        private fun warnColor() = if (night) 0xFFFBBF24.toInt() else 0xFFB45309.toInt()

        private fun levelColor(level: String): Int = when (level) {
            DiagReport.BAD -> act.getColor(R.color.mg_error)
            DiagReport.WARN -> warnColor()
            DiagReport.OK -> act.getColor(R.color.mg_success)
            else -> act.getColor(R.color.mg_text_dim)
        }

        private fun round(color: Int, r: Int, stroke: Int? = null) = GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(r).toFloat()
            if (stroke != null) setStroke(dp(1), stroke)
        }

        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)
            build()
            window?.let {
                it.setLayout(MATCH, MATCH)
                it.setBackgroundDrawable(ColorDrawable(act.getColor(R.color.mg_bg)))
            }
            load()
        }

        @Suppress("OVERRIDE_DEPRECATION")
        override fun onBackPressed() { dismiss() }

        // ---------------------------------------------------------- layout

        private fun build() {
            val root = LinearLayout(act).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(act.getColor(R.color.mg_bg))
            }
            root.addView(header(), LinearLayout.LayoutParams(MATCH, dp(56)))
            val box = LinearLayout(act).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(14), dp(4), dp(14), dp(24))
            }
            body = box
            val sv = ScrollView(act).apply { isFillViewport = false; overScrollMode = View.OVER_SCROLL_NEVER }
            sv.addView(box, ViewGroup.LayoutParams(MATCH, WRAP))
            root.addView(sv, LinearLayout.LayoutParams(MATCH, 0, 1f))
            setContentView(root)
        }

        private fun header(): View {
            val h = LinearLayout(act).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(4), 0, dp(4), 0)
            }
            h.addView(ImageView(act).apply {
                setImageResource(R.drawable.ic_back)
                setColorFilter(act.getColor(R.color.mg_text))
                setPadding(dp(10), dp(10), dp(10), dp(10))
                contentDescription = "Back"
                setOnClickListener { dismiss() }
            }, LinearLayout.LayoutParams(dp(44), dp(48)))
            h.addView(TextView(act).apply {
                text = "Report center"
                setTextColor(act.getColor(R.color.mg_text))
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 18f)
                typeface = Typeface.DEFAULT_BOLD
                maxLines = 1
                setPadding(dp(6), 0, 0, 0)
            }, LinearLayout.LayoutParams(0, WRAP, 1f))
            h.addView(ImageView(act).apply {
                setImageResource(R.drawable.ic_reload)
                setColorFilter(act.getColor(R.color.mg_text))
                setPadding(dp(10), dp(10), dp(10), dp(10))
                contentDescription = "Refresh"
                setOnClickListener { load() }
            }, LinearLayout.LayoutParams(dp(44), dp(48)))
            return h
        }

        // ---------------------------------------------------------- data

        private fun load() {
            val my = ++buildId
            val box = body ?: return
            box.removeAllViews()
            box.addView(TextView(act).apply {
                text = "Building the report..."
                setTextColor(act.getColor(R.color.mg_text_dim))
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14f)
                gravity = Gravity.CENTER
                setPadding(dp(16), dp(56), dp(16), dp(16))
            }, LinearLayout.LayoutParams(MATCH, WRAP))
            try {
                DiagReport.build(act.applicationContext, tabs, tabId) { r ->
                    if (my == buildId && isShowing) {
                        report = r
                        render()
                    }
                }
            } catch (e: Throwable) {
                Diag.e("app", "DiagScreen load failed", e)
                box.removeAllViews()
                box.addView(note("The report could not be built: " + e.javaClass.simpleName))
            }
        }

        private fun note(t: String) = TextView(act).apply {
            text = t
            setTextColor(act.getColor(R.color.mg_text_dim))
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding(dp(8), dp(24), dp(8), dp(8))
        }

        // ---------------------------------------------------------- render

        private fun render() {
            val box = body ?: return
            val r = report ?: return
            box.removeAllViews()
            if (!seeded) { // first render: open the groups that have a problem
                seeded = true
                for (g in GROUPS) if (r.sections.any { it.group == g && (it.level == DiagReport.BAD || it.level == DiagReport.WARN) }) open.add(g)
            }
            if (focusText != null) box.addView(focusCard(), LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(4) })
            box.addView(verdictCard(r), LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(if (focusText != null) 12 else 4) })
            for (f in r.findings.drop(1)) box.addView(findingCard(f), LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(8) })
            box.addView(buttons(r), LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(12) })
            for (g in GROUPS) {
                val secs = r.sections.filter { it.group == g }
                if (secs.isEmpty()) continue
                box.addView(groupCard(g, secs), LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(12) })
            }
            box.addView(TextView(act).apply {
                text = "Report time " + java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date(r.time)) +
                    ". It holds no page text, cookies or passwords. Web addresses are cut to host and path."
                setTextColor(act.getColor(R.color.mg_text_dim))
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 11f)
                setPadding(dp(6), dp(14), dp(6), 0)
            }, LinearLayout.LayoutParams(MATCH, WRAP))
        }

        private fun verdictCard(r: DiagReport.Report): View {
            val c = levelColor(r.verdictLevel)
            val title = when (r.verdictLevel) {
                DiagReport.BAD -> "Problem"
                DiagReport.WARN -> "Warning"
                DiagReport.OK -> "All OK"
                else -> "Info"
            }
            val card = LinearLayout(act).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = round(androidx.core.graphics.ColorUtils.setAlphaComponent(c, if (night) 0x2E else 0x1F), 20, androidx.core.graphics.ColorUtils.setAlphaComponent(c, 0x80))
                setPadding(dp(16), dp(14), dp(16), dp(14))
            }
            card.addView(View(act).apply { background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(c) } },
                LinearLayout.LayoutParams(dp(14), dp(14)).apply { marginEnd = dp(12) })
            val t = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
            t.addView(TextView(act).apply {
                text = title
                setTextColor(c)
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 17f)
                typeface = Typeface.DEFAULT_BOLD
            })
            t.addView(TextView(act).apply {
                text = r.verdict
                setTextColor(act.getColor(R.color.mg_text))
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14f)
                setPadding(0, dp(3), 0, 0)
            })
            r.findings.firstOrNull()?.let { f -> fixButton(f)?.let { b -> t.addView(b, LinearLayout.LayoutParams(WRAP, dp(40)).apply { topMargin = dp(10) }) } }
            card.addView(t, LinearLayout.LayoutParams(0, WRAP, 1f))
            return card
        }

        // ---------------------------------------------------------- S86 findings

        private fun fixButton(f: DiagRules.Finding): View? {
            val id = f.fix ?: return null
            return TextView(act).apply {
                text = f.fixLabel ?: "Fix"
                gravity = Gravity.CENTER
                maxLines = 1
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14f)
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(act.getColor(R.color.mg_on_primary))
                background = round(act.getColor(R.color.mg_primary), 20)
                setPadding(dp(18), 0, dp(18), 0)
                setOnClickListener { runFix(id) }
            }
        }

        /** One compact finding (the top one lives in the verdict card). */
        private fun findingCard(f: DiagRules.Finding): View {
            val c = levelColor(f.level)
            val card = LinearLayout(act).apply {
                orientation = LinearLayout.VERTICAL
                background = round(act.getColor(R.color.mg_surface), 16, act.getColor(R.color.mg_outline))
                setPadding(dp(14), dp(12), dp(14), dp(12))
            }
            val head = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            head.addView(View(act).apply { background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(c) } },
                LinearLayout.LayoutParams(dp(10), dp(10)).apply { marginEnd = dp(10) })
            head.addView(TextView(act).apply {
                text = f.what
                setTextColor(act.getColor(R.color.mg_text))
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14f)
                typeface = Typeface.DEFAULT_BOLD
            }, LinearLayout.LayoutParams(0, WRAP, 1f))
            card.addView(head)
            card.addView(TextView(act).apply {
                text = f.todo
                setTextColor(act.getColor(R.color.mg_text_dim))
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13f)
                setPadding(dp(20), dp(4), 0, 0)
            })
            fixButton(f)?.let { card.addView(it, LinearLayout.LayoutParams(WRAP, dp(38)).apply { topMargin = dp(8); marginStart = dp(20) }) }
            return card
        }

        /** "Why?" from a failed download row: its own text + masked url + the matching findings. */
        private fun focusCard(): View {
            val card = LinearLayout(act).apply {
                orientation = LinearLayout.VERTICAL
                background = round(act.getColor(R.color.mg_surface), 20, act.getColor(R.color.mg_primary))
                setPadding(dp(16), dp(14), dp(16), dp(14))
            }
            card.addView(TextView(act).apply {
                text = "This download"
                setTextColor(act.getColor(R.color.mg_primary))
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13f)
                typeface = Typeface.DEFAULT_BOLD
            })
            val info = listOfNotNull(
                focusText?.takeIf { it.isNotBlank() }?.let { "Result: " + Diag.mask(it).take(160) },
                focusUrl.takeIf { it.isNotBlank() }?.let { "Link: " + Diag.maskUrl(it) }
            ).joinToString("\n")
            if (info.isNotEmpty()) card.addView(TextView(act).apply {
                text = info
                setTextColor(act.getColor(R.color.mg_text_dim))
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12f)
                typeface = Typeface.MONOSPACE
                setTextIsSelectable(true)
                setPadding(0, dp(4), 0, dp(4))
            })
            for (f in DiagRules.explain(focusText ?: "")) card.addView(findingCard(f).apply { background = null; setPadding(0, dp(6), 0, 0) })
            return card
        }

        private fun runFix(id: String) {
            fun go(i: Intent) { try { act.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (e: Throwable) { Util.toast(act, "Cannot open this setting") } }
            when (id) {
                "wifi" -> go(Intent(android.provider.Settings.ACTION_WIFI_SETTINGS))
                "storage" -> go(Intent(android.provider.Settings.ACTION_INTERNAL_STORAGE_SETTINGS))
                "webview" -> go(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("market://details?id=com.google.android.webview")))
                "notif" -> go(Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, act.packageName))
                "battery" -> BatteryAsk.ask(act) // 0.0.403: confirm dialog first, then Android's own question
                "appinfo" -> go(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:" + act.packageName)))
                "reload" -> {
                    val t = try { if (tabId >= 0) tabs?.byId(tabId) else tabs?.active } catch (e: Throwable) { null }
                    try { t?.web?.reload() } catch (e: Throwable) { }
                    dismiss()
                }
                "settings" -> { dismiss(); SettingsScreen.open(act) }
                "downloads" -> { dismiss(); DownloadsScreen.open(act) }
            }
        }

        private fun buttons(r: DiagReport.Report): View {
            val row = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL }
            fun btn(label: String, primary: Boolean, click: () -> Unit) = TextView(act).apply {
                text = label
                gravity = Gravity.CENTER
                maxLines = 1
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14f)
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(if (primary) act.getColor(R.color.mg_on_primary) else act.getColor(R.color.mg_text))
                background = if (primary) round(act.getColor(R.color.mg_primary), 22) else round(act.getColor(R.color.mg_surface_variant), 22)
                setOnClickListener { click() }
            }
            val lp = { first: Boolean -> LinearLayout.LayoutParams(0, dp(44), 1f).apply { if (!first) marginStart = dp(8) } }
            fun txt(): String = if (DiagMore.redactOn(act)) DiagMore.redact(r.text()) else r.text() // 0.0.402: optional redact (default OFF = same text as before)
            row.addView(btn("Copy", true) {
                (act.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                    .setPrimaryClip(ClipData.newPlainText("MGB report", txt()))
                Util.toast(act, "Report copied")
            }, lp(true))
            row.addView(btn("Share", false) {
                try {
                    act.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain")
                        .putExtra(Intent.EXTRA_SUBJECT, "MGB diagnose report").putExtra(Intent.EXTRA_TEXT, txt()), null))
                } catch (e: Exception) { Util.toast(act, "Cannot share") }
            }, lp(false))
            row.addView(btn("Save .txt", false) {
                val where = saveTxt(act, txt())
                Util.toast(act, if (where != null) "Saved: $where" else "Could not save the file")
            }, lp(false))
            // 0.0.402: one line under the buttons: hide links and e-mails in what leaves the app
            val col = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
            col.addView(row, LinearLayout.LayoutParams(MATCH, WRAP))
            val tog = TextView(act).apply {
                fun show() { text = "Hide links and e-mails when copying / sharing: " + (if (DiagMore.redactOn(act)) "ON" else "OFF") + " (tap to change)" }
                show()
                setTextColor(act.getColor(R.color.mg_text_dim))
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12f)
                setPadding(dp(4), dp(10), dp(4), dp(4))
                setOnClickListener { DiagMore.setRedact(act, !DiagMore.redactOn(act)); show() }
            }
            col.addView(tog, LinearLayout.LayoutParams(MATCH, WRAP))
            return col
        }

        private fun groupCard(group: String, secs: List<DiagReport.Section>): View {
            val worst = when {
                secs.any { it.level == DiagReport.BAD } -> DiagReport.BAD
                secs.any { it.level == DiagReport.WARN } -> DiagReport.WARN
                else -> DiagReport.OK
            }
            val card = LinearLayout(act).apply {
                orientation = LinearLayout.VERTICAL
                background = round(act.getColor(R.color.mg_surface), 20, act.getColor(R.color.mg_outline))
            }
            val content = LinearLayout(act).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16), 0, dp(16), dp(12))
                visibility = if (group in open) View.VISIBLE else View.GONE
            }
            val chev = ImageView(act).apply {
                setImageResource(R.drawable.ic_chevron)
                setColorFilter(act.getColor(R.color.mg_text_dim))
                rotation = if (group in open) 90f else 0f
            }
            val head = LinearLayout(act).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(54)
                setPadding(dp(16), dp(8), dp(12), dp(8))
                val tv = android.util.TypedValue()
                if (act.theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)) setBackgroundResource(tv.resourceId)
                setOnClickListener {
                    if (content.visibility == View.VISIBLE) { content.visibility = View.GONE; chev.rotation = 0f; open.remove(group) }
                    else { content.visibility = View.VISIBLE; chev.rotation = 90f; open.add(group) }
                }
            }
            head.addView(View(act).apply { background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(levelColor(worst)) } },
                LinearLayout.LayoutParams(dp(10), dp(10)).apply { marginEnd = dp(12) })
            head.addView(TextView(act).apply {
                text = group
                setTextColor(act.getColor(R.color.mg_text))
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 16f)
                typeface = Typeface.DEFAULT_BOLD
            }, LinearLayout.LayoutParams(0, WRAP, 1f))
            head.addView(TextView(act).apply {
                text = secs.size.toString() + (if (secs.size == 1) " section" else " sections")
                setTextColor(act.getColor(R.color.mg_text_dim))
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12f)
                setPadding(0, 0, dp(8), 0)
            })
            head.addView(chev, LinearLayout.LayoutParams(dp(20), dp(20)))
            card.addView(head, LinearLayout.LayoutParams(MATCH, WRAP))
            for ((i, s) in secs.withIndex()) {
                if (i > 0) content.addView(View(act).apply { setBackgroundColor(act.getColor(R.color.mg_outline)) },
                    LinearLayout.LayoutParams(MATCH, dp(1)).apply { topMargin = dp(8) })
                content.addView(sectionView(s), LinearLayout.LayoutParams(MATCH, WRAP))
            }
            card.addView(content, LinearLayout.LayoutParams(MATCH, WRAP))
            return card
        }

        private fun sectionView(s: DiagReport.Section): View {
            val col = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(10), 0, 0) }
            val t = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            t.addView(View(act).apply { background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(levelColor(s.level)) } },
                LinearLayout.LayoutParams(dp(8), dp(8)).apply { marginEnd = dp(8) })
            t.addView(TextView(act).apply {
                text = s.title
                setTextColor(levelColor(s.level).takeIf { s.level == DiagReport.BAD || s.level == DiagReport.WARN } ?: act.getColor(R.color.mg_text))
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14f)
                typeface = Typeface.DEFAULT_BOLD
            }, LinearLayout.LayoutParams(0, WRAP, 1f))
            col.addView(t, LinearLayout.LayoutParams(MATCH, WRAP))
            val sb = StringBuilder()
            for (l in s.lines) {
                if (sb.isNotEmpty()) sb.append('\n')
                sb.append(if (l.startsWith("  ")) l.trimStart() else l)
            }
            col.addView(TextView(act).apply {
                text = sb.toString()
                setTextColor(act.getColor(R.color.mg_text_dim))
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12f)
                typeface = Typeface.MONOSPACE
                setTextIsSelectable(true)
                setLineSpacing(0f, 1.1f)
                setPadding(0, dp(4), 0, 0)
            }, LinearLayout.LayoutParams(MATCH, WRAP))
            return col
        }
    }
}
