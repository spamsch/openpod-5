package com.openpod.data.history

import com.openpod.core.database.dao.HistoryEventDao
import com.openpod.core.database.entity.HistoryEventEntity
import com.openpod.domain.history.HistoryRepository
import com.openpod.model.history.HistoryEvent
import com.openpod.model.history.HistoryEventType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class HistoryRepositoryImpl @Inject constructor(
    private val dao: HistoryEventDao,
) : HistoryRepository {

    override fun observeAllEvents(): Flow<List<HistoryEvent>> =
        dao.observeAll().map { entities -> entities.map { it.toDomain() } }

    override fun observeEventsByType(type: HistoryEventType): Flow<List<HistoryEvent>> =
        dao.observeByType(type.name).map { entities -> entities.map { it.toDomain() } }

    override suspend fun recordEvent(
        type: HistoryEventType,
        primaryValue: Double,
        secondaryValue: String?,
        metadata: String?,
    ) {
        dao.insert(
            HistoryEventEntity(
                eventType = type.name,
                timestamp = Instant.now(),
                primaryValue = primaryValue,
                secondaryValue = secondaryValue,
                metadata = metadata,
            ),
        )
    }

    private fun HistoryEventEntity.toDomain() = HistoryEvent(
        id = id,
        type = try { HistoryEventType.valueOf(eventType) } catch (_: Exception) { HistoryEventType.POD },
        timestamp = timestamp,
        primaryValue = primaryValue,
        secondaryValue = secondaryValue,
        metadata = metadata,
    )
}
