package com.openpod.feature.dashboard

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DirectionsRun
import androidx.compose.material.icons.filled.Loop
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LargeFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.openpod.core.ui.component.ActionCard
import com.openpod.core.ui.component.GlucoseDisplay
import com.openpod.core.ui.component.IobChip
import com.openpod.core.ui.component.PodStatusBar
import com.openpod.core.ui.mvi.CollectEffects
import com.openpod.core.ui.theme.InsulinBolus
import com.openpod.model.glucose.GlucoseReading
import com.openpod.model.insulin.BolusRecord
import com.openpod.model.insulin.BolusType
import com.openpod.model.pod.DeliveryStatus
import com.openpod.model.pod.OperatingMode
import com.openpod.model.pod.PodConnectionState
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Dashboard home screen providing a glanceable overview of all critical
 * diabetes management metrics.
 *
 * Renders the pod status capsule, glucose hero display, IOB pill, last bolus
 * card, active basal card, and an extended FAB for bolus entry.
 * Supports pull-to-refresh and handles edge cases for no pod, disconnected
 * state, CGM loss, and bolus in progress.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    onNavigateToBolus: () -> Unit = {},
    onNavigateToPairing: () -> Unit = {},
    onNavigateToPodStatus: () -> Unit = {},
    onNavigateToBasalPrograms: () -> Unit = {},
    onNavigateToModeSettings: () -> Unit = {},
    viewModel: DashboardViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    CollectEffects(viewModel.effect) { effect ->
        when (effect) {
            DashboardEffect.OpenBolus -> onNavigateToBolus()
            DashboardEffect.OpenPairing -> onNavigateToPairing()
            DashboardEffect.OpenPodStatus -> onNavigateToPodStatus()
            DashboardEffect.OpenGlucoseDetail -> Unit // TODO: wire when screen exists
            DashboardEffect.OpenIobDetail -> Unit // TODO: wire when screen exists
            DashboardEffect.OpenBasalPrograms -> onNavigateToBasalPrograms()
            DashboardEffect.OpenModeSettings -> onNavigateToModeSettings()
        }
    }

    DashboardContent(
        state = state,
        onIntent = viewModel::onIntent,
    )
}

