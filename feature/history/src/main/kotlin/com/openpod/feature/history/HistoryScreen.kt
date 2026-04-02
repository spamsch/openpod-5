package com.openpod.feature.history

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.ShowChart
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.openpod.model.history.HistoryEvent
import com.openpod.model.history.HistoryEventType
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun HistoryScreen(
    viewModel: HistoryViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Column(modifier = Modifier.fillMaxSize()) {
        Text(
            text = stringResource(R.string.history_title),
            style = MaterialTheme.typography.headlineLarge,
            modifier = Modifier.padding(start = 20.dp, top = 16.dp, bottom = 8.dp),
        )

        // Filter chips
        LazyRow(
            modifier = Modifier.padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(HistoryFilter.entries) { filter ->
                FilterChip(
                    selected = state.selectedFilter == filter,
                    onClick = { viewModel.onIntent(HistoryIntent.SelectFilter(filter)) },
                    label = { Text(filter.displayLabel) },
                )
            }
        }

        Spacer(Modifier.height(8.dp))

        if (state.events.isEmpty() && !state.isLoading) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(stringResource(R.string.history_empty_title), style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.history_empty_message),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    horizontal = 16.dp,
                    vertical = 8.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                state.groupedByDay.forEach { (date, events) ->
                    item(key = "header-$date") {
                        DayHeader(date)
                    }
                    items(events, key = { it.id }) { event ->
                        EventCard(event)
                    }
                    item(key = "spacer-$date") {
                        Spacer(Modifier.height(8.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun DayHeader(date: LocalDate) {
    val today = LocalDate.now()
    val label = when (date) {
        today -> stringResource(R.string.history_today)
        today.minusDays(1) -> stringResource(R.string.history_yesterday)
        else -> date.format(DateTimeFormatter.ofPattern("EEEE, MMM d"))
    }
    Text(
        text = label,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(vertical = 8.dp, horizontal = 4.dp),
    )
}

@Composable
private fun EventCard(event: HistoryEvent) {
    val timeText = event.timestamp
        .atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern("h:mm a"))

    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = event.type.icon,
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(event.type.displayTitle, style = MaterialTheme.typography.titleSmall)
                event.secondaryValue?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                val valueText = event.type.formatValue(event.primaryValue)
                if (valueText.isNotEmpty()) {
                    Text(valueText, style = MaterialTheme.typography.titleSmall)
                }
                Text(
                    timeText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private val HistoryFilter.displayLabel: String
    @Composable get() = when (this) {
        HistoryFilter.ALL -> stringResource(R.string.history_filter_all)
        HistoryFilter.BOLUS -> stringResource(R.string.history_filter_bolus)
        HistoryFilter.GLUCOSE -> stringResource(R.string.history_filter_glucose)
        HistoryFilter.BASAL -> stringResource(R.string.history_filter_basal)
        HistoryFilter.ALERTS -> stringResource(R.string.history_filter_alerts)
        HistoryFilter.POD -> stringResource(R.string.history_filter_pod)
    }

private val HistoryEventType.icon: ImageVector
    get() = when (this) {
        HistoryEventType.BOLUS -> Icons.Default.WaterDrop
        HistoryEventType.GLUCOSE -> Icons.Default.ShowChart
        HistoryEventType.BASAL -> Icons.Default.ShowChart
        HistoryEventType.ALERT -> Icons.Default.Notifications
        HistoryEventType.POD -> Icons.Default.Notifications
    }

private val HistoryEventType.displayTitle: String
    @Composable get() = when (this) {
        HistoryEventType.BOLUS -> stringResource(R.string.history_event_bolus)
        HistoryEventType.GLUCOSE -> stringResource(R.string.history_event_glucose)
        HistoryEventType.BASAL -> stringResource(R.string.history_event_basal)
        HistoryEventType.ALERT -> stringResource(R.string.history_event_alert)
        HistoryEventType.POD -> stringResource(R.string.history_event_pod)
    }

private fun HistoryEventType.formatValue(value: Double): String = when (this) {
    HistoryEventType.BOLUS -> "%.2f U".format(value)
    HistoryEventType.GLUCOSE -> "${value.toInt()} mg/dL"
    HistoryEventType.BASAL -> "%.2f U/hr".format(value)
    HistoryEventType.ALERT, HistoryEventType.POD -> ""
}
