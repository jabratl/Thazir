package com.jabra.mgb

import android.app.Activity
import android.content.ClipData
import android.content.ClipDescription
import android.os.Build
import android.os.PersistableBundle
import android.view.WindowManager
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.JavascriptInterface
import androidx.fragment.app.FragmentActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import com.google.android.material.snackbar.Snackbar
import java.security.SecureRandom
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * S30: built-in password manager. Logins are saved per host (user + password); the password is AES-GCM encrypted with
 * a Keystore key (Vault) and stored in filesDir/mgb_pw.json. NOT part of Export data.
 * Page side: HOOK_JS (injected on page finish, normal tabs only) reports a submitted login form to the native
 * Bridge. The page can only call onSubmit / onField; the host always comes from the tab url (not from the page),
 * saving always asks the user, and filling is done by native code after Vault.unlock (menu: Fill password).
 */
object PasswordStore {
    private const val TAG = "MGB.pw"
    private const val FILE = "mgb_pw.json"
    private val main = Handler(Looper.getMainLooper())

    /** 0.0.391 (PW5): one replaced password (Vault-encrypted) and the time it was replaced. */
    class Hist(val enc: String, val at: Long)

    class Entry(val host: String, val user: String, val enc: String) {
        // 0.0.391 (PW5): optional extras. Old files have none (stay empty / 0). Changed IN PLACE (same object) so open screens stay valid.
        @Volatile var notes: String = "" // Vault.encrypt(text); "" = no note
        @Volatile var created: Long = 0L
        @Volatile var modified: Long = 0L // time the password was last changed
        @Volatile var lastUsed: Long = 0L // last Fill (written at most once an hour)
        @Volatile var fav: Boolean = false
        @Volatile var totp: String = "" // 0.0.392 (PW6a): Vault.encrypt(2FA secret or otpauth link); "" = none
        val hist = ArrayList<Hist>() // newest first, max 5
    }

    private val list = ArrayList<Entry>()
    private var loaded = false
    private var lastKey = ""
    private var lastAt = 0L
    private var lastHint = ""

    // ---- storage

    // 0.0.387 (PW1): a file that cannot be read is NEVER treated as "empty" any more (see PwFile). readFailed = nothing is written until the user decides.
    @Volatile var readFailed = false
    private var failCopy: String? = null
    private var notice: String? = null

    @Synchronized
    private fun load(ctx: Context) {
        if (loaded) return
        loaded = true
        try {
            val r = PwFile.read(ctx)
            if (r.state == 2) {
                readFailed = true
                failCopy = r.copy
                return
            }
            if (r.state == 1) notice = "The password file was damaged. The last good copy was loaded."
            val a = r.array ?: return
            for (i in 0 until a.length()) {
                val o = a.optJSONObject(i) ?: continue
                list.add(try { PwMore.fromJson(o) } catch (x: Exception) { Entry(o.optString("h"), o.optString("u"), o.optString("p")) }) // 0.0.391: optional extras
            }
        } catch (e: Exception) { Log.e(TAG, "load failed", e); readFailed = true }
    }

    @Synchronized
    private fun save(ctx: Context): Boolean {
        if (readFailed) return false
        return try {
            val a = JSONArray()
            for (e in list) a.put(try { PwMore.toJson(e) } catch (x: Exception) { JSONObject().put("h", e.host).put("u", e.user).put("p", e.enc) }) // 0.0.391: h / u / p first, extras only when set
            PwFile.write(ctx, a.toString())
        } catch (e: Exception) { Log.e(TAG, "save failed", e); false }
    }

    /** Save the changed list; when it cannot be written the list goes back to what it was (memory = file). */
    @Synchronized
    private fun commit(ctx: Context, before: List<Entry>): Boolean {
        if (save(ctx)) return true
        list.clear(); list.addAll(before)
        return false
    }

    @Synchronized fun all(ctx: Context): List<Entry> { load(ctx); return list.sortedBy { it.host } }
    @Synchronized fun forHost(ctx: Context, host: String): List<Entry> { load(ctx); return list.filter { it.host == host } }

    /** true = saved. false = could not save (file damaged or disk problem); the list is unchanged. */
    @Synchronized
    private fun put(ctx: Context, host: String, user: String, pass: String, from: Entry? = null): Boolean {
        load(ctx)
        if (readFailed) return false
        val before = ArrayList(list)
        val prev = list.firstOrNull { it.host == host && it.user == user } ?: from // 0.0.391: extras come from the login that is replaced
        list.removeAll { it.host == host && it.user == user }
        val ne = Entry(host, user, Vault.encrypt(pass))
        try { PwMore.onPut(ne, prev, pass) } catch (x: Exception) { }
        list.add(ne)
        return commit(ctx, before)
    }

    @Synchronized
    private fun remove(ctx: Context, e: Entry): Boolean {
        load(ctx)
        if (readFailed) return false
        val before = ArrayList(list)
        list.remove(e)
        return commit(ctx, before)
    }

