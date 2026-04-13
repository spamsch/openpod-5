package com.openpod.model.glucose

import java.time.Instant

/**
 * A single CGM glucose reading received from the pod.
 *
 * Glucose readings flow: Dexcom G7 → Pod → Phone (BLE). The pod relays
 * CGM data alongside its own telemetry, so readings are unavailable when
 * the pod is disconnected.
 *
 * All values are stored in mg/dL internally. Conversion to mmol/L
 * happens only at the display layer.
 *
 * @property valueMgDl Glucose value in mg/dL. Valid range: 20–500 mg/dL per FDA/Dexcom spec.
 * @property timestamp When the reading was taken by the CGM sensor.
 * @property trend Rate-of-change direction derived from the last 3 readings.
 * @property source Origin of this reading (CGM sensor or manual user entry).
 */
data class GlucoseReading(
    val valueMgDl: Int,
    val timestamp: Instant,
    val trend: GlucoseTrend = GlucoseTrend.UNKNOWN,
    val source: GlucoseSource = GlucoseSource.CGM,
) {
    init {
        require(valueMgDl in VALID_RANGE) {
            "Glucose value $valueMgDl mg/dL is outside valid range $VALID_RANGE"
        }
    }

    /** Classify this reading into a display range for color coding. */
    fun range(): GlucoseRange = GlucoseRange.fromMgDl(valueMgDl)

    /** True if this reading is older than [maxAgeSeconds] relative to [now]. */
    fun isStale(now: Instant, maxAgeSeconds: Long = STALE_THRESHOLD_SECONDS): Boolean =
        java.time.Duration.between(timestamp, now).seconds > maxAgeSeconds

    companion object {
        /** Valid glucose range per Dexcom G7 specification. */
        val VALID_RANGE = 20..500

        /** Readings older than 5 minutes are considered stale. */
        const val STALE_THRESHOLD_SECONDS = 300L

        /** Readings older than 15 minutes are considered missing. */
        const val MISSING_THRESHOLD_SECONDS = 900L
    }
}

/**
 * CGM trend arrow direction. Matches Dexcom G7 trend values.
 * Used for both display (arrow icons) and bolus calculation heuristics.
 */
enum class GlucoseTrend(val description: String) {
    RISING_QUICKLY("Rising quickly"),
    RISING("Rising"),
    STEADY("Steady"),
    FALLING("Falling"),
    FALLING_QUICKLY("Falling quickly"),
    UNKNOWN("Unknown"),
}

/** Source of a glucose value — either from the CGM sensor or entered manually by the user. */
enum class GlucoseSource {
    /** Received via CGM sensor through the pod. */
    CGM,
    /** Manually entered by the user (e.g., from a fingerstick meter). */
    MANUAL,
}

/**
 * Classification of glucose values into display ranges.
 *
 * Thresholds follow ADA (American Diabetes Association) guidelines:
 * - Urgent low: < 54 mg/dL
 * - Low: 54–69 mg/dL
 * - In range: 70–180 mg/dL
 * - High: 181–250 mg/dL
 * - Very high: > 250 mg/dL
 */
enum class GlucoseRange {
    URGENT_LOW,
    LOW,
    IN_RANGE,
    HIGH,
    VERY_HIGH;

    companion object {
        fun fromMgDl(value: Int): GlucoseRange = when {
            value < 54 -> URGENT_LOW
            value < 70 -> LOW
            value <= 180 -> IN_RANGE
            value <= 250 -> HIGH
            else -> VERY_HIGH
        }
    }
}
