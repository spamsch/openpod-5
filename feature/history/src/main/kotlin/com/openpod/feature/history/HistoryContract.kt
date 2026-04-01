package com.openpod.feature.history

import com.openpod.core.ui.mvi.UiEffect
import com.openpod.core.ui.mvi.UiIntent
import com.openpod.core.ui.mvi.UiState
import com.openpod.model.history.HistoryEvent
import java.time.LocalDate
import java.time.ZoneId

enum class HistoryFilter { ALL, BOLUS, GLUCOSE, BASAL, ALERTS, POD }

data class HistoryState(
    val events: List<HistoryEvent> = emptyList(),
    val selectedFilter: HistoryFilter = HistoryFilter.ALL,
    val isLoading: Boolean = true,
) : UiState {
    val groupedByDay: Map<LocalDate, List<HistoryEvent>>
        get() = events
            .groupBy { it.timestamp.atZone(ZoneId.systemDefault()).toLocalDate() }
            .toSortedMap(compareByDescending { it })
}

sealed interface HistoryIntent : UiIntent {
    data class SelectFilter(val filter: HistoryFilter) : HistoryIntent
}

sealed interface HistoryEffect : UiEffect
