package com.jabra.mgb

import android.app.Activity
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog

/**
 * 0.0.286: Social chip in the address bar. On a social site (Social mode ON + profile found) a small letter badge shows at the
 * end of the address bar; tap = one sheet with this site's quick switches (app mode, blur, dark page) and the global ones
 * (link cleaner, focus mode), plus "All social settings". Changes apply when the sheet closes (the page reloads once).
 * No chip: other pages, Home, while typing, Social mode OFF. Nothing here changes any default.
 */
object SocialChip {
    fun update(act: Activity, tm: TabManager, typing: Boolean) {
        val chip = act.findViewById<TextView>(R.id.btn_social) ?: return
        val t = tm.active
        val p = if (t == null || t.isExt || t.home || typing || !SocialMode.on(act)) null else SocialProfiles.of(t.url)
        if (p == null) { chip.visibility = View.GONE; return }
        val d = { v: Int -> Util.dp(act, v) }
        val siteOn = SocialProfiles.siteOn(act, p.id)
        val prim = act.getColor(R.color.mg_primary)
        chip.text = p.label.firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()?.toString() ?: "S"
        chip.alpha = if (siteOn) 1f else 0.55f
        chip.background = GradientDrawable().apply {
            cornerRadius = d(11).toFloat()
            setColor(ColorUtils.setAlphaComponent(prim, 0x2B))
            if (siteOn) setStroke(d(1), ColorUtils.setAlphaComponent(prim, 0xAA))
        }
        val unread = SocialBadge.count(act, t!!.url, t.title)
        chip.contentDescription = "Social tools for ${p.label}" + (if (unread > 0) ", $unread unread" else "")
        if (chip.tag == null) { chip.tag = 1; chip.setOnClickListener { show(act, tm) } }
        if (chip.visibility != View.VISIBLE) { chip.alpha = 0f; chip.visibility = View.VISIBLE; chip.animate().alpha(if (siteOn) 1f else 0.55f).setDuration(160).start() }
    }

    fun show(act: Activity, tm: TabManager) {
        val t = tm.active ?: return
        val p = SocialProfiles.of(t.url) ?: return
        try { Sheet(act, tm, t, p).show() } catch (e: Exception) { android.util.Log.e("MGB.chip", "show", e) }
    }

    private class Sheet(private val act: Activity, private val tm: TabManager, private val tab: TabManager.Tab, private val p: SocialProfiles.Profile) : BottomSheetDialog(act) {
        private var changed = false
        private fun dp(v: Int) = Util.dp(act, v)
        private fun c(id: Int) = act.getColor(id)
        private fun round(color: Int, r: Int, stroke: Int = 0, sc: Int = 0) = GradientDrawable().apply { setColor(color); cornerRadius = dp(r).toFloat(); if (stroke > 0) setStroke(dp(stroke), sc) }

        override fun onCreate(savedInstanceState: android.os.Bundle?) {
            super.onCreate(savedInstanceState)
            setOnShowListener { try { behavior.skipCollapsed = true; behavior.state = BottomSheetBehavior.STATE_EXPANDED } catch (_: Exception) { } }
            setOnDismissListener {
                if (changed) { try { tab.web.reload() } catch (_: Exception) { }; SocialChip.update(act, tm, false) }
            }
            render()
        }

        private fun tv(t: String, sp: Float, color: Int, bold: Boolean = false) = TextView(act).apply {
            text = t; textSize = sp; setTextColor(color)
            if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        }

