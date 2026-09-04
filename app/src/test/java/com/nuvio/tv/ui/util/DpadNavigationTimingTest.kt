package com.nuvio.tv.ui.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DpadNavigationTimingTest {

    @Test
    fun `repeat gate accepts first event and blocks events inside interval`() {
        val gate = DirectionalRepeatGate()

        assertTrue(gate.tryAcquire(direction = 0, nowMs = 1_000L, minimumIntervalMs = 88L))
        assertFalse(gate.tryAcquire(direction = 0, nowMs = 1_087L, minimumIntervalMs = 88L))
        assertTrue(gate.tryAcquire(direction = 0, nowMs = 1_088L, minimumIntervalMs = 88L))
    }

    @Test
    fun `opposite directions do not delay each other`() {
        val gate = DirectionalRepeatGate()

        assertTrue(gate.tryAcquire(direction = 2, nowMs = 5_000L, minimumIntervalMs = 64L))
        assertTrue(gate.tryAcquire(direction = 3, nowMs = 5_001L, minimumIntervalMs = 64L))
    }

    @Test
    fun `release reset makes next repeat immediately eligible`() {
        val gate = DirectionalRepeatGate()

        assertTrue(gate.tryAcquire(direction = 1, nowMs = 10_000L, minimumIntervalMs = 88L))
        gate.reset(direction = 1)
        assertTrue(gate.tryAcquire(direction = 1, nowMs = 10_001L, minimumIntervalMs = 88L))
    }

    @Test
    fun `navigation defaults stay responsive without becoming frame rate repeat`() {
        assertTrue(DpadNavigationTiming.FAST_HORIZONTAL_REPEAT_MS >= 40L)
        assertTrue(DpadNavigationTiming.STANDARD_HORIZONTAL_REPEAT_MS in 56L..80L)
        assertTrue(DpadNavigationTiming.VERTICAL_REPEAT_MS in 80L..100L)
        assertTrue(DpadNavigationTiming.HELD_SCROLL_END_TIMEOUT_MS <= 130L)
    }
}
