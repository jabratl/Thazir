package com.jabra.mgb

import android.content.Context
import android.util.Log
import org.json.JSONArray
import java.io.File
import java.io.FileOutputStream

/**
 * 0.0.387 (PW1): safe file work for the saved logins (filesDir/mgb_pw.json).
 * - read(): parse errors are NOT swallowed. The bad file is copied to mgb_pw.bad.<time>.json and the one-generation
 *   backup mgb_pw.json.bak is tried. The caller learns what happened (Read.state).
 * - write(): tmp file + fsync + rename; the last good file becomes mgb_pw.json.bak first.
 * Nothing here logs a user name or a password.
 */
object PwFile {
    private const val TAG = "MGB.pwfile"
    const val NAME = "mgb_pw.json"

    /** state: 0 = ok (or no file yet), 1 = main file bad but the .bak was read, 2 = unreadable (copy kept, nothing loaded). */
    class Read(val state: Int, val array: JSONArray?, val copy: String?)

    private fun dir(ctx: Context) = ctx.applicationContext.filesDir

    fun read(ctx: Context): Read {
        val f = File(dir(ctx), NAME)
        if (!f.exists()) return Read(0, JSONArray(), null)
        try {
            return Read(0, JSONArray(f.readText()), null)
        } catch (e: Exception) {
            Log.e(TAG, "main file unreadable", e)
        }
        val copy = keepBad(ctx, f)
        try {
            val b = File(dir(ctx), "$NAME.bak")
            if (b.exists()) return Read(1, JSONArray(b.readText()), copy)
        } catch (e: Exception) {
            Log.e(TAG, "backup file unreadable", e)
        }
        return Read(2, null, copy)
    }

    private fun keepBad(ctx: Context, f: File): String? = try {
        val c = File(dir(ctx), "mgb_pw.bad." + System.currentTimeMillis() + ".json")
        f.copyTo(c, overwrite = false)
        c.name
    } catch (e: Exception) { Log.e(TAG, "could not copy the bad file", e); null }

    /** true = the new text is on disk. false = the old file is still there, untouched. */
    fun write(ctx: Context, text: String): Boolean {
        return try {
            val f = File(dir(ctx), NAME)
            val tmp = File(dir(ctx), "$NAME.tmp")
            FileOutputStream(tmp).use { o ->
                o.write(text.toByteArray(Charsets.UTF_8))
                o.flush()
                o.fd.sync()
            }
            if (f.exists()) {
                try { f.copyTo(File(dir(ctx), "$NAME.bak"), overwrite = true) } catch (e: Exception) { Log.e(TAG, "bak copy failed", e) }
            }
            if (!tmp.renameTo(f)) {
                f.delete()
                if (!tmp.renameTo(f)) { Log.e(TAG, "rename failed"); return false }
            }
            true
        } catch (e: Exception) { Log.e(TAG, "write failed", e); false }
    }
}
