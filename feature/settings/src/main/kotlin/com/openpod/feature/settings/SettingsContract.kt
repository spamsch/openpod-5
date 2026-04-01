package com.openpod.feature.settings

import com.openpod.core.ui.mvi.UiEffect
import com.openpod.core.ui.mvi.UiIntent
import com.openpod.core.ui.mvi.UiState
import com.openpod.model.glucose.GlucoseUnit

data class SettingsState(
    val diaHours: Double? = null,
    val insulinTypeName: String? = null,
    val currentIcRatio: Int? = null,
    val currentCorrectionFactor: Int? = null,
    val currentTargetLow: Int? = null,
    val currentTargetHigh: Int? = null,
    val glucoseUnit: GlucoseUnit = GlucoseUnit.MG_DL,
    val biometricEnabled: Boolean = false,
    val isLoading: Boolean = true,
    val activeDialog: SettingsDialog? = null,
    val editValue: String = "",
    val editError: String? = null,
    // PIN change
    val oldPin: String = "",
    val newPin: String = "",
    val confirmPin: String = "",
    val pinChangeStep: Int = 0,
    val pinChangeError: String? = null,
) : UiState

sealed interface SettingsDialog {
    data object EditDia : SettingsDialog
    data object EditIcRatio : SettingsDialog
    data object EditCorrectionFactor : SettingsDialog
    data object EditTargetGlucose : SettingsDialog
    data object EditGlucoseUnit : SettingsDialog
    data object ChangePin : SettingsDialog
}

sealed interface SettingsIntent : UiIntent {
    data class OpenDialog(val dialog: SettingsDialog) : SettingsIntent
    data object DismissDialog : SettingsIntent
    data class UpdateEditValue(val value: String) : SettingsIntent
    data object SaveEdit : SettingsIntent
    data class SetGlucoseUnit(val unit: GlucoseUnit) : SettingsIntent
    data class SetBiometric(val enabled: Boolean) : SettingsIntent
    data class UpdatePinField(val field: Int, val value: String) : SettingsIntent
    data object ConfirmPinChange : SettingsIntent
}

sealed interface SettingsEffect : UiEffect {
    data class ShowToast(val message: String) : SettingsEffect
}
