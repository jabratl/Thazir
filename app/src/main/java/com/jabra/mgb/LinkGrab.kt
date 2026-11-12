package com.jabra.mgb

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import java.net.HttpURLConnection
import java.net.URL

/**
 * 0.0.405: "Download" for a copied / shared link (LinkInbox card). Finds the real file behind a Facebook, Pinterest, Reddit or Discord link
 * and starts it as a normal app download (DownloadsApi.startPlain: parts, resume, rules, Downloads list, NameSniff all apply).
 *  - direct file link (mp4 / webm / mp3 / gif ..., Discord cdn attachment, i.redd.it, i.pinimg.com) = downloaded as it is.
 *  - Facebook (facebook.com, fb.watch, /reel, /watch, /share/v|r): page text is read, best HD / SD mp4 (sound included) is used.
 *  - Pinterest (pinterest.*, pin.it): best mp4 in the pin page; a picture pin gives the "originals" picture.
 *  - Reddit (reddit.com post, /s/ share link, redd.it, v.redd.it): post .json gives the video; Reddit keeps sound in a separate file,
 *    so the audio file is saved next to it ("name (audio).m4a") when it exists.
 *  - Discord message links (discord.com/channels/...) need a login: not possible, the person gets a clear message.
 *  - anything else / any failure = onDone(false, reason) and the card opens the page in a tab (old behaviour: sniffer + Files detected).
 * Requests go only to the site of the link (and its own CDN), with the browser's own cookies for that site. Nothing is sent anywhere else.
 * Runs on one short worker thread; the download itself starts on the main thread. Never throws.
 */
object LinkGrab {
    private val main = Handler(Looper.getMainLooper())
    private const val UA_DESK = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
    private const val UA_BOT = "facebookexternalhit/1.1 (+http://www.facebook.com/externalhit_uatext.php)"

    private class Page(val code: Int, val body: String, val finalUrl: String)
    private class Found(val hit: LinkGrabCore.Hit?, val why: String, val referer: String, val ua: String)

    /** Cheap check (no network): can LinkGrab even try this link? */
    fun supported(url: String): Boolean = LinkGrabCore.kindOf(url) in setOf("fb", "pin", "reddit", "vredd", "direct", "discord")

    /** onDone(ok, text): text = file name on success, short reason on failure. Called on the main thread. */
    fun grab(act: Activity, url: String, onDone: (Boolean, String) -> Unit) {
        val u = url.trim()
        if (!supported(u)) { onDone(false, "No direct file found"); return }
        Thread {
            val f = try { resolve(u) } catch (e: Throwable) { Diag.e("grab", "resolve crashed", e); Found(null, "Could not read the link", "", UA_DESK) }
            main.post {
                try {
                    val hit = f.hit
                    if (hit == null) { onDone(false, f.why); return@post }
                    var n = 0
                    for (p in hit.parts) {
                        val disp = "attachment; filename=\"" + p.fileName.replace("\"", "") + "\""
                        if (DownloadsApi.startPlain(p.url, f.ua, disp, p.mime, f.referer, null) != null) n++
                    }
                    if (n == 0) onDone(false, "Could not start the download")
                    else onDone(true, hit.parts.first().fileName + (if (n > 1) " (+ audio)" else ""))
                } catch (e: Throwable) {
                    Diag.e("grab", "start crashed", e)
                    onDone(false, "Could not start the download")
                }
            }
        }.start()
    }

    // ---------- resolve (worker thread) ----------
    private fun resolve(url: String): Found {
        val kind = LinkGrabCore.kindOf(url)
        val site = LinkGrabCore.siteName(kind)
        return when (kind) {
            "direct" -> Found(LinkGrabCore.Hit(listOf(LinkGrabCore.Part(url, nameFromUrl(url), mimeFromUrl(url)))), "", origin(url), UA_DESK)
            "discord" -> Found(null, "Discord message links need a login. Long-press the video in Discord > Copy link (cdn.discordapp.com), then copy it here", "", UA_DESK)
            "fb" -> facebook(url)
            "pin" -> pinterest(url)
            "reddit" -> reddit(url)
            "vredd" -> vreddit(url)
            else -> Found(null, "$site: not supported", "", UA_DESK)
        }
    }

