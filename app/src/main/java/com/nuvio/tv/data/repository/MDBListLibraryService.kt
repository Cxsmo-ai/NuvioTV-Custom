package com.nuvio.tv.data.repository

import android.content.Context
import com.nuvio.tv.R
import com.nuvio.tv.core.tracking.TrackingLibraryProvider
import com.nuvio.tv.core.tracking.TrackingProviderId
import com.nuvio.tv.core.tracking.TrackingRefreshIntent
import com.nuvio.tv.core.tmdb.TmdbMetadataService
import com.nuvio.tv.data.local.MDBListSettingsDataStore
import com.nuvio.tv.data.remote.api.MDBListApi
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.LibraryEntry
import com.nuvio.tv.domain.model.LibraryEntryInput
import com.nuvio.tv.domain.model.LibraryListTab
import com.nuvio.tv.domain.model.ListMembershipChanges
import com.nuvio.tv.domain.model.ListMembershipSnapshot
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Reads and writes the MDBList watchlist so it can act as a library source,
 * peer to Trakt and Simkl. Phase 1 exposes the watchlist only (a single
 * membership destination); static MDBList lists are a later slice.
 *
 * Membership is matched on the normalised content id ([normalizeContentId], via
 * [MDBListWatchlistMapping]), so an item keys identically whether it came from
 * MDBList, Trakt, or the local library. Removal is verified by re-reading the
 * watchlist rather than trusting the write's `removed` count.
 */
@Singleton
class MDBListWatchlistDataSource @Inject constructor(
    private val api: MDBListApi,
    private val settingsDataStore: MDBListSettingsDataStore,
    private val tmdbMetadataService: TmdbMetadataService
) {
    /** The active profile's key, or null when tracking is not ready. */
    suspend fun apiKeyOrNull(): String? {
        val settings = settingsDataStore.settings.first()
        return settings.apiKey.takeIf { it.isNotBlank() && settings.trackingReady }
    }

    /** The watchlist change stamp used to gate automatic refreshes. */
    suspend fun lastWatchlistedAt(apiKey: String): String? {
        val response = api.getLastActivities(apiKey)
        return response.body()?.watchlistedAt?.takeIf { response.isSuccessful }
    }

    /** The whole watchlist, paged by offset until the server reports no more. */
    /** Reads the whole watchlist. Carries no artwork - posters are filled in
     *  separately via [hydratePostersProgressively]. */
    suspend fun fetchAll(apiKey: String): List<LibraryEntry> {
        val entries = mutableListOf<LibraryEntry>()
        var offset = 0
        val limit = 1000
        var guard = 0
        while (guard++ < 50) {
            val response = api.getWatchlistItems(apiKey = apiKey, limit = limit, offset = offset)
            if (!response.isSuccessful) break
            val page = response.body() ?: break
            entries += page.toLibraryEntries()
            val pageCount = (page.movies?.size ?: 0) + (page.shows?.size ?: 0)
            if (page.pagination?.hasMore != true || pageCount == 0) break
            offset += limit
        }
        return entries.distinctBy { it.id }
    }

    /** Ids whose TMDB lookup completed this session (even when TMDB holds no
     *  art for them), so confirmed-artless titles don't re-trigger hydration
     *  passes. Misses and timeouts are deliberately NOT recorded - they stay
     *  retryable on the next pass. */
    private val completedLookups = ConcurrentHashMap.newKeySet<String>()

    /** True when a poster is still owed and a completed lookup could supply it. */
    fun needsHydration(entry: LibraryEntry): Boolean =
        entry.poster.isNullOrBlank() && entry.tmdbId != null && entry.id !in completedLookups

    /**
     * Fills posters from TMDB in bounded chunks, calling [onProgress] with the
     * growing list after each chunk so the library fills in progressively.
     * Never discards partial work: a slow/failed lookup (per-item timeout) just
     * leaves that entry as-is. TMDB results are cached, so repeat calls are cheap.
     *
     * @return true when the pass finished with nothing left needing hydration
     * (every remaining posterless entry either has no tmdb id or is confirmed
     * artless); false when interrupted lookups should be retried later.
     */
    suspend fun hydratePostersProgressively(
        entries: List<LibraryEntry>,
        onProgress: (List<LibraryEntry>) -> Unit
    ): Boolean {
        if (entries.isEmpty()) return true
        val result = entries.toMutableList()
        val indexById = entries.withIndex().associate { (index, entry) -> entry.id to index }
        for (chunk in entries.chunked(HYDRATION_CHUNK)) {
            val hydrated = coroutineScope {
                chunk.map { entry ->
                    async {
                        withTimeoutOrNull(PER_ITEM_HYDRATION_TIMEOUT_MS) { hydratePoster(entry) } ?: entry
                    }
                }.awaitAll()
            }
            hydrated.forEach { entry -> indexById[entry.id]?.let { result[it] = entry } }
            onProgress(result.toList())
        }
        return result.none { needsHydration(it) }
    }

    private suspend fun hydratePoster(entry: LibraryEntry): LibraryEntry {
        if (!entry.poster.isNullOrBlank()) return entry
        val tmdbId = entry.tmdbId ?: return entry
        val art = runCatching {
            tmdbMetadataService.fetchPosterArt(tmdbId.toString(), ContentType.fromString(entry.type))
        }.getOrNull() ?: return entry
        // The lookup answered (even if it holds no art) - no retry needed.
        completedLookups.add(entry.id)
        return entry.copy(
            poster = art.poster ?: entry.poster,
            background = art.backdrop ?: entry.background,
            description = entry.description ?: art.description,
            genres = if (entry.genres.isEmpty()) art.genres else entry.genres
        )
    }

    private companion object {
        const val HYDRATION_CHUNK = 8
        const val PER_ITEM_HYDRATION_TIMEOUT_MS = 6_000L
        const val WRITE_BATCH_SIZE = 100
    }

    suspend fun add(apiKey: String, item: LibraryEntryInput) {
        val plan = buildWatchlistWritePlan(listOf(item))
        if (plan.isEmpty) return
        api.addToWatchlist(apiKey, plan.body)
    }

    suspend fun remove(apiKey: String, item: LibraryEntryInput) {
        val plan = buildWatchlistWritePlan(listOf(item))
        if (plan.isEmpty) return
        api.removeFromWatchlist(apiKey, plan.body)
    }

    /** Bulk add for transfer: one request for the whole batch. Returns the
     *  number of unique, resolvable items sent (success is confirmed by the
     *  caller re-reading, not by the response count). */
    suspend fun addAll(apiKey: String, items: List<LibraryEntryInput>): Int {
        var written = 0
        for (batch in items.chunked(WRITE_BATCH_SIZE)) {
            val plan = buildWatchlistWritePlan(batch)
            if (plan.isEmpty) continue
            val response = api.addToWatchlist(apiKey, plan.body)
            if (response.isSuccessful) written += plan.moviesCount + plan.showsCount
        }
        return written
    }

    /** Bulk remove for transfer (move). One request for the whole batch. */
    suspend fun removeAll(apiKey: String, items: List<LibraryEntryInput>): Int {
        var removed = 0
        for (batch in items.chunked(WRITE_BATCH_SIZE)) {
            val plan = buildWatchlistWritePlan(batch)
            if (plan.isEmpty) continue
            val response = api.removeFromWatchlist(apiKey, plan.body)
            if (response.isSuccessful) removed += plan.moviesCount + plan.showsCount
        }
        return removed
    }
}

