"""
BLE Transport Protocol layer for the Omnipod 5 pod emulator.

Implements the pod side of the phone's BLE transport layer. This layer
sits between the GATT server and the application protocol session:

    Phone ↔ GATT (server.py) ↔ Transport (this) ↔ Session (session.py)

Responsibilities:
    - Accept the init command (0x06) from CMD and buffer it.
    - Wait for the PDM to subscribe to CCCDs on CMD and TpClassic.
    - Send a type-0 handshake indication on CMD.
    - Receive the type-1 handshake ack on CMD.
    - After handshake, forward application messages to/from the session.

Reference: live captures and reverse-engineering notes in ``docs/``.
"""

from __future__ import annotations

import asyncio
import enum
import logging
import struct
import zlib
from collections.abc import Awaitable, Callable

logger = logging.getLogger(__name__)

# ---------------------------------------------------------------------------
# Transport message types (first byte on CMD)
# ---------------------------------------------------------------------------

TP_HANDSHAKE_INIT = 0x00
TP_HANDSHAKE_ACK = 0x01
TP_RETRANSMIT = 0x02
TP_TIMEOUT = 0x03
TP_COMPLETE = 0x04
TP_FAILED = 0x05

# Application init command written to CMD before the handshake.
APP_MSG_INIT = 0x06

# ---------------------------------------------------------------------------
# TWICommand binary header ("TW" magic)
# ---------------------------------------------------------------------------

TWI_MAGIC = b"TW"
TWI_HEADER_SIZE = 16

# ---------------------------------------------------------------------------
# Timing
# ---------------------------------------------------------------------------

# Timeout (seconds) waiting for CCCD subscriptions after init.
CCCD_TIMEOUT_SECONDS = 5.0


class TransportState(enum.Enum):
    """Transport protocol state machine."""

    IDLE = "idle"
    WAIT_CCCD = "wait_cccd"
    HANDSHAKE_SENT = "handshake_sent"
    READY = "ready"


