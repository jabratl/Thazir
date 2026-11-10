package com.jabra.mgb

/**
 * S86: Diagnose part 5a. Rule engine: facts about the app / page / downloads -> list of Finding
 * (what happened + what to do + optional one tap fix). Rules 1-20 (S86) + rules 21-40 (S87.1, see rules21to40) + rules 41-54 (S89.4, manga page list codes, see rules41to54) + rules 55-72 (S89.5, manga page load codes, see rules55to72) + rules 73-87 (S89.6, manga reader / save codes, see rules73to87) + rules 88-92 (S8, installed extensions, see rules88to92).
 * Pure logic, no UI, no Android calls (DiagReport collects the Facts).
 *
 * fix ids (DiagScreen.runFix): wifi, storage, webview, reload, settings, downloads, notif, appinfo.
 */
object DiagRules {
    class Finding(
        val id: String,
        val level: String,      // DiagReport.BAD / WARN / INFO
        val what: String,       // what happened (1 line)
        val todo: String,       // what to do (1-2 lines)
        val fix: String? = null,
        val fixLabel: String? = null
    )

    /** Everything the rules look at. All values are plain data, filled by DiagReport.assemble. */
    class Facts {
        var online = true               // validated internet
        var hasNetwork = true           // any active network at all
        var vpn = false
        var freeBytes = -1L
        var ramLow = false
        var wvMajor = 0                 // 0 = unknown
        var tabCrashed = false
        var pageIsWeb = false
        var pageLoading = false
        var pageErrors: List<String> = emptyList()   // msgs of tag "page" in the last 10 min ("load error -2 ..." / "http 403")
        var pageErrorUrls: List<String> = emptyList() // masked urls of those events
        var httpsOnly = false
        var adblock = false
        var folderSet = true
        var folderLost = false          // 0.0.397: a folder was picked but its permission is gone
        var notifOff = false            // 0.0.399: notifications switched off for the app (any Android version)
        var activeDownloads = 0         // 0.0.399: running or resumable downloads
        var exitLowMem = 0              // 0.0.397: OS exits with LOW_MEMORY in the last 24 h
        var exitAnr = 0                 // 0.0.397: OS exits with ANR in the last 24 h
        var engineAnswered = true       // get-state answered (only meaningful when pageIsWeb)
        var engineErrors = 0            // engine console errors in the last 5 min
        var detected = 0
        var dlCodes: List<String> = emptyList()      // failed codes in the last 10 min (FILE_NO_SPACE ...)
        var sniffFails = 0              // sniffer probe failed in the last 10 min
        var partsMsgs: List<String> = emptyList()    // 0.0.361: Diag tag dlparts msgs (lower-case), last 10 min
        var notifDenied = false         // API 33+ and POST_NOTIFICATIONS not granted
        var bgRestricted = false        // API 28+ background restricted
        // ---- S87.1 (all lower-case text, already masked; empty list = nothing known)
        var novelMsgs: List<String> = emptyList()    // Diag tag novel msgs + extra, last 10 min
        var mangaMsgs: List<String> = emptyList()    // Diag tag manga, last 10 min
        var logLines: List<String> = emptyList()     // V77 mgLog last 30 lines
        var hlsNoList = 0               // hls / dash items whose quality list could not be read
        var drmItems = 0
        var blobItems = 0               // detected urls that start with blob:
        var jobErrors: List<String> = emptyList()    // error text of failed V77 jobs (masked)
        var failedJobUrls: List<String> = emptyList() // masked url of each failed job / failed download (last 10 min)
        var runningJobs = 0
        var scheduleMsgs: List<String> = emptyList() // Diag tag schedule, last 24 h
        var extMsgs: List<String> = emptyList()      // S8: Diag tag ext (worker errors / parked / not started, extension page crash, blocked or refused extension), last 10 min
        // ---- S89.0: stable codes from V77 mgLog lines "[area] code=<id> ..." (written by dbgc in shared.js)
        var mgCodes: Map<String, Int> = emptyMap()         // key "area:code" (e.g. "manga:403-blocked") -> times seen in the last 30 min (repeats=N counted)
        var mgCodeNotes: Map<String, String> = emptyMap()  // same key -> detail of the newest line (masked, lower-case, max 200)
        fun code(key: String): Int = mgCodes[key] ?: 0
    }

    private const val LOW_STORAGE = 200L * 1024 * 1024

    private fun f(id: String, level: String, what: String, todo: String, fix: String? = null, label: String? = null) =
        Finding(id, level, what, todo, fix, label)

