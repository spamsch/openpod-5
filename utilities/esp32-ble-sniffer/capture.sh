#!/bin/bash
# Capture BLE sniffer output to a timestamped log file
#
# Usage:
#   ./capture.sh              Capture until Ctrl+C
#   ./capture.sh 60           Capture for 60 seconds
#   ./capture.sh 300 mytest   Capture for 300s with custom name

PORT="${PORT:-/dev/cu.usbserial-0001}"
BAUD=115200
DURATION="${1:-0}"
LABEL="${2:-capture}"
TIMESTAMP=$(date +%Y%m%d_%H%M%S)
LOGFILE="logs/${TIMESTAMP}_${LABEL}.log"
PYTHON="$HOME/.espressif/python_env/idf5.4_py3.14_env/bin/python3"

mkdir -p logs

echo "========================================"
echo "  ESP32 BLE Sniffer — Capture"
echo "========================================"
echo "  Port:     $PORT"
echo "  Log file: $LOGFILE"
if [ "$DURATION" -gt 0 ] 2>/dev/null; then
    echo "  Duration: ${DURATION}s"
else
    echo "  Duration: until Ctrl+C"
fi
echo "========================================"
echo ""
echo "Listening... (output appears below and in log file)"
echo ""

"$PYTHON" -c "
import serial
import time
import sys
import signal

port = '$PORT'
baud = $BAUD
duration = $DURATION
logfile = '$LOGFILE'

running = True

def handle_sigint(sig, frame):
    global running
    running = False

signal.signal(signal.SIGINT, handle_sigint)

ser = serial.Serial(port, baud, timeout=1, dsrdtr=False, rtscts=False)
# Don't toggle DTR/RTS to avoid resetting the ESP32
ser.setDTR(False)
ser.setRTS(False)

start = time.time()
pkt_count = 0
omnipod_count = 0

with open(logfile, 'w') as f:
    header = f'# BLE Sniffer Capture — {time.strftime(\"%Y-%m-%d %H:%M:%S\")}\n'
    header += f'# Port: {port}\n'
    header += f'# Duration: {duration}s\n' if duration > 0 else '# Duration: manual\n'
    header += '#\n'
    f.write(header)
    print(header, end='')

    try:
        while running:
            if duration > 0 and (time.time() - start) >= duration:
                break

            line = ser.readline()
            if not line:
                continue

            try:
                text = line.decode('utf-8', errors='replace').rstrip()
            except:
                continue

            if not text:
                continue

            # Write to log
            ts = f'{time.time() - start:.3f}'
            log_line = f'[{ts}] {text}'
            f.write(log_line + '\n')
            f.flush()

            # Print to terminal
            if 'OMNIPOD' in text:
                # ANSI green highlight
                print(f'\033[92m{log_line}\033[0m')
                omnipod_count += 1
            elif 'ADV_DIRECT' in text:
                # ANSI yellow highlight
                print(f'\033[93m{log_line}\033[0m')
            elif text.startswith('[') or text.startswith('***'):
                print(log_line)
                pkt_count += 1
            else:
                # Boot messages etc — print dimmed
                print(f'\033[90m{text}\033[0m')

    except Exception as e:
        print(f'\nError: {e}')

ser.close()

elapsed = time.time() - start
print(f'\n========================================')
print(f'  Capture complete')
print(f'  Duration:   {elapsed:.1f}s')
print(f'  Packets:    {pkt_count}')
print(f'  Omnipod:    {omnipod_count}')
print(f'  Log saved:  {logfile}')
print(f'========================================')
"
