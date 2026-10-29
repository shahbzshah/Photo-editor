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
 */
object ResultNotifier {
    private const val CHANNEL_ID = "edit_results"
    private const val NOTIFICATION_ID = 1001

    fun postReady(context: Context, codeName: String) {
        ensureChannel(context)
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pending = PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Your edit is ready")
            .setContentText("$codeName is done — tap to view it.")
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
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
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Edit results",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply { description = "Tells you when a photo edit is ready." }
        manager.createNotificationChannel(channel)
    }
}
