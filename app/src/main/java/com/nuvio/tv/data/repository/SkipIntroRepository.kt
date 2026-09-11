package com.nuvio.tv.data.repository

import android.util.Log
import com.nuvio.tv.BuildConfig
import com.nuvio.tv.data.local.AutoSkipSegmentType
import com.nuvio.tv.data.local.PlayerSettingsDataStore
import com.nuvio.tv.data.local.SkipProviderCredentialsStore
import com.nuvio.tv.data.local.SkipSource
import com.nuvio.tv.data.local.SkipSourcePolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.text.Normalizer
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

data class SkipEvidence(
    val provider: String,
    val confidence: Double,
    val startTime: Double,
    val endTime: Double,
    val action: String,
    val severity: String? = null,
    val submissions: Int? = null
)

data class SkipInterval(
    val startTime: Double,
    val endTime: Double,
    val type: String,
    val provider: String,
    val action: String = "skip",
    val confidence: Double = 1.0,
    val severity: String? = null,
    val evidence: List<SkipEvidence> = emptyList()
)

private data class CachedSkipIntervals(val storedAtMs: Long, val intervals: List<SkipInterval>)

/**
 * Native metadata-only skip pipeline. It runs on the TV, returns no media
 * URLs, and isolates provider failures so playback is never blocked.
 */
