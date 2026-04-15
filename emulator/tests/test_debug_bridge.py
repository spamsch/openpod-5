"""
Tests for the debug HTTP bridge used by the Elmo FGH-bypass pipeline.

The bridge computes the controller-side AES-CCM confirmation value on
behalf of the Elmo hook.  We test it directly via the stdlib HTTP
server (bound to an ephemeral localhost port) so the tests do not
require an active ProtocolSession or any BLE state.
"""

from __future__ import annotations

import json
import urllib.error
import urllib.request
from collections.abc import Callable
from typing import Optional

import pytest

from omnipod_emulator import debug_ltk_store
from omnipod_emulator.crypto.ecdh import EcdhKeyPair
from omnipod_emulator.debug_bridge import (
    DebugBridge,
    SessionSharedSecretProvider,
    SharedSecretProvider,
)
from omnipod_emulator.protocol.pairing import (
    PairingState,
    PairingStateMachine,
    compute_controller_confirmation,
)


_FIRMWARE_ID = b"\x01\x02\x03\x04\x05\x06"
_CONTROLLER_ID = b"\xaa\xbb\xcc\xdd"


def _fresh_session(algorithm: int = 0x01) -> tuple[
    PairingStateMachine, EcdhKeyPair, bytes, bytes, bytes, bytes, bytes,
]:
    """
    Build a fresh pod + phone keypair and return everything the bridge
    request needs, plus the shared secret the provider must return.
    """
    pod_sm = PairingStateMachine(
        firmware_id=_FIRMWARE_ID,
        controller_id=_CONTROLLER_ID,
        ecdh_seed=b"\xaa" * 32,
        algorithm=algorithm,
    )
    pod_pub, pod_nonce = pod_sm.initialize()
    phone_kp = EcdhKeyPair(seed=b"\xbb" * 32, algorithm=algorithm)
    phone_pub = phone_kp.public_key_bytes
    phone_nonce = phone_kp.nonce
    pod_sm.set_peer_data(phone_pub, phone_nonce)
    _ = pod_sm.derive_keys_and_compute_confirmation()
    shared = phone_kp.compute_shared_secret(pod_pub)
    return pod_sm, phone_kp, pod_pub, pod_nonce, phone_pub, phone_nonce, shared


def _make_provider(
    pod_pub_expected: bytes, shared: bytes,
) -> SharedSecretProvider:
    def provider(pod_pub: bytes, ctrl_pub: bytes) -> Optional[bytes]:
        if pod_pub != pod_pub_expected:
            return None
        return shared
    return provider


@pytest.fixture
def bridge_factory() -> Callable[[SharedSecretProvider], DebugBridge]:
    """
    Yields a factory that starts a DebugBridge on an ephemeral port.
    All bridges started via the factory are stopped at teardown.
    """
    started: list[DebugBridge] = []

    def _factory(provider: SharedSecretProvider) -> DebugBridge:
        b = DebugBridge(provider)
        # Port 0 → OS picks an unused port; read back via .address.
        b.start(host="127.0.0.1", port=0)
        started.append(b)
        return b

    yield _factory

    for b in started:
        b.stop()


def _post(bridge: DebugBridge, path: str, payload: object) -> tuple[int, dict]:
    host, port = bridge.address  # type: ignore[misc]
    url = f"http://{host}:{port}{path}"
    body = json.dumps(payload).encode("utf-8") if not isinstance(
        payload, (bytes, bytearray)
    ) else bytes(payload)
    req = urllib.request.Request(
        url,
        data=body,
        method="POST",
        headers={"Content-Type": "application/json"},
    )
    try:
        with urllib.request.urlopen(req, timeout=5) as resp:
            return resp.status, json.loads(resp.read())
    except urllib.error.HTTPError as e:
        try:
            return e.code, json.loads(e.read())
        except Exception:  # noqa: BLE001
            return e.code, {}


class TestDebugBridgeHappyPath:
    """Round-trip: request matches what compute_controller_confirmation gives."""

    @pytest.mark.parametrize("algorithm", [0x00, 0x01])
    def test_returns_controller_confirmation(
        self, bridge_factory, algorithm: int,
    ) -> None:
        (
            pod_sm,
            _phone_kp,
            pod_pub,
            pod_nonce,
            phone_pub,
            phone_nonce,
            shared,
        ) = _fresh_session(algorithm=algorithm)
        provider = _make_provider(pod_pub, shared)
        bridge = bridge_factory(provider)

        payload = {
            "controller_id": _CONTROLLER_ID.hex(),
            "firmware_id": _FIRMWARE_ID.hex(),
            "controller_public_key": phone_pub.hex(),
            "controller_nonce": phone_nonce.hex(),
            "pod_public_key": pod_pub.hex(),
            "pod_nonce": pod_nonce.hex(),
        }
        status, body = _post(bridge, "/app_conf", payload)
        assert status == 200
        assert "confirmation" in body

        conf = bytes.fromhex(body["confirmation"])

        # The pod's verifier must accept the bridge's output end-to-end.
        assert pod_sm.verify_peer_confirmation(conf) is True

        # And the bytes must match a direct call to the same helper.
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


