"""
Tests for the BLE transport protocol layer and TWI framing.

Verifies:
    - Transport state machine (IDLE → WAIT_CCCD → HANDSHAKE_SENT → READY)
    - Transport frame stripping (7-byte header with CRC-32)
    - Transport frame building (CRC-32 matches zlib.crc32 big-endian)
    - TWI header parsing (16-byte "TW" magic header)
    - TWI header building (round-trip)
    - SP command parsing (binary RHP format)
    - Full handshake flow with CCCD subscriptions
    - TWI message routing (SP commands dispatched, raw messages passed through)
"""

from __future__ import annotations

import asyncio
import struct
import zlib

import pytest

from omnipod_emulator.ble.transport import (
    APP_MSG_INIT,
    TP_COMPLETE,
    TP_HANDSHAKE_INIT,
    TWI_HEADER_SIZE,
    TWI_MAGIC,
    BleTransportProtocol,
    TransportState,
)


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------

CONTROLLER_ID = b"\x00\x00\xc9\x90"
POD_ID = b"\xff\xff\xff\xfe"
FIRMWARE_ID = b"\xaa\xbb\xcc\xdd\xee\xff"
INIT_CMD = bytes([0x06, 0x01, 0x04]) + CONTROLLER_ID


def build_transport_frame(payload: bytes) -> bytes:
    """Build a 7-byte transport frame header + payload."""
    crc = zlib.crc32(payload) & 0xFFFFFFFF
    header = bytes([
        0x00, 0x00,
        (crc >> 24) & 0xFF, (crc >> 16) & 0xFF,
        (crc >> 8) & 0xFF, crc & 0xFF,
        len(payload) & 0xFF,
    ])
    return header + payload


def build_twi_frame(
    sequence: int, cmd_count: int, payload: bytes,
    controller_id: bytes = CONTROLLER_ID,
    pod_id: bytes = POD_ID,
) -> bytes:
    """Build a 16-byte TWI header + payload."""
    header = (
        TWI_MAGIC
        + b"\x10\x03"
        + bytes([sequence])
        + struct.pack(">H", cmd_count)
        + b"\x80"
        + controller_id
        + pod_id
    )
    return header + payload


def build_sp_value(name: str, value: bytes) -> bytes:
    """Build a binary SP command: NAME=\\x00{len}{value}."""
    return name.encode("ascii") + b"=" + b"\x00" + bytes([len(value)]) + value


# ---------------------------------------------------------------------------
# Transport frame tests
# ---------------------------------------------------------------------------


class TestTransportFrame:
    """Tests for transport frame stripping and building."""

    def test_strip_valid_frame(self) -> None:
        payload = b"hello world"
        frame = build_transport_frame(payload)
        result = BleTransportProtocol._strip_transport_frame(frame)
        assert result == payload

    def test_strip_frame_crc_is_zlib_crc32_be(self) -> None:
        payload = b"\x54\x57\x10\x03"
        frame = build_transport_frame(payload)
        crc_bytes = frame[2:6]
        expected_crc = zlib.crc32(payload) & 0xFFFFFFFF
        assert crc_bytes == expected_crc.to_bytes(4, "big")

    def test_strip_frame_too_short(self) -> None:
        assert BleTransportProtocol._strip_transport_frame(b"\x00\x01") is None

    def test_strip_multi_fragment_rejected(self) -> None:
        frame = bytes([0x00, 0x01, 0, 0, 0, 0, 0x01, 0x42])
        assert BleTransportProtocol._strip_transport_frame(frame) is None

    def test_build_frame_roundtrip(self) -> None:
        payload = b"test payload 1234567890"
        frame = BleTransportProtocol._build_transport_frame(payload)
        assert len(frame) == 7 + len(payload)
        stripped = BleTransportProtocol._strip_transport_frame(frame)
        assert stripped == payload

    def test_build_frame_crc_matches_captured_data(self) -> None:
        """Verify CRC against the actual captured PDM frame."""
        # Captured 44-byte payload from live test
        payload = bytes.fromhex(
            "5457100301000380"
            "0000c990fffffffe"
            "5350313d00040000"
            "c9922c5350323d00"
            "0b0000c99200030e"
            "01008151"
        )
        frame = BleTransportProtocol._build_transport_frame(payload)
        crc_bytes = frame[2:6]
        assert crc_bytes == bytes.fromhex("f14e3071")


