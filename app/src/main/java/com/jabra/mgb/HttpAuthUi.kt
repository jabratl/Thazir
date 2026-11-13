package com.jabra.mgb

import android.app.Activity
import android.text.InputType
import android.view.ViewGroup
import android.webkit.HttpAuthHandler
import android.widget.EditText
import android.widget.LinearLayout
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * HTTP LOGIN DIALOG (0.0.418): sites, routers, NAS boxes, staging servers and some proxies answer with "401 + WWW-Authenticate" (Basic / Digest) and
 * wait for a user name and password. The browser had no onReceivedHttpAuthRequest, so WebView cancelled the request by itself and the page just
 * stayed empty / "unauthorized" with no way to sign in. Now a small dialog asks for the two values and hands them to the WebView.
 *
 * - Nothing is saved (no password storage here; private and normal tabs behave the same). The values go only to the HttpAuthHandler of that request.
 * - One dialog at a time: a second request while the dialog is open is cancelled (the page asks again after the first answer).
 * - The handler is answered exactly once (Sign in / Cancel / back / dismiss / any error = cancel), so the WebView never hangs waiting.
 * - A plain http:// page shows a warning line (the password travels unencrypted).
 * Hook: TabManager client(tab).onReceivedHttpAuthRequest. Rollback: remove that override (WebView cancels again like before) and this file.
 */
object HttpAuthUi {
    private var showing = false

    fun ask(act: Activity, host: String, realm: String, plainHttp: Boolean, handler: HttpAuthHandler) {
        if (showing || act.isFinishing) { try { handler.cancel() } catch (_: Throwable) { }; return }
        showing = true
        var done = false
        fun finish(user: String?, pw: String?) {
            if (done) return
            done = true; showing = false
            try { if (user != null) handler.proceed(user, pw ?: "") else handler.cancel() } catch (_: Throwable) { }
        }
        try {
            val pad = (act.resources.displayMetrics.density * 20).toInt()
            val box = LinearLayout(act).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(pad, pad / 2, pad, 0)
            }
            val u = EditText(act).apply {
                hint = "User name"
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                setSingleLine()
            }
            val p = EditText(act).apply {
                hint = "Password"
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                setSingleLine()
            }
            box.addView(u, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            box.addView(p, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            val msg = StringBuilder(host)
            if (realm.isNotBlank()) msg.append('\n').append(realm)
            msg.append("\nThis site asks for a user name and password.")
            if (plainHttp) msg.append("\nNot secure (http): the password is sent without encryption.")
            MaterialAlertDialogBuilder(act)
                .setTitle("Sign in")
                .setMessage(msg.toString())
                .setView(box)
                .setPositiveButton("Sign in") { _, _ -> finish(u.text.toString(), p.text.toString()) }
                .setNegativeButton("Cancel") { _, _ -> finish(null, null) }
                .setOnCancelListener { finish(null, null) }
                .setOnDismissListener { finish(null, null) } // after "Sign in" finish() already ran: this call does nothing
                .show()
        } catch (_: Throwable) {
            finish(null, null)
        }
    }
}
