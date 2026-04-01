package com.openpod.core.protocol.command

/**
 * Alert configuration for pod programming.
 *
 * @property alertIndex Slot index on the pod (0-7).
 * @property enabled Whether this alert is active.
 * @property durationMinutes How long the alert condition must persist before triggering.
 * @property autoOff Whether the alert silences itself after triggering.
 */
data class AlertConfig(
    val alertIndex: Int,
    val enabled: Boolean,
    val durationMinutes: Int,
    val autoOff: Boolean,
) {
    init {
        require(alertIndex in 0..7) { "Alert index must be 0-7, got $alertIndex" }
        require(durationMinutes >= 0) { "Duration must be non-negative, got $durationMinutes" }
    }
}

/**
 * A single segment in a basal rate schedule.
 *
 * Segments are contiguous half-hour slots that cover a 24-hour period.
 * The pod programs basal delivery in pulses: each pulse delivers 0.05 U.
 *
 * @property startSlot Half-hour slot index (0 = 00:00, 1 = 00:30, ..., 47 = 23:30).
 * @property endSlot Half-hour slot index (exclusive). Use 48 for end-of-day.
 * @property pulsesPerSlot Number of 0.05 U pulses to deliver per half-hour slot.
 */
data class BasalSegment(
    val startSlot: Int,
    val endSlot: Int,
    val pulsesPerSlot: Int,
) {
    init {
        require(startSlot in 0..47) { "Start slot must be 0-47, got $startSlot" }
        require(endSlot in 1..48) { "End slot must be 1-48, got $endSlot" }
        require(endSlot > startSlot) { "End slot must be after start slot" }
        require(pulsesPerSlot >= 0) { "Pulses per slot must be non-negative" }
    }

    /** Basal rate in U/hr for this segment (each pulse = 0.05 U, 2 slots/hr). */
    val rateUnitsPerHour: Double get() = pulsesPerSlot * PULSE_SIZE * SLOTS_PER_HOUR

    companion object {
        /** Each pump pulse delivers 0.05 U of insulin. */
        const val PULSE_SIZE = 0.05

        /** Two half-hour slots per hour. */
        const val SLOTS_PER_HOUR = 2
    }
}

/**
 * Type of delivery program to stop.
 */
enum class StopType {
    /** Stop basal delivery only. */
    BASAL,
    /** Stop temp basal only, resume scheduled basal. */
    TEMP_BASAL,
    /** Stop bolus delivery only. */
    BOLUS,
    /** Stop all insulin delivery (suspend). */
    ALL,
}

/**
 * Sealed interface representing all commands that can be sent to an Omnipod 5 pod.
 *
 * Each subtype encapsulates the parameters needed for a specific pod operation.
 * Commands are serialized to the RHP binary format by [com.openpod.core.protocol.rhp.RhpCommandBuilder]
 * before being sent over BLE.
 *
 * **Safety note:** Command parameters are validated at construction time to prevent
 * sending invalid instructions to the pod. The pod also validates commands and will
 * respond with an error if parameters are out of range.
 */
sealed interface PodCommand {

    /**
     * Query the pod for its firmware version and hardware identifiers.
     * This is typically the first command sent during activation.
     *
     * @property podId 4-byte pod identifier from BLE advertisement.
     */
    data class GetVersion(val podId: ByteArray) : PodCommand {
        init {
            require(podId.size == 4) { "Pod ID must be 4 bytes, got ${podId.size}" }
        }

        override fun equals(other: Any?): Boolean =
            other is GetVersion && podId.contentEquals(other.podId)

        override fun hashCode(): Int = podId.contentHashCode()
    }

    /**
     * Assign a unique identifier to the pod during activation.
     *
     * @property uid 4-byte unique ID to assign.
     * @property lotNumber Manufacturing lot number.
     * @property sequenceNumber Manufacturing sequence number.
     */
    data class SetUniqueId(
        val uid: ByteArray,
        val lotNumber: Long,
        val sequenceNumber: Long,
    ) : PodCommand {
        init {
            require(uid.size == 4) { "UID must be 4 bytes, got ${uid.size}" }
            require(lotNumber > 0) { "Lot number must be positive" }
            require(sequenceNumber > 0) { "Sequence number must be positive" }
        }

        override fun equals(other: Any?): Boolean =
            other is SetUniqueId && uid.contentEquals(other.uid) &&
                lotNumber == other.lotNumber && sequenceNumber == other.sequenceNumber

        override fun hashCode(): Int {
            var result = uid.contentHashCode()
            result = 31 * result + lotNumber.hashCode()
            result = 31 * result + sequenceNumber.hashCode()
            return result
        }
    }

