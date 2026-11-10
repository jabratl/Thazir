package com.jabra.mgb

import android.content.Context

/**
 * 0.0.388 (PW2): smarter login detection. Everything here is optional: the switch "Smarter login detection" (default ON)
 * OFF = the old HOOK_JS and exact-host matching run exactly as before.
 *  - HOOK2_JS: two-step logins (user name page -> onUser), change-password forms (onChange reports the NEW password),
 *    Enter key, open shadow roots. The old Bridge calls (onSubmit / onField / onFocus / onNewPw) are unchanged.
 *  - forHostSmart: exact host first, then logins of the same site (m.site.com <-> site.com <-> login.site.com).
 *  - the user name of step 1 lives only in memory (one tab, 2 minutes), it is never written anywhere.
 * NOT done here: login forms inside iframes (needs a device test, see HANDOFF).
 */
object PwSmart {
    private const val PREF = "mgb_pw"
    private const val KEY = "smart"
    private const val KEEP_MS = 120_000L

    fun on(ctx: Context): Boolean = try { ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getBoolean(KEY, true) } catch (e: Exception) { false }
    fun set(ctx: Context, v: Boolean) { ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putBoolean(KEY, v).apply() }

    /** The script injected on page finish. Any problem = the old script. */
    fun hookJs(ctx: Context?): String = try { if (ctx != null && on(ctx)) HOOK2_JS else PasswordStore.HOOK_JS } catch (e: Exception) { PasswordStore.HOOK_JS }

    // ---- two-step: user name of step 1 (memory only)

    private class Pend(val tab: String, val site: String, val user: String, val at: Long)
    private var pend: Pend? = null

    fun noteUser(tab: String, host: String, user: String) {
        if (user.isBlank()) return
        pend = Pend(tab, registrable(host), user, System.currentTimeMillis())
    }

    /** "" when nothing fresh for this tab + site. The value is used once. */
    fun takeUser(tab: String, host: String): String {
        val p = pend ?: return ""
        if (System.currentTimeMillis() - p.at > KEEP_MS || p.tab != tab || p.site != registrable(host)) return ""
        pend = null
        return p.user
    }

    /** Change-password form: no user name on the page = step-1 user name, else the only saved login of this site. */
    fun resolveUser(ctx: Context, tab: String, host: String, user: String): String {
        if (user.isNotBlank()) return user
        val t = takeUser(tab, host)
        if (t.isNotBlank()) return t
        val es = PasswordStore.forHost(ctx, host)
        return if (es.size == 1) es[0].user else ""
    }

    // ---- related hosts

    private val TWO = setOf("co.uk", "org.uk", "ac.uk", "gov.uk", "com.au", "co.in", "co.jp", "com.br", "co.nz", "com.sg", "com.my", "com.pk",
        "com.bd", "net.bd", "org.bd", "edu.bd", "gov.bd", "co.za", "com.tr", "com.cn", "com.hk", "co.id", "com.ph", "com.np", "com.lk")
    // shared hosting: every name under it is a different owner, so the site name is one label more
    private val SHARED = setOf("github.io", "blogspot.com", "herokuapp.com", "vercel.app", "netlify.app", "pages.dev", "workers.dev", "web.app",
        "firebaseapp.com", "appspot.com", "wordpress.com", "myshopify.com", "azurewebsites.net", "onrender.com", "glitch.me", "weebly.com", "wixsite.com")

    fun registrable(host: String): String {
        val h = host.lowercase().trim('.')
        if (h.isEmpty() || h.contains(':') || h.all { it.isDigit() || it == '.' }) return h // IP address
        val p = h.split('.')
        if (p.size <= 2) return h
        val l2 = p.takeLast(2).joinToString(".")
        return if (SHARED.contains(l2) || TWO.contains(l2)) p.takeLast(3).joinToString(".") else l2
    }

    /** Exact host first, then the same site on another name. Switch OFF = exact only (old behaviour). */
    fun forHostSmart(ctx: Context, host: String): List<PasswordStore.Entry> {
        val exact = PasswordStore.forHost(ctx, host)
        if (!on(ctx)) return exact
        val r = registrable(host)
        return exact + PasswordStore.all(ctx).filter { it.host != host && registrable(it.host) == r }
    }

