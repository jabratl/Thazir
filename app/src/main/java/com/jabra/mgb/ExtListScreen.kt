package com.jabra.mgb

import android.app.Activity
import android.app.Dialog
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * S1: Settings > Extensions. Full-screen Dialog (same style as SettingsScreen, no manifest entry).
 *  - Add: file picker (.zip / .crx) -> ExtRegistry.prepare -> permission summary dialog -> commit or discard
 *  - List: name, version, short info, on/off switch, remove (with confirm)
 *  - Only the list and the flags live here. Running extension code is S2+ (an installed extension does nothing yet).
 */
object ExtListScreen {
    private const val TAG = "MGB.extlist"
    private var current: Screen? = null

    fun open(host: Activity) {
        if (host.isFinishing || host.isDestroyed) return
        close()
        try {
            ExtRegistry.init(host)
            val s = Screen(host)
            current = s
            s.show()
        } catch (e: Exception) {
            Log.e(TAG, "open failed", e)
            current = null
            Util.toast(host, "Could not open Extensions")
        }
    }

    fun close() {
        val s = current ?: return
        current = null
        try { s.dismiss() } catch (_: Exception) { }
    }

    /** Short count text for the Settings row, e.g. "2 on / 3". Empty when nothing is installed. */
    fun summary(host: Activity): String {
        return try {
            ExtRegistry.init(host)
            val all = ExtRegistry.all()
            if (all.isEmpty()) "" else "${all.count { it.enabled }} on / ${all.size}"
        } catch (e: Exception) { "" }
    }

