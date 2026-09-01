package com.nuvio.tv.data.repository

import com.nuvio.tv.MainDispatcherRule
import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.core.tracking.TrackingProgressProvider
import com.nuvio.tv.core.tracking.TrackingProgressProviderRegistry
import com.nuvio.tv.domain.model.Addon
import com.nuvio.tv.domain.model.CalendarDay
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.LibraryEntry
import com.nuvio.tv.domain.model.Meta
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.model.Video
import com.nuvio.tv.domain.model.WatchProgress
import com.nuvio.tv.domain.model.WatchedItem
import com.nuvio.tv.domain.repository.AddonRepository
import com.nuvio.tv.domain.repository.LibraryRepository
import com.nuvio.tv.domain.repository.MetaRepository
import com.nuvio.tv.domain.repository.WatchProgressRepository
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.LocalDate
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalCoroutinesApi::class)
class CalendarRepositoryTest {

    @get:Rule
    val dispatcherRule = MainDispatcherRule()

    @Test
    fun testCalendarRepositoryCollectsAndGroupsEpisodes() = runTest {
        val watchProgressRepo = mockk<WatchProgressRepository>()
        val libraryRepo = mockk<LibraryRepository>()
        val trackingRegistry = mockk<TrackingProgressProviderRegistry>()
        val metaRepo = mockk<MetaRepository>()
        val addonRepo = mockk<AddonRepository>()
        val profileManager = mockk<ProfileManager>()

        val profileIdFlow = MutableStateFlow(1)
        every { profileManager.activeProfileId } returns profileIdFlow.asStateFlow()
        every { trackingRegistry.providers() } returns emptyList()

        val today = LocalDate.now()
        val tomorrow = today.plusDays(1)
        val isoDateToday = today.format(DateTimeFormatter.ISO_LOCAL_DATE) + "T00:00:00Z"
        val isoDateTomorrow = tomorrow.format(DateTimeFormatter.ISO_LOCAL_DATE) + "T00:00:00Z"

        val testWatchProgress = WatchProgress(
            contentId = "tt_mentalist",
            contentType = "series",
            name = "The Mentalist",
            poster = "https://example.com/poster1.jpg",
            backdrop = "https://example.com/backdrop1.jpg",
            logo = null,
            videoId = "tt_mentalist:1:1",
            season = 1,
            episode = 1,
            episodeTitle = "Pilot",
            position = 1000L,
            duration = 2000L,
            lastWatched = System.currentTimeMillis()
        )

        every { watchProgressRepo.allProgress } returns flowOf(listOf(testWatchProgress))
        every { watchProgressRepo.watchedItems } returns flowOf(emptyList())
        coEvery { watchProgressRepo.getWatchedShowEpisodes() } returns mapOf("tt_mentalist" to setOf(1 to 1))

        val libraryEntry = LibraryEntry(
            id = "tt_silo",
            type = "series",
            name = "Silo",
            poster = "https://example.com/silo_poster.jpg",
            posterShape = PosterShape.POSTER,
            background = "https://example.com/silo_back.jpg",
            logo = null,
            description = "Dystopian series",
            releaseInfo = "2023",
            imdbRating = 8.1f,
            genres = listOf("Sci-Fi", "Drama"),
            addonBaseUrl = null
        )
        every { libraryRepo.libraryItems } returns flowOf(listOf(libraryEntry))

        val mentalistVideos = listOf(
            Video(
                id = "tt_mentalist:1:2",
                title = "Red Hair and Silver Tape",
                released = isoDateToday,
                thumbnail = "https://example.com/thumb1.jpg",
                season = 1,
                episode = 2,
                overview = "Mentalist episode 2"
            )
        )
        val mentalistMeta = Meta(
            id = "tt_mentalist",
            type = ContentType.SERIES,
            name = "The Mentalist",
            poster = "https://example.com/poster1.jpg",
            posterShape = PosterShape.POSTER,
            background = "https://example.com/backdrop1.jpg",
            logo = null,
            description = "Crime drama",
            releaseInfo = "2008",
            imdbRating = 8.2f,
            genres = listOf("Crime", "Drama"),
            runtime = "44 min",
            director = emptyList(),
            cast = listOf("Simon Baker"),
            videos = mentalistVideos,
            country = "US",
            awards = null,
            language = "en",
            links = emptyList()
        )

        val siloVideos = listOf(
            Video(
                id = "tt_silo:2:1",
                title = "The Engineer",
                released = isoDateTomorrow,
                thumbnail = "https://example.com/silo_ep1.jpg",
                season = 2,
                episode = 1,
                overview = "Silo season 2 premiere"
            )
        )
        val siloMeta = Meta(
            id = "tt_silo",
            type = ContentType.SERIES,
            name = "Silo",
            poster = "https://example.com/silo_poster.jpg",
            posterShape = PosterShape.POSTER,
            background = "https://example.com/silo_back.jpg",
            logo = null,
            description = "Silo description",
            releaseInfo = "2023",
            imdbRating = 8.1f,
            genres = listOf("Sci-Fi"),
            runtime = "50 min",
            director = emptyList(),
            cast = emptyList(),
            videos = siloVideos,
            country = "US",
            awards = null,
            language = "en",
            links = emptyList()
        )

        every { metaRepo.getMetaFromAllAddons(type = "series", id = "tt_mentalist") } returns flowOf(NetworkResult.Success(mentalistMeta))
        every { metaRepo.getMetaFromAllAddons(type = "series", id = "tt_silo") } returns flowOf(NetworkResult.Success(siloMeta))
        every { metaRepo.getCachedMeta("series", "tt_mentalist") } returns mentalistMeta
        every { metaRepo.getCachedMeta("series", "tt_silo") } returns siloMeta

        val repository = CalendarRepositoryImpl(
            watchProgressRepository = watchProgressRepo,
            libraryRepository = libraryRepo,
            trackingProgressProviders = trackingRegistry,
            metaRepository = metaRepo,
            addonRepository = addonRepo,
            profileManager = profileManager
        )

        repository.refresh()

        val days = repository.calendarDays
        assertNotNull(days)
    }
}
