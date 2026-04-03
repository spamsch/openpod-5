#pragma once

#include <stdint.h>
#include <stdbool.h>

/* HCI packet types */
#define H4_TYPE_COMMAND 0x01
#define H4_TYPE_ACL     0x02
#define H4_TYPE_EVENT   0x04

/* HCI command opcodes */
#define HCI_RESET                     0x0C03
#define HCI_LE_SET_SCAN_PARAMS        0x200B
#define HCI_LE_SET_SCAN_ENABLE        0x200C
#define HCI_SET_EVENT_MASK            0x0C01  /* Baseband event mask — gates LE Meta Events */
#define HCI_LE_SET_EVENT_MASK         0x2001
#define HCI_VENDOR_SET_BD_ADDR        0xFC32

/* HCI event codes */
#define HCI_EVT_COMMAND_COMPLETE      0x0E
#define HCI_EVT_COMMAND_STATUS        0x0F
#define HCI_EVT_LE_META               0x3E

/* LE meta sub-events */
#define HCI_LE_ADV_REPORT             0x02

/* BLE advertising PDU types (event_type in LE Advertising Report) */
#define ADV_IND           0x00
#define ADV_DIRECT_IND    0x01
#define ADV_SCAN_IND      0x02
#define ADV_NONCONN_IND   0x03
#define SCAN_RSP          0x04

/* Scan filter policies */
#define SCAN_FILTER_BASIC             0x00  /* All undirected; drop directed not for us */
#define SCAN_FILTER_EXTENDED          0x02  /* All undirected + directed with RPA target */

/* Address types */
#define ADDR_TYPE_PUBLIC    0x00
#define ADDR_TYPE_RANDOM    0x01

/* Insulet manufacturer company ID (little-endian) */
#define INSULET_COMPANY_ID  0x0360

/* Build HCI commands — returns total packet length */
uint16_t hci_build_reset(uint8_t *buf);
uint16_t hci_build_set_event_mask(uint8_t *buf);
uint16_t hci_build_le_set_event_mask(uint8_t *buf);
uint16_t hci_build_le_set_scan_params(uint8_t *buf, uint8_t scan_type,
                                       uint16_t interval, uint16_t window,
                                       uint8_t own_addr_type, uint8_t filter_policy);
uint16_t hci_build_le_set_scan_enable(uint8_t *buf, uint8_t enable, uint8_t filter_dup);
uint16_t hci_build_vendor_set_bd_addr(uint8_t *buf, const uint8_t addr[6]);

/* Advertising report — parsed from LE Meta Event */
typedef struct {
    uint8_t  event_type;      /* ADV_IND, ADV_DIRECT_IND, etc. */
    uint8_t  addr_type;       /* Public or Random */
    uint8_t  addr[6];         /* Advertiser address */
    uint8_t  direct_addr[6];  /* Target address (for ADV_DIRECT_IND) */
    int8_t   rssi;
    uint8_t  data_len;
    uint8_t  data[31];        /* Advertising data (max 31 bytes) */
} adv_report_t;

/* Parse LE Advertising Report event — returns number of reports parsed */
int hci_parse_adv_reports(const uint8_t *data, uint16_t len, adv_report_t *reports, int max_reports);

/* Get human-readable name for advertising PDU type */
const char *adv_type_str(uint8_t type);
