package com.openpod.core.protocol.framing

import timber.log.Timber
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

/**
 * Simplified Envelope message for chunked framing over BLE.
 *
 * In the Omnipod 5 protocol, large messages are split into multiple BLE
 * writes using an Envelope wrapper. Each envelope carries metadata about
 * the overall message and the chunk's position within it.
 *
 * This is a simplified data class standing in for the full protobuf-generated
 * message. It will be replaced by Wire codegen when the exact .proto files
 * are finalized.
 *
 * @property id Unique message identifier.
 * @property contentLength Total content length across all chunks (bytes).
 * @property chunked True if the message is split across multiple envelopes.
 * @property chunkIndex Zero-based index of this chunk within the message.
 * @property data The payload bytes for this chunk.
 */
data class Envelope(
    val id: String,
    val contentLength: Int,
    val chunked: Boolean,
    val chunkIndex: Int,
    val data: ByteArray,
) {
    override fun equals(other: Any?): Boolean =
        other is Envelope && id == other.id && chunkIndex == other.chunkIndex &&
            contentLength == other.contentLength && data.contentEquals(other.data)

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + contentLength
        result = 31 * result + chunkIndex
        result = 31 * result + data.contentHashCode()
        return result
    }
}

/**
 * Handles chunked framing and reassembly of Omnipod 5 protocol messages.
 *
 * **Sending:** Splits large payloads into MTU-sized chunks, each wrapped in
 * an [Envelope] with appropriate metadata.
 *
 * **Receiving:** Reassembles chunked envelopes back into complete payloads,
 * buffering partial messages until all chunks arrive.
 *
 * @property maxChunkSize Maximum payload size per envelope chunk (MTU - overhead).
 */
class EnvelopeFramer(
    private val maxChunkSize: Int = DEFAULT_CHUNK_SIZE,
) {

    /** Buffer of in-progress message reassemblies, keyed by message ID. */
    private val reassemblyBuffer = mutableMapOf<String, ReassemblyState>()

    /**
     * Frame a payload into one or more [Envelope] chunks for BLE transmission.
     *
     * @param payload The complete message payload to frame.
     * @return Ordered list of envelopes. Single-element list if the payload
     *   fits in one chunk.
     */
    fun frame(payload: ByteArray): List<Envelope> {
        val messageId = UUID.randomUUID().toString()
        val chunked = payload.size > maxChunkSize

        if (!chunked) {
            Timber.d("Framing single-chunk message: id=%s, length=%d", messageId, payload.size)
            return listOf(
                Envelope(
                    id = messageId,
                    contentLength = payload.size,
                    chunked = false,
                    chunkIndex = 0,
                    data = payload.copyOf(),
                )
            )
        }

        val chunks = payload.toList().chunked(maxChunkSize)
        Timber.d(
            "Framing multi-chunk message: id=%s, totalLength=%d, chunks=%d",
            messageId, payload.size, chunks.size,
        )

        return chunks.mapIndexed { index, chunkData ->
            Envelope(
                id = messageId,
                contentLength = payload.size,
                chunked = true,
                chunkIndex = index,
                data = chunkData.toByteArray(),
            )
        }
    }

    /**
     * Feed a received [Envelope] into the reassembly buffer.
     *
     * @param envelope The received envelope chunk.
     * @return The complete reassembled payload if all chunks have arrived,
     *   or null if more chunks are expected.
     */
    fun receive(envelope: Envelope): ByteArray? {
        if (!envelope.chunked) {
            Timber.d("Received single-chunk message: id=%s, length=%d", envelope.id, envelope.data.size)
            return envelope.data
        }

        val state = reassemblyBuffer.getOrPut(envelope.id) {
            Timber.d(
                "Starting reassembly: id=%s, contentLength=%d",
                envelope.id, envelope.contentLength,
            )
            ReassemblyState(
                contentLength = envelope.contentLength,
                chunks = mutableMapOf(),
            )
        }

        state.chunks[envelope.chunkIndex] = envelope.data

        val receivedBytes = state.chunks.values.sumOf { it.size }
        Timber.d(
            "Reassembly progress: id=%s, chunk=%d, received=%d/%d bytes",
            envelope.id, envelope.chunkIndex, receivedBytes, state.contentLength,
        )

        if (receivedBytes >= state.contentLength) {
            reassemblyBuffer.remove(envelope.id)
            val assembled = ByteBuffer.allocate(state.contentLength)
            for (i in 0 until state.chunks.size) {
                val chunk = state.chunks[i]
                    ?: throw IllegalStateException("Missing chunk $i for message ${envelope.id}")
                assembled.put(chunk)
            }
            Timber.d("Reassembly complete: id=%s, length=%d", envelope.id, state.contentLength)
            return assembled.array()
        }

        return null
    }

    /**
     * Discard any incomplete reassembly buffers.
     *
     * Call this when a connection is closed or a reassembly timeout expires.
     */
    fun clearBuffers() {
        if (reassemblyBuffer.isNotEmpty()) {
            Timber.d("Clearing %d incomplete reassembly buffers", reassemblyBuffer.size)
            reassemblyBuffer.clear()
        }
    }

    /**
     * Serialize an [Envelope] to bytes for BLE transmission.
     *
     * This is a simplified binary encoding. It will be replaced by Wire
     * protobuf serialization when the .proto files are finalized.
     *
     * Layout:
     * ```
     * [idLength: 1] [id: variable] [contentLength: 4] [chunked: 1]
     * [chunkIndex: 4] [dataLength: 4] [data: variable]
     * ```
     */
    fun serialize(envelope: Envelope): ByteArray {
        val idBytes = envelope.id.toByteArray(Charsets.UTF_8)
        val buffer = ByteBuffer.allocate(1 + idBytes.size + 4 + 1 + 4 + 4 + envelope.data.size)
            .order(ByteOrder.BIG_ENDIAN)
            .put(idBytes.size.toByte())
            .put(idBytes)
            .putInt(envelope.contentLength)
            .put(if (envelope.chunked) 1.toByte() else 0.toByte())
            .putInt(envelope.chunkIndex)
            .putInt(envelope.data.size)
            .put(envelope.data)
        return buffer.array()
    }

    /**
     * Deserialize bytes from BLE into an [Envelope].
     *
     * @throws IllegalArgumentException if the data is malformed.
     */
    fun deserialize(data: ByteArray): Envelope {
        val buffer = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN)

        val idLength = buffer.get().toInt() and 0xFF
        val idBytes = ByteArray(idLength)
        buffer.get(idBytes)
        val id = String(idBytes, Charsets.UTF_8)

        val contentLength = buffer.int
        val chunked = buffer.get() != 0.toByte()
        val chunkIndex = buffer.int
        val dataLength = buffer.int
        val payload = ByteArray(dataLength)
        buffer.get(payload)

        return Envelope(
            id = id,
            contentLength = contentLength,
            chunked = chunked,
            chunkIndex = chunkIndex,
            data = payload,
        )
    }

    private data class ReassemblyState(
        val contentLength: Int,
        val chunks: MutableMap<Int, ByteArray>,
    )

    companion object {
        /**
         * Default maximum chunk size, derived from the Omnipod 5 BLE MTU
         * minus ATT header and envelope overhead.
         */
        const val DEFAULT_CHUNK_SIZE = 160
    }
}
