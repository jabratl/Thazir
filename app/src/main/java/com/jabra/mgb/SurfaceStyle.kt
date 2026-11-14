package com.jabra.mgb

import android.app.Activity
import android.content.Context
import android.content.res.Configuration

/**
 * 0.0.423: surface style. Pref "surf" in mgb_ui: soft (default) | classic | black.
 *  - soft: the 0.0.421 light tokens (blue-grey) + dark tokens; in LIGHT mode a non-violet accent also tints the background (tint_<accent>_*)
 *  - classic: the old white light surfaces (#F8F8FC / #FFFFFF), used only in LIGHT mode
 *  - black: pure black dark surfaces (AMOLED), used only in DARK mode
 * Two parts, like AccentTheme: apply() = theme overlay (XML attrs mgBg / mgSurface / ..., window + status bar, Material containers),
 * pal() = colour ids that AccentTheme.res() answers for R.color.mg_bg / mg_surface / mg_surface_variant / mg_outline (native screens).
 * Text, primary and semantic colours are never changed. Soft + violet = null / 0 = old behaviour byte for byte.
 */
object SurfaceStyle {
    const val SOFT = "soft"
    const val CLASSIC = "classic"
    const val BLACK = "black"

    private val TINT: Map<String, IntArray> = mapOf(
        "ocean" to intArrayOf(R.color.tint_ocean_bg, R.color.tint_ocean_surface, R.color.tint_ocean_variant, R.color.tint_ocean_outline),
        "emerald" to intArrayOf(R.color.tint_emerald_bg, R.color.tint_emerald_surface, R.color.tint_emerald_variant, R.color.tint_emerald_outline),
        "sunset" to intArrayOf(R.color.tint_sunset_bg, R.color.tint_sunset_surface, R.color.tint_sunset_variant, R.color.tint_sunset_outline),
        "rose" to intArrayOf(R.color.tint_rose_bg, R.color.tint_rose_surface, R.color.tint_rose_variant, R.color.tint_rose_outline),
        "sapphire" to intArrayOf(R.color.tint_sapphire_bg, R.color.tint_sapphire_surface, R.color.tint_sapphire_variant, R.color.tint_sapphire_outline),
        "amber" to intArrayOf(R.color.tint_amber_bg, R.color.tint_amber_surface, R.color.tint_amber_variant, R.color.tint_amber_outline),
        "mocha" to intArrayOf(R.color.tint_mocha_bg, R.color.tint_mocha_surface, R.color.tint_mocha_variant, R.color.tint_mocha_outline),
        "slate" to intArrayOf(R.color.tint_slate_bg, R.color.tint_slate_surface, R.color.tint_slate_variant, R.color.tint_slate_outline),
        "orchid" to intArrayOf(R.color.tint_orchid_bg, R.color.tint_orchid_surface, R.color.tint_orchid_variant, R.color.tint_orchid_outline)
    )
    private val TINT_OVL: Map<String, Int> = mapOf(
        "ocean" to R.style.ThemeOverlay_MGB_Surf_Tint_Ocean,
        "emerald" to R.style.ThemeOverlay_MGB_Surf_Tint_Emerald,
        "sunset" to R.style.ThemeOverlay_MGB_Surf_Tint_Sunset,
        "rose" to R.style.ThemeOverlay_MGB_Surf_Tint_Rose,
        "sapphire" to R.style.ThemeOverlay_MGB_Surf_Tint_Sapphire,
        "amber" to R.style.ThemeOverlay_MGB_Surf_Tint_Amber,
        "mocha" to R.style.ThemeOverlay_MGB_Surf_Tint_Mocha,
        "slate" to R.style.ThemeOverlay_MGB_Surf_Tint_Slate,
        "orchid" to R.style.ThemeOverlay_MGB_Surf_Tint_Orchid
    )
    private val CLASSIC_PAL = intArrayOf(R.color.mgc_bg, R.color.mgc_surface, R.color.mgc_variant, R.color.mgc_outline)
    private val BLACK_PAL = intArrayOf(R.color.mgb_bg, R.color.mgb_surface, R.color.mgb_variant, R.color.mgb_outline)

    @Volatile private var cur: String? = null

    fun id(ctx: Context): String {
        cur?.let { return it }
        val v = try { ctx.applicationContext.getSharedPreferences("mgb_ui", Context.MODE_PRIVATE).getString("surf", SOFT) } catch (_: Exception) { null } ?: SOFT
        val ok = if (v == CLASSIC || v == BLACK) v else SOFT
        cur = ok
        return ok
    }

    /** Forget the remembered choice (after a backup restore / reset wrote the pref directly). */
    fun reload() { cur = null }

    fun set(ctx: Context, id: String) {
        ctx.applicationContext.getSharedPreferences("mgb_ui", Context.MODE_PRIVATE).edit().putString("surf", id).apply()
        cur = id
    }

    fun isNight(cfg: Configuration): Boolean = (cfg.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    /** [bg, surface, surface_variant, outline] colour ids for this mode, or null = the default tokens. */
    fun pal(ctx: Context, night: Boolean, accentId: String): IntArray? = when {
        night -> if (id(ctx) == BLACK) BLACK_PAL else null
        id(ctx) == CLASSIC -> CLASSIC_PAL
        id(ctx) == SOFT && accentId != "violet" -> TINT[accentId]
        else -> null
    }

    /** Theme overlay style for this mode, 0 = none. */
    fun overlay(ctx: Context, night: Boolean, accentId: String): Int = when {
        night -> if (id(ctx) == BLACK) R.style.ThemeOverlay_MGB_Surf_Black else 0
        id(ctx) == CLASSIC -> R.style.ThemeOverlay_MGB_Surf_Classic
        id(ctx) == SOFT && accentId != "violet" -> TINT_OVL[accentId] ?: 0
        else -> 0
    }

    /** MainActivity.onCreate after AccentTheme.apply, before setContentView (window background / status bar are read then). Not for the player (black window). */
    fun apply(act: Activity) {
        try {
            val ov = overlay(act, isNight(act.resources.configuration), AccentTheme.id(act))
            if (ov != 0) act.theme.applyStyle(ov, true)
        } catch (e: Exception) { /* keep the default surfaces */ }
    }
}
