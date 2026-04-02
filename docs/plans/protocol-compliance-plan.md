# OpenPod Protocol Compliance Plan

Status: Draft v1
Audience: Engineering
Last updated: 2026-04-01
Primary source set: `~/Projects/personal/omnipod-connector/CLAUDE.md` and `~/Projects/personal/omnipod-connector/docs/protocol/`

## 1. Purpose

This document defines the implementation plan for bringing OpenPod into compliance with the reverse-engineered Omnipod 5 protocol currently documented in the sibling `omnipod-connector` repository.

The sequencing is intentional:

1. Adapt the emulator first
2. Make the app speak the same protocol as the emulator
3. Replace simplified transport and framing with the documented protocol stack
4. Rework activation, AID setup, reconnection, and error handling
5. Require automated evidence before any real-pod work

This order matters because the app and emulator currently interoperate through a private, simplified protocol that does not match the documented Omnipod 5 stack. If the app is changed first, the repository loses its only working integration target.

## 2. Protocol Baseline

The documented protocol stack is:

1. BLE discovery and GATT connection
2. Init command: `0x06 0x01 0x04 <controller_id>`
3. MTU request of 251
4. First-time pairing via X25519 ECDH, SHA-256 KDF, confirmation exchange, and SIM-profile-backed LTK storage
5. Reconnection via stored LTK
6. EAP-AKA mutual authentication
7. AES-CCM encrypted application traffic
8. TWICommand framing over BLE
9. Text-based RHP commands inside TWICommand payloads
10. Activation and AID setup flows with the documented state machines

Key source documents:

- `~/Projects/personal/omnipod-connector/docs/protocol/01-ble-transport.md`
- `~/Projects/personal/omnipod-connector/docs/protocol/02-pairing.md`
- `~/Projects/personal/omnipod-connector/docs/protocol/03-authentication.md`
- `~/Projects/personal/omnipod-connector/docs/protocol/04-data-protocol.md`
- `~/Projects/personal/omnipod-connector/docs/protocol/05-pod-activation.md`
- `~/Projects/personal/omnipod-connector/docs/protocol/06-pod-operations.md`
- `~/Projects/personal/omnipod-connector/docs/protocol/07-error-handling.md`

## 3. Current Mismatch Summary

OpenPod is not protocol-compliant yet. The main mismatches are:

1. The app models “RHP” as a binary opcode protocol rather than text RHP commands.
2. The emulator implements the same simplified binary protocol, so current integration tests do not validate the documented Omnipod stack.
3. The protocol framing code includes placeholder envelope implementations rather than a settled TWICommand transport layer.
4. BLE discovery and connection behavior is only partially aligned with the documented advertising and reconnection model.
5. Activation and AID setup state machines are compressed and skip documented intermediate states.
6. Error handling, disconnection reasons, and interrupted-flow recovery have not been implemented to the documented depth.
7. There is no shared golden test corpus that proves Python and Kotlin implementations match the same protocol behavior.

## 4. Target Architecture

The repository should converge on this shape:

### 4.1 Emulator

The emulator becomes the protocol reference implementation for this repo:

- Real BLE advertising and GATT characteristics
- Real init, pairing, reconnection, EAP-AKA, and AES-CCM behavior
- TWICommand transport semantics
- Text RHP parsing and formatting
- Activation and AID state fidelity
- Alert, status, and unsolicited-notification behavior

### 4.2 Android App

The Android app should split along protocol boundaries:

- `core:ble`
  - scan filtering
  - advertisement parsing
  - GATT connect/disconnect
  - characteristic reads/writes/notifications
  - MTU negotiation
- `core:crypto`
  - pairing state
  - SIM profile storage
  - EAP-AKA
  - AES-CCM session encryption
- `core:protocol`
  - TWICommand message model and framing
  - RHP request builder and response parser
  - activation and AID orchestration
  - error/retry/recovery policy
- `feature:pairing` and domain layers
  - user-visible scan, connect, authenticate, activate, reconnect flows

### 4.3 Shared Test Assets

Both emulator and app should use shared protocol fixtures:

