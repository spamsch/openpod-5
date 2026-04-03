package com.openpod.feature.pairing.domain

import com.openpod.core.crypto.CryptoManager
import com.openpod.domain.pod.ActivationProgress
import com.openpod.domain.pod.DiscoveredPod
import com.openpod.domain.pod.PodActivationResult
import com.openpod.domain.pod.PodManager
import com.openpod.domain.pod.PrimeProgress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.Socket
import java.nio.ByteBuffer
import java.security.SecureRandom
import java.time.Instant
import javax.inject.Inject

/**
 * [PodManager] implementation that connects to the Python pod emulator
 * over a raw TCP socket.
 *
 * Uses [CryptoManager] (pure-Kotlin crypto) for ECDH pairing, EAP-AKA
 * authentication, and AES-CCM message encryption.
 * The emulator runs a `TcpProtocolServer` on port 9996 with 4-byte
 * length-prefixed framing.
 *
 * @param cryptoManager The crypto manager for all cryptographic operations.
 * @param host          Emulator host address. Defaults to `10.0.2.2`
 *                      (Android emulator's host loopback). Use the
 *                      machine's LAN IP for physical devices.
 * @param port          Emulator TCP port. Defaults to `9996`.
 */
class EmulatorPodManager @Inject constructor(
    private val cryptoManager: CryptoManager,
) : PodManager {

    /** Configurable via system properties for flexibility. */
    private val host: String = System.getProperty("openpod.emulator.host") ?: DEFAULT_HOST
    private val port: Int = (System.getProperty("openpod.emulator.port") ?: DEFAULT_PORT.toString()).toInt()

    /** Serializes all encrypted command sends to prevent nonce counter interleaving. */
    private val commandMutex = Mutex()

    private var socket: Socket? = null
    private var dataIn: DataInputStream? = null
    private var dataOut: DataOutputStream? = null

    /** Pod's identity and public key received during init, used in pairing. */
    private var podFirmwareId: ByteArray? = null
    private var podPublicKey: ByteArray? = null
    private var podNonce: ByteArray? = null

    /** Encryption nonce counters (matching emulator's ProtocolSession). */
    private var txNonceCounter: Long = 0
    private var rxNonceCounter: Long = 0

    /** True after EAP-AKA completes — safe to send encrypted commands. */
    @Volatile
    private var sessionReady = false

    /** Controller ID sent in the init message. */
    private val controllerId = byteArrayOf(0x01, 0x02, 0x03, 0x04)

    override suspend fun startScan(): Flow<DiscoveredPod> = flow {
        Timber.i("EmulatorPodManager: Emulator pod always available at %s:%d", host, port)
        delay(500) // Brief delay to feel realistic
        emit(
            DiscoveredPod(
                id = "EMULATOR",
                rssi = -30,
                name = "Emulator Pod",
            ),
        )
    }

    override suspend fun stopScan() {
        Timber.d("EmulatorPodManager: stopScan (no-op for emulator)")
    }

    override suspend fun connect(podId: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            Timber.i("EmulatorPodManager: Connecting to emulator at %s:%d", host, port)

            val sock = Socket(host, port)
            socket = sock
            dataIn = DataInputStream(sock.getInputStream())
            dataOut = DataOutputStream(sock.getOutputStream())

            Timber.i("EmulatorPodManager: TCP connected, sending INIT")

            // Send MSG_INIT: [0x06, 0x01, 0x04, controllerId(4)]
            val initMsg = byteArrayOf(MSG_INIT, 0x01, 0x04) + controllerId
            sendFrame(initMsg)

            // Read response: MSG_PAIRING + pod_pubkey(32) + pod_nonce(16)
            val response = readFrame()

            if (response[0] != MSG_PAIRING) {
                error("Unexpected init response type: 0x${response[0].toUByte().toString(16)}")
            }

            if (response.size == 2 && response[1] == PAIR_ALREADY_PAIRED) {
                // Reconnection path — pod already has LTK
                Timber.i("EmulatorPodManager: Pod reports already paired, will proceed to EAP-AKA")
                podFirmwareId = null
                podPublicKey = null
                podNonce = null
            } else if (response.size == 1 + 6 + 32 + 16) {
                // Fresh pairing path — pod sends firmware_id + pubkey + nonce
                podFirmwareId = response.copyOfRange(1, 7)
                podPublicKey = response.copyOfRange(7, 39)
                podNonce = response.copyOfRange(39, 55)
                Timber.i(
                    "EmulatorPodManager: Received pod pairing data (fw=%d, key=%d, nonce=%d bytes)",
                    podFirmwareId!!.size,
                    podPublicKey!!.size,
                    podNonce!!.size,
                )
            } else {
                error("Unexpected init response size: ${response.size}")
            }

            Timber.i("EmulatorPodManager: Connected successfully")
        }
    }

    override suspend fun authenticate(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            Timber.i("EmulatorPodManager: Starting authentication")

            if (podPublicKey != null && podNonce != null) {
                // Fresh pairing flow
                performPairing()
            }

            // EAP-AKA authentication
            performEapAka()

            Timber.i("EmulatorPodManager: Authentication complete")
        }
    }

    override suspend fun prime(): Flow<PrimeProgress> = flow {
        Timber.i("EmulatorPodManager: Sending PRIME_POD command")

        val primeCmd = byteArrayOf(CMD_PRIME_POD)
        sendEncryptedCommand(primeCmd).getOrThrow()

        // Poll GET_STATUS until pod reports priming complete,
        // matching the real Omnipod 5 behavior (~1s interval, up to
        // MAX_PRIME_POLLS retries).
        for (attempt in 1..MAX_PRIME_POLLS) {
            delay(PRIME_POLL_INTERVAL_MS)

            val statusResp = sendEncryptedCommand(byteArrayOf(CMD_GET_STATUS)).getOrThrow()
            // Response: [cmd_type(1), status(1), flags(1), alert_mask(1), running_state(1), ...]
            val runningState = if (statusResp.size > 4) statusResp[4].toInt() and 0xFF else 0

            Timber.i(
                "EmulatorPodManager: Prime poll %d/%d, running_state=%d",
                attempt, MAX_PRIME_POLLS, runningState,
            )

            val percent = (attempt.toFloat() / MAX_PRIME_POLLS).coerceAtMost(0.99f)

            if (runningState == RUNNING_STATE_ABOVE_MIN_VOLUME) {
                Timber.i("EmulatorPodManager: Priming complete")
                emit(PrimeProgress(percent = 1.0f, isComplete = true))
                return@flow
            }

            emit(PrimeProgress(percent = percent, isComplete = false))
        }

        error("Priming did not complete within $MAX_PRIME_POLLS polls")
    }

    override suspend fun insertCannula(): Flow<ActivationProgress> = flow {
        val substeps = listOf(
            "Programming basal" to CMD_PROGRAM_BASAL,
            "Inserting cannula" to CMD_INSERT_CANNULA,
            "Enabling algorithm" to CMD_ENABLE_ALGORITHM,
        )

        for ((index, step) in substeps.withIndex()) {
            val (label, cmdByte) = step
            Timber.i("EmulatorPodManager: Sending %s command", label)

            val cmd = if (cmdByte == CMD_PROGRAM_BASAL) {
                // Basal program payload: 1.0 U/hr as fixed-point (100 = 1.0 * 100)
                byteArrayOf(cmdByte, 0x00, 0x64)
            } else {
                byteArrayOf(cmdByte)
            }
            sendEncryptedCommand(cmd)

            delay(ACTIVATION_STEP_DELAY_MS)
            val percent = (index + 1).toFloat() / substeps.size
            val isComplete = index == substeps.lastIndex
            emit(ActivationProgress(percent = percent, step = label, isComplete = isComplete))
        }
    }

    override suspend fun getStatus(): Result<PodActivationResult> = withContext(Dispatchers.IO) {
        runCatching {
            check(sessionReady) { "Session not ready" }
            Timber.i("EmulatorPodManager: Querying pod status")

            val response = sendEncryptedCommand(byteArrayOf(CMD_GET_STATUS)).getOrThrow()

            // Parse response: [command_type(1), status(1), payload...]
            // Payload from PodState.encode_status():
            //   flags(1), alert_mask(1), running_state(1), reservoir_pulses(4 BE),
            //   unique_id(4), minutes(2), bolus_pulses(2), total_pulses(2),
            //   glucose_mg_dl(2 BE), glucose_trend(1 signed), iob_hundredths(2 BE)
            val payload = response.copyOfRange(2, response.size)

            val flags = if (payload.isNotEmpty()) payload[0].toInt() and 0xFF else 0
            val isActivated = flags and 0x01 != 0

            val reservoirPulses = if (payload.size >= 7) {
                ((payload[3].toInt() and 0xFF) shl 24) or
                    ((payload[4].toInt() and 0xFF) shl 16) or
                    ((payload[5].toInt() and 0xFF) shl 8) or
                    (payload[6].toInt() and 0xFF)
            } else {
                4000
            }

            val minutes = if (payload.size >= 13) {
                ((payload[11].toInt() and 0xFF) shl 8) or (payload[12].toInt() and 0xFF)
            } else {
                0
            }

            // Extended fields (glucose + IOB) at offsets 17-21
            val glucoseMgDl = if (payload.size >= 19) {
                ((payload[17].toInt() and 0xFF) shl 8) or (payload[18].toInt() and 0xFF)
            } else {
                null
            }

            val glucoseTrend = if (payload.size >= 20) {
                payload[19].toInt() // signed byte
            } else {
                null
            }

            val iobHundredths = if (payload.size >= 22) {
                ((payload[20].toInt() and 0xFF) shl 8) or (payload[21].toInt() and 0xFF)
            } else {
                null
            }

            // Bolus total pulses at offset 22-23
            val bolusTotalPulses = if (payload.size >= 24) {
                ((payload[22].toInt() and 0xFF) shl 8) or (payload[23].toInt() and 0xFF)
            } else {
                0
            }

            // Bolus remaining pulses at offset 13-14
            val bolusRemainingPulses = if (payload.size >= 15) {
                ((payload[13].toInt() and 0xFF) shl 8) or (payload[14].toInt() and 0xFF)
            } else {
                0
            }

            val bolusInProgress = flags and 0x08 != 0

            val expiresAt = if (isActivated && minutes > 0) {
                Instant.now().plusSeconds((80 * 60 - minutes).toLong() * 60)
            } else {
                Instant.now().plusSeconds(80 * 3600)
            }

            PodActivationResult(
                uid = "EMULATOR",
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
                bolusRemainingUnits = bolusRemainingPulses * 0.05,
            )
        }
    }

    override suspend fun sendBolus(units: Double): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            require(units in 0.05..30.0) { "Bolus must be 0.05–30.0 U, got $units" }
            val pulses = (units / 0.05).toInt()
            Timber.i("EmulatorPodManager: Sending bolus %.2fU (%d pulses)", units, pulses)
            val cmd = byteArrayOf(
                CMD_SEND_BOLUS,
                (pulses shr 8).toByte(),
                (pulses and 0xFF).toByte(),
            )
            sendEncryptedCommand(cmd).getOrThrow()
            Timber.i("EmulatorPodManager: Bolus command accepted")
        }
    }

    override suspend fun cancelBolus(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            Timber.i("EmulatorPodManager: Cancelling bolus")
            sendEncryptedCommand(byteArrayOf(CMD_STOP_PROGRAM)).getOrThrow()
            Timber.i("EmulatorPodManager: Bolus cancelled")
        }
    }

    override suspend fun deactivate(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            Timber.i("EmulatorPodManager: Deactivating pod")
            sendEncryptedCommand(byteArrayOf(CMD_DEACTIVATE)).getOrThrow()
            Timber.i("EmulatorPodManager: Pod deactivated")
        }
    }

    // -- Pairing flow --

    private suspend fun performPairing() {
        Timber.i("EmulatorPodManager: Performing ECDH pairing")

        // Create pairing session and generate local keys
        cryptoManager.createPairingSession().getOrThrow()
        val localData = cryptoManager.generateLocalPairingData().getOrThrow()

        // Send phone pubkey + nonce to emulator
        val pairMsg = byteArrayOf(MSG_PAIRING, PAIR_PHONE_KEY_NONCE) +
            localData.publicKey + localData.nonce
        sendFrame(pairMsg)

        // Receive pod's confirmation
        val confResponse = readFrame()
        check(confResponse[0] == MSG_PAIRING && confResponse[1] == PAIR_POD_CONF_RESPONSE) {
            "Unexpected pairing conf response"
        }
        val podConf = confResponse.copyOfRange(2, 18)

        // Process peer data and verify pod confirmation
        cryptoManager.processPeerData(podPublicKey!!, podNonce!!, podFirmwareId!!).getOrThrow()
        val verified = cryptoManager.verifyConfirmation(podConf).getOrThrow()
        check(verified) { "Pod confirmation verification failed" }

        // Compute and send phone confirmation
        val phoneConf = cryptoManager.computeConfirmation().getOrThrow()
        val confMsg = byteArrayOf(MSG_PAIRING, PAIR_PHONE_CONF) + phoneConf
        sendFrame(confMsg)

        // Receive pairing complete
        val completeResponse = readFrame()
        check(completeResponse[0] == MSG_PAIRING && completeResponse[1] == PAIR_COMPLETE) {
            "Pairing did not complete: 0x${completeResponse[1].toUByte().toString(16)}"
        }

        // Save LTK
        cryptoManager.saveLtk(controllerId).getOrThrow()

        Timber.i("EmulatorPodManager: Pairing complete, LTK saved")
    }

    private suspend fun performEapAka() {
        Timber.i("EmulatorPodManager: Performing EAP-AKA authentication")

        cryptoManager.startEapAkaSession(controllerId).getOrThrow()

        // Phone is the authenticator: build and send EAP-Request/AKA-Challenge
        val challenge = cryptoManager.buildEapAkaChallenge().getOrThrow()
        val eapReqMsg = byteArrayOf(MSG_EAP) + challenge
        sendFrame(eapReqMsg)

        // Receive EAP-Response/AKA-Challenge from pod
        val eapResp = readFrame()
        check(eapResp[0] == MSG_EAP) {
            "Expected MSG_EAP response, got 0x${eapResp[0].toUByte().toString(16)}"
        }
        val eapRespPayload = eapResp.copyOfRange(1, eapResp.size)

        // Validate the pod's response (derives session key on success)
        cryptoManager.processEapAkaChallenge(controllerId, eapRespPayload).getOrThrow()

        // Send EAP-Success to finalize authentication
        val success = cryptoManager.buildEapAkaSuccess().getOrThrow()
        val eapSuccessMsg = byteArrayOf(MSG_EAP) + success
        sendFrame(eapSuccessMsg)

        sessionReady = true
        Timber.i("EmulatorPodManager: EAP-AKA authentication complete, session key established")
    }

    // -- Encrypted command helpers --

    private suspend fun sendEncryptedCommand(plaintext: ByteArray): Result<ByteArray> =
        commandMutex.withLock {
            withContext(Dispatchers.IO) {
                runCatching {
                    // Build TX nonce: counter(8 BE) || suffix(4 random) || 0x00
                    val txSuffix = ByteArray(4).also { SecureRandom().nextBytes(it) }
                    val txNonce = buildNonce(txNonceCounter, txSuffix)
                    txNonceCounter++

                    val ciphertext = cryptoManager.encrypt(plaintext, ByteArray(0), txNonce).getOrThrow()
                    val msg = byteArrayOf(MSG_ENCRYPTED) + txSuffix + ciphertext
                    sendFrame(msg)

                    // Read encrypted response
                    val response = readFrame()
                    check(response[0] == MSG_ENCRYPTED) {
                        "Expected MSG_ENCRYPTED response, got 0x${response[0].toUByte().toString(16)}"
                    }
                    val rxSuffix = response.copyOfRange(1, 5)
                    val rxCiphertext = response.copyOfRange(5, response.size)
                    val rxNonce = buildNonce(rxNonceCounter, rxSuffix)
                    rxNonceCounter++

                    cryptoManager.decrypt(rxCiphertext, ByteArray(0), rxNonce).getOrThrow()
                }
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

    // -- TCP framing --

    private fun sendFrame(data: ByteArray) {
        val out = dataOut ?: error("Not connected")
        out.writeInt(data.size)
        out.write(data)
        out.flush()
        Timber.d("EmulatorPodManager: Sent frame (%d bytes)", data.size)
    }

    private fun readFrame(): ByteArray {
        val input = dataIn ?: error("Not connected")
        val length = input.readInt()
        check(length in 1..65536) { "Invalid frame length: $length" }
        val data = ByteArray(length)
        input.readFully(data)
        Timber.d("EmulatorPodManager: Received frame (%d bytes)", length)
        return data
    }

    companion object {
        private const val DEFAULT_HOST = "10.0.2.2"
        private const val DEFAULT_PORT = 9996

        // Protocol message types (must match emulator protocol/session.py)
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

        // RHP command types (must match emulator protocol/commands.py)
        private const val CMD_PRIME_POD: Byte = 0x04
        private const val CMD_PROGRAM_BASAL: Byte = 0x05
        private const val CMD_INSERT_CANNULA: Byte = 0x06
        private const val CMD_ENABLE_ALGORITHM: Byte = 0x07
        private const val CMD_GET_STATUS: Byte = 0x08
        private const val CMD_SEND_BOLUS: Byte = 0x09
        private const val CMD_STOP_PROGRAM: Byte = 0x0A
        private const val CMD_DEACTIVATE: Byte = 0x0B

        private const val PRIME_POLL_INTERVAL_MS = 1000L
        private const val MAX_PRIME_POLLS = 15
        private const val RUNNING_STATE_ABOVE_MIN_VOLUME = 8
        private const val ACTIVATION_STEP_DELAY_MS = 1000L
    }
}
