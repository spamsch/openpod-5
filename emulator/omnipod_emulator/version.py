"""
Emulator version information.

The deploy-to-pi.sh script updates BUILD_HASH and BUILD_TIME on each deploy.
"""

VERSION = "0.1.0"
BUILD_HASH = "dev"
BUILD_TIME = "unknown"


def version_string() -> str:
    """Return a human-readable version string for display and GV responses."""
    return f"EMUL-{VERSION}+{BUILD_HASH}"


def banner() -> str:
    """Return a multi-line startup banner."""
    return (
        f"OpenPod Emulator v{VERSION} (build {BUILD_HASH}, {BUILD_TIME})\n"
        f"Protocol: Omnipod 5 BLE + EAP-AKA + AES-CCM + Text RHP"
    )
