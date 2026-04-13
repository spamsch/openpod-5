"""
Tests for AES-CCM-128 encrypt/decrypt.

Verifies:
    - Encrypt then decrypt round-trip recovers the original plaintext
    - Different nonces produce different ciphertext
    - Tampered ciphertext fails authentication
    - AAD mismatches are detected
    - Various tag lengths work correctly
    - Invalid inputs are rejected
"""

import pytest
from cryptography.exceptions import InvalidTag

from omnipod_emulator.crypto.aes_ccm import decrypt, encrypt


# Fixed test values
_KEY = b"\x00\x01\x02\x03\x04\x05\x06\x07\x08\x09\x0a\x0b\x0c\x0d\x0e\x0f"
_NONCE = b"\x00\x01\x02\x03\x04\x05\x06\x07\x08\x09\x0a\x0b\x0c"  # 13 bytes
_PLAINTEXT = b"Hello Omnipod 5!"
_AAD = b"\xde\xad\xbe\xef"


class TestAesCcmRoundTrip:
    """Encrypt-then-decrypt round-trip tests."""

    def test_basic_round_trip(self) -> None:
        """Encrypt then decrypt should recover the plaintext."""
        ct = encrypt(_KEY, _PLAINTEXT, _NONCE, _AAD)
        pt = decrypt(_KEY, ct, _NONCE, _AAD)
        assert pt == _PLAINTEXT

    def test_round_trip_no_aad(self) -> None:
        """Round-trip should work without AAD."""
        ct = encrypt(_KEY, _PLAINTEXT, _NONCE, None)
        pt = decrypt(_KEY, ct, _NONCE, None)
        assert pt == _PLAINTEXT

    def test_round_trip_empty_plaintext(self) -> None:
        """Round-trip should work with empty plaintext (MAC only)."""
        ct = encrypt(_KEY, b"", _NONCE, _AAD)
        pt = decrypt(_KEY, ct, _NONCE, _AAD)
        assert pt == b""

    def test_round_trip_large_payload(self) -> None:
        """Round-trip with a larger payload (e.g., 1KB)."""
        data = bytes(range(256)) * 4
        ct = encrypt(_KEY, data, _NONCE, _AAD)
        pt = decrypt(_KEY, ct, _NONCE, _AAD)
        assert pt == data

    def test_ciphertext_includes_tag(self) -> None:
        """Ciphertext should be plaintext_length + tag_length."""
        ct = encrypt(_KEY, _PLAINTEXT, _NONCE, _AAD, tag_length=8)
        assert len(ct) == len(_PLAINTEXT) + 8

    def test_various_tag_lengths(self) -> None:
        """Test all valid CCM tag lengths."""
        for tag_len in [4, 6, 8, 10, 12, 14, 16]:
            ct = encrypt(_KEY, _PLAINTEXT, _NONCE, _AAD, tag_length=tag_len)
            pt = decrypt(_KEY, ct, _NONCE, _AAD, tag_length=tag_len)
            assert pt == _PLAINTEXT, f"Failed for tag_length={tag_len}"


class TestAesCcmIntegrity:
    """Tests verifying that tampering is detected."""

    def test_tampered_ciphertext_rejected(self) -> None:
        """Flipping a ciphertext byte should fail authentication."""
        ct = bytearray(encrypt(_KEY, _PLAINTEXT, _NONCE, _AAD))
        ct[0] ^= 0xFF
        with pytest.raises(InvalidTag):
            decrypt(_KEY, bytes(ct), _NONCE, _AAD)

    def test_wrong_key_rejected(self) -> None:
        """Decrypting with a different key should fail."""
        ct = encrypt(_KEY, _PLAINTEXT, _NONCE, _AAD)
        wrong_key = b"\xff" * 16
        with pytest.raises(InvalidTag):
            decrypt(wrong_key, ct, _NONCE, _AAD)

    def test_wrong_nonce_rejected(self) -> None:
        """Decrypting with a different nonce should fail."""
        ct = encrypt(_KEY, _PLAINTEXT, _NONCE, _AAD)
        wrong_nonce = b"\xff" * 13
        with pytest.raises(InvalidTag):
            decrypt(_KEY, ct, wrong_nonce, _AAD)

    def test_wrong_aad_rejected(self) -> None:
        """Decrypting with different AAD should fail."""
        ct = encrypt(_KEY, _PLAINTEXT, _NONCE, _AAD)
        with pytest.raises(InvalidTag):
            decrypt(_KEY, ct, _NONCE, b"\x00\x00\x00\x00")

    def test_different_nonces_produce_different_ciphertext(self) -> None:
        """The same plaintext with different nonces should produce
        different ciphertext."""
        ct1 = encrypt(_KEY, _PLAINTEXT, b"\x00" * 13, _AAD)
        ct2 = encrypt(_KEY, _PLAINTEXT, b"\x01" * 13, _AAD)
        assert ct1 != ct2


class TestAesCcmValidation:
    """Tests for input validation."""

    def test_invalid_key_length(self) -> None:
        with pytest.raises(ValueError, match="16 bytes"):
            encrypt(b"\x00" * 15, _PLAINTEXT, _NONCE, _AAD)

    def test_invalid_tag_length(self) -> None:
        with pytest.raises(ValueError, match="Tag length"):
            encrypt(_KEY, _PLAINTEXT, _NONCE, _AAD, tag_length=7)

    def test_key_type_validation(self) -> None:
        with pytest.raises(TypeError, match="bytes"):
            encrypt("not bytes", _PLAINTEXT, _NONCE, _AAD)  # type: ignore[arg-type]
