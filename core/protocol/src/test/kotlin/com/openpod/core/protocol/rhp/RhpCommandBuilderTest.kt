package com.openpod.core.protocol.rhp

import com.google.common.truth.Truth.assertThat
import com.openpod.core.protocol.command.AlertConfig
import com.openpod.core.protocol.command.BasalSegment
import com.openpod.core.protocol.command.PodCommand
import com.openpod.core.protocol.command.StopType
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Tests for [RhpCommandBuilder] — serializes PodCommand to RHP binary format.
 *
 * Verifies:
 * - Correct opcode for each command type
 * - Big-endian byte order
 * - Pulse conversion (units / 0.05 = pulses)
 * - Alert encoding (index, flags, duration)
 * - Basal segment encoding
 */
class RhpCommandBuilderTest {

    private val builder = RhpCommandBuilder()

    private fun parseHeader(data: ByteArray): Pair<Byte, Int> {
        val buf = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN)
        val opcode = buf.get()
        val seq = buf.short.toInt() and 0xFFFF
        return opcode to seq
    }

    @Test
    fun `GetVersion has correct opcode and podId payload`() {
        val podId = byteArrayOf(0x01, 0x02, 0x03, 0x04)
        val result = builder.build(PodCommand.GetVersion(podId), sequenceNumber = 1)

        val (opcode, seq) = parseHeader(result)
        assertThat(opcode).isEqualTo(RhpOpcode.GET_VERSION)
        assertThat(seq).isEqualTo(1)
        // Payload is the 4-byte podId
        assertThat(result.size).isEqualTo(3 + 4)
        assertThat(result.copyOfRange(3, 7)).isEqualTo(podId)
    }

    @Test
    fun `GetStatus has empty payload`() {
        val result = builder.build(PodCommand.GetStatus, sequenceNumber = 42)
        assertThat(result.size).isEqualTo(3) // header only
        val (opcode, seq) = parseHeader(result)
        assertThat(opcode).isEqualTo(RhpOpcode.GET_STATUS)
        assertThat(seq).isEqualTo(42)
    }

    @Test
    fun `Deactivate has empty payload`() {
        val result = builder.build(PodCommand.Deactivate, sequenceNumber = 0)
        assertThat(result.size).isEqualTo(3)
        assertThat(result[0]).isEqualTo(RhpOpcode.DEACTIVATE)
    }

    @Test
    fun `SendBolus encodes units as pulses`() {
        // 3.0 U = 60 pulses
        val result = builder.build(PodCommand.SendBolus(units = 3.0), sequenceNumber = 5)
        val buf = ByteBuffer.wrap(result).order(ByteOrder.BIG_ENDIAN)
        buf.position(3) // skip header
        val pulses = buf.short.toInt() and 0xFFFF
        val flags = buf.get()
        assertThat(pulses).isEqualTo(60) // 3.0 / 0.05 = 60
        assertThat(flags.toInt()).isEqualTo(0) // not delayed
    }

    @Test
    fun `SendBolus delayed flag is set`() {
        val result = builder.build(PodCommand.SendBolus(units = 1.0, delayed = true), sequenceNumber = 0)
        val buf = ByteBuffer.wrap(result).order(ByteOrder.BIG_ENDIAN)
        buf.position(3)
        buf.short // skip pulses
        val flags = buf.get()
        assertThat(flags.toInt()).isEqualTo(1) // delayed
    }

    @Test
    fun `PrimePod encodes volume as pulses`() {
        // 0.5 U = 10 pulses
        val result = builder.build(PodCommand.PrimePod(volume = 0.5), sequenceNumber = 0)
        val buf = ByteBuffer.wrap(result).order(ByteOrder.BIG_ENDIAN)
        buf.position(3)
        val pulses = buf.short.toInt() and 0xFFFF
        assertThat(pulses).isEqualTo(10)
    }

    @Test
    fun `ProgramAlerts encodes alerts correctly`() {
        val alerts = listOf(
            AlertConfig(alertIndex = 0, enabled = true, durationMinutes = 60, autoOff = false),
            AlertConfig(alertIndex = 3, enabled = true, durationMinutes = 120, autoOff = true),
        )
        val result = builder.build(PodCommand.ProgramAlerts(alerts), sequenceNumber = 0)
        val buf = ByteBuffer.wrap(result).order(ByteOrder.BIG_ENDIAN)
        buf.position(3)

        // Alert 0: index=0, flags=0x01 (enabled), duration=60
        assertThat(buf.get().toInt()).isEqualTo(0)
        assertThat(buf.get().toInt()).isEqualTo(0x01) // enabled, no autoOff
        assertThat(buf.short.toInt()).isEqualTo(60)

        // Alert 1: index=3, flags=0x03 (enabled + autoOff), duration=120
        assertThat(buf.get().toInt()).isEqualTo(3)
        assertThat(buf.get().toInt()).isEqualTo(0x03) // enabled | autoOff
        assertThat(buf.short.toInt()).isEqualTo(120)
    }

    @Test
    fun `ProgramBasal encodes segments`() {
        val segments = listOf(
            BasalSegment(startSlot = 0, endSlot = 24, pulsesPerSlot = 10),
            BasalSegment(startSlot = 24, endSlot = 48, pulsesPerSlot = 20),
        )
        val result = builder.build(PodCommand.ProgramBasal(segments), sequenceNumber = 0)
        val buf = ByteBuffer.wrap(result).order(ByteOrder.BIG_ENDIAN)
        buf.position(3)

        assertThat(buf.get().toInt()).isEqualTo(0) // startSlot
        assertThat(buf.get().toInt()).isEqualTo(24) // endSlot
        assertThat(buf.short.toInt()).isEqualTo(10) // pulsesPerSlot

        assertThat(buf.get().toInt()).isEqualTo(24)
        assertThat(buf.get().toInt()).isEqualTo(48)
        assertThat(buf.short.toInt()).isEqualTo(20)
    }

    @Test
    fun `StopProgram encodes type ordinal`() {
        val result = builder.build(PodCommand.StopProgram(StopType.ALL), sequenceNumber = 0)
        val buf = ByteBuffer.wrap(result).order(ByteOrder.BIG_ENDIAN)
        buf.position(3)
        assertThat(buf.get().toInt()).isEqualTo(StopType.ALL.ordinal)
    }

    @Test
    fun `SetUniqueId encodes uid lot and sequence`() {
        val uid = byteArrayOf(0x0A, 0x0B, 0x0C, 0x0D)
        val result = builder.build(
            PodCommand.SetUniqueId(uid = uid, lotNumber = 1000, sequenceNumber = 500),
            sequenceNumber = 0,
        )
        val buf = ByteBuffer.wrap(result).order(ByteOrder.BIG_ENDIAN)
        buf.position(3)

        val uidBytes = ByteArray(4)
        buf.get(uidBytes)
        assertThat(uidBytes).isEqualTo(uid)
        assertThat(buf.int).isEqualTo(1000)
        assertThat(buf.int).isEqualTo(500)
    }

    @Test
    fun `CgmTransmitterId encodes length-prefixed ASCII`() {
        val result = builder.build(PodCommand.CgmTransmitterId("ABC123"), sequenceNumber = 0)
        val buf = ByteBuffer.wrap(result).order(ByteOrder.BIG_ENDIAN)
        buf.position(3)
        val len = buf.get().toInt() and 0xFF
        assertThat(len).isEqualTo(6)
        val idBytes = ByteArray(len)
        buf.get(idBytes)
        assertThat(String(idBytes, Charsets.US_ASCII)).isEqualTo("ABC123")
    }

    @Test
    fun `SendTempBasal encodes rate and duration`() {
        // rate=2.0 U/hr → pulsesPerSlot = unitsToPulses(2.0/2) = unitsToPulses(1.0) = 20
        // duration=60 min → 2 slots
        val result = builder.build(
            PodCommand.SendTempBasal(rate = 2.0, durationMinutes = 60),
            sequenceNumber = 0,
        )
        val buf = ByteBuffer.wrap(result).order(ByteOrder.BIG_ENDIAN)
        buf.position(3)
        val pulsesPerSlot = buf.short.toInt() and 0xFFFF
        val slots = buf.short.toInt() and 0xFFFF
        assertThat(pulsesPerSlot).isEqualTo(20) // 1.0 U per slot / 0.05 = 20 pulses
        assertThat(slots).isEqualTo(2)
    }

    @Test
    fun `sequence number is encoded in header`() {
        val result = builder.build(PodCommand.GetStatus, sequenceNumber = 256)
        val (_, seq) = parseHeader(result)
        assertThat(seq).isEqualTo(256)
    }
}
