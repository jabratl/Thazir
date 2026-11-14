package com.jabra.mgb

import android.content.Context

/**
 * 0.0.434 (S9): CSS COMPAT FOR AN OLD WEBVIEW (installed extensions only; the built-in Media Grabber is never touched).
 * assets/shim/css-compat.js rewrites style text that scripts add (CSS nesting, @layer, @property) into plain CSS.
 * It is added ONLY when needed: Auto = System WebView older than 120 (or the S1 probe says nesting / @layer / @property is missing),
 * On = always, Off = never. Pref "ext_css" in mgb_ui: absent = Auto, 1 = Off, 2 = On (no Settings row yet: S10 adds it).
 * Added at document start on the origins of installed extensions' content scripts, and on EXT_ORIGIN pages of installed extensions.
 */
object ExtCssCompat {
    const val AUTO = 0
    const val OFF = 1
    const val ON = 2
    const val KEY = "ext_css"

    private var tpl: String? = null
    @Volatile private var major = -1

    private fun sp(c: Context) = c.applicationContext.getSharedPreferences("mgb_ui", Context.MODE_PRIVATE)

    fun mode(c: Context): Int = sp(c).getInt(KEY, AUTO).let { if (it == OFF || it == ON) it else AUTO }

    fun set(c: Context, v: Int) {
        sp(c).edit().apply { if (v == OFF || v == ON) putInt(KEY, v) else remove(KEY) }.apply()
    }

    fun label(v: Int): String = when (v) { ON -> "On"; OFF -> "Off"; else -> "Auto" }

    private fun webMajor(): Int {
        if (major < 0) major = try { ExtProbe.webViewMajor() } catch (_: Throwable) { 0 }
        return major
    }

    /** True when the script should be added (checked per tab / popup, cheap). */
    fun needed(c: Context): Boolean = try {
        when (mode(c)) {
            OFF -> false
            ON -> true
            else -> {
                val m = webMajor()
                (m in 1..119) || ExtProbe.cssMissing().any { it == "nesting" || it == "layer" || it == "property" }
            }
        }
    } catch (_: Throwable) { false }

    /** null = nothing to add (not needed, no installed extension, asset missing). */
    fun script(c: Context): String? = try {
        if (!needed(c)) null
        else tpl ?: c.applicationContext.assets.open("shim/css-compat.js").bufferedReader().use { it.readText() }.also { tpl = it }
    } catch (_: Throwable) { null }

    /** Settings / report line. */
    fun state(c: Context): String = when (mode(c)) {
        OFF -> "off"
        ON -> "on"
        else -> if (needed(c)) "active (WebView is old)" else "not needed"
    }
}
