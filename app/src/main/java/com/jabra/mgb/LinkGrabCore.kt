package com.jabra.mgb

/**
 * 0.0.405: pure text logic of LinkGrab (no Android, no network): which site a link is, and how to find the real file address inside the
 * page text / JSON a site returns (Facebook, Pinterest, Reddit, Discord). Never throws; no match = null.
 * Kept apart from LinkGrab.kt so it can be compiled and tested on its own (see HANDOFF 0.0.405).
 */
internal object LinkGrabCore {
    class Part(val url: String, val fileName: String, val mime: String)
    class Hit(val parts: List<Part>, val note: String = "")

    // ---------- site detection ----------
    private val HOST = Regex("^https?://(?:[^/?#@]*@)?([^/?#:]+)", RegexOption.IGNORE_CASE)
    private val FILE_EXT = Regex("\\.(mp4|m4v|webm|mkv|mov|3gp|mp3|m4a|ogg|opus|wav|flac|gif)$", RegexOption.IGNORE_CASE)
    private val IMG_EXT = Regex("\\.(jpe?g|png|webp|gif|avif)$", RegexOption.IGNORE_CASE)
    private val STREAM_EXT = Regex("\\.(m3u8|mpd)$", RegexOption.IGNORE_CASE)
    private val PIN_HOST = Regex("^(?:[a-z0-9-]+\\.)*pinterest\\.[a-z.]{2,7}$")
    private val FB_HOST = Regex("^(?:[a-z0-9-]+\\.)*(facebook\\.com|fb\\.watch|fb\\.com|fb\\.me)$")
    private val IMG_CDN = setOf("i.redd.it", "i.pinimg.com", "cdn.discordapp.com", "media.discordapp.net", "images-ext-1.discordapp.net")
    private val DISCORD_CDN = setOf("cdn.discordapp.com", "media.discordapp.net")

    fun hostOf(u: String): String = HOST.find(u.trim())?.groupValues?.get(1)?.lowercase()?.removePrefix("www.") ?: ""

    private fun pathOf(u: String): String = u.trim().substringBefore('#').substringBefore('?').substringAfter("://").substringAfter('/', "")

    /** fb, pin, reddit, vredd, direct, stream, discord, other */
    fun kindOf(url: String): String {
        val h = hostOf(url)
        if (h.isEmpty()) return "other"
        val path = pathOf(url)
        val clean = "/$path"
        if (h == "v.redd.it") return "vredd"
        if (FILE_EXT.containsMatchIn(clean)) return "direct"
        if (h in IMG_CDN && IMG_EXT.containsMatchIn(clean)) return "direct"
        if (STREAM_EXT.containsMatchIn(clean)) return "stream"
        return when {
            h == "redd.it" || h == "reddit.com" || h.endsWith(".reddit.com") -> "reddit"
            h == "pin.it" || PIN_HOST.matches(h) -> "pin"
            FB_HOST.matches(h) -> "fb"
            h in DISCORD_CDN -> if (clean.contains("/attachments/") || clean.contains("/ephemeral-attachments/")) "direct" else "other"
            h == "discord.com" || h.endsWith(".discord.com") || h == "discordapp.com" -> "discord"
            else -> "other"
        }
    }

    /** True when a copied (clipboard) link is worth a card: a real video / post link, not a profile or home page. */
    fun worthCard(url: String): Boolean {
        val k = kindOf(url)
        val p = "/" + pathOf(url).lowercase()
        return when (k) {
            "direct", "stream", "vredd" -> true
            "pin" -> hostOf(url) == "pin.it" || p.contains("/pin/")
            "reddit" -> hostOf(url) == "redd.it" || p.contains("/comments/") || p.contains("/s/")
            "fb" -> hostOf(url).let { it == "fb.watch" || it == "fb.me" } ||
                Regex("/(watch|reel|reels|videos?|share/[vr]|story\\.php|permalink\\.php|photo)").containsMatchIn(p) || url.contains("v=")
            else -> false
        }
    }

    fun siteName(kind: String): String = when (kind) {
        "fb" -> "Facebook"; "pin" -> "Pinterest"; "reddit", "vredd" -> "Reddit"; "discord" -> "Discord"; else -> "link"
    }

    // ---------- text helpers ----------
    private val UNI = Regex("\\\\u([0-9a-fA-F]{4})")

    /** JSON / HTML escapes found in page text: \/  \uXXXX  &amp; */
    fun unesc(s: String): String {
        var t = s.replace("\\/", "/").replace("&amp;", "&")
        if (t.contains("\\u")) t = UNI.replace(t) { it.groupValues[1].toInt(16).toChar().toString() }
        return t.replace("\\\"", "\"").replace("\\\\", "\\")
    }

    fun safeName(s: String?, fallback: String): String {
        var t = unesc(s ?: "").replace(Regex("[\\u0000-\\u001f\\\\/:*?\"<>|#%]"), " ").replace(Regex("\\s+"), " ").trim().trim('.', ' ')
        if (t.length > 80) t = t.take(80).trim()
        return t.ifEmpty { fallback }
    }

    private fun meta(html: String, prop: String): String? {
        val r1 = Regex("<meta[^>]+(?:property|name)=[\"']" + Regex.escape(prop) + "[\"'][^>]*?content=[\"']([^\"']*)[\"']", RegexOption.IGNORE_CASE)
        val r2 = Regex("<meta[^>]+content=[\"']([^\"']*)[\"'][^>]*?(?:property|name)=[\"']" + Regex.escape(prop) + "[\"']", RegexOption.IGNORE_CASE)
        return (r1.find(html) ?: r2.find(html))?.groupValues?.get(1)?.let { unesc(it) }?.takeIf { it.isNotBlank() }
    }

