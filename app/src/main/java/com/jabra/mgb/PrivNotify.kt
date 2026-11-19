package com.jabra.mgb

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

/**
 * 0.0.302: Chrome-style notification while private (incognito) tabs are open: "Close all Incognito tabs".
 * MainActivity.onTabsChanged calls update(); the button sends a broadcast to Receiver, which runs [closer] (set by the main window).
 * Needs the notification permission (asked on the first download); without it nothing is shown. No foreground service.
 */
object PrivNotify {
    private const val CH = "mgb_private"
    private const val ID = 7004
    private const val ACT = "com.jabra.mgb.CLOSE_PRIVATE"
    /** Set by the main window; null after the process was recreated (private tabs are gone then). */
    @Volatile var closer: (() -> Unit)? = null

    fun update(c: Context, tm: TabManager) {
        val n = tm.allTabs().count { it.priv }
        if (n <= 0) { cancel(c); return }
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(c, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        try {
            val nm = c.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CH) == null)
                nm.createNotificationChannel(NotificationChannel(CH, "Incognito tabs", NotificationManager.IMPORTANCE_LOW))
            val open = PendingIntent.getActivity(c, 3, Intent(c, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val close = PendingIntent.getBroadcast(c, 4, Intent(c, Receiver::class.java).setAction(ACT), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val b = NotificationCompat.Builder(c, CH)
                .setSmallIcon(R.drawable.ic_incognito)
                .setContentTitle(if (n == 1) "1 Incognito tab open" else "$n Incognito tabs open")
                .setContentText("Tap to open Thazir")
                .setContentIntent(open).setOngoing(true).setSilent(true).setOnlyAlertOnce(true)
                .setVisibility(NotificationCompat.VISIBILITY_SECRET)
                .addAction(0, "Close all Incognito tabs", close)
            nm.notify(ID, b.build())
        } catch (_: Exception) { }
    }

    fun cancel(c: Context) { try { (c.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(ID) } catch (_: Exception) { } }

    class Receiver : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent?) {
            if (i?.action != ACT) return
            val f = closer
            if (f == null) cancel(c) else try { f() } catch (_: Exception) { }
        }
    }
}
