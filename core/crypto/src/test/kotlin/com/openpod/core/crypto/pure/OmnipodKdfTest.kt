package com.openpod.core.crypto.pure

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * KDF tests using known-answer reference vectors.
 *
 * Vectors are computed from the protocol's length-prefixed SHA-256 KDF
 * input format with fixed test inputs.
 */
@DisplayName("Omnipod KDF")
class OmnipodKdfTest {

    @Test
    fun `KDF produces expected conf_key and LTK from reference vector`() {
        val result = OmnipodKdf.deriveKeys(
            firmwareId = "aabbccddeeff".hexToBytes(),
            controllerId = "01020304".hexToBytes(),
            phonePublicKey = ByteArray(32) { 0x11 },
            podPublicKey = ByteArray(32) { 0x22 },
            sharedSecret = ByteArray(32) { 0x33 },
        )

        assertThat(result.confirmationKey).isEqualTo(
            "88324eb940e4da749a55cfba967045dc".hexToBytes()
        )
        assertThat(result.ltk).isEqualTo(
            "5b000a5a9e419f9281e49e6d1e639c14".hexToBytes()
        )
    }

    @Test
    fun `different inputs produce different keys`() {
        val a = OmnipodKdf.deriveKeys(
            firmwareId = ByteArray(6) { 0x01 },
            controllerId = ByteArray(4) { 0x01 },
            phonePublicKey = ByteArray(32) { 0x01 },
            podPublicKey = ByteArray(32) { 0x02 },
            sharedSecret = ByteArray(32) { 0x03 },
        )
        val b = OmnipodKdf.deriveKeys(
            firmwareId = ByteArray(6) { 0x04 },
            controllerId = ByteArray(4) { 0x05 },
            phonePublicKey = ByteArray(32) { 0x06 },
            podPublicKey = ByteArray(32) { 0x07 },
            sharedSecret = ByteArray(32) { 0x08 },
        )
        assertThat(a.confirmationKey).isNotEqualTo(b.confirmationKey)
        assertThat(a.ltk).isNotEqualTo(b.ltk)
    }

    @Test
    fun `output lengths are 16 bytes each`() {
        val result = OmnipodKdf.deriveKeys(
            firmwareId = ByteArray(6),
            controllerId = ByteArray(4),
            phonePublicKey = ByteArray(32),
            podPublicKey = ByteArray(32),
            sharedSecret = ByteArray(32),
        )
        assertThat(result.confirmationKey).hasLength(16)
        assertThat(result.ltk).hasLength(16)
    }

    private fun String.hexToBytes(): ByteArray =
        chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
