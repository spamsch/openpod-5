"""
EAP-AKA protocol implementation (pod / slave side).

EAP-AKA (Extensible Authentication Protocol -- Authentication and Key
Agreement) provides mutual authentication between the phone (authenticator)
and the pod (peer) after pairing.  It uses MILENAGE with K=LTK to derive
session keys for AES-CCM encryption.

The pod-side flow:
    1. Receive EAP-Request/AKA-Challenge from phone
       -> contains AT_RAND (16 bytes) and AT_AUTN (16 bytes)
    2. Validate AUTN using MILENAGE (verify MAC-A, check SQN)
    3. Compute RES, CK, IK using MILENAGE
    4. Send EAP-Response/AKA-Challenge back (contains AT_RES)
    5. Derive session keys from CK and IK

EAP-AKA message format (simplified):
    [Code(1) | Identifier(1) | Length(2) | Type(1) | Subtype(1) | Reserved(2) | Attributes...]

Attribute format:
    [Type(1) | Length(1, in 4-byte words) | Value(variable)]

Reference: RFC 4187 (EAP-AKA), RFC 3748 (EAP)
Reference: RFC 4187 Section 7 (EAP-AKA procedures)
"""

from __future__ import annotations

import enum
import hashlib
import hmac
import logging
from dataclasses import dataclass, field

from omnipod_emulator.crypto.milenage import Milenage, validate_autn

logger = logging.getLogger(__name__)

# ---------------------------------------------------------------------------
# EAP constants
# ---------------------------------------------------------------------------

# EAP codes (RFC 3748, Section 4)
EAP_REQUEST = 1
EAP_RESPONSE = 2
EAP_SUCCESS = 3
EAP_FAILURE = 4

# EAP type for AKA (RFC 4187)
EAP_TYPE_AKA = 23

# EAP-AKA subtypes (RFC 4187, Section 9.1)
AKA_CHALLENGE = 1
AKA_AUTHENTICATION_REJECT = 2
AKA_SYNCHRONIZATION_FAILURE = 4
AKA_IDENTITY = 5

# EAP-AKA attribute types (RFC 4187, Section 10)
AT_RAND = 1
AT_AUTN = 2
AT_RES = 3
AT_AUTS = 4
AT_MAC = 11
AT_IV = 129
AT_ENCR_DATA = 130
AT_CHECKCODE = 134

# ---------------------------------------------------------------------------
# Session state
# ---------------------------------------------------------------------------


class EapAkaState(enum.Enum):
    """EAP-AKA session states."""

    IDLE = "idle"
    CHALLENGE_RECEIVED = "challenge_received"
    AUTHENTICATED = "authenticated"
    FAILED = "failed"


@dataclass
class SessionKeys:
    """Session keys derived from a successful EAP-AKA exchange."""

    ck: bytes = b""
    """16-byte cipher key from MILENAGE f3."""

    ik: bytes = b""
    """16-byte integrity key from MILENAGE f4."""

    encryption_key: bytes = b""
    """AES-CCM session encryption key: CK directly (per TWI SDK behaviour)."""


