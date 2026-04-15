"""
Unit tests for RHP handler registration and stub handlers.

Tests the new handlers added during protocol remediation:
- G11.3 / G12.3 (AID Pod Status)
- History Buffer stubs (G2.0, S2.0, G2.1)
- Logger stubs (GL0.8, GL0.9)
- Algorithm stub (G3.0)
- CGM stubs (S4.1, G4.1, G4.3)
- System stub (S255.3)
- Engineering stubs (GR0.1, GR0.2, GR0.5)
- Operations on type 2 (S2.0 - S2.6)
"""

from __future__ import annotations

from omnipod_emulator.pod.state import PodState
from omnipod_emulator.protocol.rhp_dispatcher import RhpTextDispatcher
from omnipod_emulator.protocol.rhp_handlers import RhpHandlers


def _make_handlers() -> tuple[RhpTextDispatcher, RhpHandlers, PodState]:
    pod = PodState()
    dispatcher = RhpTextDispatcher()
    handlers = RhpHandlers(pod, dispatcher)
    return dispatcher, handlers, pod


class TestTwiGetVersion:
    """Test TWI GetVersion (0x07) binary response via G0.0."""

    def test_returns_23_byte_hex_payload(self):
        d, _, _ = _make_handlers()
        result = d.dispatch("G0.0")
        assert result.startswith("0.0=")
        hex_payload = result.split("=", 1)[1]
        assert len(hex_payload) == 46  # 23 bytes = 46 hex chars

    def test_command_code_and_length(self):
        d, _, _ = _make_handlers()
        result = d.dispatch("G0.0")
        raw = bytes.fromhex(result.split("=", 1)[1])
        assert raw[0] == 0x01  # command code
        assert raw[1] == 0x15  # length = 21

    def test_firmware_version_fields(self):
        d, _, pod = _make_handlers()
        pod.firmware_version = "3.1.6"
        result = d.dispatch("G0.0")
        raw = bytes.fromhex(result.split("=", 1)[1])
        # PI version at bytes 2-4
        assert raw[2] == 3   # major
        assert raw[3] == 1   # minor
        assert raw[4] == 6   # patch

    def test_pod_state_filled_before_activation(self):
        d, _, _ = _make_handlers()
        result = d.dispatch("G0.0")
        raw = bytes.fromhex(result.split("=", 1)[1])
        pod_state = raw[9] & 0x0F
        assert pod_state == 2  # FILLED

    def test_pod_state_running_after_activation(self):
        d, h, pod = _make_handlers()
        pod.activated = True
        from omnipod_emulator.protocol.rhp_handlers import ActivationState
        h._activation_state = ActivationState.ACTIVATED
        result = d.dispatch("G0.0")
        raw = bytes.fromhex(result.split("=", 1)[1])
        pod_state = raw[9] & 0x0F
        assert pod_state == 8  # RUNNING_ABOVE_MIN_VOLUME

    def test_pod_state_uid_set(self):
        d, h, _ = _make_handlers()
        from omnipod_emulator.protocol.rhp_handlers import ActivationState
        h._activation_state = ActivationState.SET_UID
        result = d.dispatch("G0.0")
        raw = bytes.fromhex(result.split("=", 1)[1])
        pod_state = raw[9] & 0x0F
        assert pod_state == 3  # UID_SET

    def test_pod_state_basal_programmed(self):
        d, h, _ = _make_handlers()
        from omnipod_emulator.protocol.rhp_handlers import ActivationState
        h._activation_state = ActivationState.PROGRAMMED_BASAL
        result = d.dispatch("G0.0")
        raw = bytes.fromhex(result.split("=", 1)[1])
        pod_state = raw[9] & 0x0F
        assert pod_state == 6  # BASAL_PROGRAM_RUNNING

    def test_lot_number_encoded(self):
        d, _, pod = _make_handlers()
        pod.lot_number = "L05082541"
        result = d.dispatch("G0.0")
        raw = bytes.fromhex(result.split("=", 1)[1])
        lot = int.from_bytes(raw[10:14], "big")
        assert lot == 5082541

    def test_unique_id_echoed(self):
        d, _, pod = _make_handlers()
        pod.unique_id = b"\xDE\xAD\xBE\xEF"
        result = d.dispatch("G0.0")
        raw = bytes.fromhex(result.split("=", 1)[1])
        uid = int.from_bytes(raw[19:23], "big")
        assert uid == 0xDEADBEEF

    def test_product_id_is_5(self):
        # The v3.1.1 phone app validates this byte == 5
        # ("Valid POD Product Id: 5"). Previously the emulator
        # returned 0x0A, which v3.1.1 rejects.
        d, _, _ = _make_handlers()
        result = d.dispatch("G0.0")
        raw = bytes.fromhex(result.split("=", 1)[1])
        assert raw[8] == 0x05

    def test_byte_18_rssi_gain(self):
        d, _, _ = _make_handlers()
        result = d.dispatch("G0.0")
        raw = bytes.fromhex(result.split("=", 1)[1])
        # Byte 18 is RSSI (bits 0-5) | receiver gain (bits 6-7), default 0
        assert raw[18] == 0x00

    def test_sequence_number_encoded(self):
        d, _, pod = _make_handlers()
        pod.sequence_number = 770785
        result = d.dispatch("G0.0")
        raw = bytes.fromhex(result.split("=", 1)[1])
        seq = int.from_bytes(raw[14:18], "big")
        assert seq == 770785

    def test_default_lot_number_location_code_allowed(self):
        # The v3.1.1 phone app's lot-number validator requires the
        # 6-bit location code at bits [25..30] of the uint32 lot
        # number to be in the allow-list {5, 7, 9}. The default
        # pod lot_number must encode to one of those values so
        # that the phone accepts the emulator's getPodVersion
        # response.
        d, _, _ = _make_handlers()
        result = d.dispatch("G0.0")
        raw = bytes.fromhex(result.split("=", 1)[1])
        lot_uint32 = int.from_bytes(raw[10:14], "big")
        loc_code = (lot_uint32 >> 25) & 0x3F
        assert loc_code in {5, 7, 9}, (
            f"lot_number 0x{lot_uint32:08x} has location code {loc_code}, "
            f"not in the allow-list {{5, 7, 9}}"
        )
        # Family bit (bit 31) should be 0 = 'P' (not 'E')
        assert (lot_uint32 >> 31) == 0