@Singleton
class SkipIntroRepository @Inject constructor(
    private val playerSettingsDataStore: PlayerSettingsDataStore,
    private val credentialsStore: SkipProviderCredentialsStore,
    private val httpClient: OkHttpClient
) {
    private val cache = ConcurrentHashMap<String, CachedSkipIntervals>()
    // The official public endpoint is the safe default; a build-time URL can
    // still override it for mirrors or development environments.
    private val introDbConfigured = true

    suspend fun getSkipIntervals(
        imdbId: String?,
        season: Int,
        episode: Int,
        title: String? = null,
        mediaType: String? = null,
        durationMs: Long? = null,
        releaseYear: String? = null,
        tmdbId: Int? = null,
        tvdbId: Int? = null,
        anilistId: Int? = null
    ): List<SkipInterval> {
        val normalizedId = imdbId?.trim()?.takeIf { it.matches(Regex("tt\\d+")) }
        if (normalizedId == null && tmdbId == null && tvdbId == null && anilistId == null) return emptyList()
        val settings = playerSettingsDataStore.playerSettings.first()
        val credentials = credentialsStore.credentials.first()
        if (!settings.skipIntroEnabled) return emptyList()

        val sources = selectedSources(settings.skipSourcePolicy, settings.skipEnabledSources)
        if (sources.isEmpty()) return emptyList()
        val categoryKey = settings.skipEnabledSegmentTypes.map { it.storedValue }.sorted().joinToString(",")
        val key = listOf(
            normalizedId.orEmpty(), season, episode, mediaType.orEmpty(),
            title.orEmpty(), releaseYear.orEmpty(),
            tmdbId ?: 0, tvdbId ?: 0, anilistId ?: 0,
            durationMs?.takeIf { it > 0L } ?: 0L,
            settings.skipSourcePolicy.name, sources.joinToString { it.storedValue }, categoryKey,
            credentials.publicMetaDbApiKey.isNotBlank(),
            credentials.introDbAppApiKey.isNotBlank(),
            credentials.theIntroDbApiKey.isNotBlank()
        ).joinToString(":")
        val now = System.currentTimeMillis()
        cache[key]?.takeIf { now - it.storedAtMs < CACHE_TTL_MS }?.let { return it.intervals }

        val isSeries = mediaType?.lowercase(Locale.US) in setOf("series", "tv", "show") ||
            (season > 0 && episode > 0)
        val fetched = coroutineScope {
            sources.map { source ->
                async {
                    withTimeoutOrNull(PROVIDER_TIMEOUT_MS) {
                        runCatching {
                            when (source) {
                                SkipSource.SKIP_ME -> fetchFromSkipMe(
                                    normalizedId, season, episode, isSeries, durationMs,
                                    tmdbId, tvdbId, anilistId
                                )
                                SkipSource.INTRO_DB -> if (normalizedId != null && isSeries && introDbConfigured) {
                                    fetchFromIntroDb(normalizedId, season, episode, credentials.introDbAppApiKey)
                                } else emptyList()
                                SkipSource.THE_INTRO_DB -> if (normalizedId != null) {
                                    fetchFromTheIntroDb(
                                        normalizedId, season, episode, isSeries, durationMs, credentials.theIntroDbApiKey
                                    )
                                } else emptyList()
                                SkipSource.PUBLIC_META_DB -> if (normalizedId != null) {
                                    fetchFromPublicMetaDb(
                                        normalizedId, season, episode, isSeries, credentials.publicMetaDbApiKey
                                    )
                                } else emptyList()
                                SkipSource.MOVIE_HAVEN_DB -> if (!isSeries && normalizedId != null) {
                                    fetchFromMovieHavenDb(normalizedId)
                                } else emptyList()
                                SkipSource.VIDEO_SKIP -> if (isSeries || normalizedId != null) {
                                    fetchFromVideoSkip(normalizedId.orEmpty(), title, isSeries, season, episode)
                                } else emptyList()
                                SkipSource.NOT_SCARE -> if (!isSeries && !title.isNullOrBlank() && !releaseYear.isNullOrBlank()) {
                                    fetchFromNotScare(title, releaseYear)
                                } else emptyList()
                            }
                        }.getOrElse { error ->
                            Log.d(TAG, "${source.storedValue}: ${error.message ?: "unavailable"}")
                            emptyList()
                        }
                    } ?: emptyList()
                }
            }.awaitAll().flatten()
        }
        val filtered = mergeSkipIntervals(
            fetched
            .filter {
                it.endTime > it.startTime &&
                    AutoSkipSegmentType.fromSkipIntervalType(it.type) in settings.skipEnabledSegmentTypes
            }
        ).take(MAX_INTERVALS)
        cache[key] = CachedSkipIntervals(now, filtered)
        trimCacheIfNeeded()
        return filtered
    }

    private fun selectedSources(policy: SkipSourcePolicy, enabled: Set<SkipSource>): List<SkipSource> {
        val forced = when (policy) {
            SkipSourcePolicy.AUTO -> null
            SkipSourcePolicy.SKIP_ME_ONLY -> SkipSource.SKIP_ME
            SkipSourcePolicy.INTRO_DB_ONLY -> SkipSource.INTRO_DB
            SkipSourcePolicy.THE_INTRO_DB_ONLY -> SkipSource.THE_INTRO_DB
            SkipSourcePolicy.PUBLIC_META_DB_ONLY -> SkipSource.PUBLIC_META_DB
            SkipSourcePolicy.MOVIE_HAVEN_DB_ONLY -> SkipSource.MOVIE_HAVEN_DB
            SkipSourcePolicy.VIDEO_SKIP_ONLY -> SkipSource.VIDEO_SKIP
            SkipSourcePolicy.NOT_SCARE_ONLY -> SkipSource.NOT_SCARE
        }
        return if (forced != null) listOf(forced) else listOf(
            SkipSource.SKIP_ME, SkipSource.INTRO_DB, SkipSource.THE_INTRO_DB, SkipSource.PUBLIC_META_DB,
            SkipSource.MOVIE_HAVEN_DB, SkipSource.VIDEO_SKIP, SkipSource.NOT_SCARE
        ).filter { it in enabled }
    }

    private suspend fun fetchFromSkipMe(
        imdbId: String?,
        season: Int,
        episode: Int,
        isSeries: Boolean,
        durationMs: Long?,
        tmdbId: Int?,
        tvdbId: Int?,
        anilistId: Int?
    ): List<SkipInterval> {
        val movieLookup = JSONObject().apply {
            imdbId?.let { put("imdb_id", it) }
            tmdbId?.let { put("tmdb_id", it) }
            tvdbId?.let { put("tvdb_id", it) }
            anilistId?.let { put("anilist_id", it) }
            if (isSeries) {
                put("season", season)
                put("episode", episode)
            }
            if (durationMs != null && durationMs > 0L) put("duration_ms", durationMs)
        }

        // SkipMe's playback lookup is POST /v1/movies for both movies and
        // episodes. It requires a positive runtime; do not call /v1/shows
        // here because that endpoint is the bulk series-sync path and is not
        // required to resolve the current episode. The player retries when a
        // runtime becomes available, so an early startup lookup is harmless.
        if (!durationMs.isNullOrPositive()) return emptyList()
        val moviePayload = postJson(
            "https://db.skipme.workers.dev/v1/movies",
            JSONArray().put(movieLookup).toString()
        ) ?: return emptyList()
        return SkipMetadataParser.parseSkipMe(moviePayload, "skipme", isSeries, season, episode)
    }

    private suspend fun fetchFromNotScare(title: String, releaseYear: String): List<SkipInterval> {
        val slug = slugifyNotScareTitle(title)
        if (slug.isBlank()) return emptyList()
        val headers = mapOf(
            "Accept" to "text/html,application/xhtml+xml",
            "User-Agent" to "NuvioTV/skip-metadata"
        )
        val url = "https://notscare.me/movies/jump-scares-in-$slug-${releaseYear.trim()}"
        return getText(url, headers = headers)
            ?.let { SkipMetadataParser.parseNotScarePage(it, "notscare") }
            .orEmpty()
    }

    private fun slugifyNotScareTitle(title: String): String =
        Normalizer.normalize(title.trim(), Normalizer.Form.NFKD)
            .replace(Regex("\\p{M}+"), "")
            .lowercase(Locale.US)
            .replace("&", " and ")
            .replace(Regex("[’']"), "")
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')

    private suspend fun fetchFromIntroDb(
        imdbId: String,
        season: Int,
        episode: Int,
        apiKey: String
    ): List<SkipInterval> {
        val base = BuildConfig.INTRODB_API_URL.trimEnd('/').ifBlank { "https://api.introdb.app" }
        val headers = buildMap {
            put("Accept", "application/json")
            if (apiKey.isNotBlank()) put("X-API-Key", apiKey)
        }
        return getText(
            "$$base/segments?imdb_id=$$imdbId&season=$$season&episode=$$episode",
            headers = headers
        )?.let { SkipMetadataParser.parseIntroDb(it, "introdb") }.orEmpty()
    }

    private suspend fun fetchFromTheIntroDb(
        imdbId: String,
        season: Int,
        episode: Int,
        isSeries: Boolean,
        durationMs: Long?,
        apiKey: String
    ): List<SkipInterval> {
        val query = buildString {
            append("imdb_id=").append(imdbId)
            if (isSeries) {
                append("&season=").append(season)
                append("&episode=").append(episode)
            }
            if (durationMs != null && durationMs > 0) append("&duration_ms=").append(durationMs)
        }
        val headers = buildMap {
            put("Accept", "application/json")
            put("User-Agent", "NuvioTV/skip-metadata")
            if (apiKey.isNotBlank()) put("Authorization", "Bearer $$apiKey")
        }
        return getText("https://api.theintrodb.org/v3/media?$$query", headers = headers)
            ?.let { SkipMetadataParser.parseTheIntroDb(it, "theintrodb", durationMs) }
            .orEmpty()
    }

    private suspend fun fetchFromPublicMetaDb(
        imdbId: String,
        season: Int,
        episode: Int,
        isSeries: Boolean,
        apiKey: String
    ): List<SkipInterval> {
        if (apiKey.isBlank()) return emptyList()
        val headers = mapOf("Accept" to "application/json", "Authorization" to "Bearer $$apiKey")
        val mediaType = if (isSeries) "tv" else "movie"
        val mapping = getText(
            "https://publicmetadb.com/api/external/mappings/lookup?id_type=imdb&id_value=$$imdbId&media_type=$$mediaType",
            headers = headers
        )?.let(SkipMetadataParser::parsePublicMetaDbMapping) ?: return emptyList()
        val url = buildString {
            append("https://publicmetadb.com/api/external/skips?tmdb_id=$$mapping&media_type=$$mediaType")
            if (isSeries) {
                append("&season=").append(season)
                append("&episode=").append(episode)
            }
        }
        return getText(url, headers = headers)
            ?.let { SkipMetadataParser.parsePublicMetaDb(it, "publicmetadb") }
            .orEmpty()
    }

    private suspend fun fetchFromMovieHavenDb(imdbId: String): List<SkipInterval> {
        val url = "https://raw.githubusercontent.com/arman-kh/MovieHavenDB/master/movies/$imdbId.json"
        return getText(url)?.let(SkipMetadataParser::parseMovieHaven).orEmpty()
    }

    private suspend fun fetchFromVideoSkip(
        imdbId: String,
        title: String?,
        isSeries: Boolean,
        season: Int,
        episode: Int
    ): List<SkipInterval> {
        if (title.isNullOrBlank()) return emptyList()
        val query = URLEncoder.encode(title.trim(), "UTF-8")
        val search = getText("https://videoskip.herokuapp.com/exchange/search/?q=$query") ?: return emptyList()
        val detailLinks = Regex("/exchange/videos/\\d+/?")
            .findAll(search)
            .map { "https://videoskip.herokuapp.com${it.value}" }
            .distinct()
            .take(MAX_VIDEO_SKIP_DETAILS)
            .toList()
        return detailLinks.flatMap { detailUrl ->
            val detail = getText(detailUrl) ?: return@flatMap emptyList()
            val normalized = detail.lowercase(Locale.US)
            val matches = if (isSeries) {
                normalized.contains("s${season}e$episode") ||
                    (normalized.contains("season $season") && normalized.contains("episode $episode"))
            } else normalized.contains(imdbId.lowercase(Locale.US))
            if (!matches) return@flatMap emptyList()
            Regex("/exchange/skip/\\d+/download/?")
                .findAll(detail)
                .map { "https://videoskip.herokuapp.com${it.value}" }
                .distinct()
                .take(MAX_VIDEO_SKIP_DOWNLOADS)
                .toList()
                .flatMap { downloadUrl ->
                    getText(downloadUrl, MAX_SKIP_FILE_BYTES)?.let {
                        SkipMetadataParser.parseVideoSkip(it, "videoskip")
                    }.orEmpty()
                }
        }
    }

    private suspend fun getText(
        url: String,
        maxBytes: Long = MAX_RESPONSE_BYTES,
        headers: Map<String, String> = emptyMap()
    ): String? =
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url(url)
                .header("Accept", "application/json,text/plain,*/*")
                .apply { headers.forEach { (name, value) -> header(name, value) } }
                .build()
            runCatching {
                httpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@use null
                    val body = response.body ?: return@use null
                    if (body.contentLength() > maxBytes) return@use null
                    body.source().peek().readUtf8(maxBytes)
                }
            }.getOrNull()
        }

    private suspend fun postJson(url: String, body: String): String? =
        withContext(Dispatchers.IO) {
            repeat(SKIP_ME_MAX_ATTEMPTS) { attempt ->
                var retryableFailure = false
                val responseBody = runCatching {
                    val request = Request.Builder()
                        .url(url)
                        .header("Accept", "application/json")
                        .header("Content-Type", "application/json; charset=utf-8")
                        .header("User-Agent", "SkipMe.db/0.0 NuvioTV/skip-metadata")
                        .post(body.toRequestBody(JSON_MEDIA_TYPE))
                        .build()
                    httpClient.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) {
                            retryableFailure = response.code in RETRYABLE_HTTP_CODES
                            Log.d(TAG, "SkipMe request failed: HTTP ${response.code} ${url.substringAfterLast('/')}")
                            return@use null
                        }
                        val responseBody = response.body ?: return@use null
                        if (responseBody.contentLength() > MAX_RESPONSE_BYTES) return@use null
                        responseBody.source().readUtf8(MAX_RESPONSE_BYTES)
                    }
                }.getOrNull()
                if (responseBody != null) return@withContext responseBody
                if (!retryableFailure || attempt == SKIP_ME_MAX_ATTEMPTS - 1) return@withContext null
                delay(SKIP_ME_RETRY_DELAY_MS)
            }
            null
        }

    private fun trimCacheIfNeeded() {
        if (cache.size <= MAX_CACHE_ENTRIES) return
        cache.entries.sortedBy { it.value.storedAtMs }
            .take(cache.size - MAX_CACHE_ENTRIES)
            .forEach { cache.remove(it.key) }
    }

    private companion object {
        const val TAG = "SkipIntro"
        const val CACHE_TTL_MS = 6L * 60L * 60L * 1000L
        const val PROVIDER_TIMEOUT_MS = 6_000L
        const val MAX_CACHE_ENTRIES = 256
        const val MAX_INTERVALS = 256
        const val MAX_VIDEO_SKIP_DETAILS = 8
        const val MAX_VIDEO_SKIP_DOWNLOADS = 6
        const val MAX_RESPONSE_BYTES = 2L * 1024L * 1024L
        const val MAX_SKIP_FILE_BYTES = 2L * 1024L * 1024L
        const val SKIP_ME_MAX_ATTEMPTS = 2
        const val SKIP_ME_RETRY_DELAY_MS = 250L
        val RETRYABLE_HTTP_CODES = setOf(408, 425, 429, 500, 502, 503, 504)
        val JSON_MEDIA_TYPE = "application/json".toMediaType()
    }
}