- pairing vectors
- KDF vectors
- confirmation vectors
- EAP-AKA challenge/response fixtures
- AES-CCM encrypt/decrypt fixtures
- TWICommand frame fixtures
- RHP request/response fixtures
- activation transcript fixtures
- reconnection transcript fixtures

## 5. Execution Strategy

The implementation is split into ordered phases. A later phase must not start until the earlier phase has an executable result and acceptance evidence.

### Phase 0: Freeze the Protocol Baseline

Goal: Establish a stable protocol reference before code churn.

Tasks:

1. Create a protocol-reference package in `docs/` that points to the exact source documents and the repo areas they govern.
2. Add a compliance matrix mapping protocol areas to OpenPod modules:
   - BLE transport
   - pairing
   - authentication
   - encryption
   - TWICommand framing
   - RHP command layer
   - protobuf/envelope payloads
   - activation
   - AID setup
   - operations
   - error handling
3. Mark every known placeholder implementation in code comments or issue tracking:
   - simplified RHP
   - simplified envelope/framing
   - emulator-specific TCP shortcuts
   - hardcoded controller identity
   - compressed activation flow
4. Define what “protocol compliant” means for this repository:
   - app and emulator exchange documented wire formats
   - reconnection path works
   - unsolicited notifications work
   - activation and AID setup follow documented state order
   - tests use shared fixtures

Exit criteria:

- One unambiguous source of protocol truth exists.
- Every current mismatch is tracked to a workstream.

### Phase 1: Adapt the Emulator First

Goal: Make the emulator speak the documented protocol before changing the app.

Reason:

The emulator is currently the only integration target. It must become protocol-accurate first so the Android work can be validated against a real target instead of against a matching fake.

Scope:

- `emulator/omnipod_emulator/protocol/`
- `emulator/omnipod_emulator/ble/`
- `emulator/omnipod_emulator/pod/`
- `emulator/tests/`

Tasks:

1. Replace the emulator’s simplified binary command dispatcher with a TWICommand transport implementation.
2. Replace byte-coded “RHP command types” with a text RHP parser and formatter.
3. Support:
   - GET and SET commands
   - `V` version command
   - multi-command batches separated by commas
   - `ES` success responses
   - `EG` and related error responses
   - logger and alert prefixes where documented
4. Model TWICommand metadata:
   - `commandBytes`
   - `commandId`
   - `lastMessage`
   - `messageType`
   - `notificationNumber`
5. Preserve pairing and EAP-AKA message flow, but align message sequencing and state transitions to the documented transport behavior.
6. Implement reconnection advertising behavior using controller-specific paired UUIDs after LTK persistence.
7. Update BLE advertisement data to expose:
   - manufacturer data
   - pod id bits
   - alarm and alert codes
   - status service signaling
8. Add unsolicited notification support for:
   - alerts
   - bolus progress
   - algorithm status or status snapshots where needed
9. Expand pod-state modeling for:
   - activation progress
   - second prime wait
   - CGM transmitter state
   - alert lifecycle
   - reconnection state
10. Keep the current TCP test transport only as a harness around the same protocol messages, not as a separate protocol.

Exit criteria:

- The emulator no longer depends on placeholder command opcodes.
- Emulator tests validate text RHP and TWICommand behavior.
- The emulator can run fresh pairing and reconnection using the documented protocol.

### Phase 2: Build Shared Protocol Fixtures

Goal: Prevent Python and Kotlin from diverging.

Tasks:

1. Introduce a shared fixture directory under `tests/` or `docs/test-vectors/`.
2. Add vectors for:
   - X25519 pairing
   - SHA-256 KDF split into confirmation key and LTK
   - confirmation generation and verification
   - SIM profile encode/decode
   - EAP-AKA challenge/response
   - AES-CCM payloads and nonces
   - TWICommand frame serialization
   - RHP request/response strings
3. Add transcript-style fixtures for:
   - first-time pairing
   - reconnection
   - activation phase 1
   - activation phase 2
   - AID setup
   - bolus start, progress, cancel
4. Require both Python and Kotlin tests to consume the same fixture files whenever possible.

Exit criteria:

- Python and Kotlin crypto/transport tests assert against the same vectors.
- A protocol change requires updating shared fixtures, not hand-wavy behavior.

### Phase 3: Rebuild the Android Transport Layer

