package com.tempmail.otp.util

import java.time.Instant
import java.time.OffsetDateTime
import java.util.Locale

/**
 * Pure time helpers for inbox lifetime handling (unit-tested, no Android deps).
 */
object InboxClock {

    /** Free inboxes live 60 minutes. */
    const val FREE_LIFETIME_MS = 60L * 60L * 1000L

    /** `POST /api/inbox/{address}/extend` adds 10 minutes. */
    const val EXTEND_MS = 10L * 60L * 1000L

    /** Parses the API's ISO-8601 `expires` / `expires_at` value into epoch millis. */
    fun parseExpiry(iso: String?): Long? {
        if (iso.isNullOrBlank()) return null
        return runCatching { Instant.parse(iso).toEpochMilli() }.getOrNull()
            ?: runCatching { OffsetDateTime.parse(iso).toInstant().toEpochMilli() }.getOrNull()
    }

    /** "12:34" / "1:02:03" while alive, "Expired" once the deadline has passed. */
    fun formatRemaining(remainingMs: Long): String {
        if (remainingMs <= 0L) return "Expired"
        val totalSeconds = remainingMs / 1000L
        val hours = totalSeconds / 3600L
        val minutes = (totalSeconds % 3600L) / 60L
        val seconds = totalSeconds % 60L
        return if (hours > 0L) {
            String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.US, "%d:%02d", minutes, seconds)
        }
    }
}
