package com.openpod.core.crypto

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Integration tests for [PureKotlinCryptoManager].
 *
 * Tests the full pairing flow through the CryptoManager interface,
 * simulating what EmulatorPodManager would do.
 */
@DisplayName("PureKotlinCryptoManager")
class PureKotlinCryptoManagerTest {

    @Test
    fun `create pairing session`() = runTest {
        val cm = PureKotlinCryptoManager()
        val session = cm.createPairingSession().getOrThrow()
        assertThat(session.mode).isEqualTo(KeyExchangeMode.X25519)
    }

    @Test
    fun `generate local pairing data produces 32-byte key and 16-byte nonce`() = runTest {
        val cm = PureKotlinCryptoManager()
        cm.createPairingSession()
        val data = cm.generateLocalPairingData().getOrThrow()
        assertThat(data.publicKey).hasLength(32)
        assertThat(data.nonce).hasLength(16)
    }

    @Test
    fun `full pairing flow with simulated pod`() = runTest {
        val cm = PureKotlinCryptoManager()

        // Phone: create session and generate keys
        cm.createPairingSession()
        val phoneData = cm.generateLocalPairingData().getOrThrow()

        // Simulate pod: generate its own key pair
        val podKx = com.openpod.core.crypto.pure.X25519KeyExchange()

        // Phone: process pod's data (derives shared secret + keys)
        cm.processPeerData(podKx.publicKeyBytes, podKx.nonce, ByteArray(6)).getOrThrow()

        // Phone: compute confirmation
        val phoneConf = cm.computeConfirmation().getOrThrow()
        assertThat(phoneConf).hasLength(16)

        // Simulate pod: compute shared secret and its own confirmation
        val sharedSecret = podKx.computeSharedSecret(phoneData.publicKey)
        val podDerived = com.openpod.core.crypto.pure.OmnipodKdf.deriveKeys(
            firmwareId = ByteArray(6),
            controllerId = byteArrayOf(0x01, 0x02, 0x03, 0x04),
            phonePublicKey = phoneData.publicKey,
            podPublicKey = podKx.publicKeyBytes,
            sharedSecret = sharedSecret,
        )
        // Pod confirmation: pod_nonce || phone_nonce || pod_pub || phone_pub
        val podConfData = podKx.nonce + phoneData.nonce +
            podKx.publicKeyBytes + phoneData.publicKey
        val podConf = com.openpod.core.crypto.pure.AesCmac.compute(
            podDerived.confirmationKey, podConfData
        )

        // Phone: verify pod's confirmation
        val verified = cm.verifyConfirmation(podConf).getOrThrow()
        assertThat(verified).isTrue()

        // Phone: save LTK
        val podId = byteArrayOf(0x01, 0x02, 0x03, 0x04)
        cm.saveLtk(podId).getOrThrow()
        assertThat(cm.hasLtk(podId)).isTrue()

        cm.release()
    }

    @Test
    fun `hasLtk returns false for unknown pod`() = runTest {
        val cm = PureKotlinCryptoManager()
        assertThat(cm.hasLtk(byteArrayOf(0xFF.toByte()))).isFalse()
    }

    @Test
    fun `release clears state`() = runTest {
        val cm = PureKotlinCryptoManager()
        cm.createPairingSession()
        cm.release()
        // After release, generating data should fail
        val result = cm.generateLocalPairingData()
        assertThat(result.isFailure).isTrue()
    }
}