    /** Sorted: Problem first, then Warning, then Info. Same order inside a level = rule number. */
    fun diagnose(x: Facts): List<Finding> {
        val out = ArrayList<Finding>()
        val B = DiagReport.BAD
        val W = DiagReport.WARN
        val I = DiagReport.INFO

        // 1-3 network
        if (!x.hasNetwork) out.add(f("net-none", B, "The phone has no network.", "Turn on Wi-Fi or mobile data, then try again.", "wifi", "Open Wi-Fi settings"))
        else if (!x.online) out.add(f("net-captive", B, "Connected, but the internet does not answer (captive portal or no data).", "Open the Wi-Fi sign-in page, or switch to mobile data.", "wifi", "Open Wi-Fi settings"))
        if (x.vpn && (x.pageErrors.any { it.startsWith("load error") } || x.dlCodes.any { it.startsWith("NETWORK") }))
            out.add(f("net-vpn", W, "A VPN is on and loading / downloading failed.", "Many sites and video servers block VPN addresses. Turn the VPN off and retry."))

        // 4-6 storage / memory
        if (x.freeBytes in 0 until LOW_STORAGE) out.add(f("store-low", B, "Free storage is under 200 MB.", "Free some space; downloads and merging need room for the temporary file too.", "storage", "Open storage settings"))
        if (x.dlCodes.contains("FILE_NO_SPACE")) out.add(f("dl-nospace", B, "A download stopped: the phone is out of space.", "Delete files or choose a download folder on a card with more space, then resume.", "storage", "Open storage settings"))
        if (x.exitLowMem + x.exitAnr >= 3) out.add(f("exit-lowmem-anr", W, "Android ended MGB ${x.exitLowMem + x.exitAnr} times in 24 h (low memory ${x.exitLowMem}, not responding ${x.exitAnr}).", "Close other apps and tabs. If it keeps happening, copy the report and send it (see App exits and Last crash).")) // 0.0.397
        if (x.ramLow) out.add(f("ram-low", W, "The phone is low on memory.", "Close other tabs and apps. Tabs get killed when memory runs out."))

        // 7-8 tab / WebView
        if (x.tabCrashed) out.add(f("tab-crash", B, "A tab crashed or was killed by the system in the last 5 minutes.", "Reload the page. If it repeats, close other tabs (low memory) or update WebView.", "reload", "Reload page"))
        if (x.wvMajor in 1..99) out.add(f("webview-old", W, "Android System WebView is old (version ${x.wvMajor}).", "Update Android System WebView (and Chrome) in Play Store. Old versions break many players.", "webview", "Open Play Store"))

        // 9-13 page errors
        val http = x.pageErrors.mapNotNull { Regex("^http (\\d{3})").find(it)?.groupValues?.get(1)?.toIntOrNull() }
        if (http.contains(403) || http.contains(401)) out.add(f("http-403", W, "The site refused access (HTTP ${if (http.contains(403)) 403 else 401}).", "Log in on the site, reload the page, or the link has expired: open the video page again to get a fresh link.", "reload", "Reload page"))
        if (http.any { it == 404 || it == 410 }) out.add(f("http-404", W, "The page or file is gone (HTTP ${http.first { it == 404 || it == 410 }}).", "The link is old or wrong. Go back to the site and open it again."))
        if (http.any { it == 429 || it in 500..599 }) out.add(f("http-busy", W, "The site is busy or limits requests (HTTP ${http.first { it == 429 || it in 500..599 }}).", "Wait a few minutes, lower the download concurrency in Settings, then retry.", "settings", "Open Settings"))
        val le = x.pageErrors.firstOrNull { it.startsWith("load error") }
        if (le != null) {
            val code = le.removePrefix("load error ").substringBefore(' ').toIntOrNull() ?: 0
            val why = when (code) {
                -2 -> "The address could not be found (DNS). Check the spelling or your connection."
                -6 -> "The server refused the connection."
                -7 -> "The connection timed out. The site or your network is slow."
                -8 -> "The connection timed out."
                -11, -12 -> "A secure connection (SSL / certificate) problem. Check the phone date and time."
                -10 -> "The page tried to open an app link the browser cannot handle."
                else -> "The page could not load (code $code)."
            }
            out.add(f("page-load", W, why, "Check the connection and reload.", "reload", "Reload page"))
        }
        if (x.httpsOnly && x.pageErrorUrls.any { it.startsWith("http://") })
            out.add(f("https-only", W, "HTTPS-only blocked or broke a plain http:// page.", "Open the site with https://, or turn HTTPS-only off in Settings for this site.", "settings", "Open Settings"))

        // 14-16 download failure codes
        if (x.dlCodes.contains("NETWORK_TIMEOUT")) out.add(f("dl-timeout", W, "A download timed out.", "The server is slow or the connection dropped. Press Resume; if it repeats, try Wi-Fi.", "downloads", "Open Downloads"))
        if (x.dlCodes.contains("NETWORK_FAILED")) out.add(f("dl-network", W, "A download lost the connection.", "Check Wi-Fi / data and press Resume. An expired link needs a fresh one from the page.", "downloads", "Open Downloads"))
        if (x.partsMsgs.any { it.startsWith("fallback to single connection") || it.startsWith("server limits connections") }) out.add(f("dl-parts-fallback", I, "Download in parts fell back: the server may not allow several connections.", "If the download failed, try fewer parts (Settings > Downloads > Download in parts) or switch it off. Open Queue / jobs in the report to see the HTTP code.", "settings", "Open settings"))
        if (x.dlCodes.contains("FILE_FAILED")) out.add(f("dl-file", B, "A download could not write the file.", "Choose the download folder again in Settings (the folder may be gone or no longer allowed).", "settings", "Open Settings"))

        // 17-19 grabber / sniffer / blocker
        if (x.sniffFails >= 3) out.add(f("sniff-fail", W, "Several media checks failed (${x.sniffFails}).", "The site blocks the checker or the links expire fast. Play the video for a few seconds, then open Detected files again."))
        if (x.pageIsWeb && !x.engineAnswered) out.add(f("engine-down", W, "Media Grabber did not answer.", "It may still be starting. Wait a moment and press refresh; if it stays, reload the page.", "reload", "Reload page"))
        else if (x.engineErrors > 0) out.add(f("engine-err", W, "Media Grabber logged ${x.engineErrors} error(s) in the last 5 minutes.", "Open Recent errors below. Copy the report if you want to send it."))
        if (x.pageIsWeb && x.adblock && !x.pageLoading && x.engineAnswered && x.detected == 0 && x.sniffFails > 0)
            out.add(f("adblock-strict", I, "Nothing was detected and the ad blocker is on.", "Some sites hide their player when ads are blocked. Turn the ad blocker off for a test.", "settings", "Open Settings"))

        // 20 setup: folder / permissions
        if (x.folderLost) out.add(f("folder-lost", W, "The folder you picked lost its permission. Files go to Downloads.", "Choose the download folder again in Settings.", "settings", "Open Settings")) // 0.0.397
        if (!x.folderSet && !x.folderLost) out.add(f("folder-unset", I, "No download folder was chosen.", "Files go to the default Downloads folder. Choose a folder in Settings if you want another place.", "settings", "Open Settings"))
        if (x.notifDenied) out.add(f("notif-denied", I, "Notifications are not allowed.", "Download progress will not show in the notification bar. Allow notifications for MGB.", "notif", "Open notification settings"))
        if (x.notifOff && !x.notifDenied && x.activeDownloads > 0) out.add(f("notif-off-downloads", W, "Notifications are off and downloads exist.", "Progress will not show in the notification bar. Turn notifications on for MGB.", "appinfo", "Open app settings")) // 0.0.399
        if (x.bgRestricted) out.add(f("bg-restricted", W, "Android restricts MGB in the background.", "Long downloads may be stopped. Set battery use for MGB to Unrestricted.", "appinfo", "Open app settings"))

        rules21to40(x, out)
        rules41to54(x, out)
        rules55to72(x, out)
        rules73to87(x, out)
        rules88to92(x, out)

        return out.sortedBy { when (it.level) { DiagReport.BAD -> 0; DiagReport.WARN -> 1; else -> 2 } }
    }

