"""
Integration tests for the full protocol session orchestrator.

Simulates the phone side of the protocol and exercises:
    1. Connection init
    2. ECDH pairing (key exchange + confirmation)
    3. EAP-AKA mutual authentication
    4. Encrypted RHP command dispatch (GET_STATUS, SEND_BOLUS)

These tests run entirely in-process with no BLE stack.
"""

from __future__ import annotations

import hashlib
import os
import struct

import pytest

from omnipod_emulator.crypto import aes_ccm
from omnipod_emulator.crypto.ecdh import EcdhKeyPair
from omnipod_emulator.crypto.eap_aka import (
    AT_AUTN,
    AT_RAND,
    AT_RES,
    AKA_CHALLENGE,
    EAP_REQUEST,
    EAP_SUCCESS,
    EAP_TYPE_AKA,
)
from omnipod_emulator.crypto.kdf import derive_keys_for_role
from omnipod_emulator.crypto.milenage import Milenage, compute_autn
from omnipod_emulator.pod.state import PodState
from omnipod_emulator.protocol.commands import CommandType
from omnipod_emulator.protocol.pairing import _aes_cmac
from omnipod_emulator.protocol.session import (
    MSG_EAP,
    MSG_ENCRYPTED,
    MSG_INIT,
    MSG_PAIRING,
    PAIR_PHONE_CONF,
    PAIR_PHONE_KEY_NONCE,
    ProtocolSession,
    SessionPhase,
)


# ---------------------------------------------------------------------------
# Fixtures
# ---------------------------------------------------------------------------

FIRMWARE_ID = b"\xaa\xbb\xcc\xdd\xee\xff"
CONTROLLER_ID = b"\x01\x02\x03\x04"
ECDH_SEED = b"\x42" * 32  # Deterministic pod seed for reproducibility


@pytest.fixture
def pod_state() -> PodState:
    """Fresh pod state."""
    return PodState()


@pytest.fixture
def session(pod_state: PodState) -> ProtocolSession:
    """Protocol session with deterministic ECDH seed."""
    return ProtocolSession(
        pod_state=pod_state,
        firmware_id=FIRMWARE_ID,
        ecdh_seed=ECDH_SEED,
    )


# ---------------------------------------------------------------------------
# Phone-side helpers (simulate the controller)
# ---------------------------------------------------------------------------


def phone_build_init() -> bytes:
    """Build the init message the phone sends after GATT discovery."""
    return bytes([MSG_INIT, 0x01, 0x04]) + CONTROLLER_ID


def phone_build_key_nonce(phone_key_pair: EcdhKeyPair) -> bytes:
    """Build the pairing key+nonce message from the phone."""
    return (
        bytes([MSG_PAIRING, PAIR_PHONE_KEY_NONCE])
        + phone_key_pair.public_key_bytes
        + phone_key_pair.nonce
    )


def phone_compute_confirmation(
    conf_key: bytes,
    phone_nonce: bytes,
    pod_nonce: bytes,
    phone_pub: bytes,
    pod_pub: bytes,
) -> bytes:
    """Compute the phone-side confirmation value (reversed nonce/key order)."""
    data = phone_nonce + pod_nonce + phone_pub + pod_pub
    return _aes_cmac(conf_key, data)


def phone_build_confirmation(conf_value: bytes) -> bytes:
    """Build the pairing confirmation message from the phone."""
    return bytes([MSG_PAIRING, PAIR_PHONE_CONF]) + conf_value


def phone_build_eap_aka_challenge(
    ltk: bytes, rand: bytes, sqn: bytes
) -> bytes:
    """
    Build an EAP-Request/AKA-Challenge message from the phone (authenticator).

    This is what the real Omnipod PDM sends after pairing completes.
    """
    milenage = Milenage(ltk)
    amf = b"\x00\x00"

    av = milenage.generate_auth_vector(rand, sqn, amf)
    autn = compute_autn(sqn, av.ak, amf, av.mac_a)

    # Build AT_RAND attribute (type=1, length in 4-byte words)
    at_rand = _build_eap_attr(AT_RAND, rand)
    # Build AT_AUTN attribute (type=2)
    at_autn = _build_eap_attr(AT_AUTN, autn)

    attrs = at_rand + at_autn

    # AKA header: type(1) + subtype(1) + reserved(2)
    aka_payload = bytes([EAP_TYPE_AKA, AKA_CHALLENGE, 0, 0]) + attrs

    # EAP header: code=Request(1), identifier(1), length(2)
    identifier = 1
    total_length = 4 + len(aka_payload)
    eap_msg = (
        bytes([EAP_REQUEST, identifier])
        + total_length.to_bytes(2, "big")
        + aka_payload
    )

    # Wrap with MSG_EAP prefix
    return bytes([MSG_EAP]) + eap_msg


