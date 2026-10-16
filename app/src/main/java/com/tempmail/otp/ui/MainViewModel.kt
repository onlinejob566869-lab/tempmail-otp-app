package com.tempmail.otp.ui

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.tempmail.otp.data.ApiClient
import com.tempmail.otp.data.EmailSummary
import com.tempmail.otp.data.WebSocketCallback
import com.tempmail.otp.util.ClipboardHelper
import com.tempmail.otp.util.NotificationHelper
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Represents one temp email inbox.
 */
data class InboxEntry(
    val address: String,
    val label: String = address.substringBefore("@").take(12),
    val emails: List<EmailSummary> = emptyList(),
    val lastOtp: String? = null,
    val lastOtpFrom: String? = null
)

/**
 * UI state for the app.
 */
sealed class UiState {
    data object Idle : UiState()
    data class Ready(val inboxes: List<InboxEntry>, val activeIndex: Int) : UiState()
    data class OtpFound(val address: String, val otp: String, val from: String) : UiState()
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

    private val seenIds = mutableMapOf<String, MutableSet<String>>()
    private val reconnectJobs = mutableMapOf<String, Job>()

    companion object {
        private const val MAX_RETRIES = 5
        private const val INITIAL_BACKOFF_MS = 2_000L

        private const val PREFS_NAME = "tempmail_prefs"
        private const val KEY_ADDRESSES = "inbox_addresses"
        private const val KEY_OTPS = "inbox_otps"
        private const val KEY_OTP_SOURCES = "inbox_otp_sources"
        private const val KEY_ACTIVE_INDEX = "active_index"
    }

    init {
        restoreState()
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
        val savedActive = sp.getInt(KEY_ACTIVE_INDEX, 0)

        val restored = addrList.map { addr ->
            InboxEntry(
                address = addr,
                lastOtp = otpMap[addr]?.takeIf { it.isNotBlank() },
                lastOtpFrom = otpSourceMap[addr]?.takeIf { it.isNotBlank() }
            )
        }

        if (restored.isNotEmpty()) {
            _inboxes.value = restored
            _activeIndex.value = savedActive.coerceIn(0, restored.size - 1)
            _uiState.value = UiState.Ready(restored, _activeIndex.value)
            // Reconnect WebSocket for each restored inbox (staggered)
            restored.forEachIndexed { index, entry ->
                startWebSocket(entry.address, index)
            }
            // Also do one initial fetch to get any emails that arrived while app was closed
            restored.forEach { initialFetch(it.address) }
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
        sp.edit()
            .putString(KEY_ADDRESSES, addresses)
            .putString(KEY_OTPS, otps)
            .putString(KEY_OTP_SOURCES, otpSources)
            .putInt(KEY_ACTIVE_INDEX, _activeIndex.value)
            .apply()
    }

    // ────────────────────────────────────────────────────────────
    //  Inbox creation
    // ────────────────────────────────────────────────────────────

    /**
     * Create a new temp inbox with retry + exponential backoff for HTTP 429.
     * After creation, opens a WebSocket for real-time email push (no polling).
     */
    fun createInbox() {
        viewModelScope.launch {
            _uiState.value = UiState.Error("Creating inbox...")
            var lastError: String? = null

            for (attempt in 0 until MAX_RETRIES) {
                try {
                    val response = api.createInbox()
                    val address = response.address
                    if (response.ok && address != null) {
                        val entry = InboxEntry(address = address)
                        _inboxes.value = _inboxes.value + entry
                        _activeIndex.value = _inboxes.value.size - 1
                        ClipboardHelper.copyText(getApplication(), "Temp Email", address)
                        _uiState.value = UiState.Ready(_inboxes.value, _activeIndex.value)
                        saveState()
                        // Start WebSocket for real-time email push
                        startWebSocket(address, _inboxes.value.size - 1)
                        return@launch
                    } else {
                        lastError = response.error ?: "Failed to create inbox"
                    }
                } catch (e: Exception) {
                    lastError = e.message
                    val backoff = INITIAL_BACKOFF_MS * (1L shl attempt)
                    _uiState.value = UiState.Error("Rate limited, retrying in ${backoff / 1000}s...")
                    delay(backoff)
                }
            }

            _uiState.value = UiState.Error(lastError ?: "Failed to create inbox after $MAX_RETRIES attempts")
        }
    }

    // ────────────────────────────────────────────────────────────
    //  WebSocket — real-time email push (replaces polling, no 429)
    // ────────────────────────────────────────────────────────────

    /**
     * Start WebSocket connection for an inbox. New emails are pushed by the server.
     * Reconnection with backoff happens automatically on disconnect.
     */
    fun startWebSocket(address: String, inboxIndex: Int = 0) {
        // Stagger WebSocket connections to avoid burst
        val staggerMs = inboxIndex * 1_000L

        viewModelScope.launch {
            delay(staggerMs)
            connectWebSocket(address)
        }
    }

    private fun connectWebSocket(address: String) {
        wsClient.connect(address, object : WebSocketCallback {
            override fun onConnected(addr: String) {
                // WebSocket connected — emails will be pushed
            }

            override fun onEmailEvent(addr: String, email: EmailSummary) {
                val seen = seenIds[addr] ?: mutableSetOf()
                if (email.id !in seen) {
                    seen.add(email.id)
                    seenIds[addr] = seen
                    handleNewEmail(addr, email)
                }
            }

            override fun onDisconnected(addr: String) {
                // Auto-reconnect with backoff
                scheduleReconnect(addr)
            }

            override fun onError(addr: String, error: String) {
                // Auto-reconnect with backoff
                scheduleReconnect(addr)
            }
        })
    }

    /**
     * Schedule WebSocket reconnection with exponential backoff.
     */
    private fun scheduleReconnect(address: String) {
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
            try {
                val response = api.listEmails(address)
                if (response.ok) {
                    updateInboxEmails(address, response.emails)
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
     * Update the email list for a specific inbox in state.
     */
    private fun updateInboxEmails(address: String, emails: List<EmailSummary>) {
        val current = _inboxes.value.toMutableList()
        val idx = current.indexOfFirst { it.address == address }
        if (idx >= 0) {
            current[idx] = current[idx].copy(emails = emails)
            _inboxes.value = current
            if (_uiState.value is UiState.Ready) {
                _uiState.value = UiState.Ready(current, _activeIndex.value)
            }
        }
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
                        otp = extractOtpRegex(body)
                    }
                    if (!otp.isNullOrBlank()) {
                        onOtpFound(address, otp!!, email.fromAddress)
                    }
                } catch (_: Exception) {}
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
        _uiState.value = UiState.OtpFound(address, otp, from)
        saveState()
    }

    private fun extractOtpRegex(text: String): String? {
        val regex = Regex("\\b(\\d{4,8})\\b")
        return regex.find(text)?.groupValues?.get(1)
    }

    // ────────────────────────────────────────────────────────────
    //  Inbox management
    // ────────────────────────────────────────────────────────────

    fun stopWebSocket(address: String) {
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
