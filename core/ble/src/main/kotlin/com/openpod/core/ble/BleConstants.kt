package com.openpod.core.ble

/**
 * BLE constants for Omnipod 5 communication.
 *
 * UUIDs, timeouts, and protocol parameters for pod discovery,
 * connection, and data exchange.
 */
object BleConstants {

    // ---- GATT Service & Characteristics (string form for Kable) ----

    /** Primary GATT service UUID exposed by the Omnipod 5 pod. */
    const val SERVICE_UUID = "1a7e4024-e3ed-4464-8b7e-751e03d0dc5f"

    /** CMD characteristic — write commands to the pod. */
    const val CMD_CHARACTERISTIC_UUID = "1a7e2441-e3ed-4464-8b7e-751e03d0dc5f"

    /**
     * TpClassic characteristic — primary read/notify channel for pod responses.
     * Subscribe to notifications on this characteristic to receive data from the pod.
     */
    const val TP_CLASSIC_CHARACTERISTIC_UUID = "1a7e2442-e3ed-4464-8b7e-751e03d0dc5f"

    /**
     * TpFast characteristic — optional high-throughput read/notify channel.
     * Not all pods or firmware versions support this characteristic.
     */
    const val TP_FAST_CHARACTERISTIC_UUID = "1a7e2443-e3ed-4464-8b7e-751e03d0dc5f"

    // ---- Scan Filter UUIDs ----

    /**
     * UUIDs advertised by unpaired pods during discovery.
     * The PDM scans for all four variants to find available pods.
     */
    val UNPAIRED_SCAN_UUIDS: List<String> = listOf(
        "ce1f923d-c539-48ea-7300-0afffffffe00",
        "ce1f923d-c539-48ea-7300-0afffffffe01",
        "ce1f923d-c539-48ea-7300-0afffffffe02",
        "ce1f923d-c539-48ea-7300-0afffffffe03",
    )

    // ---- Timeouts ----

    /** Maximum time allowed for establishing a BLE connection (milliseconds). */
    const val CONNECTION_TIMEOUT_MS: Long = 15_000L

    /** Maximum time allowed for a single command round-trip (milliseconds). */
    const val COMMAND_TIMEOUT_MS: Long = 8_000L

    /** Maximum time allowed for a BLE scan operation (milliseconds). */
    const val SCAN_TIMEOUT_MS: Long = 30_000L

    /**
     * Timeout for incomplete envelope message reassembly (milliseconds).
     * If all chunks of a message are not received within this window,
     * the partial message is discarded.
     */
    const val ENVELOPE_REASSEMBLY_TIMEOUT_MS: Long = 10_000L

    /**
     * Minimum delay between consecutive BLE operations (milliseconds).
     * Android BLE stack can drop operations if issued too rapidly.
     */
    const val BLE_OPERATION_DELAY_MS: Long = 600L

    // ---- Retries ----

    /** Maximum retries for pod connection attempts with exponential backoff. */
    const val MAX_CONNECTION_RETRIES: Int = 3

    /**
     * Maximum command retries defined by the Omnipod 5 protocol.
     * The PDM uses up to 35 retries for critical delivery commands.
     */
    const val MAX_COMMAND_RETRIES: Int = 35

    // ---- MTU ----

    /**
     * Requested MTU size per the Omnipod 5 specification.
     * The effective payload per write is MTU minus 3 bytes (ATT header).
     */
    const val REQUESTED_MTU: Int = 185

    /** Default BLE MTU before negotiation (Android minimum). */
    const val DEFAULT_MTU: Int = 23

    /** ATT protocol header size subtracted from MTU to get usable payload. */
    const val ATT_HEADER_SIZE: Int = 3

    // ---- RSSI ----

    /**
     * Minimum RSSI (in dBm) to accept a discovered pod.
     * Pods weaker than this are too distant for reliable communication.
     * Insulin delivery must not rely on marginal signal strength.
     */
    const val MIN_RSSI_DBM: Int = -80
}
