package com.jabra.mgb

import androidx.core.widget.NestedScrollView
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.animation.ValueAnimator
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.media.MediaMetadataRetriever
import android.view.ViewOutlineProvider
import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.WindowManager
import android.widget.HorizontalScrollView
import android.widget.ImageView
import androidx.appcompat.widget.SwitchCompat
import androidx.core.graphics.ColorUtils
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * S50: "Files detected!" panel. Everything V77 (and the Sniffer) found on the page, as a clean list:
 * format tag, size, resolution, audio language / no audio, duration, source link; select several and download them;
 * Filter (size, time, type, extension, experimental, silent videos), Sort, and a menu per file (stream, preview,
 * download, remove, copy / share / show link). V77 still does the analysis and the download (start-job);
 * this only shows and starts. Plain video / audio files are read natively (MediaExtractor) for resolution,
 * duration and audio tracks.
 * S51-design: same look as the menu sheet (quiet sheet, rounded cards, rose accent): card rows with a type tile that turns
 * into a tick when selected, quick type chips (All / Video / Audio / Subtitle), sticky DOWNLOAD (n) button, bottom sheets for
 * the file menu / quality / sort / filter, friendly empty state. Selecting a file only repaints that row (no list rebuild). Also: the "Convert .ts to .mp4" switch (V77 setting convertMp4).
 */
@androidx.annotation.OptIn(UnstableApi::class)
object DetectedPanel {
    private const val PREFS = "mgb_detected"
    private const val MB = 1024.0 * 1024.0

    private val main = Handler(Looper.getMainLooper())
    private val pool = Executors.newFixedThreadPool(2)
    private val removed = HashMap<Int, HashSet<String>>()          // tab id -> keys the user removed from the list (this run)
    private var rmSaved: LinkedHashSet<String>? = null              // S51: removed keys kept across restarts (prefs mgb_detected, rm_keys, max 1000)
    private const val RM_MAX = 1000

