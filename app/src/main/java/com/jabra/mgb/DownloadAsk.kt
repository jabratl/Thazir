package com.jabra.mgb

import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.webkit.WebView
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import java.io.File

/**
 * 0.0.425: OPTIONAL "Ask before download" (Settings > Downloads, pref dl_ask in mgb_ui, default OFF = old behaviour, nothing changes).
 * When ON, a download that a browser tab starts (link / button / image long-press > Download) first shows a small sheet in the
 * Quetta / Chrome style: file type + site, an editable FILE NAME (rename), size, the folder it is saved in (tap = change) and
 * Cancel / Download. Nothing is started before Download is pressed; Cancel / Back / tap outside = nothing is downloaded.
 *
 * Hooks (the only ones): TabManager DownloadListener and TabManager.downloadImage call intercept(); it returns true when the sheet
 * took the request, false = the caller starts the download the old way. So any problem here (switch OFF, no Activity, blob: link,
 * exception) falls back to the old behaviour by itself. Not asked: blob: links (the page can revoke the blob while the sheet is open),
 * Link inbox, Grabber / engine jobs, chrome.downloads of extensions, Scheduled downloads, resumes (those are explicit or background).
 * The chosen name goes to DownloadsApi.startPlain(nameOverride): the item is flagged userName, so betterThanBin / NameSniff never
 * change a name the person typed. A name that was not changed is passed as null = exactly the old naming.
 * Several requests at once (a page that fires 2 downloads) wait in a small queue and are shown one after the other.
 */
object DownloadAsk {
    const val KEY = "dl_ask"
    private const val MAX_QUEUE = 8
    private val main = Handler(Looper.getMainLooper())
    private val EXT = Regex("^.+\\.[A-Za-z0-9]{1,8}$")

    private class Req(
        val url: String, val ua: String?, val disp: String?, val mime: String?, val size: Long,
        val referer: String?, val src: WebView?, val failMsg: String
    )

    private val queue = ArrayList<Req>()
    private var current: Sheet? = null

    private fun prefs(c: Context) = c.getSharedPreferences("mgb_ui", Context.MODE_PRIVATE)

    fun on(c: Context): Boolean = try { prefs(c).getBoolean(KEY, false) } catch (e: Exception) { false }

    fun setOn(c: Context, v: Boolean) { try { prefs(c).edit().putBoolean(KEY, v).apply() } catch (e: Exception) { } }

    fun label(c: Context): String = if (on(c)) "ON" else "OFF"

    /** http(s) and data: only. blob: is not asked (the page may revoke it while the sheet is open), see the class text. */
    private fun askable(url: String): Boolean =
        url.startsWith("http://", true) || url.startsWith("https://", true) || url.startsWith("data:", true)

    /**
     * Main thread. true = the request was taken (the sheet will start or drop it), false = the caller must start it the old way.
     * size = contentLength given by the WebView (<= 0 = unknown).
     */
    fun intercept(ctx: Context, url: String, ua: String?, disp: String?, mime: String?, size: Long,
                  referer: String?, src: WebView?, failMsg: String): Boolean {
        try {
            val act = ctx as? Activity ?: return false
            if (!on(act) || act.isFinishing || act.isDestroyed || !askable(url)) return false
            // a page that fires the same download twice (while its sheet is open or waiting) = one sheet; a new tap after Cancel / Download is a new request
            if ((current?.isShowing == true && current?.req?.url == url) || queue.any { it.url == url }) return true
            if (queue.size >= MAX_QUEUE) return false                 // too many waiting: this one starts the old way
            queue.add(Req(url, ua, disp, mime, size, referer, src, failMsg))
            pump(act)
            return true
        } catch (e: Throwable) {
            return false
        }
    }

    private fun pump(act: Activity) {
        if (current?.isShowing == true) return
        current = null
        while (queue.isNotEmpty()) {
            val r = queue.removeAt(0)
            if (act.isFinishing || act.isDestroyed) { start(act, r, null); continue }
            try {
                val s = Sheet(act, r)
                current = s
                s.show()
                return
            } catch (e: Throwable) {
                current = null
                start(act, r, null)   // the sheet could not be shown: never lose the download
            }
        }
    }

