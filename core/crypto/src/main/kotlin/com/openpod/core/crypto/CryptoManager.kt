package com.openpod.core.crypto

/**
 * High-level abstraction over all cryptographic operations required for
 * communicating with an Omnipod 5 pod.
 *
 * ## Typical usage flows
 *
 * ### First-time pairing
 * ```
 * val session = createPairingSession().getOrThrow()
 * val localData = generateLocalPairingData().getOrThrow()
 * // ... exchange localData with pod over BLE ...
 * processPeerData(peerKey, peerNonce).getOrThrow()
 * val conf = computeConfirmation().getOrThrow()
 * // ... exchange confirmation with pod ...
 * verifyConfirmation(peerConf).getOrThrow()
 * saveLtk(podId).getOrThrow()
 * ```
 *
 * ### EAP-AKA authentication (after pairing or reconnection)
 * ```
 * startEapAkaSession(podId).getOrThrow()
 * val challenge = buildEapAkaChallenge().getOrThrow()
 * // ... send challenge to pod, receive response ...
 * processEapAkaChallenge(podId, responseData).getOrThrow()
 * val success = buildEapAkaSuccess().getOrThrow()
 * // ... send success to pod ...
 * ```
 *
 * ### Per-message encryption
 * ```
 * val ciphertext = encrypt(plaintext, aad, nonce).getOrThrow()
 * val plaintext = decrypt(ciphertext, aad, nonce).getOrThrow()
 * ```
 *
 * Implementations must be thread-safe.
 */
interface CryptoManager {

    // -- Pairing --

    suspend fun createPairingSession(): Result<PairingSession>

    suspend fun generateLocalPairingData(): Result<PairingData>

    suspend fun processPeerData(peerKey: ByteArray, peerNonce: ByteArray, firmwareId: ByteArray): Result<Unit>

    suspend fun computeConfirmation(): Result<ByteArray>

    suspend fun verifyConfirmation(peerConf: ByteArray): Result<Boolean>

    suspend fun saveLtk(podId: ByteArray): Result<Unit>

    suspend fun hasLtk(podId: ByteArray): Boolean

    // -- EAP-AKA authentication --

    suspend fun startEapAkaSession(podId: ByteArray): Result<Unit>

    suspend fun buildEapAkaChallenge(): Result<ByteArray>

    suspend fun processEapAkaChallenge(podId: ByteArray, data: ByteArray): Result<Unit>

    suspend fun buildEapAkaSuccess(): Result<ByteArray>

    // -- Encryption --

    suspend fun encrypt(plaintext: ByteArray, aad: ByteArray, nonce: ByteArray): Result<ByteArray>

    suspend fun decrypt(ciphertext: ByteArray, aad: ByteArray, nonce: ByteArray): Result<ByteArray>

    suspend fun release()
}

/**
 * Opaque handle representing an active pairing session.
 */
data class PairingSession(
    val id: Long,
    val mode: KeyExchangeMode,
)

/**
 * Data exchanged during the ECDH key-exchange phase.
 *
 * @property publicKey Local ECDH public key (32 bytes for X25519).
 * @property nonce     Random nonce (16 bytes).
 */
data class PairingData(
    val publicKey: ByteArray,
    val nonce: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PairingData) return false
        return publicKey.contentEquals(other.publicKey) && nonce.contentEquals(other.nonce)
    }

    override fun hashCode(): Int = 31 * publicKey.contentHashCode() + nonce.contentHashCode()

    override fun toString(): String =
        "PairingData(publicKey=[${publicKey.size} bytes], nonce=[${nonce.size} bytes])"
}

/**
 * Key exchange mode for pairing.
 */
enum class KeyExchangeMode {
    X25519,
    P256,
}
