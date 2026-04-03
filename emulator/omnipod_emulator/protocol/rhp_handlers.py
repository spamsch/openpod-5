"""
Text RHP command handlers for the pod emulator.

Maps documented RHP type/attribute pairs to pod state operations.
Replaces the binary opcode handlers in activation.py.

Documented RHP type/attribute mappings:

    Type 0 (Logger):
        L0.4  = SET logger enable
        L0.5  = GET logger unread count
        L0.8  = GET logger read data
        L0.9  = SET logger read request

    Type 3 (Algorithm):
        3.1   = SET target BG / GET target BG profile
        3.2   = SET TDI (total daily insulin)
        3.3   = SET correction factor
        3.4   = SET proxy data (CGM EGV)
        3.5   = GET IOB
        3.6   = GET algorithm status
        3.7   = SET EGV threshold
        3.8   = GET warnings
        3.9   = SET DIA (duration of insulin action)
        3.10  = SET hypo protect mode
        3.11  = GET QN data

    Type 4 (CGM):
        4.0   = SET CGM transmitter ID
        4.1   = SET CGM calibration
        4.2   = GET CGM bound state
        4.3   = GET last calibration

    Type 255 (System):
        255.2 = SET UTC time
        255.3 = GET error codes

    Activation commands use SET operations on specific type/attr pairs.
    Status queries use GET on type 3 and type 255.

Reference: ~/Projects/personal/omnipod-connector/docs/protocol/04-data-protocol.md
Reference: ~/Projects/personal/omnipod-connector/docs/protocol/05-pod-activation.md
Reference: ~/Projects/personal/omnipod-connector/docs/protocol/06-pod-operations.md
"""

from __future__ import annotations

import enum
import logging
import struct
import time
from typing import Callable

from omnipod_emulator.pod.state import PodState
from omnipod_emulator.version import version_string
from omnipod_emulator.protocol.rhp import (
    RhpAction,
    RhpErrorCode,
    RhpRequest,
    RhpResponse,
)
from omnipod_emulator.protocol.rhp_dispatcher import RhpTextDispatcher

logger = logging.getLogger(__name__)


# ---------------------------------------------------------------------------
# Activation state — expanded to match documented state machine
# ---------------------------------------------------------------------------

class ActivationState(enum.IntEnum):
    """
    Pod activation states matching the documented protocol.

    Phase 1 (pod initialization):
        READY → STARTED → GOT_VERSION → SET_UID
        → PROGRAMMED_LOW_RESERVOIR_ALERTS → REPROGRAMMED_LOC_ALERT
        → PRIMED → PROGRAMMED_EXPIRATION_ALERT → COMPLETED_PHASE_1

    Phase 2 (cannula insertion):
        PROGRAMMED_BASAL → PROGRAMMED_CANCEL_LOC_ALERTS
        → INSERTED_CANNULA → SECOND_PRIME_WAIT_FINISHED
        → ENABLED_ALGORITHM_AND_CGM → CGM_TRANSMITTER_ID
        → GOT_STATUS → ACTIVATED
    """

    READY = 0
    STARTED = 14
    GOT_VERSION = 1
    SET_UID = 2
    PROGRAMMED_LOW_RESERVOIR_ALERTS = 3
    REPROGRAMMED_LOC_ALERT = 4
    PRIMED = 5
    PROGRAMMED_EXPIRATION_ALERT = 6
    COMPLETED_PHASE_1 = 7
    PROGRAMMED_BASAL = 8
    PROGRAMMED_CANCEL_LOC_ALERTS = 9
    INSERTED_CANNULA = 10
    SECOND_PRIME_WAIT_FINISHED = 15
    ENABLED_ALGORITHM_AND_CGM = 16
    CGM_TRANSMITTER_ID = 11
    GOT_STATUS = 12
    ACTIVATED = 13
    FAILED = -1


# ---------------------------------------------------------------------------
# AID setup state
# ---------------------------------------------------------------------------

class AidSetupState(enum.IntEnum):
    """AID algorithm setup progression."""

    READY = 0
    UTC = 1
    TDI = 2
    TARGET_BG = 3
    CORRECTION_FACTOR = 4
    DIA = 5
    EGV_THRESHOLD = 6
    INSULIN_HISTORY = 7
    GET_STATUS = 8
    COMPLETE = 99


# ---------------------------------------------------------------------------
# RHP handler registration
# ---------------------------------------------------------------------------

