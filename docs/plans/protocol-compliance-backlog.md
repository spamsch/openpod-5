# Protocol Compliance Backlog

Status: Draft v1
Depends on: [protocol-compliance-plan.md](/Users/spamies/Projects/personal/openpod-5/docs/protocol-compliance-plan.md)

## 1. How To Use This Backlog

This file turns the protocol compliance plan into executable work items. The ordering is dependency-driven, not team-structure-driven.

Rules:

1. Start with the emulator.
2. Do not rewrite Android RHP until the emulator has a text-RHP implementation.
3. Do not start real-pod work from this branch until the conformance suite is green.

## 2. Phase-by-Phase Checklist

## Phase 0: Baseline and Audit

### P0.1 Protocol reference mapping

- Add a simple reference section to repo docs pointing to:
  - `~/Projects/personal/omnipod-connector/CLAUDE.md`
  - protocol documents `01` through `07`
- Record which OpenPod modules correspond to each protocol layer.

Deliverable:

- A stable protocol-source map in `docs/`.

### P0.2 Mark current placeholders

- Annotate or track these areas as non-compliant:
  - `core/protocol/.../RhpCommandBuilder.kt`
  - `core/protocol/.../RhpCommandParser.kt`
  - `core/protocol/.../framing/EnvelopeFramer.kt`
  - `emulator/omnipod_emulator/protocol/commands.py`
  - `feature/pairing/.../EmulatorPodManager.kt`

Deliverable:

- No ambiguity about which code is temporary.

## Phase 1: Emulator First

### P1.1 Introduce TWICommand model in Python

Files:

- `emulator/omnipod_emulator/protocol/session.py`
- new module if needed under `emulator/omnipod_emulator/protocol/`

Tasks:

- Define a Python `TWICommand` model.
- Implement serialization and parsing for send/receive paths.
- Carry:
  - command bytes
  - command id
  - last-message flag
  - message type
  - notification number

Acceptance:

- Emulator tests can round-trip TWICommand frames.

### P1.2 Replace simplified RHP dispatcher

Files:

- `emulator/omnipod_emulator/protocol/commands.py`

Tasks:

- Replace byte-coded command enums with:
  - text request parser
  - typed request model
  - response formatter
- Support:
  - `GV`
  - `Gx.y`
  - `Sx.y=value`
  - comma-batched commands
  - `ES...=0`
  - `EG...=<code>`
  - logger and alert prefixes as documented

Acceptance:

- Emulator no longer branches on placeholder command-type bytes.

### P1.3 Align session flow with documented stack

Files:

- `emulator/omnipod_emulator/protocol/session.py`

Tasks:

- Preserve init, pairing, and auth sequence.
- Ensure encrypted application data path is:
  - decrypt AES-CCM
  - parse TWICommand
  - parse text RHP
  - dispatch
  - format text response
  - wrap in TWICommand
  - encrypt

Acceptance:

- Emulator logs and tests show the documented layer stack, not a custom one.

### P1.4 Reconnection advertising

Files:

- `emulator/omnipod_emulator/ble/constants.py`
- `emulator/omnipod_emulator/ble/server.py`
- `emulator/omnipod_emulator/protocol/session.py`

Tasks:

- After LTK save, change advertising behavior to controller-specific paired UUIDs.
- Preserve unpaired UUIDs before pairing.

Acceptance:

- Emulator can advertise as unpaired before pairing and as paired afterward.

### P1.5 Advertisement data fidelity

Files:

- `emulator/omnipod_emulator/ble/server.py`
- `emulator/omnipod_emulator/pod/state.py`

Tasks:

- Add manufacturer data fields:
  - company id
  - 3-byte pod id fragment
  - alarm code
  - alert code
- Add service-type signaling for “has data”, “has alert”, and related states if supported by Bumble.

Acceptance:

- Android scan tests can validate advertisement content against documented expectations.

### P1.6 Activation fidelity in emulator

Files:

- `emulator/omnipod_emulator/protocol/activation.py`
- `emulator/omnipod_emulator/pod/state.py`

Tasks:

- Add explicit intermediate activation states:
  - low reservoir alerts programmed
  - loss-of-communication alert reprogrammed
  - expiration alert programmed
  - cancel/loss-of-communication/etc alerts programmed
  - second-prime wait finished
  - CGM transmitter id step
- Add AID setup state progression.

Acceptance:

