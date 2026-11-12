package com.jabra.mgb

import android.app.Activity
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * D1: link inbox. A video link that comes from another app (Share > MGB) or that sits on the clipboard when the browser
 * comes to the front shows a small card "Download this link?". Open = the link opens in a NEW tab, where the normal
 * sniffer and the Files detected panel take over. Dismiss = gone (the same clipboard text is not offered again).
 * Switch: Settings > Downloads > Link inbox, default OFF. OFF = nothing is read (clipboard included) and Share is ignored.
 * The card lives in the web area, so it never covers the address bar. Key li_on in mgb_ui (in the Downloads Reset list).
 */
object LinkInbox {
    private const val TAG = "mgb_link_inbox"
    private val ONE_URL = Regex("^https?://[^\\s\"'<>]{4,2000}$")
    private val MEDIA_EXT = Regex("\\.(m3u8|mpd|mp4|m4v|webm|mkv|mov|mp3|m4a)(\\?|#|$)", RegexOption.IGNORE_CASE)
    private val VIDEO_HOST = Regex("(^|\\.)(youtube\\.com|youtu\\.be|vimeo\\.com|dailymotion\\.com|facebook\\.com|fb\\.watch|instagram\\.com|tiktok\\.com|twitter\\.com|x\\.com|twitch\\.tv|reddit\\.com)$", RegexOption.IGNORE_CASE)
    private var lastSeen = "" // last text that was offered (or dismissed): never offered twice in a row

    private fun sp(c: Context) = c.getSharedPreferences("mgb_ui", Context.MODE_PRIVATE)
    fun on(c: Context): Boolean = sp(c).getBoolean("li_on", true) // 0.0.405: default ON (was OFF). A person who switched it OFF keeps OFF.
    fun setOn(c: Context, v: Boolean) { sp(c).edit().putBoolean("li_on", v).apply() }

    /** Share: any single link counts (the person chose to send it). Clipboard: only links that look like video. */
    private fun linkOf(text: String, strict: Boolean): String? {
        val t = text.trim()
        val u = (if (ONE_URL.matches(t)) t else Regex("https?://[^\\s\"'<>]+").find(t)?.value.takeIf { !strict }) ?: return null
        if (!strict) return u
        // 0.0.405: Facebook / Pinterest / Reddit / Discord links: only real video / post / file links (not a profile or home page)
        if (LinkGrabCore.kindOf(u) != "other") return if (LinkGrabCore.worthCard(u)) u else null
        val host = Util.host(u)
        return if (MEDIA_EXT.containsMatchIn(u) || VIDEO_HOST.containsMatchIn(host)) u else null
    }

    /** Hook: onCreate and onNewIntent. Handles Share (ACTION_SEND, text/plain). */
    fun fromIntent(act: Activity, tabs: TabManager, i: Intent?) {
        if (i == null || i.action != Intent.ACTION_SEND || !on(act)) return
        val u = linkOf(i.getStringExtra(Intent.EXTRA_TEXT) ?: "", false) ?: return
        i.action = null // handled once, also after rotation
        show(act, tabs, u)
    }

    /** Hook: onWindowFocusChanged(true). Android only lets the focused app read the clipboard. */
    fun fromClipboard(act: Activity, tabs: TabManager) {
        if (!on(act)) return
        val t = try {
            (act.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(act)?.toString()
        } catch (e: Exception) { null } ?: return
        if (t == lastSeen) return
        val u = linkOf(t, true) ?: return
        lastSeen = t
        show(act, tabs, u)
    }

    private fun show(act: Activity, tabs: TabManager, url: String) {
        val area = act.findViewById<FrameLayout>(R.id.web_container)?.parent as? FrameLayout ?: return
        area.findViewWithTag<View>(TAG)?.let { area.removeView(it) }
        fun dp(v: Int) = Util.dp(act, v)
        val cText = act.getColor(R.color.mg_text)
        val dim = act.getColor(R.color.mg_text_dim)
        val primary = act.getColor(R.color.mg_primary)
        fun btn(label: String, color: Int, run: () -> Unit) = TextView(act).apply {
            this.text = label; textSize = 14f; setTextColor(color)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            gravity = Gravity.CENTER; minHeight = dp(44); minWidth = dp(56)
            setPadding(dp(9), 0, dp(9), 0)
            setOnClickListener { run() }
        }
        val card = LinearLayout(act).apply {
            tag = TAG
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(8), dp(4))
            elevation = dp(6).toFloat()
            background = GradientDrawable().apply {
                setColor(act.getColor(R.color.mg_surface)); cornerRadius = dp(20).toFloat()
                setStroke(dp(1), act.getColor(R.color.mg_outline))
            }
            contentDescription = "Download this link?"
        }
        val head = TextView(act).apply {
            this.text = LinkGrabCore.siteName(LinkGrabCore.kindOf(url)).let { if (it == "link") "Download this link?" else "Download from $it?" }; textSize = 15f; setTextColor(cText)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
        card.addView(head)
        card.addView(TextView(act).apply {
            this.text = url; textSize = 12f; setTextColor(dim)
            maxLines = 1; ellipsize = TextUtils.TruncateAt.MIDDLE
        })
        val row = LinearLayout(act).apply { gravity = Gravity.END }
        row.addView(btn("Dismiss", dim) { area.removeView(card) })
        row.addView(btn("Private", dim) { area.removeView(card); tabs.newTab(url, 0, true, true) }) // 0.0.331
        val sup = LinkGrab.supported(url)
        row.addView(btn("Open", if (sup) dim else primary) { area.removeView(card); tabs.newTab(url) })
        // 0.0.405: Download = LinkGrab finds the real file (Facebook / Pinterest / Reddit / Discord / direct link) and starts the app download.
        // Not possible or failed = the page opens in a new tab like "Open" (sniffer + Files detected panel take over).
        if (sup) {
            var busy = false
            row.addView(btn("Download", primary) {
                if (busy) return@btn
                busy = true
                head.text = "Getting the file..."
                LinkGrab.grab(act, url) { ok, msg ->
                    if (card.parent === area) area.removeView(card)
                    if (ok) Util.toast(act, "Downloading: $msg")
                    else { Util.toast(act, "$msg. Opening the page"); tabs.newTab(url) }
                }
            })
        }
        card.addView(row)
        val reduce = TabPrefs.reduce(act)
        if (!reduce) card.alpha = 0f
        area.addView(card, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { val m = dp(14); setMargins(m, 0, m, dp(20)); width = minOf(dp(560), area.width - 2 * m).let { if (it > 0) it else FrameLayout.LayoutParams.MATCH_PARENT } })
        if (!reduce) card.animate().alpha(1f).setDuration(180).start()
        card.postDelayed({ if (card.parent === area) area.removeView(card) }, 12000)
    }
}
