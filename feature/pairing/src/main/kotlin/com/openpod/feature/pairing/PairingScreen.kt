package com.openpod.feature.pairing

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.openpod.core.ui.component.ProgressRing
import com.openpod.core.ui.component.WizardStepper
import com.openpod.core.ui.mvi.CollectEffects
import com.openpod.core.ui.theme.PodConnected
import com.openpod.domain.pod.DiscoveredPod
import com.openpod.model.pod.InfusionSite

/**
 * Pod Pairing Wizard — full 7-step flow for discovering, connecting,
 * authenticating, and activating a new Omnipod 5 pod.
 *
 * The wizard is modal: the bottom navigation bar is hidden and the user
 * must complete or cancel the flow. A cancel confirmation dialog warns
 * about wasted pods after the priming step.
 *
 * @param onComplete Called when the user finishes the wizard (taps "Go to Dashboard").
 * @param onCancel Called when the user confirms cancellation.
 */
@Composable
fun PairingScreen(
    onComplete: () -> Unit = {},
    onCancel: () -> Unit = {},
) {
    val viewModel: PairingViewModel = hiltViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()
    var showCancelDialog by remember { mutableStateOf(false) }

    CollectEffects(viewModel.effect) { effect ->
        when (effect) {
            is PairingEffect.NavigateToDashboard -> onComplete()
            is PairingEffect.ShowCancelConfirmation -> {
                showCancelDialog = true
            }
        }
    }

    PairingContent(
        state = state,
        onIntent = viewModel::onIntent,
        showCancelDialog = showCancelDialog,
        onDismissCancelDialog = { showCancelDialog = false },
        onConfirmCancel = {
            showCancelDialog = false
            onCancel()
        },
    )
}

/**
 * Stateless pairing content for testability.
 */
@Composable
internal fun PairingContent(
    state: PairingState,
    onIntent: (PairingIntent) -> Unit,
    showCancelDialog: Boolean,
    onDismissCancelDialog: () -> Unit,
    onConfirmCancel: () -> Unit,
) {
    val currentStepNumber = state.currentStep.ordinal + 1

    Scaffold(
        topBar = {
            PairingTopBar(
                onCancel = { onIntent(PairingIntent.Cancel) },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            // Step indicator
            WizardStepper(
                totalSteps = TOTAL_STEPS,
                currentStep = currentStepNumber,
                modifier = Modifier.fillMaxWidth(),
            )

            // Error banner
            state.error?.let { error ->
                ErrorBanner(
                    message = stringResource(R.string.pairing_error_prefix, error),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                )
            }

            // Step content
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp, vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                when (state.currentStep) {
                    PairingStep.FILL -> FillStepContent(onIntent)
                    PairingStep.DISCOVER -> DiscoverStepContent(state, onIntent)
                    PairingStep.CONNECT -> ConnectStepContent(state)
                    PairingStep.PRIME -> PrimeStepContent(state, onIntent)
                    PairingStep.APPLY -> ApplyStepContent(state, onIntent)
                    PairingStep.START -> StartStepContent(state, onIntent)
                    PairingStep.COMPLETE -> CompleteStepContent(state, onIntent)
                }
            }
        }
    }

    // Cancel confirmation dialog
    if (showCancelDialog) {
        val isPastPrime = state.currentStep.ordinal >= PairingStep.PRIME.ordinal
        CancelConfirmationDialog(
            isPastPrime = isPastPrime,
            onDismiss = onDismissCancelDialog,
            onConfirm = onConfirmCancel,
        )
    }
}

// ── Top bar ────────────────────────────────────────────────────────

@Composable
private fun PairingTopBar(onCancel: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(
            onClick = onCancel,
            modifier = Modifier.semantics {
                contentDescription = "Cancel pod pairing"
            },
        ) {
            Icon(
                imageVector = Icons.Filled.Close,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface,
            )
        }
        Text(
            text = stringResource(R.string.pairing_title),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

// ── Error banner ───────────────────────────────────────────────────

@Composable
private fun ErrorBanner(message: String, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.padding(bottom = 8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
        ),
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onErrorContainer,
            modifier = Modifier.padding(12.dp),
        )
    }
}

