package com.openpod.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.time.Instant

/**
 * Room entity for a single history event (bolus, glucose, basal change, alert, pod event).
 *
 * Uses a flexible single-table design with [eventType] discriminator and nullable
 * secondary/metadata fields to avoid multiple tables for each event category.
 */
@Entity(
    tableName = "history_event",
    indices = [
        Index(value = ["timestamp"]),
        Index(value = ["event_type"]),
    ],
)
data class HistoryEventEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0,

    @ColumnInfo(name = "event_type")
    val eventType: String,

    @ColumnInfo(name = "timestamp")
    val timestamp: Instant,

    @ColumnInfo(name = "primary_value")
    val primaryValue: Double,

    @ColumnInfo(name = "secondary_value")
    val secondaryValue: String? = null,

    @ColumnInfo(name = "metadata")
    val metadata: String? = null,
)