private fun Long?.isNullOrPositive(): Boolean = this != null && this > 0L

/**
 * Merges provider reports that describe the same category/action and overlap
 * within the Universal Skip API tolerance. Timing comes from a confidence-
 * weighted consensus; all unique provider reports remain in [evidence].
 * Different actions (for example skip vs mute) are intentionally kept apart.
 */
internal fun mergeSkipIntervals(
    intervals: List<SkipInterval>,
    overlapToleranceSeconds: Double = 2.0
): List<SkipInterval> {
    if (intervals.isEmpty()) return emptyList()
    val groups = mutableListOf<MutableList<SkipInterval>>()
    intervals
        .filter { it.startTime.isFinite() && it.endTime.isFinite() && it.endTime > it.startTime }
        .sortedWith(compareBy<SkipInterval> { it.startTime }.thenByDescending { it.confidence })
        .forEach { item ->
            val group = groups.firstOrNull { candidate ->
                candidate.any { other ->
                    other.type == item.type &&
                        other.action == item.action &&
                        other.startTime <= item.endTime + overlapToleranceSeconds &&
                        item.startTime <= other.endTime + overlapToleranceSeconds
                }
            }
            if (group == null) groups += mutableListOf(item) else group += item
        }

    return groups.mapNotNull { group ->
        val strongest = group.maxWithOrNull(compareBy<SkipInterval> { it.confidence }.thenBy { -it.startTime })
            ?: return@mapNotNull null
        val weight = group.sumOf { it.confidence.coerceIn(0.0, 1.0).coerceAtLeast(0.01) }
        val providers = group.map { it.provider }.distinct()
        val evidence = group
            .flatMap { item ->
                if (item.evidence.isNotEmpty()) item.evidence else listOf(
                    SkipEvidence(
                        provider = item.provider,
                        confidence = item.confidence.coerceIn(0.0, 1.0),
                        startTime = item.startTime,
                        endTime = item.endTime,
                        action = item.action,
                        severity = item.severity
                    )
                )
            }
            .distinctBy { "${it.provider}:${it.startTime}:${it.endTime}:${it.action}:${it.submissions}" }
            .sortedByDescending { it.confidence }
        val mergedConfidence = if (providers.size > 1) {
            (group.map { it.confidence.coerceIn(0.0, 1.0) }.average() +
                minOf(0.12, (providers.size - 1) * 0.04)).coerceAtMost(0.995)
        } else {
            strongest.confidence.coerceIn(0.0, 1.0)
        }
        val start = if (group.size == 1) strongest.startTime else {
            group.sumOf { it.startTime * it.confidence.coerceIn(0.0, 1.0).coerceAtLeast(0.01) } / weight
        }
        val end = if (group.size == 1) strongest.endTime else {
            group.sumOf { it.endTime * it.confidence.coerceIn(0.0, 1.0).coerceAtLeast(0.01) } / weight
        }
        strongest.copy(
            startTime = start,
            endTime = end.coerceAtLeast(start + 0.001),
            confidence = mergedConfidence,
            evidence = evidence
        )
    }.sortedWith(compareBy<SkipInterval> { it.startTime }.thenBy { it.endTime })
}