// ── Step 1: Fill Pod ───────────────────────────────────────────────

@Composable
private fun FillStepContent(onIntent: (PairingIntent) -> Unit) {
    // Illustration placeholder
    IllustrationPlaceholder(
        description = stringResource(R.string.pairing_fill_illustration_description),
    )

    Spacer(modifier = Modifier.height(24.dp))

    Text(
        text = stringResource(R.string.pairing_fill_title),
        style = MaterialTheme.typography.headlineSmall,
        color = MaterialTheme.colorScheme.onSurface,
    )

    Spacer(modifier = Modifier.height(16.dp))

    val instructions = listOf(
        stringResource(R.string.pairing_fill_instruction_1),
        stringResource(R.string.pairing_fill_instruction_2),
        stringResource(R.string.pairing_fill_instruction_3),
        stringResource(R.string.pairing_fill_instruction_4),
    )
    NumberedInstructions(instructions)

    Spacer(modifier = Modifier.height(32.dp))

    Button(
        onClick = { onIntent(PairingIntent.PodFilled) },
        modifier = Modifier
            .fillMaxWidth()
            .semantics { contentDescription = "Confirm pod is filled" },
    ) {
        Text(text = stringResource(R.string.pairing_fill_button))
    }
}

// ── Step 2: Discover Pod ───────────────────────────────────────────

