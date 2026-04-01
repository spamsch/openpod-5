package com.openpod.core.protocol.rhp

import com.openpod.core.protocol.command.BasalSegment
import com.openpod.core.protocol.command.PodCommand
import timber.log.Timber
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Serializes [PodCommand] instances into the RHP (Remote Host Protocol) binary format.
 *
 * The RHP format is the wire protocol used between the PDM and the Omnipod 5 pod.
 * Each command type has a specific opcode byte and a defined binary layout for its
 * parameters.
 *
 * **Logging policy:** This class logs command types and sequence numbers for
 * debugging, but never logs raw payload bytes, which may contain PHI or
 * security-sensitive material.
 *
 * Binary layout (all multi-byte fields are big-endian):
 * ```
 * [opcode: 1 byte] [sequence: 2 bytes] [payload: variable]
 * ```
 */
class RhpCommandBuilder {

    /**
     * Serialize a [PodCommand] into its RHP binary representation.
     *
     * @param command The command to serialize.
     * @param sequenceNumber Monotonically increasing sequence number for ordering.
     * @return The serialized RHP command bytes, ready for encryption and framing.
     */
    fun build(command: PodCommand, sequenceNumber: Int): ByteArray {
        Timber.d("Building RHP command: type=%s, sequence=%d", command.javaClass.simpleName, sequenceNumber)

        val payload = when (command) {
            is PodCommand.GetVersion -> buildGetVersion(command)
            is PodCommand.SetUniqueId -> buildSetUniqueId(command)
            is PodCommand.ProgramAlerts -> buildProgramAlerts(command)
            is PodCommand.PrimePod -> buildPrimePod(command)
            is PodCommand.ProgramBasal -> buildProgramBasal(command)
            is PodCommand.InsertCannula -> buildInsertCannula(command)
            is PodCommand.SendBolus -> buildSendBolus(command)
            is PodCommand.CancelBolus -> buildCancelBolus(command)
            is PodCommand.StopProgram -> buildStopProgram(command)
            is PodCommand.ResumeInsulin -> buildResumeInsulin(command)
            is PodCommand.SendTempBasal -> buildSendTempBasal(command)
            is PodCommand.GetStatus -> buildGetStatus()
            is PodCommand.Deactivate -> buildDeactivate()
            is PodCommand.ConfigureAid -> buildConfigureAid(command)
            is PodCommand.SendBeep -> buildSendBeep(command)
            is PodCommand.SilenceAlert -> buildSilenceAlert(command)
            is PodCommand.CgmTransmitterId -> buildCgmTransmitterId(command)
        }

        val result = ByteBuffer.allocate(HEADER_SIZE + payload.size)
            .order(ByteOrder.BIG_ENDIAN)
            .put(opcodeFor(command))
            .putShort(sequenceNumber.toShort())
            .put(payload)
            .array()

        Timber.d(
            "RHP command built: type=%s, sequence=%d, length=%d bytes",
            command.javaClass.simpleName, sequenceNumber, result.size,
        )

        return result
    }

    /**
     * Map a [PodCommand] to its RHP opcode byte.
     *
     * Opcodes are defined by the Omnipod 5 protocol and must match exactly
     * for the pod to accept the command.
     */
    private fun opcodeFor(command: PodCommand): Byte = when (command) {
        is PodCommand.GetVersion -> RhpOpcode.GET_VERSION
        is PodCommand.SetUniqueId -> RhpOpcode.SET_UNIQUE_ID
        is PodCommand.ProgramAlerts -> RhpOpcode.PROGRAM_ALERTS
        is PodCommand.PrimePod -> RhpOpcode.PRIME_POD
        is PodCommand.ProgramBasal -> RhpOpcode.PROGRAM_BASAL
        is PodCommand.InsertCannula -> RhpOpcode.INSERT_CANNULA
        is PodCommand.SendBolus -> RhpOpcode.SEND_BOLUS
        is PodCommand.CancelBolus -> RhpOpcode.CANCEL_BOLUS
        is PodCommand.StopProgram -> RhpOpcode.STOP_PROGRAM
        is PodCommand.ResumeInsulin -> RhpOpcode.RESUME_INSULIN
        is PodCommand.SendTempBasal -> RhpOpcode.SEND_TEMP_BASAL
        is PodCommand.GetStatus -> RhpOpcode.GET_STATUS
        is PodCommand.Deactivate -> RhpOpcode.DEACTIVATE
        is PodCommand.ConfigureAid -> RhpOpcode.CONFIGURE_AID
        is PodCommand.SendBeep -> RhpOpcode.SEND_BEEP
        is PodCommand.SilenceAlert -> RhpOpcode.SILENCE_ALERT
        is PodCommand.CgmTransmitterId -> RhpOpcode.CGM_TRANSMITTER_ID
    }

    // ---- Individual command serializers ----

    private fun buildGetVersion(command: PodCommand.GetVersion): ByteArray =
        command.podId.copyOf()

    private fun buildSetUniqueId(command: PodCommand.SetUniqueId): ByteArray =
        ByteBuffer.allocate(4 + 4 + 4)
            .order(ByteOrder.BIG_ENDIAN)
            .put(command.uid)
            .putInt(command.lotNumber.toInt())
            .putInt(command.sequenceNumber.toInt())
            .array()