internal object SkipMetadataParser {
    fun parseIntroDb(raw: String, provider: String): List<SkipInterval> = runCatching {
        val root = JSONObject(raw)
        val items = mutableListOf<Pair<String, JSONObject>>()
        root.optJSONArray("segments")?.let { array ->
            for (index in 0 until array.length()) {
                array.optJSONObject(index)?.let { item ->
                    items += (item.optString("segment_type", item.optString("type", "custom")) to item)
                }
            }
        }
        listOf("intro", "recap", "outro", "credits").forEach { type ->
            root.optJSONObject(type)?.let { items += type to it }
        }
        items.mapNotNull { (rawType, item) ->
            val start = timeSeconds(item, "start_ms", "start_sec") ?: return@mapNotNull null
            val end = timeSeconds(item, "end_ms", "end_sec") ?: return@mapNotNull null
            if (end <= start) return@mapNotNull null
            SkipInterval(
                startTime = start,
                endTime = end,
                type = rawType,
                provider = provider,
                confidence = item.optDouble("confidence", 1.0).coerceIn(0.0, 1.0)
            )
        }
    }.getOrDefault(emptyList())

    fun parseTheIntroDb(
        raw: String,
        provider: String,
        durationMs: Long?
    ): List<SkipInterval> = runCatching {
        val root = JSONObject(raw)
        listOf("intro", "recap", "credits", "preview").flatMap { rawType ->
            val items = root.optJSONArray(rawType) ?: return@flatMap emptyList()
            buildList {
                for (index in 0 until items.length()) {
                    val item = items.optJSONObject(index) ?: continue
                    val start = timeSeconds(item, "start_ms", "start") ?: 0.0
                    val end = timeSeconds(item, "end_ms", "end")
                        ?: durationMs?.takeIf { it > 0 }?.div(1000.0)
                        ?: continue
                    if (end > start) add(
                        SkipInterval(start, end, rawType, provider, confidence = 0.86)
                    )
                }
            }
        }
    }.getOrDefault(emptyList())

