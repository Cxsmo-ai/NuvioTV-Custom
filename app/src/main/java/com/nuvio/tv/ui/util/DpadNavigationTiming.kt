package com.nuvio.tv.ui.util

/**
 * Shared TV navigation timings. The values keep held navigation responsive on
 * 60 Hz devices while leaving every non-repeat key press to Compose so a tap
 * can only move focus by one destination.
 */
internal object DpadNavigationTiming {
    const val STANDARD_HORIZONTAL_REPEAT_MS = 64L
    const val FAST_HORIZONTAL_REPEAT_MS = 44L
    const val VERTICAL_REPEAT_MS = 88L
    const val HELD_SCROLL_END_TIMEOUT_MS = 120L
}

/**
 * Independent repeat gates prevent a quick direction reversal from inheriting
 * the previous direction's cooldown. This class is intentionally free of
 * Android/Compose types so its edge cases can be covered by local unit tests.
 */
internal class DirectionalRepeatGate(
    directionCount: Int = 4,
) {
    private val lastAcceptedAt = LongArray(directionCount) { UNSET }

    fun reset(direction: Int) {
        lastAcceptedAt[direction] = UNSET
    }

    fun tryAcquire(direction: Int, nowMs: Long, minimumIntervalMs: Long): Boolean {
        val previous = lastAcceptedAt[direction]
        if (previous != UNSET && nowMs - previous < minimumIntervalMs) {
            return false
        }
        lastAcceptedAt[direction] = nowMs
        return true
    }

    private companion object {
        const val UNSET = Long.MIN_VALUE
    }
}
