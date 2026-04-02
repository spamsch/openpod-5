"""
Integration tests for the full protocol session orchestrator.

Simulates the phone side of the protocol and exercises:
    1. Connection init
    2. ECDH pairing (key exchange + confirmation)
    3. EAP-AKA mutual authentication
    4. Encrypted TWICommand + text RHP dispatch (GV, G11.3, S200.0=...)

These tests run entirely in-process with no BLE stack.
"""

from __future__ import annotations

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
from omnipod_emulator.protocol.twi_command import MessageType, TWICommand


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


def phone_build_encrypted_rhp(
    msk: bytes,
    rhp_text: str,
    nonce_counter: int,
    command_id: int = 1,
) -> bytes:
    """
    Build an encrypted TWICommand carrying text RHP from the phone.

    Protocol stack:
        1. Build RHP text
        2. Wrap in TWICommand frame
        3. Encrypt with AES-CCM
        4. Prepend MSG_ENCRYPTED + nonce_suffix
    """
    twi = TWICommand(
        command_bytes=rhp_text,
        command_id=command_id,
        last_message=True,
        message_type=MessageType.ENCRYPTED,
        notification_number=0,
    )
    plaintext = twi.serialize()

    nonce_suffix = os.urandom(4)
    nonce = struct.pack(">Q", nonce_counter) + nonce_suffix + b"\x00"

    ciphertext = aes_ccm.encrypt(
        key=msk,
        plaintext=plaintext,
        nonce=nonce,
    )

    return bytes([MSG_ENCRYPTED]) + nonce_suffix + ciphertext