    private const val MID_STORAGE = 1024L * 1024 * 1024

    /** S87.1 rules 21-40. Same fix ids as before (no new DiagScreen code needed). Text checks are lower-case "contains". */
    private fun rules21to40(x: Facts, out: ArrayList<Finding>) {
        val B = DiagReport.BAD
        val W = DiagReport.WARN
        val I = DiagReport.INFO
        fun anyHas(l: List<String>, vararg k: String) = l.any { m -> k.any { m.contains(it) } }
        val novelAll = x.novelMsgs + x.logLines.filter { it.contains("novel") }
        val mangaAll = x.mangaMsgs + x.logLines.filter { it.contains("manga") }

        // 21-27 novel
        if (anyHas(x.novelMsgs, "timed out")) out.add(f("novel-timeout", W, "The Novel grabber timed out (180 s).", "The page is slow or very long. Reload it, wait until it is fully loaded, then grab again. Wi-Fi helps.", "reload", "Reload page"))
        if (anyHas(x.novelMsgs, "reloaded")) out.add(f("novel-reloaded", W, "The page reloaded while the Novel grabber was working.", "Do not scroll away or refresh while it runs. Some sites reload by themselves: try again once the page is quiet.", "reload", "Reload page"))
        if (anyHas(x.novelMsgs, "could not load file")) out.add(f("novel-script-missing", B, "A Novel grabber script file could not be loaded.", "The app files look damaged. Update or reinstall MGB.", "appinfo", "Open app settings"))
        if (anyHas(x.novelMsgs, "executescript error")) out.add(f("novel-script-error", W, "The Novel grabber script stopped with an error on this page.", "Reload the page and try again. If it repeats, copy the report; this site may need its own rule."))
        if (anyHas(x.novelMsgs, "mgnativech unreadable")) out.add(f("novel-saved-bad", I, "Saved chapter data could not be read.", "Open the Novel window and grab the chapter list again."))
        if (anyHas(novelAll, "locked", "login required", "premium", "paywall", "coins")) out.add(f("novel-locked", W, "A chapter is locked (login, coins or premium) and the grabber stopped there.", "Log in on the site in this tab, open the chapter once, then continue. Locked text cannot be read without access.", "reload", "Reload page"))
        if (anyHas(novelAll, "no chapter list", "no chapters", "chapter list not found")) out.add(f("novel-nolist", W, "No chapter list was found on this page.", "Open the novel's main (table of contents) page, not a single chapter, then run the grabber again."))

        // 28-30 manga
        if (anyHas(x.mangaMsgs, "mgnativemg unreadable")) out.add(f("manga-saved-bad", I, "Saved manga page data could not be read.", "Open the Manga window and scan the chapter again."))
        if (anyHas(mangaAll, "no pages", "0 pages", "pages not found")) out.add(f("manga-nopages", W, "No manga pages were found.", "Scroll the whole chapter once so every image loads, then run the grabber again.", "reload", "Reload page"))
        if (anyHas(mangaAll, "lazy", "not loaded yet")) out.add(f("manga-lazy", I, "Some manga images were not loaded yet (lazy loading).", "Scroll slowly to the end of the chapter, wait a second, then grab."))

        // 31-35 video kinds
        if (x.hlsNoList > 0) out.add(f("hls-nolist", W, "A streaming (HLS / DASH) list could not be read (${x.hlsNoList}).", "Press Retry in Detected files. If it fails again the link expired: play the video again to get a fresh one.", "reload", "Reload page"))
        if (x.drmItems > 0) out.add(f("drm", W, "${x.drmItems} DRM protected video(s) were found.", "Protected streams (Widevine) cannot be downloaded by any app. Pick another file on the page, if there is one."))
        if (x.blobItems > 0) out.add(f("blob-video", I, "The video address is hidden by the site (blob:).", "Let the video play for a few seconds, then open Detected files again to look for a stream (HLS) or file entry."))
        if (anyHas(x.jobErrors, "403", "401", "expired", "token", "signature", "forbidden")) out.add(f("token-expired", W, "The link looks expired (the site wants a fresh token).", "Open the video page again, play it for a moment, then download from the new entry. Old links stop working after minutes.", "reload", "Reload page"))
        // 0.0.362: a log line only counts when it also says fail / error (the plain "[detect] subtitle <url>" line made a false verdict)
        if (anyHas(x.jobErrors + x.logLines.filter { it.contains("fail") || it.contains("error") }, "subtitle", ".vtt", ".srt")) out.add(f("subtitle-fail", I, "A subtitle file failed.", "The video itself is not affected. Download the subtitle again from Detected files, or skip it."))

        // 36-40 jobs / storage
        // 0.0.362: server answered 5xx (Bilibili 502 when many connections hit the CDN at once)
        if (anyHas(x.jobErrors, "http 502", "http 503", "http 504", "http 500", "http 429") || anyHas(x.logLines, "server pushing back", "stage parts failed")) out.add(f("dl-5xx", W, "The server refused the download (HTTP 5xx / 429): too many connections or a busy server.", "Set Download in parts to 2 or 4 (or switch it off), wait a minute, then download again from a fresh entry in Detected files.", "settings", "Open Settings"))
        if (anyHas(x.jobErrors, "convert", "ffmpeg", "mux", "merge", "size limit", "too large", "too big")) out.add(f("convert-limit", W, "The convert / merge step failed or hit a limit (size, time or memory).", "Close other tabs, keep 1 GB free, pick a lower quality, then retry. Turn off \"merge audio + video\" if it keeps failing.", "settings", "Open Settings"))
        if (anyHas(x.scheduleMsgs, "missed")) out.add(f("schedule-missed", I, "A scheduled download did not start on time.", "Android may have stopped MGB in the background. Set battery use for MGB to Unrestricted and keep the phone on.", "appinfo", "Open app settings"))
        if (x.runningJobs > 0 && x.freeBytes in LOW_STORAGE until MID_STORAGE) out.add(f("store-job", W, "Storage is getting low while a download is running.", "The running job may run out of space. Free at least 1 GB or pause the job.", "storage", "Open storage settings"))
        if (anyHas(x.jobErrors, "already exists", "exists", "duplicate")) out.add(f("dup-file", I, "A file with the same name already exists.", "Rename or delete the old file in Downloads, then retry.", "downloads", "Open Downloads"))
        val rep = x.failedJobUrls.filter { it.isNotBlank() }.groupingBy { it }.eachCount().filter { it.value >= 2 }
        if (rep.isNotEmpty()) out.add(f("repeat-fail", W, "The same address failed ${(rep.values.maxOrNull() ?: 2)} times in the last 10 minutes.", "Retrying the same link will not help. Open the page again for a fresh link, or try Wi-Fi.", "reload", "Reload page"))
    }

