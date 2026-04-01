"""
MILENAGE authentication algorithm (3GPP TS 35.206).

MILENAGE is the standard 3GPP algorithm set used for mutual authentication
in UMTS/EAP-AKA.  It derives authentication vectors from a subscriber key
(K) and operator-specific constants (OP / OPc).

In the Omnipod 5 protocol, K = LTK (the 16-byte Long-Term Key derived
during pairing) and OP is assumed to be all-zeros (producing OPc via
AES-128 encryption of OP under K, XORed with OP).

The five MILENAGE functions:
    f1(K, RAND, SQN, AMF) -> MAC-A    (network authentication code)
    f2(K, RAND)           -> XRES      (expected response, 8 bytes)
    f3(K, RAND)           -> CK        (cipher key, 16 bytes)
    f4(K, RAND)           -> IK        (integrity key, 16 bytes)
    f5(K, RAND)           -> AK        (anonymity key, 6 bytes)
    f1star / f5star        -> for resync (not implemented yet)

Reference: 3GPP TS 35.206 V17.0.0, 3GPP TS 35.208 (test vectors)
Reference: LTK_DERIVATION.md, Step 8 (SIM profile -> EAP-AKA)
"""

from __future__ import annotations

import logging
from typing import NamedTuple

from cryptography.hazmat.primitives.ciphers import Cipher, algorithms, modes

logger = logging.getLogger(__name__)

# MILENAGE rotation constants (r1..r5) from TS 35.206, Section 3.
_R1 = 64   # rotate for f1
_R2 = 0    # rotate for f2
_R3 = 32   # rotate for f3
_R4 = 64   # rotate for f4
_R5 = 96   # rotate for f5

# MILENAGE constants (c1..c5) from TS 35.206, Section 3.
# Each is 128 bits.  c1 is all-zeros; c2[127]=1; c3[126]=1; c4[125]=1; c5[124]=1.
_C1 = bytes(16)
_C2 = bytes(15) + b"\x01"
_C3 = bytes(15) + b"\x02"
_C4 = bytes(15) + b"\x04"
_C5 = bytes(15) + b"\x08"


class AuthVector(NamedTuple):
    """Authentication vector produced by MILENAGE."""

    mac_a: bytes
    """8-byte network authentication code (f1)."""

    xres: bytes
    """8-byte expected response (f2)."""

    ck: bytes
    """16-byte cipher key (f3)."""

    ik: bytes
    """16-byte integrity key (f4)."""

    ak: bytes
    """6-byte anonymity key (f5)."""