    fun parsePublicMetaDbMapping(raw: String): String? = runCatching {
        val results = JSONObject(raw).optJSONArray("results") ?: return@runCatching null
        results.optJSONObject(0)?.optLong("tmdb_id", 0L)?.takeIf { it > 0 }?.toString()
    }.getOrNull()

    fun parsePublicMetaDb(raw: String, provider: String): List<SkipInterval> = runCatching {
        val items = JSONObject(raw).optJSONArray("items") ?: JSONArray()
        buildList {
            for (index in 0 until items.length()) {
                val item = items.optJSONObject(index) ?: continue
                val introStart = item.optLong("intro_start_ms", -1L)
                val introEnd = item.optLong("intro_end_ms", -1L)
                if (introStart >= 0 && introEnd > introStart) {
                    add(SkipInterval(introStart / 1000.0, introEnd / 1000.0, "intro", provider, confidence = 0.82))
                }
                val creditsStart = item.optLong("credits_start_ms", -1L)
                val creditsEnd = item.optLong("credits_end_ms", -1L)
                if (creditsStart >= 0 && creditsEnd > creditsStart) {
                    add(SkipInterval(creditsStart / 1000.0, creditsEnd / 1000.0, "credits", provider, confidence = 0.82))
                }
            }
        }
    }.getOrDefault(emptyList())

