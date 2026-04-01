package com.openpod.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.openpod.core.database.entity.HistoryEventEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface HistoryEventDao {

    @Insert
    suspend fun insert(event: HistoryEventEntity): Long

    @Query("SELECT * FROM history_event ORDER BY timestamp DESC")
    fun observeAll(): Flow<List<HistoryEventEntity>>

    @Query("SELECT * FROM history_event WHERE event_type = :type ORDER BY timestamp DESC")
    fun observeByType(type: String): Flow<List<HistoryEventEntity>>

    @Query("DELETE FROM history_event")
    suspend fun deleteAll()
}
