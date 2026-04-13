"""
BLE GATT server for the Omnipod 5 pod emulator.

Uses the ``bumble`` library to create a virtual BLE peripheral that
advertises the Omnipod 5 service and exposes three characteristics:

    - **CMD** (1a7e2441): Write + Indicate — phone writes commands,
      pod sends transport protocol messages (handshake, completion).
    - **TpClassic** (1a7e2442): Write + Indicate — phone writes
      application data, pod sends responses via indication.
    - **TpFast** (1a7e2443): Write + Notify — optional high-throughput
      path (same role as TpClassic).

The transport protocol layer (``ble/transport.py``) sits between this
server and the application protocol session.

Reference: docs/protocol/01-ble-transport.md
"""

from __future__ import annotations

import asyncio
import logging
from collections.abc import Callable
from typing import Any

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
    MTU_SIZE,
    POD_ID,
    POD_SERIAL,
    SERVICE_UUID,
    TP_CLASSIC_CHARACTERISTIC_UUID,
    TP_FAST_CHARACTERISTIC_UUID,
    paired_scan_uuids,
)
from omnipod_emulator.ble.transport import BleTransportProtocol

logger = logging.getLogger(__name__)

# Type alias kept for backward compatibility (TCP server path).
CommandCallback = Callable[[bytes], bytes | None]


