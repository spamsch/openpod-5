/*
 * Omnipod BLE Advertiser — ESP32-S3 (Heltec WiFi Kit 32 V3)
 *
 * Tests different BLE advertising configurations to discover what
 * the Omnipod 5 app's scan filter expects.
 *
 * Serial commands:
 *   legacy      — switch to legacy ADV_IND advertising
 *   extended    — switch to extended advertising (BLE 5.0)
 *   scenario N  — switch to scenario N (0-9)
 *   status      — print current config
 *   restart     — restart advertising with current config
 *   list        — list all scenarios
 */

#include <stdio.h>
#include <string.h>
#include <stdbool.h>
#include <stdlib.h>

#include "esp_log.h"
#include "nvs_flash.h"

#include "nimble/nimble_port.h"
#include "nimble/nimble_port_freertos.h"
#include "host/ble_hs.h"
#include "host/util/util.h"
#include "services/gap/ble_svc_gap.h"
#include "services/gatt/ble_svc_gatt.h"

static const char *TAG = "pod_adv";

/* ------------------------------------------------------------------ */
/* Scenarios                                                           */
/*                                                                     */
/* Each scenario is a complete ADV_IND + SCAN_RSP payload to test a    */
/* specific theory about what the Omnipod app's scan filter expects.   */
/* ------------------------------------------------------------------ */

typedef struct {
    const char    *name;
    const char    *description;
    const uint8_t *adv_data;
    size_t         adv_len;
    const uint8_t *scan_rsp;
    size_t         scan_rsp_len;
} scenario_t;

/* --- Scenario 0: Emulator baseline (128-bit UUID type 0x06) --- */
static const uint8_t S0_ADV[] = {
    0x02, 0x01, 0x06,                                       /* Flags */
    0x11, 0x06,                                              /* Incomplete 128-bit UUID */
    0x00, 0xFE, 0xFF, 0xFF, 0xFF, 0x0A, 0x00, 0x73,        /* ce1f923d-c539-48ea-7300-0afffffffe00 LE */
    0xEA, 0x48, 0x39, 0xC5, 0x3D, 0x92, 0x1F, 0xCE,
    0x08, 0xFF, 0x60, 0x03, 0x00, 0x02, 0x10, 0x00, 0x00,  /* Mfr: Insulet 0x0360 */
};
static const uint8_t S0_RSP[] = {
    0x1D, 0x09,                                              /* Complete Local Name */
    0x41, 0x50, 0x20, 0x30, 0x30, 0x30, 0x30, 0x45,         /* "AP 0000E001 0F00EMU100000001" */
    0x30, 0x30, 0x31, 0x20, 0x30, 0x46, 0x30, 0x30,
    0x45, 0x4D, 0x55, 0x31, 0x30, 0x30, 0x30, 0x30,
    0x30, 0x30, 0x30, 0x31,
};

/* --- Scenario 1: Real pod bytes (captured, pod_id=0x429F) --- */
static const uint8_t S1_ADV[] = {
    0x02, 0x01, 0x06,
    0x11, 0x06,
    0x00, 0x9C, 0x42, 0x00, 0x00, 0x0A, 0x00, 0x73,
    0xEA, 0x48, 0x39, 0xC5, 0x3D, 0x92, 0x1F, 0xCE,
    0x08, 0xFF, 0x60, 0x03, 0x00, 0x02, 0x30, 0x00, 0x00,
};
static const uint8_t S1_RSP[] = {
    0x1D, 0x09,
    0x41, 0x50, 0x20, 0x30, 0x30, 0x30, 0x30, 0x34,
    0x32, 0x39, 0x46, 0x20, 0x30, 0x45, 0x39, 0x39,
    0x38, 0x34, 0x42, 0x31, 0x30, 0x30, 0x30, 0x38,
    0x39, 0x31, 0x38, 0x44,
};

/* --- Scenario 2: 16-bit UUID 0x4024 (GATT service) instead of 128-bit --- */
/* Theory: app scans for the 16-bit GATT service UUID */
static const uint8_t S2_ADV[] = {
    0x02, 0x01, 0x06,                                       /* Flags */
    0x03, 0x02, 0x24, 0x40,                                 /* Incomplete 16-bit UUID: 0x4024 LE */
    0x08, 0xFF, 0x60, 0x03, 0x00, 0x02, 0x10, 0x00, 0x00,  /* Mfr: Insulet 0x0360 */
};
/* Same scan response for all */

