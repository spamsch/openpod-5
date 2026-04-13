"""
Tests for the pod-side pairing state machine.

Verifies:
    - Full pairing flow (phone + pod) derives the same LTK
    - State transitions are enforced
    - Confirmation values are validated correctly (AES-CCM)
    - SIM profile stores and recovers the LTK
"""

import pytest

from omnipod_emulator.crypto import aes_ccm
from omnipod_emulator.crypto.ecdh import EcdhKeyPair
from omnipod_emulator.crypto.kdf import derive_keys_for_role
from omnipod_emulator.protocol.pairing import (
    PairingState,
    PairingStateMachine,
    SimProfile,
    _aes_cmac,
    compute_controller_confirmation,
)


_FIRMWARE_ID = b"\x01\x02\x03\x04\x05\x06"
_CONTROLLER_ID = b"\xaa\xbb\xcc\xdd"


class TestPairingFlow:
    """End-to-end pairing flow: phone + pod derive the same LTK."""

    def test_full_pairing_produces_matching_ltk(self) -> None:
        """
        Simulate both sides of the pairing handshake and verify they
        arrive at the same LTK.
        """
        # -- Pod side --
        pod = PairingStateMachine(
            firmware_id=_FIRMWARE_ID,
            controller_id=_CONTROLLER_ID,
        )
        pod_pubkey, pod_nonce = pod.initialize()

        # -- Phone side (manual ECDH) --
        phone_kp = EcdhKeyPair()

        # Exchange public keys
        pod.set_peer_data(phone_kp.public_key_bytes, phone_kp.nonce)

        # Pod computes keys and confirmation
        pod_conf = pod.derive_keys_and_compute_confirmation()

        # Phone computes shared secret and KDF
        phone_shared_secret = phone_kp.compute_shared_secret(pod_pubkey)
        phone_keys = derive_keys_for_role(
            role=0,
            firmware_id=_FIRMWARE_ID,
            controller_id=_CONTROLLER_ID,
            local_public_key=phone_kp.public_key_bytes,
            peer_public_key=pod_pubkey,
            shared_secret=phone_shared_secret,
        )

        # Phone computes its AES-CCM confirmation
        # Phone is role=2 (controller), nonce: role || phone_nonce[0:6] || pod_nonce[0:6]
        phone_ccm_nonce = (
            bytes([0x02]) + phone_kp.nonce[:6] + pod_nonce[:6]
        )
        # Plaintext: phone_pubkey || pod_pubkey (local first for controller)
        phone_plaintext = phone_kp.public_key_bytes + pod_pubkey
        phone_conf = aes_ccm.encrypt(
            key=phone_keys.confirmation_key,
            plaintext=phone_plaintext,
            nonce=phone_ccm_nonce,
            tag_length=8,
        )

        # Pod verifies phone's confirmation
        assert pod.verify_peer_confirmation(phone_conf) is True

        # Phone verifies pod's confirmation by decrypting it
        # Pod is role=1 (peripheral), nonce: role || pod_nonce[0:6] || phone_nonce[0:6]
        pod_ccm_nonce = (
            bytes([0x01]) + pod_nonce[:6] + phone_kp.nonce[:6]
        )
        pod_plaintext = aes_ccm.decrypt(
            key=phone_keys.confirmation_key,
            ciphertext=pod_conf,
            nonce=pod_ccm_nonce,
            tag_length=8,
        )
        # Pod's plaintext should be pod_pubkey || phone_pubkey
        assert pod_plaintext == pod_pubkey + phone_kp.public_key_bytes

        # Both sides should have the same LTK
        assert pod.ltk == phone_keys.ltk

        # Save and verify SIM profile
        sim = pod.save_ltk()
        assert sim.get_ltk() == phone_keys.ltk
        assert pod.state == PairingState.COMPLETE

    def test_deterministic_pairing(self) -> None:
        """With fixed seeds, the pairing should be reproducible."""
        seed_pod = b"\x01" * 32
        seed_phone = b"\x02" * 32

        pod = PairingStateMachine(
            firmware_id=_FIRMWARE_ID,
            controller_id=_CONTROLLER_ID,
            ecdh_seed=seed_pod,
        )
        pod_pubkey, pod_nonce = pod.initialize()

        phone_kp = EcdhKeyPair(seed=seed_phone)
        pod.set_peer_data(phone_kp.public_key_bytes, phone_kp.nonce)
        pod.derive_keys_and_compute_confirmation()

        ltk1 = pod.ltk

        # Repeat
        pod2 = PairingStateMachine(
            firmware_id=_FIRMWARE_ID,
            controller_id=_CONTROLLER_ID,
            ecdh_seed=seed_pod,
        )
        pod2_pubkey, _ = pod2.initialize()
        pod2.set_peer_data(phone_kp.public_key_bytes, phone_kp.nonce)
        pod2.derive_keys_and_compute_confirmation()

        ltk2 = pod2.ltk

        # Public keys and LTKs should be identical
        assert pod_pubkey == pod2_pubkey
        assert ltk1 == ltk2


