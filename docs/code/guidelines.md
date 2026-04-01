# OpenPod Code Guidelines

This document captures the non-negotiable coding standards for the OpenPod project.
OpenPod is insulin delivery software — bugs can be life-threatening. Every line of code
must be written as if a skilled medical auditor will review it.

---

## 1. Medical-Grade Quality (IEC 62304 Mindset)

### Logging & Observability
- Every state transition, BLE event, crypto operation, and insulin calculation must be
  logged with structured data (Timber with tags).
- Include enough context to reconstruct what happened from logs alone.
- Never log sensitive patient data in plaintext.

### Traceability
- Every insulin delivery action must be traceable via the audit log with checksums
  (`core/audit` module).
- Protocol messages, dose commands, and safety-check results must be recorded so that
  any delivery can be reconstructed after the fact.

### Safety & Defensive Coding
- Validate at system boundaries: BLE payloads, user input, pod responses.
- Validate insulin doses, reservoir levels, and timing constraints before acting.
- **Fail safe** — never deliver insulin on ambiguous state. When in doubt, halt and
  alert the user.

---

## 2. Testing

### Coverage Targets
| Layer | Minimum Coverage |
|---|---|
| Domain / business logic (bolus calculations, safety checks, state machines) | >90% |
| Protocol / BLE layers | >70% |

### Requirements
- Every feature must ship with tests. Never skip tests to save time.
- Test edge cases and failure modes, not just the happy path.
- Stack: **JUnit 5**, **MockK**, **Turbine** (Flow testing), **Truth** (assertions).
- Bolus calculations and safety checks require exhaustive boundary-value tests.

---

## 3. Documentation

- **KDoc** on all public APIs.
- Explain the "why" for non-obvious decisions — not just what the code does.
- Reference Omnipod protocol spec sections and protocol docs where relevant
  (e.g., `// See Protocol Spec §4.2.1 — EAP-AKA challenge flow`).
- Maintain architecture docs and developer guides alongside the code.

---

## 4. Internationalization (i18n)

### Languages
- **English** — default (`res/values/strings.xml`).
- **German** — from day one (`res/values-de/strings.xml`).

### Rules
- All user-facing text must use Android string resources. No hardcoded strings in
  Composables or ViewModels.
