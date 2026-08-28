package com.example.nsfwshield.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationManagerCompat
import com.example.nsfwshield.ProtectionService
import com.example.nsfwshield.Prefs
import com.example.nsfwshield.R

/**
 * On boot, if protection was previously enabled, remind the user that MediaProjection
 * consent must be re-granted after a reboot (Android revokes the capture token on reboot).
 *
 * We do NOT auto-start capture — that is impossible without the user re-confirming the
 * system dialog. We show a notification directing the user to open the app.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        if (!Prefs.isProtectionOn(context)) return

        val nm = NotificationManagerCompat.from(context)
        if (nm.areNotificationsEnabled()) {
            val notif = androidx.core.app.NotificationCompat.Builder(context, ProtectionService.CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_shield_foreground)
                .setContentTitle(context.getString(R.string.boot_notification_title))
                .setContentText(context.getString(R.string.boot_notification_text))
                .setPriority(androidx.core.app.NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .build()
            nm.notify(ProtectionService.BOOT_NOTIF_ID, notif)
        }
    }
}