/* --- Scenario 3: 16-bit UUID 0x4024 + 128-bit scan UUID (both) --- */
/* Theory: app expects both UUIDs */
static const uint8_t S3_ADV[] = {
    0x02, 0x01, 0x06,                                       /* Flags */
    0x03, 0x02, 0x24, 0x40,                                 /* Incomplete 16-bit: 0x4024 */
    0x11, 0x06,                                              /* Incomplete 128-bit */
    0x00, 0xFE, 0xFF, 0xFF, 0xFF, 0x0A, 0x00, 0x73,
    0xEA, 0x48, 0x39, 0xC5, 0x3D, 0x92, 0x1F, 0xCE,
    /* No room for mfr data in 31 bytes, put in scan response */
};
static const uint8_t S3_RSP[] = {
    0x08, 0xFF, 0x60, 0x03, 0x00, 0x02, 0x10, 0x00, 0x00,  /* Mfr: Insulet */
    0x1D, 0x09,                                              /* Name */
    0x41, 0x50, 0x20, 0x30, 0x30, 0x30, 0x30, 0x45,
    0x30, 0x30, 0x31, 0x20, 0x30, 0x46, 0x30, 0x30,
    0x45, 0x4D, 0x55, 0x31, 0x30, 0x30,
};

/* --- Scenario 4: Complete 128-bit UUID (type 0x07 vs 0x06) --- */
/* Theory: app filters on Complete List, not Incomplete */
static const uint8_t S4_ADV[] = {
    0x02, 0x01, 0x06,                                       /* Flags */
    0x11, 0x07,                                              /* COMPLETE 128-bit UUID list */
    0x00, 0xFE, 0xFF, 0xFF, 0xFF, 0x0A, 0x00, 0x73,
    0xEA, 0x48, 0x39, 0xC5, 0x3D, 0x92, 0x1F, 0xCE,
    0x08, 0xFF, 0x60, 0x03, 0x00, 0x02, 0x10, 0x00, 0x00,
};

/* --- Scenario 5: 128-bit UUID in big-endian (reversed byte order) --- */
/* Theory: UUID byte order is wrong */
static const uint8_t S5_ADV[] = {
    0x02, 0x01, 0x06,                                       /* Flags */
    0x11, 0x06,                                              /* Incomplete 128-bit UUID */
    0xCE, 0x1F, 0x92, 0x3D, 0xC5, 0x39, 0x48, 0xEA,        /* ce1f923d-c539-48ea-7300-0afffffffe00 BE */
    0x73, 0x00, 0x0A, 0xFF, 0xFF, 0xFF, 0xFE, 0x00,
    0x08, 0xFF, 0x60, 0x03, 0x00, 0x02, 0x10, 0x00, 0x00,
};

/* --- Scenario 6: Full GATT UUID as 128-bit (1a7e4024-e3ed-4464-8b7e-751e03d0dc5f) --- */
/* Theory: app uses full 128-bit GATT service UUID in scan filter */
static const uint8_t S6_ADV[] = {
    0x02, 0x01, 0x06,                                       /* Flags */
    0x11, 0x06,                                              /* Incomplete 128-bit UUID */
    0x5F, 0xDC, 0xD0, 0x03, 0x1E, 0x75, 0x7E, 0x8B,        /* 1a7e4024-e3ed-4464-8b7e-751e03d0dc5f LE */
    0x64, 0x44, 0xED, 0xE3, 0x24, 0x40, 0x7E, 0x1A,
    0x08, 0xFF, 0x60, 0x03, 0x00, 0x02, 0x10, 0x00, 0x00,
};

/* --- Scenario 7: ScanFilter on manufacturer data only (no UUID) --- */
/* Theory: app uses mfr data filter (company=0x0360) not UUID filter */
static const uint8_t S7_ADV[] = {
    0x02, 0x01, 0x06,                                       /* Flags */
    0x08, 0xFF, 0x60, 0x03, 0x00, 0x02, 0x10, 0x00, 0x00,  /* Mfr: Insulet 0x0360 */
};

