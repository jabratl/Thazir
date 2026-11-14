package com.jabra.mgb

import android.content.Context
import android.util.Log

/**
 * S8: one place for the safety rules of installed extensions.
 *  - blockReason(info): why MGB refuses an extension (manifest v2, nativeMessaging). null = fine.
 *  - sweep(ctx): turns off any installed extension that is blocked (an older install, before S8). Called once at start.
 *  - ignored(info): permissions that are accepted but do nothing in MGB (shown in the add dialog, not a block).
 *  - proxyAllowed(xid, url): host permission filter for the CORS-free proxy (same rule as the extension window in ExtScreen).
 * Install is refused in ExtListScreen (before the permission dialog); ExtRegistry.kt was not available in S8, so its prepare is unchanged.
 */
object ExtSafety {
    private const val TAG = "ExtSafety"

    private val BLOCKED_PERMS = setOf("nativeMessaging")

    /** Api of these permissions is not in ExtApi.EXT_OK (answer "Not supported in MGB"). */
    private val IGNORED_PERMS = setOf(
        "webRequestBlocking", "webRequestAuthProvider", "downloads", "downloads.open", "offscreen", "sidePanel",
        "history", "bookmarks", "proxy", "debugger", "management", "identity", "browsingData", "privacy", "topSites"
    )

    fun blockReason(info: ExtRegistry.Info): String? {
        if (info.manifestVersion != 3) return "It uses manifest v" + info.manifestVersion + ". MGB runs manifest v3 extensions only."
        val bad = info.permissions.filter { it in BLOCKED_PERMS }
        if (bad.isNotEmpty()) return "It asks for " + bad.joinToString(", ") + ", which MGB cannot give (no native apps)."
        return null
    }

    fun ignored(info: ExtRegistry.Info): List<String> = info.permissions.filter { it in IGNORED_PERMS }

    /** Turns off blocked extensions that are on. Never throws. */
    fun sweep(ctx: Context) {
        try {
            for (info in ExtRegistry.all()) {
                if (!info.enabled) continue
                val why = blockReason(info) ?: continue
                val ok = ExtRegistry.setEnabled(info.id, false)
                Diag.w("ext", "blocked extension turned off" + (if (ok) "" else " (failed)"), info.name + ": " + why)
                Log.w(TAG, "turned off " + info.id + ": " + why)
            }
        } catch (e: Throwable) {
            Log.e(TAG, "sweep", e)
        }
    }

    /**
     * 0.0.433 (S8): externally_connectable.matches that are accepted: https / http / * scheme, a real host (no "*" alone, no "<all_urls>",
     * "*." only in front of a name with at least one more dot, "localhost" allowed), max 20. Everything else is dropped.
     */
    fun externalPatterns(raw: List<String>): List<String> {
        val out = ArrayList<String>()
        val rx = Regex("^(\\*|https?)://([^/]*)(/.*)?$")
        for (p in raw) {
            if (out.size >= 20) break
            val m = rx.find(p) ?: continue
            val h = m.groupValues[2]
            if (h.isEmpty() || h == "*") continue
            val bare = h.removePrefix("*.").substringBefore(':')
            if (bare.isEmpty() || bare.contains('*') || bare.contains(' ')) continue
            if (!bare.contains('.') && bare != "localhost") continue
            out.add(p)
        }
        return out
    }

    /** True when the request of an extension page may use the CORS-free proxy (url inside its host permissions). */
    fun proxyAllowed(xid: String, url: String): Boolean {
        val inf = ExtRegistry.get(xid) ?: return false
        if (!inf.enabled) return false
        val pats = inf.hostPermissions + inf.permissions.filter { it.contains("://") || it == "<all_urls>" }
        return ContentInjector.urlAllowed(pats, url)
    }
}
