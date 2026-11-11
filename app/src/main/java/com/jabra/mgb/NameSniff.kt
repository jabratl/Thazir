package com.jabra.mgb

import java.io.File
import java.io.RandomAccessFile
import java.util.zip.ZipFile

/**
 * 0.0.404: last safety net for saved file names. When the name of a finished download has no real extension (nothing, ".bin", ".dat",
 * ".tmp", ".download", ".php" ...) the first bytes of the file are read and, when the content is a known type, the right extension is used
 * (an APK saved as "file.bin" becomes "file.apk"). A name that already has a normal extension is NEVER changed. Unknown content = name unchanged.
 * Never throws. Runs on the download worker thread only (reads the first 16 bytes; for a zip also its file list).
 */
internal object NameSniff {
    private val WEAK = setOf("bin", "dat", "tmp", "download", "php", "asp", "aspx", "jsp", "cgi")

    /** rel = "dir/name.ext" or "name.ext". Returns the same text, or the same path with a better extension. */
    fun fix(rel: String, part: File): String {
        return try {
            val name = rel.substringAfterLast('/')
            val dir = rel.substringBeforeLast('/', "")
            val dot = name.lastIndexOf('.')
            val noExt = dot <= 0 || dot == name.length - 1
            val ext = if (noExt) "" else name.substring(dot + 1).lowercase()
            if (!noExt && ext !in WEAK) return rel
            val kind = detect(part) ?: return rel
            var stem = (if (noExt) name.trimEnd('.') else name.substring(0, dot)).ifEmpty { "download" }
            if (stem.startsWith(".") && stem.substring(1).lowercase() in WEAK) stem = "download"   // a name that is only ".bin"
            val n = "$stem.$kind"
            if (dir.isEmpty()) n else "$dir/$n"
        } catch (e: Exception) {
            rel
        }
    }

    private fun detect(f: File): String? {
        if (!f.isFile || f.length() < 12) return null
        val h = ByteArray(16)
        val got = RandomAccessFile(f, "r").use { it.read(h) }
        if (got < 12) return null
        fun u(i: Int): Int = h[i].toInt() and 0xFF
        fun at(i: Int, s: String): Boolean {
            for (k in s.indices) if (u(i + k) != s[k].code) return false
            return true
        }
        return when {
            u(0) == 0x50 && u(1) == 0x4B && u(2) == 0x03 && u(3) == 0x04 -> zipKind(f)
            at(0, "%PDF-") -> "pdf"
            u(0) == 0x89 && at(1, "PNG") -> "png"
            u(0) == 0xFF && u(1) == 0xD8 && u(2) == 0xFF -> "jpg"
            at(0, "GIF8") -> "gif"
            at(0, "RIFF") && at(8, "WEBP") -> "webp"
            at(4, "ftyp") -> if (at(8, "M4A ")) "m4a" else if (at(8, "qt  ")) "mov" else "mp4"
            u(0) == 0x37 && u(1) == 0x7A && u(2) == 0xBC && u(3) == 0xAF -> "7z"
            at(0, "Rar!") -> "rar"
            u(0) == 0x1A && u(1) == 0x45 && u(2) == 0xDF && u(3) == 0xA3 -> "mkv"
            at(0, "ID3") -> "mp3"
            else -> null
        }
    }

    /** apk = real app; apkm / xapk / apks = bundles of apk files; zip = any other zip; null = office / epub style files (left as they are). */
    private fun zipKind(f: File): String? {
        ZipFile(f).use { z ->
            val names = HashSet<String>()
            val en = z.entries()
            while (en.hasMoreElements() && names.size < 20000) names.add(en.nextElement().name)
            val hasApk = names.any { it.endsWith(".apk", true) }
            return when {
                "AndroidManifest.xml" in names && ("resources.arsc" in names || names.any { it.startsWith("classes") && it.endsWith(".dex") }) -> "apk"
                "[Content_Types].xml" in names || "mimetype" in names -> null
                "info.json" in names && hasApk -> "apkm"
                "manifest.json" in names && hasApk -> "xapk"
                hasApk -> "apks"
                else -> "zip"
            }
        }
    }
}
