package com.jabra.mgb

import android.app.Activity
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.Spinner
import android.widget.TextView
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * 0.0.330: Brave-style filter for the History screen (BookmarksScreen, History mode).
 * Two chips under the search box: Time (any / 15 min / hour / today / 24 h / 7 d / 30 d / year / custom number + unit)
 * and Site (pick from visited sites or type one), plus Clear. State lives in this object (survives rotation).
 * BookmarksScreen only calls row() and apply(); stored history is never changed here.
 */
object HistoryFilter {
    private const val MIN = 60_000L
    private val UNITS = arrayOf("minutes", "hours", "days", "weeks", "months", "years")
    private val UNIT_SHORT = arrayOf("min", "hour", "day", "week", "month", "year")
    private val UNIT_MS = longArrayOf(MIN, 60 * MIN, 24 * 60 * MIN, 7 * 24 * 60 * MIN, 30 * 24 * 60 * MIN, 365 * 24 * 60 * MIN)

    private var today = false
    private var n = 0 // 0 = no time filter
    private var unit = 0
    var site = ""
        private set

    private val timeActive get() = today || n > 0
    val active get() = timeActive || site.isNotEmpty()

    fun reset() { today = false; n = 0; unit = 0; site = "" }

    private fun setSpan(t: Boolean, count: Int, u: Int) { today = t; n = count; unit = u }

    private fun cutoff(now: Long): Long {
        if (today) {
            val c = java.util.Calendar.getInstance()
            c.set(java.util.Calendar.HOUR_OF_DAY, 0); c.set(java.util.Calendar.MINUTE, 0)
            c.set(java.util.Calendar.SECOND, 0); c.set(java.util.Calendar.MILLISECOND, 0)
            return c.timeInMillis
        }
        return if (n > 0) now - n * UNIT_MS[unit] else 0L
    }

    private fun timeLabel(): String =
        if (today) "Today" else "Last $n ${UNIT_SHORT[unit]}${if (n > 1) "s" else ""}"

    private fun siteKey(raw: String): String =
        raw.trim().lowercase().removePrefix("https://").removePrefix("http://").removePrefix("www.").substringBefore('/')

    private fun hostMatch(host: String, s: String): Boolean {
        val h = host.lowercase()
        return if (s.contains('.')) h == s || h.endsWith(".$s") else h.contains(s)
    }

    /** Rows that pass the time + site filter (all rows when no filter is on). */
    fun apply(list: List<Bookmarks.Item>): List<Bookmarks.Item> {
        if (!active) return list
        val cut = cutoff(System.currentTimeMillis())
        val s = siteKey(site)
        return list.filter { (cut == 0L || it.time >= cut) && (s.isEmpty() || hostMatch(Util.host(it.url), s)) }
    }

    // ---------- UI ----------

    private fun chip(act: Activity, icon: Int, label: String, on: Boolean, tap: () -> Unit): View {
        fun d(v: Int) = Util.dp(act, v)
        val bg = GradientDrawable().apply {
            cornerRadius = d(20).toFloat()
            setColor(act.getColor(if (on) R.color.mg_surface else R.color.mg_surface_variant))
            setStroke(d(1), act.getColor(if (on) R.color.mg_primary else R.color.mg_outline))
        }
        val tint = act.getColor(if (on) R.color.mg_primary else R.color.mg_text)
        return LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = InsetDrawable(bg, 0, d(4), 0, d(4)) // 44dp touch height, 36dp drawn
            setPadding(d(12), 0, d(14), 0)
            isClickable = true
            isFocusable = true
            addView(ImageView(act).apply { setImageResource(icon); setColorFilter(tint) }, LinearLayout.LayoutParams(d(16), d(16)))
            addView(TextView(act).apply {
                text = label
                textSize = 13f
                maxLines = 1
                maxWidth = d(200)
                ellipsize = TextUtils.TruncateAt.END
                setTextColor(tint)
                setPadding(d(6), 0, 0, 0)
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            setOnClickListener { tap() }
        }
    }

    /** The chip row. changed() is called after any change (the screen rebuilds itself). */
    fun row(act: Activity, changed: () -> Unit): View {
        fun d(v: Int) = Util.dp(act, v)
        val inner = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(d(16), 0, d(8), 0)
        }
        fun lp() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, d(44)).apply { marginEnd = d(8) }
        inner.addView(chip(act, R.drawable.ic_clock, if (timeActive) timeLabel() else "Time", timeActive) { pickTime(act, changed) }, lp())
        inner.addView(chip(act, R.drawable.ic_desktop, if (site.isNotEmpty()) site else "Site", site.isNotEmpty()) { pickSite(act, changed) }, lp())
        if (active) inner.addView(chip(act, R.drawable.ic_close, "Clear", false) { reset(); changed() }, lp())
        return HorizontalScrollView(act).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(inner, ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
    }