    /**
     * S89.4 rules 41-54: manga PAGE LIST codes written by manga.js (S89.1) as "code=<id> host: detail".
     * Read through Facts.code("manga:<id>") (times seen in the last 30 min) and Facts.mgCodeNotes (newest detail).
     * A note with "rescued=tab" means the open source tab still delivered the page list: that is Info, not a problem.
     * Same fix ids as before (reload, settings). Image load codes = S89.5, reader / save codes = S89.6.
     */
    private fun rules41to54(x: Facts, out: ArrayList<Finding>) {
        val B = DiagReport.BAD
        val W = DiagReport.WARN
        val I = DiagReport.INFO
        fun mg(code: String, id: String, level: String, what: String, todo: String, fix: String? = null, label: String? = null, rescuable: Boolean = false) {
            val n = x.code("manga:$code")
            if (n <= 0) return
            val note = x.mgCodeNotes["manga:$code"].orEmpty()
            val host = if (note.contains(":")) note.substringBefore(":").trim().take(60) else ""
            val tail = " (" + n + "x" + (if (host.isNotEmpty()) ", " + host else "") + ")"
            if (rescuable && note.contains("rescued=tab")) out.add(f(id, I, what + tail + " The open tab still got the page list, so the grab worked.", "Nothing to do. Keep the manga page open in its tab while grabbing."))
            else out.add(f(id, level, what + tail, todo, fix, label))
        }
        mg("cloudflare-blocked", "manga-cloudflare", B, "The site showed a check page (Cloudflare) instead of the chapter.", "Open the chapter in this browser tab, pass the check, wait until the first page shows, then grab again.", "reload", "Reload page", true)
        mg("403-blocked", "manga-403", W, "The site refused the page list (403).", "Open the chapter in a normal tab, wait until the first image shows, then grab again. Some sites block grabbers without a visit first.", "reload", "Reload page", true)
        mg("401-login", "manga-401", W, "The site wants a login for this chapter (401).", "Log in on the site in this browser, open the chapter once, then grab again.", "reload", "Reload page", true)
        mg("404-gone", "manga-404", W, "The chapter address is not found any more (404 / 410).", "Open the chapter from the site's own list again to get the new address, then grab.", null, null, true)
        mg("429-slow-down", "manga-429", W, "The site says too many requests (429).", "Wait a few minutes. Raise the delay between pages or lower the concurrency in Settings.", "settings", "Open Settings", true)
        mg("5xx-server", "manga-5xx", W, "The site's server had an error (5xx).", "The site is down or busy. Try again in a few minutes.", null, null, true)
        mg("cors-blocked", "manga-cors", W, "The browser blocked reading the page list (CORS).", "Keep the source tab open and visible, then grab again. MGB reads through that tab when direct reading is blocked.", "reload", "Reload page", true)
        mg("fetch-error", "manga-fetch", W, "The page list could not be fetched (network error).", "Check Wi-Fi / data (a VPN can also cause this), then grab again.", "wifi", "Open Wi-Fi settings", true)
        mg("source-tab-closed", "manga-tab-closed", W, "The source tab was closed while MGB still needed it.", "Keep the manga page open in its tab until the grab has finished, then try again.")
        mg("adapter-failed", "manga-adapter", W, "The site rule for this website failed.", "MGB tries other ways automatically. If no pages come, reload the chapter page and grab again; if it repeats, copy the report (this site may need an update).", "reload", "Reload page")
        mg("fallback-used", "manga-fallback", I, "The site rule failed, so MGB used another method to find the pages.", "Nothing to do, the grab worked. If it repeats, copy the report so the site rule can be fixed.")
        mg("no-page-list", "manga-no-list", B, "No page list could be found for this chapter.", "Open the chapter itself (not the series page), scroll to the first image, then grab again. If it repeats, copy the report.", "reload", "Reload page")
        mg("pages-fewer-than-expected", "manga-fewer", W, "Fewer pages were found than the page says.", "Scroll the whole chapter once so every image loads, then grab again. Check the saved file for missing pages.", "reload", "Reload page")
        mg("thumbnails-only", "manga-thumbs", W, "Most pages found are small (thumbnails, under 400 px wide).", "Open the chapter reader page instead of the gallery overview, wait until images are sharp, then grab again.", "reload", "Reload page")
    }

