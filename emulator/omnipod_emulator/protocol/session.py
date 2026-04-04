"""
Protocol session orchestrator.

Manages the complete connection lifecycle between the phone and the pod
emulator:

    DISCONNECTED → INIT_RECEIVED → PAIRING → AUTHENTICATING → ACTIVE

Each phase routes incoming BLE messages to the correct handler:

    - INIT_RECEIVED:   Accept the 0x06 init command and begin pairing.
    - PAIRING:         Exchange ECDH keys and confirmation values.
    - AUTHENTICATING:  Perform EAP-AKA mutual authentication.
    - ACTIVE:          Decrypt → parse TWICommand → dispatch text RHP →
                       format text RHP → wrap TWICommand → encrypt.

This is the single entry point called by the BLE server for every CMD
characteristic write.

Reference: OMNIPOD5_CONNECTION_PROTOCOL.md, Sections 1-7
Reference: POD_EMULATOR_BLUEPRINT.md
"""

from __future__ import annotations

import enum
import logging
import os
import struct
from collections.abc import Callable

from omnipod_emulator.crypto import aes_ccm
from omnipod_emulator.crypto.crc16 import crc16_ccitt
from omnipod_emulator.crypto.eap_aka import EapAkaSlave, EapAkaState, SessionKeys
from omnipod_emulator.pod.state import PodState
from omnipod_emulator.protocol.pairing import PairingStateMachine
from omnipod_emulator.protocol.rhp_dispatcher import RhpTextDispatcher
from omnipod_emulator.protocol.rhp_handlers import RhpHandlers
from omnipod_emulator.protocol.twi_command import MessageType, TWICommand

logger = logging.getLogger(__name__)

# ---------------------------------------------------------------------------
# Protocol message types (first byte of CMD writes)
# ---------------------------------------------------------------------------

MSG_INIT = 0x06
"""Connection init message from the phone."""

MSG_PAIRING = 0x10
"""Pairing data exchange messages."""

MSG_EAP = 0x20
"""EAP-AKA authentication messages."""

MSG_ENCRYPTED = 0x30
"""Encrypted RHP command (post-authentication)."""

# ---------------------------------------------------------------------------
# Pairing sub-message types (second byte within MSG_PAIRING)
# ---------------------------------------------------------------------------

PAIR_PHONE_KEY_NONCE = 0x01
"""Phone sends its public key + nonce."""

PAIR_PHONE_CONF = 0x02
"""Phone sends its confirmation value."""

# ---------------------------------------------------------------------------
# Nonce management for AES-CCM
# ---------------------------------------------------------------------------

class SessionPhase(enum.Enum):
    """Connection lifecycle phases."""

    DISCONNECTED = "disconnected"
    INIT_RECEIVED = "init_received"
    PAIRING = "pairing"
    AUTHENTICATING = "authenticating"
    ACTIVE = "active"
    FAILED = "failed"


