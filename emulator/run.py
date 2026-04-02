#!/usr/bin/env python3
"""
Omnipod 5 Pod Emulator -- entry point.

Initializes the protocol session and starts either a BLE server (via bumble),
a raw TCP server, or both. The TCP server is the primary transport for
development testing with the OpenPod Android app.

Usage:
    python run.py                          # TCP mode (default)
    python run.py --mode ble               # BLE mode only
    python run.py --mode both              # Both TCP and BLE
    python run.py --tcp-port 9996          # Custom TCP port
    python run.py --seed 42               # Deterministic mode
    python run.py --log-level DEBUG
"""

from __future__ import annotations

import argparse
import asyncio
import logging
import random
import sys

from omnipod_emulator.pod.state import PodState
from omnipod_emulator.protocol.session import ProtocolSession
from omnipod_emulator.tcp.server import TcpProtocolServer
from omnipod_emulator.version import banner, version_string

logger = logging.getLogger("omnipod_emulator")

# Default bumble transport for BLE mode.
DEFAULT_BLE_TRANSPORT = "tcp-server:0.0.0.0:9995"

# Default TCP protocol server port.
DEFAULT_TCP_PORT = 9996


def parse_args() -> argparse.Namespace:
    """Parse command-line arguments."""
    parser = argparse.ArgumentParser(
        description="Omnipod 5 Pod Emulator",
        formatter_class=argparse.RawDescriptionHelpFormatter,
    )
    parser.add_argument(
        "--mode",
        default="tcp",
        choices=["tcp", "ble", "both"],
        help="Server mode: tcp (default), ble, or both.",
    )
    parser.add_argument(
        "--tcp-port",
        type=int,
        default=DEFAULT_TCP_PORT,
        help=f"TCP protocol server port. Default: {DEFAULT_TCP_PORT}",
    )
    parser.add_argument(
        "--transport",
        default=DEFAULT_BLE_TRANSPORT,
        help=(
            "Bumble BLE transport specification (for ble/both modes). "
            f"Default: {DEFAULT_BLE_TRANSPORT}"
        ),
    )
    parser.add_argument(
        "--seed",
        type=int,
        default=None,
        help="Random seed for deterministic/reproducible sessions.",
    )
    parser.add_argument(
        "--log-level",
        default="INFO",
        choices=["DEBUG", "INFO", "WARNING", "ERROR"],
        help="Logging level. Default: INFO",
    )
    parser.add_argument(
        "--firmware-id",
        default="aabbccddeeff",
        help="Pod firmware ID as hex string (6 bytes / 12 hex chars).",
    )
    parser.add_argument(
        "--log-file",
        default=None,
        help=(
            "Path to a detailed DEBUG log file. The file always logs at "
            "DEBUG level regardless of --log-level. Rotates at 50 MB, "
            "keeps 3 backups. Example: --log-file /tmp/openpod-emulator.log"
        ),
    )
    return parser.parse_args()


def setup_logging(level_name: str, log_file: str | None = None) -> None:
    """Configure structured logging.

    Console gets the requested level. The log file (if set) always gets
    DEBUG with full hex dumps so test sessions are fully reproducible.
    """
    fmt = "%(asctime)s.%(msecs)03d [%(levelname)-8s] %(name)s: %(message)s"
    datefmt = "%Y-%m-%d %H:%M:%S"

    level = getattr(logging, level_name.upper(), logging.INFO)
    root = logging.getLogger()
    root.setLevel(logging.DEBUG)  # allow everything; handlers filter

    # Console handler — respects --log-level
    console = logging.StreamHandler(sys.stderr)
    console.setLevel(level)
    console.setFormatter(logging.Formatter(fmt, datefmt=datefmt))
    root.addHandler(console)

    # File handler — always DEBUG for full traceability
    if log_file:
        from logging.handlers import RotatingFileHandler

        fh = RotatingFileHandler(
            log_file, maxBytes=50 * 1024 * 1024, backupCount=3  # 50 MB, keep 3
        )
        fh.setLevel(logging.DEBUG)
        fh.setFormatter(logging.Formatter(fmt, datefmt=datefmt))
        root.addHandler(fh)

    # Suppress noisy bumble internals unless debugging
    if level > logging.DEBUG:
        logging.getLogger("bumble").setLevel(logging.WARNING)