    /**
     * S89.5 rules 55-72: manga PAGE LOAD codes written by manga.js (S89.2). Same pattern as rules41to54.
     * img-* / not-an-image / corrupt-image lines exist only for pages STILL missing after the retries, so they are Problems.
     * retry-result: newest note "... recovered=N still-missing=M" decides Info (M = 0) or Problem (M > 0).
     * img-http-NNN has a number in the code, so it is found by scanning the keys.
     */
    private fun rules55to72(x: Facts, out: ArrayList<Finding>) {
        val B = DiagReport.BAD
        val W = DiagReport.WARN
        val I = DiagReport.INFO
        fun tailOf(n: Int, note: String): String {
            val host = if (note.contains(":")) note.substringBefore(":").trim().take(60) else ""
            return " (" + n + "x" + (if (host.isNotEmpty()) ", " + host else "") + ")"
        }
        fun mg(code: String, id: String, level: String, what: String, todo: String, fix: String? = null, label: String? = null) {
            val n = x.code("manga:$code")
            if (n <= 0) return
            out.add(f(id, level, what + tailOf(n, x.mgCodeNotes["manga:$code"].orEmpty()), todo, fix, label))
        }
        mg("img-403-blocked", "manga-img-403", B, "The site refused some page images (403), so those pages are missing.", "Open the chapter in a normal tab, wait until every image shows, then press Retry failed. Some sites block image links without a visit first.", "reload", "Reload page")
        mg("img-404-gone", "manga-img-404", B, "Some page images are not found any more (404 / 410), so those pages are missing.", "The image links may have expired. Reload the chapter page to get fresh links, then grab again.", "reload", "Reload page")
        mg("img-429-slow-down", "manga-img-429", B, "The site said too many requests (429) for some images, so those pages are missing.", "Wait a few minutes, then press Retry failed. Raise the delay between pages or lower the concurrency in Settings.", "settings", "Open Settings")
        mg("img-5xx-server", "manga-img-5xx", B, "The site's image server had an error (5xx), so some pages are missing.", "The site is down or busy. Press Retry failed in a few minutes.")
        mg("img-401-login", "manga-img-401", B, "The site wants a login for some images (401), so those pages are missing.", "Log in on the site in this browser, open the chapter once, then press Retry failed.", "reload", "Reload page")
        val other = x.mgCodes.filter { it.key.startsWith("manga:img-http-") }
        if (other.isNotEmpty()) {
            val total = other.values.sum()
            val key = other.keys.first()
            val num = key.removePrefix("manga:img-http-")
            out.add(f("manga-img-http", B, "The site answered some images with HTTP " + num + ", so those pages are missing." + tailOf(total, x.mgCodeNotes[key].orEmpty()), "Open the chapter in a normal tab, wait until every image shows, then press Retry failed. If it repeats, copy the report.", "reload", "Reload page"))
        }
        mg("not-an-image", "manga-not-image", B, "The site sent something that is not an image (often a check or login page) for some pages.", "Open the chapter in a normal tab and pass any check or login, then press Retry failed.", "reload", "Reload page")
        mg("img-network-timeout", "manga-img-timeout", B, "Some images timed out or the connection dropped, so those pages are missing.", "Check Wi-Fi / data (a VPN can also cause this), then press Retry failed.", "wifi", "Open Wi-Fi settings")
        mg("img-fetch-error", "manga-img-fetch", B, "Some images could not be loaded (unknown error), so those pages are missing.", "Press Retry failed. If it repeats, reload the chapter page and grab again, or copy the report.", "reload", "Reload page")
        mg("corrupt-image", "manga-corrupt", B, "Some images arrived broken (corrupt), so those pages are missing.", "Press Retry failed. If it repeats, the site's file may be damaged: try again later or copy the report.")
        mg("all-methods-failed", "manga-all-methods", B, "Some pages failed every loading method (direct, viewer, page fetch, canvas).", "Keep the manga tab open and visible with the screen on, reload the chapter page, then press Retry failed.", "reload", "Reload page")
        mg("rate-limited", "manga-rate-limited", W, "The site is rate-limiting MGB, so downloads were slowed down automatically.", "Wait. If it repeats, raise the delay between pages or lower the concurrency in Settings.", "settings", "Open Settings")
        mg("first-pass-failed", "manga-first-pass", I, "Some pages failed the first time. MGB retries them automatically.", "Nothing to do unless the pages stay missing (see the other findings).")
        run {
            val n = x.code("manga:retry-result")
            if (n > 0) {
                val note = x.mgCodeNotes["manga:retry-result"].orEmpty()
                val rec = Regex("""recovered=(\d+)""").find(note)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                val left = Regex("""still-missing=(\d+)""").find(note)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                val t = tailOf(n, note)
                if (left > 0) out.add(f("manga-retry-left", B, "After the retries, $left page(s) are still missing ($rec recovered)." + t, "Press Retry failed once more. If pages stay missing, reload the chapter page and grab again.", "reload", "Reload page"))
                else out.add(f("manga-retry-ok", I, "$rec page(s) were recovered by the retry." + t, "Nothing to do."))
            }
        }
        mg("still-missing", "manga-still-missing", B, "Some pages could not be loaded even after the retries.", "Press Retry failed. If they stay missing, reload the chapter page for fresh links and grab again. Do not save yet: the file would have gaps.", "reload", "Reload page")
        mg("refresh-failed", "manga-refresh", W, "MGB could not refresh the expired image links.", "Reload the chapter page, then grab again.", "reload", "Reload page")
        mg("duplicates-unselected", "manga-dups", I, "Duplicate pages were unselected automatically.", "Nothing to do. Tick them again in the Manga window if you want them.")
        mg("screenshot-failed", "manga-screenshot", W, "Some pages could not be captured as screenshots.", "Keep the manga tab visible with the screen on until the grab has finished, then try again.")
    }

