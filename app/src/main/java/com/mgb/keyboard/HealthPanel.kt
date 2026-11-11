package com.mgb.keyboard

import android.content.Context
import android.util.AtomicFile
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.mgb.keyboard.typing.BackupCodec
import com.mgb.keyboard.typing.HealthCheck
import com.mgb.keyboard.typing.Migrate
import com.mgb.keyboard.typing.ProfileCodec
import com.mgb.keyboard.vault.VaultList
import java.io.File

/**
 * v0.5.3 S9 HEALTH CHECK + RECOVERY + MIGRATION (UI + storage glue; the logic is pure Java: typing/HealthCheck.java, typing/Migrate.java).
 * - HealthPanel.build(ctx): section inside Data & Backup. Button "Jabra Health Check" runs the check on a background Thread and
 *   lists one line per store (OK / WARN / BAD). A line with a safe repair gets a button (2 taps "Sure?"):
 *   clips = backup copy first, then drop unreadable lines; vault / profiles = restore that file from the newest good backup
 *   (the damaged file is kept as <name>.damaged); crash = switch the disabled panel back on.
 * - HealthRun.migrate(ctx): once per app-screen start (background Thread). Backup first, only when something must change.
 * - PanelGuard: the ONE keyboard hook (MgbIme.showPanel). A panel that throws 2 times in a row is not built again for 24 h
 *   (letters + a short message). Prefs NEW: schema_v (existing, now 2), crash_p(str,""), crash_n(int,0), crash_t(long,0).
 * Nothing runs on the key-press path; PanelGuard.ok / failed run only when a panel opens.
 */
object HealthPanel {
    fun build(ctx: Context): View {
        val d = ctx.resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        val root = LinearLayout(ctx); root.orientation = LinearLayout.VERTICAL
        val msg = TextView(ctx); msg.setPadding(0, dp(6), 0, dp(6))
        val rows = LinearLayout(ctx); rows.orientation = LinearLayout.VERTICAL
        val act = ctx as? SettingsActivity

        fun btn(label: String, on: (Button) -> Unit): Button {
            val b = Button(ctx); b.text = label; b.isAllCaps = false
            b.setOnClickListener { try { on(b) } catch (_: Throwable) { } }
            return b
        }

        fun show(items: List<HealthCheck.Item>) {
            rows.removeAllViews()
            for (item in items) {
                val line = TextView(ctx); line.text = item.label(); line.setPadding(0, dp(3), 0, dp(3)); rows.addView(line)
                if (item.fix.isNotEmpty()) {
                    var armed = false
                    val label = when (item.fix) {
                        "clips" -> "Repair: drop unreadable lines"
                        "crash" -> "Switch the panel on again"
                        else -> "Repair: restore from last good backup"
                    }
                    rows.addView(btn(label) { b ->
                        if (!armed) { armed = true; b.text = "Sure? " + label; return@btn }
                        armed = false; b.text = label; msg.text = "Working..."
                        Thread {
                            val r = try { HealthRun.fix(ctx, item.fix) } catch (_: Throwable) { "Repair failed. Nothing changed." }
                            act?.runOnUiThread { try { msg.text = r; check(ctx, act, msg) { l -> show(l) } } catch (_: Throwable) { } }
                        }.start()
                    })
                }
            }
        }

        try { addSpeed(ctx, root, ::btn) } catch (_: Throwable) { }   // v0.9.10 (V5)
        root.addView(btn("Jabra Health Check") { msg.text = "Checking..."; check(ctx, act, msg) { l -> show(l) } })
        root.addView(msg)
        root.addView(rows)
        return root
    }

