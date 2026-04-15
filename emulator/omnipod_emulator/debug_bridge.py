"""
Debug HTTP bridge for the Elmo FGH-bypass pipeline.

Exposes a tiny, narrowly-bound HTTP endpoint that the Elmo host process
can call during live pairing to obtain the controller-side AES-CCM
confirmation value that the phone's native crypto would have produced.
The v6.9.8 app build used in live testing has no working no-cert
confirmation path, so the hook intercepts the confirmation call at the
FGH boundary, POSTs the session material here, and writes the response
into the app's output byte array. See ``ANALYSIS.md`` Section 6.4 for
the full story.

Interface
---------

``POST /app_conf``

Request body (application/json)::

    {
      "controller_id":        "aabbccdd",            // 4 bytes hex
      "firmware_id":          "010203040506",        // 6 bytes hex
      "controller_public_key":"<64 or 128 hex chars>",
      "controller_nonce":     "<32 hex chars>",
      "pod_public_key":       "<64 or 128 hex chars>",
      "pod_nonce":            "<32 hex chars>"
    }

Response (200)::

    {"confirmation": "<hex>"}

The six-input contract matches the plan in ANALYSIS.md Section 6.4.  The
ECDH shared secret is resolved server-side via an injected
``shared_secret_provider`` callback so the HTTP layer itself stays
stateless and unit-testable.

Security
--------

Bind narrowly.  The default host is ``127.0.0.1``; pass a different
interface explicitly if tunnelling is needed.  No auth, no TLS — this is
a dev-only debug sidecar for the reverse-engineering harness.
"""

from __future__ import annotations

import json
import logging
import threading
from collections.abc import Callable
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from typing import Optional

from omnipod_emulator import debug_ltk_store
from omnipod_emulator.protocol.pairing import (
    PairingState,
    compute_controller_confirmation,
)

logger = logging.getLogger(__name__)


# A shared-secret provider is called with (pod_public_key, controller_public_key)
# and must return the 32-byte ECDH shared secret, or None if it cannot
# resolve the key pair (e.g. no active session holds the matching pod_priv).
SharedSecretProvider = Callable[[bytes, bytes], Optional[bytes]]


class SessionSharedSecretProvider:
    """
    Session-scoped ``SharedSecretProvider`` backed by a live
    :class:`ProtocolSession`.

    This is the production provider used by ``run.py`` when the debug
    bridge is enabled.  Each call is strictly session-scoped: if the
    requested ``pod_public_key`` or ``controller_public_key`` does not
    match the active pairing attempt's keys byte-for-byte, the call
    returns ``None`` and the bridge replies 404.  This rejects stale
    cached material left over from a previous pairing round.

    The provider refuses to return a secret until the session has
    advanced to at least ``PEER_DATA_SET`` — i.e. the emulator has
    received the phone's SPS1 and stored the peer public key.  Before
    that point, no valid shared secret exists.
    """

    def __init__(self, session: object) -> None:
        # ``session`` is typed as ``object`` to avoid a circular import
        # with ``protocol.session``.  The provider only touches the
        # private ``_pairing`` attribute and its sub-fields.
        self._session = session

    def __call__(
        self, pod_pub: bytes, controller_pub: bytes,
    ) -> Optional[bytes]:
        pairing = getattr(self._session, "_pairing", None)
        if pairing is None:
            logger.info(
                "[bridge] provider: no active pairing state — reject",
            )
            return None

        state = getattr(pairing, "state", None)
        if state != PairingState.PEER_DATA_SET and state != PairingState.KEYS_DERIVED:
            logger.info(
                "[bridge] provider: pairing state %s is not "
                "PEER_DATA_SET/KEYS_DERIVED — reject",
                state,
            )
            return None

        key_pair = getattr(pairing, "_key_pair", None)
        if key_pair is None:
            logger.info("[bridge] provider: no key_pair on pairing — reject")
            return None

        active_pod_pub = getattr(key_pair, "public_key_bytes", None)
        if active_pod_pub != pod_pub:
            logger.info(
                "[bridge] provider: pod_public_key mismatch (stale?) — reject",
            )
            return None

        active_peer_pub = getattr(pairing, "_peer_public_key", b"")
        if active_peer_pub != controller_pub:
            logger.info(
                "[bridge] provider: controller_public_key mismatch "
                "(stale?) — reject",
            )
            return None

        try:
            return key_pair.compute_shared_secret(controller_pub)
        except Exception:  # noqa: BLE001
            logger.exception(
                "[bridge] provider: compute_shared_secret raised — reject",
            )
            return None


class _BridgeHTTPServer(ThreadingHTTPServer):
    """HTTP server that carries the shared-secret provider for handlers."""

    def __init__(
        self,
        server_address: tuple[str, int],
        provider: SharedSecretProvider,
    ) -> None:
        super().__init__(server_address, _BridgeHandler)
        self.provider: SharedSecretProvider = provider