    private fun origin(u: String): String = try { val x = URL(u); x.protocol + "://" + x.host + "/" } catch (e: Exception) { "" }

    private fun nameFromUrl(u: String): String {
        val raw = u.substringBefore('#').substringBefore('?').substringAfterLast('/')
        val dec = try { java.net.URLDecoder.decode(raw, "UTF-8") } catch (e: Exception) { raw }
        return LinkGrabCore.safeName(dec, "download")
    }

    private fun mimeFromUrl(u: String): String {
        val e = u.substringBefore('#').substringBefore('?').substringAfterLast('.', "").lowercase()
        return when (e) {
            "mp4", "m4v" -> "video/mp4"; "webm" -> "video/webm"; "mkv" -> "video/x-matroska"; "mov" -> "video/quicktime"; "3gp" -> "video/3gpp"
            "mp3" -> "audio/mpeg"; "m4a" -> "audio/mp4"; "ogg", "opus" -> "audio/ogg"; "wav" -> "audio/wav"; "flac" -> "audio/flac"
            "gif" -> "image/gif"; "png" -> "image/png"; "webp" -> "image/webp"; "avif" -> "image/avif"; "jpg", "jpeg" -> "image/jpeg"
            else -> "application/octet-stream"
        }
    }

    /** GET text (redirects followed, browser cookies of that site added). null = no answer at all. */
    private fun get(url: String, ua: String, maxBytes: Int = 6_000_000): Page? {
        var c: HttpURLConnection? = null
        return try {
            c = URL(url).openConnection() as HttpURLConnection
            c.instanceFollowRedirects = true
            c.connectTimeout = 10000; c.readTimeout = 12000
            c.setRequestProperty("User-Agent", ua)
            c.setRequestProperty("Accept", "text/html,application/xhtml+xml,application/json;q=0.9,*/*;q=0.8")
            c.setRequestProperty("Accept-Language", "en-US,en;q=0.9")
            try { CookieManager.getInstance().getCookie(url)?.let { c.setRequestProperty("Cookie", it) } } catch (e: Exception) { }
            val code = c.responseCode
            val fin = c.url.toString()
            val st = if (code in 200..299) c.inputStream else c.errorStream
            val sb = StringBuilder()
            if (st != null) st.bufferedReader(Charsets.UTF_8).use { r ->
                val buf = CharArray(16384)
                while (sb.length < maxBytes) { val n = r.read(buf); if (n < 0) break; sb.append(buf, 0, n) }
            }
            Page(code, sb.toString(), fin)
        } catch (e: Exception) {
            Diag.w("grab", "get failed", url.substringBefore('?'))
            null
        } finally { try { c?.disconnect() } catch (e: Exception) { } }
    }

    private fun exists(url: String, referer: String): Boolean {
        var c: HttpURLConnection? = null
        return try {
            c = URL(url).openConnection() as HttpURLConnection
            c.requestMethod = "HEAD"; c.connectTimeout = 6000; c.readTimeout = 6000
            c.setRequestProperty("User-Agent", UA_DESK); c.setRequestProperty("Referer", referer)
            c.responseCode == 200
        } catch (e: Exception) { false } finally { try { c?.disconnect() } catch (e: Exception) { } }
    }

    private fun mp4Hit(url: String, name: String) = LinkGrabCore.Hit(listOf(LinkGrabCore.Part(url, "$name.mp4", "video/mp4")))

