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

Reverse-engineering note:
    The real v6.9.8 phone-side implementation only accepts inbound
    subtype 1/2/4/14 while the master FSM is waiting for the pod
    response. It does not appear to accept pod-originated subtype 12
    (`AKA-Notification`) as the normal post-auth path. After a valid
    subtype-1 AKA-Response, the phone appears to generate its own
    4-byte follow-up. Keep that distinction in mind when adding
    emulator probes around session establishment.
"""

from __future__ import annotations

import enum
import hashlib
import hmac
import logging
import os
from dataclasses import dataclass, field

from omnipod_emulator import debug_ltk_store
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
AKA_NOTIFICATION = 12
AKA_CLIENT_ERROR = 14

# EAP-AKA attribute types (RFC 4187, Section 10)
AT_RAND = 1
AT_AUTN = 2
AT_RES = 3
AT_AUTS = 4
AT_MAC = 11
AT_NOTIFICATION = 12
AT_IV = 129
AT_ENCR_DATA = 130
AT_CHECKCODE = 134

# AT_NOTIFICATION 16-bit notification-code values (RFC 4187 §10.18).
# High bit (0x8000) = S bit: 1 means success, 0 means failure.
# Bit 14 (0x4000) = P bit: 1 means "before auth only", 0 means "any time".
# "Success, user has been successfully authenticated" = 32768 = 0x8000.
# This remains available for diagnostics, but the v6.9.8 phone-side
# parser does not appear to accept inbound subtype 12.
AT_NOTIFICATION_SUCCESS = 0x8000

# Omnipod-proprietary attribute for exchanging AES-CCM nonce IVs.
# Each side sends a 4-byte random IV; the 8-byte concatenation
# (controller_iv || node_iv) forms the nonce prefix for session
# encryption.  Reference: OmniBLE SessionEstablisher.swift.
AT_CUSTOM_IV = 126  # 0x7E

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

    controller_iv: bytes = b""
    """4-byte nonce prefix from the controller (phone), via AT_CUSTOM_IV."""

    node_iv: bytes = b""
    """4-byte nonce prefix from the node (pod), via AT_CUSTOM_IV."""

    @property
    def nonce_prefix(self) -> bytes:
        """8-byte nonce prefix: controller_iv(4) || node_iv(4)."""
        return self.controller_iv + self.node_iv


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
        op:  The MILENAGE OP constant (16 bytes).  Defaults to the
             Omnipod BLE constant from ``milenage.OMNIPOD_OP``.
    """

    ltk: bytes
    sqn: bytes = field(default_factory=lambda: b"\x00" * 6)
    op: bytes | None = None
    session_id: bytes | None = None
    """
    16-byte pairing session identifier (``sha256(pod_pub || controller_pub)[:16]``).

    When set, ``_handle_challenge`` consults :mod:`debug_ltk_store` for
    a matching LTK override pushed by the external dev-harness client
    and uses it in place of ``self.ltk``. Leave as ``None`` for unit
    tests and reconnect paths where there is no fresh pairing to key
    against.
    """

    ltk_override_timeout_s: float = 1.0
    """
    Maximum time ``_handle_challenge`` will block waiting for a matching
    LTK override to land in the store. 0 disables the wait entirely.
    """

    state: EapAkaState = field(default=EapAkaState.IDLE, init=False)
    session_keys: SessionKeys = field(default_factory=SessionKeys, init=False)
    _milenage: Milenage = field(init=False, repr=False)
    _last_identifier: int = field(default=0, init=False)
    _last_challenge_response: bytes = field(default=b"", init=False, repr=False)
    _ltk_override_applied: bool = field(default=False, init=False)

    def __post_init__(self) -> None:
        # ---- DEBUG LTK OVERRIDE (guardrailed) -----------------------------
        # When OPENPOD_DEBUG_LTK_OVERRIDE is set to 32 hex chars, replace
        # whatever LTK was passed in with that value and log provenance
        # loudly. Used to unblock the EAP-AKA frontier when the emulator's
        # KDF disagrees with the phone-side implementation's persisted
        # session material — e.g. because the Elmo confirmation bypass
        # prevents the native derivation path from running and the phone
        # instead reuses a cached LTK that the emulator has no way to
        # re-derive.
        # This is a DEBUG PATH: the override is never written to
        # pairing.json or any persisted artifact, and any success under
        # the override means "downstream protocol unlocked", NOT "KDF
        # agreement restored".
        override_hex = os.environ.get("OPENPOD_DEBUG_LTK_OVERRIDE", "").strip()
        if override_hex:
            try:
                override = bytes.fromhex(override_hex)
            except ValueError as e:
                raise ValueError(
                    f"OPENPOD_DEBUG_LTK_OVERRIDE is not valid hex: {e}"
                ) from e
            if len(override) != 16:
                raise ValueError(
                    "OPENPOD_DEBUG_LTK_OVERRIDE must decode to exactly 16 "
                    f"bytes, got {len(override)}"
                )
            original_hex = self.ltk.hex() if len(self.ltk) == 16 else "<unset>"
            logger.warning(
                "╔══ DEBUG LTK OVERRIDE ACTIVE ══════════════════════════╗"
            )
            logger.warning(
                "║ OPENPOD_DEBUG_LTK_OVERRIDE is set — replacing EAP-AKA")
            logger.warning(
                "║ LTK with an externally captured value.  This bypasses")
            logger.warning(
                "║ the emulator KDF and only validates downstream flow.")
            logger.warning("║   original ltk (emulator KDF): %s", original_hex)
            logger.warning("║   override ltk (debug oracle): %s", override.hex())
            logger.warning(
                "║ Source: OPENPOD_DEBUG_LTK_OVERRIDE env var."
            )
            logger.warning(
                "║ DO NOT write this value to pairing.json or treat as"
            )
            logger.warning(
                "║ emulator-derived truth.  KDF drift is a known open bug."
            )
            logger.warning(
                "╚═══════════════════════════════════════════════════════╝"
            )
            self.ltk = override
        # -------------------------------------------------------------------

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

    def _apply_dynamic_ltk_override(self) -> None:
        """
        Consult :mod:`debug_ltk_store` for an LTK pushed by the external
        dev-harness client during pairing and, if one is present for the
        current ``session_id``, replace ``self.ltk`` and rebuild MILENAGE.

        Runs at most once per :class:`EapAkaSlave` instance — a single
        pairing session should only produce a single LTK, and re-reading
        on subsequent challenges would let a late unrelated push corrupt
        an already-active session.

        Uses :func:`debug_ltk_store.consume_ltk` so the store entry is
        deleted on successful read; if an even later (cryptographically
        improbable) session_id collision were to occur, the new slave
        would not silently pick up the stale LTK.

        TODO: this is a synchronous block on a ``threading.Condition``
        called from the asyncio BLE handler. Acceptable for the dev
        harness; revisit with ``asyncio.to_thread`` if the top-level
        handler ever goes async.
        """
        if self._ltk_override_applied:
            return
        if self.session_id is None:
            return
        if self.ltk_override_timeout_s <= 0:
            # Explicitly disabled — don't touch the store at all, and
            # don't log a spurious "no override" warning.
            self._ltk_override_applied = True
            return

        override = debug_ltk_store.consume_ltk(
            self.session_id,
            timeout=self.ltk_override_timeout_s,
        )
        self._ltk_override_applied = True

        if override is None:
            logger.warning(
                "[ltk-store] no override for session_id=%s within %.2fs "
                "— continuing with emulator-derived LTK (%s); "
                "AUTN validation will likely fail if KDFs disagree",
                self.session_id.hex(),
                self.ltk_override_timeout_s,
                self.ltk.hex(),
            )
            return

        if override == self.ltk:
            logger.info(
                "[ltk-store] override for session_id=%s matches "
                "emulator-derived LTK exactly — nothing to replace",
                self.session_id.hex(),
            )
            return

        logger.warning(
            "╔══ DYNAMIC LTK OVERRIDE APPLIED ═══════════════════════╗"
        )
        logger.warning(
            "║ session_id=%s", self.session_id.hex(),
        )
        logger.warning(
            "║ emulator LTK: %s", self.ltk.hex(),
        )
        logger.warning(
            "║ override LTK: %s", override.hex(),
        )
        logger.warning(
            "║ Source: debug_ltk_store (pushed via /ltk_override)."
        )
        logger.warning(
            "╚═══════════════════════════════════════════════════════╝"
        )
        self.ltk = override
        self._milenage = Milenage(self.ltk, self.op)

    def _handle_challenge(
        self, identifier: int, attrs_data: bytes
    ) -> bytes:
        """
        Handle EAP-Request/AKA-Challenge.

        Extracts AT_RAND and AT_AUTN, validates AUTN, computes RES,
        and returns EAP-Response/AKA-Challenge with AT_RES.
        """
        logger.info("AKA-Challenge received, processing...")

        # Dynamic LTK override barrier: if this pairing session has a
        # matching LTK pushed from the external dev-harness client,
        # swap it in before MILENAGE runs. No-op when session_id is
        # unset (tests, reconnect).
        self._apply_dynamic_ltk_override()

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

        # Exchange nonce IVs via Omnipod-proprietary AT_CUSTOM_IV (0x7E).
        # The controller sends its 4-byte IV; the pod generates its own.
        # Together they form the 8-byte nonce prefix for AES-CCM.
        if AT_CUSTOM_IV in attrs:
            self.session_keys.controller_iv = attrs[AT_CUSTOM_IV][:4]
            self.session_keys.node_iv = os.urandom(4)
            logger.info(
                "IV exchange: controller_iv=%s, node_iv=%s",
                self.session_keys.controller_iv.hex(),
                self.session_keys.node_iv.hex(),
            )
        else:
            logger.warning(
                "AT_CUSTOM_IV absent in challenge — "
                "nonce prefix will be empty (test mode)"
            )

        # Omnipod 5's TWI-embedded EAP-AKA profile does not deliver an
        # inbound EAP-Success to the peer: we have never observed one
        # on the wire, and the phone-side Java session coordinator has
        # no "authenticator sends Success to the pod" step before it
        # starts its post-send watchdog. Mutual authentication is
        # therefore complete as soon as AUTN validates and we have
        # derived CK/IK + exchanged AT_CUSTOM_IV nonces, so we
        # transition straight to AUTHENTICATED here rather than
        # blocking on an inbound EAP-Success that does not arrive at
        # this stage. The upstream session handler uses this state to
        # flip its phase to ACTIVE.
        #
        # WARNING: AUTHENTICATED here does NOT mean the session is
        # then silent until the phone speaks. It just means
        # `process_message` is done with this inbound frame. The
        # phone-side library may still emit an immediate follow-up
        # after accepting our AKA-Response, and transport/watchdog
        # timing around that edge is still the main open runtime
        # question. The expected native path in app v6.9.8 is that
        # the phone generates its own 4-byte follow-up after it
        # accepts the pod's subtype-1 response.
        self.state = EapAkaState.AUTHENTICATED

        logger.info(
            "AUTN validated, session keys derived: CK=%d, IK=%d, encryption_key=%d bytes",
            len(ck),
            len(ik),
            len(self.session_keys.encryption_key),
        )

        # Build AT_RES attribute.
        #
        # The v6.9.8 phone-side master parser special-cases AT_RES:
        # it expects the 16-bit RES bit-length in bytes 2..3 of the
        # attribute and the 8-byte RES immediately after that, with
        # length_words == 3. In other words:
        #
        #   03 03 00 40 <8-byte RES>
        #
        # not the generic [type,len,0,0,<value>] layout used by most
        # other EAP-AKA attributes. Using the generic builder here yields
        # `03 04 00 00 00 40 ...`, which the real parser rejects before
        # it ever compares the RES bytes.
        at_res = _build_at_res_attribute(xres)

        # Build response attributes: AT_RES + optional AT_CUSTOM_IV + optional AT_MAC
        response_attrs = at_res

        # Include pod's IV in response if controller sent one.
        if self.session_keys.node_iv:
            response_attrs += _build_attribute(
                AT_CUSTOM_IV, self.session_keys.node_iv,
            )

        # Include AT_MAC in response if the challenge contained one
        if AT_MAC in attrs:
            # Compute response AT_MAC
            res_aka_header = bytes([EAP_TYPE_AKA, AKA_CHALLENGE, 0, 0])
            # Placeholder MAC (16 zero bytes) for computation
            placeholder_mac = _build_attribute(AT_MAC, bytes(16))
            res_payload = res_aka_header + response_attrs + placeholder_mac
            res_total_len = (4 + len(res_payload)).to_bytes(2, "big")
            res_eap_header = bytes([EAP_RESPONSE, identifier])
            res_mac_input = res_eap_header + res_total_len + res_payload
            res_mac = hmac.new(k_aut, res_mac_input, hashlib.sha256).digest()[:16]
            response_attrs += _build_attribute(AT_MAC, res_mac)

        response = self._build_eap_response(
            identifier, AKA_CHALLENGE, response_attrs
        )
        # Cache the exact subtype-1 response bytes we emitted. The v6.9.8
        # phone-side master can still be in state=1 when our first AT_RES
        # arrives, then only transition to state=2 after its own TX
        # completion callback. Replaying the exact bytes on the subsequent
        # empty SessionEstablishmentMessage is therefore a targeted race
        # workaround, not a new protocol branch.
        self._last_challenge_response = response
        return response

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

    def build_notification(
        self,
        code: int = AT_NOTIFICATION_SUCCESS,
        identifier: int | None = None,
    ) -> bytes:
        """
        Build an EAP-Response/AKA-Notification frame carrying a single
        AT_NOTIFICATION attribute with the given 16-bit notification code.

        This helper is retained as a diagnostic probe only.

        The recovered v6.9.8 phone-side master parser does not appear to
        accept inbound subtype 12 at all; it only has subtype cases for
        1, 2, 4, and 14 while waiting for the pod's response. That means
        a pod-originated AKA-Notification is not the expected post-auth
        path for the real app.

        If callers still use this helper, treat it as a transport/debug
        experiment rather than as a protocol-faithful completion step.

        Args:
            code: 16-bit AT_NOTIFICATION value. Default is
                0x8000 = "Success" (S=1, P=0).
            identifier: EAP identifier to use. Defaults to the last
                AKA-Challenge identifier.

        Returns:
            The raw EAP-Response/AKA-Notification bytes ready to be
            emitted as the inner payload of a SessionEstablishmentMessage.
        """
        if identifier is None:
            identifier = self._last_identifier

        # AT_NOTIFICATION attribute (RFC 4187 §10.18):
        #   byte 0: type = 12 (AT_NOTIFICATION)
        #   byte 1: length in 4-byte words = 1
        #   bytes 2-3: 16-bit notification code (big-endian)
        attr = bytes(
            [AT_NOTIFICATION, 1, (code >> 8) & 0xFF, code & 0xFF]
        )
        return self._build_eap_response(identifier, AKA_NOTIFICATION, attr)

    def replay_last_challenge_response(self) -> bytes | None:
        """
        Return the exact most recent EAP-Response/AKA-Challenge bytes.

        This is used by the session layer when the phone emits its
        zero-length post-auth SessionEstablishmentMessage before the
        master FSM was ready to consume the original AT_RES.
        """
        if not self._last_challenge_response:
            return None
        return self._last_challenge_response


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


def _build_at_res_attribute(res: bytes) -> bytes:
    """
    Build the TWI-native AT_RES attribute.

    RFC 4187 describes AT_RES using a RES bit-length field plus the RES
    bytes, but the v6.9.8 phone-side parser treats it as a special case
    instead of as a generic [type,len,reserved,value] attribute. The
    accepted wire image for an 8-byte RES is:

        [type=3 | len_words=3 | res_bits(2) | RES(8)]

    with no extra reserved field and no trailing padding.
    """
    res_bits = len(res) * 8
    if res_bits != 0x40:
        raise ValueError(
            f"TWI-native AT_RES expects an 8-byte RES, got {len(res)} bytes"
        )
    return bytes([AT_RES, 0x03]) + res_bits.to_bytes(2, "big") + res


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
