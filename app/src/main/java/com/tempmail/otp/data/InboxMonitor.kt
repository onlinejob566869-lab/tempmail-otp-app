package com.tempmail.otp.data

import android.content.Context
import android.util.Log
import com.tempmail.otp.util.NotificationHelper
import com.tempmail.otp.util.OtpExtractor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import retrofit2.HttpException

/**
 * The single owner of inbox sockets.
 *
 * Both the UI and the foreground service call [start]/[stop]; neither connects a WebSocket
 * itself any more, so they can no longer steal each other's connection (F-06). Notifications
 * are posted from here exactly once per email.
 */
class InboxMonitor(
    private val appContext: Context,
    private val api: MailboxTempApi,
    private val ws: MailboxWebSocketClient
) {

    data class EmailEvent(val address: String, val email: EmailSummary, val otp: String?)

    data class State(
        val connected: Boolean = false,
        val attempts: Int = 0,
        val error: String? = null
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _events = MutableSharedFlow<EmailEvent>(extraBufferCapacity = 128)
    val events: SharedFlow<EmailEvent> = _events.asSharedFlow()

    /** Emitted when the server says the inbox no longer exists (404). */
    private val _gone = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val gone: SharedFlow<String> = _gone.asSharedFlow()

    private val _states = MutableStateFlow<Map<String, State>>(emptyMap())
    val states: StateFlow<Map<String, State>> = _states.asStateFlow()

    private val seen = mutableMapOf<String, MutableSet<String>>()
    private val retries = mutableMapOf<String, Job>()
    private val stopping = mutableSetOf<String>()

    /** Starts monitoring [address]. Idempotent. */
    fun start(address: String, delayMs: Long = 0L) {
        stopping.remove(address)
        retries.remove(address)?.cancel()
        retries[address] = scope.launch {
            if (delayMs > 0L) delay(delayMs)
            connect(address)
        }
    }

    /** Starts several inboxes with a small stagger so 10 sockets do not connect at once. */
    fun startAll(addresses: List<String>) {
        addresses.forEachIndexed { index, address -> start(address, index * STAGGER_MS) }
    }

    /** Stops [address] for good (deleted or expired) and ignores late callbacks. */
    fun stop(address: String) {
        stopping.add(address)
        retries.remove(address)?.cancel()
        ws.disconnect(address)
        seen.remove(address)
        updateState(address, State(connected = false))
    }

    fun stopAll() {
        ws.disconnectAll()
        retries.values.forEach { it.cancel() }
        retries.clear()
        stopping.addAll(_states.value.keys)
        _states.value = emptyMap()
    }

    /** Marks messages found by an initial fetch as already handled. */
    fun markSeen(address: String, ids: Collection<String>) {
        seen.getOrPut(address) { mutableSetOf() }.addAll(ids)
    }

    fun isOpen(address: String): Boolean = ws.isOpen(address)

    private fun connect(address: String) {
        if (address in stopping) return
        ws.connect(address, object : MailboxWebSocketClient.Listener {
            // Callbacks arrive on OkHttp threads: move all state work to the monitor's scope.
            override fun onOpen(address: String) {
                scope.launch { updateState(address, State(connected = true)) }
            }

            override fun onEmail(address: String, email: EmailSummary) {
                scope.launch { handleEmail(address, email) }
            }

            override fun onClosed(address: String) {
                scope.launch {
                    updateState(address, State(connected = false))
                    scheduleRetry(address)
                }
            }

            override fun onFailure(address: String, error: String) {
                scope.launch {
                    val attempts = (_states.value[address]?.attempts ?: 0) + 1
                    Log.w(TAG, "Socket failed for $address (attempt $attempts): $error")
                    updateState(
                        address,
                        State(connected = false, attempts = attempts, error = error)
                    )
                    scheduleRetry(address)
                }
            }
        })
    }

    private suspend fun handleEmail(address: String, email: EmailSummary) {
        val ids = seen.getOrPut(address) { mutableSetOf() }
        if (!ids.add(email.id)) return

        var otp = email.otpCode
        if (otp.isNullOrBlank()) {
            try {
                val detail = api.readEmail(address, email.id)
                otp = detail.email?.otpCode
                    ?: OtpExtractor.extract(detail.email?.bodyText ?: detail.email?.bodyHtml)
            } catch (e: HttpException) {
                if (e.code() == 404) {
                    stopping.add(address)
                    ws.disconnect(address)
                    _gone.emit(address)
                    return
                }
                Log.w(TAG, "Reading email failed for $address", e)
            } catch (e: Exception) {
                Log.w(TAG, "Reading email failed for $address", e)
            }
        }

        if (!otp.isNullOrBlank()) {
            NotificationHelper.showOtpNotification(
                appContext, otp!!, email.fromAddress, address
            )
        }
        _events.emit(EmailEvent(address, email, otp))
    }

    /** Exponential backoff (2 s → 60 s); resets as soon as a handshake succeeds. */
    private fun scheduleRetry(address: String) {
        if (address in stopping) return
        retries.remove(address)?.cancel()
        val attempt = (_states.value[address]?.attempts ?: 1).coerceIn(1, 6)
        val backoff = (INITIAL_BACKOFF_MS shl (attempt - 1)).coerceAtMost(MAX_BACKOFF_MS)
        retries[address] = scope.launch {
            delay(backoff)
            if (address in stopping) return@launch
            connect(address)
        }
    }

    private fun updateState(address: String, state: State) {
        _states.value = _states.value + (address to state)
    }

    companion object {
        private const val TAG = "InboxMonitor"
        const val STAGGER_MS = 300L
        private const val INITIAL_BACKOFF_MS = 2_000L
        private const val MAX_BACKOFF_MS = 60_000L
    }
}