Goal: Make Android speak the same transport as the emulator and the documented protocol.

Scope:

- `core/ble`
- `core/protocol`
- `feature/pairing`

Tasks:

1. Introduce a real TWICommand model in `core:protocol`.
2. Replace the placeholder/simplified protocol framer with a single transport implementation that owns:
   - command ids
   - last-message semantics
   - message type
   - notification sequence handling
   - chunking and dechunking rules
3. Remove the assumption that application payloads are binary opcode packets.
4. Ensure post-auth application traffic is:
   - RHP text
   - UTF-8 encoded
   - wrapped in TWICommand
   - encrypted via AES-CCM
5. Separate BLE chunking concerns from application-layer TWICommand concerns.
6. Eliminate duplicate framing implementations unless one is explicitly retained for lower-layer BLE chunk reassembly with clear ownership boundaries.
7. Update session orchestration so retries, timeouts, and reads happen at the proper layer.

Exit criteria:

- Android can send and receive the same TWICommand and RHP payloads as the emulator.
- Binary opcode serialization is removed from the app protocol path.

### Phase 4: Rebuild the Android RHP Layer

Goal: Replace the current binary-RHP abstraction with the documented text RHP protocol.

Scope:

- `core/protocol/src/main/kotlin/com/openpod/core/protocol/rhp/`
- command and response models

Tasks:

1. Replace opcode-driven request building with a text RHP builder:
   - `GV`
   - `G<Type>.<Attr>`
   - `S<Type>.<Attr>=<payload>`
   - multi-command concatenation
2. Add typed payload encoders:
   - boolean
   - u8
   - u32
   - u64
   - hex/raw payload encoding
3. Replace opcode-driven response parsing with a text RHP parser that can handle:
   - attribute responses
   - success responses
   - error responses
   - logger responses
   - alarm responses
   - batched responses
4. Refactor command abstractions so they map to documented type/attribute pairs instead of invented opcode enums.
5. Add a stable catalog of supported type/attribute mappings for:
   - version
   - logger/history
   - algorithm
   - CGM
   - system
6. Add unknown-command and partial-support handling so unsupported areas fail explicitly instead of being silently mis-modeled.

Exit criteria:

- `core:protocol` represents the documented text RHP protocol directly.
- Command and response parsing can round-trip protocol fixtures.

### Phase 5: Bring Android BLE Discovery and Connection into Compliance

Goal: Align BLE behavior with the documented pod discovery and reconnection model.

Scope:

- `core/ble`

Tasks:

1. Update scanning logic to distinguish:
   - unpaired scan UUIDs
   - paired controller-specific UUIDs
2. Add advertisement parsing for manufacturer-specific data:
   - company id
   - pod id bits
   - alarm code
   - alert code
3. Derive the paired advertising UUID set from the current controller id instead of scanning only by service UUID.
4. Update connection setup to:
   - send init command immediately after service discovery
   - request MTU 251
   - subscribe to TpClassic and TpFast as needed
5. Add explicit connection substate tracking for:
   - connected/discovery
   - read/write ready
   - app mode / secure mode
6. Add reconnect behavior and state resumption rules to match the protocol docs.
7. Add BLE integration tests using the emulator for:
   - discovery
   - connect
   - init
   - MTU negotiation
   - reconnection advertisement detection

Exit criteria:

- Android scan/connect behavior matches the documented transport model.
- Reconnection no longer depends on generic service scanning.

### Phase 6: Bring Pairing, SIM Profile, and EAP-AKA into Strict Alignment

Goal: Ensure Kotlin crypto/session behavior matches the recovered protocol closely enough to interoperate with the emulator and later with hardware testing.

Scope:

- `core/crypto`
- `feature/pairing`

Tasks:

1. Remove hardcoded controller identity from runtime crypto/session paths.
2. Define a controller-id source of truth and thread it through:
   - scan
   - connect
   - pairing
   - reconnection UUID derivation
   - SIM profile storage
3. Verify KDF component ordering and role semantics against the protocol notes.
4. Verify confirmation value construction matches phone and pod role ordering.
5. Verify SIM profile storage layout and recovery:
   - masked LTK
   - firmware id
   - controller id
   - xor mask
