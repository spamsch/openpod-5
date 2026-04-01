package com.openpod.core.crypto.pure

import org.bouncycastle.crypto.engines.AESEngine
import org.bouncycastle.crypto.modes.CCMBlockCipher
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.KeyParameter

/**
 * AES-CCM-128 authenticated encryption / decryption.
 *
 * Port of `emulator/omnipod_emulator/crypto/aes_ccm.py`.
 *
 * Uses Bouncy Castle since Android's built-in JCA doesn't support CCM mode.
 */
object AesCcm {

    private const val KEY_LENGTH = 16
    private const val DEFAULT_TAG_BITS = 64 // 8 bytes

    /**
     * Encrypt and authenticate [plaintext] using AES-CCM-128.
     *
     * @param key       16-byte AES key.
     * @param plaintext Data to encrypt.
     * @param nonce     Nonce (7-13 bytes).
     * @param aad       Additional authenticated data (optional).
     * @param tagBits   Authentication tag size in bits (default 64 = 8 bytes).
     * @return Ciphertext with the authentication tag appended.
     */
    fun encrypt(
        key: ByteArray,
        plaintext: ByteArray,
        nonce: ByteArray,
        aad: ByteArray? = null,
        tagBits: Int = DEFAULT_TAG_BITS,
    ): ByteArray {
        require(key.size == KEY_LENGTH) { "Key must be $KEY_LENGTH bytes" }

        val ccm = CCMBlockCipher.newInstance(AESEngine.newInstance())
        val params = AEADParameters(KeyParameter(key), tagBits, nonce, aad)
        ccm.init(true, params)

        val output = ByteArray(ccm.getOutputSize(plaintext.size))
        var offset = ccm.processBytes(plaintext, 0, plaintext.size, output, 0)
        offset += ccm.doFinal(output, offset)

        return output.copyOf(offset)
    }

    /**
     * Decrypt and verify [ciphertext] using AES-CCM-128.
     *
     * @param key        16-byte AES key.
     * @param ciphertext Encrypted data including authentication tag.
     * @param nonce      Nonce (must match encryption nonce).
     * @param aad        Additional authenticated data (must match encryption).
     * @param tagBits    Authentication tag size in bits (default 64 = 8 bytes).
     * @return Decrypted plaintext.
     * @throws org.bouncycastle.crypto.InvalidCipherTextException if tag verification fails.
     */
    fun decrypt(
        key: ByteArray,
        ciphertext: ByteArray,
        nonce: ByteArray,
        aad: ByteArray? = null,
        tagBits: Int = DEFAULT_TAG_BITS,
    ): ByteArray {
        require(key.size == KEY_LENGTH) { "Key must be $KEY_LENGTH bytes" }

        val ccm = CCMBlockCipher.newInstance(AESEngine.newInstance())
        val params = AEADParameters(KeyParameter(key), tagBits, nonce, aad)
        ccm.init(false, params)

        val output = ByteArray(ccm.getOutputSize(ciphertext.size))
        var offset = ccm.processBytes(ciphertext, 0, ciphertext.size, output, 0)
        offset += ccm.doFinal(output, offset)

        return output.copyOf(offset)
    }
}
