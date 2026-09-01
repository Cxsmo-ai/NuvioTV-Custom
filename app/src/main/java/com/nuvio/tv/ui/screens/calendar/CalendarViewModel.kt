package com.nuvio.tv.ui.screens.calendar

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.domain.model.CalendarDay
import com.nuvio.tv.domain.model.CalendarFilter
import com.nuvio.tv.domain.repository.CalendarRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject

data class CalendarUiState(
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val allDays: List<CalendarDay> = emptyList(),
    val filteredDays: List<CalendarDay> = emptyList(),
    val selectedFilter: CalendarFilter = CalendarFilter.ALL
)

@HiltViewModel
class CalendarViewModel @Inject constructor(
    private val calendarRepository: CalendarRepository
) : ViewModel() {

    private val _selectedFilter = MutableStateFlow(CalendarFilter.ALL)
    private val _hasLoadedOnce = MutableStateFlow(false)

    val uiState: StateFlow<CalendarUiState> = combine(
        calendarRepository.calendarDays,
        calendarRepository.isRefreshing,
        _selectedFilter,
        _hasLoadedOnce
    ) { days, isRefreshing, filter, hasLoadedOnce ->
        val today = LocalDate.now()
        val filtered = when (filter) {
            CalendarFilter.ALL -> days
            CalendarFilter.UPCOMING -> days.filter { !it.date.isBefore(today) }
            CalendarFilter.PAST -> days.filter { it.date.isBefore(today) }
        }
        CalendarUiState(
            isLoading = !hasLoadedOnce && days.isEmpty() && isRefreshing,
            isRefreshing = isRefreshing,
            allDays = days,
            filteredDays = filtered,
            selectedFilter = filter
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        CalendarUiState()
    )

    init {
        viewModelScope.launch {
            calendarRepository.calendarDays.collect { days ->
                if (days.isNotEmpty()) {
                    _hasLoadedOnce.value = true
                }
            }
        }
        viewModelScope.launch {
            calendarRepository.isRefreshing.collect { refreshing ->
                if (!refreshing) {
                    _hasLoadedOnce.value = true
                }
            }
        }
    }

    fun setFilter(filter: CalendarFilter) {
        _selectedFilter.value = filter
    }

    fun refresh() {
        viewModelScope.launch {
            calendarRepository.refresh()
        }
    }
}
