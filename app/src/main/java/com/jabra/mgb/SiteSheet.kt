package com.jabra.mgb

import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import androidx.fragment.app.FragmentActivity
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog

/**
 * 0.0.287: tap the lock in the address bar = small "this site" sheet (Brave style): Camera / Microphone / Location /
 * Notifications for THIS site, tap a chip to cycle Ask first > Allowed > Blocked. Reads / writes SitePerms (nothing new stored).
 * "All site permissions" opens the existing SitePermsUi. The page reloads once on close when something changed.
 * Hook: BarGestures touch listener calls touch() first (only the url_bar, only when not typing, only on the lock).
 */
object SiteSheet {
    private var downOnLock = false
    private val CATS = listOf(
        Triple("camera", "Camera", R.drawable.ic_perm_camera),
        Triple("microphone", "Microphone", R.drawable.ic_perm_mic),
        Triple("location", "Location", R.drawable.ic_perm_location),
        Triple("notifications", "Notifications", R.drawable.ic_perm_bell)
    )

    /** true = the touch belongs to the lock (consume it). Opens the sheet on a finished tap. */
    fun touch(a: android.app.Activity, tm: TabManager, v: View, e: MotionEvent): Boolean {
        if (v.id != R.id.url_bar || v !is EditText) return false
        fun hit(): Boolean {
            val d = v.compoundDrawablesRelative[0] ?: return false
            if (v.hasFocus()) return false
            val rtl = v.layoutDirection == View.LAYOUT_DIRECTION_RTL
            val w = d.bounds.width() + v.compoundDrawablePadding + Util.dp(a, 14)
            return if (rtl) e.x >= v.width - v.paddingStart - w else e.x <= v.paddingStart + w
        }
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downOnLock = hit(); return downOnLock }
            MotionEvent.ACTION_MOVE -> return downOnLock
            MotionEvent.ACTION_CANCEL -> { val f = downOnLock; downOnLock = false; return f }
            MotionEvent.ACTION_UP -> {
                if (!downOnLock) return false
                downOnLock = false
                val t = tm.active
                if (hit() && t != null && !t.home && (t.url.startsWith("https://") || t.url.startsWith("http://"))) {
                    (a as? FragmentActivity)?.let { show(it, t) }
                }
                return true
            }
        }
        return downOnLock
    }

    private fun cur(a: FragmentActivity, host: String, key: String): Boolean? =
        SitePerms.get(a, host, key) ?: (if (key == "camera" || key == "microphone") SitePerms.get(a, host, "camera and microphone") else null)

    private fun put(a: FragmentActivity, host: String, key: String, v: Boolean?) {
        if (key == "camera" || key == "microphone") { // a saved combined answer is split first, so the other half is kept
            SitePerms.get(a, host, "camera and microphone")?.let { both ->
                SitePerms.set(a, host, "camera and microphone", null)
                SitePerms.set(a, host, if (key == "camera") "microphone" else "camera", both)
            }
        }
        SitePerms.set(a, host, key, v)
    }

    fun show(a: FragmentActivity, t: TabManager.Tab) {
        try { Sheet(a, t).show() } catch (e: Exception) { android.util.Log.e("MGB.sitesheet", "sheet", e) }
    }

    private class Sheet(private val act: FragmentActivity, private val tab: TabManager.Tab) : BottomSheetDialog(act) {
        private val host = Util.host(tab.url)
        private var changed = false
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

        override fun onCreate(savedInstanceState: android.os.Bundle?) {
            super.onCreate(savedInstanceState)
            setOnShowListener { try { behavior.skipCollapsed = true; behavior.state = BottomSheetBehavior.STATE_EXPANDED } catch (_: Exception) { } }
            setOnDismissListener { if (changed) { try { tab.web.reload() } catch (_: Exception) { } } }
            render()
        }

        private fun render() {
            val col = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(16), dp(14), dp(18)) }
            col.addView(tv(host, 20f, c(R.color.mg_text), true).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
            val sec = tab.url.startsWith("https://")
            col.addView(tv(if (sec) "Connection is secure" else "Connection is not secure", 12f, c(if (sec) R.color.mg_success else R.color.mg_error)).apply { setPadding(0, dp(2), 0, dp(12)) })
            col.addView(tv("PERMISSIONS FOR THIS SITE", 11f, c(R.color.mg_primary), true).apply { letterSpacing = 0.08f; setPadding(dp(4), 0, 0, dp(6)) })
            for ((key, label, ic) in CATS) {
                val v = cur(act, host, key)
                val def = SitePerms.blockedByDefault(act, key)
                val blocked = v == false || (v == null && def)
                val word = when { v == true -> "Allowed"; v == false -> "Blocked"; def -> "Blocked" else -> "Ask first" }
                val tone = if (v == true) R.color.mg_success else if (blocked) R.color.mg_error else R.color.mg_primary
                val line = LinearLayout(act).apply {
                    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                    background = round(c(R.color.mg_surface), 16, 1, c(R.color.mg_outline))
                    minimumHeight = dp(56); setPadding(dp(12), dp(6), dp(8), dp(6))
                }
                line.addView(ImageView(act).apply {
                    setImageResource(ic); setColorFilter(c(R.color.mg_primary)); scaleType = ImageView.ScaleType.CENTER
                    background = round(tint(R.color.mg_primary, 0.14f), 20)
                }, LinearLayout.LayoutParams(dp(36), dp(36)).apply { marginEnd = dp(12) })
                val mid = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
                mid.addView(tv(label, 15f, c(R.color.mg_text), true))
                if (v == null) mid.addView(tv(if (def) "Default for all sites" else "Not saved for this site", 11f, c(R.color.mg_text_dim)))
                line.addView(mid, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                line.addView(tv(word, 12f, c(tone), true).apply {
                    gravity = Gravity.CENTER; background = round(tint(tone, 0.16f), 16)
                    setPadding(dp(12), 0, dp(12), 0); minimumHeight = dp(36)
                    contentDescription = "$label $word. Tap to change"
                    setOnClickListener {
                        put(act, host, key, when (v) { null -> true; true -> false; false -> null })
                        changed = true; render()
                    }
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(36)))
                col.addView(line, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
            }
            col.addView(tv("Tap a chip to switch: Ask first, Allowed, Blocked. The page reloads once when you close this sheet.", 11f, c(R.color.mg_text_dim)).apply { setPadding(dp(4), dp(10), dp(4), dp(10)) })
            col.addView(tv("All site permissions", 14f, c(R.color.mg_primary), true).apply {
                gravity = Gravity.CENTER; minimumHeight = dp(48)
                background = round(tint(R.color.mg_primary, 0.12f), 16)
                isClickable = true
                setOnClickListener { dismiss(); SitePermsUi.show(act) { } }
            })
            try { SiteData.button(act, tab) { dismiss() }?.let { col.addView(it, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) }) } } catch (_: Exception) { } // 0.0.383: Delete data for this site
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
