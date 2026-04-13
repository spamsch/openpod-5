package com.openpod.core.protocol.rhp

import com.google.common.truth.Truth.assertThat
import com.openpod.core.protocol.command.PodResponse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Tests for [RhpCommandParser] — deserializes RHP binary responses into typed PodResponse.
 *
 * Verifies:
 * - All 6 response types parse correctly
 * - Pulse-to-units conversion
 * - Version string construction
 * - Error description generation
 * - Malformed data throws RhpParseException
 */
class RhpCommandParserTest {

    private val parser = RhpCommandParser()

    private fun buildResponse(opcode: Byte, sequenceNumber: Int, payload: ByteArray): ByteArray {
        val buf = ByteBuffer.allocate(3 + payload.size).order(ByteOrder.BIG_ENDIAN)
        buf.put(opcode)
        buf.putShort(sequenceNumber.toShort())
        buf.put(payload)
        return buf.array()
    }

    @Test
    fun `parse VersionInfo response`() {
        // FW 2.7.0, BLE 1.3.2, lot=1000, seq=500
        val payload = ByteBuffer.allocate(14).order(ByteOrder.BIG_ENDIAN)
            .put(2).put(7).put(0)   // firmware
            .put(1).put(3).put(2)   // BLE firmware
            .putInt(1000)           // lot number
            .putInt(500)            // sequence number
            .array()

        val data = buildResponse(RhpOpcode.RESPONSE_VERSION_INFO, 1, payload)
        val result = parser.parse(data) as PodResponse.VersionInfo

        assertThat(result.firmwareVersion).isEqualTo("2.7.0")
        assertThat(result.bleFirmwareVersion).isEqualTo("1.3.2")
        assertThat(result.lotNumber).isEqualTo(1000L)
        assertThat(result.sequenceNumber).isEqualTo(500L)
    }

    @Test
    fun `parse StatusResponse with pulse conversion`() {
        // deliveryStatus=1, podState=7, bolusRemaining=60 pulses (3.0U),
        // reservoir=1000 pulses (50U), minutesSinceActivation=120, activeAlerts=0x02
        val payload = ByteBuffer.allocate(9).order(ByteOrder.BIG_ENDIAN)
            .put(1)                     // deliveryStatus
            .put(7)                     // podState
            .putShort(60)               // bolusRemaining (pulses)
            .putShort(1000.toShort())   // reservoir (pulses)
            .putShort(120)              // minutesSinceActivation
            .put(0x02)                  // activeAlerts
            .array()

        val data = buildResponse(RhpOpcode.RESPONSE_STATUS, 2, payload)
        val result = parser.parse(data) as PodResponse.StatusResponse

        assertThat(result.deliveryStatus).isEqualTo(1)
        assertThat(result.podState).isEqualTo(7)
        assertThat(result.bolusRemaining).isEqualTo(3.0)  // 60 * 0.05
        assertThat(result.reservoirLevel).isEqualTo(50.0)  // 1000 * 0.05
        assertThat(result.minutesSinceActivation).isEqualTo(120)
        assertThat(result.activeAlerts).isEqualTo(2)
        assertThat(result.isDelivering).isTrue()
        assertThat(result.hasActiveBolus).isTrue()
    }

    @Test
    fun `StatusResponse with zero bolus has no active bolus`() {
        val payload = ByteBuffer.allocate(9).order(ByteOrder.BIG_ENDIAN)
            .put(1).put(7).putShort(0).putShort(1000.toShort()).putShort(120).put(0)
            .array()
        val data = buildResponse(RhpOpcode.RESPONSE_STATUS, 0, payload)
        val result = parser.parse(data) as PodResponse.StatusResponse
        assertThat(result.hasActiveBolus).isFalse()
    }

    @Test
    fun `suspended delivery status is not delivering`() {
        val payload = ByteBuffer.allocate(9).order(ByteOrder.BIG_ENDIAN)
            .put(0).put(7).putShort(0).putShort(1000.toShort()).putShort(120).put(0) // deliveryStatus=0 (suspended)
            .array()
        val data = buildResponse(RhpOpcode.RESPONSE_STATUS, 0, payload)
        val result = parser.parse(data) as PodResponse.StatusResponse
        assertThat(result.isDelivering).isFalse()
    }

