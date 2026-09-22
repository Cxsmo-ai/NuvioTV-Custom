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
    fun introDbMovieParserSeparatesCreditsFromPostCredits() {
        val intervals = SkipMetadataParser.parseIntroDb(
            """{"outro":{"start_sec":100,"end_sec":140},
               "post_credits":{"start_sec":125,"end_sec":130}}""",
            "introdb",
            isMovie = true
        )

        assertEquals(listOf("movie-credits", "post-credits"), intervals.map { it.type })
        assertEquals(125.0, intervals.first().endTime, 0.001)
        assertEquals(125.0, intervals.last().startTime, 0.001)
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

    @Test
    fun notScarePageParserReadsMajorAndMinorJumpscares() {
        val intervals = SkipMetadataParser.parseNotScarePage(
            """<script>00:00:01 Major</script><main>
                <div>00:03:22</div><strong>Major</strong>
                <div>03:40</div><strong>Minor</strong>
            </main>""",
            "notscare"
        )

        assertEquals(2, intervals.size)
        assertEquals(202.0, intervals[0].startTime, 0.001)
        assertEquals(208.0, intervals[0].endTime, 0.001)
        assertEquals("major", intervals[0].severity)
        assertEquals(220.0, intervals[1].startTime, 0.001)
        assertEquals(224.0, intervals[1].endTime, 0.001)
    }

    @Test
    fun skipMeParserReadsMovieCategoriesAndSubmissionConfidence() {
        val intervals = SkipMetadataParser.parseSkipMe(
            """[{"intro":[{"start_ms":12000,"end_ms":60000,"submissions":15}],
                "credits":[{"start_ms":1800000,"end_ms":1860000,"submissions":1}],
                "preview":[{"start_ms":2000,"end_ms":1000,"submissions":99}]}]""",
            "skipme",
            isSeries = false,
            season = 0,
            episode = 0
        )

        assertEquals(listOf("intro", "credits"), intervals.map { it.type })
        assertEquals(12.0, intervals[0].startTime, 0.001)
        assertEquals(0.99, intervals[0].confidence, 0.001)
        assertEquals(15, intervals[0].evidence.single().submissions)
    }

    @Test
    fun skipMeParserFiltersToRequestedSeriesEpisode() {
        val intervals = SkipMetadataParser.parseSkipMe(
            """[{"segments":[
                {"season":1,"episode":1,"segment":"intro","start_ms":1000,"end_ms":30000,"submissions":3},
                {"season":1,"episode":2,"segment":"intro","start_ms":2000,"end_ms":31000,"submissions":3},
                {"season":1,"episode":1,"segment":"unknown","start_ms":40000,"end_ms":45000}
            ]}]""",
            "skipme",
            isSeries = true,
            season = 1,
            episode = 2
        )

        assertEquals(1, intervals.size)
        assertEquals(2.0, intervals.single().startTime, 0.001)
        assertEquals("intro", intervals.single().type)
    }

    @Test
    fun overlappingEvidenceMergesConfidenceAndKeepsDifferentActionsSeparate() {
        val merged = mergeSkipIntervals(
            listOf(
                SkipInterval(10.0, 30.0, "intro", "introdb", confidence = 0.86),
                SkipInterval(11.0, 31.0, "intro", "skipme", confidence = 0.80),
                SkipInterval(12.0, 14.0, "intro", "videoskip", action = "mute", confidence = 0.90)
            )
        )

        assertEquals(2, merged.size)
        val skip = merged.first { it.action == "skip" }
        assertEquals(2, skip.evidence.map { it.provider }.toSet().size)
        assertTrue(skip.confidence > 0.86)
        assertEquals("mute", merged.first { it.action == "mute" }.action)
    }

    @Test
    fun providerUrlsIncludeIdsWithoutALiteralDollar() {
        assertEquals(
            "https://api.introdb.app/segments?imdb_id=tt0944947&season=1&episode=3",
            introDbSegmentsUrl("https://api.introdb.app", "tt0944947", 1, 3)
        )
        assertEquals(
            "https://api.theintrodb.org/v3/media?imdb_id=tt0944947&season=1&episode=3",
            theIntroDbMediaUrl("imdb_id=tt0944947&season=1&episode=3")
        )
        assertEquals(
            "https://publicmetadb.com/api/external/mappings/lookup?id_type=imdb&id_value=tt0944947&media_type=tv",
            publicMetaDbMappingUrl("tt0944947", "tv")
        )
        assertEquals(
            "https://publicmetadb.com/api/external/skips?tmdb_id=1396&media_type=tv&season=1&episode=3",
            publicMetaDbSkipsUrl("1396", "tv", season = 1, episode = 3, isSeries = true)
        )
        assertEquals("Bearer secret", bearerAuthorization("secret"))
    }

    @Test
    fun skipFetchKeyKeepsDurationAheadOfTheFingerprint() {
        val fingerprint = "AUTO:introdb,skipme:intro,credits"
        val missingDuration = skipIntervalsFetchKey(
            imdbId = "tt0944947",
            tmdbId = null,
            season = 1,
            episode = 3,
            durationMs = 0L,
            settingsFingerprint = fingerprint
        )
        assertEquals(0L, skipFetchKeyDurationMs(missingDuration))
        val withDuration = skipIntervalsFetchKey(
            imdbId = "tt0944947",
            tmdbId = 1396,
            season = 1,
            episode = 3,
            durationMs = 2_700_000L,
            settingsFingerprint = fingerprint
        )
        assertEquals(2_700_000L, skipFetchKeyDurationMs(withDuration))
    }

    @Test
    fun readUtf8AtMostAcceptsShortAndMultiSegmentBodies() {
        val json = """{"intro":[{"start_ms":1,"end_ms":2}]}"""
        assertEquals(json, readUtf8AtMost(okio.Buffer().writeUtf8(json), 2L * 1024 * 1024))
        val large = "x".repeat(20_000)
        assertEquals(large, readUtf8AtMost(okio.Buffer().writeUtf8(large), 2L * 1024 * 1024))
        assertEquals(null, readUtf8AtMost(okio.Buffer().writeUtf8("abcdef"), 3))
    }
}
