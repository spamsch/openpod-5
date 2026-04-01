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

    CollectEffects(viewModel.effect) { effect ->
        when (effect) {
            BolusEffect.NavigateBack -> onBack()
            is BolusEffect.ShowError -> snackbarHostState.showSnackbar(effect.message)
        }
    }

    Scaffold(
        topBar = {
            if (state.phase != BolusPhase.DELIVERING) {
                TopAppBar(
                    title = {
                        Text(
                            when (state.phase) {
                                BolusPhase.ENTRY -> "Bolus"
                                BolusPhase.REVIEW -> "Review Bolus"
                                BolusPhase.COMPLETE -> "Bolus Complete"
                                else -> "Bolus"
                            },
                        )
                    },
                    navigationIcon = {
                        if (state.phase == BolusPhase.ENTRY) {
                            IconButton(onClick = onBack) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                            }
                        } else if (state.phase == BolusPhase.REVIEW) {
                            IconButton(onClick = { viewModel.onIntent(BolusIntent.BackToEntry) }) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
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
        // Carbs input (primary)
        OutlinedTextField(
            value = state.carbsText,
            onValueChange = { onIntent(BolusIntent.UpdateCarbs(it)) },
            label = { Text("Carbs") },
            placeholder = { Text("0") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            suffix = { Text("g") },
        )

        // Blood glucose: pod value + override
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = state.bgOverrideText,
                onValueChange = { onIntent(BolusIntent.UpdateBgOverride(it)) },
                label = { Text("Blood Glucose") },
                placeholder = {
                    Text(
                        state.podGlucose?.let { "$it (from pod)" } ?: "Enter BG",
                    )
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                modifier = Modifier.weight(1f),
                suffix = { Text("mg/dL") },
            )
            IconButton(onClick = { onIntent(BolusIntent.RefreshBg) }) {
                Icon(Icons.Filled.Refresh, contentDescription = "Refresh BG from pod")
            }
        }

        // Current IOB info
        if (state.currentIob > 0.01) {
            Text(
                "IOB: %.2f U active".format(state.currentIob),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // Calculator breakdown
        state.calculation?.let { calc ->
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                ),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Bolus Calculator", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(8.dp))
                    if (calc.mealDose > 0) CalcRow("Meal dose", "+%.2f U".format(calc.mealDose))
                    if (calc.correctionDose > 0) CalcRow("Correction", "+%.2f U".format(calc.correctionDose))
                    if (calc.iobDeduction > 0) CalcRow("IOB deduction", "-%.2f U".format(calc.iobDeduction))
                    Spacer(Modifier.height(4.dp))
                    CalcRow("Suggested", "%.2f U".format(calc.suggestedDose), bold = true)

                    if (calc.suggestedDose > 0) {
                        Spacer(Modifier.height(8.dp))
                        FilledTonalButton(
                            onClick = { onIntent(BolusIntent.UseCalculatedDose) },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("Use Suggested Dose")
                        }
                    }
                }
            }
        }

        // Dose (editable, filled by "Use Suggested" or manual entry)
        OutlinedTextField(
            value = state.unitsText,
            onValueChange = { onIntent(BolusIntent.UpdateUnits(it)) },
            label = { Text("Dose") },
            placeholder = { Text("0.00") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            suffix = { Text("U") },
        )

        Spacer(Modifier.weight(1f))

        // Review button
        Button(
            onClick = { onIntent(BolusIntent.NextToReview) },
            modifier = Modifier.fillMaxWidth(),
            enabled = state.canReview,
        ) {
            Text("Review Bolus")
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
        // Summary card
        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            ),
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Bolus Summary", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(12.dp))
                Text(
                    "%.2f U".format(state.parsedUnits ?: 0.0),
                    style = MaterialTheme.typography.headlineLarge,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(12.dp))
                state.parsedCarbs?.let { carbs ->
                    if (carbs > 0) CalcRow("Carbs", "$carbs g")
                }
                state.effectiveGlucose?.let { glucose ->
                    CalcRow("Blood Glucose", "$glucose mg/dL")
                }
            }
        }

        // PIN entry
        Text(
            "Enter PIN to confirm delivery",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center,
        )

        // PIN dots
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
                "Incorrect PIN",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
            )
        }

        // Number pad
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

        // Deliver button
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
            Text("Deliver %.2f U".format(state.parsedUnits ?: 0.0))
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
            sublabel = "of %.2f U".format(state.requestedUnits),
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
            "$deliveredPulses of $pulses pulses",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        val minutes = state.elapsedSeconds / 60
        val seconds = state.elapsedSeconds % 60
        Text(
            "Elapsed: %d:%02d".format(minutes, seconds),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(48.dp))

        if (state.showCancelConfirm) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                OutlinedButton(onClick = { onIntent(BolusIntent.DismissCancelDialog) }) {
                    Text("Continue")
                }
                Button(
                    onClick = { onIntent(BolusIntent.ConfirmCancel) },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                    ),
                ) {
                    Text("Yes, Cancel")
                }
            }
        } else {
            OutlinedButton(
                onClick = { onIntent(BolusIntent.RequestCancel) },
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.error,
                ),
            ) {
                Text("Cancel Bolus")
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
            if (state.wasCancelled) "Bolus Cancelled" else "Bolus Complete",
            style = MaterialTheme.typography.headlineSmall,
        )

        Spacer(Modifier.height(8.dp))

        Text(
            "%.2f U delivered".format(state.deliveredUnits),
            style = MaterialTheme.typography.titleLarge,
        )

        if (state.wasCancelled) {
            Text(
                "of %.2f U requested".format(state.requestedUnits),
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
                    CalcRow("Type", record.bolusType.name.lowercase().replaceFirstChar { it.uppercase() })
                    if (record.carbsGrams > 0) CalcRow("Carbs", "${record.carbsGrams} g")
                    record.glucoseMgDl?.let { CalcRow("BG", "$it mg/dL") }
                }
            }
        }

        Spacer(Modifier.height(32.dp))

        Button(
            onClick = { onIntent(BolusIntent.Done) },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Done")
        }
    }
}
