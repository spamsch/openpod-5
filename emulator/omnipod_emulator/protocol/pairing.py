"""
Pod-side pairing state machine.

Implements the pod (role=1) side of the Omnipod 5 ECDH pairing protocol.
The pairing flow establishes a Long-Term Key (LTK) shared between the
phone and the pod, which is subsequently used as the MILENAGE subscriber
key (K) for EAP-AKA authentication.

Pod-side pairing flow:
    1. INIT          -- Generate ECDH key pair and nonce
    2. PEER_DATA_SET -- Receive phone's public key + nonce
    3. COMPUTING     -- Compute shared secret and derive keys via KDF
    4. CONF_SENT     -- Send confirmation value to phone
    5. CONF_VERIFIED -- Verify phone's confirmation value
    6. COMPLETE      -- LTK established and stored

The confirmation value is computed as AES-CMAC(conf_key, data) where data
is the concatenation of nonces and public keys, and conf_key is the first
16 bytes of the KDF output.

Reference: LTK_DERIVATION.md (full protocol)
Reference: OMNIPOD5_CONNECTION_PROTOCOL.md, Section 3c (ECDH pairing)
"""

from __future__ import annotations

import enum
import logging
import os
from dataclasses import dataclass

from cryptography.hazmat.primitives.ciphers import Cipher, algorithms, modes

from omnipod_emulator.crypto.ecdh import EcdhKeyPair
from omnipod_emulator.crypto.kdf import DerivedKeys, derive_keys

logger = logging.getLogger(__name__)


class PairingState(enum.Enum):
    """States of the pod-side pairing state machine."""

    IDLE = "idle"
    INITIALIZED = "initialized"
    PEER_DATA_SET = "peer_data_set"
    KEYS_DERIVED = "keys_derived"
    CONF_SENT = "conf_sent"
    CONF_VERIFIED = "conf_verified"
    COMPLETE = "complete"
    FAILED = "failed"


@dataclass
class SimProfile:
    """
    SIM profile storing the LTK in XOR-masked form.

    The profile is serialized as a 93-byte structure containing the
    masked LTK, pod firmware identity, controller ID, and XOR mask.
    """

    masked_ltk: bytes = b""
    firmware_id: bytes = b""
    controller_id: bytes = b""
    xor_mask: bytes = b""

    def get_ltk(self) -> bytes:
        """Recover the LTK by XORing the masked value with the mask."""
        if len(self.masked_ltk) != 16 or len(self.xor_mask) != 16:
            raise ValueError("SIM profile not fully populated")
        return bytes(a ^ b for a, b in zip(self.masked_ltk, self.xor_mask))

    def to_bytes(self) -> bytes:
        """Serialize to the 93-byte wire format."""
        buf = bytearray(93)
        buf[0x00:0x10] = self.masked_ltk
        buf[0x20:0x26] = self.firmware_id
        buf[0x36:0x3A] = self.controller_id
        buf[0x3D:0x4D] = self.xor_mask
        return bytes(buf)

    @classmethod
    def from_ltk(
        cls,
        ltk: bytes,
        firmware_id: bytes,
        controller_id: bytes,
    ) -> SimProfile:
        """Create a SIM profile by XOR-masking the LTK with a random pad."""
        xor_mask = os.urandom(16)
        masked_ltk = bytes(a ^ b for a, b in zip(ltk, xor_mask))
        return cls(
            masked_ltk=masked_ltk,
            firmware_id=firmware_id,
            controller_id=controller_id,
            xor_mask=xor_mask,
        )