6. Separate durable pairing state from ephemeral session state.
7. Implement session load/resume semantics explicitly for reconnection.
8. Expand tests for:
   - fresh pairing
   - confirmation mismatch
   - missing LTK
   - SIM profile reload
   - EAP-AKA failure and resync behavior
   - sequence rollback or nonce mismatch behavior if documented

Exit criteria:

- Kotlin crypto passes the shared fixtures.
- Reconnection uses stored LTK and controller identity correctly.

### Phase 7: Rework Activation and AID Setup to Match the Documented State Machines

Goal: Replace the compressed activation flow with the documented one.

Scope:

- `core/protocol/activation`
- feature/domain orchestration
- emulator activation state

Tasks:

1. Update activation states to include all documented phase 1 and phase 2 steps.
2. Distinguish these steps explicitly:
   - got version
   - set pod uid
   - low reservoir alerts programmed
   - loss-of-communication alert reprogrammed
   - primed pump
   - user-set expiration alert programmed
   - basal programmed
   - cancel/loss-of-communication/etc alerts programmed
   - inserted cannula
   - second prime wait finished
   - algorithm and CGM driver enabled
   - CGM transmitter id sent
   - got final status
   - activated
3. Split “configure AID” into real step-specific RHP commands and payload builders.
4. Support CGM-specific branches:
   - no CGM
   - Dexcom G6
   - Dexcom G7
   - Libre 2 if still in scope
5. Track activation attempt count and failure handling.
6. Add transcript-based tests for:
   - happy path activation
   - interrupted activation
   - wrong-state command
   - CGM-specific activation branch
   - final status verification

Exit criteria:

- Activation order matches the documented state machine.
- AID setup is not represented as a generic byte-array placeholder.

### Phase 8: Implement Pod Operations, Notifications, and Recovery Behavior

Goal: Support normal runtime operations with protocol-correct behavior.

Scope:

- `core/protocol`
- `core/domain`
- `feature/dashboard`
- `feature/bolus`
- emulator operations

Tasks:

1. Implement the supported operational commands on top of text RHP:
   - status
   - algorithm status
   - IOB
   - UTC
   - CGM transmitter data
   - bolus
   - basal/temp basal/resume/stop
   - deactivate
2. Support unsolicited notifications and progress updates.
3. Implement error parsing and mapping to domain failures.
4. Add interrupted-flow recovery rules:
   - command timeout
   - disconnect during in-flight command
   - reconnect and session reload
   - replay or requery behavior
5. Add disconnection-reason mapping from the protocol docs where implementable in app logic.
6. Add test scenarios for:
   - bolus start and progress
   - cancel bolus
   - timeout/retry
   - reconnect after disconnect
   - resumed status polling

Exit criteria:

- Runtime operations use the same transport and command semantics as activation.
- Recovery behavior is explicit and test-covered.

### Phase 9: Replace Emulator-Specific App Shortcuts

Goal: Remove architecture that exists only because the emulator used a custom protocol.

Scope:

- `feature/pairing/domain/EmulatorPodManager.kt`
- domain-level abstractions
- UI test harnesses

Tasks:

1. Replace raw TCP command semantics with a protocol-faithful transport adapter used only for tests.
2. Ensure the emulator path and BLE path differ only in the bottom transport adapter, not in command semantics.
3. Remove duplicated activation logic in emulator-facing app code where the shared protocol stack can be reused.
4. Keep deterministic local integration tests, but make them validate the real protocol messages.

Exit criteria:

- The test transport is a transport swap, not a protocol fork.

### Phase 10: Verification Gate Before Real-Pod Work

Goal: Require evidence before any hardware testing.

Tasks:

1. Define a mandatory protocol test suite:
   - emulator unit tests
   - Kotlin unit tests
   - shared fixture tests
   - Android integration tests against emulator
2. Require green results for:
   - fresh pairing
   - reconnection
   - activation
   - AID setup
   - status
   - bolus
   - cancel
   - disconnect/recovery
3. Publish a protocol conformance checklist in CI output or docs.
4. Only after the full emulator-backed suite is green should the BLE transport be exercised against real hardware.

Exit criteria:

- There is a repeatable, automated proof that app and emulator agree on protocol behavior.