class _BridgeHandler(BaseHTTPRequestHandler):
    """Per-request handler.  Routes POST /app_conf and nothing else."""

    server: _BridgeHTTPServer  # type: ignore[assignment]

    def log_message(self, format: str, *args: object) -> None:
        logger.info("[bridge] " + (format % args))

    def _send_json(self, status: int, obj: dict) -> None:
        body = json.dumps(obj).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_POST(self) -> None:  # noqa: N802 — stdlib contract
        if self.path == "/app_conf":
            self._handle_app_conf()
            return
        if self.path == "/ltk_override":
            self._handle_ltk_override()
            return
        self._send_json(404, {"error": "not found"})

    def _read_json(self) -> dict | None:
        length = int(self.headers.get("Content-Length", "0") or "0")
        if length <= 0 or length > 32_768:
            self._send_json(400, {"error": "missing or oversized body"})
            return None
        try:
            raw = self.rfile.read(length)
            return json.loads(raw)
        except (ValueError, OSError) as exc:
            self._send_json(400, {"error": f"bad json: {exc}"})
            return None

    def _handle_ltk_override(self) -> None:
        req = self._read_json()
        if req is None:
            return
        if not isinstance(req, dict):
            self._send_json(400, {"error": "body must be a JSON object"})
            return
        missing = [k for k in ("session_id", "ltk") if k not in req]
        if missing:
            self._send_json(400, {"error": f"missing fields: {missing}"})
            return
        try:
            session_id = bytes.fromhex(req["session_id"])
            ltk = bytes.fromhex(req["ltk"])
        except (TypeError, ValueError) as exc:
            self._send_json(400, {"error": f"bad hex: {exc}"})
            return
        try:
            debug_ltk_store.set_ltk(session_id, ltk)
        except ValueError as exc:
            self._send_json(400, {"error": str(exc)})
            return
        self._send_json(
            200,
            {"ok": True, "session_id": session_id.hex()},
        )

    def _handle_app_conf(self) -> None:
        req = self._read_json()
        if req is None:
            return
        required = (
            "controller_id",
            "firmware_id",
            "controller_public_key",
            "controller_nonce",
            "pod_public_key",
            "pod_nonce",
        )
        missing = [k for k in required if k not in req]
        if missing:
            self._send_json(400, {"error": f"missing fields: {missing}"})
            return

        try:
            fields = {k: bytes.fromhex(req[k]) for k in required}
        except (TypeError, ValueError) as exc:
            self._send_json(400, {"error": f"bad hex: {exc}"})
            return

        # Resolve the shared secret via the injected provider.
        try:
            shared = self.server.provider(
                fields["pod_public_key"],
                fields["controller_public_key"],
            )
        except Exception as exc:  # noqa: BLE001 — surface provider errors
            logger.exception("[bridge] provider raised")
            self._send_json(500, {"error": f"provider error: {exc}"})
            return

        if shared is None:
            self._send_json(
                404,
                {"error": "no matching pod keypair for the requested pod_public_key"},
            )
            return

        try:
            conf = compute_controller_confirmation(
                controller_id=fields["controller_id"],
                firmware_id=fields["firmware_id"],
                controller_public_key=fields["controller_public_key"],
                controller_nonce=fields["controller_nonce"],
                pod_public_key=fields["pod_public_key"],
                pod_nonce=fields["pod_nonce"],
                shared_secret=shared,
            )
        except ValueError as exc:
            self._send_json(400, {"error": str(exc)})
            return

        self._send_json(200, {"confirmation": conf.hex()})


class DebugBridge:
    """
    Small lifecycle wrapper around the background HTTP server.

    Example::

        def provider(pod_pub, ctrl_pub):
            kp = session.current_pod_keypair()
            if kp is None or kp.public_key_bytes != pod_pub:
                return None
            return kp.compute_shared_secret(ctrl_pub)

        bridge = DebugBridge(provider)
        bridge.start(host="127.0.0.1", port=9997)
        ...
        bridge.stop()
    """

    def __init__(self, shared_secret_provider: SharedSecretProvider) -> None:
        self._provider = shared_secret_provider
        self._server: Optional[_BridgeHTTPServer] = None
        self._thread: Optional[threading.Thread] = None

    def start(self, host: str = "127.0.0.1", port: int = 9997) -> None:
        if self._server is not None:
            raise RuntimeError("bridge already started")
        self._server = _BridgeHTTPServer((host, port), self._provider)
        self._thread = threading.Thread(
            target=self._server.serve_forever,
            name="omnipod-debug-bridge",
            daemon=True,
        )
        self._thread.start()
        logger.info(
            "Debug bridge listening on http://%s:%d/app_conf", host, port,
        )

    def stop(self) -> None:
        if self._server is None:
            return
        self._server.shutdown()
        self._server.server_close()
        if self._thread is not None:
            self._thread.join(timeout=2.0)
        self._server = None
        self._thread = None
        logger.info("Debug bridge stopped")

    @property
    def address(self) -> Optional[tuple[str, int]]:
        """Return (host, port) once started, else None."""
        if self._server is None:
            return None
        return self._server.server_address  # type: ignore[return-value]
