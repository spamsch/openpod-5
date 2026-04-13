package com.openpod.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import com.openpod.model.pod.InfusionSite
import java.time.Instant

/**
 * Room entity recording a pod activation session.
 *
 * Each row represents a single pod's lifecycle from activation to deactivation.
 * The active pod is the one with a null [deactivatedAt]. At most one pod may
 * be active at any time.
 *
 * @property id Auto-generated primary key.
 * @property uid Pod unique identifier (hex string from BLE advertisement).
 * @property lotNumber Manufacturing lot number.
 * @property sequenceNumber Manufacturing sequence number within the lot.
 * @property firmwareVersion Pod firmware version string.
 * @property activatedAt When the pod was activated (cannula inserted, delivery started).
 * @property deactivatedAt When the pod was deactivated (null if still active).
 * @property site Body site where the pod is applied, for site rotation tracking.
 */
@Entity(tableName = "pod_session")
data class PodSessionEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0,

    @ColumnInfo(name = "uid")
    val uid: String,

    @ColumnInfo(name = "lot_number")
    val lotNumber: String,

    @ColumnInfo(name = "sequence_number")
    val sequenceNumber: String,

    @ColumnInfo(name = "firmware_version")
    val firmwareVersion: String = "",

    @ColumnInfo(name = "activated_at")
    val activatedAt: Instant,

    @ColumnInfo(name = "deactivated_at")
    val deactivatedAt: Instant? = null,

    @ColumnInfo(name = "site")
    val site: InfusionSite? = null,
) {
    init {
        require(uid.isNotBlank()) { "Pod UID must not be blank" }
        require(lotNumber.isNotBlank()) { "Lot number must not be blank" }
        require(sequenceNumber.isNotBlank()) { "Sequence number must not be blank" }
        if (deactivatedAt != null) {
            require(!deactivatedAt.isBefore(activatedAt)) {
                "Deactivation time cannot be before activation time"
            }
        }
    }
}