    private fun start(ctx: Context, r: Req, name: String?) {
        try {
            val item = DownloadsApi.startPlain(r.url, r.ua, r.disp, r.mime, r.referer, r.src, name)
            if (item == null) Util.toast(ctx, r.failMsg)
            else Util.toast(ctx, "Downloading " + item.rel.substringAfterLast('/'))
        } catch (e: Exception) {
            Util.toast(ctx, "Download failed to start")
        }
    }

    /**
     * What the person typed -> the name that is saved. Never empty, never a path: "/" and "\" become "_", illegal characters are cleaned
     * by DownloadsApi.cleanRel, an empty result = the original name. A name WITHOUT a real extension gets the extension of the original
     * back (report -> report.pdf), so the file still opens; a name the person gave with an extension is kept as typed.
     */
    internal fun fixName(typed: String, orig: String): String {
        var t = typed.replace('\\', '_').replace('/', '_').trim()
        t = DownloadsApi.cleanRel(t)
        if (t.isEmpty()) return orig
        val oe = orig.substringAfterLast('.', "")
        if (oe.isNotEmpty() && oe.length <= 8 && !EXT.matches(t)) t = t.trimEnd('.') + "." + oe
        return t
    }

    /** Best effort: is there already a file with this name in the folder the file will be saved in? Worker thread. Never throws. */
    private fun exists(c: Context, name: String): Boolean {
        return try {
            val tree = DownloadFolder.treeUri(c)
            if (tree != null) {
                val rootId = DocumentsContract.getTreeDocumentId(tree)
                val kids = DocumentsContract.buildChildDocumentsUriUsingTree(tree, rootId)
                var hit = false
                c.contentResolver.query(kids, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { q ->
                    while (q.moveToNext()) { if (q.getString(0) == name) { hit = true; break } }
                }
                hit
            } else if (Build.VERSION.SDK_INT >= 29) {
                c.contentResolver.query(MediaStore.Downloads.EXTERNAL_CONTENT_URI, arrayOf(MediaStore.MediaColumns._ID),
                    MediaStore.MediaColumns.DISPLAY_NAME + "=? AND " + MediaStore.MediaColumns.RELATIVE_PATH + "=?",
                    arrayOf(name, "Download/"), null)?.use { it.count > 0 } ?: false
            } else File(DownloadsApi.publicDir(), name).exists()
        } catch (e: Throwable) { false }
    }

    private class Sheet(private val act: Activity, val req: Req) : BottomSheetDialog(act) {
        private val orig = DownloadsApi.suggestName(req.url, req.disp, req.mime).ifEmpty { "download" }
        private lateinit var input: EditText
        private lateinit var note: TextView
        private lateinit var reset: TextView
        private lateinit var folder: TextView
        private lateinit var foot: TextView
        private var gen = 0
        private val dupRun = Runnable { checkDup() }

        private fun dp(v: Int) = Util.dp(act, v)
        private fun c(id: Int) = act.getColor(id)
        private fun round(color: Int, r: Int, stroke: Int = 0, sc: Int = 0) = GradientDrawable().apply {
            setColor(color); cornerRadius = dp(r).toFloat(); if (stroke > 0) setStroke(dp(stroke), sc)
        }
        private fun tint(id: Int, a: Float) = ColorUtils.blendARGB(c(R.color.mg_surface), c(id), a)
        private fun tv(t: String, sp: Float, color: Int, bold: Boolean = false) = TextView(act).apply {
            text = t; textSize = sp; setTextColor(color)
            if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        }
        private fun ripple(bg: GradientDrawable, rc: Int): android.graphics.drawable.Drawable =
            RippleDrawable(ColorStateList.valueOf(ColorUtils.setAlphaComponent(rc, 40)), bg, null)

        override fun onCreate(savedInstanceState: android.os.Bundle?) {
            super.onCreate(savedInstanceState)
            try { window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE) } catch (_: Exception) { }
            setOnShowListener { try { behavior.skipCollapsed = true; behavior.state = BottomSheetBehavior.STATE_EXPANDED } catch (_: Exception) { } }
            setOnDismissListener {
                main.removeCallbacks(dupRun)
                gen++
                current = null
                main.post { pump(act) }   // next waiting request (if any)
            }
            render()
            checkDup()
        }

        private fun typed(): String = fixName(input.text.toString(), orig)

        private fun kindWord(): String {
            val e = orig.substringAfterLast('.', "")
            return if (EXT.matches(orig) && e.length <= 5) e.uppercase() else ""
        }

        private fun badge(): View {
            val k = kindWord()
            val bg = round(tint(R.color.mg_primary, 0.14f), 14)
            return if (k.isNotEmpty() && k.length <= 4) tv(k, if (k.length > 3) 11f else 13f, c(R.color.mg_primary), true).apply {
                gravity = Gravity.CENTER; background = bg; maxLines = 1
            } else ImageView(act).apply {
                setImageResource(R.drawable.ic_download); setColorFilter(c(R.color.mg_primary)); scaleType = ImageView.ScaleType.CENTER
                background = bg
            }
        }

        private fun refreshFolder() { try { folder.text = DownloadFolder.label(act) } catch (_: Exception) { } }

        private fun onEdited() {
            reset.visibility = if (typed() != orig) View.VISIBLE else View.GONE
            main.removeCallbacks(dupRun)
            main.postDelayed(dupRun, 350L)
        }

        private fun checkDup() {
            val name = typed()
            val g = ++gen
            Thread {
                val hit = exists(act, name)
                main.post {
                    if (g == gen && isShowing) {
                        note.text = if (hit) "A file with this name is already there. A number is added to the new one." else ""
                        note.visibility = if (hit) View.VISIBLE else View.GONE
                    }
                }
            }.start()
        }

        private fun confirm() {
            val nm = typed()
            val r = req
            dismiss()
            start(act, r, if (nm == orig) null else nm)   // unchanged name = null = exactly the old naming
        }

        private fun render() {
            val col = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(18), dp(18), dp(16)) }

