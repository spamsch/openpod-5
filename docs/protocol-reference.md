# Protocol Reference

Maps the documented Omnipod 5 protocol layers to OpenPod source modules.

## Source Documents

All protocol documentation lives in the sibling repository:

| Document | Path | Scope |
|----------|------|-------|
| BLE Transport | `~/Projects/personal/omnipod-connector/docs/protocol/01-ble-transport.md` | Discovery, GATT, MTU, chunking |
| Pairing | `~/Projects/personal/omnipod-connector/docs/protocol/02-pairing.md` | X25519 ECDH, KDF, confirmation, LTK/SIM profile |
| Authentication | `~/Projects/personal/omnipod-connector/docs/protocol/03-authentication.md` | EAP-AKA, session keys, AES-CCM init |
| Data Protocol | `~/Projects/personal/omnipod-connector/docs/protocol/04-data-protocol.md` | TWICommand framing, text RHP, protobuf envelope |
| Pod Activation | `~/Projects/personal/omnipod-connector/docs/protocol/05-pod-activation.md` | Phase 1/2 state machines, AID setup |
| Pod Operations | `~/Projects/personal/omnipod-connector/docs/protocol/06-pod-operations.md` | Runtime commands, reconnection, CGM |
| Error Handling | `~/Projects/personal/omnipod-connector/docs/protocol/07-error-handling.md` | Disconnection reasons, recovery flows |

## Compliance Matrix

| Protocol Layer | Emulator Module | Android Module | Status |
|----------------|----------------|----------------|--------|
| BLE discovery & advertising | `emulator/.../ble/server.py`, `constants.py` | `core/ble/KablePodScanner.kt`, `BleConstants.kt` | Partial — no manufacturer data, no paired UUIDs |
| GATT connect & init | `emulator/.../ble/server.py` | `core/ble/KablePodConnection.kt` | Partial — init command works, MTU hardcoded |
| X25519 pairing | `emulator/.../protocol/pairing.py` | `core/crypto/pure/X25519KeyExchange.kt`, `OmnipodKdf.kt` | Compliant |
| LTK / SIM profile | `emulator/.../protocol/pairing.py` | `core/crypto/pure/SimProfileStore.kt` | Compliant |
| EAP-AKA authentication | `emulator/.../crypto/eap_aka.py` | `core/crypto/pure/EapAkaAuthenticator.kt` | Compliant |
| AES-CCM encryption | `emulator/.../crypto/aes_ccm.py` | `core/crypto/pure/AesCcm.kt` | Compliant |
| TWICommand framing | **Not implemented** | **Not implemented** | Non-compliant — no TWICommand model |
| Text RHP commands | **Not implemented** | **Not implemented** | Non-compliant — uses binary opcodes |
| Protobuf envelope | `emulator` (none) | `core/protocol/framing/EnvelopeFramer.kt` | Placeholder |
| Activation state machine | `emulator/.../protocol/activation.py` | `core/protocol/activation/ActivationStateMachine.kt` | Partial — compressed, missing intermediate states |
| AID setup | **Not implemented** | `core/protocol/activation/AidSetupStateMachine.kt` | Placeholder — generic byte-array steps |
| Pod operations | `emulator/.../protocol/activation.py` (bolus/stop) | `core/protocol/command/PodCommand.kt` | Non-compliant — binary opcodes |
| Error handling & recovery | **Not implemented** | **Not implemented** | Non-compliant |

## Known Placeholders

These code areas use a simplified binary protocol that does not match the documented Omnipod 5 stack:

| File | Issue |
|------|-------|
| `emulator/.../protocol/commands.py` | Binary opcode enum (0x01-0x0F) instead of text RHP |
| `emulator/.../protocol/activation.py` | Compressed activation states, missing intermediate steps |
| `emulator/.../protocol/session.py` | Dispatches raw decrypted bytes directly to binary command handler; no TWICommand layer |
| `core/protocol/.../rhp/RhpCommandBuilder.kt` | Binary opcode serialization instead of text RHP |
| `core/protocol/.../rhp/RhpCommandParser.kt` | Binary opcode parsing instead of text RHP |
| `core/protocol/.../rhp/RhpOpcode.kt` | Invented opcode enum, not documented protocol |
| `core/protocol/.../framing/EnvelopeFramer.kt` | Simplified envelope, not TWICommand |
| `core/protocol/.../session/PodSession.kt` | Sends binary RHP through simplified envelope |
| `core/ble/.../EnvelopeFramer.kt` | BLE-level chunking separate from protocol framing — ownership unclear |
| `feature/pairing/.../EmulatorPodManager.kt` | Full protocol fork for TCP emulator path |

## What "Protocol Compliant" Means

1. App and emulator exchange TWICommand frames carrying UTF-8 text RHP payloads
2. Fresh pairing works: X25519 ECDH, SHA-256 KDF, confirmation, LTK persistence, EAP-AKA, AES-CCM
3. Reconnection uses stored LTK and controller-specific advertising UUIDs
4. Activation follows documented state order including intermediate alert and second-prime steps
5. AID setup uses step-specific RHP command builders
6. Normal operations and unsolicited notifications use the same transport
7. Error responses, retries, and disconnect recovery are implemented and tested
8. Shared fixtures prove Python and Kotlin behavior match
