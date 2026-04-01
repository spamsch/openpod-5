package com.openpod.core.crypto.pure

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("SIM Profile Store")
class SimProfileStoreTest {

    @Test
    fun `save and retrieve LTK`() {
        val store = SimProfileStore()
        val ltk = ByteArray(16) { (it + 1).toByte() }
        val podId = byteArrayOf(0x01, 0x02, 0x03, 0x04)

        store.saveLtk(podId, ltk)

        assertThat(store.hasLtk(podId)).isTrue()
        assertThat(store.getLtk(podId)).isEqualTo(ltk)
    }

    @Test
    fun `unknown pod returns null`() {
        val store = SimProfileStore()
        assertThat(store.hasLtk(byteArrayOf(0xFF.toByte()))).isFalse()
        assertThat(store.getLtk(byteArrayOf(0xFF.toByte()))).isNull()
    }

    @Test
    fun `clear removes all profiles`() {
        val store = SimProfileStore()
        store.saveLtk(byteArrayOf(1), ByteArray(16))
        store.saveLtk(byteArrayOf(2), ByteArray(16))

        store.clear()

        assertThat(store.hasLtk(byteArrayOf(1))).isFalse()
        assertThat(store.hasLtk(byteArrayOf(2))).isFalse()
    }

    @Test
    fun `LTK survives XOR masking round-trip`() {
        val store = SimProfileStore()
        val ltk = "deadbeefcafebabe0102030405060708".hexToBytes()
        val podId = byteArrayOf(0xAA.toByte(), 0xBB.toByte())

        store.saveLtk(podId, ltk)
        val recovered = store.getLtk(podId)!!

        assertThat(recovered).isEqualTo(ltk)
    }

    private fun String.hexToBytes(): ByteArray =
        chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