/* --- Scenario 8: 16-bit UUID 0x4024 as Complete List (type 0x03) --- */
/* Theory: app uses Complete 16-bit list */
static const uint8_t S8_ADV[] = {
    0x02, 0x01, 0x06,                                       /* Flags */
    0x03, 0x03, 0x24, 0x40,                                 /* Complete 16-bit UUID: 0x4024 LE */
    0x08, 0xFF, 0x60, 0x03, 0x00, 0x02, 0x10, 0x00, 0x00,  /* Mfr: Insulet */
};

/* --- Scenario 9: Real pod + GATT 16-bit UUID 0x4024 --- */
/* Theory: real pods include 0x4024 but our capture missed it */
static const uint8_t S9_ADV[] = {
    0x02, 0x01, 0x06,                                       /* Flags */
    0x03, 0x03, 0x24, 0x40,                                 /* Complete 16-bit: 0x4024 */
    0x11, 0x06,                                              /* Incomplete 128-bit */
    0x00, 0x9C, 0x42, 0x00, 0x00, 0x0A, 0x00, 0x73,         /* Real pod UUID */
    0xEA, 0x48, 0x39, 0xC5, 0x3D, 0x92, 0x1F, 0xCE,
    /* 30 bytes total — no room for mfr in ADV */
};
static const uint8_t S9_RSP[] = {
    0x08, 0xFF, 0x60, 0x03, 0x00, 0x02, 0x30, 0x00, 0x00,  /* Mfr: real pod */
    0x14, 0x09,                                              /* Name (shorter) */
    0x41, 0x50, 0x20, 0x30, 0x30, 0x30, 0x30, 0x34,         /* "AP 0000429F 0E99" */
    0x32, 0x39, 0x46, 0x20, 0x30, 0x45, 0x39, 0x39,
    0x38, 0x34, 0x42,
};

/* --- Scenarios 10-12: Unpaired UUID variants 01, 02, 03 --- */
/* Theory: app only scans for a specific UUID index, not all 4 */
static const uint8_t S10_ADV[] = {
    0x02, 0x01, 0x06,
    0x11, 0x06,
    0x01, 0xFE, 0xFF, 0xFF, 0xFF, 0x0A, 0x00, 0x73,        /* ...fffffffe01 */
    0xEA, 0x48, 0x39, 0xC5, 0x3D, 0x92, 0x1F, 0xCE,
    0x08, 0xFF, 0x60, 0x03, 0x00, 0x02, 0x10, 0x00, 0x00,
};
static const uint8_t S11_ADV[] = {
    0x02, 0x01, 0x06,
    0x11, 0x06,
    0x02, 0xFE, 0xFF, 0xFF, 0xFF, 0x0A, 0x00, 0x73,        /* ...fffffffe02 */
    0xEA, 0x48, 0x39, 0xC5, 0x3D, 0x92, 0x1F, 0xCE,
    0x08, 0xFF, 0x60, 0x03, 0x00, 0x02, 0x10, 0x00, 0x00,
};
static const uint8_t S12_ADV[] = {
    0x02, 0x01, 0x06,
    0x11, 0x06,
    0x03, 0xFE, 0xFF, 0xFF, 0xFF, 0x0A, 0x00, 0x73,        /* ...fffffffe03 */
    0xEA, 0x48, 0x39, 0xC5, 0x3D, 0x92, 0x1F, 0xCE,
    0x08, 0xFF, 0x60, 0x03, 0x00, 0x02, 0x10, 0x00, 0x00,
};

/* ------------------------------------------------------------------ */
/* Scenario table                                                      */
/* ------------------------------------------------------------------ */

#define SCENARIO(n, name, desc, adv, rsp) \
    { name, desc, adv, sizeof(adv), rsp, sizeof(rsp) }

#define SCENARIO_NO_RSP(n, name, desc, adv) \
    { name, desc, adv, sizeof(adv), S0_RSP, sizeof(S0_RSP) }

