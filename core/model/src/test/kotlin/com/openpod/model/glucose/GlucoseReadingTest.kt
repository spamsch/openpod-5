package com.openpod.model.glucose

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import java.time.Instant

/**
 * Tests for [GlucoseReading] — the fundamental glucose data model.
 *
 * These tests verify:
 * - Validation constraints (FDA/Dexcom spec range: 20–500 mg/dL)
 * - Range classification (ADA guidelines)
 * - Staleness detection
 * - Edge cases at range boundaries
 */
class GlucoseReadingTest {

    private val now = Instant.parse("2026-03-30T12:00:00Z")

    @Test
    fun `valid reading is created successfully`() {
        val reading = GlucoseReading(
            valueMgDl = 120,
            timestamp = now,
            trend = GlucoseTrend.STEADY,
            source = GlucoseSource.CGM,
        )
        assertThat(reading.valueMgDl).isEqualTo(120)
        assertThat(reading.trend).isEqualTo(GlucoseTrend.STEADY)
        assertThat(reading.source).isEqualTo(GlucoseSource.CGM)
    }

    @ParameterizedTest(name = "glucose {0} mg/dL is outside valid range")
    @ValueSource(ints = [0, 19, 501, 1000, -1, Int.MAX_VALUE])
    fun `rejects glucose values outside valid range`(value: Int) {
        assertThrows<IllegalArgumentException> {
            GlucoseReading(valueMgDl = value, timestamp = now)
        }
    }

    @ParameterizedTest(name = "glucose {0} mg/dL is within valid range")
    @ValueSource(ints = [20, 21, 100, 250, 499, 500])
    fun `accepts glucose values within valid range`(value: Int) {
        val reading = GlucoseReading(valueMgDl = value, timestamp = now)
        assertThat(reading.valueMgDl).isEqualTo(value)
    }

    @ParameterizedTest(name = "glucose {0} mg/dL = {1}")
    @CsvSource(
        "20, URGENT_LOW",
        "53, URGENT_LOW",
        "54, LOW",
        "69, LOW",
        "70, IN_RANGE",
        "120, IN_RANGE",
        "180, IN_RANGE",
        "181, HIGH",
        "250, HIGH",
        "251, VERY_HIGH",
        "500, VERY_HIGH",
    )
    fun `classifies glucose ranges per ADA guidelines`(value: Int, expectedRange: GlucoseRange) {
        val reading = GlucoseReading(valueMgDl = value, timestamp = now)
        assertThat(reading.range()).isEqualTo(expectedRange)
    }

    @Test
    fun `reading is not stale within threshold`() {
        val fourMinutesAgo = now.minusSeconds(240)
        val reading = GlucoseReading(valueMgDl = 120, timestamp = fourMinutesAgo)
        assertThat(reading.isStale(now)).isFalse()
    }

    @Test
    fun `reading is stale after threshold`() {
        val sixMinutesAgo = now.minusSeconds(360)
        val reading = GlucoseReading(valueMgDl = 120, timestamp = sixMinutesAgo)
        assertThat(reading.isStale(now)).isTrue()
    }

    @Test
    fun `reading at exactly threshold boundary is not stale`() {
        val exactlyFiveMinutesAgo = now.minusSeconds(300)
        val reading = GlucoseReading(valueMgDl = 120, timestamp = exactlyFiveMinutesAgo)
        assertThat(reading.isStale(now)).isFalse()
    }

    @Test
    fun `custom staleness threshold is respected`() {
        val twoMinutesAgo = now.minusSeconds(120)
        val reading = GlucoseReading(valueMgDl = 120, timestamp = twoMinutesAgo)
        assertThat(reading.isStale(now, maxAgeSeconds = 60)).isTrue()
        assertThat(reading.isStale(now, maxAgeSeconds = 180)).isFalse()
    }

    @Test
    fun `default trend is UNKNOWN`() {
        val reading = GlucoseReading(valueMgDl = 120, timestamp = now)
        assertThat(reading.trend).isEqualTo(GlucoseTrend.UNKNOWN)
    }

    @Test
    fun `default source is CGM`() {
        val reading = GlucoseReading(valueMgDl = 120, timestamp = now)
        assertThat(reading.source).isEqualTo(GlucoseSource.CGM)
    }

    @Test
    fun `manual glucose source is preserved`() {
        val reading = GlucoseReading(
            valueMgDl = 120,
            timestamp = now,
            source = GlucoseSource.MANUAL,
        )
        assertThat(reading.source).isEqualTo(GlucoseSource.MANUAL)
    }
}
