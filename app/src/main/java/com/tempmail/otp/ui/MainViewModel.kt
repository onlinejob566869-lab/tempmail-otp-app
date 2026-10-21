package com.tempmail.otp.ui

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.tempmail.otp.data.ApiClient
import com.tempmail.otp.data.EmailSummary
import com.tempmail.otp.data.WebSocketCallback
import com.tempmail.otp.util.ClipboardHelper
import com.tempmail.otp.util.DailyLimit
import com.tempmail.otp.util.EmailList
import com.tempmail.otp.util.InboxClock
import com.tempmail.otp.util.NotificationHelper
import com.tempmail.otp.util.OtpExtractor
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import retrofit2.HttpException

/**
 * Represents one temp email inbox.
 */
data class InboxEntry(
    val address: String,
    val label: String = address.substringBefore("@").take(12),
    val emails: List<EmailSummary> = emptyList(),
    val lastOtp: String? = null,
    val lastOtpFrom: String? = null,
    /** Epoch millis at which the inbox is purged; null when the server did not say. */
    val expiresAt: Long? = null
) {
    fun isExpired(now: Long = System.currentTimeMillis()): Boolean =
        expiresAt != null && expiresAt <= now
}

/**
 * UI state for the app.
 */
sealed class UiState {
    data object Idle : UiState()
    data class Ready(val inboxes: List<InboxEntry>, val activeIndex: Int) : UiState()
    data class Error(val message: String) : UiState()
}

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val api = ApiClient.api
    private val wsClient = ApiClient.webSocketClient

    private val _uiState = MutableStateFlow<UiState>(UiState.Idle)
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private val _inboxes = MutableStateFlow<List<InboxEntry>>(emptyList())
    val inboxes: StateFlow<List<InboxEntry>> = _inboxes.asStateFlow()

    private val _activeIndex = MutableStateFlow(0)
    val activeIndex: StateFlow<Int> = _activeIndex.asStateFlow()

    /** Inboxes created today (UTC) — free tier allows 10 per day. */
    private val _creationsToday = MutableStateFlow(0)
    val creationsToday: StateFlow<Int> = _creationsToday.asStateFlow()
    val dailyLimit: Int = DailyLimit.FREE_DAILY_LIMIT

    /** Transient user-facing message (quota, extend result, expired inbox, ...). */
    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = _notice.asStateFlow()

    private val seenIds = mutableMapOf<String, MutableSet<String>>()
    private val reconnectJobs = mutableMapOf<String, Job>()

    /** Addresses that must not reconnect: deleted by the user, or expired and dropped. */
    private val stoppedAddresses = mutableSetOf<String>()
    private var noticeJob: Job? = null

    companion object {
        private const val TAG = "MainViewModel"
        private const val MAX_RETRIES = 5
        private const val INITIAL_BACKOFF_MS = 2_000L

        private const val PREFS_NAME = "tempmail_prefs"
        private const val KEY_ADDRESSES = "inbox_addresses"
        private const val KEY_OTPS = "inbox_otps"
        private const val KEY_OTP_SOURCES = "inbox_otp_sources"
        private const val KEY_ACTIVE_INDEX = "active_index"
        private const val KEY_EXPIRES = "inbox_expires"
        private const val KEY_CREATION_DAY = "creation_day"
        private const val KEY_CREATION_COUNT = "creation_count"
    }

    init {
        restoreState()
        watchExpiries()
    }

    // ────────────────────────────────────────────────────────────
    //  Persistence — save / restore inbox data via SharedPreferences
    // ────────────────────────────────────────────────────────────

    private fun prefs(): android.content.SharedPreferences =
        getApplication<Application>().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * Restore saved inbox addresses, OTPs, and active index on app startup.
     * Automatically reconnects WebSocket for every restored inbox.
     */
    private fun restoreState() {
        val sp = prefs()
        val addresses = sp.getString(KEY_ADDRESSES, "") ?: ""
        if (addresses.isBlank()) return

        val addrList = addresses.split("\n").filter { it.isNotBlank() }
        val otpMap = (sp.getString(KEY_OTPS, "") ?: "").split("\n")
            .filter { it.isNotBlank() }
            .associate {
                val parts = it.split("\t", limit = 2)
                (parts[0]) to (parts.getOrElse(1) { "" })
            }
        val otpSourceMap = (sp.getString(KEY_OTP_SOURCES, "") ?: "").split("\n")
            .filter { it.isNotBlank() }
            .associate {
                val parts = it.split("\t", limit = 2)
                (parts[0]) to (parts.getOrElse(1) { "" })
            }
        val expiresMap = (sp.getString(KEY_EXPIRES, "") ?: "").split("\n")
            .filter { it.isNotBlank() }
            .mapNotNull {
                val parts = it.split("\t", limit = 2)
                val millis = parts.getOrNull(1)?.toLongOrNull()
                if (parts.isNotEmpty() && millis != null) parts[0] to millis else null
            }
            .toMap()
        val savedActive = sp.getInt(KEY_ACTIVE_INDEX, 0)

        // Quota resets automatically when the stored UTC day is not today.
        _creationsToday.value = DailyLimit.usedToday(
            System.currentTimeMillis(),
            sp.getString(KEY_CREATION_DAY, null),
            sp.getInt(KEY_CREATION_COUNT, 0)
        )

        val restored = addrList.map { addr ->
            InboxEntry(
                address = addr,
                lastOtp = otpMap[addr]?.takeIf { it.isNotBlank() },
                lastOtpFrom = otpSourceMap[addr]?.takeIf { it.isNotBlank() },
                expiresAt = expiresMap[addr]
            )
        }

        if (restored.isNotEmpty()) {
            _inboxes.value = restored
            _activeIndex.value = savedActive.coerceIn(0, restored.size - 1)
            _uiState.value = UiState.Ready(restored, _activeIndex.value)
            restored.forEachIndexed { index, entry ->
                if (entry.isExpired()) {
                    // Purged server-side: no socket, no fetch, no reconnect.
                    stoppedAddresses.add(entry.address)
                } else {
                    startWebSocket(entry.address, index)
                    initialFetch(entry.address)
                }
            }
        }
    }

    /**
     * Save current inbox addresses, OTPs, and active index to SharedPreferences.
     */
    private fun saveState() {
        val sp = prefs()
        val addresses = _inboxes.value.joinToString("\n") { it.address }
        val otps = _inboxes.value
            .filter { !it.lastOtp.isNullOrBlank() }
            .joinToString("\n") { "${it.address}\t${it.lastOtp}" }
        val otpSources = _inboxes.value
            .filter { !it.lastOtpFrom.isNullOrBlank() }
            .joinToString("\n") { "${it.address}\t${it.lastOtpFrom}" }
        val expires = _inboxes.value
            .filter { it.expiresAt != null }
            .joinToString("\n") { "${it.address}\t${it.expiresAt}" }
        sp.edit()
            .putString(KEY_ADDRESSES, addresses)
            .putString(KEY_OTPS, otps)
            .putString(KEY_OTP_SOURCES, otpSources)
            .putString(KEY_EXPIRES, expires)
            .putInt(KEY_ACTIVE_INDEX, _activeIndex.value)
            .apply()
    }

    // ────────────────────────────────────────────────────────────
    //  Inbox creation
    // ────────────────────────────────────────────────────────────

    /**
     * Create a new temp inbox. Stops early on the free-tier daily quota or on HTTP 429 —
     * retrying a non-recoverable limit only wastes time.
     */
    fun createInbox() {
        refreshQuota()
        if (_creationsToday.value >= dailyLimit) {
            showNotice("Aaj ki $dailyLimit inbox limit puri ho gayi. Kal naya bana sakte ho (ya Pro plan).")
            return
        }

        viewModelScope.launch {
            _uiState.value = UiState.Error("Creating inbox...")
            var lastError: String? = null

            for (attempt in 0 until MAX_RETRIES) {
                try {
                    val response = api.createInbox()
                    val address = response.address
                    if (response.ok && address != null) {
                        // Server tells us when the inbox dies; fall back to 60 minutes.
                        val expiresAt = InboxClock.parseExpiry(response.expires)
                            ?: (System.currentTimeMillis() + InboxClock.FREE_LIFETIME_MS)
                        val entry = InboxEntry(address = address, expiresAt = expiresAt)
                        stoppedAddresses.remove(address)
                        _inboxes.value = _inboxes.value + entry
                        _activeIndex.value = _inboxes.value.size - 1
                        ClipboardHelper.copyText(getApplication(), "Temp Email", address)
                        _uiState.value = UiState.Ready(_inboxes.value, _activeIndex.value)
                        registerCreation()
                        saveState()
                        startWebSocket(address, _inboxes.value.size - 1)
                        return@launch
                    }

                    // ok=false is a per-request rejection; retrying it cannot succeed.
                    lastError = response.error ?: "Failed to create inbox"
                    break
                } catch (e: HttpException) {
                    if (e.code() == 429) {
                        Log.w(TAG, "Inbox creation rate limited", e)
                        showNotice("Rate limit: free tier me 10 inbox/day aur 20/min. Thodi der baad try karo.")
                        restoreUiAfterFailure()
                        return@launch
                    }
                    lastError = "Server error ${e.code()}"
                    delay(INITIAL_BACKOFF_MS * (1L shl attempt))
                } catch (e: Exception) {
                    Log.w(TAG, "Inbox creation failed", e)
                    lastError = e.message
                    val backoff = INITIAL_BACKOFF_MS * (1L shl attempt)
                    _uiState.value = UiState.Error("Network error, retrying in ${backoff / 1000}s...")
                    delay(backoff)
                }
            }

            _uiState.value = UiState.Error(lastError ?: "Failed to create inbox after $MAX_RETRIES attempts")
        }
    }

    private fun restoreUiAfterFailure() {
        if (_inboxes.value.isEmpty()) {
            _uiState.value = UiState.Error("Could not create an inbox. Free tier allows 10 per day.")
        } else {
            publishReady()
        }
    }

    // ────────────────────────────────────────────────────────────
    //  Expiry & quota
    // ────────────────────────────────────────────────────────────

    /**
     * Extends a live inbox by 10 minutes. The API ignores this for an expired inbox,
     * so the UI only offers it while the countdown is running.
     */
    fun extendInbox(address: String) {
        viewModelScope.launch {
            try {
                val response = api.extendInbox(address)
                if (!response.ok) {
                    showNotice("Extend failed: ${response.error ?: "unknown error"}")
                    return@launch
                }
                val current = _inboxes.value.toMutableList()
                val idx = current.indexOfFirst { it.address == address }
                if (idx >= 0) {
                    val base = maxOf(current[idx].expiresAt ?: 0L, System.currentTimeMillis())
                    current[idx] = current[idx].copy(expiresAt = base + InboxClock.EXTEND_MS)
                    _inboxes.value = current
                    publishReady(current)
                    saveState()
                    if (address in stoppedAddresses) startWebSocket(address)
                    showNotice("Inbox extended by 10 minutes.")
                }
            } catch (e: HttpException) {
                if (e.code() == 404) {
                    markExpired(address)
                } else {
                    showNotice("Extend failed (HTTP ${e.code()})")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Extend failed", e)
                showNotice("Extend failed: ${e.message ?: "network error"}")
            }
        }
    }

    /** Stops the socket and marks the inbox dead (server purged it). */
    private fun markExpired(address: String, notify: Boolean = true) {
        stopWebSocket(address)
        val current = _inboxes.value.toMutableList()
        val idx = current.indexOfFirst { it.address == address }
        if (idx >= 0 && !current[idx].isExpired()) {
            current[idx] = current[idx].copy(expiresAt = System.currentTimeMillis())
            _inboxes.value = current
            publishReady(current)
            saveState()
        }
        if (notify) showNotice("Inbox expired. Create a new one to keep receiving mail.")
    }

    /** Watches the countdown so expired inboxes stop reconnecting. */
    private fun watchExpiries() {
        viewModelScope.launch {
            while (isActive) {
                delay(15_000L)
                val now = System.currentTimeMillis()
                val expired = _inboxes.value.filter {
                    it.isExpired(now) && it.address !in stoppedAddresses
                }
                expired.forEach { markExpired(it.address, notify = false) }
            }
        }
    }

    private fun registerCreation() {
        val count = _creationsToday.value + 1
        _creationsToday.value = count
        prefs().edit()
            .putString(KEY_CREATION_DAY, DailyLimit.dayKey())
            .putInt(KEY_CREATION_COUNT, count)
            .apply()
    }

    /** Re-reads the quota so a UTC day rollover while the app is open resets it. */
    private fun refreshQuota() {
        val sp = prefs()
        _creationsToday.value = DailyLimit.usedToday(
            System.currentTimeMillis(),
            sp.getString(KEY_CREATION_DAY, null),
            sp.getInt(KEY_CREATION_COUNT, 0)
        )
    }

    private fun showNotice(message: String) {
        _notice.value = message
        noticeJob?.cancel()
        noticeJob = viewModelScope.launch {
            delay(6_000L)
            _notice.value = null
        }
    }

    fun dismissNotice() {
        noticeJob?.cancel()
        _notice.value = null
    }

    // ────────────────────────────────────────────────────────────
    //  WebSocket — real-time email push (replaces polling, no 429)
    // ────────────────────────────────────────────────────────────

    /**
     * Start WebSocket connection for an inbox. New emails are pushed by the server.
     * Reconnection with backoff happens automatically on disconnect.
     */
    fun startWebSocket(address: String, inboxIndex: Int = 0) {
        stoppedAddresses.remove(address)
        // Stagger WebSocket connections to avoid burst
        val staggerMs = inboxIndex * 1_000L

        viewModelScope.launch {
            delay(staggerMs)
            connectWebSocket(address)
        }
    }

    private fun connectWebSocket(address: String) {
        if (address in stoppedAddresses) return
        wsClient.connect(address, object : WebSocketCallback {
            override fun onConnected(addr: String) {
                // WebSocket connected — emails will be pushed
            }

            // OkHttp delivers these on a reader thread with no Looper. Move all state
            // work to the main thread so clipboard/Toast/state updates are safe (F-01).
            override fun onEmailEvent(addr: String, email: EmailSummary) {
                viewModelScope.launch { handleWsEmail(addr, email) }
            }

            override fun onDisconnected(addr: String) {
                // Auto-reconnect with backoff
                viewModelScope.launch { scheduleReconnect(addr) }
            }

            override fun onError(addr: String, error: String) {
                // Auto-reconnect with backoff
                viewModelScope.launch { scheduleReconnect(addr) }
            }
        })
    }

    /**
     * Handle a pushed email: dedupe, add it to the visible list, then look for an OTP.
     */
    private fun handleWsEmail(address: String, email: EmailSummary) {
        val seen = seenIds[address] ?: mutableSetOf()
        if (!seen.add(email.id)) return
        seenIds[address] = seen

        addOrUpdateEmail(address, email)
        handleNewEmail(address, email)
    }

    /**
     * Schedule WebSocket reconnection with exponential backoff.
     */
    private fun scheduleReconnect(address: String) {
        if (address in stoppedAddresses) return
        reconnectJobs[address]?.cancel()
        reconnectJobs[address] = viewModelScope.launch {
            var attempt = 0
            while (isActive) {
                val backoff = INITIAL_BACKOFF_MS * (1L shl attempt.coerceAtMost(5))
                delay(backoff)
                if (!isActive) break
                if (wsClient.isConnected(address)) break
                connectWebSocket(address)
                attempt++
                // Wait before checking if reconnection succeeded
                delay(3_000L)
                if (wsClient.isConnected(address)) break
            }
        }
    }

    /**
     * One-time fetch of emails for an inbox (used on restore).
     * This is a single API call, not polling — safe from 429.
     */
    private fun initialFetch(address: String) {
        viewModelScope.launch {
            val entry = _inboxes.value.firstOrNull { it.address == address }
            if (entry?.isExpired() == true) {
                markExpired(address, notify = false)
                return@launch
            }
            try {
                val response = api.listEmails(address)
                if (!response.ok) return@launch

                updateInboxEmails(address, response.emails)

                // Seed the dedupe set: these messages already existed, so they must not be
                // replayed as notifications or written to the clipboard on every launch (F-04).
                val seen = seenIds[address] ?: mutableSetOf()
                seen.addAll(response.emails.map { it.id })
                seenIds[address] = seen

                // Show the newest known code on screen only — no notification, no copy.
                EmailList.newestOtp(response.emails)?.let { newest ->
                    setLastOtp(address, newest.otpCode!!, newest.fromAddress)
                }
            } catch (e: HttpException) {
                if (e.code() == 404) {
                    markExpired(address)
                } else {
                    Log.w(TAG, "Initial fetch failed for $address", e)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Initial fetch failed for $address", e)
            }
        }
    }

    // ────────────────────────────────────────────────────────────
    //  Inbox switching
    // ────────────────────────────────────────────────────────────

    /**
     * Switch active inbox — only the selected email is shown and copied.
     */
    fun switchInbox(index: Int) {
        if (index < 0 || index >= _inboxes.value.size) return
        _activeIndex.value = index
        val active = _inboxes.value[index]
        ClipboardHelper.copyText(getApplication(), "Temp Email", active.address)
        _uiState.value = UiState.Ready(_inboxes.value, index)
        saveState()
    }

    /**
     * Copy the active email address to clipboard.
     */
    fun copyActiveEmail() {
        val idx = _activeIndex.value
        if (idx < _inboxes.value.size) {
            ClipboardHelper.copyText(getApplication(), "Temp Email", _inboxes.value[idx].address)
        }
    }

    // ────────────────────────────────────────────────────────────
    //  Email handling
    // ────────────────────────────────────────────────────────────

    /**
     * Replace the whole email list for an inbox (initial fetch / refresh).
     */
    private fun updateInboxEmails(address: String, emails: List<EmailSummary>) {
        val current = _inboxes.value.toMutableList()
        val idx = current.indexOfFirst { it.address == address }
        if (idx >= 0) {
            current[idx] = current[idx].copy(emails = emails)
            _inboxes.value = current
            publishReady(current)
        }
    }

    /**
     * Prepend a pushed email so the inbox list updates live (F-02).
     */
    private fun addOrUpdateEmail(address: String, email: EmailSummary) {
        val current = _inboxes.value.toMutableList()
        val idx = current.indexOfFirst { it.address == address }
        if (idx < 0) return
        current[idx] = current[idx].copy(
            emails = EmailList.merge(current[idx].emails, email)
        )
        _inboxes.value = current
        publishReady(current)
    }

    /**
     * Keep the screen on the live inbox list. Previously list updates were dropped unless
     * the state was exactly Ready, which froze the UI after the first OTP (F-03).
     */
    private fun publishReady(inboxes: List<InboxEntry> = _inboxes.value) {
        if (inboxes.isNotEmpty()) {
            _uiState.value = UiState.Ready(inboxes, _activeIndex.value)
        }
    }

    /** Store the last OTP without the copy/notify side effects of [onOtpFound]. */
    private fun setLastOtp(address: String, otp: String, from: String) {
        val current = _inboxes.value.toMutableList()
        val idx = current.indexOfFirst { it.address == address }
        if (idx < 0) return
        current[idx] = current[idx].copy(lastOtp = otp, lastOtpFrom = from)
        _inboxes.value = current
        publishReady(current)
        saveState()
    }

    /**
     * Handle a newly arrived email: extract OTP, copy to clipboard, notify.
     */
    private fun handleNewEmail(address: String, email: EmailSummary) {
        var otp = email.otpCode

        if (otp.isNullOrBlank()) {
            viewModelScope.launch {
                try {
                    val detail = api.readEmail(address, email.id)
                    otp = detail.email?.otpCode
                    if (otp.isNullOrBlank()) {
                        val body = detail.email?.bodyText ?: detail.email?.bodyHtml ?: ""
                        otp = OtpExtractor.extract(body)
                    }
                    if (!otp.isNullOrBlank()) {
                        onOtpFound(address, otp!!, email.fromAddress)
                    }
                } catch (e: HttpException) {
                    if (e.code() == 404) {
                        markExpired(address)
                    } else {
                        Log.w(TAG, "Email detail failed for $address", e)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Email detail failed for $address", e)
                }
            }
        } else {
            onOtpFound(address, otp!!, email.fromAddress)
        }
    }

    private fun onOtpFound(address: String, otp: String, from: String) {
        val current = _inboxes.value.toMutableList()
        val idx = current.indexOfFirst { it.address == address }
        if (idx >= 0) {
            current[idx] = current[idx].copy(lastOtp = otp, lastOtpFrom = from)
            _inboxes.value = current
        }

        if (_activeIndex.value < current.size && current[_activeIndex.value].address == address) {
            ClipboardHelper.copyText(getApplication(), "OTP Code", otp)
        }

        NotificationHelper.showOtpNotification(getApplication(), otp, from)
        _uiState.value = UiState.Ready(current, _activeIndex.value)
        saveState()
    }

    // ────────────────────────────────────────────────────────────
    //  Inbox management
    // ────────────────────────────────────────────────────────────
    fun stopWebSocket(address: String) {
        stoppedAddresses.add(address)
        reconnectJobs[address]?.cancel()
        reconnectJobs.remove(address)
        wsClient.disconnect(address)
    }

    fun stopAllWebSockets() {
        reconnectJobs.values.forEach { it.cancel() }
        reconnectJobs.clear()
        wsClient.disconnectAll()
    }

    fun deleteInbox(index: Int) {
        if (index < 0 || index >= _inboxes.value.size) return
        val current = _inboxes.value.toMutableList()
        val removed = current.removeAt(index)
        stopWebSocket(removed.address)
        _inboxes.value = current
        if (_activeIndex.value >= current.size) {
            _activeIndex.value = (current.size - 1).coerceAtLeast(0)
        }
        if (current.isEmpty()) {
            _uiState.value = UiState.Idle
        } else {
            _uiState.value = UiState.Ready(current, _activeIndex.value)
        }
        saveState()
    }

    fun getAllAddresses(): List<String> = _inboxes.value.map { it.address }

    override fun onCleared() {
        super.onCleared()
        saveState()
        stopAllWebSockets()
    }
}
