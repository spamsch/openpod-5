package com.openpod.core.crypto.pure

import java.security.MessageDigest

/**
 * SHA-256 Key Derivation Function for Omnipod 5 LTK derivation.
 *
 * SHA-256 based KDF for Omnipod 5 LTK derivation.
 *
 * The hash input is length-prefixed protocol identifiers, public keys,
 * and the ECDH shared secret. Output is split into confirmation_key[0:16]
 * and LTK[16:32].
 *
 * Key ordering is the same for both phone and pod roles: phone_key first,
 * then pod_key.
 */
object OmnipodKdf {

    data class DerivedKeys(
        val confirmationKey: ByteArray,
        val ltk: ByteArray,
    )

    /**
     * Derive confirmation key and LTK from the ECDH shared secret.
     *
     * @param firmwareId     Pod firmware/identity bytes (6 bytes).
     * @param controllerId   Phone controller ID (4 bytes).
     * @param phonePublicKey Phone's X25519 public key (32 bytes).
     * @param podPublicKey   Pod's X25519 public key (32 bytes).
     * @param sharedSecret   The 32-byte X25519 shared secret.
     */
    fun deriveKeys(
        firmwareId: ByteArray,
        controllerId: ByteArray,
        phonePublicKey: ByteArray,
        podPublicKey: ByteArray,
        sharedSecret: ByteArray,
    ): DerivedKeys {
        require(firmwareId.size == 6) { "firmwareId must be 6 bytes" }
        require(controllerId.size == 4) { "controllerId must be 4 bytes" }
        require(phonePublicKey.size == 32) { "phonePublicKey must be 32 bytes" }
        require(podPublicKey.size == 32) { "podPublicKey must be 32 bytes" }
        require(sharedSecret.size == 32) { "sharedSecret must be 32 bytes" }

        val digest = MessageDigest.getInstance("SHA-256")

        digest.update(lengthPrefix(firmwareId))
        digest.update(firmwareId)

        digest.update(lengthPrefix(controllerId))
        digest.update(controllerId)

        // Phone key first, pod key second (same for both roles)
        digest.update(lengthPrefix(phonePublicKey))
        digest.update(phonePublicKey)

        digest.update(lengthPrefix(podPublicKey))
        digest.update(podPublicKey)

        digest.update(lengthPrefix(sharedSecret))
        digest.update(sharedSecret)

        val hash = digest.digest() // 32 bytes

        return DerivedKeys(
            confirmationKey = hash.copyOfRange(0, 16),
            ltk = hash.copyOfRange(16, 32),
        )
    }

    /**
     * 8-byte length prefix: `[00 00 00 00 len_hi len_lo 00 00]`
     */
    private fun lengthPrefix(data: ByteArray): ByteArray {
        val len = data.size
        return byteArrayOf(0, 0, 0, 0, (len shr 8).toByte(), (len and 0xFF).toByte(), 0, 0)
    }
}