    private fun rmLoad(ctx: android.content.Context): LinkedHashSet<String> {
        rmSaved?.let { return it }
        val s = LinkedHashSet<String>()
        try {
            val a = org.json.JSONArray(ctx.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE).getString("rm_keys", "[]"))
            for (i in 0 until a.length()) s.add(a.optString(i))
        } catch (e: Exception) { /* start empty */ }
        rmSaved = s
        return s
    }

    private fun rmAdd(ctx: android.content.Context, key: String) {
        val s = rmLoad(ctx)
        s.remove(key); s.add(key)
        while (s.size > RM_MAX) { val it = s.iterator(); it.next(); it.remove() }
        val a = org.json.JSONArray()
        for (k in s) a.put(k)
        ctx.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE).edit().putString("rm_keys", a.toString()).apply()
    }

    fun rmCount(ctx: android.content.Context): Int = rmLoad(ctx).size

    fun rmForget(ctx: android.content.Context) {
        rmLoad(ctx).clear()
        removed.clear()
        ctx.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE).edit().remove("rm_keys").apply()
    }
    private val metaCache = ConcurrentHashMap<String, Meta>()
    private val probing: MutableSet<String> = ConcurrentHashMap.newKeySet()

    private val KNOWN_EXT = setOf("mp4", "m4v", "webm", "mkv", "mov", "flv", "avi", "3gp", "ts", "m3u8", "mpd",
        "mp3", "m4a", "aac", "ogg", "opus", "wav", "flac", "srt", "vtt", "ass", "ssa")

    /** What MediaExtractor says about a plain file. hasAudio null = not read (yet). */
    private class Meta(val w: Int, val h: Int, val durS: Double, val langs: List<String>, val hasAudio: Boolean?)

    private class DItem(val j: JSONObject, val idx: Int) {
        val key: String = j.optString("key")
        val kind: String = j.optString("kind")
        val url: String = j.optString("url")
        val info: JSONObject = j.optJSONObject("info") ?: JSONObject()
        val variants: JSONArray? = info.optJSONArray("variants")
        val time: Long = j.optLong("time", 0L)
        val drm: Boolean = info.optBoolean("protected", false)
        val ext: String = url.substringBefore('?').substringBefore('#').substringAfterLast('/').substringAfterLast('.', "").lowercase()
        val name: String = j.optString("label").ifBlank { url.substringBefore('?').substringAfterLast('/') }.ifBlank { url }
        val group: String get() = when (kind) { "audio" -> "audio"; "subtitle" -> "subtitle"; "image" -> "image"; else -> "video" }
        val isStream: Boolean get() = kind == "hls" || kind == "dash"
    }

    private class Filter {
        var name = ""
        var minMb = 0f
        var maxMb = 0f
        var minSec = 0f
        var maxSec = 0f
        var video = true
        var audio = true
        var image = true
        var subtitle = true
        var experimental = true
        var noAudio = false
        var ext = ""

        fun active(): Boolean = name.isNotBlank() || minMb > 0 || maxMb > 0 || minSec > 0 || maxSec > 0 ||
            !video || !audio || !image || !subtitle || !experimental || noAudio || ext.isNotBlank()

        fun reset() {
            name = ""; minMb = 0f; maxMb = 0f; minSec = 0f; maxSec = 0f
            video = true; audio = true; image = true; subtitle = true; experimental = true; noAudio = false; ext = ""
        }

        fun load(c: Context) {
            val p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            name = p.getString("f_name", "") ?: ""
            minMb = p.getFloat("f_minmb", 0f); maxMb = p.getFloat("f_maxmb", 0f)
            minSec = p.getFloat("f_minsec", 0f); maxSec = p.getFloat("f_maxsec", 0f)
            video = p.getBoolean("f_video", true); audio = p.getBoolean("f_audio", true)
            image = p.getBoolean("f_image", true); subtitle = p.getBoolean("f_sub", true)
            experimental = p.getBoolean("f_exp", true); noAudio = p.getBoolean("f_noaudio", false)
            ext = p.getString("f_ext", "") ?: ""
        }

        fun save(c: Context) {
            c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString("f_name", name).putFloat("f_minmb", minMb).putFloat("f_maxmb", maxMb)
                .putFloat("f_minsec", minSec).putFloat("f_maxsec", maxSec)
                .putBoolean("f_video", video).putBoolean("f_audio", audio).putBoolean("f_image", image)
                .putBoolean("f_sub", subtitle).putBoolean("f_exp", experimental).putBoolean("f_noaudio", noAudio)
                .putString("f_ext", ext).apply()
        }
    }

    // ---------- conversion switch (V77 setting convertMp4) ----------

    fun convertOn(): Boolean = try { ChromeBridge.sync.get("convertMp4").optBoolean("convertMp4", true) } catch (e: Exception) { true }

    fun convertDialog(act: Activity, done: () -> Unit) {
        if (convertOn()) {
            ChromeBridge.storageSet("sync", JSONObject().put("convertMp4", false))
            Util.toast(act, ".ts files stay .ts")
            done()
            return
        }
        MaterialAlertDialogBuilder(act)
            .setTitle("Convert .ts to .mp4")
            .setMessage("Streams that are saved as .ts pieces are turned into one .mp4 when the download ends.\n\n" +
                "If you enable file conversion, external subtitles might get out of sync in some cases.")
            .setPositiveButton("Enable") { _, _ ->
                ChromeBridge.storageSet("sync", JSONObject().put("convertMp4", true))
                Util.toast(act, "Conversion is ON")
                done()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ---------- reading plain files ----------

    private fun langName(code: String?): String {
        if (code.isNullOrBlank() || code == "und") return "Unknown"
        val n = try { Locale(code).displayLanguage } catch (e: Exception) { "" }
        return if (n.isBlank() || n.equals(code, true)) code else n.replaceFirstChar { it.uppercase() }
    }

    private fun probe(url: String, ref: String?, ua: String?): Meta? {
        val ex = MediaExtractor()
        try {
            val hd = HashMap<String, String>()
            if (!ref.isNullOrEmpty()) hd["Referer"] = ref
            if (!ua.isNullOrEmpty()) hd["User-Agent"] = ua
            try { CookieManager.getInstance().getCookie(url)?.let { hd["Cookie"] = it } } catch (e: Exception) { }
            ex.setDataSource(url, hd)
            var w = 0
            var h = 0
            var dur = 0L
            var audio = false
            val langs = ArrayList<String>()
            for (i in 0 until ex.trackCount) {
                val f = ex.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: ""
                if (f.containsKey(MediaFormat.KEY_DURATION)) dur = maxOf(dur, f.getLong(MediaFormat.KEY_DURATION))
                if (mime.startsWith("video/")) {
                    if (f.containsKey(MediaFormat.KEY_WIDTH)) w = maxOf(w, f.getInteger(MediaFormat.KEY_WIDTH))
                    if (f.containsKey(MediaFormat.KEY_HEIGHT)) h = maxOf(h, f.getInteger(MediaFormat.KEY_HEIGHT))
                } else if (mime.startsWith("audio/")) {
                    audio = true
                    val l = if (f.containsKey(MediaFormat.KEY_LANGUAGE)) f.getString(MediaFormat.KEY_LANGUAGE) else null
                    val n = langName(l)
                    if (n !in langs) langs.add(n)
                }
            }
            return Meta(w, h, dur / 1e6, langs, audio)
        } catch (e: Exception) {
            return null
        } finally {
            try { ex.release() } catch (e: Exception) { }
        }
    }

    // ---------- S52: thumbnails + quality cap ----------

    private val thumbCache = android.util.LruCache<String, Bitmap>(60)
    private val thumbTried: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val thumbPool = Executors.newFixedThreadPool(2)

    private fun scaleDown(b: Bitmap, maxSide: Int): Bitmap {
        val m = maxOf(b.width, b.height)
        if (m <= maxSide || m <= 0) return b
        val f = maxSide.toFloat() / m
        return Bitmap.createScaledBitmap(b, maxOf(1, (b.width * f).toInt()), maxOf(1, (b.height * f).toInt()), true)
    }

    /** One frame (about 1 s in) of a plain http video file. Streams (HLS / DASH) have no readable frame here. */
    private fun frameOf(url: String, ref: String?, ua: String?): Bitmap? {
        val r = MediaMetadataRetriever()
        try {
            val hd = HashMap<String, String>()
            if (!ref.isNullOrEmpty()) hd["Referer"] = ref
            if (!ua.isNullOrEmpty()) hd["User-Agent"] = ua
            try { CookieManager.getInstance().getCookie(url)?.let { hd["Cookie"] = it } } catch (e: Exception) { }
            r.setDataSource(url, hd)
            val b = r.getFrameAtTime(1_000_000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC) ?: return null
            return scaleDown(b, 160)
        } catch (e: Exception) {
            return null
        } finally {
            try { r.release() } catch (e: Exception) { }
        }
    }

    /** The page poster image (V77 hint.poster), max 2 MB, scaled to a small picture. */
    private fun posterOf(url: String, ref: String?, ua: String?): Bitmap? {
        try {
            val c = java.net.URL(url).openConnection() as java.net.HttpURLConnection
            c.connectTimeout = 8000
            c.readTimeout = 8000
            if (!ref.isNullOrEmpty()) c.setRequestProperty("Referer", ref)
            if (!ua.isNullOrEmpty()) c.setRequestProperty("User-Agent", ua)
            try { CookieManager.getInstance().getCookie(url)?.let { c.setRequestProperty("Cookie", it) } } catch (e: Exception) { }
            if (c.contentLength > 2_000_000) return null
            val bytes = c.inputStream.use { it.readBytes() }
            if (bytes.isEmpty() || bytes.size > 2_000_000) return null
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
            var ss = 1
            while (o.outWidth / (ss * 2) >= 320 && o.outHeight / (ss * 2) >= 320) ss *= 2
            val b = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = ss }) ?: return null
            return scaleDown(b, 160)
        } catch (e: Exception) {
            return null
        }
    }

    /** Video rows get a small picture: a real frame for plain files, else the page poster (only if the page has ONE video). */
    private fun ensureThumb(e: DItem, poster: String, ref: String?, ua: String?, done: (Bitmap) -> Unit) {
        if (e.group != "video" || !e.url.startsWith("http") || thumbCache.get(e.url) != null) return
        if (!thumbTried.add(e.url)) return
        thumbPool.execute {
            var b: Bitmap? = if (e.kind == "video") frameOf(e.url, ref, ua) else null
            if (b == null && poster.startsWith("http")) b = posterOf(poster, ref, ua)
            if (b != null) {
                thumbCache.put(e.url, b)
                main.post { done(b) }
            }
        }
    }

    /** Max video height for \"best\" = the V77 setting maxHeight (0 = highest). Used by every download that says best, also DOWNLOAD (n). */
    /** S58: distinct heights of a stream's variants, highest first. */
    fun heightsOf(v: JSONArray?): List<Int> {
        val s = HashSet<Int>()
        if (v != null) for (i in 0 until v.length()) { val h = v.optJSONObject(i)?.optInt("h", 0) ?: 0; if (h > 0) s.add(h) }
        return s.toList().sortedDescending()
    }

    /** S58: what V77 picks for a cap: the highest height up to the cap, the lowest one if none is that small, cap 0 = the highest. */
    fun pickH(hs: List<Int>, cap: Int): Int = if (hs.isEmpty()) 0 else if (cap <= 0) hs.first() else (hs.firstOrNull { it <= cap } ?: hs.last())

    fun maxQ(): Int = try { ChromeBridge.sync.get("maxHeight").optInt("maxHeight", 0) } catch (e: Exception) { 0 }

    fun maxQLabel(): String = maxQ().let { if (it > 0) "up to ${it}p" else "Best available" }

    fun maxQDialog(act: Activity, done: () -> Unit) {
        val vals = intArrayOf(0, 4320, 2160, 1440, 1080, 720, 480, 360)
        val names = arrayOf("Best available", "Up to 4320p (8K)", "Up to 2160p (4K)", "Up to 1440p", "Up to 1080p", "Up to 720p", "Up to 480p", "Up to 360p")
        val cur = vals.indexOf(maxQ()).let { if (it < 0) 0 else it }
        MaterialAlertDialogBuilder(act)
            .setTitle("Download quality")
            .setSingleChoiceItems(names, cur) { d, i ->
                ChromeBridge.storageSet("sync", JSONObject().put("maxHeight", vals[i]))
                Util.toast(act, "Quality: " + names[i])
                d.dismiss()
                done()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun ensureMeta(e: DItem, ref: String?, ua: String?, changed: () -> Unit) {
        if (e.kind != "video" && e.kind != "audio") return
        if (!e.url.startsWith("http") || metaCache.containsKey(e.url)) return
        if (!probing.add(e.url)) return
        pool.execute {
            val m = probe(e.url, ref, ua) ?: Meta(0, 0, 0.0, emptyList(), null)
            metaCache[e.url] = m
            probing.remove(e.url)
            main.post { changed() }
        }
    }

    // ---------- small helpers ----------

    private fun durLabel(sec: Double): String {
        val t = sec.toLong()
        if (t <= 0) return ""
        val h = t / 3600
        val m = (t % 3600) / 60
        val s = t % 60
        return if (h > 0) String.format("%dh%02dm%02ds", h, m, s) else if (m > 0) String.format("%dm%02ds", m, s) else "${s}s"
    }

    /** (bytes, estimated). Streams have no size: estimate from the best variant bit rate and the duration. */
    private fun sizeOf(e: DItem): Pair<Long, Boolean> {
        val s = e.j.optLong("size", 0)
        if (s > 0) return Pair(s, false)
        val dur = e.info.optDouble("dur", 0.0)
        val bw = e.variants?.optJSONObject(0)?.optLong("bw", 0) ?: 0L
        if (e.isStream && dur > 0 && bw > 0) return Pair((bw * dur / 8).toLong(), true)
        val es = e.info.optLong("estSize", 0L) // 0.0.348: size measured by the engine for a single-quality playlist (V80 probeMedia); absent = as before
        if (es > 0L) return Pair(es, true)
        return Pair(0L, false)
    }

    /** S63: approximate size of one quality of a stream = bandwidth x duration (the biggest variant of that height). 0 = unknown. */
    private fun varSize(e: DItem, h: Int, dur: Double): Long {
        val v = e.variants ?: return 0L
        var bw = 0L
        for (i in 0 until v.length()) {
            val q = v.optJSONObject(i) ?: continue
            if (q.optInt("h", 0) == h) bw = maxOf(bw, q.optLong("bw", 0))
        }
        return if (bw > 0 && dur > 0) (bw * dur / 8).toLong() else 0L
    }

    /** 0.0.223: size that will really download = size of the quality the cap picks (same as the card line); else sizeOf(). */
    private fun sizeToGet(e: DItem): Long {
        if (e.isStream) {
            val got = pickH(heightsOf(e.variants), maxQ())
            if (got > 0) { val z = varSize(e, got, durOf(e)); if (z > 0) return z }
        }
        return sizeOf(e).first
    }

    /** S63: V77 autoSubs = download the page subtitle together with the video (same name). */
    fun subsOn(): Boolean = try { ChromeBridge.sync.get("autoSubs").optBoolean("autoSubs", true) } catch (e: Exception) { true }

    private fun durOf(e: DItem): Double {
        val d = e.info.optDouble("dur", 0.0)
        if (d > 0) return d
        return metaCache[e.url]?.durS ?: 0.0
    }

    private fun isExperimental(e: DItem): Boolean =
        e.kind != "subtitle" && !e.isStream && e.j.optLong("size", 0) <= 0 && e.ext !in KNOWN_EXT

    private fun tagOf(e: DItem): String = when (e.kind) {
        "hls" -> "HLS"
        "dash" -> "DASH"
        "subtitle" -> if (e.ext.isNotEmpty() && e.ext.length <= 4) e.ext.uppercase() else "SUBTITLE"
        else -> if (e.ext in KNOWN_EXT) e.ext.uppercase() else e.kind.uppercase()
    }

    private fun passes(e: DItem, f: Filter, removedKeys: Set<String>): Boolean {
        if (e.j.optBoolean("hidden", false) || e.key in removedKeys) return false
        when (e.group) {
            "video" -> if (!f.video) return false
            "audio" -> if (!f.audio) return false
            "image" -> if (!f.image) return false
            "subtitle" -> if (!f.subtitle) return false
        }
        if (f.name.isNotBlank() && !e.name.contains(f.name.trim(), true) && !e.url.contains(f.name.trim(), true)) return false
        val size = sizeOf(e).first
        if (size > 0) {
            if (f.minMb > 0 && size < f.minMb * MB) return false
            if (f.maxMb > 0 && size > f.maxMb * MB) return false
        }
        val dur = durOf(e)
        if (dur > 0) {
            if (f.minSec > 0 && dur < f.minSec) return false
            if (f.maxSec > 0 && dur > f.maxSec) return false
        }
        if (!f.experimental && isExperimental(e)) return false
        if (!f.noAudio && e.kind == "video" && metaCache[e.url]?.hasAudio == false) return false
        if (f.ext.isNotBlank()) {
            val allowed = f.ext.lowercase().split(',', ' ', ';').map { it.trim().trimStart('.') }.filter { it.isNotEmpty() }
            if (allowed.isNotEmpty() && e.ext !in allowed) return false
        }
        return true
    }

    // ---------- entry point ----------

    fun open(act: Activity, tabs: TabManager) {
        val t = tabs.active ?: return
        if (t.isExt || !t.url.startsWith("http")) { Util.toast(act, "Open a web page first"); return }
        val tabId = t.id
        val ref = t.url
        val ua = t.web.settings.userAgentString
        EngineApi.ask(JSONObject().put("type", "get-state").put("tabId", tabId)) { r ->
            if (r == null) { Util.toast(act, "Media Grabber is not ready yet"); return@ask }
            val p = Panel(act, tabId, ref, ua, tabs, itemsOf(r))
            p.poster = r.optJSONObject("rec")?.optJSONObject("hint")?.optString("poster") ?: "" // S52
            p.show()
        }
    }

    private fun itemsOf(r: JSONObject?): List<JSONObject> {
        val a = r?.optJSONObject("rec")?.optJSONArray("items") ?: return emptyList()
        val out = ArrayList<JSONObject>()
        for (i in 0 until a.length()) a.optJSONObject(i)?.let { out.add(it) }
        return out
    }

    // ---------- the panel ----------

    private class Panel(
        val act: Activity, val tabId: Int, val ref: String, val ua: String?, val tabs: TabManager, first: List<JSONObject>
    ) {
        private val dlg = BottomSheetDialog(act)
        private var all: List<DItem> = first.mapIndexed { i, j -> DItem(j, i) }
        private val selected = LinkedHashSet<String>()
        private val filter = Filter().also { it.load(act) }
        private var sort = act.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt("sort", 0)
        private lateinit var title: TextView
        private lateinit var sub: TextView
        private lateinit var list: LinearLayout
        private lateinit var selAll: CheckBox
        private lateinit var dlBtn: TextView
        private lateinit var filterBtn: TextView
        private var visible: List<DItem> = emptyList()
        private var sig = ""
        private val cText = act.getColor(R.color.mg_text)
        private val cDim = act.getColor(R.color.mg_text_dim)
        private val cPrimary = act.getColor(R.color.mg_primary)
        private val cOnPrimary = act.getColor(R.color.mg_on_primary)
        private val cLine = act.getColor(R.color.mg_outline)
        private val cCard = act.getColor(R.color.mg_surface)
        private val cTile = act.getColor(R.color.mg_surface_variant)
        private val cBg = act.getColor(R.color.mg_bg)
        private val cWarn = 0xFFF57C00.toInt()
        private val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        private val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
        private lateinit var badge: TextView
        private lateinit var qBtn: TextView // S58
        private lateinit var selHint: TextView
        private lateinit var chipRow: LinearLayout
        private lateinit var chipScroll: HorizontalScrollView
        private lateinit var dlWrap: View
        private val rowUpd = HashMap<String, () -> Unit>()
        private val thumbUpd = HashMap<String, (Bitmap) -> Unit>() // S52: row key -> set picture
        var poster: String = "" // S52: page poster (V77 hint.poster), set by open()
        private var firstDraw = true
        private var lastSel = ""
        private var chipSig = ""

        private class SRow(val icon: Int, val label: String, val checked: Boolean = false, val run: () -> Unit)

        /** Quiet sheet colour with a rounded top, like the menu sheet. */
        private fun sheetStyle(d: BottomSheetDialog) {
            d.setOnShowListener {
                d.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)?.background = GradientDrawable().apply {
                    setColor(cBg)
                    val r = dp(28).toFloat()
                    cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
                }
            }
        }

        private fun kindTint(e: DItem): Int {
            val hue = when {
                e.isStream -> 265f
                e.group == "audio" -> 205f
                e.group == "subtitle" -> 150f
                e.group == "image" -> 40f
                else -> 345f
            }
            return Color.HSVToColor(floatArrayOf(hue, 0.55f, 0.92f))
        }

        private fun iconBtn(res: Int, desc: String, on: () -> Unit) = ImageView(act).apply {
            setImageResource(res)
            setColorFilter(cText)
            scaleType = ImageView.ScaleType.CENTER
            background = round(cCard, 20)
            contentDescription = desc
            ripple(this)
            setOnClickListener { on() }
        }

        private fun dp(v: Int) = Util.dp(act, v)
        private fun removedKeys(): Set<String> = (removed[tabId] ?: emptySet<String>()) + rmLoad(act) // S51: + kept across restarts

        private fun round(color: Int, r: Int) = GradientDrawable().apply { setColor(color); cornerRadius = dp(r).toFloat() }

        private fun tv(text: String, sp: Float, color: Int, lines: Int = 1, bold: Boolean = false) = TextView(act).apply {
            this.text = text
            textSize = sp
            setTextColor(color)
            maxLines = lines
            ellipsize = TextUtils.TruncateAt.END
            if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }

        private fun ripple(v: View) {
            val out = TypedValue()
            if (act.theme.resolveAttribute(android.R.attr.selectableItemBackground, out, true)) v.foreground = act.getDrawable(out.resourceId)
        }

        fun show() {
            val root = LinearLayout(act).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = ViewGroup.LayoutParams(MATCH, (act.resources.displayMetrics.heightPixels * 0.9f).toInt())
            }
            // grab handle
            root.addView(View(act).apply { background = round(cLine, 3) },
                LinearLayout.LayoutParams(dp(36), dp(4)).apply { gravity = Gravity.CENTER_HORIZONTAL; topMargin = dp(8) })

            // header: title + count badge, page host, Filter, gear
            val head = LinearLayout(act).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(18), dp(10), dp(12), dp(2))
            }
            val tcol = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
            val trow = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            title = tv("Files detected!", 20f, cText, 1, true)
            badge = tv("0", 12f, cOnPrimary, 1, true).apply {
                background = round(cPrimary, 11)
                gravity = Gravity.CENTER
                setPadding(dp(8), dp(1), dp(8), dp(1))
            }
            trow.addView(title)
            trow.addView(badge, LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = dp(8) })
            sub = tv("", 12f, cDim)
            tcol.addView(trow)
            tcol.addView(sub, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(2) })
            head.addView(tcol, LinearLayout.LayoutParams(0, WRAP, 1f))
            filterBtn = pillBtn("Filter") { showFilter() }
            head.addView(filterBtn)
            head.addView(iconBtn(R.drawable.ic_settings, "Convert TS to MP4") { convertDialog(act) { } },
                LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginStart = dp(8) })
            root.addView(head)

            // quick type chips
            chipRow = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(14), dp(10), dp(14), dp(2)) }
            chipScroll = HorizontalScrollView(act).apply {
                isHorizontalScrollBarEnabled = false
                overScrollMode = View.OVER_SCROLL_NEVER
                visibility = View.GONE
                addView(chipRow)
            }
            root.addView(chipScroll, LinearLayout.LayoutParams(MATCH, WRAP))

            // select all + hint
            val selRow = LinearLayout(act).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(10), dp(4), dp(18), 0)
            }
            selAll = CheckBox(act).apply {
                text = "Select all to download"
                setTextColor(cText)
                textSize = 14f
                buttonTintList = ColorStateList.valueOf(cPrimary)
                setOnClickListener {
                    val on = isChecked
                    for (e in visible) {
                        if (on) { if (!e.drm) selected.add(e.key) } else selected.remove(e.key)
                    }
                    performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    refreshSelection(null)
                }
            }
            selRow.addView(selAll, LinearLayout.LayoutParams(0, dp(48), 1f))
            selHint = tv("", 12f, cDim)
            selRow.addView(selHint)
            root.addView(selRow)

            list = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(2), dp(12), dp(8)) }
            root.addView(NestedScrollView(act).apply { clipToPadding = false; addView(list) }, LinearLayout.LayoutParams(MATCH, 0, 1f))

            // sticky DOWNLOAD (n)
            dlBtn = TextView(act).apply {
                text = "Download"
                textSize = 15f
                gravity = Gravity.CENTER
                letterSpacing = 0.02f
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setTextColor(cOnPrimary)
                // S67: soft gradient pill + icon + file count and total size
                background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                    intArrayOf(ColorUtils.blendARGB(cPrimary, Color.WHITE, 0.16f), cPrimary)).apply { cornerRadius = dp(26).toFloat() }
                elevation = dp(4).toFloat()
                compoundDrawablePadding = dp(10)
                act.getDrawable(R.drawable.ic_download)?.mutate()?.let { d ->
                    d.setTint(cOnPrimary)
                    setCompoundDrawablesRelativeWithIntrinsicBounds(d, null, null, null)
                }
                setPadding(dp(18), 0, dp(18), 0)
                setOnClickListener { downloadSelected() }
            }
            qBtn = tv("", 13f, cText, 1, true).apply { // S58: video quality, like V77 "Preferred max video quality"
                gravity = Gravity.CENTER
                background = round(cCard, 20)
                ripple(this)
                setOnClickListener { qualityDialog() }
            }
            root.addView(FrameLayout(act).apply {
                setPadding(dp(14), dp(4), dp(14), dp(2))
                visibility = View.GONE
                addView(qBtn, FrameLayout.LayoutParams(MATCH, dp(40)))
            }, LinearLayout.LayoutParams(MATCH, WRAP))
            dlWrap = FrameLayout(act).apply {
                setPadding(dp(14), dp(6), dp(14), dp(4))
                visibility = View.GONE
                addView(dlBtn, FrameLayout.LayoutParams(MATCH, dp(52)))
            }
            root.addView(dlWrap, LinearLayout.LayoutParams(MATCH, WRAP))

            // bottom bar: one rounded card, three actions
            val bar = LinearLayout(act).apply {
                orientation = LinearLayout.HORIZONTAL
                background = round(cCard, 22)
                setPadding(dp(4), dp(2), dp(4), dp(2))
            }
            fun barBtn(icon: Int, label: String, on: () -> Unit) {
                val cell = LinearLayout(act).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                    ripple(this)
                    setOnClickListener { on() }
                }
                cell.addView(ImageView(act).apply { setImageResource(icon); setColorFilter(cDim) }, LinearLayout.LayoutParams(dp(20), dp(20)))
                cell.addView(tv(label, 11f, cDim, 1, true).apply { gravity = Gravity.CENTER_HORIZONTAL; setPadding(0, dp(2), 0, 0) })
                bar.addView(cell, LinearLayout.LayoutParams(0, dp(52), 1f))
            }
            barBtn(R.drawable.ic_settings, "TOOLS") { NativeBatch.tools(act, tabId, ref, all.count { it.kind == "subtitle" }, visible.count { !it.drm && it.kind != "subtitle" }, { downloadAllShown() },
                { visible.filter { !it.drm && it.kind != "subtitle" }.map { JSONObject().put("itemUrl", it.url).put("kind", it.kind).put("ref", ref).put("title", it.name).put("variant", "best") } },
                { u -> tabs.openUrl(u); dlg.dismiss() }) { dlg.dismiss() } } // S53, S56, S61
            barBtn(R.drawable.ic_trash, "CLEAR ALL") { confirmClear() }
            barBtn(R.drawable.ic_list, "SORT") { showSort() }
            barBtn(R.drawable.ic_close, "CLOSE") { dlg.dismiss() }
            root.addView(bar, LinearLayout.LayoutParams(MATCH, WRAP).apply { setMargins(dp(12), dp(4), dp(12), dp(10)) })

            dlg.setContentView(root)
            sheetStyle(dlg)
            dlg.behavior.skipCollapsed = true
            dlg.setOnDismissListener { open = false }
            dlg.show()
            dlg.behavior.state = BottomSheetBehavior.STATE_EXPANDED
            render()
            main.postDelayed(loop, 3000)
        }

        private fun pillBtn(label: String, on: () -> Unit) = tv(label, 14f, cText, 1, true).apply {
            gravity = Gravity.CENTER
            background = round(cCard, 18)
            setPadding(dp(16), 0, dp(16), 0)
            minHeight = dp(36)
            ripple(this)
            setOnClickListener { on() }
            layoutParams = LinearLayout.LayoutParams(WRAP, dp(36))
        }

        // ---- live refresh while the panel is open ----
        private var open = true
        private val loop = object : Runnable {
            override fun run() {
                if (!open) return
                EngineApi.ask(JSONObject().put("type", "get-state").put("tabId", tabId)) { r ->
                    if (!open) return@ask
                    if (r != null) {
                        all = itemsOf(r).mapIndexed { i, j -> DItem(j, i) }
                        render()
                    }
                    if (open) main.postDelayed(this, 3000)
                }
            }
        }

        // ---- list ----

        private fun sizeKey(e: DItem) = sizeOf(e).first

        private fun sorted(items: List<DItem>): List<DItem> = when (sort) {
            1 -> items.sortedByDescending { it.time }
            2 -> items.sortedBy { it.time }
            3 -> items.sortedByDescending { sizeKey(it) }
            4 -> items.sortedBy { sizeKey(it) }
            5 -> items.sortedByDescending { it.name.lowercase() }
            6 -> items.sortedBy { it.name.lowercase() }
            7 -> items.sortedBy { if (it.group == "video") 0 else if (it.group == "audio") 1 else 2 }
            8 -> items.sortedBy { if (it.group == "audio") 0 else if (it.group == "video") 1 else 2 }
            else -> {
                val order = listOf("hls", "dash", "video", "audio", "subtitle")
                items.sortedBy { val i = order.indexOf(it.kind); if (i < 0) 99 else i }
            }
        }

        private fun render() {
            if (!open) return
            val rk = removedKeys()
            val keysNow = all.map { it.key }.toSet()
            selected.retainAll(keysNow)
            for (e in all) ensureMeta(e, ref, ua) { render() }
            visible = sorted(all.filter { passes(it, filter, rk) })
            val vidsN = all.count { it.group == "video" && !it.j.optBoolean("hidden", false) && it.key !in rk }
            for (e in visible.take(30)) ensureThumb(e, if (vidsN == 1) poster else "", ref, ua) { b -> thumbUpd[e.key]?.invoke(b) } // S52
            val shownKeys = visible.joinToString(",") { it.key + ":" + sizeOf(it).first + ":" + (metaCache[it.url]?.hasAudio ?: "?") + ":" + it.info.optDouble("dur", 0.0) }
            val mg = NativeBatch.manga(ref) // S55
            val s = shownKeys + "|" + sort + "|" + filter.active() + "|" + maxQ() + "|" + (mg?.let { it.n.toString() + it.site } ?: "") // selection is NOT part of it: rows repaint themselves
            title.text = "Files detected!"
            val total = all.count { !it.j.optBoolean("hidden", false) && it.key !in rk }
            badge.text = visible.size.toString()
            qBtn.text = "Video quality:  " + (if (maxQ() > 0) "up to ${maxQ()}p" else "Best available") + "   ▾" // S58
            (qBtn.parent as? View)?.visibility = if (all.any { it.group == "video" }) View.VISIBLE else View.GONE
            val host = Util.host(ref).ifBlank { "this page" }
            sub.text = if (visible.size == total) host else "$host  ·  ${visible.size} of $total (filter on)"
            filterBtn.text = if (filter.active()) "Filter ●" else "Filter"
            filterBtn.setTextColor(if (filter.active()) cPrimary else cText)
            updateChips(rk)
            refreshSelection(null)
            if (s == sig) return
            sig = s
            val keep = list.parent?.let { (it as? ScrollView)?.scrollY } ?: 0
            list.removeAllViews()
            rowUpd.clear()
            thumbUpd.clear()
            if (mg != null) list.addView(mangaCard(mg), LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(8) }) // S55
            if (visible.isEmpty()) {
                if (mg == null) list.addView(emptyView())
                if (mg == null && all.isEmpty()) Diag.w("grabber", "panel: nothing found") // S87.2: auto Diagnose pill reads this
            } else {
                val anim = firstDraw && (Build.VERSION.SDK_INT < 26 || ValueAnimator.areAnimatorsEnabled())
                firstDraw = false
                var n = 0
                for (e in visible) {
                    val v = rowFor(e)
                    list.addView(v, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(8) })
                    if (anim && n < 10) {
                        v.alpha = 0f
                        v.translationY = dp(10).toFloat()
                        v.animate().alpha(1f).translationY(0f).setStartDelay(n * 30L).setDuration(220)
                            .withEndAction { v.alpha = 1f; v.translationY = 0f }.start()
                        v.postDelayed({ v.alpha = 1f; v.translationY = 0f }, 800) // safety: never stay invisible
                    }
                    n++
                }
            }
            (list.parent as? ScrollView)?.post { (list.parent as? ScrollView)?.scrollTo(0, keep) }
        }

        // ---- selection: only repaint what changed ----

        private fun refreshSelection(only: String?) {
            val n = selected.size
            var tot = 0L // S67: total of the sizes that are known
            for (k in selected) all.firstOrNull { it.key == k }?.let { tot += sizeToGet(it) }
            dlBtn.text = "Download " + n + (if (n == 1) " file" else " files") + (if (tot > 0) "  ·  ~" + EngineApi.fmtSize(tot) else "")
            setDlVisible(n > 0)
            selAll.isChecked = visible.isNotEmpty() && visible.all { it.drm || selected.contains(it.key) }
            selHint.text = if (n > 0) "$n selected" else if (visible.isEmpty()) "" else "Tap a file to select"
            val ss = selected.joinToString(",")
            if (only != null) rowUpd[only]?.invoke()
            else if (ss != lastSel) for (u in rowUpd.values) u()
            lastSel = ss
        }

        private fun setDlVisible(on: Boolean) {
            val shown = dlWrap.visibility == View.VISIBLE
            if (on == shown) return
            dlWrap.animate().cancel()
            if (on) {
                dlWrap.alpha = 0f
                dlWrap.visibility = View.VISIBLE
                dlWrap.animate().alpha(1f).setDuration(160).start()
            } else {
                dlWrap.animate().alpha(0f).setDuration(120).withEndAction { if (selected.isEmpty()) dlWrap.visibility = View.GONE }.start()
            }
        }

        // ---- quick type chips ----

        private fun baseFilter(): Filter {
            val f = Filter()
            f.name = filter.name; f.minMb = filter.minMb; f.maxMb = filter.maxMb; f.minSec = filter.minSec; f.maxSec = filter.maxSec
            f.experimental = filter.experimental; f.noAudio = filter.noAudio; f.ext = filter.ext
            return f // all media types on
        }

        private fun setTypes(v: Boolean, a: Boolean, st: Boolean, im: Boolean) {
            filter.video = v; filter.audio = a; filter.subtitle = st; filter.image = im
            filter.save(act)
            sig = ""
            render()
        }

        private fun typeChip(label: String, on: Boolean, click: () -> Unit) = tv(label, 13f, if (on) cOnPrimary else cText, 1, true).apply {
            gravity = Gravity.CENTER
            background = if (on) round(cPrimary, 17) else GradientDrawable().apply {
                setColor(cCard); cornerRadius = dp(17).toFloat(); setStroke(dp(1), cLine)
            }
            setPadding(dp(14), 0, dp(14), 0)
            setOnClickListener { click() }
        }

        private fun updateChips(rk: Set<String>) {
            val base = baseFilter()
            val cAll = all.count { passes(it, base, rk) }
            val cV = all.count { it.group == "video" && passes(it, base, rk) }
            val cA = all.count { it.group == "audio" && passes(it, base, rk) }
            val cS = all.count { it.group == "subtitle" && passes(it, base, rk) }
            val types = (if (filter.video) 1 else 0) or (if (filter.audio) 2 else 0) or (if (filter.subtitle) 4 else 0) or (if (filter.image) 8 else 0)
            val show = listOf(cV, cA, cS).count { it > 0 } >= 2 || types != 15
            val sg = "$show|$types|$cAll|$cV|$cA|$cS"
            if (sg == chipSig) return
            chipSig = sg
            chipScroll.visibility = if (show) View.VISIBLE else View.GONE
            chipRow.removeAllViews()
            if (!show) return
            fun add(label: String, n: Int, on: Boolean, onTap: () -> Unit) {
                chipRow.addView(typeChip("$label $n", on) { onTap() }, LinearLayout.LayoutParams(WRAP, dp(34)).apply { marginEnd = dp(8) })
            }
            add("All", cAll, types == 15) { setTypes(true, true, true, true) }
            if (cV > 0 || types == 1) add("Video", cV, types == 1) { setTypes(true, false, false, false) }
            if (cA > 0 || types == 2) add("Audio", cA, types == 2) { setTypes(false, true, false, false) }
            if (cS > 0 || types == 4) add("Subtitle", cS, types == 4) { setTypes(false, false, true, false) }
        }

        // ---- rows ----

        private fun chip(text: String, bg: Int, fg: Int) = tv(text, 10f, fg, 1, true).apply {
            background = round(bg, 6)
            setPadding(dp(6), dp(1), dp(6), dp(1))
        }

        /** S55: manga pages block (V77 does the work through the grab-pages message). */
        private fun mangaCard(mg: NativeBatch.Mg): View {
            val box = LinearLayout(act).apply {
                orientation = LinearLayout.VERTICAL
                background = round(cCard, 16)
                setPadding(dp(14), dp(12), dp(14), dp(12))
            }
            val cnt = if (mg.n > 0) mg.n.toString() else "all"
            box.addView(tv("Page images ($cnt)", 15f, cText, 1, true), LinearLayout.LayoutParams(MATCH, WRAP))
            box.addView(tv(
                if (mg.site.isNotBlank()) "Reads the ${mg.site} page list, original quality" else "Loads all pages, original quality",
                12f, cDim, 2
            ).apply { setPadding(0, dp(2), 0, dp(4)) }, LinearLayout.LayoutParams(MATCH, WRAP))
            fun rowOf(vararg b: Pair<String, String>) {
                val r = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL }
                for ((i, p) in b.withIndex()) {
                    val v = pillBtn(p.first) { NativeBatch.grab(act, tabId, p.second); dlg.dismiss() }
                    v.background = round(cTile, 18)
                    r.addView(v, LinearLayout.LayoutParams(0, dp(36), 1f).apply { if (i > 0) marginStart = dp(6) })
                }
                box.addView(r, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(6) })
            }
            rowOf("CBZ" to "cbz", "ZIP" to "zip", "PDF" to "pdf", "EPUB" to "epub")
            rowOf("Read" to "read", "Pick pages" to "view")
            return box
        }

        private fun emptyView(): View {
            val rk = removedKeys()
            val filtered = all.any { !it.j.optBoolean("hidden", false) && it.key !in rk }
            val box = LinearLayout(act).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(dp(28), dp(48), dp(28), dp(40))
            }
            box.addView(ImageView(act).apply {
                setImageResource(R.drawable.ic_download)
                setColorFilter(cDim)
                scaleType = ImageView.ScaleType.CENTER
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(cTile) }
            }, LinearLayout.LayoutParams(dp(72), dp(72)))
            box.addView(tv(if (filtered) "No file matches" else "Nothing detected yet", 17f, cText, 1, true)
                .apply { gravity = Gravity.CENTER_HORIZONTAL; setPadding(0, dp(16), 0, dp(6)) }, LinearLayout.LayoutParams(MATCH, WRAP))
            box.addView(tv(
                if (filtered) "Change or reset the filter to see the other files."
                else "Play the video or reload the page. New files show up here by themselves.", 14f, cDim, 4
            ).apply { gravity = Gravity.CENTER_HORIZONTAL }, LinearLayout.LayoutParams(MATCH, WRAP))
            if (filtered && filter.active()) {
                box.addView(pillBtn("Reset filter") { filter.reset(); filter.save(act); sig = ""; render() },
                    LinearLayout.LayoutParams(WRAP, dp(40)).apply { topMargin = dp(16) })
            }
            return box
        }

        private fun rowFor(e: DItem): View {
            val meta = metaCache[e.url]
            val card = LinearLayout(act).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.TOP
                setPadding(dp(12), dp(12), dp(4), dp(12))
                ripple(this)
            }
            // type tile: shows the format; turns into a rose tick when selected
            val tile = FrameLayout(act)
            val img = ImageView(act).apply { // S52: thumbnail under the tag
                scaleType = ImageView.ScaleType.CENTER_CROP
                background = round(cTile, 14)
                outlineProvider = ViewOutlineProvider.BACKGROUND
                clipToOutline = true
                visibility = View.GONE
            }
            val tag = tv(tagOf(e).take(5), 10f, cText, 1, true).apply { gravity = Gravity.CENTER; ellipsize = null }
            val tick = ImageView(act).apply {
                setImageResource(R.drawable.ic_check)
                setColorFilter(cOnPrimary)
                scaleType = ImageView.ScaleType.CENTER
            }
            tile.addView(img, FrameLayout.LayoutParams(MATCH, MATCH))
            tile.addView(tag, FrameLayout.LayoutParams(MATCH, MATCH))
            tile.addView(tick, FrameLayout.LayoutParams(MATCH, MATCH))
            val kt = kindTint(e)
            fun paint() {
                val on = selected.contains(e.key)
                card.background = GradientDrawable().apply {
                    cornerRadius = dp(18).toFloat()
                    setColor(if (on) ColorUtils.blendARGB(cCard, cPrimary, 0.10f) else cCard)
                    setStroke(dp(if (on) 2 else 1), if (on) cPrimary else cLine)
                }
                tile.background = round(if (on) cPrimary else ColorUtils.blendARGB(cTile, kt, 0.32f), 14)
                tag.visibility = if (on) View.INVISIBLE else View.VISIBLE
                val hasT = img.drawable != null // S52
                img.visibility = if (hasT && !on) View.VISIBLE else View.GONE
                tag.setTextColor(if (hasT && !on) Color.WHITE else cText)
                tick.visibility = if (on) View.VISIBLE else View.GONE
            }
            fun setThumb(b: Bitmap) { // S52
                img.setImageBitmap(b)
                tag.setShadowLayer(4f, 0f, 0f, 0xDD000000.toInt())
            }
            thumbCache.get(e.url)?.let { setThumb(it) }
            paint()
            rowUpd[e.key] = { paint() }
            thumbUpd[e.key] = { b -> setThumb(b); paint() }
            card.setOnClickListener {
                if (e.drm) { toast("DRM protected: cannot be downloaded"); return@setOnClickListener }
                if (!selected.add(e.key)) selected.remove(e.key)
                card.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                refreshSelection(e.key)
            }
            card.setOnLongClickListener { itemMenu(e); true }
            card.addView(tile, LinearLayout.LayoutParams(dp(46), dp(46)).apply { marginEnd = dp(12) })

            val col = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
            col.addView(tv(e.name, 14f, cText, 2, true))

            val bits = ArrayList<String>()
            val sz = sizeOf(e)
            val vr = e.variants?.optJSONObject(0)
            val hs = if (e.isStream) heightsOf(e.variants) else emptyList() // S58
            val capNow = maxQ()
            val got = pickH(hs, capNow)
            val gotSz = if (e.isStream && got > 0) varSize(e, got, durOf(e)) else 0L // S63: size of the quality that will download
            if (gotSz > 0) bits.add("~" + EngineApi.fmtSize(gotSz))
            else if (sz.first > 0) bits.add((if (sz.second) "~" else "") + EngineApi.fmtSize(sz.first))
            val res = when {
                hs.isNotEmpty() -> if (capNow > 0 && got != hs.first()) "Gets ${got}p of ${hs.first()}p" else "${hs.first()}p best"
                e.isStream && vr != null && vr.optString("res").isNotBlank() -> vr.optString("res")
                e.isStream && vr != null && vr.optInt("h", 0) > 0 -> vr.optInt("h").toString() + "p"
                e.isStream && e.info.optInt("estH", 0) > 0 -> (if (e.info.optInt("estGuess", 0) == 1) "~" else "") + e.info.optInt("estH").toString() + "p" // 0.0.348: V80 measured / guessed height
                meta != null && meta.w > 0 && meta.h > 0 -> "${meta.w}x${meta.h}"
                else -> ""
            }
            if (res.isNotEmpty()) bits.add(res)
            val d = durOf(e)
            if (d > 0) bits.add("Duration: " + durLabel(d))
            if (bits.isNotEmpty()) col.addView(tv(bits.joinToString("  ·  "), 12f, cDim, 2).apply { setPadding(0, dp(3), 0, 0) })
            if (hs.size > 1) { // S58: quality ladder, the one that will download is filled
                val lad = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL }
                for (h in hs.take(5)) lad.addView(chip("${h}p", if (h == got) cPrimary else cTile, if (h == got) cOnPrimary else cDim), LinearLayout.LayoutParams(WRAP, WRAP).apply { marginEnd = dp(5) })
                if (hs.size > 5) lad.addView(chip("+${hs.size - 5}", cTile, cDim), LinearLayout.LayoutParams(WRAP, WRAP))
                col.addView(lad, LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = dp(5) })
                val dd = durOf(e) // S63: size of every quality (needs the duration)
                val parts = hs.take(6).mapNotNull { h -> varSize(e, h, dd).let { z -> if (z > 0) "${h}p ~" + EngineApi.fmtSize(z) else null } }
                if (parts.isNotEmpty()) col.addView(tv(parts.joinToString("  ·  "), 11f, cDim, 3).apply { setPadding(0, dp(3), 0, 0) })
            }

            var noAudio = false
            val audioText = when {
                e.kind == "subtitle" -> {
                    val l = e.j.optString("lang").ifBlank { e.j.optString("label") }
                    if (l.isNotBlank()) "Language: " + langName(l.take(12).takeIf { it.length <= 3 } ?: l) else ""
                }
                e.isStream -> {
                    val n = e.info.optInt("audioTracks", 0)
                    if (n > 0) "Audio tracks: $n" else ""
                }
                meta != null && meta.hasAudio == false -> { noAudio = true; "No audio" }
                meta != null && meta.hasAudio == true -> "Language: " + (if (meta.langs.isEmpty()) "Unknown" else meta.langs.joinToString(", "))
                else -> ""
            }
            if (audioText.isNotEmpty()) col.addView(tv(audioText, 12f, if (noAudio) cWarn else cDim).apply { setPadding(0, dp(1), 0, 0) })

            val flags = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL }
            val lp = { LinearLayout.LayoutParams(WRAP, WRAP).apply { marginEnd = dp(6) } }
            if (isExperimental(e)) flags.addView(chip("Experimental", cWarn, Color.WHITE), lp())
            if (e.info.optBoolean("live", false)) flags.addView(chip("LIVE", 0xFFE53935.toInt(), Color.WHITE), lp())
            if (e.drm) flags.addView(chip("DRM", 0xFFE53935.toInt(), Color.WHITE), lp())
            if (flags.childCount > 0) col.addView(flags, LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = dp(6) })

            col.addView(tv(e.url, 11f, cDim, 1).apply {
                ellipsize = TextUtils.TruncateAt.MIDDLE
                alpha = 0.8f
                setPadding(0, dp(6), 0, 0)
            })
            card.addView(col, LinearLayout.LayoutParams(0, WRAP, 1f))

            card.addView(ImageView(act).apply {
                setImageResource(R.drawable.ic_more)
                setColorFilter(cDim)
                scaleType = ImageView.ScaleType.CENTER
                contentDescription = "More"
                ripple(this)
                setOnClickListener { itemMenu(e) }
            }, LinearLayout.LayoutParams(dp(40), dp(40)))
            return card
        }

        // ---- actions ----

        private fun toast(s: String) = Util.toast(act, s)

        private var subThis: Boolean? = null // S67: subtitle choice for the download being started (null = follow the V77 setting)
        private fun subEff(): Boolean = subThis ?: subsOn()

        private fun startJob(e: DItem, variant: String?, clip: String? = null, fmt: String? = null) {
            val m = JSONObject().put("type", "start-job").put("tabId", tabId).put("key", e.key)
            if (e.kind == "subtitle") m.put("fmt", fmt ?: "srt") else m.put("variant", variant ?: "best")
            if (clip != null && e.kind != "subtitle") m.put("clip", clip) // S81: V77 "Download only a part" (start-end seconds, end may be empty)
            if (e.kind != "subtitle" && subThis == false) m.put("noSubs", true) // S67: V77 background.js startJob skips the subtitle
            EngineApi.send(m)
        }

        /** S58: quality for Download, Download all and batches (= V77 maxHeight). Heights come from the streams found on this page. */
        private fun qualityDialog() {
            val found = HashMap<Int, Int>()
            val streams = all.filter { it.isStream && it.group == "video" }
            for (e in streams) for (h in heightsOf(e.variants)) found[h] = (found[h] ?: 0) + 1
            val hs = found.keys.sortedDescending().ifEmpty { listOf(1080, 720, 480, 360) }
            val vals = ArrayList<Int>()
            val names = ArrayList<String>()
            vals.add(0); names.add("Best available")
            for (h in hs) { vals.add(h); names.add("Up to ${h}p" + (if (streams.size > 1 && found[h] != null) "   (${found[h]} of ${streams.size} videos)" else "")) }
            if (!vals.contains(144)) { vals.add(144); names.add("Smallest file (lowest quality)") }
            val cur = vals.indexOf(maxQ()).let { if (it < 0) 0 else it }
            com.google.android.material.dialog.MaterialAlertDialogBuilder(act).setTitle("Video quality (best up to)")
                .setSingleChoiceItems(names.toTypedArray(), cur) { d, i ->
                    ChromeBridge.storageSet("sync", JSONObject().put("maxHeight", vals[i]))
                    toast("Quality: " + names[i].substringBefore("   ("))
                    d.dismiss()
                    sig = ""
                    render()
                }
                .setNegativeButton("Cancel", null).show()
        }

        /** S56: TOOLS > Download all = every file shown now (filter respected, subtitles and DRM left out), best quality. */
        private fun downloadAllShown() {
            val ks = visible.filter { !it.drm && it.kind != "subtitle" }
            if (ks.isEmpty()) { toast("Nothing to download"); return }
            com.google.android.material.dialog.MaterialAlertDialogBuilder(act).setTitle("Download all")
                .setMessage("Start ${ks.size} download(s) at best quality?")
                .setPositiveButton("Download") { _, _ -> selected.clear(); ks.forEach { selected.add(it.key) }; downloadSelected() }
                .setNegativeButton("Cancel", null).show()
        }

        private fun downloadSelected() {
            var n = 0
            for (k in selected.toList()) {
                val e = all.firstOrNull { it.key == k } ?: continue
                if (e.drm) continue
                startJob(e, "best")
                n++
            }
            selected.clear()
            sig = ""
            toast(if (n > 0) "$n download" + (if (n == 1) "" else "s") + " started" else "Nothing to download")
            render()
        }

        private fun download(e: DItem) {
            if (e.drm) { toast("DRM protected: cannot be downloaded"); return }
            val v = e.variants
            val pageSubs = all.count { it.kind == "subtitle" } // S63
            // S67: global ON = this row decides for this download only (V77 noSubs); global OFF = the old row that turns the setting on
            val subRow = if (subsOn()) SRow(0, if (subEff()) "Subtitle with this video: ON (tap to skip it, this download only)" else "Subtitle with this video: OFF (this download only)", subEff()) {
                subThis = !subEff()
                download(e)
            } else SRow(0, "Subtitle with the video: OFF (tap to turn on)", false) {
                try { ChromeBridge.storageSet("sync", JSONObject().put("autoSubs", true)) } catch (x: Exception) { /* engine not ready */ }
                download(e)
            }
            if (e.isStream && v != null && v.length() > 0) {
                val names = ArrayList<String>()
                val vals = ArrayList<String>()
                val dur = durOf(e)
                val cap = maxQ()
                val hsAll = heightsOf(v)
                val auto = pickH(hsAll, cap)
                val autoSz = if (auto > 0) varSize(e, auto, dur) else 0L
                names.add((if (cap > 0) "Auto (" + maxQLabel() + ")" else "Best quality (auto)") + (if (autoSz > 0) "   ~" + EngineApi.fmtSize(autoSz) else "")); vals.add("best")
                for (i in 0 until minOf(v.length(), 10)) {
                    val q = v.optJSONObject(i) ?: continue
                    val h = q.optInt("h", 0)
                    val bw = q.optLong("bw", 0)
                    var label = if (h > 0) "${h}p" else if (bw > 0) String.format("%.1f Mbps", bw / 1e6) else "Default"
                    if (bw > 0 && dur > 0) label += "   ~" + EngineApi.fmtSize((bw * dur / 8).toLong())
                    names.add(label)
                    vals.add(q.optString("url").ifBlank { q.optString("id") })
                }
                val rows = ArrayList<SRow>()
                if (pageSubs > 0 || subsOn()) rows.add(subRow)
                rows.add(SRow(0, "Audio only (separate audio stream)") { val sb = subEff(); startJob(e, "audio"); subThis = null; toast("Audio download started" + (if (sb) " (subtitle too)" else "")) }) // D4
                for (i in names.indices) rows.add(SRow(0, names[i]) { val sb = subEff(); startJob(e, vals[i]); subThis = null; toast("Download started" + (if (sb) " (subtitle too)" else "")) })
                sheet("Choose quality" + (if (dur > 0) "  ·  " + durLabel(dur) else ""), null, rows)
            } else if (e.kind != "subtitle" && pageSubs > 0) {
                val sz = sizeOf(e)
                sheet("Download", null, listOf(
                    subRow,
                    SRow(0, "Download" + (if (sz.first > 0) "   " + (if (sz.second) "~" else "") + EngineApi.fmtSize(sz.first) else "")) { val sb = subEff(); startJob(e, "best"); subThis = null; toast("Download started" + (if (sb) " (subtitle too)" else "")) }
                ))
            } else {
                startJob(e, "best")
                toast(if (e.kind == "subtitle") "Subtitle download started" else "Download started")
            }
        }

        private fun stream(e: DItem) {
            if (e.drm) { toast("DRM protected"); return }
            WebPlay.play(act, e.url, e.name, ref, ua)
            dlg.dismiss()
        }

        private fun preview(e: DItem) {
            if (e.drm) { toast("DRM protected"); return }
            try {
                val hdr = HashMap<String, String>()
                if (ref.isNotEmpty()) hdr["Referer"] = ref
                try { CookieManager.getInstance().getCookie(e.url)?.let { hdr["Cookie"] = it } } catch (x: Exception) { }
                val http = DefaultHttpDataSource.Factory().setAllowCrossProtocolRedirects(true)
                    .setUserAgent(ua ?: "Mozilla/5.0").setDefaultRequestProperties(hdr)
                val p = ExoPlayer.Builder(act).setMediaSourceFactory(DefaultMediaSourceFactory(http)).build()
                val pv = PlayerView(act).apply { player = p; useController = true }
                p.setMediaItem(MediaItem.fromUri(e.url))
                p.prepare()
                p.playWhenReady = true
                val vid = FrameLayout(act).apply {
                    setBackgroundColor(Color.BLACK)
                    addView(pv, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(240)))
                }
                val box = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; addView(vid, LinearLayout.LayoutParams(MATCH, WRAP)) }
                var dRef: androidx.appcompat.app.AlertDialog? = null
                if (e.kind == "hls") { // S81: find the part while it plays (V77 "Preview here": Start here / End here / Download part)
                    val mk = doubleArrayOf(-1.0, -1.0)
                    val bar = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(16), dp(12), dp(16), dp(4)) }
                    fun pill(t: String, prim: Boolean, on: (TextView) -> Unit) = tv(t, 14f, if (prim) Color.WHITE else cText, 1, true).apply {
                        gravity = Gravity.CENTER
                        background = round(if (prim) cPrimary else cTile, 22)
                        ripple(this)
                        setOnClickListener { on(this) }
                    }
                    val ps = pill("Start here", false) { b -> mk[0] = p.currentPosition / 1000.0; b.text = "Start " + fmtT(mk[0]) }
                    val pe = pill("End here", false) { b -> mk[1] = p.currentPosition / 1000.0; b.text = "End " + fmtT(mk[1]) }
                    val pd = pill("Download part", true) { _ ->
                        val a = if (mk[0] >= 0) mk[0] else null
                        val z = if (mk[1] >= 0) mk[1] else null
                        if (a == null && z == null) toast("Play, then press Start here and End here")
                        else { dRef?.dismiss(); clipSheet(e, a, z) }
                    }
                    bar.addView(ps, LinearLayout.LayoutParams(0, dp(44), 1f).apply { marginEnd = dp(8) })
                    bar.addView(pe, LinearLayout.LayoutParams(0, dp(44), 1f).apply { marginEnd = dp(8) })
                    bar.addView(pd, LinearLayout.LayoutParams(0, dp(44), 1.3f))
                    box.addView(bar, LinearLayout.LayoutParams(MATCH, WRAP))
                }
                val d = MaterialAlertDialogBuilder(act)
                    .setTitle("Preview " + (if (e.group == "audio") "audio" else "video"))
                    .setView(box)
                    .setNegativeButton("Close", null)
                    .create()
                dRef = d
                d.setOnDismissListener { try { pv.player = null; p.release() } catch (x: Exception) { } }
                d.show()
            } catch (x: Exception) {
                toast("Cannot preview this file")
            }
        }

        private fun copy(e: DItem) {
            (act.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("link", e.url))
            toast("Link copied")
        }

        private fun share(e: DItem) {
            try {
                act.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, e.url), "Share link"))
            } catch (x: Exception) { toast("Cannot share") }
        }

        private fun showLink(e: DItem) {
            MaterialAlertDialogBuilder(act).setTitle("Download link").setMessage(e.url)
                .setPositiveButton("Copy") { _, _ -> copy(e) }
                .setNegativeButton("Close", null).show()
        }

        private fun remove(e: DItem) {
            removed.getOrPut(tabId) { HashSet() }.add(e.key)
            rmAdd(act, e.key) // S51
            selected.remove(e.key)
            sig = ""
            render()
        }

        private fun clearAll() {
            EngineApi.send(JSONObject().put("type", "clear").put("tabId", tabId))
            removed.remove(tabId)
            selected.clear()
            all = emptyList()
            sig = ""
            render()
            toast("List cleared")
        }

        private fun confirmClear() {
            MaterialAlertDialogBuilder(act).setMessage("Clear the whole list for this page?")
                .setPositiveButton("Clear") { _, _ -> clearAll() }
                .setNegativeButton("Cancel", null).show()
        }

        // ---- bottom sheets (same look as the menu sheet) ----

        private fun sheet(title: String, header: View?, rows: List<SRow>) {
            val d = BottomSheetDialog(act)
            val col = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(16), dp(12), dp(16)) }
            if (header != null) col.addView(header, LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(12) })
            else col.addView(tv(title, 18f, cText, 1, true).apply { setPadding(dp(8), 0, dp(8), dp(10)) })
            val card = LinearLayout(act).apply {
                orientation = LinearLayout.VERTICAL
                background = round(cCard, 26)
                setPadding(0, dp(6), 0, dp(6))
            }
            for (r in rows) {
                val line = LinearLayout(act).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(16), 0, dp(16), 0)
                    ripple(this)
                    setOnClickListener { d.dismiss(); r.run() }
                }
                if (r.icon != 0) line.addView(ImageView(act).apply { setImageResource(r.icon); setColorFilter(cDim) },
                    LinearLayout.LayoutParams(dp(22), dp(22)).apply { marginEnd = dp(16) })
                line.addView(tv(r.label, 16f, if (r.checked) cPrimary else cText), LinearLayout.LayoutParams(0, WRAP, 1f))
                if (r.checked) line.addView(ImageView(act).apply { setImageResource(R.drawable.ic_check); setColorFilter(cPrimary) },
                    LinearLayout.LayoutParams(dp(22), dp(22)))
                card.addView(line, LinearLayout.LayoutParams(MATCH, dp(52)))
            }
            col.addView(card, LinearLayout.LayoutParams(MATCH, WRAP))
            d.setContentView(NestedScrollView(act).apply { addView(col) })
            sheetStyle(d)
            d.behavior.skipCollapsed = true
            d.show()
            d.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        }

        private fun itemMenu(e: DItem) {
            val rows = ArrayList<SRow>()
            val av = if (e.group == "audio") "audio" else "video"
            if (e.kind != "subtitle") {
                rows.add(SRow(R.drawable.ic_play, "Stream $av") { stream(e) })
                rows.add(SRow(R.drawable.ic_screen, "Preview $av") { preview(e) })
            }
            rows.add(SRow(R.drawable.ic_download, "Download") { download(e) })
            if (e.kind == "subtitle") rows.add(SRow(R.drawable.ic_list, "Save subtitle as...") { subFmtSheet(e) }) // S81
            if (e.kind == "hls" && !e.drm) rows.add(SRow(R.drawable.ic_cut, "Download only a part...") { clipSheet(e) }) // S81
            if (e.kind != "subtitle" && !e.drm) rows.add(SRow(R.drawable.ic_clock, "Start later...") { pickSchedule(e) }) // S81
            rows.add(SRow(R.drawable.ic_list, "Copy download link") { copy(e) })
            if (e.kind != "subtitle") rows.add(SRow(R.drawable.ic_code, "Copy as yt-dlp / ffmpeg / cURL...") { cmdSheet(e) }) // S81
            rows.add(SRow(R.drawable.ic_open, "Open in new tab") { tabs.openUrl(e.url); dlg.dismiss() }) // S81
            rows.add(SRow(R.drawable.ic_share, "Share link") { share(e) })
            rows.add(SRow(R.drawable.ic_info, "Show download link") { showLink(e) })
            rows.add(SRow(R.drawable.ic_trash, "Remove link") { remove(e) })
            // header card like the link menu: type tile + name + address
            val head = LinearLayout(act).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = round(cTile, 20)
                setPadding(dp(14), dp(12), dp(14), dp(12))
            }
            head.addView(tv(tagOf(e).take(5), 11f, cText, 1, true).apply {
                gravity = Gravity.CENTER
                ellipsize = null
                background = round(ColorUtils.blendARGB(cCard, kindTint(e), 0.32f), 14)
            }, LinearLayout.LayoutParams(dp(48), dp(48)).apply { marginEnd = dp(14) })
            val hc = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
            hc.addView(tv(e.name, 15f, cText, 2, true))
            hc.addView(tv(e.url, 12f, cDim, 1).apply { ellipsize = TextUtils.TruncateAt.MIDDLE })
            head.addView(hc, LinearLayout.LayoutParams(0, WRAP, 1f))
            sheet("", head, rows)
        }

        // ---- S81: V77 per-video menu in the native panel (only a part, start later, copy commands, subtitle format) ----

        /** null = empty, -1.0 = not understood. Accepts 90, 1:30, 1:02:30. */
        private fun parseT(s: String): Double? {
            val t = s.trim()
            if (t.isEmpty()) return null
            val p = t.split(":")
            if (p.size > 3) return -1.0
            var v = 0.0
            for (x in p) { val n = x.trim().toDoubleOrNull() ?: return -1.0; if (n < 0) return -1.0; v = v * 60 + n }
            return v
        }

        private fun fmtT(sec: Double): String {
            val s = sec.toLong(); val h = s / 3600; val m = (s % 3600) / 60; val r = s % 60
            return if (h > 0) String.format("%d:%02d:%02d", h, m, r) else String.format("%d:%02d", m, r)
        }

        private fun itemHead(e: DItem, sub: String): View {
            val head = LinearLayout(act).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = round(cTile, 20)
                setPadding(dp(14), dp(12), dp(14), dp(12))
            }
            head.addView(tv(tagOf(e).take(5), 11f, cText, 1, true).apply {
                gravity = Gravity.CENTER
                ellipsize = null
                background = round(ColorUtils.blendARGB(cCard, kindTint(e), 0.32f), 14)
            }, LinearLayout.LayoutParams(dp(48), dp(48)).apply { marginEnd = dp(14) })
            val hc = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
            hc.addView(tv(e.name, 15f, cText, 2, true))
            hc.addView(tv(sub, 12f, cDim, 1))
            head.addView(hc, LinearLayout.LayoutParams(0, WRAP, 1f))
            return head
        }

        /** Download only a part: from / to boxes, quick picks, quality chips, live length + size, Download clip / Full video. */
        private fun clipSheet(e: DItem, from: Double? = null, to: Double? = null) {
            if (e.drm) { toast("DRM protected: cannot be downloaded"); return }
            val dur = durOf(e)
            val v = e.variants
            val d = BottomSheetDialog(act)
            d.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            val col = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(18), dp(16), dp(18)) }
            col.addView(tv("Download only a part", 20f, cText, 1, true).apply { setPadding(dp(4), 0, dp(4), dp(10)) })
            col.addView(itemHead(e, if (dur > 0) "Video length " + fmtT(dur) else "Video length not known yet"), LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(14) })

            fun box(hint: String, init: Double?) = EditText(act).apply {
                setText(if (init != null) fmtT(init) else "")
                this.hint = hint
                textSize = 22f
                gravity = Gravity.CENTER
                setSingleLine()
                setTextColor(cText)
                setHintTextColor(cDim)
                background = round(cTile, 16)
                inputType = InputType.TYPE_CLASS_TEXT
                setPadding(dp(8), 0, dp(8), 0)
            }
            val fFrom = box("0:00", from)
            val fTo = box("end", to)
            val row = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            fun lab(t: String, f: EditText): View = LinearLayout(act).apply {
                orientation = LinearLayout.VERTICAL
                addView(tv(t, 12f, cDim).apply { setPadding(dp(6), 0, 0, dp(4)) })
                addView(f, LinearLayout.LayoutParams(MATCH, dp(56)))
            }
            row.addView(lab("From", fFrom), LinearLayout.LayoutParams(0, WRAP, 1f))
            row.addView(tv("to", 14f, cDim).apply { gravity = Gravity.CENTER; setPadding(dp(12), dp(18), dp(12), 0) })
            row.addView(lab("To", fTo), LinearLayout.LayoutParams(0, WRAP, 1f))
            col.addView(row, LinearLayout.LayoutParams(MATCH, WRAP))

            val info = tv("", 13f, cDim, 2).apply { setPadding(dp(6), dp(10), dp(6), 0) }
            val err = tv("", 13f, Color.parseColor("#FF6B6B"), 3).apply { setPadding(dp(6), dp(6), dp(6), 0); visibility = View.GONE }

            // quick picks
            val quick = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(12), 0, 0) }
            fun chip(t: String, on: () -> Unit) = tv(t, 13f, cText, 1, true).apply {
                gravity = Gravity.CENTER
                background = round(cTile, 18)
                setPadding(dp(14), 0, dp(14), 0)
                ripple(this)
                setOnClickListener { on() }
            }
            fun setBoth(a: Double?, b: Double?) { fFrom.setText(if (a != null) fmtT(a) else ""); fTo.setText(if (b != null) fmtT(b) else "") }
            quick.addView(chip("First minute") { setBoth(null, 60.0) }, LinearLayout.LayoutParams(WRAP, dp(36)).apply { marginEnd = dp(8) })
            quick.addView(chip("First 5 min") { setBoth(null, 300.0) }, LinearLayout.LayoutParams(WRAP, dp(36)).apply { marginEnd = dp(8) })
            if (dur > 330) quick.addView(chip("Last 5 min") { setBoth(dur - 300, null) }, LinearLayout.LayoutParams(WRAP, dp(36)).apply { marginEnd = dp(8) })
            quick.addView(chip("Clear") { setBoth(null, null) }, LinearLayout.LayoutParams(WRAP, dp(36)))
            col.addView(HorizontalScrollView(act).apply { isHorizontalScrollBarEnabled = false; addView(quick) }, LinearLayout.LayoutParams(MATCH, WRAP))

            // quality chips (Auto + heights found in the stream)
            var sel = "best"
            val qChips = ArrayList<Pair<TextView, String>>()
            val hs = if (v != null) heightsOf(v) else emptyList()
            if (hs.size > 1) {
                col.addView(tv("Quality", 12f, cDim).apply { setPadding(dp(6), dp(16), 0, dp(6)) })
                val qr = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL }
                fun addQ(t: String, value: String) {
                    val c = chip(t) { }
                    qChips.add(Pair(c, value))
                    qr.addView(c, LinearLayout.LayoutParams(WRAP, dp(36)).apply { marginEnd = dp(8) })
                }
                addQ(if (maxQ() > 0) "Auto (" + maxQLabel() + ")" else "Best (auto)", "best")
                for (h in hs) {
                    var value = ""
                    if (v != null) for (i in 0 until v.length()) { val q = v.optJSONObject(i) ?: continue; if (q.optInt("h", 0) == h) { value = q.optString("url").ifBlank { q.optString("id") }; break } }
                    if (value.isNotBlank()) addQ(h.toString() + "p", value)
                }
                col.addView(HorizontalScrollView(act).apply { isHorizontalScrollBarEnabled = false; addView(qr) }, LinearLayout.LayoutParams(MATCH, WRAP))
            }
            col.addView(info, LinearLayout.LayoutParams(MATCH, WRAP))
            col.addView(err, LinearLayout.LayoutParams(MATCH, WRAP))

            fun bwSel(): Long {
                if (v == null) return 0L
                if (sel == "best") {
                    val h = pickH(heightsOf(v), maxQ())
                    for (i in 0 until v.length()) { val q = v.optJSONObject(i) ?: continue; if (h <= 0 || q.optInt("h", 0) == h) return q.optLong("bw", 0) }
                    return v.optJSONObject(0)?.optLong("bw", 0) ?: 0L
                }
                for (i in 0 until v.length()) { val q = v.optJSONObject(i) ?: continue; if (q.optString("url").ifBlank { q.optString("id") } == sel) return q.optLong("bw", 0) }
                return 0L
            }
            fun refresh() {
                for ((c, value) in qChips) {
                    val on = value == sel
                    c.background = round(if (on) cPrimary else cTile, 18)
                    c.setTextColor(if (on) Color.WHITE else cText)
                }
                val a = parseT(fFrom.text.toString()); val b = parseT(fTo.text.toString())
                if (a == -1.0 || b == -1.0 || (a == null && b == null)) { info.text = "Type a start and / or an end time. Use 90, 1:30 or 1:02:30."; return }
                val st = a ?: 0.0
                val en = b ?: if (dur > 0) dur else -1.0
                if (en > 0 && en > st) {
                    val len = en - st
                    val bw = bwSel()
                    info.text = "Clip length " + fmtT(len) + (if (bw > 0) "  ·  about " + EngineApi.fmtSize((bw * len / 8).toLong()) else "")
                } else info.text = ""
            }
            for ((c, value) in qChips) c.setOnClickListener { sel = value; refresh() }
            val tw = object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) { }
                override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) { }
                override fun afterTextChanged(s: android.text.Editable?) { err.visibility = View.GONE; refresh() }
            }
            fFrom.addTextChangedListener(tw)
            fTo.addTextChangedListener(tw)
            refresh()

            fun go() {
                val a = parseT(fFrom.text.toString()); val b = parseT(fTo.text.toString())
                fun bad(m: String) { err.text = m; err.visibility = View.VISIBLE }
                if (a == -1.0 || b == -1.0) { bad("Time not understood. Use 90, 1:30 or 1:02:30"); return }
                if (a == null && b == null) { bad("Type a start and / or an end time first"); return }
                val st = a ?: 0.0
                if (b != null && b <= st) { bad("The end must be after the start"); return }
                if (dur > 0 && st >= dur) { bad("The start is after the end of the video (" + fmtT(dur) + ")"); return }
                val clip = Math.floor(st).toLong().toString() + "-" + (if (b == null) "" else Math.ceil(b).toLong().toString())
                val sb = subEff()
                startJob(e, sel, clip)
                subThis = null
                d.dismiss()
                toast("Clip download started" + (if (sb && all.any { it.kind == "subtitle" }) " (subtitle too)" else "") + ", see Downloads")
            }
            val btns = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(16), 0, 0) }
            btns.addView(tv("Full video", 15f, cText, 1, true).apply {
                gravity = Gravity.CENTER
                background = round(cTile, 24)
                ripple(this)
                setOnClickListener { d.dismiss(); download(e) }
            }, LinearLayout.LayoutParams(0, dp(52), 1f).apply { marginEnd = dp(10) })
            btns.addView(tv("Download clip", 15f, Color.WHITE, 1, true).apply {
                gravity = Gravity.CENTER
                background = round(cPrimary, 24)
                ripple(this)
                setOnClickListener { go() }
            }, LinearLayout.LayoutParams(0, dp(52), 1.5f))
            col.addView(btns, LinearLayout.LayoutParams(MATCH, WRAP))

            d.setContentView(NestedScrollView(act).apply { addView(col) })
            sheetStyle(d)
            d.behavior.skipCollapsed = true
            d.show()
            d.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        }

        /** Start later: one file at a chosen time (V77 schedule-job, same as TOOLS > Scheduled downloads). */
        private fun pickSchedule(e: DItem) {
            if (e.drm) { toast("DRM protected: cannot be downloaded"); return }
            val now = java.util.Calendar.getInstance()
            android.app.TimePickerDialog(act, { _, h, m ->
                val hhmm = String.format("%02d:%02d", h, m)
                val job = JSONObject().put("itemUrl", e.url).put("kind", e.kind).put("ref", ref).put("title", e.name).put("variant", "best")
                EngineApi.send(JSONObject().put("type", "schedule-job").put("hhmm", hhmm).put("job", job))
                toast("Scheduled for $hhmm (tomorrow if the time has passed). The browser must be running.")
            }, now.get(java.util.Calendar.HOUR_OF_DAY), now.get(java.util.Calendar.MINUTE), true).show()
        }

        private fun cmdSheet(e: DItem) {
            fun q(s: String) = "'" + s.replace("'", "'\\''") + "'"
            val base = e.name.substringBeforeLast('.', e.name).replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().take(80).ifBlank { "video" }
            val r = ref
            val audio = e.kind == "audio"
            val ytdlp = "yt-dlp " + (if (r.isNotEmpty()) "--referer " + q(r) + " " else "") + "-o " + q("$base.%(ext)s") + " " + q(e.url)
            val ffmpeg = "ffmpeg " + (if (r.isNotEmpty()) "-referer " + q(r) + " " else "") + "-i " + q(e.url) + " -c copy " + q(base + (if (audio) ".m4a" else ".mp4"))
            val cext = if (e.kind == "hls") "ts" else e.ext.ifBlank { "mp4" }
            val curl = "curl -L " + (if (r.isNotEmpty()) "-e " + q(r) + " " else "") + "-o " + q("$base.$cext") + " " + q(e.url)
            fun put(t: String) { (act.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("command", t)); toast("Copied") }
            sheet("Copy as command", itemHead(e, "Paste it in a terminal on your computer"), listOf(
                SRow(R.drawable.ic_code, "yt-dlp") { put(ytdlp) },
                SRow(R.drawable.ic_code, "ffmpeg") { put(ffmpeg) },
                SRow(R.drawable.ic_code, "cURL") { put(curl) }
            ))
        }

        private fun subFmtSheet(e: DItem) {
            fun go(f: String) { startJob(e, null, null, f); toast("Subtitle download started") }
            sheet("Save subtitle as", itemHead(e, "Subtitle"), listOf(
                SRow(R.drawable.ic_list, ".srt  (works in most players)") { go("srt") },
                SRow(R.drawable.ic_list, ".vtt") { go("vtt") },
                SRow(R.drawable.ic_list, ".txt  (only the words, no times)") { go("txt") },
                SRow(R.drawable.ic_list, "Original file") { go("orig") }
            ))
        }

        // ---- sort / filter ----

        private fun showSort() {
            val names = arrayOf("Default order", "Newest first", "Oldest first", "Largest first", "Smallest first", "Name Z to A", "Name A to Z", "Video first", "Audio first")
            sheet("Sort", null, names.mapIndexed { i, n ->
                SRow(0, n, i == sort) {
                    sort = i
                    act.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt("sort", i).apply()
                    sig = ""
                    render()
                }
            })
        }

        private fun showFilter() {
            val d = BottomSheetDialog(act)
            d.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            val root = LinearLayout(act).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = ViewGroup.LayoutParams(MATCH, (act.resources.displayMetrics.heightPixels * 0.85f).toInt())
            }
            val box = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(16), dp(12), dp(8)) }
            box.addView(tv("Filter", 20f, cText, 1, true).apply { setPadding(dp(8), 0, dp(8), dp(4)) })
            fun label(t: String) = box.addView(tv(t, 12f, cDim).apply { setPadding(dp(8), dp(14), 0, dp(6)) })
            fun input(hint: String, value: String, number: Boolean) = EditText(act).apply {
                setText(value)
                this.hint = hint
                textSize = 15f
                setSingleLine()
                setTextColor(cText)
                setHintTextColor(cDim)
                background = round(cTile, 14)
                setPadding(dp(14), 0, dp(14), 0)
                if (number) inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            }
            fun num(f: Float) = if (f <= 0f) "" else if (f == f.toInt().toFloat()) f.toInt().toString() else f.toString()
            fun pair(a: EditText, b: EditText) {
                val r = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL }
                r.addView(a, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginEnd = dp(8) })
                r.addView(b, LinearLayout.LayoutParams(0, dp(48), 1f))
                box.addView(r, LinearLayout.LayoutParams(MATCH, WRAP))
            }
            label("File name contains")
            val name = input("any", filter.name, false)
            box.addView(name, LinearLayout.LayoutParams(MATCH, dp(48)))
            label("Size (MB)")
            val minMb = input("Min", num(filter.minMb), true)
            val maxMb = input("Max", num(filter.maxMb), true)
            pair(minMb, maxMb)
            label("Time (SEC)")
            val minS = input("Min", num(filter.minSec), true)
            val maxS = input("Max", num(filter.maxSec), true)
            pair(minS, maxS)

            label("Media type")
            var tv0 = filter.video; var ta0 = filter.audio; var ti0 = filter.image; var ts0 = filter.subtitle
            val trow = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL }
            fun toggleChip(text: String, get: () -> Boolean, set: (Boolean) -> Unit) {
                lateinit var c: TextView
                fun paint() {
                    val on = get()
                    c.setTextColor(if (on) cOnPrimary else cText)
                    c.background = if (on) round(cPrimary, 17) else GradientDrawable().apply {
                        setColor(cCard); cornerRadius = dp(17).toFloat(); setStroke(dp(1), cLine)
                    }
                }
                c = tv(text, 13f, cText, 1, true).apply {
                    gravity = Gravity.CENTER
                    setPadding(dp(14), 0, dp(14), 0)
                    setOnClickListener { set(!get()); paint() }
                }
                paint()
                trow.addView(c, LinearLayout.LayoutParams(WRAP, dp(34)).apply { marginEnd = dp(8) })
            }
            toggleChip("Video", { tv0 }, { tv0 = it })
            toggleChip("Audio", { ta0 }, { ta0 = it })
            toggleChip("Image", { ti0 }, { ti0 = it })
            toggleChip("Subtitle", { ts0 }, { ts0 = it })
            box.addView(HorizontalScrollView(act).apply { isHorizontalScrollBarEnabled = false; addView(trow) }, LinearLayout.LayoutParams(MATCH, WRAP))

            label("More")
            fun sw(text: String, on: Boolean): SwitchCompat {
                val v = SwitchCompat(act).apply {
                    this.text = text
                    isChecked = on
                    ThazirSwitch.tint(this) // A10
                    textSize = 15f
                    setTextColor(cText)
                    setPadding(dp(8), 0, dp(8), 0)
                }
                box.addView(v, LinearLayout.LayoutParams(MATCH, dp(48)))
                return v
            }
            val ce = sw("Show experimental links", filter.experimental)
            val cn = sw("Show video links which don't have audio", filter.noAudio)
            label("Custom extensions (only these, empty = all)")
            val ext = input("mp4, mkv, m3u8", filter.ext, false)
            box.addView(ext, LinearLayout.LayoutParams(MATCH, dp(48)))

            root.addView(NestedScrollView(act).apply { addView(box) }, LinearLayout.LayoutParams(MATCH, 0, 1f))

            val btns = LinearLayout(act).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(14), dp(8), dp(14), dp(12))
            }
            fun textBtn(label: String, on: () -> Unit) = tv(label, 14f, cDim, 1, true).apply {
                gravity = Gravity.CENTER
                setPadding(dp(14), 0, dp(14), 0)
                ripple(this)
                setOnClickListener { on() }
            }
            btns.addView(textBtn("RESET") {
                filter.reset(); filter.save(act); sig = ""; render(); d.dismiss()
            }, LinearLayout.LayoutParams(WRAP, dp(44)))
            btns.addView(View(act), LinearLayout.LayoutParams(0, 1, 1f))
            btns.addView(textBtn("CANCEL") { d.dismiss() }, LinearLayout.LayoutParams(WRAP, dp(44)))
            btns.addView(tv("OK", 15f, cOnPrimary, 1, true).apply {
                gravity = Gravity.CENTER
                background = round(cPrimary, 22)
                setPadding(dp(28), 0, dp(28), 0)
                setOnClickListener {
                    filter.name = name.text.toString()
                    filter.minMb = minMb.text.toString().toFloatOrNull() ?: 0f
                    filter.maxMb = maxMb.text.toString().toFloatOrNull() ?: 0f
                    filter.minSec = minS.text.toString().toFloatOrNull() ?: 0f
                    filter.maxSec = maxS.text.toString().toFloatOrNull() ?: 0f
                    filter.video = tv0; filter.audio = ta0; filter.image = ti0; filter.subtitle = ts0
                    filter.experimental = ce.isChecked; filter.noAudio = cn.isChecked
                    filter.ext = ext.text.toString()
                    filter.save(act)
                    sig = ""
                    render()
                    d.dismiss()
                }
            }, LinearLayout.LayoutParams(WRAP, dp(44)).apply { marginStart = dp(6) })
            root.addView(btns, LinearLayout.LayoutParams(MATCH, WRAP))

            d.setContentView(root)
            sheetStyle(d)
            d.behavior.skipCollapsed = true
            d.show()
            d.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        }
    }
}
