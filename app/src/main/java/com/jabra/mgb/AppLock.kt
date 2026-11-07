package com.jabra.mgb

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.SystemClock
import android.view.Gravity
import android.view.KeyEvent
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * S45: app lock. Settings > Privacy > App lock (fingerprint / face / screen lock, same system prompt as passwords).
 * A full-screen Dialog (own window, so it also covers Settings / Bookmarks / other dialogs) is shown at cold start
 * and when the app comes back after the chosen delay. While the lock is ON the window has FLAG_SECURE:
 * the recent-apps picture is blank and screenshots are blocked. No screen lock on the phone = lock cannot be turned on.
 */
object AppLock {
    private const val PREFS = "mgb_lock"
    private val DELAYS = longArrayOf(0L, 60_000L, 300_000L)
    private val DELAY_LABELS = arrayOf("Immediately", "After 1 minute", "After 5 minutes")

    private var unlocked = false   // true after a successful unlock in this process
    private var leftAt = 0L
    private var dialog: Dialog? = null
    private var prompting = false

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    fun on(c: Context) = prefs(c).getBoolean("on", false)
    private fun delayIdx(c: Context) = prefs(c).getInt("delay", 1).coerceIn(0, DELAYS.size - 1)
    fun delayLabel(c: Context) = DELAY_LABELS[delayIdx(c)]

    private fun authenticators(): Int =
        if (Build.VERSION.SDK_INT >= 30) BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL
        else BiometricManager.Authenticators.BIOMETRIC_WEAK

    fun available(c: Context): Boolean = try {
        BiometricManager.from(c).canAuthenticate(authenticators()) == BiometricManager.BIOMETRIC_SUCCESS
    } catch (e: Exception) { false }

    /** 0.0.331: same system prompt for the private tabs lock (PrivateTabs.toggleLock). */
    fun ask(act: FragmentActivity, title: String, result: (Boolean) -> Unit) = authenticate(act, title, result)

    // ---- settings

    /** Turn ON / OFF. ON asks the user to prove it is them first (so a lock that cannot be opened is never set). */
    fun toggle(act: FragmentActivity, done: () -> Unit) {
        if (on(act)) {
            prefs(act).edit().putBoolean("on", false).apply()
            secure(act, false)
            dismiss()
            done()
            return
        }
        if (!available(act)) {
            Util.toast(act, if (Build.VERSION.SDK_INT >= 30) "Set a screen lock or fingerprint in phone Settings first" else "Add a fingerprint / face in phone Settings first")
            return
        }
        authenticate(act, "Turn on app lock", { ok ->
            if (ok) {
                prefs(act).edit().putBoolean("on", true).apply()
                unlocked = true
                secure(act, true)
                Util.toast(act, "App lock ON. Recent-apps picture and screenshots are hidden")
                done()
            }
        })
    }

    fun pickDelay(act: Context, done: () -> Unit) {
        MaterialAlertDialogBuilder(act).setTitle("Lock")
            .setSingleChoiceItems(DELAY_LABELS, delayIdx(act)) { d, i ->
                prefs(act).edit().putInt("delay", i).apply()
                d.dismiss()
                done()
            }.show()
    }

    // ---- lifecycle (called from MainActivity)

    fun onCreate(act: FragmentActivity) {
        if (!on(act)) return
        secure(act, true)
        if (!unlocked) show(act)
    }

    fun onStop(act: FragmentActivity) {
        if (on(act) && unlocked) leftAt = SystemClock.elapsedRealtime()
    }

    fun onStart(act: FragmentActivity) {
        if (!on(act)) { if (!PrivateTabs.lockOn(act)) dismiss(); return } // 0.0.331: a showing private tabs lock page must stay
        if (dialog?.isShowing == true) return
        if (unlocked && SystemClock.elapsedRealtime() - leftAt >= DELAYS[delayIdx(act)]) unlocked = false
        if (!unlocked) show(act)
    }

    // ---- 0.0.331: private tabs lock (only while the App lock is OFF)

    private var privLeftAt = 0L