class Milenage:
    """
    MILENAGE algorithm implementation using AES-128.

    Args:
        k:  Subscriber key (16 bytes).  For Omnipod 5 this is the LTK.
        op: Operator-specific constant (16 bytes).  Defaults to all-zeros.
    """

    def __init__(self, k: bytes, op: bytes | None = None) -> None:
        if len(k) != 16:
            raise ValueError(f"K must be 16 bytes, got {len(k)}")

        self._k = k

        if op is None:
            op = bytes(16)
        if len(op) != 16:
            raise ValueError(f"OP must be 16 bytes, got {len(op)}")

        # Compute OPc = AES_K(OP) XOR OP
        self._opc = _xor(self._aes_encrypt(op), op)

        logger.info("MILENAGE initialized: K=%d bytes, OPc computed", len(k))

    def generate_auth_vector(
        self, rand: bytes, sqn: bytes, amf: bytes
    ) -> AuthVector:
        """
        Generate a full authentication vector from RAND, SQN, AMF.

        Args:
            rand: Random challenge (16 bytes).
            sqn:  Sequence number (6 bytes).
            amf:  Authentication management field (2 bytes).

        Returns:
            An ``AuthVector`` with mac_a, xres, ck, ik, ak.

        Raises:
            ValueError: If any input has an unexpected length.
        """
        if len(rand) != 16:
            raise ValueError(f"RAND must be 16 bytes, got {len(rand)}")
        if len(sqn) != 6:
            raise ValueError(f"SQN must be 6 bytes, got {len(sqn)}")
        if len(amf) != 2:
            raise ValueError(f"AMF must be 2 bytes, got {len(amf)}")

        mac_a = self.f1(rand, sqn, amf)
        xres = self.f2(rand)
        ck = self.f3(rand)
        ik = self.f4(rand)
        ak = self.f5(rand)

        logger.info(
            "Auth vector generated: mac_a=%d, xres=%d, ck=%d, ik=%d, ak=%d bytes",
            len(mac_a),
            len(xres),
            len(ck),
            len(ik),
            len(ak),
        )
        return AuthVector(mac_a=mac_a, xres=xres, ck=ck, ik=ik, ak=ak)

    def f1(self, rand: bytes, sqn: bytes, amf: bytes) -> bytes:
        """
        Compute MAC-A (network authentication code).

        Formula (TS 35.206):
            TEMP = E_K(RAND XOR OPc)
            IN1  = SQN || AMF || SQN || AMF
            OUT1 = E_K(rot(IN1 XOR OPc, r1) XOR TEMP) XOR OPc
            f1   = OUT1[0:8]

        Note: the rotation is applied to (IN1 XOR OPc), NOT to (TEMP XOR OPc).

        Returns:
            8-byte MAC-A.
        """
        temp = self._aes_encrypt(_xor(rand, self._opc))

        in1 = sqn + amf + sqn + amf

        # OUT1 = E_K(rot(IN1 ^ OPc, r1) ^ TEMP) ^ OPc
        val = _xor(_rotate_left(_xor(in1, self._opc), _R1), temp)
        out = _xor(self._aes_encrypt(val), self._opc)

        return out[:8]

    def _f2f5(self, rand: bytes) -> tuple[bytes, bytes]:
        """
        Compute f2 (XRES) and f5 (AK) from the same intermediate value.

        Formula (TS 35.206):
            TEMP = E_K(RAND XOR OPc)
            OUT2 = E_K(rot(TEMP XOR OPc, r2) XOR c2) XOR OPc
            f2   = OUT2[8:16]
            f5   = OUT2[0:6]

        Returns:
            Tuple of (xres[8], ak[6]).
        """
        temp = self._aes_encrypt(_xor(rand, self._opc))
        val = _rotate_left(_xor(temp, self._opc), _R2)
        val = _xor(val, _C2)
        out = _xor(self._aes_encrypt(val), self._opc)
        return out[8:16], out[:6]

    def f2(self, rand: bytes) -> bytes:
        """
        Compute XRES (expected response).

        Returns:
            8-byte XRES.
        """
        xres, _ = self._f2f5(rand)
        return xres

    def f3(self, rand: bytes) -> bytes:
        """
        Compute CK (cipher key).

        Formula (TS 35.206):
            OUT3 = E_K(rot(TEMP XOR OPc, r3) XOR c3) XOR OPc

        Returns:
            16-byte CK.
        """
        temp = self._aes_encrypt(_xor(rand, self._opc))
        val = _rotate_left(_xor(temp, self._opc), _R3)
        val = _xor(val, _C3)
        out = _xor(self._aes_encrypt(val), self._opc)
        return out

    def f4(self, rand: bytes) -> bytes:
        """
        Compute IK (integrity key).

        Formula (TS 35.206):
            OUT4 = E_K(rot(TEMP XOR OPc, r4) XOR c4) XOR OPc

        Returns:
            16-byte IK.
        """
        temp = self._aes_encrypt(_xor(rand, self._opc))
        val = _rotate_left(_xor(temp, self._opc), _R4)
        val = _xor(val, _C4)
        out = _xor(self._aes_encrypt(val), self._opc)
        return out

    def f5(self, rand: bytes) -> bytes:
        """
        Compute AK (anonymity key).

        f5 is derived from the same OUT2 as f2 (TS 35.206):
            OUT2 = E_K(rot(TEMP XOR OPc, r2) XOR c2) XOR OPc
            f5   = OUT2[0:6]

        Returns:
            6-byte AK.
        """
        _, ak = self._f2f5(rand)
        return ak

    def _aes_encrypt(self, block: bytes) -> bytes:
        """Single-block AES-128-ECB encryption (the MILENAGE core operation)."""
        cipher = Cipher(algorithms.AES(self._k), modes.ECB())
        encryptor = cipher.encryptor()
        return encryptor.update(block) + encryptor.finalize()