def phone_decrypt_response(
    msk: bytes,
    response: bytes,
    nonce_counter: int,
) -> TWICommand:
    """
    Decrypt a pod response and parse the TWICommand frame.

    Returns the parsed TWICommand with RHP text in command_bytes.
    """
    assert response[0] == MSG_ENCRYPTED
    tx_nonce_suffix = response[1:5]
    ciphertext = response[5:]
    tx_nonce = struct.pack(">Q", nonce_counter) + tx_nonce_suffix + b"\x00"
    plaintext = aes_ccm.decrypt(key=msk, ciphertext=ciphertext, nonce=tx_nonce)
    return TWICommand.parse(plaintext)


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
    """Test encrypted TWICommand + text RHP dispatch after full authentication."""

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

        # Encryption key = CK directly (per TWI SDK behaviour)
        milenage = Milenage(ltk)
        ck = milenage.f3(rand)

        return ck

    def test_encrypted_get_status(
        self, session: ProtocolSession, pod_state: PodState
    ):
        msk = self._authenticate(session)

        # Send encrypted text RHP "G11.3" (AID pod status) wrapped in TWICommand
        enc_msg = phone_build_encrypted_rhp(
            msk=msk,
            rhp_text="G11.3",
            nonce_counter=0,
            command_id=1,
        )
        response = session.on_message(enc_msg)

        assert response is not None
        assert response[0] == MSG_ENCRYPTED

        # Decrypt and parse TWICommand
        twi_resp = phone_decrypt_response(msk, response, nonce_counter=0)

        # Response should be an attribute response: "11.3=<hex_payload>"
        assert twi_resp.command_bytes.startswith("11.3=")
        assert twi_resp.command_id == 1  # matches request command_id
        assert twi_resp.last_message is True

    def test_encrypted_get_version(
        self, session: ProtocolSession
    ):
        msk = self._authenticate(session)

        # GV = get version
        enc_msg = phone_build_encrypted_rhp(
            msk=msk,
            rhp_text="GV",
            nonce_counter=0,
            command_id=2,
        )
        response = session.on_message(enc_msg)

        assert response is not None
        assert response[0] == MSG_ENCRYPTED

        twi_resp = phone_decrypt_response(msk, response, nonce_counter=0)

        # Version response: V<version>
        assert twi_resp.command_bytes.startswith("V")
        assert "EMUL-" in twi_resp.command_bytes

    def test_encrypted_command_in_wrong_phase(self, session: ProtocolSession):
        # Without authentication, encrypted commands should fail
        session.on_message(phone_build_init())

        enc_msg = phone_build_encrypted_rhp(
            msk=bytes(16),
            rhp_text="GV",
            nonce_counter=0,
        )
        response = session.on_message(enc_msg)
        assert response is None

    def test_multiple_commands_increment_nonce(
        self, session: ProtocolSession
    ):
        msk = self._authenticate(session)

        for i in range(3):
            enc_msg = phone_build_encrypted_rhp(
                msk=msk,
                rhp_text="G11.3",
                nonce_counter=i,
                command_id=i + 1,
            )
            response = session.on_message(enc_msg)
            assert response is not None
            assert response[0] == MSG_ENCRYPTED

    def test_set_command_returns_success(
        self, session: ProtocolSession
    ):
        """Test a SET command returns ES (success) response."""
        msk = self._authenticate(session)

        # S255.2=1711929600 (set UTC time)
        enc_msg = phone_build_encrypted_rhp(
            msk=msk,
            rhp_text="S255.2=1711929600",
            nonce_counter=0,
            command_id=10,
        )
        response = session.on_message(enc_msg)

        assert response is not None
        twi_resp = phone_decrypt_response(msk, response, nonce_counter=0)

        # Success response: ES255.2=0
        assert twi_resp.command_bytes == "ES255.2=0"

    def test_batched_commands(
        self, session: ProtocolSession
    ):
        """Test comma-separated batch of RHP commands."""
        msk = self._authenticate(session)

        # Batch: get version + get warnings
        enc_msg = phone_build_encrypted_rhp(
            msk=msk,
            rhp_text="GV,G3.8",
            nonce_counter=0,
            command_id=20,
        )
        response = session.on_message(enc_msg)

        assert response is not None
        twi_resp = phone_decrypt_response(msk, response, nonce_counter=0)

        # Response should be comma-separated: VEMUL-...,3.8=0
        parts = twi_resp.command_bytes.split(",")
        assert len(parts) == 2
        assert parts[0].startswith("VEMUL-")
        assert parts[1].startswith("3.8=")

    def test_error_response_for_unknown_command(
        self, session: ProtocolSession
    ):
        """Test that unregistered type/attr returns an error response."""
        msk = self._authenticate(session)

        enc_msg = phone_build_encrypted_rhp(
            msk=msk,
            rhp_text="G99.99",
            nonce_counter=0,
            command_id=30,
        )
        response = session.on_message(enc_msg)

        assert response is not None
        twi_resp = phone_decrypt_response(msk, response, nonce_counter=0)

        # Error response: EG99.99=<code>
        assert twi_resp.command_bytes.startswith("EG99.99=")

    def test_bolus_via_text_rhp(
        self, session: ProtocolSession, pod_state: PodState
    ):
        """Test bolus command via text RHP: S200.0=<pulses>."""
        msk = self._authenticate(session)

        # First activate the pod through the activation sequence
        activation_cmds = [
            "GV",                       # get version
            "S1.0=deadbeef",            # set unique id
            "S1.1=alert_config_1",      # program low reservoir alerts
            "S1.1=alert_config_2",      # reprogram LOC alert
            "S1.2=1",                   # prime pod
            "S1.1=alert_config_3",      # program expiration alert
            "S1.3=basal_data",          # program basal
            "S1.1=alert_config_4",      # program cancel/LOC alerts
            "S1.4=1",                   # insert cannula
            "S1.5=1",                   # enable algorithm
            "G11.3",                    # get AID status (-> ACTIVATED)
        ]

        for i, cmd in enumerate(activation_cmds):
            enc_msg = phone_build_encrypted_rhp(
                msk=msk, rhp_text=cmd, nonce_counter=i, command_id=i + 100,
            )
            response = session.on_message(enc_msg)
            assert response is not None

        # Now send bolus: 20 pulses = 1.0 U
        bolus_counter = len(activation_cmds)
        enc_msg = phone_build_encrypted_rhp(
            msk=msk,
            rhp_text="S200.0=20",
            nonce_counter=bolus_counter,
            command_id=200,
        )
        response = session.on_message(enc_msg)

        assert response is not None
        twi_resp = phone_decrypt_response(msk, response, nonce_counter=bolus_counter)
        assert twi_resp.command_bytes == "ES200.0=0"

        # Verify pod state
        assert pod_state.bolus_in_progress is True
        assert pod_state.bolus_total_units == pytest.approx(1.0)


