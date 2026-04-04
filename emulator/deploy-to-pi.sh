#!/usr/bin/env bash
set -euo pipefail

PI_HOST="${PI_HOST:-openpod@openpod.local}"
PI_DIR="/home/openpod/emulator"
LOCAL_DIR="$(cd "$(dirname "$0")" && pwd)"

# Parse deploy script flags
REPLAY_REAL_POD=false
PUBLIC_ADDRESS=false
NO_LEGACY_ADV=false
BLE_ADDRESS=""
UNPAIRED_UUID_INDEX=""
for arg in "$@"; do
  case "$arg" in
    --replay-real-pod)       REPLAY_REAL_POD=true ;;
    --public-address)        PUBLIC_ADDRESS=true ;;
    --no-legacy-adv)         NO_LEGACY_ADV=true ;;
    --ble-address=*)         BLE_ADDRESS="${arg#*=}" ;;
    --unpaired-uuid-index=*) UNPAIRED_UUID_INDEX="${arg#*=}" ;;
    *)
      echo "ERROR: Unknown flag: $arg" >&2
      echo "  Valid flags: --replay-real-pod --public-address --no-legacy-adv --ble-address=XX:XX:XX:XX:XX:XX --unpaired-uuid-index=0..3" >&2
      exit 1
      ;;
  esac
done

echo "Deploy flags: replay_real_pod=${REPLAY_REAL_POD} public_address=${PUBLIC_ADDRESS} no_legacy_adv=${NO_LEGACY_ADV} ble_address=${BLE_ADDRESS:-<default>} unpaired_uuid_index=${UNPAIRED_UUID_INDEX:-0}"

# Extract user and hostname from user@host format
PI_USER="${PI_HOST%%@*}"
PI_HOSTNAME="${PI_HOST#*@}"

echo "Checking connectivity to ${PI_HOSTNAME} ..."

# Step 1: Check if the Pi is reachable (mDNS / direct IP)
if ping -c 1 -W 3 "$PI_HOSTNAME" >/dev/null 2>&1; then
  echo "  Found ${PI_HOSTNAME} via ping."
else
  echo "  ${PI_HOSTNAME} not reachable via ping — trying to discover the Pi ..."
  PI_IP=""

  # Method 1: ARP table — the Pi may already be known from recent traffic
  echo "  Checking ARP table ..."
  PI_IP=$(arp -a 2>/dev/null | grep -iE 'raspberry|openpod' | head -1 | sed 's/.*(\([0-9.]*\)).*/\1/' || true)
  if [ -n "$PI_IP" ]; then
    echo "    Found ${PI_IP} in ARP table."
  else
    echo "    Not found in ARP table."
  fi

  # Method 2: mDNS service discovery — find SSH services advertised on the network
  if [ -z "$PI_IP" ]; then
    echo "  Running mDNS scan for SSH services (5s) ..."
    MDNS_RESULT=$(timeout 5 dns-sd -B _ssh._tcp 2>/dev/null | grep -iE 'raspberry|openpod' | head -1 || true)
    if [ -n "$MDNS_RESULT" ]; then
      MDNS_NAME=$(echo "$MDNS_RESULT" | awk '{print $NF}')
      echo "    Found mDNS service: ${MDNS_NAME} — resolving ..."
      PI_IP=$(timeout 3 dns-sd -G v4 "${MDNS_NAME}.local" 2>/dev/null \
        | awk '/Addr/ {print $NF}' | head -1 || true)
      if [ -n "$PI_IP" ]; then
        echo "    Resolved to ${PI_IP}."
      else
        echo "    Could not resolve ${MDNS_NAME}.local to an IP."
      fi
    else
      echo "    No Raspberry Pi found via mDNS."
    fi
  fi

  # Method 3: Scan active interfaces for the Pi (covers USB gadget and WiFi subnets)
  if [ -z "$PI_IP" ]; then
    echo "  Scanning local subnets ..."
    for iface in $(ifconfig -l 2>/dev/null); do
      if ifconfig "$iface" 2>/dev/null | grep -q "status: active"; then
        SUBNET=$(ifconfig "$iface" 2>/dev/null | awk '/inet / {print $2}' | head -1)
        if [ -n "$SUBNET" ]; then
          NET_PREFIX="${SUBNET%.*}"
          for last_octet in 1 2; do
            CANDIDATE="${NET_PREFIX}.${last_octet}"
            [ "$CANDIDATE" = "$SUBNET" ] && continue
            if ping -c 1 -W 2 "$CANDIDATE" >/dev/null 2>&1 &&
               ssh -o ConnectTimeout=3 -o BatchMode=yes "${PI_USER}@${CANDIDATE}" true >/dev/null 2>&1; then
              PI_IP="$CANDIDATE"
              break 2
            fi
          done
        fi
      fi
    done
  fi

  # Verify the discovered IP is actually SSH-reachable before accepting it
  if [ -n "$PI_IP" ]; then
    if ping -c 1 -W 2 "$PI_IP" >/dev/null 2>&1; then
      echo "  Found Pi at ${PI_IP}."
      PI_HOST="${PI_USER}@${PI_IP}"
    else
      PI_IP=""
    fi
  fi

  if [ -z "$PI_IP" ]; then
    echo "ERROR: Cannot find the Raspberry Pi." >&2
    echo "  Tried:" >&2
    echo "    - mDNS hostname: ${PI_HOSTNAME}" >&2
    echo "    - ARP table lookup" >&2
    echo "    - mDNS service discovery (_ssh._tcp)" >&2
    echo "    - Subnet scan on active interfaces (USB gadget + WiFi)" >&2
    echo "" >&2
    echo "  Hints:" >&2
    echo "    - Is the Pi powered on?" >&2
    echo "    - USB: connected via USB-C data port? Check System Settings > Network for RNDIS." >&2
    echo "    - WiFi: is the Pi on the same network? Try: arp -a | grep raspberry" >&2
    echo "    - Override manually: PI_HOST=openpod@<ip> $0" >&2
    exit 1
  fi
