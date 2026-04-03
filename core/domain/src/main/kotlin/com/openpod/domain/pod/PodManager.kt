package com.openpod.domain.pod

import kotlinx.coroutines.flow.Flow
import java.time.Instant

/**
 * Interface for pod discovery, pairing, and activation operations.
 *
 * All BLE communication is abstracted behind this interface so the pairing
 * wizard can be developed and tested without real hardware. Production
 * implementations delegate to Kable for BLE transport and pure-Kotlin
 * crypto (Bouncy Castle + JCA) for cryptographic operations.
 *
 * Each method corresponds to a phase of the Omnipod 5 activation protocol:
 * scan -> connect -> authenticate (EAP-AKA) -> prime -> insert cannula -> verify status.
 */
interface PodManager {

    /**
     * Start scanning for nearby unpaired Omnipod 5 pods.
     *
     * Emits [DiscoveredPod] instances as they are found via BLE advertisements
     * matching the Omnipod 5 unpaired scan UUIDs. The flow completes when
     * [stopScan] is called or the scan times out.
     *
     * @return A flow of discovered pods.
     */
    suspend fun startScan(): Flow<DiscoveredPod>

    /**
     * Stop an active BLE scan.
     *
     * Safe to call even if no scan is in progress.
     */
    suspend fun stopScan()

    /**
     * Establish a BLE GATT connection to the pod with the given [podId].
     *
     * This performs BLE connection, GATT service discovery, and initial
     * handshake (init command with controller ID).
     *
     * @param podId The unique identifier of the pod to connect to.
     * @return [Result.success] if the connection was established, or
     *   [Result.failure] with a descriptive exception.
     */
    suspend fun connect(podId: String): Result<Unit>

    /**
     * Perform EAP-AKA authentication and establish an encrypted session.
     *
     * If no Long-Term Key (LTK) exists for this pod, a full ECDH key exchange
     * (X25519/P-256) is performed first. The LTK is then used for MILENAGE-based
     * EAP-AKA mutual authentication, followed by AES-CCM-128 session encryption setup.
     *
     * @return [Result.success] if authentication succeeded, or
     *   [Result.failure] with the authentication error.
     */
    suspend fun authenticate(): Result<Unit>

    /**
     * Command the pod to perform its initial prime (fill the cannula tubing).
     *
     * Emits [PrimeProgress] updates as the pod primes. The flow completes
     * when priming is finished (isComplete = true).
     *
     * @return A flow of priming progress updates.
     */
    suspend fun prime(): Flow<PrimeProgress>

    /**
     * Command the pod to insert the cannula and begin insulin delivery.
     *
     * This is the second prime / cannula insertion step. Emits
     * [ActivationProgress] updates as the pod completes the startup sequence.
     *
     * @return A flow of activation progress updates.
     */
    suspend fun insertCannula(): Flow<ActivationProgress>

    /**
     * Query the pod for its current activation status.
     *
     * Called after successful activation to retrieve the pod's UID,
     * reservoir level, expiry time, and firmware version for display
     * on the completion screen.
     *
     * @return [Result.success] with [PodActivationResult], or
     *   [Result.failure] if the status query failed.
     */
    suspend fun getStatus(): Result<PodActivationResult>

    /**
     * Command the pod to deliver a bolus of [units] insulin.
     *
     * The pod delivers in 0.05 U pulses at ~3-second intervals. Progress
     * can be monitored by polling [getStatus] and checking
     * [PodActivationResult.bolusRemainingUnits].
     *
     * @param units Bolus dose in units (0.05–30.0 U).
     */
    suspend fun sendBolus(units: Double): Result<Unit>

    /**
     * Cancel an in-progress bolus delivery.
     */
    suspend fun cancelBolus(): Result<Unit>

    /**
     * Deactivate the current pod and clear the session.
     *
     * Sends the deactivation command (S2.6=1) which resets the pod to
     * factory state and clears the Long-Term Key. After this call,
     * a new pod must be paired.
     */
    suspend fun deactivate(): Result<Unit>
}

/**
 * A pod discovered during BLE scanning.
 *
 * @property id Unique pod identifier (hex string from BLE advertisement).
 * @property rssi Received Signal Strength Indicator in dBm.
 * @property name Display name from the BLE advertisement.
 */
data class DiscoveredPod(
    val id: String,
    val rssi: Int,
    val name: String,
)

/**
 * Progress update during the pod priming phase.
 *
 * @property percent Priming completion from 0.0 to 1.0.
 * @property isComplete True when priming has finished successfully.
 */
data class PrimeProgress(
    val percent: Float,
    val isComplete: Boolean,
)

/**
 * Progress update during cannula insertion and activation.
 *
 * @property percent Activation completion from 0.0 to 1.0.
 * @property step Human-readable description of the current sub-step.
 * @property isComplete True when activation has finished successfully.
 */
data class ActivationProgress(
    val percent: Float,
    val step: String,
    val isComplete: Boolean,
)

/**
 * Pod status returned from GET_STATUS.
 *
 * @property uid Pod unique identifier.
 * @property reservoir Insulin units remaining in the reservoir.
 * @property expiresAt When the pod will expire (activation time + 80 hours).
 * @property firmwareVersion Pod firmware version string.
 * @property glucoseMgDl Latest CGM glucose reading in mg/dL, or null if unavailable.
 * @property glucoseTrend Glucose trend direction (-2 to 2), or null.
 * @property iobUnits Insulin on board in units, or null.
 * @property minutesSinceActivation Minutes since pod activation.
 * @property isActivated True if the pod is fully activated.
 * @property basalRate Current basal rate in U/hr.
 */
data class PodActivationResult(
    val uid: String,
    val reservoir: Double,
    val expiresAt: Instant,
    val firmwareVersion: String,
    val glucoseMgDl: Int? = null,
    val glucoseTrend: Int? = null,
    val iobUnits: Double? = null,
    val minutesSinceActivation: Int = 0,
    val isActivated: Boolean = false,
    val basalRate: Double = 0.0,
    val bolusInProgress: Boolean = false,
    val bolusTotalUnits: Double = 0.0,
    val bolusRemainingUnits: Double = 0.0,
)
