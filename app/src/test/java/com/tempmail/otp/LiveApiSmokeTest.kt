package com.tempmail.otp

import com.tempmail.otp.data.ApiClient
import com.tempmail.otp.data.EmailSummary
import com.tempmail.otp.data.MailboxWebSocketClient
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Live smoke test against the real MailboxTemp API.
 *
 * Excluded from the default `test` task so CI stays offline and deterministic.
 * Run it with: `./gradlew testDebugUnitTest -Plive`
 *
 * It uses one inbox from the free-tier quota (20 creations/min, 10/day).
 */
class LiveApiSmokeTest {

    @Test
    fun `create inbox list emails and connect websocket`() = runBlocking {
        val inbox = ApiClient.api.createInbox()
        assertTrue("generate failed: ${inbox.error}", inbox.ok)
        val address = inbox.address
        assertNotNull("generate returned no address", address)

        val list = ApiClient.api.listEmails(address!!)
        assertTrue("list failed: ${list.error}", list.ok)
        assertTrue("a brand new inbox should be empty", list.emails.isEmpty())

        val latch = CountDownLatch(1)
        var wsError: String? = null
        ApiClient.webSocketClient.connect(address, object : MailboxWebSocketClient.Listener {
            override fun onOpen(address: String) {
                latch.countDown()
            }

            override fun onEmail(address: String, email: EmailSummary) = Unit

            override fun onClosed(address: String) = Unit

            override fun onFailure(address: String, error: String) {
                wsError = error
                latch.countDown()
            }
        })

        val signalled = latch.await(20, TimeUnit.SECONDS)
        ApiClient.webSocketClient.disconnect(address)

        assertTrue(
            "WebSocket did not connect: ${wsError ?: "timed out after 20s"}",
            signalled && wsError == null
        )
    }
}