## 6. Repository Workstreams

### 6.1 Emulator Workstream

Primary files/modules likely to change:

- `emulator/omnipod_emulator/protocol/session.py`
- `emulator/omnipod_emulator/protocol/commands.py`
- `emulator/omnipod_emulator/protocol/activation.py`
- `emulator/omnipod_emulator/protocol/pairing.py`
- `emulator/omnipod_emulator/ble/server.py`
- `emulator/omnipod_emulator/ble/constants.py`
- `emulator/omnipod_emulator/pod/state.py`
- `emulator/tests/`

Expected outcome:

- Emulator becomes the canonical protocol target for the repo.

### 6.2 Android Protocol Workstream

Primary files/modules likely to change:

- `core/protocol/src/main/kotlin/com/openpod/core/protocol/rhp/`
- `core/protocol/src/main/kotlin/com/openpod/core/protocol/session/`
- `core/protocol/src/main/kotlin/com/openpod/core/protocol/activation/`
- `core/protocol/src/main/kotlin/com/openpod/core/protocol/command/`

Expected outcome:

- Android protocol layer stops modeling an invented binary protocol.

### 6.3 Android BLE Workstream

Primary files/modules likely to change:

- `core/ble/src/main/kotlin/com/openpod/core/ble/BleConstants.kt`
- `core/ble/src/main/kotlin/com/openpod/core/ble/KablePodScanner.kt`
- `core/ble/src/main/kotlin/com/openpod/core/ble/KablePodConnection.kt`
- `core/ble/src/main/kotlin/com/openpod/core/ble/EnvelopeFramer.kt`

Expected outcome:

- Scan, connect, and reconnect behavior align with the protocol docs.

### 6.4 Crypto Workstream

Primary files/modules likely to change:

- `core/crypto/src/main/kotlin/com/openpod/core/crypto/PureKotlinCryptoManager.kt`
- `core/crypto/src/main/kotlin/com/openpod/core/crypto/pure/`
- `core/crypto/src/test/kotlin/com/openpod/core/crypto/`

Expected outcome:

- Pairing and session state align with the documented role semantics and durable storage layout.

### 6.5 App Orchestration Workstream

Primary files/modules likely to change:

- `feature/pairing/`
- `core/domain/`
- `feature/dashboard/`
- `feature/bolus/`

Expected outcome:

- UI and domain flows reflect protocol-correct activation and runtime behavior.

## 7. Acceptance Criteria for “Protocol Compliant”

The repository should not be called protocol-compliant until all of the following are true:

1. The emulator and Android app exchange TWICommand frames carrying text RHP payloads.
2. Fresh pairing works with X25519, KDF, confirmation, LTK persistence, EAP-AKA, and AES-CCM.
3. Reconnection works using stored LTK and controller-specific advertising UUIDs.
4. Activation follows the documented state order, including intermediate alert and second-prime steps.
5. AID setup uses step-specific command builders and response handling.
6. Normal operations and unsolicited notifications are supported over the same transport.
7. Error responses, retries, and disconnect recovery are implemented and tested.
8. Shared fixtures prove Python and Kotlin behavior match.
9. Emulator-backed Android tests cover end-to-end pairing, activation, and operations.

## 8. Implementation Risks

1. The protocol docs may still omit some transport details, especially around TWICommand serialization and notification numbering. Those unknowns must be resolved from the decompiled code before claiming full compliance.
2. BLE adapter and Bumble behavior may differ from real pods. The emulator should therefore model protocol semantics exactly, but real-pod risk remains until hardware validation.
3. Activation and CGM integration have multiple branch paths. The initial compliant target should prioritize the smallest fully documented branch, then expand.
4. The current codebase duplicates framing and session responsibilities across modules. Refactoring boundaries before feature expansion will reduce later protocol drift.

## 9. Recommended Milestone Order

1. Emulator transport and RHP rewrite
2. Shared fixtures
3. Android TWICommand transport
4. Android text RHP rewrite
5. BLE scan/connect/reconnect compliance
6. Crypto/session alignment
7. Activation and AID rework
8. Operations and recovery
9. Remove emulator-specific shortcuts
10. Run full conformance suite

This is the shortest path that preserves a working integration target throughout the migration.