@dataclass
class EapAkaSlave:
    """
    Pod-side EAP-AKA protocol handler.

    This implements the peer (supplicant) role in EAP-AKA.  It processes
    EAP-Request/AKA-Challenge messages from the phone and produces
    EAP-Response/AKA-Challenge messages containing RES.

    Args:
        ltk: The 16-byte Long-Term Key derived from pairing.
        sqn: The initial sequence number (6 bytes).  Defaults to zero.
        op:  The MILENAGE OP constant (16 bytes).  Defaults to all-zeros.
    """

    ltk: bytes
    sqn: bytes = field(default_factory=lambda: b"\x00" * 6)
    op: bytes = field(default_factory=lambda: b"\x00" * 16)

    state: EapAkaState = field(default=EapAkaState.IDLE, init=False)
    session_keys: SessionKeys = field(default_factory=SessionKeys, init=False)
    _milenage: Milenage = field(init=False, repr=False)
    _last_identifier: int = field(default=0, init=False)

    def __post_init__(self) -> None:
        if len(self.ltk) != 16:
            raise ValueError(f"LTK must be 16 bytes, got {len(self.ltk)}")
        self._milenage = Milenage(self.ltk, self.op)
        logger.info("EAP-AKA slave initialized")

    def process_message(self, data: bytes) -> bytes | None:
        """
        Process an incoming EAP message and return the response (if any).

        Args:
            data: The raw EAP message bytes.

        Returns:
            The response EAP message bytes, or None if no response is needed
            (e.g., on EAP-Success or EAP-Failure).

        Raises:
            ValueError: If the message is malformed.
        """
        if len(data) < 4:
            raise ValueError(f"EAP message too short: {len(data)} bytes")

        code = data[0]
        identifier = data[1]
        length = int.from_bytes(data[2:4], "big")
        self._last_identifier = identifier

        if length != len(data):
            logger.warning(
                "EAP length mismatch: header says %d, got %d",
                length,
                len(data),
            )

        if code == EAP_SUCCESS:
            logger.info("EAP-Success received")
            self.state = EapAkaState.AUTHENTICATED
            return None

        if code == EAP_FAILURE:
            logger.warning("EAP-Failure received")
            self.state = EapAkaState.FAILED
            return None

        if code != EAP_REQUEST:
            logger.warning("Unexpected EAP code: %d", code)
            return None

        # Must be an EAP-Request
        if len(data) < 8:
            raise ValueError("EAP-Request too short for AKA header")

        eap_type = data[4]
        if eap_type != EAP_TYPE_AKA:
            logger.warning("Non-AKA EAP type: %d", eap_type)
            return None

        subtype = data[5]
        # reserved = data[6:8]

        if subtype == AKA_CHALLENGE:
            return self._handle_challenge(identifier, data[8:])
        elif subtype == AKA_IDENTITY:
            return self._handle_identity(identifier, data[8:])
        else:
            logger.warning("Unhandled AKA subtype: %d", subtype)
            return None

    def _handle_identity(self, identifier: int, _attrs_data: bytes) -> bytes:
        """
        Handle EAP-Request/AKA-Identity.

        For simplicity the pod responds with an empty identity.
        """
        logger.info("AKA-Identity request received")
        # Build EAP-Response/AKA-Identity with no attributes
        return self._build_eap_response(identifier, AKA_IDENTITY, b"")

    def _handle_challenge(
        self, identifier: int, attrs_data: bytes
    ) -> bytes:
        """
        Handle EAP-Request/AKA-Challenge.

        Extracts AT_RAND and AT_AUTN, validates AUTN, computes RES,
        and returns EAP-Response/AKA-Challenge with AT_RES.
        """
        logger.info("AKA-Challenge received, processing...")

        attrs = _parse_attributes(attrs_data)

        if AT_RAND not in attrs:
            logger.error("AKA-Challenge missing AT_RAND")
            self.state = EapAkaState.FAILED
            return self._build_auth_reject(identifier)

        if AT_AUTN not in attrs:
            logger.error("AKA-Challenge missing AT_AUTN")
            self.state = EapAkaState.FAILED
            return self._build_auth_reject(identifier)

        rand = attrs[AT_RAND][:16]
        autn = attrs[AT_AUTN][:16]

        logger.info(
            "Challenge params: RAND=%d bytes, AUTN=%d bytes",
            len(rand),
            len(autn),
        )

        # Validate AUTN and compute response values
        valid, xres, ck, ik, ak = validate_autn(
            self._milenage, rand, autn, self.sqn
        )

        if not valid:
            logger.warning("AUTN validation failed -- sending auth reject")
            self.state = EapAkaState.FAILED
            return self._build_auth_reject(identifier)

        # Update SQN (increment by 1 for next use)
        recovered_ak = ak
        concealed_sqn = autn[:6]
        sqn_bytes = bytes(a ^ b for a, b in zip(concealed_sqn, recovered_ak))
        new_sqn = int.from_bytes(sqn_bytes, "big") + 1
        self.sqn = new_sqn.to_bytes(6, "big")

        # Derive K_aut per RFC 4187 Section 7 for AT_MAC verification
        # MK = SHA-1(Identity | IK | CK), K_encr = MK[0:16], K_aut = MK[16:32]
        identity = b""  # Empty identity (pod uses empty identity in AKA-Identity)
        mk = hashlib.sha1(identity + ik + ck).digest()  # noqa: S324
        k_aut = mk[16:32]

        # Verify AT_MAC if present (RFC 4187 Section 10.15)
        if AT_MAC in attrs:
            at_mac_value = attrs[AT_MAC][:16]
            # Reconstruct the original EAP message for MAC computation:
            # The MAC is computed over the full EAP message with AT_MAC zeroed
            eap_header = bytes([
                EAP_REQUEST, identifier,
            ])
            aka_header = bytes([EAP_TYPE_AKA, AKA_CHALLENGE, 0, 0])
            # Rebuild attrs with AT_MAC value zeroed
            zeroed_attrs = _rebuild_attrs_with_zeroed_mac(attrs_data)
            full_msg = aka_header + zeroed_attrs
            total_len = (4 + len(full_msg)).to_bytes(2, "big")
            mac_input = eap_header + total_len + full_msg

            expected_mac = hmac.new(k_aut, mac_input, hashlib.sha256).digest()[:16]

            if not hmac.compare_digest(at_mac_value, expected_mac):
                logger.warning("AT_MAC verification failed — rejecting challenge")
                self.state = EapAkaState.FAILED
                return self._build_auth_reject(identifier)
            logger.info("AT_MAC verified successfully")
        else:
            logger.warning("AT_MAC absent in challenge — proceeding without MAC verification")

        # Store session keys
        self.session_keys.ck = ck
        self.session_keys.ik = ik

        # Use CK directly as the AES-CCM encryption key.
        # The real TWI SDK uses CK (MILENAGE f3) as the session key after
        # EAP-AKA completes. IK is kept for AT_MAC computation.
        self.session_keys.encryption_key = ck

        self.state = EapAkaState.CHALLENGE_RECEIVED

        logger.info(
            "AUTN validated, session keys derived: CK=%d, IK=%d, encryption_key=%d bytes",
            len(ck),
            len(ik),
            len(self.session_keys.encryption_key),
        )

        # Build AT_RES attribute
        # AT_RES format: [type=3, length_words, res_bit_length(2), RES...]
        res_bits = len(xres) * 8
        at_res = _build_attribute(AT_RES, res_bits.to_bytes(2, "big") + xres)

        # Include AT_MAC in response if the challenge contained one
        response_attrs = at_res
        if AT_MAC in attrs:
            # Compute response AT_MAC
            res_aka_header = bytes([EAP_TYPE_AKA, AKA_CHALLENGE, 0, 0])
            res_at_res = at_res
            # Placeholder MAC (16 zero bytes) for computation
            placeholder_mac = _build_attribute(AT_MAC, bytes(16))
            res_payload = res_aka_header + res_at_res + placeholder_mac
            res_total_len = (4 + len(res_payload)).to_bytes(2, "big")
            res_eap_header = bytes([EAP_RESPONSE, identifier])
            res_mac_input = res_eap_header + res_total_len + res_payload
            res_mac = hmac.new(k_aut, res_mac_input, hashlib.sha256).digest()[:16]
            response_attrs += _build_attribute(AT_MAC, res_mac)

        return self._build_eap_response(identifier, AKA_CHALLENGE, response_attrs)

    def _build_eap_response(
        self, identifier: int, subtype: int, attributes: bytes
    ) -> bytes:
        """Build a complete EAP-Response/AKA message."""
        # AKA header: type(1) + subtype(1) + reserved(2) = 4 bytes
        aka_payload = bytes([EAP_TYPE_AKA, subtype, 0, 0]) + attributes

        # EAP header: code(1) + id(1) + length(2) = 4 bytes
        total_length = 4 + len(aka_payload)
        header = bytes([EAP_RESPONSE, identifier]) + total_length.to_bytes(
            2, "big"
        )

        msg = header + aka_payload
        logger.info(
            "EAP-Response built: subtype=%d, total=%d bytes",
            subtype,
            len(msg),
        )
        return msg

    def _build_auth_reject(self, identifier: int) -> bytes:
        """Build an EAP-Response/AKA-Authentication-Reject."""
        return self._build_eap_response(
            identifier, AKA_AUTHENTICATION_REJECT, b""
        )


