package com.openpod.feature.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.openpod.core.datastore.AppDataResetter
import com.openpod.core.ui.mvi.CollectEffects
import com.openpod.feature.settings.dialogs.GlucoseUnitDialog
import com.openpod.feature.settings.dialogs.NumberEditDialog
import com.openpod.feature.settings.dialogs.PinChangeDialog
import kotlinx.coroutines.launch
import kotlin.system.exitProcess

@Composable
fun SettingsScreen(
    appDataResetter: AppDataResetter? = null,
    isDebugBuild: Boolean = false,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var showResetDialog by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    CollectEffects(viewModel.effect) { effect ->
        when (effect) {
            is SettingsEffect.ShowToast -> snackbarHostState.showSnackbar(effect.message)
        }
    }

    // Edit dialogs
    when (state.activeDialog) {
        SettingsDialog.EditDia -> NumberEditDialog(
            title = "Duration of Insulin Action",
            value = state.editValue,
            onValueChange = { viewModel.onIntent(SettingsIntent.UpdateEditValue(it)) },
            error = state.editError,
            suffix = "hours",
            keyboardType = KeyboardType.Decimal,
            onSave = { viewModel.onIntent(SettingsIntent.SaveEdit) },
            onDismiss = { viewModel.onIntent(SettingsIntent.DismissDialog) },
        )
        SettingsDialog.EditIcRatio -> NumberEditDialog(
            title = "Insulin-to-Carb Ratio",
            value = state.editValue,
            onValueChange = { viewModel.onIntent(SettingsIntent.UpdateEditValue(it)) },
            error = state.editError,
            suffix = "g per U",
            onSave = { viewModel.onIntent(SettingsIntent.SaveEdit) },
            onDismiss = { viewModel.onIntent(SettingsIntent.DismissDialog) },
        )
        SettingsDialog.EditCorrectionFactor -> NumberEditDialog(
            title = "Correction Factor",
            value = state.editValue,
            onValueChange = { viewModel.onIntent(SettingsIntent.UpdateEditValue(it)) },
            error = state.editError,
            suffix = "mg/dL per U",
            onSave = { viewModel.onIntent(SettingsIntent.SaveEdit) },
            onDismiss = { viewModel.onIntent(SettingsIntent.DismissDialog) },
        )
        SettingsDialog.EditTargetGlucose -> NumberEditDialog(
            title = "Target Glucose",
            value = state.editValue,
            onValueChange = { viewModel.onIntent(SettingsIntent.UpdateEditValue(it)) },
            error = state.editError,
            suffix = "mg/dL",
            onSave = { viewModel.onIntent(SettingsIntent.SaveEdit) },
            onDismiss = { viewModel.onIntent(SettingsIntent.DismissDialog) },
        )
        SettingsDialog.EditGlucoseUnit -> GlucoseUnitDialog(
            currentUnit = state.glucoseUnit,
            onSelect = { viewModel.onIntent(SettingsIntent.SetGlucoseUnit(it)) },
            onDismiss = { viewModel.onIntent(SettingsIntent.DismissDialog) },
        )
        SettingsDialog.ChangePin -> PinChangeDialog(
            step = state.pinChangeStep,
            oldPin = state.oldPin,
            newPin = state.newPin,
            confirmPin = state.confirmPin,
            error = state.pinChangeError,
            onUpdateField = { field, value -> viewModel.onIntent(SettingsIntent.UpdatePinField(field, value)) },
            onConfirm = { viewModel.onIntent(SettingsIntent.ConfirmPinChange) },
            onDismiss = { viewModel.onIntent(SettingsIntent.DismissDialog) },
        )
        null -> {}
    }

    if (showResetDialog) {
        ResetConfirmationDialog(
            onConfirm = {
                showResetDialog = false
                scope.launch {
                    appDataResetter?.resetAllData()
                    exitProcess(0)
                }
            },
            onDismiss = { showResetDialog = false },
        )
    }

    SnackbarHost(snackbarHostState)

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp),
    ) {
        item {
            Spacer(modifier = Modifier.height(24.dp))
            Text(
                text = "Settings",
                style = MaterialTheme.typography.headlineLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(modifier = Modifier.height(24.dp))
        }

        // ── Insulin Delivery ───────────────────────────────────────────
        item { SettingsSectionHeader("Insulin Delivery") }
        item {
            SettingsGroup {
                SettingsRow(
                    label = "IC Ratio",
                    value = state.currentIcRatio?.let { "1:$it g" } ?: "--",
                    onClick = { viewModel.onIntent(SettingsIntent.OpenDialog(SettingsDialog.EditIcRatio)) },
                )
                SettingsRowDivider()
                SettingsRow(
                    label = "Correction Factor",
                    value = state.currentCorrectionFactor?.let { "1:$it ${state.glucoseUnit.displayLabel}" } ?: "--",
                    onClick = { viewModel.onIntent(SettingsIntent.OpenDialog(SettingsDialog.EditCorrectionFactor)) },
                )
                SettingsRowDivider()
                SettingsRow(
                    label = "Target Glucose",
                    value = state.currentTargetLow?.let { "$it ${state.glucoseUnit.displayLabel}" } ?: "--",
                    onClick = { viewModel.onIntent(SettingsIntent.OpenDialog(SettingsDialog.EditTargetGlucose)) },
                )
                SettingsRowDivider()
                SettingsRow(
                    label = "Duration of Insulin Action",
                    value = state.diaHours?.let { "%.1f hours".format(it) } ?: "--",
                    onClick = { viewModel.onIntent(SettingsIntent.OpenDialog(SettingsDialog.EditDia)) },
                )
            }
        }

        // ── Security ──────────────────────────────────────────────────
        item { SettingsSectionHeader("Security") }
        item {
            SettingsGroup {
                SettingsRow(
                    label = "Change PIN",
                    onClick = { viewModel.onIntent(SettingsIntent.OpenDialog(SettingsDialog.ChangePin)) },
                )
            }
        }

        // ── App ────────────────────────────────────────────────────────
        item { SettingsSectionHeader("App") }
        item {
            SettingsGroup {
                SettingsRow(
                    label = "Glucose Units",
                    value = state.glucoseUnit.displayLabel,
                    onClick = { viewModel.onIntent(SettingsIntent.OpenDialog(SettingsDialog.EditGlucoseUnit)) },
                )
                SettingsRowDivider()
                SettingsRow(label = "About", value = "OpenPod v1.0")
            }
        }

        // ── Developer (debug builds only) ──────────────────────────────
        if (isDebugBuild) {
            item { SettingsSectionHeader("Developer") }
            item {
                SettingsGroup {
                    SettingsRow(
                        label = "Reset App Data",
                        labelColor = MaterialTheme.colorScheme.error,
                        onClick = { showResetDialog = true },
                    )
                }
            }
        }

        item { Spacer(modifier = Modifier.height(32.dp)) }
    }
}

@Composable
private fun ResetConfirmationDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Reset App Data") },
        text = {
            Text(
                "This will erase all settings, pairing data, and history. " +
                    "The app will restart at the onboarding screen.",
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text("Reset", color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@Composable
private fun SettingsSectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 24.dp, bottom = 8.dp),
    )
}

@Composable
private fun SettingsGroup(content: @Composable () -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column { content() }
    }
}

@Composable
private fun SettingsRow(
    label: String,
    value: String? = null,
    labelColor: androidx.compose.ui.graphics.Color = androidx.compose.ui.graphics.Color.Unspecified,
    onClick: (() -> Unit)? = null,
) {
    ListItem(
        headlineContent = {
            Text(text = label, style = MaterialTheme.typography.bodyLarge, color = labelColor)
        },
        trailingContent = {
            if (value != null) {
                Text(text = value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        modifier = Modifier.clickable(onClick = onClick ?: {}),
    )
}

@Composable
private fun SettingsRowDivider() {
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, modifier = Modifier.padding(horizontal = 16.dp))
}