static const scenario_t SCENARIOS[] = {
    SCENARIO(0, "emu-baseline",     "Emulator: 128-bit UUID (type 0x06) + mfr",   S0_ADV, S0_RSP),
    SCENARIO(1, "real-pod",         "Real pod captured bytes (pod_id=0x429F)",     S1_ADV, S1_RSP),
    SCENARIO_NO_RSP(2, "uuid16-4024",      "16-bit UUID 0x4024 only + mfr",              S2_ADV),
    SCENARIO(3, "uuid16+uuid128",   "16-bit 0x4024 + 128-bit scan UUID",          S3_ADV, S3_RSP),
    SCENARIO_NO_RSP(4, "uuid128-complete",  "128-bit UUID type 0x07 (Complete List)",      S4_ADV),
    SCENARIO_NO_RSP(5, "uuid128-be",        "128-bit UUID big-endian (reversed)",          S5_ADV),
    SCENARIO_NO_RSP(6, "gatt-uuid128",      "GATT service UUID 1a7e4024... (128-bit LE)", S6_ADV),
    SCENARIO_NO_RSP(7, "mfr-only",          "Manufacturer data only (no UUID)",            S7_ADV),
    SCENARIO_NO_RSP(8, "uuid16-complete",   "16-bit UUID 0x4024 type 0x03 (Complete)",     S8_ADV),
    SCENARIO(9, "real+uuid16",      "Real pod + 16-bit 0x4024",                    S9_ADV, S9_RSP),
    SCENARIO_NO_RSP(10, "unpaired-01",     "Unpaired UUID ...fffffffe01",                 S10_ADV),
    SCENARIO_NO_RSP(11, "unpaired-02",     "Unpaired UUID ...fffffffe02",                 S11_ADV),
    SCENARIO_NO_RSP(12, "unpaired-03",     "Unpaired UUID ...fffffffe03",                 S12_ADV),
};

#define NUM_SCENARIOS (sizeof(SCENARIOS) / sizeof(SCENARIOS[0]))

/* ------------------------------------------------------------------ */
/* State                                                               */
/* ------------------------------------------------------------------ */

static bool     g_use_extended = false;
static int      g_scenario     = 0;
static bool     g_advertising  = false;
static uint8_t  g_own_addr_type;

#define EXT_ADV_INSTANCE 0

/* ------------------------------------------------------------------ */
/* GAP event handler                                                   */
/* ------------------------------------------------------------------ */

static int gap_event_handler(struct ble_gap_event *event, void *arg);

static void
start_advertising(void)
{
    int rc;
    const scenario_t *sc = &SCENARIOS[g_scenario];

    /* Stop any current advertising first */
    if (g_advertising) {
        ble_gap_ext_adv_stop(EXT_ADV_INSTANCE);
        ble_gap_ext_adv_remove(EXT_ADV_INSTANCE);
        g_advertising = false;
    }

    ESP_LOGI(TAG, "--- Scenario %d: %s ---", g_scenario, sc->name);
    ESP_LOGI(TAG, "    %s", sc->description);

    struct ble_gap_ext_adv_params params = {0};
    params.own_addr_type = g_own_addr_type;
    params.itvl_min      = BLE_GAP_ADV_ITVL_MS(100);
    params.itvl_max      = BLE_GAP_ADV_ITVL_MS(100);
    params.primary_phy   = BLE_HCI_LE_PHY_1M;
    params.secondary_phy = BLE_HCI_LE_PHY_1M;
    params.sid           = EXT_ADV_INSTANCE;

    if (g_use_extended) {
        params.connectable = 1;
        params.scannable   = 0;
        params.legacy_pdu  = 0;
    } else {
        params.connectable = 1;
        params.scannable   = 1;
        params.legacy_pdu  = 1;
    }

    rc = ble_gap_ext_adv_configure(EXT_ADV_INSTANCE, &params, NULL,
                                   gap_event_handler, NULL);
    if (rc != 0) {
        ESP_LOGE(TAG, "ext_adv_configure failed: %d", rc);
        return;
    }

    /* Set advertising data */
    struct os_mbuf *adv_mbuf = os_msys_get_pkthdr(sc->adv_len, 0);
    if (!adv_mbuf) {
        ESP_LOGE(TAG, "Failed to allocate adv mbuf");
        return;
    }
    os_mbuf_append(adv_mbuf, sc->adv_data, sc->adv_len);
    rc = ble_gap_ext_adv_set_data(EXT_ADV_INSTANCE, adv_mbuf);
    if (rc != 0) {
        ESP_LOGE(TAG, "ext_adv_set_data failed: %d", rc);
        return;
    }

    /* Set scan response (only for legacy/scannable) */
    if (params.scannable && sc->scan_rsp_len > 0) {
        struct os_mbuf *rsp_mbuf = os_msys_get_pkthdr(sc->scan_rsp_len, 0);
        if (rsp_mbuf) {
            os_mbuf_append(rsp_mbuf, sc->scan_rsp, sc->scan_rsp_len);
            rc = ble_gap_ext_adv_rsp_set_data(EXT_ADV_INSTANCE, rsp_mbuf);
            if (rc != 0) {
                ESP_LOGW(TAG, "ext_adv_rsp_set_data failed: %d", rc);
            }
        }
    }

    rc = ble_gap_ext_adv_start(EXT_ADV_INSTANCE, 0, 0);
    if (rc != 0) {
        ESP_LOGE(TAG, "ext_adv_start failed: %d", rc);
        return;
    }

    g_advertising = true;

    ESP_LOGI(TAG, "=== %s advertising started ===",
             g_use_extended ? "EXTENDED" : "LEGACY");

    /* Hex dump */
    printf("ADV (%zu bytes): ", sc->adv_len);
    for (size_t i = 0; i < sc->adv_len; i++) printf("%02X", sc->adv_data[i]);
    printf("\n");
    printf("RSP (%zu bytes): ", sc->scan_rsp_len);
    for (size_t i = 0; i < sc->scan_rsp_len; i++) printf("%02X", sc->scan_rsp[i]);
    printf("\n");
}

