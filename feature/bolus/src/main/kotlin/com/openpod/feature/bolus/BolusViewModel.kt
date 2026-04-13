package com.openpod.feature.bolus

import com.openpod.core.datastore.PinManager
import com.openpod.core.ui.mvi.MviViewModel
import com.openpod.domain.audit.AuditRepository
import com.openpod.domain.bolus.BolusSafetyValidator
import com.openpod.domain.bolus.ValidationResult
import com.openpod.domain.history.HistoryRepository
import com.openpod.domain.pod.PodManager
import com.openpod.model.audit.AuditCategory
import com.openpod.model.history.HistoryEventType
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
    private val safetyValidator: BolusSafetyValidator,
    private val auditRepository: AuditRepository,
    private val historyRepository: HistoryRepository,
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
        // Skip PIN verification when no PIN is configured
        launch {
            if (!pinManager.hasPin()) {
                updateState { copy(phase = BolusPhase.REVIEW, isAuthenticated = true) }
            } else {
                updateState { copy(phase = BolusPhase.REVIEW) }
            }
        }
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
        val bolusId = UUID.randomUUID().toString()

        updateState { copy(isValidating = true) }

        launch {
            // Audit: bolus requested
            auditRepository.record(
                category = AuditCategory.BOLUS_REQUEST,
                actor = "user",
                source = "BolusViewModel",
                clinicalContext = bolusId,
                payloadJson = buildBolusPayload(units),
            )

            // Safety validation immediately before dispatch
            val validationResult = safetyValidator.validate(units)

            // Audit: precondition check result
            auditRepository.record(
                category = AuditCategory.BOLUS_PRECONDITION_CHECK,
                actor = "system",
                source = "BolusSafetyValidator",
                clinicalContext = bolusId,
                payloadJson = """{"passed":${validationResult is ValidationResult.Passed}}""",
            )

            when (validationResult) {
                is ValidationResult.Failed -> {
                    Timber.w("Bolus safety validation failed: %s", validationResult.failures)
                    auditRepository.record(
                        category = AuditCategory.BOLUS_FAIL,
                        actor = "system",
                        source = "BolusSafetyValidator",
                        clinicalContext = bolusId,
                        payloadJson = """{"reason":"safety_gate","failures":${validationResult.failures.size}}""",
                    )
                    updateState { copy(isValidating = false) }
                    emitEffect(BolusEffect.SafetyGateFailure(validationResult.failures))
                    return@launch
                }
                is ValidationResult.Passed -> { /* proceed */ }
            }

            updateState {
                copy(
                    isValidating = false,
                    phase = BolusPhase.DELIVERING,
                    requestedUnits = units,
                    deliveredUnits = 0.0,
                    isDelivering = true,
                    elapsedSeconds = 0,
                )
            }

            // Audit: dispatch
            auditRepository.record(
                category = AuditCategory.BOLUS_DISPATCH,
                actor = "system",
                source = "BolusViewModel",
                clinicalContext = bolusId,
                payloadJson = """{"units":$units}""",
            )

            val result = podManager.sendBolus(units)
            if (result.isFailure) {
                Timber.e(result.exceptionOrNull(), "Bolus command failed")
                auditRepository.record(
                    category = AuditCategory.BOLUS_FAIL,
                    actor = "system",
                    source = "PodManager",
                    clinicalContext = bolusId,
                    payloadJson = """{"reason":"command_failed","error":"${result.exceptionOrNull()?.message}"}""",
                )
                emitEffect(BolusEffect.ShowError("Failed to start bolus"))
                updateState { copy(phase = BolusPhase.ENTRY, isDelivering = false) }
                return@launch
            }

            // Audit: pod acknowledged
            auditRepository.record(
                category = AuditCategory.BOLUS_ACK,
                actor = "pod",
                source = "PodManager",
                clinicalContext = bolusId,
                payloadJson = """{"units":$units}""",
            )

            pollDeliveryProgress(bolusId)
        }
    }

    private suspend fun pollDeliveryProgress(bolusId: String) {
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
                    onDeliveryComplete(bolusId = bolusId, delivered = status.bolusTotalUnits - status.bolusRemainingUnits, cancelled = false)
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
            // Use a stable ID for cancel audit — derive from state if no bolusId is tracked
            onDeliveryComplete(bolusId = "cancel-${System.currentTimeMillis()}", delivered = delivered, cancelled = true)
        }
    }

    private suspend fun onDeliveryComplete(bolusId: String, delivered: Double, cancelled: Boolean) {
        val s = currentState
        val carbs = s.parsedCarbs ?: 0
        val bolusType = when {
            carbs > 0 && s.effectiveGlucose != null && (s.effectiveGlucose ?: 0) > s.settings.targetGlucose ->
                BolusType.MEAL_AND_CORRECTION
            carbs > 0 -> BolusType.MEAL
            s.effectiveGlucose != null -> BolusType.CORRECTION
            else -> BolusType.MANUAL
        }

        val finalDelivered = delivered.coerceAtLeast(0.0).coerceAtMost(s.requestedUnits)
        val record = BolusRecord(
            id = bolusId,
            requestedUnits = s.requestedUnits,
            deliveredUnits = finalDelivered,
            bolusType = bolusType,
            carbsGrams = carbs,
            glucoseMgDl = s.effectiveGlucose,
            startedAt = Instant.now().minusSeconds(s.elapsedSeconds.toLong()),
            completedAt = Instant.now(),
            cancelled = cancelled,
        )

        // Persist bolus to history
        historyRepository.recordEvent(
            type = HistoryEventType.BOLUS,
            primaryValue = finalDelivered,
            secondaryValue = bolusType.name,
            metadata = """{"requested":${s.requestedUnits},"carbs":$carbs,"glucose":${s.effectiveGlucose},"cancelled":$cancelled}""",
        )

        // Audit: completion or cancellation
        val auditCategory = if (cancelled) AuditCategory.BOLUS_CANCEL else AuditCategory.BOLUS_COMPLETE
        auditRepository.record(
            category = auditCategory,
            actor = if (cancelled) "user" else "pod",
            source = "BolusViewModel",
            clinicalContext = bolusId,
            payloadJson = """{"requested":${s.requestedUnits},"delivered":$finalDelivered,"cancelled":$cancelled,"type":"${bolusType.name}"}""",
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

    private fun buildBolusPayload(units: Double): String {
        val s = currentState
        return buildString {
            append("""{"units":$units""")
            append(""","carbs":${s.parsedCarbs ?: 0}""")
            s.effectiveGlucose?.let { append(""","glucose":$it""") }
            append(""","iob":${s.currentIob}""")
            append("}")
        }
    }

    companion object {
        private const val POLL_INTERVAL_MS = 1000L
    }
}