class TestPairingStateMachine:
    """Tests for state transition enforcement."""

    def test_initial_state_is_idle(self) -> None:
        pod = PairingStateMachine(_FIRMWARE_ID, _CONTROLLER_ID)
        assert pod.state == PairingState.IDLE

    def test_cannot_set_peer_data_before_init(self) -> None:
        pod = PairingStateMachine(_FIRMWARE_ID, _CONTROLLER_ID)
        with pytest.raises(RuntimeError, match="Cannot set peer data"):
            pod.set_peer_data(b"\x00" * 32, b"\x00" * 16)

    def test_cannot_derive_keys_before_peer_data(self) -> None:
        pod = PairingStateMachine(_FIRMWARE_ID, _CONTROLLER_ID)
        pod.initialize()
        with pytest.raises(RuntimeError, match="Cannot derive keys"):
            pod.derive_keys_and_compute_confirmation()

    def test_cannot_save_ltk_before_verification(self) -> None:
        pod = PairingStateMachine(_FIRMWARE_ID, _CONTROLLER_ID)
        pod.initialize()
        pod.set_peer_data(EcdhKeyPair().public_key_bytes, b"\x00" * 16)
        pod.derive_keys_and_compute_confirmation()
        with pytest.raises(RuntimeError, match="Cannot save LTK"):
            pod.save_ltk()

    def test_wrong_confirmation_sets_failed(self) -> None:
        pod = PairingStateMachine(_FIRMWARE_ID, _CONTROLLER_ID)
        pod.initialize()
        pod.set_peer_data(EcdhKeyPair().public_key_bytes, b"\x00" * 16)
        pod.derive_keys_and_compute_confirmation()

        # AES-CCM needs at least tag_length (8) bytes; bogus data fails tag check
        result = pod.verify_peer_confirmation(b"\xff" * 72)
        assert result is False
        assert pod.state == PairingState.FAILED


class TestSimProfile:
    """Tests for SIM profile XOR-masked storage."""

    def test_ltk_round_trip(self) -> None:
        """Store and recover LTK from SIM profile."""
        ltk = b"\xab\xcd\xef\x01\x23\x45\x67\x89" * 2
        sim = SimProfile.from_ltk(ltk, _FIRMWARE_ID, _CONTROLLER_ID)

        recovered = sim.get_ltk()
        assert recovered == ltk

    def test_serialization_length(self) -> None:
        """SIM profile should serialize to 93 bytes."""
        ltk = b"\x00" * 16
        sim = SimProfile.from_ltk(ltk, _FIRMWARE_ID, _CONTROLLER_ID)
        assert len(sim.to_bytes()) == 93

    def test_firmware_id_at_correct_offset(self) -> None:
        """Firmware ID should be at offset 0x20 in the serialized form."""
        ltk = b"\x00" * 16
        sim = SimProfile.from_ltk(ltk, _FIRMWARE_ID, _CONTROLLER_ID)
        raw = sim.to_bytes()
        assert raw[0x20:0x26] == _FIRMWARE_ID

    def test_controller_id_at_correct_offset(self) -> None:
        """Controller ID should be at offset 0x36."""
        ltk = b"\x00" * 16
        sim = SimProfile.from_ltk(ltk, _FIRMWARE_ID, _CONTROLLER_ID)
        raw = sim.to_bytes()
        assert raw[0x36:0x3A] == _CONTROLLER_ID


class TestAesCmac:
    """Basic tests for the AES-CMAC implementation."""

    def test_known_vector_empty(self) -> None:
        """RFC 4493 Test Vector #1: CMAC of empty message."""
        key = bytes.fromhex("2b7e151628aed2a6abf7158809cf4f3c")
        expected = bytes.fromhex("bb1d6929e95937287fa37d129b756746")
        assert _aes_cmac(key, b"") == expected

    def test_known_vector_16_bytes(self) -> None:
        """RFC 4493 Test Vector #2: CMAC of 16-byte message."""
        key = bytes.fromhex("2b7e151628aed2a6abf7158809cf4f3c")
        msg = bytes.fromhex("6bc1bee22e409f96e93d7e117393172a")
        expected = bytes.fromhex("070a16b46b4d4144f79bdd9dd04a287c")
        assert _aes_cmac(key, msg) == expected

    def test_known_vector_40_bytes(self) -> None:
        """RFC 4493 Test Vector #3: CMAC of 40-byte message."""
        key = bytes.fromhex("2b7e151628aed2a6abf7158809cf4f3c")
        msg = bytes.fromhex(
            "6bc1bee22e409f96e93d7e117393172a"
            "ae2d8a571e03ac9c9eb76fac45af8e51"
            "30c81c46a35ce411"
        )
        expected = bytes.fromhex("dfa66747de9ae63030ca32611497c827")
        assert _aes_cmac(key, msg) == expected

    def test_known_vector_64_bytes(self) -> None:
        """RFC 4493 Test Vector #4: CMAC of 64-byte message."""
        key = bytes.fromhex("2b7e151628aed2a6abf7158809cf4f3c")
        msg = bytes.fromhex(
            "6bc1bee22e409f96e93d7e117393172a"
            "ae2d8a571e03ac9c9eb76fac45af8e51"
            "30c81c46a35ce411e5fbc1191a0a52ef"
            "f69f2445df4f9b17ad2b417be66c3710"
        )
        expected = bytes.fromhex("51f0bebf7e3b9d92fc49741779363cfe")
        assert _aes_cmac(key, msg) == expected


