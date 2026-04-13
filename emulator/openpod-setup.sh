#!/bin/bash
# OpenPod Emulator - Raspberry Pi first-time setup script
#
# Run after first SSH login:
#   sudo bash /boot/firmware/openpod-setup.sh
#
# Or from the repo copy:
#   sudo bash openpod-setup.sh
#
# This installs system dependencies and prepares the Python environment.
# The emulator code and systemd service are deployed separately via
# deploy-to-pi.sh from your development machine.

set -euo pipefail

echo "=== OpenPod Emulator Setup ==="
echo ""

# 1. Update package lists
echo "[1/5] Updating package lists..."
apt-get update -qq

# 2. Install Python and BLE dependencies
echo "[2/5] Installing Python, pip, and BLE dependencies..."
apt-get install -y -qq \
    python3-full \
    python3-pip \
    python3-venv \
    bluez \
    bluetooth \
    libglib2.0-dev \
    git

# 3. Stop BlueZ from claiming hci0 (Bumble needs raw access)
echo "[3/5] Configuring Bluetooth for Bumble..."
systemctl stop bluetooth 2>/dev/null || true
systemctl disable bluetooth
systemctl mask bluetooth

# 4. Create emulator directory and venv
echo "[4/5] Setting up emulator environment..."
EMULATOR_DIR="/home/openpod/emulator"
mkdir -p "$EMULATOR_DIR"
chown openpod:openpod "$EMULATOR_DIR"

sudo -u openpod python3 -m venv "$EMULATOR_DIR/.venv"

# 5. Install Python dependencies
echo "[5/5] Installing Python packages..."
sudo -u openpod "$EMULATOR_DIR/.venv/bin/pip" install -q \
    "bumble>=0.0.200" \
    "cryptography>=42.0" \
    "pytest>=8.0"

echo ""
echo "=== Setup Complete ==="
echo ""
echo "Next steps — from your Mac, run the deploy script:"
echo "  ./emulator/deploy-to-pi.sh"
echo ""
echo "This will copy the emulator code, install the systemd service,"
echo "and start the emulator."