    fun titleOf(html: String): String? =
        meta(html, "og:title") ?: Regex("<title[^>]*>([^<]{1,300})</title>", RegexOption.IGNORE_CASE).find(html)?.groupValues?.get(1)?.let { unesc(it) }

    // ---------- Facebook ----------
    private val FB_KEYS = listOf("browser_native_hd_url", "playable_url_quality_hd", "hd_src_no_ratelimit", "hd_src",
        "browser_native_sd_url", "playable_url", "sd_src_no_ratelimit", "sd_src")
    private val FB_KEY = Regex("\"(browser_native_hd_url|playable_url_quality_hd|hd_src_no_ratelimit|hd_src|browser_native_sd_url|playable_url|sd_src_no_ratelimit|sd_src)\"\\s*:\\s*\"(https?:[^\"]+)\"")

    /** Best progressive mp4 address inside a Facebook page (HD first, then SD, then og:video). Progressive = sound included. */
    fun pickFacebook(html: String): String? {
        val found = HashMap<String, String>()
        for (m in FB_KEY.findAll(html)) {
            val k = m.groupValues[1]
            if (!found.containsKey(k)) found[k] = unesc(m.groupValues[2])
        }
        for (k in FB_KEYS) found[k]?.let { if (it.startsWith("http")) return it }
        return (meta(html, "og:video:secure_url") ?: meta(html, "og:video:url") ?: meta(html, "og:video"))?.takeIf { it.startsWith("http") && !it.contains(".m3u8") }
    }

    // ---------- Pinterest ----------
    private val PIN_MP4 = Regex("https://v\\d*\\.pinimg\\.com/videos/[^\"'\\s<>\\\\]+?\\.mp4")
    private val RES = Regex("/(\\d{3,4})p/")

    /** (address, isVideo). Video mp4 first (highest resolution); no mp4 = the biggest picture (originals). */
    fun pickPinterest(html: String): Pair<String, Boolean>? {
        val t = html.replace("\\/", "/").replace("&amp;", "&")
        val vids = PIN_MP4.findAll(t).map { it.value }.distinct().toList()
        if (vids.isNotEmpty()) {
            val best = vids.maxByOrNull { v -> RES.find(v)?.groupValues?.get(1)?.toIntOrNull() ?: if (v.contains("/expMp4/")) 720 else 0 }!!
            return Pair(best, true)
        }
        meta(t, "og:video")?.takeIf { it.contains(".mp4") }?.let { return Pair(it, true) }
        val img = meta(t, "og:image") ?: return null
        if (!img.contains("pinimg.com")) return null
        return Pair(img.replace(Regex("/(\\d+x|\\d+x\\d+|[a-z]+_\\d+)/"), "/originals/"), false)
    }

    // ---------- Reddit ----------
    private val COMMENTS_ID = Regex("/comments/([a-z0-9]{4,10})", RegexOption.IGNORE_CASE)
    fun redditPostId(url: String): String? {
        if (hostOf(url) == "redd.it") return pathOf(url).substringBefore('/').takeIf { Regex("^[a-z0-9]{4,10}$", RegexOption.IGNORE_CASE).matches(it) }
        return COMMENTS_ID.find(url)?.groupValues?.get(1)
    }
    fun redditJsonUrl(id: String): String = "https://www.reddit.com/comments/$id.json?raw_json=1&limit=1"

    private fun firstJson(json: String, key: String): String? =
        Regex("\"" + Regex.escape(key) + "\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").find(json)?.groupValues?.get(1)?.let { unesc(it) }

    /** Candidate audio addresses for a v.redd.it video address (Reddit keeps sound in a separate file). */
    fun redditAudioCandidates(videoUrl: String): List<String> {
        val base = videoUrl.substringBefore('?').substringBeforeLast('/')
        val seg = videoUrl.substringBefore('?').substringAfterLast('/')
        val first = if (seg.startsWith("CMAF", true)) "CMAF" else "DASH"
        val second = if (first == "CMAF") "DASH" else "CMAF"
        return listOf("$base/${first}_AUDIO_128.mp4", "$base/${first}_AUDIO_64.mp4", "$base/${first}_audio.mp4",
            "$base/${second}_AUDIO_128.mp4", "$base/${second}_AUDIO_64.mp4")
    }

    /** Reads the first post of a reddit .json answer. Returns the video (audio address is added later by LinkGrab), or a picture. */
    fun pickReddit(jsonAll: String): Hit? {
        val lst = Regex("\"kind\"\\s*:\\s*\"Listing\"").findAll(jsonAll).toList()
        val json = if (lst.size >= 2) jsonAll.substring(0, lst[1].range.first) else jsonAll // 1st listing = the post, 2nd = comments
        val title = safeName(firstJson(json, "title"), "reddit")
        val fb = firstJson(json, "fallback_url")?.substringBefore("?source")?.takeIf { it.startsWith("http") }
        if (fb != null) {
            val gif = Regex("\"is_gif\"\\s*:\\s*true").containsMatchIn(json)
            return Hit(listOf(Part(fb, "$title.mp4", "video/mp4")), if (gif) "gif" else "video")
        }
        val img = firstJson(json, "url_overridden_by_dest") ?: firstJson(json, "url")
        if (img != null && img.startsWith("http") && (hostOf(img) in IMG_CDN) && IMG_EXT.containsMatchIn("/" + pathOf(img))) {
            val ext = pathOf(img).substringAfterLast('.').lowercase()
            return Hit(listOf(Part(img, "$title.$ext", if (ext == "gif") "image/gif" else "image/" + ext.replace("jpg", "jpeg"))), "image")
        }
        return null
    }
}
