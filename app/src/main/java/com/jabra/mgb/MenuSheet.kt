package com.jabra.mgb

import androidx.core.widget.NestedScrollView
import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.FrameLayout
import androidx.core.graphics.ColorUtils
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog

/**
 * S22: the 3-dot menu as a bottom sheet, built in code (no layout xml).
 * S27: two rounded cards on a quiet sheet.
 *  Card 1: 4 quick tiles, then general rows (icon + label).
 *  Card 2: page header (site name + share), page rows (icon + label + optional ON/OFF), icon row (back / forward / bookmark / reload).
 * Colours come from the mg_* tokens, so dark and light follow the theme.
 */
object MenuSheet {

    class Quick(val icon: Int, val label: String, val key: String = label, val onClick: () -> Unit)
    class Act(val icon: Int, val enabled: Boolean = true, val onClick: () -> Unit)
    /** icon 0 = no icon. trail = small text on the right (for example ON / OFF). */
    /** 0.0.123: key = saved id of the row in Customise menu (default = label). bmp = extension icon (shown without tint). */
    class Item(val label: String, val icon: Int = 0, val enabled: Boolean = true, val trail: String = "", val key: String = label, val bmp: android.graphics.Bitmap? = null, val danger: Boolean = false, val onClick: () -> Unit)

    private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

