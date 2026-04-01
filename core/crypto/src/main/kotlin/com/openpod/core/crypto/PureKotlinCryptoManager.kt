package com.openpod.core.crypto

import com.openpod.core.crypto.pure.AesCcm
import com.openpod.core.crypto.pure.AesCmac
import com.openpod.core.crypto.pure.EapAkaAuthenticator
import com.openpod.core.crypto.pure.EapAkaState
import com.openpod.core.crypto.pure.MilenageAuth
import com.openpod.core.crypto.pure.OmnipodKdf
import com.openpod.core.crypto.pure.SimProfileStore
import com.openpod.core.crypto.pure.X25519KeyExchange
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Pure-Kotlin implementation of [CryptoManager].
 *
 * Replaces the native JNI-based implementation with portable crypto
 * using Bouncy Castle (X25519, AES-CCM) and standard JCA (SHA-256, AES-ECB).
 * Works on all Android ABIs (arm64, armeabi-v7a, x86_64).
 *
 * Thread-safety is ensured by a [Mutex].
 */
@Singleton
class PureKotlinCryptoManager @Inject constructor() : CryptoManager {

    private val mutex = Mutex()
    private var sessionId = 0L

    // Pairing state
    private var keyExchange: X25519KeyExchange? = null
    private var peerPublicKey: ByteArray? = null
    private var peerNonce: ByteArray? = null
    private var derivedConfirmationKey: ByteArray? = null
    private var derivedLtk: ByteArray? = null

    // SIM profile storage (persists LTKs across sessions)
    private val simProfileStore = SimProfileStore()

    // EAP-AKA state
    private var eapAka: EapAkaAuthenticator? = null

    // Encryption key (from EAP-AKA MSK)
    private var sessionKey: ByteArray? = null

    // Firmware/controller IDs for KDF (set during pairing)
    private var firmwareId: ByteArray = ByteArray(6)
    private var controllerId: ByteArray = byteArrayOf(0x01, 0x02, 0x03, 0x04)

    override suspend fun createPairingSession(): Result<PairingSession> = mutex.withLock {
        runCatching {
            Timber.i("Creating pure-Kotlin pairing session")
            releaseInternal()

            keyExchange = X25519KeyExchange()
            sessionId++

            PairingSession(id = sessionId, mode = KeyExchangeMode.X25519)
        }
    }

    override suspend fun generateLocalPairingData(): Result<PairingData> = mutex.withLock {
        runCatching {
            val kx = requireKeyExchange()
            Timber.i("Generated local pairing data: key=%d, nonce=%d bytes",
                kx.publicKeyBytes.size, kx.nonce.size)
            PairingData(publicKey = kx.publicKeyBytes, nonce = kx.nonce)
        }
    }

    override suspend fun processPeerData(
        peerKey: ByteArray,
        peerNonce: ByteArray,
        firmwareId: ByteArray,
    ): Result<Unit> = mutex.withLock {
        runCatching {
            require(peerKey.size == 32) { "Peer key must be 32 bytes" }
            require(peerNonce.size == 16) { "Peer nonce must be 16 bytes" }

            val kx = requireKeyExchange()

            this.peerPublicKey = peerKey
            this.peerNonce = peerNonce
            this.firmwareId = firmwareId

            // Compute shared secret and derive keys
            val sharedSecret = kx.computeSharedSecret(peerKey)

            val derived = OmnipodKdf.deriveKeys(
                firmwareId = firmwareId,
                controllerId = controllerId,
                phonePublicKey = kx.publicKeyBytes,
                podPublicKey = peerKey,
                sharedSecret = sharedSecret,
            )

            derivedConfirmationKey = derived.confirmationKey
            derivedLtk = derived.ltk

            Timber.i("Peer data processed, keys derived")
        }
    }

    override suspend fun computeConfirmation(): Result<ByteArray> = mutex.withLock {
        runCatching {
            val kx = requireKeyExchange()
            val confKey = derivedConfirmationKey
                ?: error("Keys not yet derived. Call processPeerData first.")
            val peerNonceLocal = peerNonce
                ?: error("Peer nonce not set")
            val peerKeyLocal = peerPublicKey
                ?: error("Peer key not set")

            // Phone confirmation: phone_nonce || pod_nonce || phone_pub || pod_pub
            val confData = kx.nonce + peerNonceLocal + kx.publicKeyBytes + peerKeyLocal
            val conf = AesCmac.compute(confKey, confData)

            Timber.i("Computed local confirmation: %d bytes", conf.size)
            conf
        }
    }