            // header: type badge, title, site
            val head = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            head.addView(badge(), LinearLayout.LayoutParams(dp(48), dp(48)).apply { marginEnd = dp(14) })
            val ht = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
            ht.addView(tv("Download file", 18f, c(R.color.mg_text), true))
            val site = if (req.url.startsWith("data:", true)) "From the page" else Util.host(req.url)
            ht.addView(tv(site, 12f, c(R.color.mg_text_dim)).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
            head.addView(ht, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            col.addView(head)

            // file name (rename)
            col.addView(tv("FILE NAME", 11f, c(R.color.mg_primary), true).apply { letterSpacing = 0.08f; setPadding(dp(4), dp(18), 0, dp(6)) })
            input = EditText(act).apply {
                setText(orig)
                textSize = 15f
                setTextColor(c(R.color.mg_text))
                setHintTextColor(c(R.color.mg_text_dim))
                hint = "File name"
                setRawInputType(android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS)
                setHorizontallyScrolling(false)
                maxLines = 3
                imeOptions = EditorInfo.IME_ACTION_DONE
                background = round(c(R.color.mg_surface_variant), 14, 1, c(R.color.mg_outline))
                setPadding(dp(14), dp(12), dp(14), dp(12))
                contentDescription = "File name. Edit to rename before download"
                setOnFocusChangeListener { _, has ->
                    if (has) post {   // like Chrome: the name without its extension is selected, so typing replaces only the name
                        try {
                            val t = text.toString()
                            val d = t.lastIndexOf('.')
                            setSelection(0, if (d > 0 && EXT.matches(t)) d else t.length)
                        } catch (_: Exception) { }
                    }
                }
                setOnEditorActionListener { _, id, _ -> if (id == EditorInfo.IME_ACTION_DONE) { confirm(); true } else false }
                addTextChangedListener(object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) { }
                    override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) { }
                    override fun afterTextChanged(s: Editable?) { onEdited() }
                })
            }
            col.addView(input, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

            // under the field: size / type line + "Original name" chip
            val info = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(4), dp(8), 0, 0) }
            val parts = ArrayList<String>()
            kindWord().let { if (it.isNotEmpty()) parts.add("$it file") }
            EngineApi.fmtSize(req.size).let { if (it.isNotEmpty()) parts.add(it) }
            if (parts.isEmpty()) parts.add("Size is shown when the download starts")
            info.addView(tv(parts.joinToString("  ·  "), 12f, c(R.color.mg_text_dim)), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            reset = tv("Original name", 12f, c(R.color.mg_primary), true).apply {
                gravity = Gravity.CENTER; setPadding(dp(12), 0, dp(12), 0); minimumHeight = dp(32)
                background = round(tint(R.color.mg_primary, 0.14f), 16)
                visibility = View.GONE
                isClickable = true
                setOnClickListener { input.setText(orig); input.setSelection(input.text.length) }
            }
            info.addView(reset, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(32)))
            col.addView(info)
            note = tv("", 12f, c(R.color.mg_primary)).apply { setPadding(dp(4), dp(6), 0, 0); visibility = View.GONE }
            col.addView(note)

            // save to
            val card = LinearLayout(act).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                background = round(c(R.color.mg_surface), 16, 1, c(R.color.mg_outline))
                minimumHeight = dp(60); setPadding(dp(12), dp(8), dp(10), dp(8))
                isClickable = true
                contentDescription = "Save to. Tap to choose another folder"
                setOnClickListener { DownloadFolder.showDialog(act) { refreshFolder(); checkDup() } }
            }
            card.addView(ImageView(act).apply {
                setImageResource(R.drawable.ic_folder); setColorFilter(c(R.color.mg_primary)); scaleType = ImageView.ScaleType.CENTER
                background = round(tint(R.color.mg_primary, 0.14f), 20)
            }, LinearLayout.LayoutParams(dp(36), dp(36)).apply { marginEnd = dp(12) })
            val mid = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
            mid.addView(tv("Save to", 11f, c(R.color.mg_text_dim)))
            folder = tv(DownloadFolder.label(act), 15f, c(R.color.mg_text), true).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
            mid.addView(folder)
            card.addView(mid, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            card.addView(tv("Change", 12f, c(R.color.mg_primary), true).apply {
                gravity = Gravity.CENTER; setPadding(dp(12), 0, dp(12), 0); minimumHeight = dp(34)
                background = round(tint(R.color.mg_primary, 0.14f), 17)
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(34)))
            col.addView(card, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(14) })

            // buttons
            val btns = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL }
            btns.addView(tv("Cancel", 15f, c(R.color.mg_text), true).apply {
                gravity = Gravity.CENTER; minimumHeight = dp(50)
                background = ripple(round(c(R.color.mg_surface), 25, 1, c(R.color.mg_outline)), c(R.color.mg_text))
                isClickable = true
                setOnClickListener { dismiss() }
            }, LinearLayout.LayoutParams(0, dp(50), 1f).apply { marginEnd = dp(8) })
            btns.addView(tv("Download", 15f, c(R.color.mg_on_primary), true).apply {
                gravity = Gravity.CENTER; minimumHeight = dp(50)
                background = ripple(round(c(R.color.mg_primary), 25), c(R.color.mg_on_primary))
                isClickable = true
                contentDescription = "Download"
                setOnClickListener { confirm() }
            }, LinearLayout.LayoutParams(0, dp(50), 1f).apply { marginStart = dp(8) })
            col.addView(btns, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(16) })

            // footer: optional switch lives here too
            foot = tv("Turn off \"Ask before download\"", 12f, c(R.color.mg_primary), true).apply {
                gravity = Gravity.CENTER; minimumHeight = dp(40); isClickable = true
                setOnClickListener {
                    setOn(act, false)
                    text = "Turned OFF. Settings > Downloads turns it on again."
                    setTextColor(c(R.color.mg_text_dim)); isClickable = false
                }
            }
            col.addView(foot, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })

            val cap = object : LinearLayout(act) { // 560 dp cap on wide screens
                override fun onMeasure(w: Int, h: Int) {
                    val size = MeasureSpec.getSize(w); val cp = dp(560)
                    super.onMeasure(if (size > cp) MeasureSpec.makeMeasureSpec(cp, MeasureSpec.EXACTLY) else w, h)
                }
            }.apply { orientation = LinearLayout.VERTICAL; addView(col, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)) }
            setContentView(ScrollView(act).apply {
                addView(cap, android.widget.FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL))
            })
        }
    }
}
