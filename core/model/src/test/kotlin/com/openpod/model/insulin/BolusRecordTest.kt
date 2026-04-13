package com.openpod.model.insulin

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Instant

/**
 * Tests for [BolusRecord] — tracks insulin bolus deliveries.
 *
 * These tests verify:
 * - Safety constraints (max dose, non-negative values)
 * - Delivery state tracking (in-progress vs completed)
 * - Duration estimation from pulse count
 */
class BolusRecordTest {

    private val now = Instant.parse("2026-03-30T12:00:00Z")

    private fun bolus(
        requested: Double = 3.0,
        delivered: Double = 3.0,
        completedAt: Instant? = now,
        cancelled: Boolean = false,
    ) = BolusRecord(
        id = "test-1",
        requestedUnits = requested,
        deliveredUnits = delivered,
        bolusType = BolusType.MEAL,
        carbsGrams = 45,
        glucoseMgDl = 142,
        startedAt = now.minusSeconds(180),
        completedAt = completedAt,
        cancelled = cancelled,
    )

    @Test
    fun `valid bolus is created successfully`() {
        val record = bolus()
        assertThat(record.requestedUnits).isEqualTo(3.0)
        assertThat(record.deliveredUnits).isEqualTo(3.0)
        assertThat(record.isInProgress).isFalse()
    }

    @Test
    fun `rejects bolus exceeding max single bolus`() {
        assertThrows<IllegalArgumentException> {
            bolus(requested = 31.0, delivered = 31.0)
        }
    }

    @Test
    fun `rejects zero unit bolus`() {
        assertThrows<IllegalArgumentException> {
            bolus(requested = 0.0, delivered = 0.0)
        }
    }

    @Test
    fun `rejects negative requested units`() {
        assertThrows<IllegalArgumentException> {
            bolus(requested = -1.0, delivered = 0.0)
        }
    }

    @Test
    fun `rejects delivered exceeding requested`() {
        assertThrows<IllegalArgumentException> {
            bolus(requested = 3.0, delivered = 3.1)
        }
    }

    @Test
    fun `bolus without completedAt is in progress`() {
        val record = bolus(delivered = 1.5, completedAt = null)
        assertThat(record.isInProgress).isTrue()
    }

    @Test
    fun `cancelled bolus is not in progress`() {
        val record = bolus(delivered = 1.5, completedAt = null, cancelled = true)
        assertThat(record.isInProgress).isFalse()
    }

    @Test
    fun `max bolus at boundary is accepted`() {
        val record = bolus(requested = 30.0, delivered = 30.0)
        assertThat(record.requestedUnits).isEqualTo(30.0)
    }

    @Test
    fun `minimum bolus is accepted`() {
        val record = bolus(requested = 0.05, delivered = 0.05)
        assertThat(record.requestedUnits).isEqualTo(0.05)
    }

    @Test
    fun `estimated duration scales with dose`() {
        val small = bolus(requested = 0.05, delivered = 0.05)
        val large = bolus(requested = 10.0, delivered = 10.0)
        assertThat(large.estimatedDuration).isGreaterThan(small.estimatedDuration)
    }

    @Test
    fun `estimated duration for 1U is approximately 60 seconds`() {
        val record = bolus(requested = 1.0, delivered = 1.0)
        // 1.0 U / 0.05 U per pulse = 20 pulses × 3 seconds = 60 seconds
        assertThat(record.estimatedDuration.seconds).isEqualTo(60)
    }
}
