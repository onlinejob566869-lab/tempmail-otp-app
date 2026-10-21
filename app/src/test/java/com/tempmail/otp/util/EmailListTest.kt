package com.tempmail.otp.util

import com.tempmail.otp.data.EmailSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EmailListTest {

    private fun email(id: String, otp: String? = null) = EmailSummary(
        id = id,
        fromAddress = "no-reply@example.com",
        subject = "Subject $id",
        otpCode = otp
    )

    @Test
    fun `new email is prepended`() {
        val merged = EmailList.merge(listOf(email("old")), email("new"))

        assertEquals(listOf("new", "old"), merged.map { it.id })
    }

    @Test
    fun `duplicate id is ignored`() {
        val existing = listOf(email("a"), email("b"))

        val merged = EmailList.merge(existing, email("a"))

        assertEquals(listOf("a", "b"), merged.map { it.id })
        assertTrue("must return the same list instance for a duplicate", merged === existing)
    }

    @Test
    fun `list is capped at the limit keeping the newest`() {
        val existing = (1..50).map { email("old-$it") }

        val merged = EmailList.merge(existing, email("newest"))

        assertEquals(EmailList.MAX_EMAILS, merged.size)
        assertEquals("newest", merged.first().id)
        assertEquals("old-49", merged.last().id)
    }

    @Test
    fun `newest otp picks the first message with a code`() {
        val emails = listOf(
            email("newest", otp = null),
            email("middle", otp = "482913"),
            email("oldest", otp = "111111")
        )

        assertEquals("middle", EmailList.newestOtp(emails)?.id)
    }

    @Test
    fun `newest otp is null when no message has a code`() {
        assertNull(EmailList.newestOtp(listOf(email("a"), email("b"))))
        assertNull(EmailList.newestOtp(emptyList()))
    }
}
