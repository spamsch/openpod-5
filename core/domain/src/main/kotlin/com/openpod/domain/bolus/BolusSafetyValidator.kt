package com.openpod.domain.bolus

import com.openpod.domain.pod.PodManager
import com.openpod.model.insulin.BolusRecord
import java.time.Duration
import java.time.Instant
import kotlin.math.abs

/**
 * Pre-dispatch safety validator for bolus delivery.
 *
 * Runs immediately before [PodManager.sendBolus] to ensure every
 * precondition is met. If any check fails, delivery is blocked and
 * typed failure reasons are returned for user display and audit logging.
 *
 * Provided via Hilt in the feature layer — this is a pure Kotlin class
 * with no DI annotations because `core:domain` is a JVM library module.
 */
class BolusSafetyValidator(
    private val podManager: PodManager,
) {

    /**
     * Validate all preconditions for delivering a bolus of [requestedUnits].
     *
     * This must be called immediately before dispatch — not minutes earlier
     * at review time — because pod state can change between review and delivery.
     */
    suspend fun validate(requestedUnits: Double): ValidationResult {
        val failures = mutableListOf<SafetyFailure>()

        // Dose range check
        if (requestedUnits < MIN_DOSE) {
            failures += SafetyFailure.DoseBelowMinimum(requestedUnits, MIN_DOSE)
        }
        if (requestedUnits > MAX_DOSE) {
            failures += SafetyFailure.DoseAboveMaximum(requestedUnits, MAX_DOSE)
        }

        // Pulse alignment check (dose must be a multiple of 0.05 U)
        if (!isPulseAligned(requestedUnits)) {
            failures += SafetyFailure.DoseNotPulseAligned(requestedUnits, PULSE_SIZE)
        }

        // Pod status check — all remaining checks require a fresh status
        val statusResult = podManager.getStatus()
        if (statusResult.isFailure) {
            failures += SafetyFailure.PodNotReachable
            return ValidationResult.Failed(failures)
        }

        val status = statusResult.getOrThrow()

        if (!status.isActivated) {
            failures += SafetyFailure.PodNotActivated
        }

        if (status.expiresAt.isBefore(Instant.now())) {
            failures += SafetyFailure.PodExpired(status.expiresAt)
        }

        if (status.reservoir < requestedUnits) {
            failures += SafetyFailure.InsufficientReservoir(
                requested = requestedUnits,
                available = status.reservoir,
            )
        }

        if (status.bolusInProgress) {
            failures += SafetyFailure.BolusAlreadyInProgress(
                remainingUnits = status.bolusRemainingUnits,
            )
        }

        return if (failures.isEmpty()) {
            ValidationResult.Passed(statusSnapshotTimestamp = Instant.now())
        } else {
            ValidationResult.Failed(failures)
        }
    }

    companion object {
        const val MIN_DOSE = BolusRecord.PULSE_SIZE // 0.05 U
        const val MAX_DOSE = BolusRecord.MAX_SINGLE_BOLUS // 30.0 U
        const val PULSE_SIZE = BolusRecord.PULSE_SIZE // 0.05 U
        val MAX_STATUS_AGE: Duration = Duration.ofSeconds(30)

        fun isPulseAligned(units: Double): Boolean {
            val pulses = units / PULSE_SIZE
            return abs(pulses - pulses.toLong()) < 0.001
        }
    }
}

/** Result of pre-dispatch safety validation. */
sealed interface ValidationResult {
    data class Passed(val statusSnapshotTimestamp: Instant) : ValidationResult
    data class Failed(val failures: List<SafetyFailure>) : ValidationResult
}

/** Individual safety check failure with typed context for UI and audit. */
sealed interface SafetyFailure {
    data class DoseBelowMinimum(val requested: Double, val minimum: Double) : SafetyFailure
    data class DoseAboveMaximum(val requested: Double, val maximum: Double) : SafetyFailure
    data class DoseNotPulseAligned(val requested: Double, val pulseSize: Double) : SafetyFailure
    data object PodNotReachable : SafetyFailure
    data object PodNotActivated : SafetyFailure
    data class PodExpired(val expiresAt: Instant) : SafetyFailure
    data class InsufficientReservoir(val requested: Double, val available: Double) : SafetyFailure
    data class BolusAlreadyInProgress(val remainingUnits: Double) : SafetyFailure
}
