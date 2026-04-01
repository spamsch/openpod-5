package com.openpod.core.crypto.pure

import org.bouncycastle.crypto.agreement.X25519Agreement
import org.bouncycastle.crypto.generators.X25519KeyPairGenerator
import org.bouncycastle.crypto.params.X25519KeyGenerationParameters
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import java.security.SecureRandom

/**
 * X25519 Elliptic-Curve Diffie-Hellman key exchange.
 *
 * Port of `emulator/omnipod_emulator/crypto/ecdh.py`.
 *
 * Generates a random X25519 key pair and 16-byte nonce, then computes
 * the 32-byte shared secret from the peer's public key.
 */
class X25519KeyExchange(seed: ByteArray? = null) {

    private val privateKey: X25519PrivateKeyParameters
    val publicKeyBytes: ByteArray
    val nonce: ByteArray

    init {
        if (seed != null) {
            require(seed.size == 32) { "Seed must be 32 bytes, got ${seed.size}" }
            privateKey = X25519PrivateKeyParameters(seed, 0)
        } else {
            val generator = X25519KeyPairGenerator()
            generator.init(X25519KeyGenerationParameters(SecureRandom()))
            val keyPair = generator.generateKeyPair()
            privateKey = keyPair.private as X25519PrivateKeyParameters
        }
        publicKeyBytes = privateKey.generatePublicKey().encoded
        nonce = ByteArray(NONCE_LENGTH).also { SecureRandom().nextBytes(it) }
    }

    /**
     * Compute the shared secret from the peer's 32-byte public key.
     */
    fun computeSharedSecret(peerPublicKey: ByteArray): ByteArray {
        require(peerPublicKey.size == PUBLIC_KEY_LENGTH) {
            "Peer public key must be $PUBLIC_KEY_LENGTH bytes, got ${peerPublicKey.size}"
        }
        val peerKey = X25519PublicKeyParameters(peerPublicKey, 0)
        val agreement = X25519Agreement()
        agreement.init(privateKey)
        val secret = ByteArray(agreement.agreementSize)
        agreement.calculateAgreement(peerKey, secret, 0)
        return secret
    }

    companion object {
        const val PUBLIC_KEY_LENGTH = 32
        const val NONCE_LENGTH = 16
    }
}