    /** 0.0.387: for the encrypted restore. Adds a login only when it is missing (or the saved one cannot be read). Never overwrites a readable one. */
    @Synchronized
    fun addIfMissing(ctx: Context, host: String, user: String, pass: String, meta: JSONObject? = null): Boolean {
        load(ctx)
        if (readFailed || host.isBlank() || pass.isEmpty()) return false
        val old = list.firstOrNull { it.host == host && it.user == user }
        if (old != null && Vault.decrypt(old.enc) != null) return false
        val before = ArrayList(list)
        if (old != null) list.remove(old)
        val ne = Entry(host, user.take(200), Vault.encrypt(pass.take(500)))
        try { if (meta != null) PwMore.applyBackup(ne, meta) } catch (x: Exception) { } // 0.0.391
        list.add(ne)
        return commit(ctx, before)
    }

    // ---- 0.0.389 (PW3): small hooks for PwScreen (nothing here changes the old screens)
    internal fun forgetAuth() { authAt = 0L }
    @Synchronized internal fun byAge(ctx: Context): List<Entry> { load(ctx); return list.reversed() } // newest saved first
    internal fun addEntry(act: FragmentActivity, done: () -> Unit) = editDialog(act, null, done)
    internal fun editEntry(act: FragmentActivity, e: Entry, done: () -> Unit) = guard(act, "Edit login") { editDialog(act, e, done) }
    internal fun copyPlain(act: Activity, label: String, text: String) = copy(act, label, text, false)
    internal fun copySecret(act: Activity, label: String, text: String) = copy(act, label, text, true) // sensitive flag + cleared after 60 s

    // ---- 0.0.391 (PW5): notes / favourite / last used. Same Entry object is changed; a failed save puts the old value back.
    @Synchronized internal fun byUse(ctx: Context): List<Entry> { load(ctx); return list.sortedWith(compareByDescending<Entry> { it.lastUsed }.thenBy { it.host }) }
    @Synchronized internal fun favFirst(ctx: Context): List<Entry> { load(ctx); return list.sortedWith(compareByDescending<Entry> { it.fav }.thenBy { it.host }) }

    /** [plain] blank = remove the note. true = saved. */
    @Synchronized
    internal fun setNotes(ctx: Context, e: Entry, plain: String): Boolean {
        load(ctx)
        if (readFailed) return false
        val old = e.notes
        e.notes = if (plain.isBlank()) "" else Vault.encrypt(plain)
        if (save(ctx)) return true
        e.notes = old
        return false
    }

    /** [plain] blank = remove the 2FA secret. true = saved. */
    @Synchronized
    internal fun setTotp(ctx: Context, e: Entry, plain: String): Boolean {
        load(ctx)
        if (readFailed) return false
        val old = e.totp
        e.totp = if (plain.isBlank()) "" else Vault.encrypt(plain)
        if (save(ctx)) return true
        e.totp = old
        return false
    }

    @Synchronized
    internal fun setFav(ctx: Context, e: Entry, v: Boolean): Boolean {
        load(ctx)
        if (readFailed) return false
        val old = e.fav
        e.fav = v
        if (save(ctx)) return true
        e.fav = old
        return false
    }

    /** Fill happened: remember the time. Written at most once an hour per login; any problem = ignored (Fill itself never depends on this). */
    internal fun touch(ctx: Context, e: Entry) {
        try {
            val now = System.currentTimeMillis()
            synchronized(this) {
                if (readFailed || now - e.lastUsed < 3_600_000L) return
                val old = e.lastUsed
                e.lastUsed = now
                if (!save(ctx)) e.lastUsed = old
            }
        } catch (x: Exception) { }
    }

    @Synchronized
    private fun restoreEntry(ctx: Context, e: Entry) {
        load(ctx)
        if (readFailed || list.any { it.host == e.host && it.user == e.user }) return
        val before = ArrayList(list)
        list.add(e)
        if (!commit(ctx, before)) Util.toast(ctx, failMsg())
    }

    /** Screen lock + confirm, then delete with an Undo bar (the old list keeps its own delete). */
    internal fun deleteEntry(act: FragmentActivity, e: Entry, undoHost: View?, changed: () -> Unit) {
        guard(act, "Delete login") {
            MaterialAlertDialogBuilder(act).setMessage("Delete the saved login for ${e.host}?")
                .setPositiveButton("Delete") { _, _ ->
                    if (remove(act, e)) {
                        changed()
                        BmUndo.show(undoHost ?: act.findViewById<View>(android.R.id.content), "Login deleted") { restoreEntry(act, e); changed() }
                    } else Util.toast(act, failMsg())
                }
                .setNegativeButton("Cancel", null).show()
        }
    }

    fun neverHosts(ctx: Context): Set<String> = HashSet(prefs(ctx).getStringSet("never", emptySet()) ?: emptySet())
    fun removeNever(ctx: Context, host: String) {
        val s = neverHosts(ctx).toMutableSet()
        s.remove(host)
        prefs(ctx).edit().putStringSet("never", s).apply()
    }

    private fun failMsg() = if (readFailed) "The password file could not be read. Nothing was saved. Open Passwords to fix it." else "Could not save. Nothing was changed."

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("mgb_pw", Context.MODE_PRIVATE)
    private fun never(ctx: Context, host: String) = prefs(ctx).getStringSet("never", emptySet())?.contains(host) == true
    private fun setNever(ctx: Context, host: String) {
        val s = HashSet(prefs(ctx).getStringSet("never", emptySet()) ?: emptySet())
        s.add(host)
        prefs(ctx).edit().putStringSet("never", s).apply()
    }

