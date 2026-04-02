package com.openpod.feature.bolus

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.openpod.core.ui.component.ProgressRing
import com.openpod.core.ui.mvi.CollectEffects

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BolusScreen(
    onBack: () -> Unit = {},
    viewModel: BolusViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    val blockedMessage = stringResource(R.string.bolus_blocked, "%s")
    CollectEffects(viewModel.effect) { effect ->
        when (effect) {
            BolusEffect.NavigateBack -> onBack()
            is BolusEffect.ShowError -> snackbarHostState.showSnackbar(effect.message)
            is BolusEffect.SafetyGateFailure -> {
                val reasons = effect.failures.joinToString(", ") { it::class.simpleName ?: "Unknown" }
                snackbarHostState.showSnackbar(blockedMessage.format(reasons))
            }
        }
    }

    Scaffold(
        topBar = {
            if (state.phase != BolusPhase.DELIVERING) {
                TopAppBar(
                    title = {
                        Text(
                            when (state.phase) {
                                BolusPhase.ENTRY -> stringResource(R.string.bolus_title)
                                BolusPhase.REVIEW -> stringResource(R.string.bolus_review_title)
                                BolusPhase.COMPLETE -> stringResource(R.string.bolus_complete_title)
                                else -> stringResource(R.string.bolus_title)
                            },
                        )
                    },
                    navigationIcon = {
                        if (state.phase == BolusPhase.ENTRY) {
                            IconButton(onClick = onBack) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.bolus_back_cd))
                            }
                        } else if (state.phase == BolusPhase.REVIEW) {
                            IconButton(onClick = { viewModel.onIntent(BolusIntent.BackToEntry) }) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.bolus_back_cd))
                            }
                        }
                    },
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Box(modifier = Modifier.padding(padding)) {
            when (state.phase) {
                BolusPhase.ENTRY -> EntryPhase(state, viewModel::onIntent)
                BolusPhase.REVIEW -> ReviewPhase(state, viewModel::onIntent)
                BolusPhase.DELIVERING -> DeliveringPhase(state, viewModel::onIntent)
                BolusPhase.COMPLETE -> CompletePhase(state, viewModel::onIntent)
            }
        }
    }
}

// ── Entry Phase ────────────────────────────────────────────────────

@Composable
private fun EntryPhase(state: BolusState, onIntent: (BolusIntent) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        OutlinedTextField(
            value = state.carbsText,
            onValueChange = { onIntent(BolusIntent.UpdateCarbs(it)) },
            label = { Text(stringResource(R.string.bolus_carbs_label)) },
            placeholder = { Text("0") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            suffix = { Text(stringResource(R.string.unit_grams_suffix)) },
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = state.bgOverrideText,
                onValueChange = { onIntent(BolusIntent.UpdateBgOverride(it)) },
                label = { Text(stringResource(R.string.bolus_bg_label)) },
                placeholder = {
                    Text(
                        state.podGlucose?.let { stringResource(R.string.bolus_bg_from_pod, it) }
                            ?: stringResource(R.string.bolus_bg_enter),
                    )
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                modifier = Modifier.weight(1f),
                suffix = { Text(stringResource(R.string.unit_mgdl_suffix)) },
            )
            IconButton(onClick = { onIntent(BolusIntent.RefreshBg) }) {
                Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.bolus_bg_refresh_cd))
            }
        }

        if (state.currentIob > 0.01) {
            Text(
                stringResource(R.string.bolus_iob_active, "%.2f".format(state.currentIob)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        state.calculation?.let { calc ->
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                ),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(stringResource(R.string.bolus_calculator_title), style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(8.dp))
                    if (calc.mealDose > 0) CalcRow(stringResource(R.string.bolus_calc_meal_dose), "+%.2f U".format(calc.mealDose))
                    if (calc.correctionDose > 0) CalcRow(stringResource(R.string.bolus_calc_correction), "+%.2f U".format(calc.correctionDose))
                    if (calc.iobDeduction > 0) CalcRow(stringResource(R.string.bolus_calc_iob_deduction), "-%.2f U".format(calc.iobDeduction))
                    Spacer(Modifier.height(4.dp))
                    CalcRow(stringResource(R.string.bolus_calc_suggested), "%.2f U".format(calc.suggestedDose), bold = true)

                    if (calc.suggestedDose > 0) {
                        Spacer(Modifier.height(8.dp))
                        FilledTonalButton(
                            onClick = { onIntent(BolusIntent.UseCalculatedDose) },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(stringResource(R.string.bolus_use_suggested))
                        }
                    }
                }
            }
        }

        OutlinedTextField(
            value = state.unitsText,
            onValueChange = { onIntent(BolusIntent.UpdateUnits(it)) },
            label = { Text(stringResource(R.string.bolus_dose_label)) },
            placeholder = { Text("0.00") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            suffix = { Text(stringResource(R.string.unit_insulin_suffix)) },
        )

        Spacer(Modifier.weight(1f))

        Button(
            onClick = { onIntent(BolusIntent.NextToReview) },
            modifier = Modifier.fillMaxWidth(),
            enabled = state.canReview,
        ) {
            Text(stringResource(R.string.bolus_review_button))
        }
    }
}

