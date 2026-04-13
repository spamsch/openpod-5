package com.openpod.model.profile

import com.openpod.model.insulin.BasalProgram
import com.openpod.model.insulin.TimeSegment
import java.time.LocalTime

/**
 * Complete insulin therapy profile configured during onboarding.
 *
 * All values come from the user's prescription and must be verified with
 * their healthcare provider. The bolus calculator uses these parameters to
 * compute suggested doses.
 *
 * @property durationOfInsulinAction How long insulin stays active after a bolus (hours).
 * @property insulinType The rapid-acting insulin type the user is using.
 * @property icRatioSegments Insulin-to-carb ratio segments (grams per unit, by time of day).
 * @property correctionFactorSegments Correction factor segments (mg/dL per unit, by time of day).
 * @property targetGlucoseSegments Target glucose range segments (mg/dL, by time of day).
 * @property basalPrograms All configured basal programs.
 */
data class InsulinProfile(
    val durationOfInsulinAction: Double,
    val insulinType: InsulinType = InsulinType.OTHER,
    val icRatioSegments: List<TimeSegment<Int>>,
    val correctionFactorSegments: List<TimeSegment<Int>>,
    val targetGlucoseSegments: List<TimeSegment<TargetGlucoseRange>>,
    val basalPrograms: List<BasalProgram>,
) {
    init {
        require(durationOfInsulinAction in DIA_RANGE) {
            "DIA $durationOfInsulinAction hours outside valid range $DIA_RANGE"
        }
        require(icRatioSegments.isNotEmpty()) { "IC ratio must have at least one segment" }
        require(correctionFactorSegments.isNotEmpty()) { "Correction factor must have at least one segment" }
        require(targetGlucoseSegments.isNotEmpty()) { "Target glucose must have at least one segment" }
        require(basalPrograms.isNotEmpty()) { "At least one basal program is required" }
    }

    /** Get the IC ratio effective at a given time of day. */
    fun icRatioAt(time: LocalTime): Int =
        segmentAt(icRatioSegments, time)

    /** Get the correction factor effective at a given time of day. */
    fun correctionFactorAt(time: LocalTime): Int =
        segmentAt(correctionFactorSegments, time)

    /** Get the target glucose range effective at a given time of day. */
    fun targetGlucoseAt(time: LocalTime): TargetGlucoseRange =
        segmentAt(targetGlucoseSegments, time)

    /** Get the currently active basal program. */
    fun activeBasalProgram(): BasalProgram? =
        basalPrograms.firstOrNull { it.isActive }

    private fun <T> segmentAt(segments: List<TimeSegment<T>>, time: LocalTime): T {
        return segments.firstOrNull { segment ->
            if (segment.endTime > segment.startTime) {
                time >= segment.startTime && time < segment.endTime
            } else {
                // Wraps midnight
                time >= segment.startTime || time < segment.endTime
            }
        }?.value ?: segments.first().value
    }

    companion object {
        /** Valid DIA range: 2.0–5.0 hours in 0.5-hour increments. */
        val DIA_RANGE = 2.0..5.0

        /** Default DIA for rapid-acting insulin. */
        const val DEFAULT_DIA = 3.0

        /** IC ratio valid range: 1–150 grams per unit. */
        val IC_RATIO_RANGE = 1..150

        /** Correction factor valid range: 1–400 mg/dL per unit. */
        val CF_RANGE = 1..400

        /** Target glucose low valid range: 70–150 mg/dL. */
        val TARGET_LOW_RANGE = 70..150

        /** Target glucose high valid range: 80–200 mg/dL. */
        val TARGET_HIGH_RANGE = 80..200

        /** Maximum time segments for IC/CF settings. */
        const val MAX_IC_CF_SEGMENTS = 12

        /** Maximum time segments for target glucose. */
        const val MAX_TARGET_SEGMENTS = 8

        /** Maximum time segments for basal programs. */
        const val MAX_BASAL_SEGMENTS = 24
    }
}

/**
 * Target glucose range — the desired blood glucose band.
 * The bolus calculator uses these values to determine correction doses.
 *
 * @property lowMgDl Lower bound of target range (mg/dL).
 * @property highMgDl Upper bound of target range (mg/dL).
 */
data class TargetGlucoseRange(
    val lowMgDl: Int,
    val highMgDl: Int,
) {
    init {
        require(lowMgDl in InsulinProfile.TARGET_LOW_RANGE) {
            "Target low $lowMgDl outside range ${InsulinProfile.TARGET_LOW_RANGE}"
        }
        require(highMgDl in InsulinProfile.TARGET_HIGH_RANGE) {
            "Target high $highMgDl outside range ${InsulinProfile.TARGET_HIGH_RANGE}"
        }
        require(highMgDl >= lowMgDl) {
            "Target high ($highMgDl) must be ≥ low ($lowMgDl)"
        }
    }

    /** Midpoint of the target range, used by the bolus calculator. */
    val midpointMgDl: Int get() = (lowMgDl + highMgDl) / 2
}

/**
 * Common rapid-acting insulin types with recommended DIA values.
 * Used as presets in the onboarding DIA step.
 */
enum class InsulinType(val displayName: String, val defaultDiaHours: Double) {
    NOVORAPID("Novorapid / NovoLog", 3.0),
    HUMALOG("Humalog / Lispro", 3.0),
    APIDRA("Apidra / Glulisine", 3.0),
    FIASP("FIASP", 2.5),
    LYUMJEV("Lyumjev", 2.5),
    OTHER("Other", 3.0),
}
