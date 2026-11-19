package com.jabra.mgb

import android.content.Context
import android.webkit.WebResourceRequest
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * S74: filter lists in EasyList syntax (EasyList + EasyPrivacy + a small seed), the engine behind AdBlock.
 *  - network rules: ||domain^path, |start, plain text with * and ^, @@ exceptions, options $third-party / ~third-party,
 *    $script $image $stylesheet $xmlhttprequest $subdocument $media $font $other (and ~type), $domain=a.com|~b.com
 *  - cosmetic rules: ##selector and domain##selector (simple CSS only), each selector as its own CSS rule
 *  - skipped on purpose: regex rules, scriptlets (+js), extended selectors (:has-text, :xpath ...), $csp / $redirect / $removeparam (0.0.480: $popup rules ARE used now, for pop-up tabs only, see AdBlock.blocksPage)
 *  Domain rules are indexed by domain, so a request only looks at the rules of its own host (and parents).
 * Loads on a background thread; until it is ready (or if it fails) AdBlock keeps working with its built-in host list.
 */
object FilterList {
    private const val PREFS = "mgb_adblock"
    private const val DAY = 24L * 3600 * 1000
    private const val MAX_NET = 160_000
    private const val MAX_GEN = 2500
    private const val MAX_COS_GEN = 800
    private const val MAX_COS_DOM = 30_000
    /** 0.0.107: one list the user can switch on / off (built-in) or add by address (custom). name = file in filesDir/filters. */
    class ListInfo(val name: String, val title: String, val url: String, val custom: Boolean, val on: Boolean)
    private class Src(val name: String, val title: String, val url: String)
    private val BUILTIN = listOf(
        // 0.0.301: Brave's own lists first (small, so the rule cap below never cuts them): github.com/brave/adblock-lists (MPL-2.0, downloaded, not bundled)
        Src("brave-unbreak.txt", "Brave unbreak (Brave)", "https://raw.githubusercontent.com/brave/adblock-lists/master/brave-unbreak.txt"),
        Src("brave-social.txt", "Brave social trackers (Brave)", "https://raw.githubusercontent.com/brave/adblock-lists/master/brave-lists/brave-social.txt"),
        Src("easylist.txt", "EasyList", "https://easylist.to/easylist/easylist.txt"),
        Src("easyprivacy.txt", "EasyPrivacy", "https://easylist.to/easylist/easyprivacy.txt"),
        Src("ubo-filters.txt", "uBlock filters", "https://ublockorigin.github.io/uAssets/filters/filters.txt"),
        Src("cookie-notices.txt", "Cookie notices (Fanboy)", "https://secure.fanboy.co.nz/fanboy-cookiemonster.txt")
    )
    private val DEFAULT_OFF = setOf("ubo-filters.txt", "cookie-notices.txt", "brave-social.txt") // 0.0.301: social list OFF (it can clash with Social sites)

    private fun offSet(): Set<String> = prefs?.getStringSet("fl_off", null) ?: DEFAULT_OFF
    private fun customSrc(): List<Src> =
        (prefs?.getString("fl_custom", "") ?: "").split("\n").mapNotNull { l ->
            val i = l.indexOf('|'); if (i <= 0 || i == l.length - 1) null else l.substring(i + 1).let { u -> Src(l.substring(0, i), hostTitle(u), u) }
        }
    private fun hostTitle(u: String): String = try { java.net.URI(u).host ?: u } catch (_: Exception) { u }
    private fun allSrc(): List<Src> = BUILTIN + customSrc()
    private fun enabledSources(): List<Src> { val off = offSet(); return allSrc().filter { it.name !in off } }

    fun lists(): List<ListInfo> {
        val off = offSet(); val cn = customSrc().map { it.name }.toSet()
        return allSrc().map { ListInfo(it.name, it.title, it.url, it.name in cn, it.name !in off) }
    }
    fun activeTitles(): List<String> = enabledSources().map { it.title }

