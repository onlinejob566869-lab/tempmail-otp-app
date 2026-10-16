package com.tempmail.otp.util

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat

/**
 * Posts a local notification when an OTP is received.
 */
object NotificationHelper {

    private const val CHANNEL_ID = "otp_notifications"
    private const val NOTIFICATION_ID = 1001

    fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "OTP Notifications",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notifies when a new OTP code is received"
            }
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    fun showOtpNotification(context: Context, otp: String, from: String) {
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("OTP Received: $otp")
            .setContentText("From: $from — code copied to clipboard")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, builder.build())
    }
}
