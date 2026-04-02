# Emulator Protocol Remediation Plan

Status: Draft — 2026-04-02
Based on: line-by-line audit of `omnipod-connector/docs/protocol/01-08` against `emulator/`

## Context

An audit compared every protocol doc section against the emulator implementation.
The crypto pipeline (ECDH, KDF, CMAC, SIM profile, MILENAGE, EAP-AKA, AES-CCM),
BLE GATT UUIDs, RHP text parser, activation state enum, and TWICommand frame are
all correct. This plan covers the gaps that remain.

Completed items from the previous backlog (P1.1-P1.3, P1.6) are not repeated here.

---

## 1. Critical: Session Key Derivation (eap_aka.py)

### Problem

`eap_aka.py:252` derives the encryption key as:

```python
msk = SHA-256(CK || IK)[:16]
```

The real protocol derives session keys per RFC 4187 Section 7. The phone side
(TWI SDK) calls `twiEapAkaHasCk` / `twiEapAkaClearCk` showing CK is used
directly as the AES-CCM session key after EAP-AKA completes. The IK may serve
as the integrity key for AT_MAC verification.

### Fix

```
File: emulator/omnipod_emulator/crypto/eap_aka.py

1. Change SessionKeys.msk to SessionKeys.encryption_key.
2. After a successful challenge response, set:
       encryption_key = CK   (16 bytes, from MILENAGE f3)
   Do NOT hash CK+IK.
3. Keep IK available for AT_MAC computation (see item 2 below).
4. Update session.py to use session_keys.encryption_key instead of
   session_keys.msk when calling aes_ccm.encrypt / aes_ccm.decrypt.
```

### Acceptance

- Existing EAP-AKA round-trip tests still pass (key material is deterministic
  from test vectors).
- A new test asserts `session_keys.encryption_key == milenage.f3(rand)`.

---

## 2. Critical: AT_MAC Verification Missing (eap_aka.py)

### Problem

RFC 4187 Section 10.15 requires the phone's AKA-Challenge to carry an AT_MAC
attribute. The pod must verify it before accepting the challenge. The emulator
currently ignores AT_MAC entirely — it only checks AT_RAND and AT_AUTN.

### Fix

```
File: emulator/omnipod_emulator/crypto/eap_aka.py

In _handle_challenge():
1. After computing CK and IK, derive K_aut per RFC 4187 Section 7:
       MK  = SHA-1(Identity | IK | CK)
       K_encr = MK[0:16]
       K_aut  = MK[16:32]   # <- key for AT_MAC HMAC-SHA-256-128
2. If AT_MAC is present in the challenge, verify it:
       - Zero out the MAC field in the original message
       - Compute HMAC-SHA-256-128(K_aut, message) and compare
3. If AT_MAC is absent, log a warning but proceed (the real TWI SDK
   may omit it in some modes).
4. Include AT_MAC in the EAP-Response if the challenge contained one.
```

### Acceptance

- Test with a crafted challenge containing a valid AT_MAC passes.
- Test with an invalid AT_MAC is rejected.

---

## 3. Critical: Command Type/Attribute Mapping (rhp_handlers.py)

### Problem

Activation commands are mapped to invented `type 1, attrs 0-5`.
Operations (bolus, stop, temp basal) are mapped to invented `type 2, attrs 0-6`.
Status is at `G1.6`. None of these match the documented protocol.

The documented AID Command Builder layer maps commands to specific type/attr
pairs (from `04-data-protocol.md` Section 4 and the AID command builder table):

| Command | Documented Type.Attr | Emulator (wrong) |
|---------|---------------------|-------------------|
| AID Pod Status | G11.3 or G12.3 | G1.6 |
| Algorithm Loop State | G6.3 / S6.3 | not mapped |
| TDI | S2.3 (AID builder) | S3.2 |
| Target BG Profile | S1.3 (AID builder) | S3.1 |
| Correction Factor | S3.3 (AID builder) | S3.3 |
| DIA | S9.3 (AID builder) | S3.9 |
| EGV Threshold | S7.3 (AID builder) | S3.7 |
| Hypo Protect | S10.3 (AID builder) | S3.10 |
| UTC Time | S2.255 (AID builder) | S255.2 |
| CGM Transmitter Info | S0.4 (AID builder) | S4.0 |
| Patient History Logger | G/S with type 4 or 9 (AID builder) | not mapped |

