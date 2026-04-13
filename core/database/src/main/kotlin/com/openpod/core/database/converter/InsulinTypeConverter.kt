package com.openpod.core.database.converter

import androidx.room.TypeConverter
import com.openpod.model.profile.InsulinType

/**
 * Room [TypeConverter] for the [InsulinType] enum.
 *
 * Stores the enum as its [InsulinType.name] string. Using the name (rather
 * than ordinal) ensures the database remains valid if enum entries are
 * reordered in future versions.
 */
internal class InsulinTypeConverter {

    /**
     * Convert a stored string to an [InsulinType] enum value.
     *
     * Falls back to [InsulinType.OTHER] if the stored value does not match
     * any known enum entry (forward-compatibility after a downgrade).
     *
     * @param value The stored enum name, or null.
     * @return The corresponding [InsulinType], or null if the input was null.
     */
    @TypeConverter
    fun fromString(value: String?): InsulinType? =
        value?.let {
            try {
                InsulinType.valueOf(it)
            } catch (_: IllegalArgumentException) {
                InsulinType.OTHER
            }
        }

    /**
     * Convert an [InsulinType] to its name string for database storage.
     *
     * @param type The [InsulinType] value, or null.
     * @return The enum name string, or null if the input was null.
     */
    @TypeConverter
    fun toString(type: InsulinType?): String? =
        type?.name
}