@Composable
private fun DiscoverStepContent(
    state: PairingState,
    onIntent: (PairingIntent) -> Unit,
) {
    // Scanning animation
    if (state.isProcessing) {
        ScanningAnimation(
            modifier = Modifier
                .size(120.dp)
                .semantics {
                    contentDescription = "Searching for pods nearby"
                },
        )
        Spacer(modifier = Modifier.height(16.dp))
    }

    Text(
        text = stringResource(R.string.pairing_discover_title),
        style = MaterialTheme.typography.headlineSmall,
        color = MaterialTheme.colorScheme.onSurface,
    )

    Spacer(modifier = Modifier.height(8.dp))

    Text(
        text = stringResource(R.string.pairing_discover_hint),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    Spacer(modifier = Modifier.height(24.dp))

    // Found pods list
    if (state.discoveredPods.isNotEmpty()) {
        Text(
            text = stringResource(R.string.pairing_discover_found_header),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(modifier = Modifier.height(8.dp))

        state.discoveredPods.forEach { pod ->
            PodCard(
                pod = pod,
                onClick = { onIntent(PairingIntent.SelectPod(pod.id)) },
            )
            Spacer(modifier = Modifier.height(8.dp))
        }
    }

    Spacer(modifier = Modifier.height(16.dp))

    // Troubleshooting tips
    Text(
        text = stringResource(R.string.pairing_discover_troubleshoot_title),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    )

    Spacer(modifier = Modifier.height(8.dp))

    val tips = listOf(
        stringResource(R.string.pairing_discover_troubleshoot_1),
        stringResource(R.string.pairing_discover_troubleshoot_2),
        stringResource(R.string.pairing_discover_troubleshoot_3),
    )
    tips.forEach { tip ->
        Row(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = "\u2022",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = tip,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    Spacer(modifier = Modifier.height(24.dp))

    TextButton(
        onClick = { onIntent(PairingIntent.ScanAgain) },
        modifier = Modifier.semantics {
            contentDescription = "Scan again for pods"
        },
    ) {
        Text(text = stringResource(R.string.pairing_discover_scan_again))
    }
}

/**
 * Card displaying a discovered pod with name, signal strength, and ID.
 */
@Composable
private fun PodCard(
    pod: DiscoveredPod,
    onClick: () -> Unit,
) {
    val signalLabel = when {
        pod.rssi > -50 -> stringResource(R.string.pairing_discover_signal_strong)
        pod.rssi > -70 -> stringResource(R.string.pairing_discover_signal_medium)
        else -> stringResource(R.string.pairing_discover_signal_weak)
    }

    val podDescription = stringResource(
        R.string.pairing_discover_pod_description,
        pod.name,
        signalLabel,
        pod.id,
    )

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .semantics { contentDescription = podDescription },
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = pod.name,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    SignalIndicator(rssi = pod.rssi)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = signalLabel,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    text = stringResource(R.string.pairing_discover_pod_id, pod.id),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = "\u25B8",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Simple signal strength indicator (3 bars).
 */
@Composable
private fun SignalIndicator(rssi: Int) {
    val bars = when {
        rssi > -50 -> 3
        rssi > -70 -> 2
        else -> 1
    }
    val activeColor = PodConnected
    val inactiveColor = MaterialTheme.colorScheme.outlineVariant

    Row(
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        for (i in 1..3) {
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .height((6 + i * 4).dp)
                    .background(
                        color = if (i <= bars) activeColor else inactiveColor,
                        shape = RoundedCornerShape(1.dp),
                    ),
            )
        }
    }
}

// ── Step 3: Connect & Authenticate ─────────────────────────────────

@Composable
private fun ConnectStepContent(state: PairingState) {
    // Connection animation placeholder
    IllustrationPlaceholder(
        description = "Connection animation: phone connecting to pod",
        height = 80,
    )

    Spacer(modifier = Modifier.height(24.dp))

    Text(
        text = stringResource(R.string.pairing_connect_title),
        style = MaterialTheme.typography.headlineSmall,
        color = MaterialTheme.colorScheme.onSurface,
    )

    Spacer(modifier = Modifier.height(24.dp))

    // Sub-step checklist
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            state.connectionProgress.forEach { step ->
                SubStepRow(
                    label = step.label,
                    status = step.status,
                )
            }
        }
    }

    Spacer(modifier = Modifier.height(16.dp))

    Text(
        text = stringResource(R.string.pairing_connect_time_hint),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

// ── Step 4: Prime ──────────────────────────────────────────────────

@Composable
private fun PrimeStepContent(
    state: PairingState,
    onIntent: (PairingIntent) -> Unit,
) {
    Spacer(modifier = Modifier.height(16.dp))

    Text(
        text = stringResource(R.string.pairing_prime_title),
        style = MaterialTheme.typography.headlineSmall,
        color = MaterialTheme.colorScheme.onSurface,
    )

    Spacer(modifier = Modifier.height(24.dp))

    val percentInt = (state.primeProgress * 100).toInt()
    ProgressRing(
        progress = state.primeProgress,
        label = stringResource(R.string.pairing_prime_progress_label, percentInt),
        sublabel = stringResource(R.string.pairing_prime_progress_sublabel),
        color = PodConnected,
        modifier = Modifier.size(160.dp),
    )

    Spacer(modifier = Modifier.height(24.dp))

    Text(
        text = stringResource(R.string.pairing_prime_explanation),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )

    Spacer(modifier = Modifier.height(32.dp))

    val primeComplete = state.primeProgress >= 1f
    Button(
        onClick = { onIntent(PairingIntent.PrimeComplete) },
        enabled = primeComplete,
        modifier = Modifier
            .fillMaxWidth()
            .semantics {
                contentDescription = if (primeComplete) {
                    "Confirm priming complete"
                } else {
                    "Priming in progress, please wait"
                }
            },
    ) {
        Text(text = stringResource(R.string.pairing_prime_button))
    }
}

// ── Step 5: Apply Pod ──────────────────────────────────────────────

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ApplyStepContent(
    state: PairingState,
    onIntent: (PairingIntent) -> Unit,
) {
    IllustrationPlaceholder(
        description = stringResource(R.string.pairing_apply_illustration_description),
    )

    Spacer(modifier = Modifier.height(24.dp))

    Text(
        text = stringResource(R.string.pairing_apply_title),
        style = MaterialTheme.typography.headlineSmall,
        color = MaterialTheme.colorScheme.onSurface,
    )

    Spacer(modifier = Modifier.height(16.dp))

    val instructions = listOf(
        stringResource(R.string.pairing_apply_instruction_1),
        stringResource(R.string.pairing_apply_instruction_2),
        stringResource(R.string.pairing_apply_instruction_3),
        stringResource(R.string.pairing_apply_instruction_4),
    )
    NumberedInstructions(instructions)

    Spacer(modifier = Modifier.height(24.dp))

    Text(
        text = stringResource(R.string.pairing_apply_site_header),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    )

    Spacer(modifier = Modifier.height(12.dp))

    // Site selector: 8 chips in 2 rows of 4
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        maxItemsInEachRow = 4,
    ) {
        InfusionSite.entries.forEach { site ->
            val isSelected = state.selectedSite == site
            val siteLabel = siteDisplayName(site)
            val siteDescription = if (isSelected) {
                stringResource(R.string.pairing_apply_site_selected_description, siteLabel)
            } else {
                stringResource(R.string.pairing_apply_site_description, siteLabel)
            }

            FilterChip(
                selected = isSelected,
                onClick = { onIntent(PairingIntent.SelectSite(site)) },
                label = {
                    Text(
                        text = siteLabel,
                        style = MaterialTheme.typography.labelMedium,
                    )
                },
                leadingIcon = if (isSelected) {
                    {
                        Icon(
                            imageVector = Icons.Filled.Check,
                            contentDescription = null,
                            modifier = Modifier.size(FilterChipDefaults.IconSize),
                        )
                    }
                } else {
                    null
                },
                modifier = Modifier.semantics {
                    contentDescription = siteDescription
                },
            )
        }
    }

    Spacer(modifier = Modifier.height(32.dp))

    Button(
        onClick = { onIntent(PairingIntent.PodApplied) },
        enabled = state.selectedSite != null,
        modifier = Modifier
            .fillMaxWidth()
            .semantics {
                contentDescription = if (state.selectedSite != null) {
                    "Confirm pod is applied"
                } else {
                    "Select an infusion site first"
                }
            },
    ) {
        Text(text = stringResource(R.string.pairing_apply_button))
    }
}

// ── Step 6: Start Pod ──────────────────────────────────────────────

@Composable
private fun StartStepContent(
    state: PairingState,
    onIntent: (PairingIntent) -> Unit,
) {
    if (!state.deliveryStarted) {
        // Pre-delivery view
        IllustrationPlaceholder(
            description = "Pod getting ready diagram",
            height = 80,
        )

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = stringResource(R.string.pairing_start_title),
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = stringResource(R.string.pairing_start_explanation),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )

        Spacer(modifier = Modifier.height(12.dp))

        Text(
            text = stringResource(R.string.pairing_start_reminder),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center,
        )

        Spacer(modifier = Modifier.height(32.dp))

        Button(
            onClick = { onIntent(PairingIntent.BeginDelivery) },
            modifier = Modifier
                .fillMaxWidth()
                .semantics { contentDescription = "Begin insulin delivery" },
        ) {
            Text(text = stringResource(R.string.pairing_start_button))
        }
    } else {
        // Delivery in progress
        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = stringResource(R.string.pairing_start_progress_title),
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )

        Spacer(modifier = Modifier.height(24.dp))

        if (state.isProcessing) {
            CircularProgressIndicator(
                modifier = Modifier
                    .size(48.dp)
                    .semantics { contentDescription = "Starting pod, please wait" },
            )
            Spacer(modifier = Modifier.height(16.dp))
        }

        // Activation sub-step checklist
        if (state.activationSubSteps.isNotEmpty()) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                ),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    state.activationSubSteps.forEach { step ->
                        SubStepRow(
                            label = step.label,
                            status = step.status,
                        )
                    }
                }
            }
        }
    }
}

