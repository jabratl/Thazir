package com.jabra.mgb

import android.content.Context
import android.webkit.WebView

/**
 * 0.0.302: Instagram Reels like the app.
 * 1. Tapping a reel / video tile opens the reels viewer (instagram.com/reels/<id>/: full screen, swipe up = next reel) instead of the
 *    single "Post" page (/p/<id>/ or /reel/<id>/), which cannot scroll to the next reel. Switch sm_reels (default ON, Social page > Extras).
 * 1b. 0.0.315: on a profile the tap keeps that profile: swipe goes to the next reel of the SAME account (see JS comment).
 * 2. Pull-to-refresh is not armed on reel / story / shorts pages: their scroller is inside the page (WebView.scrollY stays 0), so every
 *    swipe down to the previous reel was counted as "pull to reload".
 */
object SocialReels {
    private const val K = "sm_reels"
    private fun sp(c: Context) = c.getSharedPreferences("mgb_ui", Context.MODE_PRIVATE)
    fun on(c: Context): Boolean = sp(c).getBoolean(K, true)
    fun setOn(c: Context, v: Boolean) { sp(c).edit().putBoolean(K, v).apply() }

    /** 0.0.309: full screen (address bar + phone bars hidden) while a reel page is open, like the app. Switch sm_reelfull, default ON. Handle / Back shows the bars. */
    fun fullOn(c: Context): Boolean = sp(c).getBoolean("sm_reelfull", true)
    fun setFullOn(c: Context, v: Boolean) { sp(c).edit().putBoolean("sm_reelfull", v).apply() }
    // 0.0.315: instagram.com/<user>/reels/ is the profile Reels tab (a grid), not a reel page: no full screen, pull-to-refresh stays
    private const val GRID = "(?!https?://[^/]*instagram\\.com/[^/?#]+/reels/?(?:[?#]|$))"
    private val REEL = Regex("^" + GRID + "https?://[^/]+/(?:[^/?#]+/)?(reels?)(/|\\?|#|$)", RegexOption.IGNORE_CASE)
    fun isReelPage(url: String?): Boolean = url != null && REEL.containsMatchIn(url)

    private val NO_PULL = Regex("^" + GRID + "https?://([^/]+\\.)?(instagram\\.com|facebook\\.com|tiktok\\.com|youtube\\.com)/(?:[^/?#]+/)?(reels?|stories|shorts|watch|video)(/|\\?|#|$)", RegexOption.IGNORE_CASE)
    /** true = this page scrolls inside itself (reels / shorts / stories): pull-to-refresh stays off. */
    fun noPull(url: String?): Boolean = url != null && NO_PULL.containsMatchIn(url)

