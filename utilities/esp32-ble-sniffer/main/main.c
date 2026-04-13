#include <stdio.h>
#include <string.h>

#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "esp_bt.h"
#include "esp_log.h"
#include "esp_timer.h"
#include "nvs_flash.h"
#include "driver/uart.h"

#include "hci.h"
#include "sniffer.h"

static const char *TAG = "ble_sniffer";

static sniffer_state_t g_sniffer;

/* VHCI callback: controller ready to receive */
static bool vhci_ready = false;

static void controller_rcv_pkt_ready(void)
{
    vhci_ready = true;
}

/* Debug: count all callbacks to verify VHCI is delivering data */
static uint32_t g_cb_count = 0;

/* Helper: hex-dump an entire buffer */
static void hex_dump(const char *prefix, const uint8_t *buf, uint16_t len)
{
    printf("%s (%d bytes):", prefix, len);
    for (int i = 0; i < len; i++) {
        if (i % 16 == 0) printf("\n  %04X: ", i);
        printf("%02X ", buf[i]);
    }
    printf("\n");
    fflush(stdout);
}

/* VHCI callback: received HCI event from controller */
static int host_rcv_pkt(uint8_t *data, uint16_t len)
{
    g_cb_count++;

    if (len < 1) return 0;

    uint8_t pkt_type = data[0];

    /* === ACL data packet === */
    if (pkt_type == H4_TYPE_ACL && len >= 5) {
        uint16_t handle = (data[1] | (data[2] << 8)) & 0x0FFF;
        uint8_t pb_flag = (data[2] >> 4) & 0x03;
        uint8_t bc_flag = (data[2] >> 6) & 0x03;
        uint16_t acl_len = data[3] | (data[4] << 8);
        printf("\n=== HCI ACL [#%lu] handle=0x%04X PB=%d BC=%d acl_len=%d ===\n",
               (unsigned long)g_cb_count, handle, pb_flag, bc_flag, acl_len);
        hex_dump("  ACL payload", &data[5], len - 5);
        return 0;
    }

    /* === HCI Event packet === */
    uint8_t evt_code;
    uint8_t evt_len;
    int hdr_offset;

    if (pkt_type == H4_TYPE_EVENT && len >= 3) {
        /* H4 header present */
        evt_code = data[1];
        evt_len = data[2];
        hdr_offset = 3;
    } else if (len >= 2) {
        /* No H4 header — data[0] IS the event code */
        evt_code = data[0];
        evt_len = data[1];
        hdr_offset = 2;
    } else {
        hex_dump("HCI UNKNOWN", data, len);
        return 0;
    }

    /* Always log every HCI event with full hex dump */
    printf("\n--- HCI EVT [#%lu] code=0x%02X len=%d ---\n",
           (unsigned long)g_cb_count, evt_code, evt_len);
    hex_dump("  raw", data, len);

    if (evt_code == HCI_EVT_LE_META && evt_len > 0) {
        uint8_t sub_event = data[hdr_offset];
        printf("  LE Meta sub-event: 0x%02X\n", sub_event);

        if (sub_event == HCI_LE_ADV_REPORT) {
            adv_report_t reports[8];
            int count = hci_parse_adv_reports(&data[hdr_offset], evt_len, reports, 8);

            for (int i = 0; i < count; i++) {
                sniffer_process_report(&g_sniffer, &reports[i]);
            }
        } else {
            printf("  (LE sub-event 0x%02X not further decoded)\n", sub_event);
        }
    }
    else if (evt_code == HCI_EVT_COMMAND_COMPLETE) {
        if (evt_len >= 3) {
            uint16_t opcode = data[hdr_offset + 1] | (data[hdr_offset + 2] << 8);
            uint8_t status = (hdr_offset + 3 < len) ? data[hdr_offset + 3] : 0xFF;
            printf("  Command Complete: opcode=0x%04X status=%d\n", opcode, status);
        }
    }
    else if (evt_code == HCI_EVT_COMMAND_STATUS) {
        if (evt_len >= 4) {
            uint8_t status = data[hdr_offset];
            uint16_t opcode = data[hdr_offset + 2] | (data[hdr_offset + 3] << 8);
            printf("  Command Status: opcode=0x%04X status=%d\n", opcode, status);
        }
    }
    else if (evt_code == 0x13) {
        /* Number of Completed Packets */
        printf("  Num Completed Packets event\n");
    }
    else if (evt_code == 0x05) {
        /* Disconnection Complete */
        if (evt_len >= 4) {
            uint8_t status = data[hdr_offset];
            uint16_t handle = data[hdr_offset + 1] | (data[hdr_offset + 2] << 8);
            uint8_t reason = data[hdr_offset + 3];
            printf("  Disconnection Complete: handle=0x%04X status=%d reason=0x%02X\n",
                   handle, status, reason);
        }
    }
    else if (evt_code == 0x08) {
        /* Encryption Change */
        printf("  Encryption Change event\n");
    }
    else {
        printf("  (event 0x%02X not further decoded)\n", evt_code);
    }

    fflush(stdout);
    return 0;
}