    /**
     * S89.6 rules 73-87: manga READER / SAVE / DOWNLOAD codes (S89.3, written by manga.js and background.js).
     * Same pattern as rules55to72. The exact detail text of these lines was not in the attached files, so the only thing read from a note is the host
     * (before the first ":") and, for pages-missing-saved, the first number followed by "page" (if none is found the text has no number).
     */
    private fun rules73to87(x: Facts, out: ArrayList<Finding>) {
        val B = DiagReport.BAD
        val W = DiagReport.WARN
        val I = DiagReport.INFO
        fun tailOf(n: Int, note: String): String {
            val host = if (note.contains(":")) note.substringBefore(":").trim().take(60) else ""
            return " (" + n + "x" + (if (host.isNotEmpty()) ", " + host else "") + ")"
        }
        fun mg(code: String, id: String, level: String, what: String, todo: String, fix: String? = null, label: String? = null) {
            val n = x.code("manga:$code")
            if (n <= 0) return
            out.add(f(id, level, what + tailOf(n, x.mgCodeNotes["manga:$code"].orEmpty()), todo, fix, label))
        }
        mg("memory-limit", "manga-memory", B, "The phone ran out of memory while saving the chapter.", "Save fewer pages at once or lower the image size. Use CBZ instead of PDF (PDF needs the most memory). Close other apps and tabs first.")
        mg("save-failed", "manga-save-failed", B, "Saving the chapter failed.", "Press Save again. If it repeats, save fewer pages or use CBZ, then copy the report.", "downloads", "Open Downloads")
        mg("download-failed", "manga-dl-failed", B, "The saved file could not be written to the Downloads folder.", "Check free storage space and that MGB may write to the chosen Downloads folder, then save again.", "storage", "Open storage settings")
        mg("download-timeout", "manga-dl-timeout", B, "The save did not finish in time (the download stayed stuck).", "Keep the screen on and MGB open until the file is written. Check storage space, then save again.", "storage", "Open storage settings")
        mg("jpeg-convert-failed", "manga-jpeg", B, "Converting pages to JPEG failed.", "Save again. If it repeats, switch the image format / quality in the Manga window or use CBZ, then copy the report.")
        mg("save-incomplete", "manga-save-incomplete", B, "The chapter was not saved completely (the save stopped early or its tab was closed).", "Keep the manga tab open until the save has finished. Press Retry failed if pages are missing, then save again.", "reload", "Reload page")
        run {
            val n = x.code("manga:pages-missing-saved")
            if (n > 0) {
                val note = x.mgCodeNotes["manga:pages-missing-saved"].orEmpty()
                val cnt = Regex("""(\d+)\s*page""").find(note)?.groupValues?.get(1)
                val what = if (cnt != null) "The chapter was saved with $cnt page(s) missing." else "The chapter was saved with some pages missing."
                out.add(f("manga-saved-missing", W, what + tailOf(n, note), "Press Retry failed in the Manga window until no page is missing, then save the chapter again."))
            }
        }
        mg("reencode-failed", "manga-reencode", W, "Re-encoding some pages failed. The original pages were kept, the file is still saved.", "Nothing to do. The file may be bigger than expected. If it repeats, copy the report.")
        mg("postprocess-failed", "manga-postprocess", W, "A post-processing step failed. The original pages were kept, the file is still saved.", "Nothing to do. Check the saved file once. If it repeats, copy the report.")
        mg("reader-page-failed", "manga-reader-page", W, "A page could not be shown in the reader.", "Go back to the Manga window, press Retry failed, then open the reader again.")
        mg("pick-without-image", "manga-pick-noimg", W, "Some picked pages have no image data yet.", "Wait until the pages are loaded (or press Retry failed), then open the reader or save again.")
        mg("wrong-order", "manga-order", W, "The page numbers in the file names go up and down (the order may be wrong).", "Use Sort by name in the Manga window, then check the first and last page before saving.")
        mg("reader-empty", "manga-reader-empty", I, "The reader was opened with no page selected.", "Select the pages you want in the Manga window, then open the reader.")
        mg("resume-failed", "manga-resume", I, "The saved reading position is past the end of the chapter, so the reader started at the first page.", "Nothing to do.")
        mg("bookmark-failed", "manga-bookmark", I, "The reading position could not be saved or loaded.", "Nothing to do for the file itself. If it repeats, clear site data for MGB or copy the report.")
    }