    private fun buildProgramAlerts(command: PodCommand.ProgramAlerts): ByteArray {
        // Each alert: 1 byte index + 1 byte flags + 2 bytes duration = 4 bytes
        val buffer = ByteBuffer.allocate(command.alerts.size * ALERT_ENTRY_SIZE)
            .order(ByteOrder.BIG_ENDIAN)
        for (alert in command.alerts) {
            val flags = (if (alert.enabled) 0x01 else 0x00) or
                (if (alert.autoOff) 0x02 else 0x00)
            buffer.put(alert.alertIndex.toByte())
            buffer.put(flags.toByte())
            buffer.putShort(alert.durationMinutes.toShort())
        }
        return buffer.array()
    }

    private fun buildPrimePod(command: PodCommand.PrimePod): ByteArray =
        ByteBuffer.allocate(2)
            .order(ByteOrder.BIG_ENDIAN)
            .putShort(unitsToPulses(command.volume).toShort())
            .array()

    private fun buildProgramBasal(command: PodCommand.ProgramBasal): ByteArray =
        encodeBasalSegments(command.segments)

    private fun buildInsertCannula(command: PodCommand.InsertCannula): ByteArray =
        ByteBuffer.allocate(2)
            .order(ByteOrder.BIG_ENDIAN)
            .putShort(unitsToPulses(command.primeVolume).toShort())
            .array()

    private fun buildSendBolus(command: PodCommand.SendBolus): ByteArray {
        val pulses = unitsToPulses(command.units)
        val flags: Byte = if (command.delayed) 0x01 else 0x00
        return ByteBuffer.allocate(3)
            .order(ByteOrder.BIG_ENDIAN)
            .putShort(pulses.toShort())
            .put(flags)
            .array()
    }

    private fun buildCancelBolus(command: PodCommand.CancelBolus): ByteArray =
        ByteBuffer.allocate(2)
            .order(ByteOrder.BIG_ENDIAN)
            .putShort(command.bolusId.toShort())
            .array()

    private fun buildStopProgram(command: PodCommand.StopProgram): ByteArray =
        byteArrayOf(command.type.ordinal.toByte())

    private fun buildResumeInsulin(command: PodCommand.ResumeInsulin): ByteArray =
        encodeBasalSegments(command.basalSegments)

    private fun buildSendTempBasal(command: PodCommand.SendTempBasal): ByteArray {
        val pulsesPerSlot = unitsToPulses(command.rate / BasalSegment.SLOTS_PER_HOUR)
        val slots = command.durationMinutes / 30
        return ByteBuffer.allocate(4)
            .order(ByteOrder.BIG_ENDIAN)
            .putShort(pulsesPerSlot.toShort())
            .putShort(slots.toShort())
            .array()
    }

    private fun buildGetStatus(): ByteArray = ByteArray(0)

    private fun buildDeactivate(): ByteArray = ByteArray(0)

    private fun buildConfigureAid(command: PodCommand.ConfigureAid): ByteArray {
        val buffer = ByteBuffer.allocate(1 + command.data.size)
            .order(ByteOrder.BIG_ENDIAN)
            .put(command.step.ordinal.toByte())
            .put(command.data)
        return buffer.array()
    }

    private fun buildSendBeep(command: PodCommand.SendBeep): ByteArray =
        byteArrayOf(command.beepType.toByte())

    private fun buildSilenceAlert(command: PodCommand.SilenceAlert): ByteArray =
        ByteBuffer.allocate(2)
            .order(ByteOrder.BIG_ENDIAN)
            .putShort(command.alertMask.toShort())
            .array()

    private fun buildCgmTransmitterId(command: PodCommand.CgmTransmitterId): ByteArray {
        val idBytes = command.transmitterId.toByteArray(Charsets.US_ASCII)
        return ByteBuffer.allocate(1 + idBytes.size)
            .order(ByteOrder.BIG_ENDIAN)
            .put(idBytes.size.toByte())
            .put(idBytes)
            .array()
    }

    // ---- Helpers ----

    /**
     * Encode a list of basal segments into the pod's binary format.
     *
     * Each segment: 1 byte startSlot + 1 byte endSlot + 2 bytes pulsesPerSlot.
     */
    private fun encodeBasalSegments(segments: List<BasalSegment>): ByteArray {
        val buffer = ByteBuffer.allocate(segments.size * BASAL_SEGMENT_SIZE)
            .order(ByteOrder.BIG_ENDIAN)
        for (segment in segments) {
            buffer.put(segment.startSlot.toByte())
            buffer.put(segment.endSlot.toByte())
            buffer.putShort(segment.pulsesPerSlot.toShort())
        }
        return buffer.array()
    }

    /**
     * Convert insulin units to pump pulses.
     *
     * Each pulse delivers 0.05 U. Values are rounded to the nearest pulse.
     */
    private fun unitsToPulses(units: Double): Int =
        Math.round(units / PULSE_SIZE_UNITS).toInt()

    companion object {
        /** RHP header: 1 byte opcode + 2 bytes sequence number. */
        private const val HEADER_SIZE = 3

        /** Each alert entry in the binary format is 4 bytes. */
        private const val ALERT_ENTRY_SIZE = 4

        /** Each basal segment in the binary format is 4 bytes. */
        private const val BASAL_SEGMENT_SIZE = 4

        /** Insulin units per pump pulse. */
        private const val PULSE_SIZE_UNITS = 0.05
    }
}
