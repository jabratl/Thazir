package com.jabra.mgb

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.webkit.CookieManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.json.JSONArray
import org.json.JSONTokener
import java.util.concurrent.ConcurrentHashMap

/** S29: per-site switches (bit flags per host, saved in prefs mgb_site). Read from the WebView network thread too. */
object SiteConfig {
    private const val PREFS = "mgb_site"
    const val JS_OFF = 1
    const val ADS_OK = 2
    const val DESKTOP = 4

    private val map = ConcurrentHashMap<String, Int>()
    private var prefs: android.content.SharedPreferences? = null

    fun init(ctx: Context) {
        val p = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs = p
        map.clear()
        for ((k, v) in p.all) if (v is Int) map[k] = v
    }

    fun hostOf(url: String?): String =
        try { Uri.parse(url ?: "").host?.lowercase()?.removePrefix("www.") ?: "" } catch (e: Exception) { "" }

    fun flags(host: String): Int = map[host] ?: 0

    fun has(url: String?, bit: Int): Boolean {
        val h = hostOf(url)
        return h.isNotEmpty() && (flags(h) and bit) != 0
    }

    /** S42: hosts that have this flag set (sorted). */
    fun hostsWith(bit: Int): List<String> = map.filter { (it.value and bit) != 0 }.keys.sorted()

    /** Flips one flag, returns the new state of that flag. */
    fun toggle(host: String, bit: Int): Boolean {
        val n = flags(host) xor bit
        if (n == 0) {
            map.remove(host)
            prefs?.edit()?.remove(host)?.apply()
        } else {
            map[host] = n
            prefs?.edit()?.putInt(host, n)?.apply()
        }
        return (n and bit) != 0
    }
}

/**
 * S29: "Page info" (menu): View certificate, View cookies, View scripts, Site configuration, Show reader.
 * All dialogs are plain MaterialAlertDialogs, no manifest entry.
 */
object PageInfo {

    fun show(act: Activity, tabs: TabManager) {
        val t = tabs.active
        if (t == null || t.home || t.isExt || !t.url.startsWith("http")) {
            Util.toast(act, "No page info here")
            return
        }
        val items = arrayOf("View certificate", "View cookies", "View scripts", "Site configuration", "Show reader")
        MaterialAlertDialogBuilder(act)
            .setTitle(Util.host(t.url))
            .setItems(items) { _, i ->
                when (i) {
                    0 -> certificate(act, t)
                    1 -> cookies(act, t)
                    2 -> scripts(act, t)
                    3 -> siteConfig(act, tabs, t)
                    else -> reader(act, t)
                }
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun info(act: Activity, title: String, msg: String) {
        MaterialAlertDialogBuilder(act).setTitle(title).setMessage(msg).setPositiveButton("OK", null).show()
    }

    private fun copy(act: Activity, text: String) {
        val cm = act.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("MGB", text))
        Util.toast(act, "Copied")
    }

    // ---- certificate

    private fun certificate(act: Activity, t: TabManager.Tab) {
        val c = t.web.certificate
        val msg = when {
            !t.url.startsWith("https") -> "This page is not encrypted (http), so it has no certificate."
            c == null -> "The WebView gave no certificate for this page."
            else -> {
                val to = c.getIssuedTo()
                val by = c.getIssuedBy()
                "Issued to\n" + (to?.getCName() ?: "") + "\n" + (to?.getOName() ?: "") +
                    "\n\nIssued by\n" + (by?.getCName() ?: "") + "\n" + (by?.getOName() ?: "") +
                    "\n\nValid from\n" + (c.getValidNotBefore() ?: "") +
                    "\n\nValid until\n" + (c.getValidNotAfter() ?: "")
            }
        }
        info(act, "Certificate", msg)
    }

    // ---- cookies

    private fun cookies(act: Activity, t: TabManager.Tab) {
        val url = t.url
        val raw = CookieManager.getInstance().getCookie(url) ?: ""
        val parts = raw.split(";").map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.isEmpty()) {
            info(act, "Cookies", "No cookies for this site.")
            return
        }
        val lines = parts.map {
            val i = it.indexOf('=')
            if (i < 0) it else it.substring(0, i) + " = " + it.substring(i + 1).take(60)
        }.toTypedArray()
        MaterialAlertDialogBuilder(act)
            .setTitle("Cookies (" + parts.size + ")")
            .setItems(lines) { _, i -> copy(act, parts[i]) }
            .setPositiveButton("Delete all") { _, _ ->
                deleteCookies(url, parts)
                Util.toast(act, "Cookies deleted")
                t.web.reload()
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun deleteCookies(url: String, parts: List<String>) {
        val cm = CookieManager.getInstance()
        val host = try { Uri.parse(url).host ?: return } catch (e: Exception) { return }
        val domains = ArrayList<String>()
        var h = host
        while (h.contains('.')) {
            domains.add("; Domain=.$h")
            h = h.substringAfter('.')
        }
        domains.add("")
        for (p in parts) {
            val name = p.substringBefore('=')
            for (d in domains) cm.setCookie(url, "$name=; Max-Age=0; Path=/$d")
        }
        cm.flush()
    }

    // ---- scripts

    private const val SCRIPTS_JS = "(function(){var o=[];document.querySelectorAll('script').forEach(function(s){" +
        "var c=s.textContent||'';o.push({s:s.src||'',c:s.src?'':c.slice(0,6000),n:c.length});});return JSON.stringify(o);})()"

    private fun scripts(act: Activity, t: TabManager.Tab) {
        t.web.evaluateJavascript(SCRIPTS_JS) { res ->
            val text = try { JSONTokener(res ?: "null").nextValue() as? String } catch (e: Exception) { null }
            val arr = try { JSONArray(text ?: "[]") } catch (e: Exception) { JSONArray() }
            if (arr.length() == 0) {
                info(act, "Scripts", "No scripts found on this page.")
                return@evaluateJavascript
            }
            val labels = ArrayList<String>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i)
                val s = o?.optString("s") ?: ""
                labels.add(if (s.isNotEmpty()) s.removePrefix("https://") else "[inline] " + (o?.optInt("n") ?: 0) + " chars")
            }
            MaterialAlertDialogBuilder(act)
                .setTitle("Scripts (" + arr.length() + ")")
                .setItems(labels.toTypedArray()) { _, i ->
                    val o = arr.optJSONObject(i)
                    val s = o?.optString("s") ?: ""
                    if (s.isNotEmpty()) copy(act, s)
                    else {
                        val code = o?.optString("c") ?: ""
                        MaterialAlertDialogBuilder(act)
                            .setTitle("Inline script")
                            .setMessage(code.ifEmpty { "(empty)" })
                            .setPositiveButton("Copy") { _, _ -> copy(act, code) }
                            .setNegativeButton("Close", null)
                            .show()
                    }
                }
                .setNegativeButton("Close", null)
                .show()
        }
    }