@Composable
private fun CalcRow(label: String, value: String, bold: Boolean = false) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            label,
            style = if (bold) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodyMedium,
        )
        Text(
            value,
            style = if (bold) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodyMedium,
        )
    }
}

// ── Review Phase ───────────────────────────────────────────────────

@Composable
private fun ReviewPhase(state: BolusState, onIntent: (BolusIntent) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            ),
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(stringResource(R.string.bolus_summary_title), style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(12.dp))
                Text(
                    "%.2f U".format(state.parsedUnits ?: 0.0),
                    style = MaterialTheme.typography.headlineLarge,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(12.dp))
                state.parsedCarbs?.let { carbs ->
                    if (carbs > 0) CalcRow(stringResource(R.string.bolus_carbs_summary), "$carbs ${stringResource(R.string.unit_grams_suffix)}")
                }
                state.effectiveGlucose?.let { glucose ->
                    CalcRow(stringResource(R.string.bolus_bg_summary), "$glucose ${stringResource(R.string.unit_mgdl_suffix)}")
                }
            }
        }

        Text(
            stringResource(R.string.bolus_pin_prompt),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center,
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
        ) {
            repeat(4) { index ->
                val filled = index < state.pinText.length
                Box(
                    modifier = Modifier
                        .padding(horizontal = 8.dp)
                        .size(20.dp)
                        .clip(CircleShape)
                        .background(
                            if (filled) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.surfaceContainerHighest,
                        )
                        .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape),
                )
            }
        }

        if (state.pinError) {
            Text(
                stringResource(R.string.bolus_pin_incorrect),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
            )
        }

        NumberPad(
            onDigit = { digit ->
                if (state.pinText.length < 4) {
                    onIntent(BolusIntent.UpdatePin(state.pinText + digit))
                }
            },
            onDelete = {
                if (state.pinText.isNotEmpty()) {
                    onIntent(BolusIntent.UpdatePin(state.pinText.dropLast(1)))
                }
            },
        )

        Spacer(Modifier.weight(1f))

        Button(
            onClick = { onIntent(BolusIntent.Deliver) },
            modifier = Modifier.fillMaxWidth(),
            enabled = state.isAuthenticated,
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.error,
            ),
        ) {
            Icon(Icons.Filled.WaterDrop, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.bolus_deliver_button, "%.2f".format(state.parsedUnits ?: 0.0)))
        }
    }
}

@Composable
private fun NumberPad(onDigit: (Char) -> Unit, onDelete: () -> Unit) {
    val rows = listOf("123", "456", "789")
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        rows.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                row.forEach { digit ->
                    NumKey(digit.toString()) { onDigit(digit) }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Spacer(Modifier.size(64.dp))
            NumKey("0") { onDigit('0') }
            NumKey("\u232B") { onDelete() }
        }
    }
}

