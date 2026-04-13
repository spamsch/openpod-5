package com.openpod.core.crypto.pure

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * EAP-AKA authenticator (phone-side) tests.
 *
 * Verifies the phone can generate a challenge, a simulated pod can
 * respond, and the phone can validate the response.
 */
@DisplayName("EAP-AKA Authenticator")
class EapAkaAuthenticatorTest {

    private val ltk = ByteArray(16) { (it + 1).toByte() }

    @Test
    fun `build challenge produces valid EAP-Request`() {
        val auth = EapAkaAuthenticator(ltk)
        val challenge = auth.buildChallenge()

        // EAP header: code=1 (Request), id=1, length, type=23 (AKA), subtype=1 (Challenge)
        assertThat(challenge[0]).isEqualTo(1) // EAP_REQUEST
        assertThat(challenge[1]).isEqualTo(1) // identifier
        assertThat(challenge[4]).isEqualTo(23) // EAP_TYPE_AKA
        assertThat(challenge[5]).isEqualTo(1) // AKA_CHALLENGE
        assertThat(auth.state).isEqualTo(EapAkaState.CHALLENGE_SENT)
    }

    @Test
    fun `full phone-pod EAP-AKA exchange`() {
        // Phone (authenticator) side
        val phone = EapAkaAuthenticator(ltk)
        val challenge = phone.buildChallenge()

        // Simulate pod (peer) side using the same MILENAGE
        val podResponse = simulatePodResponse(ltk, challenge)

        // Phone validates pod response
        val success = phone.processResponse(podResponse)
        assertThat(success).isTrue()
        assertThat(phone.state).isEqualTo(EapAkaState.AUTHENTICATED)
        assertThat(phone.msk).isNotNull()
        assertThat(phone.msk).hasLength(16)
    }

    @Test
    fun `wrong LTK fails authentication`() {
        val phone = EapAkaAuthenticator(ltk)
        val challenge = phone.buildChallenge()

        // Pod uses a different LTK
        val wrongLtk = ByteArray(16) { 0xFF.toByte() }
        val podResponse = simulatePodResponse(wrongLtk, challenge)

        val success = phone.processResponse(podResponse)
        assertThat(success).isFalse()
        assertThat(phone.state).isEqualTo(EapAkaState.FAILED)
    }

    @Test
    fun `build success message`() {
        val phone = EapAkaAuthenticator(ltk)
        val success = phone.buildSuccess()
        assertThat(success[0]).isEqualTo(3) // EAP_SUCCESS
        assertThat(success.size).isEqualTo(4)
    }

    /**
     * Simulate the pod (peer) side of EAP-AKA.
     *
     * Extracts RAND and AUTN from the EAP-Request, validates AUTN via
     * MILENAGE, and builds an EAP-Response with AT_RES.
     */
    private fun simulatePodResponse(podLtk: ByteArray, challenge: ByteArray): ByteArray {
        // Parse attributes from offset 8 (after EAP + AKA headers)
        val attrs = parseAttrs(challenge, 8)
        val rand = attrs[1]!!.copyOfRange(0, 16) // AT_RAND value (skip 2-byte reserved)
        val autn = attrs[2]!!.copyOfRange(0, 16) // AT_AUTN value

        val milenage = MilenageAuth(podLtk)
        val result = MilenageAuth.validateAutn(milenage, rand, autn, ByteArray(6))

        if (result is AuthValidationResult.Failed) {
            // Send auth reject
            return byteArrayOf(2, challenge[1], 0, 8, 23, 2, 0, 0) // EAP-Response/AKA-Auth-Reject
        }

        val success = result as AuthValidationResult.Success

        // Build AT_RES: [type=3, len_words, reserved(2), res_bit_len(2), RES...]
        val resBits = success.xres.size * 8
        val atResValue = byteArrayOf(
            (resBits shr 8).toByte(), (resBits and 0xFF).toByte()
        ) + success.xres
        val paddedLen = ((atResValue.size + 3) / 4) * 4
        val totalLen = 4 + paddedLen
        val atRes = ByteArray(totalLen)
        atRes[0] = 3 // AT_RES
        atRes[1] = (totalLen / 4).toByte()
        atResValue.copyInto(atRes, 4)

        // Build EAP-Response/AKA-Challenge
        val akaPayload = byteArrayOf(23, 1, 0, 0) + atRes
        val eapLen = 4 + akaPayload.size
        return byteArrayOf(2, challenge[1], (eapLen shr 8).toByte(), (eapLen and 0xFF).toByte()) + akaPayload
    }

    private fun parseAttrs(data: ByteArray, start: Int): Map<Int, ByteArray> {
        val map = mutableMapOf<Int, ByteArray>()
        var offset = start
        while (offset + 2 <= data.size) {
            val type = data[offset].toInt() and 0xFF
            val lenWords = data[offset + 1].toInt() and 0xFF
            val lenBytes = lenWords * 4
            if (lenBytes < 4 || offset + lenBytes > data.size) break
            map[type] = data.copyOfRange(offset + 4, offset + lenBytes)
            offset += lenBytes
        }
        return map
    }
}
