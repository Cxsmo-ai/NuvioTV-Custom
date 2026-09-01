package com.nuvio.tv.ui.screens.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoOutputPolicyTest {

    @Test
    fun `auto keeps native output on hdr display`() {
        val decision = VideoOutputPolicy.decide(
            forceSdrOutput = false,
            outputSupportsHdr = true
        )

        assertFalse(decision.prepareSdrOutputGraph)
        assertFalse(decision.shouldToneMapInput(inputIsHdr = false))
        assertFalse(decision.shouldToneMapInput(inputIsHdr = true))
    }

    @Test
    fun `auto only tone maps hdr input on sdr display`() {
        val decision = VideoOutputPolicy.decide(
            forceSdrOutput = false,
            outputSupportsHdr = false
        )

        assertTrue(decision.prepareSdrOutputGraph)
        assertFalse(decision.shouldToneMapInput(inputIsHdr = false))
        assertTrue(decision.shouldToneMapInput(inputIsHdr = true))
    }

    @Test
    fun `force sdr overrides optimistic hdr capability`() {
        val decision = VideoOutputPolicy.decide(
            forceSdrOutput = true,
            outputSupportsHdr = true
        )

        assertTrue(decision.prepareSdrOutputGraph)
        assertFalse(decision.shouldToneMapInput(inputIsHdr = false))
        assertTrue(decision.shouldToneMapInput(inputIsHdr = true))
    }
}
