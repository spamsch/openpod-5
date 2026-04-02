"""
CRC-16 computation for TWICommand message integrity.

The native library uses ``twi_crc16_compute_checksum`` for message integrity.
The exact polynomial is unconfirmed; this implementation uses CRC-16/CCITT
(polynomial 0x1021, init 0xFFFF) which is common in embedded protocols.

Reference: native library analysis of ``libc3ec87.so``
"""

from __future__ import annotations


def crc16_ccitt(data: bytes, init: int = 0xFFFF) -> int:
    """
    Compute CRC-16/CCITT over *data*.

    Uses polynomial 0x1021 with the given initial value.

    Args:
        data: Input bytes.
        init: Initial CRC value (default 0xFFFF).

    Returns:
        The 16-bit CRC value.
    """
    crc = init
    for byte in data:
        crc ^= byte << 8
        for _ in range(8):
            if crc & 0x8000:
                crc = (crc << 1) ^ 0x1021
            else:
                crc <<= 1
            crc &= 0xFFFF
    return crc


def crc16_bytes(data: bytes) -> bytes:
    """
    Compute CRC-16/CCITT and return as 2 big-endian bytes.

    Args:
        data: Input bytes.

    Returns:
        2-byte CRC in big-endian order.
    """
    return crc16_ccitt(data).to_bytes(2, "big")