class TestAidPodStatus:
    """Test G11.3 and G12.3 AID Pod Status handlers."""

    def test_g11_3_returns_hex_payload(self):
        d, _, _ = _make_handlers()
        result = d.dispatch("G11.3")
        assert result.startswith("11.3=")
        # Payload should be hex-encoded 28 bytes = 56 hex chars
        hex_payload = result.split("=", 1)[1]
        assert len(hex_payload) == 56

    def test_g12_3_returns_hex_payload(self):
        d, _, _ = _make_handlers()
        result = d.dispatch("G12.3")
        assert result.startswith("12.3=")
        # Payload: 1 (CGM type) + 28 (G11 base) + 16 (G7 tail) = 45 bytes = 90 hex chars
        hex_payload = result.split("=", 1)[1]
        assert len(hex_payload) == 90



class TestLoggerStubs:
    """Test Logger stub handlers."""

    def test_get_logger_start_time(self):
        d, _, _ = _make_handlers()
        assert d.dispatch("GL0.8") == "L0.8=0"

    def test_get_logger_logs(self):
        d, _, _ = _make_handlers()
        assert d.dispatch("GL0.9") == "L0.9="


class TestAlgorithmStubs:
    """Test Algorithm stub handlers."""

    def test_get_algo_log_data(self):
        d, _, _ = _make_handlers()
        assert d.dispatch("G3.0") == "3.0="


class TestCgmStubs:
    """Test CGM stub handlers."""

    def test_set_cgm_calib(self):
        d, _, _ = _make_handlers()
        assert d.dispatch("S4.1=100") == "ES4.1=0"

    def test_get_cgm_calib(self):
        d, _, _ = _make_handlers()
        assert d.dispatch("G4.1") == "4.1=0"

    def test_get_last_calib(self):
        d, _, _ = _make_handlers()
        assert d.dispatch("G4.3") == "4.3=0"


class TestSystemStubs:
    """Test System stub handlers."""

    def test_set_enable_error_code(self):
        d, _, _ = _make_handlers()
        assert d.dispatch("S255.3=1") == "ES255.3=0"


class TestEngineeringStubs:
    """Test Engineering (prefix R) stub handlers."""

    def test_get_record_count(self):
        d, _, _ = _make_handlers()
        assert d.dispatch("GR0.1") == "R0.1=0"

    def test_get_offset(self):
        d, _, _ = _make_handlers()
        assert d.dispatch("GR0.2") == "R0.2=0"

    def test_get_data(self):
        d, _, _ = _make_handlers()
        assert d.dispatch("GR0.5") == "R0.5="


class TestOperationsOnType200:
    """Test that operations are accessible on type 2."""

    def test_bolus_requires_activation(self):
        d, _, _ = _make_handlers()
        result = d.dispatch("S2.0=20")
        # Should return error (pod not activated)
        assert result.startswith("ES2.0=")

    def test_stop_program(self):
        d, _, _ = _make_handlers()
        assert d.dispatch("S2.1=1") == "ES2.1=0"

    def test_temp_basal(self):
        d, _, _ = _make_handlers()
        assert d.dispatch("S2.2=1.5;12") == "ES2.2=0"

    def test_resume_insulin(self):
        d, _, _ = _make_handlers()
        assert d.dispatch("S2.3=1") == "ES2.3=0"

    def test_beep(self):
        d, _, _ = _make_handlers()
        assert d.dispatch("S2.4=1") == "ES2.4=0"

    def test_silence_alert(self):
        d, _, _ = _make_handlers()
        assert d.dispatch("S2.5=1") == "ES2.5=0"

    def test_deactivate(self):
        d, _, pod = _make_handlers()
        assert d.dispatch("S2.6=1") == "ES2.6=0"
        # Pod should be reset
        assert pod.activated is False
        assert pod.reservoir_units == 200.0


class TestDeactivationReset:
    """Test that deactivation resets handler-level state."""

    def test_deactivation_resets_aid_setup_state(self):
        d, h, _ = _make_handlers()
        # Set some AID state
        d.dispatch("S255.2=1711929600")  # UTC
        d.dispatch("S3.2=50")  # TDI
        from omnipod_emulator.protocol.rhp_handlers import AidSetupState
        assert h.aid_setup_state == AidSetupState.TDI

        # Deactivate
        d.dispatch("S2.6=1")
        assert h.aid_setup_state == AidSetupState.READY

    def test_deactivation_fires_callback(self):
        pod = PodState()
        dispatcher = RhpTextDispatcher()
        called = []
        handlers = RhpHandlers(pod, dispatcher, on_deactivate=lambda: called.append(True))

        dispatcher.dispatch("S2.6=1")
        assert len(called) == 1
