package com.jabra.mgb

import android.content.Context
import android.net.Uri
import android.webkit.WebView

/**
 * REDDIT MATURE CONTENT (0.0.415): the new Reddit site (www.reddit.com) shows "Mature Content - To view the full version of this content,
 * view this page in the Reddit app" instead of an 18+ post in a browser. The classic site (old.reddit.com) has no such wall
 * (at most a one-time "are you over 18" question, which the cookie over18=1 answers).
 *
 * What it does: on a reddit.com page a small script watches (up to 25 s, a short text check every 600 ms, only on short pages)
 * for that wall. When it shows up the script sets over18=1 and opens the SAME address on old.reddit.com. Pages without the wall are never touched.
 * On old.reddit.com the script presses "yes" on the over-18 question if it is shown.
 * Switch: key rd_mature in mgb_ui, default ON (set it to false to turn the script off). Needs JavaScript ON for the page.
 * Rollback: remove the two RedditMature.inject calls in TabManager.
 */
object RedditMature {
    private const val KEY = "rd_mature"
    fun on(c: Context): Boolean = try { c.getSharedPreferences("mgb_ui", Context.MODE_PRIVATE).getBoolean(KEY, true) } catch (_: Throwable) { true }

    private fun isReddit(url: String?): Boolean {
        if (url == null || !url.startsWith("https://", true)) return false
        val h = try { Uri.parse(url).host?.lowercase() ?: return false } catch (_: Throwable) { return false }
        return h == "reddit.com" || h.endsWith(".reddit.com")
    }

    private const val JS = "(function(){try{var n=Date.now();if(window.__mgRm&&window.__mgRm>n)return;window.__mgRm=n+25000;" +
        "var h=location.hostname;" +
        "var ck=function(){try{document.cookie='over18=1; domain=.reddit.com; path=/; max-age=31536000; secure';}catch(e){}};" +
        "var t=setInterval(function(){try{" +
        "if(Date.now()>window.__mgRm){clearInterval(t);return;}" +
        "if(h==='old.reddit.com'){if(/^\\/over18/.test(location.pathname)){ck();var b=document.querySelector('button[value=yes],input[value=yes]');if(b)b.click();}return;}" +
        "var b=document.body;if(!b)return;var x=b.innerText||'';if(x.length>4000)return;" +
        "if(/mature content/i.test(x)&&/(reddit app|in the app)/i.test(x)){clearInterval(t);ck();" +
        "location.replace(location.href.replace(/^https:\\/\\/[^\\/]+/,'https://old.reddit.com'));}" +
        "}catch(e){}},600);}catch(e){}})();"

    fun inject(ctx: Context, view: WebView?, url: String?) {
        if (view == null || !isReddit(url) || !on(ctx)) return
        try { view.evaluateJavascript(JS, null) } catch (_: Throwable) { }
    }
}