    /** Switch lists on / off in one go; a list that is on but not downloaded yet is fetched. [done] on a background thread. */
    fun setEnabledAll(ctx: Context, on: Map<String, Boolean>, done: (String) -> Unit) {
        val app = ctx.applicationContext
        val off = offSet().toMutableSet()
        for ((n, v) in on) if (v) off.remove(n) else off.add(n)
        prefs?.edit()?.putStringSet("fl_off", off)?.apply()
        val missing = enabledSources().any { !File(dir(app), it.name).exists() }
        if (missing) update(app, done) else { loading = false; load(app, false); done("Lists applied") }
    }

    /** Adds a list by https address (EasyList syntax), downloads it. Returns an error text or null when started. */
    fun addCustom(ctx: Context, url: String, done: (String) -> Unit): String? {
        val u = url.trim()
        if (!u.startsWith("https://") || u.length > 500 || u.contains('|') || u.contains('\n') || u.contains(' ')) return "Use a full https:// address"
        if (allSrc().any { it.url == u }) return "This list is already added"
        if (customSrc().size >= 8) return "At most 8 custom lists"
        val name = "c" + Integer.toHexString(u.hashCode()) + ".txt"
        val cur = prefs?.getString("fl_custom", "") ?: ""
        prefs?.edit()?.putString("fl_custom", (if (cur.isEmpty()) "" else cur + "\n") + name + "|" + u)?.apply()
        update(ctx.applicationContext, done)
        return null
    }

    fun removeCustom(ctx: Context, name: String) {
        val app = ctx.applicationContext
        val keep = (prefs?.getString("fl_custom", "") ?: "").split("\n").filter { it.isNotEmpty() && !it.startsWith("$name|") }
        prefs?.edit()?.putString("fl_custom", keep.joinToString("\n"))?.putStringSet("fl_off", offSet() - name)?.apply()
        File(dir(app), name).delete()
        loading = false; load(app, false)
    }

    const val T_SCRIPT = 1; const val T_IMAGE = 2; const val T_STYLE = 4; const val T_XHR = 8
    const val T_SUBDOC = 16; const val T_MEDIA = 32; const val T_FONT = 64; const val T_OTHER = 128
    const val T_POPUP = 256 // 0.0.480: $popup rules. NOT part of T_ALL, so a normal rule never matches a pop-up check and a $popup rule never blocks a normal request
    private const val T_ALL = 255

    private class Rule(
        val pat: String, val domainAnchored: Boolean, val startAnchor: Boolean, val lit: String,
        val third: Int, val types: Int, val inc: Array<String>?, val exc: Array<String>?
    )

    private class Index(
        val net: HashMap<String, ArrayList<Rule>>, val netExc: HashMap<String, ArrayList<Rule>>,
        val gen: ArrayList<Rule>, val genExc: ArrayList<Rule>,
        val cosGen: String, val cosDom: HashMap<String, ArrayList<String>>, val count: Int
    )

    @Volatile private var idx: Index? = null
    @Volatile var loading = false
        private set
    @Volatile var updating = false
        private set
    private var prefs: android.content.SharedPreferences? = null

    val ruleCount: Int get() = idx?.count ?: 0
    fun updatedAt(): Long = prefs?.getLong("filters_ts", 0L) ?: 0L

    /** T5: the user's own rules (one per line, same syntax as a list). Saved in prefs mgb_adblock (key user_rules) so Backup includes it. */
    fun userRules(): String = prefs?.getString("user_rules", "") ?: ""

    /** T6: dry run for the rules editor (Validate): same parser and same limits as setUserRules, saves nothing. Returns the same texts. */
    fun checkRules(text: String): String {
        if (text.length > 100_000) return "Too long (max 100 KB)"
        val lines = text.lines()
        if (lines.size > 1500) return "Too many lines (max 1500)"
        var ok = 0
        var bad = 0
        for (raw in lines) {
            val l = raw.trim()
            if (l.isEmpty() || l[0] == '!' || l[0] == '[') continue
            val t = Builder()
            try { t.add(l) } catch (_: Throwable) { }
            if (t.count + t.cosDomN + t.cosGen.size > 0) ok++ else bad++
        }
        return "$ok ok, $bad skipped (unsupported)"
    }

