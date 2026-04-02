# Running the Emulator on Raspberry Pi 4 B

The Pi runs the emulator as a real BLE peripheral using its onboard Bluetooth radio. Any BLE central (Android phone, iOS device) will see it as an Omnipod 5 pod during scanning.

## SD Card Setup

- **User:** `openpod`
- **Password:** `openpod`
- **Hostname:** `raspberrypi` (default)

Configure your WiFi network using `raspi-config` or by editing `/etc/wpa_supplicant/wpa_supplicant.conf` after first boot.

## Connectivity

The Pi is configured for two access methods:

### 1. USB Gadget Ethernet (USB-C cable to Mac)

The Pi appears as a USB Ethernet adapter when connected via its USB-C power port.

On your Mac, go to **System Settings > Network** and look for a new "RNDIS/Ethernet Gadget" interface. Enable Internet Sharing if you want the Pi to reach the internet through your Mac.

```bash
ssh openpod@raspberrypi.local
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
ping raspberrypi.local
```

## First-Time Setup

After booting and SSH'ing in:

```bash
# 1. Run the setup script (installs Python, Bumble, disables BlueZ)
sudo bash /boot/firmware/openpod-setup.sh

# 2. Copy the emulator code from your Mac
#    (run this on your Mac, not the Pi)
scp -r ~/Projects/personal/openpod-5/emulator/* openpod@raspberrypi.local:/home/openpod/emulator/

# 3. Install the emulator package
ssh openpod@raspberrypi.local
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

```bash
sudo systemctl start openpod-emulator
sudo systemctl status openpod-emulator
sudo journalctl -u openpod-emulator -f   # follow logs
```

To start automatically on boot:
```bash
sudo systemctl enable openpod-emulator
```

## Verifying BLE Advertising

From another Linux machine or the Pi itself (if BlueZ isn't masked):

```bash
sudo hcitool lescan
# Should show: XX:XX:XX:XX:XX:XX TWI_Pod
```

From your Android phone: any BLE scanner app should discover "TWI_Pod" with the Omnipod 5 service UUID `1a7e4024-e3ed-4464-8b7e-751e03d0dc5f`.

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
- Try `ssh openpod@raspberrypi.local` (mDNS)
- Try `arp -a | grep raspberry` to find the IP

**BLE not advertising:**
- Check BlueZ is stopped: `sudo systemctl status bluetooth` should show inactive
- Check hci0 exists: `hciconfig` (may need BlueZ tools installed)
- Check emulator logs: `journalctl -u openpod-emulator`

**"Permission denied" on HCI socket:**
- Run with `sudo`, or set capabilities on the Python binary
