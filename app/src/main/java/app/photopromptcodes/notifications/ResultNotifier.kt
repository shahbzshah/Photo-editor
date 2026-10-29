package app.photopromptcodes.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import app.photopromptcodes.MainActivity
import app.photopromptcodes.R

/**
 * The "your edit is ready" notification, posted when a run finishes after
 * the user asked to be notified. Tapping it just reopens the app — the
 * finished edit is waiting in History / on the result screen.
 *
 * Loud on purpose: HIGH-importance channel + HIGH priority + sound and
 * vibration, so it pops over whatever the user is doing. The v1.8.4
 * channel was IMPORTANCE_DEFAULT (silent, easy to miss entirely) — it is
 * deleted here because a channel's importance can't be raised after
 * creation. The small icon is a flat bell vector: the old one was the
 * adaptive launcher icon, which renders as a blank blob on some phones.
 */
object ResultNotifier {
    private const val CHANNEL_ID = "edit_results_high"
    private const val LEGACY_CHANNEL_ID = "edit_results"
    private const val NOTIFICATION_ID = 1001

    fun notificationsEnabled(context: Context): Boolean =
        NotificationManagerCompat.from(context).areNotificationsEnabled()

    fun postReady(context: Context, codeName: String) {
        post(
            context,
            title = "Your edit is ready",
            text = "$codeName is done — tap to view it."
        )
    }

    /** Lets the user prove the channel works from Settings in two seconds. */
    fun postTest(context: Context) {
        post(
            context,
            title = "Test notification",
            text = "Notifications are working — you'll be told when an edit is ready."
        )
    }

    private fun post(context: Context, title: String, text: String) {
        ensureChannel(context)
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pending = PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_bell)
            .setContentTitle(title)
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
        } catch (_: SecurityException) {
            // Permission was revoked after the user asked — stay silent.
        }
    }

    private fun ensureChannel(context: Context) {
        val manager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(LEGACY_CHANNEL_ID) != null) {
            manager.deleteNotificationChannel(LEGACY_CHANNEL_ID)
        }
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Edit results",
            NotificationManager.IMPORTANCE_HIGH
        ).apply { description = "Tells you when a photo edit is ready." }
        manager.createNotificationChannel(channel)
    }
}