    /** T5: saves the rules and reloads the index once. [done] gets "N ok, M skipped (unsupported)" (called on a background thread). */
    fun setUserRules(ctx: Context, text: String, done: (String) -> Unit) {
        Thread {
            if (text.length > 100_000) { done("Too long (max 100 KB)"); return@Thread }
            val lines = text.lines()
            if (lines.size > 1500) { done("Too many lines (max 1500)"); return@Thread }
            var ok = 0
            var bad = 0
            for (raw in lines) {
                val l = raw.trim()
                if (l.isEmpty() || l[0] == '!' || l[0] == '[') continue
                val t = Builder()
                try { t.add(l) } catch (_: Throwable) { }
                if (t.count + t.cosDomN + t.cosGen.size > 0) ok++ else bad++
            }
            prefs?.edit()?.putString("user_rules", text)?.apply()
            while (loading) try { Thread.sleep(150) } catch (_: InterruptedException) { }
            load(ctx.applicationContext, false)
            done("$ok ok, $bad skipped (unsupported)")
        }.start()
    }

    fun init(ctx: Context) {
        val app = ctx.applicationContext
        prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs?.let { p -> // 0.0.301: once, for a saved on / off choice: the new Brave social list starts OFF (Brave unbreak starts ON)
            if (!p.getBoolean("fl_brave301", false)) {
                val e = p.edit().putBoolean("fl_brave301", true)
                p.getStringSet("fl_off", null)?.let { e.putStringSet("fl_off", it + "brave-social.txt") }
                e.apply()
            }
        }
        load(app, true)
    }

    private fun dir(ctx: Context) = File(ctx.filesDir, "filters").also { it.mkdirs() }

    private fun load(ctx: Context, autoUpdate: Boolean) {
        if (loading) return
        loading = true
        Thread {
            try {
                val b = Builder()
                try { ctx.assets.open("filters/seed.txt").bufferedReader().use { r -> r.forEachLine { b.add(it) } } } catch (_: Exception) { }
                for (s in enabledSources()) {
                    val f = File(dir(ctx), s.name)
                    if (f.exists()) try { f.bufferedReader().use { r -> r.forEachLine { b.add(it) } } } catch (_: Exception) { }
                }
                try { userRules().lineSequence().forEach { b.add(it) } } catch (_: Exception) { } // T5: "my rules", same parser as a list
                idx = b.build()
            } catch (_: Throwable) {
                // keep the old index (or none): AdBlock still has its host list
            } finally { loading = false }
            if (autoUpdate && (System.currentTimeMillis() - updatedAt() > 7 * DAY || enabledSources().any { !File(dir(ctx), it.name).exists() })) update(ctx) { } // 0.0.301: also when a list that is ON was never downloaded
        }.start()
    }

