#include "sniffer.h"
#include "hci.h"

#include <stdio.h>
#include <string.h>
#include <ctype.h>
#include <esp_timer.h>
#include <esp_bt.h>
#include <esp_log.h>

static const char *TAG = "sniffer";

/* Known Omnipod service UUID prefix in advertising data (little-endian 128-bit) */
static const uint8_t OMNIPOD_UUID_PREFIX[] = {
    /* Reversed bytes of ce1f923d-c539-48ea-7300-0a... */
    0x00, 0xfe, 0xff, 0xff, 0x0a, 0x00, 0x73, 0xea,
    0x48, 0x39, 0xc5, 0x3d, 0x92, 0x1f, 0xce
};

/* Omnipod GATT service UUID prefix (1a7e4024...) in advertising data */
static const uint8_t OMNIPOD_GATT_PREFIX[] = {
    0x5f, 0xdc, 0xd0, 0x03, 0x1e, 0x75, 0x7e, 0x8b,
    0x64, 0x44, 0xed, 0xe3, 0x24, 0x40, 0x7e, 0x1a
};

static void send_hci(const uint8_t *buf, uint16_t len)
{
    esp_vhci_host_send_packet((uint8_t *)buf, len);
}

static float elapsed_sec(const sniffer_state_t *state)
{
    return (float)(esp_timer_get_time() - state->start_time_us) / 1000000.0f;
}

static void print_addr(const uint8_t addr[6])
{
    /* BLE addresses are stored little-endian; print MSB first */
    printf("%02X:%02X:%02X:%02X:%02X:%02X",
           addr[5], addr[4], addr[3], addr[2], addr[1], addr[0]);
}

static bool is_omnipod_adv_data(const uint8_t *data, uint8_t len)
{
    /* Search for Insulet manufacturer ID (0x0360) in manufacturer-specific AD structures */
    uint8_t pos = 0;
    while (pos + 1 < len) {
        uint8_t ad_len = data[pos];
        if (ad_len == 0 || pos + 1 + ad_len > len) break;

        uint8_t ad_type = data[pos + 1];

        /* AD type 0xFF = Manufacturer Specific Data */
        if (ad_type == 0xFF && ad_len >= 3) {
            uint16_t company_id = data[pos + 2] | (data[pos + 3] << 8);
            if (company_id == INSULET_COMPANY_ID) {
                return true;
            }
        }

        /* AD type 0x08/0x09 = Local Name — check for known Omnipod names */
        if ((ad_type == 0x08 || ad_type == 0x09) && ad_len >= 5) {
            const char *name = (const char *)&data[pos + 2];
            int name_len = ad_len - 1;
            if ((name_len >= 4 && strncasecmp(name, "DASH", 4) == 0) ||
                (name_len >= 7 && strncasecmp(name, "Omnipod", 7) == 0) ||
                (name_len >= 7 && strncasecmp(name, "InPlay ", 7) == 0)) {
                return true;
            }
        }

        /* AD type 0x02 or 0x03 = 16-bit service UUIDs */
        if ((ad_type == 0x02 || ad_type == 0x03) && ad_len >= 3) {
            /* Check each 16-bit UUID in the list */
            for (int i = 0; i + 1 < ad_len - 1; i += 2) {
                uint16_t uuid16 = data[pos + 2 + i] | (data[pos + 3 + i] << 8);
                if (uuid16 == 0x4024) {  /* Omnipod GATT service short UUID */
                    return true;
                }
            }
        }

        /* AD type 0x06 or 0x07 = 128-bit service UUIDs */
        if ((ad_type == 0x06 || ad_type == 0x07) && ad_len >= 17) {
            const uint8_t *uuid = &data[pos + 2];
            if (memcmp(uuid, OMNIPOD_UUID_PREFIX, sizeof(OMNIPOD_UUID_PREFIX)) == 0 ||
                memcmp(uuid, OMNIPOD_GATT_PREFIX, sizeof(OMNIPOD_GATT_PREFIX)) == 0) {
                return true;
            }
        }

        pos += 1 + ad_len;
    }
    return false;
}