    private fun facebook(url: String): Found {
        val ref = "https://www.facebook.com/"
        // 1) normal browser page (the person's own Facebook cookies are used when the page needs them), 2) the link-preview page
        for (ua in listOf(UA_DESK, UA_BOT)) {
            val p = get(url, ua) ?: continue
            val v = LinkGrabCore.pickFacebook(p.body)
            if (v != null) {
                val name = "Facebook " + LinkGrabCore.safeName(LinkGrabCore.titleOf(p.body), "video")
                return Found(mp4Hit(v, name), "", ref, ua)
            }
        }
        return Found(null, "Facebook: no public video found (private or login needed)", ref, UA_DESK)
    }

    private fun pinterest(url: String): Found {
        val ref = "https://www.pinterest.com/"
        val p = get(url, UA_DESK) ?: return Found(null, "Pinterest did not answer", ref, UA_DESK)
        val r = LinkGrabCore.pickPinterest(p.body) ?: return Found(null, "Pinterest: no video or picture found in this pin", ref, UA_DESK)
        val name = "Pinterest " + LinkGrabCore.safeName(LinkGrabCore.titleOf(p.body), "pin")
        return if (r.second) Found(mp4Hit(r.first, name), "", ref, UA_DESK)
        else {
            val ext = r.first.substringBefore('?').substringAfterLast('.', "jpg").lowercase().take(4)
            Found(LinkGrabCore.Hit(listOf(LinkGrabCore.Part(r.first, "$name.$ext", mimeFromUrl(r.first)))), "", ref, UA_DESK)
        }
    }

    private fun reddit(url: String): Found {
        val ref = "https://www.reddit.com/"
        var id = LinkGrabCore.redditPostId(url)
        if (id == null) { // /s/ share link: follow the redirect to the real post address
            val p = get(url, UA_DESK, 2000) ?: return Found(null, "Reddit did not answer", ref, UA_DESK)
            id = LinkGrabCore.redditPostId(p.finalUrl)
        }
        if (id == null) return Found(null, "Reddit: not a post link", ref, UA_DESK)
        var json: Page? = null
        for (ua in listOf(UA_DESK, "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36")) {
            json = get(LinkGrabCore.redditJsonUrl(id), ua)
            if (json != null && json.code == 200) break
        }
        if (json == null || json.code != 200) return Found(null, "Reddit refused (" + (json?.code ?: "no answer") + "). Try again later", ref, UA_DESK)
        val hit = LinkGrabCore.pickReddit(json.body) ?: return Found(null, "Reddit: no video or picture in this post", ref, UA_DESK)
        return Found(withAudio(hit, ref), "", ref, UA_DESK)
    }

    private fun vreddit(url: String): Found {
        val ref = "https://www.reddit.com/"
        val id = Regex("v\\.redd\\.it/([a-z0-9]+)", RegexOption.IGNORE_CASE).find(url)?.groupValues?.get(1)
            ?: return Found(null, "Reddit: bad video link", ref, UA_DESK)
        for (style in listOf("DASH", "CMAF")) for (q in listOf(1080, 720, 480, 360, 240)) {
            val v = "https://v.redd.it/$id/${style}_$q.mp4"
            if (exists(v, ref)) return Found(withAudio(mp4Hit(v, "Reddit $id"), ref), "", ref, UA_DESK)
        }
        return Found(null, "Reddit: video file not found", ref, UA_DESK)
    }

    /** v.redd.it video has no sound inside: look for the separate audio file and add it as a 2nd download. */
    private fun withAudio(hit: LinkGrabCore.Hit, ref: String): LinkGrabCore.Hit {
        if (hit.note == "gif" || hit.note == "image") return hit
        val v = hit.parts.firstOrNull() ?: return hit
        if (!v.url.contains("v.redd.it")) return hit
        for (a in LinkGrabCore.redditAudioCandidates(v.url)) {
            if (exists(a, ref)) return LinkGrabCore.Hit(listOf(v, LinkGrabCore.Part(a, v.fileName.removeSuffix(".mp4") + " (audio).m4a", "audio/mp4")), "video+audio")
        }
        return hit
    }
}
