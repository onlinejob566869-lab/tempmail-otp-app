package com.tempmail.otp.util

import java.time.Instant
import java.time.ZoneOffset

/**
 * Free-tier inbox creation quota. MailboxTemp allows 10 new inboxes per day and
 * 20 creations per minute per IP; the daily cap is the binding one.
 */
object DailyLimit {

    const val FREE_DAILY_LIMIT = 10

    /** UTC day key, e.g. "2026-10-21". */
    fun dayKey(nowMillis: Long = System.currentTimeMillis()): String =
        Instant.ofEpochMilli(nowMillis).atZone(ZoneOffset.UTC).toLocalDate().toString()

    /** How many creations were already used today, resetting on a new UTC day. */
    fun usedToday(nowMillis: Long, storedDay: String?, storedCount: Int): Int =
        if (storedDay == dayKey(nowMillis)) storedCount.coerceAtLeast(0) else 0
}
