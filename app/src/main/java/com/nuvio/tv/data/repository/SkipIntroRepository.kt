package com.nuvio.tv.data.repository

import android.util.Log
import com.nuvio.tv.BuildConfig
import com.nuvio.tv.data.local.AutoSkipSegmentType
import com.nuvio.tv.data.local.PlayerSettingsDataStore
import com.nuvio.tv.data.local.SkipSource
import com.nuvio.tv.data.local.SkipSourcePolicy
import com.nuvio.tv.data.remote.api.IntroDbApi
import com.nuvio.tv.data.remote.api.IntroDbSegment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

data class SkipInterval(
    val startTime: Double,
    val endTime: Double,
    val type: String,
    val provider: String,
    val action: String = "skip",
    val confidence: Double = 1.0,
    val severity: String? = null
)

private data class CachedSkipIntervals(val storedAtMs: Long, val intervals: List<SkipInterval>)

/**
 * Native metadata-only skip pipeline. It runs on the TV, returns no media
 * URLs, and isolates provider failures so playback is never blocked.
 */
@Singleton
class SkipIntroRepository @Inject constructor(
    private val introDbApi: IntroDbApi,
    private val playerSettingsDataStore: PlayerSettingsDataStore,
    private val httpClient: OkHttpClient
) {
    private val cache = ConcurrentHashMap<String, CachedSkipIntervals>()
    private val introDbConfigured = BuildConfig.INTRODB_API_URL.isNotEmpty()

    suspend fun getSkipIntervals(
        imdbId: String?,
        season: Int,
        episode: Int,
        title: String? = null,
        mediaType: String? = null,
        durationMs: Long? = null
    ): List<SkipInterval> {
        val normalizedId = imdbId?.trim()?.takeIf { it.matches(Regex("tt\\d+")) } ?: return emptyList()
        val settings = playerSettingsDataStore.playerSettings.first()
        if (!settings.skipIntroEnabled) return emptyList()

        val sources = selectedSources(settings.skipSourcePolicy, settings.skipEnabledSources)
        if (sources.isEmpty()) return emptyList()
        val categoryKey = settings.skipEnabledSegmentTypes.map { it.storedValue }.sorted().joinToString(",")
        val key = listOf(
            normalizedId, season, episode, mediaType.orEmpty(),
            settings.skipSourcePolicy.name, sources.joinToString { it.storedValue }, categoryKey
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
                                SkipSource.INTRO_DB -> if (isSeries && introDbConfigured) {
                                    fetchFromIntroDb(normalizedId, season, episode)
                                } else emptyList()
                                SkipSource.MOVIE_HAVEN_DB -> if (!isSeries) {
                                    fetchFromMovieHavenDb(normalizedId)
                                } else emptyList()
                                SkipSource.VIDEO_SKIP -> fetchFromVideoSkip(
                                    normalizedId, title, isSeries, season, episode
                                )
                            }
                        }.getOrElse { error ->
                            Log.d(TAG, "${source.storedValue}: ${error.message ?: "unavailable"}")
                            emptyList()
                        }
                    } ?: emptyList()
                }
            }.awaitAll().flatten()
        }
        val filtered = fetched
            .filter {
                it.endTime > it.startTime &&
                    AutoSkipSegmentType.fromSkipIntervalType(it.type) in settings.skipEnabledSegmentTypes
            }
            .sortedWith(compareBy<SkipInterval> { it.startTime }.thenBy { it.endTime })
            .distinctBy { "${it.type}:${it.startTime}:${it.endTime}:${it.action}" }
            .take(MAX_INTERVALS)
        cache[key] = CachedSkipIntervals(now, filtered)
        trimCacheIfNeeded()
        return filtered
    }

    private fun selectedSources(policy: SkipSourcePolicy, enabled: Set<SkipSource>): List<SkipSource> {
        val forced = when (policy) {
            SkipSourcePolicy.AUTO -> null
            SkipSourcePolicy.INTRO_DB_ONLY -> SkipSource.INTRO_DB
            SkipSourcePolicy.MOVIE_HAVEN_DB_ONLY -> SkipSource.MOVIE_HAVEN_DB
            SkipSourcePolicy.VIDEO_SKIP_ONLY -> SkipSource.VIDEO_SKIP
        }
        return if (forced != null) listOf(forced) else listOf(
            SkipSource.INTRO_DB, SkipSource.MOVIE_HAVEN_DB, SkipSource.VIDEO_SKIP
        ).filter { it in enabled }
    }

    private suspend fun fetchFromIntroDb(imdbId: String, season: Int, episode: Int): List<SkipInterval> = try {
        val response = introDbApi.getSegments(imdbId, season, episode)
        if (!response.isSuccessful) emptyList() else response.body()?.let { data ->
            listOfNotNull(
                data.intro.toSkipIntervalOrNull("intro", data.intro?.confidence),
                data.recap.toSkipIntervalOrNull("recap", data.recap?.confidence),
                data.outro.toSkipIntervalOrNull("outro", data.outro?.confidence)
            )
        }.orEmpty()
    } catch (_: Exception) {
        Log.d(TAG, "IntroDB unavailable for $imdbId S${season}E$episode")
        emptyList()
    }

    private fun IntroDbSegment?.toSkipIntervalOrNull(type: String, confidence: Double?): SkipInterval? {
        if (this == null) return null
        val start = startSec ?: startMs?.let { it / 1000.0 }
        val end = endSec ?: endMs?.let { it / 1000.0 }
        if (start == null || end == null || end <= start) return null
        return SkipInterval(start, end, type, "introdb", confidence = confidence ?: 1.0)
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

    private suspend fun getText(url: String, maxBytes: Long = MAX_RESPONSE_BYTES): String? =
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url(url)
                .header("Accept", "application/json,text/plain,*/*")
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
    }
}

internal object SkipMetadataParser {
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