    // ---- page side

    const val HOOK_JS = """(function(){
if(window.__mgbpw)return;window.__mgbpw=1;
function grab(root){
var p=root.querySelector('input[type=password]');
if(!p||!p.value)return;
var u='',ins=root.querySelectorAll('input');
for(var i=0;i<ins.length;i++){
if(ins[i]===p)break;
var t=(ins[i].type||'text').toLowerCase();
if((t==='text'||t==='email'||t==='tel')&&ins[i].value)u=ins[i].value;
}
try{MGBPW.onSubmit(u,p.value);}catch(e){}
}
document.addEventListener('submit',function(e){grab(e.target);},true);
document.addEventListener('click',function(e){
var b=e.target&&e.target.closest?e.target.closest('button,input[type=submit],input[type=button],[role=button]'):null;
if(!b)return;
grab(b.form||b.closest('form')||document);
},true);
if(document.querySelector('input[type=password]')){try{MGBPW.onField();}catch(e){}}
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
document.addEventListener('focusin',function(e){var t=e.target;
if(t&&t.tagName==='INPUT'&&/^(text|email|tel|password)$/i.test(t.type||'text')&&document.querySelector('input[type=password]')){try{MGBPW.onFocus();}catch(x){}}
if(t&&t.tagName==='INPUT'&&(t.type||'').toLowerCase()==='password'&&isNew(t)){try{MGBPW.onNewPw();}catch(x){}}
},true);
})();"""

    private const val FILL_JS = """function(u,p){
function set(el,v){var s=Object.getOwnPropertyDescriptor(HTMLInputElement.prototype,'value').set;s.call(el,v);
el.dispatchEvent(new Event('input',{bubbles:true}));el.dispatchEvent(new Event('change',{bubbles:true}));}
var all=[].slice.call(document.querySelectorAll('input'));
var pw=null;
for(var i=0;i<all.length;i++){if(all[i].type==='password'&&all[i].offsetParent!==null){pw=all[i];break;}}
if(!pw)return 'nofield';
var us=null;
for(var j=all.indexOf(pw)-1;j>=0;j--){
var t=(all[j].type||'text').toLowerCase();
if((t==='text'||t==='email'||t==='tel')&&all[j].offsetParent!==null){us=all[j];break;}
}
if(us&&u)set(us,u);
set(pw,p);
return 'ok';
}"""

    /** S45: put a generated password into every visible password box (new password + confirm). */
    private const val SUGGEST_JS = """function(p){
function set(el,v){var s=Object.getOwnPropertyDescriptor(HTMLInputElement.prototype,'value').set;s.call(el,v);
el.dispatchEvent(new Event('input',{bubbles:true}));el.dispatchEvent(new Event('change',{bubbles:true}));}
var all=document.querySelectorAll('input[type=password]'),n=0;
for(var i=0;i<all.length;i++){if(all[i].offsetParent!==null){set(all[i],p);n++;}}
return 'n'+n;
}"""

    /** Object exposed to normal tab pages as window.MGBPW (added in TabManager.createWebView, not for private tabs). */
    class Bridge(private val tm: TabManager) {
        @JavascriptInterface
        fun onSubmit(user: String, pass: String) {
            main.post {
                val t = tm.active ?: return@post
                if (t.priv || t.isExt || !t.url.startsWith("http")) return@post
                val act = tm.host() as? FragmentActivity ?: return@post
                val host = Util.host(t.url)
                val u = if (user.isBlank()) (try { PwSmart.takeUser(t.id.toString(), host) } catch (e: Exception) { "" }) else user.take(200) // 0.0.388: two-step login
                offerSave(act, host, u, pass.take(500))
            }
        }

        /** 0.0.388 (PW2): step 1 of a two-step login: remember the user name in memory only (2 minutes, this tab). */
        @JavascriptInterface
        fun onUser(user: String) {
            main.post {
                try {
                    val t = tm.active ?: return@post
                    if (t.priv || t.isExt || !t.url.startsWith("http")) return@post
                    PwSmart.noteUser(t.id.toString(), Util.host(t.url), user.take(200))
                } catch (e: Exception) { Log.e(TAG, "onUser failed", e) }
            }
        }

        /** 0.0.388 (PW2): change-password form: [pass] is the NEW password; the account = user name on page / step-1 name / the only saved login. */
        @JavascriptInterface
        fun onChange(user: String, pass: String) {
            main.post {
                val t = tm.active ?: return@post
                if (t.priv || t.isExt || !t.url.startsWith("http")) return@post
                val act = tm.host() as? FragmentActivity ?: return@post
                val host = Util.host(t.url)
                val u = try { PwSmart.resolveUser(act, t.id.toString(), host, user.take(200)) } catch (e: Exception) { user.take(200) }
                offerSave(act, host, u, pass.take(500))
            }
        }

        @JavascriptInterface
        fun onField() { hint(tm) }

        @JavascriptInterface
        fun onFocus() { hint(tm) }

        @JavascriptInterface
        fun onNewPw() { suggest(tm) } // S45: sign-up form
    }

    private var lastSuggest = ""

