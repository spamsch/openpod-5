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
import androidx.compose.ui.res.stringResource
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
    onNavigateToPairing: () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var showResetDialog by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    CollectEffects(viewModel.effect) { effect ->
        when (effect) {
            is SettingsEffect.ShowToast -> snackbarHostState.showSnackbar(effect.message)
            SettingsEffect.NavigateToPairing -> onNavigateToPairing()
        }
    }

    // Edit dialogs
    when (state.activeDialog) {
        SettingsDialog.EditDia -> NumberEditDialog(
            title = stringResource(R.string.settings_dialog_dia),
            value = state.editValue,
            onValueChange = { viewModel.onIntent(SettingsIntent.UpdateEditValue(it)) },
            error = state.editError,
            suffix = stringResource(R.string.settings_suffix_hours),
            keyboardType = KeyboardType.Decimal,
            onSave = { viewModel.onIntent(SettingsIntent.SaveEdit) },
            onDismiss = { viewModel.onIntent(SettingsIntent.DismissDialog) },
        )
        SettingsDialog.EditIcRatio -> NumberEditDialog(
            title = stringResource(R.string.settings_dialog_ic_ratio),
            value = state.editValue,
            onValueChange = { viewModel.onIntent(SettingsIntent.UpdateEditValue(it)) },
            error = state.editError,
            suffix = stringResource(R.string.settings_suffix_g_per_u),
            onSave = { viewModel.onIntent(SettingsIntent.SaveEdit) },
            onDismiss = { viewModel.onIntent(SettingsIntent.DismissDialog) },
        )
        SettingsDialog.EditCorrectionFactor -> NumberEditDialog(
            title = stringResource(R.string.settings_dialog_correction_factor),
            value = state.editValue,
            onValueChange = { viewModel.onIntent(SettingsIntent.UpdateEditValue(it)) },
            error = state.editError,
            suffix = stringResource(R.string.settings_suffix_mgdl_per_u),
            onSave = { viewModel.onIntent(SettingsIntent.SaveEdit) },
            onDismiss = { viewModel.onIntent(SettingsIntent.DismissDialog) },
        )
        SettingsDialog.EditTargetGlucose -> NumberEditDialog(
            title = stringResource(R.string.settings_dialog_target_glucose),
            value = state.editValue,
            onValueChange = { viewModel.onIntent(SettingsIntent.UpdateEditValue(it)) },
            error = state.editError,
            suffix = stringResource(R.string.settings_suffix_mgdl),
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
        SettingsDialog.ConfirmReplacePod -> AlertDialog(
            onDismissRequest = { viewModel.onIntent(SettingsIntent.DismissDialog) },
            title = { Text("Replace Pod?") },
            text = { Text("This will deactivate your current pod. You will need to pair a new one.") },
            confirmButton = {
                TextButton(onClick = { viewModel.onIntent(SettingsIntent.ConfirmReplacePod) }) {
                    Text("Deactivate", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.onIntent(SettingsIntent.DismissDialog) }) {
                    Text("Cancel")
                }
            },
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
                text = stringResource(R.string.settings_title),
                style = MaterialTheme.typography.headlineLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(modifier = Modifier.height(24.dp))
        }

        // ── Insulin Delivery ───────────────────────────────────────────
        item { SettingsSectionHeader(stringResource(R.string.settings_section_insulin_delivery)) }
        item {
            SettingsGroup {
                SettingsRow(
                    label = stringResource(R.string.settings_ic_ratio),
                    value = state.currentIcRatio?.let { "1:$it g" } ?: "--",
                    onClick = { viewModel.onIntent(SettingsIntent.OpenDialog(SettingsDialog.EditIcRatio)) },
                )
                SettingsRowDivider()
                SettingsRow(
                    label = stringResource(R.string.settings_correction_factor),
                    value = state.currentCorrectionFactor?.let { "1:$it ${state.glucoseUnit.displayLabel}" } ?: "--",
                    onClick = { viewModel.onIntent(SettingsIntent.OpenDialog(SettingsDialog.EditCorrectionFactor)) },
                )
                SettingsRowDivider()
                SettingsRow(
                    label = stringResource(R.string.settings_target_glucose),
                    value = state.currentTargetLow?.let { "$it ${state.glucoseUnit.displayLabel}" } ?: "--",
                    onClick = { viewModel.onIntent(SettingsIntent.OpenDialog(SettingsDialog.EditTargetGlucose)) },
                )
                SettingsRowDivider()
                SettingsRow(
                    label = stringResource(R.string.settings_dia),
                    value = state.diaHours?.let { stringResource(R.string.settings_dia_value, "%.1f".format(it)) } ?: "--",
                    onClick = { viewModel.onIntent(SettingsIntent.OpenDialog(SettingsDialog.EditDia)) },
                )
            }
        }

        // ── Security ──────────────────────────────────────────────────
        item { SettingsSectionHeader(stringResource(R.string.settings_section_security)) }
        item {
            SettingsGroup {
                SettingsRow(
                    label = stringResource(R.string.settings_change_pin),
                    onClick = { viewModel.onIntent(SettingsIntent.OpenDialog(SettingsDialog.ChangePin)) },
                )
            }
        }

        // ── App ────────────────────────────────────────────────────────
        item { SettingsSectionHeader(stringResource(R.string.settings_section_app)) }
        item {
            SettingsGroup {
                SettingsRow(
                    label = stringResource(R.string.settings_glucose_units),
                    value = state.glucoseUnit.displayLabel,
                    onClick = { viewModel.onIntent(SettingsIntent.OpenDialog(SettingsDialog.EditGlucoseUnit)) },
                )
                SettingsRowDivider()
                SettingsRow(label = stringResource(R.string.settings_about), value = stringResource(R.string.settings_about_value))
            }
        }

        // ── Pod Management ─────────────────────────────────────────────
        item { SettingsSectionHeader("Pod Management") }
        item {
            SettingsGroup {
                SettingsRow(
                    label = "Replace Pod",
                    labelColor = MaterialTheme.colorScheme.error,
                    onClick = { viewModel.onIntent(SettingsIntent.ReplacePod) },
                )
            }
        }

        // ── Developer (debug builds only) ──────────────────────────────
        if (isDebugBuild) {
            // Pod Data
            item { SettingsSectionHeader("Pod Data") }
            item {
                SettingsGroup {
                    val pod = state.podState
                    SettingsRow(label = "UID", value = pod?.uid ?: "--")
                    SettingsRowDivider()
                    SettingsRow(label = "Firmware", value = pod?.firmwareVersion?.ifEmpty { "--" } ?: "--")
                    SettingsRowDivider()
                    SettingsRow(label = "Reservoir", value = pod?.let { "%.1f U".format(it.reservoirUnits) } ?: "--")
                    SettingsRowDivider()
                    SettingsRow(label = "Connection", value = pod?.connectionState?.name ?: "--")
                    SettingsRowDivider()
                    SettingsRow(label = "Mode", value = pod?.operatingMode?.name ?: "--")
                    SettingsRowDivider()
                    SettingsRow(label = "Delivery", value = pod?.deliveryStatus?.name ?: "--")
                    SettingsRowDivider()
                    SettingsRow(label = "Activated", value = pod?.activatedAt?.toString() ?: "--")
                    SettingsRowDivider()
                    SettingsRow(label = "Last Sync", value = pod?.lastSyncAt?.toString() ?: "--")
                }
            }

            // CGM Data
            item { SettingsSectionHeader("CGM Data") }
            item {
                SettingsGroup {
                    val glucose = state.glucoseReading
                    SettingsRow(label = "Glucose", value = glucose?.let { "${it.valueMgDl} mg/dL" } ?: "--")
                    SettingsRowDivider()
                    SettingsRow(label = "Trend", value = glucose?.trend?.name ?: "--")
                    SettingsRowDivider()
                    SettingsRow(label = "Timestamp", value = glucose?.timestamp?.toString() ?: "--")
                }
            }

            // Developer
            item { SettingsSectionHeader(stringResource(R.string.settings_section_developer)) }
            item {
                SettingsGroup {
                    SettingsRow(
                        label = stringResource(R.string.settings_reset_app_data),
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
        title = { Text(stringResource(R.string.settings_reset_title)) },
        text = { Text(stringResource(R.string.settings_reset_message)) },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(stringResource(R.string.settings_reset_confirm), color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.settings_cancel)) }
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
