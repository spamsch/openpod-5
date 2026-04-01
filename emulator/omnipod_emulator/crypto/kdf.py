"""
SHA-256 based Key Derivation Function for Omnipod 5 LTK derivation.

The KDF concatenates length-prefixed protocol identifiers, public keys, and
the ECDH shared secret, then hashes the result with SHA-256.  The 32-byte
hash is split into two 16-byte keys:

    confirmation_key = hash[0:16]   (used for AES-CMAC confirmation)
    ltk              = hash[16:32]  (Long-Term Key for MILENAGE/EAP-AKA)

Length prefix format (8 bytes):
    [0x00, 0x00, 0x00, 0x00, hi_byte, lo_byte, 0x00, 0x00]

Where hi_byte:lo_byte is the big-endian 16-bit length of the following data.

IMPORTANT -- key ordering depends on the role:
    Role 0 (phone/controller): local_pubkey first, then peer_pubkey
    Role 1 (pod/node):         peer_pubkey first, then local_pubkey

This emulator is the POD (role=1), so it places the phone's public key
before its own public key in the KDF input.

Reference: LTK_DERIVATION.md, Step 5 (cert_key_derivation)
"""

from __future__ import annotations

import hashlib
import logging
from typing import NamedTuple

logger = logging.getLogger(__name__)


class DerivedKeys(NamedTuple):
    """Keys produced by the KDF."""

    confirmation_key: bytes
    """16-byte confirmation key (hash[0:16])."""

    ltk: bytes
    """16-byte Long-Term Key (hash[16:32])."""


def _length_prefix(data: bytes) -> bytes:
    """
    Build the 8-byte length prefix for *data*.

    Format: ``00 00 00 00  <len_hi> <len_lo>  00 00``

    Raises:
        ValueError: If *data* is longer than 65535 bytes.
    """
    length = len(data)
    if length > 0xFFFF:
        raise ValueError(f"Data too long for length prefix: {length}")
    return b"\x00\x00\x00\x00" + length.to_bytes(2, "big") + b"\x00\x00"


def derive_keys(
    firmware_id: bytes,
    controller_id: bytes,
    pod_public_key: bytes,
    phone_public_key: bytes,
    shared_secret: bytes,
) -> DerivedKeys:
    """
    Derive confirmation key and LTK from the ECDH shared secret.

    The hash input is the concatenation of length-prefixed protocol fields:

        len_prefix(firmware_id)      || firmware_id      ||
        len_prefix(controller_id)    || controller_id    ||
        len_prefix(phone_public_key) || phone_public_key ||   # <-- pod role: phone key first
        len_prefix(pod_public_key)   || pod_public_key   ||   # <-- then own key
        len_prefix(shared_secret)    || shared_secret

    Args:
        firmware_id:      Pod firmware/identity bytes (6 bytes).
        controller_id:    Phone controller ID (4 bytes).
        pod_public_key:   This pod's raw X25519 public key (32 bytes).
        phone_public_key: The phone's raw X25519 public key (32 bytes).
        shared_secret:    The 32-byte X25519 shared secret.

    Returns:
        A ``DerivedKeys`` named tuple of (confirmation_key, ltk), each
        16 bytes.

    Raises:
        ValueError: If any input has an unexpected length.
    """
    # ---- input validation ------------------------------------------------
    _validate("firmware_id", firmware_id, 6)
    _validate("controller_id", controller_id, 4)
    _validate("pod_public_key", pod_public_key, 32)
    _validate("phone_public_key", phone_public_key, 32)
    _validate("shared_secret", shared_secret, 32)

    # ---- build the hash input (pod is role=1: phone key first) ----------
    hasher = hashlib.sha256()

    hasher.update(_length_prefix(firmware_id))
    hasher.update(firmware_id)

    hasher.update(_length_prefix(controller_id))
    hasher.update(controller_id)

    # Role 1 (pod): phone's public key comes first, then the pod's own key
    hasher.update(_length_prefix(phone_public_key))
    hasher.update(phone_public_key)

    hasher.update(_length_prefix(pod_public_key))
    hasher.update(pod_public_key)

    hasher.update(_length_prefix(shared_secret))
    hasher.update(shared_secret)

    digest = hasher.digest()  # 32 bytes

    confirmation_key = digest[:16]
    ltk = digest[16:]

    logger.info(
        "KDF complete: confirmation_key=%d bytes, ltk=%d bytes",
        len(confirmation_key),
        len(ltk),
    )
    return DerivedKeys(confirmation_key=confirmation_key, ltk=ltk)