**Key insight**: There are two overlapping RHP addressing schemes:

1. **Base RHP attributes** (from `RhpCommandsFeaturesAttributes.java`): These use
   the format `[G|S]<type>.<attr>` and are the ones currently in the emulator for
   type 3, type 4, type 255. These are **correct for base-level commands** like
   `S3.9=300` (set DIA) or `G3.6` (get algo status).

2. **AID Command Builder layer**: Uses *different* type.attr numbers as a
   higher-level command routing. The AID builder composes commands that are
   ultimately sent as base RHP.

The base RHP type/attr mappings for type 3, 4, 255 in the emulator are actually
correct — they match `RhpCommandsFeaturesAttributes.java`. The problem is
specifically:

- **Activation control** (type 1, attrs 0-5): invented, needs removal
- **Operations** (type 2, attrs 0-6): invented, conflicts with documented type 2
  (History Buffer)
- **Pod Status** (G1.6): invented, should be G11.3 or G12.3

### Fix

This is the largest change. Break into sub-tasks:

#### 3a. Add AID Pod Status response (types 11.3 and 12.3)

```
Files:
  emulator/omnipod_emulator/protocol/rhp_handlers.py
  emulator/omnipod_emulator/pod/state.py

1. Add handler for G11.3 (AID Pod Status, G6 variant):
   - Return a 28-byte binary payload (hex-encoded in RHP text) matching
     the documented format:
       [EGV:2][EGV_time:4][TDI:2][CGM_tx_time:4][EGV_rate:1]
       [algo_loop:1][IOB:4][pod_ts:4][QN_status:1][CGM_algo:1]
       [CGM_tx_status:1][tx_lifetime:2][QN_state:1]
   - Use setHexPayload encoding: 2-byte length prefix + raw bytes.

2. Add handler for G12.3 (Unified AID Pod Status):
   - Variable-length payload with CGM-type-specific suffix.
   - Start with DEFAULT/NO_INFORMATION CGM type (no CGM data appended).

3. Remove G1.6 handler.
4. Update session flow to use G11.3 or G12.3 for status queries.
```

#### 3b. Remove invented type 1 activation commands

```
File: emulator/omnipod_emulator/protocol/rhp_handlers.py

The activation flow doesn't use dedicated RHP type/attr pairs. In the real
protocol, activation is orchestrated by the AidPodCommandController which
sends a sequence of base-level RHP commands:

  - GV                    (get version)
  - S<alerts>             (program alerts — exact type/attr TBD from deeper
                           analysis of AidPodCommandController)
  - S<prime>              (prime pod)
  - S<basal>              (program basal)
  - etc.

For now: keep the type 1 activation mapping as a documented emulator
convention, clearly marked as "emulator-specific, not wire-compatible".
Add a TODO to replace once the exact activation RHP sequences are captured
from real traffic or further decompilation.

Rationale: changing activation now without knowing the exact wire format
risks introducing a different wrong mapping. Better to keep it working
and clearly labeled.
```

#### 3c. Re-map operation commands

```
File: emulator/omnipod_emulator/protocol/rhp_handlers.py

Operations like bolus, stop, temp basal are sent by the AidPodCommandController
as RHP commands. The exact type/attr pairs need to be determined from further
decompilation. The documented command names (SendBolus, StopProgram, etc.)
are enum values in EnumC12210bwt but the RHP text they compile to is not
fully documented.

For now: move operations from type 2 to a non-conflicting emulator-specific
range (e.g., type 200+) and add clear TODO markers. This unblocks type 2
for History Buffer.

Better: if the operations are actually sent as typed RHP, map them. The
AID command builder table shows some type/attr pairs but doesn't cover
bolus/stop/temp-basal explicitly. These may use a different mechanism
(direct binary TWICommand payloads rather than text RHP). Mark as TBD.
```

#### 3d. Add History Buffer handlers (type 2)

```
File: emulator/omnipod_emulator/protocol/rhp_handlers.py

1. Register G2.0 / S2.0 (HISTORY_BUFFER_INDEX_LENGTH)
2. Register G2.1 (HISTORY_BUFFER_DATA)
3. Return empty/stub data for now — the emulator doesn't need a real
   history buffer, but it needs to not error on these requests.
```

### Acceptance

- G11.3 returns a valid 28-byte status payload.
- G12.3 returns a valid variable-length status payload.
- Type 2 no longer conflicts between operations and history buffer.
- Operations still function (under whatever mapping is chosen).

