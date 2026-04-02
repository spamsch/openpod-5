package com.openpod.model.insulin

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * Tests for [BolusCalculator] — the Omnipod 5 bolus dose calculator.
 *
 * Verifies:
 * - Meal bolus calculation from carbs and IC ratio
 * - Correction bolus from glucose above target
 * - IOB deduction preventing insulin stacking
 * - Pulse rounding to 0.05 U increments
 * - Edge cases (zero carbs, no glucose, negative results)
 */
class BolusCalculatorTest {

    private val defaultSettings = BolusSettings(
        icRatio = 15.0,
        correctionFactor = 50.0,
        targetGlucose = 110,
    )

    @Test
    fun `meal bolus with no correction`() {
        val calc = BolusCalculator.calculate(
            carbsGrams = 45,
            glucoseMgDl = null,
            iobUnits = 0.0,
            settings = defaultSettings,
        )
        // 45 / 15 = 3.0 U
        assertThat(calc.mealDose).isEqualTo(3.0)
        assertThat(calc.correctionDose).isEqualTo(0.0)
        assertThat(calc.suggestedDose).isEqualTo(3.0)
    }

    @Test
    fun `correction bolus with no carbs`() {
        val calc = BolusCalculator.calculate(
            carbsGrams = 0,
            glucoseMgDl = 210,
            iobUnits = 0.0,
            settings = defaultSettings,
        )
        // (210 - 110) / 50 = 2.0 U
        assertThat(calc.mealDose).isEqualTo(0.0)
        assertThat(calc.correctionDose).isEqualTo(2.0)
        assertThat(calc.suggestedDose).isEqualTo(2.0)
    }

    @Test
    fun `combined meal and correction bolus`() {
        val calc = BolusCalculator.calculate(
            carbsGrams = 30,
            glucoseMgDl = 160,
            iobUnits = 0.0,
            settings = defaultSettings,
        )
        // meal: 30/15 = 2.0, correction: (160-110)/50 = 1.0, total: 3.0
        assertThat(calc.mealDose).isEqualTo(2.0)
        assertThat(calc.correctionDose).isEqualTo(1.0)
        assertThat(calc.suggestedDose).isEqualTo(3.0)
    }

    @Test
    fun `IOB is deducted from suggested dose`() {
        val calc = BolusCalculator.calculate(
            carbsGrams = 45,
            glucoseMgDl = null,
            iobUnits = 1.5,
            settings = defaultSettings,
        )
        // meal: 3.0 - IOB: 1.5 = 1.5
        assertThat(calc.suggestedDose).isEqualTo(1.5)
        assertThat(calc.iobDeduction).isEqualTo(1.5)
    }

    @Test
    fun `IOB larger than dose floors at zero`() {
        val calc = BolusCalculator.calculate(
            carbsGrams = 15,
            glucoseMgDl = null,
            iobUnits = 5.0,
            settings = defaultSettings,
        )
        // meal: 1.0 - IOB: 5.0 = -4.0 → floored to 0.0
        assertThat(calc.suggestedDose).isEqualTo(0.0)
    }

    @Test
    fun `glucose at or below target produces no correction`() {
        val calc = BolusCalculator.calculate(
            carbsGrams = 0,
            glucoseMgDl = 110,
            iobUnits = 0.0,
            settings = defaultSettings,
        )
        assertThat(calc.correctionDose).isEqualTo(0.0)
        assertThat(calc.suggestedDose).isEqualTo(0.0)
    }

    @Test
    fun `glucose below target produces no correction`() {
        val calc = BolusCalculator.calculate(
            carbsGrams = 0,
            glucoseMgDl = 80,
            iobUnits = 0.0,
            settings = defaultSettings,
        )
        assertThat(calc.correctionDose).isEqualTo(0.0)
    }

    @Test
    fun `result is rounded to 0_05 U increments`() {
        // 47 / 15 = 3.1333... → rounds to 3.15
        val calc = BolusCalculator.calculate(
            carbsGrams = 47,
            glucoseMgDl = null,
            iobUnits = 0.0,
            settings = defaultSettings,
        )
        assertThat(calc.suggestedDose).isEqualTo(3.15)
    }

    @Test
    fun `zero carbs and no glucose returns zero suggested`() {
        val calc = BolusCalculator.calculate(
            carbsGrams = 0,
            glucoseMgDl = null,
            iobUnits = 0.0,
            settings = defaultSettings,
        )
        assertThat(calc.suggestedDose).isEqualTo(0.0)
    }

    @Test
    fun `custom settings are respected`() {
        val settings = BolusSettings(icRatio = 10.0, correctionFactor = 30.0, targetGlucose = 100)
        val calc = BolusCalculator.calculate(
            carbsGrams = 30,
            glucoseMgDl = 160,
            iobUnits = 0.0,
            settings = settings,
        )
        // meal: 30/10 = 3.0, correction: (160-100)/30 = 2.0, total: 5.0
        assertThat(calc.suggestedDose).isEqualTo(5.0)
    }
}
