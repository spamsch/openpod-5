# OpenPod Legal Risk Analysis And Mitigation Plan

Status: Draft v1
Audience: Repository owner, engineering, maintainers, external counsel
Last updated: 2026-04-01
Prepared from: repository review plus maintainer confirmation of provenance

## 1. Purpose

This document turns the current repository review into a concrete legal-risk assessment and mitigation plan.

It is based on two inputs:

1. The current contents of this repository as reviewed on 2026-04-01.
2. Maintainer confirmation that protocol details, constants, structures, and behavior were derived from decompiling the official APK and reverse engineering the official implementation.

This is not legal advice. It is an engineering-facing risk memo intended to help narrow exposure before formal counsel review.

## 2. Executive Summary

The main legal exposure is not that the repository targets Omnipod 5. The main exposure is the confirmed provenance.

The repository currently presents itself as:

- an open-source controller for Omnipod 5 pods
- a project that can pair with and control a real pod
- a project containing no proprietary code
- a clean-room reimplementation

Those positions sit in tension with the confirmed development method:

- decompiling the official APK
- reverse engineering the official implementation
- reimplementing behavior based on decompiled binaries and structures

The biggest risks are:

1. Copyright and anti-circumvention arguments tied to decompilation-derived implementation details.
2. FDA and similar medical-device regulatory exposure because the software is intended to control insulin delivery.
3. Trademark and false-association complaints because the product-facing copy presents the app as a controller for Omnipod 5 pods.
4. General contributor and distribution risk because the repository currently has no outbound license.

The strongest mitigation theme is straightforward:

1. Stop overstating the legal cleanliness of the codebase.
2. Remove stale references to proprietary binaries and decompilation artifacts from current implementation and docs.
3. Reframe the project as a research/interoperability effort unless and until there is a serious regulatory path.
4. Separate the repo from any workflow that depends on proprietary binaries, leaked materials, or undocumented third-party assets.
5. Get qualified counsel before public promotion, real-pod testing, app-store distribution, or community contribution scaling.

## 3. Confirmed Provenance Baseline

The current working assumption for this plan is:

- The repository was built by decompiling the official Omnipod 5 APK.
- Reverse engineering was used to recover protocol behavior, constants, structures, and state models.
- The old dependency on `libc3ec87.so` is no longer required for runtime behavior because encryption was reimplemented from the decompiled library.

That means the key legal question is not merely "is the shipped code source-original?"

It is also:

- what was copied
- what was derived
- what evidence of derivation is preserved in the repo
- whether the repo claims exceed what can be defended

## 4. Primary Risk Areas

### 4.1 Copyright And Derivative-Work Risk

Risk level: High

Why the risk exists:

- Decompilation of the official APK is now confirmed provenance.
- Several files describe constants, enums, offsets, layouts, and behavior as coming from the decompiled app or from `libc3ec87.so`.
- The repository currently claims both "no proprietary code" and "clean-room reimplementations."

The central problem is not that reverse engineering happened. The problem is that the current repo language invites an argument that implementation details were copied from protected code or from non-public internal structures rather than independently derived from public standards or black-box observation.

Specific pressure points in the repo include:

- direct references to the decompiled app
- references to internal enum names from the decompiled app
- references to exact storage layouts and offsets from `libc3ec87.so`
- broad "clean-room" language that does not match the actual provenance

Important distinction:

- Reimplementation of ideas, algorithms, and interoperability behavior is generally easier to defend.
- Copying expressive implementation details, decompiled structure names, comments, layouts, or vendor-specific internal organization is harder to defend.

### 4.2 DMCA / Anti-Circumvention Risk

Risk level: High

Why the risk exists:

- The project is not limited to diagnosis, maintenance, repair, or security research.
- The repository is explicitly framed as a controller that can pair, activate, and deliver boluses through a real pod.
- Authentication, pairing, and encrypted communications appear designed to interoperate with the live device rather than only analyze or repair it.

The current U.S. exemption landscape helps with:

- lawful access to a patient's own medical-device data
- diagnosis, maintenance, and repair of lawfully acquired medical devices
- good-faith security research

It is a weaker fit for:

- a replacement controller intended to operate the device in normal clinical use
- a public distribution channel for third parties to do the same

Even where an exemption may apply, those exemptions are not safe harbors from other laws.

### 4.3 Regulatory / Medical Device Risk

Risk level: High

Why the risk exists:

- The app is described as controlling insulin delivery.
- Product-facing copy says users can manage basal programs, deliver boluses, and monitor a pod from their phone.
- The repository appears to be on a path toward real-pod testing.

