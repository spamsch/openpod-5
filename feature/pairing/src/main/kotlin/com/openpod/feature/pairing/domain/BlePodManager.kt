package com.openpod.feature.pairing.domain

import com.openpod.core.ble.KablePodConnection
import com.openpod.core.ble.KablePodScanner
import com.openpod.core.crypto.CryptoManager
import com.openpod.domain.pod.ActivationProgress
import com.openpod.domain.pod.DiscoveredPod
import com.openpod.domain.pod.PodActivationResult
import com.openpod.domain.pod.PodManager
import com.openpod.domain.pod.PrimeProgress
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import timber.log.Timber
import java.nio.ByteBuffer
import java.security.SecureRandom
import java.time.Instant
import javax.inject.Inject
import com.openpod.core.ble.DiscoveredPod as BleDiscoveredPod

/**
 * [PodManager] implementation using real Bluetooth Low Energy.
 *
 * Connects to the pod emulator (or a real pod) over BLE using the
 * Kable library. Uses [CryptoManager] for ECDH pairing, EAP-AKA
 * authentication, and AES-CCM encryption.
 *
 * Protocol uses TWICommand framing with text RHP commands, matching
 * the emulator's protocol stack.
 */
class BlePodManager @Inject constructor(
    private val cryptoManager: CryptoManager,
) : PodManager {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val scanner = KablePodScanner()
    private var connection: KablePodConnection? = null

    /** Stored BLE discovered pod, needed for connect(). */
    private var selectedBlePod: BleDiscoveredPod? = null

    /** Serializes encrypted commands to prevent nonce counter interleaving. */
    private val commandMutex = Mutex()

    /** Pod identity received during init. */
    private var podFirmwareId: ByteArray? = null
    private var podPublicKey: ByteArray? = null
    private var podNonce: ByteArray? = null

    /** Encryption nonce counters. */
    private var txNonceCounter: Long = 0
    private var rxNonceCounter: Long = 0

    /** Incrementing command ID for TWICommand frames. */
    private var nextCommandId: Int = 1

    /** True after EAP-AKA completes. */
    @Volatile
    private var sessionReady = false

    private val controllerId = byteArrayOf(0x01, 0x02, 0x03, 0x04)

    // -- PodManager interface --

    override suspend fun startScan(): Flow<DiscoveredPod> {
        Timber.i("BlePodManager: Starting BLE scan for unpaired pods")
        return scanner.scanForUnpaired().map { blePod ->
            Timber.i(
                "BlePodManager: Discovered %s (rssi=%d, addr=%s)",
                blePod.name, blePod.rssi, blePod.address,
            )
            // Store the BLE pod so connect() can use its advertisement
            selectedBlePod = blePod
            DiscoveredPod(
                id = blePod.address,
                rssi = blePod.rssi,
                name = blePod.name ?: "Unknown Pod",
            )
        }
    }

    override suspend fun stopScan() {
        Timber.d("BlePodManager: Stopping scan")
        scanner.stopScan()
    }

    override suspend fun connect(podId: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val blePod = selectedBlePod
                ?: error("No pod selected — scan first")

            Timber.i("BlePodManager: Connecting via BLE to %s", podId)

            val conn = KablePodConnection(scope)
            connection = conn

            conn.connect(blePod.advertisement).getOrThrow()
            Timber.i("BlePodManager: BLE connected, sending INIT")

            // Send MSG_INIT: [0x06, 0x01, 0x04, controllerId(4)]
            val initMsg = byteArrayOf(MSG_INIT, 0x01, 0x04) + controllerId
            conn.writeCommand(initMsg).getOrThrow()

            // Read response — notifications are subscribed during connect()
            val response = readBleResponse()

            if (response[0] != MSG_PAIRING) {
                error("Unexpected init response type: 0x${response[0].toUByte().toString(16)}")
            }

            if (response.size == 2 && response[1] == PAIR_ALREADY_PAIRED) {
                Timber.i("BlePodManager: Pod already paired, proceeding to EAP-AKA")
                podFirmwareId = null
                podPublicKey = null
                podNonce = null
            } else if (response.size == 1 + 6 + 32 + 16) {
                podFirmwareId = response.copyOfRange(1, 7)
                podPublicKey = response.copyOfRange(7, 39)
                podNonce = response.copyOfRange(39, 55)
                Timber.i(
                    "BlePodManager: Received pod pairing data (fw=%d, key=%d, nonce=%d bytes)",
                    podFirmwareId!!.size, podPublicKey!!.size, podNonce!!.size,
                )
            } else {
                error("Unexpected init response size: ${response.size}")
            }

            Timber.i("BlePodManager: Connected successfully")
        }
    }

    override suspend fun authenticate(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            Timber.i("BlePodManager: Starting authentication")

            if (podPublicKey != null && podNonce != null) {
                performPairing()
            }

            performEapAka()

            Timber.i("BlePodManager: Authentication complete, session ready")
        }
    }

    override suspend fun prime(): Flow<PrimeProgress> = flow {
        Timber.i("BlePodManager: Sending prime command (S1.2=1)")

        sendEncryptedRhp("S1.2=1").getOrThrow()

        for (attempt in 1..MAX_PRIME_POLLS) {
            delay(PRIME_POLL_INTERVAL_MS)

            val statusText = sendEncryptedRhp("G1.6").getOrThrow()
            val fields = parseStatusFields(statusText)
            val runningState = fields.getOrNull(2)?.toIntOrNull() ?: 0

            Timber.i(
                "BlePodManager: Prime poll %d/%d, running_state=%d",
                attempt, MAX_PRIME_POLLS, runningState,
            )

            val percent = (attempt.toFloat() / MAX_PRIME_POLLS).coerceAtMost(0.99f)

            if (runningState == RUNNING_STATE_ABOVE_MIN_VOLUME) {
                Timber.i("BlePodManager: Priming complete")
                emit(PrimeProgress(percent = 1.0f, isComplete = true))
                return@flow
            }

            emit(PrimeProgress(percent = percent, isComplete = false))
        }

        error("Priming did not complete within $MAX_PRIME_POLLS polls")
    }

    override suspend fun insertCannula(): Flow<ActivationProgress> = flow {
        val substeps = listOf(
            "Programming basal" to "S1.3=0064",       // basal data
            "Programming alerts" to "S1.1=cancel_loc", // cancel/LOC alerts
            "Inserting cannula" to "S1.4=1",
            "Enabling algorithm" to "S1.5=1",
        )

        for ((index, step) in substeps.withIndex()) {
            val (label, rhpCmd) = step
            Timber.i("BlePodManager: %s (%s)", label, rhpCmd)

            sendEncryptedRhp(rhpCmd).getOrThrow()

            delay(ACTIVATION_STEP_DELAY_MS)
            val percent = (index + 1).toFloat() / substeps.size
            val isComplete = index == substeps.lastIndex
            emit(ActivationProgress(percent = percent, step = label, isComplete = isComplete))
        }
    }

    override suspend fun getStatus(): Result<PodActivationResult> = withContext(Dispatchers.IO) {
        runCatching {
            check(sessionReady) { "Session not ready" }
            Timber.i("BlePodManager: Querying status (G1.6)")

            val statusText = sendEncryptedRhp("G1.6").getOrThrow()
            val fields = parseStatusFields(statusText)

            // Status format: flags;alert_mask;running_state;reservoir_pulses;uid;
            //   minutes;bolus_pulses;total_pulses;glucose;trend;iob_hundredths;bolus_total_pulses
            val flags = fields.getOrNull(0)?.toIntOrNull(16) ?: 0
            val isActivated = flags and 0x01 != 0
            val bolusInProgress = flags and 0x08 != 0
            val reservoirPulses = fields.getOrNull(3)?.toIntOrNull() ?: 4000
            val uid = fields.getOrNull(4) ?: "unknown"
            val minutes = fields.getOrNull(5)?.toIntOrNull() ?: 0
            val bolusPulses = fields.getOrNull(6)?.toIntOrNull() ?: 0
            val glucoseMgDl = fields.getOrNull(8)?.toIntOrNull()
            val glucoseTrend = fields.getOrNull(9)?.toIntOrNull()
            val iobHundredths = fields.getOrNull(10)?.toIntOrNull()
            val bolusTotalPulses = fields.getOrNull(11)?.toIntOrNull() ?: 0

            val expiresAt = if (isActivated && minutes > 0) {
                Instant.now().plusSeconds((80 * 60 - minutes).toLong() * 60)
            } else {
                Instant.now().plusSeconds(80 * 3600)
            }

            PodActivationResult(
                uid = uid,
                reservoir = reservoirPulses * 0.05,
                expiresAt = expiresAt,
                firmwareVersion = "3.1.6",
                glucoseMgDl = glucoseMgDl,
                glucoseTrend = glucoseTrend,
                iobUnits = iobHundredths?.let { it / 100.0 },
                minutesSinceActivation = minutes,
                isActivated = isActivated,
                basalRate = 1.0,
                bolusInProgress = bolusInProgress,
                bolusTotalUnits = bolusTotalPulses * 0.05,
                bolusRemainingUnits = bolusPulses * 0.05,
            )
        }
    }

    override suspend fun sendBolus(units: Double): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            require(units in 0.05..30.0) { "Bolus must be 0.05–30.0 U, got $units" }
            val pulses = (units / 0.05).toInt()
            Timber.i("BlePodManager: Sending bolus %.2fU (%d pulses)", units, pulses)
            sendEncryptedRhp("S2.0=$pulses").getOrThrow()
            Timber.i("BlePodManager: Bolus command accepted")
        }
    }

    override suspend fun cancelBolus(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            Timber.i("BlePodManager: Cancelling bolus")
            sendEncryptedRhp("S2.1=1").getOrThrow()
            Timber.i("BlePodManager: Bolus cancelled")
        }
    }

    // -- Pairing --

    private suspend fun performPairing() {
        Timber.i("BlePodManager: Performing ECDH pairing")
        val conn = connection ?: error("Not connected")

        cryptoManager.createPairingSession().getOrThrow()
        val localData = cryptoManager.generateLocalPairingData().getOrThrow()

        val pairMsg = byteArrayOf(MSG_PAIRING, PAIR_PHONE_KEY_NONCE) +
            localData.publicKey + localData.nonce
        conn.writeCommand(pairMsg).getOrThrow()

        val confResponse = readBleResponse()
        check(confResponse[0] == MSG_PAIRING && confResponse[1] == PAIR_POD_CONF_RESPONSE) {
            "Unexpected pairing conf response"
        }
        val podConf = confResponse.copyOfRange(2, 18)

        cryptoManager.processPeerData(podPublicKey!!, podNonce!!, podFirmwareId!!).getOrThrow()
        val verified = cryptoManager.verifyConfirmation(podConf).getOrThrow()
        check(verified) { "Pod confirmation verification failed" }

        val phoneConf = cryptoManager.computeConfirmation().getOrThrow()
        val confMsg = byteArrayOf(MSG_PAIRING, PAIR_PHONE_CONF) + phoneConf
        conn.writeCommand(confMsg).getOrThrow()

        val completeResponse = readBleResponse()
        check(completeResponse[0] == MSG_PAIRING && completeResponse[1] == PAIR_COMPLETE) {
            "Pairing did not complete: 0x${completeResponse[1].toUByte().toString(16)}"
        }

        cryptoManager.saveLtk(controllerId).getOrThrow()
        Timber.i("BlePodManager: Pairing complete, LTK saved")
    }

    private suspend fun performEapAka() {
        Timber.i("BlePodManager: Performing EAP-AKA authentication")
        val conn = connection ?: error("Not connected")

        cryptoManager.startEapAkaSession(controllerId).getOrThrow()

        val challenge = cryptoManager.buildEapAkaChallenge().getOrThrow()
        conn.writeCommand(byteArrayOf(MSG_EAP) + challenge).getOrThrow()

        val eapResp = readBleResponse()
        check(eapResp[0] == MSG_EAP) {
            "Expected MSG_EAP response, got 0x${eapResp[0].toUByte().toString(16)}"
        }

        cryptoManager.processEapAkaChallenge(controllerId, eapResp.copyOfRange(1, eapResp.size)).getOrThrow()

        val success = cryptoManager.buildEapAkaSuccess().getOrThrow()
        conn.writeCommand(byteArrayOf(MSG_EAP) + success).getOrThrow()

        sessionReady = true
        Timber.i("BlePodManager: EAP-AKA complete, session key established")
    }

    // -- Encrypted text RHP commands via TWICommand --

    /**
     * Send a text RHP command wrapped in TWICommand, encrypted with AES-CCM.
     *
     * @param rhpText The RHP command string (e.g., "GV", "G1.6", "S2.0=20").
     * @return The RHP response text from the pod.
     */
    private suspend fun sendEncryptedRhp(rhpText: String): Result<String> =
        commandMutex.withLock {
            withContext(Dispatchers.IO) {
                runCatching {
                    val conn = connection ?: error("Not connected")
                    val cmdId = nextCommandId++

                    Timber.d("BlePodManager: Sending RHP: %s (cmdId=%d)", rhpText, cmdId)

                    // 1. Wrap in TWICommand
                    val twi = TwiCommandFrame(
                        commandBytes = rhpText,
                        commandId = cmdId,
                        lastMessage = true,
                        messageType = TwiCommandFrame.MESSAGE_TYPE_ENCRYPTED,
                    )
                    val twiBytes = twi.serialize()

                    // 2. Encrypt
                    val txSuffix = ByteArray(4).also { SecureRandom().nextBytes(it) }
                    val txNonce = buildNonce(txNonceCounter, txSuffix)
                    txNonceCounter++

                    val ciphertext = cryptoManager.encrypt(twiBytes, ByteArray(0), txNonce).getOrThrow()

                    // 3. Write to BLE
                    val msg = byteArrayOf(MSG_ENCRYPTED) + txSuffix + ciphertext
                    conn.writeCommand(msg).getOrThrow()

                    // 4. Read response
                    val response = readBleResponse()
                    check(response[0] == MSG_ENCRYPTED) {
                        "Expected MSG_ENCRYPTED, got 0x${response[0].toUByte().toString(16)}"
                    }

                    // 5. Decrypt
                    val rxSuffix = response.copyOfRange(1, 5)
                    val rxCiphertext = response.copyOfRange(5, response.size)
                    val rxNonce = buildNonce(rxNonceCounter, rxSuffix)
                    rxNonceCounter++

                    val plaintext = cryptoManager.decrypt(rxCiphertext, ByteArray(0), rxNonce).getOrThrow()

                    // 6. Parse TWICommand response
                    val respTwi = TwiCommandFrame.parse(plaintext)
                    Timber.d("BlePodManager: RHP response: %s", respTwi.commandBytes)

                    respTwi.commandBytes
                }
            }
        }

    // -- Helpers --

    /** Read a single response from BLE TpClassic notifications. */
    private suspend fun readBleResponse(): ByteArray {
        val conn = connection ?: error("Not connected")
        return withTimeout(BLE_RESPONSE_TIMEOUT_MS) {
            conn.notifications().first()
        }
    }

    /** Build a 13-byte AES-CCM nonce: counter(8 BE) || suffix(4) || 0x00 */
    private fun buildNonce(counter: Long, suffix: ByteArray): ByteArray {
        val buf = ByteBuffer.allocate(13)
        buf.putLong(counter)
        buf.put(suffix, 0, 4)
        buf.put(0)
        return buf.array()
    }

    /**
     * Parse semicolon-separated status fields from a "1.6=..." response.
     * Strips the "1.6=" prefix if present.
     */
    private fun parseStatusFields(rhpResponse: String): List<String> {
        val value = if (rhpResponse.startsWith("1.6=")) {
            rhpResponse.removePrefix("1.6=")
        } else {
            rhpResponse
        }
        return value.split(";")
    }

    companion object {
        // Protocol message types (same as emulator)
        private const val MSG_INIT: Byte = 0x06
        private const val MSG_PAIRING: Byte = 0x10
        private const val MSG_EAP: Byte = 0x20
        private const val MSG_ENCRYPTED: Byte = 0x30

        // Pairing sub-types
        private const val PAIR_PHONE_KEY_NONCE: Byte = 0x01
        private const val PAIR_PHONE_CONF: Byte = 0x02
        private const val PAIR_POD_CONF_RESPONSE: Byte = 0x03
        private const val PAIR_COMPLETE: Byte = 0x04
        private const val PAIR_ALREADY_PAIRED: Byte = 0x05

        private const val PRIME_POLL_INTERVAL_MS = 1000L
        private const val MAX_PRIME_POLLS = 70
        private const val RUNNING_STATE_ABOVE_MIN_VOLUME = 8
        private const val ACTIVATION_STEP_DELAY_MS = 1000L
        private const val BLE_RESPONSE_TIMEOUT_MS = 8000L
    }
}
