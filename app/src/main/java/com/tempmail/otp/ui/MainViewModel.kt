package com.tempmail.otp.ui

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.tempmail.otp.data.ApiClient
import com.tempmail.otp.data.EmailSummary
import com.tempmail.otp.data.InboxMonitor
import com.tempmail.otp.util.AppVisibility
import com.tempmail.otp.util.BulkCreate
import com.tempmail.otp.util.ClipboardHelper
import com.tempmail.otp.util.DailyLimit
import com.tempmail.otp.util.EmailList
import com.tempmail.otp.util.InboxClock
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
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

    /** Process-wide socket owner shared with the foreground service. */
    private val monitor by lazy { ApiClient.monitor(getApplication()) }

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

    /** Non-null while a bulk creation runs: (done, total). */
    private val _bulkProgress = MutableStateFlow<Pair<Int, Int>?>(null)
    val bulkProgress: StateFlow<Pair<Int, Int>?> = _bulkProgress.asStateFlow()

    /** Live socket state per address, owned by [monitor]. */
    val monitorStates: StateFlow<Map<String, InboxMonitor.State>>
        get() = monitor.states

    private var noticeJob: Job? = null
    private var bulkJob: Job? = null

    /** Addresses already handled by the expiry watcher (avoids repeated work). */
    private val expiredMarked = mutableSetOf<String>()

    companion object {
        private const val TAG = "MainViewModel"

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
        observeMonitor()
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
                    monitor.stop(entry.address)
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
     * Create one temp inbox. Stops early on the free-tier daily quota or on HTTP 429.
     */
    fun createInbox() {
        refreshQuota()
        if (!hasQuota()) return

        viewModelScope.launch {
            _uiState.value = UiState.Error("Creating inbox...")
            val address = createSingleInbox()
            if (address != null) {
                ClipboardHelper.copyText(getApplication(), "Temp Email", address)
                saveState()
            } else {
                restoreUiAfterFailure()
            }
        }
    }

    /**
     * Creates up to [count] inboxes for parallel monitoring. Requests go out in groups of
     * [BulkCreate.CONCURRENCY] to stay far below the 20 creations/minute limit.
     */
    fun createInboxes(count: Int = BulkCreate.MAX_PARALLEL) {
        refreshQuota()
        if (bulkJob?.isActive == true) return

        val toCreate = BulkCreate.plan(count, dailyLimit - _creationsToday.value)
        if (toCreate <= 0) {
            showNotice("Aaj ki $dailyLimit inbox limit puri ho gayi. Kal naya bana sakte ho (ya Pro plan).")
            return
        }

        bulkJob = viewModelScope.launch {
            _bulkProgress.value = 0 to toCreate
            var created = 0
            var done = 0
            for (start in 0 until toCreate step BulkCreate.CONCURRENCY) {
                val end = minOf(start + BulkCreate.CONCURRENCY, toCreate)
                val results = (start until end).map { index ->
                    async {
                        delay(index * 150L)
                        createSingleInbox()
                    }
                }.awaitAll()
                created += results.count { it != null }
                done = end
                _bulkProgress.value = done to toCreate
            }
            _bulkProgress.value = null
            saveState()
            showNotice(
                if (created == toCreate) {
                    "$created inboxes created and monitoring."
                } else {
                    "$created/$toCreate inboxes created (rate limit?)."
                }
            )
        }
    }

    /** @return the new address, or null on failure. Registers quota and starts monitoring. */
    private suspend fun createSingleInbox(): String? {
        return try {
            val response = api.createInbox()
            val address = response.address
            if (!response.ok || address == null) {
                Log.w(TAG, "Create failed: ${response.error}")
                return null
            }
            // The server tells us when the inbox dies; fall back to 60 minutes.
            val expiresAt = InboxClock.parseExpiry(response.expires)
                ?: (System.currentTimeMillis() + InboxClock.FREE_LIFETIME_MS)
            val entry = InboxEntry(address = address, expiresAt = expiresAt)
            _inboxes.value = _inboxes.value + entry
            _activeIndex.value = _inboxes.value.size - 1
            registerCreation()
            monitor.start(address)
            _uiState.value = UiState.Ready(_inboxes.value, _activeIndex.value)
            address
        } catch (e: HttpException) {
            if (e.code() == 429) {
                Log.w(TAG, "Inbox creation rate limited", e)
                showNotice("Rate limit: free tier me 10 inbox/day aur 20/min. Thodi der baad try karo.")
            } else {
                Log.w(TAG, "Create failed", e)
            }
            null
        } catch (e: Exception) {
            Log.w(TAG, "Create failed", e)
            null
        }
    }

    private fun hasQuota(): Boolean {
        if (_creationsToday.value >= dailyLimit) {
            showNotice("Aaj ki $dailyLimit inbox limit puri ho gayi. Kal naya bana sakte ho (ya Pro plan).")
            return false
        }
        return true
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
                    if (!monitor.isOpen(address)) startWebSocket(address)
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

    /** Extends every live inbox by 10 minutes, three requests at a time. */
    fun extendAll() {
        val addresses = _inboxes.value.filter { !it.isExpired() }.map { it.address }
        if (addresses.isEmpty()) return

        viewModelScope.launch {
            var extended = 0
            for (chunk in addresses.chunked(BulkCreate.CONCURRENCY)) {
                val results = chunk.map { address ->
                    async {
                        address to runCatching { api.extendInbox(address).ok }.getOrDefault(false)
                    }
                }.awaitAll()

                val ok = results.filter { it.second }.map { it.first }
                if (ok.isNotEmpty()) {
                    val current = _inboxes.value.toMutableList()
                    ok.forEach { address ->
                        val idx = current.indexOfFirst { it.address == address }
                        if (idx >= 0) {
                            val base = maxOf(
                                current[idx].expiresAt ?: 0L,
                                System.currentTimeMillis()
                            )
                            current[idx] = current[idx].copy(
                                expiresAt = base + InboxClock.EXTEND_MS
                            )
                        }
                    }
                    _inboxes.value = current
                    publishReady(current)
                    extended += ok.size
                }
            }
            saveState()
            showNotice("Extended $extended inbox(es) by 10 minutes.")
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
                    it.isExpired(now) && expiredMarked.add(it.address)
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
    //  Monitoring — sockets are owned by InboxMonitor
    // ────────────────────────────────────────────────────────────

    /**
     * Asks the shared monitor to watch this inbox. No socket is opened here any more, so the
     * foreground service and the UI cannot steal each other's connection (F-06).
     */
    fun startWebSocket(address: String, inboxIndex: Int = 0) {
        monitor.start(address, inboxIndex * InboxMonitor.STAGGER_MS)
    }

    /** Feeds monitor events into the UI state and copies an OTP only while visible. */
    private fun observeMonitor() {
        viewModelScope.launch {
            monitor.events.collect { event ->
                addOrUpdateEmail(event.address, event.email)
                val otp = event.otp
                if (otp != null) {
                    setLastOtp(event.address, otp, event.email.fromAddress)
                    val active = _inboxes.value.getOrNull(_activeIndex.value)
                    if (AppVisibility.isForeground && active?.address == event.address) {
                        ClipboardHelper.copyText(getApplication(), "OTP Code", otp)
                    }
                }
            }
        }
        viewModelScope.launch {
            monitor.gone.collect { address -> markExpired(address) }
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

                // Seed the monitor's dedupe set: these messages already existed, so they must
                // not be replayed as notifications or copied on every launch (F-04).
                monitor.markSeen(address, response.emails.map { it.id })

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

    /** Store the last OTP without any copy/notify side effects. */
    private fun setLastOtp(address: String, otp: String, from: String) {
        val current = _inboxes.value.toMutableList()
        val idx = current.indexOfFirst { it.address == address }
        if (idx < 0) return
        current[idx] = current[idx].copy(lastOtp = otp, lastOtpFrom = from)
        _inboxes.value = current
        publishReady(current)
        saveState()
    }

    // ────────────────────────────────────────────────────────────
    //  Inbox management
    // ────────────────────────────────────────────────────────────
    fun stopWebSocket(address: String) {
        monitor.stop(address)
    }

    fun stopAllWebSockets() {
        monitor.stopAll()
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

    fun getAllAddresses(): List<String> =
        _inboxes.value.filter { !it.isExpired() }.map { it.address }

    override fun onCleared() {
        super.onCleared()
        // Do not stop the monitor: the foreground service may still be using these sockets.
        saveState()
    }
}
