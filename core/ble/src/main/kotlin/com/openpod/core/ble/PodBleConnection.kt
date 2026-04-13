package com.openpod.core.ble

import com.juul.kable.Advertisement
import com.openpod.model.pod.PodConnectionState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Represents a BLE connection to a single Omnipod 5 pod.
 *
 * Each instance manages one connection lifecycle. Create a new instance
 * for each connection attempt — do not reuse after [disconnect].
 *
 * All operations are suspending and safe to call from any coroutine context.
 * Implementations must handle Android BLE quirks (operation delays,
 * GATT error recovery) internally.
 */
interface PodBleConnection {

    /**
     * Observable connection state. Transitions follow:
     * DISCONNECTED -> CONNECTING -> CONNECTED -> DISCONNECTED
     *
     * RECONNECTING is entered if the connection drops unexpectedly
     * and automatic reconnection is attempted.
     */
    val connectionState: StateFlow<PodConnectionState>

    /**
     * Establish a BLE connection to the pod using the given [advertisement].
     *
     * On Android with Kable 0.42+, a [com.juul.kable.Peripheral] can only be
     * created from a scanner [Advertisement] — not from a raw address string.
     * The [DiscoveredPod.advertisement] from a scan result should be passed here.
     *
     * Performs the full connection sequence:
     * 1. BLE-level connect
     * 2. GATT service discovery
     * 3. Characteristic discovery and validation
     * 4. MTU negotiation (requests [BleConstants.REQUESTED_MTU])
     * 5. Enable TpClassic notifications
     *
     * @param advertisement The Kable [Advertisement] from a [DiscoveredPod].
     * @return [Result.success] when the connection is ready for commands,
     *         [Result.failure] with the cause if any step fails.
     */
    suspend fun connect(advertisement: Advertisement): Result<Unit>

    /**
     * Gracefully disconnect from the pod.
     *
     * Disables notifications, closes the GATT connection, and
     * transitions [connectionState] to [PodConnectionState.DISCONNECTED].
     * Safe to call multiple times or when already disconnected.
     */
    suspend fun disconnect()

    /**
     * Write a command payload to the pod's CMD characteristic.
     *
     * The [data] is written using Write With Response to ensure delivery.
     * Respects [BleConstants.BLE_OPERATION_DELAY_MS] between consecutive writes.
     *
     * @param data Raw bytes to write. Must not exceed negotiated MTU minus ATT header.
     * @return [Result.success] on acknowledged write, [Result.failure] on GATT error or timeout.
     */
    suspend fun writeCommand(data: ByteArray): Result<Unit>

    /**
     * Flow of notification payloads from the TpClassic characteristic.
     *
     * Emits raw byte arrays as they arrive from the pod. The flow is
     * active as long as the connection is alive. Completes when the
     * connection is closed (by either side).
     *
     * Callers typically feed these into [EnvelopeFramer.receive] for reassembly.
     */
    fun notifications(): Flow<ByteArray>

    /**
     * Request a specific MTU size from the pod.
     *
     * @param mtu Desired MTU in bytes.
     * @return [Result.success] with the negotiated MTU (may differ from requested),
     *         [Result.failure] if the request fails.
     */
    suspend fun requestMtu(mtu: Int): Result<Int>
}
