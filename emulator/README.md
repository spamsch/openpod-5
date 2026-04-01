# Omnipod 5 Pod Emulator

A Python-based emulator that simulates an Omnipod 5 insulin pod over BLE.
This allows the OpenPod Android app to be tested end-to-end without real hardware.

## Prerequisites

- Python 3.11+
- A BLE-capable host (or bumble's virtual transport)

## Installation

```bash
pip install -e ".[dev]"
```

## Running the emulator

```bash
# Using bumble's link-relay virtual transport (no hardware needed):
python run.py

# Using a real USB BLE adapter:
python run.py --transport usb:0

# With a fixed random seed for reproducible sessions:
python run.py --seed 42
```

The emulator will:

1. Initialize a virtual BLE device using bumble
2. Register the Omnipod 5 GATT service and characteristics
3. Begin advertising as an unpaired pod
4. Wait for the OpenPod app to connect
5. Handle the full protocol: pairing, EAP-AKA auth, activation, commands

## Running tests

```bash
pytest tests/ -v
```

## Architecture

```
omnipod_emulator/
  ble/          BLE GATT server (bumble) and constants
  crypto/       Pure-Python crypto: X25519, KDF, AES-CCM, MILENAGE, EAP-AKA
  protocol/     Protocol state machines: pairing, activation, RHP commands
  pod/          Simulated pod state (reservoir, delivery, alerts)
```

## Security notes

- Key material is NEVER logged. Only key sizes, operation outcomes, and timing are logged.
- The emulator is for testing only and must not be used with real insulin delivery.
