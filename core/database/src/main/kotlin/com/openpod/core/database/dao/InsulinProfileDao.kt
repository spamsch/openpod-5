package com.openpod.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.openpod.core.database.entity.CorrectionFactorSegmentEntity
import com.openpod.core.database.entity.IcRatioSegmentEntity
import com.openpod.core.database.entity.InsulinProfileEntity
import com.openpod.core.database.entity.TargetGlucoseSegmentEntity
import kotlinx.coroutines.flow.Flow

/**
 * Data Access Object for the insulin therapy profile and its time-segmented settings.
 *
 * The profile is a single-row table (id = 1). Its IC ratio, correction factor,
 * and target glucose segments are stored in separate tables with foreign keys.
 * All multi-table operations use [Transaction] to ensure atomicity.
 */
@Dao
interface InsulinProfileDao {

    // ---- Profile header ----

    /**
     * Insert or replace the insulin profile header.
     *
     * @param profile The profile entity to upsert (id must be [InsulinProfileEntity.SINGLETON_ID]).
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertProfile(profile: InsulinProfileEntity)

    /**
     * Retrieve the insulin profile header, or null if onboarding is incomplete.
     *
     * @return The singleton profile entity, or null.
     */
    @Query("SELECT * FROM insulin_profile WHERE id = 1")
    suspend fun getProfile(): InsulinProfileEntity?

    /**
     * Observe the insulin profile header as a [Flow].
     *
     * @return A [Flow] emitting the profile whenever it changes, or null if absent.
     */
    @Query("SELECT * FROM insulin_profile WHERE id = 1")
    fun observeProfile(): Flow<InsulinProfileEntity?>

    // ---- IC ratio segments ----

    /**
     * Insert a list of IC ratio segments. Typically called after clearing existing segments.
     *
     * @param segments The IC ratio segments to insert.
     */
    @Insert
    suspend fun insertIcRatioSegments(segments: List<IcRatioSegmentEntity>)

    /**
     * Retrieve all IC ratio segments for the given profile.
     *
     * @param profileId The profile ID (normally [InsulinProfileEntity.SINGLETON_ID]).
     * @return Ordered list of IC ratio segments.
     */
    @Query("SELECT * FROM ic_ratio_segment WHERE profile_id = :profileId ORDER BY start_time")
    suspend fun getIcRatioSegments(profileId: Long = InsulinProfileEntity.SINGLETON_ID): List<IcRatioSegmentEntity>

    /**
     * Delete all IC ratio segments for the given profile.
     *
     * @param profileId The profile ID.
     */
    @Query("DELETE FROM ic_ratio_segment WHERE profile_id = :profileId")
    suspend fun deleteIcRatioSegments(profileId: Long = InsulinProfileEntity.SINGLETON_ID)

    // ---- Correction factor segments ----

    /**
     * Insert a list of correction factor segments.
     *
     * @param segments The correction factor segments to insert.
     */
    @Insert
    suspend fun insertCorrectionFactorSegments(segments: List<CorrectionFactorSegmentEntity>)

    /**
     * Retrieve all correction factor segments for the given profile.
     *
     * @param profileId The profile ID.
     * @return Ordered list of correction factor segments.
     */
    @Query("SELECT * FROM correction_factor_segment WHERE profile_id = :profileId ORDER BY start_time")
    suspend fun getCorrectionFactorSegments(profileId: Long = InsulinProfileEntity.SINGLETON_ID): List<CorrectionFactorSegmentEntity>

    /**
     * Delete all correction factor segments for the given profile.
     *
     * @param profileId The profile ID.
     */
    @Query("DELETE FROM correction_factor_segment WHERE profile_id = :profileId")
    suspend fun deleteCorrectionFactorSegments(profileId: Long = InsulinProfileEntity.SINGLETON_ID)

    // ---- Target glucose segments ----

    /**
     * Insert a list of target glucose segments.
     *
     * @param segments The target glucose segments to insert.
     */
    @Insert
    suspend fun insertTargetGlucoseSegments(segments: List<TargetGlucoseSegmentEntity>)

    /**
     * Retrieve all target glucose segments for the given profile.
     *
     * @param profileId The profile ID.
     * @return Ordered list of target glucose segments.
     */
    @Query("SELECT * FROM target_glucose_segment WHERE profile_id = :profileId ORDER BY start_time")
    suspend fun getTargetGlucoseSegments(profileId: Long = InsulinProfileEntity.SINGLETON_ID): List<TargetGlucoseSegmentEntity>

    /**
     * Delete all target glucose segments for the given profile.
     *
     * @param profileId The profile ID.
     */
    @Query("DELETE FROM target_glucose_segment WHERE profile_id = :profileId")
    suspend fun deleteTargetGlucoseSegments(profileId: Long = InsulinProfileEntity.SINGLETON_ID)

    // ---- Transactional composite operations ----

    /**
     * Replace all IC ratio segments for the profile in a single transaction.
     *
     * Deletes existing segments then inserts the new set. This ensures
     * the segment list is always consistent (no partial updates).
     *
     * @param segments The complete set of IC ratio segments.
     * @param profileId The profile ID.
     */
    @Transaction
    suspend fun replaceIcRatioSegments(
        segments: List<IcRatioSegmentEntity>,
        profileId: Long = InsulinProfileEntity.SINGLETON_ID,
    ) {
        deleteIcRatioSegments(profileId)
        insertIcRatioSegments(segments)
    }

    /**
     * Replace all correction factor segments for the profile in a single transaction.
     *
     * @param segments The complete set of correction factor segments.
     * @param profileId The profile ID.
     */
    @Transaction
    suspend fun replaceCorrectionFactorSegments(
        segments: List<CorrectionFactorSegmentEntity>,
        profileId: Long = InsulinProfileEntity.SINGLETON_ID,
    ) {
        deleteCorrectionFactorSegments(profileId)
        insertCorrectionFactorSegments(segments)
    }

    /**
     * Replace all target glucose segments for the profile in a single transaction.
     *
     * @param segments The complete set of target glucose segments.
     * @param profileId The profile ID.
     */
    @Transaction
    suspend fun replaceTargetGlucoseSegments(
        segments: List<TargetGlucoseSegmentEntity>,
        profileId: Long = InsulinProfileEntity.SINGLETON_ID,
    ) {
        deleteTargetGlucoseSegments(profileId)
        insertTargetGlucoseSegments(segments)
    }

    /**
     * Save a complete insulin profile and all its segments in a single transaction.
     *
     * This is the primary write path used during onboarding and profile editing.
     * All existing segments are replaced atomically.
     *
     * @param profile The profile header entity.
     * @param icSegments IC ratio segments covering 24 hours.
     * @param cfSegments Correction factor segments covering 24 hours.
     * @param targetSegments Target glucose segments covering 24 hours.
     */
    @Transaction
    suspend fun saveFullProfile(
        profile: InsulinProfileEntity,
        icSegments: List<IcRatioSegmentEntity>,
        cfSegments: List<CorrectionFactorSegmentEntity>,
        targetSegments: List<TargetGlucoseSegmentEntity>,
    ) {
        upsertProfile(profile)
        replaceIcRatioSegments(icSegments, profile.id)
        replaceCorrectionFactorSegments(cfSegments, profile.id)
        replaceTargetGlucoseSegments(targetSegments, profile.id)
    }

    /**
     * Delete the entire profile and all associated segments.
     *
     * Segment deletion cascades via foreign key constraints.
     */
    @Query("DELETE FROM insulin_profile WHERE id = 1")
    suspend fun deleteProfile()
}