---

## 4. Critical: Dead Binary Command Code

### Problem

`commands.py` and `activation.py` contain the old binary opcode protocol
(CommandType enum 0x01-0x0F, RhpCommandDispatcher). These are not used by
`session.py` (which routes to `RhpTextDispatcher`) but they are imported
by `session.py` (indirectly, `activation.py` imports from `commands.py`).

### Fix

```
1. Verify no code path reaches RhpCommandDispatcher.dispatch() or
   ActivationHandler from session.py. (Confirmed: session.py only
   uses RhpTextDispatcher and RhpHandlers.)
2. Delete commands.py.
3. Delete activation.py.
4. Remove any dangling imports.
```

### Acceptance

- `python -c "from omnipod_emulator.protocol.session import ProtocolSession"`
  succeeds without importing commands.py or activation.py.
- All tests pass.

---

## 5. Medium: MTU Size (constants.py)

### Problem

`constants.py:79`: `MTU_SIZE = 185`. Protocol doc says the phone requests
MTU of 251 bytes.

### Fix

```
File: emulator/omnipod_emulator/ble/constants.py

Change MTU_SIZE to 251.
Verify MAX_CHUNK_SIZE (currently 160) is still appropriate — with MTU 251,
the usable ATT payload is 251 - 3 = 248. MAX_CHUNK_SIZE could be raised
to ~244 (leaving room for envelope overhead).
```

### Acceptance

- MTU_SIZE == 251.
- MAX_CHUNK_SIZE updated if appropriate.

---

## 6. Medium: Init Command Validation (session.py)

### Problem

`session.py:215-218` reads controller_id from `data[3:7]` but doesn't
validate that `data[1] == 0x01` and `data[2] == 0x04` per the documented
format `[0x06, 0x01, 0x04, controller_id(4)]`.

### Fix

```
File: emulator/omnipod_emulator/protocol/session.py

In _handle_init():
  if data[1] != 0x01 or data[2] != 0x04:
      logger.warning("Unexpected init subfields: %02x %02x", data[1], data[2])
      # Continue anyway (permissive) but log the discrepancy
```

### Acceptance

- Valid init messages still work.
- Malformed init messages are logged.

---

## 7. Medium: Missing RHP Handlers

### Problem

Several documented type/attr pairs are unimplemented.

### Fix — add stub handlers

```
File: emulator/omnipod_emulator/protocol/rhp_handlers.py

Add GET/SET stubs for:

Logger (prefix L):
  L0.8 (LOGGER_START_TIME)  -> return "0"
  L0.9 (LOGGER_LOGS)        -> return empty hex payload

Algorithm (type 3):
  G3.0 (ALGO_LOG_DATA)      -> return empty hex payload

CGM (type 4):
  S4.1 / G4.1 (CGM_CALIB)   -> accept SET, return "0" for GET
  G4.3 (LAST_CALIB)          -> return "0"

System (type 255):
  S255.3 (ENABLE_ERROR_CODE) -> accept SET

Engineering (prefix R):
  GR<type>.1                 -> return "0" (record count)
  GR<type>.2                 -> return "0" (offset)
  GR<type>.5                 -> return empty data

Each stub should log that it was called so integration tests can
verify the request was routed correctly.
```

### Acceptance

- No "No handler for RHP key" warnings for the above type/attr pairs.
- Stubs return valid RHP responses.

---

## 8. Medium: Reconnection Advertising UUIDs (ble/server.py)

### Problem

After pairing, real pods switch from unpaired advertising UUIDs
(`...fffffffe0x`) to controller-specific UUIDs (`...{CTRL_ID}0x`).
The emulator always advertises with the unpaired UUID.

### Fix

```
Files:
  emulator/omnipod_emulator/ble/server.py
  emulator/omnipod_emulator/ble/constants.py

1. Add a function to derive paired UUIDs from controller_id:
       def paired_scan_uuids(ctrl_id: bytes) -> list[str]:
           base = "ce1f923d-c539-48ea-7300-0a"
           hex_id = ctrl_id.hex()
           return [f"{base}{hex_id}{i:02x}" for i in range(4)]

2. After pairing completes (LTK saved), restart advertising with the
   paired UUIDs.

3. On disconnect + reconnect, continue using paired UUIDs.
```

### Acceptance

