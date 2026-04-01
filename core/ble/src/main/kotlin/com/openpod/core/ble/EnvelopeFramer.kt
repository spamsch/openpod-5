package com.openpod.core.ble

import timber.log.Timber
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Chunked message framing for the Omnipod 5 Envelope protocol.
 *
 * The pod communicates using an Envelope protobuf wrapper that splits
 * large messages into MTU-sized chunks. Each chunk carries a header
 * with message identity and reassembly metadata.
 *
 * ## Envelope chunk format (header):
 * ```
 * Byte 0:      Flags — bit 0 = chunked, bits 1-7 reserved
 * Bytes 1-2:   Message ID (little-endian uint16)
 * Bytes 3-4:   Total content length (little-endian uint16)
 * Bytes 5-6:   Chunk index (little-endian uint16, only if chunked)
 * Bytes 7+:    Chunk payload data
 * ```
 *
 * This class is **not thread-safe**. Callers must synchronize externally
 * or confine access to a single coroutine.
 */
class EnvelopeFramer {

    /**
     * Pending reassembly state for a multi-chunk message.
     *
     * @property totalLength Expected total payload length.
     * @property chunks Received chunks indexed by chunk index.
     * @property receivedLength Running total of received bytes.
     * @property startTimeMs Timestamp when the first chunk arrived.
     */
    private data class PendingMessage(
        val totalLength: Int,
        val chunks: MutableMap<Int, ByteArray> = mutableMapOf(),
        var receivedLength: Int = 0,
        val startTimeMs: Long = System.currentTimeMillis(),
    )

    /** Pending multi-chunk messages keyed by message ID. */
    private val pendingMessages = mutableMapOf<Int, PendingMessage>()

    companion object {
        /** Header size for a non-chunked envelope. */
        private const val HEADER_SIZE_SIMPLE = 5

        /** Header size for a chunked envelope (includes chunk index). */
        private const val HEADER_SIZE_CHUNKED = 7

        /** Flags byte: bit 0 indicates the message is chunked. */
        private const val FLAG_CHUNKED: Byte = 0x01
    }

    /**
     * Split a payload into MTU-sized envelope chunks.
     *
     * @param messageId Unique message identifier (0-65535).
     * @param payload Complete message payload to send.
     * @param mtu Negotiated BLE MTU. Usable payload per chunk is
     *            `mtu - ATT_HEADER_SIZE - ENVELOPE_HEADER_SIZE`.
     * @return Ordered list of framed chunks ready for BLE write.
     * @throws IllegalArgumentException if [payload] is empty or [mtu] is too small.
     */
    fun frame(messageId: Int, payload: ByteArray, mtu: Int): List<ByteArray> {
        require(payload.isNotEmpty()) { "Payload must not be empty" }
        require(messageId in 0..0xFFFF) { "Message ID must fit in uint16, got $messageId" }

        val maxChunkPayload = mtu - BleConstants.ATT_HEADER_SIZE - HEADER_SIZE_CHUNKED
        require(maxChunkPayload > 0) {
            "MTU $mtu is too small for envelope framing (need > ${BleConstants.ATT_HEADER_SIZE + HEADER_SIZE_CHUNKED})"
        }

        val singleChunkPayload = mtu - BleConstants.ATT_HEADER_SIZE - HEADER_SIZE_SIMPLE
        if (payload.size <= singleChunkPayload) {
            // Single-chunk message — no chunked flag.
            val frame = buildSimpleFrame(messageId, payload)
            Timber.d(
                "Framed single-chunk message: id=%d, payload=%d bytes, frame=%d bytes",
                messageId, payload.size, frame.size,
            )
            return listOf(frame)
        }

        // Multi-chunk message.
        val chunks = mutableListOf<ByteArray>()
        var offset = 0
        var chunkIndex = 0

        while (offset < payload.size) {
            val chunkSize = minOf(maxChunkPayload, payload.size - offset)
            val chunkData = payload.copyOfRange(offset, offset + chunkSize)
            val frame = buildChunkedFrame(messageId, payload.size, chunkIndex, chunkData)
            chunks.add(frame)
            offset += chunkSize
            chunkIndex++
        }

        Timber.d(
            "Framed multi-chunk message: id=%d, payload=%d bytes, chunks=%d",
            messageId, payload.size, chunks.size,
        )

        return chunks
    }

