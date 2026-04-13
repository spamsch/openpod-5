package com.openpod.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import com.openpod.model.profile.InsulinProfile
import com.openpod.model.profile.InsulinType

/**
 * Room entity storing the user's insulin therapy profile header.
 *
 * Only a single row exists (id = 1). The profile's time-segmented settings
 * (IC ratio, correction factor, target glucose) are stored in their own
 * segment entities with a foreign key back to this row.
 *
 * @property id Fixed primary key (always 1 — single-profile design).
 * @property durationOfInsulinAction Duration of insulin action in hours. Must be within [InsulinProfile.DIA_RANGE].
 * @property insulinType The rapid-acting insulin type selected during onboarding.
 */
@Entity(tableName = "insulin_profile")
data class InsulinProfileEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: Long = SINGLETON_ID,

    @ColumnInfo(name = "duration_of_insulin_action")
    val durationOfInsulinAction: Double,

    @ColumnInfo(name = "insulin_type")
    val insulinType: InsulinType,
) {
    init {
        require(durationOfInsulinAction in InsulinProfile.DIA_RANGE) {
            "DIA $durationOfInsulinAction hours outside valid range ${InsulinProfile.DIA_RANGE}"
        }
    }

    companion object {
        /** The profile table always contains a single row with this ID. */
        const val SINGLETON_ID = 1L
    }
}