class RhpHandlers:
    """
    Registers all text RHP handlers with the dispatcher.

    Connects documented type/attribute pairs to pod state operations.
    Manages activation state progression and AID setup.

    Args:
        pod_state:       The simulated pod state.
        dispatcher:      The text RHP dispatcher.
        on_deactivate:   Optional callback invoked when the pod is deactivated,
                         allowing the session to clear LTK for fresh pairing.
    """

    def __init__(
        self,
        pod_state: PodState,
        dispatcher: RhpTextDispatcher,
        on_deactivate: Callable[[], None] | None = None,
    ) -> None:
        self._pod = pod_state
        self._dispatcher = dispatcher
        self._on_deactivate = on_deactivate
        self._activation_state = ActivationState.READY
        self._aid_setup_state = AidSetupState.READY
        self._unique_id: bytes = b""

        # Track activation-specific alert programming steps
        self._low_reservoir_alerts_programmed = False
        self._loc_alert_reprogrammed = False
        self._expiration_alert_programmed = False
        self._cancel_loc_alerts_programmed = False
        self._second_prime_done = False

        # Stored settings
        self._utc_time: int = 0
        self._tdi: int = 0
        self._target_bg: int = 0
        self._correction_factor: int = 0
        self._dia: int = 0
        self._egv_threshold: int = 0

        self._register_handlers()

        logger.info(
            "RHP handlers initialized, activation_state=READY, "
            "aid_setup_state=READY"
        )

    @property
    def activation_state(self) -> ActivationState:
        return self._activation_state

    @property
    def aid_setup_state(self) -> AidSetupState:
        return self._aid_setup_state

    def _register_handlers(self) -> None:
        """Register all RHP handlers with the dispatcher."""
        d = self._dispatcher

        # Version
        d.register_version_handler(self._handle_get_version)

        # TWI GetVersion (0x07) — binary response as 0.0=<hex>
        d.register_get(0, 0, self._handle_twi_get_version)

        # Algorithm (type 3)
        d.register_get(3, 1, self._handle_get_target_bg)
        d.register_set(3, 1, self._handle_set_target_bg)
        d.register_set(3, 2, self._handle_set_tdi)
        d.register_set(3, 3, self._handle_set_correction_factor)
        d.register_set(3, 4, self._handle_set_cgm_egv)
        d.register_get(3, 5, self._handle_get_iob)
        d.register_get(3, 6, self._handle_get_algorithm_status)
        d.register_set(3, 7, self._handle_set_egv_threshold)
        d.register_get(3, 8, self._handle_get_warnings)
        d.register_set(3, 9, self._handle_set_dia)
        d.register_set(3, 10, self._handle_set_hypo_protect)
        d.register_get(3, 11, self._handle_get_qn)

        # CGM (type 4)
        d.register_set(4, 0, self._handle_set_cgm_tx_id)
        d.register_get(4, 2, self._handle_get_cgm_bound)

        # System (type 255)
        d.register_set(255, 2, self._handle_set_utc)
        d.register_get(255, 2, self._handle_get_utc)
        d.register_get(255, 3, self._handle_get_error_codes)

        # Logger (type 0, prefix L)
        d.register_set(0, 4, self._handle_set_logger_enable, prefix="L")
        d.register_get(0, 5, self._handle_get_logger_unread, prefix="L")

        # Activation-specific commands (emulator-specific type 1 convention):
        # TODO: Replace with exact wire-format sequences once captured from
        # real traffic or further decompilation. These type 1 mappings are
        # NOT wire-compatible with the real protocol.
        d.register_get(1, 6, self._handle_get_pod_status)        # Pod status (text)
        d.register_set(1, 0, self._handle_set_unique_id)       # Set pod UID
        d.register_set(1, 1, self._handle_program_alerts)      # Program alerts
        d.register_set(1, 2, self._handle_prime_pod)            # Prime pod
        d.register_set(1, 3, self._handle_program_basal)        # Program basal
        d.register_set(1, 4, self._handle_insert_cannula)       # Insert cannula
        d.register_set(1, 5, self._handle_enable_algorithm)     # Enable algorithm

        # Operations (type 2): bolus, stop program, temp basal, etc.
        # NOTE: type 2 is also History Buffer in the real protocol, but delivery
        # ops use a separate binary mechanism on real pods.  The emulator reuses
        # type 2 as a text-RHP convenience (history buffer stubs are disabled).
        d.register_set(2, 0, self._handle_send_bolus)           # Send bolus
        d.register_set(2, 1, self._handle_stop_program)         # Stop program
        d.register_set(2, 2, self._handle_send_temp_basal)      # Temp basal
        d.register_set(2, 3, self._handle_resume_insulin)       # Resume insulin
        d.register_set(2, 4, self._handle_send_beep)            # Beep
        d.register_set(2, 5, self._handle_silence_alert)        # Silence alert
        d.register_set(2, 6, self._handle_deactivate)             # Deactivate

        # AID Pod Status (documented G11.3 and G12.3)
        d.register_get(11, 3, self._handle_get_aid_status_g11)
        d.register_get(12, 3, self._handle_get_aid_status_g12)

        # History Buffer (type 2) — stub handlers
        # History buffer stubs disabled — type 2 is used for operations
        # (bolus, stop, temp basal) which takes priority.
        # d.register_get(2, 0, self._handle_get_history_index)
        # d.register_set(2, 0, self._handle_set_history_index)
        # d.register_get(2, 1, self._handle_get_history_data)

        # Missing RHP handler stubs:

        # Logger stubs (type 0, prefix L)
        d.register_get(0, 8, self._handle_get_logger_start_time, prefix="L")
        d.register_get(0, 9, self._handle_get_logger_logs, prefix="L")

        # Algorithm stub (type 3)
        d.register_get(3, 0, self._handle_get_algo_log_data)

        # CGM stubs (type 4)
        d.register_set(4, 1, self._handle_set_cgm_calib)
        d.register_get(4, 1, self._handle_get_cgm_calib)
        d.register_get(4, 3, self._handle_get_last_calib)

        # System stub (type 255)
        d.register_set(255, 3, self._handle_set_enable_error_code)

        # Engineering stubs (prefix R)
        d.register_set(0, 1, self._handle_set_eng_record_count, prefix="R")
        d.register_get(0, 1, self._handle_get_eng_record_count, prefix="R")
        d.register_set(0, 2, self._handle_set_eng_offset, prefix="R")
        d.register_get(0, 2, self._handle_get_eng_offset, prefix="R")
        d.register_get(0, 5, self._handle_get_eng_data, prefix="R")

    # ------------------------------------------------------------------
    # Version handler
    # ------------------------------------------------------------------

    def _handle_get_version(self, _req: RhpRequest) -> RhpResponse:
        """GV -> V<emulator_version>"""
        version = version_string()
        self._activation_state = ActivationState.GOT_VERSION
        logger.info("GV: version=%s, state -> GOT_VERSION", version)
        return RhpResponse.version(version)

    def _handle_twi_get_version(self, _req: RhpRequest) -> RhpResponse:
        """
        G0.0 -> TWI GetVersion (0x07) binary response.

        Returns a 23-byte structure that the app parses for pod identity:

            Byte  Field
            0     Command code (0x01)
            1     Length (0x15 = 21, payload after this byte)
            2     PI major version
            3     PI minor version
            4     PI interim version
            5     BLE major version
            6     BLE minor version
            7     BLE interim version
            8     Product ID
            9     Pod state (lower 4 bits)
            10-13 Lot number (uint32 BE)
            14-17 Sequence number / TID (uint32 BE)
            18    RSSI (bits 0-5) | receiver gain (bits 6-7)
            19-22 Unique ID echo (uint32 BE)
        """
        major, minor, patch = self._pod.firmware_version_tuple

        # Map activation state to pod progress state (byte 9, lower 4 bits).
        # These values match the pod progress enum the app validates.
        _PROGRESS_MAP = {
            ActivationState.READY: 2,              # FILLED
            ActivationState.STARTED: 2,             # FILLED
            ActivationState.GOT_VERSION: 2,         # FILLED
            ActivationState.SET_UID: 3,             # UID_SET
            ActivationState.PROGRAMMED_LOW_RESERVOIR_ALERTS: 3,
            ActivationState.REPROGRAMMED_LOC_ALERT: 3,
            ActivationState.PRIMED: 7,              # PRIMING
            ActivationState.PROGRAMMED_EXPIRATION_ALERT: 8,  # RUNNING_ABOVE_MIN_VOLUME
            ActivationState.COMPLETED_PHASE_1: 8,
            ActivationState.PROGRAMMED_BASAL: 6,    # BASAL_PROGRAM_RUNNING
            ActivationState.PROGRAMMED_CANCEL_LOC_ALERTS: 6,
            ActivationState.INSERTED_CANNULA: 4,    # ENGAGING_CLUTCH_DRIVE
            ActivationState.SECOND_PRIME_WAIT_FINISHED: 5,  # CLUTCH_DRIVE_ENGAGED
            ActivationState.ENABLED_ALGORITHM_AND_CGM: 8,   # RUNNING_ABOVE_MIN_VOLUME
            ActivationState.CGM_TRANSMITTER_ID: 8,
            ActivationState.GOT_STATUS: 8,
            ActivationState.ACTIVATED: 8,           # RUNNING_ABOVE_MIN_VOLUME
        }
        if self._pod.deactivated:
            pod_state = 15  # DEACTIVATED
        elif self._pod.running_state.name == "PRIMING":
            pod_state = 7   # PRIMING
        else:
            pod_state = _PROGRESS_MAP.get(self._activation_state, 2)

        lot_number = int(self._pod.lot_number.lstrip("L") or "0")
        seq_number = self._pod.sequence_number
        uid = int.from_bytes(self._pod.unique_id[:4], "big")

        payload = struct.pack(
            ">BB BBB BBB B B I I B I",
            0x01,         # command code
            0x15,         # length (21 bytes after this)
            major,        # PI major
            minor,        # PI minor
            patch,        # PI interim
            major,        # BLE major (same as PI for emulator)
            minor,        # BLE minor
            patch,        # BLE interim
            0x0A,         # product ID (10 = Omnipod 5 profile)
            pod_state,    # pod state (lower 4 bits)
            lot_number,   # lot number
            seq_number,   # sequence number
            0x00,         # RSSI (bits 0-5) | receiver gain (bits 6-7)
            uid,          # unique ID echo
        )

        assert len(payload) == 23, f"Expected 23 bytes, got {len(payload)}"

        self._activation_state = ActivationState.GOT_VERSION
        hex_payload = payload.hex()
        logger.info(
            "G0.0: TWI GetVersion: state=%d, lot=%d, fw=%d.%d.%d, "
            "state -> GOT_VERSION",
            pod_state, lot_number, major, minor, patch,
        )
        return RhpResponse.attribute("", 0, 0, hex_payload)

    # ------------------------------------------------------------------
    # Activation handlers (type 1)
    # ------------------------------------------------------------------

    def _handle_set_unique_id(self, req: RhpRequest) -> RhpResponse:
        """S1.0=<hex_uid> -> Set pod unique ID."""
        if req.payload is None:
            return RhpResponse.error(RhpAction.SET, 1, 0, RhpErrorCode.INVALID_VALUE)

        try:
            uid_bytes = bytes.fromhex(req.payload)
        except ValueError:
            return RhpResponse.error(RhpAction.SET, 1, 0, RhpErrorCode.INVALID_VALUE)

        self._unique_id = uid_bytes[:4]
        self._pod.unique_id = self._unique_id
        self._activation_state = ActivationState.SET_UID
        logger.info("S1.0: uid=%s, state -> SET_UID", self._unique_id.hex())
        return RhpResponse.success("", 1, 0)

    def _handle_program_alerts(self, req: RhpRequest) -> RhpResponse:
        """S1.1=<alert_config> -> Program alerts during activation."""
        # Track which alert programming step this is based on activation state
        if self._activation_state == ActivationState.SET_UID:
            self._low_reservoir_alerts_programmed = True
            self._activation_state = ActivationState.PROGRAMMED_LOW_RESERVOIR_ALERTS
            logger.info("S1.1: low reservoir alerts programmed")
        elif self._activation_state == ActivationState.PROGRAMMED_LOW_RESERVOIR_ALERTS:
            self._loc_alert_reprogrammed = True
            self._activation_state = ActivationState.REPROGRAMMED_LOC_ALERT
            logger.info("S1.1: LOC alert reprogrammed")
        elif self._activation_state == ActivationState.PRIMED:
            self._expiration_alert_programmed = True
            self._activation_state = ActivationState.PROGRAMMED_EXPIRATION_ALERT
            logger.info("S1.1: expiration alert programmed")
        elif self._activation_state == ActivationState.PROGRAMMED_BASAL:
            self._cancel_loc_alerts_programmed = True
            self._activation_state = ActivationState.PROGRAMMED_CANCEL_LOC_ALERTS
            logger.info("S1.1: cancel/LOC alerts programmed")
        else:
            logger.info("S1.1: alert programming acknowledged (state=%s)", self._activation_state.name)

        return RhpResponse.success("", 1, 1)

    def _handle_prime_pod(self, _req: RhpRequest) -> RhpResponse:
        """S1.2=1 -> Start pod priming."""
        self._pod.prime_start_time = time.time()
        self._activation_state = ActivationState.PRIMED
        logger.info("S1.2: priming started (%.0fs duration)", self._pod.prime_duration)
        return RhpResponse.success("", 1, 2)

    def _handle_get_pod_status(self, _req: RhpRequest) -> RhpResponse:
        """G1.6 -> Pod status as semicolon-delimited text.

        Format: flags;alert_mask;running_state;reservoir_pulses;uid;
                minutes;bolus_pulses;total_pulses;glucose;trend;
                iob_hundredths;bolus_total_pulses
        """
        self._pod.tick()
        rs = self._pod.running_state

        flags = 0x01 if self._pod.activated else 0x00
        if self._pod.bolus_remaining_units > 0:
            flags |= 0x08

        uid_hex = self._pod.unique_id[:4].hex()
        minutes = self._pod.minutes_since_activation
        reservoir = int(self._pod.reservoir_units / 0.05)
        bolus_pulses = int(self._pod.bolus_remaining_units / 0.05)
        total_pulses = int(self._pod.total_insulin_delivered / 0.05)

        glucose = self._pod.glucose_mg_dl or 0
        trend = self._pod.glucose_trend or 0
        iob = int(self._pod.iob_units * 100)
        bolus_total_pulses = int(self._pod.bolus_total_units / 0.05)

        value = (
            f"{flags:02x};0000;{rs.value};"
            f"{reservoir};{uid_hex};{minutes};"
            f"{bolus_pulses};{total_pulses};"
            f"{glucose};{trend};{iob};{bolus_total_pulses}"
        )

        logger.info("G1.6: running_state=%s (%d), activation=%s",
                     rs.name, rs.value, self._activation_state.name)
        return RhpResponse.attribute("", 1, 6, value)

    def _handle_program_basal(self, req: RhpRequest) -> RhpResponse:
        """S1.3=<basal_data> -> Program basal schedule."""
        if req.payload:
            try:
                self._pod.basal_program_raw = bytes.fromhex(req.payload)
            except ValueError:
                self._pod.basal_program_raw = req.payload.encode("utf-8")
        self._activation_state = ActivationState.PROGRAMMED_BASAL
        logger.info("S1.3: basal programmed, state -> PROGRAMMED_BASAL")
        return RhpResponse.success("", 1, 3)

    def _handle_insert_cannula(self, _req: RhpRequest) -> RhpResponse:
        """S1.4=1 -> Insert cannula."""
        self._pod.cannula_inserted = True
        self._activation_state = ActivationState.INSERTED_CANNULA
        logger.info("S1.4: cannula inserted")

        # Simulate second prime wait completion
        self._second_prime_done = True
        self._activation_state = ActivationState.SECOND_PRIME_WAIT_FINISHED
        logger.info("S1.4: second prime wait finished")

        return RhpResponse.success("", 1, 4)

    def _handle_enable_algorithm(self, _req: RhpRequest) -> RhpResponse:
        """S1.5=1 -> Enable algorithm and CGM driver."""
        self._pod.activated = True
        self._pod.activation_time = time.time()
        self._activation_state = ActivationState.ENABLED_ALGORITHM_AND_CGM
        logger.info("S1.5: algorithm and CGM driver enabled")
        return RhpResponse.success("", 1, 5)

    def _handle_get_aid_status_g11(self, _req: RhpRequest) -> RhpResponse:
        """G11.3 -> Get AID Pod Status (28-byte binary payload, hex-encoded)."""
        self._pod.tick()

        payload = self._pod.encode_aid_status_g11()
        hex_payload = payload.hex()

        if self._activation_state.value >= ActivationState.ENABLED_ALGORITHM_AND_CGM:
            self._activation_state = ActivationState.GOT_STATUS
            logger.info("G11.3: state -> GOT_STATUS")

        if self._activation_state == ActivationState.GOT_STATUS:
            self._activation_state = ActivationState.ACTIVATED
            logger.info("G11.3: state -> ACTIVATED")

        return RhpResponse.attribute("", 11, 3, hex_payload)

    def _handle_get_aid_status_g12(self, _req: RhpRequest) -> RhpResponse:
        """G12.3 -> Get Unified AID Pod Status (variable-length, hex-encoded)."""
        self._pod.tick()

        payload = self._pod.encode_aid_status_g12()
        hex_payload = payload.hex()

        if self._activation_state.value >= ActivationState.ENABLED_ALGORITHM_AND_CGM:
            self._activation_state = ActivationState.GOT_STATUS
            logger.info("G12.3: state -> GOT_STATUS")

        if self._activation_state == ActivationState.GOT_STATUS:
            self._activation_state = ActivationState.ACTIVATED
            logger.info("G12.3: state -> ACTIVATED")

        return RhpResponse.attribute("", 12, 3, hex_payload)

    # ------------------------------------------------------------------
    # Algorithm handlers (type 3)
    # ------------------------------------------------------------------

    def _handle_get_target_bg(self, _req: RhpRequest) -> RhpResponse:
        return RhpResponse.attribute("", 3, 1, str(self._target_bg))

    def _handle_set_target_bg(self, req: RhpRequest) -> RhpResponse:
        if req.payload:
            self._target_bg = int(req.payload)
        if self._aid_setup_state == AidSetupState.TDI:
            self._aid_setup_state = AidSetupState.TARGET_BG
        logger.info("S3.1: target_bg=%d", self._target_bg)
        return RhpResponse.success("", 3, 1)

    def _handle_set_tdi(self, req: RhpRequest) -> RhpResponse:
        if req.payload:
            self._tdi = int(req.payload)
        if self._aid_setup_state == AidSetupState.UTC:
            self._aid_setup_state = AidSetupState.TDI
        logger.info("S3.2: tdi=%d", self._tdi)
        return RhpResponse.success("", 3, 2)

    def _handle_set_correction_factor(self, req: RhpRequest) -> RhpResponse:
        if req.payload:
            self._correction_factor = int(req.payload)
        if self._aid_setup_state == AidSetupState.TARGET_BG:
            self._aid_setup_state = AidSetupState.CORRECTION_FACTOR
        logger.info("S3.3: correction_factor=%d", self._correction_factor)
        return RhpResponse.success("", 3, 3)

    def _handle_set_cgm_egv(self, req: RhpRequest) -> RhpResponse:
        """S3.4=<glucose_value> -> Send CGM EGV proxy data."""
        if req.payload:
            try:
                self._pod.glucose_mg_dl = int(req.payload)
            except ValueError:
                pass
        logger.info("S3.4: cgm_egv=%d", self._pod.glucose_mg_dl)
        return RhpResponse.success("", 3, 4)

    def _handle_get_iob(self, _req: RhpRequest) -> RhpResponse:
        """G3.5 -> Get insulin on board."""
        iob_hundredths = int(self._pod.iob_units * 100)
        return RhpResponse.attribute("", 3, 5, str(iob_hundredths))

    def _handle_get_algorithm_status(self, _req: RhpRequest) -> RhpResponse:
        """G3.6 -> Get algorithm status."""
        algo_state = 1 if self._pod.activated else 0
        cgm_state = 1 if self._pod.activated else 0
        glucose = self._pod.glucose_mg_dl
        iob_pulses = int(self._pod.iob_units / 0.05)
        return RhpResponse.attribute("", 3, 6, f"{algo_state};{cgm_state};{glucose};{iob_pulses}")

    def _handle_set_egv_threshold(self, req: RhpRequest) -> RhpResponse:
        if req.payload:
            self._egv_threshold = int(req.payload)
        if self._aid_setup_state == AidSetupState.DIA:
            self._aid_setup_state = AidSetupState.EGV_THRESHOLD
        logger.info("S3.7: egv_threshold=%d", self._egv_threshold)
        return RhpResponse.success("", 3, 7)

    def _handle_get_warnings(self, _req: RhpRequest) -> RhpResponse:
        """G3.8 -> Get warnings."""
        return RhpResponse.attribute("", 3, 8, "0")

    def _handle_set_dia(self, req: RhpRequest) -> RhpResponse:
        if req.payload:
            self._dia = int(req.payload)
        if self._aid_setup_state == AidSetupState.CORRECTION_FACTOR:
            self._aid_setup_state = AidSetupState.DIA
        logger.info("S3.9: dia=%d", self._dia)
        return RhpResponse.success("", 3, 9)

    def _handle_set_hypo_protect(self, req: RhpRequest) -> RhpResponse:
        logger.info("S3.10: hypo protect mode set")
        return RhpResponse.success("", 3, 10)

    def _handle_get_qn(self, _req: RhpRequest) -> RhpResponse:
        return RhpResponse.attribute("", 3, 11, "0")

    # ------------------------------------------------------------------
    # CGM handlers (type 4)
    # ------------------------------------------------------------------

    def _handle_set_cgm_tx_id(self, req: RhpRequest) -> RhpResponse:
        """S4.0=<tx_id> -> Set CGM transmitter ID."""
        tx_id = req.payload or ""
        if self._activation_state == ActivationState.ENABLED_ALGORITHM_AND_CGM:
            self._activation_state = ActivationState.CGM_TRANSMITTER_ID
        logger.info("S4.0: cgm_tx_id=%s", tx_id)
        return RhpResponse.success("", 4, 0)

    def _handle_get_cgm_bound(self, _req: RhpRequest) -> RhpResponse:
        return RhpResponse.attribute("", 4, 2, "1" if self._pod.activated else "0")

    # ------------------------------------------------------------------
    # System handlers (type 255)
    # ------------------------------------------------------------------

    def _handle_set_utc(self, req: RhpRequest) -> RhpResponse:
        """S255.2=<epoch> -> Set UTC time."""
        if req.payload:
            self._utc_time = int(req.payload)
        if self._aid_setup_state == AidSetupState.READY:
            self._aid_setup_state = AidSetupState.UTC
        logger.info("S255.2: utc=%d", self._utc_time)
        return RhpResponse.success("", 255, 2)

    def _handle_get_utc(self, _req: RhpRequest) -> RhpResponse:
        """G255.2 -> Get current UTC time."""
        # If no time was set, return current wall-clock time
        utc = self._utc_time if self._utc_time else int(time.time())
        logger.info("G255.2: utc=%d", utc)
        return RhpResponse.attribute("", 255, 2, str(utc))

    def _handle_get_error_codes(self, _req: RhpRequest) -> RhpResponse:
        return RhpResponse.attribute("", 255, 3, "0")

    # ------------------------------------------------------------------
    # Logger handlers (type 0, prefix L)
    # ------------------------------------------------------------------

    def _handle_set_logger_enable(self, _req: RhpRequest) -> RhpResponse:
        logger.info("SL0.4: logger enabled")
        return RhpResponse.success("L", 0, 4)

    def _handle_get_logger_unread(self, _req: RhpRequest) -> RhpResponse:
        return RhpResponse.attribute("L", 0, 5, "0")

    # ------------------------------------------------------------------
    # Operation handlers (type 2)
    # ------------------------------------------------------------------

    def _handle_send_bolus(self, req: RhpRequest) -> RhpResponse:
        """S2.0=<pulse_count> -> Send bolus."""
        if not self._pod.activated:
            return RhpResponse.error(RhpAction.SET, 2, 0, RhpErrorCode.INVALID_STATE)

        if req.payload is None:
            return RhpResponse.error(RhpAction.SET, 2, 0, RhpErrorCode.INVALID_VALUE)

        bolus_pulses = int(req.payload)
        bolus_units = bolus_pulses * 0.05

        self._pod.bolus_in_progress = True
        self._pod.bolus_remaining_units = bolus_units
        self._pod.bolus_total_units = bolus_units
        logger.info("S2.0: bolus %.2fU (%d pulses) started", bolus_units, bolus_pulses)
        return RhpResponse.success("", 2, 0)

    def _handle_stop_program(self, _req: RhpRequest) -> RhpResponse:
        """S2.1=1 -> Stop current delivery program."""
        self._pod.bolus_in_progress = False
        self._pod.bolus_remaining_units = 0.0
        logger.info("S2.1: program stopped")
        return RhpResponse.success("", 2, 1)

    def _handle_send_temp_basal(self, req: RhpRequest) -> RhpResponse:
        """S2.2=<rate>;<duration_slots> -> Temp basal."""
        if req.payload:
            parts = req.payload.split(";")
            if len(parts) >= 1:
                self._pod.temp_basal_rate = float(parts[0])
                self._pod.temp_basal_active = True
        logger.info("S2.2: temp basal rate=%.2f", self._pod.temp_basal_rate)
        return RhpResponse.success("", 2, 2)

    def _handle_resume_insulin(self, _req: RhpRequest) -> RhpResponse:
        """S2.3=1 -> Resume insulin delivery."""
        self._pod.temp_basal_active = False
        logger.info("S2.3: insulin resumed")
        return RhpResponse.success("", 2, 3)

    def _handle_send_beep(self, _req: RhpRequest) -> RhpResponse:
        """S2.4=1 -> Beep."""
        logger.info("S2.4: beep!")
        return RhpResponse.success("", 2, 4)

    def _handle_silence_alert(self, req: RhpRequest) -> RhpResponse:
        """S2.5=<alert_index> -> Silence alert."""
        logger.info("S2.5: alert silenced")
        return RhpResponse.success("", 2, 5)

    def _handle_deactivate(self, _req: RhpRequest) -> RhpResponse:
        """S2.6=1 -> Deactivate pod and reset to factory state."""
        logger.info("S2.6: deactivating pod and resetting state")

        # Reset pod state to fresh defaults (full reservoir, no delivery, etc.)
        self._pod.reset()

        # Reset handler-level state so the emulator can re-activate
        self._activation_state = ActivationState.READY
        self._aid_setup_state = AidSetupState.READY
        self._unique_id = b""
        self._low_reservoir_alerts_programmed = False
        self._loc_alert_reprogrammed = False
        self._expiration_alert_programmed = False
        self._cancel_loc_alerts_programmed = False
        self._second_prime_done = False
        self._utc_time = 0
        self._tdi = 0
        self._target_bg = 0
        self._correction_factor = 0
        self._dia = 0
        self._egv_threshold = 0

        # Signal the session to clear LTK so a fresh pairing can occur
        if self._on_deactivate is not None:
            self._on_deactivate()

        return RhpResponse.success("", 2, 6)

    # ------------------------------------------------------------------
    # History Buffer handlers (type 2, documented)
    # ------------------------------------------------------------------

    def _handle_get_history_index(self, _req: RhpRequest) -> RhpResponse:
        """G2.0 -> Get history buffer index/length (stub)."""
        logger.info("G2.0: history buffer index requested (stub)")
        return RhpResponse.attribute("", 2, 0, "0;0")

    def _handle_set_history_index(self, _req: RhpRequest) -> RhpResponse:
        """S2.0 -> Set history buffer index (stub)."""
        logger.info("S2.0: history buffer index set (stub)")
        return RhpResponse.success("", 2, 0)

    def _handle_get_history_data(self, _req: RhpRequest) -> RhpResponse:
        """G2.1 -> Get history buffer data (stub)."""
        logger.info("G2.1: history buffer data requested (stub)")
        return RhpResponse.attribute("", 2, 1, "")

    # ------------------------------------------------------------------
    # Stub handlers for missing documented type/attr pairs
    # ------------------------------------------------------------------

    def _handle_get_logger_start_time(self, _req: RhpRequest) -> RhpResponse:
        """GL0.8 -> Get logger start time (stub)."""
        logger.info("GL0.8: logger start time requested (stub)")
        return RhpResponse.attribute("L", 0, 8, "0")

    def _handle_get_logger_logs(self, _req: RhpRequest) -> RhpResponse:
        """GL0.9 -> Get logger logs (stub)."""
        logger.info("GL0.9: logger logs requested (stub)")
        return RhpResponse.attribute("L", 0, 9, "")

    def _handle_get_algo_log_data(self, _req: RhpRequest) -> RhpResponse:
        """G3.0 -> Get algorithm log data (stub)."""
        logger.info("G3.0: algo log data requested (stub)")
        return RhpResponse.attribute("", 3, 0, "")

    def _handle_set_cgm_calib(self, _req: RhpRequest) -> RhpResponse:
        """S4.1 -> Set CGM calibration (stub)."""
        logger.info("S4.1: CGM calibration set (stub)")
        return RhpResponse.success("", 4, 1)

    def _handle_get_cgm_calib(self, _req: RhpRequest) -> RhpResponse:
        """G4.1 -> Get CGM calibration (stub)."""
        logger.info("G4.1: CGM calibration requested (stub)")
        return RhpResponse.attribute("", 4, 1, "0")

    def _handle_get_last_calib(self, _req: RhpRequest) -> RhpResponse:
        """G4.3 -> Get last calibration (stub)."""
        logger.info("G4.3: last calibration requested (stub)")
        return RhpResponse.attribute("", 4, 3, "0")

    def _handle_set_enable_error_code(self, _req: RhpRequest) -> RhpResponse:
        """S255.3 -> Enable error code (stub)."""
        logger.info("S255.3: enable error code set (stub)")
        return RhpResponse.success("", 255, 3)

    def _handle_set_eng_record_count(self, req: RhpRequest) -> RhpResponse:
        """SR0.1=<count> -> Set engineering record count (stub)."""
        logger.info("SR0.1: engineering record count set to %s (stub)", req.payload)
        return RhpResponse.success("R", 0, 1)

    def _handle_get_eng_record_count(self, _req: RhpRequest) -> RhpResponse:
        """GR0.1 -> Get engineering record count (stub)."""
        logger.info("GR0.1: engineering record count requested (stub)")
        return RhpResponse.attribute("R", 0, 1, "0")

    def _handle_set_eng_offset(self, req: RhpRequest) -> RhpResponse:
        """SR0.2=<offset> -> Set engineering read offset (stub)."""
        logger.info("SR0.2: engineering offset set to %s (stub)", req.payload)
        return RhpResponse.success("R", 0, 2)

    def _handle_get_eng_offset(self, _req: RhpRequest) -> RhpResponse:
        """GR0.2 -> Get engineering offset (stub)."""
        logger.info("GR0.2: engineering offset requested (stub)")
        return RhpResponse.attribute("R", 0, 2, "0")

    def _handle_get_eng_data(self, _req: RhpRequest) -> RhpResponse:
        """GR0.5 -> Get engineering data (stub)."""
        logger.info("GR0.5: engineering data requested (stub)")
        return RhpResponse.attribute("R", 0, 5, "")
