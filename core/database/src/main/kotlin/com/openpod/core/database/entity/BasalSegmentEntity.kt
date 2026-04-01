package com.openpod.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.openpod.model.insulin.BasalProgram
import java.time.LocalTime

/**
 * Room entity for a single basal rate time segment within a [BasalProgramEntity].
 *
 * Each segment defines the insulin delivery rate (U/hr) for a time window.
 * Segments within a program are contiguous and cover exactly 24 hours.
 *
 * @property id Auto-generated primary key.
 * @property programId Foreign key to [BasalProgramEntity].
 * @property startTime Start of this segment (inclusive), stored as "HH:mm".
 * @property endTime End of this segment (exclusive), stored as "HH:mm".
 * @property rateUnitsPerHour Insulin delivery rate in units per hour.
 */
@Entity(
    tableName = "basal_segment",
    foreignKeys = [
        ForeignKey(
            entity = BasalProgramEntity::class,
            parentColumns = ["id"],
            childColumns = ["program_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["program_id"])],
)
data class BasalSegmentEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0,

    @ColumnInfo(name = "program_id")
    val programId: Long,

    @ColumnInfo(name = "start_time")
    val startTime: LocalTime,

    @ColumnInfo(name = "end_time")
    val endTime: LocalTime,

    @ColumnInfo(name = "rate_units_per_hour")
    val rateUnitsPerHour: Double,
) {
    init {
        require(rateUnitsPerHour in BasalProgram.RATE_RANGE) {
            "Basal rate $rateUnitsPerHour U/hr outside valid range ${BasalProgram.RATE_RANGE}"
        }
    }
}
