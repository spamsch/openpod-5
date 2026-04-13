"""
Integration test for the Elmo host-side bridge.

Exercises the full Stage 2 + Stage 3 path end-to-end, without any
Elmo attach or BLE: start a real ``DebugBridge`` on an ephemeral
localhost port, call ``elmo_bridge.fetch_app_conf`` against it with
real session material, and verify the returned bytes match what
``compute_controller_confirmation`` produces directly — and that the
pod-side state machine accepts the result as a valid phone-side
confirmation (the same round-trip guarantee Stage 1 proved in isolation).

The host-side bridge source lives in ``omnipod-connector/v6.9.8/``, which
is the Elmo harness project and not part of this package.  We add it
to ``sys.path`` at import time so the test can reach it without
installing it.
"""

from __future__ import annotations

import os
import sys
from collections.abc import Callable
from typing import Optional

import pytest

from omnipod_emulator.crypto.ecdh import EcdhKeyPair
from omnipod_emulator.debug_bridge import DebugBridge, SharedSecretProvider
from omnipod_emulator.protocol.pairing import (
    PairingStateMachine,
    compute_controller_confirmation,
)

# Reach across projects into v6.9.8/elmo_bridge.py.
_HARNESS_DIR = os.path.abspath(
    os.path.join(
        os.path.dirname(__file__),
        "..", "..", "..",
        "omnipod-connector", "v6.9.8",
    )
)
if _HARNESS_DIR not in sys.path:
    sys.path.insert(0, _HARNESS_DIR)

elmo_bridge = pytest.importorskip(
    "elmo_bridge",
    reason=(
        "v6.9.8/elmo_bridge.py not reachable from this test run "
        "(expected at ../../omnipod-connector/v6.9.8/elmo_bridge.py)"
    ),
)


_FIRMWARE_ID = b"\x01\x02\x03\x04\x05\x06"
_CONTROLLER_ID = b"\xaa\xbb\xcc\xdd"


def _fresh_session(algorithm: int = 0x01):
    pod_sm = PairingStateMachine(
        firmware_id=_FIRMWARE_ID,
        controller_id=_CONTROLLER_ID,
        ecdh_seed=b"\xcc" * 32,
        algorithm=algorithm,
    )
    pod_pub, pod_nonce = pod_sm.initialize()
    phone_kp = EcdhKeyPair(seed=b"\xdd" * 32, algorithm=algorithm)
    phone_pub = phone_kp.public_key_bytes
    phone_nonce = phone_kp.nonce
    pod_sm.set_peer_data(phone_pub, phone_nonce)
    _ = pod_sm.derive_keys_and_compute_confirmation()
    shared = phone_kp.compute_shared_secret(pod_pub)
    return pod_sm, pod_pub, pod_nonce, phone_pub, phone_nonce, shared


def _make_provider(
    pod_pub: bytes, shared: bytes,
) -> SharedSecretProvider:
    def provider(got_pod_pub: bytes, _ctrl_pub: bytes) -> Optional[bytes]:
        return shared if got_pod_pub == pod_pub else None
    return provider


@pytest.fixture
def bridge_factory() -> Callable[[SharedSecretProvider], DebugBridge]:
    started: list[DebugBridge] = []

    def _factory(provider: SharedSecretProvider) -> DebugBridge:
        b = DebugBridge(provider)
        b.start(host="127.0.0.1", port=0)
        started.append(b)
        return b

    yield _factory
    for b in started:
        b.stop()