// ── Step 7: Complete ───────────────────────────────────────────────

@Composable
private fun CompleteStepContent(
    state: PairingState,
    onIntent: (PairingIntent) -> Unit,
) {
    // Success placeholder
    val successDescription = stringResource(R.string.pairing_complete_success_description)
    SuccessPlaceholder(
        modifier = Modifier.semantics {
            contentDescription = successDescription
        },
    )

    Spacer(modifier = Modifier.height(24.dp))

    Text(
        text = stringResource(R.string.pairing_complete_title),
        style = MaterialTheme.typography.headlineMedium,
        color = PodConnected,
    )

    Spacer(modifier = Modifier.height(24.dp))

    // Pod summary card
    state.activationResult?.let { result ->
        PodSummaryCard(result = result)
    }

    Spacer(modifier = Modifier.height(16.dp))

    Text(
        text = stringResource(R.string.pairing_complete_message),
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurface,
        textAlign = TextAlign.Center,
    )

    Spacer(modifier = Modifier.height(32.dp))

    Button(
        onClick = { onIntent(PairingIntent.GoToDashboard) },
        modifier = Modifier
            .fillMaxWidth()
            .semantics { contentDescription = "Go to dashboard" },
    ) {
        Text(text = stringResource(R.string.pairing_complete_button))
    }
}

