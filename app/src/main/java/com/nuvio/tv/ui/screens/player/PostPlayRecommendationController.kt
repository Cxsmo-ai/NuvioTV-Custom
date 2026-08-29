package com.nuvio.tv.ui.screens.player

import com.nuvio.tv.core.build.AppFeaturePolicy
import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.core.player.TrailerPlayerPool
import com.nuvio.tv.core.tmdb.TmdbMetadataService
import com.nuvio.tv.core.tmdb.TmdbService
import com.nuvio.tv.core.util.isUnreleased
import com.nuvio.tv.data.local.LayoutPreferenceDataStore
import com.nuvio.tv.data.local.MDBListSettingsDataStore
import com.nuvio.tv.data.local.MoreLikeThisSourcePreference
import com.nuvio.tv.data.local.PostPlayRecommendationSource
import com.nuvio.tv.data.local.PlayerSettingsDataStore
import com.nuvio.tv.data.local.TmdbSettingsDataStore
import com.nuvio.tv.data.local.TrailerSettingsDataStore
import com.nuvio.tv.data.local.TraktAuthDataStore
import com.nuvio.tv.data.local.TraktSettingsDataStore
import com.nuvio.tv.data.local.WatchedSeriesStateHolder
import com.nuvio.tv.data.repository.MDBListRepository
import com.nuvio.tv.data.repository.TraktRelatedService
import com.nuvio.tv.data.trailer.TrailerService
import com.nuvio.tv.data.trailer.TrailerPlaybackSource
import com.nuvio.tv.domain.model.Meta
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.Addon
import com.nuvio.tv.domain.model.CatalogDescriptor
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.CatalogRow
import com.nuvio.tv.domain.repository.AddonRepository
import com.nuvio.tv.domain.repository.CatalogRepository
import com.nuvio.tv.domain.repository.MetaRepository
import com.nuvio.tv.domain.repository.WatchProgressRepository
import java.time.LocalDate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withTimeoutOrNull

