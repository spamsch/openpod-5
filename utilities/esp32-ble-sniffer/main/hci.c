#include "hci.h"
#include <string.h>

/* Helper macros for building HCI packets */
#define PUT_U8(buf, val)   do { *(buf)++ = (uint8_t)(val); } while(0)
#define PUT_U16(buf, val)  do { *(buf)++ = (uint8_t)((val) & 0xFF); \
                                *(buf)++ = (uint8_t)(((val) >> 8) & 0xFF); } while(0)

uint16_t hci_build_reset(uint8_t *buf)
{
    uint8_t *p = buf;
    PUT_U8(p, H4_TYPE_COMMAND);
    PUT_U16(p, HCI_RESET);
    PUT_U8(p, 0); /* param length */
    return (uint16_t)(p - buf);
}

uint16_t hci_build_set_event_mask(uint8_t *buf)
{
    uint8_t *p = buf;
    PUT_U8(p, H4_TYPE_COMMAND);
    PUT_U16(p, HCI_SET_EVENT_MASK);
    PUT_U8(p, 8); /* param length */
    /* Enable standard events + bit 61 (LE Meta Event) */
    PUT_U8(p, 0xFF);  /* bits 0-7 */
    PUT_U8(p, 0xFF);  /* bits 8-15 */
    PUT_U8(p, 0xFF);  /* bits 16-23 */
    PUT_U8(p, 0xFF);  /* bits 24-31 */
    PUT_U8(p, 0xFF);  /* bits 32-39 */
    PUT_U8(p, 0xFF);  /* bits 40-47: all events */
    PUT_U8(p, 0xFF);  /* bits 48-55: all events */
    PUT_U8(p, 0x3F);  /* bits 56-61: all events incl LE Meta (bit 61) */
    return (uint16_t)(p - buf);
}

uint16_t hci_build_le_set_event_mask(uint8_t *buf)
{
    uint8_t *p = buf;
    PUT_U8(p, H4_TYPE_COMMAND);
    PUT_U16(p, HCI_LE_SET_EVENT_MASK);
    PUT_U8(p, 8); /* param length */
    /* Enable all LE events */
    PUT_U8(p, 0xFF);
    PUT_U8(p, 0xFF);
    PUT_U8(p, 0xFF);
    PUT_U8(p, 0xFF);
    PUT_U8(p, 0xFF);
    PUT_U8(p, 0xFF);
    PUT_U8(p, 0xFF);
    PUT_U8(p, 0x3F);
    return (uint16_t)(p - buf);
}

uint16_t hci_build_le_set_scan_params(uint8_t *buf, uint8_t scan_type,
                                       uint16_t interval, uint16_t window,
                                       uint8_t own_addr_type, uint8_t filter_policy)
{
    uint8_t *p = buf;
    PUT_U8(p, H4_TYPE_COMMAND);
    PUT_U16(p, HCI_LE_SET_SCAN_PARAMS);
    PUT_U8(p, 7); /* param length */
    PUT_U8(p, scan_type);
    PUT_U16(p, interval);
    PUT_U16(p, window);
    PUT_U8(p, own_addr_type);
    PUT_U8(p, filter_policy);
    return (uint16_t)(p - buf);
}

uint16_t hci_build_le_set_scan_enable(uint8_t *buf, uint8_t enable, uint8_t filter_dup)
{
    uint8_t *p = buf;
    PUT_U8(p, H4_TYPE_COMMAND);
    PUT_U16(p, HCI_LE_SET_SCAN_ENABLE);
    PUT_U8(p, 2); /* param length */
    PUT_U8(p, enable);
    PUT_U8(p, filter_dup);
    return (uint16_t)(p - buf);
}

uint16_t hci_build_vendor_set_bd_addr(uint8_t *buf, const uint8_t addr[6])
{
    uint8_t *p = buf;
    PUT_U8(p, H4_TYPE_COMMAND);
    PUT_U16(p, HCI_VENDOR_SET_BD_ADDR);
    PUT_U8(p, 6); /* param length */
    /* Address in little-endian order */
    for (int i = 0; i < 6; i++) {
        PUT_U8(p, addr[i]);
    }
    return (uint16_t)(p - buf);
}

int hci_parse_adv_reports(const uint8_t *data, uint16_t len, adv_report_t *reports, int max_reports)
{
    if (len < 2) return 0;

    /* data[0] = sub-event code (already checked by caller)
     * data[1] = num_reports */
    uint8_t num_reports = data[1];
    if (num_reports > max_reports) {
        num_reports = max_reports;
    }

    const uint8_t *p = &data[2];
    const uint8_t *end = data + len;

    for (int i = 0; i < num_reports && p < end; i++) {
        memset(&reports[i], 0, sizeof(adv_report_t));

        /* Event type */
        if (p >= end) break;
        reports[i].event_type = *p++;

        /* Address type */
        if (p >= end) break;
        reports[i].addr_type = *p++;

        /* Address (6 bytes, little-endian) */
        if (p + 6 > end) break;
        memcpy(reports[i].addr, p, 6);
        p += 6;

        /* Data length */
        if (p >= end) break;
        reports[i].data_len = *p++;
        if (reports[i].data_len > 31) {
            reports[i].data_len = 31;
        }

        /* Data */
        if (p + reports[i].data_len > end) break;
        memcpy(reports[i].data, p, reports[i].data_len);
        p += reports[i].data_len;

        /* RSSI (signed byte at the end) */
        if (p >= end) break;
        reports[i].rssi = (int8_t)*p++;
    }

    return num_reports;
}

const char *adv_type_str(uint8_t type)
{
    switch (type) {
        case ADV_IND:         return "ADV_IND        ";
        case ADV_DIRECT_IND:  return "ADV_DIRECT_IND ";
        case ADV_SCAN_IND:    return "ADV_SCAN_IND   ";
        case ADV_NONCONN_IND: return "ADV_NONCONN_IND";
        case SCAN_RSP:        return "SCAN_RSP       ";
        default:              return "UNKNOWN        ";
    }
}
