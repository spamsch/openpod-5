package com.openpod.feature.pairing.domain

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * Tests for [TwiCommandFrame] — serialize/parse round-trip and edge cases.
 *
 * Mirrors the Python `test_twi_command.py` test coverage to ensure
 * Kotlin and Python implementations are interoperable.
 */
class TwiCommandFrameTest {

    @Test
    fun `basic round-trip preserves all fields`() {
        val twi = TwiCommandFrame(
            commandBytes = "GV",
            commandId = 42,
            lastMessage = true,
            messageType = TwiCommandFrame.MESSAGE_TYPE_ENCRYPTED,
            notificationNumber = 0,
        )
        val data = twi.serialize()
        val parsed = TwiCommandFrame.parse(data)

        assertThat(parsed.commandBytes).isEqualTo("GV")
        assertThat(parsed.commandId).isEqualTo(42)
        assertThat(parsed.lastMessage).isTrue()
        assertThat(parsed.messageType).isEqualTo(TwiCommandFrame.MESSAGE_TYPE_ENCRYPTED)
        assertThat(parsed.notificationNumber).isEqualTo(0)
    }

    @Test
    fun `round-trip with RHP payload and notification number`() {
        val twi = TwiCommandFrame(
            commandBytes = "S3.9=300",
            commandId = 100,
            lastMessage = true,
            messageType = TwiCommandFrame.MESSAGE_TYPE_ENCRYPTED,
            notificationNumber = 12345,
        )
        val data = twi.serialize()
        val parsed = TwiCommandFrame.parse(data)

        assertThat(parsed.commandBytes).isEqualTo("S3.9=300")
        assertThat(parsed.commandId).isEqualTo(100)
        assertThat(parsed.notificationNumber).isEqualTo(12345)
    }

    @Test
    fun `round-trip lastMessage false`() {
        val twi = TwiCommandFrame(
            commandBytes = "data",
            commandId = 1,
            lastMessage = false,
            messageType = TwiCommandFrame.MESSAGE_TYPE_PRIMARY_SIGNED,
            notificationNumber = 0,
        )
        val data = twi.serialize()
        val parsed = TwiCommandFrame.parse(data)

        assertThat(parsed.lastMessage).isFalse()
        assertThat(parsed.messageType).isEqualTo(TwiCommandFrame.MESSAGE_TYPE_PRIMARY_SIGNED)
    }

    @Test
    fun `round-trip batched commands`() {
        val twi = TwiCommandFrame(
            commandBytes = "GV,G3.6,G3.5",
            commandId = 7,
            lastMessage = true,
            messageType = TwiCommandFrame.MESSAGE_TYPE_ENCRYPTED,
            notificationNumber = 999999,
        )
        val data = twi.serialize()
        val parsed = TwiCommandFrame.parse(data)

        assertThat(parsed.commandBytes).isEqualTo("GV,G3.6,G3.5")
        assertThat(parsed.notificationNumber).isEqualTo(999999)
    }

    @Test
    fun `round-trip empty payload`() {
        val twi = TwiCommandFrame(
            commandBytes = "",
            commandId = 0,
            lastMessage = true,
            messageType = TwiCommandFrame.MESSAGE_TYPE_ENCRYPTED,
            notificationNumber = 0,
        )
        val data = twi.serialize()
        val parsed = TwiCommandFrame.parse(data)

        assertThat(parsed.commandBytes).isEmpty()
    }

    @Test
    fun `round-trip CGM TX ID payload`() {
        val twi = TwiCommandFrame(
            commandBytes = "S4.0=ABCDEF123456",
            commandId = 50,
            lastMessage = true,
            messageType = TwiCommandFrame.MESSAGE_TYPE_ENCRYPTED,
            notificationNumber = 0,
        )
        val data = twi.serialize()
        val parsed = TwiCommandFrame.parse(data)

        assertThat(parsed.commandBytes).isEqualTo("S4.0=ABCDEF123456")
    }

    @Test
    fun `negative command ID round-trips`() {
        val twi = TwiCommandFrame(
            commandBytes = "GV",
            commandId = -1,
            lastMessage = true,
            messageType = TwiCommandFrame.MESSAGE_TYPE_ENCRYPTED,
            notificationNumber = 0,
        )
        val data = twi.serialize()
        val parsed = TwiCommandFrame.parse(data)

        assertThat(parsed.commandId).isEqualTo(-1)
    }

    @Test
    fun `too short data throws`() {
        assertThrows<IllegalArgumentException> {
            TwiCommandFrame.parse(byteArrayOf(0x00, 0x01, 0x02))
        }
    }

    @Test
    fun `header-only frame has correct size`() {
        val twi = TwiCommandFrame(commandBytes = "", commandId = 0)
        val data = twi.serialize()
        // 6 header + 2 CRC = 8 bytes
        assertThat(data.size).isEqualTo(8)
        val parsed = TwiCommandFrame.parse(data)
        assertThat(parsed.commandBytes).isEmpty()
    }

    @Test
    fun `message type secondary signed round-trips`() {
        val twi = TwiCommandFrame(
            commandBytes = "test",
            commandId = 1,
            messageType = TwiCommandFrame.MESSAGE_TYPE_SECONDARY_SIGNED,
        )
        val data = twi.serialize()
        val parsed = TwiCommandFrame.parse(data)
        assertThat(parsed.messageType).isEqualTo(TwiCommandFrame.MESSAGE_TYPE_SECONDARY_SIGNED)
    }

    @Test
    fun `GV frame is 6-byte header plus 2-byte payload plus 2-byte CRC`() {
        val twi = TwiCommandFrame(commandBytes = "GV", commandId = 1)
        val data = twi.serialize()
        // 6 header + 2 "GV" + 2 CRC = 10
        assertThat(data.size).isEqualTo(10)
    }

    @Test
    fun `command ID is big-endian`() {
        val twi = TwiCommandFrame(commandBytes = "", commandId = 0x0102)
        val data = twi.serialize()
        assertThat(data[0]).isEqualTo(0x01.toByte())
        assertThat(data[1]).isEqualTo(0x02.toByte())
    }

    @Test
    fun `all RHP command types used by EmulatorPodManager round-trip`() {
        val commands = listOf(
            "S1.2=1",          // prime
            "G1.6",            // get status
            "S1.3=0064",       // program basal
            "S1.1=cancel_loc", // program alerts
            "S1.4=1",          // insert cannula
            "S1.5=1",          // enable algorithm
            "S255.2=1711929600", // set UTC time
            "S2.0=20",         // send bolus (20 pulses)
            "S2.1=1",          // cancel bolus
            "S2.6=1",          // deactivate
        )

        for ((i, cmd) in commands.withIndex()) {
            val twi = TwiCommandFrame(
                commandBytes = cmd,
                commandId = i + 1,
                lastMessage = true,
                messageType = TwiCommandFrame.MESSAGE_TYPE_ENCRYPTED,
            )
            val data = twi.serialize()
            val parsed = TwiCommandFrame.parse(data)

            assertThat(parsed.commandBytes).isEqualTo(cmd)
            assertThat(parsed.commandId).isEqualTo(i + 1)
        }
    }
}
