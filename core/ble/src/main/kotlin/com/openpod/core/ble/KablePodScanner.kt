package com.openpod.core.ble

import com.juul.kable.Scanner
import com.juul.kable.logs.Logging
import com.juul.kable.logs.SystemLogEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import javax.inject.Inject
import kotlin.uuid.ExperimentalUuidApi

/**
 * Kable-based [PodBleScanner] implementation.
 *
 * Uses Kable's [Scanner] to discover Omnipod 5 pods. Scan results
 * are filtered by advertised service UUIDs and RSSI threshold to
 * ensure only reachable, valid pods are emitted.
 *
 * Thread-safe: [stopScan] can be called from any thread.
 */
class KablePodScanner @Inject constructor() : PodBleScanner {

    @Volatile
    private var scanJob: Job? = null

    private val scanner = Scanner {
        logging {
            engine = SystemLogEngine
            level = Logging.Level.Warnings
        }
    }

    override fun scanForUnpaired(): Flow<DiscoveredPod> =
        scanInternal(filterType = ScanFilterType.UNPAIRED)

    override fun scanForPaired(): Flow<DiscoveredPod> =
        scanInternal(filterType = ScanFilterType.PAIRED)

    override fun stopScan() {
        Timber.d("stopScan requested, cancelling active scan job")
        scanJob?.cancel()
        scanJob = null
    }

    /**
     * Internal scan implementation that filters advertisements by type.
     *
     * The flow emits [DiscoveredPod] instances and auto-completes on
     * timeout or when [stopScan] is called.
     */
    @OptIn(ExperimentalUuidApi::class)
    private fun scanInternal(filterType: ScanFilterType): Flow<DiscoveredPod> = channelFlow {
            val job = launch {
                withTimeoutOrNull(BleConstants.SCAN_TIMEOUT_MS) {
                    scanner.advertisements
                        .onStart {
                            Timber.i(
                                "BLE scan started, filter=%s, timeout=%dms",
                                filterType.name,
                                BleConstants.SCAN_TIMEOUT_MS,
                            )
                        }
                        .onCompletion { cause ->
                            val reason = when (cause) {
                                null -> "completed normally"
                                is CancellationException -> "cancelled"
                                else -> "error: ${cause.message}"
                            }
                            Timber.i("BLE scan ended: %s", reason)
                        }
                        .collect { advertisement ->
                            // Compare UUIDs as strings (Kable uses kotlin.uuid.Uuid)
                            val advertisedUuids = advertisement.uuids.map { it.toString() }

                            val matchesFilter = when (filterType) {
                                ScanFilterType.UNPAIRED -> {
                                    BleConstants.UNPAIRED_SCAN_UUIDS.any { it in advertisedUuids }
                                }
                                ScanFilterType.PAIRED -> {
                                    BleConstants.SERVICE_UUID in advertisedUuids
                                }
                            }

                            if (!matchesFilter) return@collect

                            val rssi = advertisement.rssi
                            if (rssi < BleConstants.MIN_RSSI_DBM) {
                                Timber.v(
                                    "Ignoring pod %s: RSSI %d dBm below threshold %d dBm",
                                    advertisement.identifier,
                                    rssi,
                                    BleConstants.MIN_RSSI_DBM,
                                )
                                return@collect
                            }

                            val discovered = DiscoveredPod(
                                id = advertisement.identifier,
                                name = advertisement.name,
                                rssi = rssi,
                                address = advertisement.identifier,
                                advertisement = advertisement,
                            )

                            Timber.i(
                                "Pod discovered: address=%s, name=%s, rssi=%d dBm",
                                discovered.address,
                                discovered.name ?: "(unnamed)",
                                discovered.rssi,
                            )

                            send(discovered)
                        }
                }
                Timber.d("Scan timeout reached after %dms", BleConstants.SCAN_TIMEOUT_MS)
            }
            scanJob = job
            job.join()
    }

    private enum class ScanFilterType {
        UNPAIRED,
        PAIRED,
    }
}