internal class PostPlayRecommendationController(
    private val playbackController: PlayerRuntimeController,
    private val playerSettingsDataStore: PlayerSettingsDataStore,
    private val metaRepository: MetaRepository,
    private val catalogRepository: CatalogRepository,
    private val addonRepository: AddonRepository,
    private val tmdbService: TmdbService,
    private val tmdbMetadataService: TmdbMetadataService,
    private val tmdbSettingsDataStore: TmdbSettingsDataStore,
    private val mdbListRepository: MDBListRepository,
    private val mdbListSettingsDataStore: MDBListSettingsDataStore,
    private val traktRelatedService: TraktRelatedService,
    private val traktAuthDataStore: TraktAuthDataStore,
    private val traktSettingsDataStore: TraktSettingsDataStore,
    private val layoutPreferenceDataStore: LayoutPreferenceDataStore,
    private val watchProgressRepository: WatchProgressRepository,
    private val watchedSeriesStateHolder: WatchedSeriesStateHolder,
    private val trailerService: TrailerService,
    private val trailerSettingsDataStore: TrailerSettingsDataStore,
    private val trailerPlayerPool: TrailerPlayerPool,
    private val scope: CoroutineScope
) {
    private data class PlaybackIdentity(
        val contentType: String?,
        val contentId: String?,
        val videoId: String?,
        val season: Int?,
        val episode: Int?
    )

    private data class PlaybackSnapshot(
        val identity: PlaybackIdentity,
        val contentType: String?,
        val postPlayRecommendationsEnabled: Boolean,
        val isNextEpisodeMetadataResolved: Boolean,
        val nextEpisodeHasAired: Boolean?,
        val hasError: Boolean,
        val hasBlockingInteraction: Boolean,
        val playbackEnded: Boolean,
        val positionMs: Long,
        val durationMs: Long
    )

    private data class ResolvedCandidate(
        val recommendation: PostPlayRecommendation,
        val meta: Meta?
    )

    private data class RatingPreferences(
        val isMdbListActive: Boolean,
        val showStandardRatings: Boolean
    )

    private val _uiState = MutableStateFlow(PostPlayRecommendationUiState())
    val uiState: StateFlow<PostPlayRecommendationUiState> = _uiState.asStateFlow()

    private var recommendationJob: Job? = null
    private var recommendationSelectionJob: Job? = null
    private var recommendationPrefetchJob: Job? = null
    private var postEndCountdownJob: Job? = null
    private var returnToPlayerAnimationJob: Job? = null
    private var recommendationCandidates = emptyList<MetaPreview>()
    private var ratingPreferences: RatingPreferences? = null
    private val candidateResolutionJobs = mutableMapOf<Int, Deferred<ResolvedCandidate?>>()
    private val recommendationDetailJobs = mutableMapOf<Int, Job>()
    private val recommendationCache = mutableMapOf<Int, PostPlayRecommendation>()
    private var recommendationLoadAttempted = false
    private val postPlayTrailerPlaybackEnabled = AppFeaturePolicy.inAppTrailerPlaybackEnabled
    private var autoPlayTrailerEnabled = postPlayTrailerPlaybackEnabled
    private var lastSnapshot: PlaybackSnapshot? = null
    private var lastPlaybackIdentity: PlaybackIdentity? = null

    init {
        scope.launch {
            combine(
                playbackController.uiState,
                playbackController.playbackTimeline,
                playerSettingsDataStore.playerSettings
            ) { playerState, timeline, playerSettings ->
                PlaybackSnapshot(
                    identity = PlaybackIdentity(
                        contentType = playerState.contentType?.trim()?.lowercase(),
                        contentId = playbackController.contentId,
                        videoId = playerState.currentVideoId,
                        season = playerState.currentSeason,
                        episode = playerState.currentEpisode
                    ),
                    contentType = playerState.contentType,
                    postPlayRecommendationsEnabled = playerSettings.postPlayRecommendationsEnabled,
                    isNextEpisodeMetadataResolved = playerState.isNextEpisodeMetadataResolved,
                    nextEpisodeHasAired = playerState.nextEpisode?.hasAired,
                    hasError = !playerState.error.isNullOrBlank(),
                    hasBlockingInteraction = playerState.blocksPostPlayRecommendation(),
                    playbackEnded = playerState.playbackEnded,
                    positionMs = timeline.currentPosition,
                    durationMs = timeline.duration
                )
            }
                .distinctUntilChanged()
                .collect { snapshot ->
                    if (lastPlaybackIdentity?.let { it != snapshot.identity } == true) {
                        clearRecommendationState()
                    }
                    lastPlaybackIdentity = snapshot.identity
                    lastSnapshot = snapshot
                    evaluate(snapshot)
                }
        }
    }

    fun playTrailer() {
        startTrailer()
    }

    fun onTrailerEnded() {
        trailerPlayerPool.stop()
        _uiState.update {
            it.copy(
                countdownSeconds = null,
                isTrailerPlaying = false,
                hasAutoPlayedTrailer = true
            )
        }
    }

    fun showPreviousRecommendation() {
        selectRecommendation(-1)
    }

    fun showNextRecommendation() {
        selectRecommendation(1)
    }

    fun returnToPlayer() {
        val state = _uiState.value
        val returnedState = state.returnToPlayer()
        if (returnedState == state) return
        recommendationJob?.cancel()
        recommendationJob = null
        clearRecommendationPipeline()
        postEndCountdownJob?.cancel()
        postEndCountdownJob = null
        returnToPlayerAnimationJob?.cancel()
        _uiState.value = returnedState
        returnToPlayerAnimationJob = scope.launch {
            delay(POST_PLAY_RECOMMENDATION_TRANSITION_MS.toLong())
            _uiState.value = PostPlayRecommendationUiState(hasReturnedToPlayer = true)
            returnToPlayerAnimationJob = null
        }
    }

    fun stop() {
        clearRecommendationState()
        lastSnapshot = null
        lastPlaybackIdentity = null
    }

    private fun clearRecommendationState() {
        recommendationJob?.cancel()
        recommendationJob = null
        clearRecommendationPipeline()
        postEndCountdownJob?.cancel()
        postEndCountdownJob = null
        returnToPlayerAnimationJob?.cancel()
        returnToPlayerAnimationJob = null
        recommendationLoadAttempted = false
        autoPlayTrailerEnabled = postPlayTrailerPlaybackEnabled
        if (_uiState.value.isTrailerPlaying) {
            trailerPlayerPool.stop()
        }
        _uiState.value = PostPlayRecommendationUiState()
    }

    private fun clearRecommendationPipeline() {
        recommendationSelectionJob?.cancel()
        recommendationSelectionJob = null
        recommendationPrefetchJob?.cancel()
        recommendationPrefetchJob = null
        candidateResolutionJobs.values.forEach { it.cancel() }
        candidateResolutionJobs.clear()
        recommendationDetailJobs.values.forEach { it.cancel() }
        recommendationDetailJobs.clear()
        recommendationCandidates = emptyList()
        recommendationCache.clear()
        ratingPreferences = null
    }

    private fun evaluate(snapshot: PlaybackSnapshot) {
        val shouldUseRecommendation = shouldUsePostPlayRecommendation(
            contentType = snapshot.contentType,
            isNextEpisodeMetadataResolved = snapshot.isNextEpisodeMetadataResolved,
            nextEpisodeHasAired = snapshot.nextEpisodeHasAired,
            enabled = snapshot.postPlayRecommendationsEnabled
        )
        if (!shouldUseRecommendation || snapshot.hasError) {
            if (_uiState.value.recommendation != null ||
                _uiState.value.isVisible ||
                _uiState.value.isLoadingRecommendation ||
                recommendationJob != null
            ) {
                clearRecommendationState()
            }
            return
        }

        if (_uiState.value.hasReturnedToPlayer) return

        val effectiveDuration = snapshot.durationMs
            .takeIf { it > 0L }
            ?: playbackController.lastKnownDuration
        if (isShortPlaceholderDuration(effectiveDuration)) return

        if (!recommendationLoadAttempted &&
            shouldPrefetchPostPlayRecommendation(snapshot.positionMs, effectiveDuration)
        ) {
            loadRecommendation()
        }

        var state = _uiState.value
        val recommendation = state.recommendation ?: return
        val shouldShow = PlayerNextEpisodeRules.shouldShowNextEpisodeCard(
            positionMs = snapshot.positionMs,
            durationMs = effectiveDuration,
            skipIntervals = playbackController.skipIntervals,
            thresholdMode = playbackController.nextEpisodeThresholdModeSetting,
            thresholdPercent = playbackController.nextEpisodeThresholdPercentSetting,
            thresholdMinutesBeforeEnd = playbackController.nextEpisodeThresholdMinutesBeforeEndSetting
        ) || snapshot.playbackEnded

        if (!shouldShow) return
        if (!state.isVisible && snapshot.hasBlockingInteraction) return

        if (!state.isVisible) {
            val needsPostEndCountdown = snapshot.playbackEnded &&
                recommendation.hasTrailer &&
                autoPlayTrailerEnabled
            _uiState.update {
                it.copy(
                    isVisible = true,
                    countdownSeconds = if (needsPostEndCountdown) {
                        POST_PLAY_RECOMMENDATION_TRAILER_COUNTDOWN_SECONDS
                    } else {
                        postPlayRecommendationCountdownSeconds(snapshot.positionMs, effectiveDuration)
                    }
                )
            }
            state = _uiState.value
            if (needsPostEndCountdown) {
                startPostEndCountdown()
                return
            }
        }

        if (state.isTrailerPlaying || state.hasAutoPlayedTrailer || !recommendation.hasTrailer) return
        if (!autoPlayTrailerEnabled) {
            if (state.countdownSeconds != null) {
                _uiState.update { it.copy(countdownSeconds = null) }
            }
            return
        }

        if (snapshot.playbackEnded) {
            if (state.countdownSeconds != null) {
                startTrailer()
            } else {
                startPostEndCountdown()
            }
            return
        }

        val countdown = postPlayRecommendationCountdownSeconds(snapshot.positionMs, effectiveDuration)
        if (countdown != state.countdownSeconds) {
            _uiState.update { it.copy(countdownSeconds = countdown) }
        }
    }

    private fun loadRecommendation() {
        recommendationLoadAttempted = true
        recommendationJob = scope.launch {
            _uiState.update { it.copy(isLoadingRecommendation = true) }
            val candidates = try {
                loadCurrentMeta()?.let { loadCandidates(it) }.orEmpty()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                emptyList()
            }
            if (candidates.isEmpty()) {
                _uiState.update { it.copy(isLoadingRecommendation = false) }
                recommendationJob = null
                return@launch
            }

            recommendationCandidates = candidates
            val preferences = loadRatingPreferences()
            ratingPreferences = preferences
            autoPlayTrailerEnabled = postPlayTrailerPlaybackEnabled && runCatching {
                trailerSettingsDataStore.settings.first().enabled
            }.getOrDefault(true)
            val resolvedCandidate = awaitCandidateResolution(0)
            if (resolvedCandidate == null) {
                clearRecommendationPipeline()
                _uiState.update { it.copy(isLoadingRecommendation = false) }
                recommendationJob = null
                return@launch
            }
            val recommendation = cacheRecommendation(0, resolvedCandidate, preferences)
            _uiState.update {
                it.copy(
                    recommendation = recommendation,
                    recommendationIndex = 0,
                    recommendationCount = candidates.size,
                    isLoadingRecommendation = false,
                    isLoadingTrailer = postPlayTrailerPlaybackEnabled
                )
            }
            lastSnapshot?.let(::evaluate)
            prefetchRecommendationDetails(preferences)
            recommendationJob = null
        }
    }

    private fun prefetchRecommendationDetails(preferences: RatingPreferences) {
        recommendationPrefetchJob?.cancel()
        recommendationPrefetchJob = scope.launch {
            val selectedIndex = _uiState.value.recommendationIndex
            val prefetchIndices = (selectedIndex - RECOMMENDATION_PREFETCH_BEHIND..selectedIndex +
                RECOMMENDATION_PREFETCH_AHEAD)
                .filter { it in recommendationCandidates.indices }
            prefetchIndices.forEach { index ->
                launch {
                    val resolvedCandidate = awaitCandidateResolution(index) ?: return@launch
                    cacheRecommendation(index, resolvedCandidate, preferences)
                    loadRecommendationDetails(index, resolvedCandidate, preferences)
                }
            }
        }
    }

    private fun startCandidateResolution(index: Int) {
        if (index !in recommendationCandidates.indices || candidateResolutionJobs.containsKey(index)) return
        val candidate = recommendationCandidates[index]
        candidateResolutionJobs[index] = scope.async {
            try {
                resolveCandidate(candidate)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
        }
    }

    private suspend fun awaitCandidateResolution(index: Int): ResolvedCandidate? {
        startCandidateResolution(index)
        return candidateResolutionJobs[index]?.await()
    }

    private fun cacheRecommendation(
        index: Int,
        resolvedCandidate: ResolvedCandidate,
        preferences: RatingPreferences
    ): PostPlayRecommendation {
        return recommendationCache.getOrPut(index) {
            resolvedCandidate.recommendation.copy(
                showStandardRatings = preferences.showStandardRatings
            )
        }
    }

    private fun selectRecommendation(offset: Int) {
        val state = _uiState.value
        if (!state.isVisible || state.isChangingRecommendation) return
        val targetIndex = state.recommendationIndex + offset
        selectRecommendationAt(targetIndex)
    }

    private fun selectRecommendationAt(targetIndex: Int) {
        val state = _uiState.value
        if (!state.isVisible || state.isChangingRecommendation) return
        if (targetIndex !in recommendationCandidates.indices) return

        postEndCountdownJob?.cancel()
        postEndCountdownJob = null
        if (state.isTrailerPlaying) {
            trailerPlayerPool.stop()
        }
        autoPlayTrailerEnabled = false
        _uiState.update {
            it.copy(
                isChangingRecommendation = true,
                countdownSeconds = null,
                isTrailerPlaying = false
            )
        }
        recommendationSelectionJob?.cancel()
        recommendationSelectionJob = scope.launch {
            try {
                val resolvedCandidate = awaitCandidateResolution(targetIndex)
                val preferences = ratingPreferences
                if (resolvedCandidate == null || preferences == null) {
                    _uiState.update { it.copy(isChangingRecommendation = false) }
                    return@launch
                }
                val recommendation = cacheRecommendation(targetIndex, resolvedCandidate, preferences)
                _uiState.update {
                    it.copy(
                        recommendation = recommendation,
                        recommendationIndex = targetIndex,
                        isChangingRecommendation = false,
                        isLoadingTrailer = postPlayTrailerPlaybackEnabled &&
                            recommendationDetailJobs[targetIndex]?.isCompleted != true
                    )
                }
                loadRecommendationDetails(targetIndex, resolvedCandidate, preferences)
            } finally {
                recommendationSelectionJob = null
            }
        }
    }

    private fun loadRecommendationDetails(
        index: Int,
        resolvedCandidate: ResolvedCandidate,
        preferences: RatingPreferences
    ) {
        if (index !in recommendationCandidates.indices || recommendationDetailJobs.containsKey(index)) return
        val candidate = recommendationCandidates[index]
        recommendationDetailJobs[index] = scope.launch {
            _uiState.update { state ->
                if (state.recommendationIndex == index) {
                    state.copy(isLoadingTrailer = postPlayTrailerPlaybackEnabled)
                } else {
                    state
                }
            }
            val ratingsJob = launch {
                val ratings = loadRatings(
                    candidate = candidate,
                    meta = resolvedCandidate.meta,
                    enabled = preferences.isMdbListActive
                )
                updateCachedRecommendation(index) {
                    it.copy(mdbListRatings = ratings)
                }
            }

            val trailerJob = launch {
                if (!postPlayTrailerPlaybackEnabled) return@launch
                val recommendation = recommendationCache[index] ?: return@launch
                val trailerSource = try {
                    withTimeoutOrNull(15_000L) {
                        // Catalog providers such as Kurato and BingeCat can return
                        // an already-selected YouTube trailer on the preview itself.
                        // Prefer that source so the post-play hero exposes Trailer
                        // even when the provider does not expose a usable Meta route
                        // or TMDB cannot map the provider's id.
                        val trailerMeta = loadTrailerMetadata(
                            candidate = candidate,
                            existingMeta = resolvedCandidate.meta
                        )
                        resolveAddonTrailerSource(
                            candidate = candidate,
                            meta = trailerMeta,
                            title = recommendation.title,
                            year = recommendation.releaseInfo
                        ) ?: trailerService.getTrailerPlaybackSource(
                            title = recommendation.title,
                            year = recommendation.releaseInfo,
                            tmdbId = recommendation.tmdbId,
                            type = recommendation.contentType,
                            ignoreUseTrailersGate = true
                        )
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    null
                }
                updateCachedRecommendation(index) {
                    it.copy(
                        trailerVideoUrl = trailerSource?.videoUrl,
                        trailerAudioUrl = trailerSource?.audioUrl
                    )
                }
                _uiState.update { state ->
                    if (state.recommendationIndex == index) state.copy(isLoadingTrailer = false) else state
                }
                if (_uiState.value.recommendationIndex == index) {
                    lastSnapshot?.let(::evaluate)
                }
            }

            ratingsJob.join()
            trailerJob.join()
            _uiState.update { state ->
                if (state.recommendationIndex == index) state.copy(isLoadingTrailer = false) else state
            }
        }
    }

    private fun updateCachedRecommendation(
        index: Int,
        transform: (PostPlayRecommendation) -> PostPlayRecommendation
    ) {
        val recommendation = recommendationCache[index]?.let(transform) ?: return
        recommendationCache[index] = recommendation
        _uiState.update { state ->
            if (state.recommendationIndex == index) {
                state.copy(recommendation = recommendation)
            } else {
                state
            }
        }
    }

    private suspend fun loadRatingPreferences(): RatingPreferences {
        val settings = mdbListSettingsDataStore.settings.first()
        val isMdbListActive = settings.enabled && settings.apiKey.isNotBlank()
        val visibility = layoutPreferenceDataStore.homeImdbRatingsVisibility.first()
        return RatingPreferences(
            isMdbListActive = isMdbListActive,
            showStandardRatings = visibility.showStandardDetailRatings(isMdbListActive)
        )
    }

    private suspend fun loadRatings(
        candidate: MetaPreview,
        meta: Meta?,
        enabled: Boolean
    ) = if (!enabled || meta == null) {
        null
    } else {
        try {
            mdbListRepository.getRatingsForMeta(
                meta = meta,
                fallbackItemId = candidate.id,
                fallbackItemType = candidate.apiType
            )?.ratings
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun loadCurrentMeta(): Meta? {
        val id = playbackController.contentId ?: return null
        val type = playbackController.contentType ?: return null
        metaRepository.getCachedMeta(type, id)?.let { return it }
        return withTimeoutOrNull(8_000L) {
            when (
                val result = metaRepository.getMetaFromAllAddons(type = type, id = id)
                    .first { it !is NetworkResult.Loading }
            ) {
                is NetworkResult.Success -> result.data
                else -> null
            }
        }
    }

    private suspend fun loadCandidates(meta: Meta): List<MetaPreview> {
        val tmdbContentType = resolvePostPlayContentType(
            apiType = playbackController.contentType,
            fallback = meta.type
        ) ?: return emptyList()
        val sourcePreference = playerSettingsDataStore.playerSettings
            .first()
            .postPlayRecommendationSource
        val shouldTryKurato = sourcePreference == PostPlayRecommendationSource.AUTO ||
            sourcePreference == PostPlayRecommendationSource.KURATO_AI
        val kuratoCandidates = if (shouldTryKurato) {
            try {
                withTimeoutOrNull(KURATO_RECOMMENDATION_TIMEOUT_MS) {
                    loadKuratoCandidates(meta, tmdbContentType)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
        } else {
            null
        }
        val shouldTryBingeCat = sourcePreference == PostPlayRecommendationSource.AUTO ||
            sourcePreference == PostPlayRecommendationSource.BINGECAT_AI
        val bingeCatCandidates = if (shouldTryBingeCat && kuratoCandidates == null) {
            try {
                withTimeoutOrNull(BINGECAT_RECOMMENDATION_TIMEOUT_MS) {
                    loadBingeCatCandidates(meta, tmdbContentType)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
        } else {
            null
        }
        val candidates = kuratoCandidates ?: bingeCatCandidates ?: withTimeoutOrNull(10_000L) {
            loadLegacyCandidates(meta, tmdbContentType, sourcePreference)
        }.orEmpty()

        val hideUnreleased = layoutPreferenceDataStore.hideUnreleasedContent.first()
        val watchedIds = combine(
            watchProgressRepository.observeWatchedMovieIds(),
            watchedSeriesStateHolder.fullyWatchedSeriesIds
        ) { movieIds, seriesIds -> movieIds to seriesIds }.first()
        val currentIds = setOfNotNull(meta.id.normalizedId(), playbackController.contentId?.normalizedId())
        val filtered = candidates
            .asSequence()
            .filterNot { it.id.normalizedId() in currentIds }
            .filterNot { candidate ->
                isPostPlayCandidateWatched(
                    candidate = candidate,
                    watchedMovieIds = watchedIds.first,
                    watchedSeriesIds = watchedIds.second
                )
            }
            .filterNot { hideUnreleased && it.isUnreleased(LocalDate.now()) }
            .distinctBy { it.apiType.normalizedId() to it.id.normalizedId() }
            .toList()
        val first = filtered.firstOrNull { !it.backdropUrl.isNullOrBlank() }
            ?: filtered.firstOrNull()
            ?: return emptyList()
        return buildList {
            add(first)
            val remaining = filtered.asSequence().filterNot { it === first }
            remaining.forEach(::add)
        }
    }

    /**
     * Resolve trailers supplied by the selected catalog before doing a global
     * title/TMDB lookup. This is important for addon-owned recommendation
     * catalogs: their catalog response can contain a valid trailer while their
     * Meta endpoint is absent, incomplete, or uses a provider-specific id.
     */
    private suspend fun resolveAddonTrailerSource(
        candidate: MetaPreview,
        meta: Meta?,
        title: String,
        year: String?
    ): TrailerPlaybackSource? {
        val trailerValues = sequenceOf(
            meta?.trailerYtIds.orEmpty().asSequence(),
            meta?.trailers.orEmpty().asSequence().mapNotNull { it.ytId },
            candidate.trailerYtIds.asSequence(),
            candidate.trailers.asSequence().mapNotNull { it.ytId }
        )
            .flatten()
            .map(String::trim)
            .filter(String::isNotBlank)
            .distinct()
            .mapNotNull(::normalizeAddonTrailerUrl)
            .toList()

        for (youtubeUrl in trailerValues) {
            val source = trailerService.getTrailerPlaybackSourceFromYouTubeUrl(
                youtubeUrl = youtubeUrl,
                title = title,
                year = year
            )
            if (source != null) return source
        }
        return null
    }

    /**
     * Follow the same all-addon metadata routing used by Nuvio's detail/player
     * screens when the catalog item itself did not carry trailer data. This
     * keeps Kurato/BingeCat recommendations compatible with the user's active
     * metadata addon instead of making their addon the metadata authority.
     */
    private suspend fun loadTrailerMetadata(
        candidate: MetaPreview,
        existingMeta: Meta?
    ): Meta? {
        val hasTrailerData = existingMeta?.trailerYtIds.orEmpty().isNotEmpty() ||
            existingMeta?.trailers.orEmpty().any { !it.ytId.isNullOrBlank() }
        if (hasTrailerData || candidate.trailerYtIds.isNotEmpty() || candidate.trailers.any { !it.ytId.isNullOrBlank() }) {
            return existingMeta
        }

        return withTimeoutOrNull(8_000L) {
            when (
                val result = metaRepository.getMetaFromAllAddons(
                    type = candidate.apiType,
                    id = candidate.id
                ).first { it !is NetworkResult.Loading }
            ) {
                is NetworkResult.Success -> result.data
                else -> existingMeta
            }
        } ?: existingMeta
    }

    private fun normalizeAddonTrailerUrl(value: String): String? {
        if (value.matches(Regex("^[a-zA-Z0-9_-]{11}$"))) {
            return "https://www.youtube.com/watch?v=$value"
        }

        val normalized = value.trim()
        if (!normalized.startsWith("https://") && !normalized.startsWith("http://")) {
            return null
        }
        val lower = normalized.lowercase()
        return normalized.takeIf {
            lower.contains("youtube.com/") || lower.contains("youtu.be/")
        }
    }

    private suspend fun loadLegacyCandidates(
        meta: Meta,
        contentType: ContentType,
        sourcePreference: PostPlayRecommendationSource
    ): List<MetaPreview> {
        val traktAuthenticated = traktAuthDataStore.isAuthenticated.first()
        if (sourcePreference != PostPlayRecommendationSource.TMDB &&
            (sourcePreference == PostPlayRecommendationSource.TRAKT ||
                traktSettingsDataStore.moreLikeThisSource.first() == MoreLikeThisSourcePreference.TRAKT) &&
            traktAuthenticated
        ) {
            return runCatching {
                traktRelatedService.getRelated(
                    meta = meta,
                    fallbackItemId = playbackController.contentId,
                    fallbackItemType = playbackController.contentType
                )
            }.getOrDefault(emptyList())
        }

        val settings = tmdbSettingsDataStore.settings.first()
        if (!settings.enabled || !settings.useMoreLikeThis) return emptyList()
        val lookupType = contentType.toApiString(playbackController.contentType)
        val tmdbId = tmdbService.ensureTmdbId(meta.id, lookupType)
            ?: playbackController.contentId?.let { tmdbService.ensureTmdbId(it, lookupType) }
            ?: return emptyList()
        return runCatching {
            tmdbMetadataService.fetchMoreLikeThis(
                tmdbId = tmdbId,
                contentType = contentType,
                language = settings.language
            )
        }.getOrDefault(emptyList())
    }

    private suspend fun loadKuratoCandidates(
        meta: Meta,
        contentType: ContentType
    ): List<MetaPreview>? {
        val installedAddons = addonRepository.getInstalledAddons().first()
        val match = findKuratoAiCatalog(installedAddons, contentType) ?: return null
        val (addon, catalog) = match
        val pageSize = (catalog.pageSize ?: KURATO_DEFAULT_PAGE_SIZE)
            .coerceIn(1, KURATO_MAX_PAGE_SIZE)
        val query = buildKuratoRecommendationQuery(meta, contentType)
        val items = LinkedHashMap<String, MetaPreview>()
        var pageStart = 0
        while (true) {
            val itemCountBeforePage = items.size
            val offsets = (0 until KURATO_PAGE_WINDOW).map { pageStart + it * pageSize }
            val pages = kotlinx.coroutines.coroutineScope {
                offsets.map { skip ->
                    async {
                        fetchKuratoCatalogPage(
                            addon = addon,
                            catalog = catalog,
                            contentType = contentType,
                            skip = skip,
                            pageSize = pageSize,
                            query = query
                        )
                    }
                }.awaitAll()
            }
            pages.forEach { page ->
                page.items.forEach { item ->
                    items.putIfAbsent("${item.apiType}:${item.id}".lowercase(), item)
                }
            }
            val shouldStop = items.size == itemCountBeforePage ||
                pages.any { it.items.isEmpty() || !it.hasMore } ||
                pages.any { it.items.size < pageSize }
            if (shouldStop) break
            pageStart += pageSize * KURATO_PAGE_WINDOW
        }
        return items.values.toList()
    }

    private suspend fun loadBingeCatCandidates(
        meta: Meta,
        contentType: ContentType
    ): List<MetaPreview>? {
        val installedAddons = addonRepository.getInstalledAddons().first()
        val match = findBingeCatAiCatalog(installedAddons, contentType) ?: return null
        val (addon, catalog) = match
        val pageSize = (catalog.pageSize ?: BINGECAT_DEFAULT_PAGE_SIZE)
            .coerceIn(1, BINGECAT_MAX_PAGE_SIZE)
        val query = buildBingeCatRecommendationQuery(meta, contentType)
        val items = LinkedHashMap<String, MetaPreview>()
        var pageStart = 0
        while (true) {
            val itemCountBeforePage = items.size
            val offsets = (0 until BINGECAT_PAGE_WINDOW).map { pageStart + it * pageSize }
            val pages = kotlinx.coroutines.coroutineScope {
                offsets.map { skip ->
                    async {
                        fetchBingeCatCatalogPage(
                            addon = addon,
                            catalog = catalog,
                            contentType = contentType,
                            skip = skip,
                            pageSize = pageSize,
                            query = query
                        )
                    }
                }.awaitAll()
            }
            pages.forEach { page ->
                page.items.forEach { item ->
                    items.putIfAbsent("${item.apiType}:${item.id}".lowercase(), item)
                }
            }
            val shouldStop = items.size == itemCountBeforePage ||
                pages.any { it.items.isEmpty() || !it.hasMore } ||
                pages.any { it.items.size < pageSize }
            if (shouldStop) break
            pageStart += pageSize * BINGECAT_PAGE_WINDOW
        }
        return items.values.toList()
    }

    private suspend fun fetchKuratoCatalogPage(
        addon: Addon,
        catalog: CatalogDescriptor,
        contentType: ContentType,
        skip: Int,
        pageSize: Int,
        query: String
    ): CatalogRow {
        val emissions = catalogRepository.getCatalog(
            addonBaseUrl = addon.baseUrl,
            addonId = addon.id,
            addonName = addon.displayName,
            catalogId = catalog.id,
            catalogName = catalog.name,
            type = catalog.apiType,
            skip = skip,
            skipStep = pageSize,
            extraArgs = mapOf("search" to query),
            supportsSkip = true
        ).toList()
        val success = emissions.asReversed().firstOrNull { it is NetworkResult.Success<*> }
        return (success as? NetworkResult.Success<*>)?.data as? CatalogRow ?:
            CatalogRow(
                addonId = addon.id,
                addonName = addon.displayName,
                addonBaseUrl = addon.baseUrl,
                catalogId = catalog.id,
                catalogName = catalog.name,
                type = contentType,
                rawType = catalog.rawType,
                items = emptyList(),
                hasMore = false,
                supportsSkip = true,
                skipStep = pageSize
            )
    }

    private suspend fun fetchBingeCatCatalogPage(
        addon: Addon,
        catalog: CatalogDescriptor,
        contentType: ContentType,
        skip: Int,
        pageSize: Int,
        query: String
    ): CatalogRow {
        val emissions = catalogRepository.getCatalog(
            addonBaseUrl = addon.baseUrl,
            addonId = addon.id,
            addonName = addon.displayName,
            catalogId = catalog.id,
            catalogName = catalog.name,
            type = catalog.apiType,
            skip = skip,
            skipStep = pageSize,
            extraArgs = mapOf("search" to query),
            supportsSkip = true
        ).toList()
        val success = emissions.asReversed().firstOrNull { it is NetworkResult.Success<*> }
        return (success as? NetworkResult.Success<*>)?.data as? CatalogRow ?:
            CatalogRow(
                addonId = addon.id,
                addonName = addon.displayName,
                addonBaseUrl = addon.baseUrl,
                catalogId = catalog.id,
                catalogName = catalog.name,
                type = contentType,
                rawType = catalog.rawType,
                items = emptyList(),
                hasMore = false,
                supportsSkip = true,
                skipStep = pageSize
            )
    }

    private suspend fun resolveCandidate(candidate: MetaPreview): ResolvedCandidate {
        val settings = tmdbSettingsDataStore.settings.first()
        val meta = try {
            loadCandidateMeta(candidate)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
        val candidateContentType = resolvePostPlayContentType(
            apiType = meta?.apiType ?: candidate.apiType,
            fallback = meta?.type ?: candidate.type
        )
        val tmdbId = try {
            tmdbService.ensureTmdbId(
                videoId = meta?.id ?: candidate.id,
                mediaType = meta?.apiType ?: candidate.apiType
            ) ?: if (meta?.id != candidate.id) {
                tmdbService.ensureTmdbId(candidate.id, candidate.apiType)
            } else {
                null
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
        val enrichment = if (settings.enabled && tmdbId != null && candidateContentType != null) {
            try {
                withTimeoutOrNull(12_000L) {
                    tmdbMetadataService.fetchEnrichment(
                        tmdbId = tmdbId,
                        contentType = candidateContentType,
                        language = settings.language
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
        } else {
            null
        }
        return ResolvedCandidate(
            recommendation = resolvePostPlayRecommendation(
                candidate = candidate,
                meta = meta,
                enrichment = enrichment,
                settings = settings,
                tmdbId = tmdbId
            ),
            meta = meta
        )
    }

    private suspend fun loadCandidateMeta(candidate: MetaPreview): Meta? {
        metaRepository.getCachedMeta(candidate.apiType, candidate.id)?.let { return it }
        return withTimeoutOrNull(8_000L) {
            when (
                val result = metaRepository.getMetaFromAllAddons(
                    type = candidate.apiType,
                    id = candidate.id,
                    sourceAddonBaseUrl = candidate.sourceAddonBaseUrl
                ).first { it !is NetworkResult.Loading }
            ) {
                is NetworkResult.Success -> result.data
                else -> null
            }
        }
    }

    private fun startPostEndCountdown() {
        val state = _uiState.value
        if (!postPlayTrailerPlaybackEnabled ||
            postEndCountdownJob?.isActive == true ||
            state.isTrailerPlaying ||
            state.hasAutoPlayedTrailer ||
            state.recommendation?.hasTrailer != true
        ) {
            return
        }
        postEndCountdownJob = scope.launch {
            for (seconds in POST_PLAY_RECOMMENDATION_TRAILER_COUNTDOWN_SECONDS downTo 1) {
                _uiState.update { it.copy(countdownSeconds = seconds) }
                delay(1_000L)
            }
            startTrailer()
        }
    }

    private fun startTrailer() {
        val state = _uiState.value
        if (!postPlayTrailerPlaybackEnabled ||
            state.isTrailerPlaying ||
            state.recommendation?.hasTrailer != true
        ) {
            return
        }
        postEndCountdownJob?.cancel()
        postEndCountdownJob = null
        playbackController.releasePlayer()
        trailerPlayerPool.reclaim()
        _uiState.update {
            it.copy(
                isVisible = true,
                countdownSeconds = null,
                isTrailerPlaying = true,
                hasAutoPlayedTrailer = true
            )
        }
    }
}

private const val KURATO_DEFAULT_PAGE_SIZE = 50
private const val KURATO_MAX_PAGE_SIZE = 100
private const val KURATO_PAGE_WINDOW = 4
private const val KURATO_RECOMMENDATION_TIMEOUT_MS = 15_000L
private const val BINGECAT_DEFAULT_PAGE_SIZE = 50
private const val BINGECAT_MAX_PAGE_SIZE = 100
private const val BINGECAT_PAGE_WINDOW = 4
private const val BINGECAT_RECOMMENDATION_TIMEOUT_MS = 15_000L
private const val RECOMMENDATION_PREFETCH_BEHIND = 1
private const val RECOMMENDATION_PREFETCH_AHEAD = 4

internal fun buildKuratoRecommendationQuery(meta: Meta, contentType: ContentType): String {
    val title = meta.name.trim().ifBlank { "this title" }
    val kind = if (contentType == ContentType.SERIES) "TV shows" else "movies"
    val genres = meta.genres
        .asSequence()
        .map(String::trim)
        .filter(String::isNotBlank)
        .distinct()
        .take(3)
        .toList()
    val genreHint = genres.takeIf { it.isNotEmpty() }
        ?.joinToString(", ")
        ?.let { " Focus on similar tone and genres such as $it." }
        .orEmpty()
    return "Recommend $kind similar to \"$title\".$genreHint Return titles only, not episodes."
}

internal fun buildBingeCatRecommendationQuery(meta: Meta, contentType: ContentType): String {
    val title = meta.name.trim().ifBlank { "this title" }
    val kind = if (contentType == ContentType.SERIES) "TV shows" else "movies"
    val genres = meta.genres
        .asSequence()
        .map(String::trim)
        .filter(String::isNotBlank)
        .distinct()
        .take(3)
        .toList()
    val genreHint = genres.takeIf { it.isNotEmpty() }
        ?.joinToString(", ")
        ?.let { " with a similar tone and genres such as $it" }
        .orEmpty()
    return "${kind} like \"$title\"$genreHint"
}

internal fun findKuratoAiCatalog(
    addons: List<Addon>,
    contentType: ContentType
): Pair<Addon, CatalogDescriptor>? {
    val expectedCatalogId = if (contentType == ContentType.SERIES) {
        "kurato-ai-discover-series"
    } else {
        "kurato-ai-discover-movie"
    }
    val enabledAddons = addons.asSequence().filter { it.enabled }.toList()
    val preferredAddons = enabledAddons.sortedByDescending { addon ->
        if (addon.id.equals("org.aiostreams.kurato", ignoreCase = true) ||
            addon.name.contains("kurato", ignoreCase = true) ||
            addon.displayName.contains("kurato", ignoreCase = true)
        ) 1 else 0
    }
    preferredAddons.forEach { addon ->
        val exact = addon.catalogs.firstOrNull { catalog ->
            catalog.id.equals(expectedCatalogId, ignoreCase = true) &&
                resolvePostPlayContentType(catalog.apiType, catalog.type) == contentType
        }
        if (exact != null) return addon to exact
    }
    return preferredAddons
        .mapNotNull { addon ->
            addon.catalogs.firstOrNull { catalog ->
                catalog.id.contains("kurato-ai-discover", ignoreCase = true) &&
                    resolvePostPlayContentType(catalog.apiType, catalog.type) == contentType
            }?.let { addon to it }
        }
        .firstOrNull()
}

internal fun findBingeCatAiCatalog(
    addons: List<Addon>,
    contentType: ContentType
): Pair<Addon, CatalogDescriptor>? {
    val expectedCatalogId = if (contentType == ContentType.SERIES) {
        "aicat_search_series"
    } else {
        "aicat_search_movie"
    }
    val enabledAddons = addons.asSequence().filter { it.enabled }.toList()
    val preferredAddons = enabledAddons.sortedByDescending { addon ->
        if (addon.id.contains("aicat", ignoreCase = true) ||
            addon.id.contains("bingecat", ignoreCase = true) ||
            addon.name.contains("bingecat", ignoreCase = true) ||
            addon.displayName.contains("bingecat", ignoreCase = true)
        ) 1 else 0
    }
    preferredAddons.forEach { addon ->
        val exact = addon.catalogs.firstOrNull { catalog ->
            catalog.id.equals(expectedCatalogId, ignoreCase = true) &&
                resolvePostPlayContentType(catalog.apiType, catalog.type) == contentType
        }
        if (exact != null) return addon to exact
    }
    return preferredAddons
        .mapNotNull { addon ->
            addon.catalogs.firstOrNull { catalog ->
                catalog.id.contains("aicat_search", ignoreCase = true) &&
                    resolvePostPlayContentType(catalog.apiType, catalog.type) == contentType
            }?.let { addon to it }
        }
        .firstOrNull()
}

private fun String.normalizedId(): String = trim().lowercase()
