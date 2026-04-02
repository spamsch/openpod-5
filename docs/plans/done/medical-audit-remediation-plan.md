# OpenPod Medical Audit Remediation Plan

Status: Draft v1
Audience: Engineering, product, and safety review
Source: Repository audit performed on 2026-04-01

## 1. Purpose

This document turns the current audit findings into an execution plan for bringing the repository closer to the stated medical-quality expectations:

- Reliable traceability of insulin-delivery actions
- Defensive fail-safe behavior at delivery boundaries
- Test coverage for safety-critical code paths
- Accurate and internally consistent documentation
- Full i18n compliance for English and German from the start

The priority order is deliberate:

1. Fix traceability and delivery safety first
2. Add tests around those guarantees
3. Remove documentation drift
4. Complete i18n cleanup and enforcement

## 2. Findings To Remediate

The audit identified these primary gaps:

1. The repository does not currently implement the append-only, checksummed audit trail claimed in project documentation.
2. Bolus delivery is not durably recorded by the bolus flow itself.
3. Bolus delivery lacks final-state safety validation immediately before insulin command dispatch.
4. Test coverage is concentrated in `core:model` and `core:crypto`; most app and protocol modules have no unit tests.
5. i18n is incomplete: hardcoded English remains in multiple feature modules and some user-facing formatting is locale-unsafe.
6. Documentation still mixes old JNI/native-crypto descriptions with the new pure-Kotlin crypto implementation.

## 3. Workstreams

### 3.1 Audit Trail And Traceability

Goal: Every insulin-delivery action and relevant state transition must be reconstructable from persisted records, not only logs.

Scope:

- `core:audit`
- `core:database`
- `core:data`
- `feature:bolus`
- `feature:pairing`
- `feature:dashboard`

Tasks:

1. Implement a real audit subsystem in `core:audit` instead of leaving the module as scaffolding.
2. Define an `AuditEvent` model with:
   - Stable event id
   - Event category
   - UTC timestamp
   - Actor/source
   - Clinical context snapshot
   - Payload hash
   - Previous-event hash
   - Record checksum / chain hash
3. Extend persistence beyond the current flexible `history_event` table or replace it with a dedicated immutable audit table.
4. Remove destructive semantics from the audit log path:
   - No generic `deleteAll()` capability for audit records
   - If data reset is required for development, separate developer reset behavior from production audit behavior
5. Record explicit bolus lifecycle events:
   - Request created
   - Preconditions validated
   - Command dispatched
   - Pod acknowledged
   - Progress observed
   - Completed / cancelled / failed
6. Ensure traceability is initiated from the bolus flow itself, not only from emulator polling.
7. Persist enough metadata to reconstruct:
   - Requested dose
   - Delivered dose
   - Carbs entered
   - Glucose used
   - IOB used
   - Pod id / session id
   - Reservoir at command time
   - Authentication status
   - Cancellation reason or failure cause
8. Add read APIs for audit review and incident investigation.

Acceptance criteria:

- A bolus can be reconstructed end-to-end from persisted audit records alone.
- Audit records are append-only at the application level.
- Checksum or hash-chain verification exists and is test-covered.

### 3.2 Bolus Delivery Safety Gates

Goal: No insulin command should be sent when system state is ambiguous, stale, or clinically unsafe.

Scope:

- `feature:bolus`
- `core:domain`
- `core:model`
- `core:protocol`
- `feature:pairing`

Tasks:

1. Introduce an explicit pre-delivery validation step in the bolus flow.
2. Define a single safety validator in domain/model code rather than ad hoc checks in UI state.
3. Validate at minimum:
   - Dose is within pod and app guardrails
   - Dose is aligned to valid pulse increments
   - Pod is connected
   - Session is authenticated and active
   - Pod is activated and not expired
   - Reservoir is sufficient for requested dose
   - No conflicting delivery is already in progress
   - Required status snapshot is recent enough
   - Required values used in calculation are not stale or ambiguous
4. Re-run validation immediately before `sendBolus()`, even if review previously passed.
5. Return typed failure reasons to the UI so blocked delivery is explicit and auditable.
6. Ensure cancellation and completion paths also record validated final state.

Acceptance criteria:

- A bolus cannot be dispatched when any precondition is unknown or stale.
- Safety rejections are deterministic, user-visible, and persisted in audit history.
- Delivery logic is centralized and unit-tested.

### 3.3 Tests And Verification

Goal: Safety-critical behavior has direct automated evidence.

Scope:

- `core:model`
- `core:domain`
- `core:data`
- `core:datastore`
- `core:protocol`
- `core:ble`
- `feature:bolus`
- `feature:pairing`

Tasks:

1. Add unit-test support to modules that currently have no tests:
   - `core:data`
   - `core:datastore`
   - `core:protocol`
   - `core:ble`
   - `feature:bolus`
   - `feature:pairing`
2. Add dedicated tests for `BolusCalculator`.
3. Add tests for bolus safety validation covering:
   - Valid meal bolus
   - Valid correction bolus
   - Excessive dose
   - Negative/invalid input
   - Stale status
   - No pod / disconnected pod
   - Insufficient reservoir
   - Existing bolus in progress
   - Expired pod
   - Ambiguous status response
4. Add tests for bolus ViewModel orchestration:
   - Review gating
   - PIN flow
   - Safety rejection
   - Audit write on success
   - Audit write on failure
   - Cancellation behavior
5. Add repository tests for audit persistence and hash-chain verification.
6. Add protocol and BLE tests for timeout, retry, parse-failure, and malformed-response paths.
7. Add datastore tests for:
   - Preference migration
   - corrupted-value fallback
   - PIN storage and verification
   - data reset boundaries
