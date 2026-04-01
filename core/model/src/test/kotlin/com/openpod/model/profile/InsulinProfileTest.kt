package com.openpod.model.profile

import com.google.common.truth.Truth.assertThat
import com.openpod.model.insulin.BasalProgram
import com.openpod.model.insulin.TimeSegment
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.time.LocalTime

/**
 * Tests for [InsulinProfile] — the user's complete therapy configuration.
 *
 * These tests verify:
 * - DIA validation (2.0–5.0 hours)
 * - Time-segment lookup for IC ratio, correction factor, target glucose
 * - Target glucose range constraints (high ≥ low)
 * - Basal program validation
 */
class InsulinProfileTest {

    private val allDay = listOf(
        TimeSegment(LocalTime.MIDNIGHT, LocalTime.MIDNIGHT, 10)
    )

    private val allDayCf = listOf(
        TimeSegment(LocalTime.MIDNIGHT, LocalTime.MIDNIGHT, 40)
    )

    private val allDayTarget = listOf(
        TimeSegment(
            LocalTime.MIDNIGHT,
            LocalTime.MIDNIGHT,
            TargetGlucoseRange(lowMgDl = 80, highMgDl = 120),
        )
    )

    private val defaultBasal = listOf(
        BasalProgram(
            id = "1",
            name = "Program 1",
            segments = listOf(TimeSegment(LocalTime.MIDNIGHT, LocalTime.MIDNIGHT, 0.8)),
            isActive = true,
        )
    )

    private fun profile(dia: Double = 3.0) = InsulinProfile(
        durationOfInsulinAction = dia,
        icRatioSegments = allDay,
        correctionFactorSegments = allDayCf,
        targetGlucoseSegments = allDayTarget,
        basalPrograms = defaultBasal,
    )

    @ParameterizedTest(name = "DIA {0} hours is valid")
    @ValueSource(doubles = [2.0, 2.5, 3.0, 3.5, 4.0, 4.5, 5.0])
    fun `accepts valid DIA values`(dia: Double) {
        val p = profile(dia = dia)
        assertThat(p.durationOfInsulinAction).isEqualTo(dia)
    }

    @ParameterizedTest(name = "DIA {0} hours is invalid")
    @ValueSource(doubles = [0.0, 1.0, 1.9, 5.1, 10.0])
    fun `rejects DIA outside valid range`(dia: Double) {
        assertThrows<IllegalArgumentException> { profile(dia = dia) }
    }

    @Test
    fun `IC ratio lookup returns value for all-day segment`() {
        val p = profile()
        assertThat(p.icRatioAt(LocalTime.NOON)).isEqualTo(10)
        assertThat(p.icRatioAt(LocalTime.MIDNIGHT)).isEqualTo(10)
    }

    @Test
    fun `IC ratio lookup with multiple segments returns correct value`() {
        val morningIc = TimeSegment(LocalTime.MIDNIGHT, LocalTime.NOON, 8)
        val afternoonIc = TimeSegment(LocalTime.NOON, LocalTime.MIDNIGHT, 12)

        val p = profile().copy(icRatioSegments = listOf(morningIc, afternoonIc))
        assertThat(p.icRatioAt(LocalTime.of(6, 0))).isEqualTo(8)
        assertThat(p.icRatioAt(LocalTime.of(14, 0))).isEqualTo(12)
    }

    @Test
    fun `correction factor lookup returns value`() {
        val p = profile()
        assertThat(p.correctionFactorAt(LocalTime.NOON)).isEqualTo(40)
    }

    @Test
    fun `target glucose lookup returns range`() {
        val p = profile()
        val target = p.targetGlucoseAt(LocalTime.NOON)
        assertThat(target.lowMgDl).isEqualTo(80)
        assertThat(target.highMgDl).isEqualTo(120)
    }

    @Test
    fun `active basal program is found`() {
        val p = profile()
        assertThat(p.activeBasalProgram()?.name).isEqualTo("Program 1")
    }

    @Test
    fun `target glucose range midpoint is calculated`() {
        val target = TargetGlucoseRange(lowMgDl = 80, highMgDl = 120)
        assertThat(target.midpointMgDl).isEqualTo(100)
    }

    @Test
    fun `target glucose rejects high below low`() {
        assertThrows<IllegalArgumentException> {
            TargetGlucoseRange(lowMgDl = 120, highMgDl = 80)
        }
    }

    @Test
    fun `target glucose accepts equal low and high`() {
        val target = TargetGlucoseRange(lowMgDl = 100, highMgDl = 100)
        assertThat(target.midpointMgDl).isEqualTo(100)
    }

    @Test
    fun `insulin types have correct default DIA`() {
        assertThat(InsulinType.NOVORAPID.defaultDiaHours).isEqualTo(3.0)
        assertThat(InsulinType.FIASP.defaultDiaHours).isEqualTo(2.5)
        assertThat(InsulinType.LYUMJEV.defaultDiaHours).isEqualTo(2.5)
    }
}