    /** S45: on a sign-up password box: bar "Suggest strong password" with Use (fills new password + confirm; the normal save prompt follows on submit). */
    private fun suggest(tm: TabManager) {
        main.post {
            val t = tm.active ?: return@post
            if (t.priv || t.isExt || !t.url.startsWith("http")) return@post
            val act = tm.host() as? FragmentActivity ?: return@post
            val host = Util.host(t.url)
            if (never(act, host)) return@post
            val key = t.id.toString() + "|" + t.url
            if (lastSuggest == key) return@post
            lastSuggest = key
            try {
                val sb = Snackbar.make(act.findViewById<View>(android.R.id.content), "Suggest a strong password?", 10000)
                AppearancePrefs.snackAnchor(act)?.let { sb.setAnchorView(it) } // A5: none in top mode
                sb.setAction("Use") {
                    val pw = generate(16)
                    val js = "(" + SUGGEST_JS + ")(" + JSONObject.quote(pw) + ")"
                    t.web.evaluateJavascript(js) { r ->
                        if (r != null && r.contains("n0")) Util.toast(act, "No visible password box")
                        else Util.toast(act, "Strong password filled. You will be asked to save it.")
                    }
                }
                sb.show()
            } catch (e: Exception) {
                Log.e(TAG, "suggest failed", e)
            }
        }
    }

    /** S40: a saved login for this site: a small bar above the address bar with a Fill button (once per page). */
    private fun hint(tm: TabManager) {
        main.post {
            val t = tm.active ?: return@post
            val act = tm.host() as? FragmentActivity ?: return@post
            if ((t.priv && !PwScreen.privFill(act)) || t.isExt || !t.url.startsWith("http")) return@post // 0.0.389: fill (never save) in private tabs only when the switch is ON
            val host = Util.host(t.url)
            val es = try { PwSmart.forHostSmart(act, host) } catch (e: Exception) { forHost(act, host) } // 0.0.388: same site, other name; any error = exact host
            if (es.isEmpty()) return@post
            val key = t.id.toString() + "|" + t.url
            if (lastHint == key) return@post
            lastHint = key
            val who = if (es.size == 1) es[0].user.ifBlank { host } else "${es.size} logins"
            if (try { PwScreen.chip(act, host, who) { fillWith(act, t, host, es) } } catch (e: Exception) { false }) return@post // 0.0.389: chip first, Snackbar = fallback
            try {
                val sb = Snackbar.make(act.findViewById<View>(android.R.id.content), "Fill login: $who", 8000)
                AppearancePrefs.snackAnchor(act)?.let { sb.setAnchorView(it) } // A5: none in top mode
                sb.setAction("Fill") { fillWith(act, t, host, es) }
                sb.show()
            } catch (e: Exception) {
                Log.e(TAG, "hint failed", e)
            }
        }
    }

    // ---- save prompt

    private fun offerSave(act: FragmentActivity, host: String, user: String, pass: String) {
        if (pass.isEmpty() || never(act, host)) return
        val key = "$host|$user|${pass.hashCode()}"
        val now = System.currentTimeMillis()
        if (key == lastKey && now - lastAt < 5000) return // submit + click both fire
        lastKey = key
        lastAt = now
        val old = forHost(act, host).firstOrNull { it.user == user }
        if (old != null && Vault.decrypt(old.enc) == pass) return // nothing new
        val who = if (user.isBlank()) "(no user name)" else user
        val sheet = try {
            PwScreen.saveSheet(act, host, user, pass, old != null, { u -> if (put(act, host, u, pass)) Util.toast(act, "Password saved") else Util.toast(act, failMsg()) }, { setNever(act, host) })
        } catch (e: Exception) { Log.e(TAG, "save sheet failed", e); false } // 0.0.389: bottom sheet first, old dialog = fallback
        if (sheet) return
        MaterialAlertDialogBuilder(act)
            .setTitle(if (old != null) "Update password for $host?" else "Save password for $host?")
            .setMessage(who)
            .setPositiveButton(if (old != null) "Update" else "Save") { _, _ ->
                if (put(act, host, user, pass)) Util.toast(act, "Password saved") else Util.toast(act, failMsg()) // 0.0.387
            }
            .setNegativeButton("Not now", null)
            .setNeutralButton("Never here") { _, _ -> setNever(act, host) }
            .show()
    }

    // ---- fill

    /** Menu: Fill password for the page in the active tab. */
    fun fill(act: FragmentActivity, tabs: TabManager) {
        val t = tabs.active ?: return
        if (t.priv && !PwScreen.privFill(act)) { Util.toast(act, "Not available in a private tab"); return }
        val host = Util.host(t.url)
        val es = try { PwSmart.forHostSmart(act, host) } catch (e: Exception) { forHost(act, host) } // 0.0.388
        if (es.isEmpty()) { Util.toast(act, "No saved login for $host"); return }
        fillWith(act, t, host, es)
    }

