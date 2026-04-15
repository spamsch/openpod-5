"""
Tests for the session-keyed LTK override store.

These exercise the threading contract directly — the store is a
singleton that the debug HTTP bridge writes to and the
``EapAkaSlave._handle_challenge`` barrier reads from — so we care
about: value/timeout semantics, session-id gating, and that a concurrent
writer actually wakes a blocked reader.
"""

from __future__ import annotations

import hashlib
import threading
import time

import pytest

from omnipod_emulator import debug_ltk_store


@pytest.fixture(autouse=True)
def _clear_store():
    """Keep tests isolated: drop any entries a sibling test may have pushed."""
    debug_ltk_store.clear()
    yield
    debug_ltk_store.clear()


def test_reconnect_session_id_is_stable_and_derived_from_seed() -> None:
    # The constant is keyed off a fixed seed string so both the Pi
    # emulator and the external dev-harness writer can recompute it
    # independently. Any accidental change to that seed breaks the
    # reconnect barrier.
    expected = hashlib.sha256(b"openpod-reconnect-cache-v1").digest()[:16]
    assert debug_ltk_store.RECONNECT_SESSION_ID == expected
    assert len(debug_ltk_store.RECONNECT_SESSION_ID) == 16


def test_reconnect_slot_round_trips() -> None:
    ltk = b"\xa1" * 16
    debug_ltk_store.set_ltk(debug_ltk_store.RECONNECT_SESSION_ID, ltk)
    assert (
        debug_ltk_store.wait_for_ltk(
            debug_ltk_store.RECONNECT_SESSION_ID, timeout=0.0,
        )
        == ltk
    )


def test_compute_session_id_stable() -> None:
    pod = b"\x11" * 64
    ctrl = b"\x22" * 64
    sid = debug_ltk_store.compute_session_id(pod, ctrl)
    assert len(sid) == 16
    assert sid == hashlib.sha256(pod + ctrl).digest()[:16]


def test_compute_session_id_order_sensitive() -> None:
    pod = b"\x11" * 64
    ctrl = b"\x22" * 64
    # Swapping the operands must produce a different id — otherwise a
    # reader computing (pod, ctrl) would accidentally match a writer
    # that hashed (ctrl, pod).
    assert (
        debug_ltk_store.compute_session_id(pod, ctrl)
        != debug_ltk_store.compute_session_id(ctrl, pod)
    )


def test_compute_session_id_rejects_empty() -> None:
    with pytest.raises(ValueError):
        debug_ltk_store.compute_session_id(b"", b"\x11" * 64)
    with pytest.raises(ValueError):
        debug_ltk_store.compute_session_id(b"\x11" * 64, b"")


def test_set_rejects_wrong_lengths() -> None:
    with pytest.raises(ValueError):
        debug_ltk_store.set_ltk(b"\x00" * 15, b"\x01" * 16)
    with pytest.raises(ValueError):
        debug_ltk_store.set_ltk(b"\x00" * 16, b"\x01" * 15)


def test_wait_rejects_bad_args() -> None:
    with pytest.raises(ValueError):
        debug_ltk_store.wait_for_ltk(b"\x00" * 15, timeout=0.1)
    with pytest.raises(ValueError):
        debug_ltk_store.wait_for_ltk(b"\x00" * 16, timeout=-1)


def test_wait_returns_value_already_present() -> None:
    sid = b"\x00" * 16
    ltk = b"\xab" * 16
    debug_ltk_store.set_ltk(sid, ltk)
    assert debug_ltk_store.wait_for_ltk(sid, timeout=0.0) == ltk


def test_wait_poll_semantics() -> None:
    # timeout=0 must not block — it polls.
    sid = b"\x00" * 16
    t0 = time.monotonic()
    assert debug_ltk_store.wait_for_ltk(sid, timeout=0.0) is None
    assert time.monotonic() - t0 < 0.05


def test_wait_times_out_when_no_writer() -> None:
    sid = b"\x01" * 16
    t0 = time.monotonic()
    result = debug_ltk_store.wait_for_ltk(sid, timeout=0.15)
    elapsed = time.monotonic() - t0
    assert result is None
    assert 0.1 <= elapsed < 0.5, elapsed


def test_wait_ignores_wrong_session_id() -> None:
    sid_a = b"\x0a" * 16
    sid_b = b"\x0b" * 16
    debug_ltk_store.set_ltk(sid_a, b"\x00" * 16)
    # Lookup for sid_b must not pick up sid_a's entry.
    assert debug_ltk_store.wait_for_ltk(sid_b, timeout=0.05) is None


def test_concurrent_writer_wakes_reader() -> None:
    sid = b"\x02" * 16
    ltk = b"\xcd" * 16
    seen: list[bytes | None] = []

    def reader() -> None:
        seen.append(debug_ltk_store.wait_for_ltk(sid, timeout=1.0))

    t = threading.Thread(target=reader)
    t.start()
    # Give the reader a moment to park on the condition variable.
    time.sleep(0.05)
    debug_ltk_store.set_ltk(sid, ltk)
    t.join(timeout=1.0)
    assert seen == [ltk]


def test_replacement_logs_but_succeeds() -> None:
    sid = b"\x03" * 16
    debug_ltk_store.set_ltk(sid, b"\x00" * 16)
    debug_ltk_store.set_ltk(sid, b"\xff" * 16)
    assert debug_ltk_store.wait_for_ltk(sid, timeout=0.0) == b"\xff" * 16


def test_clear() -> None:
    sid = b"\x04" * 16
    debug_ltk_store.set_ltk(sid, b"\x11" * 16)
    debug_ltk_store.clear()
    assert debug_ltk_store.wait_for_ltk(sid, timeout=0.0) is None


def test_consume_ltk_removes_entry() -> None:
    sid = b"\x05" * 16
    ltk = b"\x99" * 16
    debug_ltk_store.set_ltk(sid, ltk)
    assert debug_ltk_store.consume_ltk(sid, timeout=0.0) == ltk
    # Second lookup must miss — consume is destructive by design so
    # the fresh-pair barrier cannot silently reuse a stale override.
    assert debug_ltk_store.wait_for_ltk(sid, timeout=0.0) is None


def test_consume_blocks_then_returns_and_deletes() -> None:
    sid = b"\x06" * 16
    ltk = b"\x77" * 16
    seen: list[bytes | None] = []

    def reader() -> None:
        seen.append(debug_ltk_store.consume_ltk(sid, timeout=1.0))

    t = threading.Thread(target=reader)
    t.start()
    time.sleep(0.05)
    debug_ltk_store.set_ltk(sid, ltk)
    t.join(timeout=1.0)
    assert seen == [ltk]
    assert debug_ltk_store.wait_for_ltk(sid, timeout=0.0) is None


