package com.openpod.feature.pairing.domain

import com.openpod.domain.pod.ActivationProgress
import com.openpod.domain.pod.DiscoveredPod
import com.openpod.domain.pod.PodActivationResult
import com.openpod.domain.pod.PodManager
import com.openpod.domain.pod.PrimeProgress
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import timber.log.Timber
import java.time.Instant
import javax.inject.Inject

/**
 * Fake [PodManager] implementation for UI development and testing.
 *
 * Simulates realistic timing for each phase of the Omnipod 5 pairing
 * protocol without requiring BLE hardware. All operations succeed after
 * appropriate delays to match the expected user experience.
 *
 * Timing approximations:
 * - Scan: 2 seconds to discover a pod
 * - Connect: 1 second for BLE GATT connection
 * - Authenticate: 2 seconds for ECDH + EAP-AKA simulation
 * - Prime: 5 seconds for cannula fill
 * - Insert cannula: 3 seconds for second prime + delivery start
 */
class FakePodManager @Inject constructor() : PodManager {

    override suspend fun startScan(): Flow<DiscoveredPod> = flow {
        Timber.d("FakePodManager: Starting simulated BLE scan")
        delay(SCAN_DELAY_MS)
        val pod = DiscoveredPod(
            id = "A1B2C3D4",
            rssi = -45,
            name = "Omnipod 5",
        )
        Timber.d("FakePodManager: Discovered pod %s (RSSI: %d)", pod.id, pod.rssi)
        emit(pod)
    }

    override suspend fun stopScan() {
        Timber.d("FakePodManager: Scan stopped")
    }

    override suspend fun connect(podId: String): Result<Unit> {
        Timber.d("FakePodManager: Connecting to pod %s", podId)
        require(podId.isNotBlank()) { "Pod ID must not be blank" }
        delay(CONNECT_DELAY_MS)
        Timber.d("FakePodManager: Connected to pod %s", podId)
        return Result.success(Unit)
    }

    override suspend fun authenticate(): Result<Unit> {
        Timber.d("FakePodManager: Starting EAP-AKA authentication simulation")
        delay(AUTH_DELAY_MS)
        Timber.d("FakePodManager: Authentication complete, AES-CCM session established")
        return Result.success(Unit)
    }

    override suspend fun prime(): Flow<PrimeProgress> = flow {
        Timber.d("FakePodManager: Starting prime simulation")
        val steps = PRIME_STEPS
        for (i in 1..steps) {
            delay(PRIME_DURATION_MS / steps)
            val percent = i.toFloat() / steps
            val isComplete = i == steps
            emit(PrimeProgress(percent = percent, isComplete = isComplete))
            if (isComplete) {
                Timber.d("FakePodManager: Priming complete")
            }
        }
    }

    override suspend fun insertCannula(): Flow<ActivationProgress> = flow {
        Timber.d("FakePodManager: Starting cannula insertion simulation")
        val substeps = listOf(
            "Pod started",
            "Second prime complete",
            "Algorithm enabled",
            "Final status verified",
        )
        for ((index, step) in substeps.withIndex()) {
            delay(ACTIVATION_DURATION_MS / substeps.size)
            val percent = (index + 1).toFloat() / substeps.size
            val isComplete = index == substeps.lastIndex
            emit(ActivationProgress(percent = percent, step = step, isComplete = isComplete))
            Timber.d("FakePodManager: Activation step: %s (%.0f%%)", step, percent * 100)
        }
    }

    override suspend fun getStatus(): Result<PodActivationResult> {
        Timber.d("FakePodManager: Querying pod status")
        delay(STATUS_DELAY_MS)
        val result = PodActivationResult(
            uid = "A1B2C3D4",
            reservoir = 198.0,
            expiresAt = Instant.now().plusSeconds(80 * 3600),
            firmwareVersion = "3.1.6",
        )
        Timber.d("FakePodManager: Pod status — reservoir=%.1f, expires=%s", result.reservoir, result.expiresAt)
        return Result.success(result)
    }

    override suspend fun sendBolus(units: Double): Result<Unit> {
        Timber.d("FakePodManager: Sending bolus %.2fU", units)
        delay(500)
        return Result.success(Unit)
    }

    override suspend fun cancelBolus(): Result<Unit> {
        Timber.d("FakePodManager: Cancelling bolus")
        return Result.success(Unit)
    }

    override suspend fun deactivate(): Result<Unit> {
        Timber.d("FakePodManager: Deactivating pod (simulated)")
        return Result.success(Unit)
    }

    private companion object {
        const val SCAN_DELAY_MS = 2000L
        const val CONNECT_DELAY_MS = 1000L
        const val AUTH_DELAY_MS = 2000L
        const val PRIME_DURATION_MS = 5000L
        const val PRIME_STEPS = 20
        const val ACTIVATION_DURATION_MS = 3000L
        const val STATUS_DELAY_MS = 500L
    }
}
