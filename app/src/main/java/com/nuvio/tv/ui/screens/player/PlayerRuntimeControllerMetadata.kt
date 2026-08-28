package com.nuvio.tv.ui.screens.player

import com.nuvio.tv.R
import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.data.local.AutoSkipSegmentType
import com.nuvio.tv.data.repository.SkipInterval
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.Meta
import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.domain.model.resolveContentLanguage
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal fun PlayerRuntimeController.fetchMetaDetails(id: String?, type: String?) {
    if (id.isNullOrBlank() || type.isNullOrBlank()) return

    metaFetchJob = scope.launch {
        when (
            val result = metaRepository.getMetaFromAllAddons(type = type, id = id)
                .first { it !is NetworkResult.Loading }
        ) {
            is NetworkResult.Success -> {
                applyMetaDetails(result.data)
            }
            is NetworkResult.Error -> {
            }
            NetworkResult.Loading -> {
            }
        }
    }

    scope.launch {
        enrichDescriptionFromTmdb(id, type)
    }
}

internal fun PlayerRuntimeController.initializeCloudPlaybackSequence() {
    val playbackContext = cloudPlaybackContext ?: return
    metaVideos = playbackContext.asVideos()
    val currentFile = playbackContext.currentFile ?: return
    currentVideoId = playbackContext.videoId(currentFile)
    currentSeason = 1
    currentEpisode = playbackContext.currentIndex + 1
    currentEpisodeTitle = currentFile.name
    _uiState.update {
        it.copy(
            currentVideoId = currentVideoId,
            currentSeason = currentSeason,
            currentEpisode = currentEpisode,
            currentEpisodeTitle = currentEpisodeTitle
        )
    }
    recomputeNextEpisode(resetVisibility = false)
}

internal fun PlayerRuntimeController.applyMetaDetails(meta: Meta) {
    metaVideos = meta.videos
    metaGenres = meta.genres
    metaCountry = meta.country
    // Fill in content language from meta if not provided via navigation args.
    if (contentLanguage == null) {
        contentLanguage = meta.resolveContentLanguage()
    }
    val description = resolveDescription(meta)

    recomputeNextEpisode(resetVisibility = false)
    _uiState.update { state ->
        state.copy(
            description = description ?: state.description,
            castMembers = if (meta.castMembers.isNotEmpty()) meta.castMembers else state.castMembers,
            isNextEpisodeMetadataResolved = true
        )
    }
}

internal fun PlayerRuntimeController.resolveDescription(meta: Meta): String? {
    val type = contentType
    if (type in listOf("series", "tv") && currentSeason != null && currentEpisode != null) {
        val episodeOverview = meta.videos.firstOrNull { video ->
            video.season == currentSeason && video.episode == currentEpisode
        }?.overview
        if (!episodeOverview.isNullOrBlank()) return episodeOverview
    }

    return meta.description
}

internal fun PlayerRuntimeController.updateEpisodeDescription() {
    val overview = metaVideos.firstOrNull { video ->
        video.season == currentSeason && video.episode == currentEpisode
    }?.overview

    // Always update description when switching episodes - clear stale description
    _uiState.update { it.copy(description = overview) }

    // Push episode metadata to the MediaSession so Google Home shows the new episode.
    updateMediaSessionMetadata()

    // Cloud library IDs belong to the provider, not TMDB.
    if (!contentType.equals("cloud", ignoreCase = true)) {
        scope.launch {
            enrichDescriptionFromTmdb(contentId, contentType)
        }
    }
}

