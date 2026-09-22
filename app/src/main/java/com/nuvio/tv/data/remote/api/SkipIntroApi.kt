package com.nuvio.tv.data.remote.api

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Query

// --- IntroDB API ---

interface IntroDbApi {
    @GET("segments")
    suspend fun getSegments(
        @Query("imdb_id") imdbId: String,
        @Query("season") season: Int? = null,
        @Query("episode") episode: Int? = null,
        @Query("is_movie") isMovie: Boolean? = null
    ): Response<IntroDbSegmentsResponse>
}

@JsonClass(generateAdapter = true)
data class IntroDbSegmentsResponse(
    @Json(name = "imdb_id") val imdbId: String? = null,
    @Json(name = "media_type") val mediaType: String? = null,
    @Json(name = "is_movie") val isMovie: Boolean? = null,
    @Json(name = "season") val season: Int? = null,
    @Json(name = "episode") val episode: Int? = null,
    @Json(name = "intro") val intro: IntroDbSegment? = null,
    @Json(name = "recap") val recap: IntroDbSegment? = null,
    @Json(name = "outro") val outro: IntroDbSegment? = null,
    @Json(name = "post_credits") val postCredits: IntroDbSegment? = null
)

@JsonClass(generateAdapter = true)
data class IntroDbSegment(
    @Json(name = "start_sec") val startSec: Double? = null,
    @Json(name = "end_sec") val endSec: Double? = null,
    @Json(name = "start_ms") val startMs: Long? = null,
    @Json(name = "end_ms") val endMs: Long? = null,
    @Json(name = "confidence") val confidence: Double? = null,
    @Json(name = "submission_count") val submissionCount: Int? = null,
    @Json(name = "updated_at") val updatedAt: String? = null
)
