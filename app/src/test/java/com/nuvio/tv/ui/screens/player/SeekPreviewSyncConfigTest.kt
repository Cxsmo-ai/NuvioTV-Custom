package com.nuvio.tv.ui.screens.player

import org.junit.Assert.assertEquals
import org.junit.Test

class SeekPreviewSyncConfigTest {
    @Test
    fun usesFineStepsThenCoarseStepsForHeldRemoteInput() {
        assertEquals(250, seekPreviewOffsetStepMs(0, forward = true))
        assertEquals(-250, seekPreviewOffsetStepMs(2, forward = false))
        assertEquals(2_000, seekPreviewOffsetStepMs(3, forward = true))
    }

    @Test
    fun formatsPositiveAndNegativeOffsetsWithStableMilliseconds() {
        assertEquals("+1.250s", formatSeekPreviewOffset(1_250))
        assertEquals("-0.250s", formatSeekPreviewOffset(-250))
    }
}
