package com.openpod.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.openpod.core.database.entity.BasalProgramEntity
import com.openpod.core.database.entity.BasalSegmentEntity
import kotlinx.coroutines.flow.Flow

/**
 * Data Access Object for basal insulin delivery programs and their time segments.
 *
 * Basal programs define background insulin delivery rates across the day.
 * Only one program can be active at a time. The [setActiveProgram] method
 * enforces this invariant transactionally.
 */
@Dao
interface BasalProgramDao {

    // ---- Programs ----

    /**
     * Insert a new basal program and return its generated ID.
     *
     * @param program The program entity to insert.
     * @return The auto-generated row ID of the inserted program.
     */
    @Insert
    suspend fun insertProgram(program: BasalProgramEntity): Long

    /**
     * Update an existing basal program.
     *
     * @param program The program entity with updated fields.
     */
    @Update
    suspend fun updateProgram(program: BasalProgramEntity)

    /**
     * Retrieve all basal programs, ordered by name.
     *
     * @return List of all basal programs.
     */
    @Query("SELECT * FROM basal_program ORDER BY name")
    suspend fun getAllPrograms(): List<BasalProgramEntity>

    /**
     * Observe all basal programs as a [Flow], ordered by name.
     *
     * @return A [Flow] emitting the full program list whenever any program changes.
     */
    @Query("SELECT * FROM basal_program ORDER BY name")
    fun observeAllPrograms(): Flow<List<BasalProgramEntity>>

    /**
     * Retrieve a single program by ID.
     *
     * @param programId The program's primary key.
     * @return The program entity, or null if not found.
     */
    @Query("SELECT * FROM basal_program WHERE id = :programId")
    suspend fun getProgramById(programId: Long): BasalProgramEntity?

    /**
     * Retrieve the currently active basal program.
     *
     * @return The active program, or null if none is active.
     */
    @Query("SELECT * FROM basal_program WHERE is_active = 1 LIMIT 1")
    suspend fun getActiveProgram(): BasalProgramEntity?

    /**
     * Observe the currently active basal program as a [Flow].
     *
     * @return A [Flow] emitting the active program whenever it changes.
     */
    @Query("SELECT * FROM basal_program WHERE is_active = 1 LIMIT 1")
    fun observeActiveProgram(): Flow<BasalProgramEntity?>

    /**
     * Delete a basal program by ID. Associated segments are cascade-deleted.
     *
     * @param programId The program's primary key.
     */
    @Query("DELETE FROM basal_program WHERE id = :programId")
    suspend fun deleteProgram(programId: Long)

    /**
     * Deactivate all basal programs. Used as the first step of [setActiveProgram].
     */
    @Query("UPDATE basal_program SET is_active = 0")
    suspend fun deactivateAllPrograms()

    /**
     * Activate a single program by ID. Used as the second step of [setActiveProgram].
     *
     * @param programId The program to activate.
     */
    @Query("UPDATE basal_program SET is_active = 1 WHERE id = :programId")
    suspend fun activateProgram(programId: Long)

    // ---- Segments ----

    /**
     * Insert a list of basal rate segments for a program.
     *
     * @param segments The basal rate segments to insert.
     */
    @Insert
    suspend fun insertSegments(segments: List<BasalSegmentEntity>)

    /**
     * Retrieve all basal rate segments for a program, ordered by start time.
     *
     * @param programId The program's primary key.
     * @return Ordered list of basal rate segments.
     */
    @Query("SELECT * FROM basal_segment WHERE program_id = :programId ORDER BY start_time")
    suspend fun getSegments(programId: Long): List<BasalSegmentEntity>

    /**
     * Delete all basal rate segments for a program.
     *
     * @param programId The program's primary key.
     */
    @Query("DELETE FROM basal_segment WHERE program_id = :programId")
    suspend fun deleteSegments(programId: Long)

    // ---- Transactional composite operations ----

    /**
     * Set the active basal program, deactivating all others.
     *
     * This enforces the invariant that exactly one program is active at a time.
     * Both the deactivation and activation happen within a single transaction.
     *
     * @param programId The ID of the program to activate.
     */
    @Transaction
    suspend fun setActiveProgram(programId: Long) {
        deactivateAllPrograms()
        activateProgram(programId)
    }

    /**
     * Replace all segments for a program in a single transaction.
     *
     * @param programId The program's primary key.
     * @param segments The complete set of basal rate segments.
     */
    @Transaction
    suspend fun replaceSegments(programId: Long, segments: List<BasalSegmentEntity>) {
        deleteSegments(programId)
        insertSegments(segments)
    }

    /**
     * Save a complete basal program with its segments in a single transaction.
     *
     * Inserts the program, then inserts all segments referencing the new program ID.
     *
     * @param program The program entity (id = 0 for auto-generation).
     * @param segments The basal rate segments. Their [BasalSegmentEntity.programId] will
     *   be ignored; the returned program ID is used instead.
     * @return The auto-generated program ID.
     */
    @Transaction
    suspend fun saveFullProgram(
        program: BasalProgramEntity,
        segments: List<BasalSegmentEntity>,
    ): Long {
        val programId = insertProgram(program)
        val segmentsWithId = segments.map { it.copy(programId = programId) }
        insertSegments(segmentsWithId)
        return programId
    }
}