    /** S8 rules 88-92: installed extensions (Diag tag "ext"). Names of extensions are not put in the finding text. */
    private fun rules88to92(x: Facts, out: ArrayList<Finding>) {
        val W = DiagReport.WARN
        val I = DiagReport.INFO
        fun n(vararg k: String) = x.extMsgs.count { m -> k.any { m.contains(it) } }
        fun tail(c: Int) = if (c > 1) " (" + c + "x)" else ""
        n("worker parked").let { if (it > 0) out.add(f("ext-parked", W, "An extension's background worker was stopped after it crashed again and again." + tail(it), "Open Settings > Extensions and turn that extension off and on again to retry. Remove it if this keeps happening.", "settings", "Open Settings")) }
        n("worker did not start").let { if (it > 0) out.add(f("ext-nostart", W, "An extension's background worker could not start." + tail(it), "Open Settings > Extensions and turn it off and on again. If it repeats, the extension file may be broken: remove it and add it again.", "settings", "Open Settings")) }
        n("extension page crashed").let { if (it > 0) out.add(f("ext-page-crash", W, "An extension window stopped working and was closed." + tail(it), "Only that window was closed, MGB and the other extensions are fine. Open it again; if it repeats, free memory or turn the extension off.")) }
        n("worker error").let { if (it > 0) out.add(f("ext-worker-error", I, "An extension's background worker reported errors." + tail(it), "This is usually a problem inside the extension. If it does not work as expected, turn it off in Settings > Extensions or copy the report.")) }
        n("blocked extension turned off", "install refused").let { if (it > 0) out.add(f("ext-blocked", I, "An extension was refused or turned off: it uses manifest v2 or nativeMessaging." + tail(it), "MGB runs manifest v3 extensions without native apps only. Look for a manifest v3 version of the extension.")) }
    }

