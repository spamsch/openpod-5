# Running the Emulator on Raspberry Pi 4 B

The Pi runs the emulator as a real BLE peripheral using its onboard Bluetooth radio. Any BLE central (Android phone, iOS device) will see it as an Omnipod 5 pod during scanning.

## SD Card Setup

The SD card ships with Raspberry Pi OS Bookworm 64-bit (2025-05-13) and is pre-configured with:

- **User:** `openpod`
- **Password:** `openpod`
- **Hostname:** `openpod`
- **USB gadget ethernet:** enabled (`dwc2` + `g_ether` in `cmdline.txt`)
- **WiFi regulatory domain:** DE

WiFi is **not** pre-configured. After first boot, connect via USB gadget ethernet (see below) and configure WiFi with:

```bash
# Interactive — walks through SSID and password
sudo nmcli device wifi connect "<SSID>" password "<password>"

# Or use the TUI
sudo nmtui
```

## Connectivity

### 1. USB Gadget Ethernet (USB-C cable to Mac)

The Pi appears as a USB Ethernet adapter when connected via its USB-C power port. This is pre-configured on the SD card and works out of the box — no WiFi needed.

On your Mac, go to **System Settings > Network** and look for a new "RNDIS/Ethernet Gadget" interface. Enable Internet Sharing if you want the Pi to reach the internet through your Mac.

```bash
ssh openpod@openpod.local
```

If `.local` doesn't resolve, check the IP assigned to the USB interface:
```bash
# On Mac
ifconfig | grep -A5 "bridge\|en.*RNDIS"
# The Pi will be on the same /24 subnet, typically 169.254.x.x or 10.x.x.x
```

### 2. WiFi

Once WiFi is configured, find the Pi's IP via your router or:
```bash
ping openpod.local
```

## First-Time Setup

After booting and SSH'ing in:

```bash
# 1. Run the setup script (installs Python, Bumble, disables BlueZ)
sudo bash /boot/firmware/openpod-setup.sh
```

Then from your Mac, use the deploy script to copy the emulator code, install it, and start the service:

```bash
./emulator/deploy-to-pi.sh
```

The deploy script will:
1. Discover the Pi (mDNS, ARP, subnet scan)
2. Stamp the current git hash into the build
3. Rsync the emulator code to `/home/openpod/emulator/`
4. Install the package in the Pi's virtualenv
5. Copy the systemd service file and restart the emulator

You can override the Pi address: `PI_HOST=openpod@192.168.1.42 ./emulator/deploy-to-pi.sh`

### Manual setup (without deploy script)

```bash
# From your Mac — copy emulator code
scp -r ~/Projects/personal/openpod-5/emulator/* openpod@openpod.local:/home/openpod/emulator/

# On the Pi — install the package
ssh openpod@openpod.local
cd /home/openpod/emulator
.venv/bin/pip install -e .
```

## Running the Emulator

### Manual (for development)

```bash
# Ensure BlueZ isn't holding hci0
sudo hciconfig hci0 down

# Run with the Pi's built-in Bluetooth
cd /home/openpod/emulator
sudo .venv/bin/python run.py --mode ble --transport "hci-socket:0"
```

`sudo` is needed for raw HCI socket access. Alternatively, grant capabilities:
```bash
sudo setcap cap_net_admin,cap_net_raw+ep .venv/bin/python3
# Then run without sudo:
.venv/bin/python run.py --mode ble --transport "hci-socket:0"
```

### As a systemd service

The `deploy-to-pi.sh` script installs the service automatically. To manage it:

```bash
sudo systemctl start openpod-emulator
sudo systemctl status openpod-emulator
sudo journalctl -u openpod-emulator -f   # follow logs
```

Or use the log viewer shortcut from your Mac:
```bash
./emulator/pi-logs.sh        # last 50 lines + follow
./emulator/pi-logs.sh 200    # last 200 lines + follow
```

To start automatically on boot:
```bash
sudo systemctl enable openpod-emulator
```

## Verifying BLE Advertising

From another Linux machine or the Pi itself (if BlueZ isn't masked):

```bash
sudo hcitool lescan
# Should show: XX:XX:XX:XX:XX:XX Openpod_Emu
```

From your Android phone: any BLE scanner app should discover `Openpod_Emu` with the Omnipod 5 service UUID `1a7e4024-e3ed-4464-8b7e-751e03d0dc5f`.

## Architecture

```
┌─────────────────────────────────┐
│  Android Phone / OpenPod App    │
│  (BLE Central)                  │
└────────────┬────────────────────┘
             │ BLE over air
┌────────────▼────────────────────┐
│  Raspberry Pi 4 B               │
│  onboard Bluetooth (hci0)       │
│                                 │
│  omnipod_emulator               │
│  ├── ble/server.py   (Bumble)   │
│  ├── protocol/session.py        │
│  ├── protocol/twi_command.py    │
│  ├── protocol/rhp.py            │
│  ├── protocol/rhp_handlers.py   │
│  ├── crypto/                    │
│  └── pod/state.py               │
└─────────────────────────────────┘
```

## Troubleshooting

**Pi doesn't show up on USB:**
- Use the USB-C port (power port), not the USB-A ports
- Use a data-capable USB-C cable
- The Pi needs ~10 seconds to boot before the gadget appears

**Can't SSH:**
- Wait 30-60 seconds after power-on for first boot
- Try `ssh openpod@openpod.local` (mDNS)
- Try `arp -a | grep openpod` to find the IP
- Override: `PI_HOST=openpod@<ip> ./emulator/deploy-to-pi.sh`

**BLE not advertising:**
- Check BlueZ is stopped: `sudo systemctl status bluetooth` should show inactive/masked
- Check hci0 exists: `hciconfig` (may need BlueZ tools installed)
- Check emulator logs: `journalctl -u openpod-emulator` or `./emulator/pi-logs.sh`

**"Permission denied" on HCI socket:**
- Run with `sudo`, or set capabilities on the Python binary
- The systemd service uses `AmbientCapabilities=CAP_NET_ADMIN CAP_NET_RAW` to avoid needing root
