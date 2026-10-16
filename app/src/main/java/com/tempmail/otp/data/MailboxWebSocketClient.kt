package com.tempmail.otp.data

import android.util.Log
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.util.concurrent.TimeUnit

/**
 * Callback for WebSocket events from MailboxTemp.
 */
interface WebSocketCallback {
    /** Called when WebSocket connects successfully */
    fun onConnected(address: String)

    /** Called when a new email event is received via WebSocket */
    fun onEmailEvent(address: String, emailSummary: EmailSummary)

    /** Called when WebSocket disconnects */
    fun onDisconnected(address: String)

    /** Called when an error occurs */
    fun onError(address: String, error: String)
}

/**
 * WebSocket client for MailboxTemp real-time email streaming.
 * Instead of polling every N seconds (which causes HTTP 429),
 * this opens a WebSocket connection to wss://mailboxtemp.com/ws/inbox?address=<address>
 * and receives push notifications when new emails arrive.
 *
 * This completely eliminates 429 rate-limit errors from polling.
 */
class MailboxWebSocketClient(
    private val client: OkHttpClient
) {
    private val moshi: Moshi = Moshi.Builder()
        .add(KotlinJsonAdapterFactory())
        .build()

    private val activeSockets = mutableMapOf<String, WebSocket>()

    /**
     * Open a WebSocket connection for a specific inbox address.
     * New emails will be pushed to callback.onEmailEvent().
     */
    fun connect(address: String, callback: WebSocketCallback) {
        // Close existing connection for this address if any
        disconnect(address)

        val request = Request.Builder()
            .url("wss://mailboxtemp.com/ws/inbox?address=$address")
            .build()

        val ws = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d("MailboxWS", "Connected to $address")
                callback.onConnected(address)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                Log.d("MailboxWS", "Message from $address: $text")
                handleMessage(address, text, callback)
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                val text = bytes.utf8()
                Log.d("MailboxWS", "Binary message from $address: $text")
                handleMessage(address, text, callback)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.d("MailboxWS", "Closing $address: $code $reason")
                webSocket.close(code, reason)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.d("MailboxWS", "Closed $address: $code $reason")
                activeSockets.remove(address)
                callback.onDisconnected(address)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e("MailboxWS", "Failure for $address", t)
                activeSockets.remove(address)
                callback.onError(address, t.message ?: "WebSocket error")
            }
        })

        activeSockets[address] = ws
    }

    /**
     * Parse incoming WebSocket message and dispatch to callback.
     * Messages can be:
     * - {"type":"connected"} — initial connection confirmation
     * - {"type":"email","email":{...}} — new email arrived
     * - Other types may exist; handle gracefully
     */
    private fun handleMessage(address: String, text: String, callback: WebSocketCallback) {
        try {
            // Parse as generic Map to inspect type field
            @Suppress("UNCHECKED_CAST")
            val map = moshi.adapter(Map::class.java).fromJson(text) as? Map<String, Any?> ?: return
            val type = map["type"] as? String

            when (type) {
                "connected" -> {
                    // Already handled in onOpen callback
                }
                "email", "message", "new_email" -> {
                    // Extract email data from the message
                    @Suppress("UNCHECKED_CAST")
                    val emailMap = map["email"] as? Map<String, Any?>
                        ?: map["message"] as? Map<String, Any?>
                        ?: map["data"] as? Map<String, Any?>

                    if (emailMap != null) {
                        val emailJson = moshi.adapter(Map::class.java).toJson(emailMap)
                        val email = moshi.adapter(EmailSummary::class.java).fromJson(emailJson)
                        if (email != null) {
                            callback.onEmailEvent(address, email)
                        }
                    }
                }
                else -> {
                    // Unknown message type — try to parse as email directly
                    try {
                        val email = moshi.adapter(EmailSummary::class.java).fromJson(text)
                        if (email != null && email.id.isNotEmpty()) {
                            callback.onEmailEvent(address, email)
                        }
                    } catch (_: Exception) {
                        Log.d("MailboxWS", "Unknown message type: $type, text: $text")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("MailboxWS", "Error parsing message from $address: ${e.message}", e)
        }
    }

    /**
     * Disconnect WebSocket for a specific address.
     */
    fun disconnect(address: String) {
        activeSockets[address]?.let { ws ->
            ws.close(1000, "Client disconnecting")
            activeSockets.remove(address)
        }
    }

    /**
     * Disconnect all active WebSockets.
     */
    fun disconnectAll() {
        activeSockets.values.forEach { it.close(1000, "Client disconnecting") }
        activeSockets.clear()
    }

    /**
     * Check if a WebSocket is active for the given address.
     */
    fun isConnected(address: String): Boolean = activeSockets.containsKey(address)

    /**
     * Reconnect WebSocket for a specific address.
     */
    fun reconnect(address: String, callback: WebSocketCallback) {
        disconnect(address)
        connect(address, callback)
    }
}