@Composable
private fun NumKey(label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(64.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = MaterialTheme.typography.headlineSmall)
    }
}

// ── Delivering Phase ───────────────────────────────────────────────

@Composable
private fun DeliveringPhase(state: BolusState, onIntent: (BolusIntent) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        ProgressRing(
            progress = state.deliveryPercent,
            label = "%.2f U".format(state.deliveredUnits),
            sublabel = stringResource(R.string.bolus_of_total, "%.2f".format(state.requestedUnits)),
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(200.dp),
        )

        Spacer(Modifier.height(24.dp))

        Text(
            "${(state.deliveryPercent * 100).toInt()}%",
            style = MaterialTheme.typography.headlineMedium,
        )

        val pulses = (state.requestedUnits / 0.05).toInt()
        val deliveredPulses = (state.deliveredUnits / 0.05).toInt()
        Text(
            stringResource(R.string.bolus_pulses_progress, deliveredPulses, pulses),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        val minutes = state.elapsedSeconds / 60
        val seconds = state.elapsedSeconds % 60
        Text(
            stringResource(R.string.bolus_elapsed, minutes, seconds),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(48.dp))

        if (state.showCancelConfirm) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                OutlinedButton(onClick = { onIntent(BolusIntent.DismissCancelDialog) }) {
                    Text(stringResource(R.string.bolus_continue))
                }
                Button(
                    onClick = { onIntent(BolusIntent.ConfirmCancel) },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                    ),
                ) {
                    Text(stringResource(R.string.bolus_yes_cancel))
                }
            }
        } else {
            OutlinedButton(
                onClick = { onIntent(BolusIntent.RequestCancel) },
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.error,
                ),
            ) {
                Text(stringResource(R.string.bolus_cancel_bolus))
            }
        }
    }
}

// ── Complete Phase ─────────────────────────────────────────────────

@Composable
private fun CompletePhase(state: BolusState, onIntent: (BolusIntent) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            Icons.Filled.Check,
            contentDescription = null,
            modifier = Modifier
                .size(72.dp)
                .clip(CircleShape)
                .background(
                    if (state.wasCancelled) MaterialTheme.colorScheme.errorContainer
                    else MaterialTheme.colorScheme.primaryContainer,
                )
                .padding(16.dp),
            tint = if (state.wasCancelled) MaterialTheme.colorScheme.onErrorContainer
            else MaterialTheme.colorScheme.onPrimaryContainer,
        )

        Spacer(Modifier.height(24.dp))

        Text(
            if (state.wasCancelled) stringResource(R.string.bolus_cancelled) else stringResource(R.string.bolus_completed),
            style = MaterialTheme.typography.headlineSmall,
        )

        Spacer(Modifier.height(8.dp))

        Text(
            stringResource(R.string.bolus_delivered_amount, "%.2f".format(state.deliveredUnits)),
            style = MaterialTheme.typography.titleLarge,
        )

        if (state.wasCancelled) {
            Text(
                stringResource(R.string.bolus_of_requested, "%.2f".format(state.requestedUnits)),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        state.finalRecord?.let { record ->
            Spacer(Modifier.height(16.dp))
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                ),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    CalcRow(stringResource(R.string.bolus_type_label), record.bolusType.name.lowercase().replaceFirstChar { it.uppercase() })
                    if (record.carbsGrams > 0) CalcRow(stringResource(R.string.bolus_carbs_summary), "${record.carbsGrams} ${stringResource(R.string.unit_grams_suffix)}")
                    record.glucoseMgDl?.let { CalcRow(stringResource(R.string.bolus_bg_summary), "$it ${stringResource(R.string.unit_mgdl_suffix)}") }
                }
            }
        }

        Spacer(Modifier.height(32.dp))

        Button(
            onClick = { onIntent(BolusIntent.Done) },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.bolus_done))
        }
    }
}
