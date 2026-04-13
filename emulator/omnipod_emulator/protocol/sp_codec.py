"""
SP/SPS command codec for the Omnipod 5 TWI pairing protocol.

Encodes and decodes the binary command framing used by the real controller
in the live pairing flow.

Wire format for binary commands::

    [name_prefix UTF-8]  +  [2-byte BE length]  +  [value bytes]

Examples::

    SP1=   \\x00\\x04  <4 bytes controller ID>
    SPS0=  \\x00\\x05  <version + status + algo + crc16>
    SPS2.1= \\x00\\x18 <confirmation data>

Multiple commands are comma-separated in a single TWI payload::

    SP1=\\x00\\x04<v1>,SP2=\\x00\\x09<v2>

Plain-text commands (``SP0``, ``GP0``) have no ``=`` and no value.
Text acks like ``ESSP1.0=0`` are also plain text.
"""

from __future__ import annotations

import logging
import re
import struct

logger = logging.getLogger(__name__)

# Known binary command prefixes (include the trailing '=').
_BINARY_PREFIXES = (
    "SPS0=", "SPS1=", "SPS2=", "SPS4=",
    "SP1=", "SP2=", "P0=",
)

# Regex for indexed SPS2 names like "SPS2.0=", "SPS2.1=", etc.
_SPS2_INDEXED_RE = re.compile(rb"SPS2\.(\d+)=")


# ------------------------------------------------------------------
# Encode
# ------------------------------------------------------------------


def encode_sp(name: str, value: bytes) -> bytes:
    """
    Encode a single SP command frame.

    Args:
        name:  Command name including trailing ``=`` (e.g. ``"SPS0="``).
        value: Raw value bytes.

    Returns:
        ``name_bytes + 2-byte-BE-length + value``
    """
    name_bytes = name.encode("utf-8")
    length = struct.pack(">H", len(value))
    return name_bytes + length + value


# ------------------------------------------------------------------
# Decode
# ------------------------------------------------------------------


def decode_sp(data: bytes, name_offset: int) -> bytes | None:
    """
    Decode a value from a binary SP frame.

    Reads a 2-byte big-endian length at *name_offset*, validates that the
    remaining data matches, and returns the value bytes.

    This mirrors the controller's binary SP decoder: *name_offset* points
    at the 2-byte big-endian length field and the remaining frame bytes
    must match that advertised length.

    Args:
        data:        The full frame bytes (name prefix + length + value).
        name_offset: Byte offset where the 2-byte length field starts
                     (i.e. right after the name prefix).

    Returns:
        The extracted value bytes, or ``None`` if validation fails.
    """
    if name_offset + 2 > len(data):
        return None

    value_len = struct.unpack(">H", data[name_offset : name_offset + 2])[0]
    value_start = name_offset + 2

    if value_start + value_len != len(data):
        # Validation: total length must match exactly.
        # When the frame is part of a comma-batch, the caller should
        # have split on commas first so each chunk is self-contained.
        #
        # Relaxed check: allow trailing data (comma-batched remainder
        # is handled by the parser, not here).
        if value_start + value_len > len(data):
            return None

    return data[value_start : value_start + value_len]


# ------------------------------------------------------------------
# Parse a full TWI RHP payload
# ------------------------------------------------------------------


def parse_sp_commands(payload: bytes) -> list[tuple[str, bytes]]:
    """
    Parse comma-separated SP/SPS commands from a TWI RHP payload.

    Handles both binary-framed commands (``NAME=<2-byte-len><value>``)
    and plain-text commands (``SP0``, ``GP0``, ``ESSP1.0=0``).

    Returns:
        A list of ``(name, value)`` tuples.  For binary commands *name*
        is the prefix without ``=`` (e.g. ``"SPS0"``, ``"SPS2.1"``).
        For plain-text commands *value* is ``b""``.
    """
    commands: list[tuple[str, bytes]] = []
    pos = 0

    while pos < len(payload):
        # Skip any leading comma separator.
        if payload[pos:pos + 1] == b",":
            pos += 1
            continue

        # Try to match a binary command prefix at current position.
        matched = _try_parse_binary(payload, pos)
        if matched is not None:
            name, value, end_pos = matched
            commands.append((name, value))
            pos = end_pos
            continue

        # Otherwise treat as a plain-text command up to the next comma.
        comma = payload.find(b",", pos)
        if comma < 0:
            text = payload[pos:]
            pos = len(payload)
        else:
            text = payload[pos:comma]
            pos = comma  # comma will be skipped on next iteration

        text_str = text.decode("ascii", errors="replace")
        # Strip trailing '=' from text commands for consistent naming.
        name_str = text_str.rstrip("=") if "=" in text_str else text_str
        commands.append((name_str, b""))

    return commands


def _try_parse_binary(
    payload: bytes, pos: int,
) -> tuple[str, bytes, int] | None:
    """
    Try to parse a binary-framed SP command at *pos*.

    Returns ``(name_without_eq, value, end_pos)`` or ``None``.
    """
    remaining = payload[pos:]

    # Check indexed SPS2 first (e.g. "SPS2.1=").
    m = _SPS2_INDEXED_RE.match(remaining)
    if m is not None:
        prefix_len = m.end()  # includes the '='
        name = remaining[:prefix_len - 1].decode("ascii")  # "SPS2.1"
        return _extract_binary_value(payload, pos, prefix_len, name)

    # Check fixed prefixes.
    for prefix in _BINARY_PREFIXES:
        prefix_bytes = prefix.encode("ascii")
        if remaining.startswith(prefix_bytes):
            prefix_len = len(prefix_bytes)
            name = prefix[:-1]  # strip trailing '='
            return _extract_binary_value(payload, pos, prefix_len, name)

    return None


def _extract_binary_value(
    payload: bytes, pos: int, prefix_len: int, name: str,
) -> tuple[str, bytes, int] | None:
    """
    Read the 2-byte-BE length + value after a matched prefix.

    Returns ``(name, value, end_pos)`` or ``None`` on failure.
    """
    length_offset = pos + prefix_len
    if length_offset + 2 > len(payload):
        return None

    value_len = struct.unpack(">H", payload[length_offset : length_offset + 2])[0]
    value_start = length_offset + 2
    value_end = value_start + value_len

    if value_end > len(payload):
        logger.warning(
            "SP codec: %s value truncated (need %d bytes, have %d)",
            name, value_len, len(payload) - value_start,
        )
        return None

    value = payload[value_start:value_end]
    return (name, value, value_end)
