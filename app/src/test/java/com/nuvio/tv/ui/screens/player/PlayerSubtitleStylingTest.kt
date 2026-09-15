package com.nuvio.tv.ui.screens.player

import android.graphics.Color
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.SubtitleView
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

@OptIn(UnstableApi::class)
class PlayerSubtitleStylingTest {
    @Test
    fun embeddedAssStyleRestoresAuthoredSizingAndPositioning() {
        val view = mockk<SubtitleView>(relaxed = true)
        every { view.paddingLeft } returns 4
        every { view.paddingTop } returns 8
        every { view.paddingRight } returns 12
        val appliedStyle = slot<CaptionStyleCompat>()

        view.applyEmbeddedAssStyle()

        verify {
            view.setApplyEmbeddedStyles(true)
            view.setApplyEmbeddedFontSizes(true)
            view.setStyle(capture(appliedStyle))
            view.setFractionalTextSize(SubtitleView.DEFAULT_TEXT_SIZE_FRACTION)
            view.setBottomPaddingFraction(SubtitleView.DEFAULT_BOTTOM_PADDING_FRACTION)
            view.setPadding(4, 8, 12, 0)
        }
        assertEquals(Color.TRANSPARENT, appliedStyle.captured.backgroundColor)
        assertEquals(Color.TRANSPARENT, appliedStyle.captured.windowColor)
        assertEquals(CaptionStyleCompat.EDGE_TYPE_NONE, appliedStyle.captured.edgeType)
        assertNull(appliedStyle.captured.typeface)
    }
}
