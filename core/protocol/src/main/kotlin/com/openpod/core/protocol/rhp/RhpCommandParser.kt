package com.openpod.core.protocol.rhp

import com.openpod.core.protocol.command.PodResponse
import timber.log.Timber
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Deserializes raw RHP binary responses from the pod into typed [PodResponse] objects.
 *
 * The parser reads the opcode byte from the response header to determine the message
 * type, then extracts the appropriate fields from the payload.
 *
 * **Logging policy:** This class logs response types and structural metadata (opcode,
 * length) but never logs raw payload bytes or clinically sensitive values (glucose,
 * IOB, reservoir level) which may constitute PHI.
 *
 * Binary layout (all multi-byte fields are big-endian):
 * ```
 * [opcode: 1 byte] [sequence: 2 bytes] [payload: variable]
 * ```
 */
class RhpCommandParser {

    /**
     * Parse a raw RHP response into a typed [PodResponse].
     *
     * @param data Raw RHP bytes received from the pod (after decryption and deframing).
     * @return The parsed [PodResponse].
     * @throws RhpParseException if the data is malformed or the opcode is unrecognized.
     */
    fun parse(data: ByteArray): PodResponse {
        if (data.size < MIN_RESPONSE_SIZE) {
            throw RhpParseException(
                "Response too short: ${data.size} bytes, minimum is $MIN_RESPONSE_SIZE"
            )
        }

        val buffer = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN)
        val opcode = buffer.get()
        val sequenceNumber = buffer.short.toInt() and 0xFFFF

        Timber.d(
            "Parsing RHP response: opcode=%s, sequence=%d, totalLength=%d",
            RhpOpcode.nameOf(opcode), sequenceNumber, data.size,
        )

