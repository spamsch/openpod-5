"""
RHP (Remote Host Protocol) command handler -- pod side.

The pod receives RHP-encoded commands from the phone on the CMD
characteristic, processes them, and sends RHP-encoded responses back
on the TpClassic characteristic.

This is a simplified handler that recognizes the core command set.
Commands arrive as byte sequences after AES-CCM decryption.

Reference: OMNIPOD5_CONNECTION_PROTOCOL.md, Section 5 (RHP Protocol)
Reference: OMNIPOD5_CONNECTION_PROTOCOL.md, Section 9 (Command Flow Types)
"""

from __future__ import annotations

import enum
import logging
from dataclasses import dataclass
from typing import Callable

logger = logging.getLogger(__name__)


class CommandType(enum.IntEnum):
    """
    RHP command types recognized by the pod.

    RHP command types used in the Omnipod 5 pod communication protocol.
    Numeric values correspond to observed wire-format command identifiers.
    """

    GET_VERSION = 0x01
    SET_UNIQUE_ID = 0x02
    PROGRAM_ALERTS = 0x03
    PRIME_POD = 0x04
    PROGRAM_BASAL = 0x05
    INSERT_CANNULA = 0x06
    ENABLE_ALGORITHM = 0x07
    GET_STATUS = 0x08
    SEND_BOLUS = 0x09
    STOP_PROGRAM = 0x0A
    SEND_TEMP_BASAL = 0x0B
    RESUME_INSULIN = 0x0C
    SEND_BEEP = 0x0D
    SILENCE_ALERT = 0x0E
    DEACTIVATE_POD = 0x0F

    UNKNOWN = 0xFF


class ResponseStatus(enum.IntEnum):
    """Response status codes."""

    OK = 0x00
    ERROR = 0x01
    BUSY = 0x02
    INVALID_STATE = 0x03


@dataclass
class RhpCommand:
    """
    A parsed RHP command from the phone.

    Attributes:
        command_type: The command type.
        payload:      The command payload (after the type byte).
        raw:          The original raw bytes.
    """

    command_type: CommandType
    payload: bytes
    raw: bytes


@dataclass
class RhpResponse:
    """
    An RHP response to send back to the phone.

    Attributes:
        command_type: The command this is a response to.
        status:       The response status.
        payload:      The response payload data.
    """

    command_type: CommandType
    status: ResponseStatus
    payload: bytes

    def to_bytes(self) -> bytes:
        """Serialize this response to wire format."""
        return bytes([self.command_type, self.status]) + self.payload


# Type alias for command handler callbacks
CommandHandler = Callable[[RhpCommand], RhpResponse]


class RhpCommandDispatcher:
    """
    Dispatches incoming RHP commands to registered handlers.

    Handlers are registered per command type.  If no handler is registered
    for a command, a default "invalid state" response is returned.

    Usage::

        dispatcher = RhpCommandDispatcher()

        @dispatcher.register(CommandType.GET_STATUS)
        def handle_get_status(cmd: RhpCommand) -> RhpResponse:
            ...
    """

    def __init__(self) -> None:
        self._handlers: dict[CommandType, CommandHandler] = {}
        logger.info("RHP command dispatcher initialized")

    def register(
        self, command_type: CommandType
    ) -> Callable[[CommandHandler], CommandHandler]:
        """
        Decorator to register a handler for a specific command type.

        Args:
            command_type: The command type to handle.

        Returns:
            A decorator that registers the function.
        """

        def decorator(func: CommandHandler) -> CommandHandler:
            self._handlers[command_type] = func
            logger.debug(
                "Handler registered for command %s", command_type.name
            )
            return func

        return decorator

    def register_handler(
        self, command_type: CommandType, handler: CommandHandler
    ) -> None:
        """
        Register a handler for a specific command type (non-decorator form).

        Args:
            command_type: The command type to handle.
            handler:      The handler function.
        """
        self._handlers[command_type] = handler
        logger.debug("Handler registered for command %s", command_type.name)

    def dispatch(self, raw_data: bytes) -> RhpResponse:
        """
        Parse and dispatch an incoming RHP command.

        Args:
            raw_data: The raw command bytes (after decryption).

        Returns:
            The response to send back.
        """
        if len(raw_data) < 1:
            logger.warning("Empty command received")
            return RhpResponse(
                command_type=CommandType.UNKNOWN,
                status=ResponseStatus.ERROR,
                payload=b"",
            )

        try:
            command_type = CommandType(raw_data[0])
        except ValueError:
            logger.warning("Unknown command type: 0x%02x", raw_data[0])
            command_type = CommandType.UNKNOWN

        cmd = RhpCommand(
            command_type=command_type,
            payload=raw_data[1:],
            raw=raw_data,
        )

        logger.info(
            "Dispatching command: %s, payload=%d bytes",
            command_type.name,
            len(cmd.payload),
        )

        handler = self._handlers.get(command_type)
        if handler is None:
            logger.warning(
                "No handler for command %s, returning INVALID_STATE",
                command_type.name,
            )
            return RhpResponse(
                command_type=command_type,
                status=ResponseStatus.INVALID_STATE,
                payload=b"",
            )

        try:
            response = handler(cmd)
            logger.info(
                "Command %s handled: status=%s, payload=%d bytes",
                command_type.name,
                response.status.name,
                len(response.payload),
            )
            return response
        except Exception:
            logger.exception("Handler for %s raised an exception", command_type.name)
            return RhpResponse(
                command_type=command_type,
                status=ResponseStatus.ERROR,
                payload=b"",
            )