static void print_adv_data_summary(const uint8_t *data, uint8_t len)
{
    uint8_t pos = 0;
    while (pos + 1 < len) {
        uint8_t ad_len = data[pos];
        if (ad_len == 0 || pos + 1 + ad_len > len) break;

        uint8_t ad_type = data[pos + 1];

        switch (ad_type) {
        case 0x08: /* Shortened Local Name */
        case 0x09: /* Complete Local Name */
            printf(" name=\"");
            for (int i = 0; i < ad_len - 1 && pos + 2 + i < len; i++) {
                char c = data[pos + 2 + i];
                putchar(isprint(c) ? c : '.');
            }
            printf("\"");
            break;

        case 0xFF: /* Manufacturer Specific Data */
            if (ad_len >= 3) {
                uint16_t cid = data[pos + 2] | (data[pos + 3] << 8);
                printf(" mfr=%04X:", cid);
                for (int i = 2; i < ad_len - 1 && pos + 2 + i < len; i++) {
                    printf("%02X", data[pos + 2 + i]);
                }
            }
            break;

        case 0x06: /* Incomplete 128-bit UUIDs */
        case 0x07: /* Complete 128-bit UUIDs */
            if (ad_len >= 17) {
                printf(" uuid=");
                const uint8_t *u = &data[pos + 2];
                printf("%02x%02x%02x%02x-%02x%02x-%02x%02x-%02x%02x-%02x%02x%02x%02x%02x%02x",
                       u[15],u[14],u[13],u[12],u[11],u[10],u[9],u[8],
                       u[7],u[6],u[5],u[4],u[3],u[2],u[1],u[0]);
            }
            break;

        case 0x02: /* Incomplete 16-bit UUIDs */
        case 0x03: /* Complete 16-bit UUIDs */
            if (ad_len >= 3) {
                uint16_t uuid16 = data[pos + 2] | (data[pos + 3] << 8);
                printf(" uuid16=%04X", uuid16);
            }
            break;

        default:
            break;
        }

        pos += 1 + ad_len;
    }
}

void sniffer_init(sniffer_state_t *state)
{
    memset(state, 0, sizeof(*state));
    state->start_time_us = esp_timer_get_time();
}

void sniffer_process_report(sniffer_state_t *state, const adv_report_t *report)
{
    state->pkt_total++;

    bool is_omnipod = is_omnipod_adv_data(report->data, report->data_len);
    bool is_directed = (report->event_type == ADV_DIRECT_IND);

    if (is_directed) state->pkt_directed++;
    if (is_omnipod) state->pkt_omnipod++;

    /* Periodic heartbeat every 10 packets so user knows sniffer is alive */
    if (state->pkt_total % 10 == 0) {
        printf("[%8.3f] --- %lu total BLE pkts, %lu omnipod, %lu directed ---\n",
               elapsed_sec(state),
               (unsigned long)state->pkt_total,
               (unsigned long)state->pkt_omnipod,
               (unsigned long)state->pkt_directed);
    }

    /* Print prefix */
    if (is_omnipod || is_directed) {
        printf("\n*** OMNIPOD *** ");
    }

    /* Timestamp and type */
    printf("[%8.3f] %s addr=", elapsed_sec(state), adv_type_str(report->event_type));
    print_addr(report->addr);

    /* For directed advertisements, show the target */
    if (is_directed) {
        printf(" -> target=");
        print_addr(report->direct_addr);
    }

    /* RSSI */
    printf(" rssi=%d", report->rssi);

    /* Address type */
    printf(" %s", report->addr_type == ADDR_TYPE_RANDOM ? "(random)" : "(public)");

    /* Advertising data summary */
    if (report->data_len > 0) {
        print_adv_data_summary(report->data, report->data_len);
    }

    /* Raw hex for Omnipod packets */
    if (is_omnipod || is_directed) {
        if (report->data_len > 0) {
            printf("\n  raw[%d]: ", report->data_len);
            for (int i = 0; i < report->data_len; i++) {
                printf("%02X ", report->data[i]);
            }
        }
        printf("\n");
    }

    printf("\n");
    fflush(stdout);
}

void sniffer_start_scan(sniffer_state_t *state, uint8_t filter_policy)
{
    uint8_t buf[32];
    uint16_t len;

    /* Stop any existing scan first */
    len = hci_build_le_set_scan_enable(buf, 0, 0);
    send_hci(buf, len);
    vTaskDelay(pdMS_TO_TICKS(300));

    /* Try extended filter policy first, fall back to basic if rejected.
     * filter_policy 0x02 requires LE Privacy which the ESP32 controller
     * may not support without resolving list setup. Always use 0x00 for
     * reliable scanning — it sees all undirected advertisements. */
    uint8_t actual_policy = SCAN_FILTER_BASIC;  /* 0x00 — always works */

    /* Set scan parameters:
     * - Passive scan (don't send SCAN_REQ)
     * - Interval: 0x0010 (10ms) — aggressive scanning
     * - Window:   0x0010 (10ms) — 100% duty cycle
     * - Own addr: public */
    len = hci_build_le_set_scan_params(buf,
            0x01,            /* active scan — also elicits SCAN_RSP */
            0x0010,          /* interval: 10ms */
            0x0010,          /* window: 10ms (100% duty cycle) */
            ADDR_TYPE_PUBLIC,
            actual_policy);
    send_hci(buf, len);
    vTaskDelay(pdMS_TO_TICKS(300));

    /* Enable scanning, no duplicate filtering */
    len = hci_build_le_set_scan_enable(buf, 1, 0);
    send_hci(buf, len);

    state->scanning = true;
    state->start_time_us = esp_timer_get_time();

    ESP_LOGI(TAG, "Scan started (filter_policy=0x%02X, %s mode)",
             filter_policy,
             state->spoofing ? "SPOOF" : "DISCOVERY");
}