# ---------------------------------------------------------------------------
# TWI header tests
# ---------------------------------------------------------------------------


class TestTwiHeader:
    """Tests for TWI header parsing and building."""

    def test_strip_twi_header(self) -> None:
        payload = b"SP1=\x00\x04test"
        data = build_twi_frame(1, 1, payload)
        result = BleTransportProtocol._strip_twi_header(data)
        assert result is not None
        meta, rhp = result
        assert meta["sequence"] == 1
        assert meta["cmd_count"] == 1
        assert meta["flags"] == 0x80
        assert meta["controller_id"] == CONTROLLER_ID
        assert meta["pod_id"] == POD_ID
        assert rhp == payload

    def test_strip_twi_non_magic_returns_none(self) -> None:
        data = b"\x00\x06" + b"\x00" * 20
        assert BleTransportProtocol._strip_twi_header(data) is None

    def test_strip_twi_too_short_returns_none(self) -> None:
        assert BleTransportProtocol._strip_twi_header(b"TW" + b"\x00" * 5) is None

    def test_build_twi_header_size(self) -> None:
        header = BleTransportProtocol._build_twi_header(
            1, 2, CONTROLLER_ID, POD_ID
        )
        assert len(header) == TWI_HEADER_SIZE
        assert header[:2] == TWI_MAGIC

    def test_build_twi_roundtrip(self) -> None:
        payload = b"SPS0=\x00\x05\x00\x01\x09\xa2\x18"
        header = BleTransportProtocol._build_twi_header(
            3, 1, CONTROLLER_ID, POD_ID
        )
        data = header + payload
        result = BleTransportProtocol._strip_twi_header(data)
        assert result is not None
        meta, rhp = result
        assert meta["sequence"] == 3
        assert meta["cmd_count"] == 1
        assert rhp == payload

    def test_parse_captured_twi_message_1(self) -> None:
        """Parse the actual captured TWI message 1 from live BLE test."""
        data = bytes.fromhex(
            "54571003010003800000c990fffffffe"
            "5350313d00040000c9922c5350323d00"
            "0b0000c99200030e01008151"
        )
        result = BleTransportProtocol._strip_twi_header(data)
        assert result is not None
        meta, rhp = result
        assert meta["sequence"] == 1
        assert meta["cmd_count"] == 3
        assert meta["controller_id"] == b"\x00\x00\xc9\x90"
        assert meta["pod_id"] == b"\xff\xff\xff\xfe"
        assert len(rhp) == 28

    def test_parse_captured_twi_message_2(self) -> None:
        """Parse the actual captured TWI message 2 from live BLE test."""
        data = bytes.fromhex(
            "54571003020001800000c990fffffffe"
            "535053303d0005000109a218"
        )
        result = BleTransportProtocol._strip_twi_header(data)
        assert result is not None
        meta, rhp = result
        assert meta["sequence"] == 2
        assert meta["cmd_count"] == 1
        assert len(rhp) == 12


# ---------------------------------------------------------------------------
# SP command parsing tests
# ---------------------------------------------------------------------------


class TestSpCommandParsing:
    """Tests for binary RHP SIM Profile command parsing."""

    def test_parse_single_command(self) -> None:
        payload = build_sp_value("SP1", b"\x00\x00\xc9\x92")
        commands = BleTransportProtocol.parse_sp_commands(payload)
        assert len(commands) == 1
        assert commands[0] == ("SP1", b"\x00\x00\xc9\x92")

    def test_parse_multiple_commands(self) -> None:
        sp1 = build_sp_value("SP1", b"\x01\x02\x03\x04")
        sp2 = build_sp_value("SP2", b"\x05\x06")
        payload = sp1 + b"," + sp2
        commands = BleTransportProtocol.parse_sp_commands(payload)
        assert len(commands) == 2
        assert commands[0] == ("SP1", b"\x01\x02\x03\x04")
        assert commands[1] == ("SP2", b"\x05\x06")

    def test_parse_sps0_command(self) -> None:
        payload = build_sp_value("SPS0", b"\x00\x01\x09\xa2\x18")
        commands = BleTransportProtocol.parse_sp_commands(payload)
        assert len(commands) == 1
        assert commands[0][0] == "SPS0"
        assert commands[0][1] == b"\x00\x01\x09\xa2\x18"

    def test_parse_captured_sp1_sp2(self) -> None:
        """Parse the actual SP1+SP2 payload from the live BLE capture."""
        payload = bytes.fromhex(
            "5350313d00040000c992"
            "2c"
            "5350323d000b0000c99200030e01008151"
        )
        commands = BleTransportProtocol.parse_sp_commands(payload)
        assert len(commands) == 2
        assert commands[0][0] == "SP1"
        assert commands[0][1] == b"\x00\x00\xc9\x92"
        assert commands[1][0] == "SP2"
        assert len(commands[1][1]) == 11

    def test_parse_empty_payload(self) -> None:
        commands = BleTransportProtocol.parse_sp_commands(b"")
        assert commands == []

    def test_parse_no_binary_marker(self) -> None:
        """Non-binary commands (no \\x00 after =) should be skipped."""
        payload = b"FOO=bar,SP1=\x00\x02\xaa\xbb"
        commands = BleTransportProtocol.parse_sp_commands(payload)
        assert len(commands) == 1
        assert commands[0][0] == "SP1"