    private class Screen(private val act: Activity) :
        Dialog(act, com.google.android.material.R.style.Theme_Material3_DayNight_NoActionBar) {

        private val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        private val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
        private var body: LinearLayout? = null
        private var busy = false
        private var query = "" // S10: search box filter (by name)

        private fun dp(v: Int) = Util.dp(act, v)

        init {
            setOnDismissListener { if (current === this) current = null }
        }

        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)
            build()
            window?.let {
                it.setLayout(MATCH, MATCH)
                it.setBackgroundDrawable(ColorDrawable(act.getColor(R.color.mg_bg)))
            }
        }

        private fun round(color: Int, r: Int) = GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(r).toFloat()
        }

        private fun build() {
            val root = LinearLayout(act).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(act.getColor(R.color.mg_bg))
            }
            val head = LinearLayout(act).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(4), 0, dp(4), 0)
            }
            head.addView(ImageView(act).apply {
                setImageResource(R.drawable.ic_back)
                setColorFilter(act.getColor(R.color.mg_text))
                setPadding(dp(10), dp(10), dp(10), dp(10))
                contentDescription = "Back"
                setOnClickListener { dismiss() }
            }, LinearLayout.LayoutParams(dp(44), dp(48)))
            head.addView(LinearLayout(act).apply { // S14: title + Beta pill, like the Thazir tray look
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(TextView(act).apply {
                    text = "Manage Extensions"
                    textSize = 19f
                    typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                    setTextColor(act.getColor(R.color.mg_text))
                })
                addView(TextView(act).apply {
                    text = "Beta"
                    textSize = 10f
                    setTextColor(act.getColor(R.color.mg_text_dim))
                    background = round(act.getColor(R.color.mg_surface_variant), 8)
                    setPadding(dp(7), dp(2), dp(7), dp(2))
                }, LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = dp(8) })
            }, LinearLayout.LayoutParams(0, WRAP, 1f))
            head.addView(ImageView(act).apply {
                setImageResource(R.drawable.ic_add)
                setColorFilter(act.getColor(R.color.mg_text))
                setPadding(dp(10), dp(10), dp(10), dp(10))
                contentDescription = "Add extension"
                setOnClickListener { pick() }
            }, LinearLayout.LayoutParams(dp(44), dp(48)))
            root.addView(head, LinearLayout.LayoutParams(MATCH, dp(56)))

            // S10: search box (like the Thazir Extensions tray); filters the list below by name
            root.addView(android.widget.EditText(act).apply {
                hint = "Search extensions"
                textSize = 14f
                maxLines = 1
                isSingleLine = true
                setTextColor(act.getColor(R.color.mg_text))
                setHintTextColor(act.getColor(R.color.mg_text_dim))
                background = round(act.getColor(R.color.mg_surface), 22)
                setPadding(dp(16), 0, dp(16), 0)
                compoundDrawablePadding = dp(10)
                act.getDrawable(R.drawable.ic_search)?.mutate()?.let { d ->
                    d.setTint(act.getColor(R.color.mg_text_dim))
                    d.setBounds(0, 0, dp(20), dp(20))
                    setCompoundDrawablesRelative(d, null, null, null)
                }
                addTextChangedListener(object : android.text.TextWatcher {
                    override fun afterTextChanged(s: android.text.Editable?) { query = s?.toString().orEmpty().trim(); refill() }
                    override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                    override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                })
            }, LinearLayout.LayoutParams(MATCH, dp(44)).apply { marginStart = dp(12); marginEnd = dp(12); bottomMargin = dp(6) })

            // S9: on wide screens (1280 px / tablet / landscape) the list stays 640 dp wide and centered
            val cap = dp(640)
            val col = object : LinearLayout(act) {
                override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                    val w = View.MeasureSpec.getSize(widthMeasureSpec)
                    super.onMeasure(
                        if (w > cap) View.MeasureSpec.makeMeasureSpec(cap, View.MeasureSpec.EXACTLY) else widthMeasureSpec,
                        heightMeasureSpec)
                }
            }.apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), dp(4), dp(12), dp(24))
            }
            body = col
            root.addView(ScrollView(act).apply {
                isFillViewport = true
                addView(col, android.widget.FrameLayout.LayoutParams(MATCH, WRAP, Gravity.CENTER_HORIZONTAL))
            }, LinearLayout.LayoutParams(MATCH, 0, 1f))
            setContentView(root)
            fill()
        }

        /** S14b: rebuild after the current touch / click / text event is finished. Calling fill() inside the event removed views that were
         *  still being drawn (NullPointerException in ViewGroup.dispatchDraw, 0.0.109). */
        private fun refill() {
            val b = body ?: return
            b.post { if (isShowing) fill() }
        }

        private fun fill() {
            val col = body ?: return
            col.removeAllViews()
            val all = ExtTray.ordered(act, ExtRegistry.all()) // S12: same order as the tray
            val list = if (query.isEmpty()) all else all.filter { it.name.contains(query, ignoreCase = true) }
            col.addView(TextView(act).apply { // S14
                text = "INSTALLED"
                textSize = 11f
                letterSpacing = 0.08f
                setTextColor(act.getColor(R.color.mg_text_dim))
                setPadding(dp(8), dp(8), dp(6), dp(8))
            })
            if (list.isEmpty()) {
                col.addView(TextView(act).apply {
                    text = if (all.isEmpty()) "No extensions installed" else "No match"
                    textSize = 14f
                    gravity = Gravity.CENTER
                    setTextColor(act.getColor(R.color.mg_text_dim))
                }, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(28) })
            }
            for (e in list) col.addView(card(e), LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(4) })
            col.addView(TextView(act).apply {
                text = "Add from a .zip or .crx file. Chrome Web Store install is not available. Tap = details, hold = remove."
                textSize = 12f
                setTextColor(act.getColor(R.color.mg_text_dim))
                setPadding(dp(6), dp(10), dp(6), dp(4))
            })
            col.addView(TextView(act).apply {
                text = "Add extension"
                textSize = 15f
                gravity = Gravity.CENTER
                setTextColor(act.getColor(R.color.mg_on_primary))
                background = round(act.getColor(R.color.mg_primary), 24)
                isClickable = true
                isFocusable = true
                setOnClickListener { pick() }
            }, LinearLayout.LayoutParams(MATCH, dp(48)).apply { topMargin = dp(6) })
        }

        private fun toggle(e: ExtRegistry.Info, on: Boolean) {
            val why = if (on) ExtSafety.blockReason(e) else null // S8: a blocked extension cannot be turned on
            if (why != null) { Util.toast(act, why); refill(); return }
            if (!ExtRegistry.setEnabled(e.id, on)) Util.toast(act, "Could not change")
            ExtApi.sync(act) // S5: stop / wake its background worker, alarms
            refill()
        }

        /** S14: flat row like the Thazir tray: icon, name, On / Off (tap = switch), grip. Tap row = details, hold = remove. */
        private fun card(e: ExtRegistry.Info): View {
            val c = LinearLayout(act).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = round(act.getColor(R.color.mg_surface), 14)
                setPadding(dp(14), dp(8), dp(4), dp(8))
            }
            c.tag = e.id
            val sz = dp(28)
            val bmp = ExtTray.iconOf(e)
            val iconV: View = if (bmp != null) ImageView(act).apply { setImageBitmap(bmp); scaleType = ImageView.ScaleType.FIT_CENTER }
            else TextView(act).apply {
                text = e.name.trim().take(1).uppercase().ifEmpty { "?" }
                textSize = 14f
                gravity = Gravity.CENTER
                setTextColor(0xFFFFFFFF.toInt())
                background = round(ExtTray.tint(e.id), 8)
            }
            iconV.alpha = if (e.enabled) 1f else 0.45f
            c.addView(iconV, LinearLayout.LayoutParams(sz, sz))
            c.addView(TextView(act).apply {
                text = e.name
                textSize = 15f
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                setTextColor(act.getColor(if (e.enabled) R.color.mg_text else R.color.mg_text_dim))
            }, LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginStart = dp(12) })
            c.addView(TextView(act).apply {
                text = if (e.enabled) "On" else "Off"
                textSize = 13f
                setTextColor(act.getColor(if (e.enabled) R.color.mg_primary else R.color.mg_text_dim))
                setPadding(dp(12), dp(10), dp(12), dp(10))
                isClickable = true
                isFocusable = true
                contentDescription = "Turn " + e.name + " on or off"
                setOnClickListener { toggle(e, !e.enabled) }
            })
            if (query.isEmpty()) c.addView(dragHandle(c, e), LinearLayout.LayoutParams(dp(32), dp(40))) // S12
            c.isClickable = true
            c.isFocusable = true
            c.setOnClickListener { details(e) }
            c.setOnLongClickListener { askRemove(e); true }
            return c
        }

        /** S12: grip at the left of a card. Drag up / down, release = card takes the place under it, order saved (shared with the tray). */
        @android.annotation.SuppressLint("ClickableViewAccessibility")
        private fun dragHandle(c: View, e: ExtRegistry.Info): ImageView = ImageView(act).apply {
            setImageResource(R.drawable.ic_list)
            setColorFilter(act.getColor(R.color.mg_text_dim))
            setPadding(dp(6), dp(8), dp(6), dp(8))
            contentDescription = "Drag to reorder " + e.name
            var startY = 0f
            setOnTouchListener { v, ev ->
                val col = c.parent as? LinearLayout ?: return@setOnTouchListener false
                when (ev.actionMasked) {
                    android.view.MotionEvent.ACTION_DOWN -> {
                        startY = ev.rawY
                        c.elevation = dp(8).toFloat()
                        v.parent.requestDisallowInterceptTouchEvent(true)
                        true
                    }
                    android.view.MotionEvent.ACTION_MOVE -> { c.translationY = ev.rawY - startY; true }
                    android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                        v.parent.requestDisallowInterceptTouchEvent(false)
                        val others = (0 until col.childCount).map { col.getChildAt(it) }.filter { it.tag is String && it !== c }
                        val center = c.top + c.height / 2f + c.translationY
                        val idx = others.count { it.top + it.height / 2f < center }
                        val ids = others.map { it.tag as String }.toMutableList()
                        ids.add(idx, e.id)
                        c.translationY = 0f
                        c.elevation = 0f
                        if (ev.actionMasked == android.view.MotionEvent.ACTION_UP) ExtTray.saveOrder(act, ids)
                        refill()
                        true
                    }
                    else -> false
                }
            }
        }

        private fun kinds(e: ExtRegistry.Info): String {
            val k = ArrayList<String>()
            if (e.contentScriptCount > 0) k.add("page script")
            if (e.popup != null) k.add("popup")
            if (e.options != null) k.add("options")
            if (e.background != null) k.add("background")
            return if (k.isEmpty()) "no UI" else k.joinToString(", ")
        }

        private fun askRemove(e: ExtRegistry.Info) {
            MaterialAlertDialogBuilder(act)
                .setMessage("Remove " + e.name + "? Its files are deleted.")
                .setPositiveButton("Remove") { _, _ ->
                    if (!ExtRegistry.remove(e.id)) Util.toast(act, "Could not remove all files")
                    ChromeBridge.removeExtData(act, e.id) // S3: wipe its storage
                    ExtDark.forget(act, e.id) // 0.0.424
                    ExtFit.forget(act, e.id) // 0.0.427
                    ExtApi.sync(act) // S5: worker, alarms, menus of the removed extension
                    refill()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        private fun details(e: ExtRegistry.Info) {
            // 0.0.424: message + a visible "Dark mode" row (tap = Auto > Always dark > Never dark, ExtDark)
            val col = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(8), dp(24), 0) }
            col.addView(TextView(act).apply {
                text = "Version " + e.version + " (manifest v" + e.manifestVersion + ")\n\n" + permText(e)
                textSize = 14f
                setTextColor(act.getColor(R.color.mg_text))
            })
            col.addView(TextView(act).apply {
                text = "DARK MODE"
                textSize = 11f
                letterSpacing = 0.08f
                setTextColor(act.getColor(R.color.mg_primary))
                setPadding(0, dp(16), 0, dp(4))
            })
            val dk = TextView(act).apply {
                textSize = 15f
                setTextColor(act.getColor(R.color.mg_text))
                setPadding(0, dp(8), 0, dp(8))
                isClickable = true
                isFocusable = true
                fun upd() { text = ExtDark.label(ExtDark.mode(act, e.id)) + "  (tap to change)" }
                upd()
                setOnClickListener {
                    ExtDark.cycle(act, e.id)
                    upd()
                    BookmarksScreen.tabsRef?.applyDarkPages() // open extension tabs follow at once (reload for Always dark)
                }
            }
            col.addView(dk)
            col.addView(TextView(act).apply {
                text = "Auto follows the app theme. Always dark also darkens a light popup. Never dark keeps the extension's own colours. Close and reopen the popup to see it."
                textSize = 12f
                setTextColor(act.getColor(R.color.mg_text_dim))
            })
            // 0.0.427 (S2): Page width row (tap = Auto > Phone width > Desktop width, ExtFit)
            col.addView(TextView(act).apply {
                text = "PAGE WIDTH"
                textSize = 11f
                letterSpacing = 0.08f
                setTextColor(act.getColor(R.color.mg_primary))
                setPadding(0, dp(16), 0, dp(4))
            })
            col.addView(TextView(act).apply {
                textSize = 15f
                setTextColor(act.getColor(R.color.mg_text))
                setPadding(0, dp(8), 0, dp(8))
                isClickable = true
                isFocusable = true
                fun upd() { text = ExtFit.label(ExtFit.mode(act, e.id)) + "  (tap to change)" }
                upd()
                setOnClickListener {
                    ExtFit.cycle(act, e.id)
                    upd()
                    ContentInjector.refresh(act) // open extension tabs: reload to see it
                }
            })
            col.addView(TextView(act).apply {
                text = "Auto fits a popup or page to the phone. Phone width always uses the phone width. Desktop width shows the wide page scaled down. Close and reopen the popup to see it."
                textSize = 12f
                setTextColor(act.getColor(R.color.mg_text_dim))
            })
            // 0.0.433 (S8): manifest "commands": description + shortcut text; a tap runs it (_execute_action = open the popup, others = commands.onCommand)
            if (e.enabled) {
                val cmds = ExtManifest.commands(e)
                if (cmds.length() > 0) {
                    col.addView(TextView(act).apply {
                        text = "COMMANDS"
                        textSize = 11f
                        letterSpacing = 0.08f
                        setTextColor(act.getColor(R.color.mg_primary))
                        setPadding(0, dp(16), 0, dp(4))
                    })
                    for (i in 0 until cmds.length()) {
                        val c = cmds.getJSONObject(i)
                        val nm = c.optString("name")
                        val label = c.optString("description").ifEmpty { nm }
                        val key = c.optString("shortcut")
                        col.addView(TextView(act).apply {
                            text = label + (if (key.isNotEmpty()) "  [" + key + "]" else "") + "  (tap to run)"
                            textSize = 15f
                            setTextColor(act.getColor(R.color.mg_text))
                            setPadding(0, dp(8), 0, dp(8))
                            isClickable = true
                            isFocusable = true
                            setOnClickListener { ExtManifest.runCommand(act, e, nm) }
                        })
                    }
                }
            }
            // 0.0.437 (S12): COMPATIBILITY block: old WebView warning, the 3 switches in one line, chrome.* calls Thazir does not support (list shows 5, tap for all)
            try {
                col.addView(TextView(act).apply {
                    text = "COMPATIBILITY"
                    textSize = 11f
                    letterSpacing = 0.08f
                    setTextColor(act.getColor(R.color.mg_primary))
                    setPadding(0, dp(16), 0, dp(4))
                })
                val wvm = ExtProbe.webViewMajor()
                val head = StringBuilder("Dark mode: " + ExtDark.label(ExtDark.mode(act, e.id)) + "\nPage width: " + ExtFit.label(ExtFit.mode(act, e.id)) +
                    "\nMatch page theme: " + ExtTheme.label(ExtTheme.mode(act)) + " (all extensions)\nCSS compatibility: " + ExtCssCompat.label(ExtCssCompat.mode(act)) + " (all extensions)")
                if (wvm in 1..119) head.append("\n\nAndroid System WebView is old (version " + wvm + "). This extension may look wrong. Update Android System WebView in Play Store.")
                col.addView(TextView(act).apply {
                    text = head.toString()
                    textSize = 13f
                    setTextColor(act.getColor(R.color.mg_text))
                })
                val gaps = ExtGaps.forExt(e.id)
                col.addView(TextView(act).apply {
                    textSize = 13f
                    setTextColor(act.getColor(R.color.mg_text_dim))
                    setPadding(0, dp(8), 0, dp(8))
                    var all = false
                    fun upd() {
                        text = if (gaps.isEmpty()) "No unsupported chrome.* call recorded for this extension."
                        else "chrome.* calls Thazir does not support (that call fails, the extension keeps running):\n" +
                            (if (all || gaps.size <= 5) gaps else gaps.take(5)).joinToString("\n") { "  " + it } +
                            (if (!all && gaps.size > 5) "\n  ... " + (gaps.size - 5) + " more (tap to show all)" else "")
                    }
                    upd()
                    if (gaps.size > 5) { isClickable = true; setOnClickListener { all = !all; upd() } }
                })
            } catch (_: Throwable) { }
            MaterialAlertDialogBuilder(act)
                .setTitle(e.name)
                .setView(ScrollView(act).apply { addView(col) })
                .setPositiveButton("Close", null)
                .apply {
                    // S4: popup / options page of the extension, opened in an extension window over this screen
                    val pop = e.popup
                    val opt = e.options
                    if (e.enabled && pop != null) setNeutralButton("Open popup") { _, _ -> openPage(e, pop) }
                    else if (e.enabled && opt != null) setNeutralButton("Options") { _, _ -> openOptionsOf(e) }
                    if (e.enabled && pop != null && opt != null) setNegativeButton("Options") { _, _ -> openOptionsOf(e) }
                }
                .show()
        }

        private fun openOptionsOf(e: ExtRegistry.Info) { // 0.0.432 (S7): options_ui.open_in_tab
            if (!ExtManifest.openOptions(act, e)) Util.toast(act, "Could not open the options page")
        }

        private fun openPage(e: ExtRegistry.Info, rel: String) {
            if (!ExtScreen.openExtPage(act, e.id, rel)) Util.toast(act, "Could not open " + rel)
        }

        private fun pick() {
            if (busy) return
            BrowserExtras.pickExtension { uri -> install(uri) }
        }

        private fun install(uri: Uri) {
            busy = true
            Util.toast(act, "Reading the file...")
            ExtRegistry.prepare(act, uri) { r ->
                busy = false
                val st = r.getOrNull()
                if (st == null) {
                    fail(r.exceptionOrNull()?.message)
                    return@prepare
                }
                if (!isShowing) { // screen was closed while reading
                    ExtRegistry.discard(st)
                    return@prepare
                }
                val why = ExtSafety.blockReason(st.info) // S8: manifest v2 / nativeMessaging are refused before the permission dialog
                if (why != null) {
                    ExtRegistry.discard(st)
                    Diag.w("ext", "install refused", st.info.name + ": " + why)
                    fail(st.info.name + " was not added.\n\n" + why)
                    return@prepare
                }
                confirmInstall(st)
            }
        }

        private fun fail(msg: String?) {
            MaterialAlertDialogBuilder(act)
                .setTitle("Could not add extension")
                .setMessage(msg ?: "Unknown error")
                .setPositiveButton("OK", null)
                .show()
        }

        private fun confirmInstall(st: ExtRegistry.Staged) {
            val i = st.info
            val old = st.existing
            val title = if (old != null) "Update " + i.name + "?" else "Add " + i.name + "?"
            val head = if (old != null) "v" + old.version + " -> v" + i.version + "\n\n" else "Version " + i.version + "\n\n"
            var done = false
            MaterialAlertDialogBuilder(act)
                .setTitle(title)
                .setMessage(head + permText(i))
                .setPositiveButton(if (old != null) "Update" else "Add") { _, _ ->
                    done = true
                    ExtRegistry.commit(st) { r ->
                        if (r.isSuccess) {
                            Util.toast(act, if (old != null) "Extension updated" else "Extension added")
                            ExtApi.sync(act) // S5: runtime.onInstalled / worker restart
                            if (isShowing) fill()
                        } else {
                            fail(r.exceptionOrNull()?.message)
                        }
                    }
                }
                .setNegativeButton("Cancel", null)
                .setOnDismissListener { if (!done) ExtRegistry.discard(st) }
                .show()
        }

        /** Plain words for what the extension asks for. Unknown permission names are shown as they are. */
        private fun permText(e: ExtRegistry.Info): String {
            val sb = StringBuilder()
            val hosts = e.hostPermissions
            if (hosts.isEmpty()) {
                sb.append("Sites: none asked\n")
            } else if (hosts.any { it == "<all_urls>" || it == "*://*/*" || it == "http://*/*" || it == "https://*/*" }) {
                sb.append("Sites: all websites\n")
            } else {
                sb.append("Sites:\n")
                hosts.take(8).forEach { sb.append("  ").append(it).append('\n') }
                if (hosts.size > 8) sb.append("  + ").append(hosts.size - 8).append(" more\n")
            }
            if (e.contentScriptCount > 0) sb.append("\nRuns scripts inside pages (").append(e.contentScriptCount).append(")\n")
            if (e.permissions.isNotEmpty()) {
                sb.append("\nPermissions:\n")
                e.permissions.forEach { sb.append("  ").append(permLabel(it)).append('\n') }
            }
            val why = ExtSafety.blockReason(e) // S8: shown for an already installed (turned off) blocked extension
            if (why != null) sb.append("\nNot supported: ").append(why)
            val ign = ExtSafety.ignored(e)
            if (ign.isNotEmpty()) sb.append("\nNote: ").append(ign.joinToString(", ")).append(" is accepted but does nothing in MGB yet.")
            return sb.toString().trimEnd()
        }

        private fun permLabel(p: String): String = when (p) {
            "storage" -> "storage (save its settings)"
            "tabs" -> "tabs (see open tabs)"
            "activeTab" -> "activeTab (current page when you use it)"
            "scripting" -> "scripting (run scripts in pages)"
            "cookies" -> "cookies (read site cookies)"
            "downloads" -> "downloads (start downloads)"
            "webRequest", "webRequestBlocking" -> "$p (watch network requests)"
            "declarativeNetRequest" -> "declarativeNetRequest (block or change requests)"
            "contextMenus" -> "contextMenus (menu items, not shown in MGB yet)"
            "notifications" -> "notifications (show notifications)"
            "alarms" -> "alarms (timers)"
            "clipboardWrite" -> "clipboardWrite (copy text)"
            "clipboardRead" -> "clipboardRead (read your clipboard)"
            "history" -> "history (browsing history)"
            "bookmarks" -> "bookmarks"
            "nativeMessaging" -> "nativeMessaging (not supported)"
            else -> p
        }
    }
}