    /** "Why?" on one failed download row: the row's own text / code + the masked url. Always returns at least one Finding. */
    fun explain(text: String): List<Finding> {
        val t = text.lowercase()
        val out = ArrayList<Finding>()
        fun has(vararg k: String) = k.any { t.contains(it) }
        if (has("no_space", "no space", "enospc", "space")) out.add(f("x-space", DiagReport.BAD, "Out of storage space.", "Free some space or pick a folder with more room, then retry.", "storage", "Open storage settings"))
        if (has("403", "forbidden", "401", "unauthorized")) out.add(f("x-403", DiagReport.WARN, "The site refused the download (403).", "The link expired or needs a login. Open the video page again to get a fresh link, then download again."))
        if (has("404", "410", "not found", "gone")) out.add(f("x-404", DiagReport.WARN, "The file is not on the server any more (404).", "Open the page again and download from the new link."))
        if (has("429", "too many", "503", "502", "500")) out.add(f("x-busy", DiagReport.WARN, "The server is busy or limits requests.", "Wait a few minutes and retry. Lower the concurrency in Settings.", "settings", "Open Settings"))
        if (has("timeout", "timed out")) out.add(f("x-timeout", DiagReport.WARN, "The connection timed out.", "Slow server or weak signal. Retry on Wi-Fi.", "wifi", "Open Wi-Fi settings"))
        if (has("network", "connection", "unreachable", "resolve", "dns")) out.add(f("x-net", DiagReport.WARN, "The connection was lost.", "Check Wi-Fi / data, then retry. A VPN can also cause this.", "wifi", "Open Wi-Fi settings"))
        if (has("file_failed", "write", "permission", "folder")) out.add(f("x-file", DiagReport.BAD, "The file could not be written.", "Choose the download folder again in Settings.", "settings", "Open Settings"))
        if (has("drm", "protected", "widevine")) out.add(f("x-drm", DiagReport.WARN, "The video is DRM protected.", "Protected streams cannot be downloaded."))
        if (out.isEmpty()) out.add(f("x-unknown", DiagReport.INFO, "The download failed" + (if (text.isBlank()) "." else ": " + text.take(120)), "Retry once. If it fails again, open the full report and copy it."))
        return out
    }
}
