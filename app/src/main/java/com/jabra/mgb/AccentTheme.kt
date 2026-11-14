package com.jabra.mgb

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources

/**
 * 0.0.289: accent colour themes. The user picks one in Settings > Appearance > Accent colour (pref "accent" in mgb_ui, default violet).
 * Two parts, both only active when the accent is not violet (violet = old behaviour byte for byte):
 *  1) apply(): a theme overlay over Theme.MGB (colorPrimary / colorOnPrimary) for Material widgets and ?attr/colorPrimary in layouts.
 *  2) res(): MainActivity.getResources() returns a wrapper that answers R.color.mg_primary / mg_toggle_on / mg_on_primary with the
 *     accent colours (light and dark variants come from values / values-night), so all native screens that use getColor follow.
 * Surfaces, text and semantic colours (error, success) are never changed.
 */
object AccentTheme {
    class Accent(val id: String, val label: String, val color: Int, val overlay: Int)

    val ALL: List<Accent> = listOf(
        Accent("violet", "Violet", R.color.acc_violet, 0),
        Accent("ocean", "Ocean", R.color.acc_ocean, R.style.ThemeOverlay_MGB_Accent_Ocean),
        Accent("emerald", "Emerald", R.color.acc_emerald, R.style.ThemeOverlay_MGB_Accent_Emerald),
        Accent("sunset", "Sunset", R.color.acc_sunset, R.style.ThemeOverlay_MGB_Accent_Sunset),
        Accent("rose", "Rose", R.color.acc_rose, R.style.ThemeOverlay_MGB_Accent_Rose),
        Accent("sapphire", "Sapphire", R.color.acc_sapphire, R.style.ThemeOverlay_MGB_Accent_Sapphire),
        Accent("amber", "Amber", R.color.acc_amber, R.style.ThemeOverlay_MGB_Accent_Amber),
        Accent("mocha", "Mocha", R.color.acc_mocha, R.style.ThemeOverlay_MGB_Accent_Mocha),
        Accent("slate", "Slate", R.color.acc_slate, R.style.ThemeOverlay_MGB_Accent_Slate),
        Accent("orchid", "Orchid", R.color.acc_orchid, R.style.ThemeOverlay_MGB_Accent_Orchid)
    )

    @Volatile private var cur: String? = null
    private var last: Wrapper? = null

    fun id(ctx: Context): String {
        cur?.let { return it }
        val v = try { ctx.applicationContext.getSharedPreferences("mgb_ui", Context.MODE_PRIVATE).getString("accent", "violet") } catch (_: Exception) { null } ?: "violet"
        val ok = if (ALL.any { it.id == v }) v else "violet"
        cur = ok
        return ok
    }

    /** Forget the remembered choice (after a backup restore wrote the pref directly). */
    fun reload() { cur = null }

    fun current(ctx: Context): Accent = ALL.first { it.id == id(ctx) }

    fun set(ctx: Context, id: String) {
        ctx.applicationContext.getSharedPreferences("mgb_ui", Context.MODE_PRIVATE).edit().putString("accent", id).apply()
        cur = id
    }

    /** Call in Activity.onCreate after super.onCreate, before setContentView. */
    fun apply(act: Activity) {
        val a = current(act)
        if (a.overlay != 0) act.theme.applyStyle(a.overlay, true)
    }

    /** Activity.getResources() body: the wrapper for a non-violet accent or a non-default surface style (0.0.423), the plain Resources otherwise. */
    fun res(ctx: Context, base: Resources): Resources {
        try {
            val a = current(ctx)
            val pal = SurfaceStyle.pal(ctx, SurfaceStyle.isNight(base.configuration), a.id)
            if (a.overlay == 0 && pal == null) return base
            val acc = if (a.overlay == 0) 0 else a.color
            synchronized(this) {
                val w = last
                if (w != null && w.base === base && w.accent == acc && java.util.Arrays.equals(w.pal, pal) && w.cfg.diff(base.configuration) == 0) return w
                val n = Wrapper(base, acc, pal)
                last = n
                return n
            }
        } catch (_: Exception) { return base }
    }

    @Suppress("DEPRECATION")
    private class Wrapper(val base: Resources, val accent: Int, val pal: IntArray?) : Resources(base.assets, base.displayMetrics, base.configuration) {
        val cfg = Configuration(base.configuration)
        private fun map(id: Int): Int {
            if (accent != 0) {
                if (id == R.color.mg_primary || id == R.color.mg_toggle_on) return accent
                if (id == R.color.mg_on_primary) return R.color.acc_on
            }
            val p = pal ?: return id
            return when (id) { // 0.0.423: surface style
                R.color.mg_bg -> p[0]
                R.color.mg_surface -> p[1]
                R.color.mg_surface_variant -> p[2]
                R.color.mg_outline -> p[3]
                else -> id
            }
        }
        override fun getColor(id: Int, theme: Resources.Theme?): Int = super.getColor(map(id), theme)
        override fun getColorStateList(id: Int, theme: Resources.Theme?) = super.getColorStateList(map(id), theme)
        override fun getDisplayMetrics() = base.displayMetrics
        override fun getConfiguration() = base.configuration
    }
}
