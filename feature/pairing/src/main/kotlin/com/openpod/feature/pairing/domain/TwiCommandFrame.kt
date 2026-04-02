package com.openpod.feature.pairing.domain

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * TWICommand BLE frame — Kotlin implementation matching the Python emulator.
 *
 * Wire format (6-byte header + UTF-8 payload):
 *   [commandId: 2 bytes BE (signed short)]
 *   [flags: 1 byte]  (bit 0 = lastMessage, bits 1-2 = messageType)
 *   [notificationNumber: 3 bytes BE (unsigned)]
 *   [commandBytes: remaining bytes, UTF-8 text]
 */
data class TwiCommandFrame(
    val commandBytes: String,
    val commandId: Int = -1,
    val lastMessage: Boolean = true,
    val messageType: Int = MESSAGE_TYPE_ENCRYPTED,
    val notificationNumber: Int = 0,
) {
    fun serialize(): ByteArray {
        val payload = commandBytes.toByteArray(Charsets.UTF_8)
        val buf = ByteBuffer.allocate(HEADER_SIZE + payload.size)
        buf.order(ByteOrder.BIG_ENDIAN)

        // commandId as signed short (2 bytes BE)
        buf.putShort(commandId.toShort())

        // flags: bit 0 = lastMessage, bits 1-2 = messageType
        var flags = 0
        if (lastMessage) flags = flags or 0x01
        flags = flags or ((messageType and 0x03) shl 1)
        buf.put(flags.toByte())

        // notificationNumber as 3 bytes BE (take last 3 bytes of int)
        buf.put(((notificationNumber shr 16) and 0xFF).toByte())
        buf.put(((notificationNumber shr 8) and 0xFF).toByte())
        buf.put((notificationNumber and 0xFF).toByte())

        // UTF-8 payload
        buf.put(payload)

        return buf.array()
    }

    companion object {
        const val HEADER_SIZE = 6
        const val MESSAGE_TYPE_ENCRYPTED = 0
        const val MESSAGE_TYPE_PRIMARY_SIGNED = 1
        const val MESSAGE_TYPE_SECONDARY_SIGNED = 2

        fun parse(data: ByteArray): TwiCommandFrame {
            require(data.size >= HEADER_SIZE) {
                "TWICommand too short: ${data.size} bytes, need at least $HEADER_SIZE"
            }

            val buf = ByteBuffer.wrap(data)
            buf.order(ByteOrder.BIG_ENDIAN)

            val commandId = buf.short.toInt()
            val flags = buf.get().toInt() and 0xFF
            val lastMessage = (flags and 0x01) != 0
            val messageType = (flags shr 1) and 0x03

            // 3 bytes BE → unsigned int
            val nn = ((buf.get().toInt() and 0xFF) shl 16) or
                ((buf.get().toInt() and 0xFF) shl 8) or
                (buf.get().toInt() and 0xFF)

            val payload = String(data, HEADER_SIZE, data.size - HEADER_SIZE, Charsets.UTF_8)

            return TwiCommandFrame(
                commandBytes = payload,
                commandId = commandId,
                lastMessage = lastMessage,
                messageType = messageType,
                notificationNumber = nn,
            )
        }
    }
}
