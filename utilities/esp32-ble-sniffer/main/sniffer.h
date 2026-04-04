#pragma once

#include "hci.h"
#include <stdbool.h>

/* Sniffer state */
typedef struct {
    bool     scanning;
    bool     spoofing;
    uint8_t  spoof_addr[6];       /* Target address we're spoofing */
    uint8_t  original_addr[6];    /* Our real MAC (not currently retrievable, zeroed) */
    uint32_t pkt_total;           /* Total packets seen */
    uint32_t pkt_omnipod;         /* Packets matching Omnipod signatures */
    uint32_t pkt_directed;        /* ADV_DIRECT_IND packets */
    int64_t  start_time_us;       /* Scan start timestamp */
} sniffer_state_t;

/* Initialize sniffer state */
void sniffer_init(sniffer_state_t *state);

/* Process a parsed advertising report */
void sniffer_process_report(sniffer_state_t *state, const adv_report_t *report);

/* Process a serial command line */
void sniffer_process_command(sniffer_state_t *state, const char *cmd);

/* Print current status */
void sniffer_print_status(const sniffer_state_t *state);

/* Start scanning — sends HCI commands via VHCI */
void sniffer_start_scan(sniffer_state_t *state, uint8_t filter_policy);

/* Stop scanning */
void sniffer_stop_scan(sniffer_state_t *state);

/* Spoof MAC address and restart scan */
void sniffer_spoof_addr(sniffer_state_t *state, const uint8_t addr[6]);

/* Reset to original MAC and restart scan */
void sniffer_reset_addr(sniffer_state_t *state);