/**
 * Stateless dashboard content, separated from the ViewModel for
 * testability and preview support.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DashboardContent(
    state: DashboardState,
    onIntent: (DashboardIntent) -> Unit,
) {
    val hasPod = state.podState != null
    val isConnected = state.podState?.connectionState == PodConnectionState.CONNECTED
    val isDisconnected = state.podState != null &&
        state.podState.connectionState == PodConnectionState.DISCONNECTED
    val showFab = hasPod &&
        state.podState?.deliveryStatus != DeliveryStatus.BOLUS_IN_PROGRESS

    Box(modifier = Modifier.fillMaxSize()) {
        PullToRefreshBox(
            isRefreshing = state.isRefreshing,
            onRefresh = { onIntent(DashboardIntent.RefreshData) },
            modifier = Modifier.fillMaxSize(),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState()),
            ) {
                // 1. PodStatusBar (capsule)
                PodStatusBar(
                    connectionState = state.podState?.connectionState
                        ?: PodConnectionState.DISCONNECTED,
                    reservoirUnits = state.podState?.reservoirUnits,
                    timeRemainingHours = state.podState?.timeRemaining()
                        ?.toHours()?.toDouble(),
                    expiresAt = state.podState?.expiresAt,
                    operatingMode = state.podState?.operatingMode,
                    onTap = { onIntent(DashboardIntent.NavigateToPodStatus) },
                    modifier = Modifier.fillMaxWidth(),
                )

                // No pod banner
                if (!hasPod) {
                    NoPodBanner(
                        onPairPod = { onIntent(DashboardIntent.NavigateToPairing) },
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }

                // Disconnected warning banner
                if (isDisconnected) {
                    DisconnectedBanner(
                        lastSyncAt = state.podState?.lastSyncAt,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }

                // 2. GlucoseDisplay (hero) with IOB pill
                GlucoseSection(
                    glucoseReading = state.glucoseReading,
                    iobUnits = state.iob?.totalUnits,
                    iobDecaying = state.iob?.isDecaying == true,
                    hasPod = hasPod,
                    isConnected = isConnected,
                    onGlucoseTap = { onIntent(DashboardIntent.NavigateToGlucoseDetail) },
                    onIobTap = { onIntent(DashboardIntent.NavigateToIobDetail) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = if (hasPod) 48.dp else 16.dp, bottom = 24.dp),
                )

                // 3. Last Bolus / Bolus in Progress card
                if (hasPod && state.lastBolus != null) {
                    if (state.lastBolus.isInProgress) {
                        BolusProgressCard(
                            bolus = state.lastBolus,
                            modifier = Modifier.padding(horizontal = 16.dp),
                        )
                    } else {
                        LastBolusCard(
                            bolus = state.lastBolus,
                            modifier = Modifier.padding(horizontal = 16.dp),
                        )
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                }

                // Activity Mode banner (conditional, above basal card)
                val activityMode = state.podState?.activityMode
                AnimatedVisibility(
                    visible = activityMode != null && hasPod,
                    enter = fadeIn(),
                    exit = fadeOut(),
                ) {
                    if (activityMode != null) {
                        ActivityModeBanner(
                            remaining = activityMode.timeRemaining(),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                    }
                }

                // 4. Active Basal card (includes mode display)
                if (hasPod) {
                    ActiveBasalCard(
                        operatingMode = state.podState!!.operatingMode,
                        basalRate = state.activeBasalRate,
                        programName = state.activeBasalProgramName,
                        activityModeActive = state.podState.activityMode != null,
                        onClick = { onIntent(DashboardIntent.NavigateToBasalPrograms) },
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                }

                // Bottom spacing for FAB clearance
                Spacer(modifier = Modifier.height(96.dp))
            }
        }

        // Bolus Extended FAB
        if (showFab) {
            BolusFab(
                onClick = { onIntent(DashboardIntent.NavigateToBolus) },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 16.dp, bottom = 16.dp),
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Private composables
// ---------------------------------------------------------------------------

/**
 * Mode chip showing Manual or Automatic mode.
 */
@Composable
private fun ModeChip(
    operatingMode: OperatingMode,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isAutomatic = operatingMode == OperatingMode.AUTOMATIC
    val label = if (isAutomatic) {
        stringResource(R.string.dashboard_mode_automatic)
    } else {
        stringResource(R.string.dashboard_mode_manual)
    }
    val chipColor = if (isAutomatic) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceContainerHigh
    }
    val textColor = if (isAutomatic) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    val accessibilityText = stringResource(R.string.dashboard_mode_chip_description, label)

    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.small,
        color = chipColor,
        modifier = modifier.semantics { contentDescription = accessibilityText },
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            if (isAutomatic) {
                Icon(
                    imageVector = Icons.Default.Loop,
                    contentDescription = null,
                    tint = textColor,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(modifier = Modifier.width(6.dp))
            }
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = textColor,
            )
        }
    }
}

/**
 * Activity Mode banner with countdown.
 */
@Composable
private fun ActivityModeBanner(
    remaining: Duration,
    modifier: Modifier = Modifier,
) {
    val remainingText = if (remaining.toHours() > 0) {
        stringResource(R.string.dashboard_activity_mode_hours_remaining, remaining.toHours().toInt())
    } else {
        stringResource(
            R.string.dashboard_activity_mode_minutes_remaining,
            remaining.toMinutes().toInt(),
        )
    }
    val accessibilityText = stringResource(R.string.dashboard_activity_mode_accessibility, remainingText)

    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.tertiaryContainer,
        modifier = modifier.semantics {
            liveRegion = LiveRegionMode.Assertive
            contentDescription = accessibilityText
        },
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Icon(
                imageVector = Icons.Default.DirectionsRun,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onTertiaryContainer,
                modifier = Modifier.size(20.dp),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = stringResource(R.string.dashboard_activity_mode_banner),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = remainingText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
            )
        }
    }
}

/**
 * Banner shown when no pod is paired, with a "Pair Pod" call-to-action.
 */
