package com.openpod.model.pod

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Duration
import java.time.Instant

/**
 * Tests for [PodState] — the pod's runtime health and status.
 *
 * These tests verify:
 * - Pod lifetime calculation (80-hour expiry)
 * - Reservoir status classification
 * - Activity mode constraints and timing
 * - Edge cases at status boundaries
 */
class PodStateTest {

    private val activatedAt = Instant.parse("2026-03-28T08:00:00Z")

    private fun pod(
        reservoir: Double = 148.0,
        activatedAt: Instant = this.activatedAt,
    ) = PodState(
        uid = "A1B2C3D4",
        lotNumber = "12345678",
        sequenceNumber = "87654321",
        activatedAt = activatedAt,
        reservoirUnits = reservoir,
        connectionState = PodConnectionState.CONNECTED,
    )

    @Test
    fun `pod expires 80 hours after activation`() {
        val state = pod()
        val expected = activatedAt.plus(Duration.ofHours(80))
        assertThat(state.expiresAt).isEqualTo(expected)
    }

    @Test
    fun `pod is not expired within lifetime`() {
        val state = pod()
        val twentyHoursLater = activatedAt.plus(Duration.ofHours(20))
        assertThat(state.isExpired(twentyHoursLater)).isFalse()
    }

    @Test
    fun `pod is expired after lifetime`() {
        val state = pod()
        val eightyOneHoursLater = activatedAt.plus(Duration.ofHours(81))
        assertThat(state.isExpired(eightyOneHoursLater)).isTrue()
    }

    @Test
    fun `time remaining is positive when pod is active`() {
        val state = pod()
        val now = activatedAt.plus(Duration.ofHours(40))
        assertThat(state.timeRemaining(now).toHours()).isEqualTo(40)
    }

    @Test
    fun `time remaining is negative when pod is expired`() {
        val state = pod()
        val now = activatedAt.plus(Duration.ofHours(82))
        assertThat(state.timeRemaining(now).isNegative).isTrue()
    }

    @Test
    fun `reservoir status is normal above warning threshold`() {
        assertThat(pod(reservoir = 148.0).reservoirStatus()).isEqualTo(ReservoirStatus.NORMAL)
        assertThat(pod(reservoir = 10.0).reservoirStatus()).isEqualTo(ReservoirStatus.NORMAL)
    }

    @Test
    fun `reservoir status is low below warning threshold`() {
        assertThat(pod(reservoir = 9.9).reservoirStatus()).isEqualTo(ReservoirStatus.LOW)
        assertThat(pod(reservoir = 5.0).reservoirStatus()).isEqualTo(ReservoirStatus.LOW)
    }

    @Test
    fun `reservoir status is critical below critical threshold`() {
        assertThat(pod(reservoir = 4.9).reservoirStatus()).isEqualTo(ReservoirStatus.CRITICAL)
        assertThat(pod(reservoir = 0.0).reservoirStatus()).isEqualTo(ReservoirStatus.CRITICAL)
    }
}

class ActivityModeStateTest {

    private val startedAt = Instant.parse("2026-03-30T10:00:00Z")

    @Test
    fun `activity mode ends after selected duration`() {
        val mode = ActivityModeState(startedAt = startedAt, durationHours = 3)
        val expected = startedAt.plus(Duration.ofHours(3))
        assertThat(mode.endsAt).isEqualTo(expected)
    }

    @Test
    fun `activity mode is not expired within duration`() {
        val mode = ActivityModeState(startedAt = startedAt, durationHours = 3)
        val twoHoursLater = startedAt.plus(Duration.ofHours(2))
        assertThat(mode.isExpired(twoHoursLater)).isFalse()
    }

    @Test
    fun `activity mode is expired after duration`() {
        val mode = ActivityModeState(startedAt = startedAt, durationHours = 3)
        val fourHoursLater = startedAt.plus(Duration.ofHours(4))
        assertThat(mode.isExpired(fourHoursLater)).isTrue()
    }

    @Test
    fun `time remaining is zero when expired`() {
        val mode = ActivityModeState(startedAt = startedAt, durationHours = 1)
        val twoHoursLater = startedAt.plus(Duration.ofHours(2))
        assertThat(mode.timeRemaining(twoHoursLater)).isEqualTo(Duration.ZERO)
    }

    @Test
    fun `rejects duration below 1 hour`() {
        assertThrows<IllegalArgumentException> {
            ActivityModeState(startedAt = startedAt, durationHours = 0)
        }
    }

    @Test
    fun `rejects duration above 6 hours`() {
        assertThrows<IllegalArgumentException> {
            ActivityModeState(startedAt = startedAt, durationHours = 7)
        }
    }

    @Test
    fun `accepts all valid durations 1 through 6`() {
        (1..6).forEach { hours ->
            val mode = ActivityModeState(startedAt = startedAt, durationHours = hours)
            assertThat(mode.durationHours).isEqualTo(hours)
        }
    }

    @Test
    fun `glucose threshold is 150 mg per dL`() {
        assertThat(ActivityModeState.GLUCOSE_THRESHOLD_MG_DL).isEqualTo(150)
    }
}
