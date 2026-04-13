package com.openpod.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.openpod.model.profile.InsulinProfile
import java.time.LocalTime

/**
 * Room entity for a single insulin sensitivity / correction factor time segment.
 *
 * Each segment defines how many mg/dL one unit of insulin is expected to
 * lower blood glucose during the given time window.
 *
 * @property id Auto-generated primary key.
 * @property profileId Foreign key to [InsulinProfileEntity].
 * @property startTime Start of this segment (inclusive), stored as "HH:mm".
 * @property endTime End of this segment (exclusive), stored as "HH:mm".
 * @property factorMgDlPerUnit mg/dL drop per unit of insulin.
 */
@Entity(
    tableName = "correction_factor_segment",
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
data class CorrectionFactorSegmentEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0,

    @ColumnInfo(name = "profile_id")
    val profileId: Long,

    @ColumnInfo(name = "start_time")
    val startTime: LocalTime,

    @ColumnInfo(name = "end_time")
    val endTime: LocalTime,

    @ColumnInfo(name = "factor_mg_dl_per_unit")
    val factorMgDlPerUnit: Int,
) {
    init {
        require(factorMgDlPerUnit in InsulinProfile.CF_RANGE) {
            "Correction factor $factorMgDlPerUnit outside valid range ${InsulinProfile.CF_RANGE}"
        }
    }
}