@Composable
private fun NoPodBanner(
    onPairPod: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = stringResource(R.string.dashboard_no_pod_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.dashboard_no_pod_message),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(modifier = Modifier.height(16.dp))
            Button(
                onClick = onPairPod,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                ),
            ) {
                Text(text = stringResource(R.string.dashboard_no_pod_pair_button))
            }
        }
    }
}

/**
 * Warning banner shown when the pod is paired but BLE connection is lost.
 */
@Composable
private fun DisconnectedBanner(
    lastSyncAt: Instant?,
    modifier: Modifier = Modifier,
) {
    val minutesAgo = lastSyncAt?.let {
        Duration.between(it, Instant.now()).toMinutes().toInt()
    }
    val isLongDisconnect = minutesAgo != null && minutesAgo > 15

    val backgroundColor = if (isLongDisconnect) {
        MaterialTheme.colorScheme.error
    } else {
        MaterialTheme.colorScheme.errorContainer
    }
    val textColor = if (isLongDisconnect) {
        MaterialTheme.colorScheme.onError
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    val title = if (isLongDisconnect) {
        stringResource(R.string.dashboard_disconnected_long_title)
    } else {
        stringResource(R.string.dashboard_disconnected_title)
    }

    Surface(
        shape = MaterialTheme.shapes.medium,
        color = backgroundColor,
        modifier = modifier
            .fillMaxWidth()
            .semantics { liveRegion = LiveRegionMode.Assertive },
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = textColor,
            )
            if (minutesAgo != null) {
                Text(
                    text = stringResource(R.string.dashboard_disconnected_time, minutesAgo),
                    style = MaterialTheme.typography.bodySmall,
                    color = textColor,
                )
            }
            Text(
                text = stringResource(R.string.dashboard_disconnected_reconnecting),
                style = MaterialTheme.typography.bodySmall,
                color = textColor,
            )
        }
    }
}

/**
 * Glucose hero section with IOB pill and tap-to-navigate support.
 */
@Composable
private fun GlucoseSection(
    glucoseReading: GlucoseReading?,
    iobUnits: Double?,
    iobDecaying: Boolean,
    hasPod: Boolean,
    isConnected: Boolean,
    onGlucoseTap: () -> Unit,
    onIobTap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val freshnessText = when {
        glucoseReading != null -> {
            val minutesAgo = Duration.between(
                glucoseReading.timestamp,
                Instant.now(),
            ).toMinutes().toInt()
            stringResource(R.string.dashboard_glucose_freshness_minutes, minutesAgo)
        }
        !hasPod -> stringResource(R.string.dashboard_glucose_no_data_disconnected)
        !isConnected -> stringResource(R.string.dashboard_glucose_no_data_disconnected)
        else -> stringResource(R.string.dashboard_glucose_waiting_first)
    }

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Glucose value (tappable)
        Box(
            modifier = Modifier.clickable(onClick = onGlucoseTap),
            contentAlignment = Alignment.Center,
        ) {
            GlucoseDisplay(
                glucoseValue = glucoseReading?.valueMgDl,
                unit = stringResource(R.string.dashboard_glucose_unit_mgdl),
                trend = glucoseReading?.trend,
                freshnessText = freshnessText,
                glucoseRange = glucoseReading?.range(),
            )
        }

        // IOB pill (compact, tappable)
        Spacer(modifier = Modifier.height(12.dp))

        IobChip(
            iobUnits = iobUnits,
            isDecaying = iobDecaying,
            onClick = onIobTap,
        )
    }
}

/**
 * Card showing the most recent completed bolus.
 */