- Emulator activation transcript matches the documented state order.

### P1.7 Emulator tests

Files:

- `emulator/tests/`

Tasks:

- Add unit tests for:
  - TWICommand framing
  - text RHP parsing
  - batched commands
  - success/error responses
  - paired/unpaired advertising transitions
  - activation state ordering
  - reconnection path

Acceptance:

- Emulator phase has its own green suite before Android protocol rewrite starts.

## Phase 2: Shared Fixtures

### P2.1 Shared vector location

Files:

- new directory under `tests/` or `docs/`

Tasks:

- Create a canonical location for protocol fixtures.
- Version them in source control.

### P2.2 Crypto vectors

Tasks:

- Add:
  - shared secret input/output vectors
  - KDF output vectors
  - confirmation vectors
  - masked SIM profile vectors
  - EAP-AKA vectors
  - AES-CCM vectors

Acceptance:

- Kotlin and Python test code read the same vector data.

### P2.3 Transcript fixtures

Tasks:

- Add request/response transcripts for:
  - fresh pair
  - reconnect
  - activation
  - AID setup
  - status
  - bolus

Acceptance:

- End-to-end tests compare actual traffic against stored transcripts.

## Phase 3: Android Transport Rewrite

### P3.1 Introduce Kotlin TWICommand layer

Files:

- `core/protocol/src/main/kotlin/com/openpod/core/protocol/session/`
- `core/protocol/src/main/kotlin/com/openpod/core/protocol/framing/`

Tasks:

- Add a real TWICommand model.
- Add serializer/parser.
- Move transport-specific metadata out of ad hoc framing code.

Acceptance:

- Android has a first-class TWICommand abstraction.

### P3.2 Remove placeholder envelope ownership confusion

Files:

- `core/protocol/.../framing/EnvelopeFramer.kt`
- `core/ble/.../EnvelopeFramer.kt`

Tasks:

- Decide which layer owns:
  - BLE chunking
  - message framing
  - application payload framing
- Delete or replace the placeholder implementation.

Acceptance:

- Only one authoritative framing stack exists for each layer.

### P3.3 Session orchestration rewrite

Files:

- `core/protocol/.../PodSession.kt`
- `core/protocol/.../CryptoManager.kt`

Tasks:

- Make session send path:
  - build RHP text
  - wrap in TWICommand
  - encrypt
  - chunk/write over BLE
- Make receive path inverse of send path.
- Handle retries and timeouts at the correct abstraction layer.

Acceptance:

- `PodSession` no longer assumes binary RHP packets.

## Phase 4: Android RHP Rewrite

### P4.1 Request builder rewrite

Files:

- `core/protocol/.../rhp/RhpCommandBuilder.kt`

Tasks:

- Rebuild request generation around text commands and typed payload encoders.

Acceptance:

- Unit tests assert exact command strings or bytes for supported requests.

### P4.2 Response parser rewrite

Files:

- `core/protocol/.../rhp/RhpCommandParser.kt`

Tasks:

- Parse text responses and response batches.
- Distinguish:
  - normal attribute response
  - set success
  - error response
  - logger response
  - alarm response

Acceptance:

- Parser can consume shared fixture transcripts from the emulator.

### P4.3 Command model refactor

Files:

- `core/protocol/.../command/PodCommand.kt`
- `core/protocol/.../command/PodResponse.kt`

Tasks:

- Stop modeling commands as invented opcode carriers.
- Model them as domain commands that compile to type/attribute-based RHP.
- Add explicit unsupported/unknown cases if necessary.

Acceptance:

- Domain commands map cleanly to documented RHP semantics.

## Phase 5: Android BLE Compliance

### P5.1 Scanning

Files:

- `core/ble/.../BleConstants.kt`
- `core/ble/.../KablePodScanner.kt`

Tasks:

- Add paired UUID derivation from controller id.
- Stop treating service UUID scanning as reconnection logic.
- Parse manufacturer-specific data into a richer discovered-pod model.

Acceptance:

- Scanner can find unpaired and paired pods using the documented UUID patterns.

### P5.2 Connection

Files:

- `core/ble/.../KablePodConnection.kt`

Tasks:

- Request MTU 251.
- Track connection substates.
- Subscribe to TpClassic and TpFast as appropriate.
- Surface “securely connected” state to the upper layer.

Acceptance:

- BLE connect/init flow matches the documented sequence.

