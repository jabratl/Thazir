package com.jabra.mgb

import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.widget.SwitchCompat
import androidx.core.widget.NestedScrollView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.json.JSONObject

/**
 * 0.0.360 (S4): + row "Also for big grabber files" (engine key partsN).
 * 0.0.359 (S3): UI of "Download in parts" (native Downloader, DlParts.kt).
 *  - open(act): bottom sheet with the switch (pref dp_on), number of parts (dp_n 2/4/6/8/16) and "only files bigger than" (dp_min_mb 2/8/32).
 *  - summary(ctx): short text for the Settings row.
 *  - bar(ctx, fractions) / infoText(row): segmented bar + "4 parts · 3.1 MB/s · 0:42 left" for a Downloads row.
 * Prefs live in SharedPreferences "mgb_ui" (the same keys DlParts reads); the sheet keeps no state of its own, so rotation is safe.
 * Colours only from R.color.mg_*, sheet chrome copied from DlEngine.kt (BottomSheetDialog + NestedScrollView).
 */
object DlPartsUi {
    private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
    private val PARTS = intArrayOf(2, 4, 6, 8, 16)
    private val MINS = intArrayOf(2, 8, 32)

    private fun sp(c: Context) = c.getSharedPreferences("mgb_ui", Context.MODE_PRIVATE)
    private fun on(c: Context): Boolean = try { sp(c).getBoolean("dp_on", false) } catch (e: Exception) { false }
    private fun parts(c: Context): Int = try { sp(c).getInt("dp_n", 4).let { if (it in PARTS) it else 4 } } catch (e: Exception) { 4 }
    private fun minMb(c: Context): Int = try { sp(c).getInt("dp_min_mb", 8) } catch (e: Exception) { 8 }

    /** Value text of the Settings row. */
    fun summary(c: Context): String = if (on(c)) "ON · ${parts(c)} parts" else "OFF"

    private fun mbText(v: Int): String = "$v MB"

    // 0.0.360 (S4): engine key partsN (chrome.storage.sync, read by assets/ext/bigfile.js). 1 = off, 2..16 = pieces at the same time.
    private fun gParts(): Int = try { ChromeBridge.sync.get("partsN").optInt("partsN", 1) } catch (e: Exception) { 1 }
    private fun gPut(v: Int) { try { ChromeBridge.storageSet("sync", JSONObject().put("partsN", v)) } catch (e: Exception) { /* engine not ready */ } }

    // 0.0.369 (N1): engine key directToNative (chrome.storage.sync, read by assets/ext/downloader.js + nativefile.js). Default false = old engine download.
    private fun gDirect(): Boolean = try { ChromeBridge.sync.get("directToNative").optBoolean("directToNative", false) } catch (e: Exception) { false }
    private fun gDirectPut(v: Boolean) { try { ChromeBridge.storageSet("sync", JSONObject().put("directToNative", v)) } catch (e: Exception) { /* engine not ready */ } }