class ProtocolSession:
    """
    Orchestrates the full phone ↔ pod protocol flow.

    This is the single entry point for all incoming BLE CMD writes.
    It manages pairing, authentication, encryption, and command dispatch.

    Args:
        pod_state:   The simulated pod state.
        firmware_id: This pod's firmware/identity bytes (6 bytes).
        ecdh_seed:   Optional deterministic seed for the ECDH key pair
                     (for reproducible testing).
    """

    def __init__(
        self,
        pod_state: PodState,
        firmware_id: bytes,
        *,
        ecdh_seed: bytes | None = None,
        on_paired: Callable[[], None] | None = None,
    ) -> None:
        if len(firmware_id) != 6:
            raise ValueError(
                f"firmware_id must be 6 bytes, got {len(firmware_id)}"
            )

        self._pod_state = pod_state
        self._firmware_id = firmware_id
        self._ecdh_seed = ecdh_seed
        self._on_paired = on_paired

        self._phase = SessionPhase.DISCONNECTED
        self._controller_id: bytes = b""

        # Pairing
        self._pairing: PairingStateMachine | None = None
        self._ltk: bytes | None = None  # Persisted across disconnections

        # Authentication
        self._eap_aka: EapAkaSlave | None = None
        self._session_keys: SessionKeys | None = None

        # Encryption nonce counters (13 bytes each, incrementing)
        self._tx_nonce_counter: int = 0
        self._rx_nonce_counter: int = 0

        # Text RHP command dispatch (TWICommand + text RHP stack)
        self._rhp_dispatcher = RhpTextDispatcher()
        self._rhp_handlers = RhpHandlers(
            pod_state, self._rhp_dispatcher,
            on_deactivate=self._on_pod_deactivated,
        )

        # TWICommand notification counter
        self._notification_number: int = 0

        logger.info(
            "Protocol session created: firmware_id=%s",
            firmware_id.hex(),
        )

    @property
    def phase(self) -> SessionPhase:
        """Current protocol phase."""
        return self._phase

    def on_message(self, data: bytes) -> bytes | None:
        """
        Process an incoming BLE CMD write.

        This is the single entry point called by the BLE server for every
        message received from the phone.

        Args:
            data: Raw bytes written to the CMD characteristic.

        Returns:
            Response bytes to send back via TpClassic notification,
            or None if no response is needed.
        """
        if len(data) < 1:
            logger.warning("Empty message received")
            return None

        msg_type = data[0]

        logger.info(
            "[BLE] RX: type=0x%02x, %d bytes, phase=%s",
            msg_type,
            len(data),
            self._phase.value,
        )
        logger.debug("[BLE] RX hex: %s", data.hex())

        try:
            if msg_type == MSG_INIT:
                result = self._handle_init(data)
            elif msg_type == MSG_PAIRING:
                result = self._handle_pairing(data)
            elif msg_type == MSG_EAP:
                result = self._handle_eap(data)
            elif msg_type == MSG_ENCRYPTED:
                result = self._handle_encrypted_command(data)
            else:
                logger.warning("Unknown message type: 0x%02x", msg_type)
                return None

            if result is not None:
                logger.debug("[BLE] TX hex: %s", result.hex())
            return result
        except Exception:
            logger.exception(
                "Error handling message type 0x%02x in phase %s",
                msg_type,
                self._phase.value,
            )
            self._phase = SessionPhase.FAILED
            return None

    # ------------------------------------------------------------------
    # Phase handlers
    # ------------------------------------------------------------------

    def _handle_init(self, data: bytes) -> bytes | None:
        """
        Handle the connection init message (0x06).

        Expected format:
            [0x06, 0x01, 0x04, controller_id(4 bytes)]

        If the pod already has a stored LTK (from a previous pairing),
        it skips pairing and transitions directly to AUTHENTICATING for
        EAP-AKA re-authentication with the existing key.

        Otherwise, it initializes a new pairing state machine and sends
        back its public key + nonce.
        """
        if len(data) < 7:
            logger.warning("Init message too short: %d bytes", len(data))
            return None

        # Validate init subfields: expected [0x06, 0x01, 0x04, controller_id(4)]
        if data[1] != 0x01 or data[2] != 0x04:
            logger.warning(
                "Unexpected init subfields: %02x %02x", data[1], data[2]
            )

        self._controller_id = data[3:7]

        logger.info(
            "Init received: controller_id=%s",
            self._controller_id.hex(),
        )

        # Reconnection: if we already have an LTK, skip straight to auth
        if self._ltk is not None:
            self._eap_aka = EapAkaSlave(ltk=self._ltk)
            self._phase = SessionPhase.AUTHENTICATING

            logger.info(
                "Reconnection: LTK exists, skipping pairing, "
                "phase -> AUTHENTICATING"
            )

            # Respond with a reconnection indicator so the phone knows
            # to skip pairing and send EAP-AKA directly.
            # Format: [MSG_PAIRING, 0x05] = already paired, proceed to auth
            return bytes([MSG_PAIRING, 0x05])

        # Fresh pairing — generate keys with default algorithm (Curve25519).
        # If the BLE path later sends SPS0 with a different algorithm
        # (e.g. P-256), _handle_sps0() will re-generate with the correct curve.
        self._pairing = PairingStateMachine(
            firmware_id=self._firmware_id,
            controller_id=self._controller_id,
            ecdh_seed=self._ecdh_seed,
        )

        pod_pub_key, pod_nonce = self._pairing.initialize()

        self._phase = SessionPhase.PAIRING

        # Respond with firmware ID, pod's public key, and nonce
        response = bytes([MSG_PAIRING]) + self._firmware_id + pod_pub_key + pod_nonce

        logger.info(
            "Init handled: phase -> PAIRING, sending pod pubkey + nonce (%d bytes)",
            len(response),
        )

        return response

    def _handle_pairing(self, data: bytes) -> bytes | None:
        """
        Handle pairing exchange messages.

        Sub-message routing based on second byte:
            0x01 -- Phone sends public key + nonce
            0x02 -- Phone sends confirmation value
        """
        if self._phase != SessionPhase.PAIRING:
            logger.warning(
                "Pairing message in wrong phase: %s", self._phase.value
            )
            return None

        if self._pairing is None:
            logger.error("Pairing state machine not initialized")
            return None

        if len(data) < 2:
            logger.warning("Pairing message too short")
            return None

        sub_type = data[1]

        if sub_type == PAIR_PHONE_KEY_NONCE:
            return self._handle_pairing_key_nonce(data[2:])
        elif sub_type == PAIR_PHONE_CONF:
            return self._handle_pairing_confirmation(data[2:])
        else:
            logger.warning("Unknown pairing sub-type: 0x%02x", sub_type)
            return None

    def _handle_pairing_key_nonce(self, payload: bytes) -> bytes | None:
        """
        Receive phone's public key + nonce, compute shared secret and
        confirmation, send back pod's confirmation.

        Expected payload: [phone_pub_key(32), phone_nonce(16)]
        """
        assert self._pairing is not None

        if len(payload) < 48:
            logger.warning(
                "Pairing key+nonce payload too short: %d bytes", len(payload)
            )
            return None

        phone_pub_key = payload[:32]
        phone_nonce = payload[32:48]

        # Store peer data
        self._pairing.set_peer_data(phone_pub_key, phone_nonce)

        # Derive keys and compute confirmation
        pod_conf = self._pairing.derive_keys_and_compute_confirmation()

        # Respond with pod's confirmation value
        # Format: [MSG_PAIRING, PAIR_PHONE_CONF response marker, pod_conf(16)]
        response = bytes([MSG_PAIRING, 0x03]) + pod_conf

        logger.info(
            "Pairing: peer data set, keys derived, sending pod confirmation (%d bytes)",
            len(response),
        )

        return response

    def _handle_pairing_confirmation(self, payload: bytes) -> bytes | None:
        """
        Receive and verify the phone's confirmation value.
        If valid, save the LTK and transition to authenticating.

        Expected payload: [phone_conf(16)]
        """
        assert self._pairing is not None

        if len(payload) < 16:
            logger.warning(
                "Pairing confirmation payload too short: %d bytes",
                len(payload),
            )
            return None

        phone_conf = payload[:16]

        # Verify
        if not self._pairing.verify_peer_confirmation(phone_conf):
            logger.error("Pairing FAILED: phone confirmation invalid")
            self._phase = SessionPhase.FAILED
            # Send failure indicator
            return bytes([MSG_PAIRING, 0xFF])

        # Save LTK (persisted across disconnections for reconnection)
        sim_profile = self._pairing.save_ltk()
        ltk = sim_profile.get_ltk()
        self._ltk = ltk

        logger.info("Pairing COMPLETE: LTK established and stored")

        # Notify BLE server to switch to paired advertising UUIDs
        if self._on_paired is not None:
            self._on_paired()

        # Initialize EAP-AKA with the derived LTK
        self._eap_aka = EapAkaSlave(ltk=ltk)
        self._phase = SessionPhase.AUTHENTICATING

        # Send pairing success indicator
        # Format: [MSG_PAIRING, 0x04] = pairing complete
        response = bytes([MSG_PAIRING, 0x04])

        logger.info("Phase -> AUTHENTICATING, waiting for EAP-AKA challenge")

        return response

    def _handle_eap(self, data: bytes) -> bytes | None:
        """
        Handle EAP-AKA authentication messages.

        The EAP message payload starts after the MSG_EAP type byte.
        """
        if self._phase != SessionPhase.AUTHENTICATING:
            logger.warning(
                "EAP message in wrong phase: %s", self._phase.value
            )
            return None

        if self._eap_aka is None:
            logger.error("EAP-AKA handler not initialized")
            return None

        eap_payload = data[1:]

        eap_response = self._eap_aka.process_message(eap_payload)

        # Check if authentication completed
        if self._eap_aka.state == EapAkaState.AUTHENTICATED:
            self._session_keys = self._eap_aka.session_keys
            self._tx_nonce_counter = 0
            self._rx_nonce_counter = 0
            self._phase = SessionPhase.ACTIVE

            logger.info(
                "EAP-AKA AUTHENTICATED: session keys established, "
                "phase -> ACTIVE"
            )

        elif self._eap_aka.state == EapAkaState.FAILED:
            logger.error("EAP-AKA authentication FAILED")
            self._phase = SessionPhase.FAILED

        # Wrap EAP response with message type prefix
        if eap_response is not None:
            return bytes([MSG_EAP]) + eap_response

        return None

    def _handle_encrypted_command(self, data: bytes) -> bytes | None:
        """
        Handle an encrypted application message.

        Protocol stack (documented layer order):
            1. Decrypt AES-CCM
            2. Parse TWICommand frame
            3. Extract UTF-8 text RHP from commandBytes
            4. Dispatch text RHP to handlers
            5. Format text RHP response
            6. Wrap in TWICommand frame
            7. Encrypt AES-CCM

        Expected wire format:
            [MSG_ENCRYPTED, nonce_suffix(4), ciphertext...]

        The full nonce is constructed as:
            rx_nonce_counter(8, big-endian) || nonce_suffix(4) || 0x00
        """
        if self._phase != SessionPhase.ACTIVE:
            logger.warning(
                "Encrypted command in wrong phase: %s", self._phase.value
            )
            return None

        if self._session_keys is None or not self._session_keys.encryption_key:
            logger.error("No session keys available for decryption")
            return None

        if len(data) < 6:
            logger.warning(
                "Encrypted message too short: %d bytes", len(data)
            )
            return None

        nonce_suffix = data[1:5]
        ciphertext = data[5:]

        # Step 1: Decrypt AES-CCM
        rx_nonce = self._build_nonce(self._rx_nonce_counter, nonce_suffix)
        self._rx_nonce_counter += 1

        try:
            plaintext = aes_ccm.decrypt(
                key=self._session_keys.encryption_key,
                ciphertext=ciphertext,
                nonce=rx_nonce,
            )
        except Exception:
            logger.exception("AES-CCM decryption failed")
            return None

        logger.debug("[TWI] Decrypt → plaintext: %s", plaintext.hex())

        # Step 2: Parse TWICommand frame
        twi_cmd: TWICommand | None = None
        try:
            twi_cmd = TWICommand.parse(plaintext)
            rhp_text = twi_cmd.command_bytes
        except ValueError:
            logger.warning(
                "TWICommand parse failed, falling back to raw UTF-8"
            )
            # Fallback: treat entire plaintext as UTF-8 RHP text
            # (for backward compatibility during migration)
            rhp_text = plaintext.decode("utf-8", errors="replace")

        if twi_cmd is not None:
            logger.debug(
                "[TWI] Parsed: id=%d, last=%s, nn=%d",
                twi_cmd.command_id,
                twi_cmd.last_message,
                twi_cmd.notification_number,
            )

        # Step 3-4: Dispatch text RHP
        logger.info("[RHP] ← %s", rhp_text)
        rhp_response_text = self._rhp_dispatcher.dispatch(rhp_text)
        logger.info("[RHP] → %s", rhp_response_text)

        # Step 5-6: Wrap response in TWICommand
        response_twi = TWICommand(
            command_bytes=rhp_response_text,
            command_id=twi_cmd.command_id if twi_cmd else 0,
            last_message=True,
            message_type=MessageType.ENCRYPTED,
            notification_number=self._notification_number,
        )
        self._notification_number += 1

        response_bytes = response_twi.serialize()
        logger.debug("[TWI] Encrypt → ciphertext: %d bytes", len(response_bytes))

        # Step 7: Encrypt AES-CCM
        tx_nonce_suffix = os.urandom(4)
        tx_nonce = self._build_nonce(self._tx_nonce_counter, tx_nonce_suffix)
        self._tx_nonce_counter += 1

        encrypted_response = aes_ccm.encrypt(
            key=self._session_keys.encryption_key,
            plaintext=response_bytes,
            nonce=tx_nonce,
        )

        # Wire format: [MSG_ENCRYPTED, tx_nonce_suffix(4), ciphertext...]
        result = bytes([MSG_ENCRYPTED]) + tx_nonce_suffix + encrypted_response

        logger.debug("[TWI] TX encrypted: %s", result.hex())

        return result

    # ------------------------------------------------------------------
    # Helpers
    # ------------------------------------------------------------------

    @staticmethod
    def _build_nonce(counter: int, suffix: bytes) -> bytes:
        """
        Build a 13-byte AES-CCM nonce.

        Format: counter(8 bytes, big-endian) || suffix(4 bytes) || 0x00

        Args:
            counter: Monotonic message counter.
            suffix:  4-byte nonce suffix from the message.

        Returns:
            13-byte nonce.
        """
        return struct.pack(">Q", counter) + suffix[:4] + b"\x00"

    def _on_pod_deactivated(self) -> None:
        """
        Called by the RHP deactivation handler to clear LTK.

        Only clears the LTK so the next connection requires a fresh pairing.
        Session keys and phase are preserved so the deactivation response
        can still be encrypted and sent back. Full cleanup happens on the
        next disconnect or init.
        """
        logger.info("Pod deactivated: clearing LTK for fresh pairing")
        self._ltk = None

    # ------------------------------------------------------------------
    # TWI command handler (SIM Profile setup)
    # ------------------------------------------------------------------

    def on_twi_commands(
        self, commands: list[tuple[str, bytes]]
    ) -> bytes | None:
        """
        Handle TWI-framed SIM Profile commands from the phone.

        Called by the transport layer after stripping the TWI header.
        Commands are ``(name, value)`` tuples parsed from the binary
        RHP payload (e.g. ``("SP1", b"\\x00\\x00\\xc9\\x92")``).

        Returns the binary RHP response payload to be wrapped back in
        a TWI frame, or ``None`` if no response.
        """
        responses: list[bytes] = []

        for name, value in commands:
            if name == "SP1":
                resp = self._handle_sp1(value)
            elif name == "SP2":
                resp = self._handle_sp2(value)
            elif name == "SPS0":
                resp = self._handle_sps0(value)
            else:
                logger.warning(
                    "Unknown TWI command: %s (%d bytes)", name, len(value)
                )
                resp = None

            if resp is not None:
                responses.append(resp)

        if not responses:
            return None
        return b",".join(responses)

    def _handle_sp1(self, value: bytes) -> bytes | None:
        """
        SP1: Controller ID registration.

        Value: 4-byte controller ID (BE) with peripheral index in low 2 bits.
        """
        if len(value) < 4:
            logger.warning("SP1 value too short: %d bytes", len(value))
            return None

        ctrl_id = int.from_bytes(value[0:4], "big")
        peripheral_index = ctrl_id & 0x03
        self._controller_id = value[0:4]

        logger.info(
            "SP1: controller_id=0x%08x, peripheral_index=%d",
            ctrl_id, peripheral_index,
        )

        # Acknowledge with success
        return b"ESSP1.0=0"

    def _handle_sp2(self, value: bytes) -> bytes | None:
        """
        SP2: Device capabilities and AKA parameters.

        Value: controller_id(4) + pad(1) + device_type(1) + features(1)
               + algo_mode(1) + pad(1) + crc16(2)
        """
        if len(value) < 9:
            logger.warning("SP2 value too short: %d bytes", len(value))
            return None

        ctrl_id = int.from_bytes(value[0:4], "big")
        device_type = value[5] if len(value) > 5 else 0
        feature_flags = value[6] if len(value) > 6 else 0
        algorithm_mode = value[7] if len(value) > 7 else 0

        logger.info(
            "SP2: controller_id=0x%08x, device_type=0x%02x, "
            "features=0x%02x, algo_mode=0x%02x, raw=%s",
            ctrl_id, device_type, feature_flags, algorithm_mode,
            value.hex(),
        )

        # Acknowledge with success
        return b"ESSP2.0=0"

    def _handle_sps0(self, value: bytes) -> bytes | None:
        """
        SPS0: Algorithm negotiation.

        Value: selector(2) + algorithm_byte(1) + crc16(2)

        Algorithm bytes (from EnumC3934an.java):
            0x00 = Curve25519, no password, no cert
            0x08 = Curve25519, no password, with cert
            0x01 = P-256, no password, no cert
            0x09 = P-256, no password, with cert
            0x0D = P-256, password, with cert

        Responds with SPS0 acceptance echoing the algorithm byte.
        """
        if len(value) < 3:
            logger.warning("SPS0 value too short: %d bytes", len(value))
            return None

        selector = int.from_bytes(value[0:2], "big")
        algorithm_byte = value[2]

        algo_names = {
            0x00: "CURVE25519_NO_PWD_NO_CERT",
            0x08: "CURVE25519_NO_PWD_CERT",
            0x01: "P256_NO_PWD_NO_CERT",
            0x05: "P256_PWD_NO_CERT",
            0x09: "P256_NO_PWD_CERT",
            0x0D: "P256_PWD_CERT",
        }
        algo_name = algo_names.get(algorithm_byte, f"UNKNOWN(0x{algorithm_byte:02x})")

        logger.info(
            "SPS0: selector=%d, algorithm=0x%02x (%s)",
            selector, algorithm_byte, algo_name,
        )

        # Re-generate keys with the negotiated algorithm.
        # The init handler already generated Curve25519 keys (default),
        # but SPS0 may request P-256 instead.
        if self._pairing is not None:
            from omnipod_emulator.crypto.ecdh import is_p256
            from omnipod_emulator.protocol.pairing import PairingState

            needs_regen = (
                is_p256(algorithm_byte)
                != is_p256(self._pairing._algorithm)
            )
            if needs_regen:
                self._pairing._algorithm = algorithm_byte
                self._pairing._state = PairingState.IDLE
                self._pairing.initialize()
                logger.info(
                    "ECDH keys RE-generated with algorithm 0x%02x (%s)",
                    algorithm_byte, algo_name,
                )
            else:
                self._pairing._algorithm = algorithm_byte
                logger.info(
                    "Algorithm 0x%02x (%s) matches existing keys",
                    algorithm_byte, algo_name,
                )

        # Build SPS0 acceptance response:
        # "SPS0=" + \x00 + length + [selector(2), algorithm(1)] + CRC-16
        inner = value[0:3]  # Echo selector + algorithm
        crc = crc16_ccitt(inner)
        response_value = inner + crc.to_bytes(2, "big")
        response = b"SPS0=" + bytes([0x00, len(response_value)]) + response_value

        logger.info(
            "SPS0 response (ACCEPTED): %s", response.hex(),
        )

        # After SPS0, also send SPS1 with pod's key material
        sps1 = self._build_sps1()
        if sps1 is not None:
            return response + b"," + sps1
        return response

    def _build_sps1(self) -> bytes | None:
        """
        Build SPS1 response: pod's ECDH nonce + public key.

        Reuses the key material already generated during init handling.
        """
        if self._pairing is None or self._pairing._key_pair is None:
            logger.warning("SPS1: no pairing state machine or keys not generated")
            return None

        pub_key = self._pairing._key_pair.public_key_bytes
        nonce = self._pairing._key_pair.nonce

        # SPS1 = nonce(16) + pubkey(32) = 48 bytes
        sps1_value = nonce + pub_key
        response = b"SPS1=" + bytes([0x00, len(sps1_value)]) + sps1_value

        logger.info(
            "SPS1 response: nonce=%d bytes, pubkey=%d bytes",
            len(nonce), len(pub_key),
        )

        return response

    def on_disconnect(self) -> None:
        """
        Handle BLE disconnection.

        Resets the protocol phase and session keys but preserves the LTK
        for reconnection. On the next init message, the pod will skip
        pairing and go directly to EAP-AKA re-authentication.
        """
        logger.info(
            "Disconnection: phase was %s, resetting to DISCONNECTED",
            self._phase.value,
        )

        # LTK (self._ltk) is intentionally preserved across disconnections
        self._phase = SessionPhase.DISCONNECTED
        self._session_keys = None
        self._tx_nonce_counter = 0
        self._rx_nonce_counter = 0
        self._notification_number = 0
        self._eap_aka = None

        if self._ltk is not None:
            logger.info("LTK preserved for reconnection")
