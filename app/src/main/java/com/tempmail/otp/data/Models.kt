package com.tempmail.otp.data

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

/**
 * Response from POST /api/inbox/generate
 */
@JsonClass(generateAdapter = true)
data class InboxResponse(
    val ok: Boolean = false,
    val address: String? = null,
    val error: String? = null
)

/**
 * Single email summary from GET /api/inbox/{address}/emails
 */
@JsonClass(generateAdapter = true)
data class EmailSummary(
    val id: String = "",
    @Json(name = "from_address") val fromAddress: String = "",
    @Json(name = "from_name") val fromName: String? = null,
    val subject: String = "",
    @Json(name = "otp_code") val otpCode: String? = null,
    @Json(name = "has_attachments") val hasAttachments: Boolean = false,
    val read: Boolean = false,
    @Json(name = "received_at") val receivedAt: String = ""
)

/**
 * Response from GET /api/inbox/{address}/emails
 */
@JsonClass(generateAdapter = true)
data class EmailListResponse(
    val ok: Boolean = false,
    val emails: List<EmailSummary> = emptyList(),
    val error: String? = null
)

/**
 * Full email body from GET /api/email/{address}/{id}
 */
@JsonClass(generateAdapter = true)
data class EmailDetail(
    val id: String = "",
    @Json(name = "from_address") val fromAddress: String = "",
    val subject: String = "",
    @Json(name = "body_html") val bodyHtml: String? = null,
    @Json(name = "body_text") val bodyText: String? = null,
    @Json(name = "otp_code") val otpCode: String? = null,
    val read: Boolean = false,
    @Json(name = "received_at") val receivedAt: String = ""
)

@JsonClass(generateAdapter = true)
data class EmailDetailResponse(
    val ok: Boolean = false,
    val email: EmailDetail? = null,
    val error: String? = null
)