static const esp_vhci_host_callback_t vhci_callbacks = {
    .notify_host_send_available = controller_rcv_pkt_ready,
    .notify_host_recv = host_rcv_pkt,
};

/* Serial command reading task */
static void cmd_task(void *arg)
{
    char line[128];
    int pos = 0;

    printf("\n");
    printf("========================================\n");
    printf("  ESP32 BLE Sniffer for Omnipod 5\n");
    printf("========================================\n");
    printf("Type 'help' for commands.\n");
    printf("Scanning in DISCOVERY mode...\n\n");

    while (1) {
        int c = getchar();
        if (c == EOF || c == -1) {
            vTaskDelay(pdMS_TO_TICKS(50));
            continue;
        }

        if (c == '\n' || c == '\r') {
            line[pos] = '\0';
            if (pos > 0) {
                sniffer_process_command(&g_sniffer, line);
            }
            pos = 0;
        } else if (pos < (int)sizeof(line) - 1) {
            line[pos++] = (char)c;
        }
    }
}

void app_main(void)
{
    esp_err_t ret;

    /* Initialize NVS — required by BT controller */
    ret = nvs_flash_init();
    if (ret == ESP_ERR_NVS_NO_FREE_PAGES || ret == ESP_ERR_NVS_NEW_VERSION_FOUND) {
        ESP_ERROR_CHECK(nvs_flash_erase());
        ret = nvs_flash_init();
    }
    ESP_ERROR_CHECK(ret);

    /* Release memory for classic BT (we only use BLE) */
    ESP_ERROR_CHECK(esp_bt_controller_mem_release(ESP_BT_MODE_CLASSIC_BT));

    /* Configure and initialize BT controller */
    esp_bt_controller_config_t bt_cfg = BT_CONTROLLER_INIT_CONFIG_DEFAULT();

    ret = esp_bt_controller_init(&bt_cfg);
    if (ret != ESP_OK) {
        ESP_LOGE(TAG, "BT controller init failed: %s", esp_err_to_name(ret));
        return;
    }

    ret = esp_bt_controller_enable(ESP_BT_MODE_BLE);
    if (ret != ESP_OK) {
        ESP_LOGE(TAG, "BT controller enable failed: %s", esp_err_to_name(ret));
        return;
    }

    /* Register VHCI callbacks */
    ret = esp_vhci_host_register_callback(&vhci_callbacks);
    if (ret != ESP_OK) {
        ESP_LOGE(TAG, "VHCI register failed: %s", esp_err_to_name(ret));
        return;
    }

    ESP_LOGI(TAG, "BLE controller initialized in VHCI mode");

    /* Wait for controller to be ready */
    vTaskDelay(pdMS_TO_TICKS(500));

    /* Initialize sniffer state */
    sniffer_init(&g_sniffer);

    /* Send HCI commands — wait for controller ready before each */
    uint8_t buf[32];
    uint16_t len;

    /* 1. HCI Reset */
    while (!esp_vhci_host_check_send_available()) vTaskDelay(pdMS_TO_TICKS(10));
    len = hci_build_reset(buf);
    esp_vhci_host_send_packet(buf, len);
    vTaskDelay(pdMS_TO_TICKS(300));

    /* 2. Set baseband Event Mask — bit 61 enables LE Meta Events.
     *    Without this, the controller silently drops all advertising reports! */
    while (!esp_vhci_host_check_send_available()) vTaskDelay(pdMS_TO_TICKS(10));
    len = hci_build_set_event_mask(buf);
    esp_vhci_host_send_packet(buf, len);
    vTaskDelay(pdMS_TO_TICKS(100));

    /* 3. Set LE Event Mask — enable advertising report sub-events */
    while (!esp_vhci_host_check_send_available()) vTaskDelay(pdMS_TO_TICKS(10));
    len = hci_build_le_set_event_mask(buf);
    esp_vhci_host_send_packet(buf, len);
    vTaskDelay(pdMS_TO_TICKS(100));

    /* Start scanning in discovery mode */
    sniffer_start_scan(&g_sniffer, SCAN_FILTER_BASIC);

    /* Start command processing task */
    xTaskCreate(cmd_task, "cmd_task", 4096, NULL, 5, NULL);
}
