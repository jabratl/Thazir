package com.jabra.mgb

import android.app.ActivityManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.StatFs
import android.webkit.WebView
import org.json.JSONObject

/**
 * S84: Diagnose part 3/5. Builds ONE report (structured + plain text) about the app, the device and the
 * current page, from things the app already knows. No UI here (S85 shows it, S86 adds the rule engine).
 *
 * Privacy: only Diag.maskUrl (host + path), never page content, cookies, passwords or full query strings.
 * A private tab adds no URL at all.
 *
 * Use: DiagReport.build(ctx, tabs, tabId) { report -> report.text() }   (callback on the main thread)
 */
object DiagReport {
    const val OK = "ok"
    const val WARN = "warn"
    const val BAD = "bad"
    const val INFO = "info"

    /** group = the S85 screen group: Video / Novel / Manga / Browser / Download / Device. */
    class Section(val id: String, val group: String, val title: String, val level: String, val lines: List<String>)

    class Report(val time: Long, val verdictLevel: String, val verdict: String, val sections: List<Section>,
                 val findings: List<DiagRules.Finding> = emptyList()) { // S86: findings from DiagRules
        fun text(): String {
            val sb = StringBuilder()
            sb.append("MGB diagnose report\n")
            sb.append("Time: ").append(java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date(time))).append('\n')
            sb.append("Verdict (").append(verdictLevel).append("): ").append(verdict).append("\n")
            if (findings.isNotEmpty()) {
                sb.append("\n== Findings\n")
                for (f in findings) sb.append("- [").append(f.level).append("] ").append(f.what).append(" -> ").append(f.todo).append('\n')
            }
            for (s in sections) {
                sb.append("\n== ").append(s.title).append(" [").append(s.level).append("]\n")
                for (l in s.lines) sb.append(if (l.startsWith("  ")) l else "- $l").append('\n')
            }
            return sb.toString().trimEnd()
        }
    }

    private const val FIVE_MIN = 5 * 60 * 1000L
    private const val LOW_STORAGE = 200L * 1024 * 1024

    private inline fun <T> safe(def: T, f: () -> T): T = try { f() } catch (e: Throwable) { def }
    private fun mb(b: Long): String = if (b >= 1L shl 30) String.format("%.2f GB", b / 1073741824.0) else (b / 1048576).toString() + " MB"

    // ------------------------------------------------------------------ entry

    /** Main thread. Asks the engine for the page state (V77 detected items) once, then builds. cb always runs. */
    fun build(ctx: Context, tabs: TabManager?, tabId: Int, cb: (Report) -> Unit) {
        // 0.0.393: the app-data size walk can take a moment: do it off the main thread, then build on the main thread as before
        Thread {
            val sizes = safe(listOf("not readable")) { DataSizes.lines(ctx) }
            android.os.Handler(android.os.Looper.getMainLooper()).post { dataSizeLines = sizes; buildNow(ctx, tabs, tabId, cb) }
        }.also { it.isDaemon = true }.start()
    }

    @Volatile private var dataSizeLines: List<String> = emptyList()

    private fun buildNow(ctx: Context, tabs: TabManager?, tabId: Int, cb: (Report) -> Unit) {
        val tab = safe(null) { if (tabId >= 0) tabs?.byId(tabId) else tabs?.active }
        val id = tab?.id ?: -1
        var done = false
        fun finish(state: JSONObject?) {
            if (done) return
            done = true
            cb(try { assemble(ctx, tab, state, safe(0) { tabs?.count ?: 0 }) } catch (e: Throwable) {
                Diag.e("app", "DiagReport failed", e)
                Report(System.currentTimeMillis(), BAD, "The report could not be built: " + e.javaClass.simpleName,
                    listOf(Section("err", "Device", "Report error", BAD, listOf(e.javaClass.simpleName + " " + (e.message ?: "")))))
            })
        }
        if (tab == null || tab.isExt || !tab.url.startsWith("http")) { finish(null); return }
        try {
            EngineApi.ask(JSONObject().put("type", "get-state").put("tabId", id)) { r -> finish(r) }
        } catch (e: Throwable) { finish(null) }
    }

    // --------------------------------------------------------------- sections

    private fun assemble(ctx: Context, tab: TabManager.Tab?, state: JSONObject?, tabCount: Int): Report {
        val now = System.currentTimeMillis()
        val secs = ArrayList<Section>()

        // ---- Device group
        secs.add(Section("app", "Device", "App", INFO, listOf(
            "MGB " + safe("?") { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "?" } +
                " (build " + safe(0L) { if (Build.VERSION.SDK_INT >= 28) ctx.packageManager.getPackageInfo(ctx.packageName, 0).longVersionCode else 0L } + ")",
            "Engine: " + safe("?") { EngineHost.status().lineSequence().firstOrNull() ?: "?" }
        )))
        val wv = safe("?") { WebView.getCurrentWebViewPackage()?.let { it.packageName + " " + it.versionName } ?: "unknown" }
        val wvMajor = safe(0) { wv.substringAfterLast(' ').substringBefore('.').toInt() }
        secs.add(Section("webview", "Browser", "WebView", if (wvMajor in 1..99) WARN else INFO, listOf(
            wv, if (wvMajor in 1..99) "WebView is old (version $wvMajor): update Android System WebView / Chrome" else "WebView version looks fine")))
        secs.add(Section("device", "Device", "Device", INFO, listOf(
            Build.MANUFACTURER + " " + Build.MODEL, "Android " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")",
            "ABI " + (Build.SUPPORTED_ABIS.firstOrNull() ?: "?"))))

        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val mi = ActivityManager.MemoryInfo()
        safe(Unit) { am?.getMemoryInfo(mi) }
        val ramLow = mi.lowMemory
        secs.add(Section("ram", "Device", "Memory", if (ramLow) BAD else INFO, listOf(
            "Free " + mb(mi.availMem) + " of " + mb(mi.totalMem), "Low memory flag: " + (if (ramLow) "YES" else "no"),
            "Tabs open: $tabCount")))

        val free = safe(-1L) { StatFs(ctx.filesDir.path).availableBytes }
        val tree = safe(null) { DownloadFolder.treeUri(ctx) }
        val lowStore = free in 0 until LOW_STORAGE
        secs.add(Section("storage", "Download", "Storage", if (lowStore) BAD else INFO, listOf(
            "Free on the phone (app area): " + (if (free < 0) "unknown" else mb(free)),
            "Download folder: " + (if (tree == null) "not chosen (default folder)" else safe("chosen") { DownloadFolder.label(ctx) }))))

        secs.add(Section("appdata", "Download", "App data size", INFO, dataSizeLines.ifEmpty { listOf("not measured") })) // 0.0.393: where "User data" sits

        // ---- Network
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        var online = true
        var hasNet = true // S86
        var vpnOn = false // S86
        val netLines = ArrayList<String>()
        safe(Unit) {
            val net = cm?.activeNetwork
            val caps = if (net == null) null else cm?.getNetworkCapabilities(net)
            if (caps == null) { online = false; hasNet = false; netLines.add("No active network") } else {
                online = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                netLines.add("Type: " + (if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) "Wi-Fi" else if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) "Mobile data" else if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) "VPN" else "other"))
                val valid = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                netLines.add("Internet reachable (validated): " + (if (valid) "yes" else "NO (captive portal or no data)"))
                if (!valid) online = false
                netLines.add("Metered: " + (if (cm?.isActiveNetworkMetered == true) "yes" else "no"))
                vpnOn = caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
                netLines.add("VPN: " + (if (vpnOn) "on" else "off"))
            }
        }
        secs.add(Section("network", "Download", "Network", if (online) INFO else BAD, netLines.ifEmpty { listOf("unknown") }))

        // ---- Settings health
        val setL = ArrayList<String>()
        var setLevel = INFO
        setL.add("Ad blocker: " + (if (AdBlock.enabled) "on" else "off"))
        setL.add("HTTPS-only: " + (if (safe(false) { HttpsOnly.on(ctx) }) "on" else "off"))
        setL.add("Download folder set: " + (if (tree != null) "yes" else "no (default)"))
        val sync = safe(null) { ChromeBridge.sync.get(null) }
        if (sync != null) {
            for (k in listOf("blockAds", "mergeAV", "concurrency", "maxJobs", "sniffSeconds")) if (sync.has(k)) setL.add("V82 $k = " + sync.opt(k))
        } else { setL.add("V82 settings: not readable (engine not started?)"); setLevel = WARN }
        // 0.0.361: Download in parts (native prefs + engine key partsN)
        setL.add(safe("Download in parts: not readable") {
            val up = ctx.getSharedPreferences("mgb_ui", Context.MODE_PRIVATE)
            "Download in parts: " + (if (up.getBoolean("dp_on", true)) "on" else "off") + ", " + up.getInt("dp_n", 4) + " parts, only files over " + up.getInt("dp_min_mb", 8) + " MB, grabber files " + (sync?.optInt("partsN", 1) ?: 1)
        })
        // 0.0.374 (N6): native download control (speed limit, simultaneous downloads, auto-resume, smart tail, app downloader for grabber files)
        setL.add(safe("Download control: not readable") {
            val up = ctx.getSharedPreferences("mgb_ui", Context.MODE_PRIVATE)
            val lim = up.getInt("dl_limit_kbps", 0)
            "Download control: speed limit " + (if (lim > 0) "$lim KB/s" else "off") + ", " + up.getInt("dl_max_jobs", 3) + " at the same time, auto-resume " +
                (if (up.getBoolean("dl_auto_resume", false)) "on" else "off") + ", smart tail " + (if (up.getBoolean("dp_tail", false)) "on" else "off") +
                ", app downloader for grabber files " + (if (sync?.optBoolean("directToNative", false) == true) "on" else "off")
        })
        secs.add(Section("settings", "Browser", "Settings", setLevel, setL))

        // ---- Page / tab
        val pg = ArrayList<String>()
        var pageLevel = INFO
        if (tab == null) { pg.add("No tab") } else if (tab.priv) { pg.add("Private tab: address not logged") }
        else if (tab.isExt || tab.home || !tab.url.startsWith("http")) { pg.add("Not a web page (" + (if (tab.home) "home" else "extension / blank") + ")") }
        else {
            pg.add("Page: " + Diag.maskUrl(tab.url))
            pg.add("Loading: " + (if (tab.loading) "yes (" + tab.progress + "%)" else "done"))
            pg.add("Desktop mode: " + (if (tab.desktop) "on" else "off"))
        }
        val tabErr = Diag.countSince("tab", now - FIVE_MIN, "E") + Diag.countSince("tab", now - FIVE_MIN, "W")
        if (tabErr > 0) { pageLevel = WARN; pg.add("Tab errors / warnings in the last 5 min: $tabErr") }
        val crashed = Diag.recent(100, "tab", false).any { it.ts >= now - FIVE_MIN && (it.msg.contains("crash", true) || it.msg.contains("killed", true)) }
        if (crashed) { pageLevel = BAD; pg.add("A tab crashed or was killed in the last 5 min") }
        secs.add(Section("page", "Browser", "Page", pageLevel, pg))

        // ---- V77 detected items
        val items = state?.optJSONObject("rec")?.optJSONArray("items")
        val dl = ArrayList<String>()
        var detLevel = INFO
        if (state == null) { dl.add(if (tab == null || tab.isExt || !tab.url.startsWith("http")) "No web page selected" else "Media Grabber did not answer (not ready or not running)"); if (tab != null && !tab.isExt && tab.url.startsWith("http")) detLevel = WARN }
        else if (items == null || items.length() == 0) { dl.add("Nothing detected on this page yet"); detLevel = WARN }
        else {
            val kinds = LinkedHashMap<String, Int>()
            var drm = 0
            for (i in 0 until items.length()) { val j = items.optJSONObject(i) ?: continue; val k = j.optString("kind"); kinds[k] = (kinds[k] ?: 0) + 1; if (j.optJSONObject("info")?.optBoolean("protected", false) == true) drm++ }
            dl.add("Detected: " + items.length() + " (" + kinds.entries.joinToString(", ") { it.key + " " + it.value } + ")")
            if (drm > 0) { dl.add("DRM protected: $drm (cannot be downloaded)"); detLevel = WARN }
            for (i in 0 until minOf(items.length(), 10)) { val j = items.optJSONObject(i) ?: continue; dl.add("  - " + j.optString("kind") + " " + Diag.maskUrl(j.optString("url"))) }
        }
        secs.add(Section("detected", "Video", "Detected files", detLevel, dl))

        // ---- Jobs / queue
        val jobs = safe(emptyList<JSONObject>()) { EngineApi.jobs() }
        val states = LinkedHashMap<String, Int>()
        for (j in jobs) { val s = j.optString("state").ifBlank { "?" }; states[s] = (states[s] ?: 0) + 1 }
        val failedJobs = jobs.filter { it.optString("state") == "error" || it.optString("state") == "failed" }
        val jl = ArrayList<String>()
        jl.add("V82 jobs: " + (if (jobs.isEmpty()) "none" else states.entries.joinToString(", ") { it.key + " " + it.value }))
        for (j in failedJobs.take(3)) jl.add("  - failed: " + Diag.mask(jobErr(j).take(120)) + " " + Diag.maskUrl(j.optString("url")))
        val dFail5 = Diag.countSince("download", now - FIVE_MIN, null)
        if (dFail5 > 0) jl.add("Download failures logged in the last 5 min: $dFail5")
        for (e in Diag.recent(3, "download", false)) jl.add(fmtEvent(e))
        for (e in Diag.recent(3, "dlparts", false)) jl.add(fmtEvent(e)) // 0.0.361: last parts download lines (start / fallback)
        for (e in Diag.recent(2, "autoresume", false)) jl.add(fmtEvent(e)) // 0.0.374 (N6): last auto-resume lines
        secs.add(Section("jobs", "Download", "Queue / jobs", if (failedJobs.isNotEmpty() || dFail5 > 0) WARN else INFO, jl))

        // ---- Novel / Manga (native side: only the events, the page side is in the V77 report)
        for ((tag, title) in listOf("novel" to "Novel grabber", "manga" to "Manga")) {
            val ev = Diag.recent(5, tag, true)
            val lines = if (ev.isEmpty()) listOf("No errors logged") else ev.map { fmtEvent(it) }
            secs.add(Section(tag, if (tag == "novel") "Novel" else "Manga", title, if (ev.any { it.ts >= now - FIVE_MIN }) WARN else INFO, lines))
        }

        // ---- V77 log (storage.local mgLog, last 30, masked)
        val log = safe(null) { ChromeBridge.local.get("mgLog").optJSONArray("mgLog") }
        val ll = ArrayList<String>()
        if (log == null || log.length() == 0) ll.add("Empty") else for (i in maxOf(0, log.length() - 30) until log.length()) ll.add("  " + Diag.mask(log.optString(i)).take(300))
        secs.add(Section("mglog", "Video", "V82 log (last 30)", INFO, ll))

        // ---- Recent native errors
        val errs = Diag.recent(20, null, true)
        secs.add(Section("errors", "Device", "Recent errors", if (errs.any { it.ts >= now - FIVE_MIN }) WARN else INFO,
            if (errs.isEmpty()) listOf("No errors logged") else errs.flatMap { listOf(fmtEvent(it)) + crashFrames(it) }))

        // ---- verdict (S86: rule engine, DiagRules.kt)
        val x = DiagRules.Facts()
        val TEN_MIN = 10 * 60 * 1000L
        x.online = online; x.hasNetwork = hasNet; x.vpn = vpnOn
        x.freeBytes = free; x.ramLow = ramLow; x.wvMajor = wvMajor; x.tabCrashed = crashed
        x.pageIsWeb = tab != null && !tab.priv && !tab.isExt && !tab.home && tab.url.startsWith("http")
        x.pageLoading = tab?.loading == true
        val pageEv = Diag.recent(30, "page", false).filter { it.ts >= now - TEN_MIN }
        x.pageErrors = pageEv.map { it.msg }
        x.pageErrorUrls = pageEv.map { it.extra }
        x.httpsOnly = safe(false) { HttpsOnly.on(ctx) }
        x.adblock = AdBlock.enabled
        x.folderSet = tree != null
        x.engineAnswered = state != null
        x.engineErrors = Diag.countSince("engine", now - FIVE_MIN, "E")
        x.detected = items?.length() ?: 0
        x.dlCodes = Diag.recent(30, "download", false).filter { it.ts >= now - TEN_MIN && it.msg.startsWith("failed ") }.map { it.msg.removePrefix("failed ").trim() }
        x.partsMsgs = Diag.recent(30, "dlparts", false).filter { it.ts >= now - TEN_MIN }.map { it.msg.lowercase() } // 0.0.361
        x.sniffFails = Diag.recent(40, "sniffer", false).filter { it.ts >= now - TEN_MIN && it.msg.startsWith("probe failed") }.sumOf { maxOf(1, it.n) }
        // S87.1 facts for rules 21-40 (all text lower-case; urls only via maskUrl, error text via mask)
        fun evTxt(tag: String, sinceMs: Long) = Diag.recent(30, tag, false).filter { it.ts >= now - sinceMs }.map { (it.msg + " " + it.extra).lowercase() }
        x.novelMsgs = evTxt("novel", TEN_MIN)
        x.mangaMsgs = evTxt("manga", TEN_MIN)
        x.scheduleMsgs = evTxt("schedule", 24 * 60 * 60 * 1000L)
        x.extMsgs = evTxt("ext", TEN_MIN) // S8
        x.logLines = if (log == null) emptyList() else (maxOf(0, log.length() - 30) until log.length()).map { Diag.mask(log.optString(it)).lowercase() }
        if (items != null) for (i in 0 until items.length()) {
            val j = items.optJSONObject(i) ?: continue
            val k = j.optString("kind")
            val inf = j.optJSONObject("info")
            if ((k == "hls" || k == "dash") && inf != null && inf.has("error") && (inf.optJSONArray("variants")?.length() ?: 0) == 0) x.hlsNoList++
            if (inf?.optBoolean("protected", false) == true) x.drmItems++
            if (j.optString("url").startsWith("blob:")) x.blobItems++
        }
        // S89.0: stable codes from the whole mgLog (200 lines), last 30 min
        run {
            val cnt = LinkedHashMap<String, Int>()
            val note = HashMap<String, String>()
            if (log != null) for (i in 0 until log.length()) {
                val m = MGLOG_CODE.matchEntire(log.optString(i)) ?: continue
                val ts = mgLogTime(m, now) ?: continue
                if (ts < now - 30 * 60 * 1000L) continue
                val key = m.groupValues[6] + ":" + m.groupValues[7]
                val rest = m.groupValues[8]
                val rep = MGLOG_REPEATS.find(rest)?.groupValues?.get(1)?.toIntOrNull()
                cnt[key] = (cnt[key] ?: 0) + (rep ?: 1)
                if (rep == null) note[key] = Diag.mask(rest).lowercase().take(200)
            }
            x.mgCodes = cnt; x.mgCodeNotes = note
        }
        x.jobErrors = failedJobs.map { Diag.mask(jobErr(it)).lowercase() }
        x.failedJobUrls = failedJobs.map { Diag.maskUrl(it.optString("url")) } +
            Diag.recent(30, "download", false).filter { it.ts >= now - TEN_MIN && it.msg.startsWith("failed ") }.map { Diag.maskUrl(it.extra) }
        x.runningJobs = jobs.count { it.optString("state") == "run" }
        x.notifDenied = safe(false) { Build.VERSION.SDK_INT >= 33 && androidx.core.content.ContextCompat.checkSelfPermission(ctx, "android.permission.POST_NOTIFICATIONS") != android.content.pm.PackageManager.PERMISSION_GRANTED }
        x.bgRestricted = safe(false) { Build.VERSION.SDK_INT >= 28 && am?.isBackgroundRestricted == true }
        val findings = safe(emptyList<DiagRules.Finding>()) { DiagRules.diagnose(x) }
        val top = findings.firstOrNull()
        val v: Pair<String, String> = if (top == null) OK to "No problem found by the rules." else top.level to (top.what + " " + top.todo)
        return Report(now, v.first, v.second, secs, findings)
    }

    // S89.0: "05-31 12:34:56 [manga] code=403-blocked detail" (time is UTC, no year)
    private val MGLOG_CODE = Regex("""^(\d\d)-(\d\d) (\d\d):(\d\d):(\d\d) \[([A-Za-z0-9_-]+)] code=([a-z0-9-]+)(?: (.*))?$""")
    private val MGLOG_REPEATS = Regex("""\brepeats=(\d+)""")

    private fun mgLogTime(m: MatchResult, now: Long): Long? {
        val g = m.groupValues
        val c = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
        c.timeInMillis = now
        val y = c.get(java.util.Calendar.YEAR)
        fun at(year: Int): Long? = try {
            c.clear(); c.set(year, g[1].toInt() - 1, g[2].toInt(), g[3].toInt(), g[4].toInt(), g[5].toInt()); c.timeInMillis
        } catch (_: Throwable) { null }
        val t = at(y) ?: return null
        return if (t > now + 24 * 60 * 60 * 1000L) at(y - 1) else t   // Dec lines read in Jan
    }

    /** 0.0.222: V77 jobs keep the failure text in "text" (background.js trackJob); "error" / "why" stay as old fallbacks. */
    private fun jobErr(j: JSONObject): String = j.optString("text").ifBlank { j.optString("error") }.ifBlank { j.optString("why") }

    /** 0.0.222: a crash event carries its stack in e.extra; the report now shows the first frame(s) + the app frames, so the crash can be found. */
    private fun crashFrames(e: Diag.Event): List<String> {
        if (e.tag != "crash") return emptyList()
        val lines = e.extra.lines().map { it.trim() }
        val cause = lines.filter { it.startsWith("Caused by:") }.take(4) // 0.0.346: the real reason of a crash
        val at = lines.filter { it.startsWith("at ") }
        val keep = at.withIndex().filter { (i, l) -> i < 2 || l.contains("com.jabra.mgb") }.map { it.value }.take(6)
        return (cause.map { "      " + Diag.mask(it.take(260)) }) + keep.map { "      " + Diag.mask(it.take(160)) }
    }

    private fun fmtEvent(e: Diag.Event): String {
        val t = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date(e.ts))
        return "  $t ${e.level} ${e.tag}: ${e.msg}" + (if (e.n > 1) " (x${e.n})" else "")
    }
}
