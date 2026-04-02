package com.openpod.core.datastore

import android.content.SharedPreferences
import com.google.common.truth.Truth.assertThat
import com.google.crypto.tink.Aead
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.util.Base64

/**
 * Tests for [PinManagerImpl] — SHA-256 + Tink AEAD PIN storage and verification.
 *
 * Uses a passthrough AEAD (identity encrypt/decrypt) to test the hashing and
 * storage logic without real Tink key material.
 *
 * Verifies:
 * - PIN storage with SHA-256 hash + AEAD encryption
 * - Successful and failed verification
 * - Blank PIN rejection
 * - PIN clearing
 * - Graceful handling of corrupted data
 */
class PinManagerImplTest {

    private val storedValues = mutableMapOf<String, String?>()

    /** Passthrough AEAD that prepends a marker byte for encrypt and strips it for decrypt. */
    private val passthroughAead = object : Aead {
        override fun encrypt(plaintext: ByteArray, associatedData: ByteArray): ByteArray =
            byteArrayOf(0x42) + plaintext // marker + plaintext

        override fun decrypt(ciphertext: ByteArray, associatedData: ByteArray): ByteArray {
            require(ciphertext.isNotEmpty() && ciphertext[0] == 0x42.toByte()) {
                "Invalid ciphertext"
            }
            return ciphertext.copyOfRange(1, ciphertext.size)
        }
    }

    private val editor: SharedPreferences.Editor = mockk(relaxed = true) {
        every { putString(any(), any()) } answers {
            storedValues[firstArg()] = secondArg()
            this@mockk
        }
        every { remove(any()) } answers {
            storedValues.remove(firstArg())
            this@mockk
        }
    }

    private val prefs: SharedPreferences = mockk {
        every { getString(any(), any()) } answers { storedValues[firstArg()] ?: secondArg() }
        every { edit() } returns editor
    }

    private lateinit var pinManager: PinManagerImpl

    @BeforeEach
    fun setup() {
        storedValues.clear()
        pinManager = PinManagerImpl(passthroughAead, prefs)
    }

    @Test
    fun `storePin stores encrypted SHA-256 hash`() = runTest {
        pinManager.storePin("1234")

        val stored = storedValues[PinManagerImpl.KEY_ENCRYPTED_PIN_HASH]
        assertThat(stored).isNotNull()

        // Decode and strip marker byte to get the hash
        val encrypted = Base64.getDecoder().decode(stored)
        val hash = passthroughAead.decrypt(encrypted, PinManagerImpl.PIN_AAD)
        val expectedHash = MessageDigest.getInstance("SHA-256").digest("1234".toByteArray(Charsets.UTF_8))
        assertThat(hash).isEqualTo(expectedHash)
    }

    @Test
    fun `verifyPin returns true for correct PIN`() = runTest {
        pinManager.storePin("1234")
        assertThat(pinManager.verifyPin("1234")).isTrue()
    }

    @Test
    fun `verifyPin returns false for wrong PIN`() = runTest {
        pinManager.storePin("1234")
        assertThat(pinManager.verifyPin("9999")).isFalse()
    }

    @Test
    fun `verifyPin returns false when no PIN stored`() = runTest {
        assertThat(pinManager.verifyPin("1234")).isFalse()
    }

    @Test
    fun `storePin rejects blank PIN`() = runTest {
        assertThrows<IllegalArgumentException> {
            pinManager.storePin("")
        }
    }

    @Test
    fun `storePin rejects whitespace-only PIN`() = runTest {
        assertThrows<IllegalArgumentException> {
            pinManager.storePin("   ")
        }
    }

    @Test
    fun `clearPin removes stored PIN`() = runTest {
        pinManager.storePin("1234")
        assertThat(storedValues).containsKey(PinManagerImpl.KEY_ENCRYPTED_PIN_HASH)

        pinManager.clearPin()
        verify { editor.remove(PinManagerImpl.KEY_ENCRYPTED_PIN_HASH) }
    }

    @Test
    fun `verifyPin returns false after clearPin`() = runTest {
        pinManager.storePin("1234")
        pinManager.clearPin()
        assertThat(pinManager.verifyPin("1234")).isFalse()
    }

    @Test
    fun `verifyPin returns false when stored data is corrupted Base64`() = runTest {
        storedValues[PinManagerImpl.KEY_ENCRYPTED_PIN_HASH] = "!!!not-base64!!!"
        assertThat(pinManager.verifyPin("1234")).isFalse()
    }

    @Test
    fun `verifyPin returns false when AEAD decrypt fails`() = runTest {
        // Store a valid PIN first
        pinManager.storePin("1234")

        // Replace with a broken AEAD that always fails decrypt
        val brokenAead = object : Aead {
            override fun encrypt(plaintext: ByteArray, associatedData: ByteArray) = plaintext
            override fun decrypt(ciphertext: ByteArray, associatedData: ByteArray): ByteArray {
                throw GeneralSecurityException("Key corrupted")
            }
        }
        val brokenManager = PinManagerImpl(brokenAead, prefs)
        assertThat(brokenManager.verifyPin("1234")).isFalse()
    }

    @Test
    fun `different PINs produce different hashes`() = runTest {
        pinManager.storePin("1111")
        val hash1 = storedValues[PinManagerImpl.KEY_ENCRYPTED_PIN_HASH]

        pinManager.storePin("2222")
        val hash2 = storedValues[PinManagerImpl.KEY_ENCRYPTED_PIN_HASH]

        assertThat(hash1).isNotEqualTo(hash2)
    }
}