    /**
     * Process a received chunk and attempt reassembly.
     *
     * @param chunk Raw bytes received from a TpClassic notification.
     * @return Complete reassembled payload if this chunk completes a message,
     *         or `null` if more chunks are needed.
     */
    fun receive(chunk: ByteArray): ByteArray? {
        purgeStaleMessages()

        if (chunk.size < HEADER_SIZE_SIMPLE) {
            Timber.w("Received chunk too small (%d bytes), discarding", chunk.size)
            return null
        }

        val buffer = ByteBuffer.wrap(chunk).order(ByteOrder.LITTLE_ENDIAN)
        val flags = buffer.get()
        val messageId = buffer.short.toInt() and 0xFFFF
        val contentLength = buffer.short.toInt() and 0xFFFF
        val isChunked = (flags.toInt() and FLAG_CHUNKED.toInt()) != 0

        if (!isChunked) {
            val data = ByteArray(chunk.size - HEADER_SIZE_SIMPLE)
            buffer.get(data)
            Timber.d(
                "Received single-chunk message: id=%d, length=%d bytes",
                messageId, data.size,
            )
            return data
        }

        if (chunk.size < HEADER_SIZE_CHUNKED) {
            Timber.w(
                "Chunked envelope too small (%d bytes), need at least %d",
                chunk.size, HEADER_SIZE_CHUNKED,
            )
            return null
        }

        val chunkIndex = buffer.short.toInt() and 0xFFFF
        val chunkData = ByteArray(chunk.size - HEADER_SIZE_CHUNKED)
        buffer.get(chunkData)

        val pending = pendingMessages.getOrPut(messageId) {
            Timber.d(
                "Starting reassembly for message id=%d, expected length=%d",
                messageId, contentLength,
            )
            PendingMessage(totalLength = contentLength)
        }

        if (pending.chunks.containsKey(chunkIndex)) {
            Timber.w(
                "Duplicate chunk index=%d for message id=%d, replacing",
                chunkIndex, messageId,
            )
            pending.receivedLength -= pending.chunks[chunkIndex]!!.size
        }

        pending.chunks[chunkIndex] = chunkData
        pending.receivedLength += chunkData.size

        Timber.d(
            "Received chunk: id=%d, index=%d, chunkSize=%d, progress=%d/%d bytes",
            messageId, chunkIndex, chunkData.size, pending.receivedLength, pending.totalLength,
        )

        if (pending.receivedLength >= pending.totalLength) {
            pendingMessages.remove(messageId)

            val reassembled = ByteArray(pending.totalLength)
            var offset = 0
            for (i in 0 until pending.chunks.size) {
                val part = pending.chunks[i]
                if (part == null) {
                    Timber.e(
                        "Missing chunk index=%d during reassembly of message id=%d",
                        i, messageId,
                    )
                    return null
                }
                part.copyInto(reassembled, offset)
                offset += part.size
            }

            Timber.i(
                "Message reassembled: id=%d, total=%d bytes from %d chunks",
                messageId, reassembled.size, pending.chunks.size,
            )
            return reassembled
        }

        return null
    }

    /**
     * Remove any pending messages that have exceeded the reassembly timeout.
     * This prevents memory leaks from lost or incomplete transmissions.
     */
    private fun purgeStaleMessages() {
        val now = System.currentTimeMillis()
        val staleIds = pendingMessages.entries
            .filter { (_, msg) -> now - msg.startTimeMs > BleConstants.ENVELOPE_REASSEMBLY_TIMEOUT_MS }
            .map { it.key }

        for (id in staleIds) {
            val msg = pendingMessages.remove(id)
            Timber.w(
                "Purged stale incomplete message: id=%d, received=%d/%d bytes, age=%dms",
                id,
                msg?.receivedLength ?: 0,
                msg?.totalLength ?: 0,
                now - (msg?.startTimeMs ?: now),
            )
        }
    }

    /** Build a single-chunk (non-chunked) envelope frame. */
    private fun buildSimpleFrame(messageId: Int, payload: ByteArray): ByteArray {
        val frame = ByteBuffer.allocate(HEADER_SIZE_SIMPLE + payload.size)
            .order(ByteOrder.LITTLE_ENDIAN)
        frame.put(0.toByte()) // flags: not chunked
        frame.putShort(messageId.toShort())
        frame.putShort(payload.size.toShort())
        frame.put(payload)
        return frame.array()
    }

    /** Build a chunked envelope frame with chunk index. */
    private fun buildChunkedFrame(
        messageId: Int,
        totalLength: Int,
        chunkIndex: Int,
        chunkData: ByteArray,
    ): ByteArray {
        val frame = ByteBuffer.allocate(HEADER_SIZE_CHUNKED + chunkData.size)
            .order(ByteOrder.LITTLE_ENDIAN)
        frame.put(FLAG_CHUNKED) // flags: chunked
        frame.putShort(messageId.toShort())
        frame.putShort(totalLength.toShort())
        frame.putShort(chunkIndex.toShort())
        frame.put(chunkData)
        return frame.array()
    }
}
