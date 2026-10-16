package com.tempmail.otp.ui

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.tempmail.otp.data.ApiClient
import com.tempmail.otp.data.EmailSummary
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

    private val _uiState = MutableStateFlow<UiState>(UiState.Idle)
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private val _inboxes = MutableStateFlow<List<InboxEntry>>(emptyList())
    val inboxes: StateFlow<List<InboxEntry>> = _inboxes.asStateFlow()

    private val _activeIndex = MutableStateFlow(0)
    val activeIndex: StateFlow<Int> = _activeIndex.asStateFlow()

    private val pollingJobs = mutableMapOf<String, Job>()
    private val seenIds = mutableMapOf<String, MutableSet<String>>()

    companion object {
        private const val POLL_INTERVAL_MS = 10_000L  // 10 seconds between polls
        private const val STAGGER_DELAY_MS = 2_000L   // 2 second offset per inbox
        private const val MAX_RETRIES = 5
        private const val INITIAL_BACKOFF_MS = 2_000L // 2 seconds initial backoff

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
     * Automatically restarts polling for every restored inbox.
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
            // Restart polling for each restored inbox
            restored.forEach { startPolling(it.address) }
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
                        startPolling(address)
                        return@launch
                    } else {
                        lastError = response.error ?: "Failed to create inbox"
                    }
                } catch (e: Exception) {
                    lastError = e.message
                    val backoff = INITIAL_BACKOFF_MS * (1L shl attempt) // 2s, 4s, 8s, 16s, 32s
                    _uiState.value = UiState.Error("Rate limited, retrying in ${backoff / 1000}s...")
                    delay(backoff)
                }
            }

            _uiState.value = UiState.Error(lastError ?: "Failed to create inbox after $MAX_RETRIES attempts")
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
    //  Polling
    // ────────────────────────────────────────────────────────────

    /**
     * Start polling a specific inbox.
     * Uses longer interval + stagger to avoid HTTP 429 with multiple inboxes.
     * Includes retry with backoff on 429.
     */
    fun startPolling(address: String) {
        pollingJobs[address]?.cancel()
        seenIds[address] = mutableSetOf()

        // Stagger: offset each inbox's polling by STAGGER_DELAY_MS
        val staggerOffset = (_inboxes.value.indexOfFirst { it.address == address }).coerceAtLeast(0) * STAGGER_DELAY_MS

        pollingJobs[address] = viewModelScope.launch {
            delay(staggerOffset) // Stagger start to spread API calls

            var consecutiveErrors = 0
            while (isActive) {
                try {
                    val response = api.listEmails(address)
                    if (response.ok) {
                        consecutiveErrors = 0
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
                } catch (e: Exception) {
                    consecutiveErrors++
                    val backoff = (INITIAL_BACKOFF_MS * (1L shl consecutiveErrors.coerceAtMost(4)))
                    delay(backoff)
                    continue
                }
                delay(POLL_INTERVAL_MS)
            }
        }
    }

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

    fun stopPolling(address: String) {
        pollingJobs[address]?.cancel()
        pollingJobs.remove(address)
    }

    fun stopAllPolling() {
        pollingJobs.values.forEach { it.cancel() }
        pollingJobs.clear()
    }

    fun deleteInbox(index: Int) {
        if (index < 0 || index >= _inboxes.value.size) return
        val current = _inboxes.value.toMutableList()
        val removed = current.removeAt(index)
        stopPolling(removed.address)
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
        stopAllPolling()
    }
}
