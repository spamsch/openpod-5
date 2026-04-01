package com.openpod.core.ble

import com.juul.kable.Advertisement
import kotlinx.coroutines.flow.Flow

/**
 * Discovered pod information from a BLE scan.
 *
 * @property id Opaque identifier for this advertisement (platform-specific).
 * @property name BLE advertised name, if present.
 * @property rssi Signal strength in dBm at time of discovery.
 * @property address MAC address or platform identifier for connection.
 * @property advertisement The raw Kable [Advertisement] object, required to
 *   create a [com.juul.kable.Peripheral] on Android (Kable 0.42+).
 */
data class DiscoveredPod(
    val id: String,
    val name: String?,
    val rssi: Int,
    val address: String,
    val advertisement: Advertisement,
)

/**
 * Scanner for discovering Omnipod 5 pods over BLE.
 *
 * Implementations filter advertisements by the Omnipod service UUID
 * (for reconnection to paired pods) or by unpaired scan UUIDs (for
 * initial pairing). Signal strength filtering prevents connections
 * to pods that are too distant for reliable insulin delivery.
 */
interface PodBleScanner {

    /**
     * Scan for unpaired pods advertising discovery UUIDs.
     *
     * Emits [DiscoveredPod] for each advertisement that passes
     * UUID and RSSI filters. The flow completes when [stopScan]
     * is called or the scan timeout ([BleConstants.SCAN_TIMEOUT_MS]) expires.
     *
     * Callers should collect on a background dispatcher.
     */
    fun scanForUnpaired(): Flow<DiscoveredPod>

    /**
     * Scan for a previously-paired pod advertising the Omnipod service UUID.
     *
     * Used during reconnection. Emits matching advertisements until
     * [stopScan] is called or the scan timeout expires.
     */
    fun scanForPaired(): Flow<DiscoveredPod>

    /** Stop any active scan immediately. */
    fun stopScan()
}
