package com.openpod.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.openpod.core.database.entity.PodSessionEntity
import kotlinx.coroutines.flow.Flow
import java.time.Instant

/**
 * Data Access Object for pod activation session records.
 *
 * Each pod activation creates a new session row. The "active" pod is the
 * one with a null deactivated_at timestamp. At most one pod may be active
 * at any time — callers must deactivate the current pod before inserting a new one.
 */
@Dao
interface PodSessionDao {

    /**
     * Insert a new pod session record.
     *
     * @param session The pod session entity to insert.
     * @return The auto-generated row ID.
     */
    @Insert
    suspend fun insert(session: PodSessionEntity): Long

    /**
     * Retrieve the currently active pod session (the one not yet deactivated).
     *
     * @return The active session, or null if no pod is active.
     */
    @Query("SELECT * FROM pod_session WHERE deactivated_at IS NULL LIMIT 1")
    suspend fun getActiveSession(): PodSessionEntity?

    /**
     * Observe the currently active pod session as a [Flow].
     *
     * Emits null when no pod is active (between deactivation and next activation).
     *
     * @return A [Flow] emitting the active session whenever it changes.
     */
    @Query("SELECT * FROM pod_session WHERE deactivated_at IS NULL LIMIT 1")
    fun observeActiveSession(): Flow<PodSessionEntity?>

    /**
     * Deactivate the currently active pod by setting its deactivation timestamp.
     *
     * This is a no-op if no pod is currently active (deactivated_at IS NULL matches nothing).
     *
     * @param deactivatedAt The instant the pod was deactivated.
     */
    @Query("UPDATE pod_session SET deactivated_at = :deactivatedAt WHERE deactivated_at IS NULL")
    suspend fun deactivateCurrentSession(deactivatedAt: Instant = Instant.now())

    /**
     * Retrieve all pod sessions ordered by activation time (most recent first).
     *
     * Useful for displaying pod history and site rotation patterns.
     *
     * @return All pod session records, newest first.
     */
    @Query("SELECT * FROM pod_session ORDER BY activated_at DESC")
    suspend fun getAllSessions(): List<PodSessionEntity>

    /**
     * Observe all pod sessions as a [Flow], ordered by activation time (most recent first).
     *
     * @return A [Flow] emitting the full session history whenever it changes.
     */
    @Query("SELECT * FROM pod_session ORDER BY activated_at DESC")
    fun observeAllSessions(): Flow<List<PodSessionEntity>>

    /**
     * Count the total number of pod sessions (for statistics/debugging).
     *
     * @return The number of pod session records.
     */
    @Query("SELECT COUNT(*) FROM pod_session")
    suspend fun getSessionCount(): Int
}
