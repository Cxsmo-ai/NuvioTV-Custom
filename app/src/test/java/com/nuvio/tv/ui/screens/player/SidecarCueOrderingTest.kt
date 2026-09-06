package com.nuvio.tv.ui.screens.player

import org.junit.Assert.assertEquals
import org.junit.Test

/** Regression coverage for the sorted, exact-timing lenient sidecar parser. */
class SidecarCueOrderingTest {

    @Test
    fun `lenient parse orders cues that appear out of order in the file`() {
        val srt = """
            1
            00:00:10,000 --> 00:00:12,000
            third

            2
            00:00:02,000 --> 00:00:04,000
            first

            3
            00:00:06,000 --> 00:00:08,000
            second
        """.trimIndent()

        val parsed = parseSidecarTimedCuesLenient(srt, "https://example.test/subs.srt")

        assertEquals(3, parsed.size)
        assertEquals(
            listOf(2_000_000L, 6_000_000L, 10_000_000L),
            parsed.map { it.startTimeUs }
        )
        assertEquals(
            listOf("first", "second", "third"),
            parsed.map { it.cues.single().text.toString() }
        )
    }

    @Test
    fun `lenient parse preserves each cue end time`() {
        val srt = """
            1
            00:00:01,250 --> 00:00:03,750
            exact duration

            2
            00:00:05,000 --> 00:00:05,750
            second duration
        """.trimIndent()

        val parsed = parseSidecarTimedCuesLenient(srt, "https://example.test/subs.srt")

        assertEquals(2_500_000L, parsed[0].durationUs)
        assertEquals(750_000L, parsed[1].durationUs)
    }
}
