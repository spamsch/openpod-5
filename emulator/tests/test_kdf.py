"""
Tests for the SHA-256 based KDF (LTK derivation).

Verifies:
    - Output lengths are correct (16 + 16 bytes)
    - Known-answer test with fixed inputs
    - Role-dependent key ordering produces different results
    - Phone (role=0) and pod (role=1) derive the same keys when using the
      correct role parameters
    - Invalid inputs are rejected
"""

import hashlib

import pytest

from omnipod_emulator.crypto.kdf import (
    DerivedKeys,
    _length_prefix,
    derive_keys,
    derive_keys_for_role,
)


# Fixed test inputs
_FIRMWARE_ID = b"\x01\x02\x03\x04\x05\x06"
_CONTROLLER_ID = b"\xaa\xbb\xcc\xdd"
_POD_PUBKEY = b"\x10" * 32
_PHONE_PUBKEY = b"\x20" * 32
_SHARED_SECRET = b"\x30" * 32


class TestLengthPrefix:
    """Tests for the length prefix encoding."""

    def test_prefix_is_8_bytes(self) -> None:
        """Length prefix should always be 8 bytes."""
        assert len(_length_prefix(b"\x00" * 6)) == 8

    def test_prefix_encoding_6_bytes(self) -> None:
        """6-byte data should produce [0,0,0,0, 0,6, 0,0]."""
        prefix = _length_prefix(b"\x00" * 6)
        assert prefix == b"\x00\x00\x00\x00\x00\x06\x00\x00"

    def test_prefix_encoding_32_bytes(self) -> None:
        """32-byte data should produce [0,0,0,0, 0,32, 0,0]."""
        prefix = _length_prefix(b"\x00" * 32)
        assert prefix == b"\x00\x00\x00\x00\x00\x20\x00\x00"

    def test_prefix_encoding_4_bytes(self) -> None:
        """4-byte data should produce [0,0,0,0, 0,4, 0,0]."""
        prefix = _length_prefix(b"\x00" * 4)
        assert prefix == b"\x00\x00\x00\x00\x00\x04\x00\x00"


class TestDeriveKeys:
    """Tests for the derive_keys function (pod role)."""

    def test_output_lengths(self) -> None:
        """Both keys should be 16 bytes each."""
        keys = derive_keys(
            _FIRMWARE_ID, _CONTROLLER_ID,
            _POD_PUBKEY, _PHONE_PUBKEY, _SHARED_SECRET,
        )
        assert len(keys.confirmation_key) == 16
        assert len(keys.ltk) == 16

    def test_known_answer(self) -> None:
        """Verify against a manually computed reference."""
        # Build the expected hash input (pod key first, phone key second)
        data = b""
        data += _length_prefix(_FIRMWARE_ID) + _FIRMWARE_ID
        data += _length_prefix(_CONTROLLER_ID) + _CONTROLLER_ID
        data += _length_prefix(_POD_PUBKEY) + _POD_PUBKEY
        data += _length_prefix(_PHONE_PUBKEY) + _PHONE_PUBKEY
        data += _length_prefix(_SHARED_SECRET) + _SHARED_SECRET

        expected_hash = hashlib.sha256(data).digest()
        expected_conf = expected_hash[:16]
        expected_ltk = expected_hash[16:]

        keys = derive_keys(
            _FIRMWARE_ID, _CONTROLLER_ID,
            _POD_PUBKEY, _PHONE_PUBKEY, _SHARED_SECRET,
        )

        assert keys.confirmation_key == expected_conf
        assert keys.ltk == expected_ltk

    def test_deterministic(self) -> None:
        """Same inputs should always produce the same output."""
        keys1 = derive_keys(
            _FIRMWARE_ID, _CONTROLLER_ID,
            _POD_PUBKEY, _PHONE_PUBKEY, _SHARED_SECRET,
        )
        keys2 = derive_keys(
            _FIRMWARE_ID, _CONTROLLER_ID,
            _POD_PUBKEY, _PHONE_PUBKEY, _SHARED_SECRET,
        )
        assert keys1 == keys2

    def test_different_inputs_produce_different_keys(self) -> None:
        """Changing any input should change the output."""
        baseline = derive_keys(
            _FIRMWARE_ID, _CONTROLLER_ID,
            _POD_PUBKEY, _PHONE_PUBKEY, _SHARED_SECRET,
        )
        altered = derive_keys(
            b"\xff" * 6, _CONTROLLER_ID,
            _POD_PUBKEY, _PHONE_PUBKEY, _SHARED_SECRET,
        )
        assert baseline != altered


class TestDeriveKeysForRole:
    """Tests for role-aware KDF."""

    def test_phone_and_pod_derive_same_keys(self) -> None:
        """Role 0 (phone) and role 1 (pod) should derive the same keys
        when given each other's public keys correctly."""
        phone_keys = derive_keys_for_role(
            role=0,
            firmware_id=_FIRMWARE_ID,
            controller_id=_CONTROLLER_ID,
            local_public_key=_PHONE_PUBKEY,
            peer_public_key=_POD_PUBKEY,
            shared_secret=_SHARED_SECRET,
        )
        pod_keys = derive_keys_for_role(
            role=1,
            firmware_id=_FIRMWARE_ID,
            controller_id=_CONTROLLER_ID,
            local_public_key=_POD_PUBKEY,
            peer_public_key=_PHONE_PUBKEY,
            shared_secret=_SHARED_SECRET,
        )
        assert phone_keys == pod_keys

    def test_invalid_role_rejected(self) -> None:
        """Role values other than 0 or 1 should raise ValueError."""
        with pytest.raises(ValueError, match="Invalid role"):
            derive_keys_for_role(
                role=2,
                firmware_id=_FIRMWARE_ID,
                controller_id=_CONTROLLER_ID,
                local_public_key=_POD_PUBKEY,
                peer_public_key=_PHONE_PUBKEY,
                shared_secret=_SHARED_SECRET,
            )


class TestInputValidation:
    """Tests for input validation."""

    def test_wrong_firmware_id_length(self) -> None:
        with pytest.raises(ValueError, match="firmware_id"):
            derive_keys(b"\x00" * 5, _CONTROLLER_ID, _POD_PUBKEY, _PHONE_PUBKEY, _SHARED_SECRET)

    def test_wrong_controller_id_length(self) -> None:
        with pytest.raises(ValueError, match="controller_id"):
            derive_keys(_FIRMWARE_ID, b"\x00" * 3, _POD_PUBKEY, _PHONE_PUBKEY, _SHARED_SECRET)

    def test_wrong_pubkey_length(self) -> None:
        with pytest.raises(ValueError, match="pod_public_key"):
            derive_keys(_FIRMWARE_ID, _CONTROLLER_ID, b"\x00" * 31, _PHONE_PUBKEY, _SHARED_SECRET)

    def test_wrong_secret_length(self) -> None:
        with pytest.raises(ValueError, match="shared_secret"):
            derive_keys(_FIRMWARE_ID, _CONTROLLER_ID, _POD_PUBKEY, _PHONE_PUBKEY, b"\x00" * 31)