private suspend fun PlayerRuntimeController.enrichDescriptionFromTmdb(id: String?, type: String?) {
    if (id.isNullOrBlank() || type.isNullOrBlank()) return
    val settings = tmdbSettingsDataStore.settings.first()
    if (!settings.enabled || !settings.useBasicInfo) return

    val tmdbId = runCatching { tmdbService.ensureTmdbId(id, type) }.getOrNull() ?: return
    val contentType = when (type.lowercase()) {
        "series", "tv" -> ContentType.SERIES
        else -> ContentType.MOVIE
    }
    val enrichment = runCatching {
        tmdbMetadataService.fetchEnrichment(
            tmdbId = tmdbId,
            contentType = contentType,
            language = settings.language
        )
    }.getOrNull() ?: return

    val isSeries = type.lowercase() in listOf("series", "tv")
    val season = currentSeason
    val episode = currentEpisode

    // For series, try to get episode-level overview and title from TMDB.
    val episodeEnrichment = if (isSeries && season != null && episode != null) {
        runCatching {
            tmdbMetadataService.fetchEpisodeEnrichment(
                tmdbId = tmdbId,
                seasonNumbers = listOf(season),
                language = settings.language
            )[season to episode]
        }.getOrNull()
    } else null

    val tmdbDescription = episodeEnrichment?.overview ?: enrichment.description
    if (settings.useBasicInfo && !tmdbDescription.isNullOrBlank()) {
        _uiState.update { it.copy(description = tmdbDescription) }
    }

    // Enrich title from TMDB (localized).
    if (settings.useBasicInfo) {
        val tmdbTitle = enrichment.localizedTitle
        if (!tmdbTitle.isNullOrBlank()) {
            _uiState.update { it.copy(title = tmdbTitle) }
        }
    }

    // Enrich logo from TMDB if artwork is enabled.
    if (settings.useArtwork) {
        val tmdbLogo = enrichment.logo
        if (!tmdbLogo.isNullOrBlank()) {
            _uiState.update { it.copy(logo = tmdbLogo) }
        }
    }

    // Also enrich episode title from TMDB if available.
    if (settings.useBasicInfo) {
        val tmdbEpisodeTitle = episodeEnrichment?.title
        if (!tmdbEpisodeTitle.isNullOrBlank()) {
            _uiState.update { it.copy(currentEpisodeTitle = tmdbEpisodeTitle) }
        }
    }

    // Enrich cast from TMDB if addon didn't provide any.
    if (settings.useBasicInfo && enrichment.castMembers.isNotEmpty()) {
        _uiState.update { state ->
            if (state.castMembers.isEmpty()) state.copy(castMembers = enrichment.castMembers)
            else state
        }
    }

    // Refresh MediaSession metadata with TMDB-enriched title / artwork.
    updateMediaSessionMetadata()
}

internal fun PlayerRuntimeController.recomputeNextEpisode(resetVisibility: Boolean) {
    val normalizedType = contentType?.lowercase()
    if (normalizedType !in listOf("series", "tv", "other", "cloud")) {
        nextEpisodeVideo = null
        clearNextEpisodeAndCancelPostPlay()
        return
    }

    if (normalizedType == "other" || normalizedType == "cloud") {
        val currentId = currentVideoId
        val idx = if (currentId != null) metaVideos.indexOfFirst { it.id == currentId } else -1
        val resolvedNext = if (idx >= 0 && idx < metaVideos.size - 1) metaVideos[idx + 1] else null
        nextEpisodeVideo = resolvedNext
        if (resolvedNext == null) {
            clearNextEpisodeAndCancelPostPlay()
            return
        }
        val nextInfo = NextEpisodeInfo(
            videoId = resolvedNext.id,
            season = resolvedNext.season ?: 1,
            episode = resolvedNext.episode ?: (idx + 2),
            title = resolvedNext.title,
            thumbnail = resolvedNext.thumbnail,
            overview = resolvedNext.overview,
            released = resolvedNext.released,
            hasAired = true,
            unairedMessage = null,
            isOtherType = normalizedType == "other" || normalizedType == "cloud"
        )
        applyRecomputedNextEpisode(nextInfo, resetVisibility)
        return
    }

    val season = currentSeason
    val episode = currentEpisode
    if (season == null || episode == null) {
        nextEpisodeVideo = null
        clearNextEpisodeAndCancelPostPlay()
        return
    }

    val resolvedNext = PlayerNextEpisodeRules.resolveNextEpisode(
        videos = metaVideos,
        currentSeason = season,
        currentEpisode = episode
    )

    nextEpisodeVideo = resolvedNext
    if (resolvedNext == null) {
        clearNextEpisodeAndCancelPostPlay()
        return
    }

    val hasAired = PlayerNextEpisodeRules.hasEpisodeAired(resolvedNext.released)
    val nextInfo = NextEpisodeInfo(
        videoId = resolvedNext.id,
        season = resolvedNext.season ?: return,
        episode = resolvedNext.episode ?: return,
        title = resolvedNext.title,
        thumbnail = resolvedNext.thumbnail,
        overview = resolvedNext.overview,
        released = resolvedNext.released,
        hasAired = hasAired,
        unairedMessage = if (hasAired) {
            null
        } else {
            context.getString(com.nuvio.tv.R.string.next_episode_not_aired_yet)
        }
    )
    applyRecomputedNextEpisode(nextInfo, resetVisibility)
}

