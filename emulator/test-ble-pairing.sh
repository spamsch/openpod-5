#!/usr/bin/env bash
# test-ble-pairing.sh — End-to-end BLE pairing test
#
# Restarts the Pi emulator, builds and installs the app, then drives
# the UI through: Dashboard → Pair Pod → Pod Is Filled → Discover →
# tap pod → watch connection/pairing/auth logs.
#
# Usage:  ./emulator/test-ble-pairing.sh
#         ./emulator/test-ble-pairing.sh --skip-build   # reuse current APK

set -euo pipefail

PI_HOST="${PI_HOST:-openpod@openpod.local}"
PACKAGE="com.openpod"
ACTIVITY="${PACKAGE}/.MainActivity"
LOG_TAG_FILTER="BlePodManager|KablePod|MTU|PairingModule|DashboardModule|MVI/Pairing.*Intent|MVI/Pairing.*error|Pod discovered|EAP|pairing|TpClassic|auth"

skip_build=false
[[ "${1:-}" == "--skip-build" ]] && skip_build=true

# ── Helpers ────────────────────────────────────────────────────────

die()  { echo "FAIL: $*" >&2; exit 1; }
info() { echo "▸ $*"; }

tap_text() {
  # Dump UI, find element by text, tap its centre
  local text="$1"
  adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
  local coords
  coords=$(adb shell cat /sdcard/ui.xml | python3 -c "
import sys, re, xml.etree.ElementTree as ET
tree = ET.parse(sys.stdin)
target = '${text}'
for node in tree.iter('node'):
    t = node.get('text','')
    d = node.get('content-desc','')
    if target in t or target in d:
        m = re.findall(r'\d+', node.get('bounds',''))
        if m:
            print((int(m[0])+int(m[2]))//2, (int(m[1])+int(m[3]))//2)
            break
" 2>/dev/null)
  [[ -z "$coords" ]] && return 1
  local x=${coords%% *} y=${coords##* }
  adb shell input tap "$x" "$y"
  return 0
}

wait_for_text() {
  local text="$1" timeout="${2:-30}"
  for (( i=0; i<timeout; i+=2 )); do
    adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
    if adb shell cat /sdcard/ui.xml 2>/dev/null | grep -q "$text"; then
      return 0
    fi
    sleep 2
  done
  return 1
}

# ── 1. Restart emulator ───────────────────────────────────────────

info "Stopping app and restarting emulator on Pi..."
adb shell am force-stop "$PACKAGE" 2>/dev/null || true
ssh -o ConnectTimeout=5 "$PI_HOST" "sudo systemctl restart openpod-emulator"
sleep 4  # wait for emulator to fully start and begin advertising

# ── 2. Build & install ────────────────────────────────────────────

if [[ "$skip_build" == false ]]; then
  info "Building and installing app..."
  make install 2>&1 | tail -3
fi

# ── 3. Launch app ─────────────────────────────────────────────────

info "Launching app..."
adb shell am force-stop "$PACKAGE"
adb logcat -c
adb shell am start -n "$ACTIVITY" >/dev/null
sleep 3

# ── 4. Pair Pod ───────────────────────────────────────────────────

info "Tapping 'Pair Pod'..."
tap_text "Pair Pod" || die "Could not find 'Pair Pod' button"
sleep 2

# ── 5. Pod Is Filled ─────────────────────────────────────────────

info "Tapping 'Pod Is Filled'..."
tap_text "Pod Is Filled" || die "Could not find 'Pod Is Filled' button"
sleep 1

# ── 6. Wait for pod discovery ────────────────────────────────────

info "Waiting for emulator pod to appear..."
if ! wait_for_text "Openpod_Emu" 30; then
  die "Pod not discovered within 30s"
fi

# ── 7. Tap discovered pod ────────────────────────────────────────

info "Pod discovered — tapping it..."
sleep 1
tap_text "Openpod_Emu" || die "Could not tap discovered pod"

# ── 8. Collect logs ──────────────────────────────────────────────

info "Waiting for connection + auth sequence (20s)..."
sleep 20

PID=$(adb shell pidof "$PACKAGE" 2>/dev/null || true)
if [[ -z "$PID" ]]; then
  die "App not running"
fi

echo ""
echo "═══ App logs ═══"
adb logcat -d --pid="$PID" | grep -iE "$LOG_TAG_FILTER" | grep -iv "WindowManager|FeatureFlags|CompatChange"

echo ""
echo "═══ Emulator logs ═══"
ssh -o ConnectTimeout=5 "$PI_HOST" \
  "sudo journalctl -u openpod-emulator --no-pager --since '1 min ago'" 2>&1 \
  | grep -iE "INFO|WARN|ERROR" \
  | grep -v "transport\|recv\|send"

echo ""
# Check for errors
if adb logcat -d --pid="$PID" | grep -qiE "error.*Timed out|exception|FAILED"; then
  echo "⚠  Errors detected in app logs"
  exit 1
else
  echo "✓  No errors detected"
  exit 0
fi
