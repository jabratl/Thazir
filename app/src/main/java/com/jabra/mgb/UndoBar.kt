package com.jabra.mgb

import android.app.Activity
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 0.0.363 (P2): the Undo bar for the main browser (tab closed from the group strip or Back), same look as the
 * switcher's bar (TabSwitcher.undo): mg_surface_variant, 16dp, 52dp, 5 s, swipe left / right to remove (SwipeAway.Bar).
 * Stack of one: a newer bar replaces the old one. Settings "Show undo toast" OFF = no bar. Reduce motion = no animation.
 * It sits above the group strip (or the bottom bar) when one is visible.
 */
object UndoBar {
    private var bar: SwipeAway.Bar? = null
    private var host: FrameLayout? = null

    fun show(act: Activity, msg: String, onUndo: () -> Unit) {
        hide(false)
        if (!AppearancePrefs.undoToast(act)) return
        val root = act.findViewById<FrameLayout>(android.R.id.content) ?: return
        val reduce = TabPrefs.reduce(act)
        fun dp(v: Int) = Util.dp(act, v)
        val b = SwipeAway.Bar(act, reduce).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), 0, dp(4), 0)
            background = GradientDrawable().apply {
                setColor(act.getColor(R.color.mg_surface_variant))
                setStroke(dp(1), act.getColor(R.color.mg_outline))
                cornerRadius = dp(16).toFloat()
            }
            elevation = dp(8).toFloat()
            if (!reduce) { alpha = 0f; translationY = dp(20).toFloat() }
        }
        b.addView(TextView(act).apply {
            text = msg
            textSize = 14f
            maxLines = 1
            setTextColor(act.getColor(R.color.mg_text))
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        b.addView(TextView(act).apply {
            text = "Undo"
            textSize = 14f
            setTextColor(act.getColor(R.color.mg_primary))
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setPadding(dp(14), dp(14), dp(14), dp(14))
            setOnClickListener { hide(false); onUndo() }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        var mb = dp(24)
        try { // sit above the strip / bottom bar when that bar is in the lower half of the screen
            val anchor = GroupStrip.topView() ?: act.findViewById<View>(R.id.bottom_bar)?.takeIf { it.visibility == View.VISIBLE }
            if (anchor != null && root.height > 0) {
                val a = IntArray(2); val r = IntArray(2)
                anchor.getLocationInWindow(a); root.getLocationInWindow(r)
                val top = a[1] - r[1]
                if (top > root.height / 2) mb = (root.height - top) + dp(6)
            }
        } catch (_: Exception) { }
        root.addView(b, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52), Gravity.BOTTOM).apply { setMargins(dp(14), 0, dp(14), mb) })
        if (!reduce) b.animate().alpha(1f).translationY(0f).setInterpolator(android.view.animation.DecelerateInterpolator(1.6f)).setDuration(200).start()
        bar = b; host = root
        b.onGone = { if (bar === b) hide(false) }
        val autoHide = object : Runnable { // not while a finger is dragging the bar
            override fun run() { if (bar !== b) return; if (b.busy) b.postDelayed(this, 1500) else hide(true) }
        }
        b.postDelayed(autoHide, 5000)
    }

    fun hide(anim: Boolean) {
        val b = bar ?: return
        val h = host
        bar = null; host = null
        if (anim && b.isAttachedToWindow) b.animate().alpha(0f).translationY(b.height * 0.4f).setDuration(160).withEndAction { h?.removeView(b) }.start()
        else h?.removeView(b)
    }
}
