#!/usr/bin/env bash
# test-ble-pairing.sh — End-to-end BLE pairing + activation test
#
# Restarts the Pi emulator, builds and installs the app, then drives
# the full UI flow:
#   Dashboard → Pair Pod → Pod Is Filled → Discover → tap pod →
#   Connect/Auth → Prime → Apply (select site) → Begin Delivery →
#   Activation → Go to Dashboard
#
# Usage:  ./emulator/test-ble-pairing.sh
#         ./emulator/test-ble-pairing.sh --skip-build   # reuse current APK

set -euo pipefail

PI_HOST="${PI_HOST:-openpod@openpod.local}"
PACKAGE="com.openpod"
ACTIVITY="${PACKAGE}/.MainActivity"
LOG_TAG_FILTER="BlePodManager|KablePod|MTU|PairingModule|DashboardModule|MVI/Pairing.*Intent|MVI/Pairing.*error|Pod discovered|EAP|pairing|TpClassic|auth|prime|activation|cannula|delivery|Activated"

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

get_pid() {
  adb shell pidof "$PACKAGE" 2>/dev/null || true
}

dump_logs() {
  local pid="$1" label="$2"
  echo ""
  echo "═══ $label — App logs ═══"
  adb logcat -d --pid="$pid" \
    | grep -iE "$LOG_TAG_FILTER" \
    | grep -iv "WindowManager|FeatureFlags|CompatChange" \
    | tail -40
  echo ""
  echo "═══ $label — Emulator logs ═══"
  ssh -o ConnectTimeout=5 "$PI_HOST" \
    "sudo journalctl -u openpod-emulator --no-pager --since '2 min ago'" 2>&1 \
    | grep -iE "INFO|WARN|ERROR" \
    | grep -v "transport\|recv\|send" \
    | tail -30
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

info "Granting BLE permissions..."
adb shell pm grant "$PACKAGE" android.permission.BLUETOOTH_SCAN
adb shell pm grant "$PACKAGE" android.permission.BLUETOOTH_CONNECT
adb shell pm grant "$PACKAGE" android.permission.ACCESS_FINE_LOCATION

info "Launching app..."
adb shell am force-stop "$PACKAGE"
adb logcat -c
adb shell am start -n "$ACTIVITY" >/dev/null
sleep 3

# ── Step 1: FILL — Tap "Pair Pod" then "Pod Is Filled" ───────────

info "Step 1/7 FILL: Tapping 'Pair Pod'..."
tap_text "Pair Pod" || die "Could not find 'Pair Pod' button"
sleep 2

info "Step 1/7 FILL: Tapping 'Pod Is Filled'..."
tap_text "Pod Is Filled" || die "Could not find 'Pod Is Filled' button"
sleep 1

# ── Step 2: DISCOVER — Wait for pod, tap it ──────────────────────

info "Step 2/7 DISCOVER: Waiting for emulator pod..."
if ! wait_for_text "AP 0000E001" 30; then
  die "Pod not discovered within 30s"
fi
info "Step 2/7 DISCOVER: Pod found — tapping it..."
sleep 1
tap_text "AP 0000E001" || die "Could not tap discovered pod"

# ── Step 3: CONNECT — Automatic (BLE + ECDH + EAP-AKA) ──────────

info "Step 3/7 CONNECT: Waiting for connection + auth..."
if ! wait_for_text "Priming" 30; then
  # Might already be past priming to the button
  if ! wait_for_text "Priming Complete" 5; then
    PID=$(get_pid)
    dump_logs "$PID" "CONNECT failed"
    die "Connection/auth did not complete within 30s"
  fi
fi
info "Step 3/7 CONNECT: Complete"

# ── Step 4: PRIME — Wait for priming, tap "Priming Complete" ─────

info "Step 4/7 PRIME: Waiting for priming to finish..."
if ! wait_for_text "Confirm priming complete" 90; then
  PID=$(get_pid)
  dump_logs "$PID" "PRIME failed"
  die "Priming did not complete within 30s"
fi
info "Step 4/7 PRIME: Done — confirming..."
sleep 1
tap_text "Priming Complete" || die "Could not tap 'Priming Complete'"
sleep 2

# ── Step 5: APPLY — Select site, tap "Pod Is Applied" ────────────

info "Step 5/7 APPLY: Selecting infusion site..."
# Wait for the apply screen
if ! wait_for_text "Pod Is Applied" 10; then
  die "Apply screen did not appear"
fi
# Select first available site (e.g. "Abdomen L")
tap_text "Abdomen L" || tap_text "Abdomen" || die "Could not select infusion site"
sleep 1
info "Step 5/7 APPLY: Tapping 'Pod Is Applied'..."
tap_text "Pod Is Applied" || die "Could not tap 'Pod Is Applied'"
sleep 2

# ── Step 6: START — Tap "Begin Delivery", wait for activation ────

info "Step 6/7 START: Tapping 'Begin Delivery'..."
if ! wait_for_text "Begin Delivery" 10; then
  die "Start screen did not appear"
fi
tap_text "Begin Delivery" || die "Could not tap 'Begin Delivery'"

info "Step 6/7 START: Waiting for activation to complete..."
if ! wait_for_text "Pod Activated" 45; then
  PID=$(get_pid)
  dump_logs "$PID" "ACTIVATION failed"
  die "Activation did not complete within 45s"
fi
info "Step 6/7 START: Activation complete"
sleep 1

# ── Step 7: COMPLETE — Tap "Go to Dashboard" ─────────────────────

info "Step 7/7 COMPLETE: Tapping 'Go to Dashboard'..."
tap_text "Go to Dashboard" || die "Could not tap 'Go to Dashboard'"
sleep 2

# ── Results ──────────────────────────────────────────────────────

PID=$(get_pid)
if [[ -z "$PID" ]]; then
  die "App not running"
fi

dump_logs "$PID" "Final"

echo ""
# Check for errors
if adb logcat -d --pid="$PID" | grep -qiE "error.*Timed out|IllegalState.*exception"; then
  echo "⚠  Errors detected in app logs"
  exit 1
else
  echo "✓  Full pairing + activation completed successfully"
  exit 0
fi
