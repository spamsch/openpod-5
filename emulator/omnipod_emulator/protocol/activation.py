"""
Pod activation state machine.

Handles the multi-step activation sequence that transitions a pod from
unactivated to fully active.  The activation phases mirror the real
Omnipod 5 activation flow documented in the protocol analysis.

Phase 1 -- Pod initialization:
    GetVersion -> SetUniqueId -> ProgramAlerts -> PrimePod

Phase 2 -- Cannula insertion and algorithm setup:
    ProgramBasal -> InsertCannula -> EnableAlgorithm -> GetStatus

Reference: OMNIPOD5_CONNECTION_PROTOCOL.md, Section 6 (Pod Activation)
"""

from __future__ import annotations

import enum
import logging
import struct
import time

from omnipod_emulator.pod.state import PodState
from omnipod_emulator.protocol.commands import (
    CommandType,
    ResponseStatus,
    RhpCommand,
    RhpCommandDispatcher,
    RhpResponse,
)

logger = logging.getLogger(__name__)


class ActivationState(enum.IntEnum):
    """
    Pod activation states.

    Values match the activation state constants from PodCommManager.
    """

    READY = 0
    STARTED = 14
    GOT_VERSION = 1
    SET_UID = 2
    PROGRAMMED_ALERTS = 3
    PRIMED = 5
    PHASE1_COMPLETE = 7
    PROGRAMMED_BASAL = 8
    INSERTED_CANNULA = 10
    ENABLED_ALGORITHM = 16
    GOT_STATUS = 12
    ACTIVATED = 13
    FAILED = -1