class TestHostBridgeIntegration:
    """fetch_app_conf against a real DebugBridge."""

    @pytest.mark.parametrize("algorithm", [0x00, 0x01])
    def test_round_trip(self, bridge_factory, algorithm: int) -> None:
        pod_sm, pod_pub, pod_nonce, phone_pub, phone_nonce, shared = (
            _fresh_session(algorithm=algorithm)
        )
        bridge = bridge_factory(_make_provider(pod_pub, shared))
        host, port = bridge.address  # type: ignore[misc]
        url = f"http://{host}:{port}/app_conf"

        fields = {
            "controller_id": _CONTROLLER_ID.hex(),
            "firmware_id": _FIRMWARE_ID.hex(),
            "controller_public_key": phone_pub.hex(),
            "controller_nonce": phone_nonce.hex(),
            "pod_public_key": pod_pub.hex(),
            "pod_nonce": pod_nonce.hex(),
        }
        conf = elmo_bridge.fetch_app_conf(url, fields, timeout=2.0)

        # 1. Identical to a direct in-process compute.
        direct = compute_controller_confirmation(
            controller_id=_CONTROLLER_ID,
            firmware_id=_FIRMWARE_ID,
            controller_public_key=phone_pub,
            controller_nonce=phone_nonce,
            pod_public_key=pod_pub,
            pod_nonce=pod_nonce,
            shared_secret=shared,
        )
        assert conf == direct

        # 2. Pod-side verifier accepts it (end-to-end round trip).
        assert pod_sm.verify_peer_confirmation(conf) is True

    def test_provider_mismatch_raises_bridge_error(
        self, bridge_factory,
    ) -> None:
        _, pod_pub, pod_nonce, phone_pub, phone_nonce, _ = _fresh_session()
        bridge = bridge_factory(lambda *_: None)  # never matches
        host, port = bridge.address  # type: ignore[misc]
        url = f"http://{host}:{port}/app_conf"

        with pytest.raises(elmo_bridge.BridgeError) as excinfo:
            elmo_bridge.fetch_app_conf(
                url,
                {
                    "controller_id": _CONTROLLER_ID.hex(),
                    "firmware_id": _FIRMWARE_ID.hex(),
                    "controller_public_key": phone_pub.hex(),
                    "controller_nonce": phone_nonce.hex(),
                    "pod_public_key": pod_pub.hex(),
                    "pod_nonce": pod_nonce.hex(),
                },
            )
        assert "404" in str(excinfo.value)

    def test_missing_field_raises_before_http(
        self, bridge_factory,
    ) -> None:
        bridge = bridge_factory(lambda *_: None)
        host, port = bridge.address  # type: ignore[misc]
        url = f"http://{host}:{port}/app_conf"

        with pytest.raises(elmo_bridge.BridgeError, match="missing fields"):
            elmo_bridge.fetch_app_conf(url, {"controller_id": "aabbccdd"})

    def test_unreachable_bridge_raises(self) -> None:
        # Port 1 is privileged and unbound on every dev box.
        with pytest.raises(elmo_bridge.BridgeError):
            elmo_bridge.fetch_app_conf(
                "http://127.0.0.1:1/app_conf",
                {
                    "controller_id": _CONTROLLER_ID.hex(),
                    "firmware_id": _FIRMWARE_ID.hex(),
                    "controller_public_key": "aa" * 64,
                    "controller_nonce": "bb" * 16,
                    "pod_public_key": "cc" * 64,
                    "pod_nonce": "dd" * 16,
                },
                timeout=1.0,
            )


class TestHandleScriptMessageRouting:
    """handle_script_message() dispatches correctly and posts replies."""

    def test_non_send_messages_are_ignored(self) -> None:
        posted: list[dict] = []
        fake_script = type("S", (), {"post": posted.append})()
        handled = elmo_bridge.handle_script_message(
            fake_script,
            {"type": "log", "payload": "hello"},
            None,
            bridge_url="http://unused",
        )
        assert handled is False
        assert posted == []

    def test_non_app_conf_sends_are_ignored(self) -> None:
        posted: list[dict] = []
        fake_script = type("S", (), {"post": posted.append})()
        handled = elmo_bridge.handle_script_message(
            fake_script,
            {"type": "send", "payload": {"cmd": "something_else"}},
            None,
            bridge_url="http://unused",
        )
        assert handled is False
        assert posted == []

    def test_app_conf_success_posts_result(self, bridge_factory) -> None:
        _, pod_pub, pod_nonce, phone_pub, phone_nonce, shared = _fresh_session()
        bridge = bridge_factory(_make_provider(pod_pub, shared))
        host, port = bridge.address  # type: ignore[misc]
        url = f"http://{host}:{port}/app_conf"

        posted: list[dict] = []
        fake_script = type("S", (), {"post": posted.append})()
        msg = {
            "type": "send",
            "payload": {
                "cmd": "app_conf",
                "controller_id": _CONTROLLER_ID.hex(),
                "firmware_id": _FIRMWARE_ID.hex(),
                "controller_public_key": phone_pub.hex(),
                "controller_nonce": phone_nonce.hex(),
                "pod_public_key": pod_pub.hex(),
                "pod_nonce": pod_nonce.hex(),
            },
        }
        handled = elmo_bridge.handle_script_message(
            fake_script, msg, None, bridge_url=url,
        )
        assert handled is True
        assert len(posted) == 1
        assert posted[0]["type"] == "app_conf_reply"
        assert posted[0]["ok"] is True
        conf = bytes.fromhex(posted[0]["confirmation"])
        # Shape check: P-256 = 64 + 64 + 8
        assert len(conf) == 136

    def test_app_conf_failure_posts_error(self, bridge_factory) -> None:
        bridge = bridge_factory(lambda *_: None)  # always 404
        host, port = bridge.address  # type: ignore[misc]
        url = f"http://{host}:{port}/app_conf"

        posted: list[dict] = []
        fake_script = type("S", (), {"post": posted.append})()
        msg = {
            "type": "send",
            "payload": {
                "cmd": "app_conf",
                "controller_id": _CONTROLLER_ID.hex(),
                "firmware_id": _FIRMWARE_ID.hex(),
                "controller_public_key": "aa" * 64,
                "controller_nonce": "bb" * 16,
                "pod_public_key": "cc" * 64,
                "pod_nonce": "dd" * 16,
            },
        }
        handled = elmo_bridge.handle_script_message(
            fake_script, msg, None, bridge_url=url,
        )
        assert handled is True
        assert len(posted) == 1
        assert posted[0]["type"] == "app_conf_reply"
        assert posted[0]["ok"] is False
        assert "404" in posted[0]["error"]
