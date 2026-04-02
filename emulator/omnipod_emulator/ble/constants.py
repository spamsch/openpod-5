"""
BLE GATT UUIDs and characteristic configurations for the Omnipod 5 protocol.

These values define the GATT service and characteristics used by the
Omnipod 5 BLE communication protocol.

Reference: OMNIPOD5_CONNECTION_PROTOCOL.md, Section 1 (BLE Discovery)
"""

# ---------------------------------------------------------------------------
# GATT service UUID
# ---------------------------------------------------------------------------

SERVICE_UUID = "1a7e4024-e3ed-4464-8b7e-751e03d0dc5f"
"""Primary GATT service exposed by the pod."""

# ---------------------------------------------------------------------------
# GATT characteristic UUIDs
# ---------------------------------------------------------------------------

CMD_CHARACTERISTIC_UUID = "1a7e2441-e3ed-4464-8b7e-751e03d0dc5f"
"""
Write-without-response characteristic.
The phone (central) writes commands here.  The pod reads incoming data from
this characteristic.
"""

TP_CLASSIC_CHARACTERISTIC_UUID = "1a7e2442-e3ed-4464-8b7e-751e03d0dc5f"
"""
Notify characteristic (classic transport path).
The pod sends responses and unsolicited data to the phone via notifications
on this characteristic.
"""

TP_FAST_CHARACTERISTIC_UUID = "1a7e2443-e3ed-4464-8b7e-751e03d0dc5f"
"""
Notify characteristic (fast transport path, optional).
Used for high-throughput data transfers when supported.
"""

# ---------------------------------------------------------------------------
# Advertising UUIDs for unpaired pods
# ---------------------------------------------------------------------------

UNPAIRED_SCAN_UUIDS = [
    "ce1f923d-c539-48ea-7300-0afffffffe00",
    "ce1f923d-c539-48ea-7300-0afffffffe01",
    "ce1f923d-c539-48ea-7300-0afffffffe02",
    "ce1f923d-c539-48ea-7300-0afffffffe03",
]
"""
Service UUIDs included in the advertising data of an unpaired pod.
The PDM app scans for any of these to discover pods that are ready to pair.
"""

DEFAULT_UNPAIRED_SCAN_UUID = UNPAIRED_SCAN_UUIDS[0]
"""Default UUID used in advertising for a new unpaired pod."""


def paired_scan_uuids(ctrl_id: bytes) -> list[str]:
    """
    Derive paired advertising UUIDs from a controller ID.

    After pairing, real pods switch from unpaired UUIDs (``...fffffffe0x``)
    to controller-specific UUIDs (``...{CTRL_ID}0x``).

    Args:
        ctrl_id: 4-byte controller ID.

    Returns:
        List of 4 UUID strings for paired advertising.
    """
    base = "ce1f923d-c539-48ea-7300-0a"
    hex_id = ctrl_id.hex()
    return [f"{base}{hex_id}{i:02x}" for i in range(4)]

# ---------------------------------------------------------------------------
# Advertising parameters
# ---------------------------------------------------------------------------

ADVERTISING_INTERVAL_MS = 100
"""Advertising interval in milliseconds (fast advertising for discovery)."""

DEVICE_NAME = "Openpod_Emu"
"""BLE device name advertised by the pod emulator."""

# ---------------------------------------------------------------------------
# Connection parameters
# ---------------------------------------------------------------------------

MTU_SIZE = 251
"""
ATT MTU size.
The phone requests an MTU of 251 bytes per the documented protocol.
"""

# ---------------------------------------------------------------------------
# Envelope / chunking constants
# ---------------------------------------------------------------------------

MAX_CHUNK_SIZE = 244
"""
Maximum payload bytes per BLE write.
With MTU 251, the usable ATT payload is 251 - 3 (ATT header) = 248.
Leave 4 bytes for envelope overhead, giving 244 usable bytes.
"""

ENVELOPE_HEADER_SIZE = 16
"""Protobuf Envelope header overhead (id + contentLength + flags)."""