# ---------------------------------------------------------------------------
# Attribute parsing / building helpers
# ---------------------------------------------------------------------------


def _parse_attributes(data: bytes) -> dict[int, bytes]:
    """
    Parse EAP-AKA attributes from raw bytes.

    Each attribute is: [type(1) | length_in_4byte_words(1) | value(...)].
    The length includes the type and length bytes themselves.

    Returns:
        A dict mapping attribute type to value bytes (excluding the 2-byte
        type+length header and any reserved padding).
    """
    attrs: dict[int, bytes] = {}
    offset = 0

    while offset + 2 <= len(data):
        attr_type = data[offset]
        attr_len_words = data[offset + 1]
        attr_len_bytes = attr_len_words * 4

        if attr_len_bytes < 4:
            logger.warning(
                "Invalid attribute length at offset %d: %d words",
                offset,
                attr_len_words,
            )
            break

        if offset + attr_len_bytes > len(data):
            logger.warning(
                "Attribute at offset %d extends beyond data "
                "(need %d, have %d)",
                offset,
                attr_len_bytes,
                len(data) - offset,
            )
            break

        # Value starts after type(1) + length(1) + reserved(2) = 4 bytes
        value = data[offset + 4 : offset + attr_len_bytes]
        attrs[attr_type] = value

        logger.debug(
            "Parsed attribute: type=%d, length=%d words, value=%d bytes",
            attr_type,
            attr_len_words,
            len(value),
        )

        offset += attr_len_bytes

    return attrs