    private fun fillWith(act: FragmentActivity, t: TabManager.Tab, host: String, es: List<Entry>) {
        val run = { e: Entry ->
            Vault.unlock(act, "Fill password for $host") {
                val pass = Vault.decrypt(e.enc)
                if (pass == null) Util.toast(act, "Cannot read this password")
                else {
                    touch(act, e) // 0.0.391 (PW5): last used (never throws)
                    val js = "(" + FILL_JS + ")(" + JSONObject.quote(e.user) + "," + JSONObject.quote(pass) + ")"
                    t.web.evaluateJavascript(js) { r ->
                        if (r != null && r.contains("nofield")) Util.toast(act, "No password field on this page")
                    }
                }
            }
        }
        if (es.size == 1) run(es[0])
        else MaterialAlertDialogBuilder(act)
            .setTitle("Fill password")
            .setItems(es.map { it.user.ifBlank { "(no user name)" } }.toTypedArray()) { _, i -> run(es[i]) }
            .show()
    }

    // ---- import (Chrome / Edge / Firefox / Bitwarden CSV, pasted)

    private fun parseCsv(text: String): List<List<String>> {
        val rows = ArrayList<List<String>>()
        var row = ArrayList<String>()
        val f = StringBuilder()
        var q = false
        var i = 0
        while (i < text.length) {
            val ch = text[i]
            if (q) {
                if (ch == '"') { if (i + 1 < text.length && text[i + 1] == '"') { f.append('"'); i++ } else q = false }
                else f.append(ch)
            } else when (ch) {
                '"' -> q = true
                ',' -> { row.add(f.toString()); f.setLength(0) }
                '\n' -> { row.add(f.toString()); f.setLength(0); rows.add(row); row = ArrayList() }
                '\r' -> { }
                else -> f.append(ch)
            }
            i++
        }
        if (f.isNotEmpty() || row.isNotEmpty()) { row.add(f.toString()); rows.add(row) }
        return rows
    }

    /** Returns how many logins were added / updated. */
    @Synchronized
    fun importCsv(ctx: Context, text: String): Int {
        val rows = parseCsv(text.trim().removePrefix("\uFEFF"))
        if (rows.size < 2) return 0
        val head = rows[0].map { it.trim().lowercase() }
        val iu = head.indexOfFirst { it == "url" || it == "login_uri" || it == "origin" }
        val iw = head.indexOfFirst { it == "username" || it == "login_username" }
        val ip = head.indexOfFirst { it == "password" || it == "login_password" }
        if (iu < 0 || ip < 0) return -1
        var n = 0
        load(ctx)
        if (readFailed) { Util.toast(ctx, failMsg()); return 0 } // 0.0.387
        val before = ArrayList(list)
        for (r in rows.drop(1)) {
            val url = r.getOrNull(iu)?.trim().orEmpty()
            val pass = r.getOrNull(ip).orEmpty()
            if (pass.isEmpty() || !url.startsWith("http")) continue
            val user = if (iw >= 0) r.getOrNull(iw).orEmpty().trim() else ""
            val host = Util.host(url)
            val prev = list.firstOrNull { it.host == host && it.user == user } // 0.0.391
            list.removeAll { it.host == host && it.user == user }
            val ne = Entry(host, user.take(200), Vault.encrypt(pass.take(500)))
            try { PwMore.onPut(ne, prev, pass.take(500)) } catch (x: Exception) { }
            list.add(ne)
            n++
        }
        if (!commit(ctx, before)) { Util.toast(ctx, failMsg()); return 0 } // 0.0.387
        return n
    }

