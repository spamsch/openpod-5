"""
Unit tests for PodState encoding and reset methods.
"""

from __future__ import annotations

import struct

from omnipod_emulator.pod.state import AlertType, PodState


class TestEncodeAidStatusG11:
    """Test G11.3 AID Pod Status encoding."""

    def test_returns_28_bytes(self):
        pod = PodState()
        payload = pod.encode_aid_status_g11()
        assert len(payload) == 28

    def test_unactivated_pod_zeros_algo_fields(self):
        pod = PodState()
        payload = pod.encode_aid_status_g11()
        # Unpack to verify algo_loop, cgm_algo, cgm_tx_status are 0
        fields = struct.unpack(">HI H I b B I I B B B H B", payload)
        egv, _, _, _, _, algo_loop, _, _, _, cgm_algo, cgm_tx_status, _, _ = fields
        assert egv == 120  # default glucose
        assert algo_loop == 0
        assert cgm_algo == 0
        assert cgm_tx_status == 0

    def test_activated_pod_sets_algo_fields(self):
        pod = PodState(activated=True)
        payload = pod.encode_aid_status_g11()
        fields = struct.unpack(">HI H I b B I I B B B H B", payload)
        _, _, _, _, _, algo_loop, _, _, _, cgm_algo, cgm_tx_status, _, _ = fields
        assert algo_loop == 1
        assert cgm_algo == 1
        assert cgm_tx_status == 1

    def test_encodes_glucose_and_iob(self):
        pod = PodState(glucose_mg_dl=180, iob_units=2.5)
        payload = pod.encode_aid_status_g11()
        fields = struct.unpack(">HI H I b B I I B B B H B", payload)
        egv = fields[0]
        iob = fields[6]
        assert egv == 180
        assert iob == 25000  # 2.5 * 10000

    def test_encodes_tdi_as_pulses(self):
        pod = PodState(total_insulin_delivered=5.0)
        payload = pod.encode_aid_status_g11()
        fields = struct.unpack(">HI H I b B I I B B B H B", payload)
        tdi = fields[2]
        assert tdi == 100  # 5.0 / 0.05


class TestEncodeAidStatusG12:
    """Test G12.3 Unified AID Pod Status encoding."""

    def test_returns_45_bytes(self):
        pod = PodState()
        payload = pod.encode_aid_status_g12()
        # 1 (CGM type) + 28 (G11 base) + 16 (G7 tail) = 45
        assert len(payload) == 45

    def test_first_byte_is_dexcom_g7(self):
        pod = PodState()
        payload = pod.encode_aid_status_g12()
        assert payload[0] == 7  # DEXCOM_G7

    def test_base_payload_matches_g11(self):
        pod = PodState(glucose_mg_dl=150)
        g11 = pod.encode_aid_status_g11()
        g12 = pod.encode_aid_status_g12()
        assert g12[1:29] == g11


class TestPodStateReset:
    """Test PodState.reset() returns to factory defaults."""

    def test_reset_clears_activation(self):
        pod = PodState(activated=True, primed=True, cannula_inserted=True, deactivated=True)
        pod.reset()
        assert pod.activated is False
        assert pod.primed is False
        assert pod.cannula_inserted is False
        assert pod.deactivated is False

    def test_reset_restores_reservoir(self):
        pod = PodState(reservoir_units=50.0, total_insulin_delivered=150.0)
        pod.reset()
        assert pod.reservoir_units == 200.0
        assert pod.total_insulin_delivered == 0.0

    def test_reset_clears_delivery(self):
        pod = PodState(
            bolus_in_progress=True,
            bolus_remaining_units=2.0,
            bolus_total_units=3.0,
            temp_basal_active=True,
            temp_basal_rate=1.5,
        )
        pod.reset()
        assert pod.bolus_in_progress is False
        assert pod.bolus_remaining_units == 0.0
        assert pod.bolus_total_units == 0.0
        assert pod.temp_basal_active is False
        assert pod.temp_basal_rate == 0.0

    def test_reset_clears_alerts(self):
        pod = PodState(alerts=[AlertType.LOW_RESERVOIR, AlertType.OCCLUSION])
        pod.reset()
        assert pod.alerts == []

    def test_reset_clears_unique_id(self):
        pod = PodState(unique_id=b"\xDE\xAD\xBE\xEF")
        pod.reset()
        assert pod.unique_id == b"\x00\x00\x00\x00"

    def test_reset_clears_iob(self):
        pod = PodState(iob_units=5.0)
        pod.reset()
        assert pod.iob_units == 0.0

    def test_reset_preserves_firmware_identity(self):
        pod = PodState(firmware_version="4.0.0", lot_number="L99999")
        pod.activated = True
        pod.reservoir_units = 10.0
        pod.reset()
        assert pod.firmware_version == "4.0.0"
        assert pod.lot_number == "L99999"

    def test_reset_resets_glucose_to_default(self):
        pod = PodState(glucose_mg_dl=300, glucose_trend=2)
        pod.reset()
        assert pod.glucose_mg_dl == 120
        assert pod.glucose_trend == 0
