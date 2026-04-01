package com.openpod.domain.history

import com.openpod.model.history.HistoryEvent
import com.openpod.model.history.HistoryEventType
import kotlinx.coroutines.flow.Flow

interface HistoryRepository {
    fun observeAllEvents(): Flow<List<HistoryEvent>>
    fun observeEventsByType(type: HistoryEventType): Flow<List<HistoryEvent>>
    suspend fun recordEvent(type: HistoryEventType, primaryValue: Double, secondaryValue: String? = null, metadata: String? = null)
}
