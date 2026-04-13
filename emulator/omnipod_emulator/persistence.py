"""
Pairing-state persistence for the Omnipod 5 emulator.

The emulator's in-memory `ProtocolSession` loses its LTK and assigned
pod identity on restart. A real pod retains them across power cycles —
without that, the PDM cannot reconnect after the first successful
pairing because it stores the LTK on the phone and skips straight to
EAP-AKA on subsequent connections.

This module provides a tiny JSON file format for that state. It is
intentionally minimal: enough fields to bootstrap an `EapAkaSlave` and
let the transport layer answer post-pairing TWI traffic.

File format (JSON, hex-encoded byte fields)::

    {
      "version": 1,
      "key_id": "default",
      "ltk": "<32 hex chars>",
      "controller_id": "<8 hex chars>",
      "firmware_id": "<12 hex chars>",
      "sqn": "<12 hex chars>",
      "saved_at": "<ISO-8601 UTC timestamp>"
    }

Multiple identities are supported by selecting a different file path
or, in the same file, a different ``key_id``. The emulator currently
loads exactly one identity at a time (chosen by ``key_id``); selecting
between identities is the operator's job at startup.
"""

from __future__ import annotations

import datetime as _dt
import json
import logging
import os
from dataclasses import dataclass, field
from pathlib import Path

logger = logging.getLogger(__name__)

PERSISTENCE_VERSION = 1


@dataclass
class PairingStateRecord:
    """One persisted pairing identity."""

    ltk: bytes
    controller_id: bytes
    firmware_id: bytes
    sqn: bytes = field(default_factory=lambda: b"\x00" * 6)
    key_id: str = "default"
    saved_at: str = ""

    def __post_init__(self) -> None:
        if len(self.ltk) != 16:
            raise ValueError(f"ltk must be 16 bytes, got {len(self.ltk)}")
        if len(self.controller_id) != 4:
            raise ValueError(
                f"controller_id must be 4 bytes, got {len(self.controller_id)}"
            )
        if len(self.firmware_id) != 6:
            raise ValueError(
                f"firmware_id must be 6 bytes, got {len(self.firmware_id)}"
            )
        if len(self.sqn) != 6:
            raise ValueError(f"sqn must be 6 bytes, got {len(self.sqn)}")

    def to_dict(self) -> dict:
        return {
            "version": PERSISTENCE_VERSION,
            "key_id": self.key_id,
            "ltk": self.ltk.hex(),
            "controller_id": self.controller_id.hex(),
            "firmware_id": self.firmware_id.hex(),
            "sqn": self.sqn.hex(),
            "saved_at": self.saved_at,
        }

    @classmethod
    def from_dict(cls, data: dict) -> "PairingStateRecord":
        version = data.get("version", 1)
        if version != PERSISTENCE_VERSION:
            raise ValueError(
                f"Unsupported persistence version: {version} "
                f"(expected {PERSISTENCE_VERSION})"
            )
        return cls(
            ltk=bytes.fromhex(data["ltk"]),
            controller_id=bytes.fromhex(data["controller_id"]),
            firmware_id=bytes.fromhex(data["firmware_id"]),
            sqn=bytes.fromhex(data.get("sqn", "00" * 6)),
            key_id=data.get("key_id", "default"),
            saved_at=data.get("saved_at", ""),
        )


def save_pairing_state(path: str | os.PathLike, record: PairingStateRecord) -> None:
    """
    Atomically write a pairing-state record to ``path`` as JSON.

    Writes to a sibling ``.tmp`` file first and renames into place to
    avoid leaving a half-written state file if the process is killed.
    """
    p = Path(path)
    p.parent.mkdir(parents=True, exist_ok=True)

    record.saved_at = _dt.datetime.now(_dt.timezone.utc).isoformat()
    tmp = p.with_suffix(p.suffix + ".tmp")
    tmp.write_text(json.dumps(record.to_dict(), indent=2) + "\n")
    tmp.replace(p)
    # Restrictive permissions — LTK is a long-term secret.
    try:
        os.chmod(p, 0o600)
    except OSError as e:
        logger.warning("Could not chmod %s to 0600: %s", p, e)
    logger.info(
        "Persisted pairing state to %s (key_id=%s)", p, record.key_id
    )


def load_pairing_state(
    path: str | os.PathLike, key_id: str = "default",
) -> PairingStateRecord | None:
    """
    Load a pairing-state record from ``path`` if it exists.

    Returns ``None`` if the file is missing. Raises on malformed
    content so the operator notices corrupted state instead of silently
    starting unpaired.

    The ``key_id`` argument is checked against the file's ``key_id``
    field. A mismatch logs a warning but still returns the record (so
    a typo in the CLI doesn't silently strand the operator).
    """
    p = Path(path)
    if not p.exists():
        logger.info("No pairing state file at %s — starting unpaired", p)
        return None

    try:
        data = json.loads(p.read_text())
    except (OSError, json.JSONDecodeError) as e:
        raise ValueError(f"Failed to read pairing state from {p}: {e}") from e

    record = PairingStateRecord.from_dict(data)

    if record.key_id != key_id:
        logger.warning(
            "Pairing state at %s has key_id=%s but %s was requested — "
            "loading anyway",
            p, record.key_id, key_id,
        )

    logger.info(
        "Loaded pairing state from %s (key_id=%s, saved_at=%s)",
        p, record.key_id, record.saved_at,
    )
    return record
