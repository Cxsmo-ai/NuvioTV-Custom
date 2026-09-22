package com.nuvio.tv.core.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LetterboxRenderPolicyTest {
    @Test
    fun nonAmazonDevicesDefaultToTransparentLetterbox() {
        assertTrue(LetterboxRenderPolicy.defaultTransparentLetterbox("NVIDIA"))
        assertTrue(LetterboxRenderPolicy.defaultTransparentLetterbox(""))
    }

    @Test
    fun AmazonDevicesKeepOpaqueLetterbox() {
        assertFalse(LetterboxRenderPolicy.defaultTransparentLetterbox("Amazon"))
        assertFalse(LetterboxRenderPolicy.defaultTransparentLetterbox(" amazon "))
    }

    @Test
    fun onlyResolvedExoPlayerMayRequestTransparency() {
        assertTrue(LetterboxRenderPolicy.shouldUseTransparentLetterbox(true, "NVIDIA"))
        assertFalse(LetterboxRenderPolicy.shouldUseTransparentLetterbox(false, "NVIDIA"))
        assertFalse(LetterboxRenderPolicy.shouldUseTransparentLetterbox(true, "NVIDIA", exitDispatched = true))
    }
}