/**
 * Summary card showing pod information after successful activation.
 */
@Composable
private fun PodSummaryCard(result: com.openpod.domain.pod.PodActivationResult) {
    val expiresFormatted = java.time.format.DateTimeFormatter
        .ofPattern("MMM d, h:mm a")
        .withZone(java.time.ZoneId.systemDefault())
        .format(result.expiresAt)

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            SummaryRow(
                label = stringResource(R.string.pairing_complete_uid_label),
                value = result.uid,
            )
            SummaryRow(
                label = stringResource(R.string.pairing_complete_reservoir_label),
                value = stringResource(R.string.pairing_complete_reservoir_value, result.reservoir),
            )
            SummaryRow(
                label = stringResource(R.string.pairing_complete_expires_label),
                value = expiresFormatted,
            )
            SummaryRow(
                label = stringResource(R.string.pairing_complete_firmware_label),
                value = result.firmwareVersion,
            )
        }
    }
}

@Composable
private fun SummaryRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.Medium,
        )
    }
}

// ── Shared components ──────────────────────────────────────────────

/**
 * A row in the connection/activation sub-step checklist.
 */
@Composable
private fun SubStepRow(
    label: String,
    status: SubStepStatus,
) {
    val accessibilityLabel = when (status) {
        SubStepStatus.COMPLETED -> "$label, complete"
        SubStepStatus.IN_PROGRESS -> "$label, in progress"
        SubStepStatus.PENDING -> "$label, pending"
        SubStepStatus.FAILED -> "$label, failed"
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .semantics { contentDescription = accessibilityLabel },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Status indicator
        Box(modifier = Modifier.size(24.dp), contentAlignment = Alignment.Center) {
            when (status) {
                SubStepStatus.COMPLETED -> {
                    Icon(
                        imageVector = Icons.Filled.Check,
                        contentDescription = null,
                        tint = PodConnected,
                        modifier = Modifier.size(18.dp),
                    )
                }
                SubStepStatus.IN_PROGRESS -> {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                    )
                }
                SubStepStatus.PENDING -> {
                    Canvas(modifier = Modifier.size(10.dp)) {
                        drawCircle(
                            color = Color.Gray.copy(alpha = 0.4f),
                        )
                    }
                }
                SubStepStatus.FAILED -> {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }

        Spacer(modifier = Modifier.width(12.dp))

        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = when (status) {
                SubStepStatus.COMPLETED -> MaterialTheme.colorScheme.onSurface
                SubStepStatus.IN_PROGRESS -> MaterialTheme.colorScheme.onSurface
                SubStepStatus.PENDING -> MaterialTheme.colorScheme.onSurfaceVariant
                SubStepStatus.FAILED -> MaterialTheme.colorScheme.error
            },
        )
    }
}

/**
 * Numbered instruction list used in fill and apply steps.
 */
