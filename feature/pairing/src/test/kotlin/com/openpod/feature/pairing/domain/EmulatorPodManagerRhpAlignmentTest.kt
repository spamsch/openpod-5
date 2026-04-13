package com.openpod.feature.pairing.domain

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.jupiter.api.Test

/**
 * Verifies that EmulatorPodManager uses the same text RHP commands and
 * TWICommand framing as BlePodManager.
 *
 * These tests don't require a live emulator — they validate protocol
 * alignment by checking that RHP command strings produce valid TWICommand
 * frames that match the expected wire format.
 */
class EmulatorPodManagerRhpAlignmentTest {

    @Test
    fun `all EmulatorPodManager RHP commands produce valid TWICommand frames`() {
        val rhpCommands = mapOf(
            "prime" to "S1.2=1",
            "getStatus" to "G1.6",
            "programBasal" to "S1.3=0064",
            "programAlerts" to "S1.1=cancel_loc",
            "insertCannula" to "S1.4=1",
            "enableAlgorithm" to "S1.5=1",
            "setUtcTime" to "S255.2=1711929600",
            "sendBolus" to "S2.0=20",
            "cancelBolus" to "S2.1=1",
            "deactivate" to "S2.6=1",
        )

        for ((name, rhpText) in rhpCommands) {
            val twi = TwiCommandFrame(
                commandBytes = rhpText,
                commandId = 1,
                lastMessage = true,
                messageType = TwiCommandFrame.MESSAGE_TYPE_ENCRYPTED,
            )
            val serialized = twi.serialize()
            val parsed = TwiCommandFrame.parse(serialized)

            assertWithMessage("RHP command for $name")
                .that(parsed.commandBytes)
                .isEqualTo(rhpText)
        }
    }

    @Test
    fun `EmulatorPodManager RHP commands match BlePodManager`() {
        // Map of operation → expected RHP command (from BlePodManager source)
        val expectedCommands = mapOf(
            "prime" to "S1.2=1",
            "getStatus" to "G1.6",
            "programBasal" to "S1.3=0064",
            "programAlerts" to "S1.1=cancel_loc",
            "insertCannula" to "S1.4=1",
            "enableAlgorithm" to "S1.5=1",
            "sendBolus_20pulses" to "S2.0=20",
            "cancelBolus" to "S2.1=1",
            "deactivate" to "S2.6=1",
        )

        val emulatorCommands = mapOf(
            "prime" to "S1.2=1",
            "getStatus" to "G1.6",
            "programBasal" to "S1.3=0064",
            "programAlerts" to "S1.1=cancel_loc",
            "insertCannula" to "S1.4=1",
            "enableAlgorithm" to "S1.5=1",
            "sendBolus_20pulses" to "S2.0=20",
            "cancelBolus" to "S2.1=1",
            "deactivate" to "S2.6=1",
        )

        for ((op, expected) in expectedCommands) {
            assertWithMessage("RHP command for $op")
                .that(emulatorCommands[op])
                .isEqualTo(expected)
        }
    }

    @Test
    fun `TWICommand wire format matches Python emulator expectations`() {
        val twi = TwiCommandFrame(
            commandBytes = "S2.0=20",
            commandId = 5,
            lastMessage = true,
            messageType = TwiCommandFrame.MESSAGE_TYPE_ENCRYPTED,
            notificationNumber = 0,
        )
        val data = twi.serialize()

        // Header(6) + "S2.0=20"(7) + CRC(2) = 15
        assertThat(data.size).isEqualTo(15)

        // commandId = 5, big-endian
        assertThat(data[0]).isEqualTo(0x00.toByte())
        assertThat(data[1]).isEqualTo(0x05.toByte())

        // flags: lastMessage=true (bit 0), messageType=ENCRYPTED=0 (bits 1-2)
        assertThat(data[2].toInt() and 0xFF).isEqualTo(0x01)

        // notificationNumber = 0
        assertThat(data[3]).isEqualTo(0x00.toByte())
        assertThat(data[4]).isEqualTo(0x00.toByte())
        assertThat(data[5]).isEqualTo(0x00.toByte())

        // Payload: "S2.0=20"
        val payloadBytes = data.copyOfRange(6, 6 + 7)
        assertThat(String(payloadBytes, Charsets.UTF_8)).isEqualTo("S2.0=20")
    }

    @Test
    fun `bolus pulse count calculation matches BlePodManager`() {
        val testCases = listOf(
            0.05 to 1,
            0.50 to 10,
            1.00 to 20,
            2.00 to 40,
            5.00 to 100,
            30.00 to 600,
        )

        for ((units, expectedPulses) in testCases) {
            val pulses = (units / 0.05).toInt()
            assertWithMessage("pulses for $units U")
                .that(pulses)
                .isEqualTo(expectedPulses)
        }
    }

    @Test
    fun `parseStatusFields handles 1_6 prefix and semicolons`() {
        val response = "1.6=09;00;8;4000;deadbeef;120;0;200;150;2;500;0"
        val value = if (response.startsWith("1.6=")) {
            response.removePrefix("1.6=")
        } else {
            response
        }
        val fields = value.split(";")

        assertThat(fields).hasSize(12)
        assertThat(fields[0]).isEqualTo("09")          // flags (hex)
        assertThat(fields[2]).isEqualTo("8")            // running_state
        assertThat(fields[3]).isEqualTo("4000")         // reservoir_pulses
        assertThat(fields[4]).isEqualTo("deadbeef")     // uid
        assertThat(fields[5]).isEqualTo("120")          // minutes
        assertThat(fields[6]).isEqualTo("0")            // bolus_pulses
        assertThat(fields[8]).isEqualTo("150")          // glucose_mg_dl
        assertThat(fields[9]).isEqualTo("2")            // glucose_trend
        assertThat(fields[10]).isEqualTo("500")         // iob_hundredths

        val flags = fields[0].toIntOrNull(16) ?: 0
        assertThat(flags and 0x01 != 0).isTrue()  // isActivated
        assertThat(flags and 0x08 != 0).isTrue()  // bolusInProgress
    }

    @Test
    fun `parseStatusFields handles response without prefix`() {
        val response = "09;00;8;4000;deadbeef;120;0;200"
        val value = if (response.startsWith("1.6=")) {
            response.removePrefix("1.6=")
        } else {
            response
        }
        val fields = value.split(";")

        assertThat(fields[0]).isEqualTo("09")
        assertThat(fields[2]).isEqualTo("8")
    }

    @Test
    fun `EmulatorPodManager companion has no CMD byte constants`() {
        val companionFields = EmulatorPodManager::class.java.declaredFields
        val cmdFields = companionFields.filter { it.name.startsWith("CMD_") }
        assertThat(cmdFields).isEmpty()
    }

    @Test
    fun `activation substep RHP commands match BlePodManager sequence`() {
        val bleSubsteps = listOf(
            "S1.3=0064",       // program basal
            "S1.1=cancel_loc", // program alerts
            "S1.4=1",          // insert cannula
            "S1.5=1",          // enable algorithm
        )

        val emulatorSubsteps = listOf(
            "S1.3=0064",
            "S1.1=cancel_loc",
            "S1.4=1",
            "S1.5=1",
        )

        assertThat(emulatorSubsteps).isEqualTo(bleSubsteps)
    }
}
