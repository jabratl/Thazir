package com.jabra.mgb

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.widget.doAfterTextChanged
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * V4 + V5: vault lists for bookmarks ("bm") and history ("hist"), plus the move / copy core.
 *
 * UI: one full-screen Dialog on top of the vault dialog. Pages: list (search, folder chips, multi-select, open in tab, restore, delete)
 * and picker ("Add from browser": checkboxes, search, chips, Move / Copy). History list is grouped by day.
 * Core (BLOCKING, call on a background thread; reused by later steps and by any BookmarksScreen hook):
 *   addToVault(ctx, type, sources, move)  -> add to the vault index, flush, read back, THEN remove originals (only when move).
 *   restoreBookmarks(ctx, ids, keep)      -> add back to the browser, verify with Bookmarks.isBookmarked, THEN remove from vault.
 * Rules: duplicates are merged (bookmarks by url, history by url+time). Home pins are never touched. Nothing is logged. Items stay
 * inside the encrypted index (no blobs). Item JSON: id,type,title,url,time,folder,size,mime,blob,orig (same as the V2 contract).
 * History restore into the browser is not offered (Bookmarks.addHistory cannot set the visit time): that is V10.
 */
object VaultItems {
    private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
    private const val PAGE = 150

    /** Neutral copy of a browser entry, so this file does not depend on the exact Bookmarks item classes. */
    class E(val title: String, val url: String, val time: Long, val folder: String = "", val ref: String = "", val size: Long = 0L)
    /** An entry plus the action that removes the ORIGINAL from the browser (run only after the vault copy is verified). */
    class Src(val e: E, val remove: () -> Unit)
    class Result(
        val ok: Boolean, val added: Int = 0, val dup: Int = 0, val removed: Int = 0, val msg: String = "",
        val newIds: List<String> = emptyList(), val moved: List<E> = emptyList()
    )

    private val main = Handler(Looper.getMainLooper())
    private val bg = Executors.newSingleThreadExecutor()

    // ---------- core (blocking) ----------

    fun bmSources(): List<Src> = Bookmarks.bookmarks().map { b -> Src(E(b.title, b.url, b.time.toLong(), b.folder), { Bookmarks.removeBookmark(b.url) }) }

    fun histSources(): List<Src> = Bookmarks.history().map { h -> Src(E(h.title, h.url, h.time.toLong(), ""), { Bookmarks.removeHistory(h.time, h.url) }) }

    private fun keyOf(type: String, url: String, time: Long) = if (type == "bm") url else "$time|$url"

    /** Flush the vault index and confirm that it is on disk (VaultStore.flush reads the file back before swapping). */
    internal fun sealed(c: Context): Boolean {
        VaultStore.flush(c)
        if (!VaultStore.isSaved()) VaultStore.flush(c)
        return VaultStore.isUnlocked() && VaultStore.isSaved()
    }

    fun addToVault(c: Context, type: String, src: List<Src>, move: Boolean): Result {
        val app = c.applicationContext
        if (!VaultStore.isUnlocked()) return Result(false, msg = "The vault is locked. Nothing was changed.")
        val have = HashSet<String>()
        for (o in VaultStore.items(type)) have.add(keyOf(type, o.optString("url"), o.optLong("time")))
        val good = src.filter { it.e.url.isNotEmpty() }
        val ids = ArrayList<String>()
        var dup = 0
        for (s in good) {
            val e = s.e
            if (!have.add(keyOf(type, e.url, e.time))) { dup++; continue }
            if (type == "bm" && e.folder.isNotEmpty()) VaultStore.addFolder(app, e.folder)
            val o = JSONObject().put("type", type).put("title", e.title).put("url", e.url).put("time", e.time).put("folder", e.folder)
                .put("size", 0).put("mime", "").put("blob", "").put("orig", JSONObject())
            val id = VaultStore.addItem(app, o)
            if (id.isEmpty()) return Result(false, msg = "The vault locked while saving. Nothing was removed from the browser.")
            ids.add(id)
        }
        if (!sealed(app)) return Result(false, msg = "The vault could not save its list. Nothing was removed from the browser.")
        val now = HashSet<String>()
        for (o in VaultStore.items(type)) now.add(keyOf(type, o.optString("url"), o.optLong("time")))
        if (!good.all { now.contains(keyOf(type, it.e.url, it.e.time)) }) return Result(false, msg = "Could not verify the vault copy. Nothing was removed from the browser.")
        var removed = 0
        val moved = ArrayList<E>()
        if (move) for (s in good) { try { s.remove(); removed++; moved.add(s.e) } catch (x: Exception) { } }
        return Result(true, ids.size, dup, removed, newIds = ids, moved = moved)
    }

    /** User text for a finished add (also used by BookmarksScreen). */
    fun describe(t: String, r: Result, move: Boolean): String {
        if (!r.ok) return r.msg
        val n = r.added + r.dup
        val what = plural(t, n)
        return (if (move) "Moved " else "Copied ") + what + " to the vault" + (if (r.dup > 0) " (${r.dup} already there)" else "")
    }

    private fun ensureBookmark(e: E) {
        if (!Bookmarks.isBookmarked(e.url)) Bookmarks.addBookmark(e.title, e.url)
        if (e.folder.isNotEmpty()) {
            if (Bookmarks.folders().none { it.toString() == e.folder }) Bookmarks.addFolder(e.folder)
            Bookmarks.setFolder(e.url, e.folder)
        }
    }

    private fun toE(o: JSONObject) = E(o.optString("title"), o.optString("url"), o.optLong("time"), o.optString("folder"))

    /** Puts vault bookmarks back into the browser. keep=false removes them from the vault afterwards (only the verified ones). */
    fun restoreBookmarks(c: Context, ids: Collection<String>, keep: Boolean): Result {
        val app = c.applicationContext
        if (!VaultStore.isUnlocked()) return Result(false, msg = "The vault is locked. Nothing was changed.")
        val done = ArrayList<String>()
        for (id in ids) {
            val o = VaultStore.item(id) ?: continue
            val e = toE(o)
            if (e.url.isEmpty()) continue
            try { ensureBookmark(e) } catch (x: Exception) { continue }
            if (Bookmarks.isBookmarked(e.url)) done.add(id)
        }
        if (done.size < ids.size && done.isEmpty()) return Result(false, msg = "Could not restore. Nothing was removed from the vault.")
        if (!keep) { for (id in done) VaultStore.removeItem(app, id, false); if (!sealed(app)) return Result(true, done.size, msg = "Restored, but the vault list could not update. Repeat to clean up.") }
        return Result(true, done.size, removed = if (keep) 0 else done.size)
    }

