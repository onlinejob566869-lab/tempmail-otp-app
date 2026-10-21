package com.tempmail.otp.util

/**
 * Fallback OTP extraction used when the API does not return an `otp_code`
 * (for example when the code only appears inside the message body).
 *
 * MailboxTemp already extracts codes server-side, so this is a safety net.
 * Kept in one place so the foreground ViewModel and the background service
 * cannot drift apart.
 */
object OtpExtractor {

    /** First 4–8 digit sequence that is not part of a longer number. */
    private val CODE_REGEX = Regex("\\b(\\d{4,8})\\b")

    /** Returns the first 4–8 digit code in [text], or null when there is none. */
    fun extract(text: String?): String? {
        if (text.isNullOrBlank()) return null
        return CODE_REGEX.find(text)?.groupValues?.get(1)
    }
}
