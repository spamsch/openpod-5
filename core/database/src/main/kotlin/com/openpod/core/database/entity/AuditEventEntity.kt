package com.openpod.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Room entity for the immutable audit trail.
 *
 * This table is append-only at the application level: the DAO provides no
 * delete or update operations. Each row participates in a SHA-256 hash chain
 * linking it to the previous record via [previousEventHash] and [recordChecksum].
 */
@Entity(
    tableName = "audit_event",
    indices = [
        Index(value = ["timestamp_utc"]),
        Index(value = ["category"]),
        Index(value = ["clinical_context"]),
    ],
)
data class AuditEventEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0,

    @ColumnInfo(name = "category")
    val category: String,

    @ColumnInfo(name = "timestamp_utc")
    val timestampUtc: Long,

    @ColumnInfo(name = "actor")
    val actor: String,

    @ColumnInfo(name = "source")
    val source: String,

    @ColumnInfo(name = "clinical_context")
    val clinicalContext: String,

    @ColumnInfo(name = "payload_json")
    val payloadJson: String,

    @ColumnInfo(name = "payload_hash")
    val payloadHash: String,

    @ColumnInfo(name = "previous_event_hash")
    val previousEventHash: String,

    @ColumnInfo(name = "record_checksum")
    val recordChecksum: String,
)
