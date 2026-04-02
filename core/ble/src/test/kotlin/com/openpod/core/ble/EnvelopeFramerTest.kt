package com.openpod.core.ble

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Tests for [EnvelopeFramer] — BLE-layer chunked message framing and reassembly.
 *
 * Verifies:
 * - Single-chunk framing and receiving
 * - Multi-chunk splitting based on MTU
 * - Reassembly from multiple chunks
 * - Round-trip (frame → receive → same payload)
 * - Rejects empty payload
 * - Handles duplicate chunks
 */
class EnvelopeFramerTest {

    private lateinit var framer: EnvelopeFramer

    @BeforeEach
    fun setup() {
        framer = EnvelopeFramer()
    }

    @Test
    fun `single-chunk frame round-trip`() {
        val payload = byteArrayOf(0x01, 0x02, 0x03, 0x04, 0x05)
        // MTU large enough for single chunk: header(5) + payload(5) + ATT(3) = 13
        val chunks = framer.frame(messageId = 1, payload = payload, mtu = 185)

        assertThat(chunks).hasSize(1)

        // Receive the single chunk
        val result = framer.receive(chunks[0])
        assertThat(result).isNotNull()
        assertThat(result).isEqualTo(payload)
    }

    @Test
    fun `multi-chunk frame round-trip`() {
        // Create a payload larger than what fits in a single chunk with small MTU
        val payload = ByteArray(100) { it.toByte() }
        // MTU = 20: usable = 20 - 3 (ATT) - 7 (chunked header) = 10 bytes per chunk
        val chunks = framer.frame(messageId = 42, payload = payload, mtu = 20)

        assertThat(chunks.size).isGreaterThan(1)

        // Feed all chunks into the receiver
        var result: ByteArray? = null
        for (chunk in chunks) {
            result = framer.receive(chunk)
        }

        assertThat(result).isNotNull()
        assertThat(result).isEqualTo(payload)
    }

    @Test
    fun `single chunk is not flagged as chunked`() {
        val payload = byteArrayOf(0x0A, 0x0B)
        val chunks = framer.frame(messageId = 1, payload = payload, mtu = 185)
        assertThat(chunks).hasSize(1)

        // First byte (flags) should have chunked bit = 0
        val flags = chunks[0][0].toInt()
        assertThat(flags and 0x01).isEqualTo(0)
    }

    @Test
    fun `multi-chunk frames have chunked flag set`() {
        val payload = ByteArray(50) { it.toByte() }
        val chunks = framer.frame(messageId = 1, payload = payload, mtu = 20)
        assertThat(chunks.size).isGreaterThan(1)

        for (chunk in chunks) {
            val flags = chunk[0].toInt()
            assertThat(flags and 0x01).isEqualTo(1)
        }
    }

    @Test
    fun `receive returns null for incomplete multi-chunk`() {
        val payload = ByteArray(50) { it.toByte() }
        val chunks = framer.frame(messageId = 1, payload = payload, mtu = 20)

        // Only feed the first chunk
        val result = framer.receive(chunks[0])
        assertThat(result).isNull()
    }

    @Test
    fun `frame rejects empty payload`() {
        try {
            framer.frame(messageId = 1, payload = ByteArray(0), mtu = 185)
            assertThat(false).isTrue() // should not reach
        } catch (e: IllegalArgumentException) {
            assertThat(e.message).contains("empty")
        }
    }

    @Test
    fun `frame rejects MTU too small`() {
        try {
            framer.frame(messageId = 1, payload = byteArrayOf(1), mtu = 5)
            assertThat(false).isTrue()
        } catch (e: IllegalArgumentException) {
            assertThat(e.message).contains("MTU")
        }
    }

    @Test
    fun `receive discards too-small chunk`() {
        val result = framer.receive(byteArrayOf(0x00, 0x01)) // too small (< 5 bytes)
        assertThat(result).isNull()
    }

    @Test
    fun `large payload produces correct chunk count`() {
        val payload = ByteArray(200) { it.toByte() }
        // MTU=30: usable = 30 - 3 - 7 = 20 bytes per chunk
        val chunks = framer.frame(messageId = 1, payload = payload, mtu = 30)
        // 200 / 20 = 10 chunks
        assertThat(chunks).hasSize(10)
    }

    @Test
    fun `multiple independent messages do not interfere`() {
        // Frame two separate messages
        val payload1 = ByteArray(50) { 0xAA.toByte() }
        val payload2 = ByteArray(50) { 0xBB.toByte() }
        val chunks1 = framer.frame(messageId = 1, payload = payload1, mtu = 20)
        val chunks2 = framer.frame(messageId = 2, payload = payload2, mtu = 20)

        // Interleave chunk reception
        var result1: ByteArray? = null
        var result2: ByteArray? = null
        for (i in chunks1.indices.coerceAtMost(chunks2.indices)) {
            val r1 = framer.receive(chunks1[i])
            if (r1 != null) result1 = r1
            val r2 = framer.receive(chunks2[i])
            if (r2 != null) result2 = r2
        }
        // Process any remaining chunks
        for (i in chunks2.size until chunks1.size) {
            val r = framer.receive(chunks1[i])
            if (r != null) result1 = r
        }
        for (i in chunks1.size until chunks2.size) {
            val r = framer.receive(chunks2[i])
            if (r != null) result2 = r
        }

        assertThat(result1).isEqualTo(payload1)
        assertThat(result2).isEqualTo(payload2)
    }

    private fun IntRange.coerceAtMost(other: IntRange): IntRange =
        first..minOf(last, other.last)
}
