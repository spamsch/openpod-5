"""
Elliptic-Curve Diffie-Hellman key exchange (X25519 and P-256).

This module implements the pod side of the ECDH key exchange used during
Omnipod 5 pairing.  The PDM negotiates the curve via the SPS0 command:

    - Algorithm bytes 0x00, 0x08 → X25519 (Curve25519, RFC 7748)
    - Algorithm bytes 0x01, 0x05, 0x09, 0x0D → P-256 (secp256r1)

Key sizes:
    - X25519: 32-byte public key, 32-byte shared secret
    - P-256:  64-byte public key (uncompressed x||y), 32-byte shared secret

Reference: docs/protocol/02-pairing.md, Steps 1-4
"""

from __future__ import annotations

import logging
import os

from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.hazmat.primitives.asymmetric.x25519 import (
    X25519PrivateKey,
    X25519PublicKey,
)
from cryptography.hazmat.primitives.serialization import (
    Encoding,
    PublicFormat,
)

logger = logging.getLogger(__name__)

_NONCE_LENGTH = 16

# Algorithm bytes that select P-256 (from EnumC3934an.java)
_P256_ALGORITHMS = (0x01, 0x05, 0x09, 0x0D)


def is_p256(algorithm: int) -> bool:
    """Return True if the algorithm byte selects P-256."""
    return algorithm in _P256_ALGORITHMS


class EcdhKeyPair:
    """
    An ECDH key pair (X25519 or P-256) with an associated random nonce.

    Attributes:
        public_key_bytes: The raw public key (32 bytes for X25519,
                          64 bytes for P-256 uncompressed).
        nonce:            A 16-byte random nonce.
        algorithm:        The negotiated algorithm byte.
    """

    def __init__(
        self, *, seed: bytes | None = None, algorithm: int = 0x00
    ) -> None:
        """
        Generate a fresh key pair and nonce.

        Args:
            seed:      Optional 32-byte seed for deterministic testing.
            algorithm: SPS0 algorithm byte (0x00/0x08 = X25519,
                       0x01/0x05/0x09/0x0D = P-256).
        """
        self.algorithm = algorithm

        if is_p256(algorithm):
            self._init_p256(seed)
        else:
            self._init_x25519(seed)

        self.nonce: bytes = os.urandom(_NONCE_LENGTH)

        logger.info(
            "ECDH key pair ready: curve=%s, public_key=%d bytes, "
            "nonce=%d bytes",
            "P-256" if is_p256(algorithm) else "X25519",
            len(self.public_key_bytes),
            len(self.nonce),
        )

    # ------------------------------------------------------------------
    # X25519 (Curve25519) — 32-byte public key
    # ------------------------------------------------------------------

    def _init_x25519(self, seed: bytes | None) -> None:
        if seed is not None:
            if len(seed) != 32:
                raise ValueError(
                    f"Seed must be exactly 32 bytes, got {len(seed)}"
                )
            self._private_key = X25519PrivateKey.from_private_bytes(seed)
            logger.debug("X25519 key pair from deterministic seed")
        else:
            self._private_key = X25519PrivateKey.generate()
            logger.debug("X25519 key pair generated randomly")

        pub = self._private_key.public_key()
        self.public_key_bytes: bytes = pub.public_bytes_raw()

    def _compute_x25519(self, peer_bytes: bytes) -> bytes:
        if len(peer_bytes) != 32:
            raise ValueError(
                f"X25519 peer key must be 32 bytes, got {len(peer_bytes)}"
            )
        peer_key = X25519PublicKey.from_public_bytes(peer_bytes)
        return self._private_key.exchange(peer_key)

    # ------------------------------------------------------------------
    # P-256 (secp256r1) — 64-byte uncompressed public key
    # ------------------------------------------------------------------

    def _init_p256(self, seed: bytes | None) -> None:
        if seed is not None:
            # Derive deterministic P-256 private key from seed.
            # The seed is interpreted as a big-endian integer mod n.
            private_value = int.from_bytes(seed[:32], "big")
            self._private_key = ec.derive_private_key(
                private_value, ec.SECP256R1()
            )
            logger.debug("P-256 key pair from deterministic seed")
        else:
            self._private_key = ec.generate_private_key(ec.SECP256R1())
            logger.debug("P-256 key pair generated randomly")

        pub = self._private_key.public_key()
        # X9.62 uncompressed = 0x04 || x(32) || y(32).  Strip the 0x04
        # prefix to get 64 raw bytes, matching the native library format.
        raw = pub.public_bytes(Encoding.X962, PublicFormat.UncompressedPoint)
        self.public_key_bytes: bytes = raw[1:]  # 64 bytes

    def _compute_p256(self, peer_bytes: bytes) -> bytes:
        if len(peer_bytes) != 64:
            raise ValueError(
                f"P-256 peer key must be 64 bytes, got {len(peer_bytes)}"
            )
        # Reconstruct uncompressed point with 0x04 prefix.
        peer_key = ec.EllipticCurvePublicKey.from_encoded_point(
            ec.SECP256R1(), b"\x04" + peer_bytes
        )
        return self._private_key.exchange(ec.ECDH(), peer_key)

    # ------------------------------------------------------------------
    # Public interface
    # ------------------------------------------------------------------

    def compute_shared_secret(self, peer_public_key_bytes: bytes) -> bytes:
        """
        Compute the ECDH shared secret from the peer's public key.

        Returns the 32-byte shared secret.
        """
        if is_p256(self.algorithm):
            shared = self._compute_p256(peer_public_key_bytes)
        else:
            shared = self._compute_x25519(peer_public_key_bytes)

        logger.info(
            "ECDH shared secret computed: %d bytes", len(shared)
        )
        return shared
