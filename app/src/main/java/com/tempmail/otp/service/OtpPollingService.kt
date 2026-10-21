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
import com.tempmail.otp.data.EmailSummary
import com.tempmail.otp.data.WebSocketCallback
import com.tempmail.otp.util.NotificationHelper
import com.tempmail.otp.util.OtpExtractor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Foreground service that keeps WebSocket connections alive in the background
 * even when the app is closed. Shows a persistent notification.
 * Uses WebSocket real-time push instead of polling — eliminates HTTP 429.
 */
class OtpPollingService : Service() {

    companion object {
        const val CHANNEL_ID = "otp_polling_service"
        const val NOTIFICATION_ID = 2001
        const val EXTRA_ADDRESSES = "addresses"

        private const val RECONNECT_DELAY_MS = 5_000L

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
    private val wsClient = ApiClient.webSocketClient
    // Single-threaded (main) scope: WebSocket callbacks arrive on OkHttp threads, so all
    // state changes are serialised here instead of racing on Dispatchers.IO.
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val seenIds = mutableMapOf<String, MutableSet<String>>()
    private val reconnectJobs = mutableMapOf<String, Job>()

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification("Monitoring inboxes..."))

        intent?.getStringArrayExtra(EXTRA_ADDRESSES)?.let { addresses ->
            for ((index, address) in addresses.withIndex()) {
                startWebSocketInbox(address, index)
            }
            updateNotification("Monitoring ${addresses.size} inbox(es)...")
        }

        return START_STICKY
    }

    /**
     * Start WebSocket connection for an inbox with stagger.
     */
    private fun startWebSocketInbox(address: String, inboxIndex: Int = 0) {
        seenIds[address] = mutableSetOf()
        val staggerMs = inboxIndex * 1_000L

        serviceScope.launch {
            delay(staggerMs)
            connectWebSocket(address)
        }
    }

    /**
     * Connect WebSocket for real-time email push.
     */
    private fun connectWebSocket(address: String) {
        wsClient.connect(address, object : WebSocketCallback {
            override fun onConnected(addr: String) {
                // Connected — emails will be pushed
            }

            override fun onEmailEvent(addr: String, email: EmailSummary) {
                serviceScope.launch {
                    val seen = seenIds[addr] ?: mutableSetOf()
                    if (email.id !in seen) {
                        seen.add(email.id)
                        seenIds[addr] = seen
                        handleNewEmail(addr, email)
                    }
                }
            }

            override fun onDisconnected(addr: String) {
                serviceScope.launch { scheduleReconnect(addr) }
            }

            override fun onError(addr: String, error: String) {
                serviceScope.launch { scheduleReconnect(addr) }
            }
        })
    }

    /**
     * Schedule WebSocket reconnection with backoff.
     */
    private fun scheduleReconnect(address: String) {
        reconnectJobs[address]?.cancel()
        reconnectJobs[address] = serviceScope.launch {
            var attempt = 0
            while (isActive) {
                val backoff = RECONNECT_DELAY_MS * (1L shl attempt.coerceAtMost(5))
                delay(backoff)
                if (!isActive) break
                if (wsClient.isConnected(address)) break
                connectWebSocket(address)
                attempt++
                delay(3_000L)
                if (wsClient.isConnected(address)) break
            }
        }
    }

    private fun handleNewEmail(address: String, email: EmailSummary) {
        var otp = email.otpCode
        if (otp.isNullOrBlank()) {
            serviceScope.launch {
                try {
                    val detail = api.readEmail(address, email.id)
                    otp = detail.email?.otpCode
                    if (otp.isNullOrBlank()) {
                        val body = detail.email?.bodyText ?: detail.email?.bodyHtml ?: ""
                        otp = OtpExtractor.extract(body)
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
        reconnectJobs.values.forEach { it.cancel() }
        reconnectJobs.clear()
        wsClient.disconnectAll()
        serviceScope.cancel()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
