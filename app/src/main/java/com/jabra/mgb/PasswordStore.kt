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

    class Entry(val host: String, val user: String, val enc: String)

    private val list = ArrayList<Entry>()
    private var loaded = false
    private var lastKey = ""
    private var lastAt = 0L
    private var lastHint = ""

    // ---- storage

    private fun load(ctx: Context) {
        if (loaded) return
        loaded = true
        try {
            val f = File(ctx.applicationContext.filesDir, FILE)
            if (!f.exists()) return
            val a = JSONArray(f.readText())
            for (i in 0 until a.length()) {
                val o = a.optJSONObject(i) ?: continue
                list.add(Entry(o.optString("h"), o.optString("u"), o.optString("p")))
            }
        } catch (e: Exception) { Log.e(TAG, "load failed", e) }
    }

    private fun save(ctx: Context) {
        try {
            val a = JSONArray()
            for (e in list) a.put(JSONObject().put("h", e.host).put("u", e.user).put("p", e.enc))
            File(ctx.applicationContext.filesDir, FILE).writeText(a.toString())
        } catch (e: Exception) { Log.e(TAG, "save failed", e) }
    }

    fun all(ctx: Context): List<Entry> { load(ctx); return list.sortedBy { it.host } }
    fun forHost(ctx: Context, host: String): List<Entry> { load(ctx); return list.filter { it.host == host } }

    private fun put(ctx: Context, host: String, user: String, pass: String) {
        load(ctx)
        list.removeAll { it.host == host && it.user == user }
        list.add(Entry(host, user, Vault.encrypt(pass)))
        save(ctx)
    }

    private fun remove(ctx: Context, e: Entry) { load(ctx); list.remove(e); save(ctx) }

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
                offerSave(act, Util.host(t.url), user.take(200), pass.take(500))
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
            if (t.priv || t.isExt || !t.url.startsWith("http")) return@post
            val act = tm.host() as? FragmentActivity ?: return@post
            val host = Util.host(t.url)
            val es = forHost(act, host)
            if (es.isEmpty()) return@post
            val key = t.id.toString() + "|" + t.url
            if (lastHint == key) return@post
            lastHint = key
            val who = if (es.size == 1) es[0].user.ifBlank { host } else "${es.size} logins"
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
        MaterialAlertDialogBuilder(act)
            .setTitle(if (old != null) "Update password for $host?" else "Save password for $host?")
            .setMessage(who)
            .setPositiveButton(if (old != null) "Update" else "Save") { _, _ ->
                put(act, host, user, pass)
                Util.toast(act, "Password saved")
            }
            .setNegativeButton("Not now", null)
            .setNeutralButton("Never here") { _, _ -> setNever(act, host) }
            .show()
    }

    // ---- fill

    /** Menu: Fill password for the page in the active tab. */
    fun fill(act: FragmentActivity, tabs: TabManager) {
        val t = tabs.active ?: return
        if (t.priv) { Util.toast(act, "Not available in a private tab"); return }
        val host = Util.host(t.url)
        val es = forHost(act, host)
        if (es.isEmpty()) { Util.toast(act, "No saved login for $host"); return }
        fillWith(act, t, host, es)
    }

    private fun fillWith(act: FragmentActivity, t: TabManager.Tab, host: String, es: List<Entry>) {
        val run = { e: Entry ->
            Vault.unlock(act, "Fill password for $host") {
                val pass = Vault.decrypt(e.enc)
                if (pass == null) Util.toast(act, "Cannot read this password")
                else {
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
        for (r in rows.drop(1)) {
            val url = r.getOrNull(iu)?.trim().orEmpty()
            val pass = r.getOrNull(ip).orEmpty()
            if (pass.isEmpty() || !url.startsWith("http")) continue
            val user = if (iw >= 0) r.getOrNull(iw).orEmpty().trim() else ""
            val host = Util.host(url)
            list.removeAll { it.host == host && it.user == user }
            list.add(Entry(host, user.take(200), Vault.encrypt(pass.take(500))))
            n++
        }
        save(ctx)
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

    private fun guard(act: FragmentActivity, why: String, ok: () -> Unit) {
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

    fun screen(act: FragmentActivity) = guard(act, "Unlock passwords") { screenOpen(act) }

    /** 0.0.382: delete ALL saved logins (Delete browsing data). Asks the screen lock first; the caller already asked the user. */
    fun wipeAll(act: FragmentActivity, done: () -> Unit) = guard(act, "Unlock to delete passwords") {
        try { load(act); list.clear(); save(act); done() } catch (e: Exception) { Util.toast(act, "Could not delete passwords") }
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
            val items = arrayOf("Generate a password", "Import from a CSV file", "Import by paste", "Export as CSV file")
            MaterialAlertDialogBuilder(act).setItems(items) { _, i ->
                when (i) {
                    0 -> generatorDialog(act)
                    1 -> importFile(act) { render() }
                    2 -> importDialog(act)
                    else -> exportCsv(act)
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
                        .setPositiveButton("Delete") { _, _ -> remove(act, e); Util.toast(act, "Deleted"); changed() }
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
            if (e != null) remove(act, e)
            put(act, h, u, p)
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
