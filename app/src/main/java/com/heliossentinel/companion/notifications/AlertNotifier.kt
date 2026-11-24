package com.heliossentinel.companion.notifications

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.heliossentinel.companion.MainActivity
import com.heliossentinel.companion.R
import com.heliossentinel.companion.domain.Anomaly

/**
 * Posts tracker alerts: going offline (after a few failed polls, once until
 * it reconnects) and sensor anomalies (each one at most every 10 minutes).
 */
class AlertNotifier(private val context: Context) {

    private var offlineNotified = false
    private val lastAnomalyNotifiedAt = mutableMapOf<String, Long>()

    init {
        createChannels(context)
    }

    fun onOnline() {
        if (offlineNotified) {
            NotificationManagerCompat.from(context).cancel(ID_OFFLINE)
            offlineNotified = false
        }
    }

    fun onOffline(consecutiveFailures: Int, reason: String) {
        if (offlineNotified || consecutiveFailures < OFFLINE_AFTER_FAILURES) return
        if (post(ID_OFFLINE, "Tracker offline", "Lost contact with Helios Sentinel: $reason")) {
            offlineNotified = true
        }
    }

    fun onAnomalies(anomalies: List<Anomaly>, nowMs: Long = System.currentTimeMillis()) {
        anomalies.forEach { anomaly ->
            val last = lastAnomalyNotifiedAt[anomaly.key] ?: 0L
            if (nowMs - last >= ANOMALY_COOLDOWN_MS) {
                if (post(ID_ANOMALY_BASE + (anomaly.key.hashCode() and 0xFFF), "Sensor check", anomaly.message)) {
                    lastAnomalyNotifiedAt[anomaly.key] = nowMs
                }
            }
        }
    }

    private fun post(id: Int, title: String, text: String): Boolean {
        // Explicit, in-method permission check (Android 13+ requires runtime consent).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) return false
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return false
        val notification = NotificationCompat.Builder(context, CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_stat_sun)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openAppIntent(context))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        return runCatching { NotificationManagerCompat.from(context).notify(id, notification) }.isSuccess
    }

    companion object {
        const val CHANNEL_ALERTS = "tracker_alerts"
        const val CHANNEL_MONITORING = "tracker_monitoring"
        private const val ID_OFFLINE = 1001
        private const val ID_ANOMALY_BASE = 2000
        private const val OFFLINE_AFTER_FAILURES = 3
        private const val ANOMALY_COOLDOWN_MS = 10 * 60 * 1000L

        fun canPost(context: Context): Boolean {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
            ) return false
            return NotificationManagerCompat.from(context).areNotificationsEnabled()
        }

        fun openAppIntent(context: Context): PendingIntent = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        fun createChannels(context: Context) {
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ALERTS, "Tracker alerts", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "Tracker offline and sensor problems"
                }
            )
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_MONITORING, "Background monitoring", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Shown while the app keeps watching the tracker in the background"
                }
            )
        }
    }
}