    /** Parses visible NotScare page text; scripts and markup are intentionally ignored. */
    fun parseNotScarePage(rawHtml: String, provider: String): List<SkipInterval> = runCatching {
        val plain = decodeHtmlEntities(visibleHtmlText(rawHtml))
        val matches = Regex(
            "(?:^|\\n)\\s*((?:\\d{1,2}:)?\\d{1,2}:\\d{2})\\s+(Major|Minor)\\b",
            RegexOption.IGNORE_CASE
        ).findAll(plain)
        matches.map { match ->
            val start = parseTimestamp(match.groupValues[1]) ?: return@map null
            val major = match.groupValues[2].equals("Major", ignoreCase = true)
            SkipInterval(
                startTime = start,
                endTime = start + if (major) 6.0 else 4.0,
                type = "jumpscare",
                provider = provider,
                action = "warn",
                confidence = 0.76,
                severity = if (major) "major" else "minor"
            )
        }.filterNotNull().toList()
    }.getOrDefault(emptyList())

    /** Parses SkipMe.db's one-item movie/show response without trusting payload shape. */
    fun parseSkipMe(
        raw: String,
        provider: String,
        isSeries: Boolean,
        season: Int,
        episode: Int
    ): List<SkipInterval> = runCatching {
        val root = JSONArray(raw)
        val item = root.optJSONObject(0) ?: return@runCatching emptyList()
        if (isSeries && item.has("segments")) {
            val segments = item.optJSONArray("segments") ?: return@runCatching emptyList()
            buildList {
                for (index in 0 until segments.length()) {
                    val segment = segments.optJSONObject(index) ?: continue
                    if (segment.optInt("season", -1) != season ||
                        segment.optInt("episode", -1) != episode
                    ) continue
                    val type = skipMeType(segment.optString("segment")) ?: continue
                    val startMs = segment.optLongValue("start_ms") ?: continue
                    val endMs = segment.optLongValue("end_ms") ?: continue
                    if (endMs <= startMs) continue
                    add(
                        skipMeInterval(
                            type = type,
                            startMs = startMs,
                            endMs = endMs,
                            submissions = segment.optIntValue("submissions"),
                            provider = provider
                        )
                    )
                }
            }
        } else {
            val typeMap = mapOf(
                "intro" to "intro",
                "recap" to "recap",
                "credits" to "credits",
                "preview" to "preview"
            )
            buildList {
                typeMap.forEach { (rawType, type) ->
                    val values = item.optJSONArray(rawType) ?: return@forEach
                    for (index in 0 until values.length()) {
                        val segment = values.optJSONObject(index) ?: continue
                        val startMs = segment.optLongValue("start_ms") ?: continue
                        val endMs = segment.optLongValue("end_ms") ?: continue
                        if (endMs <= startMs) continue
                        add(
                            skipMeInterval(
                                type = type,
                                startMs = startMs,
                                endMs = endMs,
                                submissions = segment.optIntValue("submissions"),
                                provider = provider
                            )
                        )
                    }
                }
            }
        }
    }.getOrDefault(emptyList())