For regulatory purposes, intended use matters heavily. The current code and copy describe a software function that controls or influences a medical device used to deliver insulin. That is the kind of function regulators treat seriously, regardless of whether the project is open source or contains disclaimers.

The current disclaimer is useful, but it does not neutralize intended use if the software and messaging still present a functioning control path.

### 4.4 Trademark / False Association Risk

Risk level: Medium

Why the risk exists:

- The repository repeatedly uses `Omnipod 5`, `Dexcom`, and related product names.
- The README contains a non-affiliation disclaimer, but product-facing app text currently markets the software as an "open-source controller for Omnipod 5 insulin pods."
- For a safety-critical medical product category, trademark owners are likely to be aggressive about sponsorship, affiliation, confusion, and dilution theories.

Using another company's mark to identify compatibility may be defensible as nominative use. The risk increases when the software is distributed in a way that could make users think it is officially approved, supported, or connected to the manufacturer.

### 4.5 Proprietary Artifact Workflow Risk

Risk level: Medium

Why the risk exists:

- The repo still contains references to `libc3ec87.so`, Docker flow around it, and comments that anchor behavior to the proprietary library.
- Even if the shared object is no longer needed, those references preserve a record that the project was validated against or derived from proprietary components.

This is now a mitigation opportunity:

- because the `.so` is no longer needed, the repository can remove obsolete references, workflows, and claims tied to it.

### 4.6 Missing Repository License

Risk level: Medium

Why the risk exists:

- The README says `License: TBD`.
- There is no repository `LICENSE` file.

This does not create liability to Insulet by itself, but it materially weakens contribution, redistribution, and governance clarity and makes the project look immature in exactly the area where legal hygiene matters.

## 5. What Most Increases Exposure

The following repo characteristics are especially problematic:

1. Statements that implementation details were taken from a decompiled APK or proprietary library.
2. "Clean-room" claims that do not match the actual development history.
3. Product copy that presents the app as a practical controller for real Omnipod 5 therapy.
4. Roadmap language pointing toward real-pod use before a regulatory and legal strategy exists.
5. Any retained workflow, notes, or artifacts that assume contributors will extract or use proprietary vendor binaries.
6. Missing separation between research intent and clinical-use intent.

## 6. What Helps Defensibility

The following facts help, though they do not eliminate risk:

1. The repository does not appear to ship the proprietary `.so`.
2. Many cryptographic primitives themselves are public standards.
3. The repo already contains warnings and non-affiliation language in the README.
4. The `.so` is reportedly no longer needed.
5. The project can still improve its posture substantially by correcting documentation, provenance statements, and contributor workflow.

## 7. Recommended Mitigation Strategy

The goal should not be to create a false appearance that no reverse engineering occurred. That is no longer supportable.

The goal should be:

1. make the provenance accurate
2. remove unnecessary legally damaging statements and artifacts
3. stop making claims that exceed the defensible record
4. narrow public-facing intended use
5. get counsel review before broader distribution

## 8. Immediate Mitigations

These actions should happen first because they are low-cost and materially reduce avoidable exposure.

### 8.1 Replace Inaccurate Legal Claims

Priority: P0

Actions:

1. Remove or rewrite claims that the repository is "clean-room" unless there is documented personnel and process separation that actually supports that term.
2. Replace "this project contains no proprietary code" with narrower, accurate wording.
3. State plainly that the project is an independent reimplementation informed by reverse engineering for interoperability and research.
4. Avoid language that implies legal clearance.

Suggested positioning:

- "OpenPod is an independent research and interoperability project."
- "The implementation is source-original, but protocol behavior and compatibility details were derived through reverse engineering."
- "The repository does not include proprietary vendor binaries or source code."

### 8.2 Remove Obsolete `.so` References

Priority: P0

Actions:

1. Remove stale comments claiming the crypto path delegates to `libc3ec87.so`.
2. Remove test harnesses, Docker flows, or developer instructions that depend on the proprietary library, unless they are preserved privately for counsel-directed provenance work.
3. Remove mentions that current behavior "matches" the official library unless that comparison is needed and can be described more neutrally.
4. Replace comments that tie implementation to exact proprietary binary layouts with comments that describe protocol behavior only.

Expected result:

- The repo no longer advertises a dependency on proprietary binaries it does not ship or need.

### 8.3 Remove Decompilation-Derived Naming And Commentary

Priority: P0

Actions:

1. Replace references to internal decompiled enum names such as `EnumC...`.
2. Remove comments that say exact offsets or structures came from `libc3ec87.so` unless counsel advises keeping them in a private provenance record.
3. Replace comments like "derived from reverse-engineered PDM app" with comments that describe what the value does, not where it came from.

Important note:

This does not erase provenance. It reduces self-inflicted evidence that expressive details of the vendor implementation were imported into the public repo unnecessarily.

### 8.4 Add In-App Legal And Safety Positioning

Priority: P0

Actions:

1. Add a research-only / not-approved / not-affiliated warning inside onboarding, not only in the README.
2. Add an explicit warning before any real-pod workflow is enabled.
3. Avoid UI copy that sounds like a consumer-ready therapy controller.
4. Make the first-run experience clearly state that the software is not manufacturer-supported and not authorized for human insulin delivery.

Expected result:

- Product-facing intended use becomes less aggressive than the current marketing copy.

### 8.5 Add An Actual License

Priority: P0

Actions:

1. Choose and publish a repository license.
2. If public contribution is intended, add contribution terms and provenance expectations.
3. If the project is not ready for open collaboration, say so explicitly instead of leaving the repo unlicensed.

## 9. Structural Mitigations

These actions require more effort but matter more over time.

### 9.1 Create A Private Provenance Record

Priority: P1

Goal:

Maintain an internal record of what was reverse engineered, from where, by whom, and how it influenced the implementation.

Actions:

1. Build a private provenance ledger outside the public repo.
2. For each major subsystem, record:
   - whether behavior came from public standards
   - whether behavior came from packet capture / black-box testing
   - whether behavior came from APK decompilation
   - whether behavior came from decompiled native code
3. Mark high-risk areas where public code most closely tracks proprietary internal structures.
4. Preserve this for counsel, not for public marketing.

Why this matters:

- If challenged, a disciplined private record is more useful than broad public "clean-room" claims.

### 9.2 Rebuild High-Risk Components From Public Behavioral Specs

Priority: P1

Goal:

Reduce reliance on proprietary internal structure where possible.

Actions:

1. Identify the highest-risk areas:
   - exact binary structure layouts
   - internal enum mappings
   - vendor-specific storage formats
   - code paths described primarily by decompiled comments rather than observed behavior
2. For each area, ask:
   - is this actually required for interoperability?
   - can the same result be derived from on-wire behavior or public standards?
   - can the representation be redesigned without mirroring vendor internals?
3. Rewrite internal models to use neutral naming and repo-owned abstractions.
4. Keep only the minimum device-compatibility facts needed to interoperate.

### 9.3 Narrow Public Intended Use

Priority: P1

Goal:

Reduce the mismatch between current legal posture and current product claims.

Actions:

1. Reframe public docs around:
   - research
   - interoperability study
   - emulator and protocol validation
   - non-clinical experimentation
2. Remove consumer-style controller marketing language until there is a credible regulatory strategy.
3. Consider disabling or gating real-pod paths in public builds.
4. Consider separating:
   - emulator-only development builds
   - private experimental builds
   - any future regulated product path

### 9.4 Add Contributor Provenance Rules

Priority: P1

Goal:

Prevent future repo contamination.

Actions:

1. Add `CONTRIBUTING.md` rules that prohibit committing:
   - proprietary vendor binaries
   - decompiled source output
   - screenshots of decompiled code
   - copied comments or names from proprietary code
2. Require contributors to describe the source of protocol facts:
   - public standard
   - packet capture
   - emulator behavior
   - independent testing
3. Require legal review before merging any contribution that references reverse-engineered proprietary artifacts directly.

## 10. Regulatory Mitigation Plan

If this project remains public and continues toward real-pod control, it needs a serious regulatory strategy. Documentation disclaimers alone are not enough.

### 10.1 Short-Term Position

Priority: P0

Actions:

1. Treat the public repo as a research project, not as a deployable therapy controller.
2. Avoid distribution channels that suggest consumer medical use.
3. Do not represent the app as safe, validated, or clinically usable.

### 10.2 Product Separation

Priority: P1

Actions:

1. Separate emulator-only and protocol-research features from any real-pod control path.
2. Consider feature flags that hard-disable real-device delivery in public builds.
3. Keep all real-pod work behind explicit local development gating.

### 10.3 Counsel And Regulatory Review

Priority: P1

Actions:

1. Obtain U.S. counsel with software, medical-device, IP, and open-source experience.
2. Review intended use statements in:
   - README
   - app onboarding
   - repository description
   - release notes
   - screenshots