    private fun pickTime(act: Activity, changed: () -> Unit) {
        val names = arrayOf("Any time", "Last 15 minutes", "Last hour", "Today", "Last 24 hours", "Last 7 days", "Last 30 days", "Last year", "Custom\u2026")
        val cur = when {
            !timeActive -> 0
            today -> 3
            n == 15 && unit == 0 -> 1
            n == 1 && unit == 1 -> 2
            n == 24 && unit == 1 -> 4
            n == 7 && unit == 2 -> 5
            n == 30 && unit == 2 -> 6
            n == 1 && unit == 5 -> 7
            else -> 8
        }
        MaterialAlertDialogBuilder(act).setTitle("Time").setSingleChoiceItems(names, cur) { dlg, w ->
            dlg.dismiss()
            when (w) {
                0 -> setSpan(false, 0, 0)
                1 -> setSpan(false, 15, 0)
                2 -> setSpan(false, 1, 1)
                3 -> setSpan(true, 0, 0)
                4 -> setSpan(false, 24, 1)
                5 -> setSpan(false, 7, 2)
                6 -> setSpan(false, 30, 2)
                7 -> setSpan(false, 1, 5)
            }
            if (w == 8) pickCustom(act, changed) else changed()
        }.setNegativeButton("Cancel", null).show()
    }

    private fun pickCustom(act: Activity, changed: () -> Unit) {
        fun d(v: Int) = Util.dp(act, v)
        val box = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(d(24), d(8), d(24), 0)
        }
        val had = n > 0 && !today
        box.addView(TextView(act).apply {
            text = "Last"
            textSize = 15f
            setTextColor(act.getColor(R.color.mg_text_dim))
            setPadding(0, 0, d(12), 0)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val num = EditText(act).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setSingleLine()
            hint = "Number"
            setText(if (had) n.toString() else "2")
            setSelection(text.length)
            filters = arrayOf(InputFilter.LengthFilter(4))
        }
        box.addView(num, LinearLayout.LayoutParams(d(96), ViewGroup.LayoutParams.WRAP_CONTENT))
        val sp = Spinner(act).apply {
            adapter = ArrayAdapter(act, android.R.layout.simple_spinner_dropdown_item, UNITS)
            setSelection(if (had) unit else 1)
        }
        box.addView(sp, LinearLayout.LayoutParams(0, d(48), 1f).apply { marginStart = d(8) })
        MaterialAlertDialogBuilder(act).setTitle("Custom time").setView(box)
            .setPositiveButton("Apply") { _, _ ->
                val v = num.text.toString().toIntOrNull()
                if (v == null || v < 1) Util.toast(act, "Enter a number")
                else { setSpan(false, v, sp.selectedItemPosition); changed() }
            }
            .setNegativeButton("Cancel", null).show()
    }

    private fun pickSite(act: Activity, changed: () -> Unit) {
        fun d(v: Int) = Util.dp(act, v)
        val counts = LinkedHashMap<String, Int>()
        for (h in Bookmarks.history()) {
            val k = Util.host(h.url).lowercase()
            if (k.isNotEmpty()) counts[k] = (counts[k] ?: 0) + 1
        }
        val hosts = counts.entries.sortedByDescending { it.value }.map { it.key }
        val labels = ArrayList<String>()
        val keys = ArrayList<String>()
        val ad = object : ArrayAdapter<String>(act, android.R.layout.simple_list_item_1, labels) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val v = super.getView(position, convertView, parent)
                v.minimumHeight = d(52)
                return v
            }
        }
        fun refill(q: String) {
            val s = siteKey(q)
            labels.clear(); keys.clear()
            for (h in hosts) if (s.isEmpty() || h.contains(s)) { keys.add(h); labels.add("$h  \u00B7  ${counts[h]}") }
            ad.notifyDataSetChanged()
        }
        refill("")
        val box = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(d(24), d(8), d(24), 0)
        }
        val et = EditText(act).apply {
            hint = "Type a site, e.g. youtube.com"
            setSingleLine()
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setText(site)
            setSelection(text.length)
            addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(e: Editable?) { refill(e?.toString().orEmpty()) }
                override fun beforeTextChanged(t: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(t: CharSequence?, a: Int, b: Int, c: Int) {}
            })
        }
        box.addView(et, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val lv = ListView(act).apply { adapter = ad }
        box.addView(lv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, d(240)))
        val dlg = MaterialAlertDialogBuilder(act).setTitle("Site").setView(box)
            .setPositiveButton("Apply") { _, _ -> site = siteKey(et.text.toString()); changed() }
            .setNeutralButton("Any site") { _, _ -> site = ""; changed() }
            .setNegativeButton("Cancel", null).create()
        lv.setOnItemClickListener { _, _, i, _ -> if (i in keys.indices) { site = keys[i]; dlg.dismiss(); changed() } }
        dlg.show()
    }
}
