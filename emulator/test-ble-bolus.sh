#!/usr/bin/env bash
# test-ble-bolus.sh — End-to-end bolus delivery test
#
# Prerequisite: Run test-ble-pairing.sh first so the pod is activated
# and the app is on the dashboard. This script:
#   Dashboard → Bolus FAB → Enter carbs → Use suggested dose →
#   Review → PIN → Deliver → Wait for completion → Done → Dashboard
#
# Usage:  ./emulator/test-ble-bolus.sh
#         ./emulator/test-ble-bolus.sh --skip-build
#         ./emulator/test-ble-bolus.sh --carbs 30
#         BOLUS_PIN=3151 ./emulator/test-ble-bolus.sh

set -euo pipefail

PACKAGE="com.openpod"
ACTIVITY="${PACKAGE}/.MainActivity"
PI_HOST="${PI_HOST:-openpod@openpod.local}"
BOLUS_PIN="${BOLUS_PIN:-3151}"
BOLUS_CARBS="${BOLUS_CARBS:-20}"
LOG_TAG_FILTER="BlePodManager|BolusViewModel|MVI/Bolus|MVI/Dashboard|bolus|Bolus|delivery|Deliver"

skip_build=false
for arg in "$@"; do
  case "$arg" in
    --skip-build) skip_build=true ;;
    --carbs=*) BOLUS_CARBS="${arg#*=}" ;;
  esac
done
# Handle --carbs VALUE form
prev=""
for arg in "$@"; do
  if [[ "$prev" == "--carbs" ]]; then BOLUS_CARBS="$arg"; fi
  prev="$arg"
done

# ── Helpers ────────────────────────────────────────────────────────

die()  { echo "FAIL: $*" >&2; exit 1; }
info() { echo "▸ $*"; }

