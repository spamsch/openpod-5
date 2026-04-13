package com.openpod.core.crypto.pure

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * X25519 key exchange tests using known-answer reference vectors.
 */
@DisplayName("X25519 Key Exchange")
class X25519KeyExchangeTest {

    @Test
    fun `keygen from seed matches reference vector`() {
        val seed = "4042424242424242424242424242424242424242424242424242424242424242".hexToBytes()
        val kx = X25519KeyExchange(seed)
        assertThat(kx.publicKeyBytes).isEqualTo(
            "132c442be010fbd57e72603328aa76e71fccc1503aae219327d14d9c9993f472".hexToBytes()
        )
    }

    @Test
    fun `shared secret agreement matches native vector`() {
        val aliceSeed = "1011111111111111111111111111111111111111111111111111111111111151".hexToBytes()
        val bobSeed = "2022222222222222222222222222222222222222222222222222222222222262".hexToBytes()

        val alice = X25519KeyExchange(aliceSeed)
        val bob = X25519KeyExchange(bobSeed)

        assertThat(alice.publicKeyBytes).isEqualTo(
            "7b4e909bbe7ffe44c465a220037d608ee35897d31ef972f07f74892cb0f73f13".hexToBytes()
        )
        assertThat(bob.publicKeyBytes).isEqualTo(
            "0faa684ed28867b97f4a6a2dee5df8ce974e76b7018e3f22a1c4cf2678570f20".hexToBytes()
        )

        val aliceShared = alice.computeSharedSecret(bob.publicKeyBytes)
        val bobShared = bob.computeSharedSecret(alice.publicKeyBytes)

        assertThat(aliceShared).isEqualTo(bobShared)
        assertThat(aliceShared).isEqualTo(
            "9e004098efc091d4ec2663b4e9f5cfd4d7064571690b4bea97ab146ab9f35056".hexToBytes()
        )
    }

    @Test
    fun `random keygen produces 32-byte key and 16-byte nonce`() {
        val kx = X25519KeyExchange()
        assertThat(kx.publicKeyBytes).hasLength(32)
        assertThat(kx.nonce).hasLength(16)
    }

    @Test
    fun `two random parties derive same shared secret`() {
        val alice = X25519KeyExchange()
        val bob = X25519KeyExchange()

        val aliceShared = alice.computeSharedSecret(bob.publicKeyBytes)
        val bobShared = bob.computeSharedSecret(alice.publicKeyBytes)

        assertThat(aliceShared).isEqualTo(bobShared)
    }

    private fun String.hexToBytes(): ByteArray =
        chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
