"""
Tests for X25519 ECDH key exchange.

Verifies:
    - Key pair generation produces correct-length keys
    - Two parties derive the same shared secret
    - Deterministic seeding produces reproducible results
    - Invalid inputs are rejected
"""

import pytest

from omnipod_emulator.crypto.ecdh import EcdhKeyPair


class TestEcdhKeyPair:
    """Tests for EcdhKeyPair."""

    def test_key_pair_generation_produces_correct_lengths(self) -> None:
        """Public key should be 32 bytes, nonce should be 16 bytes."""
        kp = EcdhKeyPair()
        assert len(kp.public_key_bytes) == 32
        assert len(kp.nonce) == 16

    def test_two_parties_derive_same_shared_secret(self) -> None:
        """Both sides of the exchange must arrive at the same secret."""
        alice = EcdhKeyPair()
        bob = EcdhKeyPair()

        secret_a = alice.compute_shared_secret(bob.public_key_bytes)
        secret_b = bob.compute_shared_secret(alice.public_key_bytes)

        assert secret_a == secret_b
        assert len(secret_a) == 32

    def test_deterministic_seed_produces_same_key(self) -> None:
        """Given the same 32-byte seed, the public key should be identical."""
        seed = b"\x01" * 32
        kp1 = EcdhKeyPair(seed=seed)
        kp2 = EcdhKeyPair(seed=seed)

        assert kp1.public_key_bytes == kp2.public_key_bytes
        # Nonces are still random
        assert kp1.nonce != kp2.nonce

    def test_deterministic_seed_shared_secret(self) -> None:
        """Seeded key pairs should produce valid shared secrets."""
        seed_a = b"\x01" * 32
        seed_b = b"\x02" * 32

        alice = EcdhKeyPair(seed=seed_a)
        bob = EcdhKeyPair(seed=seed_b)

        secret_a = alice.compute_shared_secret(bob.public_key_bytes)
        secret_b = bob.compute_shared_secret(alice.public_key_bytes)

        assert secret_a == secret_b

    def test_invalid_seed_length_rejected(self) -> None:
        """Seeds that are not 32 bytes should raise ValueError."""
        with pytest.raises(ValueError, match="32 bytes"):
            EcdhKeyPair(seed=b"\x00" * 16)

    def test_invalid_peer_key_length_rejected(self) -> None:
        """Peer public keys that are not 32 bytes should raise ValueError."""
        kp = EcdhKeyPair()
        with pytest.raises(ValueError, match="32 bytes"):
            kp.compute_shared_secret(b"\x00" * 16)

    def test_different_keys_produce_different_secrets(self) -> None:
        """Different key pairs should (with overwhelming probability) yield
        different shared secrets with a third party."""
        alice = EcdhKeyPair()
        bob = EcdhKeyPair()
        carol = EcdhKeyPair()

        secret_ab = alice.compute_shared_secret(bob.public_key_bytes)
        secret_ac = alice.compute_shared_secret(carol.public_key_bytes)

        assert secret_ab != secret_ac
