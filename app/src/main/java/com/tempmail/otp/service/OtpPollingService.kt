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
import com.tempmail.otp.util.NotificationHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Foreground service that keeps polling inboxes in the background
 * even when the app is closed. Shows a persistent notification.
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

    private val api = ApiClient.api
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val pollingJobs = mutableMapOf<String, Job>()
    private val seenIds = mutableMapOf<String, MutableSet<String>>()

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification("Monitoring inboxes..."))

        intent?.getStringArrayExtra(EXTRA_ADDRESSES)?.let { addresses ->
            for (address in addresses) {
                startPollingInbox(address)
            }
            updateNotification("Monitoring ${addresses.size} inbox(es)...")
        }

        return START_STICKY
    }

    private fun startPollingInbox(address: String) {
        pollingJobs[address]?.cancel()
        seenIds[address] = mutableSetOf()

        pollingJobs[address] = serviceScope.launch {
            while (isActive) {
                try {
                    val response = api.listEmails(address)
                    if (response.ok) {
                        for (email in response.emails) {
                            val seen = seenIds[address] ?: mutableSetOf()
                            if (email.id !in seen) {
                                seen.add(email.id)
                                seenIds[address] = seen
                                handleNewEmail(address, email)
                            }
                        }
                    }
                } catch (_: Exception) {}
                delay(5000L)
            }
        }
    }

    private fun handleNewEmail(address: String, email: com.tempmail.otp.data.EmailSummary) {
        var otp = email.otpCode
        if (otp.isNullOrBlank()) {
            serviceScope.launch {
                try {
                    val detail = api.readEmail(address, email.id)
                    otp = detail.email?.otpCode
                    if (otp.isNullOrBlank()) {
                        val body = detail.email?.bodyText ?: detail.email?.bodyHtml ?: ""
                        otp = extractOtpRegex(body)
                    }
                    if (!otp.isNullOrBlank()) {
                        NotificationHelper.showOtpNotification(
                            this@OtpPollingService, otp!!, email.fromAddress
                        )
                    }
                } catch (_: Exception) {}
            }
        } else {
            NotificationHelper.showOtpNotification(
                this@OtpPollingService, otp!!, email.fromAddress
            )
        }
    }

    private fun extractOtpRegex(text: String): String? {
        val regex = Regex("\\b(\\d{4,8})\\b")
        return regex.find(text)?.groupValues?.get(1)
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "OTP Polling Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Background monitoring of temp email inboxes"
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

    override fun onDestroy() {
        super.onDestroy()
        pollingJobs.values.forEach { it.cancel() }
        pollingJobs.clear()
        serviceScope.cancel()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