    /** 0.0.335: NestedScrollView that never grows past [maxH] (so the pinned icon bar below it always fits on screen). */
    private class CapScroll(c: Context, val maxH: Int) : NestedScrollView(c) {
        init {
            // 0.0.338: scrolled rows used to be cut by a hard square edge that stuck out of the rounded sheet corners.
            // Clip to the sheet's rounded top (28 dp) and fade the rows out softly under the top / bottom edge.
            val r = 28f * c.resources.displayMetrics.density
            clipToOutline = true
            outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(v: View, o: android.graphics.Outline) { o.setRoundRect(0, 0, v.width, v.height + r.toInt(), r) }
            }
            isVerticalFadingEdgeEnabled = true
            setFadingEdgeLength((22f * c.resources.displayMetrics.density).toInt())
        }
        override fun onMeasure(w: Int, h: Int) {
            val cap = if (View.MeasureSpec.getMode(h) == View.MeasureSpec.UNSPECIFIED) maxH else minOf(View.MeasureSpec.getSize(h), maxH)
            super.onMeasure(w, View.MeasureSpec.makeMeasureSpec(cap, View.MeasureSpec.AT_MOST))
        }
    }

    fun show(
        ctx: Context, quick: List<Quick>, general: List<Item>,
        pageTitle: String, onShare: (() -> Unit)?, page: List<Item>,
        acts: List<Act>, footer: String, reopen: (() -> Unit)? = null
    ) {
        val dlg = BottomSheetDialog(ctx)
        fun dp(v: Int) = Util.dp(ctx, v)
        fun round(color: Int, r: Int) = GradientDrawable().apply { setColor(color); cornerRadius = dp(r).toFloat() }
        val ripple = ctx.obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackground)).let {
            val d = it.getDrawable(0)
            it.recycle()
            d
        }
        val cText = ctx.getColor(R.color.mg_text)
        val cDim = ctx.getColor(R.color.mg_text_dim)
        val cCard = ctx.getColor(R.color.mg_surface)
        val cTile = ctx.getColor(R.color.mg_surface_variant)
        val cLine = ctx.getColor(R.color.mg_outline)
        val cAccent = ctx.getColor(R.color.mg_primary)

        fun card() = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = round(cCard, 26)
            setPadding(0, dp(8), 0, dp(8))
        }
        fun divider() = View(ctx).apply { setBackgroundColor(cLine) }
        fun addDivider(c: LinearLayout) =
            c.addView(divider(), LinearLayout.LayoutParams(MATCH, dp(1)).apply { marginStart = dp(16); marginEnd = dp(16); topMargin = dp(6); bottomMargin = dp(6) })

        fun itemRow(m: Item): View {
            val r = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(16), 0, dp(16), 0)
                alpha = if (m.enabled) 1f else 0.4f
                isEnabled = m.enabled
                isClickable = m.enabled
                background = ripple?.constantState?.newDrawable()
                setOnClickListener { dlg.dismiss(); m.onClick() }
            }
            if (m.bmp != null) r.addView(ImageView(ctx).apply { // 0.0.123: extension icon, own colours
                setImageBitmap(m.bmp)
                scaleType = ImageView.ScaleType.FIT_CENTER
            }, LinearLayout.LayoutParams(dp(24), dp(24)).apply { marginEnd = dp(16); marginStart = dp(-1) })
            else if (m.icon != 0) r.addView(ImageView(ctx).apply {
                setImageResource(m.icon)
                setColorFilter(cDim)
            }, LinearLayout.LayoutParams(dp(22), dp(22)).apply { marginEnd = dp(16) })
            r.addView(TextView(ctx).apply {
                text = m.label
                textSize = 16f
                setTextColor(cText)
                maxLines = 1
            }, LinearLayout.LayoutParams(0, WRAP, 1f))
            if (m.trail.isNotEmpty()) r.addView(TextView(ctx).apply { // S71: ON = small rose pill
                text = m.trail
                textSize = 12f
                val on = m.trail.startsWith("ON")
                setTextColor(if (on) cAccent else cDim)
                if (on) {
                    background = round(ColorUtils.setAlphaComponent(cAccent, 0x26), 12)
                    setPadding(dp(10), dp(3), dp(10), dp(3))
                }
            })
            return r
        }

        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(16), dp(12), dp(16))
        }

        col.addView(View(ctx).apply { background = round(cLine, 3) }, // S71: grab handle
            LinearLayout.LayoutParams(dp(36), dp(4)).apply { gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = dp(12); topMargin = dp(-6) })
        val tiles = mutableListOf<View>()

        // ---- card 1: quick tiles + general rows
        val c1 = card()
        val qrow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(8), dp(4), dp(8), dp(4)) }
        for (q in quick) {
            val t = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                background = round(cTile, 18)
                if (android.os.Build.VERSION.SDK_INT >= 23) foreground = ripple?.constantState?.newDrawable() // S71: press feedback
                setPadding(0, dp(12), 0, dp(12))
                isClickable = true
                isFocusable = true
                setOnClickListener { it.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK); dlg.dismiss(); q.onClick() }
            }
            tiles.add(t)
            val badge = FrameLayout(ctx).apply { background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(ColorUtils.setAlphaComponent(cAccent, 0x26)) } } // S71
            badge.addView(ImageView(ctx).apply { setImageResource(q.icon); setColorFilter(cAccent) }, FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER))
            t.addView(badge, LinearLayout.LayoutParams(dp(40), dp(40)))
            t.addView(TextView(ctx).apply {
                text = q.label
                textSize = 12f
                setTextColor(cText)
                gravity = Gravity.CENTER
                maxLines = 1
                setPadding(0, dp(6), 0, 0)
            })
            qrow.addView(t, LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginStart = dp(4); marginEnd = dp(4) })
        }
        if (quick.isNotEmpty()) c1.addView(qrow, LinearLayout.LayoutParams(MATCH, WRAP)) // 0.0.123: tiles can be hidden
        if (quick.isNotEmpty() && general.isNotEmpty()) addDivider(c1)
        for (m in general) c1.addView(itemRow(m), LinearLayout.LayoutParams(MATCH, dp(52)))
        col.addView(c1, LinearLayout.LayoutParams(MATCH, WRAP))

        // ---- card 2: page header, page rows, icon row
        val c2 = card()
        val head = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), 0, dp(4), 0)
        }
        head.addView(TextView(ctx).apply {
            text = pageTitle
            textSize = 16f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(cText)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(0, WRAP, 1f))
        head.addView(ImageView(ctx).apply {
            setImageResource(R.drawable.ic_share)
            setColorFilter(cText)
            scaleType = ImageView.ScaleType.CENTER
            alpha = if (onShare != null) 1f else 0.35f
            isEnabled = onShare != null
            isClickable = onShare != null
            contentDescription = "Share"
            background = ripple?.constantState?.newDrawable()
            setOnClickListener { dlg.dismiss(); onShare?.invoke() }
        }, LinearLayout.LayoutParams(dp(48), dp(48)))
        val showPage = pageTitle.isNotBlank() || page.isNotEmpty() // 0.0.126: Home passes blank title + no rows = only the icon row
        if (showPage) c2.addView(head, LinearLayout.LayoutParams(MATCH, dp(48)))
        for (m in page) c2.addView(itemRow(m), LinearLayout.LayoutParams(MATCH, dp(52)))
        if (showPage) col.addView(c2, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(12) }) // 0.0.335: icon row moved out of the card (pinned below)

        col.addView(TextView(ctx).apply {
            text = footer
            textSize = 12f
            gravity = Gravity.CENTER
            setTextColor(cDim)
            setPadding(dp(12), dp(14), dp(12), 0)
        })

        // 0.0.335: the list scrolls, the icon bar stays pinned at the bottom (like Brave). Hold an icon 3 s = Edit shortcut (MenuBar).
        val hPx = ctx.resources.displayMetrics.heightPixels
        val sbId = ctx.resources.getIdentifier("status_bar_height", "dimen", "android")
        val sbPx = if (sbId > 0) ctx.resources.getDimensionPixelSize(sbId) else dp(24)
        // 0.0.336: real free height (screen minus status bar), never below 140 dp, so a small landscape screen still scrolls the list
        val maxH = (minOf((hPx * 0.88f).toInt(), hPx - sbPx - dp(16)) - (if (acts.isNotEmpty()) dp(80) else 0)).coerceAtLeast(dp(140))
        val sv = CapScroll(ctx, maxH)
        sv.overScrollMode = View.OVER_SCROLL_NEVER // S88: no stretch/glow fighting the sheet drag
        sv.addView(col, ViewGroup.LayoutParams(MATCH, WRAP))
        val root = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        root.addView(sv, LinearLayout.LayoutParams(MATCH, WRAP))
        if (acts.isNotEmpty()) {
            root.addView(View(ctx).apply { setBackgroundColor(cLine) }, LinearLayout.LayoutParams(MATCH, dp(1)))
            val bar = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), dp(8), dp(12), dp(10))
                setBackgroundColor(ctx.getColor(R.color.mg_bg))
            }
            acts.forEachIndexed { i, a ->
                val slot = FrameLayout(ctx)
                val b = ImageView(ctx).apply {
                    setImageResource(a.icon)
                    setColorFilter(cText)
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    setPadding(dp(11), dp(11), dp(11), dp(11))
                    background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(cTile) }
                    if (android.os.Build.VERSION.SDK_INT >= 23) foreground = ripple?.constantState?.newDrawable()
                    alpha = if (a.enabled) 1f else 0.35f
                    isClickable = true // not isEnabled=false: a disabled view would not get the 3 s hold
                    isFocusable = true
                    contentDescription = MenuBar.entry(MenuBar.ids(ctx).getOrElse(i) { "" }).label
                    setOnClickListener { if (a.enabled) { dlg.dismiss(); a.onClick() } }
                }
                if (reopen != null) MenuBar.attachEdit(b, i) { dlg.dismiss(); reopen() }
                slot.addView(b, FrameLayout.LayoutParams(dp(44), dp(44), Gravity.CENTER))
                bar.addView(slot, LinearLayout.LayoutParams(0, dp(52), 1f))
            }
            root.addView(bar, LinearLayout.LayoutParams(MATCH, WRAP))
        }
        dlg.setContentView(root)
        dlg.setOnShowListener { // quiet sheet colour with rounded top
            val ease = android.view.animation.DecelerateInterpolator(1.6f)
            tiles.forEachIndexed { i, tv -> // S71: tiles pop in one after the other
                tv.alpha = 0f; tv.translationY = dp(14).toFloat()
                tv.animate().alpha(1f).translationY(0f).setStartDelay(80L + i * 45L).setDuration(260).setInterpolator(ease).start()
            }
            dlg.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)?.background =
                GradientDrawable().apply {
                    setColor(ctx.getColor(R.color.mg_bg))
                    val r = dp(28).toFloat()
                    cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
                }
        }
        dlg.behavior.skipCollapsed = true
        dlg.behavior.maxWidth = dp(560) // 0.0.336: width cap (design rules)
        dlg.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        dlg.show()
    }

    /** S40: long press menu of a link / image: header card (letter, title, address) and one card of icon rows. */
    fun showLink(ctx: Context, title: String, url: String, rows: List<Item>) {
        val dlg = BottomSheetDialog(ctx)
        fun dp(v: Int) = Util.dp(ctx, v)
        fun round(color: Int, r: Int) = GradientDrawable().apply { setColor(color); cornerRadius = dp(r).toFloat() }
        val ripple = ctx.obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackground)).let {
            val d = it.getDrawable(0)
            it.recycle()
            d
        }
        val cText = ctx.getColor(R.color.mg_text)
        val cDim = ctx.getColor(R.color.mg_text_dim)
        val cCard = ctx.getColor(R.color.mg_surface)
        val cTile = ctx.getColor(R.color.mg_surface_variant)
        val cLine = ctx.getColor(R.color.mg_outline)

        val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(16), dp(12), dp(16)) }

        // header: round letter + title + address
        val head = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = round(cTile, 20)
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }
        val host = Util.host(url)
        val letter = (host.ifBlank { title }).trim().firstOrNull()?.uppercaseChar()?.toString() ?: "?"
        head.addView(TextView(ctx).apply {
            text = letter
            textSize = 22f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(cText)
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(cLine) }
        }, LinearLayout.LayoutParams(dp(48), dp(48)).apply { marginEnd = dp(14) })
        val tcol = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        tcol.addView(TextView(ctx).apply {
            text = title.ifBlank { host.ifBlank { url } }
            textSize = 15f
            setTextColor(cText)
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
        })
        tcol.addView(TextView(ctx).apply {
            text = url
            textSize = 12f
            setTextColor(cDim)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
        })
        head.addView(tcol, LinearLayout.LayoutParams(0, WRAP, 1f))
        col.addView(head, LinearLayout.LayoutParams(MATCH, WRAP))

        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = round(cCard, 26)
            setPadding(0, dp(6), 0, dp(6))
        }
        for (m in rows) {
            val r = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(18), 0, dp(18), 0)
                background = ripple?.constantState?.newDrawable()
                isClickable = true
                setOnClickListener { dlg.dismiss(); m.onClick() }
            }
            val cRow = if (m.danger) ctx.getColor(R.color.mg_error) else cText // 0.0.127: red Delete
            val lc = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_VERTICAL }
            lc.addView(TextView(ctx).apply { text = m.label; textSize = 16f; setTextColor(cRow); maxLines = 1 })
            if (m.trail.isNotBlank()) lc.addView(TextView(ctx).apply { text = m.trail; textSize = 12f; setTextColor(cDim); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.MIDDLE })
            r.addView(lc, LinearLayout.LayoutParams(0, WRAP, 1f))
            if (m.icon != 0) r.addView(ImageView(ctx).apply { setImageResource(m.icon); setColorFilter(if (m.danger) cRow else cDim) }, LinearLayout.LayoutParams(dp(22), dp(22)))
            card.addView(r, LinearLayout.LayoutParams(MATCH, if (m.trail.isNotBlank()) dp(60) else dp(50)))
        }
        col.addView(card, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(12) })

        val sv = NestedScrollView(ctx)
        sv.overScrollMode = View.OVER_SCROLL_NEVER
        sv.addView(col, ViewGroup.LayoutParams(MATCH, WRAP))
        dlg.setContentView(sv)
        dlg.setOnShowListener {
            dlg.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)?.background =
                GradientDrawable().apply {
                    setColor(ctx.getColor(R.color.mg_bg))
                    val rr = dp(28).toFloat()
                    cornerRadii = floatArrayOf(rr, rr, rr, rr, 0f, 0f, 0f, 0f)
                }
        }
        dlg.behavior.skipCollapsed = true
        dlg.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        dlg.show()
    }
}
