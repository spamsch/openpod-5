package com.openpod.scanner;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanRecord;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;
import android.util.SparseArray;
import android.widget.Button;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class ScanActivity extends AppCompatActivity {

    // Emulator: TWI SDK 128-bit UUID prefix (both paired & unpaired)
    private static final String OMNIPOD_UUID_PREFIX = "ce1f923d-c539-48ea-7300-0a";

    // Real pod: 16-bit service UUID 0x4024 in standard Bluetooth base form
    private static final String OMNIPOD_REAL_SERVICE_UUID = "00004024-0000-1000-8000-00805f9b34fb";

    // Insulet Bluetooth SIG company ID
    private static final int INSULET_COMPANY_ID = 0x0360;

    private BluetoothLeScanner scanner;
    private TextView tvStatus;
    private TextView tvResults;
    private Button btnScan;
    private boolean scanning = false;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final StringBuilder results = new StringBuilder();
    private final Set<String> seenAddresses = new HashSet<>();
    private int deviceCount = 0;
    private PrintWriter logWriter;
    private File logFile;

    private static final long SCAN_DURATION_MS = 180_000;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_scan);

        tvStatus = findViewById(R.id.tvStatus);
        tvResults = findViewById(R.id.tvResults);
        btnScan = findViewById(R.id.btnScan);

        btnScan.setOnClickListener(v -> {
            if (scanning) stopScan();
            else startScan();
        });
    }

    private void startScan() {
        // Check permissions
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{
                            Manifest.permission.BLUETOOTH_SCAN,
                            Manifest.permission.BLUETOOTH_CONNECT,
                            Manifest.permission.ACCESS_FINE_LOCATION
                    }, 1);
            return;
        }

        BluetoothManager btMgr = getSystemService(BluetoothManager.class);
        BluetoothAdapter adapter = btMgr.getAdapter();
        if (adapter == null || !adapter.isEnabled()) {
            tvStatus.setText("Bluetooth not available or disabled");
            return;
        }

        scanner = adapter.getBluetoothLeScanner();
        results.setLength(0);
        seenAddresses.clear();
        deviceCount = 0;
        tvResults.setText("");

        // Open log file for this scan session
        openLogFile();

        // No scan filters — we want to see ALL BLE devices but highlight Omnipod ones
        ScanSettings settings = new ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .setLegacy(false)
                .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
                .build();
        scanner.startScan(null, settings, scanCallback);
        scanning = true;
        btnScan.setText("Stop Scan");
        tvStatus.setText("Scanning... (showing ALL devices, Omnipod highlighted)");

        handler.postDelayed(this::stopScan, SCAN_DURATION_MS);
    }

    private void stopScan() {
        if (scanner != null && scanning) {
            try {
                scanner.stopScan(scanCallback);
            } catch (SecurityException ignored) {}
        }
        scanning = false;
        closeLogFile();
        btnScan.setText("Start Scan");
        String logPath = logFile != null ? logFile.getAbsolutePath() : "none";
        tvStatus.setText("Scan stopped. " + deviceCount + " device(s). Log: " + logPath);
    }

    private final ScanCallback scanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            String addr = result.getDevice().getAddress();
            ScanRecord record = result.getScanRecord();
            if (record == null) return;

            boolean isOmnipod = isOmnipodDevice(record);
            boolean firstSeen = !seenAddresses.contains(addr);

            // Log EVERY device to file (first sighting only to avoid huge logs)
            // But always log Omnipod devices (even repeat sightings for RSSI tracking)
            boolean shouldLog = firstSeen || isOmnipod;

            // For screen display: non-Omnipod only shown once
            if (!isOmnipod && !firstSeen) {
                // Still log raw bytes for repeat sightings with new advertising data
                return;
            }
            if (firstSeen) {
                seenAddresses.add(addr);
                deviceCount++;
            }

            StringBuilder entry = new StringBuilder();

            if (isOmnipod) {
                entry.append("========== OMNIPOD FOUND ==========\n");
            }

            String name = record.getDeviceName();
            entry.append("Device: ").append(name != null ? name : "(no name)")
                    .append("  [").append(addr).append("]\n");
            entry.append("RSSI: ").append(result.getRssi()).append(" dBm\n");

            // Service UUIDs
            List<ParcelUuid> uuids = record.getServiceUuids();
            if (uuids != null && !uuids.isEmpty()) {
                entry.append("Service UUIDs (").append(uuids.size()).append("):\n");
                boolean hasRealPodUuid = false;
                for (ParcelUuid uuid : uuids) {
                    String uuidStr = uuid.toString().toLowerCase();
                    entry.append("  ").append(uuidStr);
                    if (uuidStr.startsWith(OMNIPOD_UUID_PREFIX)) {
                        entry.append("  <-- EMULATOR SCAN UUID");
                        parseOmnipodUuid(entry, uuid);
                    } else if (uuidStr.equals(OMNIPOD_REAL_SERVICE_UUID)) {
                        entry.append("  <-- OMNIPOD 0x4024");
                        hasRealPodUuid = true;
                    }
                    entry.append("\n");
                }
                // Real pods encode pod info across 9 service UUIDs
                if (hasRealPodUuid && uuids.size() >= 5) {
                    parseRealPodServiceUuids(entry, uuids);
                }
            }

            // Manufacturer specific data
            SparseArray<byte[]> mfrData = record.getManufacturerSpecificData();
            if (mfrData != null && mfrData.size() > 0) {
                entry.append("Manufacturer Data:\n");
                for (int i = 0; i < mfrData.size(); i++) {
                    int companyId = mfrData.keyAt(i);
                    byte[] data = mfrData.valueAt(i);
                    entry.append("  Company ID: 0x")
                            .append(String.format("%04X", companyId))
                            .append(" (").append(companyId).append(")");
                    if (companyId == INSULET_COMPANY_ID) entry.append("  <-- INSULET");
                    entry.append("\n");
                    entry.append("  Data (").append(data.length).append(" bytes): ")
                            .append(bytesToHex(data)).append("\n");

                    if (isOmnipod) {
                        parseOmnipodMfrData(entry, companyId, data);
                    }
                }
            }

            // Raw advertising bytes
            byte[] rawBytes = record.getBytes();
            if (rawBytes != null) {
                entry.append("Raw AD (").append(rawBytes.length).append(" bytes):\n");
                entry.append("  ").append(bytesToHex(rawBytes)).append("\n");
                entry.append("AD Structures:\n");
                parseAdStructures(entry, rawBytes);
            }

            if (isOmnipod) {
                entry.append("====================================\n");
            }
            entry.append("\n");

            // Log ALL devices to file so we can find hidden Omnipods
            if (shouldLog) {
                logOmnipod(entry.toString());
            }

            handler.post(() -> {
                results.insert(isOmnipod ? 0 : results.length(), entry);
                tvResults.setText(results.toString());
            });
        }
    };

    private void openLogFile() {
        try {
            File dir = new File(getExternalFilesDir(null), "scans");
            dir.mkdirs();
            String ts = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
            logFile = new File(dir, "omnipod_scan_" + ts + ".log");
            logWriter = new PrintWriter(new FileWriter(logFile, true), true);
            logWriter.println("=== Omnipod Scan started " + new Date() + " ===\n");
        } catch (IOException e) {
            logWriter = null;
        }
    }

    private void closeLogFile() {
        if (logWriter != null) {
            logWriter.println("\n=== Scan ended " + new Date() + " ===");
            logWriter.close();
            logWriter = null;
        }
    }

    private synchronized void logOmnipod(String entry) {
        if (logWriter != null) {
            String ts = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(new Date());
            logWriter.println("[" + ts + "] " + entry);
        }
    }

    private boolean isOmnipodDevice(ScanRecord record) {
        // Check service UUIDs for both emulator (128-bit) and real pod (16-bit 0x4024)
        List<ParcelUuid> uuids = record.getServiceUuids();
        if (uuids != null) {
            for (ParcelUuid uuid : uuids) {
                String s = uuid.toString().toLowerCase();
                if (s.startsWith(OMNIPOD_UUID_PREFIX) || s.equals(OMNIPOD_REAL_SERVICE_UUID)) {
                    return true;
                }
            }
        }
        // Also match by Insulet company ID in manufacturer data
        SparseArray<byte[]> mfrData = record.getManufacturerSpecificData();
        if (mfrData != null && mfrData.indexOfKey(INSULET_COMPANY_ID) >= 0) {
            return true;
        }
        return false;
    }

    /**
     * Parse real Omnipod pod info from 16-bit service UUIDs.
     * Per AndroidAPS/OmniBLE: 9 UUIDs encode pod ID, lot, sequence.
     * Index 0: 0x4024 (service), 1: 0x2470, 2: 0x000A,
     * 3-4: pod ID (FFFF/FFFE = unactivated), 5-8: lot + sequence.
     */
    private void parseRealPodServiceUuids(StringBuilder sb, List<ParcelUuid> uuids) {
        sb.append("  --- Real Pod decode ---\n");

        // Extract 16-bit values from standard Bluetooth base UUIDs
        String[] shorts = new String[uuids.size()];
        for (int i = 0; i < uuids.size(); i++) {
            // UUID format: 0000XXXX-0000-1000-8000-00805f9b34fb
            String s = uuids.get(i).toString().toUpperCase();
            shorts[i] = s.substring(4, 8);  // extract XXXX
            sb.append("    [").append(i).append("] 0x").append(shorts[i]);
            switch (i) {
                case 0: sb.append(" (service ID)"); break;
                case 1: sb.append(" (status?)"); break;
                case 2: sb.append(" (profile?)"); break;
                case 3: sb.append(" (pod ID hi)"); break;
                case 4: sb.append(" (pod ID lo)"); break;
                default:
                    if (i <= 7) sb.append(" (lot)");
                    else sb.append(" (seq)");
                    break;
            }
            sb.append("\n");
        }

        if (uuids.size() >= 5) {
            String hiStr = shorts[3];
            String loStr = shorts[4];
            long podId = (Long.parseLong(hiStr, 16) << 16) | Long.parseLong(loStr, 16);
            sb.append("    Pod ID: 0x").append(String.format("%08X", podId));
            if (podId == 0xFFFFFFFEL) {
                sb.append("  [UNACTIVATED / PAIRABLE]");
            } else {
                sb.append("  [ACTIVATED]");
            }
            sb.append("\n");
        }

        if (uuids.size() >= 8) {
            String lot = shorts[5] + shorts[6] + shorts[7];
            sb.append("    Lot: ").append(lot).append("\n");
        }
        if (uuids.size() >= 9) {
            String seq = shorts[7].substring(2) + shorts[8];
            sb.append("    Seq: ").append(seq).append("\n");
        }
    }

    /**
     * Parse Omnipod-specific fields from the scan UUID.
     * Per protocol: bytes are in LE order in BLE advertising.
     * UUID bytes 5-6 (LE) = profile ID, bytes 1-4 (LE) = pod ID base.
     */
    private void parseOmnipodUuid(StringBuilder sb, ParcelUuid uuid) {
        // Convert UUID to 16 bytes in BLE (little-endian) order
        ByteBuffer bb = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN);
        bb.putLong(uuid.getUuid().getLeastSignificantBits());
        bb.putLong(uuid.getUuid().getMostSignificantBits());
        byte[] leBytes = bb.array();

        sb.append("\n    UUID LE bytes: ").append(bytesToHex(leBytes));

        // Profile ID from bytes 5-6 (LE int16)
        int profileId = (leBytes[5] & 0xFF) | ((leBytes[6] & 0xFF) << 8);
        sb.append("\n    Profile ID: ").append(profileId);
        sb.append(profileId == 10 ? " (VALID)" : " (INVALID - expected 10)");

        // Pod ID base from bytes 1-4 (LE int32)
        long podId = (leBytes[1] & 0xFFL) | ((leBytes[2] & 0xFFL) << 8)
                | ((leBytes[3] & 0xFFL) << 16) | ((leBytes[4] & 0xFFL) << 24);
        sb.append("\n    Pod ID base: 0x").append(String.format("%08X", podId))
                .append(" (").append(podId).append(")");

        // Check if paired or unpaired
        if (podId == 0xFFFFFFFEL) {
            sb.append("  [UNPAIRED]");
        } else {
            // For paired pods, bytes encode the controller ID
            sb.append("  [PAIRED - ctrl ID embedded]");
        }
    }

    /**
     * Parse Omnipod manufacturer data per protocol spec:
     * Byte[len-3]: pod ID adjustment bits (bits 4-5)
     * Byte[len-2]: alarm code
     * Byte[len-1]: alert code
     * Byte[4] & 0x0F: service type nibble
     */
    private void parseOmnipodMfrData(StringBuilder sb, int companyId, byte[] data) {
        sb.append("  --- Omnipod decode ---\n");
        if (data.length >= 3) {
            int podAdj = (data[data.length - 3] & 0x30) >> 4;
            int alarm = data[data.length - 2] & 0xFF;
            int alert = data[data.length - 1] & 0xFF;
            sb.append("    Pod ID adj bits: ").append(podAdj).append("\n");
            sb.append("    Alarm code: 0x").append(String.format("%02X", alarm)).append("\n");
            sb.append("    Alert code: 0x").append(String.format("%02X", alert)).append("\n");
        }
        // Service type from byte 4 (if manufacturer data is long enough)
        // Note: Android strips company ID from mfr data, so byte indices shift by -2
        // The raw AD has: [company_id_lo][company_id_hi][data...]
        // Android gives us just [data...], so "byte 4" in the raw AD = byte 2 here
        if (data.length >= 3) {
            int lowNibble = data[2] & 0x0F;
            int serviceType = (lowNibble == 0) ? 0x2470 : lowNibble + 0xC000;
            sb.append("    Service type: 0x").append(String.format("%04X", serviceType))
                    .append(" (").append(describeServiceType(serviceType)).append(")\n");
        }
    }

    private String describeServiceType(int type) {
        switch (type) {
            case 0x2470: return "NO_DATA (default)";
            case 0xC001: return "ALERT_CLOUD_ONLY";
            case 0xC002: return "ALARM_CLOUD_ONLY";
            case 0xC004: return "ALERT_CENTRAL_ONLY";
            case 0xC005: return "ALERT_CENTRAL+CLOUD";
            case 0xC006: return "ALERT_CENTRAL+ALARM_CLOUD";
            case 0xC008: return "ALARM_CENTRAL_ONLY";
            case 0xC009: return "ALARM_CENTRAL+ALERT_CLOUD";
            case 0xC00A: return "ALARM_CENTRAL+CLOUD";
            default: return "UNKNOWN";
        }
    }

    /** Parse raw AD structures: [length][type][data...] */
    private void parseAdStructures(StringBuilder sb, byte[] raw) {
        int offset = 0;
        while (offset < raw.length) {
            int len = raw[offset] & 0xFF;
            if (len == 0) break;
            if (offset + len >= raw.length) break;

            int type = raw[offset + 1] & 0xFF;
            byte[] data = new byte[len - 1];
            System.arraycopy(raw, offset + 2, data, 0, Math.min(data.length, raw.length - offset - 2));

            sb.append("  [type=0x").append(String.format("%02X", type))
                    .append(" len=").append(len)
                    .append("] ").append(describeAdType(type))
                    .append(": ").append(bytesToHex(data)).append("\n");

            offset += len + 1;
        }
    }

    private String describeAdType(int type) {
        switch (type) {
            case 0x01: return "Flags";
            case 0x06: return "Incomplete 128-bit UUIDs";
            case 0x07: return "Complete 128-bit UUIDs";
            case 0x08: return "Shortened Name";
            case 0x09: return "Complete Name";
            case 0xFF: return "Manufacturer Data";
            default: return "Type " + type;
        }
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < bytes.length; i++) {
            if (i > 0 && i % 16 == 0) sb.append("\n  ");
            else if (i > 0) sb.append(" ");
            sb.append(String.format("%02X", bytes[i]));
        }
        return sb.toString();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == 1) {
            for (int r : results) {
                if (r != PackageManager.PERMISSION_GRANTED) {
                    tvStatus.setText("BLE permissions denied");
                    return;
                }
            }
            startScan();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopScan();
    }
}
