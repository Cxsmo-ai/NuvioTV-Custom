package com.nuvio.tv.data.repository

import android.util.Log
import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.core.tracking.TrackingProgressProviderRegistry
import com.nuvio.tv.core.util.parseEpisodeReleaseLocalDate
import com.nuvio.tv.domain.model.CalendarDay
import com.nuvio.tv.domain.model.CalendarEpisode
import com.nuvio.tv.domain.model.Meta
import com.nuvio.tv.domain.repository.AddonRepository
import com.nuvio.tv.domain.repository.CalendarRepository
import com.nuvio.tv.domain.repository.LibraryRepository
import com.nuvio.tv.domain.repository.MetaRepository
import com.nuvio.tv.domain.repository.WatchProgressRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CalendarRepositoryImpl @Inject constructor(
    private val watchProgressRepository: WatchProgressRepository,
    private val libraryRepository: LibraryRepository,
    private val trackingProgressProviders: TrackingProgressProviderRegistry,
    private val metaRepository: MetaRepository,
    private val addonRepository: AddonRepository,
    private val profileManager: ProfileManager
) : CalendarRepository {

    companion object {
        private const val TAG = "CalendarRepo"
        private const val CACHE_TTL_MS = 30 * 60 * 1000L // 30 minutes
        private const val PAST_DAYS_LOOKBACK = 30L
        private const val FUTURE_DAYS_LOOKAHEAD = 90L
        private const val CONCURRENT_FETCH_LIMIT = 6
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val semaphore = Semaphore(CONCURRENT_FETCH_LIMIT)

    private val _calendarDays = MutableStateFlow<List<CalendarDay>>(emptyList())
    override val calendarDays: Flow<List<CalendarDay>> = _calendarDays.asStateFlow()

    private val _isRefreshing = MutableStateFlow(false)
    override val isRefreshing: Flow<Boolean> = _isRefreshing.asStateFlow()

    private var lastFetchTime = 0L
    private var cachedProfileId = 1

    init {
        scope.launch {
            profileManager.activeProfileId.collect { profileId ->
                if (profileId != cachedProfileId) {
                    cachedProfileId = profileId
                    lastFetchTime = 0L
                    _calendarDays.value = emptyList()
                    fetchScheduleInternal(forceRefresh = true)
                }
            }
        }
        scope.launch {
            fetchScheduleInternal(forceRefresh = false)
        }
    }

    override suspend fun refresh() {
        fetchScheduleInternal(forceRefresh = true)
    }

    private suspend fun fetchScheduleInternal(forceRefresh: Boolean = false) = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        if (!forceRefresh && _calendarDays.value.isNotEmpty() && (now - lastFetchTime) < CACHE_TTL_MS) {
            return@withContext
        }

        mutex.withLock {
            if (!forceRefresh && _calendarDays.value.isNotEmpty() && (System.currentTimeMillis() - lastFetchTime) < CACHE_TTL_MS) {
                return@withLock
            }

            _isRefreshing.value = true
            try {
                val showIds = collectWatchedAndTrackedShowIds()
                Log.d(TAG, "Discovered " + showIds.size + " unique series for Calendar")

                if (showIds.isEmpty()) {
                    _calendarDays.value = emptyList()
                    lastFetchTime = System.currentTimeMillis()
                    return@withLock
                }

                val today = LocalDate.now()
                val minDate = today.minusDays(PAST_DAYS_LOOKBACK)
                val maxDate = today.plusDays(FUTURE_DAYS_LOOKAHEAD)

                val watchedEpisodes = collectWatchedEpisodesMap()

                val episodeList = mutableListOf<CalendarEpisode>()

                val deferredList = showIds.map { showId ->
                    async {
                        semaphore.withPermit {
                            runCatching {
                                fetchEpisodesForShow(showId, minDate, maxDate, watchedEpisodes)
                            }.getOrDefault(emptyList())
                        }
                    }
                }

                val results = deferredList.awaitAll()
                results.forEach { episodeList.addAll(it) }

                val groupedDays = episodeList
                    .groupBy { it.airDate }
                    .map { (date, eps) ->
                        CalendarDay(
                            date = date,
                            episodes = eps.sortedWith(
                                compareBy<CalendarEpisode> { it.showTitle }
                                    .thenBy { it.seasonNumber }
                                    .thenBy { it.episodeNumber }
                            )
                        )
                    }
                    .sortedBy { it.date }

                _calendarDays.value = groupedDays
                lastFetchTime = System.currentTimeMillis()
                Log.d(TAG, "Generated " + groupedDays.size + " calendar days with " + episodeList.size + " episodes")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to build calendar schedule", e)
            } finally {
                _isRefreshing.value = false
            }
        }
    }

    private suspend fun collectWatchedAndTrackedShowIds(): Set<String> {
        val showIds = mutableSetOf<String>()

        // 1. From local / synced WatchProgress (Continue Watching / History)
        runCatching {
            val progress = watchProgressRepository.allProgress.firstOrNull() ?: emptyList()
            progress.forEach {
                if (it.contentType.equals("series", ignoreCase = true) || it.season != null) {
                    if (it.contentId.isNotBlank()) showIds.add(it.contentId)
                }
            }
        }

        // 2. From local / synced WatchedItems
        runCatching {
            val watched = watchProgressRepository.watchedItems.firstOrNull() ?: emptyList()
            watched.forEach {
                if (it.contentType.equals("series", ignoreCase = true) || it.season != null) {
                    if (it.contentId.isNotBlank()) showIds.add(it.contentId)
                }
            }
        }

        // 3. From Library entries (Saved / Watchlist)
        runCatching {
            val libraryEntries = libraryRepository.libraryItems.firstOrNull() ?: emptyList()
            libraryEntries.forEach {
                if (it.type.equals("series", ignoreCase = true)) {
                    if (it.id.isNotBlank()) showIds.add(it.id)
                }
            }
        }

        // 4. From all connected Tracking providers (Trakt, Simkl, MDBList)
        runCatching {
            for (provider in trackingProgressProviders.providers()) {
                val isAuth = runCatching { provider.isAuthenticated.first() }.getOrDefault(false)
                if (!isAuth) continue

                runCatching {
                    val prog = provider.allProgress.firstOrNull() ?: emptyList()
                    prog.forEach {
                        if (it.contentType.equals("series", ignoreCase = true) || it.season != null) {
                            if (it.contentId.isNotBlank()) showIds.add(it.contentId)
                        }
                    }
                }

                runCatching {
                    val nextSeeds = provider.nextUpSeeds.firstOrNull() ?: emptyList()
                    nextSeeds.forEach {
                        if (it.contentType.equals("series", ignoreCase = true) || it.season != null) {
                            if (it.contentId.isNotBlank()) showIds.add(it.contentId)
                        }
                    }
                }

                runCatching {
                    val watched = provider.watchedItems.firstOrNull() ?: emptyList()
                    watched.forEach {
                        if (it.contentType.equals("series", ignoreCase = true) || it.season != null) {
                            if (it.contentId.isNotBlank()) showIds.add(it.contentId)
                        }
                    }
                }

                runCatching {
                    provider.watchedShowEpisodes().keys.forEach { showId ->
                        if (showId.isNotBlank()) showIds.add(showId)
                    }
                }
            }
        }

        return showIds.filter { it.isNotBlank() }.toSet()
    }

    private suspend fun collectWatchedEpisodesMap(): Map<String, Set<Pair<Int, Int>>> {
        val map = mutableMapOf<String, MutableSet<Pair<Int, Int>>>()

        runCatching {
            watchProgressRepository.getWatchedShowEpisodes().forEach { (showId, eps) ->
                map.getOrPut(showId) { mutableSetOf() }.addAll(eps)
            }
        }

        runCatching {
            val watched = watchProgressRepository.watchedItems.firstOrNull() ?: emptyList()
            watched.forEach { item ->
                if (item.season != null && item.episode != null) {
                    map.getOrPut(item.contentId) { mutableSetOf() }.add(item.season to item.episode)
                }
            }
        }

        runCatching {
            for (provider in trackingProgressProviders.providers()) {
                val isAuth = runCatching { provider.isAuthenticated.first() }.getOrDefault(false)
                if (!isAuth) continue
                runCatching {
                    provider.watchedShowEpisodes().forEach { (showId, eps) ->
                        map.getOrPut(showId) { mutableSetOf() }.addAll(eps)
                    }
                }
            }
        }

        return map
    }

    private suspend fun fetchEpisodesForShow(
        showId: String,
        minDate: LocalDate,
        maxDate: LocalDate,
        watchedEpisodes: Map<String, Set<Pair<Int, Int>>>
    ): List<CalendarEpisode> {
        val meta = metaRepository.getMetaFromAllAddons(type = "series", id = showId)
            .filterIsInstance<NetworkResult.Success<Meta>>()
            .map { it.data }
            .firstOrNull() ?: metaRepository.getCachedMeta("series", showId)
            ?: return emptyList()

        if (meta.videos.isEmpty()) return emptyList()

        val showWatchedSet = watchedEpisodes[showId] ?: watchedEpisodes[meta.id] ?: emptySet()

        val results = mutableListOf<CalendarEpisode>()

        for (video in meta.videos) {
            val season = video.season ?: continue
            val episode = video.episode ?: continue
            if (season <= 0) continue // Skip specials from calendar by default

            val airDate = parseEpisodeReleaseLocalDate(video.released) ?: continue
            if (airDate.isBefore(minDate) || airDate.isAfter(maxDate)) continue

            val isWatched = (season to episode) in showWatchedSet

            val epTitle = video.title.trim().takeIf { it.isNotEmpty() }
                ?: ("Episode " + episode)

            results.add(
                CalendarEpisode(
                    showId = meta.id,
                    showTitle = meta.name,
                    episodeId = video.id,
                    seasonNumber = season,
                    episodeNumber = episode,
                    episodeTitle = epTitle,
                    airDate = airDate,
                    releaseIso = video.released,
                    thumbnail = video.thumbnail,
                    showPoster = meta.poster,
                    showBackdrop = meta.backdropUrl,
                    overview = video.overview,
                    rating = video.rating,
                    isWatched = isWatched
                )
            )
        }

        return results
    }
}
