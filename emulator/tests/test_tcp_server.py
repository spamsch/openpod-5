"""
Integration tests for the TCP protocol server.

Verifies that the TcpProtocolServer correctly frames messages,
routes them through ProtocolSession, and returns framed responses.
"""

from __future__ import annotations

import asyncio
import struct

import pytest

from omnipod_emulator.pod.state import PodState
from omnipod_emulator.protocol.session import (
    MSG_INIT,
    MSG_PAIRING,
    ProtocolSession,
    SessionPhase,
)
from omnipod_emulator.tcp.server import TcpProtocolServer

FIRMWARE_ID = b"\xaa\xbb\xcc\xdd\xee\xff"
CONTROLLER_ID = b"\x01\x02\x03\x04"
ECDH_SEED = b"\x42" * 32


def _frame(payload: bytes) -> bytes:
    """Wrap payload with a 4-byte big-endian length header."""
    return struct.pack(">I", len(payload)) + payload


async def _read_frame(reader: asyncio.StreamReader) -> bytes:
    """Read one length-prefixed frame."""
    header = await asyncio.wait_for(reader.readexactly(4), timeout=5.0)
    (length,) = struct.unpack(">I", header)
    payload = await asyncio.wait_for(reader.readexactly(length), timeout=5.0)
    return payload


@pytest.fixture
def session() -> ProtocolSession:
    pod_state = PodState()
    return ProtocolSession(
        pod_state=pod_state,
        firmware_id=FIRMWARE_ID,
        ecdh_seed=ECDH_SEED,
    )


@pytest.mark.asyncio
async def test_tcp_init_message(session: ProtocolSession):
    """Send a framed INIT and verify we get a framed pairing response."""
    server = TcpProtocolServer(session, host="127.0.0.1", port=0)

    # Start server on a random port
    tcp_server = await asyncio.start_server(
        server._handle_client,
        "127.0.0.1",
        0,
    )
    port = tcp_server.sockets[0].getsockname()[1]

    try:
        # Connect as a client
        reader, writer = await asyncio.open_connection("127.0.0.1", port)

        # Send INIT message
        init_msg = bytes([MSG_INIT, 0x01, 0x04]) + CONTROLLER_ID
        writer.write(_frame(init_msg))
        await writer.drain()

        # Read response
        response = await _read_frame(reader)

        # Should be MSG_PAIRING + firmware_id(6) + pod_pubkey(32) + pod_nonce(16) = 55 bytes
        assert response[0] == MSG_PAIRING
        assert len(response) == 1 + 6 + 32 + 16
        assert session.phase == SessionPhase.PAIRING

        writer.close()
        await writer.wait_closed()
    finally:
        tcp_server.close()
        await tcp_server.wait_closed()


@pytest.mark.asyncio
async def test_tcp_disconnect_notifies_session(session: ProtocolSession):
    """Verify that client disconnect triggers session.on_disconnect()."""
    server = TcpProtocolServer(session, host="127.0.0.1", port=0)

    tcp_server = await asyncio.start_server(
        server._handle_client,
        "127.0.0.1",
        0,
    )
    port = tcp_server.sockets[0].getsockname()[1]

    try:
        # Connect, send init, then disconnect
        reader, writer = await asyncio.open_connection("127.0.0.1", port)

        init_msg = bytes([MSG_INIT, 0x01, 0x04]) + CONTROLLER_ID
        writer.write(_frame(init_msg))
        await writer.drain()
        await _read_frame(reader)

        assert session.phase == SessionPhase.PAIRING

        # Disconnect
        writer.close()
        await writer.wait_closed()

        # Give the server handler time to process the disconnect
        await asyncio.sleep(0.1)

        assert session.phase == SessionPhase.DISCONNECTED
    finally:
        tcp_server.close()
        await tcp_server.wait_closed()


@pytest.mark.asyncio
async def test_tcp_multiple_messages(session: ProtocolSession):
    """Verify multiple messages can be sent on the same connection."""
    server = TcpProtocolServer(session, host="127.0.0.1", port=0)

    tcp_server = await asyncio.start_server(
        server._handle_client,
        "127.0.0.1",
        0,
    )
    port = tcp_server.sockets[0].getsockname()[1]

    try:
        reader, writer = await asyncio.open_connection("127.0.0.1", port)

        # Send INIT
        init_msg = bytes([MSG_INIT, 0x01, 0x04]) + CONTROLLER_ID
        writer.write(_frame(init_msg))
        await writer.drain()
        init_response = await _read_frame(reader)
        assert init_response[0] == MSG_PAIRING

        # Send a pairing message (phone key + nonce)
        # Just verify it doesn't crash — the crypto details are tested elsewhere
        from omnipod_emulator.crypto.ecdh import EcdhKeyPair

        phone_kp = EcdhKeyPair()
        pair_msg = (
            bytes([MSG_PAIRING, 0x01])
            + phone_kp.public_key_bytes
            + phone_kp.nonce
        )
        writer.write(_frame(pair_msg))
        await writer.drain()
        pair_response = await _read_frame(reader)
        assert pair_response[0] == MSG_PAIRING

        writer.close()
        await writer.wait_closed()
    finally:
        tcp_server.close()
        await tcp_server.wait_closed()