        return when (opcode) {
            RhpOpcode.RESPONSE_VERSION_INFO -> parseVersionInfo(buffer)
            RhpOpcode.RESPONSE_STATUS -> parseStatusResponse(buffer)
            RhpOpcode.RESPONSE_BOLUS_PROGRESS -> parseBolusProgress(buffer)
            RhpOpcode.RESPONSE_AID_STATUS -> parseAidStatus(buffer)
            RhpOpcode.RESPONSE_ERROR -> parseErrorResponse(buffer)
            RhpOpcode.RESPONSE_ACKNOWLEDGE -> parseAcknowledge(buffer, sequenceNumber)
            else -> {
                Timber.w("Unrecognized RHP response opcode: %s", RhpOpcode.nameOf(opcode))
                throw RhpParseException(
                    "Unrecognized response opcode: 0x${String.format("%02X", opcode)}"
                )
            }
        }
    }

    /**
     * Parse a version info response.
     *
     * Layout after header:
     * ```
     * [fwMajor: 1] [fwMinor: 1] [fwPatch: 1]
     * [bleMajor: 1] [bleMinor: 1] [blePatch: 1]
     * [lotNumber: 4] [sequenceNumber: 4]
     * ```
     */
    private fun parseVersionInfo(buffer: ByteBuffer): PodResponse.VersionInfo {
        requireRemaining(buffer, 14, "VersionInfo")

        val fwMajor = buffer.get().toInt() and 0xFF
        val fwMinor = buffer.get().toInt() and 0xFF
        val fwPatch = buffer.get().toInt() and 0xFF
        val bleMajor = buffer.get().toInt() and 0xFF
        val bleMinor = buffer.get().toInt() and 0xFF
        val blePatch = buffer.get().toInt() and 0xFF
        val lotNumber = buffer.int.toLong() and 0xFFFFFFFFL
        val sequenceNumber = buffer.int.toLong() and 0xFFFFFFFFL

        val firmwareVersion = "$fwMajor.$fwMinor.$fwPatch"
        val bleFirmwareVersion = "$bleMajor.$bleMinor.$blePatch"

        Timber.d(
            "Parsed VersionInfo: firmware=%s, bleFirmware=%s, lot=%d, seq=%d",
            firmwareVersion, bleFirmwareVersion, lotNumber, sequenceNumber,
        )

        return PodResponse.VersionInfo(
            firmwareVersion = firmwareVersion,
            bleFirmwareVersion = bleFirmwareVersion,
            lotNumber = lotNumber,
            sequenceNumber = sequenceNumber,
        )
    }

    /**
     * Parse a status response.
     *
     * Layout after header:
     * ```
     * [deliveryStatus: 1] [podState: 1] [bolusRemaining: 2 (pulses)]
     * [reservoirLevel: 2 (pulses)] [minutesSinceActivation: 2] [activeAlerts: 1]
     * ```
     */
    private fun parseStatusResponse(buffer: ByteBuffer): PodResponse.StatusResponse {
        requireRemaining(buffer, 9, "StatusResponse")

        val deliveryStatus = buffer.get().toInt() and 0xFF
        val podState = buffer.get().toInt() and 0xFF
        val bolusRemainingPulses = buffer.short.toInt() and 0xFFFF
        val reservoirPulses = buffer.short.toInt() and 0xFFFF
        val minutesSinceActivation = buffer.short.toInt() and 0xFFFF
        val activeAlerts = buffer.get().toInt() and 0xFF

        Timber.d(
            "Parsed StatusResponse: deliveryStatus=%d, podState=%d, minutesSinceActivation=%d",
            deliveryStatus, podState, minutesSinceActivation,
        )

        return PodResponse.StatusResponse(
            deliveryStatus = deliveryStatus,
            podState = podState,
            bolusRemaining = pulsesToUnits(bolusRemainingPulses),
            reservoirLevel = pulsesToUnits(reservoirPulses),
            minutesSinceActivation = minutesSinceActivation,
            activeAlerts = activeAlerts,
        )
    }

    /**
     * Parse a bolus progress update.
     *
     * Layout after header:
     * ```
     * [deliveredPulses: 2] [remainingPulses: 2]
     * ```
     */
    private fun parseBolusProgress(buffer: ByteBuffer): PodResponse.BolusProgress {
        requireRemaining(buffer, 4, "BolusProgress")

        val deliveredPulses = buffer.short.toInt() and 0xFFFF
        val remainingPulses = buffer.short.toInt() and 0xFFFF

        Timber.d("Parsed BolusProgress response")

        return PodResponse.BolusProgress(
            delivered = pulsesToUnits(deliveredPulses),
            remaining = pulsesToUnits(remainingPulses),
        )
    }

    /**
     * Parse an AID status response.
     *
     * Layout after header:
     * ```
     * [algorithmState: 1] [cgmState: 1] [glucoseValue: 2] [iobPulses: 2]
     * ```
     */
    private fun parseAidStatus(buffer: ByteBuffer): PodResponse.AidStatus {
        requireRemaining(buffer, 6, "AidStatus")

        val algorithmState = buffer.get().toInt() and 0xFF
        val cgmState = buffer.get().toInt() and 0xFF
        val glucoseValue = buffer.short.toInt() and 0xFFFF
        val iobPulses = buffer.short.toInt() and 0xFFFF

        Timber.d("Parsed AidStatus: algorithmState=%d, cgmState=%d", algorithmState, cgmState)

        return PodResponse.AidStatus(
            algorithmState = algorithmState,
            cgmState = cgmState,
            glucoseValue = glucoseValue,
            iob = pulsesToUnits(iobPulses),
        )
    }

    /**
     * Parse an error response.
     *
     * Layout after header:
     * ```
     * [errorCode: 1] [faultCode: 1] [descriptionLength: 1] [description: variable]
     * ```
     */
    private fun parseErrorResponse(buffer: ByteBuffer): PodResponse.ErrorResponse {
        requireRemaining(buffer, 3, "ErrorResponse")

        val errorCode = buffer.get().toInt() and 0xFF
        val faultCode = buffer.get().toInt() and 0xFF
        val descriptionLength = buffer.get().toInt() and 0xFF

        val description = if (descriptionLength > 0 && buffer.remaining() >= descriptionLength) {
            val descBytes = ByteArray(descriptionLength)
            buffer.get(descBytes)
            String(descBytes, Charsets.US_ASCII)
        } else {
            errorDescriptionFor(errorCode, faultCode)
        }

        Timber.w("Parsed ErrorResponse: errorCode=%d, faultCode=%d", errorCode, faultCode)

        return PodResponse.ErrorResponse(
            errorCode = errorCode,
            faultCode = faultCode,
            description = description,
        )
    }

    /**
     * Parse an acknowledge response.
     *
     * The acknowledge payload is minimal — just the opcode and sequence number,
     * which we already parsed from the header.
     */
    private fun parseAcknowledge(buffer: ByteBuffer, sequenceNumber: Int): PodResponse.Acknowledge {
        Timber.d("Parsed Acknowledge: commandId=%d", sequenceNumber)
        return PodResponse.Acknowledge(commandId = sequenceNumber)
    }

    // ---- Helpers ----

    /**
     * Validate that the buffer has at least [required] bytes remaining.
     *
     * @throws RhpParseException if insufficient data is available.
     */
    private fun requireRemaining(buffer: ByteBuffer, required: Int, responseName: String) {
        if (buffer.remaining() < required) {
            throw RhpParseException(
                "$responseName payload too short: need $required bytes, have ${buffer.remaining()}"
            )
        }
    }

    /**
     * Convert pump pulses to insulin units.
     *
     * Each pulse delivers 0.05 U.
     */
    private fun pulsesToUnits(pulses: Int): Double = pulses * PULSE_SIZE_UNITS

    /**
     * Provide a default error description for known error/fault code combinations.
     */
    private fun errorDescriptionFor(errorCode: Int, faultCode: Int): String = when {
        faultCode != 0 -> "Pod fault (code $faultCode)"
        errorCode == 0x0D -> "Bad command parameter"
        errorCode == 0x0E -> "Command out of sequence"
        errorCode == 0x0F -> "Command not allowed in current state"
        errorCode == 0x14 -> "Pod expired"
        else -> "Unknown error (error=$errorCode, fault=$faultCode)"
    }

    companion object {
        /** Minimum valid response: 1 byte opcode + 2 bytes sequence. */
        private const val MIN_RESPONSE_SIZE = 3

        /** Insulin units per pump pulse. */
        private const val PULSE_SIZE_UNITS = 0.05
    }
}

/**
 * Exception thrown when RHP response parsing fails due to malformed data.
 *
 * @property message Description of the parse failure.
 */
class RhpParseException(message: String) : Exception(message)