private fun PlayerRuntimeController.clearNextEpisodeAndCancelPostPlay() {
    val mode = _uiState.value.postPlayMode
    if (mode != null) {
        resetPostPlayOverlayState(clearEpisode = true)
        return
    }
    _uiState.update {
        it.copy(
            nextEpisode = null,
            postPlayDismissedForCurrentEpisode = false,
        )
    }
}

private fun PlayerRuntimeController.applyRecomputedNextEpisode(
    nextInfo: NextEpisodeInfo,
    resetVisibility: Boolean,
) {
    val previousState = _uiState.value
    val previousNextEpisode = previousState.nextEpisode
    val previousMode = previousState.postPlayMode
    if (previousMode is PostPlayMode.StillWatching &&
        previousNextEpisode != null &&
        previousNextEpisode.videoId != nextInfo.videoId
    ) {
        resetPostPlayOverlayState(clearEpisode = true)
        return
    }
    _uiState.update { state ->
        val sameEpisode = state.nextEpisode?.videoId == nextInfo.videoId
        val shouldResetVisibility = resetVisibility || !sameEpisode
        val updatedMode = if (shouldResetVisibility) {
            null
        } else {
            state.postPlayMode?.copyWithNextEpisode(nextInfo)
        }
        state.copy(
            nextEpisode = nextInfo,
            postPlayMode = updatedMode,
            postPlayDismissedForCurrentEpisode =
                if (shouldResetVisibility && !state.postPlayDismissedForCurrentEpisode) false
                else state.postPlayDismissedForCurrentEpisode,
        )
    }
}

internal fun PlayerRuntimeController.resetPostPlayOverlayState(clearEpisode: Boolean = false) {
    nextEpisodeAutoPlayJob?.cancel()
    nextEpisodeAutoPlayJob = null
    stillWatchingPromptJob?.cancel()
    stillWatchingPromptJob = null
    _uiState.update { state ->
        state.copy(
            nextEpisode = if (clearEpisode) null else state.nextEpisode,
            postPlayMode = null,
            postPlayDismissedForCurrentEpisode = false,
        )
    }
    if (clearEpisode) {
        nextEpisodeVideo = null
    }
}

/**
 * S5 binge lookahead: start the next episode's scrape before it is pressed.
 *
 * Called from [evaluatePostPlayOverlayVisibility], which the progress loop
 * already invokes on every tick for both engines with the position and
 * duration this needs. Sharing that call site is deliberate -- a second
 * per-tick hook would be a second thing to keep in step.
 *
 * Why the trigger is near the END of the episode and not at its start:
 * StreamPrefetchCache entries carry debrid cached-availability annotations
 * and expire after five minutes. A prefetch fired when playback began would
 * be forty minutes stale by the time the next-episode button is pressed.
 *
 * Six minutes exceeds that five-minute TTL by design (Paul's call, 26 Jul).
 * The first fire expires with about a minute of episode left, and because
 * [StreamPrefetchCache.prefetch] no-ops on a fresh or identical in-flight key,
 * the very next tick after expiry re-fires it for free -- no re-arm state to
 * hold. The second entry lands with roughly fifty seconds to spare and stays
 * fresh for about four minutes past the episode end, which also covers
 * dwelling on the post-play card. Cost is two scrape cycles per episode.
 *
 * S5 part 3: a ranker IS now supplied, so the lookahead also ranks and
 * pre-resolves the winner. Two things follow from that, and the second is why
 * it was promoted ahead of everything left in the segment:
 *
 *  - the ~873 ms debrid resolve leaves the press path entirely;
 *  - the resolve is what REVEALS THE CDN HOST, and Patch B's prewarm hangs off
 *    it. The 26 Jul nt3 capture caught the next episode resolving to
 *    nexus-170 while every pooled connection was to nexus-196, so the probe
 *    paid a full cold connect including fresh DNS (1,480 ms to headers).
 *    OkHttp pools per host: warming the wrong node is worth nothing. Resolving
 *    early is the only way to learn the right one.
 *
 * Runway note: connections idle out of the pool after three minutes, so the
 * six-minute fire warms a node that will have gone cold by the press. The TTL
 * re-fire about a minute before the end is the one that lands, and it
 * re-resolves against the resolver's own 15-minute link cache, so the second
 * pass is cheap.
 */
