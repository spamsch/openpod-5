"""
Cryptographic reference vector tests.

These vectors were generated using known inputs and verify that the Python
crypto implementations produce correct outputs for SHA-256, AES-128-ECB,
X25519, and the protocol KDF.
"""

from __future__ import annotations

import hashlib

import pytest

from omnipod_emulator.crypto.ecdh import EcdhKeyPair

# ── Reference vectors ─────────────────────────────────────────────────

# SHA-256 vectors
SHA256_VECTORS = [
    ("", "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"),
    ("616263", "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"),
    (
        "42" * 55,
        "1eed5900533b34bb08a62c072a0b0a67058b181b53a8f6e14d3d88d1d78fbe2b",
    ),
]

# AES-128-ECB vector (FIPS 197 Appendix B)
AES_ECB_KEY = bytes.fromhex("2b7e151628aed2a6abf7158809cf4f3c")
AES_ECB_PLAINTEXT = bytes.fromhex("3243f6a8885a308d313198a2e0370734")
AES_ECB_CIPHERTEXT = bytes.fromhex("3925841d02dc09fbdc118597196a0b32")

# Curve25519 vectors
CURVE25519_KEYGEN_PRIVATE = bytes.fromhex(
    "4042424242424242424242424242424242424242424242424242424242424242"
)
CURVE25519_KEYGEN_PUBLIC = bytes.fromhex(
    "132c442be010fbd57e72603328aa76e71fccc1503aae219327d14d9c9993f472"
)

CURVE25519_ALICE_PRIVATE = bytes.fromhex(
    "1011111111111111111111111111111111111111111111111111111111111151"
)
CURVE25519_ALICE_PUBLIC = bytes.fromhex(
    "7b4e909bbe7ffe44c465a220037d608ee35897d31ef972f07f74892cb0f73f13"
)
CURVE25519_BOB_PRIVATE = bytes.fromhex(
    "2022222222222222222222222222222222222222222222222222222222222262"
)
CURVE25519_BOB_PUBLIC = bytes.fromhex(
    "0faa684ed28867b97f4a6a2dee5df8ce974e76b7018e3f22a1c4cf2678570f20"
)
CURVE25519_SHARED_SECRET = bytes.fromhex(
    "9e004098efc091d4ec2663b4e9f5cfd4d7064571690b4bea97ab146ab9f35056"
)

# KDF test vector (SHA-256 of length-prefixed protocol inputs)
KDF_FIRMWARE_ID = bytes.fromhex("aabbccddeeff")
KDF_CONTROLLER_ID = bytes.fromhex("01020304")
KDF_PHONE_KEY = bytes(32) + b""  # replaced below
KDF_POD_KEY = bytes(32) + b""    # replaced below
KDF_CONF_KEY = bytes.fromhex("b9d9ac48d333bb7e10c875d964b7ca15")
KDF_LTK = bytes.fromhex("aa2197a633560b49d0496ea2b82f9075")

# AES-ECB vectors
AES_ECB_ZEROS_CIPHERTEXT = bytes.fromhex("66e94bd4ef8a2c3b884cfa59ca342b2e")
CMAC_SUBKEY_L = bytes.fromhex("7df76b0c1ab899b33e42f047b91b546f")


# ── SHA-256 tests ──────────────────────────────────────────────────────


class TestSha256NativeVectors:
    """Verify Python SHA-256 against known reference vectors."""

    @pytest.mark.parametrize(
        "input_hex,expected_hex",
        SHA256_VECTORS,
        ids=["empty", "abc", "55_bytes_0x42"],
    )
    def test_sha256(self, input_hex: str, expected_hex: str):
        data = bytes.fromhex(input_hex)
        result = hashlib.sha256(data).hexdigest()
        assert result == expected_hex


# ── AES-128-ECB tests ──────────────────────────────────────────────────


class TestAesEcbNativeVectors:
    """Verify Python AES-128-ECB against FIPS 197 test vectors."""

    def test_aes_ecb_encrypt(self):
        from cryptography.hazmat.primitives.ciphers import Cipher, algorithms, modes

        cipher = Cipher(algorithms.AES(AES_ECB_KEY), modes.ECB())
        encryptor = cipher.encryptor()
        result = encryptor.update(AES_ECB_PLAINTEXT) + encryptor.finalize()
        assert result == AES_ECB_CIPHERTEXT


# ── Curve25519 tests ───────────────────────────────────────────────────


class TestKdfNativeVectors:
    """Verify Python KDF against known reference vectors."""

    def test_kdf_derivation(self):
        from omnipod_emulator.crypto.kdf import derive_keys

        phone_key = bytes([0x11] * 32)
        pod_key = bytes([0x22] * 32)
        shared_secret = bytes([0x33] * 32)

        result = derive_keys(
            firmware_id=KDF_FIRMWARE_ID,
            controller_id=KDF_CONTROLLER_ID,
            pod_public_key=pod_key,
            phone_public_key=phone_key,
            shared_secret=shared_secret,
        )

        assert result.confirmation_key == KDF_CONF_KEY
        assert result.ltk == KDF_LTK


