package com.openpod.core.protocol.session

import com.openpod.core.ble.BleConstants
import com.openpod.core.ble.PodBleConnection
import com.openpod.core.protocol.command.PodCommand
import com.openpod.core.protocol.command.PodResponse
import com.openpod.core.protocol.framing.EnvelopeFramer
import com.openpod.core.protocol.rhp.RhpCommandBuilder
import com.openpod.core.protocol.rhp.RhpCommandParser
import com.openpod.core.protocol.rhp.RhpParseException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.withTimeout
import timber.log.Timber
import java.util.concurrent.atomic.AtomicInteger

/**
 * Manages an active communication session with an Omnipod 5 pod.
 *
 * [PodSession] orchestrates the full command pipeline:
 * 1. Serialize a [PodCommand] to RHP binary format
 * 2. Encrypt the payload via [CryptoManager]
 * 3. Frame into BLE-sized [EnvelopeFramer] chunks
 * 4. Write chunks to the pod via [PodBleConnection]
 * 5. Receive BLE notifications
 * 6. Reassemble chunks into a complete message
 * 7. Decrypt the response
 * 8. Parse the RHP response into a typed [PodResponse]
 *
 * **Command timeout:** Each command round-trip is subject to [commandTimeoutMs]
 * (default 8 seconds per Omnipod 5 spec).
 *
 * **Retry logic:** Failed commands are retried up to [maxRetries] times
 * (default 35 per protocol spec) with the same sequence number.
 *
 * **Thread safety:** This class uses an [AtomicInteger] for sequence numbers
 * but is otherwise not thread-safe. Commands must be sent sequentially
 * (one at a time) to maintain protocol ordering.
 *
 * @property connection BLE connection to the pod.
 * @property crypto Cryptographic operations for encryption/decryption.
 * @property commandTimeoutMs Timeout for each command attempt in milliseconds.
 * @property maxRetries Maximum retry count per command.
 */
class PodSession(
    private val connection: PodBleConnection,
    private val crypto: CryptoManager,
    private val commandTimeoutMs: Long = BleConstants.COMMAND_TIMEOUT_MS,
    private val maxRetries: Int = BleConstants.MAX_COMMAND_RETRIES,
) {
    private val sequenceNumber = AtomicInteger(0)
    private val commandBuilder = RhpCommandBuilder()
    private val responseParser = RhpCommandParser()
    private val framer = EnvelopeFramer()

    /**
     * Send a command to the pod and wait for a response.
     *
     * The command is serialized, encrypted, framed, and written to BLE.
     * This method then waits for the pod's response notification, reassembles
     * it, decrypts it, and parses it into a typed [PodResponse].
     *
     * On failure, the command is retried up to [maxRetries] times with the
     * same sequence number. If all retries are exhausted, the last failure
     * is returned.
     *
     * @param command The pod command to send.
     * @return [Result.success] with the parsed response, or [Result.failure]
     *   with the cause of the final failed attempt.
     */
    suspend fun sendCommand(command: PodCommand): Result<PodResponse> {
        val seq = sequenceNumber.getAndIncrement()
        val commandName = command.javaClass.simpleName

        Timber.d("Sending command: type=%s, sequence=%d", commandName, seq)

        var lastException: Exception? = null

        for (attempt in 0..maxRetries) {
            if (attempt > 0) {
                Timber.d(
                    "Retrying command: type=%s, sequence=%d, attempt=%d/%d",
                    commandName, seq, attempt, maxRetries,
                )
            }

            try {
                val response = executeCommand(command, seq)
                Timber.d(
                    "Command completed: type=%s, sequence=%d, responseType=%s",
                    commandName, seq, response.javaClass.simpleName,
                )
                return Result.success(response)
            } catch (e: TimeoutCancellationException) {
                Timber.w("Command timeout: type=%s, sequence=%d, attempt=%d", commandName, seq, attempt)
                lastException = e
            } catch (e: RhpParseException) {
                Timber.w(e, "Parse error: type=%s, sequence=%d, attempt=%d", commandName, seq, attempt)
                lastException = e
            } catch (e: CryptoException) {
                Timber.e(e, "Crypto error: type=%s, sequence=%d — not retrying", commandName, seq)
                return Result.failure(e)
            } catch (e: Exception) {
                Timber.w(e, "Command failed: type=%s, sequence=%d, attempt=%d", commandName, seq, attempt)
                lastException = e
            }
        }

        Timber.e(
            "Command exhausted all retries: type=%s, sequence=%d, maxRetries=%d",
            commandName, seq, maxRetries,
        )
        return Result.failure(
            lastException ?: IllegalStateException("Command failed after $maxRetries retries")
        )
    }

    /**
     * Observe unsolicited notifications from the pod.
     *
     * Returns a [Flow] of parsed [PodResponse] objects from pod-initiated
     * messages (e.g., bolus progress updates, alert notifications).
     * Malformed notifications are logged and skipped.
     */
    fun observeNotifications(): Flow<PodResponse> =
        connection.notifications().mapNotNull { rawBytes ->
            try {
                val envelope = framer.deserialize(rawBytes)
                val assembled = framer.receive(envelope) ?: return@mapNotNull null
                val decrypted = crypto.decrypt(
                    ciphertext = assembled,
                    nonce = crypto.generateNonce(0), // Notifications use a separate nonce space
                    associatedData = ByteArray(0),
                )
                val response = responseParser.parse(decrypted)
                Timber.d("Received notification: type=%s", response.javaClass.simpleName)
                response
            } catch (e: Exception) {
                Timber.w(e, "Failed to parse notification — skipping")
                null
            }
        }

    /**
     * Execute a single command attempt (no retries).
     *
     * @return The parsed response from the pod.
     * @throws Exception on any failure in the pipeline.
     */
    private suspend fun executeCommand(command: PodCommand, seq: Int): PodResponse {
        // Step 1: Serialize to RHP binary
        val rhpBytes = commandBuilder.build(command, seq)

        // Step 2: Encrypt
        val nonce = crypto.generateNonce(seq)
        val encrypted = crypto.encrypt(
            plaintext = rhpBytes,
            nonce = nonce,
            associatedData = ByteArray(0),
        )

        // Step 3: Frame into BLE chunks
        val envelopes = framer.frame(encrypted)

        // Step 4: Write all chunks to BLE
        for (envelope in envelopes) {
            val serialized = framer.serialize(envelope)
            val writeResult = connection.writeCommand(serialized)
            if (writeResult.isFailure) {
                throw writeResult.exceptionOrNull()
                    ?: IllegalStateException("BLE write failed without exception")
            }
        }

        // Step 5-8: Wait for response with timeout
        return withTimeout(commandTimeoutMs) {
            val responseBytes = connection.notifications().mapNotNull { rawBytes ->
                val responseEnvelope = framer.deserialize(rawBytes)
                framer.receive(responseEnvelope)
            }.first()

            val decrypted = crypto.decrypt(
                ciphertext = responseBytes,
                nonce = nonce, // Response uses the same nonce as the command
                associatedData = ByteArray(0),
            )

            responseParser.parse(decrypted)
        }
    }

    /**
     * Release resources held by this session.
     *
     * Clears framing buffers and resets internal state. The BLE connection
     * is not closed — the caller is responsible for connection lifecycle.
     */
    fun close() {
        Timber.d("Closing PodSession, final sequence=%d", sequenceNumber.get())
        framer.clearBuffers()
    }
}
