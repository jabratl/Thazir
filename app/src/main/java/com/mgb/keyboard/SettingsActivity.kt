package com.mgb.keyboard

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class SettingsActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var updBtn: Button
    private var pending: Updater.Info? = null
    var refreshData: (() -> Unit)? = null   // v0.4.1 Data & Backup page hooks
    var dataMsg: ((String) -> Unit)? = null

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun btn(label: String, onClick: () -> Unit): Button {
        val b = Button(this)
        b.text = label
        b.isAllCaps = false
        b.setOnClickListener { try { onClick() } catch (_: Throwable) { } }
        return b
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(16), dp(16), dp(16), dp(16))

        val title = TextView(this)
        title.text = "Jabra Keyboard"
        title.textSize = 22f
        try {   // v0.5.6: logo next to the title (own drawing, res/drawable/jabra_logo.xml)
            val head = LinearLayout(this)
            head.orientation = LinearLayout.HORIZONTAL
            head.gravity = android.view.Gravity.CENTER_VERTICAL
            val logo = android.widget.ImageView(this)
            logo.setImageResource(R.drawable.jabra_logo)
            logo.contentDescription = "Jabra Keyboard logo"
            logo.layoutParams = LinearLayout.LayoutParams(dp(56), dp(56))
            title.setPadding(dp(12), 0, 0, 0)
            head.addView(logo)
            head.addView(title)
            col.addView(head)
        } catch (_: Throwable) { if (title.parent == null) col.addView(title) }

        val ver = TextView(this)
        ver.text = "Version ${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE})"
        col.addView(ver)
        try {   // v0.5.7: visible warning when the crash guard switched a panel off
            val sp0 = getSharedPreferences("jabra", MODE_PRIVATE)
            val bn = com.mgb.keyboard.typing.HealthCheck.backupNote(sp0.getBoolean("bk_auto", true), sp0.getString("bk_last", "") ?: "", System.currentTimeMillis())   // v0.5.8
            val w = listOf(PanelGuard.note(this), bn).filter { it.isNotEmpty() }.joinToString("\n")
            if (w.isNotEmpty()) { val wt = TextView(this); wt.text = w; wt.setTextColor(0xFFD84315.toInt()); wt.setPadding(0, dp(6), 0, dp(6)); col.addView(wt) }
        } catch (_: Throwable) { }

        col.addView(btn("1. Enable keyboard") {
            startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
        })
        col.addView(btn("2. Choose Jabra Keyboard as current keyboard") {
            (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker()
        })

        val test = EditText(this)
        test.hint = "Type here to test the keyboard"
        col.addView(test)

        try { col.addView(SettingsGroups.build(this)) } catch (_: Throwable) { }   // v0.1.7: settings in 5 groups
        try { HealthRun.migrate(this) } catch (_: Throwable) { }   // v0.5.3: settings migration (background Thread, backup first)
        try { BackupStore.autoIfDue(this) } catch (_: Throwable) { }   // v0.4.0: auto backup once per day (background Thread)

        col.addView(btn("Check for updates") { checkUpdate() })
        col.addView(btn("Update emoji list") { updateEmoji() })   // v0.8.5: only on tap
        col.addView(btn("Use built-in emoji list") { builtInEmoji() })   // v0.8.5

        updBtn = btn("Download & install") { doUpdate() }
        updBtn.visibility = android.view.View.GONE
        col.addView(updBtn)

        status = TextView(this)
        col.addView(status)

        val sv = ScrollView(this)
        sv.addView(col)
        setContentView(sv)
    }

    // ---- v0.4.1: Data & Backup file picker (system picker, no storage permission) ----
    fun exportBackup() {
        try {
            val i = Intent(Intent.ACTION_CREATE_DOCUMENT)
            i.addCategory(Intent.CATEGORY_OPENABLE); i.type = "application/json"
            i.putExtra(Intent.EXTRA_TITLE, "jabra_backup_" + java.text.SimpleDateFormat("yyyyMMdd_HHmm", java.util.Locale.US).format(java.util.Date()) + ".json")
            startActivityForResult(i, 4101)
        } catch (_: Throwable) { dataMsg?.invoke("Could not open the file picker") }
    }

    fun importBackup() {
        try {
            val i = Intent(Intent.ACTION_OPEN_DOCUMENT)
            i.addCategory(Intent.CATEGORY_OPENABLE); i.type = "*/*"
            startActivityForResult(i, 4102)
        } catch (_: Throwable) { dataMsg?.invoke("Could not open the file picker") }
    }

    // v0.10.7 (W2): pick the folder for the copy that survives uninstall (system folder picker, request code 4103, no storage permission)
    fun chooseBackupFolder() {
        try {
            val i = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
            startActivityForResult(i, 4103)
        } catch (_: Throwable) { dataMsg?.invoke("Could not open the folder picker") }
    }

    private fun onFolderPicked(resultCode: Int, data: Intent?) {   // v0.10.7
        try {
            if (resultCode != RESULT_OK) return
            val uri = data?.data ?: return
            val app = applicationContext
            try { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
            catch (_: Throwable) { dataMsg?.invoke("This folder cannot be kept. Choose another folder (not the top of the storage)."); return }
            BackupStore.setExtFolder(app, uri)
            dataMsg?.invoke("Folder set. Saving the first copy...")
            Thread {
                val m = try { BackupStore.copyExternal(app) } catch (_: Throwable) { "External backup failed" }
                runOnUiThread { try { dataMsg?.invoke(m); refreshData?.invoke() } catch (_: Throwable) { } }
            }.start()
        } catch (_: Throwable) { }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        try {
            if (requestCode == 4103) { onFolderPicked(resultCode, data); return }   // v0.10.7
            if ((requestCode != 4101 && requestCode != 4102) || resultCode != RESULT_OK) return
            val uri = data?.data ?: return
            val app = applicationContext
            Thread {
                var m = "Failed"
                try {
                    if (requestCode == 4101) {
                        val text = BackupStore.collect(app)
                        if (text == null) m = "Export failed (could not read data)"
                        else if (text.toByteArray(Charsets.UTF_8).size > com.mgb.keyboard.typing.BackupCodec.MAX_BYTES) m = "Export refused: data is bigger than 5 MB"
                        else {
                            contentResolver.openOutputStream(uri, "wt")!!.use { it.write(text.toByteArray(Charsets.UTF_8)) }
                            m = "Exported."
                        }
                    } else {
                        val bytes = contentResolver.openInputStream(uri)!!.use { it.readBytes() }
                        m = if (bytes.size > com.mgb.keyboard.typing.BackupCodec.MAX_BYTES) "Import stopped: file is bigger than 5 MB. Nothing changed."
                            else BackupStore.importText(app, String(bytes, Charsets.UTF_8))
                    }
                } catch (_: Throwable) { m = if (requestCode == 4101) "Export failed" else "Import failed. Nothing changed." }
                runOnUiThread { try { dataMsg?.invoke(m); refreshData?.invoke() } catch (_: Throwable) { } }
            }.start()
        } catch (_: Throwable) { }
    }

    // v0.8.5 (E6): download a newer Unicode emoji list. Only when tapped. Everything runs on a background Thread.
    private fun updateEmoji() {
        status.text = "Downloading the emoji list..."
        updBtn.visibility = android.view.View.GONE
        Thread {
            var m = "Emoji update failed. Nothing changed."
            try { m = EmojiUpdater.run(applicationContext) } catch (_: Throwable) { }
            runOnUiThread { try { status.text = m } catch (_: Throwable) { } }
        }.start()
    }

    private fun builtInEmoji() {
        Thread {
            var m = "Could not switch the emoji list."
            try { m = EmojiUpdater.useBuiltIn(applicationContext) } catch (_: Throwable) { }
            runOnUiThread { try { status.text = m } catch (_: Throwable) { } }
        }.start()
    }

    private fun checkUpdate() {
        status.text = "Checking..."
        updBtn.visibility = android.view.View.GONE
        Thread {
            var info: Updater.Info? = null
            try { info = Updater.fetchLatest() } catch (_: Throwable) { }
            runOnUiThread {
                try {
                    if (info == null) {
                        status.text = "Could not check (offline, or the GitHub repo is private)."
                    } else if (Updater.isNewer(info)) {
                        pending = info
                        status.text = "New version: ${info.tag}\n${info.notes}"
                        updBtn.visibility = android.view.View.VISIBLE
                    } else {
                        status.text = "You are up to date (${info.tag})."
                    }
                } catch (_: Throwable) { }
            }
        }.start()
    }

    private fun doUpdate() {
        val info = pending ?: return
        status.text = "Downloading..."
        Thread {
            var file: java.io.File? = null
            try {
                val url = info.apkUrl
                if (url != null) file = Updater.download(this, url)
            } catch (e: Throwable) {
                android.util.Log.w("MGB", "update stage 1 failed: " + e.message)
            }
            val f = file
            runOnUiThread {
                var done = false
                try {
                    if (f != null) {
                        val r = Updater.install(this, f)
                        status.text = if (r == Updater.InstallResult.NEED_PERMISSION)
                            "Allow 'Install unknown apps' for Jabra Keyboard, come back and tap Download & install again."
                        else "Installer opened. Tap Install."
                        done = true
                    }
                } catch (e: Throwable) {
                    android.util.Log.w("MGB", "install failed: " + e.message)
                }
                if (!done) {
                    try {
                        status.text = "Could not install here. Opening the release page..."
                        Updater.openPage(this, info.pageUrl) // stage 2 fallback
                    } catch (_: Throwable) { }
                }
            }
        }.start()
    }
}