class TestAesEcbAdditionalVectors:
    """Additional AES-ECB reference vectors."""

    def test_all_zeros(self):
        from cryptography.hazmat.primitives.ciphers import Cipher, algorithms, modes

        cipher = Cipher(algorithms.AES(bytes(16)), modes.ECB())
        result = cipher.encryptor().update(bytes(16)) + cipher.encryptor().finalize()
        # finalize may return empty, use single call
        enc = Cipher(algorithms.AES(bytes(16)), modes.ECB()).encryptor()
        result = enc.update(bytes(16)) + enc.finalize()
        assert result == AES_ECB_ZEROS_CIPHERTEXT

    def test_cmac_subkey_l(self):
        """Verify AES_K(0^128) = L, used for CMAC subkey generation."""
        from cryptography.hazmat.primitives.ciphers import Cipher, algorithms, modes

        enc = Cipher(algorithms.AES(AES_ECB_KEY), modes.ECB()).encryptor()
        result = enc.update(bytes(16)) + enc.finalize()
        assert result == CMAC_SUBKEY_L


class TestCmacNativeVectors:
    """Verify Python AES-CMAC against RFC 4493 known-answer tests."""

    def test_cmac_empty(self):
        """RFC 4493 Example 1: CMAC of empty message."""
        from omnipod_emulator.protocol.pairing import _aes_cmac

        key = bytes.fromhex("2b7e151628aed2a6abf7158809cf4f3c")
        expected = bytes.fromhex("bb1d6929e95937287fa37d129b756746")
        assert _aes_cmac(key, b"") == expected

    def test_cmac_16bytes(self):
        """RFC 4493 Example 2: CMAC of 16 bytes."""
        from omnipod_emulator.protocol.pairing import _aes_cmac

        key = bytes.fromhex("2b7e151628aed2a6abf7158809cf4f3c")
        msg = bytes.fromhex("6bc1bee22e409f96e93d7e117393172a")
        expected = bytes.fromhex("070a16b46b4d4144f79bdd9dd04a287c")
        assert _aes_cmac(key, msg) == expected

    def test_cmac_40bytes(self):
        """RFC 4493 Example 3: CMAC of 40 bytes."""
        from omnipod_emulator.protocol.pairing import _aes_cmac

        key = bytes.fromhex("2b7e151628aed2a6abf7158809cf4f3c")
        msg = bytes.fromhex(
            "6bc1bee22e409f96e93d7e117393172a"
            "ae2d8a571e03ac9c9eb76fac45af8e51"
            "30c81c46a35ce411"
        )
        expected = bytes.fromhex("dfa66747de9ae63030ca32611497c827")
        assert _aes_cmac(key, msg) == expected

    def test_cmac_64bytes(self):
        """RFC 4493 Example 4: CMAC of 64 bytes."""
        from omnipod_emulator.protocol.pairing import _aes_cmac

        key = bytes.fromhex("2b7e151628aed2a6abf7158809cf4f3c")
        msg = bytes.fromhex(
            "6bc1bee22e409f96e93d7e117393172a"
            "ae2d8a571e03ac9c9eb76fac45af8e51"
            "30c81c46a35ce411e5fbc1191a0a52ef"
            "f69f2445df4f9b17ad2b417be66c3710"
        )
        expected = bytes.fromhex("51f0bebf7e3b9d92fc49741779363cfe")
        assert _aes_cmac(key, msg) == expected


class TestCurve25519NativeVectors:
    """Verify Python X25519 against known reference vectors."""

    def test_keygen_from_seed(self):
        """Verify public key derivation from a fixed seed."""
        kp = EcdhKeyPair(seed=CURVE25519_KEYGEN_PRIVATE)
        assert kp.public_key_bytes == CURVE25519_KEYGEN_PUBLIC

    def test_shared_secret_agreement(self):
        """Verify shared secret computation between two key pairs."""
        alice = EcdhKeyPair(seed=CURVE25519_ALICE_PRIVATE)
        assert alice.public_key_bytes == CURVE25519_ALICE_PUBLIC

        bob = EcdhKeyPair(seed=CURVE25519_BOB_PRIVATE)
        assert bob.public_key_bytes == CURVE25519_BOB_PUBLIC

        alice_shared = alice.compute_shared_secret(bob.public_key_bytes)
        bob_shared = bob.compute_shared_secret(alice.public_key_bytes)

        assert alice_shared == bob_shared
        assert alice_shared == CURVE25519_SHARED_SECRET
