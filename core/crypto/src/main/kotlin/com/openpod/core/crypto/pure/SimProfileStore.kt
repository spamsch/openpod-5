package com.openpod.core.crypto.pure

import java.security.SecureRandom

/**
 * In-memory SIM profile storage for LTK persistence across sessions.
 *
 * Port of `SimProfile` from `emulator/omnipod_emulator/protocol/pairing.py`.
 *
 * Stores the LTK in a 93-byte XOR-masked format. The masked LTK,
 * firmware ID, controller ID, and random XOR mask are packed into a
 * fixed-size byte array for serialization.
 */
class SimProfileStore {

    private val profiles = mutableMapOf<String, ByteArray>()

    /**
     * Store an LTK for a pod identifier.
     */
    fun saveLtk(
        podId: ByteArray,
        ltk: ByteArray,
        firmwareId: ByteArray = ByteArray(6),
        controllerId: ByteArray = ByteArray(4),
    ) {
        require(ltk.size == 16) { "LTK must be 16 bytes" }
        val key = podId.toHexString()

        val xorMask = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val maskedLtk = ByteArray(16) { (ltk[it].toInt() xor xorMask[it].toInt()).toByte() }

        val profile = ByteArray(93)
        maskedLtk.copyInto(profile, 0x00)
        firmwareId.copyInto(profile, 0x20, 0, minOf(firmwareId.size, 6))
        controllerId.copyInto(profile, 0x36, 0, minOf(controllerId.size, 4))
        xorMask.copyInto(profile, 0x3D)

        profiles[key] = profile
    }

    /**
     * Retrieve the LTK for a pod identifier.
     *
     * @return The 16-byte LTK, or null if not stored.
     */
    fun getLtk(podId: ByteArray): ByteArray? {
        val profile = profiles[podId.toHexString()] ?: return null
        val maskedLtk = profile.copyOfRange(0x00, 0x10)
        val xorMask = profile.copyOfRange(0x3D, 0x4D)
        return ByteArray(16) { (maskedLtk[it].toInt() xor xorMask[it].toInt()).toByte() }
    }

    /**
     * Check if an LTK exists for a pod identifier.
     */
    fun hasLtk(podId: ByteArray): Boolean = profiles.containsKey(podId.toHexString())

    /**
     * Clear all stored profiles.
     */
    fun clear() {
        profiles.values.forEach { it.fill(0) }
        profiles.clear()
    }

    private fun ByteArray.toHexString(): String =
        joinToString("") { "%02x".format(it) }
}
