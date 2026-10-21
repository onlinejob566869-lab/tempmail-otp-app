package com.tempmail.otp.data

import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path

/**
 * MailboxTemp API — free, no auth, no API key.
 * https://mailboxtemp.com/docs/
 */
interface MailboxTempApi {

    /** Create a new disposable inbox */
    @POST("api/inbox/generate")
    suspend fun createInbox(): InboxResponse

    /** Inbox metadata — remaining lifetime is in [InboxMeta.ttl] seconds. */
    @GET("api/inbox/{address}")
    suspend fun getInbox(@Path("address") address: String): InboxMeta

    /** Extend a live inbox by 10 minutes. Has no effect on an expired inbox. */
    @POST("api/inbox/{address}/extend")
    suspend fun extendInbox(@Path("address") address: String): OkResponse

    /** List messages in an inbox (newest first) */
    @GET("api/inbox/{address}/emails")
    suspend fun listEmails(@Path("address") address: String): EmailListResponse

    /** Fetch one full message */
    @GET("api/email/{address}/{id}")
    suspend fun readEmail(
        @Path("address") address: String,
        @Path("id") id: String
    ): EmailDetailResponse
}