    private fun skipMeInterval(
        type: String,
        startMs: Long,
        endMs: Long,
        submissions: Int?,
        provider: String
    ): SkipInterval {
        val confidence = (0.72 +
            (kotlin.math.ln((submissions ?: 1).coerceAtLeast(0) + 1.0) / kotlin.math.ln(2.0)) * 0.08)
            .coerceAtMost(0.99)
        return SkipInterval(
            startTime = startMs / 1000.0,
            endTime = endMs / 1000.0,
            type = type,
            provider = provider,
            confidence = confidence,
            evidence = listOf(
                SkipEvidence(
                    provider = provider,
                    confidence = confidence,
                    startTime = startMs / 1000.0,
                    endTime = endMs / 1000.0,
                    action = "skip",
                    submissions = submissions
                )
            )
        )
    }

    private fun skipMeType(raw: String): String? = when (raw.trim().lowercase(Locale.US)) {
        "intro" -> "intro"
        "recap" -> "recap"
        "credits" -> "credits"
        "preview" -> "preview"
        else -> null
    }

    private fun JSONObject.optLongValue(key: String): Long? =
        if (!has(key) || isNull(key)) null else optLong(key, Long.MIN_VALUE).takeUnless { it == Long.MIN_VALUE }

    private fun JSONObject.optIntValue(key: String): Int? =
        if (!has(key) || isNull(key)) null else optInt(key, Int.MIN_VALUE).takeUnless { it == Int.MIN_VALUE }

    private fun visibleHtmlText(html: String): String {
        val output = StringBuilder()
        val lower = html.lowercase(Locale.US)
        var cursor = 0
        while (cursor < html.length) {
            if (lower.startsWith("<script", cursor) || lower.startsWith("<style", cursor)) {
                val closing = if (lower.startsWith("<script", cursor)) "</script>" else "</style>"
                val end = lower.indexOf(closing, cursor + 7)
                if (end < 0) break
                cursor = end + closing.length
                output.append('\n')
                continue
            }
            if (html[cursor] == '<') {
                val end = html.indexOf('>', cursor + 1)
                if (end < 0) break
                cursor = end + 1
                output.append('\n')
                continue
            }
            val nextTag = html.indexOf('<', cursor).let { if (it < 0) html.length else it }
            output.append(html, cursor, nextTag)
            cursor = nextTag
        }
        return output.toString()
    }

    private fun decodeHtmlEntities(value: String): String = value
        .replace("&nbsp;", " ", ignoreCase = true)
        .replace("&amp;", "&", ignoreCase = true)
        .replace("&lt;", "<", ignoreCase = true)
        .replace("&gt;", ">", ignoreCase = true)
        .replace(Regex("&#(\\d+);")) { match ->
            match.groupValues[1].toIntOrNull()?.let { code -> code.toChar().toString() } ?: match.value
        }

    private fun timeSeconds(item: JSONObject, millisKey: String, secondsKey: String): Double? {
        val millis = item.opt(millisKey)
        if (millis != null && millis != JSONObject.NULL) {
            millis.toString().toDoubleOrNull()?.let { return it / 1000.0 }
        }
        val seconds = item.opt(secondsKey)
        if (seconds != null && seconds != JSONObject.NULL) {
            seconds.toString().toDoubleOrNull()?.let { return it }
            parseClock(seconds.toString())?.let { return it }
        }
        return null
    }

