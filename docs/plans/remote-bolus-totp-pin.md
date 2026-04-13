# Remote Bolus Authentication: TOTP + PIN

This document describes the TOTP+PIN authentication scheme used by AndroidAPS for
remote bolus commands, as a reference for OpenPod's remote control design.

## Overview

AndroidAPS allows remote bolus delivery exclusively via SMS (not Nightscout).
Every remote command requires two-factor authentication: a time-based one-time
password (TOTP) combined with a user-chosen PIN.

## Authentication Flow

```
Caregiver                        AAPS Phone
    |                                |
    |  SMS: "BOLUS 1.2"             |
    |------------------------------->|
    |                                |  1. Verify sender is in allowlist
    |                                |  2. Apply constraint pipeline
    |                                |     (max bolus, hard limits)
    |  SMS: "To deliver 1.2U reply  |
    |   with code XXXXXX"           |
    |<-------------------------------|
    |                                |
    |  SMS: "XXXXXX"                |  (6-digit TOTP + PIN suffix)
    |------------------------------->|
    |                                |  3. Validate TOTP + PIN
    |                                |  4. Execute bolus via CommandQueue
    |  SMS: "Bolus 1.2U delivered"  |
    |<-------------------------------|
```

## TOTP Implementation

Based on RFC 6238 (TOTP) and RFC 4226 (HOTP).

| Parameter        | Value                          |
|------------------|--------------------------------|
| Algorithm        | HMAC-SHA1                      |
| Key length       | 160 bits                       |
| Code digits      | 6                              |
| Time step        | 30 seconds                     |
| Accepted window  | Current + N previous intervals |

The TOTP secret is generated at setup time and stored in AAPS preferences. The
caregiver enrolls the secret into a standard authenticator app (Google
Authenticator, Authy, etc.) by scanning a QR code.

### Code Format

The reply code is the concatenation of the 6-digit TOTP and the user PIN:

```
Reply = TOTP(6 digits) + PIN(>= 3 characters)
```

Example: if the current TOTP is `482917` and the PIN is `safe`, the reply is
`482917safe`.

### Clock Tolerance

To handle clock drift between the caregiver's authenticator app and the AAPS
phone, a small window of previous TOTP values is accepted
(`OTP_ACCEPT_OLD_TOKENS_COUNT`). Only past tokens are accepted, never future
ones, to prevent precomputation attacks.

## Access Controls

### Phone Number Allowlist

Only phone numbers explicitly configured in `SmsAllowedNumbers` can issue
commands. Messages from unknown numbers are silently ignored.

### Rate Limiting

A minimum interval between remote boluses is enforced via `lastRemoteBolusTime`.
This prevents rapid-fire dosing even with valid credentials.

### Constraint Pipeline (same as local)

Remote boluses pass through the exact same `ConstraintsCheckerImpl` as local UI
boluses:

1. **User max bolus** -- configurable in preferences (`SafetyMaxBolus`)
2. **Hard limits** -- age-dependent ceiling: 5 / 10 / 17 / 25 / 60 U
   (child / teenage / adult / resistant adult / pregnant)
3. `setIfSmaller()` ensures the most restrictive limit always wins

Constraints are applied **twice**: once when the SMS is received, and again when
the command enters the `CommandQueue`.

## Threat Model

| Threat                        | Mitigation                                          |
|-------------------------------|-----------------------------------------------------|
| Stolen phone sends SMS        | TOTP from authenticator app required (second factor) |
| Compromised authenticator app | PIN required (knowledge factor)                     |
| SMS interception / replay     | TOTP expires after 30s; old codes rejected           |
| Brute-force TOTP              | 6 digits + PIN = large keyspace; rate limiting       |
| Clock manipulation            | Only past tokens accepted, not future                |
| Unauthorized phone number     | Allowlist check before any processing                |
| Excessive dosing              | Same hard limits and constraint pipeline as local UI |

## Limitations

- **SMS is not encrypted in transit.** Carrier-level interception could observe
  commands and codes. This is an accepted trade-off for simplicity and
  availability (SMS works without internet).
- **SIM swap attacks** could allow an attacker to receive the challenge SMS, but
  they would still need the TOTP secret and PIN to reply.
- **Single device.** The TOTP secret lives on one authenticator device. Loss of
  that device requires re-enrollment.

## Key Source Files (AndroidAPS)

| File | Role |
|------|------|
| `plugins/main/.../smsCommunicator/SmsCommunicatorPlugin.kt` | SMS command parsing, allowlist check, challenge/response flow |
| `plugins/main/.../smsCommunicator/otp/OneTimePassword.kt` | TOTP generation and validation (RFC 6238) |
| `implementation/.../queue/CommandQueueImplementation.kt` | Command queuing with duplicate/replay guards |
| `plugins/constraints/.../safety/SafetyPlugin.kt` | Hard limits and user max bolus enforcement |

## Applicability to OpenPod

If OpenPod implements remote control, the TOTP+PIN model provides a
well-understood baseline:

- Standard TOTP libraries exist for Kotlin/Android (e.g., `commons-codec`,
  `dev.turingcomplete:kotlin-onetimepassword`)
- QR-based enrollment works with any authenticator app
- The challenge-response SMS flow avoids needing a persistent network connection
- The constraint pipeline already exists in OpenPod's bolus path and can be
  reused directly for remote commands
