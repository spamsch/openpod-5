# ESP32 BLE Sniffer for Omnipod 5

Captures BLE advertising packets — including **directed advertisements** (ADV_DIRECT_IND)
that are normally invisible to standard BLE scanners.

## Hardware

- **ESP32-WROOM-32D** dev board with CP2102 USB-UART bridge
- Connected at `/dev/cu.usbserial-0001` (macOS)

## Background

The Omnipod 5 Pod likely uses BLE directed advertising to communicate exclusively
with its paired PDM. Directed advertisements contain only the advertiser's address
and the target's address — no payload. The BLE controller on phones and standard
scanners drops these packets in hardware before any app can see them.

This sniffer bypasses that limitation by **spoofing the ESP32's BLE MAC address**
to match the PDM's address, causing the controller to accept the directed
advertisements as if they were intended for this device.

## Operating Modes

### Mode 1: Discovery (default)

Scans for all BLE advertisements with extended filter policy (0x02).
Use this to find:
- The Pod's BLE address (look for Insulet manufacturer data `0x0360`)
- The PDM's BLE address
- Any advertising activity from Omnipod devices

```
# Just flash and open serial monitor — discovery mode is default
```

### Mode 2: Directed Capture

Once you know the PDM's BLE address, configure it via serial command.
The ESP32 will set its own MAC to match the PDM's, then the BLE controller
will accept directed advertisements from the Pod.

```
# In serial monitor, type:
spoof AA:BB:CC:DD:EE:FF
```

Where `AA:BB:CC:DD:EE:FF` is the PDM's Bluetooth address.

## Output Format

Each captured advertisement prints:

```
[  12.345] ADV_DIRECT_IND  addr=A1:B2:C3:D4:E5:F6 -> target=AA:BB:CC:DD:EE:FF rssi=-42
[  12.456] ADV_IND         addr=A1:B2:C3:D4:E5:F6 rssi=-55 name="DASH 1234" mfr=0360:0102...
[  12.567] ADV_NONCONN_IND addr=X1:X2:X3:X4:X5:X6 rssi=-78 (filtered)
```

Omnipod-related packets are highlighted with `*** OMNIPOD ***` prefix.

## Building & Flashing

### Prerequisites

- [ESP-IDF v5.x](https://docs.espressif.com/projects/esp-idf/en/stable/esp32/get-started/)
  installed and sourced (`. $IDF_PATH/export.sh`)

### Build & Flash

```bash
cd esp32-ble-sniffer
idf.py set-target esp32
idf.py build
idf.py -p /dev/cu.usbserial-0001 flash monitor
```

Or use the helper script:

```bash
./flash.sh          # build, flash, and open monitor
./flash.sh monitor  # just open serial monitor
```

### Serial Monitor (without flashing)

```bash
idf.py -p /dev/cu.usbserial-0001 monitor
# or
screen /dev/cu.usbserial-0001 115200
```

## Serial Commands

Type these in the serial monitor (idf.py monitor):

| Command | Description |
|---------|-------------|
| `scan` | Start/restart scanning in discovery mode |
| `stop` | Stop scanning |
| `spoof AA:BB:CC:DD:EE:FF` | Set ESP32 MAC to match target, restart scan |
| `reset` | Restore original MAC and restart scan |
| `status` | Show current mode, MAC, and packet counts |

## How It Works

1. Initializes the ESP32 BLE controller in VHCI (Virtual HCI) mode — no Bluedroid
   or NimBLE stack, just raw HCI commands
2. Sends `HCI_LE_Set_Scan_Parameters` with `filter_policy = 0x02` (accept
   undirected ads + directed ads with RPA targets)
3. Parses incoming `LE Advertising Report` events and prints decoded packets
4. When `spoof` command is used, sends vendor-specific HCI command `0xFC32` to
   change the ESP32's BD_ADDR, then restarts scanning — directed ads targeted
   at that address are now accepted by the controller

## Limitations

- **Discovery mode** cannot see directed ads targeted at a specific public address
  (this is a BLE spec hardware limitation)
- **Spoof mode** requires knowing the PDM's BLE address first
- The ESP32 can only listen on one advertising channel at a time in the standard
  scan mode; it rotates through channels 37/38/39 automatically
- While spoofing the PDM's address, the ESP32 should NOT be near the real PDM
  to avoid BLE address collisions

## References

- [ESP-IDF VHCI API](https://docs.espressif.com/projects/esp-idf/en/stable/esp32/api-reference/bluetooth/controller_vhci.html)
- [ESP-IDF ble_adv_scan_combined example](https://github.com/espressif/esp-idf/tree/master/examples/bluetooth/hci/ble_adv_scan_combined)
- [BLE Core Spec Vol 6 Part B Section 4.3.3](https://www.bluetooth.com/specifications/specs/core60-html/) — Scan Filter Policy
- [Tarlogic ESP32 HCI Research](https://www.tarlogic.com/blog/hacking-bluetooth-the-easy-way-with-esp32-hci-commands-and-hidden-features/)
- [FCC ID RBV-029 — Omnipod 5](https://fcc.report/FCC-ID/RBV-029)
