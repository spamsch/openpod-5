package com.openpod.model.history

import java.time.Instant

enum class HistoryEventType {
    BOLUS,
    GLUCOSE,
    BASAL,
    ALERT,
    POD,
}

data class HistoryEvent(
    val id: Long,
    val type: HistoryEventType,
    val timestamp: Instant,
    val primaryValue: Double,
    val secondaryValue: String? = null,
    val metadata: String? = null,
)
