package com.openpod.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.openpod.model.profile.InsulinProfile
import java.time.LocalTime

/**
 * Room entity for a single target blood glucose time segment.
 *
 * Each segment defines the desired glucose range (low/high bounds in mg/dL)
 * for the given time window. The bolus calculator uses these bounds to
 * compute correction doses.
 *
 * @property id Auto-generated primary key.
 * @property profileId Foreign key to [InsulinProfileEntity].
 * @property startTime Start of this segment (inclusive), stored as "HH:mm".
 * @property endTime End of this segment (exclusive), stored as "HH:mm".
 * @property lowMgDl Lower bound of the target range (mg/dL).
 * @property highMgDl Upper bound of the target range (mg/dL).
 */
@Entity(
    tableName = "target_glucose_segment",
    foreignKeys = [
        ForeignKey(
            entity = InsulinProfileEntity::class,
            parentColumns = ["id"],
            childColumns = ["profile_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["profile_id"])],
)
data class TargetGlucoseSegmentEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0,

    @ColumnInfo(name = "profile_id")
    val profileId: Long,

    @ColumnInfo(name = "start_time")
    val startTime: LocalTime,

    @ColumnInfo(name = "end_time")
    val endTime: LocalTime,

    @ColumnInfo(name = "low_mg_dl")
    val lowMgDl: Int,

    @ColumnInfo(name = "high_mg_dl")
    val highMgDl: Int,
) {
    init {
        require(lowMgDl in InsulinProfile.TARGET_LOW_RANGE) {
            "Target low $lowMgDl outside range ${InsulinProfile.TARGET_LOW_RANGE}"
        }
        require(highMgDl in InsulinProfile.TARGET_HIGH_RANGE) {
            "Target high $highMgDl outside range ${InsulinProfile.TARGET_HIGH_RANGE}"
        }
        require(highMgDl >= lowMgDl) {
            "Target high ($highMgDl) must be >= low ($lowMgDl)"
        }
    }
}
