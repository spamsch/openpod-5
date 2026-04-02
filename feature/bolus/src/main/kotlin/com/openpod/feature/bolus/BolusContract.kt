package com.openpod.feature.bolus

import com.openpod.core.ui.mvi.UiEffect
import com.openpod.core.ui.mvi.UiIntent
import com.openpod.core.ui.mvi.UiState
import com.openpod.domain.bolus.SafetyFailure
import com.openpod.model.insulin.BolusCalculation
import com.openpod.model.insulin.BolusRecord
import com.openpod.model.insulin.BolusSettings

enum class BolusPhase { ENTRY, REVIEW, DELIVERING, COMPLETE }

data class BolusState(
    val phase: BolusPhase = BolusPhase.ENTRY,
    // Entry
    val unitsText: String = "",
    val carbsText: String = "",
    val bgOverrideText: String = "",
    val podGlucose: Int? = null,
    val currentIob: Double = 0.0,
    val calculation: BolusCalculation? = null,
    val settings: BolusSettings = BolusSettings(),
    // Review
    val pinText: String = "",
    val pinError: Boolean = false,
    val isAuthenticated: Boolean = false,
    // Validation
    val isValidating: Boolean = false,
    // Delivery
    val requestedUnits: Double = 0.0,
    val deliveredUnits: Double = 0.0,
    val isDelivering: Boolean = false,
    val showCancelConfirm: Boolean = false,
    val elapsedSeconds: Int = 0,
    // Complete
    val finalRecord: BolusRecord? = null,
    val wasCancelled: Boolean = false,
) : UiState {
    val parsedUnits: Double? get() = unitsText.toDoubleOrNull()
    val parsedCarbs: Int? get() = carbsText.toIntOrNull()
    val effectiveGlucose: Int? get() = bgOverrideText.toIntOrNull() ?: podGlucose
    val canReview: Boolean get() {
        val u = parsedUnits ?: return false
        return u in 0.05..30.0
    }
    val deliveryPercent: Float get() {
        if (requestedUnits <= 0) return 0f
        return ((requestedUnits - deliveredUnits) / requestedUnits)
            .coerceIn(0.0, 1.0)
            .let { 1.0 - it }
            .toFloat()
    }
}

sealed interface BolusIntent : UiIntent {
    data class UpdateCarbs(val text: String) : BolusIntent
    data class UpdateBgOverride(val text: String) : BolusIntent
    data class UpdateUnits(val text: String) : BolusIntent
    data object RefreshBg : BolusIntent
    data object UseCalculatedDose : BolusIntent
    data object NextToReview : BolusIntent
    data class UpdatePin(val text: String) : BolusIntent
    data object Deliver : BolusIntent
    data object BackToEntry : BolusIntent
    data object RequestCancel : BolusIntent
    data object ConfirmCancel : BolusIntent
    data object DismissCancelDialog : BolusIntent
    data object Done : BolusIntent
}

sealed interface BolusEffect : UiEffect {
    data object NavigateBack : BolusEffect
    data class ShowError(val message: String) : BolusEffect
    data class SafetyGateFailure(val failures: List<SafetyFailure>) : BolusEffect
}
