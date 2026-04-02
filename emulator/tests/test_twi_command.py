"""
Unit tests for TWICommand frame serialization and parsing.
"""

from __future__ import annotations

import pytest

from omnipod_emulator.protocol.twi_command import MessageType, TWICommand


class TestTWICommandRoundTrip:
    """Test TWICommand serialize/parse round-trip."""

    def test_basic_round_trip(self):
        twi = TWICommand(
            command_bytes="GV",
            command_id=42,
            last_message=True,
            message_type=MessageType.ENCRYPTED,
            notification_number=0,
        )
        data = twi.serialize()
        parsed = TWICommand.parse(data)

        assert parsed.command_bytes == "GV"
        assert parsed.command_id == 42
        assert parsed.last_message is True
        assert parsed.message_type == MessageType.ENCRYPTED
        assert parsed.notification_number == 0

    def test_round_trip_with_payload(self):
        twi = TWICommand(
            command_bytes="S3.9=300",
            command_id=100,
            last_message=True,
            message_type=MessageType.ENCRYPTED,
            notification_number=12345,
        )
        data = twi.serialize()
        parsed = TWICommand.parse(data)

        assert parsed.command_bytes == "S3.9=300"
        assert parsed.command_id == 100
        assert parsed.notification_number == 12345

    def test_round_trip_last_message_false(self):
        twi = TWICommand(
            command_bytes="data",
            command_id=1,
            last_message=False,
            message_type=MessageType.PRIMARY_SIGNED,
            notification_number=0,
        )
        data = twi.serialize()
        parsed = TWICommand.parse(data)

        assert parsed.last_message is False
        assert parsed.message_type == MessageType.PRIMARY_SIGNED

    def test_round_trip_batched_commands(self):
        twi = TWICommand(
            command_bytes="GV,G3.6,G3.5",
            command_id=7,
            last_message=True,
            message_type=MessageType.ENCRYPTED,
            notification_number=999999,
        )
        data = twi.serialize()
        parsed = TWICommand.parse(data)

        assert parsed.command_bytes == "GV,G3.6,G3.5"
        assert parsed.notification_number == 999999

    def test_round_trip_empty_payload(self):
        twi = TWICommand(
            command_bytes="",
            command_id=0,
            last_message=True,
            message_type=MessageType.ENCRYPTED,
            notification_number=0,
        )
        data = twi.serialize()
        parsed = TWICommand.parse(data)

        assert parsed.command_bytes == ""

    def test_round_trip_utf8_payload(self):
        twi = TWICommand(
            command_bytes="S4.0=ABCDEF123456",
            command_id=50,
            last_message=True,
            message_type=MessageType.ENCRYPTED,
            notification_number=0,
        )
        data = twi.serialize()
        parsed = TWICommand.parse(data)

        assert parsed.command_bytes == "S4.0=ABCDEF123456"

    def test_negative_command_id(self):
        twi = TWICommand(
            command_bytes="GV",
            command_id=-1,
            last_message=True,
            message_type=MessageType.ENCRYPTED,
            notification_number=0,
        )
        data = twi.serialize()
        parsed = TWICommand.parse(data)

        assert parsed.command_id == -1


class TestTWICommandParsing:
    """Test TWICommand parse edge cases."""

    def test_too_short_raises(self):
        with pytest.raises(ValueError, match="too short"):
            TWICommand.parse(b"\x00\x01\x02")

    def test_exactly_header_only(self):
        # 6-byte header + 2-byte CRC with empty payload
        twi = TWICommand(command_bytes="", command_id=0)
        data = twi.serialize()
        assert len(data) == 8  # 6 header + 2 CRC
        parsed = TWICommand.parse(data)
        assert parsed.command_bytes == ""

    def test_message_type_secondary_signed(self):
        twi = TWICommand(
            command_bytes="test",
            command_id=1,
            message_type=MessageType.SECONDARY_SIGNED,
        )
        data = twi.serialize()
        parsed = TWICommand.parse(data)
        assert parsed.message_type == MessageType.SECONDARY_SIGNED


class TestTWICommandSerialization:
    """Test TWICommand serialization details."""

    def test_header_is_6_bytes_plus_crc(self):
        twi = TWICommand(command_bytes="GV", command_id=1)
        data = twi.serialize()
        # 6 byte header + 2 bytes "GV" + 2 bytes CRC
        assert len(data) == 10

    def test_command_id_big_endian(self):
        twi = TWICommand(command_bytes="", command_id=0x0102)
        data = twi.serialize()
        assert data[0] == 0x01
        assert data[1] == 0x02
