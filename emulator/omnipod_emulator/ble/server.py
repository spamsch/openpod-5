"""
BLE GATT server for the Omnipod 5 pod emulator.

Uses the ``bumble`` library to create a virtual BLE peripheral that
advertises the Omnipod 5 service and exposes the CMD (write) and
TpClassic (notify) characteristics.

The server:
    - Accepts writes on the CMD characteristic and forwards them to the
      protocol handler.
    - Sends responses (and unsolicited data) as notifications on the
      TpClassic characteristic.
    - Supports envelope-based chunking for messages larger than the MTU.

Reference: OMNIPOD5_CONNECTION_PROTOCOL.md, Sections 1-4
"""

from __future__ import annotations

import asyncio
import logging
from typing import Callable

from bumble.core import UUID as BumbleUUID
from bumble.device import Device, Connection
from bumble.gatt import (
    Characteristic,
    CharacteristicValue,
    Service,
)
from bumble.host import Host
from bumble.transport import open_transport

from omnipod_emulator.ble.constants import (
    CMD_CHARACTERISTIC_UUID,
    DEFAULT_UNPAIRED_SCAN_UUID,
    DEVICE_NAME,
    MAX_CHUNK_SIZE,
    SERVICE_UUID,
    TP_CLASSIC_CHARACTERISTIC_UUID,
    TP_FAST_CHARACTERISTIC_UUID,
    paired_scan_uuids,
)

logger = logging.getLogger(__name__)

# Type alias for the callback invoked when a command is received.
# Signature: (data: bytes) -> bytes | None
# Returns response bytes, or None if no response should be sent.
CommandCallback = Callable[[bytes], bytes | None]


