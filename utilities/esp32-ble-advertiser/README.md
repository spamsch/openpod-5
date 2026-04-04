# Omnipod BLE Advertiser Test

Minimal ESP32-S3 firmware that advertises with the same BLE payload as the
Omnipod emulator or a real pod. Used to test whether BLE 5.0 extended
advertising is needed for the Omnipod app to discover the device.

## Hardware

Heltec WiFi Kit 32 V3 (ESP32-S3)

## Build & Flash

Requires ESP-IDF v5.x with the ESP32-S3 target.

```bash
# Find your serial port
ls /dev/cu.usb*

# Build, flash, monitor (set PORT if needed)
PORT=/dev/cu.usbserial-0001 ./flash.sh
```

## Serial Commands

| Command    | Effect                                        |
|------------|-----------------------------------------------|
| `legacy`   | Switch to legacy ADV_IND (BLE 4.x)           |
| `extended` | Switch to extended advertising (BLE 5.0)      |
| `realpod`  | Use real pod's captured advertisement bytes   |
| `emulator` | Use emulator's advertisement bytes            |
| `status`   | Print current configuration                   |
| `restart`  | Restart advertising with current settings     |

## Test Matrix

| #  | Command sequence       | What it tests                           |
|----|------------------------|-----------------------------------------|
| 1  | (default)              | Legacy + emulator — baseline            |
| 2  | `extended`             | Extended + emulator — BLE 5.0 theory    |
| 3  | `realpod`              | Legacy + real pod — payload control      |
| 4  | `extended` → `realpod` | Extended + real pod — best chance        |

For each test, check:
1. Does nRF Connect see it?
2. Does the Omnipod app see it?
