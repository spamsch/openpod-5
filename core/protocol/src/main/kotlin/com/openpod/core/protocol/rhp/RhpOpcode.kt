package com.openpod.core.protocol.rhp

/**
 * RHP (Remote Host Protocol) opcode constants.
 *
 * Each opcode identifies a specific command or response type in the binary
 * protocol between the PDM and the Omnipod 5 pod. Opcodes are the first
 * byte of every RHP message.
 *
 * These values identify the wire-format command and response types used by the
 * Omnipod 5 protocol.
 */
object RhpOpcode {

    // ---- Command opcodes (PDM -> Pod) ----

    const val GET_VERSION: Byte = 0x07
    const val SET_UNIQUE_ID: Byte = 0x03
    const val PROGRAM_ALERTS: Byte = 0x19
    const val PRIME_POD: Byte = 0x17.toByte()
    const val PROGRAM_BASAL: Byte = 0x13.toByte()
    const val INSERT_CANNULA: Byte = 0x17.toByte() // Same opcode as prime, distinguished by pod state
    const val SEND_BOLUS: Byte = 0x17.toByte()
    const val CANCEL_BOLUS: Byte = 0x1F.toByte()
    const val STOP_PROGRAM: Byte = 0x1F.toByte()
    const val RESUME_INSULIN: Byte = 0x13.toByte()
    const val SEND_TEMP_BASAL: Byte = 0x16.toByte()
    const val GET_STATUS: Byte = 0x0E.toByte()
    const val DEACTIVATE: Byte = 0x1C.toByte()
    const val CONFIGURE_AID: Byte = 0x30.toByte()
    const val SEND_BEEP: Byte = 0x1E.toByte()
    const val SILENCE_ALERT: Byte = 0x11.toByte()
    const val CGM_TRANSMITTER_ID: Byte = 0x32.toByte()

    // ---- Response opcodes (Pod -> PDM) ----

    const val RESPONSE_VERSION_INFO: Byte = 0x01
    const val RESPONSE_STATUS: Byte = 0x1D.toByte()
    const val RESPONSE_BOLUS_PROGRESS: Byte = 0x22.toByte()
    const val RESPONSE_AID_STATUS: Byte = 0x24.toByte()
    const val RESPONSE_ERROR: Byte = 0x06
    const val RESPONSE_ACKNOWLEDGE: Byte = 0x04

    /**
     * Look up a human-readable name for an opcode, for logging purposes.
     *
     * @param opcode The RHP opcode byte.
     * @return A descriptive name, or "UNKNOWN(0xNN)" for unrecognized opcodes.
     */
    fun nameOf(opcode: Byte): String = when (opcode) {
        GET_VERSION -> "GET_VERSION"
        SET_UNIQUE_ID -> "SET_UNIQUE_ID"
        PROGRAM_ALERTS -> "PROGRAM_ALERTS"
        PROGRAM_BASAL -> "PROGRAM_BASAL"
        SEND_TEMP_BASAL -> "SEND_TEMP_BASAL"
        GET_STATUS -> "GET_STATUS"
        SILENCE_ALERT -> "SILENCE_ALERT"
        DEACTIVATE -> "DEACTIVATE"
        SEND_BEEP -> "SEND_BEEP"
        CONFIGURE_AID -> "CONFIGURE_AID"
        CGM_TRANSMITTER_ID -> "CGM_TRANSMITTER_ID"
        RESPONSE_VERSION_INFO -> "RESPONSE_VERSION_INFO"
        RESPONSE_STATUS -> "RESPONSE_STATUS"
        RESPONSE_BOLUS_PROGRESS -> "RESPONSE_BOLUS_PROGRESS"
        RESPONSE_AID_STATUS -> "RESPONSE_AID_STATUS"
        RESPONSE_ERROR -> "RESPONSE_ERROR"
        RESPONSE_ACKNOWLEDGE -> "RESPONSE_ACKNOWLEDGE"
        else -> "UNKNOWN(0x${String.format("%02X", opcode)})"
    }
}