    // ---- site configuration

    private fun siteConfig(act: Activity, tabs: TabManager, t: TabManager.Tab) {
        val host = SiteConfig.hostOf(t.url)
        if (host.isEmpty()) return
        val f = SiteConfig.flags(host)
        val names = arrayOf("JavaScript off for this site", "Allow ads here (ad blocker off)", "Always open as desktop site")
        val bits = intArrayOf(SiteConfig.JS_OFF, SiteConfig.ADS_OK, SiteConfig.DESKTOP)
        val checked = BooleanArray(3) { (f and bits[it]) != 0 }
        MaterialAlertDialogBuilder(act)
            .setTitle("Site configuration: $host")
            .setMultiChoiceItems(names, checked) { _, i, on -> checked[i] = on }
            .setPositiveButton("Apply") { _, _ ->
                for (i in 0..2) if (checked[i] != ((f and bits[i]) != 0)) SiteConfig.toggle(host, bits[i])
                t.web.settings.javaScriptEnabled = !checked[0]
                tabs.setDesktop(t, checked[2]) // reloads the page
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ---- reader

    private const val READER_JS = "(function(){var d=document;" +
        "var root=d.querySelector('article')||d.querySelector('main')||d.querySelector('[role=main]');" +
        "if(!root){var best=null,bl=0;d.querySelectorAll('div,section').forEach(function(e){var l=0;" +
        "e.querySelectorAll(':scope > p').forEach(function(p){l+=p.textContent.length;});if(l>bl){bl=l;best=e;}});root=best||d.body;}" +
        "var title=(d.querySelector('h1')||{}).textContent||d.title||'';" +
        "var out=d.createElement('div');out.id='mgb-reader';" +
        "var h=d.createElement('h1');h.textContent=title.trim();out.appendChild(h);" +
        "root.querySelectorAll('h2,h3,p,li,blockquote,pre,img').forEach(function(n){var tg=n.tagName.toLowerCase();" +
        "if(tg==='img'){var s=n.currentSrc||n.src;if(s&&n.naturalWidth>150){var im=d.createElement('img');im.src=s;out.appendChild(im);}return;}" +
        "var tx=(n.textContent||'').trim();if(tx.length<(tg==='h2'||tg==='h3'?2:25))return;" +
        "var e=d.createElement(tg==='li'?'p':tg);e.textContent=tx;out.appendChild(e);});" +
        "if(out.childNodes.length<3){return 'none';}" +
        "d.querySelectorAll('style,link[rel=stylesheet]').forEach(function(e){e.remove();});" +
        "var st=d.createElement('style');" +
        "st.textContent='html,body{margin:0;background:__BG__;color:__FG__}#mgb-reader{max-width:680px;margin:0 auto;padding:20px 18px 80px;" +
        "font:19px/1.65 Georgia,serif}#mgb-reader h1{font:700 26px/1.3 sans-serif}#mgb-reader h2,#mgb-reader h3{font-family:sans-serif}" +
        "#mgb-reader img{max-width:100%;height:auto;border-radius:8px}#mgb-reader blockquote{border-left:3px solid #888;margin:0;padding-left:14px}" +
        "#mgb-reader pre{white-space:pre-wrap;font-size:14px}';" +
        "d.head.appendChild(st);d.body.innerHTML='';d.body.removeAttribute('class');d.body.removeAttribute('style');d.body.appendChild(out);" +
        "window.scrollTo(0,0);return 'ok';})()"

    fun reader(act: Activity, t: TabManager.Tab) { // S41: also a menu row
        val dark = (act.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        fun hex(id: Int) = String.format("#%06X", act.getColor(id) and 0xFFFFFF) // 0.0.422: reader follows the app tokens (soft light / dark), not pure white
        val js = READER_JS.replace("__BG__", if (dark) "#0A0C10" else hex(R.color.mg_surface)).replace("__FG__", if (dark) "#ECEFF4" else hex(R.color.mg_text))
        t.web.evaluateJavascript(js) { res ->
            if ((res ?: "").contains("none")) Util.toast(act, "No readable article found on this page")
            else Util.toast(act, "Reader view (reload the page to leave)")
        }
    }
}
