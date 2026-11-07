package com.jabra.mgb

import android.content.Context
import android.os.Build
import androidx.fragment.app.FragmentActivity

/**
 * 0.0.331: private tab settings (Settings > Privacy and security > Private tabs), like Brave / Chrome private mode.
 *  - priv_secure (mgb_ui, default ON): while a private tab is open the window is FLAG_SECURE = blank Recents picture, no screenshots.
 *  - priv_lock (mgb_lock, default OFF): coming back to the app with a private tab in front asks fingerprint / PIN first
 *    (same prompt + lock page as the App lock; not used while the App lock itself is ON, it already covers everything).
 * Logic that touches the window stays in MainActivity.syncPrivateSecure and AppLock.privStart / privStop.
 */
object PrivateTabs {
    private fun ui(c: Context) = c.getSharedPreferences("mgb_ui", Context.MODE_PRIVATE)
    private fun lk(c: Context) = c.getSharedPreferences("mgb_lock", Context.MODE_PRIVATE)

    fun secureOn(c: Context): Boolean = ui(c).getBoolean("priv_secure", true)
    fun setSecure(c: Context, v: Boolean) { ui(c).edit().putBoolean("priv_secure", v).apply() }

    fun lockOn(c: Context): Boolean = lk(c).getBoolean("priv_lock", false)

    /** Turn the lock ON (asks the user to prove it is them first, so it cannot lock them out) or OFF. */
    fun toggleLock(act: FragmentActivity, done: () -> Unit) {
        if (lockOn(act)) {
            lk(act).edit().putBoolean("priv_lock", false).apply()
            done()
            return
        }
        if (!AppLock.available(act)) {
            Util.toast(act, if (Build.VERSION.SDK_INT >= 30) "Set a screen lock or fingerprint in phone Settings first" else "Add a fingerprint / face in phone Settings first")
            return
        }
        AppLock.ask(act, "Turn on private tabs lock") { ok ->
            if (ok) {
                lk(act).edit().putBoolean("priv_lock", true).apply()
                Util.toast(act, "Private tabs lock ON")
                done()
            }
        }
    }
}
