package com.jabra.mgb

import android.app.Activity
import android.net.Uri
import android.net.http.SslCertificate
import android.net.http.SslError
import android.webkit.SslErrorHandler
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.security.MessageDigest

/**
 * CERTIFICATE WARNING (0.0.419): the browser had no onReceivedSslError, so WebView cancelled every bad certificate by itself and the
 * person only saw a "net::ERR_CERT_..." page with no explanation and no way on (home router https, NAS, a test server with a self-signed or
 * expired certificate, a phone with a wrong date).
 *
 * Now a warning dialog like a normal browser: what is wrong (expired / wrong site name / not trusted / not valid yet / wrong date), and two buttons:
 * "Go back" (safe, the first button, also back key / dismiss) and "Continue (unsafe)".
 * - Only the MAIN page can be continued. A bad certificate on an image / script / frame of a page (host differs from the tab address) is always cancelled.
 * - "Continue" is remembered ONLY in memory for this tab, for this host AND this exact certificate (SHA-256), so a different certificate asks again.
 *   Nothing is saved to disk; closing the app forgets it.
 * - Extension pages are always cancelled. One dialog at a time (a second one is cancelled). The handler is answered exactly once; any error = cancel.
 * Hook: TabManager client(tab).onReceivedSslError. Rollback: remove that override (WebView cancels again like before) and this file.
 */
object SslWarn {
    private var showing = false
    private val allowed = HashMap<Int, HashSet<String>>() // tab id -> "host|certificate hash"

    private fun hostOf(u: String?): String? = try { Uri.parse(u ?: "").host?.lowercase() } catch (_: Throwable) { null }

    private fun certKey(host: String, cert: SslCertificate?): String {
        val raw: ByteArray? = try { cert?.let { SslCertificate.saveState(it).getByteArray("x509-certificate") } } catch (_: Throwable) { null }
        val h = try {
            val d = MessageDigest.getInstance("SHA-256").digest(raw ?: (cert?.toString() ?: "").toByteArray())
            d.joinToString("") { "%02x".format(it) }
        } catch (_: Throwable) { "?" }
        return "$host|$h"
    }

    private fun reason(e: SslError): String = when (e.primaryError) {
        SslError.SSL_EXPIRED -> "The certificate of this site has expired."
        SslError.SSL_IDMISMATCH -> "The certificate is made for a different site name."
        SslError.SSL_UNTRUSTED -> "The certificate is not from a trusted authority (self-signed or unknown)."
        SslError.SSL_NOTYETVALID -> "The certificate is not valid yet. Check the date and time of the phone."
        SslError.SSL_DATE_INVALID -> "The date of the certificate is wrong. Check the date and time of the phone."
        else -> "The certificate of this site is not valid."
    }

    fun handle(act: Activity?, tabId: Int, tabUrl: String?, viewUrl: String?, isExt: Boolean, handler: SslErrorHandler, error: SslError) {
        var done = false
        fun answer(proceed: Boolean) {
            if (done) return
            done = true
            try { if (proceed) handler.proceed() else handler.cancel() } catch (_: Throwable) { }
        }
        try {
            val host = hostOf(error.url)
            if (isExt || act == null || act.isFinishing || host == null || (host != hostOf(tabUrl) && host != hostOf(viewUrl))) { answer(false); return } // not the main page
            val key = certKey(host, error.certificate)
            if (allowed[tabId]?.contains(key) == true) { answer(true); return } // the person already said "Continue" for this exact certificate
            if (showing) { answer(false); return }
            showing = true
            Diag.w("ssl", "certificate warning " + reason(error), host)
            MaterialAlertDialogBuilder(act)
                .setTitle("Connection is not private")
                .setMessage(host + "\n" + reason(error) + "\nOthers on the network might be able to see or change what you send to this site (passwords, messages, card numbers).")
                .setPositiveButton("Go back") { _, _ -> answer(false) }
                .setNegativeButton("Continue (unsafe)") { _, _ ->
                    allowed.getOrPut(tabId) { HashSet() }.add(key)
                    answer(true)
                }
                .setOnCancelListener { answer(false) }
                .setOnDismissListener { showing = false; answer(false) } // after a button answer() already ran: nothing happens
                .show()
        } catch (_: Throwable) {
            showing = false
            answer(false)
        }
    }
}