class PairingStateMachine:
    """
    Pod-side pairing state machine.

    Manages the complete pairing flow from ECDH key generation through
    confirmation exchange to LTK storage.

    Args:
        firmware_id:   This pod's firmware/identity bytes (6 bytes).
        controller_id: The phone's controller ID (4 bytes).
        ecdh_seed:     Optional deterministic seed for the ECDH key pair
                       (32 bytes).  For testing only.
    """

    def __init__(
        self,
        firmware_id: bytes,
        controller_id: bytes,
        *,
        ecdh_seed: bytes | None = None,
        algorithm: int = 0x00,
    ) -> None:
        if len(firmware_id) != 6:
            raise ValueError(
                f"firmware_id must be 6 bytes, got {len(firmware_id)}"
            )
        if len(controller_id) != 4:
            raise ValueError(
                f"controller_id must be 4 bytes, got {len(controller_id)}"
            )

        self._firmware_id = firmware_id
        self._controller_id = controller_id
        self._ecdh_seed = ecdh_seed
        self._algorithm = algorithm

        self._state = PairingState.IDLE
        self._key_pair: EcdhKeyPair | None = None
        self._peer_public_key: bytes = b""
        self._peer_nonce: bytes = b""
        self._shared_secret: bytes = b""
        self._derived_keys: DerivedKeys | None = None
        self._my_conf_value: bytes = b""
        self._sim_profile: SimProfile | None = None

        logger.info(
            "Pairing state machine created: firmware_id=%d bytes, "
            "controller_id=%d bytes",
            len(firmware_id),
            len(controller_id),
        )

    @property
    def state(self) -> PairingState:
        """Current state of the pairing state machine."""
        return self._state

    @property
    def ltk(self) -> bytes | None:
        """The derived LTK, or None if pairing is not complete."""
        if self._derived_keys is not None:
            return self._derived_keys.ltk
        return None

    @property
    def sim_profile(self) -> SimProfile | None:
        """The stored SIM profile, or None if not yet saved."""
        return self._sim_profile

    def initialize(self) -> tuple[bytes, bytes]:
        """
        Step 1: Generate the pod's ECDH key pair and nonce.

        Returns:
            A tuple of (public_key_bytes, nonce) to send to the phone.

        Raises:
            RuntimeError: If the state machine is not IDLE.
        """
        if self._state != PairingState.IDLE:
            raise RuntimeError(
                f"Cannot initialize from state {self._state.value}"
            )

        key_pair = EcdhKeyPair(seed=self._ecdh_seed, algorithm=self._algorithm)
        self._key_pair = key_pair
        self._state = PairingState.INITIALIZED

        logger.info(
            "Pairing initialized: public_key=%d bytes, nonce=%d bytes",
            len(key_pair.public_key_bytes),
            len(key_pair.nonce),
        )

        return (key_pair.public_key_bytes, key_pair.nonce)

    def set_peer_data(
        self, peer_public_key: bytes, peer_nonce: bytes
    ) -> None:
        """
        Step 2: Store the phone's public key and nonce.

        Args:
            peer_public_key: The phone's 32-byte X25519 public key.
            peer_nonce:      The phone's 16-byte nonce.

        Raises:
            RuntimeError: If the state machine is not INITIALIZED.
            ValueError: If the inputs have unexpected lengths.
        """
        if self._state != PairingState.INITIALIZED:
            raise RuntimeError(
                f"Cannot set peer data from state {self._state.value}"
            )

        from omnipod_emulator.crypto.ecdh import is_p256

        expected_len = 64 if is_p256(self._algorithm) else 32
        if len(peer_public_key) != expected_len:
            raise ValueError(
                f"Peer public key must be {expected_len} bytes "
                f"(algorithm=0x{self._algorithm:02x}), "
                f"got {len(peer_public_key)}"
            )
        if len(peer_nonce) != 16:
            raise ValueError(
                f"Peer nonce must be 16 bytes, got {len(peer_nonce)}"
            )

        self._peer_public_key = peer_public_key
        self._peer_nonce = peer_nonce
        self._state = PairingState.PEER_DATA_SET

        logger.info(
            "Peer data stored: public_key=%d, nonce=%d bytes",
            len(peer_public_key), len(peer_nonce),
        )

    def derive_keys_and_compute_confirmation(self) -> bytes:
        """
        Steps 3-4: Compute shared secret, derive keys, compute confirmation.

        Performs:
            1. X25519(pod_private, phone_public) -> shared_secret
            2. KDF(firmware_id, controller_id, phone_pub, pod_pub, secret) -> keys
            3. AES-CMAC(conf_key, pod_nonce || phone_nonce || pod_pub || phone_pub)

        Returns:
            The 16-byte confirmation value to send to the phone.

        Raises:
            RuntimeError: If the state machine is not PEER_DATA_SET.
        """
        if self._state != PairingState.PEER_DATA_SET:
            raise RuntimeError(
                f"Cannot derive keys from state {self._state.value}"
            )
        assert self._key_pair is not None

        # 1. Compute ECDH shared secret
        self._shared_secret = self._key_pair.compute_shared_secret(
            self._peer_public_key
        )

        # 2. Derive confirmation key + LTK via KDF (pod is role=1)
        derived = derive_keys(
            firmware_id=self._firmware_id,
            controller_id=self._controller_id,
            pod_public_key=self._key_pair.public_key_bytes,
            phone_public_key=self._peer_public_key,
            shared_secret=self._shared_secret,
        )
        self._derived_keys = derived

        # 3. Compute confirmation value
        # The pod's confirmation is over: pod_nonce || phone_nonce || pod_pub || phone_pub
        conf_data = (
            self._key_pair.nonce
            + self._peer_nonce
            + self._key_pair.public_key_bytes
            + self._peer_public_key
        )
        self._my_conf_value = _aes_cmac(
            derived.confirmation_key, conf_data
        )

        self._state = PairingState.KEYS_DERIVED

        logger.info(
            "Keys derived and confirmation computed: conf_value=%d bytes",
            len(self._my_conf_value),
        )

        return self._my_conf_value

    def verify_peer_confirmation(self, peer_conf_value: bytes) -> bool:
        """
        Step 5: Verify the phone's confirmation value.

        The phone's confirmation is expected to be:
            AES-CMAC(conf_key, phone_nonce || pod_nonce || phone_pub || pod_pub)

        Note the reversed nonce/key order compared to the pod's confirmation.

        Args:
            peer_conf_value: The phone's 16-byte confirmation value.

        Returns:
            True if the confirmation matches, False otherwise.

        Raises:
            RuntimeError: If the state machine is not KEYS_DERIVED.
        """
        if self._state != PairingState.KEYS_DERIVED:
            raise RuntimeError(
                f"Cannot verify confirmation from state {self._state.value}"
            )
        assert self._derived_keys is not None
        assert self._key_pair is not None

        # The phone's confirmation data has opposite nonce/key ordering
        expected_data = (
            self._peer_nonce
            + self._key_pair.nonce
            + self._peer_public_key
            + self._key_pair.public_key_bytes
        )
        expected_conf = _aes_cmac(
            self._derived_keys.confirmation_key, expected_data
        )

        if peer_conf_value != expected_conf:
            logger.warning("Peer confirmation verification FAILED")
            self._state = PairingState.FAILED
            return False

        self._state = PairingState.CONF_VERIFIED
        logger.info("Peer confirmation verified successfully")
        return True

    def save_ltk(self) -> SimProfile:
        """
        Step 6: Store the LTK in a SIM profile.

        Creates a SIM profile with the LTK XOR-masked by a random pad,
        matching the 93-byte SIM profile wire format.

        Returns:
            The created ``SimProfile``.

        Raises:
            RuntimeError: If the state machine is not CONF_VERIFIED.
        """
        if self._state != PairingState.CONF_VERIFIED:
            raise RuntimeError(
                f"Cannot save LTK from state {self._state.value}"
            )
        assert self._derived_keys is not None

        self._sim_profile = SimProfile.from_ltk(
            ltk=self._derived_keys.ltk,
            firmware_id=self._firmware_id,
            controller_id=self._controller_id,
        )

        self._state = PairingState.COMPLETE

        logger.info("LTK saved to SIM profile: pairing COMPLETE")
        return self._sim_profile

    def get_confirmation_value(self) -> bytes:
        """Return the previously computed confirmation value."""
        if not self._my_conf_value:
            raise RuntimeError("Confirmation value not yet computed")
        return self._my_conf_value


