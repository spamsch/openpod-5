# Bolus Safety Gates Architecture

## Purpose

No insulin command is sent when system state is ambiguous, stale, or clinically unsafe.
The `BolusSafetyValidator` runs immediately before `PodManager.sendBolus()` — not at
review time — because pod state can change between user review and delivery.

## Validation Checks

| Check                   | Condition                                  | Failure Type              |
|-------------------------|--------------------------------------------|---------------------------|
| Dose minimum            | `units >= 0.05 U`                          | `DoseBelowMinimum`        |
| Dose maximum            | `units <= 30.0 U`                          | `DoseAboveMaximum`        |
| Pulse alignment         | `units % 0.05 < 0.001`                     | `DoseNotPulseAligned`     |
| Pod reachable           | `getStatus()` succeeds                     | `PodNotReachable`         |
| Pod activated           | `status.isActivated == true`               | `PodNotActivated`         |
| Pod not expired         | `status.expiresAt > now`                   | `PodExpired`              |
| Sufficient reservoir    | `status.reservoir >= requestedUnits`        | `InsufficientReservoir`   |
| No conflicting delivery | `status.bolusInProgress == false`           | `BolusAlreadyInProgress`  |

All checks run in sequence. Multiple failures are accumulated and returned together,
allowing the UI to display all blocking reasons at once.

## MVI Integration

```
BolusViewModel.onDeliver()
  │
  ├─ Audit: BOLUS_REQUEST
  │
  ├─ BolusSafetyValidator.validate(units)
  │   ├─ Audit: BOLUS_PRECONDITION_CHECK (passed/failed)
  │   │
  │   ├─ [FAILED] → Audit: BOLUS_FAIL
  │   │              → Emit SafetyGateFailure effect → Snackbar
  │   │              → Stay in REVIEW phase
  │   │
  │   └─ [PASSED] → Transition to DELIVERING
  │                → Audit: BOLUS_DISPATCH
  │                → PodManager.sendBolus(units)
  │                   ├─ [SUCCESS] → Audit: BOLUS_ACK → Poll progress
  │                   └─ [FAILURE] → Audit: BOLUS_FAIL → ShowError effect
  │
  └─ On completion:
      ├─ HistoryRepository.recordEvent(BOLUS, ...)
      └─ Audit: BOLUS_COMPLETE or BOLUS_CANCEL
```

The validator is constructor-injected into `BolusViewModel` via Hilt. It produces a
`ValidationResult` (either `Passed` or `Failed` with typed `SafetyFailure` list) —
it does not modify state directly, preserving MVI unidirectional data flow.

## Failure UX

Safety rejections are surfaced to the user via `BolusEffect.SafetyGateFailure`, which
the `BolusScreen` renders as a Snackbar listing the failure class names. The UI remains
on the REVIEW screen so the user can address the issue (e.g., wait for a bolus to finish,
connect to the pod) and retry.

## Audit Trail

Every safety validation result — pass or fail — is recorded in the audit trail with:
- The requested dose and clinical inputs
- Whether validation passed
- Specific failure reasons (if any)
- The bolus UUID as `clinicalContext` for end-to-end traceability

## Modules

| Module          | Responsibility                                     |
|-----------------|---------------------------------------------------|
| `core:domain`   | `BolusSafetyValidator`, `ValidationResult`, `SafetyFailure` |
| `feature:bolus` | `BolusModule` (Hilt provider), `BolusViewModel` integration |
