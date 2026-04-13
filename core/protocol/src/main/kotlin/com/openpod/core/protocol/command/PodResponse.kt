package com.openpod.core.protocol.command

/**
 * Sealed interface representing all responses received from an Omnipod 5 pod.
 *
 * Each subtype corresponds to a specific response message parsed from the
 * RHP binary protocol by [com.openpod.core.protocol.rhp.RhpCommandParser].
 *
 * **Privacy note:** Response objects contain clinically relevant data.
 * Logging implementations must redact glucose values, IOB, and other
 * protected health information (PHI). Only log response types and
 * non-sensitive metadata.
 */
sealed interface PodResponse {

    /**
     * Pod version and hardware identification, returned during activation.
     *
     * @property firmwareVersion Pod main firmware version (e.g., "2.7.0").
     * @property bleFirmwareVersion Pod BLE radio firmware version.
     * @property lotNumber Manufacturing lot number.
     * @property sequenceNumber Manufacturing sequence number.
     */
    data class VersionInfo(
        val firmwareVersion: String,
        val bleFirmwareVersion: String,
        val lotNumber: Long,
        val sequenceNumber: Long,
    ) : PodResponse

    /**
     * Current pod status snapshot.
     *
     * @property deliveryStatus Encoded delivery state (basal/bolus/suspended).
     * @property podState Encoded pod lifecycle state.
     * @property bolusRemaining Remaining bolus to deliver (units). Zero if no bolus active.
     * @property reservoirLevel Insulin remaining in reservoir (units). 50+ U reads as 50.0.
     * @property minutesSinceActivation Minutes elapsed since pod activation.
     * @property activeAlerts Bitmask of currently active alert slots.
     */
    data class StatusResponse(
        val deliveryStatus: Int,
        val podState: Int,
        val bolusRemaining: Double,
        val reservoirLevel: Double,
        val minutesSinceActivation: Int,
        val activeAlerts: Int,
    ) : PodResponse {
        /** True if the pod is actively delivering insulin. */
        val isDelivering: Boolean get() = deliveryStatus != DELIVERY_SUSPENDED

        /** True if the pod has an active bolus. */
        val hasActiveBolus: Boolean get() = bolusRemaining > 0.0

        companion object {
            /** Delivery status code indicating all delivery is suspended. */
            const val DELIVERY_SUSPENDED = 0x00

            /** Delivery status code indicating normal basal delivery. */
            const val DELIVERY_BASAL_ACTIVE = 0x01

            /** Delivery status code indicating temp basal active. */
            const val DELIVERY_TEMP_BASAL_ACTIVE = 0x02

            /** Delivery status code indicating bolus in progress. */
            const val DELIVERY_BOLUS_ACTIVE = 0x04

            /** Reservoir level value indicating ">50 U remaining" (exact level unknown). */
            const val RESERVOIR_ABOVE_THRESHOLD = 50.0
        }
    }

    /**
     * Bolus delivery progress update, received periodically during bolus delivery.
     *
     * @property delivered Units delivered so far.
     * @property remaining Units remaining to deliver.
     */
    data class BolusProgress(
        val delivered: Double,
        val remaining: Double,
    ) : PodResponse

    /**
     * AID algorithm status from the pod.
     *
     * @property algorithmState Encoded algorithm mode (active, limited, suspended).
     * @property cgmState Encoded CGM data availability state.
     * @property glucoseValue Latest glucose reading in mg/dL (0 if unavailable).
     * @property iob Insulin on board as calculated by the pod algorithm (units).
     */
    data class AidStatus(
        val algorithmState: Int,
        val cgmState: Int,
        val glucoseValue: Int,
        val iob: Double,
    ) : PodResponse

    /**
     * Error response from the pod indicating a command failure.
     *
     * @property errorCode Protocol-level error code.
     * @property faultCode Pod fault code (0 if no hardware fault).
     * @property description Human-readable error description.
     */
    data class ErrorResponse(
        val errorCode: Int,
        val faultCode: Int,
        val description: String,
    ) : PodResponse

    /**
     * Simple acknowledgment that a command was received and executed.
     *
     * @property commandId The sequence number of the acknowledged command.
     */
    data class Acknowledge(
        val commandId: Int,
    ) : PodResponse
}
