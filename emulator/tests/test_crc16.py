"""
Unit tests for CRC-16/CCITT implementation.
"""

from __future__ import annotations

from omnipod_emulator.crypto.crc16 import crc16_bytes, crc16_ccitt


class TestCrc16Ccitt:
    """Test CRC-16/CCITT computation."""

    def test_empty_data(self):
        assert crc16_ccitt(b"") == 0xFFFF

    def test_single_byte(self):
        result = crc16_ccitt(b"\x00")
        assert isinstance(result, int)
        assert 0 <= result <= 0xFFFF

    def test_known_vector_123456789(self):
        # CRC-16/CCITT-FALSE of "123456789" = 0x29B1
        data = b"123456789"
        assert crc16_ccitt(data) == 0x29B1

    def test_deterministic(self):
        data = b"\xDE\xAD\xBE\xEF"
        assert crc16_ccitt(data) == crc16_ccitt(data)

    def test_different_data_different_crc(self):
        assert crc16_ccitt(b"\x01\x02") != crc16_ccitt(b"\x03\x04")

    def test_custom_init_value(self):
        result = crc16_ccitt(b"\x00", init=0x0000)
        assert result != crc16_ccitt(b"\x00", init=0xFFFF)


class TestCrc16Bytes:
    """Test big-endian 2-byte output."""

    def test_returns_2_bytes(self):
        result = crc16_bytes(b"test")
        assert len(result) == 2

    def test_big_endian_encoding(self):
        crc = crc16_ccitt(b"test")
        result = crc16_bytes(b"test")
        assert result == crc.to_bytes(2, "big")

    def test_empty_data(self):
        result = crc16_bytes(b"")
        assert result == b"\xFF\xFF"  # init value for empty