    /**
     * Program alert configurations on the pod.
     *
     * @property alerts List of alert configurations to program (up to 8).
     */
    data class ProgramAlerts(val alerts: List<AlertConfig>) : PodCommand {
        init {
            require(alerts.isNotEmpty()) { "Must provide at least one alert configuration" }
            require(alerts.size <= 8) { "Pod supports a maximum of 8 alerts, got ${alerts.size}" }
        }
    }

    /**
     * Prime the pod by filling the cannula insertion mechanism.
     *
     * @property volume Priming volume in units.
     */
    data class PrimePod(val volume: Double) : PodCommand {
        init {
            require(volume > 0.0) { "Prime volume must be positive, got $volume" }
        }
    }

    /**
     * Program the scheduled basal delivery rate.
     *
     * @property segments Basal rate segments covering 24 hours.
     */
    data class ProgramBasal(val segments: List<BasalSegment>) : PodCommand {
        init {
            require(segments.isNotEmpty()) { "Must provide at least one basal segment" }
        }
    }

    /**
     * Insert the cannula by running the insertion motor.
     *
     * @property primeVolume Volume to deliver during cannula fill (units).
     */
    data class InsertCannula(val primeVolume: Double) : PodCommand {
        init {
            require(primeVolume > 0.0) { "Cannula prime volume must be positive" }
        }
    }

    /**
     * Deliver an immediate or extended bolus.
     *
     * @property units Bolus dose in insulin units (0.05 U increments).
     * @property delayed If true, deliver as an extended bolus.
     */
    data class SendBolus(val units: Double, val delayed: Boolean = false) : PodCommand {
        init {
            require(units > 0.0) { "Bolus must be > 0 U, got $units" }
            require(units <= MAX_BOLUS) { "Bolus $units U exceeds max $MAX_BOLUS U" }
        }

        companion object {
            /** Maximum single bolus supported by the pod hardware. */
            const val MAX_BOLUS = 30.0
        }
    }

    /**
     * Cancel an in-progress bolus delivery.
     *
     * @property bolusId Identifier of the bolus to cancel.
     */
    data class CancelBolus(val bolusId: Int) : PodCommand

    /**
     * Stop one or more active delivery programs.
     *
     * @property type Which delivery program(s) to stop.
     */
    data class StopProgram(val type: StopType) : PodCommand

    /**
     * Resume insulin delivery after a suspension.
     *
     * @property basalSegments The basal schedule to resume with.
     */
    data class ResumeInsulin(val basalSegments: List<BasalSegment>) : PodCommand {
        init {
            require(basalSegments.isNotEmpty()) { "Must provide basal segments to resume" }
        }
    }

    /**
     * Set a temporary basal rate.
     *
     * @property rate Temporary rate in U/hr.
     * @property durationMinutes Duration in minutes (must be a multiple of 30).
     */
    data class SendTempBasal(val rate: Double, val durationMinutes: Int) : PodCommand {
        init {
            require(rate >= 0.0) { "Temp basal rate must be non-negative" }
            require(durationMinutes > 0) { "Duration must be positive" }
            require(durationMinutes % 30 == 0) { "Duration must be a multiple of 30 minutes" }
            require(durationMinutes <= MAX_DURATION_MINUTES) {
                "Duration $durationMinutes exceeds max $MAX_DURATION_MINUTES minutes"
            }
        }

        companion object {
            /** Maximum temp basal duration: 12 hours. */
            const val MAX_DURATION_MINUTES = 720
        }
    }

    /**
     * Query the pod for its current status (delivery, reservoir, alerts, etc.).
     */
    data object GetStatus : PodCommand

    /**
     * Deactivate and discard the pod. This is irreversible.
     */
    data object Deactivate : PodCommand

    /**
     * Configure an AID (Automated Insulin Delivery) algorithm parameter.
     *
     * @property step Which AID configuration step this data corresponds to.
     * @property data Serialized parameter data for this step.
     */
    data class ConfigureAid(
        val step: com.openpod.core.protocol.activation.AidSetupStep,
        val data: ByteArray,
    ) : PodCommand {
        override fun equals(other: Any?): Boolean =
            other is ConfigureAid && step == other.step && data.contentEquals(other.data)

        override fun hashCode(): Int {
            var result = step.hashCode()
            result = 31 * result + data.contentHashCode()
            return result
        }
    }

    /**
     * Send a beep/confirmation tone to the pod.
     *
     * @property beepType Type of audible alert to play.
     */
    data class SendBeep(val beepType: Int) : PodCommand

    /**
     * Silence an active alert on the pod.
     *
     * @property alertMask Bitmask of alerts to silence.
     */
    data class SilenceAlert(val alertMask: Int) : PodCommand

    /**
     * Set the CGM transmitter ID for the pod's integrated CGM receiver.
     *
     * @property transmitterId Alphanumeric transmitter identifier.
     */
    data class CgmTransmitterId(val transmitterId: String) : PodCommand {
        init {
            require(transmitterId.isNotBlank()) { "Transmitter ID must not be blank" }
        }
    }
}
