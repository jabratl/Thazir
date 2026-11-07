package com.jabra.mgb

import android.accounts.Account
import android.accounts.AccountManager
import android.app.Activity
import android.content.Intent
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder
import java.util.WeakHashMap
import java.util.concurrent.TimeUnit

/**
 * 0.0.303: Google sign-in + Google Drive backup for the Passport.
 *  - Sign-in = Google Play services Authorization API. The Android OAuth client (package com.jabra.mgb + signing SHA-1)
 *    is matched by Google itself, no client id or secret is needed in the app.
 *  - Scopes: drive.appdata (a private app folder in the user's Drive, other Drive files are not visible), userinfo.email, userinfo.profile.
 *  - One file in that folder: thazir-passport.json = Backup.passportJson (the parts chosen in Passport).
 *  - The access token is asked again for every action (Google returns it without a prompt after the first consent), nothing is stored.
 * Not live sync: manual Save / Restore, plus an upload with the daily auto backup when signed in.
 */
object CloudSync {
    private const val DRIVE = "https://www.googleapis.com/auth/drive.appdata"
    private const val EMAIL = "https://www.googleapis.com/auth/userinfo.email"
    private const val PROFILE = "https://www.googleapis.com/auth/userinfo.profile"
    private const val FILE = "thazir-passport.json"
    const val K_ON = "orb_g"
    const val K_AT = "orb_g_at"
    const val K_ACC = "orb_g_acc" // 0.0.316: the Google account picked at sign-in; every later upload / restore uses it

