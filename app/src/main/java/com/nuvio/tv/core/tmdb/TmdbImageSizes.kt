package com.nuvio.tv.core.tmdb

import android.content.res.Resources

/**
 * Resolution-aware TMDB source-size selection.
 *
 * Backdrops render full-bleed, so the source ceiling should track the UI framebuffer:
 * a fixed w1280 is a permanent 3x upscale on devices rendering the UI at 4K, while
 * remaining well matched on 1080p-rendering boxes. TMDB offers no official backdrop
 * size between w1280 and original, so the step is binary. Threshold 1600 px keeps
 * 1080p UIs on w1280 (no change in transfer cost) and upgrades only above that.
 *
 * Stills: the official TMDB still ladder is w92/w185/w300/original. w500 is not an
 * official still size and TMDB has removed unofficial sizes before - if that happens
 * every episode thumbnail 404s at once. Episode cards render at ~640-800 px, so
 * original (TMDB enforces a 1280x720 minimum for stills) is the only official size
 * that is not undersized. Decode memory is unaffected: requests are already sized to
 * display pixels, only transfer size rises.
 */
object TmdbImageSizes {

    val backdrop: String by lazy {
        val dm = runCatching { Resources.getSystem()?.displayMetrics }.getOrNull()
        if (dm != null && maxOf(dm.widthPixels, dm.heightPixels) > 1600) "original" else "w1280"
    }

    const val STILL: String = "original"
}