# ---------------------------------------------------------------------------
# Transport state machine tests
# ---------------------------------------------------------------------------


class TestTransportStateMachine:
    """Tests for the transport protocol state machine."""

    def _make_transport(self):
        """Create a BleTransportProtocol with mock callbacks."""
        app_messages = []
        twi_commands = []
        sent_cmd = []
        sent_tp = []

        def on_app(data):
            app_messages.append(data)
            return b"\x10\xaa"  # Dummy response

        def on_twi(commands):
            twi_commands.append(commands)
            return b"ESSP1.0=0"

        tp = BleTransportProtocol(
            on_app_message=on_app,
            on_twi_commands=on_twi,
        )

        async def mock_send_cmd(data):
            sent_cmd.append(data)

        async def mock_send_tp(data):
            sent_tp.append(data)

        tp.set_send_callbacks(
            send_cmd=mock_send_cmd,
            send_tp_classic=mock_send_tp,
            upgrade_mtu=lambda: None,
        )

        return tp, app_messages, twi_commands, sent_cmd, sent_tp

    def test_initial_state_is_idle(self) -> None:
        tp, *_ = self._make_transport()
        assert tp._state == TransportState.IDLE

    @pytest.mark.asyncio
    async def test_init_transitions_to_wait_cccd(self) -> None:
        tp, *_ = self._make_transport()
        tp.on_cmd_write(INIT_CMD)
        assert tp._state == TransportState.WAIT_CCCD
        assert tp._init_data == INIT_CMD

    @pytest.mark.asyncio
    async def test_cccd_subscriptions_trigger_handshake(self) -> None:
        tp, _, _, sent_cmd, _ = self._make_transport()
        tp.on_cmd_write(INIT_CMD)

        tp.on_cccd_subscribed("cmd")
        tp.on_cccd_subscribed("tp_fast")

        # Let the async handshake task run
        await asyncio.sleep(0.05)

        # Handshake is one-way: with a buffered init the transport
        # advances straight to READY and processes the init immediately.
        assert tp._state == TransportState.READY
        assert sent_cmd[0] == bytes([TP_HANDSHAKE_INIT, 0x00])
        # The buffered init is also processed, producing an app response.
        assert len(sent_cmd) >= 1

    @pytest.mark.asyncio
    async def test_data_write_completes_handshake(self) -> None:
        tp, app_messages, _, _, _ = self._make_transport()
        tp.on_cmd_write(INIT_CMD)
        tp.on_cccd_subscribed("cmd")
        tp.on_cccd_subscribed("tp_fast")
        await asyncio.sleep(0.05)

        # Simulate phone sending transport-framed data on TpFast
        raw_payload = b"\x06\x01\x02\x03"  # Dummy app data
        frame = build_transport_frame(raw_payload)
        tp.on_data_write(frame, source="tp_fast")

        assert tp._state == TransportState.READY
        # The buffered init should have been processed
        assert INIT_CMD in app_messages

    @pytest.mark.asyncio
    async def test_twi_messages_routed_to_handler(self) -> None:
        tp, app_messages, twi_commands, _, sent_tp = self._make_transport()
        tp.on_cmd_write(INIT_CMD)
        tp.on_cccd_subscribed("cmd")
        tp.on_cccd_subscribed("tp_fast")
        await asyncio.sleep(0.05)

        # Build a TWI-framed SP command inside a transport frame
        sp_payload = build_sp_value("SP1", b"\x00\x00\xc9\x92")
        twi_data = build_twi_frame(1, 1, sp_payload)
        frame = build_transport_frame(twi_data)
        tp.on_data_write(frame, source="tp_fast")

        await asyncio.sleep(0.05)

        # TWI commands should go to the TWI handler, not the app handler
        assert len(twi_commands) == 1
        assert twi_commands[0][0] == ("SP1", b"\x00\x00\xc9\x92")
        # Init was also processed as raw app message
        assert INIT_CMD in app_messages

    @pytest.mark.asyncio
    async def test_reset_clears_state(self) -> None:
        tp, *_ = self._make_transport()
        tp.on_cmd_write(INIT_CMD)
        tp.on_cccd_subscribed("cmd")
        assert tp._state == TransportState.WAIT_CCCD

        tp.reset()
        assert tp._state == TransportState.IDLE
        assert tp._init_data is None
        assert len(tp._subscribed) == 0

    @pytest.mark.asyncio
    async def test_tp_classic_cccd_also_triggers_handshake(self) -> None:
        tp, _, _, sent_cmd, _ = self._make_transport()
        tp.on_cmd_write(INIT_CMD)
        tp.on_cccd_subscribed("cmd")
        tp.on_cccd_subscribed("tp_classic")
        await asyncio.sleep(0.05)

        # With buffered init, transport goes straight to READY.
        assert tp._state == TransportState.READY
        assert sent_cmd[0] == bytes([TP_HANDSHAKE_INIT, 0x00])


