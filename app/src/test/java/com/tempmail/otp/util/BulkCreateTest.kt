package com.tempmail.otp.util

import org.junit.Assert.assertEquals
import org.junit.Test

class BulkCreateTest {

    @Test
    fun `creates at most ten at a time`() {
        assertEquals(10, BulkCreate.plan(requested = 20, remainingQuota = 10))
        assertEquals(10, BulkCreate.plan(requested = 10, remainingQuota = 10))
    }

    @Test
    fun `respects the remaining daily quota`() {
        assertEquals(3, BulkCreate.plan(requested = 10, remainingQuota = 3))
        assertEquals(0, BulkCreate.plan(requested = 10, remainingQuota = 0))
    }

    @Test
    fun `negative or empty requests create nothing`() {
        assertEquals(0, BulkCreate.plan(requested = 0, remainingQuota = 10))
        assertEquals(0, BulkCreate.plan(requested = -5, remainingQuota = 10))
        assertEquals(0, BulkCreate.plan(requested = 10, remainingQuota = -1))
    }

    @Test
    fun `never exceeds the caller supplied maximum`() {
        assertEquals(3, BulkCreate.plan(requested = 10, remainingQuota = 10, max = 3))
    }

    @Test
    fun `parallel target and concurrency are sane`() {
        assertEquals(10, BulkCreate.MAX_PARALLEL)
        assertEquals(3, BulkCreate.CONCURRENCY)
    }
}