    /** v0.9.10 (V5): speed meter rows. Pref perf_meter (default OFF = zero cost). Numbers = last 100 samples per kind. */
    private fun addSpeed(ctx: Context, root: LinearLayout, mk: (String, (Button) -> Unit) -> Button) {
        val d = ctx.resources.displayMetrics.density
        val sp = ctx.getSharedPreferences("jabra", Context.MODE_PRIVATE)
        val head = TextView(ctx); head.setPadding(0, (6 * d).toInt(), 0, (3 * d).toInt()); head.text = "Speed meter"
        val out = TextView(ctx); out.textSize = 12f
        fun on(): Boolean = try { sp.getBoolean("perf_meter", false) } catch (_: Throwable) { false }
        fun fill() {
            val t = com.mgb.keyboard.KeyCfg.timing
            val sb = StringBuilder(if (on()) "ON. Type on the keyboard, then tap Refresh.\n" else "OFF (no cost). Turn ON, type a bit, then Refresh.\n")
            for (k in 0 until com.mgb.keyboard.typing.KeyTiming.KINDS) sb.append(t.line(k)).append('\n')
            out.text = sb.toString()
        }
        val tg = mk("Speed meter") { b -> }
        fun label() { tg.text = if (on()) "Speed meter: ON (tap = OFF)" else "Speed meter: OFF (tap = ON)" }
        tg.setOnClickListener { try { sp.edit().putBoolean("perf_meter", !on()).apply(); label(); fill() } catch (_: Throwable) { } }
        label(); fill()
        root.addView(head); root.addView(tg)
        root.addView(mk("Refresh numbers") { fill() })
        root.addView(mk("Reset numbers") { com.mgb.keyboard.KeyCfg.timing.reset(); fill() })
        root.addView(out)
    }

    private fun check(ctx: Context, act: SettingsActivity?, msg: TextView, show: (List<HealthCheck.Item>) -> Unit) {
        Thread {
            val items = try { HealthCheck.run(HealthRun.collect(ctx)) } catch (_: Throwable) { null }
            act?.runOnUiThread {
                try {
                    if (items == null) { msg.text = "Health check failed to run. Nothing changed." }
                    else {
                        show(items)
                        val w = HealthCheck.worst(items)
                        msg.text = if (w == HealthCheck.OK) "All fine." else if (w == HealthCheck.WARN) "Some things need a look (no data is lost)." else "Something is damaged. Use Repair, or restore a backup above."
                    }
                } catch (_: Throwable) { }
            }
        }.start()
    }
}

object HealthRun {
    private fun sp(ctx: Context) = ctx.getSharedPreferences("jabra", Context.MODE_PRIVATE)
    private fun readFile(f: File): String? = try { if (!f.exists()) null else String(AtomicFile(f).readFully(), Charsets.UTF_8) } catch (_: Throwable) { "" }
    private fun fileSize(f: File): Long = try { if (f.isDirectory) (f.listFiles() ?: emptyArray()).sumOf { fileSize(it) } else f.length() } catch (_: Throwable) { 0L }

    /** Reads everything the check needs (background Thread only). Never throws. */
    fun collect(ctx: Context): HealthCheck.Input {
        val i = HealthCheck.Input()
        val s = sp(ctx)
        fun str(k: String): String = try { s.getString(k, "") ?: "" } catch (_: Throwable) { "" }
        fun int(k: String, d: Int): Int = try { s.getInt(k, d) } catch (_: Throwable) { d }
        i.clips = str("clips_v1"); i.fmtCur = str("fmt_cur"); i.fmtPresets = str("fmt_presets"); i.ctJson = str("ct_json")
        i.theme = int("theme", 0)
        i.vault = readFile(File(ctx.filesDir, "vault_v1.json"))
        i.profiles = readFile(File(ctx.filesDir, "profiles_v1.json"))
        try {
            val files = BackupStore.list(ctx)
            i.backups = files.size
            for (f in files) {
                val t = try { String(AtomicFile(f).readFully(), Charsets.UTF_8) } catch (_: Throwable) { null }
                if (t == null || BackupCodec.validate(t) != BackupCodec.OK) i.badBackups++
            }
        } catch (_: Throwable) { }
        i.storageBytes = fileSize(ctx.filesDir) + fileSize(File(ctx.applicationInfo.dataDir, "shared_prefs/jabra.xml"))
        i.schemaV = int("schema_v", 1)
        i.crashPanel = str("crash_p"); i.crashN = int("crash_n", 0)
        i.crashT = try { s.getLong("crash_t", 0L) } catch (_: Throwable) { 0L }
        i.now = System.currentTimeMillis()
        try {   // v0.10.7: external backup state
            i.extSet = str("bk_ext_uri").isNotEmpty(); i.extAccessOk = BackupStore.extAccessOk(ctx); i.extLast = str("bk_ext_last")
            i.vaultCount = VaultStore.countOnDisk(ctx)
        } catch (_: Throwable) { }
        return i
    }

