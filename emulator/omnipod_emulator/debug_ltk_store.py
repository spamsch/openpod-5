"""
Session-keyed in-memory LTK override store for the EAP-AKA debug path.

Background
----------
The emulator's LTK KDF is known to drift from the phone-side native
implementation. Rather than chasing that drift every live run, an
external dev-harness client (on the host running against the phone)
captures the phone's derived LTK during pairing and pushes it to the
emulator via the ``/debug/ltk`` HTTP bridge endpoint; the emulator then
uses the captured value in place of its own KDF output when running
the MILENAGE AUTN check.

Design
------
- A single process-level store guarded by a ``threading.Condition``.
- Values are keyed by a *session id* derived from the ECDH public keys
  of the current pairing attempt (``sha256(pod_pub || controller_pub)``,
  first 16 bytes). This rejects stale values from previous pairings that
  may still be sitting in the store.
- Writers (the bridge endpoint) call :func:`set_ltk` which stores the
  value and notifies any waiter.
- Readers (``EapAkaSlave._handle_challenge``) call :func:`wait_for_ltk`
  which blocks on the condition variable until a matching entry appears
  or the timeout elapses.

This module is deliberately tiny and has no dependencies beyond the
stdlib so it stays trivially testable.
"""

from __future__ import annotations

import hashlib
import logging
import threading

logger = logging.getLogger(__name__)

_SESSION_ID_LEN = 16
_LTK_LEN = 16

# Well-known session_id used by the reconnect path.
#
# Fresh-pair sessions key the store by ``sha256(pod_pub || ctrl_pub)[:16]``
# which is derived from the ECDH exchange. A reconnect has no ECDH and
# therefore no natural per-session identity — the phone simply reuses
# whatever LTK is sitting in its profile cache. We give that path its
# own namespace by deriving a sentinel id from a fixed string so it
# cannot collide with any real fresh-pair session_id (both the external
# dev-harness writer and the emulator must recompute the same constant
# at startup; keep the seed string identical on both sides).
RECONNECT_SESSION_ID: bytes = hashlib.sha256(
    b"openpod-reconnect-cache-v1"
).digest()[:_SESSION_ID_LEN]


def compute_session_id(pod_pub: bytes, controller_pub: bytes) -> bytes:
    """
    Derive a stable session id from the ECDH public keys exchanged
    during pairing.

    The same formula must be used on both the external dev-harness
    (writer) and the emulator (reader) sides so the store lookup
    matches.
    """
    if not pod_pub or not controller_pub:
        raise ValueError("pod_pub and controller_pub must both be non-empty")
    digest = hashlib.sha256(pod_pub + controller_pub).digest()
    return digest[:_SESSION_ID_LEN]


class _LtkStore:
    """Process-level store; do not instantiate directly — use the module API."""

    def __init__(self) -> None:
        self._cv = threading.Condition()
        self._entries: dict[bytes, bytes] = {}

    def set_ltk(self, session_id: bytes, ltk: bytes) -> None:
        if len(session_id) != _SESSION_ID_LEN:
            raise ValueError(
                f"session_id must be {_SESSION_ID_LEN} bytes, "
                f"got {len(session_id)}"
            )
        if len(ltk) != _LTK_LEN:
            raise ValueError(
                f"ltk must be {_LTK_LEN} bytes, got {len(ltk)}"
            )
        with self._cv:
            prior = self._entries.get(session_id)
            self._entries[session_id] = ltk
            self._cv.notify_all()
        if prior is None:
            logger.info(
                "[ltk-store] stored LTK for session_id=%s",
                session_id.hex(),
            )
        elif prior != ltk:
            logger.warning(
                "[ltk-store] replaced existing LTK for session_id=%s",
                session_id.hex(),
            )

    def wait_for_ltk(
        self, session_id: bytes, timeout: float,
    ) -> bytes | None:
        return self._wait(session_id, timeout, consume=False)

    def consume_ltk(
        self, session_id: bytes, timeout: float,
    ) -> bytes | None:
        return self._wait(session_id, timeout, consume=True)

    def _wait(
        self, session_id: bytes, timeout: float, *, consume: bool,
    ) -> bytes | None:
        if len(session_id) != _SESSION_ID_LEN:
            raise ValueError(
                f"session_id must be {_SESSION_ID_LEN} bytes, "
                f"got {len(session_id)}"
            )
        if timeout < 0:
            raise ValueError("timeout must be non-negative")

        deadline: float | None
        if timeout == 0:
            deadline = None
        else:
            deadline = _monotonic() + timeout

        with self._cv:
            while True:
                entry = self._entries.get(session_id)
                if entry is not None:
                    if consume:
                        del self._entries[session_id]
                    return entry
                if deadline is None:
                    return None
                remaining = deadline - _monotonic()
                if remaining <= 0:
                    return None
                self._cv.wait(timeout=remaining)

    def clear(self) -> None:
        with self._cv:
            self._entries.clear()
            self._cv.notify_all()


# Module-level singleton + thin API. A singleton keeps the bridge process
# (writer) and the EapAkaSlave (reader) sharing state by import, without
# needing to plumb a store handle through every construction site.
_store = _LtkStore()


def _monotonic() -> float:
    # Indirection so tests can monkeypatch time without touching
    # threading.Condition.wait semantics.
    import time

    return time.monotonic()


def set_ltk(session_id: bytes, ltk: bytes) -> None:
    """Store an LTK for a pairing session and wake any waiter."""
    _store.set_ltk(session_id, ltk)


def wait_for_ltk(
    session_id: bytes, timeout: float,
) -> bytes | None:
    """
    Block up to ``timeout`` seconds waiting for an LTK matching
    ``session_id``. Returns the LTK bytes or ``None`` on timeout.

    ``timeout=0`` means "poll" — return immediately if no entry exists.

    This is the *non-destructive* read. The entry stays in the store
    after the call, so a later lookup for the same ``session_id`` will
    still find it. Use :func:`consume_ltk` instead when the caller
    wants "use exactly once" semantics.
    """
    return _store.wait_for_ltk(session_id, timeout)


def consume_ltk(
    session_id: bytes, timeout: float,
) -> bytes | None:
    """
    Wait for an LTK like :func:`wait_for_ltk` and then atomically
    delete the entry before returning it.

    Used by the fresh-pair dynamic LTK override barrier: each ECDH
    session produces a unique ``session_id`` derived from the pubkeys,
    so an override only has one legitimate reader. Removing the entry
    after that read defends against a late or duplicate push from a
    previous ECDH session ever being mis-applied to a new slave that
    happens to reuse the same ``session_id`` (cryptographically
    improbable for random pubkeys, but cheap insurance).

    The reconnect sentinel path deliberately uses
    :func:`wait_for_ltk` instead — the same LTK is expected to satisfy
    repeated reconnect attempts inside a single emulator process.
    """
    return _store.consume_ltk(session_id, timeout)


def clear() -> None:
    """Drop all stored entries (test-only)."""
    _store.clear()
