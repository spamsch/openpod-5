package com.openpod.core.crypto.pure

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * AES-CCM-128 tests. Round-trip encryption/decryption and integrity checks.
 */
@DisplayName("AES-CCM")
class AesCcmTest {

    private val key = ByteArray(16) { (0x40 + it).toByte() }
    private val nonce = ByteArray(13) { (0x10 + it).toByte() }
    private val plaintext = ByteArray(16) { (0x20 + it).toByte() }

    @Test
    fun `encrypt then decrypt round-trip`() {
        val ciphertext = AesCcm.encrypt(key, plaintext, nonce)
        val decrypted = AesCcm.decrypt(key, ciphertext, nonce)
        assertThat(decrypted).isEqualTo(plaintext)
    }

    @Test
    fun `ciphertext includes 8-byte tag by default`() {
        val ciphertext = AesCcm.encrypt(key, plaintext, nonce)
        assertThat(ciphertext.size).isEqualTo(plaintext.size + 8)
    }

    @Test
    fun `round-trip with AAD`() {
        val aad = byteArrayOf(0x01, 0x02, 0x03)
        val ciphertext = AesCcm.encrypt(key, plaintext, nonce, aad)
        val decrypted = AesCcm.decrypt(key, ciphertext, nonce, aad)
        assertThat(decrypted).isEqualTo(plaintext)
    }

    @Test
    fun `wrong key fails decryption`() {
        val ciphertext = AesCcm.encrypt(key, plaintext, nonce)
        val wrongKey = ByteArray(16) { 0xFF.toByte() }
        assertThrows<Exception> {
            AesCcm.decrypt(wrongKey, ciphertext, nonce)
        }
    }

    @Test
    fun `tampered ciphertext fails decryption`() {
        val ciphertext = AesCcm.encrypt(key, plaintext, nonce)
        ciphertext[0] = (ciphertext[0].toInt() xor 0xFF).toByte()
        assertThrows<Exception> {
            AesCcm.decrypt(key, ciphertext, nonce)
        }
    }

    @Test
    fun `wrong AAD fails decryption`() {
        val aad = byteArrayOf(0x01, 0x02, 0x03)
        val ciphertext = AesCcm.encrypt(key, plaintext, nonce, aad)
        val wrongAad = byteArrayOf(0x04, 0x05, 0x06)
        assertThrows<Exception> {
            AesCcm.decrypt(key, ciphertext, nonce, wrongAad)
        }
    }

    @Test
    fun `different nonces produce different ciphertext`() {
        val ct1 = AesCcm.encrypt(key, plaintext, nonce)
        val nonce2 = ByteArray(13) { (0x50 + it).toByte() }
        val ct2 = AesCcm.encrypt(key, plaintext, nonce2)
        assertThat(ct1).isNotEqualTo(ct2)
    }
}
