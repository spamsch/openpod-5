"""
X25519 (Curve25519) Elliptic-Curve Diffie-Hellman key exchange.

This module implements the pod side of the ECDH key exchange used during
Omnipod 5 pairing.  The Omnipod 5 protocol defaults to X25519 (RFC 7748)
with P-256 ECDH as a fallback.  This emulator implements X25519 only.

Key exchange flow (pod perspective):
    1. Generate a random X25519 key pair and a 16-byte nonce
    2. Receive the phone's public key (32 bytes)
    3. Compute the shared secret: X25519(pod_private, phone_public)
    4. Pass the shared secret into the KDF (see kdf.py)

Reference: LTK_DERIVATION.md, Steps 1-4
"""

from __future__ import annotations

import logging
import os

from cryptography.hazmat.primitives.asymmetric.x25519 import (
    X25519PrivateKey,
    X25519PublicKey,
)

logger = logging.getLogger(__name__)

# Nonce length used by the Omnipod 5 protocol (16 bytes).
_NONCE_LENGTH = 16

# X25519 public key length in bytes.
_PUBLIC_KEY_LENGTH = 32


class EcdhKeyPair:
    """
    An X25519 key pair with an associated random nonce.

    The nonce is exchanged alongside the public key during the pairing
    handshake and is used later in the confirmation-value calculation.

    Attributes:
        public_key_bytes: The 32-byte raw public key.
        nonce: A 16-byte random nonce generated at construction time.
    """

    def __init__(self, *, seed: bytes | None = None) -> None:
        """
        Generate a fresh X25519 key pair and nonce.

        Args:
            seed: If provided (32 bytes), used as the private key material
                  for deterministic / reproducible testing.  Must be exactly
                  32 bytes.  **Never reuse a seed in production.**
        """
        if seed is not None:
            if len(seed) != 32:
                raise ValueError(
                    f"Seed must be exactly 32 bytes, got {len(seed)}"
                )
            self._private_key = X25519PrivateKey.from_private_bytes(seed)
            logger.debug(
                "ECDH key pair created from deterministic seed (32 bytes)"
            )
        else:
            self._private_key = X25519PrivateKey.generate()
            logger.debug("ECDH key pair generated randomly")

        self._public_key = self._private_key.public_key()
        self.public_key_bytes: bytes = self._public_key.public_bytes_raw()
        self.nonce: bytes = os.urandom(_NONCE_LENGTH)

        logger.info(
            "ECDH key pair ready: public_key=%d bytes, nonce=%d bytes",
            len(self.public_key_bytes),
            len(self.nonce),
        )

    def compute_shared_secret(self, peer_public_key_bytes: bytes) -> bytes:
        """
        Compute the X25519 shared secret from the peer's public key.

        Args:
            peer_public_key_bytes: The peer's raw 32-byte X25519 public key.

        Returns:
            The 32-byte shared secret.

        Raises:
            ValueError: If *peer_public_key_bytes* is not 32 bytes.
            cryptography.exceptions.InvalidKey: If the peer key is invalid.
        """
        if len(peer_public_key_bytes) != _PUBLIC_KEY_LENGTH:
            raise ValueError(
                f"Peer public key must be {_PUBLIC_KEY_LENGTH} bytes, "
                f"got {len(peer_public_key_bytes)}"
            )

        peer_key = X25519PublicKey.from_public_bytes(peer_public_key_bytes)
        shared_secret = self._private_key.exchange(peer_key)

        logger.info(
            "ECDH shared secret computed: %d bytes", len(shared_secret)
        )
        return shared_secret