    fun importDialog(act: FragmentActivity) {
        val et = android.widget.EditText(act).apply {
            hint = "Paste the CSV file text here"
            minLines = 6
            gravity = android.view.Gravity.TOP
            setPadding(Util.dp(act, 20), Util.dp(act, 12), Util.dp(act, 20), Util.dp(act, 12))
        }
        MaterialAlertDialogBuilder(act)
            .setTitle("Import passwords")
            .setMessage("Export a CSV from Chrome / Firefox / Bitwarden, open it, copy all, paste here. Needs a url and a password column. Delete the CSV afterwards: it is plain text.")
            .setView(et)
            .setPositiveButton("Import") { _, _ ->
                val n = importCsv(act, et.text.toString())
                Util.toast(act, if (n < 0) "No url / password columns found" else "$n logins imported")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ---- saved list (S40: search, Add, Edit, Generate, file import / export)

    // 0.0.321: Brave-style protection. Opening the list, showing, copying, editing and exporting always ask for fingerprint / screen lock
    // (own 30 s window, NOT the Vault one); no screen lock on the phone = no access (never "open without asking").
    private var authAt = 0L
    private const val GRACE_MS = 30_000L
    private const val CLIP_MS = 60_000L
    private const val SHOW_MS = 30_000L

    internal fun guard(act: FragmentActivity, why: String, ok: () -> Unit) { // 0.0.387: internal (PwBackup uses it)
        if (System.currentTimeMillis() - authAt < GRACE_MS) { ok(); return }
        val auth = if (Build.VERSION.SDK_INT >= 30) BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL
            else BiometricManager.Authenticators.BIOMETRIC_WEAK
        val can = try { BiometricManager.from(act).canAuthenticate(auth) } catch (e: Exception) { -99 }
        if (can != BiometricManager.BIOMETRIC_SUCCESS) {
            Util.toast(act, "Set a screen lock or fingerprint first to open saved passwords")
            return
        }
        try {
            val prompt = BiometricPrompt(act, ContextCompat.getMainExecutor(act), object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(r: BiometricPrompt.AuthenticationResult) { authAt = System.currentTimeMillis(); ok() }
            })
            val b = BiometricPrompt.PromptInfo.Builder().setTitle(why).setSubtitle("Saved passwords are protected").setAllowedAuthenticators(auth)
            if (Build.VERSION.SDK_INT < 30) b.setNegativeButtonText("Cancel")
            prompt.authenticate(b.build())
        } catch (e: Exception) {
            Util.toast(act, "Cannot ask for unlock")
        }
    }

    fun screen(act: FragmentActivity) = guard(act, "Unlock passwords") { if (!problem(act) && !(try { PwScreen.open(act) } catch (e: Exception) { Log.e(TAG, "new screen failed", e); false })) screenOpen(act) } // 0.0.389: new screen first, old list = fallback

    /** 0.0.387: true = a problem dialog was shown and the list was NOT opened (file unreadable / key lost). */
    private fun problem(act: FragmentActivity): Boolean {
        try {
            notice?.let { Util.toast(act, it); notice = null }
            load(act)
            if (readFailed) {
                MaterialAlertDialogBuilder(act)
                    .setTitle("Password file could not be read")
                    .setMessage("Nothing was deleted. A copy of the damaged file was kept" + (failCopy?.let { " ($it, in the app files)" } ?: "") +
                        ". Restore your backup file, or start with an empty list (the copy stays).")
                    .setPositiveButton("Restore backup") { _, _ -> readFailed = false; PwBackup.restore(act) }
                    .setNeutralButton("Start empty") { _, _ ->
                        MaterialAlertDialogBuilder(act).setMessage("Start with an empty password list? The damaged file copy is kept.")
                            .setPositiveButton("Start empty") { _, _ -> readFailed = false }
                            .setNegativeButton("Cancel", null).show()
                    }
                    .setNegativeButton("Not now", null).show()
                return true
            }
            val es = all(act).take(10)
            if (es.isNotEmpty() && es.all { Vault.decrypt(it.enc) == null }) {
                MaterialAlertDialogBuilder(act)
                    .setTitle("This phone cannot open the saved passwords")
                    .setMessage("The lock key of this phone is gone (new phone, reinstall or cleared security data). Restore the passwords from your encrypted backup file.")
                    .setPositiveButton("Restore backup") { _, _ -> PwBackup.restore(act) }
                    .setNegativeButton("Close", null).show()
                return true
            }
        } catch (e: Exception) { Log.e(TAG, "problem check failed", e) }
        return false
    }

    /** 0.0.382: delete ALL saved logins (Delete browsing data). Asks the screen lock first; the caller already asked the user. */
    fun wipeAll(act: FragmentActivity, done: () -> Unit) = guard(act, "Unlock to delete passwords") {
        try {
            var ok = false
            synchronized(this) {
                load(act)
                val before = ArrayList(list)
                list.clear()
                ok = commit(act, before)
            }
            if (ok) done() else Util.toast(act, "Could not delete passwords") // 0.0.387: no false "deleted"
        } catch (e: Exception) { Util.toast(act, "Could not delete passwords") }
    }

    private fun screenOpen(act: FragmentActivity) {
        fun dp(v: Int) = Util.dp(act, v)
        val cText = act.getColor(R.color.mg_text)
        val cDim = act.getColor(R.color.mg_text_dim)
        val rows = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        val search = EditText(act).apply {
            hint = "Search saved logins"
            setSingleLine()
            inputType = InputType.TYPE_CLASS_TEXT
        }
        val sv = ScrollView(act).apply { addView(rows, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)) }
        val col = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(search)
            addView(sv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(340)))
        }
        val ripple = act.obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackground)).let {
            val d = it.getDrawable(0); it.recycle(); d
        }
        val dlg = MaterialAlertDialogBuilder(act)
            .setTitle("Passwords")
            .setView(col)
            .setPositiveButton("Add", null)
            .setNeutralButton("More", null)
            .setNegativeButton("Close", null)
            .create()
        fun render() {
            rows.removeAllViews()
            val q = search.text.toString().trim().lowercase()
            val es = all(act).filter { q.isEmpty() || it.host.lowercase().contains(q) || it.user.lowercase().contains(q) }
            dlg.setTitle("Passwords (" + all(act).size + ")")
            if (es.isEmpty()) {
                rows.addView(TextView(act).apply {
                    text = if (q.isEmpty()) "No saved logins yet.\nLog in to a site and tap Save, or use Add / More > Import." else "Nothing found."
                    setTextColor(cDim)
                    textSize = 14f
                    setPadding(dp(4), dp(24), dp(4), dp(24))
                })
                return
            }
            for (e in es) {
                val r = LinearLayout(act).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(8), dp(10), dp(8), dp(10))
                    background = ripple?.constantState?.newDrawable()
                    isClickable = true
                    setOnClickListener { entryMenu(act, e, { dlg.dismiss() }) { render() } }
                }
                r.addView(TextView(act).apply { text = e.host; textSize = 16f; setTextColor(cText); maxLines = 1 })
                r.addView(TextView(act).apply { text = e.user.ifBlank { "(no user name)" }; textSize = 13f; setTextColor(cDim); maxLines = 1 })
                rows.addView(r)
            }
        }
        search.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) { render() }
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) { }
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) { }
        })
        dlg.window?.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE) // 0.0.321: no screenshot / recents picture of the list
        dlg.setOnDismissListener { authAt = 0L }
        dlg.show()
        render()
        // the dialog stays open after Add / More: the list refreshes when the work is done
        dlg.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener { editDialog(act, null) { render() } }
        dlg.getButton(android.app.AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
            val items = arrayOf("Generate a password", "Import from a CSV file", "Import by paste", "Export as CSV file", "Encrypted backup file", "Restore from backup file")
            MaterialAlertDialogBuilder(act).setItems(items) { _, i ->
                when (i) {
                    0 -> generatorDialog(act)
                    1 -> importFile(act) { render() }
                    2 -> importDialog(act)
                    3 -> exportCsv(act)
                    4 -> PwBackup.export(act)
                    else -> PwBackup.restore(act) { render() }
                }
            }.show()
        }
    }

    private fun copy(act: Activity, label: String, text: String, secret: Boolean = false) {
        val cm = act.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText(label, text)
        if (secret && Build.VERSION.SDK_INT >= 33) clip.description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) } // 0.0.321: hidden in the clipboard preview
        cm.setPrimaryClip(clip)
        if (secret) {
            Handler(Looper.getMainLooper()).postDelayed({ // 0.0.321: password leaves the clipboard after 60 s
                try {
                    if (cm.primaryClipDescription?.label == label) {
                        if (Build.VERSION.SDK_INT >= 28) cm.clearPrimaryClip() else cm.setPrimaryClip(ClipData.newPlainText("", ""))
                    }
                } catch (e: Exception) { }
            }, CLIP_MS)
            Util.toast(act, "$label copied, cleared in 60 s")
        } else Util.toast(act, "$label copied")
    }

    private fun entryMenu(act: FragmentActivity, e: Entry, close: () -> Unit, changed: () -> Unit) {
        MaterialAlertDialogBuilder(act)
            .setTitle(e.host + (if (e.user.isBlank()) "" else "\n" + e.user))
            .setItems(arrayOf("Copy user name", "Copy password", "Show password", "Open site", "Edit", "Delete")) { _, i ->
                when (i) {
                    0 -> copy(act, "User name", e.user)
                    1 -> guard(act, "Copy password") {
                        val p = Vault.decrypt(e.enc)
                        if (p == null) Util.toast(act, "Cannot read this password") else copy(act, "Password", p, true)
                    }
                    2 -> guard(act, "Show password") {
                        val p = Vault.decrypt(e.enc)
                        if (p == null) { Util.toast(act, "Cannot read this password"); return@guard }
                        val dots = "\u2022\u2022\u2022\u2022\u2022\u2022\u2022\u2022\u2022\u2022" // 0.0.321: hidden first, length not shown
                        var shown = false
                        val d = MaterialAlertDialogBuilder(act).setTitle(e.host).setMessage(e.user + "\n" + dots)
                            .setPositiveButton("Close", null).setNeutralButton("Show", null).create()
                        d.window?.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
                        val hd = Handler(Looper.getMainLooper())
                        val auto = Runnable { if (d.isShowing) d.dismiss() } // closes itself after 30 s
                        d.setOnDismissListener { hd.removeCallbacks(auto) }
                        d.show()
                        hd.postDelayed(auto, SHOW_MS)
                        d.getButton(android.app.AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                            shown = !shown
                            d.setMessage(e.user + "\n" + (if (shown) p else dots))
                            d.getButton(android.app.AlertDialog.BUTTON_NEUTRAL).text = if (shown) "Hide" else "Show"
                        }
                    }
                    3 -> { close(); (act as? MainActivity)?.openSite("https://" + e.host) }
                    4 -> guard(act, "Edit login") { editDialog(act, e, changed) }
                    else -> MaterialAlertDialogBuilder(act)
                        .setMessage("Delete the saved login for ${e.host}?")
                        .setPositiveButton("Delete") { _, _ -> if (remove(act, e)) Util.toast(act, "Deleted") else Util.toast(act, failMsg()); changed() }
                        .setNegativeButton("Cancel", null).show()
                }
            }
            .show()
    }

    /** Add (e == null) or edit one login. Empty password while editing = keep the old one. */
    private fun editDialog(act: FragmentActivity, e: Entry?, done: () -> Unit) {
        fun dp(v: Int) = Util.dp(act, v)
        val host = EditText(act).apply { hint = "Website (example.com)"; setSingleLine(); setText(e?.host ?: ""); if (e != null) isEnabled = false }
        val user = EditText(act).apply { hint = "User name or email"; setSingleLine(); setText(e?.user ?: "") }
        val pass = EditText(act).apply {
            hint = if (e == null) "Password" else "New password (empty = keep)"
            setSingleLine()
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        }
        val col = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(host); addView(user); addView(pass)
        }
        val dlg = MaterialAlertDialogBuilder(act)
            .setTitle(if (e == null) "Add login" else "Edit login")
            .setView(col)
            .setPositiveButton("Save", null)
            .setNeutralButton("Generate", null)
            .setNegativeButton("Cancel", null)
            .create()
        dlg.show()
        dlg.getButton(android.app.AlertDialog.BUTTON_NEUTRAL).setOnClickListener { pass.setText(generate(16)) }
        dlg.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val h = normHost(host.text.toString())
            val u = user.text.toString().trim().take(200)
            var p = pass.text.toString().take(500)
            if (h.isBlank()) { Util.toast(act, "Enter the website"); return@setOnClickListener }
            if (e == null && p.isEmpty()) { Util.toast(act, "Enter a password or tap Generate"); return@setOnClickListener }
            if (e != null && p.isEmpty()) {
                p = Vault.decrypt(e.enc) ?: run { Util.toast(act, "Cannot read the old password"); return@setOnClickListener }
            }
            val keepOld = e != null && e.host == h && e.user == u
            if (!put(act, h, u, p, e)) { Util.toast(act, failMsg()); return@setOnClickListener } // 0.0.387: new first (0.0.391: + extras of the edited login), so a failed save never loses the old login; the dialog stays open
            if (e != null && !keepOld) remove(act, e)
            Util.toast(act, "Saved")
            dlg.dismiss()
            done()
        }
    }

    private fun normHost(s: String): String {
        val t = s.trim()
        if (t.startsWith("http://") || t.startsWith("https://")) return Util.host(t)
        return t.substringBefore('/').lowercase()
    }

    // ---- generator

    fun generate(len: Int): String {
        val sets = listOf("abcdefghijkmnopqrstuvwxyz", "ABCDEFGHJKLMNPQRSTUVWXYZ", "23456789", "!@#\$%^&*-_=+?")
        val r = SecureRandom()
        val every = sets.joinToString("")
        val out = ArrayList<Char>()
        for (x in sets) out.add(x[r.nextInt(x.length)])
        while (out.size < len) out.add(every[r.nextInt(every.length)])
        out.shuffle(r)
        return out.joinToString("")
    }

    fun generatorDialog(act: FragmentActivity) {
        if (try { PwScreen.generatorSheet(act) } catch (e: Exception) { false }) return // 0.0.389: new sheet first, old dialog below = fallback
        fun dp(v: Int) = Util.dp(act, v)
        var cur = generate(16)
        val tv = TextView(act).apply {
            text = cur
            textSize = 20f
            typeface = android.graphics.Typeface.MONOSPACE
            setTextColor(act.getColor(R.color.mg_text))
            setTextIsSelectable(true)
            setPadding(0, dp(8), 0, dp(8))
        }
        val lenTv = TextView(act).apply { text = "Length 16"; textSize = 13f; setTextColor(act.getColor(R.color.mg_text_dim)) }
        val bar = SeekBar(act).apply { max = 24; progress = 8 }
        bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, v: Int, fromUser: Boolean) {
                lenTv.text = "Length " + (8 + v)
                if (fromUser) { cur = generate(8 + v); tv.text = cur }
            }
            override fun onStartTrackingTouch(s: SeekBar?) { }
            override fun onStopTrackingTouch(s: SeekBar?) { }
        })
        val col = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), 0)
            addView(tv); addView(lenTv); addView(bar)
        }
        val dlg = MaterialAlertDialogBuilder(act)
            .setTitle("Password generator")
            .setView(col)
            .setPositiveButton("Copy", null)
            .setNeutralButton("New", null)
            .setNegativeButton("Close", null)
            .create()
        dlg.show()
        dlg.getButton(android.app.AlertDialog.BUTTON_NEUTRAL).setOnClickListener { cur = generate(8 + bar.progress); tv.text = cur }
        dlg.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener { copy(act, "Password", cur) }
    }

    // ---- file import / export

    fun importFile(act: FragmentActivity, done: () -> Unit = { }) {
        BrowserExtras.loadBackup { text ->
            if (text == null) Util.toast(act, "Could not read the file")
            else {
                val n = importCsv(act, text)
                Util.toast(act, if (n < 0) "No url / password columns found" else "$n logins imported. Delete the CSV file now: it is plain text.")
                done()
            }
        }
    }

    fun importChoice(act: FragmentActivity) {
        MaterialAlertDialogBuilder(act)
            .setTitle("Import passwords")
            .setItems(arrayOf("From a CSV file (Chrome, Firefox, Bitwarden)", "Paste CSV text")) { _, i ->
                if (i == 0) importFile(act) else importDialog(act)
            }
            .show()
    }

    private fun csv(s: String) = "\"" + s.replace("\"", "\"\"") + "\""

    fun exportCsv(act: FragmentActivity) {
        val es = all(act)
        if (es.isEmpty()) { Util.toast(act, "No saved passwords yet"); return }
        MaterialAlertDialogBuilder(act)
            .setTitle("Export passwords")
            .setMessage("The CSV file is plain text: anyone who gets it can read every password. Save it only where you trust, and delete it after use.")
            .setPositiveButton("Export") { _, _ ->
                guard(act, "Export passwords") {
                    val sb = StringBuilder("name,url,username,password\n")
                    for (e in es) {
                        val p = Vault.decrypt(e.enc) ?: continue
                        sb.append(csv(e.host)).append(',').append(csv("https://" + e.host)).append(',').append(csv(e.user)).append(',').append(csv(p)).append('\n')
                    }
                    BrowserExtras.saveCsv("mgb-passwords.csv", sb.toString())
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
