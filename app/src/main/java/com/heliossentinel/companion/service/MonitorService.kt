package com.heliossentinel.companion.service

import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.heliossentinel.companion.HeliosApp
import com.heliossentinel.companion.R
import com.heliossentinel.companion.notifications.AlertNotifier

/**
 * Optional foreground service that keeps polling (and therefore logging and
 * alerting) while the app is in the background — e.g. to log a whole day of
 * tracking for the DS2001 dataset. Its only job is to keep the process alive
 * and tell the repository to stay active; the polling itself lives in
 * TrackerRepository.
 */
class MonitorService : Service() {

    private val repository get() = (application as HeliosApp).trackerRepository

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            repository.setBackgroundMonitoring(false)
            stopSelf()
            return START_NOT_STICKY
        }

        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, MonitorService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification = NotificationCompat.Builder(this, AlertNotifier.CHANNEL_MONITORING)
            .setSmallIcon(R.drawable.ic_stat_sun)
            .setContentTitle("Monitoring Helios Sentinel")
            .setContentText("Logging readings and watching for problems in the background.")
            .setContentIntent(AlertNotifier.openAppIntent(this))
            .addAction(0, "Stop", stopIntent)
            .setOngoing(true)
            .build()

        val started = runCatching {
            ServiceCompat.startForeground(
                this, NOTIFICATION_ID, notification,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
            )
        }.isSuccess

        if (!started) {
            // e.g. Android refused a start from the background — fail quietly instead of crashing.
            stopSelf()
            return START_NOT_STICKY
        }
        repository.setBackgroundActive(true)
        // Not sticky: Android 12+ forbids restarting a foreground service from the background.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        repository.setBackgroundActive(false)
        super.onDestroy()
    }

    companion object {
        private const val NOTIFICATION_ID = 42
        private const val ACTION_STOP = "com.heliossentinel.companion.action.STOP_MONITORING"

        /** Must be called while the app is in the foreground. */
        fun start(context: Context) {
            runCatching {
                ContextCompat.startForegroundService(context, Intent(context, MonitorService::class.java))
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, MonitorService::class.java))
        }
    }
}
