package com.openpod.core.crypto.pure

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * AES-CMAC tests using RFC 4493 Section 4 known-answer vectors.
 */
@DisplayName("AES-CMAC (RFC 4493)")
class AesCmacTest {

    private val key = "2b7e151628aed2a6abf7158809cf4f3c".hexToBytes()

    @Test
    fun `CMAC of empty message`() {
        val expected = "bb1d6929e95937287fa37d129b756746".hexToBytes()
        assertThat(AesCmac.compute(key, byteArrayOf())).isEqualTo(expected)
    }

    @Test
    fun `CMAC of 16 bytes`() {
        val msg = "6bc1bee22e409f96e93d7e117393172a".hexToBytes()
        val expected = "070a16b46b4d4144f79bdd9dd04a287c".hexToBytes()
        assertThat(AesCmac.compute(key, msg)).isEqualTo(expected)
    }

    @Test
    fun `CMAC of 40 bytes`() {
        val msg = (
            "6bc1bee22e409f96e93d7e117393172a" +
            "ae2d8a571e03ac9c9eb76fac45af8e51" +
            "30c81c46a35ce411"
        ).hexToBytes()
        val expected = "dfa66747de9ae63030ca32611497c827".hexToBytes()
        assertThat(AesCmac.compute(key, msg)).isEqualTo(expected)
    }

    @Test
    fun `CMAC of 64 bytes`() {
        val msg = (
            "6bc1bee22e409f96e93d7e117393172a" +
            "ae2d8a571e03ac9c9eb76fac45af8e51" +
            "30c81c46a35ce411e5fbc1191a0a52ef" +
            "f69f2445df4f9b17ad2b417be66c3710"
        ).hexToBytes()
        val expected = "51f0bebf7e3b9d92fc49741779363cfe".hexToBytes()
        assertThat(AesCmac.compute(key, msg)).isEqualTo(expected)
    }

    private fun String.hexToBytes(): ByteArray =
        chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