internal fun PlayerRuntimeController.maybePrefetchNextEpisodeForBinge(
    positionMs: Long,
    durationMs: Long
) {
    if (!hasRenderedFirstFrame) return
    val type = contentType ?: return
    val nextVideo = nextEpisodeVideo ?: return
    if (_uiState.value.nextEpisode?.hasAired != true) return

    val effectiveDuration = durationMs.takeIf { it > 0L } ?: lastKnownDuration
    if (effectiveDuration <= 0L) return
    val remainingMs = effectiveDuration - positionMs
    if (remainingMs <= 0L || remainingMs > BINGE_LOOKAHEAD_TRIGGER_MS) return

    com.nuvio.tv.core.stream.StreamPrefetchCache.prefetch(
        repository = streamRepository,
        type = type,
        videoId = nextVideo.id,
        season = nextVideo.season,
        episode = nextVideo.episode,
        source = "binge_lookahead",
        background = true,
        rank = { groups ->
            // Mirror what the press SETTLES on, which is not its first
            // attempt. PlayerRuntimeControllerStreams tries
            // tryBingeGroupOnly opportunistically while the scrape is still
            // arriving, but when nothing matches it falls through on timeout
            // to trySelectStream -- a full select carrying
            // currentStreamBingeGroup as a PREFERENCE, not a requirement.
            //
            // nt10 replicated only the early attempt and stopped there. The
            // 27 Jul capture caught the consequence immediately: no stream in
            // the next episode shared the current binge group, the lookahead
            // returned winner=none, and the transition paid a full 1,743 ms
            // resolve and a 2,093 ms cold probe -- worse than the
            // quality-ranked guess it replaced. The press meanwhile settled
            // in 343 ms with selectCalls=2, which is the fall-through in the
            // log. Cross-episode binge-group matches are the exception, not
            // the rule, so the fall-through is the common path and the only
            // one worth predicting.
            val preferBinge = playerSettingsDataStore.playerSettings.first()
                .streamAutoPlayPreferBingeGroupForNextEpisode
            val lookaheadBingeGroup = currentStreamBingeGroup?.takeIf { preferBinge }
            prefetchSelectionSupplier.rankAndPreResolve(
                groups = groups,
                contentId = contentId,
                season = nextVideo.season,
                episode = nextVideo.episode,
                bingeOverride = lookaheadBingeGroup
            )
        }
    )
}

/** S5: how much of the episode may remain when the lookahead prefetch fires. */
private const val BINGE_LOOKAHEAD_TRIGGER_MS = 6L * 60L * 1000L

internal fun PlayerRuntimeController.evaluatePostPlayOverlayVisibility(positionMs: Long, durationMs: Long) {
    if (_playbackTimeline.value.isLive) return
    maybePrefetchNextEpisodeForBinge(positionMs, durationMs)
    if (!hasRenderedFirstFrame) return
    // Short debrid/error clips must never arm next-episode auto-play (see #2819).
    val effectiveDurationEarly = durationMs.takeIf { it > 0L } ?: lastKnownDuration
    if (isShortPlaceholderDuration(effectiveDurationEarly)) return
    if (!_uiState.value.error.isNullOrBlank()) return

    val state = _uiState.value
    if (state.nextEpisode?.hasAired != true || nextEpisodeVideo == null) {
        if (state.postPlayMode != null) {
            _uiState.update { it.copy(postPlayMode = null) }
        }
        return
    }
    if (state.postPlayMode != null || state.postPlayDismissedForCurrentEpisode) return

    val effectiveDuration = effectiveDurationEarly
    val shouldShow = PlayerNextEpisodeRules.shouldShowNextEpisodeCard(
        positionMs = positionMs,
        durationMs = effectiveDuration,
        skipIntervals = skipIntervals,
        thresholdMode = nextEpisodeThresholdModeSetting,
        thresholdPercent = nextEpisodeThresholdPercentSetting,
        thresholdMinutesBeforeEnd = nextEpisodeThresholdMinutesBeforeEndSetting
    )

    if (!shouldShow) return

    if (_uiState.value.postPlayDismissedForCurrentEpisode) return

    val shouldEnterStillWatching = shouldEnterStillWatchingPrompt(
        stillWatchingEnabled = stillWatchingEnabledSetting,
        autoPlayNextEpisodeEnabled = streamAutoPlayNextEpisodeEnabledSetting,
        nextEpisodeHasAired = state.nextEpisode.hasAired,
        consecutiveAutoPlayCount = consecutiveAutoPlayCount,
        threshold = stillWatchingEpisodeThresholdSetting,
    )

    if (shouldEnterStillWatching) {
        enterStillWatchingPromptMode()
    } else {
        _uiState.update {
            it.copy(postPlayMode = PostPlayMode.AutoPlay(nextEpisode = state.nextEpisode))
        }
        if (state.nextEpisode.hasAired && streamAutoPlayNextEpisodeEnabledSetting) {
            playNextEpisode()
        }
    }
}

