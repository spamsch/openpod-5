# Audit Trail Architecture

## Purpose

Every insulin-delivery action and safety-relevant state transition is recorded in an
append-only, hash-chained audit trail. Records are persisted in the same SQLCipher-encrypted
database as clinical data, ensuring they inherit the existing encryption-at-rest guarantees.

## Data Model

### AuditEvent

| Field              | Type         | Description                                              |
|--------------------|--------------|----------------------------------------------------------|
| id                 | Long (PK)    | Auto-generated row ID                                    |
| category           | AuditCategory| Event type (see below)                                   |
| timestampUtc       | Instant      | When the event occurred (UTC)                            |
| actor              | String       | Who initiated it: `user`, `system`, or `pod`             |
| source             | String       | Code component that produced the event                   |
| clinicalContext     | String       | Grouping key tying related events (e.g., bolus UUID)     |
| payloadJson        | String       | Canonical sorted-keys JSON with event-specific data      |
| payloadHash        | String       | SHA-256 of payloadJson                                   |
| previousEventHash  | String       | recordChecksum of the preceding event in the chain       |
| recordChecksum     | String       | SHA-256 of all fields except id and recordChecksum       |

### AuditCategory Values

**Bolus lifecycle:** `BOLUS_REQUEST` → `BOLUS_PRECONDITION_CHECK` → `BOLUS_DISPATCH` → `BOLUS_ACK` → `BOLUS_PROGRESS` → `BOLUS_COMPLETE` / `BOLUS_CANCEL` / `BOLUS_FAIL`

**Other:** `BASAL_CHANGE`, `POD_ACTIVATION`, `POD_DEACTIVATION`, `AUTHENTICATION`, `SYSTEM`

## Hash Chain Design

```
Genesis ──────────────────────────────────────────────────
  │
  │  previousEventHash = SHA-256("OPENPOD_AUDIT_GENESIS")
  ▼
┌──────────────────────────────────────────────────────┐
│ Event 1                                              │
│ recordChecksum = SHA-256(category || timestamp ||     │
│   actor || source || clinicalContext || payloadJson   │
│   || payloadHash || previousEventHash)               │
└──────────────────────┬───────────────────────────────┘
                       │ previousEventHash = Event1.recordChecksum
                       ▼
┌──────────────────────────────────────────────────────┐
│ Event 2                                              │
│ recordChecksum = SHA-256(...)                         │
└──────────────────────┬───────────────────────────────┘
                       │
                       ▼
                      ...
```

Each event's `recordChecksum` serves as both:
1. A tamper-detection seal for that record
2. The chain link consumed by the next event's `previousEventHash`

Modifying any record invalidates its checksum and breaks the chain for all subsequent records.

## Immutability

- The `AuditEventDao` provides **no delete or update** operations
- `HistoryEventDao.deleteAll()` (used for factory reset) does **not** affect audit records
- Full device wipe via `context.deleteDatabase()` is the only way to remove audit records

## Concurrency

The `AuditRepositoryImpl` uses a `kotlinx.coroutines.sync.Mutex` to serialize the
read-latest → compute-hashes → insert sequence, ensuring the chain remains consistent
under concurrent writes from different coroutines.

## Chain Verification

`AuditRepository.verifyChainIntegrity()` walks every record from genesis to latest:

1. Verifies `previousEventHash` matches the prior record's `recordChecksum`
2. Recomputes `recordChecksum` from stored fields and compares
3. Recomputes `payloadHash` from `payloadJson` and compares

Returns `Valid(recordCount)`, `Empty`, or `Tampered(failedAtId, reason)`.

## Modules

| Module        | Responsibility                                    |
|---------------|---------------------------------------------------|
| `core:model`  | `AuditEvent`, `AuditCategory`, `AuditHasher`      |
| `core:database`| `AuditEventEntity`, `AuditEventDao`, migration    |
| `core:domain` | `AuditRepository` interface                       |
| `core:audit`  | `AuditRepositoryImpl`, Hilt module                |