- Before pairing: advertises with `...fffffffe00`.
- After pairing: advertises with `...{ctrl_id}00`.

---

## 9. Medium: CRC-16 Message Integrity

### Problem

The native library uses `twi_crc16_compute_checksum` for message integrity.
The emulator has no CRC validation. This may cause silent data corruption
to go undetected in integration tests.

### Fix

```
File: new emulator/omnipod_emulator/crypto/crc16.py

1. Implement CRC-16 (likely CRC-16/CCITT or CRC-16/XMODEM — needs
   verification from native library analysis).
2. Add CRC computation to TWICommand serialization.
3. Add CRC validation to TWICommand parsing (warn on mismatch,
   don't hard-fail since the exact polynomial is unconfirmed).
```

### Acceptance

- CRC is computed and appended on outgoing TWICommand frames.
- CRC is validated on incoming frames (with warning-level logging on mismatch).

---

## 10. Medium: Manufacturer Advertising Data (ble/server.py)

### Problem

The emulator's advertising data includes only the device name and service
UUID. The real pod includes manufacturer-specific data (AD Type 0xFF)
containing company ID, alarm code, alert code, and pod ID adjustment bits.

### Fix

```
File: emulator/omnipod_emulator/ble/server.py

In _start_advertising():
1. Build manufacturer-specific data:
       company_id (2 bytes, little-endian)
       padding (variable)
       pod_id_adj_bits (1 byte, bits 4-5 = pod ID fragment)
       alarm_code (1 byte, from pod_state.alerts)
       alert_code (1 byte)

2. Add it to advertising_data as AD Type 0xFF.

3. Include profile ID = 10 in the UUID data to pass the scan
   callback's profileID validation.
```

### Acceptance

- Android scan callback `C10949bXg.onScanResult()` would accept the
  advertising packet (profile ID == 10, service type parseable).

---

## 11. Low: P-256 ECDH Fallback

### Problem

The protocol supports P-256 ECDH as a fallback. The emulator only
implements X25519. If the phone negotiates P-256, pairing will fail.

### Fix

This is low priority because the protocol defaults to X25519 and the
phone (TWI SDK) uses X25519 for Omnipod 5. Document as a known
limitation. If P-256 is needed later:

```
File: emulator/omnipod_emulator/crypto/ecdh.py

Add an EcdhP256KeyPair class using cryptography.hazmat.primitives
ec.generate_private_key(ec.SECP256R1()). Adjust KDF to handle
64-byte public keys.
```

---

## Execution Order

Dependencies flow downward. Items at the same level can be parallelized.

```
Phase A — Correctness (must-fix before integration testing)
  1. Session key derivation          (standalone, ~1h)
  4. Delete dead binary code         (standalone, ~30min)
  5. MTU size fix                    (standalone, ~15min)
  6. Init command validation         (standalone, ~15min)

Phase B — Protocol fidelity (needed for app ↔ emulator traffic)
  3a. AID Pod Status G11.3 / G12.3  (depends on pod/state.py, ~3h)
  3c. Re-map operations              (depends on 3a for status, ~2h)
  3d. History Buffer stubs           (after 3c frees type 2, ~1h)
  7.  Missing RHP handler stubs      (standalone, ~2h)
  8.  Reconnection advertising       (standalone, ~2h)

Phase C — Hardening
  2.  AT_MAC verification            (after phase A, ~3h)
  9.  CRC-16                         (standalone, ~2h)
  10. Manufacturer advertising data  (standalone, ~2h)

Phase D — Deferred
  3b. Real activation command mapping (blocked on further decompilation)
  11. P-256 fallback                  (not needed yet)
```

---

## Files Changed (Summary)

| File | Changes |
|------|---------|
| `crypto/eap_aka.py` | Session key = CK directly; AT_MAC verification |
| `protocol/rhp_handlers.py` | G11.3/G12.3 status; re-map ops; add stubs |
| `protocol/session.py` | Use encryption_key; init validation |
| `protocol/commands.py` | DELETE |
| `protocol/activation.py` | DELETE |
| `ble/constants.py` | MTU 251; paired UUID helper |
| `ble/server.py` | Paired advertising; manufacturer data |
| `pod/state.py` | encode_aid_status() for 28-byte format |
| `protocol/twi_command.py` | CRC-16 in serialization |
| `crypto/crc16.py` | NEW: CRC-16 implementation |
