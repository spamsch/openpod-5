package com.openpod.core.datastore

/**
 * Interface for secure PIN storage and verification.
 *
 * The PIN protects critical insulin delivery operations (bolus confirmation,
 * pod deactivation). It is stored as a SHA-256 hash, encrypted at rest
 * with Tink AEAD. The raw PIN is never persisted.
 */
interface PinManager {

    /**
     * Store a new PIN by hashing and encrypting it.
     *
     * Replaces any previously stored PIN. The raw PIN string is hashed with
     * SHA-256 before encryption — it is never stored in plaintext.
     *
     * @param pin The user's chosen PIN (typically 4-6 digits).
     * @throws IllegalArgumentException if the PIN is blank.
     */
    suspend fun storePin(pin: String)

    /**
     * Verify a PIN attempt against the stored hash.
     *
     * @param pin The PIN to verify.
     * @return `true` if the PIN matches the stored hash, `false` otherwise.
     *   Returns `false` if no PIN has been stored.
     */
    suspend fun verifyPin(pin: String): Boolean

    /**
     * Clear the stored PIN, effectively disabling PIN protection.
     *
     * After calling this method, [verifyPin] will return `false` for all inputs.
     */
    suspend fun clearPin()
}