class TestDebugBridgeErrors:
    """Input validation and provider failure modes."""

    def _valid_payload(self) -> tuple[dict, bytes, bytes]:
        _, _, pod_pub, pod_nonce, phone_pub, phone_nonce, shared = _fresh_session()
        return (
            {
                "controller_id": _CONTROLLER_ID.hex(),
                "firmware_id": _FIRMWARE_ID.hex(),
                "controller_public_key": phone_pub.hex(),
                "controller_nonce": phone_nonce.hex(),
                "pod_public_key": pod_pub.hex(),
                "pod_nonce": pod_nonce.hex(),
            },
            pod_pub,
            shared,
        )

    def test_unknown_path_is_404(self, bridge_factory) -> None:
        bridge = bridge_factory(lambda *_: None)
        status, body = _post(bridge, "/nope", {})
        assert status == 404
        assert "error" in body

    def test_missing_field_is_400(self, bridge_factory) -> None:
        payload, pod_pub, shared = self._valid_payload()
        bridge = bridge_factory(_make_provider(pod_pub, shared))
        del payload["pod_nonce"]
        status, body = _post(bridge, "/app_conf", payload)
        assert status == 400
        assert "pod_nonce" in body.get("error", "")

    def test_bad_hex_is_400(self, bridge_factory) -> None:
        payload, pod_pub, shared = self._valid_payload()
        bridge = bridge_factory(_make_provider(pod_pub, shared))
        payload["controller_id"] = "not-hex"
        status, body = _post(bridge, "/app_conf", payload)
        assert status == 400

    def test_provider_returns_none_is_404(self, bridge_factory) -> None:
        payload, _pod_pub, _shared = self._valid_payload()
        # Provider that never matches.
        bridge = bridge_factory(lambda *_: None)
        status, body = _post(bridge, "/app_conf", payload)
        assert status == 404

    def test_short_nonce_is_400(self, bridge_factory) -> None:
        payload, pod_pub, shared = self._valid_payload()
        bridge = bridge_factory(_make_provider(pod_pub, shared))
        payload["controller_nonce"] = "aa" * 8  # 8 bytes, not 16
        status, body = _post(bridge, "/app_conf", payload)
        assert status == 400

    def test_empty_body_is_400(self, bridge_factory) -> None:
        bridge = bridge_factory(lambda *_: None)
        host, port = bridge.address  # type: ignore[misc]
        req = urllib.request.Request(
            f"http://{host}:{port}/app_conf",
            data=b"",
            method="POST",
        )
        try:
            with urllib.request.urlopen(req, timeout=5) as resp:
                status, body = resp.status, json.loads(resp.read())
        except urllib.error.HTTPError as e:
            status, body = e.code, json.loads(e.read())
        assert status == 400


class _FakeSession:
    """Minimal ``ProtocolSession`` stand-in for provider unit tests."""

    def __init__(self, pairing: PairingStateMachine | None) -> None:
        self._pairing = pairing


