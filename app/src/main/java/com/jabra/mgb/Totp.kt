package com.jabra.mgb

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * 0.0.392 (PW6a): 2FA codes (TOTP, RFC 6238). Works fully offline, no network, no camera, no new dependency.
 * Input = a base32 secret ("JBSW Y3DP ...") or an otpauth://totp/... link (secret, digits, period, algorithm, issuer).
 * The secret is stored Vault-encrypted (PasswordStore.Entry.totp). Nothing here logs a secret or a code.
 */
object Totp {
    class Cfg(val secret: ByteArray, val digits: Int, val period: Int, val algo: String)

    /** null = not a usable secret. */
    fun parse(input: String): Cfg? {
        try {
            val t = input.trim()
            var secretStr = t
            var digits = 6; var period = 30; var algo = "SHA1"
            if (t.startsWith("otpauth://", true)) {
                val u = android.net.Uri.parse(t)
                if (!"totp".equals(u.host, true)) return null
                secretStr = u.getQueryParameter("secret") ?: return null
                digits = u.getQueryParameter("digits")?.toIntOrNull() ?: 6
                period = u.getQueryParameter("period")?.toIntOrNull() ?: 30
                algo = (u.getQueryParameter("algorithm") ?: "SHA1").uppercase()
            }
            val key = base32(secretStr) ?: return null
            if (key.isEmpty() || digits !in 6..8 || period !in 10..120 || algo !in setOf("SHA1", "SHA256", "SHA512")) return null
            return Cfg(key, digits, period, algo)
        } catch (e: Exception) { return null }
    }

    fun base32(s: String): ByteArray? {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
        var buf = 0; var bits = 0
        val out = ByteArrayOutputStream()
        for (c in s.uppercase()) {
            if (c == ' ' || c == '-' || c == '=') continue
            val v = alphabet.indexOf(c)
            if (v < 0) return null
            buf = ((buf shl 5) or v) and 0xFFFF
            bits += 5
            if (bits >= 8) { bits -= 8; out.write((buf shr bits) and 0xFF) }
        }
        return out.toByteArray()
    }

    fun code(c: Cfg, nowMs: Long): String {
        val counter = nowMs / 1000L / c.period
        val mac = Mac.getInstance("Hmac" + c.algo)
        mac.init(SecretKeySpec(c.secret, "RAW"))
        val h = mac.doFinal(ByteBuffer.allocate(8).putLong(counter).array())
        val o = h[h.size - 1].toInt() and 0xF
        val bin = ((h[o].toInt() and 0x7F) shl 24) or ((h[o + 1].toInt() and 0xFF) shl 16) or ((h[o + 2].toInt() and 0xFF) shl 8) or (h[o + 3].toInt() and 0xFF)
        var mod = 1
        repeat(c.digits) { mod *= 10 }
        return (bin % mod).toString().padStart(c.digits, '0')
    }

    fun secondsLeft(c: Cfg, nowMs: Long): Int = c.period - ((nowMs / 1000L) % c.period).toInt()
}
