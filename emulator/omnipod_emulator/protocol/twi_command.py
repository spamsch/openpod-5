"""
TWICommand transport frame model.

TWICommand is the BLE-level application frame that wraps RHP text payloads
after AES-CCM encryption/decryption. Each TWICommand carries:

    - commandBytes: UTF-8 encoded RHP text (the payload)
    - commandId:    unique command identifier (-1 if unset)
    - lastMessage:  True if this is the final frame in a multi-frame exchange
    - messageType:  ENCRYPTED, PRIMARY_SIGNED, or SECONDARY_SIGNED
    - notificationNumber: BLE notification sequence (0–4,999,999)

Wire format (simplified for emulator):

    [commandId: 2 bytes BE]
    [flags: 1 byte]  (bit 0 = lastMessage, bits 1-2 = messageType)
    [notificationNumber: 3 bytes BE]
    [commandBytes: remaining bytes, UTF-8 text]

Reference: ~/Projects/personal/omnipod-connector/docs/protocol/04-data-protocol.md
"""

from __future__ import annotations

import enum
import logging
import struct
from dataclasses import dataclass

from omnipod_emulator.crypto.crc16 import crc16_bytes, crc16_ccitt

logger = logging.getLogger(__name__)


class MessageType(enum.IntEnum):
    """TWICommand message types."""

    ENCRYPTED = 0
    PRIMARY_SIGNED = 1
    SECONDARY_SIGNED = 2


@dataclass
class TWICommand:
    """
    A TWICommand frame carrying an RHP text payload.

    Attributes:
        command_bytes:       UTF-8 RHP text payload.
        command_id:          Unique command identifier (-1 if unset).
        last_message:        True if final frame in exchange.
        message_type:        The message type.
        notification_number: BLE notification sequence number.
    """

    command_bytes: str
    command_id: int = -1
    last_message: bool = True
    message_type: MessageType = MessageType.ENCRYPTED
    notification_number: int = 0

    def serialize(self) -> bytes:
        """
        Serialize this TWICommand to wire bytes.

        Returns:
            The serialized frame bytes.
        """
        flags = 0
        if self.last_message:
            flags |= 0x01
        flags |= (self.message_type & 0x03) << 1

        # notification_number as 3 bytes big-endian
        nn_bytes = struct.pack(">I", self.notification_number)[1:]  # last 3 bytes

        header = struct.pack(">h", self.command_id) + bytes([flags]) + nn_bytes
        payload = self.command_bytes.encode("utf-8")

        frame = header + payload
        # Append CRC-16/CCITT for message integrity
        return frame + crc16_bytes(frame)

    @classmethod
    def parse(cls, data: bytes) -> TWICommand:
        """
        Parse a TWICommand from wire bytes.

        Args:
            data: The raw frame bytes.

        Returns:
            The parsed TWICommand.

        Raises:
            ValueError: If the data is too short or malformed.
        """
        # Minimum: 6 header + 2 CRC = 8 bytes
        if len(data) < 8:
            raise ValueError(
                f"TWICommand too short: {len(data)} bytes, need at least 8"
            )

        # Validate CRC-16 (last 2 bytes)
        frame_data = data[:-2]
        received_crc = int.from_bytes(data[-2:], "big")
        computed_crc = crc16_ccitt(frame_data)
        if received_crc != computed_crc:
            logger.warning(
                "CRC-16 mismatch: received=0x%04x, computed=0x%04x "
                "(exact polynomial unconfirmed, proceeding anyway)",
                received_crc,
                computed_crc,
            )

        command_id = struct.unpack(">h", frame_data[0:2])[0]
        flags = frame_data[2]
        last_message = bool(flags & 0x01)
        message_type = MessageType((flags >> 1) & 0x03)

        nn_bytes = b"\x00" + frame_data[3:6]  # pad to 4 bytes
        notification_number = struct.unpack(">I", nn_bytes)[0]

        command_bytes = frame_data[6:].decode("utf-8")

        return cls(
            command_bytes=command_bytes,
            command_id=command_id,
            last_message=last_message,
            message_type=message_type,
            notification_number=notification_number,
        )

    def __repr__(self) -> str:
        return (
            f"TWICommand(id={self.command_id}, last={self.last_message}, "
            f"type={self.message_type.name}, nn={self.notification_number}, "
            f"payload={self.command_bytes!r})"
        )
