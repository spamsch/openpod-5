package com.openpod.domain.bolus

import com.google.common.truth.Truth.assertThat
import com.openpod.domain.pod.ActivationProgress
import com.openpod.domain.pod.DiscoveredPod
import com.openpod.domain.pod.PodActivationResult
import com.openpod.domain.pod.PodManager
import com.openpod.domain.pod.PrimeProgress
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * Tests for [BolusSafetyValidator] — pre-dispatch safety gate for bolus delivery.
 *
 * Uses a simple fake PodManager to avoid MockK's `Result` return type issues.
 * Each test verifies a single safety check; the validator must block delivery
 * when any precondition is unmet.
 */
class BolusSafetyValidatorTest {

    private val healthyStatus = PodActivationResult(
        uid = "pod-abc",
        reservoir = 100.0,
        expiresAt = Instant.now().plusSeconds(3600),
        firmwareVersion = "1.0.0",
        isActivated = true,
        bolusInProgress = false,
        bolusTotalUnits = 0.0,
        bolusRemainingUnits = 0.0,
    )

    private class FakePodManager(
        var statusResult: Result<PodActivationResult>,
    ) : PodManager {
        override suspend fun startScan(): Flow<DiscoveredPod> = emptyFlow()
        override suspend fun stopScan() {}
        override suspend fun connect(podId: String) = Result.success(Unit)
        override suspend fun authenticate() = Result.success(Unit)
        override suspend fun prime(): Flow<PrimeProgress> = emptyFlow()
        override suspend fun insertCannula(): Flow<ActivationProgress> = emptyFlow()
        override suspend fun getStatus(): Result<PodActivationResult> = statusResult
        override suspend fun sendBolus(units: Double) = Result.success(Unit)
        override suspend fun cancelBolus() = Result.success(Unit)
        override suspend fun deactivate() = Result.success(Unit)
    }

    private fun validatorWith(status: Result<PodActivationResult> = Result.success(healthyStatus)): BolusSafetyValidator {
        return BolusSafetyValidator(FakePodManager(status))
    }

    @Test
    fun `valid bolus passes all checks`() = runTest {
        val result = validatorWith().validate(3.0)
        assertThat(result).isInstanceOf(ValidationResult.Passed::class.java)
    }

    @Test
    fun `dose below minimum is rejected`() = runTest {
        val result = validatorWith().validate(0.01)
        assertThat(result).isInstanceOf(ValidationResult.Failed::class.java)
        val failures = (result as ValidationResult.Failed).failures
        assertThat(failures.any { it is SafetyFailure.DoseBelowMinimum }).isTrue()
    }

    @Test
    fun `dose above maximum is rejected`() = runTest {
        val result = validatorWith().validate(31.0)
        assertThat(result).isInstanceOf(ValidationResult.Failed::class.java)
        val failures = (result as ValidationResult.Failed).failures
        assertThat(failures).hasSize(1)
        assertThat(failures[0]).isInstanceOf(SafetyFailure.DoseAboveMaximum::class.java)
    }

    @Test
    fun `dose not pulse aligned is rejected`() = runTest {
        val result = validatorWith().validate(3.03)
        assertThat(result).isInstanceOf(ValidationResult.Failed::class.java)
        val failures = (result as ValidationResult.Failed).failures
        assertThat(failures.any { it is SafetyFailure.DoseNotPulseAligned }).isTrue()
    }

    @Test
    fun `pulse-aligned doses are accepted`() = runTest {
        val validator = validatorWith()
        for (units in listOf(0.05, 0.10, 0.50, 1.0, 3.25, 30.0)) {
            val result = validator.validate(units)
            assertThat(result).isInstanceOf(ValidationResult.Passed::class.java)
        }
    }

    @Test
    fun `pod not reachable returns failure`() = runTest {
        val result = validatorWith(Result.failure(RuntimeException("BLE timeout"))).validate(3.0)
        assertThat(result).isInstanceOf(ValidationResult.Failed::class.java)
        val failures = (result as ValidationResult.Failed).failures
        assertThat(failures.any { it is SafetyFailure.PodNotReachable }).isTrue()
    }

    @Test
    fun `pod not activated is rejected`() = runTest {
        val result = validatorWith(Result.success(healthyStatus.copy(isActivated = false))).validate(3.0)
        assertThat(result).isInstanceOf(ValidationResult.Failed::class.java)
        val failures = (result as ValidationResult.Failed).failures
        assertThat(failures.any { it is SafetyFailure.PodNotActivated }).isTrue()
    }

    @Test
    fun `expired pod is rejected`() = runTest {
        val result = validatorWith(
            Result.success(healthyStatus.copy(expiresAt = Instant.now().minusSeconds(3600))),
        ).validate(3.0)
        assertThat(result).isInstanceOf(ValidationResult.Failed::class.java)
        val failures = (result as ValidationResult.Failed).failures
        assertThat(failures.any { it is SafetyFailure.PodExpired }).isTrue()
    }

    @Test
    fun `insufficient reservoir is rejected`() = runTest {
        val result = validatorWith(Result.success(healthyStatus.copy(reservoir = 2.0))).validate(3.0)
        assertThat(result).isInstanceOf(ValidationResult.Failed::class.java)
        val failures = (result as ValidationResult.Failed).failures
        val failure = failures.filterIsInstance<SafetyFailure.InsufficientReservoir>().single()
        assertThat(failure.requested).isEqualTo(3.0)
        assertThat(failure.available).isEqualTo(2.0)
    }

    @Test
    fun `bolus already in progress is rejected`() = runTest {
        val result = validatorWith(
            Result.success(healthyStatus.copy(bolusInProgress = true, bolusRemainingUnits = 1.5)),
        ).validate(3.0)
        assertThat(result).isInstanceOf(ValidationResult.Failed::class.java)
        val failures = (result as ValidationResult.Failed).failures
        assertThat(failures.any { it is SafetyFailure.BolusAlreadyInProgress }).isTrue()
    }

    @Test
    fun `multiple failures are accumulated`() = runTest {
        val result = validatorWith(
            Result.success(
                healthyStatus.copy(
                    isActivated = false,
                    reservoir = 1.0,
                    bolusInProgress = true,
                    bolusRemainingUnits = 2.0,
                ),
            ),
        ).validate(5.0)
        assertThat(result).isInstanceOf(ValidationResult.Failed::class.java)
        val failures = (result as ValidationResult.Failed).failures
        assertThat(failures.size).isAtLeast(3)
    }

    @Test
    fun `minimum valid dose passes`() = runTest {
        val result = validatorWith().validate(0.05)
        assertThat(result).isInstanceOf(ValidationResult.Passed::class.java)
    }

    @Test
    fun `maximum valid dose passes`() = runTest {
        val result = validatorWith().validate(30.0)
        assertThat(result).isInstanceOf(ValidationResult.Passed::class.java)
    }

    @Test
    fun `isPulseAligned utility works correctly`() {
        assertThat(BolusSafetyValidator.isPulseAligned(0.05)).isTrue()
        assertThat(BolusSafetyValidator.isPulseAligned(0.10)).isTrue()
        assertThat(BolusSafetyValidator.isPulseAligned(1.25)).isTrue()
        assertThat(BolusSafetyValidator.isPulseAligned(3.03)).isFalse()
        assertThat(BolusSafetyValidator.isPulseAligned(0.07)).isFalse()
    }
}
