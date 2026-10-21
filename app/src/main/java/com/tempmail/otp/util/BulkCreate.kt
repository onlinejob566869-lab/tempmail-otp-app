package com.tempmail.otp.util

/**
 * Pure planning helpers for bulk inbox creation. The free tier allows 10 new inboxes
 * per day, so "10 parallel" is exactly the daily budget.
 */
object BulkCreate {

    /** Free-tier maximum number of inboxes to create in one batch. */
    const val MAX_PARALLEL = 10

    /** Requests are sent in small groups to stay well inside 20 creations/minute. */
    const val CONCURRENCY = 3

    /** How many inboxes we may actually create right now. */
    fun plan(requested: Int, remainingQuota: Int, max: Int = MAX_PARALLEL): Int =
        requested.coerceAtLeast(0)
            .coerceAtMost(remainingQuota.coerceAtLeast(0))
            .coerceAtMost(max.coerceAtLeast(0))
}
