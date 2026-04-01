package com.openpod.feature.settings

import com.openpod.core.database.dao.InsulinProfileDao
import com.openpod.core.database.entity.CorrectionFactorSegmentEntity
import com.openpod.core.database.entity.IcRatioSegmentEntity
import com.openpod.core.database.entity.InsulinProfileEntity
import com.openpod.core.database.entity.TargetGlucoseSegmentEntity
import com.openpod.core.datastore.OpenPodPreferences
import com.openpod.core.datastore.PinManager
import com.openpod.core.ui.mvi.MviViewModel
import com.openpod.model.glucose.GlucoseUnit
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import java.time.LocalTime
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val profileDao: InsulinProfileDao,
    private val preferences: OpenPodPreferences,
    private val pinManager: PinManager,
) : MviViewModel<SettingsState, SettingsIntent, SettingsEffect>(
    initialState = SettingsState(),
) {

    init {
        observeSettings()
    }

    override fun handleIntent(intent: SettingsIntent) {
        when (intent) {
            is SettingsIntent.OpenDialog -> openDialog(intent.dialog)
            SettingsIntent.DismissDialog -> updateState {
                copy(activeDialog = null, editValue = "", editError = null,
                    oldPin = "", newPin = "", confirmPin = "", pinChangeStep = 0, pinChangeError = null)
            }
            is SettingsIntent.UpdateEditValue -> updateState { copy(editValue = intent.value, editError = null) }
            SettingsIntent.SaveEdit -> saveEdit()
            is SettingsIntent.SetGlucoseUnit -> setGlucoseUnit(intent.unit)
            is SettingsIntent.SetBiometric -> setBiometric(intent.enabled)
            is SettingsIntent.UpdatePinField -> onUpdatePinField(intent.field, intent.value)
            SettingsIntent.ConfirmPinChange -> onConfirmPinChange()
        }
    }

    private fun observeSettings() {
        launch {
            combine(
                profileDao.observeProfile(),
                preferences.glucoseUnit(),
                preferences.isBiometricEnabled(),
            ) { profile, unit, biometric -> Triple(profile, unit, biometric) }
                .collectLatest { (profile, unit, biometric) ->
                    val now = LocalTime.now()
                    val icSegments = profileDao.getIcRatioSegments()
                    val cfSegments = profileDao.getCorrectionFactorSegments()
                    val tgSegments = profileDao.getTargetGlucoseSegments()

                    val ic = icSegments.findAt(now)?.ratioGramsPerUnit
                    val cf = cfSegments.findAt(now)?.factorMgDlPerUnit
                    val tg = tgSegments.findAt(now)

                    updateState {
                        copy(
                            diaHours = profile?.durationOfInsulinAction,
                            insulinTypeName = profile?.insulinType?.name,
                            currentIcRatio = ic,
                            currentCorrectionFactor = cf,
                            currentTargetLow = tg?.lowMgDl,
                            currentTargetHigh = tg?.highMgDl,
                            glucoseUnit = unit,
                            biometricEnabled = biometric,
                            isLoading = false,
                        )
                    }
                }
        }
    }

    private fun openDialog(dialog: SettingsDialog) {
        val prefillValue = when (dialog) {
            SettingsDialog.EditDia -> currentState.diaHours?.toString() ?: ""
            SettingsDialog.EditIcRatio -> currentState.currentIcRatio?.toString() ?: ""
            SettingsDialog.EditCorrectionFactor -> currentState.currentCorrectionFactor?.toString() ?: ""
            SettingsDialog.EditTargetGlucose -> currentState.currentTargetLow?.toString() ?: ""
            else -> ""
        }
        updateState { copy(activeDialog = dialog, editValue = prefillValue, editError = null) }
    }

    private fun saveEdit() {
        launch {
            val value = currentState.editValue
            when (currentState.activeDialog) {
                SettingsDialog.EditDia -> {
                    val dia = value.toDoubleOrNull()
                    if (dia == null || dia !in 2.0..5.0) {
                        updateState { copy(editError = "Must be 2.0–5.0 hours") }; return@launch
                    }
                    val profile = profileDao.getProfile() ?: return@launch
                    profileDao.upsertProfile(profile.copy(durationOfInsulinAction = dia))
                }
                SettingsDialog.EditIcRatio -> {
                    val ratio = value.toIntOrNull()
                    if (ratio == null || ratio !in 1..150) {
                        updateState { copy(editError = "Must be 1–150") }; return@launch
                    }
                    profileDao.replaceIcRatioSegments(listOf(
                        IcRatioSegmentEntity(profileId = 1L, startTime = LocalTime.MIDNIGHT, endTime = LocalTime.MIDNIGHT, ratioGramsPerUnit = ratio),
                    ))
                }
                SettingsDialog.EditCorrectionFactor -> {
                    val cf = value.toIntOrNull()
                    if (cf == null || cf !in 1..400) {
                        updateState { copy(editError = "Must be 1–400") }; return@launch
                    }
                    profileDao.replaceCorrectionFactorSegments(listOf(
                        CorrectionFactorSegmentEntity(profileId = 1L, startTime = LocalTime.MIDNIGHT, endTime = LocalTime.MIDNIGHT, factorMgDlPerUnit = cf),
                    ))
                }
                SettingsDialog.EditTargetGlucose -> {
                    val target = value.toIntOrNull()
                    if (target == null || target !in 70..200) {
                        updateState { copy(editError = "Must be 70–200 mg/dL") }; return@launch
                    }
                    profileDao.replaceTargetGlucoseSegments(listOf(
                        TargetGlucoseSegmentEntity(profileId = 1L, startTime = LocalTime.MIDNIGHT, endTime = LocalTime.MIDNIGHT, lowMgDl = target, highMgDl = target),
                    ))
                }
                else -> return@launch
            }
            updateState { copy(activeDialog = null, editValue = "", editError = null) }
            emitEffect(SettingsEffect.ShowToast("Saved"))
        }
    }

    private fun setGlucoseUnit(unit: GlucoseUnit) {
        launch {
            preferences.setGlucoseUnit(unit)
            updateState { copy(activeDialog = null) }
        }
    }

    private fun setBiometric(enabled: Boolean) {
        launch { preferences.setBiometricEnabled(enabled) }
    }

    private fun onUpdatePinField(field: Int, value: String) {
        if (value.length > 6) return
        updateState {
            when (field) {
                0 -> copy(oldPin = value, pinChangeError = null)
                1 -> copy(newPin = value, pinChangeError = null)
                2 -> copy(confirmPin = value, pinChangeError = null)
                else -> this
            }
        }
    }

    private fun onConfirmPinChange() {
        val s = currentState
        when (s.pinChangeStep) {
            0 -> {
                // Verify old PIN
                launch {
                    if (pinManager.verifyPin(s.oldPin)) {
                        updateState { copy(pinChangeStep = 1) }
                    } else {
                        updateState { copy(pinChangeError = "Incorrect PIN") }
                    }
                }
            }
            1 -> {
                if (s.newPin.length < 4) {
                    updateState { copy(pinChangeError = "PIN must be at least 4 digits") }; return
                }
                updateState { copy(pinChangeStep = 2) }
            }
            2 -> {
                if (s.newPin != s.confirmPin) {
                    updateState { copy(pinChangeError = "PINs don't match") }; return
                }
                launch {
                    pinManager.storePin(s.newPin)
                    updateState { copy(activeDialog = null, oldPin = "", newPin = "", confirmPin = "", pinChangeStep = 0) }
                    emitEffect(SettingsEffect.ShowToast("PIN changed"))
                }
            }
        }
    }

    private fun <T> List<T>.findAt(time: LocalTime): T? where T : HasTimeRange =
        firstOrNull { segment ->
            val start = segment.startTime
            val end = segment.endTime
            if (start == end) true // 24h segment
            else if (start < end) time in start..end
            else time >= start || time < end // wraps midnight
        } ?: firstOrNull()
}

private interface HasTimeRange {
    val startTime: LocalTime
    val endTime: LocalTime
}

private val IcRatioSegmentEntity.asHasTimeRange: HasTimeRange
    get() = object : HasTimeRange {
        override val startTime = this@asHasTimeRange.startTime
        override val endTime = this@asHasTimeRange.endTime
    }

@Suppress("UNCHECKED_CAST")
private fun <T> List<T>.findAt(time: LocalTime): T? {
    return firstOrNull { segment ->
        val start = when (segment) {
            is IcRatioSegmentEntity -> segment.startTime
            is CorrectionFactorSegmentEntity -> segment.startTime
            is TargetGlucoseSegmentEntity -> segment.startTime
            else -> return@firstOrNull false
        }
        val end = when (segment) {
            is IcRatioSegmentEntity -> segment.endTime
            is CorrectionFactorSegmentEntity -> segment.endTime
            is TargetGlucoseSegmentEntity -> segment.endTime
            else -> return@firstOrNull false
        }
        if (start == end) true
        else if (start < end) time in start..end
        else time >= start || time < end
    } ?: firstOrNull()
}
