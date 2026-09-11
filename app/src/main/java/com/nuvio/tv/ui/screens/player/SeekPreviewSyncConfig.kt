package com.nuvio.tv.ui.screens.player

internal const val SEEK_PREVIEW_OFFSET_MIN_MS = -240_000
internal const val SEEK_PREVIEW_OFFSET_MAX_MS = 240_000
internal const val SEEK_PREVIEW_OFFSET_STEP_MS = 250
internal const val SEEK_PREVIEW_OFFSET_COARSE_STEP_MS = 2_000
internal const val SEEK_PREVIEW_OFFSET_COARSE_AFTER_REPEATS = 3

internal fun seekPreviewOffsetStepMs(repeatCount: Int, forward: Boolean): Int {
    val magnitude = if (repeatCount >= SEEK_PREVIEW_OFFSET_COARSE_AFTER_REPEATS) {
        SEEK_PREVIEW_OFFSET_COARSE_STEP_MS
    } else {
        SEEK_PREVIEW_OFFSET_STEP_MS
    }
    return if (forward) magnitude else -magnitude
}

internal fun formatSeekPreviewOffset(offsetMs: Int): String {
    val sign = if (offsetMs >= 0) "+" else "-"
    val absolute = kotlin.math.abs(offsetMs)
    return "$sign${absolute / 1000}.${(absolute % 1000).toString().padStart(3, '0')}s"
}
