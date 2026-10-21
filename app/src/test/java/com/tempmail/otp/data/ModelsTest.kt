package com.tempmail.otp.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract tests for the JSON shapes documented at https://mailboxtemp.com/docs/.
 * These run against the real [ApiClient.moshi] instance, so a broken adapter
 * configuration (or the missing codegen) shows up here.
 */
class ModelsTest {

    private val moshi = ApiClient.moshi

    @Test
    fun `generate response parses documented payload`() {
        val json = """
            {"ok":true,"address":"k3x91qa@fakhita.xyz",
             "expires":"2026-08-23T18:23:26.684Z",
             "inboxId":"0177695b-7bb8-4f1c-9427-0669199be808"}
        """.trimIndent()

        val response = moshi.adapter(InboxResponse::class.java).fromJson(json)!!

        assertTrue(response.ok)
        assertEquals("k3x91qa@fakhita.xyz", response.address)
        assertNull(response.error)
    }

    @Test
    fun `generate response parses rate limit error`() {
        val response = moshi.adapter(InboxResponse::class.java)
            .fromJson("""{"ok":false,"error":"RATE_LIMITED"}""")!!

        assertFalse(response.ok)
        assertNull(response.address)
        assertEquals("RATE_LIMITED", response.error)
    }

    @Test
    fun `email list parses documented payload`() {
        val json = """
            {"ok":true,"emails":[
              {"id":"8ddfa2b4-1111","from_address":"no-reply@example-app.com",
               "from_name":"Example App","subject":"Your verification code",
               "otp_code":"482913","has_attachments":false,"read":false,
               "received_at":"2026-08-23T17:23:43.840Z"}
            ]}
        """.trimIndent()

        val response = moshi.adapter(EmailListResponse::class.java).fromJson(json)!!

        assertTrue(response.ok)
        assertEquals(1, response.emails.size)
        val email = response.emails.first()
        assertEquals("8ddfa2b4-1111", email.id)
        assertEquals("no-reply@example-app.com", email.fromAddress)
        assertEquals("Example App", email.fromName)
        assertEquals("482913", email.otpCode)
        assertFalse(email.hasAttachments)
        assertFalse(email.read)
    }

    @Test
    fun `email list accepts null otp code`() {
        val json = """
            {"ok":true,"emails":[
              {"id":"id-1","from_address":"a@b.c","subject":"Newsletter",
               "otp_code":null,"received_at":"2026-08-23T17:23:43.840Z"}
            ]}
        """.trimIndent()

        val email = moshi.adapter(EmailListResponse::class.java)
            .fromJson(json)!!.emails.single()

        assertNull(email.otpCode)
        assertNull(email.fromName)
    }

    @Test
    fun `email detail parses body and ignores attachments array`() {
        val json = """
            {"ok":true,
             "email":{"id":"id-2","from_address":"no-reply@example-app.com",
                      "subject":"Your code","body_html":"<p>Your code is 482913</p>",
                      "body_text":"Your code is 482913","otp_code":"482913",
                      "read":true,"received_at":"2026-08-23T17:23:43.840Z"},
             "attachments":[{"id":"att-1","filename":"invoice.pdf",
                             "mime_type":"application/pdf","size_bytes":48213}]}
        """.trimIndent()

        val response = moshi.adapter(EmailDetailResponse::class.java).fromJson(json)!!

        assertTrue(response.ok)
        val email = response.email!!
        assertEquals("482913", email.otpCode)
        assertEquals("Your code is 482913", email.bodyText)
        assertTrue(email.bodyHtml!!.contains("482913"))
        assertTrue(email.read)
    }

    @Test
    fun `websocket email object parses into summary`() {
        // Mirrors the payload MailboxWebSocketClient feeds into EmailSummary.
        val json = """
            {"id":"id-3","from_address":"no-reply@example-app.com",
             "from_name":"Example App","subject":"Your verification code",
             "otp_code":"482913","has_attachments":false,"read":false,
             "received_at":"2026-08-23T17:23:43.840Z"}
        """.trimIndent()

        val email = moshi.adapter(EmailSummary::class.java).fromJson(json)!!

        assertEquals("id-3", email.id)
        assertEquals("482913", email.otpCode)
    }

    @Test
    fun `missing optional fields fall back to defaults`() {
        val email = moshi.adapter(EmailSummary::class.java)
            .fromJson("""{"id":"only-id"}""")!!

        assertEquals("only-id", email.id)
        assertEquals("", email.fromAddress)
        assertEquals("", email.subject)
        assertNull(email.otpCode)
        assertFalse(email.read)
    }
}
