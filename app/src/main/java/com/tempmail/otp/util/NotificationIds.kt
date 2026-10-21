package com.tempmail.otp.util

/**
 * Stable notification ids, so parallel inboxes do not overwrite each other's OTP alerts.
 */
object NotificationIds {

    private const val BASE = 2_000

    /** Deterministic id in [BASE, BASE + 4095] for an inbox address. */
    fun forAddress(address: String): Int = BASE + (address.hashCode() and 0x0FFF)
}
