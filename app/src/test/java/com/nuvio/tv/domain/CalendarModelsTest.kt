package com.nuvio.tv.domain

import com.nuvio.tv.domain.model.CalendarDay
import com.nuvio.tv.domain.model.CalendarEpisode
import com.nuvio.tv.domain.model.CalendarFilter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class CalendarModelsTest {

    @Test
    fun testCalendarEpisodeFormatting() {
        val today = LocalDate.now()
        val episode = CalendarEpisode(
            showId = "tt1196946",
            showTitle = "The Mentalist",
            episodeId = "tt1196946:1:4",
            seasonNumber = 1,
            episodeNumber = 4,
            episodeTitle = "Ladies in Red",
            airDate = today,
            releaseIso = "2026-08-28T00:00:00Z",
            thumbnail = "https://example.com/thumb.jpg",
            showPoster = "https://example.com/poster.jpg",
            showBackdrop = "https://example.com/backdrop.jpg",
            isWatched = true
        )

        assertEquals("S01E04", episode.episodeCode)
        assertEquals("https://example.com/thumb.jpg", episode.backdropUrl)
        assertEquals(
            listOf(
                "https://example.com/thumb.jpg",
                "https://example.com/backdrop.jpg",
                "https://example.com/poster.jpg"
            ),
            episode.artworkCandidates()
        )
        assertTrue(episode.isWatched)
    }

    @Test
    fun testCalendarArtworkCandidatesIgnoreBlankAndDuplicateUrls() {
        val episode = CalendarEpisode(
            showId = "tt1",
            showTitle = "Show",
            episodeId = "tt1:1:2",
            seasonNumber = 1,
            episodeNumber = 2,
            episodeTitle = "Hidden episode",
            airDate = LocalDate.now(),
            releaseIso = null,
            thumbnail = "   ",
            showBackdrop = "https://example.com/show.jpg",
            showPoster = "https://example.com/show.jpg",
            isSpoilerHidden = true
        )

        assertEquals(listOf("https://example.com/show.jpg"), episode.artworkCandidates())
        assertEquals(
            listOf("https://example.com/show.jpg"),
            episode.artworkCandidates(includeEpisodeThumbnail = false)
        )
        assertTrue(episode.isSpoilerHidden)
    }

    @Test
    fun testCalendarDayFlags() {
        val today = LocalDate.now()
        val yesterday = today.minusDays(1)
        val tomorrow = today.plusDays(1)

        val pastDay = CalendarDay(
            date = yesterday,
            episodes = listOf(
                CalendarEpisode(
                    showId = "tt1",
                    showTitle = "Show 1",
                    episodeId = "ep1",
                    seasonNumber = 1,
                    episodeNumber = 1,
                    episodeTitle = "Pilot",
                    airDate = yesterday,
                    releaseIso = null
                )
            )
        )

        val todayDay = CalendarDay(
            date = today,
            episodes = emptyList()
        )

        val tomorrowDay = CalendarDay(
            date = tomorrow,
            episodes = emptyList()
        )

        assertTrue(pastDay.isPast)
        assertFalse(pastDay.isToday)
        assertFalse(pastDay.isTomorrow)
        assertEquals(1, pastDay.episodeCount)

        assertFalse(todayDay.isPast)
        assertTrue(todayDay.isToday)
        assertFalse(todayDay.isTomorrow)
        assertEquals(0, todayDay.episodeCount)

        assertFalse(tomorrowDay.isPast)
        assertFalse(tomorrowDay.isToday)
        assertTrue(tomorrowDay.isTomorrow)
    }
}
