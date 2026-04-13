package com.openpod.core.datastore

import android.content.SharedPreferences
import com.google.crypto.tink.Aead
import timber.log.Timber
import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Tink AEAD-backed implementation of [PinManager].
 *
 * The PIN is hashed with SHA-256, then the hash is encrypted with the Tink
 * AEAD primitive before being stored in SharedPreferences. Verification
 * decrypts the stored ciphertext and compares the SHA-256 digests.
 *
 * This two-layer approach ensures:
 * 1. The PIN cannot be recovered even if the AEAD key is compromised (SHA-256 is one-way).
 * 2. The hash cannot be brute-forced from the preference file alone (AEAD encryption).
 */
@Singleton
internal class PinManagerImpl @Inject constructor(
    private val aead: Aead,
    private val sharedPreferences: SharedPreferences,
) : PinManager {

    override suspend fun storePin(pin: String) {
        require(pin.isNotBlank()) { "PIN must not be blank" }

        val hash = sha256(pin)
        val encrypted = try {
            aead.encrypt(hash, PIN_AAD)
        } catch (e: GeneralSecurityException) {
            Timber.e(e, "Failed to encrypt PIN hash")
            throw IllegalStateException("Cannot encrypt PIN hash", e)
        }

        val encoded = Base64.getEncoder().encodeToString(encrypted)
        sharedPreferences.edit().putString(KEY_ENCRYPTED_PIN_HASH, encoded).apply()
        Timber.i("PIN stored successfully (SHA-256 hash encrypted with AEAD)")
    }

    override suspend fun verifyPin(pin: String): Boolean {
        val encoded = sharedPreferences.getString(KEY_ENCRYPTED_PIN_HASH, null)
        if (encoded == null) {
            Timber.d("PIN verification failed: no PIN stored")
            return false
        }

        return try {
            val encrypted = Base64.getDecoder().decode(encoded)
            val storedHash = aead.decrypt(encrypted, PIN_AAD)
            val inputHash = sha256(pin)
            val matches = MessageDigest.isEqual(storedHash, inputHash)
            Timber.d("PIN verification result: %s", if (matches) "match" else "mismatch")
            matches
        } catch (e: GeneralSecurityException) {
            Timber.e(e, "Failed to decrypt stored PIN hash — PIN data may be corrupted")
            false
        } catch (e: IllegalArgumentException) {
            Timber.e(e, "Failed to decode stored PIN hash — Base64 data corrupted")
            false
        }
    }

    override suspend fun hasPin(): Boolean =
        sharedPreferences.getString(KEY_ENCRYPTED_PIN_HASH, null) != null

    override suspend fun clearPin() {
        sharedPreferences.edit().remove(KEY_ENCRYPTED_PIN_HASH).apply()
        Timber.i("PIN cleared")
    }

    /**
     * Compute the SHA-256 hash of the given input string.
     *
     * @param input The string to hash.
     * @return The 32-byte SHA-256 digest.
     */
    private fun sha256(input: String): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))

    internal companion object {
        /** SharedPreferences key for the encrypted PIN hash. */
        const val KEY_ENCRYPTED_PIN_HASH = "encrypted_pin_hash"

        /** Associated data binding the AEAD ciphertext to PIN usage. */
        val PIN_AAD = "openpod-pin-hash".toByteArray()
    }
}
