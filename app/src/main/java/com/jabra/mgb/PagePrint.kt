package com.jabra.mgb

import android.app.Activity
import android.content.Context
import android.print.PrintAttributes
import android.print.PrintManager

/**
 * PRINT / SAVE AS PDF (0.0.420, NEW): Menu > Page actions > "Print / Save as PDF" hands the page of the open tab to Android's own print screen.
 * There the person can print, or choose "Save as PDF" (the file name is the page title) and pick where to store it: share, Drive, Files.
 * Uses WebView.createPrintDocumentAdapter + PrintManager: no permission, no file written by the app, nothing stored by the app.
 * Not for the speed dial, extension pages or a tab that is suspended / not loaded yet (its WebView is empty): a short toast says so.
 * Rollback: remove the menu row (MainActivity "Print / Save as PDF" + MenuCustom entry) and this file.
 */
object PagePrint {
    fun print(act: Activity, t: TabManager.Tab) {
        if (t.home || t.isExt || t.suspended || t.pending != null || !t.url.startsWith("http") && !t.savedPage) {
            Util.toast(act, "Open the page first, then print it")
            return
        }
        try {
            val pm = act.getSystemService(Context.PRINT_SERVICE) as? PrintManager
            if (pm == null) { Util.toast(act, "Printing is not available on this phone"); return }
            val raw = (t.web.title?.takeIf { it.isNotBlank() } ?: t.title.takeIf { it.isNotBlank() } ?: Util.host(t.url).ifBlank { "page" })
            val name = raw.replace(Regex("[\\\\/:*?\"<>|\\r\\n]+"), "_").trim().take(80).ifBlank { "page" }
            pm.print(name, t.web.createPrintDocumentAdapter(name), PrintAttributes.Builder().build())
        } catch (e: Throwable) {
            Diag.w("print", "could not start: " + (e.message ?: e.javaClass.simpleName), "")
            Util.toast(act, "Could not open the print screen")
        }
    }
}