    /** Downloads EasyList + EasyPrivacy, then reloads. [done] gets a one line result (called on a background thread). */
    fun update(ctx: Context, done: (String) -> Unit) {
        if (updating) { done("Already updating"); return }
        updating = true
        val app = ctx.applicationContext
        val src = enabledSources()
        if (src.isEmpty()) { updating = false; loading = false; load(app, false); done("No list is on"); return }
        Thread {
            var ok = 0
            for (s in src) {
                val name = s.name; val link = s.url
                try {
                    val c = URL(link).openConnection() as HttpURLConnection
                    c.connectTimeout = 15000; c.readTimeout = 25000
                    c.setRequestProperty("User-Agent", "MGB filter updater")
                    if (c.responseCode == 200) {
                        val tmp = File(dir(app), "$name.tmp")
                        var size = 0L
                        c.inputStream.use { ins -> tmp.outputStream().use { out ->
                            val buf = ByteArray(16384)
                            while (true) {
                                val n = ins.read(buf); if (n < 0) break
                                size += n; if (size > 12_000_000) throw java.io.IOException("too big")
                                out.write(buf, 0, n)
                            }
                        } }
                        if (size > 1000 && tmp.renameTo(File(dir(app), name)).also { if (!it) { File(dir(app), name).delete(); tmp.renameTo(File(dir(app), name)) } }) ok++
                        else tmp.delete()
                    }
                    c.disconnect()
                } catch (_: Exception) { }
            }
            updating = false
            if (ok > 0) {
                prefs?.edit()?.putLong("filters_ts", System.currentTimeMillis())?.apply()
                loading = false
                load(app, false)
                done("Updated $ok of ${src.size} lists")
            } else done("Update failed (no internet?)")
        }.start()
    }

    // ---------------------------------------------------------------- request side

    fun typeOf(req: WebResourceRequest): Int {
        val u = req.url.path?.lowercase() ?: ""
        val acc = req.requestHeaders?.get("Accept")?.lowercase() ?: ""
        return when {
            u.endsWith(".js") || u.endsWith(".mjs") -> T_SCRIPT
            u.endsWith(".png") || u.endsWith(".jpg") || u.endsWith(".jpeg") || u.endsWith(".gif") || u.endsWith(".webp") ||
                u.endsWith(".svg") || u.endsWith(".ico") || u.endsWith(".avif") || acc.startsWith("image/") -> T_IMAGE
            u.endsWith(".css") || acc.startsWith("text/css") -> T_STYLE
            u.endsWith(".mp4") || u.endsWith(".webm") || u.endsWith(".m3u8") || u.endsWith(".mp3") || u.endsWith(".ts") -> T_MEDIA
            u.endsWith(".woff") || u.endsWith(".woff2") || u.endsWith(".ttf") || u.endsWith(".otf") -> T_FONT
            acc.contains("text/html") -> T_SUBDOC
            else -> T_XHR or T_OTHER
        }
    }

    /** 0 = no rule matches, 1 = a block rule matches, 2 = an exception rule matches (allow). Runs on the WebView network thread. */
    fun verdict(url: String, host: String, pageHost: String?, type: Int): Int {
        val ix = idx ?: return 0
        val u = url.lowercase()
        val h = host.lowercase()
        val ph = pageHost?.lowercase()?.removePrefix("www.")
        val third = ph == null || base(h) != base(ph)
        val se = u.indexOf("://")
        val hostEnd = if (se >= 0) { val i = u.indexOf(h, se + 3); if (i >= 0) i + h.length else -1 } else -1
        if (hit(ix.netExc, ix.genExc, u, h, hostEnd, ph, third, type)) return 2
        return if (hit(ix.net, ix.gen, u, h, hostEnd, ph, third, type)) 1 else 0
    }

    private fun hit(map: HashMap<String, ArrayList<Rule>>, gen: ArrayList<Rule>, u: String, h: String, hostEnd: Int, ph: String?, third: Boolean, type: Int): Boolean {
        var d = h
        while (true) {
            val l = map[d]
            if (l != null && hostEnd >= 0) for (r in l) if (opts(r, ph, third, type) && m(r.pat, 0, r.pat.length - (if (r.pat.endsWith("|")) 1 else 0), u, hostEnd, r.pat.endsWith("|"))) return true
            val i = d.indexOf('.')
            if (i < 0 || i == d.length - 1) break
            d = d.substring(i + 1)
        }
        for (r in gen) {
            if (!opts(r, ph, third, type)) continue
            val endA = r.pat.endsWith("|")
            val plen = r.pat.length - (if (endA) 1 else 0)
            if (r.startAnchor) { if (m(r.pat, 0, plen, u, 0, endA)) return true; continue }
            var p = u.indexOf(r.lit)
            while (p >= 0) {
                if (m(r.pat, 0, plen, u, p, endA)) return true
                p = u.indexOf(r.lit, p + 1)
            }
        }
        return false
    }