    /** Called from VaultStore.onAppStop: when the vault is open and "Send history to vault" is on, browser history moves into the vault. */
    fun sweepHistory(c: Context) {
        val app = c.applicationContext
        if (!VaultStore.isUnlocked() || !VaultStore.histAuto(app)) return
        bg.execute { try { if (VaultStore.isUnlocked()) { val s = histSources(); if (s.isNotEmpty()) addToVault(app, "hist", s, true) } } catch (x: Exception) { } }
    }

    // ---------- UI state (kept in the object so rotation just rebuilds the dialog) ----------

    private var dlg: Dialog? = null
    private var host: FrameLayout? = null
    private var act: Activity? = null
    private var type = "bm"
    private var onClose: (() -> Unit)? = null
    private var snack: View? = null

    private var query = ""
    private var chip = 0
    private var chipName = "" // 0.0.219: folder the chip points to, so the chip follows the folder (not a stale position)
    private var selecting = false
    private var showHidden = false // V16: hidden bookmarks / folders stay out of the list until this is ON (reset on every open)
    private val sel = LinkedHashSet<String>()
    private var limit = PAGE

    private var pQuery = ""
    private var pChip = 0
    private val pSel = LinkedHashSet<String>()
    private var pLimit = PAGE
    private var pSrc: List<Src> = emptyList()

    private fun dp(a: Context, v: Int) = Util.dp(a, v)
    private fun col(a: Context, id: Int) = a.getColor(id)
    private fun lp(top: Int = 0, w: Int = MATCH, h: Int = WRAP) = LinearLayout.LayoutParams(w, h).apply { topMargin = top }

    private fun title() = when (type) { "bm" -> "Bookmarks"; "page" -> "Saved pages"; "file" -> "Files"; else -> "History" }
    private fun plural(n: Int) = plural(type, n)
    private fun plural(t: String, n: Int) = when (t) {
        "bm" -> if (n == 1) "1 bookmark" else "$n bookmarks"
        "page" -> if (n == 1) "1 saved page" else "$n saved pages"
        "file" -> if (n == 1) "1 file" else "$n files"
        else -> if (n == 1) "1 history entry" else "$n history entries"
    }

    /** Opens the list for "bm" or "hist". onClose runs when the dialog goes away (VaultScreen refreshes its tile counts). */
    fun open(a: Activity, t: String, closed: () -> Unit) {
        if (a.isFinishing || a.isDestroyed || dlg?.isShowing == true) return
        if (!VaultStore.isUnlocked()) return
        act = a; type = t; onClose = closed
        query = ""; chip = 0; selecting = false; sel.clear(); limit = PAGE; showHidden = false
        val d = object : Dialog(a, android.R.style.Theme_Black_NoTitleBar_Fullscreen) {
            @Suppress("OVERRIDE_DEPRECATION")
            override fun onBackPressed() { back() }
        }
        val h = FrameLayout(a)
        h.setBackgroundColor(col(a, R.color.mg_bg))
        host = h
        d.setContentView(h, ViewGroup.LayoutParams(MATCH, MATCH))
        d.window?.apply {
            setBackgroundDrawable(ColorDrawable(col(a, R.color.mg_bg)))
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            if (VaultStore.hideRecents(a)) setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        }
        d.setOnDismissListener { if (dlg === d) { dlg = null; host = null; snack = null; val f = onClose; onClose = null; f?.invoke() } }
        dlg = d
        try { d.show() } catch (e: Exception) { dlg = null; return }
        listPage()
    }

    /** VaultScreen.refresh (MainActivity.onStart) calls this: a locked vault must not leave its list on screen. */
    fun refresh() {
        if (dlg?.isShowing == true && !VaultStore.isUnlocked()) dlg?.dismiss()
    }

    fun closeAll() { dlg?.dismiss() }

    private var page = "list"
    private fun back() { if (page == "pick") listPage() else dlg?.dismiss() }

    // ---------- shared view pieces ----------

    private class Ui(val head: LinearLayout, val list: LinearLayout, val foot: LinearLayout)

