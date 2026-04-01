package com.openpod.model.pod

import java.time.Duration
import java.time.Instant

/**
 * Complete runtime state of a paired Omnipod 5 pod.
 *
 * This is the single source of truth for pod health, synced from the pod
 * via BLE on connection and after each command. All fields reflect the last
 * known state — when disconnected, values become stale and should be
 * displayed with appropriate indicators.
 *
 * @property uid Pod unique identifier (hex string from BLE advertisement).
 * @property lotNumber Manufacturing lot number.
 * @property sequenceNumber Manufacturing sequence number.
 * @property firmwareVersion Pod firmware version string.
 * @property bleFirmwareVersion Pod BLE firmware version string.
 * @property activatedAt When the pod was activated (cannula inserted, delivery started).
 * @property reservoirUnits Insulin remaining in the pod reservoir.
 * @property connectionState Current BLE connection state.
 * @property operatingMode Current delivery mode (Manual or Automatic).
 * @property deliveryStatus Current delivery status.
 * @property activityMode Activity Mode state (null if not active).
 * @property activeAlerts Currently active pod alerts/alarms.
 * @property lastSyncAt When the pod last successfully communicated with the app.
 * @property site Body site where the pod is applied.
 */
data class PodState(
    val uid: String,
    val lotNumber: String,
    val sequenceNumber: String,
    val firmwareVersion: String = "",
    val bleFirmwareVersion: String = "",
    val activatedAt: Instant,
    val reservoirUnits: Double,
    val connectionState: PodConnectionState = PodConnectionState.DISCONNECTED,
    val operatingMode: OperatingMode = OperatingMode.AUTOMATIC,
    val deliveryStatus: DeliveryStatus = DeliveryStatus.NORMAL,
    val activityMode: ActivityModeState? = null,
    val activeAlerts: List<PodAlert> = emptyList(),
    val lastSyncAt: Instant? = null,
    val site: InfusionSite? = null,
) {
    /** Pod lifetime is 80 hours (3 days + 8 hours) from activation. */
    val expiresAt: Instant get() = activatedAt.plus(POD_LIFETIME)

    /** Time remaining until pod expiry. Negative if expired. */
    fun timeRemaining(now: Instant = Instant.now()): Duration =
        Duration.between(now, expiresAt)

    /** True if the pod has exceeded its 80-hour lifetime. */
    fun isExpired(now: Instant = Instant.now()): Boolean =
        now.isAfter(expiresAt)

    /** Reservoir status for display color coding. */
    fun reservoirStatus(): ReservoirStatus = when {
        reservoirUnits < LOW_RESERVOIR_CRITICAL -> ReservoirStatus.CRITICAL
        reservoirUnits < LOW_RESERVOIR_WARNING -> ReservoirStatus.LOW
        else -> ReservoirStatus.NORMAL
    }

    companion object {
        /** Pod maximum lifetime: 80 hours. */
        val POD_LIFETIME: Duration = Duration.ofHours(80)

        /** Reservoir capacity in units. */
        const val RESERVOIR_CAPACITY = 200.0

        /** Low reservoir warning threshold. */
        const val LOW_RESERVOIR_WARNING = 10.0

        /** Critical reservoir threshold. */
        const val LOW_RESERVOIR_CRITICAL = 5.0
    }
}

/** BLE connection state between the phone and the pod. */
enum class PodConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    RECONNECTING,
}

/** Pod operating mode — determines how basal insulin is managed. */
enum class OperatingMode {
    /** User-programmed basal rate schedule runs unmodified. */
    MANUAL,
    /** Pod algorithm adjusts basal delivery based on CGM data. */
    AUTOMATIC,
}

/** Current insulin delivery status. */
enum class DeliveryStatus {
    /** Normal basal delivery (and any active bolus). */
    NORMAL,
    /** All delivery suspended by user. */
    SUSPENDED,
    /** Bolus delivery in progress alongside basal. */
    BOLUS_IN_PROGRESS,
}

/** Reservoir level classification for UI display. */
enum class ReservoirStatus {
    NORMAL,
    LOW,
    CRITICAL,
}

/**
 * Activity Mode state — temporary mode where the pod reduces basal
 * delivery below a glucose threshold to prevent exercise-induced lows.
 *
 * @property startedAt When activity mode was activated.
 * @property durationHours Selected duration (1–6 hours).
 */
data class ActivityModeState(
    val startedAt: Instant,
    val durationHours: Int,
) {
    init {
        require(durationHours in 1..6) {
            "Activity mode duration must be 1–6 hours, got $durationHours"
        }
    }

    val endsAt: Instant get() = startedAt.plus(Duration.ofHours(durationHours.toLong()))

    fun isExpired(now: Instant = Instant.now()): Boolean = now.isAfter(endsAt)

    fun timeRemaining(now: Instant = Instant.now()): Duration =
        if (isExpired(now)) Duration.ZERO else Duration.between(now, endsAt)

    companion object {
        /** Pod does not deliver basal insulin below this threshold during Activity Mode. */
        const val GLUCOSE_THRESHOLD_MG_DL = 150
    }
}

/** Body site where the pod is applied. Recorded for site rotation tracking. */
enum class InfusionSite(val displayName: String) {
    ABDOMEN_LEFT("Left Abdomen"),
    ABDOMEN_RIGHT("Right Abdomen"),
    ARM_LEFT("Left Arm"),
    ARM_RIGHT("Right Arm"),
    THIGH_LEFT("Left Thigh"),
    THIGH_RIGHT("Right Thigh"),
    BACK_LEFT("Left Lower Back"),
    BACK_RIGHT("Right Lower Back"),
}

/** Pod alert or alarm condition. */
enum class PodAlert(val description: String, val isCritical: Boolean) {
    LOW_RESERVOIR("Low reservoir", isCritical = false),
    EXPIRATION_IMMINENT("Pod expiring soon", isCritical = false),
    EXPIRED("Pod expired", isCritical = true),
    OCCLUSION("Occlusion detected", isCritical = true),
    POD_FAULT("Pod fault", isCritical = true),
}