    /** onStop: remember that the app was left with a private tab in front. */
    fun privStop(act: FragmentActivity, privFront: Boolean) {
        if (!on(act) && privFront && PrivateTabs.lockOn(act) && !act.isChangingConfigurations) privLeftAt = SystemClock.elapsedRealtime()
    }

    /** onStart: coming back with a private tab in front = lock page (fingerprint / PIN) until unlocked. */
    fun privStart(act: FragmentActivity, privFront: Boolean) {
        if (on(act) || !PrivateTabs.lockOn(act) || privLeftAt == 0L) return
        if (dialog?.isShowing == true) return
        privLeftAt = 0L
        if (privFront) show(act, "Private tabs are locked")
    }

    fun onDestroy() {
        try { dialog?.dismiss() } catch (e: Exception) { }
        dialog = null
        prompting = false
    }

    // ---- internals

    /** 0.0.330: true while a private tab is open; MainActivity keeps FLAG_SECURE on (blank app preview) and the lock must not clear it. */
    @Volatile var privateOpen = false

    private fun secure(act: FragmentActivity, on: Boolean) {
        try {
            if (on || privateOpen) act.window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
            else act.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        } catch (e: Exception) { }
    }

    private fun dismiss() {
        try { dialog?.dismiss() } catch (e: Exception) { }
        dialog = null
    }

    private fun authenticate(act: FragmentActivity, title: String, result: (Boolean) -> Unit) {
        if (prompting) return
        prompting = true
        try {
            val prompt = BiometricPrompt(act, ContextCompat.getMainExecutor(act), object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(r: BiometricPrompt.AuthenticationResult) { prompting = false; result(true) }
                override fun onAuthenticationError(code: Int, msg: CharSequence) { prompting = false; result(false) }
            })
            val b = BiometricPrompt.PromptInfo.Builder().setTitle(title).setAllowedAuthenticators(authenticators())
            if (Build.VERSION.SDK_INT < 30) b.setNegativeButtonText("Cancel")
            prompt.authenticate(b.build())
        } catch (e: Exception) {
            prompting = false
            result(false)
        }
    }

    private fun show(act: FragmentActivity, title: String = "Thazir is locked") {
        if (act.isFinishing || act.isDestroyed) return
        if (!available(act)) { unlocked = true; return } // lock screen was removed on the phone: do not trap the user
        if (dialog?.isShowing == true) return
        val dp = { v: Int -> Util.dp(act, v) }
        val d = object : Dialog(act, android.R.style.Theme_Black_NoTitleBar_Fullscreen) {
            @Deprecated("Deprecated in Java")
            override fun onBackPressed() { act.moveTaskToBack(true) } // back = leave the app, never past the lock
            override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean =
                if (keyCode == KeyEvent.KEYCODE_BACK) { act.moveTaskToBack(true); true } else super.onKeyDown(keyCode, event)
        }
        d.setCancelable(false)
        d.setCanceledOnTouchOutside(false)
        val col = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(act.getColor(R.color.mg_bg))
            setPadding(dp(32), dp(32), dp(32), dp(32))
        }
        col.addView(ImageView(act).apply {
            setImageResource(R.drawable.ic_lock)
            setColorFilter(act.getColor(R.color.mg_primary))
        }, LinearLayout.LayoutParams(dp(56), dp(56)))
        col.addView(TextView(act).apply {
            text = title
            textSize = 22f
            gravity = Gravity.CENTER
            setTextColor(act.getColor(R.color.mg_text))
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(16) })
        val btn = MaterialButton(act).apply {
            text = "Unlock"
            setOnClickListener { tryUnlock(act) }
        }
        col.addView(btn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(48)).apply { topMargin = dp(24) })
        d.setContentView(col, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        d.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.BLACK))
            setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        }
        dialog = d
        try { d.show() } catch (e: Exception) { dialog = null; return }
        d.window?.decorView?.post { tryUnlock(act) }
    }

    private fun tryUnlock(act: FragmentActivity) {
        authenticate(act, "Unlock Thazir") { ok ->
            if (ok) {
                unlocked = true
                dismiss()
            }
        }
    }
}