    private fun opts(r: Rule, ph: String?, third: Boolean, type: Int): Boolean {
        if ((r.types and type) == 0) return false
        if (r.third == 1 && !third) return false
        if (r.third == 2 && third) return false
        if (r.inc != null || r.exc != null) {
            if (ph == null) return r.inc == null
            if (r.exc != null && r.exc.any { ph == it || ph.endsWith(".$it") }) return false
            if (r.inc != null && r.inc.none { ph == it || ph.endsWith(".$it") }) return false
        }
        return true
    }

    private fun isSep(c: Char) = !(c.isLetterOrDigit() || c == '_' || c == '-' || c == '.' || c == '%')

    /** Wildcard match of pattern p[pi, plen) against s starting at si ('*' any, '^' separator or end). Pattern and s are lower case. */
    private fun m(p: String, pi: Int, plen: Int, s: String, si: Int, endA: Boolean): Boolean {
        var i = pi; var j = si
        while (i < plen) {
            val c = p[i]
            if (c == '*') {
                if (i + 1 >= plen) return true
                var k = j
                while (k <= s.length) { if (m(p, i + 1, plen, s, k, endA)) return true; k++ }
                return false
            } else if (c == '^') {
                if (j >= s.length) { i++; continue }
                if (!isSep(s[j])) return false
                i++; j++
            } else {
                if (j >= s.length || s[j] != c) return false
                i++; j++
            }
        }
        return !endA || j == s.length
    }

    private val SLD = setOf("co", "com", "org", "net", "gov", "edu", "ac")
    private fun base(h: String): String {
        val l = h.split('.')
        if (l.size <= 2) return h
        return if (l[l.size - 1].length == 2 && l[l.size - 2] in SLD && l.size >= 3) l.takeLast(3).joinToString(".") else l.takeLast(2).joinToString(".")
    }

    // ---------------------------------------------------------------- cosmetic side

    /** CSS rules (each selector alone, so one bad selector cannot break the others) for this page host, or "". */
    fun cosmeticCss(host: String): String {
        val ix = idx ?: return ""
        val sb = StringBuilder(ix.cosGen)
        var d = host.lowercase().removePrefix("www.")
        while (true) {
            ix.cosDom[d]?.forEach { sb.append(it).append("{display:none!important}") }
            val i = d.indexOf('.')
            if (i < 0 || i == d.length - 1) break
            d = d.substring(i + 1)
        }
        return sb.toString()
    }

    // ---------------------------------------------------------------- parser

    private class Builder {
        val net = HashMap<String, ArrayList<Rule>>(); val netExc = HashMap<String, ArrayList<Rule>>()
        val gen = ArrayList<Rule>(); val genExc = ArrayList<Rule>()
        val cosGen = ArrayList<String>(); val cosDom = HashMap<String, ArrayList<String>>()
        var count = 0; var cosDomN = 0

        private val BAD_SEL = listOf(":has-text", ":-abp-", ":xpath", ":matches-", ":upward", ":contains(", ":remove(", ":style(", ":min-text", ":watch-attr", ":others", ":nth-ancestor", "{", "}")

        fun add(raw: String) {
            val line = raw.trim()
            if (line.isEmpty() || line[0] == '!' || line[0] == '[') return
            if (line.contains("#@#") || line.contains("#?#") || line.contains("#$#") || line.contains("#%#") || line.contains("+js(")) return
            val c = line.indexOf("##")
            if (c >= 0) { cosmetic(line.substring(0, c), line.substring(c + 2)); return }
            network(line)
        }

