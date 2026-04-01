package com.openpod.core.crypto.pure

import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * AES-CMAC (RFC 4493) implementation.
 *
 * Port of `_aes_cmac()` from `emulator/omnipod_emulator/protocol/pairing.py`.
 *
 * Uses AES-128-ECB as the underlying block cipher.
 */
object AesCmac {

    /**
     * Compute AES-CMAC over [data] using [key].
     *
     * @param key  16-byte AES key.
     * @param data Arbitrary-length data to MAC.
     * @return 16-byte CMAC.
     */
    fun compute(key: ByteArray, data: ByteArray): ByteArray {
        require(key.size == 16) { "Key must be 16 bytes, got ${key.size}" }

        val cipher = Cipher.getInstance("AES/ECB/NoPadding")
        val keySpec = SecretKeySpec(key, "AES")

        // Step 1: Generate subkeys K1, K2
        cipher.init(Cipher.ENCRYPT_MODE, keySpec)
        val l = cipher.doFinal(ByteArray(16))
        val k1 = double(l)
        val k2 = double(k1)

        // Step 2: Number of blocks
        var n = (data.size + 15) / 16
        val lastBlockComplete: Boolean
        if (n == 0) {
            n = 1
            lastBlockComplete = false
        } else {
            lastBlockComplete = data.size % 16 == 0
        }

        // Step 3: XOR last block with K1 or K2
        val lastBlock: ByteArray
        if (lastBlockComplete) {
            val block = data.copyOfRange((n - 1) * 16, n * 16)
            lastBlock = xor(block, k1)
        } else {
            val partial = data.copyOfRange((n - 1) * 16, data.size)
            val padded = ByteArray(16)
            partial.copyInto(padded)
            padded[partial.size] = 0x80.toByte()
            lastBlock = xor(padded, k2)
        }

        // Step 4: CBC-MAC
        var x = ByteArray(16)
        for (i in 0 until n - 1) {
            val block = data.copyOfRange(i * 16, (i + 1) * 16)
            val y = xor(x, block)
            cipher.init(Cipher.ENCRYPT_MODE, keySpec)
            x = cipher.doFinal(y)
        }

        // Final block
        val y = xor(x, lastBlock)
        cipher.init(Cipher.ENCRYPT_MODE, keySpec)
        return cipher.doFinal(y)
    }

    /**
     * Double a 128-bit value in GF(2^128) with the CMAC polynomial.
     */
    private fun double(block: ByteArray): ByteArray {
        val result = ByteArray(16)
        var carry = 0
        for (i in 15 downTo 0) {
            val b = block[i].toInt() and 0xFF
            result[i] = ((b shl 1) or carry).toByte()
            carry = b shr 7
        }
        if (carry != 0) {
            result[15] = (result[15].toInt() xor 0x87).toByte()
        }
        return result
    }

    private fun xor(a: ByteArray, b: ByteArray): ByteArray {
        val result = ByteArray(a.size)
        for (i in a.indices) {
            result[i] = (a[i].toInt() xor b[i].toInt()).toByte()
        }
        return result
    }
}
