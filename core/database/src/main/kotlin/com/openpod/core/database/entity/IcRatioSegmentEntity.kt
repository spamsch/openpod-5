package com.openpod.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.openpod.model.profile.InsulinProfile
import java.time.LocalTime

/**
 * Room entity for a single insulin-to-carb ratio time segment.
 *
 * Each segment defines the number of grams of carbohydrate covered by
 * one unit of insulin during the given time window. Multiple segments
 * cover the full 24-hour day.
 *
 * @property id Auto-generated primary key.
 * @property profileId Foreign key to [InsulinProfileEntity].
 * @property startTime Start of this segment (inclusive), stored as "HH:mm".
 * @property endTime End of this segment (exclusive), stored as "HH:mm".
 * @property ratioGramsPerUnit Grams of carbohydrate per unit of insulin.
 */
@Entity(
    tableName = "ic_ratio_segment",
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
data class IcRatioSegmentEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0,

    @ColumnInfo(name = "profile_id")
    val profileId: Long,

    @ColumnInfo(name = "start_time")
    val startTime: LocalTime,

    @ColumnInfo(name = "end_time")
    val endTime: LocalTime,

    @ColumnInfo(name = "ratio_grams_per_unit")
    val ratioGramsPerUnit: Int,
) {
    init {
        require(ratioGramsPerUnit in InsulinProfile.IC_RATIO_RANGE) {
            "IC ratio $ratioGramsPerUnit outside valid range ${InsulinProfile.IC_RATIO_RANGE}"
        }
    }
}
