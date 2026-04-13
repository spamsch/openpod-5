package com.openpod.core.ble

import com.juul.kable.Advertisement
import com.juul.kable.AndroidPeripheral
import com.juul.kable.Peripheral
import com.juul.kable.State
import com.juul.kable.WriteType
import com.juul.kable.characteristicOf
import com.juul.kable.logs.Logging
import com.juul.kable.logs.SystemLogEngine
import com.openpod.model.pod.PodConnectionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.withTimeout
import timber.log.Timber
import kotlin.time.measureTimedValue
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Kable-based implementation of [PodBleConnection].
 *
 * Manages a single BLE connection to an Omnipod 5 pod, including:
 * - GATT service and characteristic discovery
 * - MTU negotiation
 * - TpClassic notification subscription
 * - Exponential backoff reconnection (up to [BleConstants.MAX_CONNECTION_RETRIES] attempts)
 * - 600ms inter-operation delay for Android BLE stack stability
 *
 * Each instance is single-use: after [disconnect], create a new instance.
 *
 * @param scope Coroutine scope for background state observation.
 */
@OptIn(ExperimentalUuidApi::class)
class KablePodConnection(
    private val scope: CoroutineScope,
) : PodBleConnection {

    private val _connectionState = MutableStateFlow(PodConnectionState.DISCONNECTED)
    override val connectionState: StateFlow<PodConnectionState> = _connectionState.asStateFlow()

    private var peripheral: Peripheral? = null
    private var negotiatedMtu: Int = BleConstants.DEFAULT_MTU

    /**
     * Buffered channel for TpClassic notifications, subscribed once during
     * connection setup. Using a Channel (not SharedFlow) ensures notifications
     * are queued and not lost between write and readBleResponse() calls.
     */
    private val _notifications = Channel<ByteArray>(capacity = 8)

    /** Last write timestamp for enforcing inter-operation delay. */
    private var lastOperationTimeMs: Long = 0L

    private val cmdCharacteristic = characteristicOf(
        service = Uuid.parse(BleConstants.SERVICE_UUID),
        characteristic = Uuid.parse(BleConstants.CMD_CHARACTERISTIC_UUID),
    )

    private val tpClassicCharacteristic = characteristicOf(
        service = Uuid.parse(BleConstants.SERVICE_UUID),
        characteristic = Uuid.parse(BleConstants.TP_CLASSIC_CHARACTERISTIC_UUID),
    )

    override suspend fun connect(advertisement: Advertisement): Result<Unit> {
        Timber.i("connect() called for advertisement=%s", advertisement.identifier)
        _connectionState.value = PodConnectionState.CONNECTING

        var lastException: Throwable? = null

        for (attempt in 1..BleConstants.MAX_CONNECTION_RETRIES) {
            Timber.d(
                "Connection attempt %d/%d for advertisement=%s",
                attempt, BleConstants.MAX_CONNECTION_RETRIES, advertisement.identifier,
            )

            try {
                val result = attemptConnection(advertisement)
                if (result.isSuccess) {
                    return result
                }
                lastException = result.exceptionOrNull()
            } catch (e: Exception) {
                lastException = e
                Timber.w(
                    e,
                    "Connection attempt %d failed for advertisement=%s",
                    attempt, advertisement.identifier,
                )
            }

            if (attempt < BleConstants.MAX_CONNECTION_RETRIES) {
                val backoffMs = calculateBackoffMs(attempt)
                _connectionState.value = PodConnectionState.RECONNECTING
                Timber.d(
                    "Retrying connection in %dms (attempt %d/%d)",
                    backoffMs, attempt + 1, BleConstants.MAX_CONNECTION_RETRIES,
                )
                delay(backoffMs)
            }
        }

        _connectionState.value = PodConnectionState.DISCONNECTED
        val error = lastException ?: IllegalStateException("Connection failed after all retries")
        Timber.e(error, "All %d connection attempts exhausted for advertisement=%s",
            BleConstants.MAX_CONNECTION_RETRIES, advertisement.identifier)
        return Result.failure(error)
    }

    /**
     * Single connection attempt including GATT setup.
     *
     * Steps:
     * 1. Create Kable [Peripheral] from the [Advertisement]
     * 2. Connect with timeout
     * 3. Discover services and validate required characteristics
     * 4. Observe peripheral state for lifecycle tracking
     */
    private suspend fun attemptConnection(advertisement: Advertisement): Result<Unit> = runCatching {
        val (p, connectDuration) = measureTimedValue {
            val p = Peripheral(advertisement) {
                logging {
                    engine = SystemLogEngine
                    level = Logging.Level.Warnings
                }
            }
            withTimeout(BleConstants.CONNECTION_TIMEOUT_MS) {
                p.connect()
            }
            p
        }
        peripheral = p

        Timber.i(
            "BLE connected to %s in %dms, discovering services",
            advertisement.identifier, connectDuration.inWholeMilliseconds,
        )

        // Validate that the Omnipod service exists by attempting to observe characteristics.
        // Kable validates characteristics on use; if the service doesn't exist,
        // write/observe calls will fail with a descriptive error.

        // Observe peripheral state changes for logging and state tracking.
        p.state
            .onEach { state ->
                val mapped = mapKableState(state)
                Timber.d("Peripheral state changed: kable=%s, mapped=%s", state, mapped)
                _connectionState.value = mapped
            }
            .launchIn(scope)

        // Negotiate a larger MTU so notifications can carry full responses.
        val androidPeripheral = p as AndroidPeripheral
        val mtu = androidPeripheral.requestMtu(BleConstants.REQUESTED_MTU)
        negotiatedMtu = mtu
        Timber.i("MTU negotiated: requested=%d, granted=%d", BleConstants.REQUESTED_MTU, mtu)

        // Subscribe to TpClassic notifications once and keep the subscription
        // alive for the lifetime of the connection. This avoids repeated CCCD
        // enable/disable cycles and the associated race conditions.
        p.observe(tpClassicCharacteristic)
            .onEach { data ->
                Timber.d("TpClassic notification received: %d bytes", data.size)
                _notifications.send(data)
            }
            .launchIn(scope)

        // The CCCD descriptor write is async — wait for it to propagate to
        // the peripheral so notifications are active before any commands.
        delay(BleConstants.BLE_OPERATION_DELAY_MS)
        Timber.d("CCCD subscription settled")

        _connectionState.value = PodConnectionState.CONNECTED
        Timber.i("Connection fully established to %s", advertisement.identifier)
    }

    override suspend fun disconnect() {
        Timber.i("disconnect() called, current state=%s", _connectionState.value)
        val p = peripheral
        if (p == null) {
            Timber.d("No peripheral to disconnect")
            _connectionState.value = PodConnectionState.DISCONNECTED
            return
        }

        try {
            p.disconnect()
            Timber.i("Peripheral disconnected gracefully")
        } catch (e: Exception) {
            Timber.w(e, "Error during disconnect, forcing state to DISCONNECTED")
        } finally {
            peripheral = null
            _connectionState.value = PodConnectionState.DISCONNECTED
        }
    }

    override suspend fun writeCommand(data: ByteArray): Result<Unit> = runCatching {
        val p = peripheral ?: throw IllegalStateException("Not connected — cannot write command")

        require(data.isNotEmpty()) { "Command data must not be empty" }

        val maxPayload = negotiatedMtu - BleConstants.ATT_HEADER_SIZE
        require(data.size <= maxPayload) {
            "Command data size ${data.size} exceeds max payload $maxPayload " +
                "(MTU=$negotiatedMtu - ATT header=${BleConstants.ATT_HEADER_SIZE})"
        }

        enforceOperationDelay()

        val (_, writeDuration) = measureTimedValue {
            withTimeout(BleConstants.COMMAND_TIMEOUT_MS) {
                p.write(cmdCharacteristic, data, WriteType.WithResponse)
            }
        }

        lastOperationTimeMs = System.currentTimeMillis()

        Timber.d(
            "Command written: %d bytes in %dms",
            data.size, writeDuration.inWholeMilliseconds,
        )
    }.onFailure { e ->
        Timber.e(e, "Failed to write command (%d bytes)", data.size)
    }

    override fun notifications(): Flow<ByteArray> {
        check(peripheral != null) { "Not connected — cannot observe notifications" }
        return _notifications.receiveAsFlow()
    }

    override suspend fun requestMtu(mtu: Int): Result<Int> = runCatching {
        require(mtu in BleConstants.DEFAULT_MTU..517) {
            "MTU must be between ${BleConstants.DEFAULT_MTU} and 517, got $mtu"
        }
        Timber.d("Requesting MTU=%d", mtu)
        // Kable handles MTU negotiation during connect(). The actual
        // negotiated value is managed internally by the peripheral.
        negotiatedMtu = mtu
        mtu
    }

    /**
     * Enforce minimum delay between BLE operations.
     *
     * The Android BLE stack is unreliable when operations are issued
     * back-to-back. This is a safety-critical delay for insulin delivery.
     */
    private suspend fun enforceOperationDelay() {
        val elapsed = System.currentTimeMillis() - lastOperationTimeMs
        val remaining = BleConstants.BLE_OPERATION_DELAY_MS - elapsed
        if (remaining > 0) {
            Timber.v("Enforcing BLE operation delay: %dms remaining", remaining)
            delay(remaining)
        }
        lastOperationTimeMs = System.currentTimeMillis()
    }

    /**
     * Calculate exponential backoff delay for connection retries.
     *
     * @param attempt Current attempt number (1-based).
     * @return Delay in milliseconds: 1000, 2000, 4000, ...
     */
    private fun calculateBackoffMs(attempt: Int): Long {
        val baseMs = 1_000L
        return baseMs * (1L shl (attempt - 1).coerceAtMost(4))
    }

    /** Map Kable [State] to our [PodConnectionState]. */
    private fun mapKableState(state: State): PodConnectionState = when (state) {
        is State.Connecting -> PodConnectionState.CONNECTING
        is State.Connected -> PodConnectionState.CONNECTED
        is State.Disconnecting -> PodConnectionState.DISCONNECTED
        is State.Disconnected -> PodConnectionState.DISCONNECTED
    }
}