- Use parameterized strings (`%1$s`, `%1$d`) for dynamic values.
- Use `<plurals>` where quantity matters (e.g., "1 hour remaining" vs "2 hours
  remaining").
- Format numbers and dates with locale-aware formatters.
- Medical units (mg/dL, U/hr, mmol/L, etc.) remain untranslated — they are universal.

---

## 5. Cryptography

The project uses a **pure-Kotlin crypto stack** built on Bouncy Castle
(`org.bouncycastle:bcprov-jdk18on`), replacing the former native JNI library. This
runs on all Android ABIs without native library extraction.

### Implementation (`core/crypto`)
| Module | Purpose |
|---|---|
| `PureKotlinCryptoManager` | Thread-safe singleton implementing `CryptoManager`; orchestrates pairing, EAP-AKA, and per-message encryption |
| `AesCcm` | AES-CCM-128 authenticated encryption/decryption (128-bit key, 7–13 byte nonce, 64-bit auth tag) |
| `AesCmac` | AES-CMAC message authentication (RFC 4493) |
| `X25519KeyExchange` | X25519 ECDH key generation and shared secret computation |
| `OmnipodKdf` | SHA-256 KDF deriving confirmation keys and LTK from ECDH shared secret |
| `MilenageAuth` | 3GPP TS 35.206 MILENAGE f1–f5 functions for EAP-AKA |
| `EapAkaAuthenticator` | EAP-AKA protocol state machine (IDLE → CHALLENGE_SENT → AUTHENTICATED / FAILED) |
| `SimProfileStore` | In-memory SIM profile storage with XOR-masked LTK persistence |

### Rules
- No pre-shared keys, test keys, or simplified flows — use the real crypto path always.
- Every crypto operation must be logged (input hashes, success/failure, timing).
- All new crypto primitives must have test vectors validated against known-good outputs.
- Changes to `core/crypto` require review of the full pairing → EAP-AKA → encrypted
  message flow to ensure no regressions.

---

## 6. UI Design Principles

The full visual spec lives in [`docs/redesign-spec.md`](../redesign-spec.md). The
rules below are the ones most likely to be violated during day-to-day coding.

### Product Feel
- **Clinically trustworthy, calm under stress, premium but not flashy.**
- Do **not** clone the existing Omnipod 5 UI — replicate functionality, reimagine the
  interface.
- Dashboard must be understood in under 2 seconds. Three-tap-max for any critical
  action.

### One Hero Per Screen
- Each screen gets one dominant focal point. Never place multiple competing "primary"
  cards in the same viewport.

### Color
- Mineral blue / deep cobalt brand palette — no purple as default accent.
- Dual theme (light + dark) with fixed branded tokens, not unrestricted dynamic color.
- **Semantic colors are sacred:** glucose, insulin, and pod status colors must never be
  repurposed for branding, decoration, or theming. Error red is reserved for true risk.

### Shape System
| Token | Radius | Usage |
|---|---|---|
| `shape.xs` | 12 dp | — |
| `shape.sm` | 16 dp | Buttons, small cards, list cells |
| `shape.md` | 20 dp | Hero cards, action cards, trays |
| `shape.lg` | 28 dp | Modal sheets, major task containers |
| `shape.xl` | 32 dp | — |
| `shape.full` | 999 dp | Chips, pills, nav indicator |

### Spacing & Layout
- Base unit: `4 dp`. Scale: 4, 8, 12, 16, 20, 24, 32, 40, 48.
- Content gutter: `20 dp`. Min touch target: `48 dp`. Preferred primary: `56 dp`.
- All top-level screens render edge-to-edge (transparent system bars, window-inset
  padding).

### Typography
- Hero glucose: `72/76 medium tabular`. All numeric medical data uses tabular figures.
- Screen titles: `headlineLarge`. Section labels: `labelLarge` / `titleSmall`.
- See the redesign spec for the full type scale.

### Elevation
- Prefer tonal separation over shadows. Shadow only on floating/moving components.

### Components
- **Status capsule** (not loose text row): connectivity dot, pod label, reservoir, time
  remaining — single tap target, tonal background.
- **Hero metric module**: trend icon + large glucose + unit/freshness + IOB pill. One
  strong semantic color at a time.
- **Action cards**: `20 dp` radius, `16–20 dp` padding, concise summary style — not
  settings rows.
- **Chips/pills**: full-pill shape, tonal fill. Avoid outlined chips unless the screen
  is already dense.
- **Primary action**: prefer extended FAB (`icon + Bolus`) or bottom action tray over a
  detached circular FAB.
- **Banners**: tonal container, strong leading icon, one sentence, optional action.
  Use sparingly.

### Navigation
- Bottom navigation bar: `80 dp`, icon + label on all three tabs, tonal active
  indicator (not a saturated blob).
- Top-level screens: Dashboard, History, Settings.

### Guided Flows (Onboarding / Pairing)
- Quiet dot/bar stepper above content, below top bar.
- Centered task title, brief explanation, one content region, fixed bottom action row.

### Motion
- Tap response: 90–120 ms. Card expand/collapse: 180–220 ms. Screen transition:
  240–300 ms. Hero number changes: 160–200 ms crossfade.
- Shared axis for flow steps, fade-through for tab changes, subtle scale for FAB
  reveal.
- No bouncy novelty on medical data changes.

### Accessibility & Safety
- Font scaling to at least 200 % without clipping essential actions.
- Contrast: ≥ 4.5 : 1 body text, ≥ 3 : 1 large text / components.
- Never communicate glucose state by color alone — always pair with text or icon.
- Stable spatial positions for critical actions. No animation on urgent alerts beyond a
  single attention pulse.

---

## 7. Architecture & Tech Stack

| Concern | Choice |
|---|---|
| Architecture | MVI + Clean Architecture |
| UI | Jetpack Compose + Material 3 |
| DI | Hilt |
| BLE | Kable |
| Database | Room + SQLCipher |
| Crypto | Pure Kotlin (Bouncy Castle + JCA) |

---

## Summary Checklist

Before merging any code, verify:

- [ ] All public APIs have KDoc
- [ ] User-facing strings are in `strings.xml` (EN) and `strings-de.xml` (DE)
- [ ] Tests cover happy path, edge cases, and failure modes
- [ ] State transitions and delivery actions are logged and auditable
- [ ] Safety checks validate inputs at system boundaries
- [ ] No hardcoded strings in UI code
- [ ] Pure-Kotlin crypto stack is used — no test-key shortcuts
- [ ] Crypto changes validated with test vectors
