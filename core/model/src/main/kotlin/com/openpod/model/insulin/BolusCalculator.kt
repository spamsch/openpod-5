package com.openpod.model.insulin

import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Bolus calculation settings — time-segment aware in the real app,
 * simplified to fixed values for the emulator dev flow.
 */
data class BolusSettings(
    val icRatio: Double = 15.0,
    val correctionFactor: Double = 50.0,
    val targetGlucose: Int = 110,
)

/**
 * Result of a bolus calculation showing the dose breakdown.
 */
data class BolusCalculation(
    val mealDose: Double,
    val correctionDose: Double,
    val iobDeduction: Double,
    val suggestedDose: Double,
)

/**
 * Simple bolus calculator matching the Omnipod 5 algorithm:
 *
 *     meal_dose = carbs / IC_ratio
 *     correction_dose = (glucose - target) / correction_factor
 *     suggested = meal_dose + correction_dose - IOB
 *     suggested = max(0, round_to_0.05)
 */
object BolusCalculator {

    fun calculate(
        carbsGrams: Int,
        glucoseMgDl: Int?,
        iobUnits: Double,
        settings: BolusSettings,
    ): BolusCalculation {
        val mealDose = if (carbsGrams > 0) carbsGrams / settings.icRatio else 0.0

        val correctionDose = if (glucoseMgDl != null && glucoseMgDl > settings.targetGlucose) {
            (glucoseMgDl - settings.targetGlucose) / settings.correctionFactor
        } else {
            0.0
        }

        val raw = mealDose + correctionDose - iobUnits
        val suggested = max(0.0, roundTo005(raw))

        return BolusCalculation(
            mealDose = roundTo005(mealDose),
            correctionDose = roundTo005(correctionDose),
            iobDeduction = roundTo005(iobUnits),
            suggestedDose = suggested,
        )
    }

    private fun roundTo005(value: Double): Double {
        return (value * 20).roundToInt() / 20.0
    }
}
