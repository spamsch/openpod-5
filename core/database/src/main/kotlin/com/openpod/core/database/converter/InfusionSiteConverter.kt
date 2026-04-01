package com.openpod.core.database.converter

import androidx.room.TypeConverter
import com.openpod.model.pod.InfusionSite

/**
 * Room [TypeConverter] for the [InfusionSite] enum.
 *
 * Stores the enum as its [InfusionSite.name] string for forward-compatible
 * persistence. If the stored value does not match a known site, the converter
 * returns null (the field is nullable on [PodSessionEntity]).
 */
internal class InfusionSiteConverter {

    /**
     * Convert a stored string to an [InfusionSite] enum value.
     *
     * @param value The stored enum name, or null.
     * @return The corresponding [InfusionSite], or null if unrecognized or null.
     */
    @TypeConverter
    fun fromString(value: String?): InfusionSite? =
        value?.let {
            try {
                InfusionSite.valueOf(it)
            } catch (_: IllegalArgumentException) {
                null
            }
        }

    /**
     * Convert an [InfusionSite] to its name string for database storage.
     *
     * @param site The [InfusionSite] value, or null.
     * @return The enum name string, or null if the input was null.
     */
    @TypeConverter
    fun toString(site: InfusionSite?): String? =
        site?.name
}
