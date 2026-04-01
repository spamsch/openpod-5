package com.openpod.core.protocol.session

/**
 * Interface for cryptographic operations required by the protocol layer.
 *
 * This abstracts the AES-CCM-128 encryption/decryption and message signing
 * used to secure communication with the Omnipod 5 pod. The implementation
 * lives in the `:core:crypto` module (pure Kotlin, Bouncy Castle + JCA).
 *
 * **Security note:** Implementations must handle key material securely and
 * must not log keys, nonces, or plaintext payloads.
 */
interface CryptoManager {

    /**
     * Encrypt a plaintext payload using AES-CCM-128.
     *
     * @param plaintext The data to encrypt.
     * @param nonce Per-message nonce (must be unique per message under the same key).
     * @param associatedData Additional authenticated data (AAD) included in the MAC
     *   but not encrypted.
     * @return The ciphertext with appended authentication tag.
     * @throws CryptoException if encryption fails.
     */
    fun encrypt(plaintext: ByteArray, nonce: ByteArray, associatedData: ByteArray): ByteArray

    /**
     * Decrypt and authenticate a ciphertext payload using AES-CCM-128.
     *
     * @param ciphertext The encrypted data with appended authentication tag.
     * @param nonce The nonce used during encryption.
     * @param associatedData The AAD used during encryption.
     * @return The decrypted plaintext.
     * @throws CryptoException if decryption or authentication fails.
     */
    fun decrypt(ciphertext: ByteArray, nonce: ByteArray, associatedData: ByteArray): ByteArray

    /**
     * Generate a per-message nonce from the current sequence state.
     *
     * The nonce must be unique for every message sent under the same session key.
     * Typically derived from a counter or the message sequence number.
     *
     * @param sequenceNumber The current message sequence number.
     * @return A nonce suitable for AES-CCM-128 (typically 13 bytes).
     */
    fun generateNonce(sequenceNumber: Int): ByteArray

    /**
     * True if this crypto manager has been initialized with valid session keys.
     *
     * The protocol layer should check this before attempting to send encrypted
     * commands. An uninitialized crypto manager means the EAP-AKA authentication
     * has not yet completed.
     */
    val isSessionActive: Boolean
}

/**
 * Exception thrown when a cryptographic operation fails.
 *
 * @property message Description of the failure.
 */
class CryptoException(message: String, cause: Throwable? = null) : Exception(message, cause)
