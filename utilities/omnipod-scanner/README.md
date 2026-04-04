# Omnipod BLE Scanner

Minimal Android app that scans for Omnipod BLE devices and dumps their raw advertising data to log files.

## Detection methods

- **Real pod**: 128-bit service UUID `00004024-0000-1000-8000-00805f9b34fb`
- **Emulator**: 128-bit UUIDs with prefix `ce1f923d-c539-48ea-7300-0a`
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
