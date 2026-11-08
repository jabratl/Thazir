package com.jabra.mgb

import android.content.Context
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import java.util.concurrent.atomic.AtomicInteger

/**
 * 0.0.337: the AI picture check is REMOVED (it made the app heavy: 24 MB model + TensorFlow Lite, users complained).
 * This object keeps the old names so every caller still compiles, but it does nothing:
 * no model is loaded, no picture is downloaded or checked, intercept() always returns null (the request goes on untouched).
 * The CSS / JS "Sensitive media" blur (SocialBlur) is a separate light feature and is not changed.
 */
object SocialNsfw {
    /** old pref keys: the SettingsScreen RESET list still clears them. */
    val keys: List<String> = listOf("sm_nsfw", "sm_nsfw_th", "sm_nsfw_ok")
    val levelNames: List<String> = listOf("Low", "Normal", "Strict")
    val blurred = AtomicInteger(0)
    val fineCount = AtomicInteger(0)

    fun on(c: Context): Boolean = false
    fun setOn(c: Context, v: Boolean) {}
    fun threshold(c: Context): Float = 0.60f
    fun setThreshold(c: Context, v: Float) {}
    fun level(c: Context): Int = 1
    fun setLevel(c: Context, i: Int) {}
    fun clearCaches() {}
    fun isFine(c: Context, u: String): Boolean = false
    fun isBlurred(u: String): Boolean = false
    fun markFine(c: Context, u: String): Boolean = false
    fun modelReady(c: Context): Boolean = false
    fun status(c: Context): String = "Removed"
    fun intercept(ctx: Context, tabUrl: String, r: WebResourceRequest): WebResourceResponse? = null
}
