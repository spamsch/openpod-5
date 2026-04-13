"""
Unit tests for BLE constants and helpers.
"""

from __future__ import annotations

from omnipod_emulator.ble.constants import (
    MAX_CHUNK_SIZE,
    MTU_SIZE,
    paired_scan_uuids,
)


class TestPairedScanUuids:
    """Test paired UUID derivation from controller ID."""

    def test_returns_4_uuids(self):
        uuids = paired_scan_uuids(b"\x01\x02\x03\x04")
        assert len(uuids) == 4

    def test_uuid_format(self):
        uuids = paired_scan_uuids(b"\x01\x02\x03\x04")
        for uuid in uuids:
            # Should be valid UUID-like string
            assert uuid.startswith("ce1f923d-c539-48ea-7300-0a")
            assert "01020304" in uuid

    def test_uuid_suffix_increments(self):
        uuids = paired_scan_uuids(b"\x01\x02\x03\x04")
        assert uuids[0].endswith("00")
        assert uuids[1].endswith("01")
        assert uuids[2].endswith("02")
        assert uuids[3].endswith("03")

    def test_different_ctrl_ids_produce_different_uuids(self):
        uuids_a = paired_scan_uuids(b"\x01\x02\x03\x04")
        uuids_b = paired_scan_uuids(b"\xAA\xBB\xCC\xDD")
        assert uuids_a[0] != uuids_b[0]

    def test_ctrl_id_embedded_in_uuid(self):
        uuids = paired_scan_uuids(b"\xAA\xBB\xCC\xDD")
        assert "aabbccdd" in uuids[0]


class TestMtuConstants:
    """Test MTU and chunk size constants."""

    def test_mtu_is_251(self):
        assert MTU_SIZE == 251

    def test_max_chunk_fits_in_mtu(self):
        assert MAX_CHUNK_SIZE <= MTU_SIZE - 3  # ATT header overhead
