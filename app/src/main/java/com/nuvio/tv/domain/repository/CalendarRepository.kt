package com.nuvio.tv.domain.repository

import com.nuvio.tv.domain.model.CalendarDay
import kotlinx.coroutines.flow.Flow

interface CalendarRepository {
    val calendarDays: Flow<List<CalendarDay>>
    val isRefreshing: Flow<Boolean>
    suspend fun refresh()
}
