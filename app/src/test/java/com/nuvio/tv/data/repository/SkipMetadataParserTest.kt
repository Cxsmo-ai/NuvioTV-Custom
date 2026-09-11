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

    @Test
    fun introDbParserSupportsArrayAndClockTimestamps() {
        val intervals = SkipMetadataParser.parseIntroDb(
            """{"segments":[
                {"segment_type":"intro","start_ms":2000,"end_ms":60000,"confidence":0.9},
                {"segment_type":"outro","start_sec":"52:00","end_sec":"53:00"}
            ]}""",
            "introdb"
        )

        assertEquals(2, intervals.size)
        assertEquals(2.0, intervals[0].startTime, 0.001)
        assertEquals("outro", intervals[1].type)
        assertEquals(0.9, intervals[0].confidence, 0.001)
    }

    @Test
    fun theIntroDbParserUsesAllCategoryArraysAndDurationForOpenEnd() {
        val intervals = SkipMetadataParser.parseTheIntroDb(
            """{"intro":[{"start_ms":null,"end_ms":90000}],
               "credits":[{"start_ms":1800000,"end_ms":null}],
               "preview":[{"start_ms":1000,"end_ms":3000}]}""",
            "theintrodb",
            2_000_000L
        )

        assertEquals(3, intervals.size)
        assertEquals(0.0, intervals[0].startTime, 0.001)
        assertEquals(2000.0, intervals[1].endTime, 0.001)
        assertEquals("credits", intervals[1].type)
    }

    @Test
    fun publicMetaDbParserReadsMappingAndContributedRanges() {
        assertEquals(
            "1396",
            SkipMetadataParser.parsePublicMetaDbMapping(
                """{"results":[{"tmdb_id":1396,"media_type":"tv"}]}"""
            )
        )
        val intervals = SkipMetadataParser.parsePublicMetaDb(
            """{"items":[{"id":"a","intro_start_ms":1000,"intro_end_ms":60000,
                "credits_start_ms":3200000,"credits_end_ms":3300000}]}""",
            "publicmetadb"
        )
        assertEquals(listOf("intro", "credits"), intervals.map { it.type })
        assertEquals(60.0, intervals[0].endTime, 0.001)
    }
}
