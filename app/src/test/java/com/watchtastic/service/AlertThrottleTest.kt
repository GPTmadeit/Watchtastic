package com.watchtastic.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AlertThrottleTest {

    private var now = 1_000_000L
    private val throttle = AlertThrottle(minIntervalMs = 5_000) { now }

    @Test
    fun `the first alert is always allowed`() {
        assertTrue(throttle.tryAcquire())
    }

    @Test
    fun `alerts inside the window are suppressed`() {
        assertTrue(throttle.tryAcquire())
        now += 4_999
        assertFalse(throttle.tryAcquire())
    }

    @Test
    fun `suppressed alerts do not extend the window`() {
        assertTrue(throttle.tryAcquire())
        now += 3_000
        assertFalse(throttle.tryAcquire())
        now += 2_000 // 5 s after the last *allowed* alert
        assertTrue(throttle.tryAcquire())
    }

    @Test
    fun `a clock that steps backwards does not silence alerts`() {
        assertTrue(throttle.tryAcquire())
        now -= 3_600_000
        assertTrue(throttle.tryAcquire())
    }
}
