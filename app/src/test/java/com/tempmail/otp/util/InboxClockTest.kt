package com.tempmail.otp.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class InboxClockTest {

    @Test
    fun `parses iso timestamp to epoch millis`() {
        assertEquals(1_000L, InboxClock.parseExpiry("1970-01-01T00:00:01Z"))
    }

    @Test
    fun `parses fractional seconds from the api`() {
        val millis = InboxClock.parseExpiry("2026-08-23T18:23:26.684Z")

        assertEquals(684L, millis!! % 1000L)
    }

    @Test
    fun `parses timestamps with an offset`() {
        assertEquals(
            InboxClock.parseExpiry("2026-08-23T18:23:26Z"),
            InboxClock.parseExpiry("2026-08-23T20:23:26+02:00")
        )
    }

    @Test
    fun `returns null for blank or invalid input`() {
        assertNull(InboxClock.parseExpiry(null))
        assertNull(InboxClock.parseExpiry(""))
        assertNull(InboxClock.parseExpiry("not-a-date"))
    }

    @Test
    fun `formats minutes and seconds`() {
        assertEquals("1:05", InboxClock.formatRemaining(65_000L))
        assertEquals("0:59", InboxClock.formatRemaining(59_000L))
        assertEquals("59:59", InboxClock.formatRemaining(3_599_000L))
    }

    @Test
    fun `formats hours when more than an hour remains`() {
        assertEquals("1:00:00", InboxClock.formatRemaining(3_600_000L))
        assertEquals("1:01:01", InboxClock.formatRemaining(3_661_000L))
    }

    @Test
    fun `shows expired at or below zero`() {
        assertEquals("Expired", InboxClock.formatRemaining(0L))
        assertEquals("Expired", InboxClock.formatRemaining(-5_000L))
    }
}
