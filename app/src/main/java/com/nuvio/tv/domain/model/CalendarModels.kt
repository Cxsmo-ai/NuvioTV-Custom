package com.nuvio.tv.domain.model

import androidx.compose.runtime.Immutable
import java.time.LocalDate

@Immutable
data class CalendarEpisode(
    val showId: String,
    val showTitle: String,
    val episodeId: String,
    val seasonNumber: Int,
    val episodeNumber: Int,
    val episodeTitle: String,
    val airDate: LocalDate,
    val releaseIso: String?,
    val thumbnail: String? = null,
    val showPoster: String? = null,
    val showBackdrop: String? = null,
    val overview: String? = null,
    val rating: Double? = null,
    val isWatched: Boolean = false,
    val sourceAddonBaseUrl: String? = null
) {
    val episodeCode: String
        get() = "S%02dE%02d".format(seasonNumber, episodeNumber)

    val backdropUrl: String?
        get() = thumbnail ?: showBackdrop ?: showPoster
}

@Immutable
data class CalendarDay(
    val date: LocalDate,
    val episodes: List<CalendarEpisode>
) {
    val isPast: Boolean
        get() = date.isBefore(LocalDate.now())

    val isToday: Boolean
        get() = date == LocalDate.now()

    val isTomorrow: Boolean
        get() = date == LocalDate.now().plusDays(1)

    val episodeCount: Int
        get() = episodes.size
}

enum class CalendarFilter {
    ALL,
    UPCOMING,
    PAST
}
