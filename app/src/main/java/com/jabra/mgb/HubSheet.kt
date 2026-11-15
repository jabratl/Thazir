package com.jabra.mgb

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView

/**
 * 0.0.456: the Hub = the 3 x 3 popup behind the Hub button (Appearance > Hub button).
 *  - floating card, same width as the bar, above a bottom bar / below a top bar.
 *  - a tile = 52 dp chip (radius 16) + 24 dp icon + 12 sp label. Tap = close, then run. A disabled tile is dim (alpha .38) and does nothing.
 *  - active (accent) tile = chip in colorPrimary (Privacy Guard while it guards the page).
 * The actions themselves live in MainActivity.hubRun (needs the tabs); here only look + layout.
 */
object HubSheet {
    private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

    /** [state] 0 = normal, 1 = accent (on). */
    class Tile(val id: String, val label: String, val icon: Int, val enabled: Boolean = true, val state: Int = 0)

    private var current: PopupWindow? = null

    fun show(act: Activity, bar: View, tiles: List<Tile>, run: (String) -> Unit) {
        try { current?.dismiss() } catch (_: Exception) { }
        val cText = act.getColor(R.color.mg_text)
        val cChip = act.getColor(R.color.mg_surface_variant)
        val cPrimary = act.getColor(R.color.mg_primary)
        val cOnPrimary = act.getColor(R.color.mg_on_primary)

        val card = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Util.dp(act, 12), Util.dp(act, 12), Util.dp(act, 12), Util.dp(act, 8))
            background = GradientDrawable().apply {
                setColor(act.getColor(R.color.mg_surface))
                cornerRadius = Util.dp(act, 24).toFloat()
                setStroke(Util.dp(act, 1), act.getColor(R.color.mg_outline))
            }
            elevation = Util.dp(act, 8).toFloat()
        }
        val pop = PopupWindow(card, bar.width.coerceAtLeast(Util.dp(act, 240)), WRAP, true).apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            isOutsideTouchable = true
            elevation = Util.dp(act, 8).toFloat()
        }
        val ripple = TypedValue()
        val hasRipple = act.theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, ripple, true)

        for (r in 0 until 3) {
            val row = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; weightSum = 3f }
            for (c in 0 until 3) {
                val t = tiles.getOrNull(r * 3 + c)
                val cell = LinearLayout(act).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER_HORIZONTAL
                    setPadding(0, Util.dp(act, 4), 0, Util.dp(act, 6))
                }
                if (t != null) {
                    val on = t.state == 1
                    val chip = ImageView(act).apply {
                        setImageResource(t.icon)
                        setColorFilter(if (on) cOnPrimary else cText)
                        scaleType = ImageView.ScaleType.CENTER_INSIDE
                        setPadding(Util.dp(act, 14), Util.dp(act, 14), Util.dp(act, 14), Util.dp(act, 14))
                        background = GradientDrawable().apply { setColor(if (on) cPrimary else cChip); cornerRadius = Util.dp(act, 16).toFloat() }
                    }
                    cell.addView(chip, LinearLayout.LayoutParams(Util.dp(act, 52), Util.dp(act, 52)))
                    cell.addView(TextView(act).apply {
                        text = t.label
                        textSize = 12f
                        setTextColor(cText)
                        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                        gravity = Gravity.CENTER
                        setSingleLine(true)
                        setPadding(0, Util.dp(act, 6), 0, 0)
                    }, LinearLayout.LayoutParams(MATCH, WRAP))
                    cell.isClickable = true
                    cell.contentDescription = t.label
                    if (hasRipple) cell.setBackgroundResource(ripple.resourceId)
                    cell.alpha = if (t.enabled) 1f else 0.38f
                    cell.setOnClickListener {
                        if (!t.enabled) return@setOnClickListener
                        it.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
                        pop.dismiss()
                        bar.post { try { run(t.id) } catch (_: Exception) { } }
                    }
                }
                row.addView(cell, LinearLayout.LayoutParams(0, WRAP, 1f))
            }
            card.addView(row, LinearLayout.LayoutParams(MATCH, WRAP))
        }

        card.measure(
            View.MeasureSpec.makeMeasureSpec(pop.width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val loc = IntArray(2)
        bar.getLocationOnScreen(loc)
        val gap = Util.dp(act, 8)
        val y = if (AppearancePrefs.barTop(act)) loc[1] + bar.height + gap else loc[1] - card.measuredHeight - gap
        try {
            pop.showAtLocation(bar, Gravity.NO_GRAVITY, loc[0], y.coerceAtLeast(0))
            current = pop
            pop.setOnDismissListener { if (current === pop) current = null }
        } catch (_: Exception) { }
    }
}