class BleTransportProtocol:
    """
    Pod-side BLE transport protocol.

    Args:
        on_app_message: Callback into the application protocol layer
            (``ProtocolSession.on_message``).  Receives raw application
            bytes, returns response bytes or ``None``.
    """

    def __init__(
        self,
        on_app_message: Callable[[bytes], bytes | None],
        on_twi_commands: Callable[[list[tuple[str, bytes]]], bytes | None] | None = None,
        on_twi_session_message: Callable[[bytes], bytes | None] | None = None,
    ) -> None:
        self._on_app_message = on_app_message
        self._on_twi_handler = on_twi_commands
        self._on_twi_session_handler = on_twi_session_message
        self._state = TransportState.IDLE

        # Send callbacks — set by server after construction.
        self._send_cmd: Callable[[bytes], Awaitable[None]] | None = None
        self._send_tp_classic: Callable[[bytes], Awaitable[None]] | None = None
        self._send_tp_fast: Callable[[bytes], Awaitable[None]] | None = None
        self._upgrade_mtu: Callable[[], None] | None = None

        # Buffered init command (received before handshake).
        self._init_data: bytes | None = None

        # CCCD subscription tracking.
        self._subscribed: set[str] = set()

        # Timeout task handle for CCCD wait.
        self._cccd_timeout_task: asyncio.Task | None = None

        # TWICommand context (populated from incoming TWI frames).
        self._twi_sequence: int = 0
        self._twi_controller_id: bytes = b"\x00" * 4
        self._twi_pod_id: bytes = b"\xff\xff\xff\xfe"
        # Last request's flag byte — mirrored in responses so the PDM
        # classifies the response as the same message type (pairing,
        # session establishment, etc).  Default 0x80 (unencrypted).
        self._twi_flags: int = 0x80
        # Mirrored version field (bytes 2-3 of the TWI header). Pairing
        # uses 0x10 0x03; session-phase EAP-AKA uses 0x10 0x02.
        self._twi_version: bytes = b"\x10\x03"

    def set_send_callbacks(
        self,
        send_cmd: Callable[[bytes], Awaitable[None]],
        send_tp_classic: Callable[[bytes], Awaitable[None]],
        upgrade_mtu: Callable[[], None],
        send_tp_fast: Callable[[bytes], Awaitable[None]] | None = None,
    ) -> None:
        """
        Provide the async send callbacks from the GATT server.

        Must be called once per connection (from ``_on_connection``).
        """
        self._send_cmd = send_cmd
        self._send_tp_classic = send_tp_classic
        self._send_tp_fast = send_tp_fast
        self._upgrade_mtu = upgrade_mtu

    def reset(self) -> None:
        """Reset to IDLE on disconnect or new connection."""
        self._state = TransportState.IDLE
        self._init_data = None
        self._subscribed.clear()
        if self._cccd_timeout_task is not None:
            self._cccd_timeout_task.cancel()
            self._cccd_timeout_task = None
        self._twi_sequence = 0
        logger.info("Transport reset to IDLE")

    # ------------------------------------------------------------------
    # Incoming writes
    # ------------------------------------------------------------------

    def on_cmd_write(self, data: bytes) -> None:
        """
        Handle a write to the CMD characteristic.

        Dispatches based on the first byte and current state.
        """
        if len(data) < 1:
            logger.warning("[TP] Empty CMD write")
            return

        msg_type = data[0]
        logger.info(
            "[TP] CMD write: type=0x%02x, %d bytes, state=%s",
            msg_type, len(data), self._state.value,
        )

        if msg_type == APP_MSG_INIT:
            self._handle_init(data)
        elif msg_type == TP_HANDSHAKE_ACK:
            self._handle_handshake_ack(data)
        elif self._state == TransportState.READY:
            self._handle_app_data(data, source="cmd")
        else:
            logger.warning(
                "[TP] Unexpected CMD type 0x%02x in state %s",
                msg_type, self._state.value,
            )

    def on_data_write(self, data: bytes, *, source: str = "tp_classic") -> None:
        """
        Handle a write to TpClassic or TpFast (application data from phone).

        The PDM does not send a type-1 handshake ack.  Instead, after
        receiving the type-0 handshake on CMD it immediately starts
        sending transport-framed application data on the data
        characteristic (TpFast preferred, TpClassic fallback).
        Receiving this data implicitly completes the handshake.
        """
        if self._state == TransportState.HANDSHAKE_SENT:
            logger.info(
                "[TP] Data write in HANDSHAKE_SENT — "
                "implicit handshake complete, state -> READY"
            )
            self._state = TransportState.READY

            # Process the buffered init first.
            if self._init_data is not None:
                self._handle_app_data(self._init_data, source="cmd_buffered")
                self._init_data = None

        if self._state != TransportState.READY:
            logger.warning(
                "[TP] %s write in state %s (expected READY), "
                "%d bytes ignored",
                source, self._state.value, len(data),
            )
            return

        logger.info("[TP] %s write: %d bytes (framed)", source, len(data))
        payload = self._strip_transport_frame(data)
        if payload is not None:
            self._handle_app_data(payload, source=source)

    def on_cccd_subscribed(self, characteristic: str) -> None:
        """
        Called when the PDM writes a CCCD (enables indications/notifications).

        Args:
            characteristic: ``"cmd"`` or ``"tp_classic"``.
        """
        if characteristic in self._subscribed:
            logger.debug("[TP] CCCD already subscribed: %s", characteristic)
            return

        self._subscribed.add(characteristic)
        logger.info(
            "[TP] CCCD subscribed: %s (total: %s)",
            characteristic, self._subscribed,
        )

        if self._state == TransportState.WAIT_CCCD:
            self._check_cccd_complete()

    # ------------------------------------------------------------------
    # Internal handlers
    # ------------------------------------------------------------------

    def _handle_init(self, data: bytes) -> None:
        """Buffer the init command and wait for CCCD subscriptions."""
        self._init_data = data
        self._state = TransportState.WAIT_CCCD
        logger.info(
            "[TP] Init buffered (%d bytes), state -> WAIT_CCCD", len(data),
        )

        # Start timeout — if CCCDs don't arrive, send handshake anyway.
        if self._cccd_timeout_task is not None:
            self._cccd_timeout_task.cancel()
        self._cccd_timeout_task = asyncio.get_event_loop().create_task(
            self._cccd_timeout()
        )

        # Check if CCCDs already arrived (unlikely but possible).
        self._check_cccd_complete()

    def _check_cccd_complete(self) -> None:
        """If CMD + a data characteristic are subscribed, send the handshake."""
        has_data_char = "tp_classic" in self._subscribed or "tp_fast" in self._subscribed
        if "cmd" in self._subscribed and has_data_char:
            if self._cccd_timeout_task is not None:
                self._cccd_timeout_task.cancel()
                self._cccd_timeout_task = None
            asyncio.get_event_loop().create_task(self._send_handshake())

    async def _cccd_timeout(self) -> None:
        """Fallback: send handshake after timeout even without CCCD writes."""
        await asyncio.sleep(CCCD_TIMEOUT_SECONDS)
        logger.warning(
            "[TP] CCCD timeout (%.1fs) — subscribed: %s, "
            "sending handshake anyway",
            CCCD_TIMEOUT_SECONDS, self._subscribed,
        )
        await self._send_handshake()

    async def _send_handshake(self) -> None:
        """Send the type-0 handshake indication on CMD."""
        if self._state != TransportState.WAIT_CCCD:
            return

        # Upgrade MTU before sending — the app response after handshake
        # may exceed the default 20-byte ATT payload.
        if self._upgrade_mtu is not None:
            self._upgrade_mtu()
            logger.info("[TP] ATT MTU upgraded (post-CCCD)")

        handshake = bytes([TP_HANDSHAKE_INIT, 0x00])
        logger.info("[TP] Sending handshake %s on CMD", handshake.hex())

        if self._send_cmd is not None:
            await self._send_cmd(handshake)

        self._state = TransportState.HANDSHAKE_SENT
        logger.info("[TP] State -> HANDSHAKE_SENT")

        # The handshake is one-way: the PDM does NOT send a type-1 ack.
        # It expects the init response immediately.  Transition to READY
        # and process the buffered init now so the PDM gets its response.
        if self._init_data is not None:
            self._state = TransportState.READY
            logger.info(
                "[TP] Buffered init present — immediate READY, "
                "processing %d bytes",
                len(self._init_data),
            )
            self._handle_app_data(self._init_data, source="cmd_buffered")
            self._init_data = None

    def _handle_handshake_ack(self, data: bytes) -> None:
        """Process the type-1 handshake ack from the phone."""
        if self._state != TransportState.HANDSHAKE_SENT:
            logger.warning(
                "[TP] Handshake ack in wrong state: %s",
                self._state.value,
            )
            return

        logger.info("[TP] Handshake ack received: %s", data.hex())
        self._state = TransportState.READY
        logger.info("[TP] State -> READY")

        # Now process the buffered init command through the app protocol.
        if self._init_data is not None:
            self._handle_app_data(self._init_data, source="cmd_buffered")
            self._init_data = None

    # ------------------------------------------------------------------
    # Transport frame handling
    # ------------------------------------------------------------------

    @staticmethod
    def _strip_transport_frame(data: bytes) -> bytes | None:
        """
        Strip the 7-byte transport frame header from incoming data.

        Frame format (phone → pod):
            Byte 0:    fragment type (0x00 = first/only)
            Byte 1:    total fragments (0x00 = single frame)
            Bytes 2-5: CRC-32 (4 bytes)
            Byte 6:    payload length
            Bytes 7+:  application payload

        Returns the application payload, or None on error.
        """
        if len(data) < 7:
            logger.warning("[TP] Frame too short: %d bytes", len(data))
            return None

        fragment_type = data[0]
        fragment_count = data[1]
        crc = data[2:6]
        payload_length = data[6]
        payload = data[7:]

        if fragment_type != 0x00 or fragment_count != 0x00:
            logger.warning(
                "[TP] Multi-fragment not yet supported: "
                "type=0x%02x, count=%d",
                fragment_type, fragment_count,
            )
            return None

        if len(payload) < payload_length:
            logger.warning(
                "[TP] Payload truncated: expected %d, got %d",
                payload_length, len(payload),
            )

        logger.info(
            "[TP] Frame stripped: header=7, payload=%d, crc=%s",
            payload_length, crc.hex(),
        )
        return payload[:payload_length]

    @staticmethod
    def _build_transport_frame(payload: bytes) -> bytes:
        """
        Wrap an application payload in a 7-byte transport frame header.

        Frame format (pod → phone):
            Byte 0:    0x00 (first/only fragment)
            Byte 1:    0x00 (single frame)
            Bytes 2-5: CRC-32 (4 bytes, big-endian)
            Byte 6:    payload length
            Bytes 7+:  application payload
        """
        crc = zlib.crc32(payload) & 0xFFFFFFFF
        header = bytes([
            0x00,                       # fragment type
            0x00,                       # fragment count
            (crc >> 24) & 0xFF,         # CRC byte 0 (big-endian)
            (crc >> 16) & 0xFF,         # CRC byte 1
            (crc >> 8) & 0xFF,          # CRC byte 2
            crc & 0xFF,                 # CRC byte 3
            len(payload) & 0xFF,        # payload length
        ])
        return header + payload

    # ------------------------------------------------------------------
    # TWICommand header handling
    # ------------------------------------------------------------------

    @staticmethod
    def _strip_twi_header(data: bytes) -> tuple[dict, bytes] | None:
        """
        Parse the 16-byte TWI header.  Returns ``(meta, rhp_payload)``
        or ``None`` if the data is not TWI-framed.

        Header layout (16 bytes):
            0-1:   "TW" magic
            2-3:   version (0x10 0x03)
            4:     sequence id
            5-6:   command count (BE16)
            7:     flags (0x80 = unencrypted)
            8-11:  controller id (BE32)
            12-15: pod id (BE32)
        """
        if len(data) < TWI_HEADER_SIZE or data[0:2] != TWI_MAGIC:
            return None
        meta = {
            "version": bytes(data[2:4]),
            "sequence": data[4],
            "cmd_count": struct.unpack(">H", data[5:7])[0],
            "flags": data[7],
            "controller_id": data[8:12],
            "pod_id": data[12:16],
        }
        return meta, data[TWI_HEADER_SIZE:]

    @staticmethod
    def _build_twi_header(
        sequence: int,
        payload_length: int,
        controller_id: bytes,
        pod_id: bytes,
        flags: int = 0x80,
        version: bytes = b"\x10\x03",
    ) -> bytes:
        """
        Build a 16-byte TWI response header.

        Layout:
          0-1:   "TW" magic
          2-3:   version "10 03"
          4:     sequence number
          5:     reserved (always 0 in observed traces)
          6-7:   16-bit BE = (payload_length << 5) | (flags & 0x1F)
                 i.e. upper 11 bits = payload length, lower 5 bits = flags.
                 Decode: length = ((byte6 << 8) | byte7) >> 5
          8-11:  controller_id (4 bytes)
          12-15: pod_id (4 bytes)

        Note: earlier versions of this builder reserved the upper 3 bits of
        byte 7 for a "flag classifier" (0x80/0xa0/0xc0/0xe0) and mirrored
        them from the request. That was a misreading: those bits are part
        of the length encoding (length & 0x07) << 5. Real frames with
        len ≡ 7 mod 8 (e.g. SPS1 len=87, SPS2 len=143, SP0,GP0 len=7)
        coincidentally land on 0xE0 and made the bug invisible, but
        len=6 (P0=) produced a one-byte length error and the app dropped
        the frame — see post-P0-trace investigation 2026-04-13.
        """
        encoded = ((payload_length & 0x07FF) << 5) | (flags & 0x1F)
        if len(version) != 2:
            raise ValueError(f"version must be 2 bytes, got {len(version)}")
        return (
            TWI_MAGIC
            + version
            + bytes([sequence & 0xFF])
            + b"\x00"  # byte 5 reserved
            + bytes([(encoded >> 8) & 0xFF, encoded & 0xFF])
            + controller_id[:4]
            + pod_id[:4]
        )

    @staticmethod
    def parse_sp_commands(payload: bytes) -> list[tuple[str, bytes]]:
        """
        Parse SP/SPS commands from a TWI RHP payload.

        Delegates to :func:`omnipod_emulator.protocol.sp_codec.parse_sp_commands`
        which implements the 2-byte-BE-length binary framing used by the
        real controller.

        Returns a list of ``(name, value)`` tuples.
        """
        from omnipod_emulator.protocol.sp_codec import (
            parse_sp_commands as _parse,
        )
        return _parse(payload)

    # ------------------------------------------------------------------
    # Application data forwarding
    # ------------------------------------------------------------------

    def _handle_app_data(self, data: bytes, *, source: str) -> None:
        """
        Forward application data to the session.

        Detects TWI framing (``"TW"`` magic) and handles it separately
        from raw application messages (init 0x06, pairing 0x10, etc.).
        """
        if len(data) >= TWI_HEADER_SIZE and data[0:2] == TWI_MAGIC:
            self._handle_twi_message(data, source=source)
        else:
            logger.info(
                "[TP] Raw app data from %s: %d bytes, type=0x%02x",
                source, len(data), data[0] if data else 0,
            )
            response = self._on_app_message(data)
            if response is not None:
                logger.info(
                    "[TP] App response: %d bytes, sending on TpClassic",
                    len(response),
                )
                asyncio.get_event_loop().create_task(
                    self._send_app_response(response)
                )

    def _handle_twi_message(self, data: bytes, *, source: str) -> None:
        """
        Process a TWI-framed message: strip header, parse SP commands,
        forward to session, wrap and send response.
        """
        result = self._strip_twi_header(data)
        if result is None:
            logger.warning("[TP] TWI header parse failed")
            return

        meta, rhp_payload = result
        self._twi_controller_id = meta["controller_id"]
        self._twi_pod_id = meta["pod_id"]
        self._twi_version = meta["version"]
        # Remember the request's flag byte so we can mirror it in the response.
        self._twi_flags = meta["flags"]

        logger.info(
            "[TP] TWI: ver=%s, seq=%d, count=%d, flags=0x%02x, "
            "ctrl=%s, pod=%s, payload=%d bytes",
            meta["version"].hex(),
            meta["sequence"],
            meta["cmd_count"],
            meta["flags"],
            meta["controller_id"].hex(),
            meta["pod_id"].hex(),
            len(rhp_payload),
        )

        # Route by destination pod_id: broadcast (0xFFFFFFFE) means we are
        # still in the SP/SPS pairing path; any other destination means the
        # PDM is addressing the assigned pod, so the payload is a session
        # message (EAP-AKA initiation, then encrypted RHP).
        if meta["pod_id"] == b"\xff\xff\xff\xfe":
            response_payload = self._handle_twi_pairing(rhp_payload)
        else:
            response_payload = self._handle_twi_session(rhp_payload)

        if response_payload is not None:
            self._twi_sequence += 1
            # Swap source/destination: the request had
            #   bytes 8-11 = controller_id (source=app)
            #   bytes 12-15 = pod_id (destination=pod)
            # Our response is FROM pod TO app, so:
            #   bytes 8-11 = pod_id (source=pod)
            #   bytes 12-15 = controller_id (destination=app)
            # The app checks the destination field and drops the message
            # if it doesn't match its own id — previously we kept the
            # original order so the app ignored every SPS1 response.
            twi_header = self._build_twi_header(
                self._twi_sequence,
                len(response_payload),
                self._twi_pod_id,          # source = pod
                self._twi_controller_id,   # destination = app
                flags=self._twi_flags,
                version=self._twi_version,
            )
            twi_response = twi_header + response_payload
            logger.info(
                "[TP] TWI response: seq=%d, payload=%d, flags=0x%02x, %d bytes",
                self._twi_sequence, len(response_payload),
                self._twi_flags, len(twi_response),
            )
            asyncio.get_event_loop().create_task(
                self._send_app_response(twi_response)
            )

    def _handle_twi_pairing(self, rhp_payload: bytes) -> bytes | None:
        """
        Pairing-phase TWI payload: parse SP/SPS commands and dispatch.
        """
        commands = self.parse_sp_commands(rhp_payload)
        if not commands:
            logger.warning(
                "[TP] No SP commands parsed from TWI payload: %s",
                rhp_payload.hex(),
            )
            return None

        for name, value in commands:
            logger.info(
                "[TP] SP cmd: %s = %s (%d bytes)",
                name, value.hex(), len(value),
            )

        return self._on_twi_commands(commands)

    def _handle_twi_session(self, rhp_payload: bytes) -> bytes | None:
        """
        Session-phase TWI payload (EAP-AKA initiation, then encrypted RHP).

        The PDM addresses these to the assigned pod_id (not broadcast),
        and the version field is typically 0x10 0x02. The payload is the
        raw EAP message (or, post-EAP, encrypted application data) — it
        does NOT use the SP/SPS framing of the pairing phase.
        """
        if self._on_twi_session_handler is None:
            logger.warning(
                "[TP] No session handler — TWI session payload dropped: %s",
                rhp_payload.hex(),
            )
            return None
        logger.info(
            "[TP] TWI session payload (%d bytes): %s",
            len(rhp_payload), rhp_payload.hex(),
        )
        return self._on_twi_session_handler(rhp_payload)

    def _on_twi_commands(
        self, commands: list[tuple[str, bytes]]
    ) -> bytes | None:
        """
        Dispatch parsed SP commands to the session's TWI handler.

        Falls back to self._on_app_message if no TWI handler is set.
        """
        if self._on_twi_handler is not None:
            return self._on_twi_handler(commands)
        logger.warning("[TP] No TWI command handler — commands dropped")
        return None

    async def _send_app_response(self, data: bytes) -> None:
        """
        Send an application response as a transport-framed notification/indication
        on the preferred data characteristic, followed by TP_COMPLETE on CMD.

        Prefers TpFast (notification) when the PDM has subscribed to it,
        falls back to TpClassic (indication).  The real PDM subscribes to
        TpFast for data and only uses TpClassic as fallback.
        """
        frame = self._build_transport_frame(data)
        logger.debug(
            "[TP] Sending framed response: %d payload + 7 header = %d total",
            len(data), len(frame),
        )

        # Prefer TpFast when subscribed (the real PDM subscribes to TpFast).
        if "tp_fast" in self._subscribed and self._send_tp_fast is not None:
            logger.info("[TP] Sending response on TpFast (notify)")
            await self._send_tp_fast(frame)
        elif self._send_tp_classic is not None:
            logger.info("[TP] Sending response on TpClassic (indicate)")
            await self._send_tp_classic(frame)
        else:
            logger.error("[TP] No data send callback available")
            return

        # Signal transfer complete on CMD.
        if self._send_cmd is not None:
            await self._send_cmd(bytes([TP_COMPLETE]))
            logger.debug("[TP] Sent TP_COMPLETE on CMD")
