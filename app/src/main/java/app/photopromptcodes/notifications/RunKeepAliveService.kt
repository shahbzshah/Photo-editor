package app.photopromptcodes.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import app.photopromptcodes.R

/**
 * Keeps the app's process alive while a run is waiting after the user
 * tapped "Notify me when ready". The WebView doing the work lives in the
 * Activity — this service holds no driver and no WebView. It only raises
 * the process priority (via its ongoing notification) so Android doesn't
 * kill the run when the user leaves the app to do something else.
 *
 * Started only on the user's tap, stopped the moment the run ends
 * (finished, failed, or abandoned). If the process dies anyway, the
 * service is NOT restarted — the run is dead with it, and a phantom
 * "developing" notice would be a lie.
 */
class RunKeepAliveService : Service() {
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureChannel()
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_bell)
            .setContentTitle("Your edit is developing")
            .setContentText("We'll tell you when it's ready — you can leave the app.")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
        // specialUse exists only from API 34; the constant is inlined at
        // compile time, but older runtimes don't enforce the manifest type.
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun ensureChannel() {
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Edit in progress",
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = "Keeps a requested edit alive while it develops." }
        )
    }

    companion object {
        private const val CHANNEL_ID = "run_keep_alive"
        private const val NOTIFICATION_ID = 1002

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, RunKeepAliveService::class.java)
            )
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, RunKeepAliveService::class.java))
        }
    }
}
