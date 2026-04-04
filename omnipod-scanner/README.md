# Omnipod BLE Scanner

Minimal Android app that scans for Omnipod BLE devices and dumps their raw advertising data to log files.

## Detection methods

- **Omnipod 5**: 128-bit UUIDs with prefix `ce1f923d-c539-48ea-7300-0a` (both real pods and emulator)
- **Insulet company ID**: `0x0360` (864) in manufacturer-specific data

For real pods, the app decodes the 9 service UUIDs that encode pod ID, lot number, and sequence number.

## Scanning a paired pod

A paired Omnipod 5 pod does **not** advertise while its PDM (controller phone) is powered on and connected. The pod maintains a bonded BLE connection to the PDM and only starts advertising again when it loses that connection.

**To make a paired pod visible to the scanner, power down the PDM first.** The pod will begin advertising after it detects the connection has been lost.

## Build & deploy

```
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Pulling logs

```
adb pull /sdcard/Android/data/com.openpod.scanner/files/scans/
```

Each scan session creates a timestamped log file (`omnipod_scan_YYYYMMDD_HHmmss.log`).

## Captured Omnipod advertisements

### Paired pod (2026-04-03, PDM powered off)

```
Device: AP 0000429F 0E9984B10008918D  [34:3C:30:C9:65:5C]
RSSI: -73 dBm (public address)

UUID:   ce1f923d-c539-48ea-7300-0a0000429c00
        Pod ID base: 0x0000429C  [PAIRED - ctrl ID embedded]
        Pod ID adj bits: 3  →  full pod ID = 0x0000429F

Manufacturer Data (company 0x0360 / Insulet):
        00 02 30 00 00
        byte[1]=0x02, byte[2]=0x30 (adj 3 << 4), alarm=0x00, alert=0x00

Raw AD (62 bytes, ADV_IND + SCAN_RSP merged by Android):
  02 01 06 11 06 00 9C 42 00 00 0A 00 73 EA 48 39
  C5 3D 92 1F CE 08 FF 60 03 00 02 30 00 00 1D 09
  41 50 20 30 30 30 30 34 32 39 46 20 30 45 39 39
  38 34 42 31 30 30 30 38 39 31 38 44 00 00

ADV_IND (30 bytes):
  02 01 06              Flags: LE General Discoverable + BR/EDR Not Supported
  11 06 <16 bytes>      Incomplete 128-bit UUID (pod-specific, paired)
  08 FF 60 03 00 02 30 00 00   Manufacturer data (Insulet, adj=3)

SCAN_RSP (30 bytes):
  1D 09 <29 bytes>      Complete Local Name: "AP 0000429F 0E9984B10008918D"
```

### Unpaired pod (2026-04-04, factory fresh)

```
Device: AP FFFFFFFE 0E9984B100097C25  [34:3C:30:C9:64:BD]
RSSI: -87 dBm (public address)

UUID:   ce1f923d-c539-48ea-7300-0afffffffe00
        Pod ID base: 0xFFFFFFFE  [UNPAIRED]
        Pod ID adj bits: 0

Manufacturer Data (company 0x0360 / Insulet):
        00 00 00 00 00
        All zeros — no adj bits, no alarm, no alert

Raw AD (62 bytes, ADV_IND + SCAN_RSP merged by Android):
  02 01 06 11 06 00 FE FF FF FF 0A 00 73 EA 48 39
  C5 3D 92 1F CE 08 FF 60 03 00 00 00 00 00 1D 09
  41 50 20 46 46 46 46 46 46 46 45 20 30 45 39 39
  38 34 42 31 30 30 30 39 37 43 32 35 00 00

ADV_IND (30 bytes):
  02 01 06              Flags: LE General Discoverable + BR/EDR Not Supported
  11 06 <16 bytes>      Incomplete 128-bit UUID (unpaired sentinel)
  08 FF 60 03 00 00 00 00 00   Manufacturer data (Insulet, all zeros)

SCAN_RSP (30 bytes):
  1D 09 <29 bytes>      Complete Local Name: "AP FFFFFFFE 0E9984B100097C25"

Confirmed via nRF sniffer (ADV_IND only, 30 bytes):
  02 01 06 11 06 00 FE FF FF FF 0A 00 73 EA 48 39
  C5 3D 92 1F CE 08 FF 60 03 00 00 00 00 00
```

### Key differences: paired vs unpaired

| Field | Unpaired | Paired |
|-------|----------|--------|
| UUID suffix | `fffffffe0x` (sentinel) | `{pod_id & ~3}{index}` |
| Mfr byte[1] | `0x00` | `0x02` |
| Mfr byte[2] (adj bits) | `0x00` | `(pod_id & 0x03) << 4` |
| Device name pod ID | `FFFFFFFE` | actual pod ID hex |
| MAC address | public, fixed | public, fixed (different from unpaired) |