        private fun cosmetic(domains: String, sel: String) {
            if (sel.isEmpty() || sel.length > 80 || BAD_SEL.any { sel.contains(it) }) return
            if (domains.isEmpty()) { if (cosGen.size < MAX_COS_GEN) { cosGen.add(sel); count++ }; return }
            for (d in domains.split(',')) {
                val dd = d.trim().lowercase()
                if (dd.isEmpty() || dd.startsWith("~") || dd.contains('*') || dd.contains('/') || cosDomN >= MAX_COS_DOM) continue
                cosDom.getOrPut(dd) { ArrayList() }.add(sel); cosDomN++; count++
            }
        }

        private fun network(l0: String) {
            var l = l0
            var exception = false
            if (l.startsWith("@@")) { exception = true; l = l.substring(2) }
            var third = 0; var types = T_ALL; var neg = 0; var pos = 0
            var inc: Array<String>? = null; var exc: Array<String>? = null
            val d = l.lastIndexOf('$')
            if (d >= 0 && !l.substring(d).contains('/') ) {
                val o = l.substring(d + 1); l = l.substring(0, d)
                for (op0 in o.split(',')) {
                    val op = op0.trim().lowercase()
                    when {
                        op == "third-party" || op == "3p" -> third = 1
                        op == "~third-party" || op == "first-party" || op == "1p" -> third = 2
                        op == "important" || op == "match-case" || op == "all" -> { }
                        op.startsWith("domain=") -> {
                            val inl = ArrayList<String>(); val ex = ArrayList<String>()
                            for (x in op.substring(7).split('|')) if (x.startsWith("~")) ex.add(x.substring(1)) else if (x.isNotEmpty()) inl.add(x)
                            inc = if (inl.isEmpty()) null else inl.toTypedArray(); exc = if (ex.isEmpty()) null else ex.toTypedArray()
                        }
                        else -> {
                            val neg1 = op.startsWith("~"); val n = if (neg1) op.substring(1) else op
                            val t = when (n) {
                                "script" -> T_SCRIPT; "image" -> T_IMAGE; "stylesheet", "css" -> T_STYLE; "xmlhttprequest", "xhr" -> T_XHR
                                "subdocument", "frame" -> T_SUBDOC; "media" -> T_MEDIA; "font" -> T_FONT
                                "other", "ping", "object", "websocket" -> T_OTHER
                                "popup" -> T_POPUP // 0.0.480
                                else -> return // unsupported option: skip the whole rule
                            }
                            if (neg1) neg = neg or t else pos = pos or t
                        }
                    }
                }
                if (pos != 0) types = pos
                if (neg != 0) types = types and neg.inv()
            }
            l = l.lowercase()
            if (l.length < 3 || (l.startsWith("/") && l.endsWith("/"))) return // regex rule
            if (count >= MAX_NET) return
            if (l.startsWith("||")) {
                var e = 2
                while (e < l.length && l[e] != '^' && l[e] != '/' && l[e] != '*' && l[e] != ':' && l[e] != '?' && l[e] != '|') e++
                val dom = l.substring(2, e)
                if (dom.isEmpty() || dom.contains('*') || !dom.contains('.')) return
                val rest = l.substring(e)
                val r = Rule(rest, true, false, "", third, types, inc, exc)
                (if (exception) netExc else net).getOrPut(dom) { ArrayList() }.add(r); count++
                return
            }
            var start = false
            if (l.startsWith("|") && !l.startsWith("||")) { start = true; l = l.substring(1) }
            var lit = ""
            run { var e = 0; while (e < l.length && l[e] != '*' && l[e] != '^' && l[e] != '|') e++; lit = l.substring(0, e) }
            if (!start && lit.length < 5) return
            val target = if (exception) genExc else gen
            if (target.size >= MAX_GEN) return
            target.add(Rule(l, false, start, lit, third, types, inc, exc)); count++
        }

        fun build(): Index {
            val sb = StringBuilder()
            for (s in cosGen) sb.append(s).append("{display:none!important}")
            return Index(net, netExc, gen, genExc, sb.toString(), cosDom, count)
        }
    }
}