@Composable
private fun LastBolusCard(
    bolus: BolusRecord,
    modifier: Modifier = Modifier,
) {
    val doseText = String.format(Locale.US, "%.2f", bolus.deliveredUnits)
    val timeText = DateTimeFormatter.ofPattern("h:mm a", Locale.US)
        .format(bolus.startedAt.atZone(ZoneId.systemDefault()))
    val primaryText = stringResource(R.string.dashboard_last_bolus_primary, doseText, timeText)

    val typeText = when (bolus.bolusType) {
        BolusType.MEAL -> stringResource(R.string.dashboard_last_bolus_type_meal)
        BolusType.CORRECTION -> stringResource(R.string.dashboard_last_bolus_type_correction)
        BolusType.MEAL_AND_CORRECTION -> stringResource(R.string.dashboard_last_bolus_type_meal_correction)
        BolusType.MANUAL -> stringResource(R.string.dashboard_last_bolus_type_manual)
    }

    val secondaryText = if (bolus.carbsGrams > 0) {
        stringResource(R.string.dashboard_last_bolus_secondary_with_carbs, bolus.carbsGrams, typeText)
    } else {
        stringResource(R.string.dashboard_last_bolus_secondary_no_carbs, typeText)
    }

    ActionCard(
        sectionLabel = stringResource(R.string.dashboard_last_bolus_label),
        onClick = { /* TODO: navigate to bolus detail */ },
        content = {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = primaryText,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = secondaryText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        modifier = modifier,
    )
}

/**
 * Card showing bolus delivery progress when a bolus is in flight.
 */
@Composable
private fun BolusProgressCard(
    bolus: BolusRecord,
    modifier: Modifier = Modifier,
) {
    val deliveredText = String.format(Locale.US, "%.2f", bolus.deliveredUnits)
    val requestedText = String.format(Locale.US, "%.2f", bolus.requestedUnits)
    val progress = if (bolus.requestedUnits > 0) {
        (bolus.deliveredUnits / bolus.requestedUnits).toFloat().coerceIn(0f, 1f)
    } else {
        0f
    }
    val remainingUnits = bolus.requestedUnits - bolus.deliveredUnits
    val remainingSeconds = (remainingUnits / BolusRecord.PULSE_SIZE * BolusRecord.SECONDS_PER_PULSE).toInt()

    ActionCard(
        sectionLabel = stringResource(R.string.dashboard_bolus_delivering),
        content = {
            Spacer(modifier = Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxWidth(),
                color = InsulinBolus,
                trackColor = MaterialTheme.colorScheme.outlineVariant,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.dashboard_bolus_progress, deliveredText, requestedText),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = stringResource(R.string.dashboard_bolus_time_remaining, remainingSeconds),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(8.dp))
            TextButton(
                onClick = { /* TODO: cancel bolus confirmation dialog */ },
                colors = ButtonDefaults.textButtonColors(
                    contentColor = MaterialTheme.colorScheme.error,
                ),
            ) {
                Text(text = stringResource(R.string.dashboard_bolus_cancel))
            }
        },
        modifier = modifier,
    )
}

/**
 * Card showing the active basal program or Automatic Mode indicator.
 */
@Composable
private fun ActiveBasalCard(
    operatingMode: OperatingMode,
    basalRate: Double?,
    programName: String?,
    activityModeActive: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isAutomatic = operatingMode == OperatingMode.AUTOMATIC

    ActionCard(
        sectionLabel = stringResource(R.string.dashboard_active_basal_label),
        onClick = onClick,
        content = {
            Spacer(modifier = Modifier.height(4.dp))
            if (isAutomatic) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Loop,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.dashboard_active_basal_automatic),
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = stringResource(R.string.dashboard_active_basal_auto_description),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                val rateText = basalRate?.let {
                    String.format(Locale.US, "%.2f", it)
                } ?: "--"

                if (programName != null) {
                    Text(
                        text = stringResource(
                            R.string.dashboard_active_basal_rate_with_name,
                            rateText,
                            programName,
                        ),
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                } else {
                    Text(
                        text = stringResource(R.string.dashboard_active_basal_rate, rateText),
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }

            // Activity Mode annotation
            if (activityModeActive) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.dashboard_active_basal_activity_mode_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }
        },
        modifier = modifier,
    )
}

/**
 * Extended Bolus floating action button with icon and label.
 */
@Composable
private fun BolusFab(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LargeFloatingActionButton(
        onClick = onClick,
        containerColor = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary,
        modifier = modifier,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                imageVector = Icons.Default.WaterDrop,
                contentDescription = null,
                modifier = Modifier.size(28.dp),
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.dashboard_bolus_fab),
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
}