def main() -> None:
    """Main entry point."""
    args = parse_args()
    setup_logging(args.log_level, log_file=args.log_file)

    logger.info("=" * 60)
    for line in banner().splitlines():
        logger.info(line)
    logger.info("=" * 60)
    logger.info("Mode: %s", args.mode)
    logger.info("Version: %s", version_string())
    logger.info("Firmware ID: %s", args.firmware_id)

    # Deterministic mode
    ecdh_seed: bytes | None = None
    if args.seed is not None:
        random.seed(args.seed)
        ecdh_seed = args.seed.to_bytes(32, "big")
        logger.info("Deterministic mode: seed=%d", args.seed)

    # Parse firmware ID
    try:
        firmware_id = bytes.fromhex(args.firmware_id)
        if len(firmware_id) != 6:
            raise ValueError("Must be 6 bytes")
    except ValueError as exc:
        logger.error("Invalid firmware ID '%s': %s", args.firmware_id, exc)
        sys.exit(1)

    # Initialize pod state and protocol session
    pod_state = PodState()
    session = ProtocolSession(
        pod_state=pod_state,
        firmware_id=firmware_id,
        ecdh_seed=ecdh_seed,
    )

    logger.info(
        "Pod state initialized: reservoir=%.1fU, firmware=%s",
        pod_state.reservoir_units,
        pod_state.firmware_version,
    )

    # Start servers based on mode
    if args.mode == "tcp":
        _run_tcp(session, args.tcp_port)
    elif args.mode == "ble":
        _run_ble(session, args.transport)
    elif args.mode == "both":
        _run_both(session, args.tcp_port, args.transport)


def _run_tcp(session: ProtocolSession, port: int) -> None:
    """Run only the TCP protocol server."""
    tcp_server = TcpProtocolServer(session, port=port)
    logger.info("Starting TCP protocol server on port %d...", port)
    try:
        asyncio.run(tcp_server.start())
    except KeyboardInterrupt:
        logger.info("Shutting down (KeyboardInterrupt)")
    except Exception:
        logger.exception("Fatal error in TCP server")
        sys.exit(1)


def _run_ble(session: ProtocolSession, transport: str) -> None:
    """Run only the BLE server."""
    from omnipod_emulator.ble.server import OmnipodBleServer

    ble_server = OmnipodBleServer(
        transport_name=transport,
        on_command=session.on_message,
    )
    logger.info("Starting BLE server on transport %s...", transport)
    try:
        asyncio.run(ble_server.start())
    except KeyboardInterrupt:
        logger.info("Shutting down (KeyboardInterrupt)")
    except Exception:
        logger.exception("Fatal error in BLE server")
        sys.exit(1)


def _run_both(
    session: ProtocolSession, tcp_port: int, transport: str
) -> None:
    """Run both TCP and BLE servers concurrently."""
    from omnipod_emulator.ble.server import OmnipodBleServer

    tcp_server = TcpProtocolServer(session, port=tcp_port)
    ble_server = OmnipodBleServer(
        transport_name=transport,
        on_command=session.on_message,
    )

    async def run() -> None:
        await asyncio.gather(
            tcp_server.start(),
            ble_server.start(),
        )

    logger.info(
        "Starting both servers: TCP port %d, BLE transport %s...",
        tcp_port,
        transport,
    )
    try:
        asyncio.run(run())
    except KeyboardInterrupt:
        logger.info("Shutting down (KeyboardInterrupt)")
    except Exception:
        logger.exception("Fatal error")
        sys.exit(1)


if __name__ == "__main__":
    main()
