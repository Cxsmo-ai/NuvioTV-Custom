package com.nuvio.tv.ui.screens.player

import android.graphics.Bitmap
import tv.seekr.previews.android.SeekrTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** A single candidate position tested against one Seekr cue. */
internal data class SeekrCalibrationCandidate(
    val positionMs: Long,
    val similarity: Double
)

/** A cue and the local-player positions tested against it. */
internal data class SeekrCalibrationAnchor(
    val cueStartMs: Long,
    val expectedPlaybackMs: Long,
    val candidates: List<SeekrCalibrationCandidate>
)

/**
 * Result of frame-based preview calibration.
 *
 * [seekrOffsetMs] uses Seekr's sign convention: it is added to the lookup
 * position before the VTT cue is resolved. A positive local playback drift
 * therefore produces a negative Seekr offset.
 */
internal data class SeekrFrameAlignment(
    val seekrOffsetMs: Int = 0,
    val confidence: Float = 0f,
    val anchorsUsed: Int = 0,
    val calibrated: Boolean = false
)

internal object SeekrFrameCalibrator {
    const val MIN_SIMILARITY = 0.56
    const val MIN_MARGIN = 0.035
    const val MAX_SEEKR_OFFSET_MS = 240_000

    /**
     * Picks the best local frame for every cue, rejects ambiguous/weak matches,
     * then takes a robust median. Median + MAD is deliberately used instead of
     * a mean: a seek landing on a stale decoder frame must not move the whole
     * title's calibration.
     */
    fun estimate(
        anchors: List<SeekrCalibrationAnchor>,
        playbackToSourceScale: Double,
        minimumSimilarity: Double = MIN_SIMILARITY
    ): SeekrFrameAlignment {
        if (anchors.isEmpty() || playbackToSourceScale <= 0.0) return SeekrFrameAlignment()

        val matches = anchors.mapNotNull { anchor ->
            val ranked = anchor.candidates.sortedByDescending { it.similarity }
            val best = ranked.firstOrNull() ?: return@mapNotNull null
            val second = ranked.getOrNull(1)
            if (best.similarity < minimumSimilarity) return@mapNotNull null
            if (second != null && best.similarity - second.similarity < MIN_MARGIN) {
                return@mapNotNull null
            }
            val playbackDriftMs = best.positionMs - anchor.expectedPlaybackMs
            Match(playbackDriftMs, best.similarity)
        }
        if (matches.size < 2) return SeekrFrameAlignment()

        val sortedDrifts = matches.map { it.playbackDriftMs }.sorted()
        val median = median(sortedDrifts)
        val deviations = sortedDrifts.map { abs(it - median) }.sorted()
        val mad = medianDouble(deviations)
        val tolerance = max(1_250.0, mad * 3.0)
        val inliers = matches.filter { abs(it.playbackDriftMs - median) <= tolerance }
        if (inliers.size < 2) return SeekrFrameAlignment()

        val stableDriftMs = median(inliers.map { it.playbackDriftMs }.sorted())
        val sourceOffsetMs = (-stableDriftMs / playbackToSourceScale)
            .coerceIn(-MAX_SEEKR_OFFSET_MS.toDouble(), MAX_SEEKR_OFFSET_MS.toDouble())
            .toInt()
        val confidence = (inliers.map { it.similarity }.average() *
            (inliers.size.toFloat() / anchors.size.toFloat())).toFloat().coerceIn(0f, 1f)
        return SeekrFrameAlignment(
            seekrOffsetMs = sourceOffsetMs,
            confidence = confidence,
            anchorsUsed = inliers.size,
            calibrated = true
        )
    }

    /** Candidate offsets cover Seekr's documented keyframe error with low startup cost. */
    fun candidatePositions(expectedPlaybackMs: Long): List<Long> =
        CALIBRATION_OFFSETS_MS.map { expectedPlaybackMs + it }

    /**
     * Compares two frames after reducing them to a small luminance grid. This
     * is intentionally CPU-light for Android TV and robust to scale/letterbox
     * differences. The result is normalized cross-correlation in [0, 1].
     */
    fun perceptualSimilarity(first: Bitmap?, second: Bitmap?): Double {
        if (first == null || second == null || first.isRecycled || second.isRecycled) return 0.0
        if (first.width <= 0 || first.height <= 0 || second.width <= 0 || second.height <= 0) return 0.0
        val firstSamples = sampleLuma(first)
        val secondSamples = sampleLuma(second)
        val firstMean = firstSamples.average()
        val secondMean = secondSamples.average()
        var numerator = 0.0
        var firstEnergy = 0.0
        var secondEnergy = 0.0
        for (index in firstSamples.indices) {
            val a = firstSamples[index] - firstMean
            val b = secondSamples[index] - secondMean
            numerator += a * b
            firstEnergy += a * a
            secondEnergy += b * b
        }
        if (firstEnergy <= 1e-9 || secondEnergy <= 1e-9) return 0.0
        return ((numerator / (kotlin.math.sqrt(firstEnergy * secondEnergy))) + 1.0) / 2.0
    }

