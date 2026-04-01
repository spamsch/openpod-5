package com.openpod.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Room entity for a named basal insulin delivery program.
 *
 * A basal program defines background insulin delivery rates across the day.
 * Multiple programs may exist (e.g., "Weekday", "Weekend") but only one
 * can be active at a time.
 *
 * @property id Auto-generated primary key.
 * @property name User-given name for this program (max 20 characters).
 * @property isActive True if this is the currently running program. Exactly one program must be active.
 */
@Entity(tableName = "basal_program")
data class BasalProgramEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0,

    @ColumnInfo(name = "name")
    val name: String,

    @ColumnInfo(name = "is_active")
    val isActive: Boolean = false,
) {
    init {
        require(name.isNotBlank()) { "Basal program name must not be blank" }
        require(name.length <= 20) { "Basal program name must be <= 20 characters" }
    }
}