internal fun PlayerRuntimeController.showStreamSourceIndicator(stream: Stream) {
    val chosenSource = (stream.name?.takeIf { it.isNotBlank() } ?: stream.addonName).trim()
    if (chosenSource.isBlank()) return

    hideStreamSourceIndicatorJob?.cancel()
    _uiState.update {
        it.copy(
            showStreamSourceIndicator = true,
            streamSourceIndicatorText = "Source: $chosenSource"
        )
    }
    hideStreamSourceIndicatorJob = scope.launch {
        delay(2200)
        _uiState.update { it.copy(showStreamSourceIndicator = false) }
    }
}

internal fun PlayerRuntimeController.updateActiveSkipInterval(positionMs: Long) {
    if (skipIntervals.isEmpty()) {
        if (_uiState.value.activeSkipInterval != null) {
            _uiState.update { it.copy(activeSkipInterval = null, skipIntervalDismissed = false) }
        }
        return
    }

    // Don't evaluate skip intervals until player settings are loaded from DataStore.
    // Without this, autoSkipSegmentTypes is empty on first iterations, causing the
    // skip button to appear instead of auto-skipping.
    if (!playerSettingsInitialized) return

    val active = nextActiveSkipInterval(skipIntervals, positionMs)
    val currentActive = _uiState.value.activeSkipInterval

    if (active != null) {
        if (currentActive == null || active.type != currentActive.type || active.startTime != currentActive.startTime) {
            lastActiveSkipType = active.type
            _uiState.update { it.copy(activeSkipInterval = active, skipIntervalDismissed = false) }
        }
        val segmentType = AutoSkipSegmentType.fromSkipIntervalType(active.type)
        val activeKey = active.autoSkipKey()
        if (
            segmentType != null &&
            segmentType in autoSkipSegmentTypes &&
            activeKey !in autoSkippedIntervalKeys
        ) {
            autoSkippedIntervalKeys.add(activeKey)
            skipInterval(active)
        }
    } else if (currentActive != null) {
        _uiState.update { it.copy(activeSkipInterval = null, skipIntervalDismissed = false) }
    }
}

private fun SkipInterval.autoSkipKey(): String =
    "$provider:$type:$startTime:$endTime"

internal fun PlayerRuntimeController.tryShowParentalGuide() {
    val state = _uiState.value
    if (!state.parentalGuideHasShown && state.parentalWarnings.isNotEmpty() && !playbackStartedForParentalGuide) {
        playbackStartedForParentalGuide = true
        _uiState.update { it.copy(showParentalGuide = true, parentalGuideHasShown = true) }
    }
}

internal fun PlayerRuntimeController.fetchParentalGuide(id: String?, type: String?, season: Int?, episode: Int?) {
    if (!parentalGuideEnabled) return
    if (id.isNullOrBlank()) return

    val imdbId = id.split(":").firstOrNull()?.takeIf { it.startsWith("tt") } ?: return

    scope.launch {
        val guide = parentalGuideRepository.getParentalGuide(imdbId) ?: return@launch

        val labels = mapOf(
            "nudity" to context.getString(R.string.parental_nudity),
            "violence" to context.getString(R.string.parental_violence),
            "profanity" to context.getString(R.string.parental_profanity),
            "alcohol" to context.getString(R.string.parental_alcohol),
            "frightening" to context.getString(R.string.parental_frightening)
        )
        val severityOrder = mapOf(
            "severe" to 0, "moderate" to 1, "mild" to 2
        )

        val entries = listOfNotNull(
            guide.nudity?.let { "nudity" to it },
            guide.violence?.let { "violence" to it },
            guide.profanity?.let { "profanity" to it },
            guide.alcohol?.let { "alcohol" to it },
            guide.frightening?.let { "frightening" to it }
        )

        val warnings = entries
            .sortedBy { severityOrder[it.second.lowercase()] ?: 3 }
            .map {
                val localizedSeverity = when (it.second.lowercase()) {
                    "severe" -> context.getString(R.string.parental_severity_severe)
                    "moderate" -> context.getString(R.string.parental_severity_moderate)
                    "mild" -> context.getString(R.string.parental_severity_mild)
                    else -> it.second
                }
                ParentalWarning(label = labels[it.first] ?: it.first, severity = localizedSeverity)
            }
            .take(5)

        _uiState.update {
            it.copy(
                parentalWarnings = warnings,
                showParentalGuide = false,
                parentalGuideHasShown = false
            )
        }

        if (_uiState.value.isPlaying) {
            tryShowParentalGuide()
        }
    }
}
