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
}
