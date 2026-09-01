package com.nuvio.tv.ui.screens.calendar

import com.nuvio.tv.MainDispatcherRule
import com.nuvio.tv.domain.model.CalendarDay
import com.nuvio.tv.domain.model.CalendarEpisode
import com.nuvio.tv.domain.model.CalendarFilter
import com.nuvio.tv.domain.repository.CalendarRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import java.time.LocalDate

@OptIn(ExperimentalCoroutinesApi::class)
class CalendarViewModelTest {

    @get:Rule
    val dispatcherRule = MainDispatcherRule()

    private class FakeCalendarRepository : CalendarRepository {
        val daysFlow = MutableStateFlow<List<CalendarDay>>(emptyList())
        val refreshingFlow = MutableStateFlow(false)
        var refreshCallCount = 0

        override val calendarDays: Flow<List<CalendarDay>> = daysFlow.asStateFlow()
        override val isRefreshing: Flow<Boolean> = refreshingFlow.asStateFlow()

        override suspend fun refresh() {
            refreshCallCount++
        }
    }

    @Test
    fun testCalendarFilterSwitching() = runTest {
        val fakeRepo = FakeCalendarRepository()
        val today = LocalDate.now()
        val pastDate = today.minusDays(3)
        val futureDate = today.plusDays(4)

        val pastEpisode = CalendarEpisode(
            showId = "s1",
            showTitle = "Past Show",
            episodeId = "e1",
            seasonNumber = 1,
            episodeNumber = 1,
            episodeTitle = "Past Ep",
            airDate = pastDate,
            releaseIso = null
        )
        val futureEpisode = CalendarEpisode(
            showId = "s2",
            showTitle = "Future Show",
            episodeId = "e2",
            seasonNumber = 1,
            episodeNumber = 2,
            episodeTitle = "Future Ep",
            airDate = futureDate,
            releaseIso = null
        )

        val sampleDays = listOf(
            CalendarDay(date = pastDate, episodes = listOf(pastEpisode)),
            CalendarDay(date = futureDate, episodes = listOf(futureEpisode))
        )
        fakeRepo.daysFlow.value = sampleDays

        val viewModel = CalendarViewModel(fakeRepo)
        advanceUntilIdle()

        assertEquals(CalendarFilter.ALL, viewModel.uiState.value.selectedFilter)
        assertEquals(2, viewModel.uiState.value.filteredDays.size)

        viewModel.setFilter(CalendarFilter.UPCOMING)
        advanceUntilIdle()
        assertEquals(CalendarFilter.UPCOMING, viewModel.uiState.value.selectedFilter)
        assertEquals(1, viewModel.uiState.value.filteredDays.size)
        assertEquals(futureDate, viewModel.uiState.value.filteredDays[0].date)

        viewModel.setFilter(CalendarFilter.PAST)
        advanceUntilIdle()
        assertEquals(CalendarFilter.PAST, viewModel.uiState.value.selectedFilter)
        assertEquals(1, viewModel.uiState.value.filteredDays.size)
        assertEquals(pastDate, viewModel.uiState.value.filteredDays[0].date)

        viewModel.refresh()
        advanceUntilIdle()
        assertEquals(1, fakeRepo.refreshCallCount)
    }
}
