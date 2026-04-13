#!/usr/bin/env bash
set -euo pipefail

PI_HOST="${PI_HOST:-openpod@openpod.local}"
SERVICE="openpod-emulator"

echo "Stopping $SERVICE on $PI_HOST ..."
ssh "$PI_HOST" "sudo systemctl stop $SERVICE"

sleep 2

echo "Status:"
ssh "$PI_HOST" "sudo systemctl status $SERVICE --no-pager -l" | tail -8