## Phase 6: Kotlin Crypto Alignment

### P6.1 Controller identity plumbing

Files:

- `core/crypto/.../PureKotlinCryptoManager.kt`
- `feature/pairing/...`

Tasks:

- Remove hardcoded controller id.
- Persist and reuse a real controller id.

Acceptance:

- Paired UUID derivation and SIM profile storage use the same controller identity.

### P6.2 KDF and confirmation verification

Files:

- `core/crypto/src/main/kotlin/com/openpod/core/crypto/pure/`

Tasks:

- Verify role ordering in KDF inputs.
- Verify phone/pod confirmation data ordering.

Acceptance:

- Shared vectors pass in Kotlin and Python.

### P6.3 SIM profile and session resume

Files:

- `core/crypto/.../SimProfileStore.kt`
- `core/crypto/.../PureKotlinCryptoManager.kt`

Tasks:

- Confirm storage layout and retrieval behavior.
- Separate pair persistence from auth-session persistence.

Acceptance:

- Reconnection survives app restart in tests.

## Phase 7: Activation and AID Setup

### P7.1 Activation state machine rewrite

Files:

- `core/protocol/.../activation/ActivationStateMachine.kt`

Tasks:

- Add documented intermediate states and step-specific commands.

Acceptance:

- State order matches protocol docs and emulator transcripts.

### P7.2 AID setup command builders

Files:

- `core/protocol/.../activation/AidSetupStateMachine.kt`
- `core/protocol/.../rhp/`

Tasks:

- Replace generic byte-array placeholders with typed builders for:
  - UTC
  - TDI
  - target BG profile
  - correction factor profile
  - DIA
  - EGV threshold
  - insulin history
  - final status query

Acceptance:

- AID setup is expressed in protocol terms, not generic blobs.

### P7.3 CGM-specific branches

Files:

- activation/domain/UI orchestration files

Tasks:

- Model no-CGM, G6, G7, and any in-scope branches explicitly.

Acceptance:

- Activation path is selected by actual CGM configuration.

## Phase 8: Operations and Recovery

### P8.1 Runtime commands

Files:

- protocol, domain, dashboard, bolus modules

Tasks:

- Implement status, algorithm status, IOB, bolus, cancel, temp basal, resume, deactivate using protocol-correct requests.

Acceptance:

- Runtime features no longer depend on the old emulator protocol.

### P8.2 Notifications and progress

Tasks:

- Add unsolicited-notification handling path.
- Support bolus progress and alerts.

Acceptance:

- Dashboard and bolus flows can react to unsolicited pod events.

### P8.3 Disconnect and retry recovery

Tasks:

- Implement:
  - timeout handling
  - disconnect mapping
  - reconnect and session reload
  - status requery after reconnect

Acceptance:

- Failure behavior is deterministic and tested.

## Phase 9: Remove Emulator-Only Forks

### P9.1 Replace protocol-forked test transport

Files:

- `feature/pairing/.../EmulatorPodManager.kt`

Tasks:

- Make emulator integration use the same protocol stack with a transport adapter.
- Remove raw TCP-specific protocol assumptions from app code.

Acceptance:

- The emulator path is a transport substitution, not a separate protocol implementation.

## Phase 10: Conformance Gate

### P10.1 Required automated suites

- Python emulator unit tests
- Kotlin crypto tests
- Kotlin protocol tests
- Android integration tests against emulator
- transcript/fixture validation

### P10.2 Required scenarios

- fresh pairing
- reconnection
- activation
- AID setup
- status polling
- bolus start
- bolus progress
- cancel bolus
- disconnect and recovery

### P10.3 Stop condition before real pods

Do not move to hardware testing until:

1. All required suites are green
2. Shared fixtures are stable
3. The app and emulator exchange protocol-accurate traffic
4. Activation and reconnection are both proven end-to-end

## 3. Suggested Delivery Order By Pull Request

1. Emulator TWICommand transport
2. Emulator text RHP parser/formatter
3. Emulator activation fidelity
4. Shared fixtures
5. Android TWICommand layer
6. Android text RHP builder/parser
7. Android BLE scan/connect/reconnect rewrite
8. Kotlin crypto/session alignment
9. Activation/AID rewrite
10. Operations/notifications/recovery
11. Emulator-only shortcut removal
12. Conformance suite hardening

This ordering keeps the repo testable throughout the migration.