8. Add emulator integration checks to CI in addition to Gradle tests.
9. Publish a test matrix in docs so expected coverage is explicit.

Coverage targets:

- Domain/model safety logic: >90%
- Protocol/BLE/data boundary layers: >70%
- Feature delivery orchestration: meaningful scenario coverage, not line-count theater

Acceptance criteria:

- All safety validators and audit-chain logic are directly unit-tested.
- CI runs both Gradle tests and emulator tests.
- Green CI implies meaningful coverage of delivery-critical behavior.

### 3.4 Documentation And Architecture Consistency

Goal: Docs and KDoc must describe the implementation that actually exists.

Scope:

- `README.md`
- `docs/`
- KDoc in `core:domain`, `core:protocol`, `feature:pairing`, and crypto-adjacent modules

Tasks:

1. Remove or update all stale references to JNI / `libc3ec87.so` where the implementation is now pure Kotlin.
2. Align these artifacts first:
   - `README.md`
   - `core/domain/.../PodManager.kt`
   - `core/protocol/.../CryptoManager.kt`
   - `feature/pairing/.../EmulatorPodManager.kt`
3. Document the actual safety model for bolus delivery:
   - Preconditions
   - Failure behavior
   - Audit behavior
   - Cancellation semantics
4. Add architecture docs for:
   - Audit subsystem
   - Delivery validation pipeline
   - Event model and traceability guarantees
5. Add module-level developer notes for how emulator tests, app tests, and future real-pod tests fit together.
6. Review public APIs for missing or misleading KDoc and correct them.

Acceptance criteria:

- No public docs describe a crypto architecture that the code no longer uses.
- Safety-critical flows have architecture notes and public API docs.
- README claims are supportable by the implementation.

### 3.5 i18n Completion And Enforcement

Goal: All user-facing text is resource-backed, translated, and locale-aware.

Scope:

- `app`
- `core:ui`
- `feature:bolus`
- `feature:settings`
- `feature:history`
- `feature:onboarding`
- `feature:pairing`

Tasks:

1. Replace all remaining hardcoded user-facing strings in Kotlin/Compose with resource lookups.
2. Add missing `values-de/strings.xml` where the module currently lacks one.
3. Complete resource coverage for:
   - `app`
   - `feature:bolus`
   - `feature:settings`
   - `feature:history`
4. Replace locale-forced formatting in user-facing UI with locale-aware formatting.
5. Replace custom date/time patterns where needed with locale-sensitive formatters or explicitly documented clinical formatting rules.
6. Review accessibility content descriptions separately from visible labels; both must be localized.
7. Add static checks or tests to prevent regression:
   - search-based check for hardcoded `Text("...")`
   - resource parity check between `values` and `values-de`
8. Define policy for universal medical units:
   - values translated around them
   - units themselves remain clinically standard where required

Acceptance criteria:

- No user-visible English literals remain in code.
- English and German resource files exist for every feature surface.
- Number/date formatting respects device locale where clinically acceptable.

## 4. Execution Order

Recommended implementation sequence:

### Phase 1: Safety Foundation

1. Build the audit event model and persistence path
2. Add bolus pre-dispatch safety validator
3. Wire bolus flow to durable audit writes
4. Add unit tests for validator and bolus lifecycle persistence

### Phase 2: Delivery And Protocol Hardening

1. Expand protocol/BLE/data tests
2. Add failure-mode tests around timeouts, parse failures, and ambiguous state
3. Ensure cancellation and retry behavior are audited and test-covered

### Phase 3: Documentation Truthfulness

1. Fix README and stale KDoc
2. Add architecture documentation for audit and safety flows
3. Publish test matrix and safety assumptions

### Phase 4: i18n Cleanup

1. Remove hardcoded strings from settings, bolus, and history
2. Add missing German resources
3. Replace locale-unsafe formatting
4. Add regression checks

## 5. Suggested Task Breakdown

### Epic A: Audit Infrastructure

- A1. Create `AuditEvent` domain model
- A2. Create immutable audit storage schema
- A3. Add checksum / hash-chain verification
- A4. Add audit repository + tests

### Epic B: Bolus Safety

- B1. Introduce `BolusSafetyValidator`
- B2. Gate bolus dispatch on typed validation result
- B3. Persist bolus lifecycle audit events
- B4. Add bolus ViewModel tests

### Epic C: Coverage Expansion

- C1. Enable test dependencies in uncovered modules
- C2. Add `BolusCalculator` tests
- C3. Add protocol / BLE failure-mode tests
- C4. Add emulator suite to CI

### Epic D: Documentation Alignment

- D1. Remove obsolete JNI/native references
- D2. Add delivery safety architecture doc
- D3. Add audit architecture doc

### Epic E: i18n

- E1. Migrate hardcoded settings strings to resources
- E2. Migrate hardcoded bolus strings to resources
- E3. Migrate hardcoded history strings to resources
- E4. Add German resource parity and locale-formatting fixes

## 6. Definition Of Done

This audit is considered materially remediated only when all of the following are true:

1. Bolus delivery is blocked on explicit, centralized safety validation.
2. Every insulin-delivery action is durably and append-only traceable.
3. Audit records support checksum or hash-chain verification.
4. Safety-critical logic is directly unit-tested.
5. CI runs both Android/Kotlin tests and emulator tests.
6. Public documentation matches the actual crypto and safety architecture.
7. All user-facing strings are localized via resources in English and German.

## 7. Immediate Next Actions

Recommended next implementation steps:

1. Design the audit schema and repository API.
2. Implement bolus pre-dispatch validation in domain/model code.
3. Add tests for bolus validation and bolus lifecycle persistence before expanding UI behavior further.