class TestDisconnection:
    """Test disconnection handling."""

    def test_disconnect_resets_phase(self, session: ProtocolSession):
        session.on_message(phone_build_init())
        assert session.phase == SessionPhase.PAIRING

        session.on_disconnect()
        assert session.phase == SessionPhase.DISCONNECTED


class TestDeactivation:
    """Test that deactivation resets pod and session state for re-pairing."""

    def _authenticate(self, session: ProtocolSession) -> bytes:
        """Run pairing + EAP-AKA and return encryption key."""
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
        rand = os.urandom(16)
        sqn = b"\x00" * 6
        session.on_message(phone_build_eap_aka_challenge(ltk, rand, sqn))
        session.on_message(phone_build_eap_success())

        milenage = Milenage(ltk)
        return milenage.f3(rand)

    def test_deactivate_resets_pod_state(
        self, session: ProtocolSession, pod_state: PodState
    ):
        """Deactivation should reset pod to factory defaults."""
        msk = self._authenticate(session)

        # Activate the pod
        activation_cmds = [
            "GV", "S1.0=deadbeef",
            "S1.1=a1", "S1.1=a2", "S1.2=1", "S1.1=a3",
            "S1.3=basal", "S1.1=a4", "S1.4=1", "S1.5=1", "G11.3",
        ]
        for i, cmd in enumerate(activation_cmds):
            enc = phone_build_encrypted_rhp(msk=msk, rhp_text=cmd, nonce_counter=i, command_id=i)
            session.on_message(enc)

        assert pod_state.activated is True
        assert pod_state.cannula_inserted is True

        # Start a bolus
        n = len(activation_cmds)
        enc = phone_build_encrypted_rhp(msk=msk, rhp_text="S200.0=20", nonce_counter=n, command_id=n)
        session.on_message(enc)
        assert pod_state.bolus_in_progress is True

        # Deactivate
        deactivate_n = n + 1
        enc = phone_build_encrypted_rhp(msk=msk, rhp_text="S200.6=1", nonce_counter=deactivate_n, command_id=deactivate_n)
        response = session.on_message(enc)
        assert response is not None
        twi_resp = phone_decrypt_response(msk, response, nonce_counter=deactivate_n)
        assert twi_resp.command_bytes == "ES200.6=0"

        # Pod state should be fully reset
        assert pod_state.activated is False
        assert pod_state.deactivated is False
        assert pod_state.cannula_inserted is False
        assert pod_state.primed is False
        assert pod_state.bolus_in_progress is False
        assert pod_state.bolus_remaining_units == 0.0
        assert pod_state.reservoir_units == 200.0
        assert pod_state.total_insulin_delivered == 0.0
        assert pod_state.iob_units == 0.0
        assert pod_state.unique_id == b"\x00\x00\x00\x00"
        assert pod_state.alerts == []

    def test_deactivate_clears_ltk_for_fresh_pairing(
        self, session: ProtocolSession, pod_state: PodState
    ):
        """After deactivation, init should trigger fresh pairing (not reconnect)."""
        msk = self._authenticate(session)

        # Activate
        activation_cmds = [
            "GV", "S1.0=deadbeef",
            "S1.1=a1", "S1.1=a2", "S1.2=1", "S1.1=a3",
            "S1.3=basal", "S1.1=a4", "S1.4=1", "S1.5=1", "G11.3",
        ]
        for i, cmd in enumerate(activation_cmds):
            enc = phone_build_encrypted_rhp(msk=msk, rhp_text=cmd, nonce_counter=i, command_id=i)
            session.on_message(enc)

        # Deactivate
        n = len(activation_cmds)
        enc = phone_build_encrypted_rhp(msk=msk, rhp_text="S200.6=1", nonce_counter=n, command_id=n)
        session.on_message(enc)

        # Session stays ACTIVE (keys still valid for final response),
        # but LTK is cleared so next init triggers fresh pairing
        session.on_disconnect()

        # Next init should trigger fresh pairing, NOT reconnection
        init_resp = session.on_message(phone_build_init())
        assert init_resp is not None
        assert session.phase == SessionPhase.PAIRING
        # Should get pubkey+nonce (55 bytes), NOT the 2-byte "already paired" indicator
        assert len(init_resp) == 1 + 6 + 32 + 16


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
        """After reconnection, EAP-AKA + encrypted text RHP should work."""
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

        # Encryption key = CK directly (per TWI SDK behaviour)
        milenage = Milenage(ltk)
        ck = milenage.f3(rand)
        msk = ck

        # Send a text RHP command via TWICommand
        enc_msg = phone_build_encrypted_rhp(
            msk=msk,
            rhp_text="G11.3",
            nonce_counter=0,
            command_id=1,
        )
        response = session.on_message(enc_msg)

        assert response is not None
        assert response[0] == MSG_ENCRYPTED

        # Decrypt and verify TWICommand + text RHP
        twi_resp = phone_decrypt_response(msk, response, nonce_counter=0)
        assert twi_resp.command_bytes.startswith("11.3=")

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


