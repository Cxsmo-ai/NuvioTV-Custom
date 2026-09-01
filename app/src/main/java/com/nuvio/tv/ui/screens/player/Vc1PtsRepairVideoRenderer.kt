package com.nuvio.tv.ui.screens.player

import android.content.Context
import android.util.Log
import androidx.media3.common.Format
import androidx.media3.exoplayer.mediacodec.MediaCodecAdapter
import androidx.media3.exoplayer.video.MediaCodecVideoRenderer
import androidx.media3.exoplayer.video.PlaybackVideoGraphWrapper
import androidx.media3.exoplayer.video.VideoFrameReleaseControl
import java.nio.ByteBuffer
import kotlin.math.abs

/**
 * VC-1 presentation-timestamp repair renderer.
 *
 * The Amlogic VC-1 hardware decoder (c2.amlogic.vc1.decoder), in the non-tunnel
 * stream-mode path used on this platform, emits decoded pictures in correct display
 * order but attaches mis-associated presentation timestamps: the pts labels form a
 * period-N sawtooth (proven on AM9 Pro). media3's non-tunnel VideoFrameReleaseControl
 * then late-drops every label that lands behind the clock, dropping ~29% of frames.
 *
 * This renderer repairs the labels before media3's early/late maths runs: it detects
 * the sawtooth (backward pts steps, which genuine VFR never produces) and, once
 * engaged, rewrites each VC-1 frame's presentation time to a monotone grid
 * (base + frameIndex * frameDuration), re-anchoring on any on-grid label so rounding
 * drift cannot accumulate. Because the pictures are already in display order,
 * sequential grid timestamps reconstruct the correct timeline. Non-VC-1 formats and
 * healthy VC-1 streams pass through untouched.
 *
 * State advances ONLY when super.processOutputBuffer consumes the frame. media3
 * re-presents a held (early) buffer on the next render iteration; advancing on every
 * call would race frameIndex ahead of the true frame and run the grid into the
 * future, freezing video.
 */
internal class Vc1PtsRepairVideoRenderer(
    builder: MediaCodecVideoRenderer.Builder,
    private val forceSdrOutput: Boolean = true
) : MediaCodecVideoRenderer(builder) {

    override fun createPlaybackVideoGraphWrapper(
        context: Context,
        videoFrameReleaseControl: VideoFrameReleaseControl
    ): PlaybackVideoGraphWrapper =
        super.createPlaybackVideoGraphWrapper(context, videoFrameReleaseControl).also { graph ->
            graph.setRequestOpenGlToneMapping(forceSdrOutput)
        }

    private enum class Mode { WATCH, ENGAGED }

    private var checkedFormat: Format? = null
    private var isVc1 = false
    private var frameDurationUs = 0L

    private var mode = Mode.WATCH
    private var baseUs = 0L
    private var baseSet = false
    private var frameIndex = 0L
    private var prevLabelUs = Long.MIN_VALUE
    private var backSteps = 0

    private var processed = 0L
    private var accepted = 0L
    private var substituted = 0L
    private var loggedEngage = false

    override fun processOutputBuffer(
        positionUs: Long,
        elapsedRealtimeUs: Long,
        codec: MediaCodecAdapter?,
        buffer: ByteBuffer?,
        bufferIndex: Int,
        bufferFlags: Int,
        sampleCount: Int,
        bufferPresentationTimeUs: Long,
        isDecodeOnlyBuffer: Boolean,
        isLastBuffer: Boolean,
        format: Format
    ): Boolean {
        val repairedUs = computeRepairedUs(bufferPresentationTimeUs, format)
        val consumed = super.processOutputBuffer(
            positionUs,
            elapsedRealtimeUs,
            codec,
            buffer,
            bufferIndex,
            bufferFlags,
            sampleCount,
            repairedUs,
            isDecodeOnlyBuffer,
            isLastBuffer,
            format
        )
        if (consumed && isVc1 && frameDurationUs > 0L) {
            commitFrame(bufferPresentationTimeUs)
        }
        return consumed
    }

    /** Computes the repaired pts for the current frame WITHOUT mutating advance state. */
    private fun computeRepairedUs(labelUs: Long, format: Format): Long {
        if (format !== checkedFormat) {
            checkedFormat = format
            val mime = format.sampleMimeType
            isVc1 = mime == MIME_VC1 || mime == MIME_WVC1
            if (isVc1 && format.frameRate > 0f) {
                frameDurationUs = (1_000_000.0 / format.frameRate).toLong()
            }
        }
        if (!isVc1 || frameDurationUs <= 0L) return labelUs
        if (!baseSet) {
            baseUs = labelUs
            baseSet = true
        }
        if (mode == Mode.WATCH) return labelUs
        val gridUs = baseUs + frameIndex * frameDurationUs
        val half = frameDurationUs / 2
        return if (abs(labelUs - gridUs) <= half) labelUs else gridUs
    }

    /** Advances state after a frame is actually consumed (rendered/dropped/skipped). */
    private fun commitFrame(labelUs: Long) {
        val half = frameDurationUs / 2
        if (mode == Mode.WATCH) {
            if (prevLabelUs != Long.MIN_VALUE && labelUs < prevLabelUs - half) {
                backSteps++
                if (backSteps >= ENGAGE_AFTER_BACKSTEPS) {
                    mode = Mode.ENGAGED
                    if (!loggedEngage) {
                        loggedEngage = true
                        Log.i(TAG, "engaged: vc1 pts sawtooth detected, durUs=$frameDurationUs idx=$frameIndex")
                    }
                }
            }
        } else {
            val gridUs = baseUs + frameIndex * frameDurationUs
            if (abs(labelUs - gridUs) <= half) {
                accepted++
                baseUs = labelUs - frameIndex * frameDurationUs
            } else {
                substituted++
            }
        }
        prevLabelUs = labelUs
        frameIndex++
        processed++
        if (processed % LOG_EVERY == 0L) {
            Log.i(TAG, "processed=$processed accepted=$accepted substituted=$substituted")
        }
    }

    override fun onPositionReset(positionUs: Long, joining: Boolean) {
        // Re-anchor after seek: back to WATCH, re-detect. Keep frameDurationUs
        // (format unchanged across a seek).
        mode = Mode.WATCH
        baseSet = false
        frameIndex = 0L
        prevLabelUs = Long.MIN_VALUE
        backSteps = 0
        loggedEngage = false
        super.onPositionReset(positionUs, joining)
    }

    private companion object {
        const val TAG = "VC1_RESTAMP"
        const val MIME_VC1 = "video/vc1"
        const val MIME_WVC1 = "video/wvc1"
        const val ENGAGE_AFTER_BACKSTEPS = 2
        const val LOG_EVERY = 500L
    }
}
