package com.openpod.model.insulin

import java.time.Duration
import java.time.Instant
import java.time.LocalTime

/**
 * Insulin on Board — the estimated amount of active insulin in the body.
 *
 * IOB is calculated by the pod's algorithm (in Automatic Mode) or derived
 * from bolus history using the user's DIA curve (in Manual Mode). It is
 * used by the bolus calculator to prevent insulin stacking.
 *
 * @property totalUnits Total active insulin in units (U).
 * @property mealIob Portion from meal boluses.
 * @property correctionIob Portion from correction boluses.
 * @property algorithmIob Portion from algorithm micro-boluses (Automatic Mode only).
 * @property calculatedAt When this IOB value was computed.
 */
data class InsulinOnBoard(
    val totalUnits: Double,
    val mealIob: Double = 0.0,
    val correctionIob: Double = 0.0,
    val algorithmIob: Double = 0.0,
    val calculatedAt: Instant,
) {
    init {
        require(totalUnits >= 0.0) { "IOB cannot be negative: $totalUnits" }
    }

    /** True if IOB is decreasing (total is declining over time). */
    val isDecaying: Boolean get() = totalUnits > 0.0

    companion object {
        /** IOB with no active insulin. */
        fun zero(at: Instant = Instant.now()) = InsulinOnBoard(
            totalUnits = 0.0,
            calculatedAt = at,
        )
    }
}

/**
 * Record of a completed or in-progress bolus delivery.
 *
 * @property id Unique identifier for this bolus event.
 * @property requestedUnits The dose requested by the user.
 * @property deliveredUnits The dose actually delivered (may be less if cancelled).
 * @property bolusType Classification of this bolus.
 * @property carbsGrams Carbohydrates entered for this bolus (0 if correction-only).
 * @property glucoseMgDl Blood glucose at time of bolus (null if not available).
 * @property startedAt When delivery began.
 * @property completedAt When delivery finished (null if still in progress).
 * @property cancelled True if the user cancelled delivery mid-bolus.
 */
data class BolusRecord(
    val id: String,
    val requestedUnits: Double,
    val deliveredUnits: Double,
    val bolusType: BolusType,
    val carbsGrams: Int = 0,
    val glucoseMgDl: Int? = null,
    val startedAt: Instant,
    val completedAt: Instant? = null,
    val cancelled: Boolean = false,
) {
    init {
        require(requestedUnits > 0.0) { "Bolus must be > 0 U: $requestedUnits" }
        require(requestedUnits <= MAX_SINGLE_BOLUS) {
            "Bolus $requestedUnits U exceeds max $MAX_SINGLE_BOLUS U"
        }
        require(deliveredUnits >= 0.0) { "Delivered units cannot be negative" }
        require(deliveredUnits <= requestedUnits) {
            "Delivered ($deliveredUnits) cannot exceed requested ($requestedUnits)"
        }
    }

    /** True if delivery is still in progress. */
    val isInProgress: Boolean get() = completedAt == null && !cancelled

    /** Estimated delivery duration based on pulse rate. */
    val estimatedDuration: Duration
        get() = Duration.ofMillis((requestedUnits / PULSE_SIZE * SECONDS_PER_PULSE * 1000).toLong())

    companion object {
        /** Maximum single bolus allowed by the pod hardware. */
        const val MAX_SINGLE_BOLUS = 30.0

        /** Each pulse delivers 0.05 U. */
        const val PULSE_SIZE = 0.05

        /** Approximately 3 seconds per pulse. */
        const val SECONDS_PER_PULSE = 3.0
    }
}

/** Classification of a bolus based on the inputs that triggered it. */
enum class BolusType {
    /** Bolus with carbohydrate input (may also include correction). */
    MEAL,
    /** Bolus to correct high blood glucose (no carbs). */
    CORRECTION,
    /** Bolus with both carb and correction components. */
    MEAL_AND_CORRECTION,
    /** Manually entered dose without calculator assistance. */
    MANUAL,
}

/**
 * A time-segmented insulin setting value.
 *
 * Used for IC ratio, correction factor, target BG, and basal rate — all of which
 * can vary by time of day. Segments are contiguous and cover exactly 24 hours.
 *
 * @property startTime Start of this segment (inclusive).
 * @property endTime End of this segment (exclusive). 00:00 means midnight (end of day).
 * @property value The setting value for this time period.
 */
data class TimeSegment<T>(
    val startTime: LocalTime,
    val endTime: LocalTime,
    val value: T,
)

/**
 * A named basal insulin delivery program.
 *
 * Basal programs define background insulin delivery rates across the day.
 * Only used in Manual Mode — in Automatic Mode, the pod adjusts basal
 * delivery algorithmically based on CGM data.
 *
 * @property id Unique identifier.
 * @property name User-given name (e.g., "Weekday", "Weekend").
 * @property segments Time-segmented delivery rates in U/hr.
 * @property isActive True if this is the currently running program.
 */
data class BasalProgram(
    val id: String,
    val name: String,
    val segments: List<TimeSegment<Double>>,
    val isActive: Boolean = false,
) {
    init {
        require(name.isNotBlank()) { "Basal program name must not be blank" }
        require(name.length <= 20) { "Basal program name must be ≤ 20 characters" }
        require(segments.isNotEmpty()) { "Basal program must have at least one segment" }
        segments.forEach { segment ->
            require(segment.value in RATE_RANGE) {
                "Basal rate ${segment.value} U/hr outside valid range $RATE_RANGE"
            }
        }
    }

    /** Total daily basal dose computed from segment rates weighted by duration. */
    val totalDailyDose: Double
        get() = segments.sumOf { segment ->
            val hours = durationHours(segment.startTime, segment.endTime)
            segment.value * hours
        }

    companion object {
        /** Valid basal rate range per Omnipod 5 spec. */
        val RATE_RANGE = 0.05..30.0

        private fun durationHours(start: LocalTime, end: LocalTime): Double {
            val startMinutes = start.hour * 60 + start.minute
            val endMinutes = if (end == LocalTime.MIDNIGHT) 24 * 60 else end.hour * 60 + end.minute
            val minutes = if (endMinutes > startMinutes) endMinutes - startMinutes else (24 * 60 - startMinutes) + endMinutes
            return minutes / 60.0
        }
    }
}
