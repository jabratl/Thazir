package com.jabra.mgb

/**
 * 0.0.406: removes tracking parameters from a link (fbclid, gclid, utm_*, igsh, mibextid, rdt_cid ...). Pure text, no Android, never throws.
 * Only parameters on the fixed list below are removed; every other parameter (v=, id=, story_fbid=, t=, signatures like ex / is / hm) and the #fragment stay.
 * A link to a media file on a CDN (cdn.discordapp.com ..., signed links) and a link with no "?" are returned unchanged.
 */
internal object LinkClean {
    private val EXACT = setOf("fbclid", "gclid", "dclid", "msclkid", "igshid", "igsh", "mibextid", "rdt_cid", "share_id", "ref_src", "ref_url",
        "mc_cid", "mc_eid", "_ga", "yclid", "twclid", "ttclid", "si_src", "s_cid", "fb_source", "referral_code", "sfnsn", "wtsid")

    private fun bad(name: String): Boolean {
        val n = name.lowercase()
        return n in EXACT || n.startsWith("utm_")
    }

    fun clean(url: String): String {
        return try {
            val q = url.indexOf('?')
            val h0 = url.indexOf('#')
            if (q < 0 || (h0 in 0 until q)) return url
            val frag = url.indexOf('#').let { if (it > q) it else url.length }
            val head = url.substring(0, q)
            val query = url.substring(q + 1, frag)
            val tail = url.substring(frag)
            if (LinkGrabCore.kindOf(url) == "direct") return url   // signed CDN / file links: never touched
            val keep = query.split('&').filter { it.isNotEmpty() && !bad(it.substringBefore('=')) }
            if (keep.size == query.split('&').filter { it.isNotEmpty() }.size) url
            else head + (if (keep.isEmpty()) "" else "?" + keep.joinToString("&")) + tail
        } catch (e: Exception) { url }
    }
}