# ---------------------------------------------------------------------------
# AES-CMAC (RFC 4493)
# ---------------------------------------------------------------------------


def _aes_cmac(key: bytes, data: bytes) -> bytes:
    """
    Compute AES-CMAC (RFC 4493) over *data* using *key*.

    This is a pure-Python implementation using AES-128-ECB as the
    underlying block cipher.

    Args:
        key:  16-byte AES key.
        data: Arbitrary-length data to MAC.

    Returns:
        16-byte CMAC.
    """
    if len(key) != 16:
        raise ValueError(f"CMAC key must be 16 bytes, got {len(key)}")

    # Step 1: Generate subkeys K1, K2
    cipher = Cipher(algorithms.AES(key), modes.ECB())

    # L = AES_K(0^128)
    encryptor = cipher.encryptor()
    L = encryptor.update(bytes(16)) + encryptor.finalize()

    K1 = _double(L)
    K2 = _double(K1)

    # Step 2: Determine number of blocks
    n = (len(data) + 15) // 16
    if n == 0:
        n = 1
        last_block_complete = False
    else:
        last_block_complete = (len(data) % 16 == 0)

    # Step 3: XOR last block with K1 or K2
    if last_block_complete:
        # M_last = M_n XOR K1
        last_block = bytes(
            a ^ b for a, b in zip(data[(n - 1) * 16 : n * 16], K1)
        )
    else:
        # Pad with 10*0 and XOR with K2
        padded = data[(n - 1) * 16 :] + b"\x80"
        padded += bytes(16 - len(padded))
        last_block = bytes(a ^ b for a, b in zip(padded, K2))

    # Step 4: CBC-MAC
    X = bytes(16)
    for i in range(n - 1):
        block = data[i * 16 : (i + 1) * 16]
        Y = bytes(a ^ b for a, b in zip(X, block))
        encryptor = cipher.encryptor()
        X = encryptor.update(Y) + encryptor.finalize()

    # Last block
    Y = bytes(a ^ b for a, b in zip(X, last_block))
    encryptor = cipher.encryptor()
    T = encryptor.update(Y) + encryptor.finalize()

    return T


def _double(block: bytes) -> bytes:
    """
    Double a 128-bit value in GF(2^128) with the CMAC polynomial.

    If the MSB is 1, shift left and XOR with 0x87.
    """
    val = int.from_bytes(block, "big")
    msb = val >> 127
    val = (val << 1) & ((1 << 128) - 1)
    if msb:
        val ^= 0x87
    return val.to_bytes(16, "big")
