package com.nuvio.tv.ui.screens.player

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.Extractor
import com.nuvio.tv.core.player.HdrColorSignalingExtractor
import io.mockk.mockk
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(UnstableApi::class)
class PlayerSubtitleExtractorTest {
    @Test
    fun subtitleReplacementReachesExtractorInsideHdrWrapper() {
        val original = mockk<Extractor>()
        val replacement = mockk<Extractor>()

        val result = HdrColorSignalingExtractor(original).mapSubtitleExtractor {
            assertSame(original, it)
            replacement
        }

        assertTrue(result is HdrColorSignalingExtractor)
        assertSame(replacement, (result as HdrColorSignalingExtractor).delegate)
    }

    @Test
    fun unwrappedExtractorIsReplacedDirectly() {
        val original = mockk<Extractor>()
        val replacement = mockk<Extractor>()

        assertSame(
            replacement,
            original.mapSubtitleExtractor {
                assertSame(original, it)
                replacement
            }
        )
    }
}