    const val HOOK2_JS = """(function(){
if(window.__mgbpw)return;window.__mgbpw=1;
function tg(e){return (e.composedPath&&e.composedPath()[0])||e.target;}
function vis(e){return e.offsetParent!==null||(e.getClientRects&&e.getClientRects().length>0);}
function walk(root,out,sel,d){
try{var l=root.querySelectorAll(sel);for(var i=0;i<l.length;i++)out.push(l[i]);
if(d>4)return;var a=root.querySelectorAll('*'),n=Math.min(a.length,3000);
for(var j=0;j<n;j++){var s=a[j].shadowRoot;if(s)walk(s,out,sel,d+1);}}catch(x){}
}
function qa(root,sel){var o=[];walk(root||document,o,sel,0);return o;}
function isPw(e){return (e.type||'').toLowerCase()==='password';}
function userBefore(list,p){var u='';for(var i=0;i<list.length;i++){if(list[i]===p)break;var t=(list[i].type||'text').toLowerCase();if((t==='text'||t==='email'||t==='tel')&&list[i].value)u=list[i].value;}return u;}
function userLike(e){var t=(e.type||'text').toLowerCase();if(!(t==='text'||t==='email'||t==='tel')||!e.value||!vis(e))return false;
var ac=(e.getAttribute('autocomplete')||'').toLowerCase();if(/username|email|tel/.test(ac)||t==='email')return true;
return /user|e-?mail|login|account|phone|ident|mobile|member/.test(((e.name||'')+' '+(e.id||'')).toLowerCase());}
function grab(root){
var list=qa(root,'input'),pws=[],seePw=false,i;
for(i=0;i<list.length;i++){if(isPw(list[i])){if(list[i].value)pws.push(list[i]);if(vis(list[i]))seePw=true;}}
if(!pws.length){
if(!seePw){for(i=0;i<list.length;i++){if(userLike(list[i])){try{MGBPW.onUser(list[i].value);}catch(x){}break;}}}
return;}
var seen={},n=0;for(i=0;i<pws.length;i++){if(!seen[pws[i].value]){seen[pws[i].value]=1;n++;}}
if(n>=2){var np=null;for(i=0;i<pws.length;i++){if((pws[i].getAttribute('autocomplete')||'').toLowerCase()==='new-password')np=pws[i];}
if(!np)np=pws[pws.length-1];
try{MGBPW.onChange(userBefore(list,pws[0]),np.value);}catch(x){}return;}
try{MGBPW.onSubmit(userBefore(list,pws[0]),pws[0].value);}catch(x){}
}
document.addEventListener('submit',function(e){grab(e.target);},true);
document.addEventListener('click',function(e){var t=tg(e);
var b=t&&t.closest?t.closest('button,input[type=submit],input[type=button],[role=button]'):null;
if(!b)return;grab(b.form||b.closest('form')||document);},true);
document.addEventListener('keydown',function(e){if(e.key!=='Enter'||e.isComposing)return;var t=tg(e);
if(!t||t.tagName!=='INPUT')return;grab(t.form||document);},true);
function hasPw(){return !!document.querySelector('input[type=password]')||qa(document,'input[type=password]').length>0;}
if(hasPw()){try{MGBPW.onField();}catch(x){}}
function isNew(p){
var ac=(p.getAttribute('autocomplete')||'').toLowerCase();
if(ac==='new-password')return true;
if(ac==='current-password')return false;
var f=p.form||document,n=0,l=f.querySelectorAll('input[type=password]');
for(var i=0;i<l.length;i++){if(l[i].offsetParent!==null)n++;}
if(n>=2)return true;
var s=((p.name||'')+' '+(p.id||'')+' '+(location.pathname||'')+' '+(f.id||'')+' '+(f.className||'')+' '+String(f.action||'')).toLowerCase();
return /sign.?up|regist|create.?account|join|new.?pass/.test(s);
}
document.addEventListener('focusin',function(e){var t=tg(e);
if(t&&t.tagName==='INPUT'&&/^(text|email|tel|password)$/i.test(t.type||'text')&&hasPw()){try{MGBPW.onFocus();}catch(x){}}
if(t&&t.tagName==='INPUT'&&isPw(t)&&isNew(t)){try{MGBPW.onNewPw();}catch(x){}}
},true);
})();"""
}