class ActivationHandler:
    """
    Manages the pod activation flow.

    Registers command handlers with the RHP dispatcher and advances the
    activation state as each step completes.

    Args:
        pod_state:  The simulated pod state.
        dispatcher: The RHP command dispatcher to register handlers with.
    """

    def __init__(
        self, pod_state: PodState, dispatcher: RhpCommandDispatcher
    ) -> None:
        self._pod = pod_state
        self._dispatcher = dispatcher
        self._activation_state = ActivationState.READY
        self._unique_id: bytes = b""

        # Register command handlers
        dispatcher.register_handler(
            CommandType.GET_VERSION, self._handle_get_version
        )
        dispatcher.register_handler(
            CommandType.SET_UNIQUE_ID, self._handle_set_unique_id
        )
        dispatcher.register_handler(
            CommandType.PROGRAM_ALERTS, self._handle_program_alerts
        )
        dispatcher.register_handler(
            CommandType.PRIME_POD, self._handle_prime_pod
        )
        dispatcher.register_handler(
            CommandType.PROGRAM_BASAL, self._handle_program_basal
        )
        dispatcher.register_handler(
            CommandType.INSERT_CANNULA, self._handle_insert_cannula
        )
        dispatcher.register_handler(
            CommandType.ENABLE_ALGORITHM, self._handle_enable_algorithm
        )
        dispatcher.register_handler(
            CommandType.GET_STATUS, self._handle_get_status
        )
        dispatcher.register_handler(
            CommandType.SEND_BOLUS, self._handle_send_bolus
        )
        dispatcher.register_handler(
            CommandType.STOP_PROGRAM, self._handle_stop_program
        )
        dispatcher.register_handler(
            CommandType.SEND_BEEP, self._handle_send_beep
        )
        dispatcher.register_handler(
            CommandType.DEACTIVATE_POD, self._handle_deactivate
        )

        logger.info("Activation handler initialized, state=READY")

    @property
    def activation_state(self) -> ActivationState:
        """Current activation state."""
        return self._activation_state

    def _handle_get_version(self, _cmd: RhpCommand) -> RhpResponse:
        """Return firmware version information."""
        logger.info("GET_VERSION: returning firmware info")

        major, minor, patch = self._pod.firmware_version_tuple
        payload = struct.pack(
            ">BBB6s",
            major,
            minor,
            patch,
            self._pod.lot_number.encode("ascii")[:6],
        )

        self._activation_state = ActivationState.GOT_VERSION
        logger.info("Activation state -> GOT_VERSION")

        return RhpResponse(
            command_type=CommandType.GET_VERSION,
            status=ResponseStatus.OK,
            payload=payload,
        )

    def _handle_set_unique_id(self, cmd: RhpCommand) -> RhpResponse:
        """Store the unique ID assigned by the phone."""
        if len(cmd.payload) < 4:
            logger.warning("SET_UNIQUE_ID: payload too short")
            return RhpResponse(
                command_type=CommandType.SET_UNIQUE_ID,
                status=ResponseStatus.ERROR,
                payload=b"",
            )

        self._unique_id = cmd.payload[:4]
        self._pod.unique_id = self._unique_id
        self._activation_state = ActivationState.SET_UID

        logger.info(
            "SET_UNIQUE_ID: id=%s, activation state -> SET_UID",
            self._unique_id.hex(),
        )

        return RhpResponse(
            command_type=CommandType.SET_UNIQUE_ID,
            status=ResponseStatus.OK,
            payload=b"",
        )

    def _handle_program_alerts(self, _cmd: RhpCommand) -> RhpResponse:
        """Acknowledge alert programming."""
        logger.info("PROGRAM_ALERTS: acknowledged")
        self._activation_state = ActivationState.PROGRAMMED_ALERTS
        return RhpResponse(
            command_type=CommandType.PROGRAM_ALERTS,
            status=ResponseStatus.OK,
            payload=b"",
        )

    def _handle_prime_pod(self, _cmd: RhpCommand) -> RhpResponse:
        """
        Start pod priming simulation.

        In a real pod this involves motor actuation and takes ~10 seconds.
        The emulator records the start time; GET_STATUS will report the
        running state as PRIMING until the duration elapses, then
        RUNNING_ABOVE_MIN_VOLUME to signal completion.
        """
        logger.info("PRIME_POD: starting prime sequence (%.0fs)", self._pod.prime_duration)
        self._pod.prime_start_time = time.time()
        self._activation_state = ActivationState.PRIMED

        return RhpResponse(
            command_type=CommandType.PRIME_POD,
            status=ResponseStatus.OK,
            payload=b"",
        )

    def _handle_program_basal(self, cmd: RhpCommand) -> RhpResponse:
        """Store the basal program."""
        logger.info("PROGRAM_BASAL: storing basal program (%d bytes)", len(cmd.payload))

        # In a real implementation we would parse the basal schedule.
        # For emulation, just acknowledge and store raw payload.
        self._pod.basal_program_raw = cmd.payload
        self._activation_state = ActivationState.PROGRAMMED_BASAL
        logger.info("PROGRAM_BASAL: state -> PROGRAMMED_BASAL")

        return RhpResponse(
            command_type=CommandType.PROGRAM_BASAL,
            status=ResponseStatus.OK,
            payload=b"",
        )

    def _handle_insert_cannula(self, _cmd: RhpCommand) -> RhpResponse:
        """Simulate cannula insertion."""
        logger.info("INSERT_CANNULA: simulating insertion")
        self._pod.cannula_inserted = True
        self._activation_state = ActivationState.INSERTED_CANNULA
        logger.info("INSERT_CANNULA: state -> INSERTED_CANNULA")

        return RhpResponse(
            command_type=CommandType.INSERT_CANNULA,
            status=ResponseStatus.OK,
            payload=b"",
        )

    def _handle_enable_algorithm(self, _cmd: RhpCommand) -> RhpResponse:
        """Enable the AID algorithm (mark pod as active)."""
        logger.info("ENABLE_ALGORITHM: activating pod")
        self._pod.activated = True
        self._activation_state = ActivationState.ACTIVATED
        logger.info("ENABLE_ALGORITHM: pod ACTIVATED")

        return RhpResponse(
            command_type=CommandType.ENABLE_ALGORITHM,
            status=ResponseStatus.OK,
            payload=b"",
        )

    def _handle_get_status(self, _cmd: RhpCommand) -> RhpResponse:
        """Return current pod status."""
        logger.info("GET_STATUS: returning pod status")
        self._pod.tick()
        payload = self._pod.encode_status()
        return RhpResponse(
            command_type=CommandType.GET_STATUS,
            status=ResponseStatus.OK,
            payload=payload,
        )

    def _handle_send_bolus(self, cmd: RhpCommand) -> RhpResponse:
        """Start a bolus delivery."""
        if not self._pod.activated:
            logger.warning("SEND_BOLUS: pod not activated")
            return RhpResponse(
                command_type=CommandType.SEND_BOLUS,
                status=ResponseStatus.INVALID_STATE,
                payload=b"",
            )

        if len(cmd.payload) < 2:
            logger.warning("SEND_BOLUS: payload too short")
            return RhpResponse(
                command_type=CommandType.SEND_BOLUS,
                status=ResponseStatus.ERROR,
                payload=b"",
            )

        # Parse bolus amount (pulse count, big-endian uint16)
        bolus_pulses = struct.unpack(">H", cmd.payload[:2])[0]
        bolus_units = bolus_pulses * 0.05

        # Start progressive delivery — tick() handles actual delivery
        self._pod.bolus_in_progress = True
        self._pod.bolus_remaining_units = bolus_units
        self._pod.bolus_total_units = bolus_units
        logger.info("SEND_BOLUS: %.2f units (%d pulses) — delivery started", bolus_units, bolus_pulses)

        return RhpResponse(
            command_type=CommandType.SEND_BOLUS,
            status=ResponseStatus.OK,
            payload=b"",
        )

    def _handle_stop_program(self, _cmd: RhpCommand) -> RhpResponse:
        """Stop current delivery program."""
        logger.info("STOP_PROGRAM: stopping all delivery")
        self._pod.bolus_in_progress = False
        self._pod.bolus_remaining_units = 0.0
        return RhpResponse(
            command_type=CommandType.STOP_PROGRAM,
            status=ResponseStatus.OK,
            payload=b"",
        )

    def _handle_send_beep(self, _cmd: RhpCommand) -> RhpResponse:
        """Simulate pod beep."""
        logger.info("SEND_BEEP: beep!")
        return RhpResponse(
            command_type=CommandType.SEND_BEEP,
            status=ResponseStatus.OK,
            payload=b"",
        )

    def _handle_deactivate(self, _cmd: RhpCommand) -> RhpResponse:
        """Deactivate the pod."""
        logger.info("DEACTIVATE_POD: deactivating")
        self._pod.activated = False
        self._pod.deactivated = True
        self._activation_state = ActivationState.READY
        return RhpResponse(
            command_type=CommandType.DEACTIVATE_POD,
            status=ResponseStatus.OK,
            payload=b"",
        )
