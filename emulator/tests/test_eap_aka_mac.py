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


class TestDynamicLtkOverride:
    """
    The dynamic LTK barrier in ``_handle_challenge`` should swap a
    pushed override into place before MILENAGE runs, so a slave that
    was constructed with the wrong LTK can still authenticate.
    """

    def setup_method(self) -> None:
        from omnipod_emulator import debug_ltk_store

        debug_ltk_store.clear()

    def teardown_method(self) -> None:
        from omnipod_emulator import debug_ltk_store

        debug_ltk_store.clear()

    def test_override_swaps_ltk_before_validation(self) -> None:
        from omnipod_emulator import debug_ltk_store

        wrong_ltk = b"\x00" * 16
        correct_ltk = os.urandom(16)
        session_id = b"\xA1" * 16

        slave = EapAkaSlave(
            ltk=wrong_ltk,
            session_id=session_id,
            ltk_override_timeout_s=0.5,
        )

        debug_ltk_store.set_ltk(session_id, correct_ltk)

        rand = os.urandom(16)
        sqn = b"\x00" * 6
        challenge = _build_challenge(correct_ltk, rand, sqn, include_mac=False)

        response = slave.process_message(challenge)

        assert response is not None
        assert slave.state == EapAkaState.AUTHENTICATED
        assert slave.ltk == correct_ltk

    def test_missing_override_times_out_and_fails(self) -> None:
        correct_ltk = os.urandom(16)
        wrong_ltk = b"\xff" * 16
        session_id = b"\xB2" * 16

        slave = EapAkaSlave(
            ltk=wrong_ltk,
            session_id=session_id,
            ltk_override_timeout_s=0.05,
        )

        rand = os.urandom(16)
        sqn = b"\x00" * 6
        challenge = _build_challenge(correct_ltk, rand, sqn, include_mac=False)

        response = slave.process_message(challenge)

        # The slave does not block forever — it times out and attempts
        # validation with its own (wrong) LTK, which MILENAGE rejects.
        assert response is not None
        assert slave.state == EapAkaState.FAILED
        assert slave.ltk == wrong_ltk

    def test_override_is_consumed_only_once(self) -> None:
        from omnipod_emulator import debug_ltk_store

        wrong_ltk = b"\x00" * 16
        correct_ltk = os.urandom(16)
        session_id = b"\xC3" * 16

        slave = EapAkaSlave(
            ltk=wrong_ltk,
            session_id=session_id,
            ltk_override_timeout_s=0.5,
        )
        debug_ltk_store.set_ltk(session_id, correct_ltk)

        rand = os.urandom(16)
        sqn = b"\x00" * 6
        challenge = _build_challenge(correct_ltk, rand, sqn, include_mac=False)
        slave.process_message(challenge)
        assert slave.state == EapAkaState.AUTHENTICATED

        # A later "poisoned" override must NOT clobber the live session.
        debug_ltk_store.set_ltk(session_id, b"\xee" * 16)
        # Feeding a second challenge with the same (still correct) LTK
        # should not re-enter the barrier — the slave's ltk stays as it
        # was after the first apply.
        challenge2 = _build_challenge(correct_ltk, os.urandom(16), sqn, include_mac=False)
        slave.process_message(challenge2)
        assert slave.ltk == correct_ltk

    def test_no_session_id_skips_barrier(self) -> None:
        # Reconnect and test-only paths pass session_id=None. The
        # slave must not touch the store in that case.
        ltk = os.urandom(16)
        slave = EapAkaSlave(ltk=ltk, session_id=None)

        rand = os.urandom(16)
        sqn = b"\x00" * 6
        challenge = _build_challenge(ltk, rand, sqn, include_mac=False)
        response = slave.process_message(challenge)

        assert response is not None
        assert slave.state == EapAkaState.AUTHENTICATED

    def test_override_arriving_after_first_timeout_is_ignored(self) -> None:
        """
        One-shot latch: if the barrier times out on the first
        challenge, a later push + a later second challenge must NOT
        cause the slave to swap in the (now available) override.
        Rebuilding MILENAGE mid-session against a fresh LTK would
        break session keys already derived from the (wrong) one.
        """
        from omnipod_emulator import debug_ltk_store

        wrong_ltk = b"\x00" * 16
        real_ltk = os.urandom(16)
        session_id = b"\xD4" * 16

        slave = EapAkaSlave(
            ltk=wrong_ltk,
            session_id=session_id,
            ltk_override_timeout_s=0.05,  # barrier will time out fast
        )

        # First challenge: barrier misses, slave fails AUTN against
        # the wrong LTK, latch flips.
        rand_a = os.urandom(16)
        sqn = b"\x00" * 6
        challenge_a = _build_challenge(real_ltk, rand_a, sqn, include_mac=False)
        slave.process_message(challenge_a)
        assert slave.state == EapAkaState.FAILED
        assert slave._ltk_override_applied is True

        # Now a late push arrives — exactly the scenario the latch
        # defends against. The slave must not pick it up.
        debug_ltk_store.set_ltk(session_id, real_ltk)

        # Reset the slave's state to simulate a retry loop and feed
        # another challenge. The latch is still set, so the barrier
        # does NOT re-read the store and the slave keeps its broken
        # LTK.
        slave.state = EapAkaState.IDLE  # direct reset; real code never does this
        rand_b = os.urandom(16)
        challenge_b = _build_challenge(real_ltk, rand_b, sqn, include_mac=False)
        slave.process_message(challenge_b)

        assert slave.ltk == wrong_ltk, "latch should have blocked a second read"
        assert slave.state == EapAkaState.FAILED