static int
gap_event_handler(struct ble_gap_event *event, void *arg)
{
    switch (event->type) {
    case BLE_GAP_EVENT_CONNECT:
        ESP_LOGI(TAG, "****** CONNECTION %s ******  handle=%d, scenario=%d (%s)",
                 event->connect.status == 0 ? "ESTABLISHED" : "FAILED",
                 event->connect.conn_handle,
                 g_scenario, SCENARIOS[g_scenario].name);
        if (event->connect.status == 0) {
            struct ble_gap_conn_desc desc;
            ble_gap_conn_find(event->connect.conn_handle, &desc);
            ESP_LOGI(TAG, "  Peer: %02X:%02X:%02X:%02X:%02X:%02X (type=%d)",
                     desc.peer_ota_addr.val[5], desc.peer_ota_addr.val[4],
                     desc.peer_ota_addr.val[3], desc.peer_ota_addr.val[2],
                     desc.peer_ota_addr.val[1], desc.peer_ota_addr.val[0],
                     desc.peer_ota_addr.type);
            g_advertising = false;
        } else {
            start_advertising();
        }
        break;

    case BLE_GAP_EVENT_DISCONNECT:
        ESP_LOGI(TAG, "*** DISCONNECTED *** reason=%d", event->disconnect.reason);
        start_advertising();
        break;

    case BLE_GAP_EVENT_ADV_COMPLETE:
        ESP_LOGI(TAG, "Advertising complete");
        g_advertising = false;
        break;

    default:
        break;
    }
    return 0;
}

/* ------------------------------------------------------------------ */
/* NimBLE host sync callback                                           */
/* ------------------------------------------------------------------ */

static void
on_nimble_sync(void)
{
    int rc;

    rc = ble_hs_util_ensure_addr(0);
    if (rc != 0) { ESP_LOGE(TAG, "ensure_addr failed: %d", rc); return; }

    rc = ble_hs_id_infer_auto(0, &g_own_addr_type);
    if (rc != 0) { ESP_LOGE(TAG, "infer_auto failed: %d", rc); return; }

    uint8_t addr[6];
    ble_hs_id_copy_addr(g_own_addr_type, addr, NULL);
    ESP_LOGI(TAG, "BLE address: %02X:%02X:%02X:%02X:%02X:%02X (type=%d)",
             addr[5], addr[4], addr[3], addr[2], addr[1], addr[0],
             g_own_addr_type);

    ESP_LOGI(TAG, "NimBLE synced — starting scenario %d", g_scenario);
    start_advertising();
}

static void on_nimble_reset(int reason) {
    ESP_LOGW(TAG, "NimBLE reset: reason=%d", reason);
}

static void nimble_host_task(void *param) {
    ESP_LOGI(TAG, "NimBLE host task started");
    nimble_port_run();
    nimble_port_freertos_deinit();
}

/* ------------------------------------------------------------------ */
/* Serial command handler                                              */
/* ------------------------------------------------------------------ */

