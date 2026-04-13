"""
Tests for the SP/SPS command codec.

Verifies encode/decode round-trips, comma-separated parsing,
indexed SPS2 names, and plain-text command handling — all matching
the wire format observed in the controller pairing flow.
"""

import struct

import pytest

from omnipod_emulator.protocol.sp_codec import (
    decode_sp,
    encode_sp,
    parse_sp_commands,
)


class TestEncode:
    """Tests for encode_sp."""

    def test_encode_basic(self) -> None:
        result = encode_sp("SP1=", b"\x01\x02\x03\x04")
        assert result == b"SP1=" + b"\x00\x04" + b"\x01\x02\x03\x04"

    def test_encode_empty_value(self) -> None:
        result = encode_sp("SPS4=", b"")
        assert result == b"SPS4=" + b"\x00\x00"

    def test_encode_length_is_big_endian(self) -> None:
        value = b"\xff" * 300
        result = encode_sp("SPS1=", value)
        # 300 = 0x012C
        assert result[5:7] == b"\x01\x2c"
        assert result[7:] == value


class TestDecode:
    """Tests for decode_sp."""

    def test_decode_basic(self) -> None:
        frame = b"SP1=" + struct.pack(">H", 4) + b"\xaa\xbb\xcc\xdd"
        value = decode_sp(frame, name_offset=4)
        assert value == b"\xaa\xbb\xcc\xdd"

    def test_decode_sps0_response(self) -> None:
        inner = b"\x01\x00\x00\xa2\x18"  # version + status + algo + crc16
        frame = b"SPS0=" + struct.pack(">H", 5) + inner
        value = decode_sp(frame, name_offset=5)
        assert value == inner

    def test_decode_length_mismatch_returns_none(self) -> None:
        # Claim 10 bytes but only provide 4
        frame = b"SP1=" + struct.pack(">H", 10) + b"\x01\x02\x03\x04"
        value = decode_sp(frame, name_offset=4)
        assert value is None

    def test_decode_truncated_length_field(self) -> None:
        # Only 1 byte where 2-byte length is expected
        frame = b"SP1=\x00"
        value = decode_sp(frame, name_offset=4)
        assert value is None


class TestEncodeDecodeRoundTrip:
    """Encode then decode should recover the original value."""

    def test_roundtrip_sp1(self) -> None:
        original = b"\x01\x02\x03\x04"
        frame = encode_sp("SP1=", original)
        recovered = decode_sp(frame, name_offset=4)
        assert recovered == original

    def test_roundtrip_sps2_indexed(self) -> None:
        original = b"\xaa" * 72
        frame = encode_sp("SPS2.1=", original)
        recovered = decode_sp(frame, name_offset=7)
        assert recovered == original

    def test_roundtrip_large_value(self) -> None:
        original = bytes(range(256)) * 4
        frame = encode_sp("SPS1=", original)
        recovered = decode_sp(frame, name_offset=5)
        assert recovered == original


class TestParseSpCommands:
    """Tests for parse_sp_commands (full payload parsing)."""

    def test_single_sp1(self) -> None:
        payload = encode_sp("SP1=", b"\x01\x02\x03\x04")
        cmds = parse_sp_commands(payload)
        assert len(cmds) == 1
        assert cmds[0] == ("SP1", b"\x01\x02\x03\x04")

    def test_comma_separated_sp1_sp2(self) -> None:
        """SP1 and SP2 in one payload, comma-separated (per AC.java:22)."""
        sp1 = encode_sp("SP1=", b"\x01\x02\x03\x04")
        sp2 = encode_sp(",SP2=", b"\x05\x06\x07\x08\x09\x0a\x0b\x0c\x0d")
        payload = sp1 + sp2
        cmds = parse_sp_commands(payload)
        assert len(cmds) == 2
        assert cmds[0][0] == "SP1"
        assert cmds[1][0] == "SP2"

    def test_sps0_command(self) -> None:
        inner = b"\x01\x00\x09\xab\xcd"  # version + selector + algo + crc
        payload = encode_sp("SPS0=", inner)
        cmds = parse_sp_commands(payload)
        assert len(cmds) == 1
        assert cmds[0] == ("SPS0", inner)

    def test_sps2_indexed(self) -> None:
        """SPS2.1= should parse with name 'SPS2.1'."""
        value = b"\xaa" * 24
        payload = encode_sp("SPS2.1=", value)
        cmds = parse_sp_commands(payload)
        assert len(cmds) == 1
        assert cmds[0][0] == "SPS2.1"
        assert cmds[0][1] == value

    def test_sps2_index_zero(self) -> None:
        """SPS2= (no index) should parse with name 'SPS2'."""
        value = b"\xbb" * 24
        payload = encode_sp("SPS2=", value)
        cmds = parse_sp_commands(payload)
        assert len(cmds) == 1
        assert cmds[0][0] == "SPS2"

    def test_sps4_command(self) -> None:
        value = b"t"  # controller sends literal "t" for SPS4
        payload = encode_sp("SPS4=", value)
        cmds = parse_sp_commands(payload)
        assert len(cmds) == 1
        assert cmds[0] == ("SPS4", value)

    def test_plain_text_sp0_gp0(self) -> None:
        """SP0,GP0 are plain-text finalization commands."""
        payload = b"SP0,GP0"
        cmds = parse_sp_commands(payload)
        assert len(cmds) == 2
        assert cmds[0] == ("SP0", b"")
        assert cmds[1] == ("GP0", b"")

    def test_empty_payload(self) -> None:
        cmds = parse_sp_commands(b"")
        assert cmds == []

    def test_text_ack_response(self) -> None:
        """ESSP1.0=0 is plain-text (no binary length field)."""
        payload = b"ESSP1.0=0"
        cmds = parse_sp_commands(payload)
        assert len(cmds) == 1
        # Full token is preserved as plain-text name, value is empty
        assert cmds[0][0] == "ESSP1.0=0"
        assert cmds[0][1] == b""

    def test_combined_text_ack_response(self) -> None:
        """ESSP1.0=0,ESSP2.0=0 response parsing."""
        payload = b"ESSP1.0=0,ESSP2.0=0"
        cmds = parse_sp_commands(payload)
        assert len(cmds) == 2
