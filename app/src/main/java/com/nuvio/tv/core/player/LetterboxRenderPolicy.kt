package com.nuvio.tv.core.player

import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue

/** Device-safe policy for leaving ExoPlayer's HDR letterbox transparent. */
object LetterboxRenderPolicy {
    fun defaultTransparentLetterbox(manufacturer: String? = Build.MANUFACTURER): Boolean =
        !manufacturer.orEmpty().trim().equals("Amazon", ignoreCase = true)

    fun shouldUseTransparentLetterbox(
        isResolvedExoPlayer: Boolean,
        manufacturer: String? = Build.MANUFACTURER,
        exitDispatched: Boolean = false
    ): Boolean = isResolvedExoPlayer &&
        !exitDispatched &&
        defaultTransparentLetterbox(manufacturer)
}

/** Activity-level backdrop state used while the internal player owns the window. */
object PlayerWindowBackdrop {
    private var transparentRequests by mutableIntStateOf(0)

    val isTransparentRequested: Boolean
        get() = transparentRequests > 0

    fun acquireTransparent() {
        transparentRequests++
    }

    fun releaseTransparent() {
        transparentRequests = (transparentRequests - 1).coerceAtLeast(0)
    }
}
