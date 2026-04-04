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
from bumble.hci import Address as BumbleAddress, OwnAddressType
from bumble.gatt import (
    Characteristic,
    CharacteristicValue,
    Service,
)
from bumble.host import Host
from bumble.transport import open_transport

from omnipod_emulator.ble.constants import (
    ADVERTISING_INTERVAL_MS,
    CMD_CHARACTERISTIC_UUID,
    UNPAIRED_SCAN_UUIDS,
    MAX_CHUNK_SIZE,
    POD_ID,
    POD_SERIAL,
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
        *,
        force_legacy_advertising: bool = True,
        replay_real_pod_adv: bool = False,
        use_public_address: bool = False,
        ble_address: str | None = None,
        unpaired_uuid_index: int = 0,
    ) -> None:
        self._transport_name = transport_name
        self._on_command = on_command
        self._force_legacy_advertising = force_legacy_advertising
        self._replay_real_pod_adv = replay_real_pod_adv
        self._use_public_address = use_public_address
        self._ble_address = ble_address
        self._unpaired_uuid_index = unpaired_uuid_index
        self._device: Device | None = None
        self._connection: Connection | None = None
        self._tp_classic_characteristic: Characteristic | None = None
        self._tp_fast_characteristic: Characteristic | None = None
        self._paired_pod_id: bytes | None = None
        self._is_advertising: bool = False

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
            device_name = f"AP {POD_ID:08X} {POD_SERIAL}"
            device_kwargs: dict = dict(name=device_name, host=host)
            if self._ble_address:
                device_kwargs["address"] = BumbleAddress(
                    self._ble_address,
                    address_type=BumbleAddress.PUBLIC_DEVICE_ADDRESS,
                )
                logger.info("Using explicit BLE address: %s", self._ble_address)
            self._device = Device(**device_kwargs)

            # Force legacy advertising (BLE 4.x ADV_IND) if requested.
            # Without this, bumble auto-upgrades to extended advertising
            # HCI commands when the adapter supports BLE 5.0, which some
            # Android stacks / chipsets silently ignore.
            if self._force_legacy_advertising:
                type(self._device).supports_le_extended_advertising = (
                    property(lambda self: False)
                )
                logger.info(
                    "Forced legacy advertising (BLE 4.x ADV_IND)"
                )

            # Register GATT service
            self._register_gatt_service()

            # Listen for connections
            self._device.on("connection", self._on_connection)
            self._device.on("disconnection", self._on_disconnection)

            # Power on
            await self._device.power_on()
            logger.info("BLE device powered on: %s", device_name)

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

    def set_paired(self) -> None:
        """
        Switch advertising from unpaired to paired UUIDs.

        The paired UUID embeds ``POD_ID & ~3`` (upper 30 bits of the pod ID).
        The lower 2 bits are already carried in manufacturer data byte[4]
        bits 4-5, so the app reconstructs the full pod ID from both sources.

        Call this after pairing completes.  The next advertising restart will
        use the paired UUID.
        """
        masked_pod_id = (POD_ID & ~3).to_bytes(4, "big")
        self._paired_pod_id = masked_pod_id
        logger.info(
            "Paired mode: pod_id=0x%08X, masked=0x%s — "
            "will use paired UUIDs on next advertising restart",
            POD_ID, masked_pod_id.hex(),
        )
        # If not currently connected, restart advertising with paired UUID.
        # If connected, the next _on_disconnection will pick it up.
        if self._connection is None:
            asyncio.get_event_loop().create_task(self._restart_advertising())

    async def _stop_advertising(self) -> None:
        """Stop BLE advertising (we have an active connection)."""
        if not self._is_advertising:
            return
        assert self._device is not None
        await self._device.stop_advertising()
        self._is_advertising = False
        logger.info("Advertising stopped")

    async def _restart_advertising(self) -> None:
        """Stop then start advertising (e.g. after UUID change)."""
        await self._stop_advertising()
        await self._start_advertising()

    async def _start_advertising(self) -> None:
        """Start BLE advertising with the appropriate scan UUID."""
        if self._is_advertising:
            logger.debug("Already advertising, skipping")
            return
        assert self._device is not None

        if self._replay_real_pod_adv:
            # Replay a real pod's exact captured advertisement bytes.
            # Real pod: name "AP 0000429F ...", pod_id=0x429F,
            # UUID base=0x0000429C.
            # ADV_IND payload (30 bytes) and SCAN_RSP payload (30 bytes)
            # captured from a dissected real pod advertisement.
            advertising_data = bytes.fromhex(
                "0201061106009C4200000A0073EA4839"
                "C53D921FCE08FF60030000000000"
            )
            scan_response_data = bytes.fromhex(
                "1D09415020303030303432394620304539"
                "39383442313030303839313844"
            )
            logger.info(
                "REPLAY MODE: using real pod's exact adv bytes "
                "(pod_id=0x429F)"
            )
        else:
            if self._paired_pod_id is not None:
                scan_uuid = paired_scan_uuids(self._paired_pod_id)[0]
            else:
                scan_uuid = UNPAIRED_SCAN_UUIDS[self._unpaired_uuid_index]

            logger.info("Starting advertising with UUID: %s", scan_uuid)

            from bumble.core import AdvertisingData  # noqa: E402

            # Build manufacturer-specific data (AD Type 0xFF):
            #   company_id (2 bytes LE) + 5 payload bytes.
            # Unpaired real pod: 60 03 | 00 00 00 00 00  (all zeros)
            # Paired real pod:   60 03 | 00 02 {adj} 00 00
            #   where adj = (pod_id & 0x03) << 4
            from .constants import INSULET_COMPANY_ID

            company_id = INSULET_COMPANY_ID.to_bytes(2, "little")
            if self._paired_pod_id is not None:
                pod_id_adj = (POD_ID & 0x03) << 4
                mfr_data = company_id + bytes(
                    [0x00, 0x02, pod_id_adj, 0x00, 0x00]
                )
            else:
                mfr_data = company_id + bytes(5)

            # Device name matches real pod format
            device_name = f"AP {POD_ID:08X} {POD_SERIAL}"

            # Advertising data: Flags + 128-bit UUID + manufacturer data
            # Scan response: device name
            advertising_data = bytes(
                AdvertisingData([
                    (AdvertisingData.FLAGS, bytes([0x06])),
                    (
                        AdvertisingData.INCOMPLETE_LIST_OF_128_BIT_SERVICE_CLASS_UUIDS,
                        bytes(BumbleUUID(scan_uuid)),
                    ),
                    (AdvertisingData.MANUFACTURER_SPECIFIC_DATA, mfr_data),
                ])
            )

            scan_response_data = bytes(
                AdvertisingData([
                    (AdvertisingData.COMPLETE_LOCAL_NAME,
                     bytes(device_name, 'utf-8')),
                ])
            )

            # Real pods send exactly 30-byte ADV_IND; enforce the same.
            assert len(advertising_data) == 30, (
                f"ADV_IND must be 30 bytes, got {len(advertising_data)}"
            )

        own_addr_type = (
            OwnAddressType.PUBLIC
            if self._use_public_address
            else OwnAddressType.RANDOM
        )
        logger.info(
            "Advertising own_address_type=%s (public_addr=%s, random_addr=%s)",
            own_addr_type.name,
            self._device.public_address,
            self._device.random_address,
        )

        # Bumble expects interval in 0.625ms slots
        adv_interval = int(ADVERTISING_INTERVAL_MS / 0.625)

        await self._device.start_advertising(
            auto_restart=False,
            own_address_type=own_addr_type,
            advertising_data=advertising_data,
            scan_response_data=scan_response_data,
            advertising_interval_min=adv_interval,
            advertising_interval_max=adv_interval,
        )
        self._is_advertising = True
        logger.info("Advertising started")

    def _on_connection(self, connection: Connection) -> None:
        """Handle a new BLE connection from the phone."""
        self._connection = connection
        logger.info(
            "BLE connection established: peer=%s",
            connection.peer_address,
        )
        asyncio.get_event_loop().create_task(self._stop_advertising())

    def _on_disconnection(self, reason: int) -> None:
        """Handle BLE disconnection."""
        self._connection = None
        logger.info("BLE disconnection: reason=%d", reason)
        asyncio.get_event_loop().create_task(self._start_advertising())

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
