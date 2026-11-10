package com.jabra.mgb

import android.app.Activity
import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 0.0.377: address bar suggestions. While you type in the address bar, up to 5 matching bookmarks / history pages
 * show in a small card next to the bar (above it when the bar is at the bottom, below it when the bar is on top).
 * Tap = open that page. Matching: host starts with the text > address starts with > host contains > address contains
 * > title contains > every word found (bookmarks first, then newest history). Same page twice = one row.
 * Not shown: private tab, empty text, text equal to the current address, nothing matches, switch OFF.
 * Switch: pref url_suggest in SharedPreferences "mgb_ui", default ON (Settings > Browser > Address suggestions).
 * Hook: MainActivity calls attach() once and hide() when the field loses focus. Any error = the card just disappears.
 * Colours only from R.color.mg_*. Nothing is stored, nothing leaves the phone.
 */
object UrlSuggest {
    private const val MAX = 5
    private const val K_ON = "url_suggest"

    private class Hit(val title: String, val url: String, val bm: Boolean, val score: Int)

    private var card: LinearLayout? = null
    private var host: FrameLayout? = null
    private var quiet = false

    fun enabled(c: Context): Boolean = try { c.getSharedPreferences("mgb_ui", Context.MODE_PRIVATE).getBoolean(K_ON, true) } catch (e: Exception) { true }
    fun setEnabled(c: Context, on: Boolean) { try { c.getSharedPreferences("mgb_ui", Context.MODE_PRIVATE).edit().putBoolean(K_ON, on).apply() } catch (e: Exception) { } }

    /** priv() = active tab is private (no suggestions); cur() = address of the active tab ("" on the home page); onPick(url) = open it. */
    fun attach(act: Activity, bar: EditText, priv: () -> Boolean, cur: () -> String, onPick: (String) -> Unit) {
        bar.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) { }
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) { }
            override fun afterTextChanged(s: Editable?) { refresh(act, bar, priv, cur, onPick) }
        })
    }

    fun hide() {
        try { card?.let { host?.removeView(it) } } catch (e: Exception) { }
        card = null
        host = null
    }

    private fun refresh(act: Activity, bar: EditText, priv: () -> Boolean, cur: () -> String, onPick: (String) -> Unit) {
        try {
            if (quiet) return
            if (!enabled(act) || !bar.hasFocus() || priv()) { hide(); return }
            val q = bar.text.toString().trim()
            if (q.isEmpty() || q == cur().trim()) { hide(); return }
            val hits = find(q)
            if (hits.isEmpty()) { hide(); return }
            show(act, hits) { u ->
                hide()
                quiet = true
                try { onPick(u) } finally { quiet = false }
            }
        } catch (e: Exception) { hide() }
    }

    private fun key(u: String): String {
        var s = u.trim().lowercase()
        for (p in arrayOf("https://", "http://")) if (s.startsWith(p)) { s = s.substring(p.length); break }
        if (s.startsWith("www.")) s = s.substring(4)
        return s.trimEnd('/')
    }

    private fun shown(u: String): String = u.trim().removePrefix("https://").removePrefix("http://").trimEnd('/')

    private fun find(raw: String): List<Hit> {
        val q = key(raw)
        if (q.isEmpty()) return emptyList()
        val words = raw.trim().lowercase().split(' ').filter { it.isNotEmpty() }
        val seen = HashSet<String>()
        val out = ArrayList<Hit>()
        fun add(title: String, url: String, bm: Boolean) {
            if (!(url.startsWith("http://") || url.startsWith("https://"))) return
            val k = key(url)
            if (k.isEmpty()) return
            val t = title.lowercase()
            val h = k.substringBefore('/')
            val score = when {
                h.startsWith(q) -> 0
                k.startsWith(q) -> 1
                h.contains(q) -> 2
                k.contains(q) -> 3
                t.contains(q) -> 4
                words.size > 1 && words.all { k.contains(it) || t.contains(it) } -> 5
                else -> -1
            }
            if (score < 0) return
            if (!seen.add(k)) return
            out.add(Hit(title, url, bm, score))
        }
        for (b in Bookmarks.bookmarks()) add(b.title, b.url, true)
        for (h in Bookmarks.history()) add(h.title, h.url, false)
        return out.sortedWith(compareBy<Hit>({ it.score }, { if (it.bm) 0 else 1 })).take(MAX)
    }

    private fun show(act: Activity, hits: List<Hit>, pick: (String) -> Unit) {
        val root = act.findViewById<FrameLayout>(android.R.id.content) ?: return
        val bar = act.findViewById<View>(R.id.bottom_bar) ?: return
        fun dp(v: Int) = Util.dp(act, v)
        if (root.height <= 0) return
        val rl = IntArray(2); val bl = IntArray(2)
        root.getLocationInWindow(rl); bar.getLocationInWindow(bl)
        val top = bl[1] - rl[1]
        val below = top <= root.height / 2 // bar in the upper half (bar position Top): card goes under it
        val edge = if (below) top + bar.height + dp(6) else root.height - top + dp(6)
        val rows = minOf(hits.size, (root.height - edge - dp(8)) / dp(48))
        if (rows < 1) { hide(); return }

        hide()
        val dim = act.getColor(R.color.mg_text_dim)
        val c = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(act.getColor(R.color.mg_surface))
                setStroke(dp(1), act.getColor(R.color.mg_outline))
                cornerRadius = dp(16).toFloat()
            }
            elevation = dp(8).toFloat()
            setPadding(0, dp(4), 0, dp(4))
        }
        for (i in 0 until rows) {
            val h = hits[i]
            val row = LinearLayout(act).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(14), 0, dp(14), 0)
                val tv = TypedValue()
                if (act.theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)) setBackgroundResource(tv.resourceId)
                contentDescription = "Open " + (if (h.title.isBlank()) shown(h.url) else h.title)
                setOnClickListener { pick(h.url) }
            }
            row.addView(ImageView(act).apply {
                setImageResource(if (h.bm) R.drawable.ic_bookmark else R.drawable.ic_history)
                setColorFilter(dim)
            }, LinearLayout.LayoutParams(dp(18), dp(18)).apply { marginEnd = dp(12) })
            val col = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
            col.addView(TextView(act).apply {
                text = if (h.title.isBlank()) shown(h.url) else h.title
                setTextColor(act.getColor(R.color.mg_text))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            })
            col.addView(TextView(act).apply {
                text = shown(h.url)
                setTextColor(dim)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                typeface = Typeface.DEFAULT
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            })
            row.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            c.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)))
        }
        val w = minOf(root.width - dp(20), dp(640))
        val lp = FrameLayout.LayoutParams(w, ViewGroup.LayoutParams.WRAP_CONTENT, (if (below) Gravity.TOP else Gravity.BOTTOM) or Gravity.CENTER_HORIZONTAL)
        if (below) lp.topMargin = edge else lp.bottomMargin = edge
        root.addView(c, lp)
        card = c
        host = root
    }
}