    @Test
    fun `parse BolusProgress`() {
        // delivered=40 pulses (2.0U), remaining=20 pulses (1.0U)
        val payload = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN)
            .putShort(40).putShort(20)
            .array()
        val data = buildResponse(RhpOpcode.RESPONSE_BOLUS_PROGRESS, 3, payload)
        val result = parser.parse(data) as PodResponse.BolusProgress

        assertThat(result.delivered).isEqualTo(2.0)
        assertThat(result.remaining).isEqualTo(1.0)
    }

    @Test
    fun `parse AidStatus`() {
        // algorithmState=1, cgmState=2, glucose=140, iob=30 pulses (1.5U)
        val payload = ByteBuffer.allocate(6).order(ByteOrder.BIG_ENDIAN)
            .put(1).put(2).putShort(140).putShort(30)
            .array()
        val data = buildResponse(RhpOpcode.RESPONSE_AID_STATUS, 4, payload)
        val result = parser.parse(data) as PodResponse.AidStatus

        assertThat(result.algorithmState).isEqualTo(1)
        assertThat(result.cgmState).isEqualTo(2)
        assertThat(result.glucoseValue).isEqualTo(140)
        assertThat(result.iob).isEqualTo(1.5)
    }

    @Test
    fun `parse ErrorResponse with description`() {
        val desc = "Bad param"
        val descBytes = desc.toByteArray(Charsets.US_ASCII)
        val payload = ByteBuffer.allocate(3 + descBytes.size).order(ByteOrder.BIG_ENDIAN)
            .put(0x0D.toByte()) // errorCode
            .put(0)             // faultCode
            .put(descBytes.size.toByte())
            .put(descBytes)
            .array()
        val data = buildResponse(RhpOpcode.RESPONSE_ERROR, 5, payload)
        val result = parser.parse(data) as PodResponse.ErrorResponse

        assertThat(result.errorCode).isEqualTo(0x0D)
        assertThat(result.faultCode).isEqualTo(0)
        assertThat(result.description).isEqualTo("Bad param")
    }

    @Test
    fun `parse ErrorResponse without description uses default`() {
        val payload = ByteBuffer.allocate(3).order(ByteOrder.BIG_ENDIAN)
            .put(0x14.toByte()) // errorCode = pod expired
            .put(0)
            .put(0) // zero description length
            .array()
        val data = buildResponse(RhpOpcode.RESPONSE_ERROR, 0, payload)
        val result = parser.parse(data) as PodResponse.ErrorResponse

        assertThat(result.description).isEqualTo("Pod expired")
    }

    @Test
    fun `parse Acknowledge uses sequence number`() {
        val data = buildResponse(RhpOpcode.RESPONSE_ACKNOWLEDGE, 99, ByteArray(0))
        val result = parser.parse(data) as PodResponse.Acknowledge
        assertThat(result.commandId).isEqualTo(99)
    }

    @Test
    fun `too-short response throws RhpParseException`() {
        assertThrows<RhpParseException> {
            parser.parse(byteArrayOf(0x01, 0x00))  // only 2 bytes
        }
    }

    @Test
    fun `unrecognized opcode throws RhpParseException`() {
        assertThrows<RhpParseException> {
            parser.parse(byteArrayOf(0xFF.toByte(), 0x00, 0x00))
        }
    }

    @Test
    fun `VersionInfo with too-short payload throws RhpParseException`() {
        val data = buildResponse(RhpOpcode.RESPONSE_VERSION_INFO, 0, ByteArray(5)) // need 14
        assertThrows<RhpParseException> {
            parser.parse(data)
        }
    }

    @Test
    fun `StatusResponse with too-short payload throws RhpParseException`() {
        val data = buildResponse(RhpOpcode.RESPONSE_STATUS, 0, ByteArray(3)) // need 9
        assertThrows<RhpParseException> {
            parser.parse(data)
        }
    }
}
