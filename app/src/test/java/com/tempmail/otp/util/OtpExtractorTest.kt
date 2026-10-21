package com.tempmail.otp.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OtpExtractorTest {

    @Test
    fun `extracts six digit code from plain text`() {
        assertEquals("482913", OtpExtractor.extract("Your verification code is 482913"))
    }

    @Test
    fun `extracts code from html body`() {
        assertEquals("482913", OtpExtractor.extract("<p>Your code is 482913</p>"))
    }

    @Test
    fun `extracts four digit code`() {
        assertEquals("1234", OtpExtractor.extract("OTP: 1234"))
    }

    @Test
    fun `extracts eight digit code`() {
        assertEquals("12345678", OtpExtractor.extract("Code 12345678"))
    }

    @Test
    fun `ignores numbers shorter than four digits`() {
        assertNull(OtpExtractor.extract("Order 123 shipped"))
    }

    @Test
    fun `ignores numbers longer than eight digits`() {
        assertNull(OtpExtractor.extract("Tracking 1234567890"))
    }

    @Test
    fun `returns null for blank and null input`() {
        assertNull(OtpExtractor.extract(null))
        assertNull(OtpExtractor.extract(""))
        assertNull(OtpExtractor.extract("   \n"))
    }

    /** Characterisation of the known limitation: the first number wins. */
    @Test
    fun `first number wins when the body has several`() {
        assertEquals("12345", OtpExtractor.extract("Order 12345, verification code 987654"))
    }

    /** Characterisation of the known limitation: a four digit year is a valid candidate. */
    @Test
    fun `four digit year is picked up`() {
        assertEquals("2024", OtpExtractor.extract("Your account was created in 2024"))
    }
}