fi

# Step 2: Verify SSH access
echo "Checking SSH access to ${PI_HOST} ..."
SSH_OUTPUT=$(ssh -o ConnectTimeout=5 -o BatchMode=yes "$PI_HOST" true 2>&1) && SSH_OK=true || SSH_OK=false

if [ "$SSH_OK" = false ]; then
  if echo "$SSH_OUTPUT" | grep -qi "permission denied"; then
    echo "ERROR: SSH key auth failed for ${PI_HOST}." >&2
    echo "  sshd is running, but your key is not authorized." >&2
    echo "  Run: ssh-copy-id ${PI_HOST}" >&2
    echo "  (password: openpod)" >&2
  elif echo "$SSH_OUTPUT" | grep -qi "connection refused"; then
    echo "ERROR: SSH connection refused by ${PI_HOST}." >&2
    echo "  The Pi is reachable but sshd may not be running." >&2
    echo "  SSH into the Pi via another method and check: sudo systemctl status sshd" >&2
  else
    echo "ERROR: Cannot SSH into ${PI_HOST}." >&2
    echo "  $SSH_OUTPUT" >&2
  fi
  exit 1
fi
echo "  SSH connection OK."

# Step 3: Stamp build info into version.py
BUILD_HASH=$(git -C "$LOCAL_DIR/.." rev-parse --short HEAD 2>/dev/null || echo "unknown")
BUILD_TIME=$(date -u '+%Y-%m-%dT%H:%M:%SZ')
VERSION_FILE="${LOCAL_DIR}/omnipod_emulator/version.py"

echo "Stamping build: ${BUILD_HASH} at ${BUILD_TIME}"
sed -i.bak "s/^BUILD_HASH = .*/BUILD_HASH = \"${BUILD_HASH}\"/" "$VERSION_FILE"
sed -i.bak "s/^BUILD_TIME = .*/BUILD_TIME = \"${BUILD_TIME}\"/" "$VERSION_FILE"
rm -f "${VERSION_FILE}.bak"

echo "Deploying emulator to ${PI_HOST}:${PI_DIR} ..."

rsync -avz --delete \
  --exclude '__pycache__' \
  --exclude '*.pyc' \
  --exclude '.pytest_cache' \
  --exclude '*.egg-info' \
  --exclude '.venv' \
  "$LOCAL_DIR/" "${PI_HOST}:${PI_DIR}/"

echo "Reinstalling package on Pi ..."
ssh "$PI_HOST" "cd ${PI_DIR} && .venv/bin/pip install -e . --quiet"

echo "Installing systemd service ..."
EXTRA_ARGS=""
if [ "$REPLAY_REAL_POD" = true ]; then
  EXTRA_ARGS="${EXTRA_ARGS} --replay-real-pod"
  echo "  >> REPLAY MODE: injecting --replay-real-pod"
fi
if [ "$PUBLIC_ADDRESS" = true ]; then
  EXTRA_ARGS="${EXTRA_ARGS} --public-address"
  echo "  >> PUBLIC ADDRESS: injecting --public-address"
fi
if [ "$NO_LEGACY_ADV" = true ]; then
  EXTRA_ARGS="${EXTRA_ARGS} --no-legacy-adv"
  echo "  >> EXTENDED ADV: injecting --no-legacy-adv"
fi
if [ -n "$BLE_ADDRESS" ]; then
  EXTRA_ARGS="${EXTRA_ARGS} --ble-address ${BLE_ADDRESS}"
  echo "  >> BLE ADDRESS: injecting --ble-address ${BLE_ADDRESS}"
fi
if [ -n "$UNPAIRED_UUID_INDEX" ]; then
  EXTRA_ARGS="${EXTRA_ARGS} --unpaired-uuid-index ${UNPAIRED_UUID_INDEX}"
  echo "  >> UNPAIRED UUID INDEX: injecting --unpaired-uuid-index ${UNPAIRED_UUID_INDEX}"
fi
if [ -n "$EXTRA_ARGS" ]; then
  ssh "$PI_HOST" "sed 's|--log-file|${EXTRA_ARGS} --log-file|' ${PI_DIR}/openpod-emulator.service | sudo tee /etc/systemd/system/openpod-emulator.service >/dev/null && sudo systemctl daemon-reload"
else
  ssh "$PI_HOST" "sudo cp ${PI_DIR}/openpod-emulator.service /etc/systemd/system/ && sudo systemctl daemon-reload"
fi

echo "Restarting emulator service ..."
ssh "$PI_HOST" "sudo systemctl restart openpod-emulator"

# Step 6: Reset version.py back to dev (don't pollute working tree)
sed -i.bak "s/^BUILD_HASH = .*/BUILD_HASH = \"dev\"/" "$VERSION_FILE"
sed -i.bak "s/^BUILD_TIME = .*/BUILD_TIME = \"unknown\"/" "$VERSION_FILE"
rm -f "${VERSION_FILE}.bak"

echo "Done. Checking service status:"
ssh "$PI_HOST" "sudo systemctl status openpod-emulator --no-pager -l" || true
