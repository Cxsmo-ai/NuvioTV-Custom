package com.nuvio.tv.core.player.effects

import androidx.media3.common.C
import androidx.media3.common.ColorInfo
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import com.nuvio.tv.data.local.PlayerSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SmartVibrancePlusEffectTest {

    @Test
    fun supportGate_waitsForFormatAndAcceptsSdr() {
        assertFalse(supportsSmartVibrancePlus(null))
        assertTrue(
            supportsSmartVibrancePlus(
                Format.Builder()
                    .setSampleMimeType(MimeTypes.VIDEO_H264)
                    .setColorInfo(ColorInfo.SDR_BT709_LIMITED)
                    .build()
            )
        )
    }

    @Test
    fun supportGate_acceptsHdrTransfersAndRejectsNativeDolbyVision() {
        val pq = ColorInfo.Builder()
            .setColorTransfer(C.COLOR_TRANSFER_ST2084)
            .build()
        val hlg = ColorInfo.Builder()
            .setColorTransfer(C.COLOR_TRANSFER_HLG)
            .build()

        assertTrue(
            supportsSmartVibrancePlus(
                Format.Builder().setSampleMimeType(MimeTypes.VIDEO_H265).setColorInfo(pq).build()
            )
        )
        assertTrue(
            supportsSmartVibrancePlus(
                Format.Builder().setSampleMimeType(MimeTypes.VIDEO_H265).setColorInfo(hlg).build()
            )
        )
        assertFalse(
            supportsSmartVibrancePlus(
                Format.Builder().setSampleMimeType(MimeTypes.VIDEO_DOLBY_VISION).build()
            )
        )
        assertFalse(
            supportsSmartVibrancePlus(
                Format.Builder().setSampleMimeType(MimeTypes.VIDEO_H265).setCodecs("dvhe.07.06").build()
            )
        )
    }

    @Test
    fun applicationGate_enablesSdrNativeHdrAndHdrToSdrPaths() {
        assertTrue(shouldApplySmartVibrance(SmartVibranceRuntimeStatus.ACTIVE))
        assertTrue(shouldApplySmartVibrance(SmartVibranceRuntimeStatus.ACTIVE_NATIVE_HDR))
        assertTrue(shouldApplySmartVibrance(SmartVibranceRuntimeStatus.ACTIVE_TONEMAPPED_HDR))
        assertFalse(shouldApplySmartVibrance(SmartVibranceRuntimeStatus.BYPASSED_HDR))
        assertFalse(shouldApplySmartVibrance(SmartVibranceRuntimeStatus.BYPASSED_MPV))
        assertFalse(shouldApplySmartVibrance(SmartVibranceRuntimeStatus.FAILED))
    }

    @Test
    fun forceSdr_defaultsOnAndUsesANoOpGraphTrigger() {
        assertFalse(PlayerSettings().forceSdrOutput)
        assertTrue(ForceSdrOutputEffect().isNoOp(3840, 2160))
        assertEquals(
            1,
            "texture2D".toRegex().findAll(FORCE_SDR_PASSTHROUGH_FRAGMENT_SHADER).count()
        )
    }

    @Test
    fun shader_preservesUpstreamPlusConstantsAndSingleTexturePass() {
        assertTrue(SMART_VIBRANCE_PLUS_FRAGMENT_SHADER.contains("const float kIntensity = 1.5"))
        assertTrue(SMART_VIBRANCE_PLUS_FRAGMENT_SHADER.contains("const float kSatPivot = 0.5"))
        assertTrue(SMART_VIBRANCE_PLUS_FRAGMENT_SHADER.contains("const float kGrayPivot = 0.003"))
        assertTrue(SMART_VIBRANCE_PLUS_FRAGMENT_SHADER.contains("const float kGraySharpness = 45.0"))
        assertEquals(
            1,
            "texture2D".toRegex().findAll(SMART_VIBRANCE_PLUS_FRAGMENT_SHADER).count()
        )
    }
}
