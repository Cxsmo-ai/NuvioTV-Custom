package com.nuvio.tv.ui.screens.player

import android.graphics.Color
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.SubtitleView

/**
 * Reset Media3's plain-text overrides when an ASS/SSA track is rendered by the fallback
 * renderer. Embedded positioning and font sizes are authored data; forcing the user's SRT
 * settings onto that track makes signs and dialogue lose their intended layout.
 */
@OptIn(UnstableApi::class)
internal fun SubtitleView.applyEmbeddedAssStyle() {
    setApplyEmbeddedStyles(true)
    setApplyEmbeddedFontSizes(true)
    setStyle(
        CaptionStyleCompat(
            Color.WHITE,
            Color.TRANSPARENT,
            Color.TRANSPARENT,
            CaptionStyleCompat.EDGE_TYPE_NONE,
            Color.BLACK,
            null
        )
    )
    setFractionalTextSize(SubtitleView.DEFAULT_TEXT_SIZE_FRACTION)
    setBottomPaddingFraction(SubtitleView.DEFAULT_BOTTOM_PADDING_FRACTION)
    setPadding(paddingLeft, paddingTop, paddingRight, 0)
}