    private fun shell(a: Activity): Ui {
        val h = host ?: throw IllegalStateException("no host")
        h.removeAllViews(); snack = null
        val root = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        val head = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(a, 20), dp(a, 8), dp(a, 20), dp(a, 4)) }
        val list = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(a, 20), dp(a, 4), dp(a, 20), dp(a, 24)) }
        val sv = ScrollView(a)
        sv.addView(list, FrameLayout.LayoutParams(MATCH, WRAP))
        val foot = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(a, 20), dp(a, 8), dp(a, 20), dp(a, 12)); setBackgroundColor(col(a, R.color.mg_bg)) }
        root.addView(head, LinearLayout.LayoutParams(MATCH, WRAP))
        root.addView(sv, LinearLayout.LayoutParams(MATCH, 0, 1f))
        root.addView(foot, LinearLayout.LayoutParams(MATCH, WRAP))
        val w = minOf(a.resources.displayMetrics.widthPixels, dp(a, 560))
        h.addView(root, FrameLayout.LayoutParams(w, MATCH, Gravity.CENTER_HORIZONTAL))
        return Ui(head, list, foot)
    }

    private fun icon(a: Activity, res: Int, desc: String, tint: Int = R.color.mg_text, onClick: () -> Unit) = ImageView(a).apply {
        setImageResource(res); setColorFilter(col(a, tint)); setPadding(dp(a, 10), dp(a, 10), dp(a, 10), dp(a, 10))
        contentDescription = desc; isClickable = true; isFocusable = true; setOnClickListener { onClick() }
    }

    private fun topBar(a: Activity, text: String, right: List<View>): LinearLayout {
        val top = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        top.addView(icon(a, R.drawable.ic_back, "Back") { back() }, LinearLayout.LayoutParams(dp(a, 44), dp(a, 44)))
        top.addView(VaultScreen.tv(a, text, 20f, R.color.mg_text, true).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }, LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginStart = dp(a, 6) })
        for (v in right) top.addView(v, LinearLayout.LayoutParams(dp(a, 44), dp(a, 44)))
        return top
    }

    private fun search(a: Activity, hint: String, start: String, change: (String) -> Unit): EditText {
        val f = VaultScreen.field(a, hint, false)
        f.inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        f.setText(start)
        f.doAfterTextChanged { change(it?.toString() ?: "") }
        return f
    }

    private fun chips(a: Activity, labels: List<String>, selected: Int, pick: (Int) -> Unit): View {
        val hs = HorizontalScrollView(a).apply { isHorizontalScrollBarEnabled = false }
        val row = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(a, 4), 0, dp(a, 4)) }
        labels.forEachIndexed { i, l ->
            val on = i == selected
            val t = TextView(a).apply {
                text = l; setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13f); gravity = Gravity.CENTER; minHeight = dp(a, 40)
                setPadding(dp(a, 14), 0, dp(a, 14), 0); maxLines = 1
                setTextColor(col(a, if (on) R.color.mg_on_primary else R.color.mg_text))
                background = GradientDrawable().apply {
                    cornerRadius = dp(a, 20).toFloat()
                    setColor(col(a, if (on) R.color.mg_primary else R.color.mg_surface)); setStroke(dp(a, 1), col(a, if (on) R.color.mg_primary else R.color.mg_outline))
                }
                isClickable = true; isFocusable = true; contentDescription = if (on) "$l, selected" else l
                setOnClickListener { pick(i) }
            }
            row.addView(t, LinearLayout.LayoutParams(WRAP, dp(a, 40)).apply { marginEnd = dp(a, 8) })
        }
        hs.addView(row)
        return hs
    }

    private fun check(a: Activity, on: Boolean): View = ImageView(a).apply {
        setPadding(dp(a, 3), dp(a, 3), dp(a, 3), dp(a, 3))
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(if (on) col(a, R.color.mg_primary) else Color.TRANSPARENT); setStroke(dp(a, 2), col(a, if (on) R.color.mg_primary else R.color.mg_outline))
        }
        if (on) { setImageResource(R.drawable.ic_check); setColorFilter(col(a, R.color.mg_on_primary)) }
    }

    private fun header(a: Activity, text: String) = VaultScreen.tv(a, text.uppercase(), 11f, R.color.mg_primary).apply { typeface = Typeface.DEFAULT_BOLD; letterSpacing = 0.08f }

    /** One list row. checked == null: no check circle (normal mode); sealed: tiny lock chip on the right. */
    private fun row(a: Activity, title: String, sub: String, checked: Boolean?, lock: Boolean, click: () -> Unit, long: (() -> Unit)?): View {
        val r = LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; background = VaultScreen.box(a, 16)
            setPadding(dp(a, 14), dp(a, 10), dp(a, 12), dp(a, 10)); minimumHeight = dp(a, 56); isClickable = true; isFocusable = true
            contentDescription = if (sub.isEmpty()) title else "$title, $sub"
            setOnClickListener { click() }
            if (long != null) setOnLongClickListener { long(); true }
        }
        if (checked != null) r.addView(check(a, checked), LinearLayout.LayoutParams(dp(a, 24), dp(a, 24)).apply { marginEnd = dp(a, 12) })
        val tx = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        tx.addView(VaultScreen.tv(a, title, 15f, R.color.mg_text).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
        if (sub.isNotEmpty()) tx.addView(VaultScreen.tv(a, sub, 12f, R.color.mg_text_dim).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
        r.addView(tx, LinearLayout.LayoutParams(0, WRAP, 1f))
        if (lock) r.addView(ImageView(a).apply { setImageResource(R.drawable.ic_lock); setColorFilter(col(a, R.color.mg_text_dim)); importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO },
            LinearLayout.LayoutParams(dp(a, 14), dp(a, 14)).apply { marginStart = dp(a, 8) })
        return r
    }

    private fun empty(a: Activity, t: String) = VaultScreen.tv(a, t, 14f, R.color.mg_text_dim).apply { setLineSpacing(0f, 1.15f); setPadding(0, dp(a, 16), 0, 0) }

    private fun showMore(a: Activity, left: Int, more: () -> Unit) = VaultScreen.link(a, "Show more ($left left)") { more() }

    /** Undo-style bar at the bottom. Disappears after 7 s. action == null: plain message. */
    private fun bar(a: Activity, msg: String, action: String?, run: (() -> Unit)?) {
        val h = host ?: return
        snack?.let { h.removeView(it) }
        val b = LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; background = VaultScreen.box(a, 16, R.color.mg_surface_variant)
            setPadding(dp(a, 16), dp(a, 4), dp(a, 4), dp(a, 4)); accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        b.addView(VaultScreen.tv(a, msg, 14f, R.color.mg_text), LinearLayout.LayoutParams(0, WRAP, 1f))
        if (action != null && run != null) b.addView(VaultScreen.link(a, action) { if (snack === b) { h.removeView(b); snack = null }; run() }, LinearLayout.LayoutParams(WRAP, dp(a, 48)))
        val w = minOf(a.resources.displayMetrics.widthPixels - dp(a, 32), dp(a, 528))
        h.addView(b, FrameLayout.LayoutParams(w, WRAP, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dp(a, 20) })
        snack = b
        main.postDelayed({ if (snack === b) { host?.removeView(b); snack = null } }, 7000)
    }

    // ---------- list page ----------

    private fun dayKey(t: Long): Long { val c = Calendar.getInstance(); c.timeInMillis = t; c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 0); c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0); return c.timeInMillis }

    private fun dayLabel(t: Long): String {
        val d = dayKey(t); val today = dayKey(System.currentTimeMillis())
        return when (d) {
            today -> "Today"
            dayKey(today - 1) -> "Yesterday"
            else -> SimpleDateFormat("EEE d MMM yyyy", Locale.getDefault()).format(Date(t))
        }
    }

    private fun clock(t: Long) = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(t))

    /** 0.0.219: the folder chip is a position in bmFolders(). After Restore / Delete / Hide the folder can vanish or move: re-find it by name, else go back to All. */
    private fun fixChip() {
        if (chip <= 0) return
        if (type != "bm") { chip = 0; return }
        val i = bmFolders().indexOf(chipName)
        chip = if (i >= 0) i + 1 else 0
    }

    private fun vaultItems(): List<JSONObject> {
        fixChip()
        val q = query.trim().lowercase()
        var l = shownAll().sortedByDescending { it.optLong("time") }
        if (type == "bm" && chip > 0) { val f = bmFolders().getOrNull(chip - 1); if (f != null) l = l.filter { Bookmarks.under(it.optString("folder"), f) } }
        if (q.isNotEmpty()) l = l.filter { it.optString("title").lowercase().contains(q) || it.optString("url").lowercase().contains(q) || it.optString("folder").lowercase().contains(q) }
        return l
    }

    /** 0.0.328: folders are paths "A/B"; a parent folder is listed even when only its sub folders hold bookmarks. */
    private fun bmFolders(): List<String> = shownAll().map { it.optString("folder") }.filter { it.isNotEmpty() }.flatMap { listOf(it) + Bookmarks.ancestors(it) }.distinct().sorted()

    /** 0.0.328: a folder is hidden when it, or any folder above it, is hidden. */
    private fun folderHid(f: String): Boolean { if (f.isEmpty()) return false; val hf = VaultStore.hiddenFolders(); return hf.contains(f) || Bookmarks.ancestors(f).any { hf.contains(it) } }

    // ---------- V16: hide + selection helpers ----------

    /** A bookmark is hidden by its own flag or because its folder is hidden. */
    private fun isHid(o: JSONObject) = o.optBoolean("hidden") || folderHid(o.optString("folder"))

    /** Items of this list that are allowed on screen now (hidden ones only when Show hidden is ON). Only bookmarks can be hidden. */
    private fun shownAll(): List<JSONObject> {
        val l = VaultStore.items(type)
        return if (type == "bm" && !showHidden) l.filter { !isHid(it) } else l
    }

    private fun hiddenCount(): Int = if (type == "bm") VaultStore.items("bm").count { isHid(it) } else 0

    /** V17: Hide button. Selection with folders = ask: only the selected bookmarks, or the bookmarks and their folders. */
    private fun onHideClick(a: Activity, hide: Boolean) {
        if (!hide) { hideSelected(a, false); return }
        val fo = sel.mapNotNull { VaultStore.item(it) }.map { it.optString("folder") }.filter { it.isNotEmpty() }.distinct()
        if (fo.isEmpty()) { hideSelected(a, true); return }
        val total = VaultStore.items("bm").count { b -> fo.any { Bookmarks.under(b.optString("folder"), it) } }
        MaterialAlertDialogBuilder(a).setTitle("Hide")
            .setItems(arrayOf("Only the selected bookmarks", "Selected bookmarks and their " + (if (fo.size == 1) "folder" else "${fo.size} folders") + " ($total bookmarks in total)")) { _, i ->
                hideSelected(a, true, i == 1)
            }.setNegativeButton("Cancel", null).show()
    }

    private fun hideSelected(a: Activity, hide: Boolean, withFolders: Boolean = false) {
        val ids = ArrayList(sel)
        if (ids.isEmpty()) return
        val folders = HashSet<String>()
        val newlyHidden = HashSet<String>() // V17: folders this action hid (Undo opens only these)
        var n = 0
        for (id in ids) {
            val o = VaultStore.item(id) ?: continue
            val fo = o.optString("folder")
            if (!hide && fo.isNotEmpty()) { folders.add(fo); folders.addAll(Bookmarks.ancestors(fo)); VaultStore.hiddenFolders().filter { Bookmarks.under(it, fo) }.let { folders.addAll(it) } } // 0.0.328
            if (hide && withFolders && fo.isNotEmpty() && !VaultStore.hiddenFolders().contains(fo) && newlyHidden.add(fo)) VaultStore.setFolderHidden(a, fo, true)
            if (o.optBoolean("hidden") != hide) { o.put("hidden", hide); VaultStore.updateItem(a, o) }
            n++
        }
        val wasHid = if (hide) emptySet() else VaultStore.hiddenFolders()
        if (!hide) for (f in folders) VaultStore.setFolderHidden(a, f, false) // unhiding a bookmark also opens its folder (and the folders above / below it)
        selecting = false; sel.clear(); chip = 0; listPage()
        bar(a, (if (hide) "Hid " else "Unhid ") + plural(n), "Undo") {
            for (id in ids) { val o = VaultStore.item(id) ?: continue; o.put("hidden", !hide); VaultStore.updateItem(a, o) }
            for (f in folders) if (wasHid.contains(f)) VaultStore.setFolderHidden(a, f, true)
            for (f in newlyHidden) VaultStore.setFolderHidden(a, f, false)
            chip = 0; listPage()
        }
    }

    /** 0.0.219: one tap brings every hidden bookmark and folder back. Undo hides exactly those again. */
    private fun unhideAll(a: Activity) {
        val ids = VaultStore.items("bm").filter { it.optBoolean("hidden") }.map { it.optString("id") }
        val folders = VaultStore.hiddenFolders().toList()
        val n = hiddenCount()
        if (ids.isEmpty() && folders.isEmpty()) return
        for (id in ids) { val o = VaultStore.item(id) ?: continue; o.put("hidden", false); VaultStore.updateItem(a, o) }
        for (f in folders) VaultStore.setFolderHidden(a, f, false)
        chip = 0; listPage()
        bar(a, "Unhid all ($n)", "Undo") {
            for (id in ids) { val o = VaultStore.item(id) ?: continue; o.put("hidden", true); VaultStore.updateItem(a, o) }
            for (f in folders) VaultStore.setFolderHidden(a, f, true)
            chip = 0; listPage()
        }
    }

    /** 0.0.328: Hide / Unhide a folder = the folder, all its sub folders and every bookmark under it. Unhide also opens the folders above it. Undo puts the exact old state back. */
    private fun hideFolder(a: Activity, folder: String, hide: Boolean) {
        val items = VaultStore.items("bm").filter { Bookmarks.under(it.optString("folder"), folder) }
        val oldItem = items.associate { it.optString("id") to it.optBoolean("hidden") }
        val oldFol = VaultStore.hiddenFolders().filter { Bookmarks.under(it, folder) || Bookmarks.ancestors(folder).contains(it) }.toSet()
        val subFol = (VaultStore.hiddenFolders().filter { Bookmarks.under(it, folder) } + folder).toSet()
        val upFol = Bookmarks.ancestors(folder)
        for (o in items) if (o.optBoolean("hidden") != hide) { o.put("hidden", hide); VaultStore.updateItem(a, o) }
        if (hide) VaultStore.setFolderHidden(a, folder, true)
        else { for (f in subFol + upFol) VaultStore.setFolderHidden(a, f, false) }
        chip = 0; listPage()
        bar(a, (if (hide) "Folder hidden: " else "Folder shown: ") + folder, "Undo") {
            for ((id, was) in oldItem) { val o = VaultStore.item(id) ?: continue; if (o.optBoolean("hidden") != was) { o.put("hidden", was); VaultStore.updateItem(a, o) } }
            for (f in subFol + upFol) VaultStore.setFolderHidden(a, f, oldFol.contains(f))
            chip = 0; listPage()
        }
    }

    private fun selectFolderDialog(a: Activity) {
        val fs = bmFolders()
        if (fs.isEmpty()) { Util.toast(a, "No folders here"); return }
        // V17: tick folders one by one (a ticked folder selects all its bookmarks, un-ticking a fully selected folder removes them), or All folders
        val shown = shownAll()
        fun idsOf(f: String) = shown.filter { Bookmarks.under(it.optString("folder"), f) }.map { it.optString("id") }
        val checked = BooleanArray(fs.size) { val l = idsOf(fs[it]); l.isNotEmpty() && sel.containsAll(l) }
        val was = checked.copyOf()
        MaterialAlertDialogBuilder(a).setTitle("Select folders")
            .setMultiChoiceItems(fs.map { "$it (${idsOf(it).size})" }.toTypedArray(), checked) { _, i, on -> checked[i] = on }
            .setPositiveButton("Apply") { _, _ ->
                for (i in fs.indices) { val l = idsOf(fs[i]); if (checked[i]) sel.addAll(l) else if (was[i]) sel.removeAll(l.toSet()) }
                selecting = sel.isNotEmpty(); listPage()
            }
            .setNeutralButton("All folders") { _, _ -> for (f in fs) sel.addAll(idsOf(f)); selecting = sel.isNotEmpty(); listPage() }
            .setNegativeButton("Cancel", null).show()
    }

    /** All / None / Invert / Folder... shown under the top bar while selecting. */
    private fun selectTools(a: Activity): View {
        val r = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        fun add(t: String, run: () -> Unit) = r.addView(VaultScreen.link(a, t) { run() }, LinearLayout.LayoutParams(WRAP, dp(a, 44)))
        add("All") { sel.addAll(vaultItems().map { it.optString("id") }); listPage() }
        add("None") { sel.clear(); listPage() }
        add("Invert") { val shown = vaultItems().map { it.optString("id") }.toSet(); val keep = shown.filter { !sel.contains(it) }; sel.clear(); sel.addAll(keep); listPage() }
        if (type == "bm" && bmFolders().isNotEmpty()) add("Folders...") { selectFolderDialog(a) }
        return r
    }

    private fun listPage() {
        val a = act ?: return
        page = "list"
        fixChip()
        val ui = shell(a)
        val info = VaultScreen.tv(a, "", 12f, R.color.mg_text_dim)
        if (selecting) {
            ui.head.addView(topBar(a, "${sel.size} selected", listOf(
                icon(a, R.drawable.ic_list, "Select all shown") { sel.addAll(vaultItems().map { it.optString("id") }); listPage() },
                icon(a, R.drawable.ic_close, "Cancel selection") { selecting = false; sel.clear(); listPage() })), lp())
        } else {
            ui.head.addView(topBar(a, title(), listOf(icon(a, R.drawable.ic_add, if (type == "file") "Add from downloads" else "Add from browser", R.color.mg_primary) { pick(a) })), lp())
        }
        ui.head.addView(search(a, when (type) { "bm" -> "Search bookmarks"; "page" -> "Search saved pages"; "file" -> "Search files"; else -> "Search history" }, query) { query = it; limit = PAGE; fill(a, ui, info) }, lp(dp(a, 8), MATCH, dp(a, 48)))
        if (selecting) ui.head.addView(selectTools(a), lp(dp(a, 2)))
        if (type == "bm" && bmFolders().isNotEmpty()) {
            val hf = VaultStore.hiddenFolders()
            ui.head.addView(chips(a, listOf("All") + bmFolders().map { if (folderHid(it)) "$it (hidden)" else it }, chip) { chip = it; chipName = bmFolders().getOrNull(it - 1) ?: ""; limit = PAGE; listPage() }, lp(dp(a, 4)))
        }
        if (type == "bm" && !selecting) { // V16: hide controls
            val hc = hiddenCount()
            val r = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            if (hc > 0 || showHidden) r.addView(VaultScreen.link(a, if (showHidden) "Hide hidden again" else "Show hidden ($hc)") { showHidden = !showHidden; chip = 0; listPage() }, LinearLayout.LayoutParams(WRAP, dp(a, 44)))
            if (showHidden && hc > 0 && chip == 0) r.addView(VaultScreen.link(a, "Unhide all ($hc)") { unhideAll(a) }, LinearLayout.LayoutParams(WRAP, dp(a, 44)))
            if (chip > 0) {
                val f = bmFolders().getOrNull(chip - 1)
                if (f != null) {
                    val hidden = folderHid(f)
                    r.addView(VaultScreen.link(a, if (hidden) "Unhide folder" else "Hide folder") { hideFolder(a, f, !hidden) }, LinearLayout.LayoutParams(WRAP, dp(a, 44)))
                }
            }
            if (r.childCount > 0) ui.head.addView(r, lp())
        }
        if (type == "hist") {
            ui.head.addView(VaultScreen.switchRow(a, "Send history to vault", "While the vault is open, new history moves here when you leave the app. Off by default.", VaultStore.histAuto(a)) { on ->
                VaultStore.setHistAuto(a, on)
                if (on) sweepHistory(a)
            }.also { it.background = VaultScreen.box(a) }, lp(dp(a, 4)))
        }
        ui.head.addView(info, lp(dp(a, 6)))
        fill(a, ui, info)
        foot(a, ui)
    }

    private fun foot(a: Activity, ui: Ui) {
        ui.foot.removeAllViews()
        if (!selecting) { ui.foot.visibility = View.GONE; return }
        ui.foot.visibility = View.VISIBLE
        val r = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL }
        val has = sel.isNotEmpty()
        if (type == "bm" || type == "page" || type == "file") {
            r.addView(VaultScreen.btn(a, if (type == "file") "Export" else "Restore", true) { restoreDialog(a) }.apply { isEnabled = has }, LinearLayout.LayoutParams(0, dp(a, 48), 1f).apply { marginEnd = dp(a, 6) })
        }
        if (type == "bm") {
            val allHid = has && sel.all { id -> VaultStore.item(id)?.let { isHid(it) } == true }
            r.addView(VaultScreen.btn(a, if (allHid) "Unhide" else "Hide", false) { onHideClick(a, !allHid) }.apply { isEnabled = has }, LinearLayout.LayoutParams(0, dp(a, 48), 1f).apply { marginStart = dp(a, 6) })
        }
        r.addView(VaultScreen.btn(a, "Delete", false) { deleteDialog(a) }.apply { isEnabled = has }, LinearLayout.LayoutParams(0, dp(a, 48), 1f).apply { marginStart = if (type == "bm" || type == "page" || type == "file") dp(a, 6) else 0 })
        ui.foot.addView(r, lp())
    }

    private fun fill(a: Activity, ui: Ui, info: TextView) {
        ui.list.removeAllViews()
        val all = shownAll().size
        val l = vaultItems()
        val hc = hiddenCount()
        info.text = (if (query.isBlank() && chip == 0) plural(all) else "${l.size} of ${plural(all)}") + (if (!showHidden && hc > 0) " - $hc hidden" else "") + " - sealed in " + VaultScreen.folderLabel(a)
        if (l.isEmpty()) {
            ui.list.addView(empty(a, if (all == 0 && hc > 0 && !showHidden) "Everything here is hidden. Tap Show hidden to see it." else if (all == 0) (when (type) { "bm" -> "No bookmarks in the vault yet. Tap + to add some from the browser."; "page" -> "No saved pages in the vault yet. Tap + to add some from Saved pages."; "file" -> "No files in the vault yet. Tap + to add some from your downloads."; else -> "No history in the vault yet. Tap + to add some from the browser." }) else "Nothing matches."))
            return
        }
        if (!selecting && all > 0) ui.list.addView(VaultScreen.tv(a, "Tap to open. Press and hold to select.", 12f, R.color.mg_text_dim), lp(dp(a, 4)))
        var lastDay = -1L
        for (o in l.take(limit)) {
            val id = o.optString("id")
            val t = o.optLong("time")
            if (type == "hist") {
                val dk = dayKey(t)
                if (dk != lastDay) { lastDay = dk; ui.list.addView(header(a, dayLabel(t)), lp(dp(a, 16))) }
            }
            val host = Util.host(o.optString("url"))
            val sub = when (type) {
                "hist" -> clock(t) + " - " + host
                "page" -> host + " - " + SimpleDateFormat("d MMM yyyy", Locale.getDefault()).format(Date(t)) + " - " + Util.fmtBytes(o.optLong("size"))
                "file" -> SimpleDateFormat("d MMM yyyy", Locale.getDefault()).format(Date(t)) + " - " + Util.fmtBytes(o.optLong("size")) + (if (host.isNotEmpty() && o.optString("url").startsWith("http")) " - " + host else "")
                else -> (if (showHidden && isHid(o)) "hidden - " else "") + (if (o.optString("folder").isNotEmpty()) o.optString("folder") + " - " else "") + host
            }
            val title = o.optString("title").ifBlank { host }
            ui.list.addView(row(a, title, sub, if (selecting) sel.contains(id) else null, true, {
                if (selecting) { if (!sel.add(id)) sel.remove(id); if (sel.isEmpty()) selecting = false; listPage() } else if (type == "page") VaultFiles.openPage(a, id) { VaultScreen.closeAll() } else if (type == "file") fileMenu(a, id) else openInTab(a, o.optString("url"))
            }, { if (!selecting) { selecting = true; sel.clear(); sel.add(id); listPage() } }), lp(dp(a, 8)))
        }
        if (l.size > limit) ui.list.addView(showMore(a, l.size - limit) { limit += PAGE; fill(a, ui, info) }, lp(dp(a, 8), MATCH, dp(a, 48)))
    }

    private fun openInTab(a: Activity, url: String) {
        val tabs = BookmarksScreen.tabsRef
        if (url.isEmpty() || tabs == null) { Util.toast(a, "Could not open it now"); return }
        tabs.newTab(url)
        VaultScreen.closeAll()
    }

    /** V7: tap on a vault file. Open / Share decrypt to a temp copy (wiped later), Export writes a readable copy, Delete removes it from the vault. */
    private fun fileMenu(a: Activity, id: String) {
        val o = VaultStore.item(id) ?: return
        val labels = if (VaultPlay.isMedia(o)) listOf("Play", "Play with temp copy", "Share", "Export to Downloads/Thazir", "Delete from vault") else listOf("Open", "Share", "Export to Downloads/Thazir", "Delete from vault")
        MaterialAlertDialogBuilder(a).setTitle(o.optString("title").ifBlank { "File" })
            .setItems(labels.toTypedArray()) { _, which ->
                when (labels[which]) {
                    "Play" -> VaultFiles.openFile(a, id, false)
                    "Play with temp copy" -> VaultFiles.openFile(a, id, false, true)
                    "Open" -> VaultFiles.openFile(a, id, false)
                    "Share" -> VaultFiles.openFile(a, id, true)
                    "Export to Downloads/Thazir" -> { sel.clear(); sel.add(id); restoreDialog(a) }
                    else -> { sel.clear(); sel.add(id); deleteDialog(a) }
                }
            }.setNegativeButton("Cancel", null).show()
    }

    private fun deleteDialog(a: Activity) {
        val n = sel.size
        if (n == 0) return
        MaterialAlertDialogBuilder(a).setTitle("Delete from vault?")
            .setMessage("${plural(n)} will be deleted from the vault for good. " + (if (type == "file") "Copies you exported are not affected." else "Items you already restored to the browser are not affected."))
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Delete") { _, _ ->
                val ids = ArrayList(sel)
                val t0 = type
                // 0.0.332: bookmarks / history entries are only index rows, so they can come back with Undo (pages / files are wiped with their blob)
                val keep = if (t0 == "page" || t0 == "file") emptyList() else try {
                    VaultStore.items(t0).filter { ids.contains(it.optString("id")) }.map { E(it.optString("title"), it.optString("url"), it.optLong("time"), it.optString("folder")) }
                } catch (x: Exception) { emptyList() }
                bg.execute {
                    for (id in ids) VaultStore.removeItem(a, id, type == "page" || type == "file")
                    val ok = sealed(a)
                    main.post { if (dlg?.isShowing != true) return@post; selecting = false; sel.clear(); listPage(); bar(a, if (ok) "Deleted ${plural(n)}" else "Deleted, but the vault list could not save yet. It will retry.", if (ok && keep.isNotEmpty()) "Undo" else null, if (ok && keep.isNotEmpty()) ({ undoDelete(a, t0, keep) }) else null) }
                }
            }.show()
    }

    /** 0.0.332: Undo of a vault delete of bookmarks / history: the same verified add path as Move to vault (no originals touched). */
    private fun undoDelete(a: Activity, t: String, keep: List<E>) {
        bg.execute {
            val r = try { addToVault(a, t, keep.map { Src(it, { }) }, false) } catch (x: Exception) { Result(false) }
            main.post {
                if (dlg?.isShowing == true) { listPage(); bar(a, if (r.ok) "Undone" else "Could not undo", null, null) }
                else Util.toast(a, if (r.ok) "Undone" else "Could not undo")
            }
        }
    }

    private fun restoreDialog(a: Activity) {
        val n = sel.size
        if (n == 0) return
        val fl = type == "file"
        MaterialAlertDialogBuilder(a).setTitle((if (fl) "Export " else "Restore ") + plural(n))
            .setItems(if (fl) arrayOf("Move out to Downloads/Thazir (remove from vault)", "Copy out to Downloads/Thazir (keep in vault)") else arrayOf("Move back to the browser", "Copy to the browser (keep in vault)")) { _, which ->
                val keep = which == 1
                val ids = ArrayList(sel)
                bg.execute {
                    val r = if (type == "page") VaultFiles.restorePages(a, ids, keep) else if (type == "file") VaultFiles.exportFiles(a, ids, keep) else restoreBookmarks(a, ids, keep)
                    main.post {
                        if (dlg?.isShowing != true) return@post
                        selecting = false; sel.clear(); listPage()
                        bar(a, if (r.ok) (if (keep) "Copied " else if (type == "file") "Exported " else "Restored ") + plural(r.added) + (if (type == "page") " to Saved pages" else if (type == "file") " to Downloads/Thazir" else " to the browser") + (if (r.msg.isNotEmpty()) ". " + r.msg else "") else r.msg, null, null)
                    }
                }
            }.setNegativeButton("Cancel", null).show()
    }

    // ---------- picker page: add from the browser ----------

    private fun pick(a: Activity) {
        pQuery = ""; pChip = 0; pSel.clear(); pLimit = PAGE
        pSrc = emptyList()
        pickerPage()
        bg.execute {
            val s = try { when (type) { "bm" -> bmSources(); "page" -> VaultFiles.pageSources(a); "file" -> VaultFiles.fileSources(a); else -> histSources() } } catch (x: Exception) { emptyList() }
            main.post { if (dlg?.isShowing == true && page == "pick") { pSrc = s; pickerPage() } }
        }
    }

    private fun pKey(s: Src) = if (type == "file") s.e.ref + ":" + s.e.time else keyOf(type, s.e.url, s.e.time)

    private fun pLabels(): List<String> =
        if (type == "bm") listOf("All") + pSrc.map { it.e.folder }.filter { it.isNotEmpty() }.flatMap { listOf(it) + Bookmarks.ancestors(it) }.distinct().sorted()
        else listOf("All", "Today", "Last 7 days", "Older than 30 days")

    private fun pShown(): List<Src> {
        var l = if (type == "file") pSrc else pSrc.filter { it.e.url.isNotEmpty() }
        val labels = pLabels()
        if (pChip > 0 && pChip < labels.size) {
            val now = System.currentTimeMillis(); val today = dayKey(now)
            l = when {
                type == "bm" -> l.filter { Bookmarks.under(it.e.folder, labels[pChip]) }
                pChip == 1 -> l.filter { it.e.time >= today }
                pChip == 2 -> l.filter { it.e.time >= today - 6L * 86400000L }
                else -> l.filter { it.e.time < now - 30L * 86400000L }
            }
        }
        val q = pQuery.trim().lowercase()
        if (q.isNotEmpty()) l = l.filter { it.e.title.lowercase().contains(q) || it.e.url.lowercase().contains(q) }
        return if (type == "bm") l else l.sortedByDescending { it.e.time }
    }

    private fun pickerPage() {
        val a = act ?: return
        page = "pick"
        val ui = shell(a)
        val info = VaultScreen.tv(a, "", 12f, R.color.mg_text_dim)
        ui.head.addView(topBar(a, if (type == "page") "Add from saved pages" else if (type == "file") "Add from downloads" else "Add from browser", listOf(icon(a, R.drawable.ic_list, "Select all shown") {
            val shown = pShown()
            if (shown.isNotEmpty() && shown.all { pSel.contains(pKey(it)) }) shown.forEach { pSel.remove(pKey(it)) } else shown.forEach { pSel.add(pKey(it)) }
            pickerPage()
        })), lp())
        ui.head.addView(search(a, when (type) { "bm" -> "Search browser bookmarks"; "page" -> "Search saved pages"; "file" -> "Search downloads"; else -> "Search browser history" }, pQuery) { pQuery = it; pLimit = PAGE; pFill(a, ui, info) }, lp(dp(a, 8), MATCH, dp(a, 48)))
        val labels = pLabels()
        if (labels.size > 1) ui.head.addView(chips(a, labels, pChip) { pChip = it; pLimit = PAGE; pickerPage() }, lp(dp(a, 4)))
        ui.head.addView(info, lp(dp(a, 6)))
        pFill(a, ui, info)
        val n = pSel.size
        val r = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL }
        r.addView(VaultScreen.btn(a, "Move to vault", true) { runAdd(a, true) }.apply { isEnabled = n > 0 }, LinearLayout.LayoutParams(0, dp(a, 48), 1f).apply { marginEnd = dp(a, 6) })
        r.addView(VaultScreen.btn(a, "Copy to vault", false) { runAdd(a, false) }.apply { isEnabled = n > 0 }, LinearLayout.LayoutParams(0, dp(a, 48), 1f).apply { marginStart = dp(a, 6) })
        ui.foot.addView(VaultScreen.tv(a, if (n == 0) "Nothing selected" else plural(n) + " selected", 12f, R.color.mg_text_dim), lp())
        ui.foot.addView(r, lp(dp(a, 8)))
    }

    private fun pFill(a: Activity, ui: Ui, info: TextView) {
        ui.list.removeAllViews()
        val shown = pShown()
        info.text = if (type == "file") "The file is sealed and checked first. With Move you are then asked whether to delete the original from your phone. Duplicates are merged." else "Move removes the " + (if (type == "page") "saved page" else "browser") + " copy after the vault copy is saved and checked. Copy keeps both. Duplicates are merged."
        if (shown.isEmpty()) { ui.list.addView(empty(a, if (pSrc.isEmpty()) (if (type == "file") "Loading, or no finished downloads yet." else "Loading, or nothing in the browser yet.") else "Nothing matches.")); return }
        var lastDay = -1L
        for (s in shown.take(pLimit)) {
            val key = pKey(s)
            val host = Util.host(s.e.url)
            if (type != "bm") { val dk = dayKey(s.e.time); if (dk != lastDay) { lastDay = dk; ui.list.addView(header(a, dayLabel(s.e.time)), lp(dp(a, 16))) } }
            val sub = when (type) {
                "hist" -> clock(s.e.time) + " - " + host
                "page" -> host + " - " + Util.fmtBytes(s.e.size)
                "file" -> Util.fmtBytes(s.e.size) + (if (s.e.url.startsWith("http")) " - " + host else "")
                else -> (if (s.e.folder.isNotEmpty()) s.e.folder + " - " else "") + host
            }
            ui.list.addView(row(a, s.e.title.ifBlank { host }, sub, pSel.contains(key), false, { if (!pSel.add(key)) pSel.remove(key); pickerPage() }, null), lp(dp(a, 8)))
        }
        if (shown.size > pLimit) ui.list.addView(showMore(a, shown.size - pLimit) { pLimit += PAGE; pFill(a, ui, info) }, lp(dp(a, 8), MATCH, dp(a, 48)))
    }

    private fun runAdd(a: Activity, move: Boolean) {
        if (pSel.isEmpty()) return
        if (move) {
            MaterialAlertDialogBuilder(a).setTitle("Move to vault?")
                .setMessage(if (type == "file") "${plural(pSel.size)} will be sealed in the vault and checked. Then you choose whether to delete the originals from your phone." else "${plural(pSel.size)} will be saved in the vault, checked, and then removed from " + (if (type == "page") "Saved pages" else "the browser") + ".")
                .setNegativeButton("Cancel", null).setPositiveButton("Move") { _, _ -> doAdd(a, true) }.show()
        } else doAdd(a, false)
    }

    private fun doAdd(a: Activity, move: Boolean) {
        val chosen = pSrc.filter { pSel.contains(pKey(it)) }
        val t = type
        if (t == "file") { // V7: own progress dialog (stream + cancel); originals are only deleted after the user confirms
            VaultFiles.runSeal(a, chosen, move) { r, del ->
                pSel.clear(); selecting = false; sel.clear(); query = ""; chip = 0
                if (dlg?.isShowing == true) { listPage(); bar(a, VaultFiles.sealedText(r, del), null, null) } else Util.toast(a, VaultFiles.sealedText(r, del))
            }
            return
        }
        val ui = host?.let { shell(a) } ?: return
        ui.list.addView(VaultScreen.tv(a, "Sealing ${plural(chosen.size)}...", 15f, R.color.mg_text_dim).apply { gravity = Gravity.CENTER }, lp(dp(a, 80)))
        bg.execute {
            val r = try { if (t == "page") VaultFiles.addPages(a, chosen, move) else addToVault(a, t, chosen, move) } catch (x: Exception) { Result(false, msg = "Something went wrong. Nothing was removed from the browser.") }
            main.post {
                if (dlg?.isShowing != true) return@post
                pSel.clear(); selecting = false; sel.clear(); query = ""; chip = 0
                listPage()
                if (!r.ok) { bar(a, r.msg, null, null); return@post }
                val msg = describe(t, r, move)
                if (move && t == "bm" && r.moved.isNotEmpty()) bar(a, msg, "Undo") { undoMove(a, r) } else bar(a, msg, null, null)
            }
        }
    }

    /** Undo of a bookmark move: put the originals back, verify, then drop only the vault copies this move created. */
    private fun undoMove(a: Activity, r: Result) {
        bg.execute {
            var back = 0
            for (e in r.moved) { try { ensureBookmark(e) } catch (x: Exception) { }; if (Bookmarks.isBookmarked(e.url)) back++ }
            if (back == r.moved.size) { for (id in r.newIds) VaultStore.removeItem(a, id, false); sealed(a) }
            main.post {
                if (dlg?.isShowing == true) { listPage(); bar(a, if (back == r.moved.size) "Undone" else "Could not undo everything. The vault copies were kept.", null, null) }
                else Util.toast(a, if (back == r.moved.size) "Undone" else "Could not undo everything")
            }
        }
    }
}
