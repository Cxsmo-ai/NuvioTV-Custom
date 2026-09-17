package com.nuvio.tv.ui.screens.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SeekrFrameCalibrationTest {
    @Test
    fun medianRejectsOneBadDecoderLanding() {
        val anchors = listOf(
            anchor(expected = 100_000, best = 103_000, score = 0.92),
            anchor(expected = 500_000, best = 503_000, score = 0.91),
            anchor(expected = 800_000, best = 799_000, score = 0.93),
            anchor(expected = 1_100_000, best = 1_250_000, score = 0.94)
        )

        val result = SeekrFrameCalibrator.estimate(anchors, playbackToSourceScale = 1.0)

        assertTrue(result.calibrated)
        assertEquals(-3_000, result.seekrOffsetMs)
        assertEquals(3, result.anchorsUsed)
    }

    @Test
    fun ambiguousMatchesAreNotApplied() {
        val anchors = listOf(
            SeekrCalibrationAnchor(
                cueStartMs = 10_000,
                expectedPlaybackMs = 10_000,
                candidates = listOf(
                    SeekrCalibrationCandidate(10_000, 0.81),
                    SeekrCalibrationCandidate(12_000, 0.80)
                )
            ),
            anchor(expected = 500_000, best = 501_000, score = 0.90)
        )

        val result = SeekrFrameCalibrator.estimate(anchors, playbackToSourceScale = 1.0)

        assertFalse(result.calibrated)
        assertEquals(0, result.seekrOffsetMs)
    }

    @Test
    fun sourceScaleConvertsPlaybackDriftToSeekrOffset() {
        val result = SeekrFrameCalibrator.estimate(
            anchors = listOf(
                anchor(expected = 100_000, best = 104_000, score = 0.90),
                anchor(expected = 500_000, best = 504_000, score = 0.91)
            ),
            playbackToSourceScale = 2.0
        )

        assertTrue(result.calibrated)
        assertEquals(-2_000, result.seekrOffsetMs)
    }

    private fun anchor(expected: Long, best: Long, score: Double) =
        SeekrCalibrationAnchor(
            cueStartMs = expected,
            expectedPlaybackMs = expected,
            candidates = listOf(
                SeekrCalibrationCandidate(best, score),
                SeekrCalibrationCandidate(expected, score - 0.15)
            )
        )
}