3. Review whether any testing, distribution, or claims create additional FDA or state-law risk.

## 11. Trademark Mitigation Plan

Priority: P1

Actions:

1. Keep trademark use strictly nominative.
2. Use vendor marks only as needed to describe compatibility targets.
3. Add non-affiliation language anywhere compatibility is described, not only in the README.
4. Do not use manufacturer logos, app screenshots, or trade dress.
5. Consider whether the project name, iconography, or listing copy implies affiliation.
6. Review all app-store-style copy before publication.

## 12. Recommended Execution Plan

### Phase 0: Legal Hygiene Cleanup

Target: 1 week

Deliverables:

1. Remove stale `libc3ec87.so` references from current code comments and docs.
2. Remove or rewrite all "clean-room" language.
3. Rewrite README legal positioning for accuracy.
4. Add an actual `LICENSE`.
5. Add in-app non-affiliation and research-only warnings.

Exit criteria:

- The public repo no longer overclaims legal cleanliness.
- The repo no longer contains obsolete proprietary-library dependencies.

### Phase 1: Provenance And Contribution Controls

Target: 2 to 3 weeks

Deliverables:

1. Private provenance ledger.
2. `CONTRIBUTING.md` with provenance restrictions.
3. Internal review of all comments and identifiers that refer to decompiled internals.
4. Removal or renaming of unnecessary decompilation-derived identifiers.

Exit criteria:

- Future contamination risk is meaningfully reduced.

### Phase 2: Public Positioning Reset

Target: 2 weeks

Deliverables:

1. README, docs, and onboarding copy reframed around research and interoperability.
2. Public builds gated away from real-pod delivery where feasible.
3. A written decision on whether the public repo supports:
   - emulator only
   - protocol research only
   - private real-device experimentation only

Exit criteria:

- Public intended use is materially narrower than today.

### Phase 3: Counsel Review

Target: before any broader distribution

Deliverables:

1. Outside counsel review of copyright, DMCA, trademark, and medical-device posture.
2. Decision memo on:
   - whether to keep the repo public
   - whether to distribute binaries
   - whether to keep real-pod support in the public tree
   - whether to pursue a formal regulatory path

Exit criteria:

- No further expansion of public distribution without legal signoff.

## 13. File-Level Cleanup Candidates

The following areas should be reviewed first during Phase 0 and Phase 1:

- `README.md`
- `core/ble/src/main/kotlin/com/openpod/core/ble/BleConstants.kt`
- `core/protocol/src/main/kotlin/com/openpod/core/protocol/rhp/RhpOpcode.kt`
- `core/protocol/src/main/kotlin/com/openpod/core/protocol/session/CryptoManager.kt`
- `core/crypto/src/main/kotlin/com/openpod/core/crypto/pure/SimProfileStore.kt`
- `emulator/omnipod_emulator/protocol/commands.py`
- `emulator/omnipod_emulator/protocol/pairing.py`
- `emulator/omnipod_emulator/pod/state.py`
- `tests/native_vectors/`
- onboarding and any user-facing strings describing real-pod control

Each file should be reviewed for:

1. obsolete `.so` references
2. decompiled internal names
3. exact proprietary layout claims
4. statements of legal cleanliness that are too broad
5. user-facing intended-use language that sounds clinically deployable

## 14. Red Lines For The Public Repo

The public repository should not contain:

1. proprietary vendor binaries
2. decompiled source output
3. copied code comments or names from proprietary source where avoidable
4. instructions telling contributors how to extract proprietary artifacts
5. claims of approval, endorsement, or compatibility certification
6. consumer-facing copy implying safe human insulin delivery

## 15. Bottom Line

The most important fact is now confirmed:

- this repository was built using decompilation and reverse engineering of the official APK

That does not automatically make the project indefensible. It does mean the current public language is too aggressive and too clean relative to the actual provenance.

The right mitigation is not to pretend the reverse engineering did not happen.

The right mitigation is to:

1. describe it accurately
2. minimize unnecessary public references to proprietary internals
3. stop claiming "clean-room" status that the record does not support
4. narrow the public intended use
5. remove obsolete `.so` workflows
6. get counsel involved before real-pod distribution expands

## 16. Reference Sources

These sources informed the original review and should be part of counsel prep:

- U.S. Copyright Office, `37 C.F.R. § 201.40`
- FDA guidance on device software functions and mobile medical apps
- FDA safety communication on smartphone-connected diabetes device alerts
- USPTO materials on trademark infringement and likelihood of confusion

