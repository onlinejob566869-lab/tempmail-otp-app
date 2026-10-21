package com.tempmail.otp.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationIdsTest {

    @Test
    fun `id is deterministic for an address`() {
        val first = NotificationIds.forAddress("abc123@fakhita.xyz")
        val second = NotificationIds.forAddress("abc123@fakhita.xyz")

        assertEquals(first, second)
    }

    @Test
    fun `ids stay in the dedicated range`() {
        val id = NotificationIds.forAddress("someone@example.com")

        assertTrue("id must not collide with app notification ids", id in 2_000..6_095)
    }

    @Test
    fun `different inboxes usually get different ids`() {
        // The 4096-slot space is plenty for the 10 parallel inboxes the app supports.
        val ids = listOf(
            "alpha@fakhita.xyz",
            "bravo@fakhita.xyz",
            "charlie@fakhita.xyz",
            "delta@fakhita.xyz"
        ).map { NotificationIds.forAddress(it) }

        assertEquals(ids.size, ids.toSet().size)
    }
}