def derive_keys_for_role(
    role: int,
    firmware_id: bytes,
    controller_id: bytes,
    local_public_key: bytes,
    peer_public_key: bytes,
    shared_secret: bytes,
) -> DerivedKeys:
    """
    Role-aware wrapper around :func:`derive_keys`.

    Args:
        role: 0 for phone/controller, 1 for pod/node.
        firmware_id:      Pod firmware/identity bytes (6 bytes).
        controller_id:    Phone controller ID (4 bytes).
        local_public_key: Caller's own raw X25519 public key (32 bytes).
        peer_public_key:  The other side's raw X25519 public key (32 bytes).
        shared_secret:    The 32-byte X25519 shared secret.

    Returns:
        A ``DerivedKeys`` named tuple.

    Raises:
        ValueError: If *role* is not 0 or 1, or if any input has a bad length.
    """
    if role == 0:
        # Phone: local key first, peer key second
        first_key = local_public_key
        second_key = peer_public_key
    elif role == 1:
        # Pod: peer (phone) key first, local (pod) key second
        first_key = peer_public_key
        second_key = local_public_key
    else:
        raise ValueError(f"Invalid role: {role} (must be 0 or 1)")

    # In derive_keys the signature already expects pod-role ordering
    # (phone_public_key, pod_public_key).  Map appropriately:
    if role == 0:
        return _derive_keys_raw(
            firmware_id, controller_id, first_key, second_key, shared_secret
        )
    else:
        return _derive_keys_raw(
            firmware_id, controller_id, first_key, second_key, shared_secret
        )


def _derive_keys_raw(
    firmware_id: bytes,
    controller_id: bytes,
    first_public_key: bytes,
    second_public_key: bytes,
    shared_secret: bytes,
) -> DerivedKeys:
    """
    Internal: derive keys with explicit key ordering (no role logic).

    The first_public_key and second_public_key are placed in that exact
    order in the hash input.
    """
    _validate("firmware_id", firmware_id, 6)
    _validate("controller_id", controller_id, 4)
    _validate("first_public_key", first_public_key, 32)
    _validate("second_public_key", second_public_key, 32)
    _validate("shared_secret", shared_secret, 32)

    hasher = hashlib.sha256()

    hasher.update(_length_prefix(firmware_id))
    hasher.update(firmware_id)

    hasher.update(_length_prefix(controller_id))
    hasher.update(controller_id)

    hasher.update(_length_prefix(first_public_key))
    hasher.update(first_public_key)

    hasher.update(_length_prefix(second_public_key))
    hasher.update(second_public_key)

    hasher.update(_length_prefix(shared_secret))
    hasher.update(shared_secret)

    digest = hasher.digest()

    confirmation_key = digest[:16]
    ltk = digest[16:]

    logger.info(
        "KDF (raw) complete: confirmation_key=%d bytes, ltk=%d bytes",
        len(confirmation_key),
        len(ltk),
    )
    return DerivedKeys(confirmation_key=confirmation_key, ltk=ltk)


def _validate(name: str, data: bytes, expected_len: int) -> None:
    """Raise ValueError if *data* is not exactly *expected_len* bytes."""
    if not isinstance(data, (bytes, bytearray)):  # type: ignore[redundant-expr]
        raise TypeError(f"{name} must be bytes, got {type(data).__name__}")
    if len(data) != expected_len:
        raise ValueError(
            f"{name} must be {expected_len} bytes, got {len(data)}"
        )
