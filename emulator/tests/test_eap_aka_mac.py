"""
Tests for EAP-AKA AT_MAC verification and session key derivation.
"""

from __future__ import annotations

import hashlib
import hmac
import os

from omnipod_emulator.crypto.eap_aka import (
    AT_AUTN,
    AT_MAC,
    AT_RAND,
    AT_RES,
    AKA_CHALLENGE,
    EAP_REQUEST,
    EAP_TYPE_AKA,
    EapAkaSlave,
    EapAkaState,
    _build_attribute,
    _build_at_res_attribute,
)
from omnipod_emulator.crypto.milenage import Milenage, compute_autn


def _build_challenge(
    ltk: bytes, rand: bytes, sqn: bytes, include_mac: bool = False
) -> bytes:
    """Build an EAP-Request/AKA-Challenge, optionally with AT_MAC."""
    milenage = Milenage(ltk)
    amf = b"\x00\x00"
    av = milenage.generate_auth_vector(rand, sqn, amf)
    autn = compute_autn(sqn, av.ak, amf, av.mac_a)

    at_rand = _build_attribute(AT_RAND, rand)
    at_autn = _build_attribute(AT_AUTN, autn)
    attrs = at_rand + at_autn

    if include_mac:
        # Derive K_aut per RFC 4187 Section 7
        ck = milenage.f3(rand)
        ik = milenage.f4(rand)
        identity = b""
        mk = hashlib.sha1(identity + ik + ck).digest()  # noqa: S324
        k_aut = mk[16:32]

        # Build message with placeholder MAC
        placeholder_mac = _build_attribute(AT_MAC, bytes(16))
        full_attrs = attrs + placeholder_mac

        aka_header = bytes([EAP_TYPE_AKA, AKA_CHALLENGE, 0, 0])
        full_payload = aka_header + full_attrs
        total_len = (4 + len(full_payload)).to_bytes(2, "big")
        identifier = 1
        eap_header = bytes([EAP_REQUEST, identifier])
        mac_input = eap_header + total_len + full_payload
        mac_value = hmac.new(k_aut, mac_input, hashlib.sha256).digest()[:16]

        # Replace placeholder with real MAC
        real_mac = _build_attribute(AT_MAC, mac_value)
        attrs = attrs + real_mac

    aka_payload = bytes([EAP_TYPE_AKA, AKA_CHALLENGE, 0, 0]) + attrs
    identifier = 1
    total_length = 4 + len(aka_payload)
    eap_msg = (
        bytes([EAP_REQUEST, identifier])
        + total_length.to_bytes(2, "big")
        + aka_payload
    )
    return eap_msg


class TestSessionKeyDerivation:
    """Test that encryption_key = CK directly."""

    def test_encryption_key_equals_ck(self):
        ltk = os.urandom(16)
        slave = EapAkaSlave(ltk=ltk)

        rand = os.urandom(16)
        sqn = b"\x00" * 6
        challenge = _build_challenge(ltk, rand, sqn)
        slave.process_message(challenge)

        milenage = Milenage(ltk)
        expected_ck = milenage.f3(rand)
        assert slave.session_keys.encryption_key == expected_ck

    def test_ik_stored_separately(self):
        ltk = os.urandom(16)
        slave = EapAkaSlave(ltk=ltk)

        rand = os.urandom(16)
        sqn = b"\x00" * 6
        challenge = _build_challenge(ltk, rand, sqn)
        slave.process_message(challenge)

        milenage = Milenage(ltk)
        expected_ik = milenage.f4(rand)
        assert slave.session_keys.ik == expected_ik


class TestAtMacVerification:
    """Test AT_MAC handling in EAP-AKA challenge."""

    def test_challenge_without_at_mac_succeeds(self):
        ltk = os.urandom(16)
        slave = EapAkaSlave(ltk=ltk)

        rand = os.urandom(16)
        sqn = b"\x00" * 6
        challenge = _build_challenge(ltk, rand, sqn, include_mac=False)
        response = slave.process_message(challenge)

        assert response is not None
        # After 2026-04-13 fix, a successful AKA-Challenge transitions
        # the slave directly to AUTHENTICATED instead of an
        # intermediate CHALLENGE_RECEIVED state — the Omnipod 5 TWI
        # profile never delivers an inbound EAP-Success, so we treat
        # mutual auth as complete once AUTN validates and session
        # keys are derived. See eap_aka.py::_handle_challenge for
        # the commentary.
        assert slave.state == EapAkaState.AUTHENTICATED

    def test_challenge_with_valid_at_mac_succeeds(self):
        ltk = os.urandom(16)
        slave = EapAkaSlave(ltk=ltk)

        rand = os.urandom(16)
        sqn = b"\x00" * 6
        challenge = _build_challenge(ltk, rand, sqn, include_mac=True)
        response = slave.process_message(challenge)

        assert response is not None
        assert slave.state == EapAkaState.AUTHENTICATED

    def test_challenge_with_invalid_at_mac_rejected(self):
        ltk = os.urandom(16)
        slave = EapAkaSlave(ltk=ltk)

        rand = os.urandom(16)
        sqn = b"\x00" * 6
        # Build with valid MAC, then corrupt it
        challenge = _build_challenge(ltk, rand, sqn, include_mac=True)

        # Find AT_MAC in the EAP message and corrupt the value
        # AT_MAC type = 11, appears in attributes after the AKA header
        corrupted = bytearray(challenge)
        # Walk through to find and corrupt AT_MAC value
        # EAP header(4) + AKA header(4) = 8 bytes, then attributes
        offset = 8
        while offset + 2 <= len(corrupted):
            attr_type = corrupted[offset]
            attr_len_words = corrupted[offset + 1]
            attr_len_bytes = attr_len_words * 4
            if attr_type == AT_MAC:
                # Corrupt the MAC value (offset + 4 to skip header)
                corrupted[offset + 4] ^= 0xFF
                break
            offset += attr_len_bytes

        response = slave.process_message(bytes(corrupted))
        assert slave.state == EapAkaState.FAILED

    def test_successful_challenge_is_cached_for_exact_replay(self):
        ltk = os.urandom(16)
        slave = EapAkaSlave(ltk=ltk)

        rand = os.urandom(16)
        sqn = b"\x00" * 6
        challenge = _build_challenge(ltk, rand, sqn, include_mac=False)

        response = slave.process_message(challenge)

        assert response is not None
        assert slave.replay_last_challenge_response() == response


class TestAtResEncoding:
    """Test the special TWI-native AT_RES wire format."""

    def test_build_at_res_attribute_uses_native_12_byte_shape(self):
        res = bytes.fromhex("cfa2fdec7ccfaf40")

        attr = _build_at_res_attribute(res)

        assert attr == bytes.fromhex("03030040cfa2fdec7ccfaf40")

    def test_successful_response_contains_native_at_res_shape(self):
        ltk = os.urandom(16)
        slave = EapAkaSlave(ltk=ltk)

        rand = os.urandom(16)
        sqn = b"\x00" * 6
        challenge = _build_challenge(ltk, rand, sqn, include_mac=False)

        response = slave.process_message(challenge)

        assert response is not None
        assert response[0] == 0x02  # EAP-Response
        assert response[4] == EAP_TYPE_AKA
        assert response[5] == AKA_CHALLENGE
        assert response[8] == AT_RES
        assert response[9] == 0x03