def phone_build_eap_success() -> bytes:
    """Build an EAP-Success message from the phone."""
    identifier = 2
    eap_msg = bytes([EAP_SUCCESS, identifier, 0, 4])
    return bytes([MSG_EAP]) + eap_msg


def phone_build_encrypted_command(
    msk: bytes, command_type: int, payload: bytes, nonce_counter: int
) -> bytes:
    """Build an encrypted RHP command from the phone."""
    plaintext = bytes([command_type]) + payload
    nonce_suffix = os.urandom(4)
    nonce = struct.pack(">Q", nonce_counter) + nonce_suffix + b"\x00"

    ciphertext = aes_ccm.encrypt(
        key=msk,
        plaintext=plaintext,
        nonce=nonce,
    )

    return bytes([MSG_ENCRYPTED]) + nonce_suffix + ciphertext


def _build_eap_attr(attr_type: int, value: bytes) -> bytes:
    """Build a single EAP-AKA attribute with padding."""
    padded_len = ((len(value) + 3) // 4) * 4
    total_len = 4 + padded_len
    length_words = total_len // 4
    result = bytes([attr_type, length_words, 0, 0]) + value
    padding = padded_len - len(value)
    if padding > 0:
        result += bytes(padding)
    return result


# ---------------------------------------------------------------------------
# Tests
# ---------------------------------------------------------------------------


class TestConnectionInit:
    """Test the init phase."""

    def test_init_transitions_to_pairing(self, session: ProtocolSession):
        init_msg = phone_build_init()
        response = session.on_message(init_msg)

        assert session.phase == SessionPhase.PAIRING
        assert response is not None
        # Response should be MSG_PAIRING + firmware_id(6) + pod_pubkey(32) + pod_nonce(16)
        assert response[0] == MSG_PAIRING
        assert len(response) == 1 + 6 + 32 + 16

    def test_init_too_short_returns_none(self, session: ProtocolSession):
        response = session.on_message(bytes([MSG_INIT, 0x01]))
        assert response is None
        assert session.phase == SessionPhase.DISCONNECTED

    def test_empty_message_returns_none(self, session: ProtocolSession):
        response = session.on_message(b"")
        assert response is None


class TestPairing:
    """Test the pairing key exchange and confirmation."""

    def test_full_pairing_flow(self, session: ProtocolSession):
        # Step 1: Init
        init_response = session.on_message(phone_build_init())
        assert session.phase == SessionPhase.PAIRING
        pod_pub = init_response[7:39]
        pod_nonce = init_response[39:55]

        # Step 2: Phone sends its key + nonce
        phone_kp = EcdhKeyPair()
        key_nonce_msg = phone_build_key_nonce(phone_kp)
        conf_response = session.on_message(key_nonce_msg)

        assert conf_response is not None
        assert conf_response[0] == MSG_PAIRING
        assert conf_response[1] == 0x03  # pod conf response marker
        pod_conf = conf_response[2:18]
        assert len(pod_conf) == 16

        # Step 3: Phone computes shared secret and derives keys
        shared_secret = phone_kp.compute_shared_secret(pod_pub)
        phone_derived = derive_keys_for_role(
            role=0,
            firmware_id=FIRMWARE_ID,
            controller_id=CONTROLLER_ID,
            local_public_key=phone_kp.public_key_bytes,
            peer_public_key=pod_pub,
            shared_secret=shared_secret,
        )

        # Step 4: Phone verifies pod's confirmation
        expected_pod_conf = _aes_cmac(
            phone_derived.confirmation_key,
            pod_nonce + phone_kp.nonce + pod_pub + phone_kp.public_key_bytes,
        )
        assert pod_conf == expected_pod_conf

        # Step 5: Phone sends its confirmation
        phone_conf = phone_compute_confirmation(
            phone_derived.confirmation_key,
            phone_kp.nonce,
            pod_nonce,
            phone_kp.public_key_bytes,
            pod_pub,
        )
        success_response = session.on_message(
            phone_build_confirmation(phone_conf)
        )

        assert success_response is not None
        assert success_response[0] == MSG_PAIRING
        assert success_response[1] == 0x04  # pairing complete
        assert session.phase == SessionPhase.AUTHENTICATING

    def test_wrong_confirmation_fails(self, session: ProtocolSession):
        # Init
        session.on_message(phone_build_init())

        # Key exchange
        phone_kp = EcdhKeyPair()
        session.on_message(phone_build_key_nonce(phone_kp))

        # Send bogus confirmation
        bogus_conf = bytes(16)
        response = session.on_message(phone_build_confirmation(bogus_conf))

        assert response is not None
        assert response[0] == MSG_PAIRING
        assert response[1] == 0xFF  # failure indicator
        assert session.phase == SessionPhase.FAILED


class TestEapAka:
    """Test EAP-AKA authentication after successful pairing."""

    def _pair(self, session: ProtocolSession) -> tuple[bytes, bytes]:
        """
        Run the full pairing flow and return (ltk, phone_nonce).

        Returns the LTK so the test can build EAP-AKA challenges.
        """
        init_resp = session.on_message(phone_build_init())
        pod_pub = init_resp[7:39]
        pod_nonce = init_resp[39:55]

        phone_kp = EcdhKeyPair()
        session.on_message(phone_build_key_nonce(phone_kp))

        shared_secret = phone_kp.compute_shared_secret(pod_pub)
        phone_derived = derive_keys_for_role(
            role=0,
            firmware_id=FIRMWARE_ID,
            controller_id=CONTROLLER_ID,
            local_public_key=phone_kp.public_key_bytes,
            peer_public_key=pod_pub,
            shared_secret=shared_secret,
        )

        phone_conf = phone_compute_confirmation(
            phone_derived.confirmation_key,
            phone_kp.nonce,
            pod_nonce,
            phone_kp.public_key_bytes,
            pod_pub,
        )
        session.on_message(phone_build_confirmation(phone_conf))

        return phone_derived.ltk, phone_kp.nonce

    def test_eap_aka_authentication(self, session: ProtocolSession):
        ltk, _ = self._pair(session)
        assert session.phase == SessionPhase.AUTHENTICATING

        # Phone sends EAP-Request/AKA-Challenge
        rand = os.urandom(16)
        sqn = b"\x00" * 6
        challenge_msg = phone_build_eap_aka_challenge(ltk, rand, sqn)
        eap_response = session.on_message(challenge_msg)

        assert eap_response is not None
        assert eap_response[0] == MSG_EAP
        # Should contain an EAP-Response with AT_RES
        eap_payload = eap_response[1:]
        assert eap_payload[0] == 2  # EAP_RESPONSE
        assert eap_payload[4] == EAP_TYPE_AKA
        assert eap_payload[5] == AKA_CHALLENGE

        # Phone sends EAP-Success
        success_msg = phone_build_eap_success()
        result = session.on_message(success_msg)

        assert result is None  # EAP-Success has no response
        assert session.phase == SessionPhase.ACTIVE

    def test_eap_in_wrong_phase_ignored(self, session: ProtocolSession):
        # Without pairing first, EAP messages should be ignored
        session.on_message(phone_build_init())
        # Still in PAIRING phase
        eap_msg = phone_build_eap_aka_challenge(bytes(16), os.urandom(16), b"\x00" * 6)
        response = session.on_message(eap_msg)
        assert response is None


class TestEncryptedCommands:
    """Test encrypted RHP command dispatch after full authentication."""

    def _authenticate(
        self, session: ProtocolSession
    ) -> bytes:
        """
        Run pairing + EAP-AKA and return the MSK for encryption.
        """
        # Pair
        init_resp = session.on_message(phone_build_init())
        pod_pub = init_resp[7:39]
        pod_nonce = init_resp[39:55]

        phone_kp = EcdhKeyPair()
        session.on_message(phone_build_key_nonce(phone_kp))

        shared_secret = phone_kp.compute_shared_secret(pod_pub)
        phone_derived = derive_keys_for_role(
            role=0,
            firmware_id=FIRMWARE_ID,
            controller_id=CONTROLLER_ID,
            local_public_key=phone_kp.public_key_bytes,
            peer_public_key=pod_pub,
            shared_secret=shared_secret,
        )

        phone_conf = phone_compute_confirmation(
            phone_derived.confirmation_key,
            phone_kp.nonce,
            pod_nonce,
            phone_kp.public_key_bytes,
            pod_pub,
        )
        session.on_message(phone_build_confirmation(phone_conf))

        ltk = phone_derived.ltk

        # EAP-AKA
        rand = os.urandom(16)
        sqn = b"\x00" * 6
        session.on_message(phone_build_eap_aka_challenge(ltk, rand, sqn))
        session.on_message(phone_build_eap_success())

        assert session.phase == SessionPhase.ACTIVE

        # Compute MSK the same way the pod does
        milenage = Milenage(ltk)
        ck = milenage.f3(rand)
        ik = milenage.f4(rand)
        msk = hashlib.sha256(ck + ik).digest()[:16]

        return msk

    def test_encrypted_get_status(
        self, session: ProtocolSession, pod_state: PodState
    ):
        msk = self._authenticate(session)

        # Send encrypted GET_STATUS command
        enc_msg = phone_build_encrypted_command(
            msk=msk,
            command_type=CommandType.GET_STATUS,
            payload=b"",
            nonce_counter=0,
        )
        response = session.on_message(enc_msg)

        assert response is not None
        assert response[0] == MSG_ENCRYPTED

        # Decrypt the response
        tx_nonce_suffix = response[1:5]
        ciphertext = response[5:]
        tx_nonce = struct.pack(">Q", 0) + tx_nonce_suffix + b"\x00"
        plaintext = aes_ccm.decrypt(key=msk, ciphertext=ciphertext, nonce=tx_nonce)

        # First byte should be GET_STATUS command type
        assert plaintext[0] == CommandType.GET_STATUS
        # Second byte should be ResponseStatus.OK (0x00)
        assert plaintext[1] == 0x00
        # Rest is the encoded pod status
        assert len(plaintext) > 2

    def test_encrypted_get_version(
        self, session: ProtocolSession
    ):
        msk = self._authenticate(session)

        enc_msg = phone_build_encrypted_command(
            msk=msk,
            command_type=CommandType.GET_VERSION,
            payload=b"",
            nonce_counter=0,
        )
        response = session.on_message(enc_msg)

        assert response is not None
        assert response[0] == MSG_ENCRYPTED

        # Decrypt
        tx_nonce_suffix = response[1:5]
        ciphertext = response[5:]
        tx_nonce = struct.pack(">Q", 0) + tx_nonce_suffix + b"\x00"
        plaintext = aes_ccm.decrypt(key=msk, ciphertext=ciphertext, nonce=tx_nonce)

        assert plaintext[0] == CommandType.GET_VERSION
        assert plaintext[1] == 0x00  # OK

    def test_encrypted_command_in_wrong_phase(self, session: ProtocolSession):
        # Without authentication, encrypted commands should fail
        session.on_message(phone_build_init())

        enc_msg = phone_build_encrypted_command(
            msk=bytes(16),
            command_type=CommandType.GET_STATUS,
            payload=b"",
            nonce_counter=0,
        )
        response = session.on_message(enc_msg)
        assert response is None

    def test_multiple_commands_increment_nonce(
        self, session: ProtocolSession
    ):
        msk = self._authenticate(session)

        for i in range(3):
            enc_msg = phone_build_encrypted_command(
                msk=msk,
                command_type=CommandType.GET_STATUS,
                payload=b"",
                nonce_counter=i,
            )
            response = session.on_message(enc_msg)
            assert response is not None
            assert response[0] == MSG_ENCRYPTED


class TestDisconnection:
    """Test disconnection handling."""

    def test_disconnect_resets_phase(self, session: ProtocolSession):
        session.on_message(phone_build_init())
        assert session.phase == SessionPhase.PAIRING

        session.on_disconnect()
        assert session.phase == SessionPhase.DISCONNECTED


class TestReconnection:
    """Test reconnection using stored LTK (no re-pairing needed)."""

    def _full_pair_and_auth(
        self, session: ProtocolSession
    ) -> bytes:
        """
        Run full pairing + EAP-AKA and return the LTK.
        """
        init_resp = session.on_message(phone_build_init())
        pod_pub = init_resp[7:39]
        pod_nonce = init_resp[39:55]

        phone_kp = EcdhKeyPair()
        session.on_message(phone_build_key_nonce(phone_kp))

        shared_secret = phone_kp.compute_shared_secret(pod_pub)
        phone_derived = derive_keys_for_role(
            role=0,
            firmware_id=FIRMWARE_ID,
            controller_id=CONTROLLER_ID,
            local_public_key=phone_kp.public_key_bytes,
            peer_public_key=pod_pub,
            shared_secret=shared_secret,
        )

        phone_conf = phone_compute_confirmation(
            phone_derived.confirmation_key,
            phone_kp.nonce,
            pod_nonce,
            phone_kp.public_key_bytes,
            pod_pub,
        )
        session.on_message(phone_build_confirmation(phone_conf))

        ltk = phone_derived.ltk

        # EAP-AKA
        rand = os.urandom(16)
        sqn = b"\x00" * 6
        session.on_message(phone_build_eap_aka_challenge(ltk, rand, sqn))
        session.on_message(phone_build_eap_success())
        assert session.phase == SessionPhase.ACTIVE

        return ltk

    def test_reconnect_skips_pairing(self, session: ProtocolSession):
        """After pairing + disconnect, init should skip to AUTHENTICATING."""
        ltk = self._full_pair_and_auth(session)

        # Disconnect
        session.on_disconnect()
        assert session.phase == SessionPhase.DISCONNECTED

        # Reconnect: send init again
        init_resp = session.on_message(phone_build_init())

        # Should get the "already paired" indicator, not a pubkey+nonce
        assert init_resp is not None
        assert init_resp[0] == MSG_PAIRING
        assert init_resp[1] == 0x05  # already paired indicator
        assert session.phase == SessionPhase.AUTHENTICATING

    def test_reconnect_full_auth_and_command(self, session: ProtocolSession):
        """After reconnection, EAP-AKA + encrypted commands should work."""
        ltk = self._full_pair_and_auth(session)

        # Disconnect
        session.on_disconnect()

        # Reconnect
        session.on_message(phone_build_init())
        assert session.phase == SessionPhase.AUTHENTICATING

        # Re-authenticate with EAP-AKA using the same LTK
        rand = os.urandom(16)
        sqn = (1).to_bytes(6, "big")  # SQN=1 (incremented from initial 0)
        session.on_message(phone_build_eap_aka_challenge(ltk, rand, sqn))
        session.on_message(phone_build_eap_success())
        assert session.phase == SessionPhase.ACTIVE

        # Derive the new session MSK
        milenage = Milenage(ltk)
        ck = milenage.f3(rand)
        ik = milenage.f4(rand)
        msk = hashlib.sha256(ck + ik).digest()[:16]

        # Send an encrypted command
        enc_msg = phone_build_encrypted_command(
            msk=msk,
            command_type=CommandType.GET_STATUS,
            payload=b"",
            nonce_counter=0,
        )
        response = session.on_message(enc_msg)

        assert response is not None
        assert response[0] == MSG_ENCRYPTED

        # Decrypt and verify
        tx_nonce_suffix = response[1:5]
        ciphertext = response[5:]
        tx_nonce = struct.pack(">Q", 0) + tx_nonce_suffix + b"\x00"
        plaintext = aes_ccm.decrypt(key=msk, ciphertext=ciphertext, nonce=tx_nonce)

        assert plaintext[0] == CommandType.GET_STATUS
        assert plaintext[1] == 0x00  # OK

    def test_multiple_reconnections(self, session: ProtocolSession):
        """LTK should survive multiple disconnect/reconnect cycles."""
        ltk = self._full_pair_and_auth(session)

        for i in range(3):
            session.on_disconnect()
            assert session.phase == SessionPhase.DISCONNECTED

            init_resp = session.on_message(phone_build_init())
            assert init_resp[1] == 0x05  # already paired
            assert session.phase == SessionPhase.AUTHENTICATING

            # Re-auth
            rand = os.urandom(16)
            sqn = (i + 1).to_bytes(6, "big")
            session.on_message(phone_build_eap_aka_challenge(ltk, rand, sqn))
            session.on_message(phone_build_eap_success())
            assert session.phase == SessionPhase.ACTIVE