    /** One safe repair. Returns a short message. Background Thread only. */
    fun fix(ctx: Context, id: String): String {
        val s = sp(ctx)
        when (id) {
            "clips" -> {
                val raw = s.getString("clips_v1", "") ?: ""
                val pre = BackupStore.backupNow(ctx)
                if (!pre.startsWith("Backup saved")) return "Repair stopped: could not save a backup copy first. Nothing changed."
                val fixed = HealthCheck.repairClips(raw)
                return if (s.edit().putString("clips_v1", fixed).commit()) "Clipboard repaired (a backup copy was saved first)." else "Repair failed while saving. Backup copy exists."
            }
            "vault", "profiles" -> {
                val name = if (id == "vault") "vault_v1.json" else "profiles_v1.json"
                val good = BackupStore.fileFromBackup(ctx, name) { t -> if (id == "vault") VaultList.parse(t) != null else ProfileCodec.parse(t) != null }
                    ?: return "No good backup with this file was found. The damaged file was left as it is."
                return if (BackupStore.replaceFile(ctx, name, good)) "Restored from the newest good backup. The damaged file was kept as $name.damaged. Close and reopen the keyboard."
                else "Repair failed while saving. Nothing changed."
            }
            "crash" -> { PanelGuard.reset(ctx); return "Panel switched on again." }
        }
        return "Nothing to do."
    }

    /** v0.5.3: runs once per app-screen start on a background Thread. Backup first, only if a value must be removed; schema_v is written LAST. */
    fun migrate(ctx: Context) {
        val app = ctx.applicationContext
        Thread {
            try {
                val s = sp(app)
                val from = try { s.getInt("schema_v", 1) } catch (_: Throwable) { 1 }
                if (from >= Migrate.CURRENT) return@Thread
                val plan = Migrate.plan(s.all, from)
                if (plan.changes()) {
                    val pre = BackupStore.backupNow(app)
                    if (!pre.startsWith("Backup saved")) return@Thread   // no safety copy = no change; tried again next start
                    val e = s.edit()
                    for (k in plan.remove) e.remove(k)
                    if (!e.commit()) return@Thread
                }
                s.edit().putInt("schema_v", plan.to).apply()   // LAST
            } catch (_: Throwable) { }
        }.start()
    }
}

/** The one keyboard hook (MgbIme.showPanel). Counts consecutive failures of one panel; blocks it for 24 h after 2. */
object PanelGuard {
    private fun sp(ctx: Context) = ctx.getSharedPreferences("jabra", Context.MODE_PRIVATE)

    fun blocked(ctx: Context, name: String): Boolean = try {
        val s = sp(ctx)
        val n = s.getInt("crash_n", 0)
        n >= HealthCheck.CRASH_LIMIT && HealthCheck.blocked(s.getString("crash_p", "") ?: "", n, s.getLong("crash_t", 0L), name, System.currentTimeMillis())
    } catch (_: Throwable) { false }

    fun failed(ctx: Context, name: String) {
        try {
            val s = sp(ctx)
            val n = HealthCheck.nextCount(s.getString("crash_p", "") ?: "", s.getInt("crash_n", 0), name)
            s.edit().putString("crash_p", name).putInt("crash_n", n).putLong("crash_t", System.currentTimeMillis()).apply()
        } catch (_: Throwable) { }
    }

    /** A panel opened fine: forget an earlier failure of THIS panel (one string read; writes only when there is something to forget). */
    fun ok(ctx: Context, name: String) {
        try {
            val s = sp(ctx)
            if (name == (s.getString("crash_p", "") ?: "")) s.edit().remove("crash_p").remove("crash_n").remove("crash_t").apply()
        } catch (_: Throwable) { }
    }

    /** v0.5.7: one warning line for the app screen when a panel is switched off right now ("" = nothing to say). */
    fun note(ctx: Context): String = try {
        val s = sp(ctx)
        val n = s.getInt("crash_n", 0); val p = s.getString("crash_p", "") ?: ""
        if (p.isNotEmpty() && n >= HealthCheck.CRASH_LIMIT && HealthCheck.blocked(p, n, s.getLong("crash_t", 0L), p, System.currentTimeMillis()))
            "Warning: the keyboard panel \"$p\" is switched off after repeated crashes. Open Data & Backup > Jabra Health Check to switch it on again."
        else ""
    } catch (_: Throwable) { "" }

    fun reset(ctx: Context) { try { sp(ctx).edit().remove("crash_p").remove("crash_n").remove("crash_t").apply() } catch (_: Throwable) { } }
}