class TestComputeControllerConfirmation:
    """
    Stateless helper for the Elmo FGH-bypass bridge.

    Proves that compute_controller_confirmation() produces a value that
    the pod-side PairingStateMachine.verify_peer_confirmation() accepts —
    i.e. the helper correctly mirrors what the phone's native crypto
    would have computed.
    """

    @pytest.mark.parametrize("algorithm", [0x00, 0x01])
    def test_round_trip_verifies(self, algorithm: int) -> None:
        """Phone confirmation from helper round-trips through pod verify."""
        # Deterministic seeds for both sides so the test is reproducible.
        pod_seed = b"\x11" * 32
        phone_seed = b"\x22" * 32

        pod_sm = PairingStateMachine(
            firmware_id=_FIRMWARE_ID,
            controller_id=_CONTROLLER_ID,
            ecdh_seed=pod_seed,
            algorithm=algorithm,
        )
        pod_pub, pod_nonce = pod_sm.initialize()

        phone_kp = EcdhKeyPair(seed=phone_seed, algorithm=algorithm)
        phone_pub = phone_kp.public_key_bytes
        phone_nonce = phone_kp.nonce

        # Pod stores phone's key material and derives its own confirmation.
        pod_sm.set_peer_data(phone_pub, phone_nonce)
        _ = pod_sm.derive_keys_and_compute_confirmation()

        # Shared secret from phone's perspective
        # (== from pod's perspective by ECDH symmetry).
        shared = phone_kp.compute_shared_secret(pod_pub)
        assert shared == pod_sm._shared_secret  # symmetry sanity check

        phone_conf = compute_controller_confirmation(
            controller_id=_CONTROLLER_ID,
            firmware_id=_FIRMWARE_ID,
            controller_public_key=phone_pub,
            controller_nonce=phone_nonce,
            pod_public_key=pod_pub,
            pod_nonce=pod_nonce,
            shared_secret=shared,
        )

        # The payoff: the pod's verifier accepts the helper's output.
        assert pod_sm.verify_peer_confirmation(phone_conf) is True
        assert pod_sm.state == PairingState.CONF_VERIFIED

    def test_output_length_p256(self) -> None:
        """P-256 confirmation: 64 + 64 + 8 = 136 bytes."""
        pod_sm = PairingStateMachine(
            firmware_id=_FIRMWARE_ID,
            controller_id=_CONTROLLER_ID,
            ecdh_seed=b"\x33" * 32,
            algorithm=0x01,
        )
        pod_pub, pod_nonce = pod_sm.initialize()
        phone_kp = EcdhKeyPair(seed=b"\x44" * 32, algorithm=0x01)
        shared = phone_kp.compute_shared_secret(pod_pub)

        out = compute_controller_confirmation(
            controller_id=_CONTROLLER_ID,
            firmware_id=_FIRMWARE_ID,
            controller_public_key=phone_kp.public_key_bytes,
            controller_nonce=phone_kp.nonce,
            pod_public_key=pod_pub,
            pod_nonce=pod_nonce,
            shared_secret=shared,
        )
        assert len(out) == 64 + 64 + 8

    def test_rejects_bad_nonce_length(self) -> None:
        pod_sm = PairingStateMachine(
            firmware_id=_FIRMWARE_ID,
            controller_id=_CONTROLLER_ID,
            ecdh_seed=b"\x55" * 32,
            algorithm=0x01,
        )
        pod_pub, pod_nonce = pod_sm.initialize()
        phone_kp = EcdhKeyPair(seed=b"\x66" * 32, algorithm=0x01)
        shared = phone_kp.compute_shared_secret(pod_pub)

        with pytest.raises(ValueError, match="controller_nonce"):
            compute_controller_confirmation(
                controller_id=_CONTROLLER_ID,
                firmware_id=_FIRMWARE_ID,
                controller_public_key=phone_kp.public_key_bytes,
                controller_nonce=b"\x00" * 8,  # too short
                pod_public_key=pod_pub,
                pod_nonce=pod_nonce,
                shared_secret=shared,
            )

        with pytest.raises(ValueError, match="pod_nonce"):
            compute_controller_confirmation(
                controller_id=_CONTROLLER_ID,
                firmware_id=_FIRMWARE_ID,
                controller_public_key=phone_kp.public_key_bytes,
                controller_nonce=phone_kp.nonce,
                pod_public_key=pod_pub,
                pod_nonce=b"\x00" * 8,  # too short
                shared_secret=shared,
            )