def compute_autn(sqn: bytes, ak: bytes, amf: bytes, mac_a: bytes) -> bytes:
    """
    Build the AUTN parameter from its components.

    AUTN = (SQN XOR AK) || AMF || MAC-A

    Args:
        sqn:   Sequence number (6 bytes).
        ak:    Anonymity key (6 bytes).
        amf:   Authentication management field (2 bytes).
        mac_a: Network authentication code (8 bytes).

    Returns:
        16-byte AUTN.
    """
    concealed_sqn = _xor(sqn, ak)
    return concealed_sqn + amf + mac_a


def validate_autn(
    milenage: Milenage,
    rand: bytes,
    autn: bytes,
    expected_sqn: bytes,
) -> tuple[bool, bytes, bytes, bytes, bytes]:
    """
    Validate an AUTN received in an EAP-AKA challenge (pod side).

    Extracts SQN from AUTN using AK, verifies MAC-A, then returns
    the authentication response values.

    Args:
        milenage:     Initialized Milenage instance with K=LTK.
        rand:         The RAND challenge (16 bytes).
        autn:         The AUTN parameter (16 bytes).
        expected_sqn: The expected SQN (6 bytes) for replay protection.

    Returns:
        A tuple of (valid, xres, ck, ik, ak).
        *valid* is True if MAC-A verification succeeds and SQN is acceptable.

    Raises:
        ValueError: If inputs have unexpected lengths.
    """
    if len(autn) != 16:
        raise ValueError(f"AUTN must be 16 bytes, got {len(autn)}")

    # Compute AK to recover SQN
    ak = milenage.f5(rand)
    concealed_sqn = autn[:6]
    sqn = _xor(concealed_sqn, ak)
    amf = autn[6:8]
    received_mac = autn[8:16]

    # Compute expected MAC-A
    expected_mac = milenage.f1(rand, sqn, amf)

    if received_mac != expected_mac:
        logger.warning("AUTN validation failed: MAC-A mismatch")
        return (False, b"", b"", b"", b"")

    # SQN freshness check (simplified: accept if >= expected)
    sqn_int = int.from_bytes(sqn, "big")
    expected_int = int.from_bytes(expected_sqn, "big")
    if sqn_int < expected_int:
        logger.warning(
            "AUTN validation failed: SQN too old (%d < %d)",
            sqn_int,
            expected_int,
        )
        return (False, b"", b"", b"", b"")

    xres = milenage.f2(rand)
    ck = milenage.f3(rand)
    ik = milenage.f4(rand)

    logger.info("AUTN validation succeeded")
    return (True, xres, ck, ik, ak)


# ---------------------------------------------------------------------------
# Bit-manipulation helpers
# ---------------------------------------------------------------------------


def _xor(a: bytes, b: bytes) -> bytes:
    """XOR two byte strings of equal length."""
    if len(a) != len(b):
        # Allow XOR of different lengths by truncating to the shorter
        min_len = min(len(a), len(b))
        return bytes(x ^ y for x, y in zip(a[:min_len], b[:min_len]))
    return bytes(x ^ y for x, y in zip(a, b))


def _rotate_left(block: bytes, bits: int) -> bytes:
    """
    Rotate a 128-bit (16-byte) block left by *bits* positions.

    If bits == 0, return unchanged.
    """
    if bits == 0:
        return block
    if len(block) != 16:
        raise ValueError(f"Block must be 16 bytes, got {len(block)}")

    n = int.from_bytes(block, "big")
    bits = bits % 128
    rotated = ((n << bits) | (n >> (128 - bits))) & ((1 << 128) - 1)
    return rotated.to_bytes(16, "big")
