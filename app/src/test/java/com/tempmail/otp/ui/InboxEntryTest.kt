package com.tempmail.otp.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InboxEntryTest {

    private fun entry(expiresAt: Long?) = InboxEntry(address = "abc@example.com", expiresAt = expiresAt)

    @Test
    fun `expired when the deadline has passed`() {
        assertTrue(entry(1_000L).isExpired(now = 1_000L))
        assertTrue(entry(1_000L).isExpired(now = 2_000L))
    }

    @Test
    fun `alive before the deadline`() {
        assertFalse(entry(2_000L).isExpired(now = 1_000L))
    }

    @Test
    fun `an inbox without a deadline never expires`() {
        assertFalse(entry(null).isExpired(now = Long.MAX_VALUE))
    }

    @Test
    fun `label is derived from the local part`() {
        assertTrue(entry(null).label == "abc")
    }
}