@Singleton
class MDBListLibraryService @Inject constructor(
    private val dataSource: MDBListWatchlistDataSource,
    private val settingsDataStore: MDBListSettingsDataStore,
    @ApplicationContext private val appContext: Context
) : TrackingLibraryProvider {

    private data class State(
        val entries: List<LibraryEntry> = emptyList(),
        val watchlistIds: Set<String> = emptySet(),
        val isLoading: Boolean = false,
        val loaded: Boolean = false,
        val lastWatchlistedAt: String? = null,
        val hydrationComplete: Boolean = false
    )

    private val state = MutableStateFlow(State())
    private val refreshMutex = Mutex()

    /** Owns poster hydration so it survives the UI collector that triggered the
     *  refresh. Hydration used to run inline in [refresh] (inside the items
     *  flow's onStart, i.e. in the collector's coroutine): navigating away
     *  mid-fill cancelled it between chunks, and the AUTOMATIC stamp gate then
     *  froze the half-hydrated snapshot until a user-initiated write. */
    private val hydrationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** The single in-flight hydration pass. All reads/writes happen under
     *  [refreshMutex]. */
    private var hydrationJob: Job? = null

    private val watchlistTab = LibraryListTab(
        key = MDBLIST_WATCHLIST_KEY,
        title = appContext.getString(R.string.library_watchlist),
        type = LibraryListTab.Type.WATCHLIST,
        trackingProviderId = TrackingProviderId.MDBLIST.storageId,
        isMembershipDestination = true
    )

    override val providerId = TrackingProviderId.MDBLIST

    override val isAuthenticated = settingsDataStore.settings
        .map { settings -> settings.trackingReady }
        .distinctUntilChanged()

    override val isRefreshing = state.map { it.isLoading }.distinctUntilChanged()

    override val items = state.map { it.entries }
        .onStart { refresh(TrackingRefreshIntent.AUTOMATIC) }
        .distinctUntilChanged()

    override val tabs = isAuthenticated.map { authenticated ->
        if (authenticated) listOf(watchlistTab) else emptyList()
    }.distinctUntilChanged()

    override fun recognizesListKey(key: String): Boolean = key == MDBLIST_WATCHLIST_KEY

    override fun observeMembership(itemId: String, itemType: String) =
        state.map { snapshot ->
            if (contentKey(itemId) in snapshot.watchlistIds) setOf(MDBLIST_WATCHLIST_KEY) else emptySet()
        }.onStart { refresh(TrackingRefreshIntent.AUTOMATIC) }.distinctUntilChanged()

    override fun toggledDefaultMembership(
        currentMembership: Map<String, Boolean>
    ): Map<String, Boolean> =
        currentMembership + (MDBLIST_WATCHLIST_KEY to (currentMembership[MDBLIST_WATCHLIST_KEY] != true))

    override suspend fun getMembershipSnapshot(item: LibraryEntryInput): ListMembershipSnapshot {
        ensureLoaded()
        val member = contentKey(item.itemId) in state.value.watchlistIds
        return ListMembershipSnapshot(mapOf(MDBLIST_WATCHLIST_KEY to member))
    }

    override suspend fun applyMembershipChanges(
        item: LibraryEntryInput,
        changes: ListMembershipChanges,
        destructiveRemovalConfirmed: Boolean
    ) {
        val apiKey = dataSource.apiKeyOrNull()
            ?: throw IllegalStateException("MDBList API key is required")
        val desired = changes.desiredMembership[MDBLIST_WATCHLIST_KEY] ?: return
        ensureLoaded()
        val current = contentKey(item.itemId) in state.value.watchlistIds
        if (desired == current) return
        if (desired) dataSource.add(apiKey, item) else dataSource.remove(apiKey, item)
        // Re-read the authoritative watchlist: the write's count is not trusted.
        refresh(TrackingRefreshIntent.USER_INITIATED)
    }

    override suspend fun refresh(intent: TrackingRefreshIntent) {
        val apiKey = dataSource.apiKeyOrNull()
        if (apiKey == null) {
            refreshMutex.withLock {
                hydrationJob?.cancelAndJoin()
                state.value = State()
            }
            return
        }
        refreshMutex.withLock {
            val current = state.value
            if (intent == TrackingRefreshIntent.AUTOMATIC && current.loaded) {
                val stamp = runCatching { dataSource.lastWatchlistedAt(apiKey) }.getOrNull()
                if (stamp != null && stamp == current.lastWatchlistedAt) {
                    // Watchlist unchanged - but resume hydration if an earlier
                    // pass was interrupted (cancelled mid-way, or lookups timed
                    // out/failed). The entries themselves are still current, so
                    // no re-fetch is needed, and finished lookups are cached.
                    if (!current.hydrationComplete && hydrationJob?.isActive != true) {
                        startHydration(current.entries)
                    }
                    return
                }
            }
            state.value = current.copy(isLoading = true)
            val fetched = runCatching { dataSource.fetchAll(apiKey) }.getOrNull()
            val stamp = runCatching { dataSource.lastWatchlistedAt(apiKey) }.getOrNull()
            if (fetched == null) {
                state.value = current.copy(isLoading = false)
                return@withLock
            }
            // Stop any pass still filling the OLD snapshot before the new one
            // is published, so a straggling progress update can't overwrite it.
            hydrationJob?.cancelAndJoin()
            // Publish the watchlist immediately (posterless), then fill posters
            // in the background - detached from the caller, so the lifetime of
            // whichever screen triggered the refresh cannot cut the fill short.
            state.value = State(
                entries = fetched,
                watchlistIds = fetched.map { it.id }.toSet(),
                isLoading = false,
                loaded = true,
                lastWatchlistedAt = stamp ?: current.lastWatchlistedAt,
                hydrationComplete = false
            )
            startHydration(fetched)
        }
    }

    /** Cancels any running hydration pass (its partial work is already
     *  published per chunk and its lookups cached) and launches a fresh pass
     *  in [hydrationScope]. Must be called under [refreshMutex]. */
    private suspend fun startHydration(entries: List<LibraryEntry>) {
        hydrationJob?.cancelAndJoin()
        hydrationJob = hydrationScope.launch {
            val complete = dataSource.hydratePostersProgressively(entries) { progress ->
                state.value = state.value.copy(entries = progress)
            }
            if (complete) state.value = state.value.copy(hydrationComplete = true)
        }
    }

    private suspend fun ensureLoaded() {
        if (!state.value.loaded) refresh(TrackingRefreshIntent.AUTOMATIC)
    }

    /** Normalises an item id the same way library entries are keyed. */
    private fun contentKey(itemId: String): String =
        normalizeContentId(toTraktIds(parseContentIds(itemId))).ifBlank { itemId.trim() }
}
