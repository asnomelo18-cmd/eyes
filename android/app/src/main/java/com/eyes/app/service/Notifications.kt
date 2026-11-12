package com.eyes.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.eyes.app.MainActivity

object Notifications {
    const val CHANNEL = "eyes-share"
    const val ID = 1

    fun ensureChannel(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Partage caméra et micro", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Visible tant qu'Eyes est prêt à partager ou partage"
                    setShowBadge(false)
                },
            )
        }
    }

    fun build(context: Context, sharingWith: String?): Notification {
        val open = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val text = if (sharingWith == null) "Eyes est prêt à partager" else "Caméra et micro partagés avec $sharingWith"
        return Notification.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle("Eyes")
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(open)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()
            .apply { flags = flags or Notification.FLAG_NO_CLEAR or Notification.FLAG_ONGOING_EVENT }
    }

    fun update(context: Context, sharingWith: String?) {
        context.getSystemService(NotificationManager::class.java).notify(ID, build(context, sharingWith))
    }
}