        private fun render() {
            val col = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(16), dp(14), dp(18)) }
            val siteOn = SocialProfiles.siteOn(act, p.id)
            val head = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            head.addView(tv(p.label.firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()?.toString() ?: "S", 18f, c(R.color.mg_primary), true).apply {
                gravity = Gravity.CENTER; background = round(ColorUtils.setAlphaComponent(c(R.color.mg_primary), 0x2B), 14)
            }, LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginEnd = dp(12) })
            val hb = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
            hb.addView(tv(p.label, 20f, c(R.color.mg_text), true))
            hb.addView(tv(if (siteOn) "App mode is ON for this site" else "App mode is OFF for this site", 12f, c(R.color.mg_text_dim)))
            head.addView(hb, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            col.addView(head, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(12) })

            val ql = SocialQuick.links(p.id)
            if (ql.isNotEmpty() && siteOn) { // 0.0.307: pages the app has as tabs
                col.addView(tv("Go to", 12f, c(R.color.mg_text_dim)).apply { setPadding(dp(4), 0, dp(4), dp(4)) })
                val hs = android.widget.HorizontalScrollView(act).apply { isHorizontalScrollBarEnabled = false }
                val ll = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL }
                for (q in ql) ll.addView(tv(q.label, 13f, c(R.color.mg_primary), true).apply {
                    gravity = Gravity.CENTER; minimumHeight = dp(40); setPadding(dp(14), 0, dp(14), 0)
                    background = round(ColorUtils.setAlphaComponent(c(R.color.mg_primary), 0x1F), 20, 1, ColorUtils.setAlphaComponent(c(R.color.mg_primary), 0x66))
                    setOnClickListener { dismiss(); try { tab.web.loadUrl(q.url) } catch (_: Exception) { } }
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(8) })
                hs.addView(ll)
                col.addView(hs, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(12) })
            }
            col.addView(sw("App mode for ${p.label}", "Phone-style page, banners hidden", siteOn) { v -> SocialProfiles.setSiteOn(act, p.id, v) })
            val bm = SocialBlur.siteMode(act, p.id)
            col.addView(pick("Blur pictures", SocialBlur.siteLabel(bm)) { SocialBlur.cycleSite(act, p.id) })
            col.addView(pick("Pull to refresh here", SocialPull.siteNames[SocialPull.site(act, p.id)] + if (SocialPull.site(act, p.id) == 0) (if (SocialPull.isChat(tab.url)) " (off in chat)" else " (on)") else "", live = true) { SocialPull.setSite(act, p.id, (SocialPull.site(act, p.id) + 1) % 3) })
            col.addView(sw("Link cleaner", "All social sites", SocialClean.on(act)) { v -> SocialClean.setOn(act, v) })
            col.addView(sw("Focus mode", "All social sites: hide Reels / Shorts-like parts", SocialFocus.on(act)) { v -> SocialFocus.setOn(act, v) })
            val muted = SocialMute.count(act)
            col.addView(pick("Muted words", if (muted == 0) "None" else "$muted words", opens = true) { })
            col.addView(tv("Changes apply when you close this sheet (the page reloads once).", 11f, c(R.color.mg_text_dim)).apply { setPadding(dp(4), dp(8), dp(4), dp(4)) })
            col.addView(tv("All social settings", 14f, c(R.color.mg_primary), true).apply {
                gravity = Gravity.CENTER; minimumHeight = dp(48)
                setOnClickListener { dismiss(); try { SocialScreen.open(act) } catch (_: Exception) { } }
            })
            val cap = object : LinearLayout(act) { // 560 dp cap on wide screens
                override fun onMeasure(w: Int, h: Int) {
                    val size = MeasureSpec.getSize(w); val cp = dp(560)
                    super.onMeasure(if (size > cp) MeasureSpec.makeMeasureSpec(cp, MeasureSpec.EXACTLY) else w, h)
                }
            }.apply { orientation = LinearLayout.VERTICAL; addView(col, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)) }
            setContentView(ScrollView(act).apply { addView(cap, android.widget.FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL)) })
        }

        private fun rowBox(): LinearLayout = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = round(c(R.color.mg_surface), 18, 1, c(R.color.mg_outline))
            minimumHeight = dp(56); setPadding(dp(16), dp(6), dp(14), dp(6)); isClickable = true
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) }
        }

        private fun sw(label: String, hint: String, on: Boolean, set: (Boolean) -> Unit): View {
            val r = rowBox()
            val b = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
            b.addView(tv(label, 15f, c(R.color.mg_text)))
            b.addView(tv(hint, 12f, c(R.color.mg_text_dim)))
            r.addView(b, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            val s = ThazirSwitch(act).apply { isChecked = on; isClickable = false; isFocusable = false }
            r.addView(s, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(8) })
            r.setOnClickListener { val n = !s.isChecked; s.isChecked = n; set(n); changed = true; r.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK) }
            return r
        }

        private fun pick(label: String, value: String, opens: Boolean = false, live: Boolean = false, next: () -> Unit): View {
            val r = rowBox()
            r.addView(tv(label, 15f, c(R.color.mg_text)), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            val vt = tv(value, 13f, c(R.color.mg_primary), true)
            r.addView(vt)
            if (opens) r.setOnClickListener { dismiss(); try { SocialScreen.open(act) } catch (_: Exception) { } }
            else r.setOnClickListener { next(); if (!live) changed = true; render() } // live = applies at once, no page reload
            return r
        }
    }
}