static void
print_scenarios(void)
{
    printf("\n");
    for (int i = 0; i < (int)NUM_SCENARIOS; i++) {
        printf("  %d: %-20s  %s\n", i, SCENARIOS[i].name, SCENARIOS[i].description);
    }
    printf("\n");
}

static void
serial_task(void *param)
{
    char line[64];
    int  pos = 0;

    vTaskDelay(pdMS_TO_TICKS(2000));

    ESP_LOGI(TAG, "Commands: scenario N | legacy | extended | status | list | restart");
    print_scenarios();

    while (1) {
        int c = fgetc(stdin);
        if (c == EOF) {
            vTaskDelay(pdMS_TO_TICKS(50));
            continue;
        }
        if (c == '\r' || c == '\n') {
            if (pos == 0) continue;  /* ignore empty lines */
            line[pos] = 0;
            pos = 0;
            printf("\n");
        } else {
            if (pos < (int)sizeof(line) - 1) {
                line[pos++] = (char)c;
                fputc(c, stdout);  /* echo */
            }
            continue;  /* keep reading until Enter */
        }

        if (strncmp(line, "scenario ", 9) == 0) {
            int n = atoi(line + 9);
            if (n >= 0 && n < (int)NUM_SCENARIOS) {
                g_scenario = n;
                ESP_LOGI(TAG, "Switched to scenario %d: %s", n, SCENARIOS[n].name);
                start_advertising();
            } else {
                ESP_LOGW(TAG, "Invalid scenario %d (valid: 0-%d)", n, (int)NUM_SCENARIOS - 1);
            }

        } else if (strcmp(line, "legacy") == 0) {
            g_use_extended = false;
            ESP_LOGI(TAG, "Switched to LEGACY mode");
            start_advertising();

        } else if (strcmp(line, "extended") == 0) {
            g_use_extended = true;
            ESP_LOGI(TAG, "Switched to EXTENDED mode");
            start_advertising();

        } else if (strcmp(line, "status") == 0) {
            ESP_LOGI(TAG, "Scenario: %d (%s) | Mode: %s | Advertising: %s",
                     g_scenario, SCENARIOS[g_scenario].name,
                     g_use_extended ? "EXTENDED" : "LEGACY",
                     g_advertising ? "YES" : "NO");

        } else if (strcmp(line, "list") == 0) {
            print_scenarios();

        } else if (strcmp(line, "restart") == 0) {
            start_advertising();

        } else if (strcmp(line, "next") == 0) {
            g_scenario = (g_scenario + 1) % NUM_SCENARIOS;
            ESP_LOGI(TAG, "Next -> scenario %d: %s", g_scenario, SCENARIOS[g_scenario].name);
            start_advertising();

        } else if (strlen(line) > 0) {
            ESP_LOGW(TAG, "Unknown: '%s'", line);
            ESP_LOGI(TAG, "Commands: scenario N | next | legacy | extended | status | list | restart");
        }
    }
}

/* ------------------------------------------------------------------ */
/* Main                                                                */
/* ------------------------------------------------------------------ */

void
app_main(void)
{
    ESP_LOGI(TAG, "=============================================");
    ESP_LOGI(TAG, "  Omnipod BLE Advertiser — Scenario Tester");
    ESP_LOGI(TAG, "  %d scenarios available", (int)NUM_SCENARIOS);
    ESP_LOGI(TAG, "=============================================");

    esp_err_t ret = nvs_flash_init();
    if (ret == ESP_ERR_NVS_NO_FREE_PAGES ||
        ret == ESP_ERR_NVS_NEW_VERSION_FOUND) {
        nvs_flash_erase();
        nvs_flash_init();
    }

    ret = nimble_port_init();
    if (ret != ESP_OK) {
        ESP_LOGE(TAG, "nimble_port_init failed: %d", ret);
        return;
    }

    ble_hs_cfg.reset_cb        = on_nimble_reset;
    ble_hs_cfg.sync_cb         = on_nimble_sync;
    ble_hs_cfg.store_status_cb = ble_store_util_status_rr;

    ble_svc_gap_init();
    ble_svc_gatt_init();
    ble_svc_gap_device_name_set("AP 0000E001 0F00EMU100000001");

    nimble_port_freertos_init(nimble_host_task);
    xTaskCreate(serial_task, "serial", 4096, NULL, 5, NULL);
}
