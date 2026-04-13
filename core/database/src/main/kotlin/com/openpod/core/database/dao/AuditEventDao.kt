package com.openpod.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.openpod.core.database.entity.AuditEventEntity
import kotlinx.coroutines.flow.Flow

/**
 * Data access object for the immutable audit event table.
 *
 * This DAO intentionally provides NO delete or update operations.
 * Audit records are append-only at the application level.
 */
@Dao
interface AuditEventDao {

    @Insert
    suspend fun insert(event: AuditEventEntity): Long

    @Query("SELECT * FROM audit_event ORDER BY id ASC")
    fun observeAll(): Flow<List<AuditEventEntity>>

    @Query("SELECT * FROM audit_event WHERE category = :category ORDER BY id ASC")
    fun observeByCategory(category: String): Flow<List<AuditEventEntity>>

    @Query("SELECT * FROM audit_event WHERE clinical_context = :clinicalContext ORDER BY id ASC")
    fun observeByClinicalContext(clinicalContext: String): Flow<List<AuditEventEntity>>

    @Query("SELECT * FROM audit_event ORDER BY id DESC LIMIT 1")
    suspend fun getLatest(): AuditEventEntity?

    @Query("SELECT COUNT(*) FROM audit_event")
    suspend fun count(): Long

    @Query("SELECT * FROM audit_event ORDER BY id ASC")
    suspend fun getAll(): List<AuditEventEntity>
}
