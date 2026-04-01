package com.openpod.feature.bolus

import com.openpod.core.datastore.PinManager
import com.openpod.core.ui.mvi.MviViewModel
import com.openpod.domain.pod.PodManager
import com.openpod.model.insulin.BolusCalculator
import com.openpod.model.insulin.BolusRecord
import com.openpod.model.insulin.BolusType
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import timber.log.Timber
import java.time.Instant
import java.util.UUID
import javax.inject.Inject

@HiltViewModel
class BolusViewModel @Inject constructor(
    private val podManager: PodManager,
    private val pinManager: PinManager,
) : MviViewModel<BolusState, BolusIntent, BolusEffect>(
    initialState = BolusState(),
) {

    init {
        fetchCurrentValues()
    }

    override fun handleIntent(intent: BolusIntent) {
        when (intent) {
            is BolusIntent.UpdateCarbs -> onUpdateCarbs(intent.text)
            is BolusIntent.UpdateBgOverride -> onUpdateBgOverride(intent.text)
            is BolusIntent.UpdateUnits -> onUpdateUnits(intent.text)
            BolusIntent.RefreshBg -> fetchCurrentValues()
            BolusIntent.UseCalculatedDose -> onUseCalculatedDose()
            BolusIntent.NextToReview -> onNextToReview()
            is BolusIntent.UpdatePin -> onUpdatePin(intent.text)
            BolusIntent.Deliver -> onDeliver()
            BolusIntent.BackToEntry -> updateState { copy(phase = BolusPhase.ENTRY, pinText = "", pinError = false, isAuthenticated = false) }
            BolusIntent.RequestCancel -> updateState { copy(showCancelConfirm = true) }
            BolusIntent.ConfirmCancel -> onConfirmCancel()
            BolusIntent.DismissCancelDialog -> updateState { copy(showCancelConfirm = false) }
            BolusIntent.Done -> emitEffect(BolusEffect.NavigateBack)
        }
    }

    private fun fetchCurrentValues() {
        launch {
            val status = podManager.getStatus().getOrNull() ?: return@launch
            val bg = status.glucoseMgDl
            updateState {
                copy(
                    podGlucose = bg,
                    currentIob = status.iobUnits ?: 0.0,
                    bgOverrideText = bg?.toString() ?: bgOverrideText,
                )
            }
            recalculate()
        }
    }

    private fun onUpdateUnits(text: String) {
        if (text.length > 5) return
        updateState { copy(unitsText = text) }
    }

    private fun onUpdateCarbs(text: String) {
        if (text.length > 3) return
        updateState { copy(carbsText = text) }
        recalculate()
    }

    private fun onUpdateBgOverride(text: String) {
        if (text.length > 3) return
        updateState { copy(bgOverrideText = text) }
        recalculate()
    }

    private fun recalculate() {
        val s = currentState
        val carbs = s.parsedCarbs ?: 0
        val glucose = s.effectiveGlucose
        if (carbs <= 0 && glucose == null) {
            updateState { copy(calculation = null) }
            return
        }
        val calc = BolusCalculator.calculate(
            carbsGrams = carbs,
            glucoseMgDl = glucose,
            iobUnits = s.currentIob,
            settings = s.settings,
        )
        updateState { copy(calculation = calc) }
    }

    private fun onUseCalculatedDose() {
        val dose = currentState.calculation?.suggestedDose ?: return
        if (dose <= 0) return
        updateState { copy(unitsText = "%.2f".format(dose)) }
    }

    private fun onNextToReview() {
        if (!currentState.canReview) return
        updateState { copy(phase = BolusPhase.REVIEW) }
    }

    private fun onUpdatePin(text: String) {
        if (text.length > 4) return
        updateState { copy(pinText = text, pinError = false) }
        if (text.length == 4) {
            launch {
                val verified = pinManager.verifyPin(text)
                updateState {
                    copy(
                        pinError = !verified,
                        isAuthenticated = verified,
                    )
                }
            }
        }
    }

    private fun onDeliver() {
        if (!currentState.isAuthenticated) return
        val units = currentState.parsedUnits ?: return

        updateState {
            copy(
                phase = BolusPhase.DELIVERING,
                requestedUnits = units,
                deliveredUnits = 0.0,
                isDelivering = true,
                elapsedSeconds = 0,
            )
        }

        launch {
            val result = podManager.sendBolus(units)
            if (result.isFailure) {
                Timber.e(result.exceptionOrNull(), "Bolus command failed")
                emitEffect(BolusEffect.ShowError("Failed to start bolus"))
                updateState { copy(phase = BolusPhase.ENTRY, isDelivering = false) }
                return@launch
            }
            pollDeliveryProgress()
        }
    }

    private suspend fun pollDeliveryProgress() {
        val startTime = System.currentTimeMillis()
        while (currentState.isDelivering) {
            delay(POLL_INTERVAL_MS)
            val elapsed = ((System.currentTimeMillis() - startTime) / 1000).toInt()
            val status = podManager.getStatus().getOrNull()
            if (status != null) {
                val delivered = status.bolusTotalUnits - status.bolusRemainingUnits
                updateState {
                    copy(
                        deliveredUnits = delivered.coerceAtLeast(0.0),
                        elapsedSeconds = elapsed,
                    )
                }
                if (!status.bolusInProgress && status.bolusTotalUnits > 0) {
                    onDeliveryComplete(delivered = status.bolusTotalUnits - status.bolusRemainingUnits, cancelled = false)
                    return
                }
            } else {
                updateState { copy(elapsedSeconds = elapsed) }
            }
        }
    }

    private fun onConfirmCancel() {
        updateState { copy(showCancelConfirm = false) }
        launch {
            podManager.cancelBolus()
            delay(500)
            val status = podManager.getStatus().getOrNull()
            val delivered = if (status != null) {
                status.bolusTotalUnits - status.bolusRemainingUnits
            } else {
                currentState.deliveredUnits
            }
            onDeliveryComplete(delivered = delivered, cancelled = true)
        }
    }

    private fun onDeliveryComplete(delivered: Double, cancelled: Boolean) {
        val s = currentState
        val carbs = s.parsedCarbs ?: 0
        val bolusType = when {
            carbs > 0 && s.effectiveGlucose != null && (s.effectiveGlucose ?: 0) > s.settings.targetGlucose ->
                BolusType.MEAL_AND_CORRECTION
            carbs > 0 -> BolusType.MEAL
            s.effectiveGlucose != null -> BolusType.CORRECTION
            else -> BolusType.MANUAL
        }

        val record = BolusRecord(
            id = UUID.randomUUID().toString(),
            requestedUnits = s.requestedUnits,
            deliveredUnits = delivered.coerceAtLeast(0.0).coerceAtMost(s.requestedUnits),
            bolusType = bolusType,
            carbsGrams = carbs,
            glucoseMgDl = s.effectiveGlucose,
            startedAt = Instant.now().minusSeconds(s.elapsedSeconds.toLong()),
            completedAt = Instant.now(),
            cancelled = cancelled,
        )

        updateState {
            copy(
                phase = BolusPhase.COMPLETE,
                isDelivering = false,
                finalRecord = record,
                wasCancelled = cancelled,
                deliveredUnits = record.deliveredUnits,
            )
        }
    }

    companion object {
        private const val POLL_INTERVAL_MS = 1000L
    }
}
