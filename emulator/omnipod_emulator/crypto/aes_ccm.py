"""
AES-CCM-128 authenticated encryption / decryption.

AES-CCM (Counter with CBC-MAC) provides both confidentiality and integrity.
The Omnipod 5 protocol uses it for all post-authentication communication
with:
    - 128-bit (16-byte) AES key
    - Variable nonce (typically 13 bytes for CCM)
    - Additional Authenticated Data (AAD) -- integrity-protected but not
      encrypted
    - Configurable authentication tag length (default 8 bytes)

Reference: OMNIPOD5_CONNECTION_PROTOCOL.md, Section 3d (AES-CCM encryption)
Reference: RFC 3610 (Counter with CBC-MAC)
"""

from __future__ import annotations

import logging

from cryptography.hazmat.primitives.ciphers.aead import AESCCM

logger = logging.getLogger(__name__)

# AES-128 key length in bytes.
_KEY_LENGTH = 16

# Default authentication tag length in bytes.
_DEFAULT_TAG_LENGTH = 8


def encrypt(
    key: bytes,
    plaintext: bytes,
    nonce: bytes,
    aad: bytes | None = None,
    tag_length: int = _DEFAULT_TAG_LENGTH,
) -> bytes:
    """
    Encrypt and authenticate *plaintext* using AES-CCM-128.

    Args:
        key:        16-byte AES key.
        plaintext:  Data to encrypt.
        nonce:      Nonce / IV for CCM (typically 7-13 bytes).
        aad:        Additional authenticated data (integrity-protected,
                    not encrypted).  May be ``None`` or empty.
        tag_length: Authentication tag length in bytes (4, 6, 8, 10, 12,
                    14, or 16).  Omnipod default is 8.

    Returns:
        Ciphertext with the authentication tag appended (len = len(plaintext)
        + tag_length).

    Raises:
        ValueError: If *key* is not 16 bytes or *tag_length* is invalid.
    """
    _validate_key(key)
    _validate_tag_length(tag_length)

    aesccm = AESCCM(key, tag_length=tag_length)
    ciphertext = aesccm.encrypt(nonce, plaintext, aad)

    logger.info(
        "AES-CCM encrypt: plaintext=%d bytes, aad=%d bytes, "
        "nonce=%d bytes, tag=%d bytes -> ciphertext=%d bytes",
        len(plaintext),
        len(aad) if aad else 0,
        len(nonce),
        tag_length,
        len(ciphertext),
    )
    return ciphertext


def decrypt(
    key: bytes,
    ciphertext: bytes,
    nonce: bytes,
    aad: bytes | None = None,
    tag_length: int = _DEFAULT_TAG_LENGTH,
) -> bytes:
    """
    Decrypt and verify *ciphertext* using AES-CCM-128.

    Args:
        key:        16-byte AES key.
        ciphertext: Data to decrypt (includes authentication tag).
        nonce:      Nonce / IV used during encryption.
        aad:        Additional authenticated data (must match encryption).
        tag_length: Authentication tag length (must match encryption).

    Returns:
        The decrypted plaintext.

    Raises:
        ValueError: If *key* is not 16 bytes or *tag_length* is invalid.
        cryptography.exceptions.InvalidTag: If authentication fails
            (tampered ciphertext or wrong key/nonce/aad).
    """
    _validate_key(key)
    _validate_tag_length(tag_length)

    aesccm = AESCCM(key, tag_length=tag_length)
    plaintext = aesccm.decrypt(nonce, ciphertext, aad)

    logger.info(
        "AES-CCM decrypt: ciphertext=%d bytes, aad=%d bytes, "
        "nonce=%d bytes, tag=%d bytes -> plaintext=%d bytes",
        len(ciphertext),
        len(aad) if aad else 0,
        len(nonce),
        tag_length,
        len(plaintext),
    )
    return plaintext


def _validate_key(key: bytes) -> None:
    """Raise ValueError if *key* is not a valid AES-128 key."""
    if not isinstance(key, (bytes, bytearray)):  # type: ignore[redundant-expr]
        raise TypeError(f"Key must be bytes, got {type(key).__name__}")
    if len(key) != _KEY_LENGTH:
        raise ValueError(
            f"Key must be {_KEY_LENGTH} bytes, got {len(key)}"
        )


def _validate_tag_length(tag_length: int) -> None:
    """Raise ValueError if *tag_length* is not a valid CCM tag length."""
    valid = {4, 6, 8, 10, 12, 14, 16}
    if tag_length not in valid:
        raise ValueError(
            f"Tag length must be one of {sorted(valid)}, got {tag_length}"
        )