    // 0.0.315 / 0.0.318: a reel tapped on a PROFILE (main grid or Reels tab) opens /reel/<id>/ and swipe up / down walks through THAT
    // profile's reels (list in sessionStorage __mgPl, 30 min, dropped as soon as you leave that profile). A tile tapped on Explore opens
    // the global /reels/<id>/ viewer. Home feed, stories, suggestions and every other page are NOT touched (0.0.318: before, any video
    // link anywhere, e.g. in the Home feed, was sent to the global viewer = other / suggested accounts). A /reel/<id>/ link opened from
    // outside (no referrer) still goes to the global viewer.
    private const val JS = """(function(){try{
var R=/^\/(?:[^\/]+\/)?(reel|reels|p)\/([A-Za-z0-9_-]+)/;
var SK='__mgPl';
var VS='svg[aria-label*="eel" i],svg[aria-label*="lip" i],svg[aria-label*="ideo" i],video';
var RES={reels:1,reel:1,p:1,explore:1,stories:1,direct:1,accounts:1,tv:1,web:1,about:1,legal:1,developer:1,directory:1,challenge:1,emails:1,session:1};
function ld(){try{var o=JSON.parse(sessionStorage.getItem(SK)||'null');if(o&&o.ids&&Date.now()-o.t<1800000)return o;}catch(e){}return null;}
function drop(){try{sessionStorage.removeItem(SK);}catch(e){}}
function go(id){try{var k='__mgRl'+id;if(sessionStorage.getItem(k))return false;sessionStorage.setItem(k,'1');}catch(e){}location.assign('/reels/'+id+'/');return true;}
function curId(){var q=R.exec(location.pathname);return q&&q[1]==='reel'?q[2]:null;}
var cur=curId();
var pl=ld();
var inList=!!(cur&&pl&&pl.ids.indexOf(cur)>=0);
if(pl&&!inList&&location.pathname.toLowerCase().indexOf('/'+String(pl.u).toLowerCase()+'/')!==0)drop();
if(cur&&!inList&&!document.referrer){go(cur);}
if(inList&&!window.__mgRlPg){window.__mgRlPg=1;
var busy=false;
function toast(t){try{var d=document.createElement('div');d.textContent=t;d.style.cssText='position:fixed;left:50%;bottom:90px;transform:translateX(-50%);background:rgba(20,23,34,.92);color:#F0EFF8;padding:10px 16px;border-radius:16px;font:13px sans-serif;z-index:2147483647;pointer-events:none';document.body.appendChild(d);setTimeout(function(){d.remove();},2200);}catch(e){}}
function step(d){if(busy)return;var o=ld(),c=curId();if(!o||!c)return;var i=o.ids.indexOf(c);if(i<0)return;var n=i+d;if(n<0||n>=o.ids.length){toast(d>0?'Last loaded reel of this profile':'First reel of this profile');return;}busy=true;setTimeout(function(){busy=false;},2500);location.replace('/reel/'+o.ids[n]+'/');}
var sx=0,sy=0,st=0,bad=false;
document.addEventListener('touchstart',function(e){var t=e.touches[0];sx=t.clientX;sy=t.clientY;st=Date.now();bad=!!(e.touches.length>1||(e.target&&e.target.closest&&e.target.closest('[role=dialog],input,textarea,[contenteditable=true]')));},{passive:true});
document.addEventListener('touchend',function(e){if(bad)return;var t=e.changedTouches[0];var dx=t.clientX-sx,dy=t.clientY-sy;if(Math.abs(dy)<100||Math.abs(dy)<Math.abs(dx)*1.8||Date.now()-st>800)return;step(dy<0?1:-1);},{passive:true});
}
if(window.__mgRlOn)return;window.__mgRlOn=1;
document.addEventListener('click',function(e){try{
var a=e.target&&e.target.closest?e.target.closest('a[href]'):null;if(!a)return;
var u=new URL(a.href,location.href);if(u.host.indexOf('instagram.com')<0)return;
var x=R.exec(u.pathname);if(!x||x[1]==='reels')return;
var vid=x[1]==='reel'||!!a.querySelector(VS);
if(!vid)return;
var pm=/^\/([^\/]+)\/(?:reels\/?)?$/.exec(location.pathname);
var prof=!!(pm&&!RES[pm[1].toLowerCase()]);
var expl=/^\/explore(\/|$)/.test(location.pathname);
if(!prof&&!expl)return;
e.preventDefault();e.stopImmediatePropagation();
if(prof){
var ids=[],seen={};
var all=document.querySelectorAll('a[href]');
for(var j=0;j<all.length;j++){var q=all[j],w,pn;try{pn=new URL(q.href,location.href).pathname;w=R.exec(pn);}catch(z){continue;}
if(!w||w[1]==='reels'||seen[w[2]])continue;
var seg=pn.split('/')[1]||'';
if(seg.toLowerCase()!==pm[1].toLowerCase()&&seg!=='p'&&seg!=='reel')continue;
if(w[1]==='reel'||q.querySelector(VS)){seen[w[2]]=1;ids.push(w[2]);}}
if(ids.indexOf(x[2])<0)ids.push(x[2]);
try{sessionStorage.setItem(SK,JSON.stringify({u:pm[1],t:Date.now(),ids:ids}));}catch(z){}
location.assign('/reel/'+x[2]+'/');return;}
drop();try{sessionStorage.removeItem('__mgRl'+x[2]);}catch(z){}go(x[2]);
}catch(e){}},true);
}catch(e){}})();"""

    fun inject(ctx: Context, view: WebView, id: String?) {
        if (id != "instagram" || !on(ctx)) return
        try { view.evaluateJavascript(JS, null) } catch (_: Exception) { }
    }
}
