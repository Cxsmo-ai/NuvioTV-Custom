package com.nuvio.tv.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SkipMetadataParserTest {
    @Test
    fun videoSkipParserReadsClockAndCategory() {
        val intervals = SkipMetadataParser.parseVideoSkip(
            "00:01:02.500 --> 00:01:08.000\nJumpscare 3\n" +
                "01:10.000 --> 01:12.000\nProfanity 2 audio\n"
        )

        assertEquals(2, intervals.size)
        assertEquals(62.5, intervals[0].startTime, 0.001)
        assertEquals("jumpscare", intervals[0].type)
        assertEquals("high", intervals[0].severity)
        assertEquals("mute", intervals[1].action)
        assertEquals("profanity", intervals[1].type)
    }

    @Test
    fun movieHavenParserKeepsWarnAndMuteSemantics() {
        val intervals = SkipMetadataParser.parseMovieHaven(
            """{"tt0110357":{"title":"Example","scenes":[
                {"start":12.0,"end":15.5,"reason":"violence","skip":true},
                {"start":20.0,"end":22.0,"reason":"nudity","mute":true},
                {"start":30.0,"end":29.0,"reason":"gore","skip":true}
            ]}}"""
        )

        assertEquals(2, intervals.size)
        assertEquals("violence", intervals[0].type)
        assertEquals("skip", intervals[0].action)
        assertEquals("nudity", intervals[1].type)
        assertEquals("mute", intervals[1].action)
    }

    @Test
    fun malformedProviderPayloadIsSafe() {
        assertTrue(SkipMetadataParser.parseMovieHaven("not json").isEmpty())
        assertTrue(SkipMetadataParser.parseVideoSkip("garbage").isEmpty())
        assertEquals(3723.25, SkipMetadataParser.parseTimestamp("1:02:03.25")!!, 0.001)
    }
}
