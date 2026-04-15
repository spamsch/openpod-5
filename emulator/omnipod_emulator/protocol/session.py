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

from omnipod_emulator import debug_ltk_store
from omnipod_emulator.crypto import aes_ccm
from omnipod_emulator.crypto.crc16 import crc16_ccitt
from omnipod_emulator.crypto.eap_aka import (
    EAP_SUCCESS,
    EapAkaSlave,
    EapAkaState,
    SessionKeys,
)
from omnipod_emulator.persistence import (
    PairingStateRecord,
    load_pairing_state,
    save_pairing_state,
)
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
        reject_cert_algorithms: bool = True,
        state_file: str | os.PathLike | None = None,
        key_id: str = "default",
    ) -> None:
        if len(firmware_id) != 6:
            raise ValueError(
                f"firmware_id must be 6 bytes, got {len(firmware_id)}"
            )

        self._pod_state = pod_state
        self._firmware_id = firmware_id
        self._ecdh_seed = ecdh_seed
        self._on_paired = on_paired
        self._reject_cert_algorithms = reject_cert_algorithms
        self._state_file = state_file
        self._key_id = key_id

        self._phase = SessionPhase.DISCONNECTED
        self._controller_id: bytes = b""

        # Pairing
        self._pairing: PairingStateMachine | None = None
        self._ltk: bytes | None = None  # Persisted across disconnections

        # Authentication
        self._eap_aka: EapAkaSlave | None = None
        self._session_keys: SessionKeys | None = None

        # Pre-load persisted pairing state if a state file was provided.
        if state_file is not None:
            self._restore_persisted_state()

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

        # Reconnection: if the emulator already holds a persisted LTK
        # (loaded by _restore_persisted_state from pairing.json) we can
        # spin up EAP-AKA directly. The "dynamic reconnect LTK from
        # debug_ltk_store" case is handled later, inside
        # on_twi_session_message when the phone's AKA-Challenge actually
        # arrives — not here — because the dev-harness LTK push can lag
        # the phone's Init frame by 1–3 seconds and blocking this
        # handler on a barrier would stall the BLE event loop with the
        # phone still waiting on our pairing response.
        if self._ltk is not None:
            return self._begin_reconnect_with_ltk(
                self._ltk, source="persisted state file",
            )

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

        Expected payload: AES-CCM confirmation (ciphertext + 8-byte tag).
        """
        assert self._pairing is not None

        if len(payload) < 8:
            logger.warning(
                "Pairing confirmation payload too short: %d bytes",
                len(payload),
            )
            return None

        phone_conf = payload

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

        # Initialize EAP-AKA with the derived LTK. session_id keys the
        # dynamic LTK override store (see omnipod_emulator.debug_ltk_store)
        # so the dev-harness client can push the phone-side LTK in
        # before the first AKA-Challenge arrives.
        self._eap_aka = EapAkaSlave(
            ltk=ltk,
            session_id=self._current_pairing_session_id(),
        )
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
        rx_nonce = self._build_nonce(
            self._rx_nonce_counter, nonce_suffix, pod_receiving=True,
        )
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
        tx_nonce = self._build_nonce(
            self._tx_nonce_counter, tx_nonce_suffix, pod_receiving=False,
        )
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

    def _build_nonce(
        self, counter: int, suffix: bytes, *, pod_receiving: bool,
    ) -> bytes:
        """
        Build a 13-byte AES-CCM nonce.

        When IV prefixes are available (from AT_CUSTOM_IV exchange during
        EAP-AKA), the nonce follows the OmniBLE format::

            nonce_prefix(8) || sqn(5)

        where ``nonce_prefix = controller_iv(4) || node_iv(4)`` and
        ``sqn`` is 5 bytes of the counter with a direction bit:

        - bit 7 of sqn[0] = 0: pod receiving (controller → pod)
        - bit 7 of sqn[0] = 1: pod sending (pod → controller)

        Without IV prefixes (test mode), falls back to::

            counter(8) || suffix(4) || 0x00

        Args:
            counter:       Monotonic message counter.
            suffix:        4-byte nonce suffix from the message.
            pod_receiving: True if this nonce is for a message the pod
                           is receiving; False for messages the pod sends.
        """
        if (
            self._session_keys is not None
            and self._session_keys.nonce_prefix
        ):
            # OmniBLE-style: prefix(8) + sqn(5) with direction bit.
            prefix = self._session_keys.nonce_prefix
            # Extract 5 low bytes of the counter.
            sqn = struct.pack(">Q", counter)[3:8]
            sqn_bytes = bytearray(sqn)
            if pod_receiving:
                sqn_bytes[0] &= 0x7F  # clear high bit
            else:
                sqn_bytes[0] |= 0x80  # set high bit
            return prefix + bytes(sqn_bytes)

        # Fallback: legacy format for tests without IV exchange.
        return struct.pack(">Q", counter) + suffix[:4] + b"\x00"

    def _begin_reconnect_with_ltk(
        self, ltk: bytes, *, source: str,
    ) -> bytes:
        """
        Common reconnect body: install ``ltk`` on the session, spin up an
        EapAkaSlave primed for the reconnect's EAP-AKA flow, move to
        AUTHENTICATING, and return the ``MSG_PAIRING 0x05`` "already
        paired, proceed to auth" indicator the phone expects.

        ``source`` is free-form text used only in the log line so we
        can tell persisted-state reconnects apart from debug-store
        reconnects during live runs.
        """
        self._ltk = ltk
        # session_id stays None on reconnect — the dynamic override
        # barrier inside EapAkaSlave._handle_challenge is only relevant
        # for fresh pairings where a dev-harness-pushed LTK might need
        # to replace the emulator-derived one at challenge time.
        # Reconnect LTKs are already authoritative by the time we get
        # here.
        self._eap_aka = EapAkaSlave(ltk=ltk)
        self._phase = SessionPhase.AUTHENTICATING

        logger.info(
            "Reconnection: LTK available (%s), skipping pairing, "
            "phase -> AUTHENTICATING",
            source,
        )
        return bytes([MSG_PAIRING, 0x05])

    def _current_pairing_session_id(self) -> bytes | None:
        """
        Derive the 16-byte session id for the current fresh-pair attempt.

        Returns ``None`` when either ECDH public key is missing — for
        example on reconnect paths that reuse a persisted LTK without a
        fresh ECDH exchange. The EAP-AKA slave treats ``None`` as "no
        dynamic override possible for this session", which is the right
        behaviour for reconnects.
        """
        pairing = self._pairing
        if pairing is None:
            return None
        key_pair = getattr(pairing, "_key_pair", None)
        peer_pub = getattr(pairing, "_peer_public_key", b"")
        if key_pair is None or not peer_pub:
            return None
        pod_pub = getattr(key_pair, "public_key_bytes", b"")
        if not pod_pub:
            return None
        try:
            return debug_ltk_store.compute_session_id(pod_pub, peer_pub)
        except ValueError:
            return None

    # ------------------------------------------------------------------
    # Persistent pairing state (LTK across restarts)
    # ------------------------------------------------------------------

    def _restore_persisted_state(self) -> None:
        """
        Load LTK + identity from the configured state file and pre-init
        the EAP-AKA slave so the emulator can answer reconnect traffic
        without re-pairing.

        Called from __init__ when ``state_file`` is provided. A missing
        file is fine (logged and skipped). A malformed file raises so
        operators notice instead of silently starting unpaired.
        """
        if self._state_file is None:
            return
        try:
            record = load_pairing_state(self._state_file, key_id=self._key_id)
        except ValueError as e:
            logger.error("Failed to load persisted pairing state: %s", e)
            raise

        if record is None:
            return

        # Apply the persisted identity. firmware_id from the file overrides
        # the constructor default — the file is the authoritative identity
        # once it exists, so the same pod identity is presented across
        # restarts.
        self._ltk = record.ltk
        self._controller_id = record.controller_id
        self._firmware_id = record.firmware_id
        self._eap_aka = EapAkaSlave(ltk=record.ltk, sqn=record.sqn)
        # Stay DISCONNECTED until the BLE link is up; the first session
        # message will trigger the AUTHENTICATING-style handling.
        # We set the phase to AUTHENTICATING so on_twi_session_message
        # accepts the incoming EAP-AKA challenge immediately.
        self._phase = SessionPhase.AUTHENTICATING
        logger.info(
            "Restored pairing state: firmware=%s, controller=%s, "
            "key_id=%s — emulator is reconnect-ready",
            record.firmware_id.hex(),
            record.controller_id.hex(),
            record.key_id,
        )

    def _persist_pairing_state(self) -> None:
        """
        Write the current LTK + identity to the configured state file.

        No-op if no state file is configured. Called whenever the LTK
        is saved during a successful pairing (SPS2 handler) so the next
        emulator restart can answer reconnect traffic.
        """
        if self._state_file is None:
            return
        if self._ltk is None:
            logger.warning("Cannot persist pairing state: LTK not set")
            return
        try:
            sqn = self._eap_aka.sqn if self._eap_aka is not None else b"\x00" * 6
            record = PairingStateRecord(
                ltk=self._ltk,
                controller_id=self._controller_id or b"\x00" * 4,
                firmware_id=self._firmware_id,
                sqn=sqn,
                key_id=self._key_id,
            )
            save_pairing_state(self._state_file, record)
        except (OSError, ValueError) as e:
            logger.error("Failed to persist pairing state: %s", e)

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

        Returns a single binary RHP response payload (comma-joined if
        multiple commands), or ``None`` if no response.  All responses
        go in one TWI frame so the response sequence number matches
        the request.
        """
        responses: list[bytes] = []

        for name, value in commands:
            if name == "SP1":
                resp = self._handle_sp1(value)
            elif name == "SP2":
                resp = self._handle_sp2(value)
            elif name == "SPS0":
                resp = self._handle_sps0(value)
            elif name == "SPS1":
                resp = self._handle_sps1(value)
            elif name.startswith("SPS2"):
                # "SPS2" → index 0, "SPS2.1" → index 1, etc.
                idx = (
                    int(name[5:]) if len(name) > 4 and name[4] == "."
                    else 0
                )
                resp = self._handle_sps2(value, round_index=idx)
            elif name == "SPS3":
                resp = self._handle_sps3()
            elif name == "SPS4":
                resp = self._handle_sps4(value)
            elif name == "SP0":
                resp = self._handle_sp0()
            elif name == "GP0":
                # GP0 accompanies SP0 in the "SP0,GP0" finalization.
                # No separate response needed — P0 is returned for SP0.
                resp = None
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

    # ------------------------------------------------------------------
    # TWI session-phase handler (EAP-AKA + encrypted RHP)
    # ------------------------------------------------------------------

    def on_twi_session_message(self, payload: bytes) -> bytes | None:
        """
        Handle a TWI-framed session-phase message from the PDM.

        Called by the transport layer when the request's destination
        pod_id is the assigned pod address (not the broadcast 0xFFFFFFFE
        used during pairing). The payload is the raw inner message:

          - In AUTHENTICATING phase: an EAP-Request/AKA-Challenge.
            Hand off to :class:`EapAkaSlave` and return the raw
            EAP-Response bytes.
          - In ACTIVE phase: an encrypted RHP frame. Hand off to
            :meth:`_handle_encrypted_command` (without the legacy
            MSG_ENCRYPTED prefix byte).

        Returns the raw response payload (no TWI header — transport adds
        that), or ``None`` if no response should be sent.
        """
        if self._eap_aka is None:
            # Recovery path: the phone is reconnecting with a cached
            # LTK and has jumped straight to EAP-AKA, but the emulator
            # has no persisted pairing.json and is still in PAIRING
            # phase with a PairingStateMachine it will never complete.
            # Before erroring out, consult the debug_ltk_store reconnect
            # slot — the external dev-harness client pushes the phone's
            # cached LTK there. The push has already had plenty of wall
            # time by the time a TWI session frame arrives (the phone's
            # reconnect TX typically lags Init by several seconds) so
            # even a tight barrier is almost always a no-block hit.
            #
            # TODO: the barrier below blocks the asyncio BLE handler
            # thread on a threading.Condition for up to 2 seconds.
            # That's fine today because bumble dispatches callbacks
            # single-threaded from the event loop and there's no other
            # work to do until the phone sends its next frame; revisit
            # with ``asyncio.to_thread`` if the top-level handler ever
            # goes async.
            recovered = debug_ltk_store.wait_for_ltk(
                debug_ltk_store.RECONNECT_SESSION_ID,
                timeout=2.0,
            )

            # Second check after the wait: if a concurrent caller (e.g.
            # two rapid TWI session frames) raced this branch and
            # already installed an EapAkaSlave via the same recovery
            # path, don't rebuild — fall through to normal dispatch
            # against the slave the other call created. This is cheap
            # insurance; bumble's dispatch is single-threaded today,
            # but the invariant is worth pinning down.
            if self._eap_aka is not None:
                logger.info(
                    "[ltk-store] reconnect recovery: another caller "
                    "already upgraded the session; falling through"
                )
            elif recovered is not None:
                logger.warning(
                    "[ltk-store] late reconnect LTK (%s) — upgrading "
                    "session from %s to AUTHENTICATING via recovery path",
                    recovered.hex(),
                    self._phase.value,
                )
                self._begin_reconnect_with_ltk(
                    recovered, source="debug_ltk_store reconnect slot (late)",
                )
                # Fall through to the normal AUTHENTICATING dispatch
                # below so the current frame is processed as an
                # AKA-Challenge immediately instead of bouncing.
            else:
                logger.error(
                    "TWI session message in phase %s but EAP-AKA not "
                    "initialised and no reconnect LTK in store "
                    "— pairing must complete first",
                    self._phase.value,
                )
                return None

        # Zero-length session payloads in ACTIVE phase are NOT transport
        # ACKs. Live tracing shows the phone's TWI parser has an
        # explicit length>0 guard before forwarding plaintext session
        # payloads into the Java EAP/session handler, so zero-length
        # non-ACK frames are parsed, logged, and silently dropped. Any
        # follow-up probe must therefore be a NON-empty plaintext
        # SessionEstablishmentMessage to cross that guard and clear the
        # phone's watchdog before native EAP parsing runs.
        #
        # Important reverse-engineering result: the v6.9.8 phone-side
        # master parser does NOT appear to accept inbound subtype 12
        # (`AKA-Notification`). Its accepted inbound subtypes while
        # waiting for the pod response are 1, 2, 4, and 14. Live
        # master-context traces also show a race: the first pod AT_RES
        # can arrive while the phone is still in state=1, then the phone
        # only flips to state=2 after its own challenge TX is
        # acknowledged. Replaying the exact cached subtype-1 response on
        # the subsequent empty frame is therefore the highest-value
        # probe.
        if not payload:
            if self._phase == SessionPhase.ACTIVE:
                # The phone's native stack sends this empty
                # SessionEstablishment frame immediately after the edge
                # where its own master FSM transitions to "waiting for
                # inbound". Replaying the exact AT_RES bytes we already
                # sent gives the phone a second chance to consume the
                # original challenge response in the correct state.
                replay = self._eap_aka.replay_last_challenge_response()
                if replay is None:
                    logger.warning(
                        "TWI session empty payload in ACTIVE but no cached "
                        "AKA-Response is available for replay"
                    )
                    return None
                logger.info(
                    "TWI session empty payload in ACTIVE — replaying cached "
                    "EAP-Response/AKA-Challenge (%d bytes): %s",
                    len(replay),
                    replay.hex(),
                )
                return replay
            logger.info(
                "TWI session empty payload (phase=%s) — no response",
                self._phase.value,
            )
            return None

        if self._phase == SessionPhase.AUTHENTICATING:
            try:
                eap_response = self._eap_aka.process_message(payload)
            except ValueError as e:
                logger.error("EAP-AKA process_message failed: %s", e)
                self._phase = SessionPhase.FAILED
                return None

            if self._eap_aka.state == EapAkaState.AUTHENTICATED:
                self._session_keys = self._eap_aka.session_keys
                self._tx_nonce_counter = 0
                self._rx_nonce_counter = 0
                self._phase = SessionPhase.ACTIVE
                logger.info(
                    "EAP-AKA AUTHENTICATED (TWI session): keys established, "
                    "phase -> ACTIVE"
                )
            elif self._eap_aka.state == EapAkaState.FAILED:
                logger.error("EAP-AKA authentication FAILED (TWI session)")
                self._phase = SessionPhase.FAILED

            return eap_response

        if self._phase == SessionPhase.ACTIVE:
            # EAP-Success in ACTIVE phase. Once the pod has emitted its
            # AKA-Response, the phone's TWI layer flips into
            # encryption/decryption mode (`En/De mode`). It then emits a
            # 4-byte ``[0x03, identifier, 0x00, 0x04]`` EAP-Success frame
            # purely as a "protocol close" marker and does NOT expect a
            # SessionEstablishmentMessage back — replaying the cached
            # AKA-Response here triggers a phone-side
            # ``Received in En/De mode while the message in invalid
            # state: SessionEstablishmentMessage`` → disconnect with
            # reason ``INVALID_MESSAGE_TYPE_IN_INVALID_MODE``.
            #
            # The right move is to silently swallow the EAP-Success.
            # The phone's send-side watchdog is already cleared by the
            # transport-level ACK of the AKA-Response, so dropping the
            # frame does not re-arm anything on the phone side. If the
            # phone still disconnects later, the cause is upstream (no
            # post-auth sync trigger) and must be investigated at the
            # higher layers — not papered over here.
            if (
                len(payload) == 4
                and payload[0] == EAP_SUCCESS
                and payload[2] == 0x00
                and payload[3] == 0x04
            ):
                logger.info(
                    "EAP-Success in ACTIVE (id=0x%02x) — silently dropping "
                    "(session already in En/De mode on phone side)",
                    payload[1],
                )
                return None

            # Post-EAP-AKA: encrypted RHP. _handle_encrypted_command expects
            # a leading MSG_ENCRYPTED byte in the legacy raw path; here we
            # synthesize that prefix so the existing handler can be reused.
            return self._handle_encrypted_command(
                bytes([MSG_ENCRYPTED]) + payload
            )

        logger.warning(
            "TWI session message in unexpected phase: %s",
            self._phase.value,
        )
        return None

    # P0 success payload observed in the controller's pairing-finalization flow.
    _P0_SUCCESS = b"\xa5"

    def _handle_sp0(self) -> bytes | None:
        """
        SP0: Pairing finalization.

        The controller sends ``SP0,GP0`` after successful confirmation
        exchange.  The pod responds with ``P0=<0xa5>`` to signal success.
        """
        from omnipod_emulator.protocol.sp_codec import encode_sp

        logger.info("SP0: pairing finalization, responding P0=0xa5")
        return encode_sp("P0=", self._P0_SUCCESS)

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

        # The binary MSG_INIT handler sets `_controller_id` from a
        # placeholder value in the v6.9.8 app (all zeros), so the pod's
        # PairingStateMachine was constructed with the wrong controller
        # ID.  SP1 carries the authoritative value — propagate it down
        # so later KDF calls use the same bytes both sides see.
        if self._pairing is not None and self._pairing._controller_id != value[0:4]:
            logger.info(
                "SP1: propagating controller_id %s → PairingStateMachine "
                "(was %s)",
                value[0:4].hex(),
                self._pairing._controller_id.hex(),
            )
            self._pairing._controller_id = value[0:4]

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

        The algo_mode byte tells the pod which curve the PDM will use
        for the subsequent SPS1 key exchange.  When the PDM skips SPS0
        (e.g. unregistered / forced X25519 path via Elmo hook), SP2 is
        the only place where the algorithm is announced, so we must
        re-initialize the pairing state machine here to match.
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

        # Re-generate ECDH keys with the algorithm announced by the PDM.
        # The init handler always generates Curve25519 keys by default,
        # but SP2 may announce P-256 (0x01) instead — re-init if needed.
        if self._pairing is not None:
            from omnipod_emulator.crypto.ecdh import is_p256
            from omnipod_emulator.protocol.pairing import PairingState

            needs_regen = (
                is_p256(algorithm_mode)
                != is_p256(self._pairing._algorithm)
            )
            if needs_regen:
                self._pairing._algorithm = algorithm_mode
                self._pairing._state = PairingState.IDLE
                self._pairing.initialize()
                logger.info(
                    "SP2: ECDH keys RE-generated with algorithm 0x%02x "
                    "(%s) — %d byte pubkey",
                    algorithm_mode,
                    "P-256" if is_p256(algorithm_mode) else "X25519",
                    len(self._pairing._key_pair.public_key_bytes)
                    if self._pairing._key_pair else 0,
                )
            else:
                self._pairing._algorithm = algorithm_mode

        # Acknowledge with success
        return b"ESSP2.0=0"

    def _handle_sps3(self) -> bytes | None:
        """
        SPS3: Pairing ready signal.

        The PDM sends this plain-text command after SP1/SP2 to signal
        it is ready to begin the ECDH key exchange.  The pod acknowledges
        and the PDM then sends SPS0 with the algorithm negotiation.

        This is a plain-text command (no ``=`` suffix, no binary value).
        """
        logger.info("SPS3: Pairing ready signal received — acknowledging")
        return b"ESSPS3.0=0"

    # Algorithms that require certificates or passwords — the emulator
    # implements neither. The algorithm byte is a 3-bit bitmap seen in
    # the controller's SPS0 negotiation: bit 0 = curve (0 X25519 / 1 P-256), bit 2 =
    # password required, bit 3 = certificate required.  We accept
    # 0x00 (X25519 plain) and 0x01 (P-256 plain); everything else with
    # bit 2 or bit 3 set ends up in this rejection set.
    _UNSUPPORTED_AUTH_ALGORITHMS = {
        0x05,  # P-256 + password + no cert
        0x08,  # X25519 + no password + cert
        0x09,  # P-256 + no password + cert
        0x0D,  # P-256 + password + cert
    }

    # SPS0 response status codes observed on the wire.
    _SPS0_ACCEPTED = 0x00
    _SPS0_ALGO_NOT_SUPPORTED = 0x02

    def _handle_sps0(self, value: bytes) -> bytes | None:
        """
        SPS0: Algorithm negotiation.

        Request value: version(1) + selector(1) + algorithm(1) + CRC-16(2)
        Response value: version(1) + status(1)  + algorithm(1) + CRC-16(2)

        Algorithm bytes used in SPS0 negotiation:
            0x00 = Curve25519, no password, no cert
            0x08 = Curve25519, no password, with cert
            0x01 = P-256, no password, no cert
            0x09 = P-256, no password, with cert
            0x0D = P-256, password, with cert

        Certificate algorithms (0x08, 0x09, 0x0D) are rejected when
        ``reject_cert_algorithms`` is True, pushing the PDM toward
        non-certificate pairing (0x00 or 0x01).
        """
        if len(value) < 3:
            logger.warning("SPS0 value too short: %d bytes", len(value))
            return None

        version = value[0]
        selector = value[1]
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
            "SPS0: version=%d, selector=%d, algorithm=0x%02x (%s), raw=%s",
            version, selector, algorithm_byte, algo_name, value.hex(),
        )

        # Reject certificate-bearing *and* password-bearing algorithms
        # if configured.  The ``_reject_cert_algorithms`` flag name is
        # slightly historical — it actually gates the
        # ``_UNSUPPORTED_AUTH_ALGORITHMS`` set which covers both cert
        # paths (bit 3) and password paths (bit 2).  See the comment
        # on the set definition for the bit layout.
        if (
            self._reject_cert_algorithms
            and algorithm_byte in self._UNSUPPORTED_AUTH_ALGORITHMS
        ):
            logger.warning(
                "SPS0: REJECTING unsupported auth algorithm 0x%02x (%s) "
                "— emulator does not implement certificate or password "
                "pairing paths. Sending status=0x%02x "
                "(REQUESTED_ALGORITHM_NOT_SUPPORTED). PDM should retry "
                "with a plain non-cert non-password algorithm (0x00 or "
                "0x01), or disconnect if it has no fallback.",
                algorithm_byte, algo_name, self._SPS0_ALGO_NOT_SUPPORTED,
            )
            return self._build_sps0_response(
                value[0], self._SPS0_ALGO_NOT_SUPPORTED, algorithm_byte,
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

        return self._build_sps0_response(
            value[0], self._SPS0_ACCEPTED, algorithm_byte,
        )

    def _build_sps0_response(
        self, version: int, status: int, algorithm: int,
    ) -> bytes:
        """
        Build an SPS0 response frame.

        Format: ``SPS0=`` + 2-byte-BE-length + version(1) + status(1) +
        algorithm(1) + CRC-16(2).
        """
        from omnipod_emulator.protocol.sp_codec import encode_sp

        inner = bytes([version, status, algorithm])
        crc = crc16_ccitt(inner)
        response_value = inner + crc.to_bytes(2, "big")
        response = encode_sp("SPS0=", response_value)

        status_label = (
            "ACCEPTED" if status == self._SPS0_ACCEPTED
            else f"REJECTED(0x{status:02x})"
        )
        logger.info(
            "SPS0 response (%s): %s", status_label, response.hex(),
        )

        return response

    def _handle_sps1(self, value: bytes) -> bytes | None:
        """
        SPS1: PDM's ECDH public key + nonce.

        The PDM sends its key material after receiving the SPS0 acceptance.
        We store the peer's key/nonce and respond with the pod's own
        key material.

        Value format: pubkey(64 for P-256, 32 for X25519) + nonce(16)
        """
        if self._pairing is None or self._pairing._key_pair is None:
            logger.warning("SPS1: no pairing state or keys")
            return None

        pod_pub_key_len = len(self._pairing._key_pair.public_key_bytes)
        nonce_len = 16
        expected = pod_pub_key_len + nonce_len

        if len(value) < expected:
            logger.warning(
                "SPS1 value too short: %d bytes (expected %d)",
                len(value), expected,
            )
            return None

        peer_pubkey = value[0:pod_pub_key_len]
        peer_nonce = value[pod_pub_key_len:pod_pub_key_len + nonce_len]

        logger.info(
            "SPS1 RX: peer pubkey=%d bytes, peer nonce=%d bytes",
            len(peer_pubkey), len(peer_nonce),
        )

        # Store peer key material for shared secret derivation later.
        self._pairing.set_peer_data(peer_pubkey, peer_nonce)

        return self._build_sps1()

    def _build_sps1(self) -> bytes | None:
        """
        Build SPS1 response: pod's ECDH public key + nonce.

        Format matches the PDM's own SPS1: pubkey first, then nonce.
        """
        from omnipod_emulator.protocol.sp_codec import encode_sp

        if self._pairing is None or self._pairing._key_pair is None:
            logger.warning("SPS1: no pairing state machine or keys not generated")
            return None

        pub_key = self._pairing._key_pair.public_key_bytes
        nonce = self._pairing._key_pair.nonce

        sps1_value = pub_key + nonce
        response = encode_sp("SPS1=", sps1_value)

        logger.info(
            "SPS1 response: pubkey=%d bytes, nonce=%d bytes",
            len(pub_key), len(nonce),
        )

        return response

    def _handle_sps2(
        self, value: bytes, *, round_index: int = 0,
    ) -> bytes | None:
        """
        SPS2: Confirmation exchange (single-round, non-certificate).

        The PDM sends its AES-CCM confirmation value (ciphertext + 8-byte
        tag).  The pod verifies it, then responds with its own confirmation.

        On success, saves the LTK and transitions to AUTHENTICATING.

        Args:
            value:       The peer's confirmation bytes.
            round_index: Confirmation round index (0 for no-cert path).
                         Extracted from the command name: ``SPS2`` → 0,
                         ``SPS2.1`` → 1, etc.  The parser and response
                         preserve this index for wire compatibility even
                         though the no-cert path only uses index 0.

        NOTE: This handles a single confirmation round only.  The full
        protocol supports multi-round confirmation (SPS2/SPS4) with peer
        index tracking and nonce counter increments.  Multi-round would
        be needed for certificate-based algorithms.
        """
        from omnipod_emulator.protocol.pairing import PairingState
        from omnipod_emulator.protocol.sp_codec import encode_sp

        if self._pairing is None:
            logger.warning("SPS2: no pairing state")
            return None

        # Derive keys if not yet done (bridges the SPS1 → SPS2 gap).
        if self._pairing.state == PairingState.PEER_DATA_SET:
            self._pairing.derive_keys_and_compute_confirmation()

        if self._pairing.state != PairingState.KEYS_DERIVED:
            logger.warning(
                "SPS2: unexpected pairing state %s",
                self._pairing.state.value,
            )
            return None

        logger.info(
            "SPS2 RX: round_index=%d, peer confirmation %d bytes",
            round_index, len(value),
        )

        # --- DIAG: self-compute what the phone *should* have produced
        # using exactly the same inputs the emulator already holds.
        # If this mismatches `value`, the FGH-bypass side is sending
        # incorrect material; if it matches `value` but verify still
        # fails, the verify path itself is bugged.  Remove once the
        # FGH bypass is stable.
        try:
            from omnipod_emulator.protocol.pairing import (
                compute_controller_confirmation,
            )
            pk = self._pairing._key_pair
            expected = compute_controller_confirmation(
                controller_id=self._controller_id,
                firmware_id=self._firmware_id,
                controller_public_key=self._pairing._peer_public_key,
                controller_nonce=self._pairing._peer_nonce,
                pod_public_key=pk.public_key_bytes,
                pod_nonce=pk.nonce,
                shared_secret=self._pairing._shared_secret,
            )
            logger.info(
                "SPS2 DIAG: received conf = %s", value.hex(),
            )
            logger.info(
                "SPS2 DIAG: expected conf = %s", expected.hex(),
            )
            logger.info(
                "SPS2 DIAG: inputs — ctrl=%s fw=%s pod_pub=%s pod_nonce=%s "
                "phone_pub=%s phone_nonce=%s shared=%s",
                self._controller_id.hex(),
                self._firmware_id.hex(),
                pk.public_key_bytes.hex(),
                pk.nonce.hex(),
                self._pairing._peer_public_key.hex(),
                self._pairing._peer_nonce.hex(),
                self._pairing._shared_secret.hex(),
            )
            if value == expected:
                logger.info(
                    "SPS2 DIAG: bytes match — verify should pass"
                )
            else:
                logger.warning(
                    "SPS2 DIAG: bytes DIFFER — FGH bypass sent wrong material"
                )
        except Exception as _diag_exc:
            logger.exception("SPS2 DIAG failed: %s", _diag_exc)

        # Verify the PDM's confirmation.
        if not self._pairing.verify_peer_confirmation(value):
            logger.error("SPS2: peer confirmation verification FAILED")
            self._phase = SessionPhase.FAILED
            return None

        # Get pod's own confirmation to send back.
        pod_conf = self._pairing.get_confirmation_value()

        # Save LTK and transition to authentication.
        sim_profile = self._pairing.save_ltk()
        ltk = sim_profile.get_ltk()
        self._ltk = ltk

        logger.info("SPS2: pairing COMPLETE, LTK established")

        if self._on_paired is not None:
            self._on_paired()

        self._eap_aka = EapAkaSlave(
            ltk=ltk,
            session_id=self._current_pairing_session_id(),
        )
        self._phase = SessionPhase.AUTHENTICATING

        # Persist LTK + identity so reconnects after restart work.
        self._persist_pairing_state()

        # Build SPS2 response, preserving the round index.
        resp_name = f"SPS2.{round_index}=" if round_index > 0 else "SPS2="
        response = encode_sp(resp_name, pod_conf)

        logger.info(
            "SPS2 response: %d bytes, phase -> AUTHENTICATING",
            len(response),
        )

        return response

    def _handle_sps4(self, value: bytes) -> bytes | None:
        """
        SPS4: Terminal confirmation marker (peer_index=255 sentinel).

        The controller sends SPS4= as the final-round signal with the
        ``peer_index=255`` sentinel.

        For the simple no-cert path, this is treated as equivalent to an
        SPS2 verification.  No certificate semantics are attached.
        """
        from omnipod_emulator.protocol.pairing import PairingState
        from omnipod_emulator.protocol.sp_codec import encode_sp

        if self._pairing is None:
            logger.warning("SPS4: no pairing state")
            return None

        logger.info(
            "SPS4 RX (terminal round, peer_index=255): %d bytes",
            len(value),
        )

        # Derive keys if not yet done.
        if self._pairing.state == PairingState.PEER_DATA_SET:
            self._pairing.derive_keys_and_compute_confirmation()

        if self._pairing.state != PairingState.KEYS_DERIVED:
            logger.warning(
                "SPS4: unexpected pairing state %s",
                self._pairing.state.value,
            )
            return None

        # Verify the terminal confirmation value.
        if not self._pairing.verify_peer_confirmation(value):
            logger.error("SPS4: terminal confirmation verification FAILED")
            self._phase = SessionPhase.FAILED
            return None

        # Get pod's confirmation and save LTK.
        pod_conf = self._pairing.get_confirmation_value()

        sim_profile = self._pairing.save_ltk()
        ltk = sim_profile.get_ltk()
        self._ltk = ltk

        logger.info("SPS4: pairing COMPLETE (terminal round), LTK established")

        if self._on_paired is not None:
            self._on_paired()

        self._eap_aka = EapAkaSlave(
            ltk=ltk,
            session_id=self._current_pairing_session_id(),
        )
        self._phase = SessionPhase.AUTHENTICATING

        response = encode_sp("SPS4=", pod_conf)

        logger.info(
            "SPS4 response: %d bytes, phase -> AUTHENTICATING",
            len(response),
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
