"""
BLE Transport Protocol layer for the Omnipod 5 pod emulator.

Implements the pod side of ``TwiBleTransportProtocol`` (decompiled from
``bWX.java``).  This layer sits between the GATT server and the application
protocol session:

    Phone ↔ GATT (server.py) ↔ Transport (this) ↔ Session (session.py)

Responsibilities:
    - Accept the init command (0x06) from CMD and buffer it.
    - Wait for the PDM to subscribe to CCCDs on CMD and TpClassic.
    - Send a type-0 handshake indication on CMD.
    - Receive the type-1 handshake ack on CMD.
    - After handshake, forward application messages to/from the session.

Reference: Decompiled ``bWX.java`` (TwiBleTransportProtocol)
"""

from __future__ import annotations

import asyncio
import enum
import logging
from collections.abc import Awaitable, Callable

logger = logging.getLogger(__name__)

# ---------------------------------------------------------------------------
# Transport message types (first byte)
# ---------------------------------------------------------------------------

TP_HANDSHAKE_INIT = 0x00
TP_HANDSHAKE_ACK = 0x01
TP_RETRANSMIT = 0x02
TP_TIMEOUT = 0x03
TP_COMPLETE = 0x04
TP_FAILED = 0x05

# Application init command written to CMD before the handshake.
APP_MSG_INIT = 0x06

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
    ) -> None:
        self._on_app_message = on_app_message
        self._state = TransportState.IDLE

        # Send callbacks — set by server after construction.
        self._send_cmd: Callable[[bytes], Awaitable[None]] | None = None
        self._send_tp_classic: Callable[[bytes], Awaitable[None]] | None = None
        self._upgrade_mtu: Callable[[], None] | None = None

        # Buffered init command (received before handshake).
        self._init_data: bytes | None = None

        # CCCD subscription tracking.
        self._subscribed: set[str] = set()

        # Timeout task handle for CCCD wait.
        self._cccd_timeout_task: asyncio.Task | None = None

    def set_send_callbacks(
        self,
        send_cmd: Callable[[bytes], Awaitable[None]],
        send_tp_classic: Callable[[bytes], Awaitable[None]],
        upgrade_mtu: Callable[[], None],
    ) -> None:
        """
        Provide the async send callbacks from the GATT server.

        Must be called once per connection (from ``_on_connection``).
        """
        self._send_cmd = send_cmd
        self._send_tp_classic = send_tp_classic
        self._upgrade_mtu = upgrade_mtu

    def reset(self) -> None:
        """Reset to IDLE on disconnect or new connection."""
        self._state = TransportState.IDLE
        self._init_data = None
        self._subscribed.clear()
        if self._cccd_timeout_task is not None:
            self._cccd_timeout_task.cancel()
            self._cccd_timeout_task = None
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

        logger.info("[TP] %s write: %d bytes", source, len(data))
        self._handle_app_data(data, source=source)

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

    def _handle_app_data(self, data: bytes, *, source: str) -> None:
        """
        Forward application data to the session and send the response
        on TpClassic.
        """
        logger.info(
            "[TP] App data from %s: %d bytes, type=0x%02x",
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

    async def _send_app_response(self, data: bytes) -> None:
        """Send application response as indication on TpClassic."""
        if self._send_tp_classic is None:
            logger.error("[TP] No TpClassic send callback")
            return

        await self._send_tp_classic(data)
        logger.debug("[TP] TpClassic indication sent: %d bytes", len(data))
