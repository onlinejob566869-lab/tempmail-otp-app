package com.tempmail.otp.util

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

class DailyLimitTest {

    private fun millis(iso: String) = Instant.parse(iso).toEpochMilli()

    @Test
    fun `day key is the utc date`() {
        assertEquals("2026-10-21", DailyLimit.dayKey(millis("2026-10-21T00:00:00Z")))
        assertEquals("2026-10-21", DailyLimit.dayKey(millis("2026-10-21T23:59:59Z")))
        assertEquals("2026-10-22", DailyLimit.dayKey(millis("2026-10-22T00:00:00Z")))
    }

    @Test
    fun `count is kept on the same day`() {
        assertEquals(3, DailyLimit.usedToday(millis("2026-10-21T10:00:00Z"), "2026-10-21", 3))
    }

    @Test
    fun `count resets on a new day`() {
        assertEquals(0, DailyLimit.usedToday(millis("2026-10-22T00:01:00Z"), "2026-10-21", 7))
    }

    @Test
    fun `missing or negative stored values are treated as zero`() {
        assertEquals(0, DailyLimit.usedToday(millis("2026-10-21T10:00:00Z"), null, 7))
        assertEquals(0, DailyLimit.usedToday(millis("2026-10-21T10:00:00Z"), "2026-10-21", -2))
    }

    @Test
    fun `free tier limit is ten per day`() {
        assertEquals(10, DailyLimit.FREE_DAILY_LIMIT)
    }
}
