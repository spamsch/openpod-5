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

        Header layout (16 bytes, cross-referenced against live wire
        captures on the v3.1.1 phone pairing path):

            Offset  Size  Field / encoding
             0-1    2     "TW" magic (0x54 0x57)
             2      1     high nibble = const 0x1 (version);
                          low nibble  = eqos (0-3 observed)
                          bit 3 also toggled by a private bool flag
             3      1     high nibble = flag bits:
                            bit 7 (0x80) = isAck
                            bit 6 (0x40) = reserved / flag a
                            bit 5 (0x20) = reserved / flag d
                            bit 4 (0x10) = reserved / flag c
                          low nibble  = msg_type wire value:
                            0x0 ClearMessage
                            0x1 EncryptedMessage
                            0x2 SessionEstablishmentMessage
                            0x3 PairingMessage
                            0x4 EncryptedSignedMessage
                            0x5 EncryptedSignedWithUserConfirmationMessage
             4      1     sequence (low byte of the 16-bit seq field)
             5      1     ack_num (low byte of the 16-bit ack field)
             6-7    2     encoded = (plaintext_length << 5) | reserved5
                          (reserved5 is always 0 in observed traffic)
             8-11   4     src_twi_sn  (phone=00000000, pod=00000003)
            12-15   4     dst_twi_sn

        The legacy ``version`` field (2-byte slice) is preserved in the
        meta dict so older callers keep working.
        """
        if len(data) < TWI_HEADER_SIZE or data[0:2] != TWI_MAGIC:
            return None
        version_byte = data[2]
        msg_type_byte = data[3]
        encoded_len_flags = (data[6] << 8) | data[7]
        meta = {
            # Legacy fields (preserved for older callers).
            "version": bytes(data[2:4]),
            "sequence": data[4],
            # Historical alias; prefer encoded_len / encoded_reserved.
            "cmd_count": struct.unpack(">H", data[5:7])[0],
            "flags": data[7],
            "controller_id": data[8:12],
            "pod_id": data[12:16],
            # New fields (from the v3.1.1 wire-capture decode).
            "version_byte": version_byte,
            "version_high": (version_byte & 0xF0) >> 4,
            "eqos": version_byte & 0x0F,
            "msg_type_byte": msg_type_byte,
            "msg_type": msg_type_byte & 0x0F,
            "flag_bits": (msg_type_byte & 0xF0) >> 4,
            "is_ack": bool(msg_type_byte & 0x80),
            "ack_num": data[5],
            "encoded_len": encoded_len_flags >> 5,
            "encoded_reserved": encoded_len_flags & 0x1F,
            "src_twi_sn": data[8:12],
            "dst_twi_sn": data[12:16],
            "raw_header": bytes(data[:TWI_HEADER_SIZE]),
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
        is_ack: bool = False,
        ack_num: int = 0,
    ) -> bytes:
        """
        Build a 16-byte TWI response header.

        Layout (see ``_strip_twi_header`` for the full decode):
          0-1:   "TW" magic
          2-3:   ``version`` — byte 2 = high nibble version / low nibble
                 eqos; byte 3 = high nibble flag bits / low nibble msg_type
          4:     sequence number
          5:     ack_num (0 for non-ACK frames)
          6-7:   16-bit BE = (payload_length << 5) | (flags & 0x1F)
                 i.e. upper 11 bits = payload length, lower 5 bits =
                 legacy "reserved flags" field (always 0 on the wire).
          8-11:  controller_id (4 bytes)
          12-15: pod_id (4 bytes)

        ``is_ack``: when True, OR the 0x80 bit into byte 3 (the isAck
        flag). Callers that build an ACK frame should also set
        ``payload_length = 0`` and pass the inbound frame's sequence
        as ``ack_num``.

        Note on byte 7 / flags: earlier versions of this builder
        reserved the upper 3 bits of byte 7 for a "flag classifier"
        (0x80/0xa0/0xc0/0xe0) and mirrored them from the request. That
        was a misreading: those bits are part of the length encoding
        ``(length & 0x07) << 5``. Real frames with ``len ≡ 7 mod 8``
        (SPS1 len=87, SPS2 len=143, SP0,GP0 len=7) coincidentally land
        on 0xE0 and made the bug invisible, but len=6 (P0=) produced a
        one-byte length error and the app dropped the frame — see
        post-P0-trace investigation 2026-04-13. The 5-bit flag field at
        bytes 6-7 low 5 bits is always 0 in observed outbound traffic,
        so ``flags`` is effectively a no-op parameter retained only for
        legacy callers.
        """
        encoded = ((payload_length & 0x07FF) << 5) | (flags & 0x1F)
        if len(version) != 2:
            raise ValueError(f"version must be 2 bytes, got {len(version)}")
        byte2 = version[0]
        byte3 = version[1]
        if is_ack:
            byte3 |= 0x80
        return (
            TWI_MAGIC
            + bytes([byte2, byte3])
            + bytes([sequence & 0xFF])
            + bytes([ack_num & 0xFF])
            + bytes([(encoded >> 8) & 0xFF, encoded & 0xFF])
            + controller_id[:4]
            + pod_id[:4]
        )

    def _build_twi_ack(self, meta: dict) -> bytes:
        """
        Build a pod-side ACK frame for an inbound TWI frame.

        This is an exploratory / historical implementation. The
        hand-rolled frames produced here were observed to be silently
        dropped by the v3.1.1 phone parser, so live runs rely on a
        different mechanism to clear the reliable-transport retry
        budget. The code is kept because the header encoding is
        still correct and the frame is useful in unit tests and for
        future protocol iterations.

        Encoding derived from ``_strip_twi_header``:

        - byte 2 = ``inbound.version_byte & 0xF0`` — keep the version
          high nibble (security-association state bit) but clear the
          eqos low nibble. ACKs themselves are not reliable transport
          (they should not need their own ACKs), so eqos = 0. The
          phone's own ACK builder allocates a fresh frame and sets
          only the isAck flag, the swapped src/dst arrays, the new
          sequence, the computed ack_num, and the mirrored msg_type;
          it does NOT copy the eqos field from the inbound frame.
        - byte 3 = ``0x80 | 0x04`` — isAck bit set, all other flag
          bits cleared, msg_type forced to ``EncryptedSignedMessage``
          (wire value 4) instead of mirroring the inbound
          ``EncryptedMessage`` (wire value 1). The phone's receive
          path special-cases ``EncryptedMessage`` with a mandatory
          AES-CCM decrypt on every inbound frame; an empty-payload
          ACK fails that decrypt and gets silently dropped before
          reaching the ACK acceptance path. Switching the msg_type
          routes the frame to the no-decrypt branch instead.
        - byte 4 = pod's outbound sequence counter (fresh).
        - byte 5 = inbound frame's sequence + 1. The phone's own
          ACK validator requires ``ack_num == my_seq + 1`` and
          rejects any frame that does not match.
        - bytes 6-7 = 0 (no payload).
        - bytes 8-11 = inbound dst_twi_sn (pod-side src in the reply).
        - bytes 12-15 = inbound src_twi_sn (controller-side dst).
        - a 4-byte zero pad is appended to cross a 19-byte minimum-
          size threshold that the phone's inbound parser enforces on
          some branches.

        Returns a 20-byte ACK frame.
        """
        self._twi_sequence += 1
        # Keep the version high nibble (security/version flags) but
        # clear the eqos low nibble - ACKs are not themselves reliable.
        byte2 = meta["version_byte"] & 0xF0
        # Force msg_type = EncryptedSignedMessage (wire 4) instead of
        # mirroring the inbound EncryptedMessage (wire 1).  The phone's
        # receive path special-cases EncryptedMessage with a mandatory
        # AES-CCM decrypt that fails on empty-payload ACK frames.
        # EncryptedSignedMessage (still in the post-encryption
        # allowlist) routes through the no-decrypt branch.
        byte3 = 0x80 | 0x04
        ack_num = (meta["sequence"] + 1) & 0xFF
        src = bytes(meta["dst_twi_sn"])[:4]
        dst = bytes(meta["src_twi_sn"])[:4]
        # The phone's inbound parser has a branched minimum-size
        # check (16 or 19 bytes depending on a per-call state
        # counter). Empty-payload ACK frames sit at exactly 16 bytes
        # and are silently dropped on the 19-byte branch. Append a
        # 4-byte zero pad so the frame is 20 bytes total and both
        # branches accept it. The encoded length field at bytes 6-7
        # is set to (4 << 5) so the receiver consumes the right
        # number of bytes.
        pad = b"\x00\x00\x00\x00"
        encoded = ((len(pad) & 0x07FF) << 5) & 0xFFFF
        ack_frame = (
            TWI_MAGIC
            + bytes([byte2, byte3])
            + bytes([self._twi_sequence & 0xFF])
            + bytes([ack_num])
            + bytes([(encoded >> 8) & 0xFF, encoded & 0xFF])
            + src
            + dst
            + pad
        )
        logger.info(
            "[TP] TWI ACK: seq=%d ack_num=%d (=inbound_seq+1) "
            "byte2=0x%02x byte3=0x%02x src=%s dst=%s",
            self._twi_sequence, ack_num, byte2, byte3,
            src.hex(), dst.hex(),
        )
        return ack_frame

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
            "[TP] TWI: ver=%s seq=%d ack_num=%d msg_type=0x%02x eqos=%d "
            "is_ack=%s ctrl=%s pod=%s payload=%d bytes",
            meta["version"].hex(),
            meta["sequence"],
            meta["ack_num"],
            meta["msg_type"],
            meta["eqos"],
            meta["is_ack"],
            meta["controller_id"].hex(),
            meta["pod_id"].hex(),
            len(rhp_payload),
        )

        # Reliable-transport ACK: when the inbound frame is sent at
        # eqos == 1 and is not itself an ACK, emit a matching pod-side
        # ACK frame BEFORE processing the payload. Without this the
        # phone's TWI layer times out waiting for an ACK, reports
        # status: -38 on onCommandDelivered, and never processes any
        # pod-side reply to the request - see ANALYSIS.md sections
        # 7.7 and 7.9 for the full live-evidence chain.
        if meta["eqos"] == 1 and not meta["is_ack"]:
            ack_frame = self._build_twi_ack(meta)
            asyncio.get_event_loop().create_task(
                self._send_app_response(ack_frame)
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
