package com.openpod.core.database.converter

import androidx.room.TypeConverter
import java.time.Instant

/**
 * Room [TypeConverter] for [Instant] values.
 *
 * Stores instants as epoch milliseconds (Long). This provides millisecond
 * precision which is sufficient for medical event timestamps while remaining
 * timezone-agnostic.
 */
internal class InstantConverter {

    /**
     * Convert an epoch millisecond value from the database to an [Instant].
     *
     * @param value The stored epoch millis, or null.
     * @return The corresponding [Instant], or null if the input was null.
     */
    @TypeConverter
    fun fromEpochMillis(value: Long?): Instant? =
        value?.let { Instant.ofEpochMilli(it) }

    /**
     * Convert an [Instant] to epoch milliseconds for database storage.
     *
     * @param instant The [Instant] value, or null.
     * @return The epoch millis value, or null if the input was null.
     */
    @TypeConverter
    fun toEpochMillis(instant: Instant?): Long? =
        instant?.toEpochMilli()
}
