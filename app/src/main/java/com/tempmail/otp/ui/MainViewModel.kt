package com.tempmail.otp.ui

import android.app.Application
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

    /**
     * Create a new temp inbox, add it to the list, and start polling.
     */
    fun createInbox() {
        viewModelScope.launch {
            _uiState.value = UiState.Error("Creating inbox...")
            try {
                val response = api.createInbox()
                val address = response.address
                if (response.ok && address != null) {
                    val entry = InboxEntry(address = address)
                    _inboxes.value = _inboxes.value + entry
                    _activeIndex.value = _inboxes.value.size - 1
                    // Copy the newly created email to clipboard
                    ClipboardHelper.copyText(getApplication(), "Temp Email", address)
                    _uiState.value = UiState.Ready(_inboxes.value, _activeIndex.value)
                    // Start polling this inbox
                    startPolling(address)
                } else {
                    _uiState.value = UiState.Error(response.error ?: "Failed to create inbox")
                }
            } catch (e: Exception) {
                _uiState.value = UiState.Error("Network error: ${e.message}")
            }
        }
    }

    /**
     * Switch active inbox — only the selected email is shown and copied.
     */
    fun switchInbox(index: Int) {
        if (index < 0 || index >= _inboxes.value.size) return
        _activeIndex.value = index
        val active = _inboxes.value[index]
        // Copy the selected email address to clipboard
        ClipboardHelper.copyText(getApplication(), "Temp Email", active.address)
        _uiState.value = UiState.Ready(_inboxes.value, index)
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

    /**
     * Start polling a specific inbox every [intervalMs] milliseconds.
     */
    fun startPolling(address: String, intervalMs: Long = 5000L) {
        // Cancel existing job for this address if any
        pollingJobs[address]?.cancel()
        seenIds[address] = mutableSetOf()

        pollingJobs[address] = viewModelScope.launch {
            while (isActive) {
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
                } catch (_: Exception) {
                }
                delay(intervalMs)
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
        // Update inbox entry with last OTP
        val current = _inboxes.value.toMutableList()
        val idx = current.indexOfFirst { it.address == address }
        if (idx >= 0) {
            current[idx] = current[idx].copy(lastOtp = otp, lastOtpFrom = from)
            _inboxes.value = current
        }

        // Only copy to clipboard if this is the active inbox
        if (_activeIndex.value < current.size && current[_activeIndex.value].address == address) {
            ClipboardHelper.copyText(getApplication(), "OTP Code", otp)
        }

        NotificationHelper.showOtpNotification(getApplication(), otp, from)
        _uiState.value = UiState.OtpFound(address, otp, from)
    }

    private fun extractOtpRegex(text: String): String? {
        val regex = Regex("\\b(\\d{4,8})\\b")
        return regex.find(text)?.groupValues?.get(1)
    }

    /**
     * Stop polling for a specific inbox.
     */
    fun stopPolling(address: String) {
        pollingJobs[address]?.cancel()
        pollingJobs.remove(address)
    }

    /**
     * Stop all polling.
     */
    fun stopAllPolling() {
        pollingJobs.values.forEach { it.cancel() }
        pollingJobs.clear()
    }

    /**
     * Delete an inbox from the list.
     */
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
    }

    /**
     * Get all inbox addresses for the foreground service.
     */
    fun getAllAddresses(): List<String> = _inboxes.value.map { it.address }

    override fun onCleared() {
        super.onCleared()
        stopAllPolling()
    }
}