class OmnipodBleServer:
    """
    BLE GATT server emulating an Omnipod 5 pod.

    Args:
        transport_name:  Bumble transport specification string.
        on_app_message:  Application protocol callback
                         (``ProtocolSession.on_message``).
    """

    def __init__(
        self,
        transport_name: str,
        on_app_message: CommandCallback,
        on_twi_commands: Callable[[list[tuple[str, bytes]]], bytes | None] | None = None,
        on_twi_session_message: Callable[[bytes], bytes | None] | None = None,
        *,
        force_legacy_advertising: bool = True,
        replay_real_pod_adv: bool = False,
        use_public_address: bool = False,
        ble_address: str | None = None,
        unpaired_uuid_index: int = 0,
    ) -> None:
        self._transport_name = transport_name
        self._force_legacy_advertising = force_legacy_advertising
        self._replay_real_pod_adv = replay_real_pod_adv
        self._use_public_address = use_public_address
        self._ble_address = ble_address
        self._unpaired_uuid_index = unpaired_uuid_index
        self._device: Device | None = None
        self._connection: Connection | None = None

        # GATT characteristics (populated by _register_gatt_service)
        self._cmd_characteristic: Characteristic | None = None
        self._tp_classic_characteristic: Characteristic | None = None
        self._tp_fast_characteristic: Characteristic | None = None

        self._paired_pod_id: bytes | None = None
        self._is_advertising: bool = False

        # Transport protocol layer
        self._transport = BleTransportProtocol(
            on_app_message=on_app_message,
            on_twi_commands=on_twi_commands,
            on_twi_session_message=on_twi_session_message,
        )

        logger.info(
            "BLE server created: transport=%s", self._transport_name
        )

    async def start(self) -> None:
        """
        Initialize the BLE stack, register the GATT service, and begin
        advertising.  Runs until cancelled.
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

            # Power on
            await self._device.power_on()
            logger.info("BLE device powered on: %s", device_name)

            # Start advertising
            await self._start_advertising()

            # Run forever (until cancelled)
            logger.info("BLE server running, waiting for connections...")
            await asyncio.Future()

    # ------------------------------------------------------------------
    # GATT service setup
    # ------------------------------------------------------------------

    def _register_gatt_service(self) -> None:
        """Register the Omnipod 5 GATT service and characteristics."""
        assert self._device is not None

        # CMD — phone writes commands, pod sends transport indications.
        # Properties: WRITE | WRITE_WITHOUT_RESPONSE | INDICATE
        # Bumble auto-adds a CCCD when INDICATE is set.
        cmd_value = CharacteristicValue(
            read=self._on_cmd_read,
            write=self._on_cmd_write,
        )
        self._cmd_characteristic = Characteristic(
            uuid=BumbleUUID(CMD_CHARACTERISTIC_UUID),
            properties=(
                Characteristic.Properties.WRITE
                | Characteristic.Properties.WRITE_WITHOUT_RESPONSE
                | Characteristic.Properties.INDICATE
            ),
            permissions=(
                Characteristic.Permissions.READABLE
                | Characteristic.Permissions.WRITEABLE
            ),
            value=cmd_value,
        )

        # TpClassic — phone writes app data, pod responds via indication.
        # Properties: WRITE_WITHOUT_RESPONSE | INDICATE
        tp_classic_value = CharacteristicValue(
            read=self._on_tp_classic_read,
            write=self._on_tp_classic_write,
        )
        self._tp_classic_characteristic = Characteristic(
            uuid=BumbleUUID(TP_CLASSIC_CHARACTERISTIC_UUID),
            properties=(
                Characteristic.Properties.WRITE_WITHOUT_RESPONSE
                | Characteristic.Properties.INDICATE
            ),
            permissions=(
                Characteristic.Permissions.READABLE
                | Characteristic.Permissions.WRITEABLE
            ),
            value=tp_classic_value,
        )

        # TpFast — optional high-throughput path.
        # Properties: WRITE_WITHOUT_RESPONSE | NOTIFY
        tp_fast_value = CharacteristicValue(
            read=self._on_tp_fast_read,
            write=self._on_tp_fast_write,
        )
        self._tp_fast_characteristic = Characteristic(
            uuid=BumbleUUID(TP_FAST_CHARACTERISTIC_UUID),
            properties=(
                Characteristic.Properties.WRITE_WITHOUT_RESPONSE
                | Characteristic.Properties.NOTIFY
            ),
            permissions=(
                Characteristic.Permissions.READABLE
                | Characteristic.Permissions.WRITEABLE
            ),
            value=tp_fast_value,
        )

        service = Service(
            uuid=BumbleUUID(SERVICE_UUID),
            characteristics=[
                self._cmd_characteristic,
                self._tp_classic_characteristic,
                self._tp_fast_characteristic,
            ],
        )

        self._device.add_service(service)

        # Listen for CCCD subscription events (fired when PDM writes to
        # the auto-generated CCCD descriptors).
        self._cmd_characteristic.on(
            Characteristic.EVENT_SUBSCRIPTION,
            lambda bearer, notify, indicate: (
                self._transport.on_cccd_subscribed("cmd")
            ),
        )
        self._tp_classic_characteristic.on(
            Characteristic.EVENT_SUBSCRIPTION,
            lambda bearer, notify, indicate: (
                self._transport.on_cccd_subscribed("tp_classic")
            ),
        )
        self._tp_fast_characteristic.on(
            Characteristic.EVENT_SUBSCRIPTION,
            lambda bearer, notify, indicate: (
                self._transport.on_cccd_subscribed("tp_fast")
            ),
        )

        logger.info(
            "GATT service registered: service=%s, "
            "cmd=%s (W|WNR|IND), tp_classic=%s (WNR|IND), "
            "tp_fast=%s (WNR|NOT)",
            SERVICE_UUID,
            CMD_CHARACTERISTIC_UUID,
            TP_CLASSIC_CHARACTERISTIC_UUID,
            TP_FAST_CHARACTERISTIC_UUID,
        )

    # ------------------------------------------------------------------
    # Paired advertising
    # ------------------------------------------------------------------

    def set_paired(self) -> None:
        """Switch advertising from unpaired to paired UUIDs."""
        masked_pod_id = (POD_ID & ~3).to_bytes(4, "big")
        self._paired_pod_id = masked_pod_id
        logger.info(
            "Paired mode: pod_id=0x%08X, masked=0x%s — "
            "will use paired UUIDs on next advertising restart",
            POD_ID, masked_pod_id.hex(),
        )
        if self._connection is None:
            asyncio.get_event_loop().create_task(self._restart_advertising())

    # ------------------------------------------------------------------
    # Advertising
    # ------------------------------------------------------------------

    async def _stop_advertising(self) -> None:
        if not self._is_advertising:
            return
        assert self._device is not None
        await self._device.stop_advertising()
        self._is_advertising = False
        logger.info("Advertising stopped")

    async def _restart_advertising(self) -> None:
        await self._stop_advertising()
        await self._start_advertising()

    async def _start_advertising(self) -> None:
        """Start BLE advertising with the appropriate scan UUID."""
        if self._is_advertising:
            logger.debug("Already advertising, skipping")
            return
        assert self._device is not None

        if self._replay_real_pod_adv:
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

            from bumble.core import AdvertisingData
            from .constants import INSULET_COMPANY_ID

            company_id = INSULET_COMPANY_ID.to_bytes(2, "little")
            if self._paired_pod_id is not None:
                pod_id_adj = (POD_ID & 0x03) << 4
                mfr_data = company_id + bytes(
                    [0x00, 0x02, pod_id_adj, 0x00, 0x00]
                )
            else:
                mfr_data = company_id + bytes(5)

            device_name = f"AP {POD_ID:08X} {POD_SERIAL}"

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

    # ------------------------------------------------------------------
    # Connection / disconnection
    # ------------------------------------------------------------------

    def _on_connection(self, connection: Connection) -> None:
        """Handle a new BLE connection from the phone."""
        self._connection = connection

        # Do NOT force ATT MTU here.  With 128-bit UUIDs, bumble would
        # pack oversized GATT discovery responses that the PDM rejects.
        # MTU is upgraded by the transport layer after CCCD setup.

        connection.on("disconnection", self._on_disconnection)

        # Wire transport layer send callbacks for this connection.
        self._transport.reset()
        self._transport.set_send_callbacks(
            send_cmd=self._send_cmd_indication,
            send_tp_classic=self._send_tp_classic_indication,
            send_tp_fast=self._send_tp_fast_notification,
            upgrade_mtu=lambda: setattr(connection, 'att_mtu', MTU_SIZE),
        )

        logger.info(
            "BLE connection established: peer=%s, att_mtu=%d (default)",
            connection.peer_address,
            connection.att_mtu,
        )
        asyncio.get_event_loop().create_task(self._stop_advertising())

    def _on_disconnection(self, reason: int) -> None:
        """Handle BLE disconnection."""
        self._connection = None
        self._transport.reset()
        logger.info("BLE disconnection: reason=%d", reason)
        asyncio.get_event_loop().create_task(self._start_advertising())

    # ------------------------------------------------------------------
    # GATT characteristic handlers
    # ------------------------------------------------------------------

    def _on_cmd_read(self, connection: Connection | None) -> bytes:
        logger.debug("CMD characteristic read (unexpected)")
        return b""

    def _on_cmd_write(
        self, connection: Connection | None, value: bytes
    ) -> None:
        """Route CMD writes to the transport protocol layer."""
        logger.info(
            "[BLE] CMD write: %d bytes from %s",
            len(value),
            connection.peer_address if connection else "unknown",
        )
        logger.debug("[BLE] CMD RX: %s", value.hex())
        self._transport.on_cmd_write(value)

    def _on_tp_classic_read(self, connection: Connection | None) -> bytes:
        return b""

    def _on_tp_classic_write(
        self, connection: Connection | None, value: bytes
    ) -> None:
        """Route TpClassic writes to the transport protocol layer."""
        logger.info(
            "[BLE] TpClassic write: %d bytes from %s",
            len(value),
            connection.peer_address if connection else "unknown",
        )
        logger.debug("[BLE] TpClassic RX: %s", value.hex())
        self._transport.on_data_write(value, source="tp_classic")

    def _on_tp_fast_read(self, connection: Connection | None) -> bytes:
        return b""

    def _on_tp_fast_write(
        self, connection: Connection | None, value: bytes
    ) -> None:
        """Route TpFast writes to the transport protocol layer."""
        logger.info(
            "[BLE] TpFast write: %d bytes from %s",
            len(value),
            connection.peer_address if connection else "unknown",
        )
        logger.debug("[BLE] TpFast RX: %s", value.hex())
        self._transport.on_data_write(value, source="tp_fast")

    # ------------------------------------------------------------------
    # Indication / notification send helpers
    # ------------------------------------------------------------------

    async def _send_cmd_indication(self, data: bytes) -> None:
        """Send an indication on the CMD characteristic."""
        if self._connection is None or self._cmd_characteristic is None:
            logger.warning("Cannot send CMD indication: no connection")
            return
        logger.debug("[BLE] CMD TX (indicate): %s", data.hex())
        await self._device.indicate_subscriber(
            self._connection,
            self._cmd_characteristic,
            data,
            force=True,
        )

    async def _send_tp_classic_indication(self, data: bytes) -> None:
        """Send an indication on the TpClassic characteristic."""
        if self._connection is None or self._tp_classic_characteristic is None:
            logger.warning("Cannot send TpClassic indication: no connection")
            return
        logger.debug("[BLE] TpClassic TX (indicate): %s", data.hex())
        await self._device.indicate_subscriber(
            self._connection,
            self._tp_classic_characteristic,
            data,
            force=True,
        )

    async def _send_tp_fast_notification(self, data: bytes) -> None:
        """Send a notification on the TpFast characteristic."""
        if self._connection is None or self._tp_fast_characteristic is None:
            logger.warning("Cannot send TpFast notification: no connection")
            return
        logger.debug("[BLE] TpFast TX (notify): %s", data.hex())
        await self._device.notify_subscriber(
            self._connection,
            self._tp_fast_characteristic,
            data,
            force=True,
        )
