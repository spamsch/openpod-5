package com.openpod.core.crypto.pure

import java.security.SecureRandom

/**
 * EAP-AKA protocol handler — phone (authenticator) side.
 *
 * Phone (authenticator) side of EAP-AKA. The Python emulator implements
 * the pod (peer) side; this implements the complementary phone side.
 *
 * The phone:
 * 1. Generates RAND and computes auth vector via MILENAGE(K=LTK)
 * 2. Sends EAP-Request/AKA-Challenge with AT_RAND + AT_AUTN
 * 3. Receives EAP-Response/AKA-Challenge with AT_RES
 * 4. Validates AT_RES against expected XRES
 * 5. Derives MSK = SHA-256(CK || IK)[0:16]
 */
class EapAkaAuthenticator(ltk: ByteArray) {

    private val milenage = MilenageAuth(ltk)
    private var sqn: ByteArray = ByteArray(6)
    private var expectedXres: ByteArray? = null
    private var ck: ByteArray? = null
    private var ik: ByteArray? = null

    var state: EapAkaState = EapAkaState.IDLE
        private set

    var msk: ByteArray? = null
        private set

    /**
     * Build the EAP-Request/AKA-Challenge message to send to the pod.
     *
     * @return The complete EAP message bytes.
     */
    fun buildChallenge(): ByteArray {
        val rand = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val amf = ByteArray(2)

        val av = milenage.generateAuthVector(rand, sqn, amf)
        val autn = MilenageAuth.computeAutn(sqn, av.ak, amf, av.macA)

        expectedXres = av.xres
        ck = av.ck
        ik = av.ik

        // Increment SQN
        val sqnVal = sqn.fold(0L) { acc, b -> (acc shl 8) or (b.toLong() and 0xFF) } + 1
        sqn = ByteArray(6).also {
            for (i in 5 downTo 0) {
                it[i] = (sqnVal shr ((5 - i) * 8)).toByte()
            }
        }

        // Build EAP-Request/AKA-Challenge
        val atRand = buildAttribute(AT_RAND, rand)
        val atAutn = buildAttribute(AT_AUTN, autn)
        val attrs = atRand + atAutn

        val akaPayload = byteArrayOf(EAP_TYPE_AKA, AKA_CHALLENGE, 0, 0) + attrs
        val totalLength = 4 + akaPayload.size
        val header = byteArrayOf(
            EAP_REQUEST,
            1, // identifier
            (totalLength shr 8).toByte(),
            (totalLength and 0xFF).toByte(),
        )

        state = EapAkaState.CHALLENGE_SENT
        return header + akaPayload
    }

    /**
     * Process the EAP-Response/AKA-Challenge from the pod.
     *
     * Extracts AT_RES and validates against expected XRES.
     *
     * @param data Raw EAP-Response message bytes.
     * @return `true` if authentication succeeded.
     */
    fun processResponse(data: ByteArray): Boolean {
        if (data.size < 8) return false

        val code = data[0]
        if (code != EAP_RESPONSE) return false

        val eapType = data[4]
        val subtype = data[5]

        if (eapType != EAP_TYPE_AKA) return false

        if (subtype == AKA_AUTHENTICATION_REJECT) {
            state = EapAkaState.FAILED
            return false
        }

        if (subtype != AKA_CHALLENGE) return false

        // Parse attributes
        val attrs = parseAttributes(data, 8)
        val atRes = attrs[AT_RES.toInt() and 0xFF] ?: return false

        // AT_RES value starts with 2-byte bit-length, then the RES
        if (atRes.size < 2) return false
        val resBitLen = ((atRes[0].toInt() and 0xFF) shl 8) or (atRes[1].toInt() and 0xFF)
        val resByteLen = resBitLen / 8
        if (atRes.size < 2 + resByteLen) return false
        val res = atRes.copyOfRange(2, 2 + resByteLen)

        // Validate against expected XRES
        val xres = expectedXres ?: return false
        if (!res.contentEquals(xres)) {
            state = EapAkaState.FAILED
            return false
        }

        // Encryption key = CK directly (per TWI SDK behaviour)
        msk = ck!!.copyOf()

        state = EapAkaState.AUTHENTICATED
        return true
    }

    /**
     * Build the EAP-Success message to send to the pod.
     */
    fun buildSuccess(): ByteArray {
        return byteArrayOf(EAP_SUCCESS, 2, 0, 4)
    }

    private fun parseAttributes(data: ByteArray, startOffset: Int): Map<Int, ByteArray> {
        val attrs = mutableMapOf<Int, ByteArray>()
        var offset = startOffset
        while (offset + 2 <= data.size) {
            val attrType = data[offset].toInt() and 0xFF
            val attrLenWords = data[offset + 1].toInt() and 0xFF
            val attrLenBytes = attrLenWords * 4
            if (attrLenBytes < 4 || offset + attrLenBytes > data.size) break
            val value = data.copyOfRange(offset + 4, offset + attrLenBytes)
            attrs[attrType] = value
            offset += attrLenBytes
        }
        return attrs
    }

    private fun buildAttribute(attrType: Byte, value: ByteArray): ByteArray {
        val paddedLen = ((value.size + 3) / 4) * 4
        val totalLen = 4 + paddedLen
        val lenWords = totalLen / 4
        val result = ByteArray(totalLen)
        result[0] = attrType
        result[1] = lenWords.toByte()
        value.copyInto(result, 4)
        return result
    }

    companion object {
        const val EAP_REQUEST: Byte = 1
        const val EAP_RESPONSE: Byte = 2
        const val EAP_SUCCESS: Byte = 3
        const val EAP_TYPE_AKA: Byte = 23
        const val AKA_CHALLENGE: Byte = 1
        const val AKA_AUTHENTICATION_REJECT: Byte = 2
        const val AT_RAND: Byte = 1
        const val AT_AUTN: Byte = 2
        const val AT_RES: Byte = 3
    }
}

enum class EapAkaState {
    IDLE,
    CHALLENGE_SENT,
    AUTHENTICATED,
    FAILED,
}
