#!/usr/bin/env bash
set -euo pipefail

PI_HOST="${PI_HOST:-openpod@openpod.local}"
LINES="${1:-50}"

echo "Connecting to ${PI_HOST} — tailing emulator logs (last ${LINES} lines) ..."
echo "Press Ctrl+C to stop."
echo ""

ssh -o ConnectTimeout=5 "$PI_HOST" "sudo journalctl -u openpod-emulator -n ${LINES} -f"