    private fun parseClock(value: String): Double? {
        val parts = value.trim().split(":")
        return runCatching {
            when (parts.size) {
                2 -> parts[0].toDouble() * 60 + parts[1].toDouble()
                3 -> parts[0].toDouble() * 3600 + parts[1].toDouble() * 60 + parts[2].toDouble()
                else -> null
            }
        }.getOrNull()
    }

    fun parseMovieHaven(raw: String): List<SkipInterval> = runCatching {
        val root = JSONObject(raw)
        // MovieHavenDB stores either a direct document or an IMDb-keyed
        // document: {"tt123": {"title": ..., "scenes": [...]}}.
        val document = root.optJSONArray("scenes")?.let { root }
            ?: root.optJSONArray("segments")?.let { root }
            ?: root.keys().asSequence()
                .mapNotNull { key -> root.optJSONObject(key) }
                .firstOrNull { it.has("scenes") || it.has("segments") }
            ?: root
        val scenes = document.optJSONArray("scenes") ?: document.optJSONArray("segments") ?: JSONArray()
        buildList {
            for (index in 0 until scenes.length()) {
                val scene = scenes.optJSONObject(index) ?: continue
                val start = scene.optDouble("start", Double.NaN)
                val end = scene.optDouble("end", Double.NaN)
                if (!start.isFinite() || !end.isFinite() || end <= start) continue
                val reason = scene.optString("reason", scene.optString("type", "custom"))
                val type = mapCategory(reason)
                val action = when {
                    scene.optBoolean("skip", false) -> "skip"
                    scene.optBoolean("mute", false) -> "mute"
                    scene.optBoolean("blur", false) -> "warn"
                    else -> "warn"
                }
                add(
                    SkipInterval(
                        start, end, type, "moviehavendb", action, 0.76,
                        scene.optString("severity").takeIf { it.isNotBlank() }
                    )
                )
            }
        }
    }.getOrDefault(emptyList())

    fun parseVideoSkip(raw: String, provider: String = "videoskip"): List<SkipInterval> {
        val lines = raw.lineSequence().map { it.trim() }.toList()
        val result = ArrayList<SkipInterval>()
        var index = 0
        while (index < lines.size - 1) {
            val match = Regex("^(.+?)\\s+-->\\s+(.+?)\\s*").matchEntire(lines[index])
            if (match == null) {
                index++
                continue
            }
            val start = parseTimestamp(match.groupValues[1])
            val end = parseTimestamp(match.groupValues[2])
            val label = lines.getOrNull(index + 1).orEmpty()
            if (start != null && end != null && end > start && label.isNotBlank()) {
                val action = when {
                    label.contains("audio", true) || label.contains("mute", true) ||
                        label.contains("dialog", true) -> "mute"
                    label.contains("visual", true) || label.contains("blur", true) -> "warn"
                    else -> "skip"
                }
                result += SkipInterval(start, end, mapCategory(label), provider, action, 0.72, severity(label))
                index += 2
            } else {
                index++
            }
        }
        return result
    }

    internal fun parseTimestamp(value: String): Double? {
        val parts = value.trim().split(":")
        return runCatching {
            when (parts.size) {
                1 -> parts[0].toDouble()
                2 -> parts[0].toDouble() * 60 + parts[1].toDouble()
                3 -> parts[0].toDouble() * 3600 + parts[1].toDouble() * 60 + parts[2].toDouble()
                else -> null
            }
        }.getOrNull()
    }

    private fun severity(label: String): String? =
        Regex("\\b([1-5])\\b").find(label)?.groupValues?.get(1)?.let {
            when (it.toInt()) {
                1 -> "low"
                2 -> "medium"
                3 -> "high"
                else -> "extreme"
            }
        }

    private fun mapCategory(raw: String): String {
        val value = raw.lowercase(Locale.US)
        return when {
            "jumpscare" in value || "fright" in value || "scare" in value -> "jumpscare"
            "nudity" in value -> "nudity"
            "sex" in value || "sexual" in value -> "sex"
            "gore" in value -> "gore"
            "violence" in value -> "violence"
            "profan" in value || "language" in value || "curse" in value -> "profanity"
            "intro" in value || "opening" in value -> "intro"
            "recap" in value -> "recap"
            "outro" in value || "ending" in value || "credit" in value -> "outro"
            "preview" in value || "filler" in value -> "preview"
            else -> "custom"
        }
    }
}
