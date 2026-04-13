package com.openpod.core.database.converter

import androidx.room.TypeConverter
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * Room [TypeConverter] for [LocalTime] values.
 *
 * Stores times as "HH:mm" strings (e.g., "08:30", "23:00", "00:00").
 * This format is human-readable in the database and unambiguous for
 * 24-hour time values used in insulin therapy schedules.
 */
internal class LocalTimeConverter {

    private val formatter: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

    /**
     * Convert a "HH:mm" string from the database to a [LocalTime].
     *
     * @param value The stored string, or null.
     * @return The parsed [LocalTime], or null if the input was null.
     */
    @TypeConverter
    fun fromString(value: String?): LocalTime? =
        value?.let { LocalTime.parse(it, formatter) }

    /**
     * Convert a [LocalTime] to a "HH:mm" string for database storage.
     *
     * @param time The [LocalTime] value, or null.
     * @return The formatted string, or null if the input was null.
     */
    @TypeConverter
    fun toString(time: LocalTime?): String? =
        time?.format(formatter)
}
