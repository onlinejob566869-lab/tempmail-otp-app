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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Owns at most one WebSocket per inbox address.
 *
 * Two fixes over the old implementation:
 *  - connection state is only reported after the handshake (`onOpen`), not when the socket
 *    object is created, so callers can trust [isOpen];
 *  - every socket gets a generation id and callbacks from a replaced socket are ignored,
 *    so a stale connection can never resurrect itself.
 */
class MailboxWebSocketClient(
    private val client: OkHttpClient
) {

    interface Listener {
        fun onOpen(address: String)
        fun onEmail(address: String, email: EmailSummary)
        fun onClosed(address: String)
        fun onFailure(address: String, error: String)
    }

    private val moshi: Moshi = Moshi.Builder()
        .add(KotlinJsonAdapterFactory())
        .build()

    private val generation = AtomicLong(0L)
    private val sockets = ConcurrentHashMap<String, WebSocket>()
    private val currentIds = ConcurrentHashMap<String, Long>()
    private val openAddresses = ConcurrentHashMap.newKeySet<String>()

    /** Connects [address], replacing any previous socket for the same inbox. */
    fun connect(address: String, listener: Listener) {
        disconnect(address)

        val id = generation.incrementAndGet()
        currentIds[address] = id

        val request = Request.Builder()
            .url("wss://mailboxtemp.com/ws/inbox?address=$address")
            .build()

        val ws = client.newWebSocket(request, object : WebSocketListener() {
            private fun isCurrent(): Boolean = currentIds[address] == id

            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (!isCurrent()) return
                openAddresses.add(address)
                listener.onOpen(address)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (!isCurrent()) return
                handleMessage(address, text, listener)
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                if (!isCurrent()) return
                handleMessage(address, bytes.utf8(), listener)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, reason)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (!isCurrent()) return
                openAddresses.remove(address)
                listener.onClosed(address)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (!isCurrent()) return
                openAddresses.remove(address)
                Log.w("MailboxWS", "Failure for $address", t)
                listener.onFailure(address, t.message ?: "WebSocket error")
            }
        })

        sockets[address] = ws
    }

    /** Closes the socket for [address]; later callbacks from it are ignored. */
    fun disconnect(address: String) {
        currentIds.remove(address)
        openAddresses.remove(address)
        sockets.remove(address)?.close(1000, "Client disconnecting")
    }

    fun disconnectAll() {
        for (address in sockets.keys) disconnect(address)
    }

    /** True only after the server accepted the handshake. */
    fun isOpen(address: String): Boolean = openAddresses.contains(address)

    /**
     * Parses a pushed message:
     *  - `{"type":"connected"}` on open
     *  - `{"type":"email","email":{...}}` (also tolerates `message`/`data` wrappers)
     */
    private fun handleMessage(address: String, text: String, listener: Listener) {
        try {
            @Suppress("UNCHECKED_CAST")
            val map = moshi.adapter(Map::class.java).fromJson(text) as? Map<String, Any?> ?: return
            val type = map["type"] as? String

            when (type) {
                "connected" -> Unit
                "email", "message", "new_email" -> {
                    @Suppress("UNCHECKED_CAST")
                    val emailMap = map["email"] as? Map<String, Any?>
                        ?: map["message"] as? Map<String, Any?>
                        ?: map["data"] as? Map<String, Any?>

                    if (emailMap != null) {
                        val emailJson = moshi.adapter(Map::class.java).toJson(emailMap)
                        val email = moshi.adapter(EmailSummary::class.java).fromJson(emailJson)
                        if (email != null) listener.onEmail(address, email)
                    }
                }
                else -> {
                    // Unknown type — try to read the frame as an email object.
                    try {
                        val email = moshi.adapter(EmailSummary::class.java).fromJson(text)
                        if (email != null && email.id.isNotEmpty()) listener.onEmail(address, email)
                    } catch (_: Exception) {
                        Log.d("MailboxWS", "Unknown message type: $type")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("MailboxWS", "Error parsing message from $address", e)
        }
    }
}
