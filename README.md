# OpenPod

> ⚠️ **VERY EARLY STAGE — MOST COMPONENTS DO NOT WORK.**
> This is an exploratory research project. Expect broken builds, incomplete features, failing flows, and protocol layers that have never been exercised against real hardware. Nothing here should be considered functional, reliable, or safe. Do **not** attempt to use this with a real pod. If you want to help then get into contact. Looking for researchers.

Independent research and interoperability project for the **Omnipod 5** insulin delivery system. Built from the ground up with modern Android development practices — not a fork of the official app.

> **Omnipod 5 only.** This project targets the Omnipod 5 (automated insulin delivery with Dexcom G6/G7 CGM integration). It does **not** support Omnipod Dash or Omnipod Eros — those use different communication protocols and are served by the [AndroidAPS](https://github.com/nightscout/AndroidAPS) project.

## Status

**Phase 4 — Integration Testing.** The app, communication stack, and pod emulator are implemented. Integration testing is next.

| Phase | Description | Status |
|-------|-------------|--------|
| 1. UI/UX Spec | 33 screen specifications with wireframe review tool | Done |
| 2. Working App | Onboarding, dashboard, pod pairing, BLE + crypto + protocol layers | Done |
| 3. Pod Emulator | Python + Bumble emulator with pure-Python crypto (218 tests) | Done |
| 4. Integration | End-to-end: pairing, live dashboard, bolus delivery via TCP emulator | Done |
| 5. Hardware Testing | BLE transport, real pod testing (research only) | Next |

## Architecture

MVI + Clean Architecture with a multi-module Gradle project.

```
app/                          Application shell, navigation, Koin entry point
core/
  model/                      Pure Kotlin domain models
  domain/                     Use cases, bolus calculator, safety logic
  data/                       Repositories (BLE + DB + preferences)
  database/                   Room + SQLCipher (encrypted at rest)
  datastore/                  DataStore + Tink (encrypted preferences)
  ble/                        Kable-based BLE connection manager
  protocol/                   RHP commands, protobuf framing, TWICommand
  crypto/                     Pure-Kotlin crypto (X25519, AES-CCM, EAP-AKA, MILENAGE)
  ui/                         Compose theme, shared components
  audit/                      Append-only delivery log with checksums
  testing/                    Shared test utilities and fakes
feature/
  dashboard/                  Home screen — glucose, IOB, pod status
  bolus/                      Bolus calculator and delivery flow
  basal/                      Basal programs, temp basal, activity mode
  pairing/                    Pod discovery, pairing, activation wizard
  history/                    Delivery and glucose history
  alerts/                     Alarms, alerts, notifications
  settings/                   User preferences, CGM config, insulin settings
utilities/
  esp32-ble-advertiser/       ESP32-S3 firmware: Omnipod 5 BLE advertisement spoofer
  esp32-ble-sniffer/          ESP32 firmware: BLE sniffer for directed advertisements
  omnipod-scanner/            Android app: BLE scanner for Omnipod devices
```

## Tech Stack

| Layer | Technology |
|-------|------------|
| UI | Jetpack Compose + Material 3 (dark-first) |
| Architecture | MVI with deterministic state for medical safety |
| DI | Koin (lightweight, pure-Kotlin) |
| BLE | Kable (coroutines-native) |
| Database | Room + SQLCipher (AES-256 encryption at rest) |
| Preferences | DataStore + Tink |
| Pod Crypto | Pure Kotlin: Bouncy Castle (X25519, AES-CCM) + JCA (SHA-256, MILENAGE) |
| Serialization | Wire (protobuf) |
| Async | Kotlin Coroutines + Flow |
| Testing | JUnit 5 + MockK + Turbine + Compose Test |
| Build | Gradle 9.4.1 + AGP 9.0.1 + Kotlin 2.3.20 |
| CI | GitHub Actions |

## Building

**Requirements:** Android SDK (API 36), JDK 17

```bash
# Clone
git clone https://github.com/spamsch/openpod-5.git
cd openpod-5

# Set Android SDK path
echo "sdk.dir=/path/to/android/sdk" > local.properties

# Build
./gradlew assembleDebug

# Run tests
./gradlew test
```

## How It Works

The Omnipod 5 communicates over Bluetooth Low Energy using a proprietary protocol stack:

```
Application    PodCommManager, AidPodCommandController
Protocol       RHP text commands (Get/Set attributes)
Data           Protobuf (Envelope framing, chunked messages)
Security       EAP-AKA mutual auth + AES-CCM-128 encryption
Transport      BLE GATT (TWI SDK)
```

OpenPod reimplements the phone side of this protocol entirely in Kotlin — no native libraries required. The cryptographic stack (X25519 ECDH, AES-CMAC, AES-CCM-128, MILENAGE, EAP-AKA) is implemented using Bouncy Castle and standard JCA, based on published cryptographic standards.

Key BLE identifiers:
- **GATT Service:** `1a7e4024-e3ed-4464-8b7e-751e03d0dc5f`
- **Scan UUIDs (unpaired):** `ce1f923d-c539-48ea-7300-0afffffffe0{0-3}`

## Pod Emulator

The `emulator/` directory contains a Python-based Omnipod 5 pod emulator that implements the full protocol stack: BLE advertising, EAP-AKA mutual authentication, AES-CCM encryption, TWI command framing, and RHP command handling.

**TCP mode** (development — no Bluetooth hardware needed):
```bash
make emulator          # or: python emulator/run.py --mode tcp
```

**BLE mode on Raspberry Pi 4** (real over-the-air testing):
The emulator runs on a Raspberry Pi 4 B using its onboard Bluetooth radio via [Bumble](https://github.com/nicoreinaldo/bumble). The Pi advertises as a pod, and any BLE central (phone, tablet) can discover and pair with it.

```bash
# Deploy from your Mac
./emulator/deploy-to-pi.sh

# On the Pi — run as a systemd service
sudo systemctl start openpod-emulator
```

See [`emulator/RASPBERRY_PI.md`](emulator/RASPBERRY_PI.md) for full setup instructions.

## Disclaimer

> **WARNING: This software is not approved for medical use.** It is an independent research project and has **not** been reviewed, approved, or cleared by the FDA, any notified body, or any regulatory authority. It is **not** CE-marked. It is **not** endorsed by, affiliated with, or supported by Insulet Corporation, Dexcom, or any medical device manufacturer.

> **Do not use this software to deliver insulin to a person.** Incorrect commands, protocol errors, or software bugs could result in dangerous insulin over-delivery or under-delivery, which can cause severe hypoglycemia, diabetic ketoacidosis, or death.

> **Using this software with a real Omnipod 5 pod may permanently brick the pod.** Failed authentication or protocol errors can leave a pod in an unrecoverable state — the pod becomes unusable and must be discarded. Each pod costs ~$30 and cannot be reset.

> Provenance: OpenPod is an independent, source-original implementation intended to achieve interoperability with third-party systems. Compatibility information was derived from observation, testing, and reverse engineering of lawfully obtained devices/software, to the extent permitted by applicable law and only as necessary to understand protocol behavior and interoperability requirements. The repository does not include proprietary vendor source code, firmware, binaries, or other copyrighted vendor materials. Cryptographic primitives are independently implemented from published specifications and standards, including X25519, AES-CCM, and EAP-AKA/MILENAGE as specified in 3GPP TS 35.206. This provenance statement addresses code origin only and does not grant rights under any third-party patents, trademarks, contracts, or regulatory regimes.

> **Not affiliated.** "Omnipod" and "Dexcom" are trademarks of their respective owners. This project is not manufactured, endorsed, or supported by Insulet Corporation or Dexcom, Inc. These names are used solely to identify compatibility targets.

OpenPod is provided "as is" without warranty of any kind. Use entirely at your own risk. This software is intended for research and interoperability study only. Always consult your healthcare provider before making changes to your insulin therapy.

## License

GNU General Public License v3.0 — see [LICENSE](LICENSE) for details.

## Contributing

The project is in early development. Contributions welcome once the core architecture stabilizes. See [CONTRIBUTING.md](CONTRIBUTING.md) for contribution guidelines and provenance requirements. See the [UI/UX specifications](https://github.com/spamsch/openpod-5/wiki) for the design direction.
