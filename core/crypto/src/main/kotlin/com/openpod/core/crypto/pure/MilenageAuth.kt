package com.openpod.core.crypto.pure

import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * MILENAGE authentication algorithm (3GPP TS 35.206).
 *
 * Port of `emulator/omnipod_emulator/crypto/milenage.py`.
 *
 * In the Omnipod 5 protocol, K = LTK and OP is all-zeros.
 */
class MilenageAuth(
    private val k: ByteArray,
    op: ByteArray = ByteArray(16),
) {
    private val opc: ByteArray

    init {
        require(k.size == 16) { "K must be 16 bytes" }
        require(op.size == 16) { "OP must be 16 bytes" }
        opc = xor(aesEncrypt(op), op)
    }

    data class AuthVector(
        val macA: ByteArray,  // 8 bytes
        val xres: ByteArray,  // 8 bytes
        val ck: ByteArray,    // 16 bytes
        val ik: ByteArray,    // 16 bytes
        val ak: ByteArray,    // 6 bytes
    )

    /**
     * Generate a full authentication vector.
     */
    fun generateAuthVector(rand: ByteArray, sqn: ByteArray, amf: ByteArray): AuthVector {
        require(rand.size == 16) { "RAND must be 16 bytes" }
        require(sqn.size == 6) { "SQN must be 6 bytes" }
        require(amf.size == 2) { "AMF must be 2 bytes" }

        return AuthVector(
            macA = f1(rand, sqn, amf),
            xres = f2(rand),
            ck = f3(rand),
            ik = f4(rand),
            ak = f5(rand),
        )
    }

    /** f1: MAC-A (8 bytes). */
    fun f1(rand: ByteArray, sqn: ByteArray, amf: ByteArray): ByteArray {
        val temp = aesEncrypt(xor(rand, opc))
        val in1 = sqn + amf + sqn + amf
        val rotated = rotateLeft(xor(in1, opc), R1)
        val out = xor(aesEncrypt(xor(rotated, temp)), opc)
        return out.copyOfRange(0, 8)
    }

    /** f2: XRES (8 bytes) and f5: AK (6 bytes) from same intermediate. */
    private fun f2f5(rand: ByteArray): Pair<ByteArray, ByteArray> {
        val temp = aesEncrypt(xor(rand, opc))
        val rotated = rotateLeft(xor(temp, opc), R2)
        val out = xor(aesEncrypt(xor(rotated, C2)), opc)
        return out.copyOfRange(8, 16) to out.copyOfRange(0, 6)
    }

    /** f2: XRES (8 bytes). */
    fun f2(rand: ByteArray): ByteArray = f2f5(rand).first

    /** f3: CK (16 bytes). */
    fun f3(rand: ByteArray): ByteArray {
        val temp = aesEncrypt(xor(rand, opc))
        val rotated = rotateLeft(xor(temp, opc), R3)
        return xor(aesEncrypt(xor(rotated, C3)), opc)
    }

    /** f4: IK (16 bytes). */
    fun f4(rand: ByteArray): ByteArray {
        val temp = aesEncrypt(xor(rand, opc))
        val rotated = rotateLeft(xor(temp, opc), R4)
        return xor(aesEncrypt(xor(rotated, C4)), opc)
    }

    /** f5: AK (6 bytes). */
    fun f5(rand: ByteArray): ByteArray = f2f5(rand).second

    private fun aesEncrypt(block: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/ECB/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(k, "AES"))
        return cipher.doFinal(block)
    }

    companion object {
        private const val R1 = 64
        private const val R2 = 0
        private const val R3 = 32
        private const val R4 = 64
        @Suppress("unused")
        private const val R5 = 96

        private val C2 = ByteArray(15) + byteArrayOf(0x01)
        private val C3 = ByteArray(15) + byteArrayOf(0x02)
        private val C4 = ByteArray(15) + byteArrayOf(0x04)

        /**
         * Build AUTN: (SQN XOR AK) || AMF || MAC-A
         */
        fun computeAutn(sqn: ByteArray, ak: ByteArray, amf: ByteArray, macA: ByteArray): ByteArray {
            val concealedSqn = xor(sqn, ak)
            return concealedSqn + amf + macA
        }

        /**
         * Validate AUTN received in an EAP-AKA challenge.
         */
        fun validateAutn(
            milenage: MilenageAuth,
            rand: ByteArray,
            autn: ByteArray,
            expectedSqn: ByteArray,
        ): AuthValidationResult {
            require(autn.size == 16) { "AUTN must be 16 bytes" }

            val ak = milenage.f5(rand)
            val sqn = xor(autn.copyOfRange(0, 6), ak)
            val amf = autn.copyOfRange(6, 8)
            val receivedMac = autn.copyOfRange(8, 16)

            val expectedMac = milenage.f1(rand, sqn, amf)
            if (!receivedMac.contentEquals(expectedMac)) {
                return AuthValidationResult.Failed
            }

            val sqnVal = sqn.fold(0L) { acc, b -> (acc shl 8) or (b.toLong() and 0xFF) }
            val expectedVal = expectedSqn.fold(0L) { acc, b -> (acc shl 8) or (b.toLong() and 0xFF) }
            if (sqnVal < expectedVal) {
                return AuthValidationResult.Failed
            }

            return AuthValidationResult.Success(
                xres = milenage.f2(rand),
                ck = milenage.f3(rand),
                ik = milenage.f4(rand),
                ak = ak,
            )
        }

        private fun rotateLeft(block: ByteArray, bits: Int): ByteArray {
            if (bits == 0) return block.copyOf()
            require(block.size == 16) { "Block must be 16 bytes" }

            val bitCount = bits % 128
            val byteShift = bitCount / 8
            val bitShift = bitCount % 8

            val result = ByteArray(16)
            for (i in 0 until 16) {
                val srcIdx = (i + byteShift) % 16
                val nextIdx = (srcIdx + 1) % 16
                result[i] = (
                    ((block[srcIdx].toInt() and 0xFF) shl bitShift) or
                        ((block[nextIdx].toInt() and 0xFF) ushr (8 - bitShift))
                    ).toByte()
            }
            return result
        }

        private fun xor(a: ByteArray, b: ByteArray): ByteArray {
            val len = minOf(a.size, b.size)
            val result = ByteArray(len)
            for (i in 0 until len) {
                result[i] = (a[i].toInt() xor b[i].toInt()).toByte()
            }
            return result
        }
    }
}

sealed interface AuthValidationResult {
    data class Success(
        val xres: ByteArray,
        val ck: ByteArray,
        val ik: ByteArray,
        val ak: ByteArray,
    ) : AuthValidationResult

    data object Failed : AuthValidationResult
}
