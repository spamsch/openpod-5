package com.openpod.feature.history

import com.openpod.core.ui.mvi.MviViewModel
import com.openpod.domain.history.HistoryRepository
import com.openpod.model.history.HistoryEventType
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.flatMapLatest
import javax.inject.Inject

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class HistoryViewModel @Inject constructor(
    private val historyRepository: HistoryRepository,
) : MviViewModel<HistoryState, HistoryIntent, HistoryEffect>(
    initialState = HistoryState(),
) {

    private val filterFlow = MutableStateFlow(HistoryFilter.ALL)

    init {
        launch {
            filterFlow.flatMapLatest { filter ->
                when (filter) {
                    HistoryFilter.ALL -> historyRepository.observeAllEvents()
                    HistoryFilter.BOLUS -> historyRepository.observeEventsByType(HistoryEventType.BOLUS)
                    HistoryFilter.GLUCOSE -> historyRepository.observeEventsByType(HistoryEventType.GLUCOSE)
                    HistoryFilter.BASAL -> historyRepository.observeEventsByType(HistoryEventType.BASAL)
                    HistoryFilter.ALERTS -> historyRepository.observeEventsByType(HistoryEventType.ALERT)
                    HistoryFilter.POD -> historyRepository.observeEventsByType(HistoryEventType.POD)
                }
            }.collectLatest { events ->
                updateState { copy(events = events, isLoading = false) }
            }
        }
    }

    override fun handleIntent(intent: HistoryIntent) {
        when (intent) {
            is HistoryIntent.SelectFilter -> {
                updateState { copy(selectedFilter = intent.filter) }
                filterFlow.value = intent.filter
            }
        }
    }
}