# ---------------------------------------------------------------------------
# P-256 ECDH key pair tests
# ---------------------------------------------------------------------------


class TestP256Ecdh:
    """Tests for P-256 ECDH support alongside Curve25519."""

    def test_p256_key_pair_produces_64_byte_pubkey(self) -> None:
        from omnipod_emulator.crypto.ecdh import EcdhKeyPair
        kp = EcdhKeyPair(algorithm=0x09)
        assert len(kp.public_key_bytes) == 64
        assert len(kp.nonce) == 16

    def test_x25519_key_pair_produces_32_byte_pubkey(self) -> None:
        from omnipod_emulator.crypto.ecdh import EcdhKeyPair
        kp = EcdhKeyPair(algorithm=0x00)
        assert len(kp.public_key_bytes) == 32

    def test_p256_shared_secret_agreement(self) -> None:
        from omnipod_emulator.crypto.ecdh import EcdhKeyPair
        alice = EcdhKeyPair(algorithm=0x09)
        bob = EcdhKeyPair(algorithm=0x09)

        secret_a = alice.compute_shared_secret(bob.public_key_bytes)
        secret_b = bob.compute_shared_secret(alice.public_key_bytes)

        assert secret_a == secret_b
        assert len(secret_a) == 32

    def test_p256_rejects_32_byte_peer_key(self) -> None:
        from omnipod_emulator.crypto.ecdh import EcdhKeyPair
        kp = EcdhKeyPair(algorithm=0x09)
        with pytest.raises(ValueError, match="64 bytes"):
            kp.compute_shared_secret(b"\x00" * 32)

    def test_x25519_rejects_64_byte_peer_key(self) -> None:
        from omnipod_emulator.crypto.ecdh import EcdhKeyPair
        kp = EcdhKeyPair(algorithm=0x00)
        with pytest.raises(ValueError, match="32 bytes"):
            kp.compute_shared_secret(b"\x00" * 64)

    def test_is_p256_function(self) -> None:
        from omnipod_emulator.crypto.ecdh import is_p256
        assert is_p256(0x00) is False
        assert is_p256(0x08) is False
        assert is_p256(0x01) is True
        assert is_p256(0x09) is True
        assert is_p256(0x0D) is True

    def test_p256_kdf_accepts_64_byte_keys(self) -> None:
        from omnipod_emulator.crypto.ecdh import EcdhKeyPair
        from omnipod_emulator.crypto.kdf import derive_keys

        alice = EcdhKeyPair(algorithm=0x09)
        bob = EcdhKeyPair(algorithm=0x09)
        shared = alice.compute_shared_secret(bob.public_key_bytes)

        keys = derive_keys(
            firmware_id=FIRMWARE_ID,
            controller_id=CONTROLLER_ID,
            pod_public_key=alice.public_key_bytes,
            phone_public_key=bob.public_key_bytes,
            shared_secret=shared,
        )
        assert len(keys.confirmation_key) == 16
        assert len(keys.ltk) == 16
