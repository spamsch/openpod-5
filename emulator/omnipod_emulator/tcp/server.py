"""
Raw TCP protocol server for the Omnipod 5 pod emulator.

Provides a direct TCP endpoint that the Android app connects to,
bypassing BLE entirely. Uses simple length-prefixed framing:

    [length (4 bytes, big-endian)] [payload (length bytes)]

Each received frame is passed to ``ProtocolSession.on_message()``.
If a response is returned, it is sent back with the same framing.

This is the primary transport for development and testing — the app's
``EmulatorPodManager`` connects here via ``java.net.Socket``.

Usage:
    server = TcpProtocolServer(session, host="0.0.0.0", port=9996)
    await server.start()
"""

from __future__ import annotations

import asyncio
import logging
import struct

from omnipod_emulator.protocol.session import ProtocolSession

logger = logging.getLogger(__name__)

# Frame header: 4-byte big-endian unsigned int.
_HEADER_FORMAT = ">I"
_HEADER_SIZE = 4


class TcpProtocolServer:
    """
    Asyncio TCP server wrapping a ``ProtocolSession``.

    Accepts one client connection at a time. When the client disconnects,
    the session is notified and the server waits for the next connection.

    Args:
        session: The protocol session that processes messages.
        host:    Bind address (default ``"0.0.0.0"``).
        port:    Bind port (default ``9996``).
    """

    def __init__(
        self,
        session: ProtocolSession,
        host: str = "0.0.0.0",
        port: int = 9996,
    ) -> None:
        self._session = session
        self._host = host
        self._port = port

    async def start(self) -> None:
        """Start the TCP server and listen for connections forever."""
        server = await asyncio.start_server(
            self._handle_client,
            self._host,
            self._port,
        )

        addr = server.sockets[0].getsockname() if server.sockets else (self._host, self._port)
        logger.info("TCP protocol server listening on %s:%d", addr[0], addr[1])

        async with server:
            await server.serve_forever()

    async def _handle_client(
        self,
        reader: asyncio.StreamReader,
        writer: asyncio.StreamWriter,
    ) -> None:
        """Handle a single client connection."""
        peer = writer.get_extra_info("peername")
        logger.info("TCP client connected: %s", peer)

        try:
            while True:
                # Read frame header (4 bytes)
                header = await reader.readexactly(_HEADER_SIZE)
                (length,) = struct.unpack(_HEADER_FORMAT, header)

                if length == 0:
                    logger.warning("Received zero-length frame, ignoring")
                    continue

                if length > 65536:
                    logger.error(
                        "Frame too large: %d bytes, closing connection",
                        length,
                    )
                    break

                # Read payload
                payload = await reader.readexactly(length)

                logger.info(
                    "TCP frame received: %d bytes from %s",
                    length,
                    peer,
                )

                # Process through the protocol session
                response = self._session.on_message(payload)

                # Send response if any
                if response is not None:
                    resp_header = struct.pack(_HEADER_FORMAT, len(response))
                    writer.write(resp_header + response)
                    await writer.drain()

                    logger.info(
                        "TCP frame sent: %d bytes to %s",
                        len(response),
                        peer,
                    )

        except asyncio.IncompleteReadError:
            logger.info("TCP client disconnected (incomplete read): %s", peer)
        except ConnectionResetError:
            logger.info("TCP client disconnected (connection reset): %s", peer)
        except Exception:
            logger.exception("Error handling TCP client %s", peer)
        finally:
            # Notify the session about disconnection
            self._session.on_disconnect()
            writer.close()
            try:
                await writer.wait_closed()
            except Exception:
                pass
            logger.info("TCP client session ended: %s", peer)