    fun open(act: Activity, onClose: (() -> Unit)? = null) {
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
            text = "Download in parts"
            setTextColor(cText)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(4), 0, 0, dp(2))
        })
        root.addView(TextView(act).apply {
            text = "One big file is fetched in several pieces at the same time. Applies to the next download."
            setTextColor(cDim)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(dp(4), 0, 0, dp(6))
        })
        val box = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        root.addView(NestedScrollView(act).apply { addView(box) }, LinearLayout.LayoutParams(MATCH, 0, 1f))
        root.addView(TextView(act).apply {
            text = "DONE"
            gravity = Gravity.CENTER
            setTextColor(act.getColor(R.color.mg_on_primary))
            typeface = Typeface.DEFAULT_BOLD
            background = GradientDrawable().apply { setColor(cPrimary); cornerRadius = dp(24).toFloat() }
            setOnClickListener { d.dismiss() }
        }, LinearLayout.LayoutParams(MATCH, dp(48)).apply { topMargin = dp(8) })

        fun card(): LinearLayout = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply { setColor(cCard); cornerRadius = dp(20).toFloat(); setStroke(dp(1), cLine) }
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }

        fun head(t: String) = TextView(act).apply {
            text = t.uppercase()
            setTextColor(cPrimary)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.08f
            setPadding(dp(8), dp(16), 0, dp(6))
        }

        fun rowView(title: String, value: String?, hint: String?): LinearLayout {
            val r = LinearLayout(act).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(52)
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
                setTextColor(cPrimary)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.END
                maxLines = 2
            }, LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = dp(8) })
            return r
        }

        lateinit var fill: () -> Unit

        fun choice(parent: LinearLayout, title: String, hint: String?, key: String, cur: Int, values: IntArray, label: (Int) -> String) {
            parent.addView(rowView(title, label(cur), hint).apply {
                setOnClickListener {
                    MaterialAlertDialogBuilder(act).setTitle(title)
                        .setSingleChoiceItems(values.map { label(it) }.toTypedArray(), values.indexOf(cur)) { dd, i ->
                            try { sp(act).edit().putInt(key, values[i]).apply() } catch (e: Exception) { }
                            if (key == "dp_n" && gParts() > 1) gPut(values[i]) // 0.0.360: grabber files follow the number of parts
                            dd.dismiss(); fill()
                        }
                        .setNegativeButton("Cancel", null).show()
                }
            }, LinearLayout.LayoutParams(MATCH, WRAP))
        }

        fill = {
            box.removeAllViews()
            box.addView(head("Switch"))
            val c1 = card()
            val r = rowView("Download in parts", null, "Faster on servers that allow it. Off = the normal single connection")
            val s = SwitchCompat(act).apply { isChecked = on(act); ThazirSwitch.tint(this) }
            r.addView(s, LinearLayout.LayoutParams(WRAP, WRAP))
            val flip = {
                try { sp(act).edit().putBoolean("dp_on", !on(act)).apply() } catch (e: Exception) { }
                fill()
            }
            r.setOnClickListener { flip() }
            s.setOnClickListener { flip() }
            c1.addView(r, LinearLayout.LayoutParams(MATCH, WRAP))
            box.addView(c1, LinearLayout.LayoutParams(MATCH, WRAP))

            box.addView(head("Options"))
            val c2 = card()
            choice(c2, "Parts", "More parts = faster, but some sites refuse and the app falls back to one connection", "dp_n", parts(act), PARTS) { "$it" }
            val cm = minMb(act)
            val mins = if (MINS.contains(cm)) MINS else (MINS.toList() + cm).sorted().toIntArray()
            choice(c2, "Only files bigger than", "Smaller files use the normal single connection", "dp_min_mb", cm, mins) { mbText(it) }
            box.addView(c2, LinearLayout.LayoutParams(MATCH, WRAP))

            box.addView(head("Grabber"))
            val c3 = card()
            val r3 = rowView("Also for big grabber files", null, "Bilibili and similar sites: video / audio pieces are fetched together, same number of parts. Off = one piece after the other")
            val s3 = SwitchCompat(act).apply { isChecked = gParts() > 1; ThazirSwitch.tint(this) }
            r3.addView(s3, LinearLayout.LayoutParams(WRAP, WRAP))
            val flip3 = {
                gPut(if (gParts() > 1) 1 else parts(act))
                fill()
            }
            r3.setOnClickListener { flip3() }
            s3.setOnClickListener { flip3() }
            c3.addView(r3, LinearLayout.LayoutParams(MATCH, WRAP))
            box.addView(c3, LinearLayout.LayoutParams(MATCH, WRAP))

            // 0.0.369 (N1): single plain files from the grabber go to the native downloader (disk, resume, parts)
            val c4 = card()
            val r4 = rowView("Single grabber files: app downloader", null, "A plain file (mp4, zip...) is fetched by the app itself: saved on disk, can resume, uses parts. If it cannot start, the normal way runs. Off = old way")
            val s4 = SwitchCompat(act).apply { isChecked = gDirect(); ThazirSwitch.tint(this) }
            r4.addView(s4, LinearLayout.LayoutParams(WRAP, WRAP))
            val flip4 = {
                gDirectPut(!gDirect())
                fill()
            }
            r4.setOnClickListener { flip4() }
            s4.setOnClickListener { flip4() }
            c4.addView(r4, LinearLayout.LayoutParams(MATCH, WRAP))
            box.addView(c4, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(8) })

            // 0.0.370 (N2): control of the native downloader
            box.addView(head("Control"))
            val c5 = card()
            val lim = try { sp(act).getInt("dl_limit_kbps", 0) } catch (e: Exception) { 0 }
            choice(c5, "Speed limit", "All native downloads together. Off = full speed", "dl_limit_kbps", lim, DlLimit.LIMITS) {
                if (it == 0) "Off" else if (it >= 1024) "${it / 1024} MB/s" else "$it KB/s"
            }
            val mj = try { sp(act).getInt("dl_max_jobs", 3).let { if (it in DlLimit.JOBS) it else 3 } } catch (e: Exception) { 3 }
            choice(c5, "Downloads at the same time", "More downloads wait in line. Applies to downloads that start next", "dl_max_jobs", mj, DlLimit.JOBS) { "$it" }
            box.addView(c5, LinearLayout.LayoutParams(MATCH, WRAP))

            // 0.0.371 (N3): auto-resume
            val c6 = card()
            val r6 = rowView("Resume by itself", null, "After the app was closed or the network came back, cut downloads continue alone (download rules are respected). Off = press Resume yourself")
            val s6 = SwitchCompat(act).apply { isChecked = DlAutoResume.enabled(act); ThazirSwitch.tint(this) }
            r6.addView(s6, LinearLayout.LayoutParams(WRAP, WRAP))
            val flip6 = {
                try { sp(act).edit().putBoolean("dl_auto_resume", !DlAutoResume.enabled(act)).apply() } catch (e: Exception) { }
                fill()
            }
            r6.setOnClickListener { flip6() }
            s6.setOnClickListener { flip6() }
            c6.addView(r6, LinearLayout.LayoutParams(MATCH, WRAP))
            box.addView(c6, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(8) })
        }
        fill()
        d.setContentView(root)
        d.setOnDismissListener { try { onClose?.invoke() } catch (e: Exception) { } }
        d.behavior.skipCollapsed = true
        d.show()
        d.behavior.state = BottomSheetBehavior.STATE_EXPANDED
    }

    // ---------- Downloads screen row ----------

    /** Segmented bar: one small segment per part, track mg_outline, fill mg_primary, no animation. */
    class PartsBar(ctx: Context, private val fr: List<Double>) : View(ctx) {
        private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ctx.getColor(R.color.mg_outline) }
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ctx.getColor(R.color.mg_primary) }
        private val rect = RectF()
        private val gap = Util.dp(ctx, 2).toFloat()

        override fun onDraw(c: Canvas) {
            val n = fr.size
            if (n < 1) return
            val h = height.toFloat()
            val rad = h / 2f
            val w = (width - gap * (n - 1)) / n
            if (w <= 0f) return
            for (i in 0 until n) {
                val x = i * (w + gap)
                rect.set(x, 0f, x + w, h)
                c.drawRoundRect(rect, rad, rad, track)
                val f = fr[i].coerceIn(0.0, 1.0).toFloat()
                if (f > 0f) {
                    rect.set(x, 0f, x + w * f, h)
                    c.drawRoundRect(rect, rad, rad, fill)
                }
            }
        }
    }

    fun bar(ctx: Context, fr: List<Double>): View = PartsBar(ctx, fr).apply {
        contentDescription = "${fr.size} parts, " + (fr.sum() / fr.size.coerceAtLeast(1) * 100).toInt() + " percent"
    }

    private fun clock(sec: Long): String {
        val s = sec.coerceAtLeast(0L)
        return if (s >= 3600L) String.format("%d:%02d:%02d", s / 3600, (s % 3600) / 60, s % 60) else String.format("%d:%02d", s / 60, s % 60)
    }

    /** "4 parts · 3.1 MB/s · 0:42 left" from the optional plainJobs fields parts / speed / eta. */
    fun infoText(j: JSONObject): String {
        val n = j.optJSONArray("parts")?.length() ?: 0
        val out = ArrayList<String>()
        out.add(if (n == 1) "1 part" else "$n parts")
        val sp = j.optLong("speed", 0L)
        if (sp > 0L) out.add(EngineApi.fmtSize(sp) + "/s")
        if (j.has("eta")) out.add(clock(j.optLong("eta")) + " left")
        return out.joinToString(" · ")
    }
}
