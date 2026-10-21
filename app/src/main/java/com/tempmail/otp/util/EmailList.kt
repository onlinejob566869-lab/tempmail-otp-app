package com.tempmail.otp.util

import com.tempmail.otp.data.EmailSummary

/**
 * Pure list handling for one inbox. Kept out of the ViewModel so it can be unit-tested
 * without Android dependencies.
 */
object EmailList {

    /** The API lists at most 50 messages per inbox. */
    const val MAX_EMAILS = 50

    /**
     * Adds [incoming] to [existing] (which is newest-first), ignoring duplicates by id
     * and keeping at most [limit] entries.
     */
    fun merge(
        existing: List<EmailSummary>,
        incoming: EmailSummary,
        limit: Int = MAX_EMAILS
    ): List<EmailSummary> {
        if (existing.any { it.id == incoming.id }) return existing
        return (listOf(incoming) + existing).take(limit)
    }

    /** Newest message that carries an OTP code, or null when there is none. */
    fun newestOtp(emails: List<EmailSummary>): EmailSummary? =
        emails.firstOrNull { !it.otpCode.isNullOrBlank() }
}
