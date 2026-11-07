package com.jabra.mgb

import android.content.Context
import android.graphics.Color
import android.os.Build
import android.animation.ValueAnimator
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.widget.HorizontalScrollView
import androidx.core.graphics.ColorUtils
import java.util.Calendar
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * S13: speed-dial home shown over the (blank) WebView of a "home" tab (Tab.home).
 * Tiles = bookmarks. Tap = open here, long press = open in new tab / remove, add tile = add a site.
 * S22: wordmark + 4 column tiles. S24: quiet monochrome tiles (letter, no colours), vector icons,
 * edit mode (pencil, top right): tiles get a remove badge.
 * S34: shows at once (never waits for a layout pass), greeting + rose-dot wordmark, tinted monogram tiles,
 * "Jump back in" row (recent sites not pinned) and a quiet ads-blocked line.
 * Built in code (no layout xml), colours from the mg_* tokens so dark and light both follow the theme.
 */
class HomeView(
    ctx: Context,
    private val onOpen: (String) -> Unit,
    private val onOpenNew: (String) -> Unit,
    private val onBookmarks: () -> Unit, // kept for TabManager; the menu has these now
    private val onHistory: () -> Unit
) : FrameLayout(ctx) {

    private val content = LinearLayout(ctx)
    private val pencil = ImageView(ctx)
    private val wpImg = ImageView(ctx) // A7: wallpaper under the tiles
    private val wpScrim = View(ctx) // A7: dim layer (mg_bg colour) so the tiles stay readable
    private val wpListener: () -> Unit = { applyWallpaper() }
    private var cols = 0
    private var editing = false
    private var folder: String? = null // S26: folder open on the home screen (null = top level)
    /** S26: private tab: small "Private tab" note under the wordmark. */
    var priv = false
    /** S28: tap on the search pill (MainActivity focuses the address bar). */
    var onSearch: (() -> Unit)? = null
    private var animateNext = false

    private companion object {
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
    }

    private fun dp(v: Int) = Util.dp(context, v)

    init {
        setBackgroundColor(context.getColor(R.color.mg_bg))
        isClickable = true // swallow touches: the blank WebView below must not get them
        content.orientation = LinearLayout.VERTICAL
        content.setPadding(dp(18), dp(30), dp(18), dp(28))
        wpImg.scaleType = ImageView.ScaleType.CENTER_CROP
        wpImg.visibility = View.GONE; wpScrim.visibility = View.GONE
        addView(wpImg, LayoutParams(MATCH, MATCH)) // A7: first children = behind the scroll content
        addView(wpScrim, LayoutParams(MATCH, MATCH))
        val sv = ScrollView(context)
        sv.isFillViewport = true
        sv.addView(content, LayoutParams(MATCH, WRAP))
        addView(sv, LayoutParams(MATCH, MATCH))

        pencil.setImageResource(R.drawable.ic_edit)
        pencil.setPadding(dp(10), dp(10), dp(10), dp(10))
        pencil.setColorFilter(context.getColor(R.color.mg_primary))
        pencil.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(15).toFloat()
            setColor(context.getColor(R.color.mg_surface))
            setStroke(dp(1), context.getColor(R.color.mg_outline))
        }
        pencil.contentDescription = "Edit home shortcuts"
        pencil.isClickable = true
        pencil.isFocusable = true
        pencil.setOnClickListener {
            editing = !editing
            pencil.setImageResource(if (editing) R.drawable.ic_check else R.drawable.ic_edit)
            rebuild()
        }
        addView(pencil, LayoutParams(dp(44), dp(44), Gravity.TOP or Gravity.END).apply { topMargin = dp(12); marginEnd = dp(12) })
    }

    /** A7: show / hide the wallpaper (file exists = on) and apply the dim value. No wallpaper = home looks as before. */
    fun applyWallpaper() {
        val bmp = Wallpaper.load(context)
        if (bmp == null) { wpImg.visibility = View.GONE; wpScrim.visibility = View.GONE; return }
        wpImg.setImageBitmap(bmp)
        wpScrim.setBackgroundColor(androidx.core.graphics.ColorUtils.setAlphaComponent(context.getColor(R.color.mg_bg), AppearancePrefs.wpDim(context) * 255 / 100))
        wpImg.visibility = View.VISIBLE; wpScrim.visibility = View.VISIBLE
    }

    override fun onDetachedFromWindow() {
        Wallpaper.listeners.remove(wpListener)
        super.onDetachedFromWindow()
    }

    /** Rebuild the tiles from the current bookmarks (width 0 = not laid out yet, onSizeChanged does it). */
    fun refresh() = rebuild()

    /** S26: back to the top level (called when the home tab is shown). */
    fun showTop() { folder = null; editing = false; pencil.setImageResource(R.drawable.ic_edit); animateNext = true }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        Wallpaper.listeners.add(wpListener); applyWallpaper() // A7
        // S34: if nothing was built yet (first layout not done), build now
        post { if (isAttachedToWindow && content.childCount == 0) rebuild() }
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        val c = ((w - dp(36)) / dp(88)).coerceIn(3, 5)
        if (c != cols) rebuild()
    }

    private fun rebuild() {
        // S34: before the first layout width is 0: use the screen size so the home is never empty
        val dm = resources.displayMetrics
        val w = if (width > 0) width else dm.widthPixels
        cols = ((w - dp(36)) / dp(88)).coerceIn(3, 5)
        content.removeAllViews()

        val f0 = folder
        if (f0 != null && !Bookmarks.folders().contains(f0)) folder = null
        val cur = folder
        if (cur == null) {
            // Signature start-page header: compact, left aligned, and clear of the edit control.
            content.addView(TextView(context).apply {
                text = if (priv) "PRIVATE BROWSING" else greeting().uppercase()
                setTextColor(context.getColor(R.color.mg_primary))
                textSize = 11f
                letterSpacing = 0.18f
                gravity = Gravity.START
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setPadding(dp(2), dp(2), dp(52), dp(8))
            }, LinearLayout.LayoutParams(MATCH, WRAP))
            content.addView(TextView(context).apply {
                val sb = SpannableStringBuilder("Thazir.")
                sb.setSpan(ForegroundColorSpan(context.getColor(R.color.mg_primary)), 6, 7, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                text = sb
                setTextColor(context.getColor(R.color.mg_text))
                textSize = 34f
                letterSpacing = 0.01f
                gravity = Gravity.START
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setPadding(0, 0, dp(52), dp(2))
            }, LinearLayout.LayoutParams(MATCH, WRAP))
            content.addView(TextView(context).apply {
                text = if (priv) "Your private session starts here." else "A calmer place to start browsing."
                setTextColor(context.getColor(R.color.mg_text_dim))
                textSize = 13f
                gravity = Gravity.START
                setPadding(dp(2), 0, dp(52), dp(18))
            }, LinearLayout.LayoutParams(MATCH, WRAP))
            content.addView(searchPill(), LinearLayout.LayoutParams(MATCH, dp(52)).apply {
                bottomMargin = dp(26)
            })
        } else {
            val head = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(24), 0, dp(24))
            }
            head.addView(ImageView(context).apply {
                setImageResource(R.drawable.ic_back)
                setColorFilter(context.getColor(R.color.mg_text))
                setPadding(dp(10), dp(10), dp(10), dp(10))
                contentDescription = "Back"
                setOnClickListener { folder = null; rebuild() }
            }, LinearLayout.LayoutParams(dp(44), dp(44)))
            head.addView(TextView(context).apply {
                text = cur
                setTextColor(context.getColor(R.color.mg_text))
                textSize = 20f
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            }, LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginStart = dp(8); marginEnd = dp(48) })
            content.addView(head, LinearLayout.LayoutParams(MATCH, WRAP))
        }

        if (cur == null) {
            content.addView(sectionHeading("YOUR SHORTCUTS", "${Bookmarks.home().size} pinned"),
                LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(8) })
        }

        val cells = ArrayList<View>()
        if (cur == null) { // S28: only what the user pinned (not every bookmark)
            for (h in Bookmarks.home()) {
                if (h.folder.isNotEmpty()) { if (Bookmarks.folders().contains(h.folder)) cells.add(folderTile(h.folder)) }
                else cells.add(tile(h.title, h.url))
            }
        } else {
            for (b in Bookmarks.bookmarks()) if (b.folder == cur) cells.add(tile(b.title, b.url))
        }
        if (cur == null && Bookmarks.home().isEmpty() && !editing) {
            content.addView(TextView(context).apply {
                text = "Pin your favorite sites here for one-tap access."
                setTextColor(context.getColor(R.color.mg_text_dim))
                textSize = 12f
                setPadding(dp(4), dp(2), dp(4), dp(8))
            }, LinearLayout.LayoutParams(MATCH, WRAP))
        }
        if (!editing) cells.add(addTile())
        var i = 0
        while (i < cells.size) {
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
            }
            for (n in 0 until cols) {
                val v = if (i + n < cells.size) cells[i + n] else View(context)
                row.addView(v, LinearLayout.LayoutParams(0, WRAP, 1f))
            }
            content.addView(row)
            i += cols
        }
        if (cur == null && !editing && !priv) { // S34: smart extras (not in edit mode / private tab)
            recentRow()?.let { content.addView(it, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(26) }) }
            blockedLine()?.let { content.addView(it, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(22) }) }
        }
        if (animateNext) { animateNext = false; fadeIn() }
    }

    /** S34: children slide in one after the other; a timer makes sure nothing stays invisible. */
    private fun fadeIn() {
        if (Build.VERSION.SDK_INT >= 26 && !ValueAnimator.areAnimatorsEnabled()) return
        for (i in 0 until content.childCount) {
            val v = content.getChildAt(i)
            v.alpha = 0f
            v.translationY = dp(12).toFloat()
            v.animate().alpha(1f).translationY(0f).setStartDelay(i * 40L).setDuration(240)
                .withEndAction { v.alpha = 1f; v.translationY = 0f }.start()
        }
        postDelayed({
            for (i in 0 until content.childCount) { val v = content.getChildAt(i); v.alpha = 1f; v.translationY = 0f }
        }, 900)
    }

    private fun greeting(): String {
        val h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        return when {
            h < 5 -> "Late night"
            h < 12 -> "Good morning"
            h < 17 -> "Good afternoon"
            h < 22 -> "Good evening"
            else -> "Late night"
        }
    }

    /** S34: soft colour per site (from its name), blended into the theme surface so dark and light both work. */
    private fun tint(seed: String): Int {
        val hue = ((seed.lowercase().hashCode() % 360) + 360) % 360
        return Color.HSVToColor(floatArrayOf(hue.toFloat(), 0.55f, 0.92f))
    }

    /** Compact section label used to give the start page a clearer visual hierarchy. */
    private fun sectionHeading(title: String, detail: String): View = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(2), dp(2), dp(2), dp(2))
        addView(TextView(context).apply {
            text = title
            setTextColor(context.getColor(R.color.mg_text))
            textSize = 11f
            letterSpacing = 0.12f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }, LinearLayout.LayoutParams(0, WRAP, 1f))
        addView(TextView(context).apply {
            text = detail
            setTextColor(context.getColor(R.color.mg_text_dim))
            textSize = 11f
        })
    }

    /** S34: recent sites that are not pinned: one tap to jump back. */
    private fun recentRow(): View? {
        val skip = HashSet<String>()
        skip.add("appassets.androidplatform.net")
        for (h in Bookmarks.home()) if (h.url.isNotBlank()) skip.add(Util.host(h.url))
        val items = ArrayList<Bookmarks.Item>()
        for (h in Bookmarks.history()) {
            if (!h.url.startsWith("http", true)) continue
            val host = Util.host(h.url)
            if (host.isBlank() || !skip.add(host)) continue
            items.add(h)
            if (items.size >= 6) break
        }
        if (items.isEmpty()) return null
        val col = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        col.addView(TextView(context).apply {
            text = "RECENTLY VISITED"
            setTextColor(context.getColor(R.color.mg_text_dim))
            textSize = 11f
            letterSpacing = 0.16f
            setPadding(dp(8), 0, 0, dp(10))
        }, LinearLayout.LayoutParams(MATCH, WRAP))
        val line = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(4), 0, dp(4), 0) }
        for (r in items) line.addView(chip(r.title, r.url), LinearLayout.LayoutParams(WRAP, dp(44)).apply { marginEnd = dp(8) })
        col.addView(HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(line, FrameLayout.LayoutParams(WRAP, WRAP))
        }, LinearLayout.LayoutParams(MATCH, WRAP))
        return col
    }

    private fun chip(title: String, url: String): View {
        val name = title.ifBlank { Util.host(url) }
        val letter = Util.host(url).firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()?.toString() ?: "?"
        val t = tint(Util.host(url))
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), 0, dp(16), 0)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(22).toFloat()
                setColor(context.getColor(R.color.mg_surface))
                setStroke(dp(1), context.getColor(R.color.mg_outline))
            }
            isClickable = true
            isFocusable = true
            setOnClickListener { onOpen(url) }
            setOnLongClickListener { onOpenNew(url); true }
            val favC = Favicons.get(Util.host(url)) // S45
            if (favC != null) addView(ImageView(context).apply {
                setImageBitmap(favC)
                scaleType = ImageView.ScaleType.FIT_CENTER
                setPadding(dp(5), dp(5), dp(5), dp(5))
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(ColorUtils.blendARGB(context.getColor(R.color.mg_surface_variant), t, 0.30f))
                }
            }, LinearLayout.LayoutParams(dp(28), dp(28)).apply { marginEnd = dp(10) })
            else addView(TextView(context).apply {
                text = letter
                gravity = Gravity.CENTER
                textSize = 12f
                setTextColor(context.getColor(R.color.mg_text))
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(ColorUtils.blendARGB(context.getColor(R.color.mg_surface_variant), t, 0.30f))
                }
            }, LinearLayout.LayoutParams(dp(28), dp(28)).apply { marginEnd = dp(10) })
            addView(TextView(context).apply {
                text = name
                setTextColor(context.getColor(R.color.mg_text))
                textSize = 13f
                maxLines = 1
                maxWidth = dp(150)
                ellipsize = TextUtils.TruncateAt.END
            })
        }
    }

    /** S34: quiet line at the bottom when the ad blocker stopped something in this run. */
    private fun blockedLine(): View? {
        val n = AdBlock.blocked
        if (!AdBlock.enabled || n <= 0) return null
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            addView(ImageView(context).apply {
                setImageResource(R.drawable.ic_shield)
                setColorFilter(context.getColor(R.color.mg_text_dim))
            }, LinearLayout.LayoutParams(dp(16), dp(16)).apply { marginEnd = dp(8) })
            addView(TextView(context).apply {
                text = "$n ads and trackers blocked"
                textSize = 12f
                setTextColor(context.getColor(R.color.mg_text_dim))
            })
        }
    }

    /** S28: search pill under the wordmark. Tap = focus the address bar. */
    private fun searchPill(): View = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(16), 0, dp(18), 0)
        background = squircle(context.getColor(R.color.mg_surface), context.getColor(R.color.mg_outline), false).apply { cornerRadius = dp(18).toFloat() }
        isClickable = true
        isFocusable = true
        setOnClickListener { onSearch?.invoke() }
        addView(ImageView(context).apply {
            setImageResource(R.drawable.ic_search)
            setColorFilter(context.getColor(R.color.mg_text_dim))
        }, LinearLayout.LayoutParams(dp(20), dp(20)).apply { marginEnd = dp(12) })
        addView(TextView(context).apply {
            text = "Search or type address"
            textSize = 14f
            setTextColor(context.getColor(R.color.mg_text_dim))
        })
    }

    private fun squircle(fill: Int, stroke: Int, dashed: Boolean) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(16).toFloat()
        setColor(fill)
        if (dashed) setStroke(dp(1), stroke, dp(4).toFloat(), dp(3).toFloat()) else setStroke(dp(1), stroke)
    }

    private fun letterBox(letter: String, seed: String): View {
        val t = tint(seed) // S34: soft per-site colour
        val fav = Favicons.get(seed) // S45: real site icon when we have one
        if (fav != null) {
            return ImageView(context).apply {
                setImageBitmap(fav)
                scaleType = ImageView.ScaleType.FIT_CENTER
                setPadding(dp(14), dp(14), dp(14), dp(14))
                val surf = context.getColor(R.color.mg_surface)
                background = GradientDrawable(
                    GradientDrawable.Orientation.TL_BR,
                    intArrayOf(ColorUtils.blendARGB(surf, t, 0.26f), ColorUtils.blendARGB(surf, t, 0.07f))
                ).apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dp(20).toFloat()
                    setStroke(dp(1), ColorUtils.blendARGB(context.getColor(R.color.mg_outline), t, 0.30f))
                }
            }
        }
        return TextView(context).apply {
            text = letter
            gravity = Gravity.CENTER
            setTextColor(context.getColor(R.color.mg_text))
            textSize = 22f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            val surf = context.getColor(R.color.mg_surface)
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(ColorUtils.blendARGB(surf, t, 0.26f), ColorUtils.blendARGB(surf, t, 0.07f))
            ).apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(20).toFloat()
                setStroke(dp(1), ColorUtils.blendARGB(context.getColor(R.color.mg_outline), t, 0.30f))
            }
        }
    }

    private fun addBox(): View = FrameLayout(context).apply {
        background = squircle(0, context.getColor(R.color.mg_outline), true)
        addView(ImageView(context).apply {
            setImageResource(R.drawable.ic_add)
            setColorFilter(context.getColor(R.color.mg_text_dim))
        }, LayoutParams(dp(22), dp(22), Gravity.CENTER))
    }

    private fun cell(icon: View, label: String): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(dp(3), dp(7), dp(3), dp(7))
        isClickable = true
        isFocusable = true
        setOnTouchListener { v, e -> // S28: soft press
            when (e.action) {
                android.view.MotionEvent.ACTION_DOWN -> v.animate().scaleX(0.94f).scaleY(0.94f).setDuration(90).start()
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> v.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
            }
            false
        }
        addView(icon)
        addView(TextView(context).apply {
            text = label
            setTextColor(context.getColor(R.color.mg_text_dim))
            textSize = 12f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, 0)
        })
    }

    private fun tile(title: String, url: String): View {
        val name = title.ifBlank { Util.host(url) }
        val letter = name.firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()?.toString() ?: "?"
        val box = FrameLayout(context)
        box.addView(letterBox(letter, Util.host(url).ifBlank { name }), FrameLayout.LayoutParams(dp(56), dp(56), Gravity.CENTER))
        if (editing && folder == null) addBadge(box) { Bookmarks.removeHome(url); refresh() } // S28: unpin only
        val c = cell(box, name)
        box.layoutParams = LinearLayout.LayoutParams(dp(64), dp(60))
        c.setOnClickListener { if (!editing) onOpen(url) }
        c.setOnLongClickListener {
            val labels = ArrayList<String>()
            val acts = ArrayList<() -> Unit>()
            labels.add("Open in new tab"); acts.add { onOpenNew(url) }
            if (folder == null) {
                labels.add("Remove from home"); acts.add { Bookmarks.removeHome(url); refresh() }
            } else {
                labels.add("Move out of folder"); acts.add { Bookmarks.setFolder(url, ""); refresh() }
                labels.add("Remove bookmark"); acts.add { Bookmarks.removeBookmark(url); refresh() }
            }
            MaterialAlertDialogBuilder(context)
                .setTitle(name)
                .setItems(labels.toTypedArray()) { _, which -> acts[which]() }
                .show()
            true
        }
        return c
    }

    private fun addBadge(box: FrameLayout, onTap: () -> Unit) {
        box.addView(ImageView(context).apply {
            setImageResource(R.drawable.ic_close)
            setColorFilter(context.getColor(R.color.mg_on_primary))
            setPadding(dp(4), dp(4), dp(4), dp(4))
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(context.getColor(R.color.mg_primary)) }
            setOnClickListener { onTap() }
        }, FrameLayout.LayoutParams(dp(22), dp(22), Gravity.TOP or Gravity.END))
    }

    /** S26: folder tile (opens the folder here). Edit mode: badge deletes the folder, its bookmarks go back to the top level. */
    private fun folderTile(name: String): View {
        val box = FrameLayout(context)
        val inner = FrameLayout(context).apply {
            background = squircle(context.getColor(R.color.mg_surface), context.getColor(R.color.mg_outline), false)
            addView(ImageView(context).apply {
                setImageResource(R.drawable.ic_folder)
                setColorFilter(context.getColor(R.color.mg_text_dim))
            }, LayoutParams(dp(26), dp(26), Gravity.CENTER))
        }
        box.addView(inner, FrameLayout.LayoutParams(dp(56), dp(56), Gravity.CENTER))
        if (editing) addBadge(box) { Bookmarks.removeHomeFolder(name); refresh() } // S28: unpin only
        val c = cell(box, Bookmarks.shortName(name)) // 0.0.329: sub folder shows its own name
        box.layoutParams = LinearLayout.LayoutParams(dp(64), dp(60))
        c.setOnClickListener { if (!editing) { folder = name; rebuild() } }
        return c
    }

    private fun addTile(): View {
        val b = addBox()
        val c = cell(b, "Add")
        b.layoutParams = LinearLayout.LayoutParams(dp(56), dp(56))
        c.setOnClickListener { if (folder == null) chooseAdd() else askAdd() }
        return c
    }

    /** S28: top level: pick from bookmarks (many at once) or type a new address. */
    private fun chooseAdd() {
        MaterialAlertDialogBuilder(context)
            .setTitle("Add to home")
            .setItems(arrayOf("From bookmarks", "New address")) { _, which -> if (which == 0) pickFromBookmarks() else askAdd() }
            .show()
    }

    private fun pickFromBookmarks() {
        val labels = ArrayList<String>()
        val acts = ArrayList<() -> Unit>()
        for (f in Bookmarks.folders()) if (Bookmarks.home().none { it.folder == f }) {
            labels.add("Folder: $f"); acts.add { Bookmarks.addHomeFolder(f) }
        }
        for (b in Bookmarks.bookmarks()) if (!Bookmarks.isHome(b.url)) {
            labels.add(b.title.ifBlank { Util.host(b.url) }); acts.add { Bookmarks.addHome(b.title, b.url) }
        }
        if (labels.isEmpty()) { Util.toast(context, "Nothing to add"); return }
        val checked = BooleanArray(labels.size)
        MaterialAlertDialogBuilder(context)
            .setTitle("Add to home")
            .setMultiChoiceItems(labels.toTypedArray(), checked) { _, i, on -> checked[i] = on }
            .setPositiveButton("Add") { _, _ ->
                for (i in labels.indices) if (checked[i]) acts[i]()
                refresh()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun askAdd() {
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), 0)
        }
        val name = EditText(context).apply { hint = "Name"; setSingleLine(); inputType = InputType.TYPE_CLASS_TEXT }
        val url = EditText(context).apply {
            hint = "Address (example.com)"
            setSingleLine()
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        }
        box.addView(name, LinearLayout.LayoutParams(MATCH, WRAP))
        box.addView(url, LinearLayout.LayoutParams(MATCH, WRAP))
        MaterialAlertDialogBuilder(context)
            .setTitle("Add to speed dial")
            .setView(box)
            .setPositiveButton("Add") { _, _ ->
                val u = Util.toUrlOrSearch(url.text.toString())
                if (!u.startsWith("http", true)) Util.toast(context, "Enter a web address")
                else {
                    Bookmarks.addBookmark(name.text.toString().trim(), u)
                    val fo = folder
                    if (fo != null) Bookmarks.setFolder(u, fo) // S26: add inside the open folder
                    else Bookmarks.addHome(name.text.toString().trim(), u) // S28: pin to home
                    refresh()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
