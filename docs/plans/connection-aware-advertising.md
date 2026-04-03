# Connection-Aware Advertising Plan

Status: Draft — 2026-04-03
Relates to: `emulator-protocol-remediation.md` Section 8

## Problem

The real Omnipod 5 pod stops advertising while a PDM holds an active BLE
connection.  It only resumes advertising after the connection is lost.  This is
documented in `esp32-ble-sniffer/README.md` and `omnipod-scanner/README.md`.

The emulator does the opposite: it calls `start_advertising(auto_restart=True)`
once on startup and never stops.  Advertising continues even while a client is
connected, which:

1. **Breaks fidelity** — any scanner will see the emulator during an active
   session, unlike a real pod.
2. **Allows multiple connections** — a second client could attempt to connect
   while a session is in progress (real pod prevents this).
3. **Wastes radio time** — minor for emulation, but conceptually wrong.

## Desired Behavior

| Event | Action |
|-------|--------|
| Startup (unpaired) | Advertise with unpaired UUID |
| Startup (has saved LTK) | Advertise with paired UUID |
| Client connects | **Stop advertising** |
| Client disconnects | **Resume advertising** (paired UUID if LTK exists) |
| Pairing completes (LTK saved) | Switch UUID for next advertising cycle |
| No reconnection within 60 s | Continue advertising (pod times out after ~60 s without connection and enters a fault state; emulator should **not** fault but should keep advertising for dev convenience) |

## Implementation

### 1. Stop advertising on connect (`server.py:_on_connection`)

```
Current:
    def _on_connection(self, connection: Connection) -> None:
        self._connection = connection
        logger.info(...)

Change:
    def _on_connection(self, connection: Connection) -> None:
        self._connection = connection
        logger.info(...)
        asyncio.get_event_loop().create_task(self._stop_advertising())
```

Add `_stop_advertising()`:

```python
async def _stop_advertising(self) -> None:
    """Stop BLE advertising (we have an active connection)."""
    assert self._device is not None
    await self._device.stop_advertising()
    logger.info("Advertising stopped (client connected)")
```

### 2. Resume advertising on disconnect (`server.py:_on_disconnection`)

```
Current:
    def _on_disconnection(self, reason: int) -> None:
        self._connection = None
        logger.info(...)

Change:
    def _on_disconnection(self, reason: int) -> None:
        self._connection = None
        logger.info(...)
        asyncio.get_event_loop().create_task(self._start_advertising())
```

`_start_advertising()` already picks the correct UUID based on
`self._paired_controller_id`, so no additional logic is needed here.

### 3. Remove `auto_restart=True` (`server.py:_start_advertising`)

```
Current:
    await self._device.start_advertising(
        auto_restart=True,
        ...
    )

Change:
    await self._device.start_advertising(
        auto_restart=False,
        ...
    )
```

With the explicit stop/start in the connection handlers, `auto_restart` is no
longer needed and would fight the new logic by restarting advertising
immediately after a connection is accepted.

### 4. Guard against double-start / double-stop

Add a boolean `_is_advertising` flag to track state and guard both
`_start_advertising()` and `_stop_advertising()`:

```python
def __init__(self, ...):
    ...
    self._is_advertising: bool = False

async def _start_advertising(self) -> None:
    if self._is_advertising:
        logger.debug("Already advertising, skipping")
        return
    ...  # existing advertising setup
    self._is_advertising = True

async def _stop_advertising(self) -> None:
    if not self._is_advertising:
        return
    await self._device.stop_advertising()
    self._is_advertising = False
    logger.info("Advertising stopped")
```

### 5. Restart advertising with new UUID after pairing

`set_paired_controller_id()` currently only stores the ID.  Add an immediate
advertising restart so the UUID switches without waiting for a disconnect cycle:

```python
def set_paired_controller_id(self, ctrl_id: bytes) -> None:
    self._paired_controller_id = ctrl_id
    logger.info("Paired controller ID set: %s", ctrl_id.hex())
    # If not currently connected, restart advertising with paired UUID.
    # If connected, the next _on_disconnection will pick it up.
    if self._connection is None:
        asyncio.get_event_loop().create_task(self._restart_advertising())

async def _restart_advertising(self) -> None:
    await self._stop_advertising()
    await self._start_advertising()
```

## Files Changed

| File | Changes |
|------|---------|
| `emulator/omnipod_emulator/ble/server.py` | All changes above (stop on connect, resume on disconnect, remove auto_restart, add guard flag, restart after pairing) |

No other files need modification.  The session layer (`session.py`) already
calls `set_paired_controller_id()` at the right time.

## Testing

1. **Unit test: advertising stops on connection**
   - Start server, verify `_is_advertising == True`.
   - Simulate `_on_connection()`, verify `_is_advertising == False`.

2. **Unit test: advertising resumes on disconnection**
   - From stopped state, simulate `_on_disconnection()`.
   - Verify `_is_advertising == True`.

3. **Unit test: paired UUID used after pairing**
   - Call `set_paired_controller_id(b'\x01\x02\x03\x04')`.
   - Verify next `_start_advertising()` uses the paired UUID.

4. **Integration test: scanner visibility**
   - Start emulator, confirm scanner sees it.
   - Connect a client, confirm scanner no longer sees it.
   - Disconnect client, confirm scanner sees it again with paired UUID.

## Relation to Remediation Plan Section 8

Section 8 of `emulator-protocol-remediation.md` covers the UUID switching part
(unpaired -> paired UUIDs after pairing).  This plan is complementary: it adds
the connection-aware stop/start behavior that Section 8 does not address.  Both
can be implemented together since they touch the same methods.
