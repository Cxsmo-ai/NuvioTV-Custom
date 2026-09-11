package com.nuvio.tv.ui.screens.player

import tv.seekr.previews.core.SeekrContent

/** Maps the IDs already carried by the player into Seekr's typed content model. */
internal fun seekrContentFor(
    contentId: String?,
    contentType: String?,
    season: Int?,
    episode: Int?
): SeekrContent? {
    val baseId = contentId
        ?.removePrefix("tmdb:")
        ?.removePrefix("movie:")
        ?.removePrefix("series:")
        ?.substringBefore(':')
        ?.substringBefore('/')
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?: return null

    val imdbId = baseId.takeIf { it.startsWith("tt") }
    val tmdbId = baseId.toIntOrNull()
    if (imdbId == null && tmdbId == null) return null

    val isSeries = contentType?.lowercase() in setOf("series", "tv", "show")
    return if (isSeries && season != null && episode != null) {
        SeekrContent.Episode(
            showTmdbId = tmdbId,
            showImdbId = imdbId,
            season = season,
            episode = episode
        )
    } else {
        SeekrContent.Movie(tmdbId = tmdbId, imdbId = imdbId)
    }
}