class TestEndToEndBolusFlow:
    """
    End-to-end test covering the complete lifecycle:
        Init → ECDH pairing → EAP-AKA auth → Activation → Bolus delivery

    Every protocol step is explicit — no helper shortcuts.
    """

    def test_full_flow_init_to_bolus(self, pod_state: PodState):
        session = ProtocolSession(
            pod_state=pod_state,
            firmware_id=FIRMWARE_ID,
            ecdh_seed=ECDH_SEED,
        )
        assert session.phase == SessionPhase.DISCONNECTED

        # ---------------------------------------------------------------
        # 1. Connection init
        # ---------------------------------------------------------------
        init_msg = bytes([MSG_INIT, 0x01, 0x04]) + CONTROLLER_ID
        init_resp = session.on_message(init_msg)

        assert session.phase == SessionPhase.PAIRING
        assert init_resp is not None
        assert init_resp[0] == MSG_PAIRING
        # Response: MSG_PAIRING(1) + firmware_id(6) + pod_pubkey(32) + pod_nonce(16)
        assert len(init_resp) == 55

        pod_pub = init_resp[7:39]
        pod_nonce = init_resp[39:55]

        # ---------------------------------------------------------------
        # 2. ECDH key exchange — phone sends its public key + nonce
        # ---------------------------------------------------------------
        phone_kp = EcdhKeyPair()

        key_nonce_msg = (
            bytes([MSG_PAIRING, PAIR_PHONE_KEY_NONCE])
            + phone_kp.public_key_bytes
            + phone_kp.nonce
        )
        conf_resp = session.on_message(key_nonce_msg)

        assert conf_resp is not None
        assert conf_resp[0] == MSG_PAIRING
        assert conf_resp[1] == 0x03  # pod confirmation marker
        pod_conf = conf_resp[2:18]

        # ---------------------------------------------------------------
        # 3. Key derivation and confirmation verification
        # ---------------------------------------------------------------
        shared_secret = phone_kp.compute_shared_secret(pod_pub)
        phone_derived = derive_keys_for_role(
            role=0,
            firmware_id=FIRMWARE_ID,
            controller_id=CONTROLLER_ID,
            local_public_key=phone_kp.public_key_bytes,
            peer_public_key=pod_pub,
            shared_secret=shared_secret,
        )

        # Verify pod's confirmation is correct
        expected_pod_conf = _aes_cmac(
            phone_derived.confirmation_key,
            pod_nonce + phone_kp.nonce + pod_pub + phone_kp.public_key_bytes,
        )
        assert pod_conf == expected_pod_conf

        # Phone computes and sends its confirmation
        phone_conf = _aes_cmac(
            phone_derived.confirmation_key,
            phone_kp.nonce + pod_nonce + phone_kp.public_key_bytes + pod_pub,
        )
        pair_done_resp = session.on_message(
            bytes([MSG_PAIRING, PAIR_PHONE_CONF]) + phone_conf
        )

        assert pair_done_resp is not None
        assert pair_done_resp == bytes([MSG_PAIRING, 0x04])  # pairing complete
        assert session.phase == SessionPhase.AUTHENTICATING

        ltk = phone_derived.ltk

        # ---------------------------------------------------------------
        # 4. EAP-AKA mutual authentication
        # ---------------------------------------------------------------
        rand = os.urandom(16)
        sqn = b"\x00" * 6
        milenage = Milenage(ltk)
        amf = b"\x00\x00"

        av = milenage.generate_auth_vector(rand, sqn, amf)
        autn = compute_autn(sqn, av.ak, amf, av.mac_a)

        at_rand = _build_eap_attr(AT_RAND, rand)
        at_autn = _build_eap_attr(AT_AUTN, autn)
        aka_payload = bytes([EAP_TYPE_AKA, AKA_CHALLENGE, 0, 0]) + at_rand + at_autn
        eap_challenge = (
            bytes([EAP_REQUEST, 1])
            + (4 + len(aka_payload)).to_bytes(2, "big")
            + aka_payload
        )

        eap_resp = session.on_message(bytes([MSG_EAP]) + eap_challenge)
        assert eap_resp is not None
        assert eap_resp[0] == MSG_EAP
        # EAP-Response contains AT_RES
        assert eap_resp[5] == EAP_TYPE_AKA
        assert eap_resp[6] == AKA_CHALLENGE

        # Phone sends EAP-Success
        eap_success = bytes([EAP_SUCCESS, 2, 0, 4])
        result = session.on_message(bytes([MSG_EAP]) + eap_success)
        assert result is None  # EAP-Success has no response
        assert session.phase == SessionPhase.ACTIVE

        # Derive the encryption key (CK directly)
        encryption_key = milenage.f3(rand)

        # ---------------------------------------------------------------
        # 5. Activation sequence (encrypted RHP over TWICommand)
        # ---------------------------------------------------------------
        nonce_ctr = 0

        def send_rhp(text: str, cmd_id: int) -> str:
            """Send an encrypted RHP command, return the response text."""
            nonlocal nonce_ctr
            enc = phone_build_encrypted_rhp(
                msk=encryption_key,
                rhp_text=text,
                nonce_counter=nonce_ctr,
                command_id=cmd_id,
            )
            resp = session.on_message(enc)
            assert resp is not None, f"No response for {text!r}"
            assert resp[0] == MSG_ENCRYPTED
            twi = phone_decrypt_response(encryption_key, resp, nonce_counter=nonce_ctr)
            nonce_ctr += 1
            return twi.command_bytes

        # Get version
        ver = send_rhp("GV", 1)
        assert ver.startswith("V")
        assert "EMUL-" in ver

        # Set unique ID
        resp = send_rhp("S1.0=deadbeef", 2)
        assert resp == "ES1.0=0"
        assert pod_state.unique_id == b"\xde\xad\xbe\xef"

        # Program alerts (4 rounds per documented activation flow)
        assert send_rhp("S1.1=cfg1", 3) == "ES1.1=0"
        assert send_rhp("S1.1=cfg2", 4) == "ES1.1=0"

        # Prime pod
        assert send_rhp("S1.2=1", 5) == "ES1.2=0"

        # Program expiration alert
        assert send_rhp("S1.1=cfg3", 6) == "ES1.1=0"

        # Program basal
        assert send_rhp("S1.3=basal_schedule", 7) == "ES1.3=0"

        # Program cancel/LOC alerts
        assert send_rhp("S1.1=cfg4", 8) == "ES1.1=0"

        # Insert cannula
        assert send_rhp("S1.4=1", 9) == "ES1.4=0"
        assert pod_state.cannula_inserted is True

        # Enable algorithm + CGM
        assert send_rhp("S1.5=1", 10) == "ES1.5=0"
        assert pod_state.activated is True

        # Get AID status (transitions to ACTIVATED)
        status = send_rhp("G11.3", 11)
        assert status.startswith("11.3=")
        # 28-byte payload = 56 hex chars
        assert len(status.split("=", 1)[1]) == 56

        # ---------------------------------------------------------------
        # 6. Bolus delivery
        # ---------------------------------------------------------------
        assert pod_state.bolus_in_progress is False

        # Send 40 pulses = 2.0 U bolus
        bolus_resp = send_rhp("S200.0=40", 12)
        assert bolus_resp == "ES200.0=0"

        assert pod_state.bolus_in_progress is True
        assert pod_state.bolus_total_units == pytest.approx(2.0)
        assert pod_state.bolus_remaining_units == pytest.approx(2.0)

        # Verify we can query status while bolus is in progress
        status2 = send_rhp("G11.3", 13)
        assert status2.startswith("11.3=")