class OmnipodBleServer:
    """
    BLE GATT server emulating an Omnipod 5 pod.

    Args:
        transport_name: Bumble transport specification string.
                        Examples: ``"link-relay:ws://localhost:10723/test"``
                        or ``"usb:0"`` for a real USB BLE adapter.
        on_command:     Callback invoked when the phone writes to the CMD
                       characteristic.  Receives raw bytes, must return
                       response bytes or None.
    """

    def __init__(
        self,
        transport_name: str,
        on_command: CommandCallback,
    ) -> None:
        self._transport_name = transport_name
        self._on_command = on_command
        self._device: Device | None = None
        self._connection: Connection | None = None
        self._tp_classic_characteristic: Characteristic | None = None
        self._tp_fast_characteristic: Characteristic | None = None
        self._paired_controller_id: bytes | None = None

        logger.info(
            "BLE server created: transport=%s", self._transport_name
        )

    async def start(self) -> None:
        """
        Initialize the BLE stack, register the GATT service, and begin
        advertising.

        This method runs until cancelled.
        """
        logger.info("Opening BLE transport: %s", self._transport_name)
        async with await open_transport(self._transport_name) as (
            hci_source,
            hci_sink,
        ):
            host = Host(
                controller_source=hci_source,
                controller_sink=hci_sink,
            )
            self._device = Device(
                name=DEVICE_NAME,
                host=host,
            )

            # Register GATT service
            self._register_gatt_service()

            # Listen for connections
            self._device.on("connection", self._on_connection)
            self._device.on("disconnection", self._on_disconnection)

            # Power on
            await self._device.power_on()
            logger.info("BLE device powered on: %s", DEVICE_NAME)

            # Start advertising
            await self._start_advertising()

            # Run forever (until cancelled)
            logger.info("BLE server running, waiting for connections...")
            await asyncio.Future()  # Block forever

    def _register_gatt_service(self) -> None:
        """Register the Omnipod 5 GATT service and characteristics."""
        assert self._device is not None

        # CMD characteristic -- phone writes commands here
        cmd_value = CharacteristicValue(
            read=self._on_cmd_read,
            write=self._on_cmd_write,
        )
        cmd_characteristic = Characteristic(
            uuid=BumbleUUID(CMD_CHARACTERISTIC_UUID),
            properties=(
                Characteristic.Properties.WRITE
                | Characteristic.Properties.WRITE_WITHOUT_RESPONSE
            ),
            permissions=Characteristic.Permissions.WRITEABLE,
            value=cmd_value,
        )

        # TpClassic characteristic -- pod sends notifications here
        self._tp_classic_characteristic = Characteristic(
            uuid=BumbleUUID(TP_CLASSIC_CHARACTERISTIC_UUID),
            properties=(
                Characteristic.Properties.READ
                | Characteristic.Properties.NOTIFY
            ),
            permissions=Characteristic.Permissions.READABLE,
            value=b"",
        )

        # TpFast characteristic -- optional high-throughput path
        self._tp_fast_characteristic = Characteristic(
            uuid=BumbleUUID(TP_FAST_CHARACTERISTIC_UUID),
            properties=(
                Characteristic.Properties.READ
                | Characteristic.Properties.NOTIFY
            ),
            permissions=Characteristic.Permissions.READABLE,
            value=b"",
        )

        service = Service(
            uuid=BumbleUUID(SERVICE_UUID),
            characteristics=[
                cmd_characteristic,
                self._tp_classic_characteristic,
                self._tp_fast_characteristic,
            ],
        )

        self._device.add_service(service)

        logger.info(
            "GATT service registered: service=%s, "
            "cmd=%s, tp_classic=%s, tp_fast=%s",
            SERVICE_UUID,
            CMD_CHARACTERISTIC_UUID,
            TP_CLASSIC_CHARACTERISTIC_UUID,
            TP_FAST_CHARACTERISTIC_UUID,
        )

    def set_paired_controller_id(self, ctrl_id: bytes) -> None:
        """
        Set the controller ID for paired advertising.

        After pairing completes, call this to switch from unpaired UUIDs
        (``...fffffffe0x``) to controller-specific UUIDs (``...{ctrl_id}0x``).
        The next advertising restart will use the paired UUIDs.

        Args:
            ctrl_id: 4-byte controller ID.
        """
        self._paired_controller_id = ctrl_id
        logger.info(
            "Paired controller ID set: %s — will use paired UUIDs on next advertising restart",
            ctrl_id.hex(),
        )

    async def _start_advertising(self) -> None:
        """Start BLE advertising with the appropriate scan UUID."""
        assert self._device is not None

        if self._paired_controller_id is not None:
            scan_uuid = paired_scan_uuids(self._paired_controller_id)[0]
        else:
            scan_uuid = DEFAULT_UNPAIRED_SCAN_UUID

        logger.info("Starting advertising with UUID: %s", scan_uuid)

        from bumble.core import AdvertisingData  # noqa: E402

        # Build manufacturer-specific data (AD Type 0xFF):
        #   company_id (2 bytes LE) + padding (4 bytes) +
        #   pod_id_adj_bits (1 byte, bits 4-5 = pod ID fragment) +
        #   alarm_code (1 byte) + alert_code (1 byte)
        company_id = (0x0000).to_bytes(2, "little")  # placeholder company ID
        padding = bytes(4)
        pod_id_adj = 0x00  # bits 4-5 = 0 (no adjustment)
        alarm_code = 0x00
        alert_code = 0x00
        # Profile ID = 10 in UUID data to pass scan callback validation
        mfr_data = company_id + padding + bytes([pod_id_adj, alarm_code, alert_code])

        advertising_data = bytes(
            AdvertisingData([
                (AdvertisingData.COMPLETE_LOCAL_NAME, bytes(DEVICE_NAME, 'utf-8')),
                (
                    AdvertisingData.INCOMPLETE_LIST_OF_128_BIT_SERVICE_CLASS_UUIDS,
                    bytes(BumbleUUID(scan_uuid)),
                ),
                (AdvertisingData.MANUFACTURER_SPECIFIC_DATA, mfr_data),
            ])
        )

        scan_response_data = bytes(
            AdvertisingData([
                (AdvertisingData.COMPLETE_LOCAL_NAME, bytes(DEVICE_NAME, 'utf-8')),
                (
                    AdvertisingData.COMPLETE_LIST_OF_128_BIT_SERVICE_CLASS_UUIDS,
                    bytes(BumbleUUID(SERVICE_UUID)),
                ),
            ])
        )

        await self._device.start_advertising(
            auto_restart=True,
            advertising_data=advertising_data,
            scan_response_data=scan_response_data,
        )
        logger.info("Advertising started")

    def _on_connection(self, connection: Connection) -> None:
        """Handle a new BLE connection from the phone."""
        self._connection = connection
        logger.info(
            "BLE connection established: peer=%s",
            connection.peer_address,
        )

    def _on_disconnection(self, reason: int) -> None:
        """Handle BLE disconnection."""
        self._connection = None
        logger.info("BLE disconnection: reason=%d", reason)

    def _on_cmd_read(self, connection: Connection | None) -> bytes:
        """Handle a read on the CMD characteristic (not normally used)."""
        logger.debug("CMD characteristic read (unexpected)")
        return b""

    def _on_cmd_write(
        self, connection: Connection | None, value: bytes
    ) -> None:
        """
        Handle a write to the CMD characteristic.

        Forwards the data to the protocol handler and sends any response
        as a notification on TpClassic.
        """
        logger.info(
            "[BLE] GATT write to CMD: %d bytes from %s",
            len(value),
            connection.peer_address if connection else "unknown",
        )
        logger.debug("[BLE] CMD RX: %s", value.hex())

        try:
            response = self._on_command(value)
        except Exception:
            logger.exception("Command handler raised an exception")
            response = None

        if response is not None:
            logger.debug("[BLE] CMD TX: %s", response.hex())
            asyncio.get_event_loop().create_task(
                self._send_response(response)
            )

    async def _send_response(self, data: bytes) -> None:
        """
        Send response data as notification(s) on TpClassic.

        If the data exceeds MAX_CHUNK_SIZE, it is split into chunks.
        """
        if self._connection is None:
            logger.warning("Cannot send response: no active connection")
            return

        if self._tp_classic_characteristic is None:
            logger.error("TpClassic characteristic not registered")
            return

        chunks = self._chunk_data(data)
        logger.info(
            "Sending response: %d bytes in %d chunk(s)",
            len(data),
            len(chunks),
        )

        for i, chunk in enumerate(chunks):
            try:
                await self._device.notify_subscribers(
                    self._tp_classic_characteristic, chunk
                )
                logger.debug(
                    "Chunk %d/%d sent: %d bytes", i + 1, len(chunks), len(chunk)
                )
            except Exception:
                logger.exception("Failed to send chunk %d/%d", i + 1, len(chunks))
                return

    @staticmethod
    def _chunk_data(data: bytes) -> list[bytes]:
        """
        Split *data* into chunks no larger than MAX_CHUNK_SIZE.

        Returns:
            A list of byte chunks.
        """
        if len(data) <= MAX_CHUNK_SIZE:
            return [data]

        chunks = []
        offset = 0
        while offset < len(data):
            end = min(offset + MAX_CHUNK_SIZE, len(data))
            chunks.append(data[offset:end])
            offset = end
        return chunks
