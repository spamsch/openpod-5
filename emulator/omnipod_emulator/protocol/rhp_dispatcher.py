"""
Text RHP command dispatcher for the pod emulator.

Replaces the binary opcode dispatcher with a text-based RHP handler that
routes requests by (action, type_prefix, type_no, attr_no) tuples.

Supports:
    - GV: Get version
    - Gx.y: Get attribute
    - Sx.y=value: Set attribute
    - Comma-batched commands
    - ES success responses
    - EG/ES error responses

Reference: ~/Projects/personal/omnipod-connector/docs/protocol/04-data-protocol.md
"""

from __future__ import annotations

import logging
from typing import Callable

from omnipod_emulator.protocol.rhp import (
    RhpAction,
    RhpErrorCode,
    RhpRequest,
    RhpResponse,
    RhpTypePrefix,
    parse_rhp_batch,
    format_rhp_batch,
)

logger = logging.getLogger(__name__)

# Handler type: receives an RhpRequest, returns an RhpResponse
RhpHandler = Callable[[RhpRequest], RhpResponse]


class RhpTextDispatcher:
    """
    Dispatches text RHP requests to registered handlers.

    Handlers are registered by (action, prefix, type_no, attr_no) key.
    Special handlers can be registered for version requests.

    Usage::

        dispatcher = RhpTextDispatcher()

        dispatcher.register_handler("G", "", 3, 6, handle_get_algorithm_status)
        dispatcher.register_handler("S", "", 3, 9, handle_set_dia)
        dispatcher.register_version_handler(handle_get_version)
    """

    def __init__(self) -> None:
        self._handlers: dict[tuple[str, str, int | None, int | None], RhpHandler] = {}
        self._version_handler: RhpHandler | None = None
        logger.info("RHP text dispatcher initialized")

    def register_handler(
        self,
        action: str,
        prefix: str,
        type_no: int,
        attr_no: int,
        handler: RhpHandler,
    ) -> None:
        """
        Register a handler for a specific (action, prefix, type_no, attr_no).

        Args:
            action:  "G" or "S"
            prefix:  "" for normal, "L" for logger, "A" for alarm, etc.
            type_no: The type number.
            attr_no: The attribute number.
            handler: The handler function.
        """
        key = (action, prefix, type_no, attr_no)
        self._handlers[key] = handler
        logger.debug("Handler registered for %s", key)

    def register_get(
        self,
        type_no: int,
        attr_no: int,
        handler: RhpHandler,
        prefix: str = "",
    ) -> None:
        """Shorthand to register a GET handler."""
        self.register_handler("G", prefix, type_no, attr_no, handler)

    def register_set(
        self,
        type_no: int,
        attr_no: int,
        handler: RhpHandler,
        prefix: str = "",
    ) -> None:
        """Shorthand to register a SET handler."""
        self.register_handler("S", prefix, type_no, attr_no, handler)

    def register_version_handler(self, handler: RhpHandler) -> None:
        """Register the GV (get version) handler."""
        self._version_handler = handler
        logger.debug("Version handler registered")

    def dispatch(self, text: str) -> str:
        """
        Parse and dispatch a (possibly batched) RHP text request.

        Args:
            text: The RHP request string, possibly comma-separated.

        Returns:
            The response string (comma-separated if batched).
        """
        try:
            requests = parse_rhp_batch(text)
        except ValueError:
            logger.warning("Failed to parse RHP text: %r", text)
            return f"EG0.0={RhpErrorCode.NOT_SUPPORTED}"

        if not requests:
            logger.warning("Empty RHP batch")
            return f"EG0.0={RhpErrorCode.NOT_SUPPORTED}"

        responses: list[RhpResponse] = []
        for req in requests:
            resp = self._dispatch_single(req)
            responses.append(resp)

        result = format_rhp_batch(responses)
        logger.info("RHP dispatch: %r -> %r", text, result)
        return result

    def _dispatch_single(self, req: RhpRequest) -> RhpResponse:
        """Dispatch a single RHP request to its handler."""
        # Version request
        if req.is_version_request:
            if self._version_handler is not None:
                try:
                    return self._version_handler(req)
                except Exception:
                    logger.exception("Version handler raised an exception")
                    return RhpResponse.error(req.action, 0, 0, RhpErrorCode.INVALID_STATE)
            else:
                logger.warning("No version handler registered")
                return RhpResponse.error(req.action, 0, 0, RhpErrorCode.NOT_SUPPORTED)

        # Normal attribute request
        key = (
            req.action.value,
            req.type_prefix.value,
            req.type_no,
            req.attr_no,
        )

        handler = self._handlers.get(key)
        if handler is None:
            logger.warning("No handler for RHP key %s", key)
            type_no = req.type_no or 0
            attr_no = req.attr_no or 0
            return RhpResponse.error(req.action, type_no, attr_no, RhpErrorCode.NOT_SUPPORTED)

        try:
            return handler(req)
        except Exception:
            logger.exception("Handler for %s raised an exception", key)
            type_no = req.type_no or 0
            attr_no = req.attr_no or 0
            return RhpResponse.error(req.action, type_no, attr_no, RhpErrorCode.INVALID_STATE)