@Composable
private fun NumberedInstructions(instructions: List<String>) {
    instructions.forEachIndexed { index, instruction ->
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
        ) {
            Text(
                text = "${index + 1}.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.width(24.dp),
            )
            Text(
                text = instruction,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Placeholder for illustrations that will be replaced with actual assets.
 */
@Composable
private fun IllustrationPlaceholder(
    description: String,
    height: Int = 120,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(height.dp)
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant,
                shape = RoundedCornerShape(12.dp),
            )
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "[Illustration]",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Animated concentric circles for the BLE scanning state.
 */
@Composable
private fun ScanningAnimation(modifier: Modifier = Modifier) {
    val infiniteTransition = rememberInfiniteTransition(label = "scanPulse")
    val pulseProgress by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "pulseProgress",
    )

    val primaryColor = MaterialTheme.colorScheme.primary

    Canvas(modifier = modifier) {
        val maxRadius = size.minDimension / 2

        // Three concentric expanding circles with staggered phases
        for (i in 0..2) {
            val phase = (pulseProgress + i * 0.33f) % 1f
            val radius = maxRadius * phase
            val alpha = (1f - phase).coerceIn(0f, 0.6f)
            drawCircle(
                color = primaryColor.copy(alpha = alpha),
                radius = radius,
            )
        }

        // Center dot
        drawCircle(
            color = primaryColor,
            radius = 8.dp.toPx(),
        )
    }
}

/**
 * Success animation placeholder for the completion step.
 */
@Composable
private fun SuccessPlaceholder(modifier: Modifier = Modifier) {
    val infiniteTransition = rememberInfiniteTransition(label = "successPulse")
    val scale by infiniteTransition.animateFloat(
        initialValue = 0.9f,
        targetValue = 1.1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1500, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "successScale",
    )

    Box(
        modifier = modifier.size(100.dp),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.size((80 * scale).dp)) {
            drawCircle(color = PodConnected.copy(alpha = 0.2f))
        }
        Icon(
            imageVector = Icons.Filled.Check,
            contentDescription = null,
            tint = PodConnected,
            modifier = Modifier.size(48.dp),
        )
    }
}

/**
 * Cancel confirmation alert dialog.
 */
@Composable
private fun CancelConfirmationDialog(
    isPastPrime: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(text = stringResource(R.string.pairing_cancel_dialog_title))
        },
        text = {
            Text(
                text = if (isPastPrime) {
                    stringResource(R.string.pairing_cancel_dialog_message_after_prime)
                } else {
                    stringResource(R.string.pairing_cancel_dialog_message)
                },
            )
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                modifier = Modifier.semantics {
                    contentDescription = "Confirm cancel pod setup"
                },
            ) {
                Text(
                    text = stringResource(R.string.pairing_cancel_dialog_confirm),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.semantics {
                    contentDescription = "Continue pod setup"
                },
            ) {
                Text(text = stringResource(R.string.pairing_cancel_dialog_dismiss))
            }
        },
    )
}

// ── Helpers ────────────────────────────────────────────────────────

/**
 * Returns the localized display name for an [InfusionSite].
 */
@Composable
private fun siteDisplayName(site: InfusionSite): String = when (site) {
    InfusionSite.ABDOMEN_LEFT -> stringResource(R.string.site_abdomen_left)
    InfusionSite.ABDOMEN_RIGHT -> stringResource(R.string.site_abdomen_right)
    InfusionSite.ARM_LEFT -> stringResource(R.string.site_arm_left)
    InfusionSite.ARM_RIGHT -> stringResource(R.string.site_arm_right)
    InfusionSite.THIGH_LEFT -> stringResource(R.string.site_thigh_left)
    InfusionSite.THIGH_RIGHT -> stringResource(R.string.site_thigh_right)
    InfusionSite.BACK_LEFT -> stringResource(R.string.site_back_left)
    InfusionSite.BACK_RIGHT -> stringResource(R.string.site_back_right)
}

/** Total number of steps in the pairing wizard. */
private const val TOTAL_STEPS = 7