void sniffer_stop_scan(sniffer_state_t *state)
{
    uint8_t buf[16];
    uint16_t len = hci_build_le_set_scan_enable(buf, 0, 0);
    send_hci(buf, len);

    state->scanning = false;
    ESP_LOGI(TAG, "Scan stopped");
    sniffer_print_status(state);
}

void sniffer_spoof_addr(sniffer_state_t *state, const uint8_t addr[6])
{
    uint8_t buf[32];
    uint16_t len;

    /* Stop scanning first */
    if (state->scanning) {
        len = hci_build_le_set_scan_enable(buf, 0, 0);
        send_hci(buf, len);
        vTaskDelay(pdMS_TO_TICKS(200));
    }

    /* Set BD_ADDR via vendor command */
    len = hci_build_vendor_set_bd_addr(buf, addr);
    send_hci(buf, len);
    vTaskDelay(pdMS_TO_TICKS(200));

    memcpy(state->spoof_addr, addr, 6);
    state->spoofing = true;

    ESP_LOGI(TAG, "MAC spoofed to %02X:%02X:%02X:%02X:%02X:%02X",
             addr[5], addr[4], addr[3], addr[2], addr[1], addr[0]);

    /* Restart scan — now using basic filter (0x00) since directed ads
     * targeted at this address will pass the filter naturally */
    sniffer_start_scan(state, SCAN_FILTER_BASIC);
}

void sniffer_reset_addr(sniffer_state_t *state)
{
    state->spoofing = false;
    memset(state->spoof_addr, 0, 6);

    ESP_LOGI(TAG, "Spoof disabled — restart the device to restore original MAC");
    ESP_LOGI(TAG, "Restarting scan in discovery mode...");

    sniffer_start_scan(state, SCAN_FILTER_BASIC);
}

void sniffer_print_status(const sniffer_state_t *state)
{
    printf("\n=== Sniffer Status ===\n");
    printf("  Mode:     %s\n", state->spoofing ? "SPOOF" : "DISCOVERY");
    printf("  Scanning: %s\n", state->scanning ? "YES" : "NO");
    if (state->spoofing) {
        printf("  Spoofed:  ");
        print_addr(state->spoof_addr);
        printf("\n");
    }
    printf("  Packets:  %lu total, %lu omnipod, %lu directed\n",
           (unsigned long)state->pkt_total,
           (unsigned long)state->pkt_omnipod,
           (unsigned long)state->pkt_directed);
    printf("  Uptime:   %.1f sec\n", elapsed_sec(state));
    printf("======================\n\n");
}

static int parse_hex_addr(const char *str, uint8_t addr[6])
{
    /* Parse "AA:BB:CC:DD:EE:FF" into addr[6] in little-endian BLE order */
    unsigned int b[6];
    if (sscanf(str, "%02x:%02x:%02x:%02x:%02x:%02x",
               &b[0], &b[1], &b[2], &b[3], &b[4], &b[5]) != 6) {
        return -1;
    }
    /* Store in BLE order (LSB first) */
    for (int i = 0; i < 6; i++) {
        addr[5 - i] = (uint8_t)b[i];
    }
    return 0;
}

void sniffer_process_command(sniffer_state_t *state, const char *cmd)
{
    /* Skip whitespace */
    while (*cmd == ' ' || *cmd == '\t') cmd++;

    if (strncmp(cmd, "scan", 4) == 0) {
        sniffer_start_scan(state, state->spoofing ? SCAN_FILTER_BASIC : SCAN_FILTER_EXTENDED);
    }
    else if (strncmp(cmd, "stop", 4) == 0) {
        sniffer_stop_scan(state);
    }
    else if (strncmp(cmd, "spoof ", 6) == 0) {
        uint8_t addr[6];
        if (parse_hex_addr(cmd + 6, addr) == 0) {
            sniffer_spoof_addr(state, addr);
        } else {
            printf("Usage: spoof AA:BB:CC:DD:EE:FF\n");
        }
    }
    else if (strncmp(cmd, "reset", 5) == 0) {
        sniffer_reset_addr(state);
    }
    else if (strncmp(cmd, "status", 6) == 0) {
        sniffer_print_status(state);
    }
    else if (strncmp(cmd, "help", 4) == 0) {
        printf("\nCommands:\n");
        printf("  scan                     Start/restart scanning\n");
        printf("  stop                     Stop scanning\n");
        printf("  spoof AA:BB:CC:DD:EE:FF  Spoof MAC and capture directed ads\n");
        printf("  reset                    Disable spoof, return to discovery mode\n");
        printf("  status                   Show packet counters and mode\n");
        printf("  help                     This message\n\n");
    }
    else if (*cmd != '\0') {
        printf("Unknown command: %s (type 'help')\n", cmd);
    }
}