def _build_attribute(attr_type: int, value: bytes) -> bytes:
    """
    Build a single EAP-AKA attribute.

    Pads the value to a multiple of 4 bytes (the attribute length is
    expressed in 4-byte words and includes the 4-byte header).
    """
    # Total = 4 (header) + len(value), rounded up to multiple of 4
    padded_value_len = ((len(value) + 3) // 4) * 4
    total_len = 4 + padded_value_len
    length_words = total_len // 4

    result = bytes([attr_type, length_words, 0, 0])
    result += value
    # Pad to alignment
    padding_needed = padded_value_len - len(value)
    if padding_needed > 0:
        result += bytes(padding_needed)

    return result


def _rebuild_attrs_with_zeroed_mac(attrs_data: bytes) -> bytes:
    """
    Rebuild EAP-AKA attributes with the AT_MAC value zeroed (for MAC computation).

    Walks through the raw attribute bytes, finds AT_MAC, and replaces its
    value with zeros while preserving all other attributes unchanged.
    """
    result = bytearray(attrs_data)
    offset = 0

    while offset + 2 <= len(result):
        attr_type = result[offset]
        attr_len_words = result[offset + 1]
        attr_len_bytes = attr_len_words * 4

        if attr_len_bytes < 4 or offset + attr_len_bytes > len(result):
            break

        if attr_type == AT_MAC:
            # Zero the MAC value (bytes 4..end of attribute)
            for i in range(offset + 4, offset + attr_len_bytes):
                result[i] = 0

        offset += attr_len_bytes

    return bytes(result)
