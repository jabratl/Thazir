package com.jabra.mgb

import android.content.Context
import android.webkit.WebView

/**
 * DARK PAGE PER SITE (0.0.275, C8 part). Pref sm_dk_<id> in mgb_ui: absent = same as the app setting "Dark pages", 1 = On, 2 = Off for this site only.
 * It only switches the WebView's own algorithmic darkening (UiMode.applyPage) on the next page load of that site, so it still needs a dark app theme to show.
 * Nothing happens until the person sets at least one site: anyOverride() is false and apply() returns at once.
 */
object SocialDark {
    const val SAME = 0
    const val ON = 1
    const val OFF = 2

    /** Keys for the SettingsScreen RESET list. */
    val keys: List<String> = SocialProfiles.all.map { "sm_dk_" + it.id }

    private fun sp(c: Context) = c.getSharedPreferences("mgb_ui", Context.MODE_PRIVATE)

    fun siteMode(c: Context, id: String): Int = sp(c).getInt("sm_dk_" + id, SAME).coerceIn(SAME, OFF)
    fun setSiteMode(c: Context, id: String, v: Int) {
        if (v <= SAME) sp(c).edit().remove("sm_dk_" + id).apply() else sp(c).edit().putInt("sm_dk_" + id, v.coerceIn(ON, OFF)).apply()
    }
    /** Tap order: Same as app -> On -> Off -> Same as app. Returns the new value. */
    fun cycleSite(c: Context, id: String): Int { val n = if (siteMode(c, id) >= OFF) SAME else siteMode(c, id) + 1; setSiteMode(c, id, n); return n }
    fun siteLabel(v: Int): String = when (v) { ON -> "Dark"; OFF -> "Original"; else -> "Same as app setting" }
    fun siteOverrides(c: Context): Int = SocialProfiles.all.count { siteMode(c, it.id) != SAME }

    /** 0.0.424: the choice of the social site this address belongs to (ON / OFF), SAME when none is set, Social sites is OFF for it, or it is no social site. Used by SiteDark.effective(). */
    fun own(c: Context, url: String?): Int {
        try {
            if (url == null || siteOverrides(c) == 0 || !SocialMode.on(c)) return SAME
            val p = SocialProfiles.of(url) ?: return SAME
            return if (SocialProfiles.siteOn(c, p.id)) siteMode(c, p.id) else SAME
        } catch (_: Throwable) { return SAME }
    }

    /** Called from TabManager.onPageStarted (not for extension pages). Fail-safe: any error leaves the page as it is. */
    fun apply(c: Context, view: WebView, url: String?) {
        try {
            if (siteOverrides(c) == 0) return
            val p = if (url != null && SocialMode.on(c)) SocialProfiles.of(url) else null
            val own = if (p != null && SocialProfiles.siteOn(c, p.id)) siteMode(c, p.id) else SAME
            UiMode.applyPage(view, when (own) { ON -> true; OFF -> false; else -> UiMode.darkPages(c) })
        } catch (_: Throwable) { }
    }
}
