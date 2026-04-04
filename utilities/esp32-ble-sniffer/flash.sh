#!/bin/bash
# ESP32 BLE Sniffer — build, flash, and monitor
#
# Usage:
#   ./flash.sh           Build, flash, and open monitor
#   ./flash.sh monitor   Just open serial monitor
#   ./flash.sh build     Just build

PORT="${PORT:-/dev/cu.usbserial-0001}"
BAUD="${BAUD:-115200}"

set -e

cd "$(dirname "$0")"

case "${1:-all}" in
    monitor)
        echo "Opening monitor on $PORT..."
        idf.py -p "$PORT" monitor
        ;;
    build)
        echo "Building..."
        idf.py set-target esp32
        idf.py build
        echo "Build complete."
        ;;
    all|"")
        echo "Building, flashing, and monitoring..."
        echo "Port: $PORT"
        idf.py set-target esp32
        idf.py build
        idf.py -p "$PORT" flash monitor
        ;;
    *)
        echo "Usage: $0 [build|monitor|all]"
        exit 1
        ;;
esac