    private data class Match(val playbackDriftMs: Long, val similarity: Double)

    private fun median(values: List<Long>): Double {
        if (values.isEmpty()) return 0.0
        val middle = values.size / 2
        return if (values.size % 2 == 1) values[middle].toDouble()
        else (values[middle - 1] + values[middle]).toDouble() / 2.0
    }

    private fun medianDouble(values: List<Double>): Double {
        if (values.isEmpty()) return 0.0
        val middle = values.size / 2
        return if (values.size % 2 == 1) values[middle]
        else (values[middle - 1] + values[middle]) / 2.0
    }

    private fun sampleLuma(bitmap: Bitmap): DoubleArray {
        val result = DoubleArray(SAMPLE_WIDTH * SAMPLE_HEIGHT)
        var index = 0
        for (y in 0 until SAMPLE_HEIGHT) {
            val sourceY = min(bitmap.height - 1, y * bitmap.height / SAMPLE_HEIGHT)
            for (x in 0 until SAMPLE_WIDTH) {
                val sourceX = min(bitmap.width - 1, x * bitmap.width / SAMPLE_WIDTH)
                val pixel = bitmap.getPixel(sourceX, sourceY)
                result[index++] =
                    (0.2126 * ((pixel shr 16) and 0xff)) +
                        (0.7152 * ((pixel shr 8) and 0xff)) +
                        (0.0722 * (pixel and 0xff))
            }
        }
        return result
    }

    private const val SAMPLE_WIDTH = 32
    private const val SAMPLE_HEIGHT = 18
    private val CALIBRATION_OFFSETS_MS = listOf(-3_000L, 0L, 3_000L)
}

/** A local frame source used by the bounded calibration pass. */
internal fun interface SeekrCalibrationFrameSource {
    suspend fun captureAt(positionMs: Long): Bitmap?
}

/**
 * Calibrates a Seekr track against a few real frames from the current release.
 * Three anchors are enough to distinguish a constant head offset from a bad
 * individual seek while keeping startup work bounded on Android TV.
 */
internal suspend fun calibrateSeekrTrack(
    track: SeekrTrack,
    playbackDurationMs: Long,
    frameSource: SeekrCalibrationFrameSource
): SeekrFrameAlignment? = withTimeoutOrNull(CALIBRATION_TIMEOUT_MS) {
    if (playbackDurationMs <= 0L || track.isEmpty) return@withTimeoutOrNull null
    track.offsetMs = 0L
    val scale = track.scale.takeIf { it > 0.0 }
        ?: (playbackDurationMs.toDouble() / track.sourceDurationMs.coerceAtLeast(1L))
    val sourceDurationMs = track.sourceDurationMs.takeIf { it > 0L }
        ?: (playbackDurationMs / scale).toLong()
    val sourceAnchors = listOf(0.2, 0.5, 0.8)
        .map { (sourceDurationMs * it).toLong() }
        .filter { it in 15_000L..(sourceDurationMs - 15_000L).coerceAtLeast(15_000L) }
    val anchors = mutableListOf<SeekrCalibrationAnchor>()
    val seenCues = mutableSetOf<Long>()

    for (sourcePositionMs in sourceAnchors) {
        val thumbnail = track.thumbnailFor(sourcePositionMs) ?: continue
        if (!seenCues.add(thumbnail.cueStartMs)) continue
        val expectedPlaybackMs = (thumbnail.cueStartMs * scale).toLong()
            .coerceIn(0L, (playbackDurationMs - 1L).coerceAtLeast(0L))
        val candidates = buildList {
            for (candidatePositionMs in SeekrFrameCalibrator.candidatePositions(expectedPlaybackMs)) {
                val clamped = candidatePositionMs.coerceIn(0L, (playbackDurationMs - 1L).coerceAtLeast(0L))
                // Surface capture and player seeks must stay on the main
                // dispatcher, but the perceptual comparison is pure CPU work
                // and must not steal frames from Compose/Media3. Keeping this
                // split here also makes the whole calibration coroutine safe
                // to launch from a background dispatcher.
                val frame = withContext(Dispatchers.Main.immediate) {
                    frameSource.captureAt(clamped)
                }
                val similarity = withContext(Dispatchers.Default) {
                    SeekrFrameCalibrator.perceptualSimilarity(thumbnail.bitmap, frame)
                }
                add(
                    SeekrCalibrationCandidate(
                        positionMs = clamped,
                        similarity = similarity
                    )
                )
            }
        }
        anchors += SeekrCalibrationAnchor(thumbnail.cueStartMs, expectedPlaybackMs, candidates)
    }
    SeekrFrameCalibrator.estimate(anchors, playbackToSourceScale = scale)
}

private const val CALIBRATION_TIMEOUT_MS = 9_000L