tap_text() {
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

tap_pin_digit() {
  # The PIN number pad has a fixed 3x4 grid layout.
  # We dump the UI once and find the exact digit by matching
  # only within the PIN pad region (y > 1000).
  local digit="$1"
  adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
  local coords
  coords=$(adb shell cat /sdcard/ui.xml | python3 -c "
import sys, re, xml.etree.ElementTree as ET
tree = ET.parse(sys.stdin)
digit = '${digit}'
for node in tree.iter('node'):
    t = node.get('text','')
    if t == digit:
        m = re.findall(r'\d+', node.get('bounds',''))
        if m and int(m[1]) > 1000:  # only PIN pad region
            cx = (int(m[0])+int(m[2]))//2
            cy = (int(m[1])+int(m[3]))//2
            print(cx, cy)
            break
" 2>/dev/null)
  [[ -z "$coords" ]] && return 1
  local x=${coords%% *} y=${coords##* }
  adb shell input tap "$x" "$y"
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
    | tail -30
  echo ""
  echo "═══ $label — Emulator logs ═══"
  ssh -o ConnectTimeout=5 "$PI_HOST" \
    "sudo journalctl -u openpod-emulator --no-pager --since '2 min ago'" 2>&1 \
    | grep -iE "INFO|WARN|ERROR" \
    | grep -v "transport\|recv\|send" \
    | tail -20
}

# ── Preconditions ─────────────────────────────────────────────────

info "Bolus test: carbs=${BOLUS_CARBS}g, PIN=${BOLUS_PIN}"

PID=$(get_pid)
if [[ -z "$PID" ]]; then
  die "App is not running — run test-ble-pairing.sh first"
fi

# Build & install if requested
if [[ "$skip_build" == false ]]; then
  info "Building and installing app..."
  make install 2>&1 | tail -3
  # Relaunch since install replaces the app
  adb shell am force-stop "$PACKAGE"
  adb shell am start -n "$ACTIVITY" >/dev/null
  sleep 3
  PID=$(get_pid)
fi

adb logcat -c

# ── Ensure we're on the dashboard ────────────────────────────────

info "Ensuring we're on the dashboard..."
for attempt in 1 2 3 4 5; do
  adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
  if adb shell cat /sdcard/ui.xml 2>/dev/null | grep -q "Dashboard"; then
    break
  fi
  adb shell input keyevent KEYCODE_BACK
  sleep 1
done
if ! wait_for_text "Dashboard" 5; then
  # Try relaunching the app
  adb shell am start -n "$ACTIVITY" >/dev/null
  sleep 3
fi

# ── Step 1: Tap Bolus FAB on dashboard ───────────────────────────

info "Step 1: Tapping Bolus FAB..."
if ! wait_for_text "Bolus" 10; then
  die "Dashboard not showing — is the pod activated?"
fi
tap_text "Bolus" || die "Could not tap Bolus FAB"
sleep 2

# ── Step 2: Enter dose ───────────────────────────────────────────

info "Step 2: Entering ${BOLUS_CARBS}g carbs..."
if ! wait_for_text "Carbs" 5; then
  die "Bolus entry screen did not appear"
fi
# Tap the Carbs field, type the value, then dismiss the keyboard
tap_text "Carbs" || true
sleep 0.5
adb shell input text "$BOLUS_CARBS"
sleep 0.5
# Dismiss keyboard so subsequent taps hit UI buttons, not the keyboard
adb shell input keyevent KEYCODE_BACK
sleep 1

# Tap "Use Suggested Dose" if the calculator shows a suggestion
info "Step 2: Using suggested dose from calculator..."
if ! tap_text "Use Suggested"; then
  info "No suggested dose button — dose may already be set"
fi
sleep 1

# ── Step 3: Tap "Review Bolus" ───────────────────────────────────

info "Step 3: Tapping 'Review Bolus'..."
tap_text "Review Bolus" || die "Could not tap 'Review Bolus'"
sleep 2

# ── Step 4: Enter PIN ────────────────────────────────────────────

info "Step 4: Entering PIN..."
if wait_for_text "Enter PIN" 5; then
  for (( i=0; i<${#BOLUS_PIN}; i++ )); do
    digit="${BOLUS_PIN:$i:1}"
    tap_pin_digit "$digit" || die "Could not tap PIN digit $digit"
    sleep 0.5
  done
  sleep 1
else
  info "No PIN required (skipped)"
fi

# ── Step 5: Tap "Deliver" ────────────────────────────────────────

info "Step 5: Tapping 'Deliver'..."
if ! wait_for_text "Deliver" 5; then
  PID=$(get_pid)
  dump_logs "$PID" "Deliver button not found"
  die "Deliver button did not appear — PIN may have failed"
fi
tap_text "Deliver" || die "Could not tap Deliver button"
sleep 2

# ── Step 6: Wait for delivery ────────────────────────────────────

info "Step 6: Waiting for bolus delivery to complete..."
if ! wait_for_text "Bolus Complete" 60; then
  if wait_for_text "Bolus Cancelled" 2; then
    PID=$(get_pid)
    dump_logs "$PID" "Bolus was cancelled"
    die "Bolus was cancelled"
  fi
  PID=$(get_pid)
  dump_logs "$PID" "Bolus delivery timeout"
  die "Bolus delivery did not complete within 60s"
fi
info "Step 6: Bolus delivery complete!"
sleep 1

# ── Step 7: Tap "Done" ──────────────────────────────────────────

info "Step 7: Tapping 'Done'..."
tap_text "Done" || die "Could not tap 'Done'"
sleep 2

# ── Results ──────────────────────────────────────────────────────

PID=$(get_pid)
if [[ -z "$PID" ]]; then
  die "App not running"
fi

dump_logs "$PID" "Final"

echo ""
if adb logcat -d --pid="$PID" | grep -qiE "error.*Timed out|IllegalState.*exception|Failed to start bolus"; then
  echo "⚠  Errors detected in app logs"
  exit 1
else
  echo "✓  Bolus for ${BOLUS_CARBS}g carbs delivered successfully"
  exit 0
fi
