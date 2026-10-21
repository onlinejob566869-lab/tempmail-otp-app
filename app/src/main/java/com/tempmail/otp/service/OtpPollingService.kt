package com.tempmail.otp.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.tempmail.otp.data.ApiClient

/**
 * Keeps the process alive so the shared [com.tempmail.otp.data.InboxMonitor] can hold its
 * sockets open in the background.
 *
 * The service no longer connects WebSockets itself: sockets, dedupe, retries and OTP
 * notifications all live in the monitor, so the UI and the service cannot steal each
 * other's connection any more (F-06).
 */
class OtpPollingService : Service() {

    companion object {
        const val CHANNEL_ID = "otp_polling_service"
        const val NOTIFICATION_ID = 2001
        const val EXTRA_ADDRESSES = "addresses"

        fun start(context: Context, addresses: List<String>) {
            val intent = Intent(context, OtpPollingService::class.java)
            intent.putExtra(EXTRA_ADDRESSES, addresses.toTypedArray())
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, OtpPollingService::class.java))
        }
    }

    private val monitor by lazy { ApiClient.monitor(this) }

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification("Monitoring inboxes..."))

        val addresses = intent?.getStringArrayExtra(EXTRA_ADDRESSES)?.toList().orEmpty()
        if (addresses.isNotEmpty()) {
            // Idempotent: the UI may already be monitoring these addresses through the same monitor.
            monitor.startAll(addresses)
            updateNotification("Monitoring ${addresses.size} inbox(es)...")
        }

        return START_STICKY
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Background monitoring",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps temp inboxes monitored while the app is closed"
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("TempMail OTP")
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification(text))
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
