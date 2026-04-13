# Native Crypto Test Vectors

This directory contains a C test harness and Docker setup for validating
cryptographic operations against reference vectors from the original
Omnipod 5 native library (`libc3ec87.so`).

## Important

The `.so` file and C harness in this directory are **test artifacts only**.
They are not used at runtime by the OpenPod application. The app uses a
pure-Kotlin crypto implementation (Bouncy Castle + JCA) — see `core:crypto`.

These vectors were used during development to verify that the pure-Kotlin
implementation produces identical output to the original native library
for MILENAGE, EAP-AKA, AES-CCM, and X25519 operations.

## Contents

- `libc3ec87.so` — ARM binary from the original native crypto (reference only)
- `test_crypto.c` — C harness that calls the native library functions
- `stub_liblog.c` — Android logging stub for off-device compilation
- `Dockerfile` — Cross-compilation environment for running the harness
- `test_vectors.json` — Known-answer test vectors

## Running

```bash
docker build -t openpod-vectors .
docker run --rm openpod-vectors
```