    private val launchers = WeakHashMap<Activity, ActivityResultLauncher<IntentSenderRequest>>()
    private val pickers = WeakHashMap<Activity, ActivityResultLauncher<Intent>>()
    private var pending: ((AuthorizationResult?) -> Unit)? = null
    private var pendingPick: ((Account?) -> Unit)? = null
    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(40, TimeUnit.SECONDS).build()
    }

    private fun sp(c: Context) = c.getSharedPreferences("mgb_ui", Context.MODE_PRIVATE)
    fun signedIn(c: Context): Boolean = sp(c).getBoolean(K_ON, false)
    fun lastSync(c: Context): Long = sp(c).getLong(K_AT, 0L)
    /** The account chosen at sign-in (null = none yet). */
    private fun account(c: Context): Account? = sp(c).getString(K_ACC, null)?.takeIf { it.isNotBlank() }?.let { Account(it, "com.google") }
    fun photoFile(c: Context): File = File(c.applicationContext.filesDir, "orb_photo.png")

    /** Call in Activity.onCreate next to BrowserExtras.init (launchers must be registered before STARTED). */
    fun init(a: ComponentActivity) {
        pickers[a] = a.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
            val cb = pendingPick
            pendingPick = null
            val n = r.data?.getStringExtra(AccountManager.KEY_ACCOUNT_NAME)
            cb?.invoke(if (r.resultCode == Activity.RESULT_OK && !n.isNullOrBlank()) Account(n, r.data?.getStringExtra(AccountManager.KEY_ACCOUNT_TYPE) ?: "com.google") else null)
        }
        launchers[a] = a.registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { r ->
            val cb = pending
            pending = null
            val data = r.data
            if (r.resultCode != Activity.RESULT_OK || data == null) {
                cb?.invoke(null)
            } else {
                val res = try { Identity.getAuthorizationClient(a).getAuthorizationResultFromIntent(data) } catch (e: Exception) { null }
                cb?.invoke(res)
            }
        }
    }

    /** Gets an access token for [scopes]. [interactive] false = never show a Google screen (daily auto upload). */
    private fun authorize(act: Activity, scopes: List<String>, interactive: Boolean, acc: Account?, cb: (String?, String?) -> Unit) {
        try {
            val rb = AuthorizationRequest.builder().setRequestedScopes(scopes.map { Scope(it) })
            if (acc != null) rb.setAccount(acc) // 0.0.316: always the chosen account, never "whichever Google decides"
            val req = rb.build()
            Identity.getAuthorizationClient(act).authorize(req)
                .addOnSuccessListener { res ->
                    if (res.hasResolution()) {
                        val pi = res.pendingIntent
                        val l = launchers[act]
                        if (!interactive) { cb(null, "Sign in again to upload"); return@addOnSuccessListener }
                        if (pi == null || l == null) { cb(null, "Could not open the Google sign-in screen"); return@addOnSuccessListener }
                        pending = { r ->
                            val t = r?.accessToken
                            cb(t, if (t == null) "Sign-in cancelled" else null)
                        }
                        try {
                            l.launch(IntentSenderRequest.Builder(pi.intentSender).build())
                        } catch (e: Exception) {
                            pending = null
                            cb(null, "Could not open the Google sign-in screen")
                        }
                    } else {
                        val t = res.accessToken
                        cb(t, if (t == null) "Google gave no access" else null)
                    }
                }
                .addOnFailureListener { e -> cb(null, e.message ?: "Google sign-in failed") }
        } catch (e: Exception) {
            cb(null, e.message ?: "Google sign-in failed")
        }
    }

    // ------------------------------------------------------------ sign in / out
    /** done(null) = signed in, else a short error text. Runs on the main thread. */
    fun signIn(act: Activity, done: (String?) -> Unit) {
        // 0.0.316: ask WHICH account first (accounts on this phone + "Add account"), so a second sign-in never reuses the first one silently
        pickAccount(act) { acc ->
            if (acc == null) { done("Sign-in cancelled"); return@pickAccount }
            authorize(act, listOf(DRIVE, EMAIL, PROFILE), true, acc) { token, err ->
                if (token == null) { done(err ?: "Sign-in failed"); return@authorize }
                val app = act.applicationContext
                Thread {
                    val msg = try { loadProfile(app, token); null } catch (e: Exception) { "Signed in, but the Google profile could not be read" }
                    act.runOnUiThread {
                        sp(app).edit().putBoolean(K_ON, true).putString(K_ACC, acc.name).apply()
                        done(msg)
                    }
                }.start()
            }
        }
    }

    private fun pickAccount(act: Activity, cb: (Account?) -> Unit) {
        val l = pickers[act]
        if (l == null) { cb(null); return }
        try {
            val i = AccountManager.newChooseAccountIntent(null, null, arrayOf("com.google"), null, null, null, null)
            pendingPick = cb
            l.launch(i)
        } catch (e: Exception) {
            pendingPick = null
            Util.toast(act, "Could not open the account list")
            cb(null)
        }
    }

    fun signOut(c: Context) {
        sp(c).edit().remove(K_ON).remove(K_AT).remove(K_ACC).remove(Orbit.NAME).remove(Orbit.MAIL).apply()
        try { photoFile(c).delete() } catch (_: Exception) { }
    }

    private fun auth(token: String) = "Bearer $token"

    private fun loadProfile(app: Context, token: String) {
        val req = Request.Builder().url("https://www.googleapis.com/oauth2/v3/userinfo").header("Authorization", auth(token)).build()
        http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw Exception("HTTP " + r.code)
            val j = JSONObject(r.body?.string() ?: "{}")
            val name = j.optString("name").ifBlank { j.optString("given_name") }
            sp(app).edit().putString(Orbit.NAME, name).putString(Orbit.MAIL, j.optString("email")).apply()
            val pic = j.optString("picture")
            if (pic.isNotEmpty()) {
                try {
                    http.newCall(Request.Builder().url(pic).build()).execute().use { pr ->
                        val bytes = pr.body?.bytes()
                        if (pr.isSuccessful && bytes != null) {
                            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                            if (bmp != null) photoFile(app).outputStream().use { o -> bmp.compress(Bitmap.CompressFormat.PNG, 100, o) }
                        }
                    }
                } catch (_: Exception) { /* no photo: the letter avatar stays */ }
            }
        }
    }

    // ------------------------------------------------------------ Drive
    private fun findId(token: String): String? {
        val q = URLEncoder.encode("name='$FILE'", "UTF-8")
        val url = "https://www.googleapis.com/drive/v3/files?spaces=appDataFolder&fields=files(id)&q=$q"
        http.newCall(Request.Builder().url(url).header("Authorization", auth(token)).build()).execute().use { r ->
            if (!r.isSuccessful) throw Exception("Google Drive said " + r.code)
            val a = JSONObject(r.body?.string() ?: "{}").optJSONArray("files")
            return if (a != null && a.length() > 0) a.getJSONObject(0).optString("id").ifEmpty { null } else null
        }
    }

    private fun driveUpload(token: String, json: String) {
        val id = findId(token)
        val req = if (id == null) {
            val meta = JSONObject().put("name", FILE).put("parents", JSONArray().put("appDataFolder")).toString()
            val body = MultipartBody.Builder().setType("multipart/related".toMediaType())
                .addPart(meta.toRequestBody("application/json; charset=UTF-8".toMediaType()))
                .addPart(json.toRequestBody("application/json".toMediaType()))
                .build()
            Request.Builder().url("https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart").header("Authorization", auth(token)).post(body).build()
        } else {
            Request.Builder().url("https://www.googleapis.com/upload/drive/v3/files/$id?uploadType=media").header("Authorization", auth(token))
                .patch(json.toRequestBody("application/json".toMediaType())).build()
        }
        http.newCall(req).execute().use { r -> if (!r.isSuccessful) throw Exception("Google Drive said " + r.code) }
    }

    private fun driveDownload(token: String): String {
        val id = findId(token) ?: throw Exception("No Passport on your Drive yet")
        http.newCall(Request.Builder().url("https://www.googleapis.com/drive/v3/files/$id?alt=media").header("Authorization", auth(token)).build()).execute().use { r ->
            if (!r.isSuccessful) throw Exception("Google Drive said " + r.code)
            return r.body?.string() ?: throw Exception("The Passport file is empty")
        }
    }

    /** Uploads [json] (made by Backup.passportJson on the main thread). done(null) = saved. */
    fun upload(act: Activity, json: String, interactive: Boolean, done: (String?) -> Unit) {
        authorize(act, listOf(DRIVE), interactive, account(act)) { token, err ->
            if (token == null) { done(err ?: "Not signed in"); return@authorize }
            val app = act.applicationContext
            Thread {
                val msg = try { driveUpload(token, json); null } catch (e: Exception) { e.message ?: "Upload failed" }
                act.runOnUiThread {
                    if (msg == null) sp(app).edit().putLong(K_AT, System.currentTimeMillis()).apply()
                    done(msg)
                }
            }.start()
        }
    }

    /** Downloads the Passport file and hands it to the normal Import dialog (choose what to restore). */
    fun restore(act: Activity, done: (String?) -> Unit) {
        authorize(act, listOf(DRIVE), true, account(act)) { token, err ->
            if (token == null) { done(err ?: "Not signed in"); return@authorize }
            Thread {
                var text: String? = null
                val msg = try { text = driveDownload(token); null } catch (e: Exception) { e.message ?: "Download failed" }
                act.runOnUiThread {
                    done(msg)
                    if (msg == null) Backup.importText(act, text)
                }
            }.start()
        }
    }
}
