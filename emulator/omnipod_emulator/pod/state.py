"""
Simulated Omnipod 5 pod state.

Tracks all mutable pod state: reservoir level, activation status, delivery
programs, alerts, and firmware metadata.  This is the single source of truth
that the protocol handlers read from and write to.

Reference: OMNIPOD5_CONNECTION_PROTOCOL.md, Section 6 (Activation states)
"""

from __future__ import annotations

import logging
import math
import random
import struct
import time
from dataclasses import dataclass, field
from enum import IntEnum

logger = logging.getLogger(__name__)


class RunningState(IntEnum):
    """Pod running states during the activation and delivery lifecycle."""

    IDLE = 0
    PRIMING = 7
    CLUTCH_DRIVE_ENGAGED = 5
    RUNNING_ABOVE_MIN_VOLUME = 8
    ACTIVE = 9


class AlertType(IntEnum):
    """Pod alert types."""

    LOW_RESERVOIR = 1
    EXPIRATION = 2
    OCCLUSION = 3
    AUTO_OFF = 4
    LOSS_OF_COMMUNICATION = 5


@dataclass
class PodState:
    """
    Complete mutable state of a simulated Omnipod 5 pod.

    All fields have safe defaults representing an unactivated pod with a
    full reservoir.

    Attributes:
        firmware_version:     Firmware version string (e.g. "3.1.6").
        lot_number:           Manufacturing lot number.
        reservoir_units:      Insulin remaining in the reservoir (units).
        activated:            True after the full activation sequence.
        primed:               True after priming.
        cannula_inserted:     True after cannula insertion.
        deactivated:          True after explicit deactivation.
        basal_rate:           Current basal rate (units/hour).
        basal_program_raw:    Raw basal program bytes from the phone.
        bolus_in_progress:    True while a bolus is being delivered.
        bolus_remaining_units: Remaining bolus amount (units).
        temp_basal_active:    True if a temporary basal is running.
        temp_basal_rate:      Temporary basal rate (units/hour).
        alerts:               Currently active alerts.
        unique_id:            Pod unique ID assigned during activation.
        activation_time:      Epoch timestamp when the pod was activated.
        total_insulin_delivered: Cumulative insulin delivered (units).
        prime_start_time:       Epoch when priming started (0 = not started).
        prime_duration:         Simulated priming duration in seconds.
    """

    firmware_version: str = "3.1.6"
    # The v3.1.1 phone app's lot-number validator requires the
    # 6-bit location code at bits [25..30] of the uint32 lot
    # number to be in the allow-list {5, 7, 9}. The previous
    # default "L05082541" (= 0x004D8DAD) decoded to location
    # code 0 and failed the check. 167772160 = 0x0A000000 has
    # location code 5 and family 'P' (bit 31 = 0).
    lot_number: str = "L167772160"
    sequence_number: int = 770785
    reservoir_units: float = 200.0
    activated: bool = False
    primed: bool = False
    cannula_inserted: bool = False
    deactivated: bool = False
    prime_start_time: float = 0.0
    prime_duration: float = 55.0     # Real Omnipod 5 priming takes ~55 seconds
    bolus_pulse_rate: float = 1.0   # Seconds per bolus pulse (real pod: ~3s)
    basal_rate: float = 1.0
    basal_program_raw: bytes = b""
    bolus_in_progress: bool = False
    bolus_remaining_units: float = 0.0
    bolus_total_units: float = 0.0
    temp_basal_active: bool = False
    temp_basal_rate: float = 0.0
    alerts: list[AlertType] = field(default_factory=list)
    unique_id: bytes = b"\x00\x00\x00\x00"
    activation_time: float = 0.0
    total_insulin_delivered: float = 0.0

    # Glucose simulation (CGM proxy — real Dexcom updates every 5 minutes)
    glucose_mg_dl: int = 120
    glucose_trend: int = 0  # -2=FALLING_QUICKLY, -1=FALLING, 0=STEADY, 1=RISING, 2=RISING_QUICKLY
    _prev_glucose: int = 120
    _last_tick: float = 0.0
    _last_cgm_update: float = 0.0  # epoch of last CGM reading
    _last_bolus_pulse_time: float = 0.0  # epoch of last bolus pulse delivery

    # Automatic micro-bolus (Omnipod 5 auto-mode, every 5 minutes)
    auto_mode_enabled: bool = True
    _last_auto_bolus_time: float = 0.0
    auto_bolus_max_units: float = 0.20  # max per 5-min cycle

    # Basal pulse tracking (real pod delivers 0.05U pulses)
    _basal_pulse_accumulator: float = 0.0  # fractional pulses accumulated

    # IOB tracking
    iob_units: float = 0.0

    @property
    def firmware_version_tuple(self) -> tuple[int, int, int]:
        """Parse firmware version string into (major, minor, patch)."""
        parts = self.firmware_version.split(".")
        if len(parts) != 3:
            return (0, 0, 0)
        try:
            return (int(parts[0]), int(parts[1]), int(parts[2]))
        except ValueError:
            return (0, 0, 0)

    @property
    def running_state(self) -> RunningState:
        """Current pod running state based on activation progress."""
        if self.deactivated:
            return RunningState.IDLE
        if self.activated:
            return RunningState.ACTIVE
        if self.prime_start_time > 0:
            elapsed = time.time() - self.prime_start_time
            if elapsed >= self.prime_duration:
                self.primed = True
                return RunningState.RUNNING_ABOVE_MIN_VOLUME
            return RunningState.PRIMING
        return RunningState.IDLE

    @property
    def minutes_since_activation(self) -> int:
        """Minutes elapsed since activation, or 0 if not activated."""
        if not self.activated or self.activation_time == 0.0:
            return 0
        elapsed = time.time() - self.activation_time
        return max(0, int(elapsed / 60))

    @property
    def pod_expiry_hours_remaining(self) -> float:
        """Hours remaining until the 72-hour pod expiry."""
        if not self.activated:
            return 72.0
        elapsed_hours = self.minutes_since_activation / 60.0
        return max(0.0, 72.0 - elapsed_hours)

    # Constants matching real hardware
    PULSE_UNITS: float = 0.05       # 1 pump pulse = 0.05 U
    BOLUS_PULSE_INTERVAL: float = 3.0  # seconds between bolus pulses
    CGM_INTERVAL: float = 300.0     # 5 minutes between Dexcom readings
    AUTO_BOLUS_INTERVAL: float = 300.0  # 5 minutes between auto-bolus cycles

    def tick(self) -> None:
        """
        Advance the simulation by one time step.

        Called before each encode_status() to evolve glucose, IOB, and
        reservoir values over time.  Uses wall-clock time so values change
        at a realistic pace regardless of polling frequency.

        Timing matches real Omnipod 5 + Dexcom behaviour:
        - CGM glucose updates every 5 minutes (Dexcom G6/G7 interval)
        - Basal delivered as discrete 0.05 U pulses spread over the hour
        - Manual bolus delivered as 0.05 U pulses every 3 seconds
        - Auto-mode micro-bolus every 5 minutes, max 0.20 U per cycle
        """
        now = time.time()
        if self._last_tick == 0.0:
            self._last_tick = now
            self._last_cgm_update = now
            self._last_auto_bolus_time = now
            return

        elapsed = now - self._last_tick
        if elapsed < 1.0:
            return  # Avoid sub-second noise
        self._last_tick = now

        # -- CGM glucose: update only every 5 minutes (Dexcom interval) --
        cgm_elapsed = now - self._last_cgm_update
        if cgm_elapsed >= self.CGM_INTERVAL:
            # Number of 5-min periods that passed (usually 1)
            periods = int(cgm_elapsed / self.CGM_INTERVAL)
            self._prev_glucose = self.glucose_mg_dl
            for _ in range(periods):
                # Drift toward 120 mg/dL (mean reversion) + random noise
                # Coefficients tuned for one 5-minute step
                mean_reversion = (120 - self.glucose_mg_dl) * 0.01
                noise = random.gauss(0, 3.0)  # ~3 mg/dL std dev per 5 min
                self.glucose_mg_dl = max(
                    40, min(400, int(self.glucose_mg_dl + mean_reversion + noise))
                )
            self._last_cgm_update += periods * self.CGM_INTERVAL

            # Trend: rate of change per 5 minutes
            rate = self.glucose_mg_dl - self._prev_glucose
            if periods > 1:
                rate = rate / periods  # average per period
            if rate > 10:
                self.glucose_trend = 2   # RISING_QUICKLY
            elif rate > 3:
                self.glucose_trend = 1   # RISING
            elif rate < -10:
                self.glucose_trend = -2  # FALLING_QUICKLY
            elif rate < -3:
                self.glucose_trend = -1  # FALLING
            else:
                self.glucose_trend = 0   # STEADY

            logger.debug(
                "CGM update: %d mg/dL, trend=%d, rate=%.1f mg/dL per 5min",
                self.glucose_mg_dl, self.glucose_trend, rate,
            )

        if not self.activated:
            return

        # -- Basal delivery: discrete 0.05 U pulses --
        effective_basal = (
            self.temp_basal_rate if self.temp_basal_active else self.basal_rate
        )
        hours = elapsed / 3600.0
        self._basal_pulse_accumulator += effective_basal * hours / self.PULSE_UNITS
        basal_pulses = int(self._basal_pulse_accumulator)
        if basal_pulses > 0:
            self._basal_pulse_accumulator -= basal_pulses
            basal_delivered = basal_pulses * self.PULSE_UNITS
            self.deliver_insulin(min(basal_delivered, self.reservoir_units))
            self.iob_units += basal_delivered

        # -- Manual bolus: 0.05 U per pulse, one pulse every 3 seconds --
        if self.bolus_in_progress and self.bolus_remaining_units > 0:
            if self._last_bolus_pulse_time == 0.0:
                self._last_bolus_pulse_time = now
            bolus_elapsed = now - self._last_bolus_pulse_time
            max_pulses = int(bolus_elapsed / self.bolus_pulse_rate)
            if max_pulses > 0:
                self._last_bolus_pulse_time += max_pulses * self.bolus_pulse_rate
                bolus_delivered = min(
                    max_pulses * self.PULSE_UNITS,
                    self.bolus_remaining_units,
                )
                if bolus_delivered > 0:
                    self.deliver_insulin(min(bolus_delivered, self.reservoir_units))
                    self.iob_units += bolus_delivered
                    self.bolus_remaining_units -= bolus_delivered
                    if self.bolus_remaining_units <= 0.001:
                        self.bolus_remaining_units = 0.0
                        self.bolus_in_progress = False
                        self._last_bolus_pulse_time = 0.0
                        logger.info(
                            "Bolus delivery complete: %.2fU total",
                            self.bolus_total_units,
                        )

        # -- Auto-mode micro-bolus: every 5 minutes, max 0.20 U --
        if self.auto_mode_enabled:
            auto_elapsed = now - self._last_auto_bolus_time
            if auto_elapsed >= self.AUTO_BOLUS_INTERVAL:
                cycles = int(auto_elapsed / self.AUTO_BOLUS_INTERVAL)
                self._last_auto_bolus_time += cycles * self.AUTO_BOLUS_INTERVAL
                for _ in range(cycles):
                    self._deliver_auto_bolus()

        # -- IOB: exponential decay, half-life ~90 min --
        decay = math.exp(-0.693 * elapsed / (90.0 * 60.0))
        self.iob_units *= decay
        if self.iob_units < 0.01:
            self.iob_units = 0.0

    def _deliver_auto_bolus(self) -> None:
        """
        Deliver one automatic micro-bolus (Omnipod 5 auto-mode).

        The real algorithm considers CGM trend, IOB, target glucose, and
        carbs-on-board.  This simplified version delivers a correction when
        glucose is above target, capped at auto_bolus_max_units (0.20 U).
        """
        target = 110  # mg/dL — typical Omnipod 5 target
        if self.glucose_mg_dl <= target:
            return

        # Simple proportional correction: ~0.01 U per mg/dL above target,
        # clamped to [1 pulse .. max].  Real algorithm is far more complex.
        correction = (self.glucose_mg_dl - target) * 0.01
        # Round down to whole pulses
        pulses = int(correction / self.PULSE_UNITS)
        if pulses < 1:
            return
        dose = min(pulses * self.PULSE_UNITS, self.auto_bolus_max_units)
        dose = min(dose, self.reservoir_units)

        if dose > 0:
            self.deliver_insulin(dose)
            self.iob_units += dose
            logger.info(
                "Auto micro-bolus: %.2fU (glucose=%d, target=%d)",
                dose, self.glucose_mg_dl, target,
            )

    def encode_status(self) -> bytes:
        """
        Encode pod status into a binary payload for GET_STATUS responses.

        The format is a simplified representation.  The real protocol uses
        more fields; this covers the essentials.

        Returns:
            Encoded status bytes.
        """
        # Flags byte
        flags = 0
        if self.activated:
            flags |= 0x01
        if self.primed:
            flags |= 0x02
        if self.cannula_inserted:
            flags |= 0x04
        if self.bolus_in_progress:
            flags |= 0x08
        if self.temp_basal_active:
            flags |= 0x10
        if self.deactivated:
            flags |= 0x20

        # Alert bitmask (8 bits, one per alert type)
        alert_mask = 0
        for alert in self.alerts:
            if alert.value < 8:
                alert_mask |= 1 << alert.value

        # Reservoir in pulses (1 pulse = 0.05 units)
        reservoir_pulses = int(self.reservoir_units / 0.05)

        # Minutes since activation
        minutes = self.minutes_since_activation

        # Bolus remaining in pulses
        bolus_pulses = int(self.bolus_remaining_units / 0.05)

        # Bolus total in pulses (for computing delivered = total - remaining)
        bolus_total_pulses = int(self.bolus_total_units / 0.05)

        # Total insulin delivered in pulses
        total_pulses = int(self.total_insulin_delivered / 0.05)

        running = self.running_state
        iob_hundredths = int(self.iob_units * 100)

        payload = struct.pack(
            ">BBBI4sHHHHbHH",
            flags,
            alert_mask,
            running,
            reservoir_pulses,
            self.unique_id[:4],
            minutes,
            bolus_pulses,
            total_pulses,
            self.glucose_mg_dl,
            self.glucose_trend,
            iob_hundredths,
            bolus_total_pulses,
        )

        logger.debug(
            "Status encoded: flags=0x%02x, running=%s, reservoir=%.1fU, "
            "glucose=%d mg/dL, iob=%.2fU, minutes=%d",
            flags,
            RunningState(running).name,
            self.reservoir_units,
            self.glucose_mg_dl,
            self.iob_units,
            minutes,
        )

        return payload

    def encode_aid_status_g11(self) -> bytes:
        """
        Encode AID Pod Status for G11.3 response.

        Returns a 28-byte binary payload matching the documented format:
            [EGV:2][EGV_time:4][TDI:2][CGM_tx_time:4][EGV_rate:1]
            [algo_loop:1][IOB:4][pod_ts:4][QN_status:1][CGM_algo:1]
            [CGM_tx_status:1][tx_lifetime:2][QN_state:1]
        """
        egv = self.glucose_mg_dl
        egv_time = self.minutes_since_activation * 60  # seconds
        tdi = int(self.total_insulin_delivered / 0.05)  # pulses
        cgm_tx_time = egv_time  # same as EGV time for emulator
        egv_rate = max(-128, min(127, self.glucose_trend * 5))  # scaled trend
        algo_loop = 1 if self.activated else 0
        iob = int(self.iob_units * 10000)  # 0.0001 U resolution
        pod_ts = egv_time
        qn_status = 0  # normal
        cgm_algo = 1 if self.activated else 0
        cgm_tx_status = 1 if self.activated else 0
        tx_lifetime = 0  # not tracked
        qn_state = 0  # normal

        payload = struct.pack(
            ">HI H I b B I I B B B H B",
            egv,          # EGV (2)
            egv_time,     # EGV_time (4)
            tdi,          # TDI (2)
            cgm_tx_time,  # CGM_tx_time (4)
            egv_rate,     # EGV_rate (1)
            algo_loop,    # algo_loop (1)
            iob,          # IOB (4)
            pod_ts,       # pod_ts (4)
            qn_status,    # QN_status (1)
            cgm_algo,     # CGM_algo (1)
            cgm_tx_status,  # CGM_tx_status (1)
            tx_lifetime,  # tx_lifetime (2)
            qn_state,     # QN_state (1)
        )

        logger.debug(
            "AID status G11.3 encoded: egv=%d, iob=%.2fU, algo=%d",
            egv, self.iob_units, algo_loop,
        )

        return payload

    def encode_aid_status_g12(self) -> bytes:
        """
        Encode Unified AID Pod Status for G12.3 response.

        Variable-length payload: base G11.3 data prefixed with CGM type byte,
        followed by CGM-type-specific tail data.

        Dexcom G7 (type 7) appends 16 bytes of CGM data.
        """
        cgm_type = 7  # DEXCOM_G7
        base = self.encode_aid_status_g11()
        # Dexcom G7 tail: 16 bytes of CGM-specific data (stub zeros)
        g7_tail = bytes(16)
        return bytes([cgm_type]) + base + g7_tail

    def reset(self) -> None:
        """
        Reset the pod to a fresh unactivated state.

        Called after deactivation so the emulator can simulate a new pod
        without restarting the process. Preserves firmware_version and
        lot_number (identity), resets everything else to defaults.
        """
        self.reservoir_units = 200.0
        self.activated = False
        self.primed = False
        self.cannula_inserted = False
        self.deactivated = False
        self.prime_start_time = 0.0
        self.basal_rate = 1.0
        self.basal_program_raw = b""
        self.bolus_in_progress = False
        self.bolus_remaining_units = 0.0
        self.bolus_total_units = 0.0
        self.temp_basal_active = False
        self.temp_basal_rate = 0.0
        self.alerts = []
        self.unique_id = b"\x00\x00\x00\x00"
        self.activation_time = 0.0
        self.total_insulin_delivered = 0.0
        self.glucose_mg_dl = 120
        self.glucose_trend = 0
        self._prev_glucose = 120
        self._last_tick = 0.0
        self._last_cgm_update = 0.0
        self._last_bolus_pulse_time = 0.0
        self._last_auto_bolus_time = 0.0
        self._basal_pulse_accumulator = 0.0
        self.auto_mode_enabled = True
        self.iob_units = 0.0
        logger.info("Pod state reset to factory defaults")

    def activate(self) -> None:
        """Mark the pod as fully activated."""
        self.activated = True
        self.activation_time = time.time()
        logger.info("Pod activated at %f", self.activation_time)

    def deliver_insulin(self, units: float) -> bool:
        """
        Simulate delivering *units* of insulin from the reservoir.

        Args:
            units: Amount to deliver.

        Returns:
            True if the delivery succeeded, False if insufficient insulin.
        """
        if units < 0:
            raise ValueError(f"Cannot deliver negative insulin: {units}")

        if units > self.reservoir_units:
            logger.warning(
                "Insufficient reservoir: requested %.2f, available %.2f",
                units,
                self.reservoir_units,
            )
            return False

        self.reservoir_units -= units
        self.total_insulin_delivered += units

        logger.info(
            "Delivered %.2fU insulin, reservoir=%.1fU, total=%.1fU",
            units,
            self.reservoir_units,
            self.total_insulin_delivered,
        )

        # Check for low reservoir alert
        if self.reservoir_units <= 10.0 and AlertType.LOW_RESERVOIR not in self.alerts:
            self.alerts.append(AlertType.LOW_RESERVOIR)
            logger.warning("LOW_RESERVOIR alert triggered (%.1fU remaining)", self.reservoir_units)

        return True
