# Contributing to OpenPod

Thank you for your interest in contributing to OpenPod. This document outlines the guidelines and requirements for contributions.

## Project Status

OpenPod is in early development. The core architecture is stabilizing and contributions are welcome in the areas outlined below.

## Provenance Requirements

All contributions must comply with the following provenance rules. These exist to protect the project and its contributors.

### Prohibited Content

Do not submit contributions that include:

- Proprietary vendor binaries (shared libraries, APKs, firmware images)
- Decompiled source code output from any proprietary application
- Screenshots or excerpts of decompiled code
- Copied comments, identifiers, or structural elements from proprietary source code
- Instructions for extracting or obtaining proprietary artifacts

### Source Declaration

For any contribution that introduces or modifies protocol behavior, you must describe the source of the protocol facts in your pull request description. Acceptable sources include:

- **Published standards** — e.g., RFC, 3GPP TS, IEEE, NIST specifications
- **Packet capture / on-wire observation** — data observed from BLE communication
- **Emulator behavior** — behavior derived from the project's own emulator
- **Independent testing** — behavior verified through black-box experimentation
- **Public documentation** — manufacturer-published documentation or specifications

### Review Process

Contributions that reference reverse-engineered proprietary artifacts directly will require additional review before merging.

## Code Standards

- All public APIs must have KDoc (Kotlin) or docstrings (Python)
- User-facing strings must be in `strings.xml`, not hardcoded
- Tests must cover happy path, edge cases, and failure modes
- State transitions and delivery actions must be logged and auditable
- Safety checks must validate inputs at system boundaries
- Use the pure-Kotlin crypto stack — no test-key shortcuts

## Getting Started

1. Fork the repository
2. Create a feature branch from `main`
3. Make your changes following the guidelines above
4. Run tests: `./gradlew test` (Kotlin) and `cd emulator && python -m pytest` (Python)
5. Submit a pull request with a clear description of the changes and their provenance

## License

By contributing to OpenPod, you agree that your contributions will be licensed under the GNU General Public License v3.0.