    override suspend fun verifyConfirmation(peerConf: ByteArray): Result<Boolean> = mutex.withLock {
        runCatching {
            val kx = requireKeyExchange()
            val confKey = derivedConfirmationKey
                ?: error("Keys not yet derived")
            val peerNonceLocal = peerNonce
                ?: error("Peer nonce not set")
            val peerKeyLocal = peerPublicKey
                ?: error("Peer key not set")

            // Pod confirmation: pod_nonce || phone_nonce || pod_pub || phone_pub
            val expectedData = peerNonceLocal + kx.nonce + peerKeyLocal + kx.publicKeyBytes
            val expectedConf = AesCmac.compute(confKey, expectedData)

            val matched = peerConf.contentEquals(expectedConf)
            Timber.i("Peer confirmation verification: matched=%b", matched)
            matched
        }
    }

    override suspend fun saveLtk(podId: ByteArray): Result<Unit> = mutex.withLock {
        runCatching {
            val ltk = derivedLtk ?: error("LTK not yet derived. Complete pairing first.")
            simProfileStore.saveLtk(podId, ltk, firmwareId, controllerId)
            Timber.i("LTK saved for pod (idSize=%d)", podId.size)
        }
    }

    override suspend fun hasLtk(podId: ByteArray): Boolean = mutex.withLock {
        simProfileStore.hasLtk(podId)
    }

    override suspend fun startEapAkaSession(podId: ByteArray): Result<Unit> = mutex.withLock {
        runCatching {
            val ltk = simProfileStore.getLtk(podId)
                ?: error("No LTK stored for pod. Pair first.")

            eapAka = EapAkaAuthenticator(ltk)
            Timber.i("EAP-AKA session started for pod (idSize=%d)", podId.size)
        }
    }

    override suspend fun buildEapAkaChallenge(): Result<ByteArray> = mutex.withLock {
        runCatching {
            val aka = eapAka ?: error("No active EAP-AKA session")
            val challenge = aka.buildChallenge()
            Timber.i("EAP-AKA challenge built: %d bytes", challenge.size)
            challenge
        }
    }

    override suspend fun processEapAkaChallenge(
        podId: ByteArray,
        data: ByteArray,
    ): Result<Unit> = mutex.withLock {
        runCatching {
            val aka = eapAka ?: error("No active EAP-AKA session")
            val success = aka.processResponse(data)
            if (!success) {
                error("EAP-AKA authentication failed")
            }
            sessionKey = aka.msk
            Timber.i("EAP-AKA challenge processed, session key derived")
        }
    }

    override suspend fun buildEapAkaSuccess(): Result<ByteArray> = mutex.withLock {
        runCatching {
            val aka = eapAka ?: error("No active EAP-AKA session")
            val success = aka.buildSuccess()
            Timber.i("EAP-AKA success built: %d bytes", success.size)
            success
        }
    }

    override suspend fun encrypt(
        plaintext: ByteArray,
        aad: ByteArray,
        nonce: ByteArray,
    ): Result<ByteArray> = mutex.withLock {
        runCatching {
            val key = sessionKey ?: error("No session key. Complete EAP-AKA first.")
            AesCcm.encrypt(key, plaintext, nonce, aad.takeIf { it.isNotEmpty() })
        }
    }

    override suspend fun decrypt(
        ciphertext: ByteArray,
        aad: ByteArray,
        nonce: ByteArray,
    ): Result<ByteArray> = mutex.withLock {
        runCatching {
            val key = sessionKey ?: error("No session key. Complete EAP-AKA first.")
            AesCcm.decrypt(key, ciphertext, nonce, aad.takeIf { it.isNotEmpty() })
        }
    }

    override suspend fun release() = mutex.withLock {
        releaseInternal()
    }

    private fun releaseInternal() {
        keyExchange = null
        peerPublicKey = null
        peerNonce = null
        derivedConfirmationKey?.fill(0)
        derivedConfirmationKey = null
        derivedLtk?.fill(0)
        derivedLtk = null
        eapAka = null
        sessionKey?.fill(0)
        sessionKey = null
        Timber.i("Crypto resources released")
    }

    private fun requireKeyExchange(): X25519KeyExchange =
        keyExchange ?: error("No active pairing session. Call createPairingSession first.")
}