class TestSessionSharedSecretProvider:
    """
    SessionSharedSecretProvider must reject every stale / mismatched /
    pre-SPS1 case and only return a secret when the active session is
    in exactly the right state with byte-for-byte matching keys.
    """

    def _prepared_session(
        self, algorithm: int = 0x01,
    ) -> tuple[_FakeSession, PairingStateMachine, EcdhKeyPair, bytes, bytes]:
        pod_sm = PairingStateMachine(
            firmware_id=_FIRMWARE_ID,
            controller_id=_CONTROLLER_ID,
            # Safe small seed: stays well below the P-256 curve order.
            ecdh_seed=b"\x33" * 32,
            algorithm=algorithm,
        )
        pod_pub, _pod_nonce = pod_sm.initialize()
        phone_kp = EcdhKeyPair(seed=b"\x44" * 32, algorithm=algorithm)
        session = _FakeSession(pod_sm)
        return session, pod_sm, phone_kp, pod_pub, phone_kp.public_key_bytes

    def test_no_pairing_returns_none(self) -> None:
        provider = SessionSharedSecretProvider(_FakeSession(None))
        assert provider(b"aa" * 32, b"bb" * 32) is None

    def test_pre_peer_data_returns_none(self) -> None:
        session, pod_sm, _phone_kp, pod_pub, phone_pub = self._prepared_session()
        # Still in INITIALIZED — peer key not set yet.
        assert pod_sm.state == PairingState.INITIALIZED
        provider = SessionSharedSecretProvider(session)
        assert provider(pod_pub, phone_pub) is None

    def test_happy_path_returns_matching_secret(self) -> None:
        session, pod_sm, phone_kp, pod_pub, phone_pub = self._prepared_session()
        pod_sm.set_peer_data(phone_pub, phone_kp.nonce)
        assert pod_sm.state == PairingState.PEER_DATA_SET
        provider = SessionSharedSecretProvider(session)

        result = provider(pod_pub, phone_pub)
        assert result is not None
        # Must equal what the phone itself would compute.
        assert result == phone_kp.compute_shared_secret(pod_pub)
        # And what the pod's own state machine derives internally.
        _ = pod_sm.derive_keys_and_compute_confirmation()
        assert result == pod_sm._shared_secret  # type: ignore[attr-defined]

    def test_stale_pod_pub_is_rejected(self) -> None:
        session, pod_sm, phone_kp, _pod_pub, phone_pub = self._prepared_session()
        pod_sm.set_peer_data(phone_pub, phone_kp.nonce)
        provider = SessionSharedSecretProvider(session)
        # Caller passes a pod_pub that does not match the active session.
        wrong_pod_pub = bytes(b ^ 0xFF for b in _pod_pub)
        assert provider(wrong_pod_pub, phone_pub) is None

    def test_stale_controller_pub_is_rejected(self) -> None:
        session, pod_sm, phone_kp, pod_pub, phone_pub = self._prepared_session()
        pod_sm.set_peer_data(phone_pub, phone_kp.nonce)
        provider = SessionSharedSecretProvider(session)
        wrong_ctrl_pub = bytes(b ^ 0xFF for b in phone_pub)
        assert provider(pod_pub, wrong_ctrl_pub) is None

    def test_works_after_keys_derived(self) -> None:
        """KEYS_DERIVED is still a valid state (after pod computes its own conf)."""
        session, pod_sm, phone_kp, pod_pub, phone_pub = self._prepared_session()
        pod_sm.set_peer_data(phone_pub, phone_kp.nonce)
        _ = pod_sm.derive_keys_and_compute_confirmation()
        assert pod_sm.state == PairingState.KEYS_DERIVED
        provider = SessionSharedSecretProvider(session)
        result = provider(pod_pub, phone_pub)
        assert result is not None
        assert result == phone_kp.compute_shared_secret(pod_pub)

    def test_conf_verified_state_is_rejected(self) -> None:
        """After pairing finishes, we no longer hand out the secret."""
        session, pod_sm, phone_kp, pod_pub, phone_pub = self._prepared_session()
        pod_sm.set_peer_data(phone_pub, phone_kp.nonce)
        _ = pod_sm.derive_keys_and_compute_confirmation()

        # Simulate verifying the phone's conf with a value we compute here.
        phone_conf = compute_controller_confirmation(
            controller_id=_CONTROLLER_ID,
            firmware_id=_FIRMWARE_ID,
            controller_public_key=phone_pub,
            controller_nonce=phone_kp.nonce,
            pod_public_key=pod_pub,
            pod_nonce=pod_sm._key_pair.nonce,  # type: ignore[attr-defined]
            shared_secret=phone_kp.compute_shared_secret(pod_pub),
        )
        assert pod_sm.verify_peer_confirmation(phone_conf) is True
        assert pod_sm.state == PairingState.CONF_VERIFIED

        provider = SessionSharedSecretProvider(session)
        assert provider(pod_pub, phone_pub) is None


class TestLtkOverrideEndpoint:
    """
    ``POST /ltk_override`` stores a session-keyed LTK for the EAP-AKA
    barrier. The provider isn't touched by this route, so we use a
    trivial one that always returns None.
    """

    @staticmethod
    def _null_provider(pod_pub: bytes, ctrl_pub: bytes) -> Optional[bytes]:
        return None

    def setup_method(self) -> None:
        debug_ltk_store.clear()

    def teardown_method(self) -> None:
        debug_ltk_store.clear()

    def test_happy_path_stores_value(self, bridge_factory) -> None:
        bridge = bridge_factory(self._null_provider)
        session_id = b"\xaa" * 16
        ltk = b"\xbc" * 16
        status, body = _post(
            bridge,
            "/ltk_override",
            {"session_id": session_id.hex(), "ltk": ltk.hex()},
        )
        assert status == 200
        assert body == {"ok": True, "session_id": session_id.hex()}
        assert debug_ltk_store.wait_for_ltk(session_id, timeout=0.0) == ltk

    def test_missing_fields(self, bridge_factory) -> None:
        bridge = bridge_factory(self._null_provider)
        status, body = _post(bridge, "/ltk_override", {"session_id": "aa" * 16})
        assert status == 400
        assert "missing fields" in body.get("error", "")

    def test_bad_hex(self, bridge_factory) -> None:
        bridge = bridge_factory(self._null_provider)
        status, body = _post(
            bridge,
            "/ltk_override",
            {"session_id": "zz" * 16, "ltk": "bb" * 16},
        )
        assert status == 400
        assert "bad hex" in body.get("error", "")

    def test_wrong_length(self, bridge_factory) -> None:
        bridge = bridge_factory(self._null_provider)
        status, body = _post(
            bridge,
            "/ltk_override",
            {"session_id": "aa" * 15, "ltk": "bb" * 16},
        )
        assert status == 400
        assert "session_id" in body.get("error", "")

    def test_unknown_path_still_404(self, bridge_factory) -> None:
        bridge = bridge_factory(self._null_provider)
        status, _body = _post(bridge, "/nope", {})
        assert status == 404
