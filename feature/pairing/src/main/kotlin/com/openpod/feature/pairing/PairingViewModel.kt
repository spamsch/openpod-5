package com.openpod.feature.pairing

import com.openpod.core.ui.mvi.MviViewModel
import com.openpod.core.ui.mvi.UiEffect
import com.openpod.core.ui.mvi.UiIntent
import com.openpod.core.ui.mvi.UiState
import com.openpod.domain.pod.DiscoveredPod
import com.openpod.domain.pod.PodActivationResult
import com.openpod.domain.pod.PodManager
import com.openpod.model.pod.InfusionSite
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import timber.log.Timber
import javax.inject.Inject

// ── Step enum ──────────────────────────────────────────────────────

/**
 * The seven steps of the pod pairing wizard.
 *
 * Each step maps to a distinct screen in the wizard flow and corresponds
 * to a phase of the Omnipod 5 activation protocol.
 */
enum class PairingStep {
    /** User fills the pod reservoir with insulin. */
    FILL,
    /** BLE scan discovers nearby unpaired pods. */
    DISCOVER,
    /** BLE connection, service discovery, and EAP-AKA authentication. */
    CONNECT,
    /** Pod primes the cannula tubing with insulin. */
    PRIME,
    /** User applies the pod to their body and selects the infusion site. */
    APPLY,
    /** Pod inserts cannula and begins insulin delivery. */
    START,
    /** Activation complete — pod is delivering insulin. */
    COMPLETE,
}

// ── Connection sub-steps ───────────────────────────────────────────

/**
 * Status of a single connection/authentication sub-step displayed
 * in the Step 3 checklist.
 */
enum class SubStepStatus {
    /** Not yet started. */
    PENDING,
    /** Currently in progress (spinner displayed). */
    IN_PROGRESS,
    /** Successfully completed (checkmark displayed). */
    COMPLETED,
    /** Failed (error indicator displayed). */
    FAILED,
}

/**
 * A sub-step in the connection and authentication sequence.
 *
 * @property label Human-readable description of the sub-step.
 * @property status Current status for display.
 */
data class ConnectionSubStep(
    val label: String,
    val status: SubStepStatus,
)

// ── Activation sub-steps (Step 6) ──────────────────────────────────

/**
 * A sub-step in the delivery start / activation sequence (Step 6).
 *
 * @property label Human-readable description.
 * @property status Current status for display.
 */
data class ActivationSubStep(
    val label: String,
    val status: SubStepStatus,
)

// ── State ──────────────────────────────────────────────────────────

/**
 * Complete UI state for the pod pairing wizard.
 *
 * This is an immutable snapshot rendered by [PairingScreen]. All
 * mutations go through [PairingViewModel.updateState].
 */
data class PairingState(
    /** Current wizard step. */
    val currentStep: PairingStep = PairingStep.FILL,
    /** Pods found during BLE scan (Step 2). */
    val discoveredPods: List<DiscoveredPod> = emptyList(),
    /** ID of the user-selected pod (Step 2 onward). */
    val selectedPodId: String? = null,
    /** Connection/auth sub-step checklist (Step 3). */
    val connectionProgress: List<ConnectionSubStep> = DEFAULT_CONNECTION_STEPS,
    /** Prime progress from 0.0 to 1.0 (Step 4). */
    val primeProgress: Float = 0f,
    /** User-selected infusion site (Step 5). */
    val selectedSite: InfusionSite? = null,
    /** Activation progress from 0.0 to 1.0 (Step 6). */
    val activationProgress: Float = 0f,
    /** Delivery start sub-step checklist (Step 6 after button tap). */
    val activationSubSteps: List<ActivationSubStep> = emptyList(),
    /** True when delivery start has been initiated (Step 6). */
    val deliveryStarted: Boolean = false,
    /** Final pod status (Step 7). */
    val activationResult: PodActivationResult? = null,
    /** True when an async operation is in progress. */
    val isProcessing: Boolean = false,
    /** Error message to display, or null. */
    val error: String? = null,
) : UiState {

    companion object {
        /** Default connection sub-steps for Step 3. */
        val DEFAULT_CONNECTION_STEPS = listOf(
            ConnectionSubStep("BLE connected", SubStepStatus.PENDING),
            ConnectionSubStep("Services discovered", SubStepStatus.PENDING),
            ConnectionSubStep("Authenticating", SubStepStatus.PENDING),
            ConnectionSubStep("Security established", SubStepStatus.PENDING),
            ConnectionSubStep("Pod initialized", SubStepStatus.PENDING),
        )

        /** Default activation sub-steps for Step 6. */
        val DEFAULT_ACTIVATION_STEPS = listOf(
            ActivationSubStep("Pod started", SubStepStatus.PENDING),
            ActivationSubStep("Second prime complete", SubStepStatus.PENDING),
            ActivationSubStep("Algorithm enabled", SubStepStatus.PENDING),
            ActivationSubStep("UTC time set", SubStepStatus.PENDING),
            ActivationSubStep("Final status verified", SubStepStatus.PENDING),
        )
    }
}

// ── Intents ────────────────────────────────────────────────────────

/**
 * User actions in the pairing wizard.
 */
sealed interface PairingIntent : UiIntent {
    /** User confirms the pod has been filled (Step 1 -> Step 2). */
    data object PodFilled : PairingIntent

    /** User selects a discovered pod (Step 2 -> Step 3). */
    data class SelectPod(val id: String) : PairingIntent

    /** User requests a new BLE scan (Step 2). */
    data object ScanAgain : PairingIntent

    /** User confirms priming is complete (Step 4 -> Step 5). */
    data object PrimeComplete : PairingIntent

    /** User selects an infusion site (Step 5). */
    data class SelectSite(val site: InfusionSite) : PairingIntent

    /** User confirms the pod is applied (Step 5 -> Step 6). */
    data object PodApplied : PairingIntent

    /** User taps "Begin Delivery" (Step 6 -> activation sequence). */
    data object BeginDelivery : PairingIntent

    /** User taps "Go to Dashboard" (Step 7 -> exit). */
    data object GoToDashboard : PairingIntent

    /** User taps "Cancel" at any step. */
    data object Cancel : PairingIntent
}

// ── Effects ────────────────────────────────────────────────────────

/**
 * One-shot side effects emitted by the pairing wizard.
 */
sealed interface PairingEffect : UiEffect {
    /** Navigate to the dashboard after successful activation. */
    data object NavigateToDashboard : PairingEffect

    /** Show a cancel confirmation dialog. */
    data object ShowCancelConfirmation : PairingEffect
}

// ── ViewModel ──────────────────────────────────────────────────────

/**
 * MVI ViewModel for the pod pairing wizard.
 *
 * Orchestrates the 7-step pairing flow, delegating BLE operations to
 * [PodManager]. All state mutations are logged for audit trail via
 * the [MviViewModel] base class.
 *
 * @param podManager Abstraction over pod BLE operations (stubbed for development).
 */
@HiltViewModel
class PairingViewModel @Inject constructor(
    private val podManager: PodManager,
) : MviViewModel<PairingState, PairingIntent, PairingEffect>(
    initialState = PairingState(),
) {

    override fun handleIntent(intent: PairingIntent) {
        when (intent) {
            is PairingIntent.PodFilled -> onPodFilled()
            is PairingIntent.SelectPod -> onSelectPod(intent.id)
            is PairingIntent.ScanAgain -> onScanAgain()
            is PairingIntent.PrimeComplete -> onPrimeComplete()
            is PairingIntent.SelectSite -> onSelectSite(intent.site)
            is PairingIntent.PodApplied -> onPodApplied()
            is PairingIntent.BeginDelivery -> onBeginDelivery()
            is PairingIntent.GoToDashboard -> emitEffect(PairingEffect.NavigateToDashboard)
            is PairingIntent.Cancel -> emitEffect(PairingEffect.ShowCancelConfirmation)
        }
    }

    /**
     * Step 1 -> Step 2: Start BLE scan when user confirms pod is filled.
     */
    private fun onPodFilled() {
        updateState {
            copy(
                currentStep = PairingStep.DISCOVER,
                isProcessing = true,
                error = null,
                discoveredPods = emptyList(),
            )
        }
        startScanning()
    }

    /**
     * Step 2 -> Step 3: Connect to the selected pod.
     */
    private fun onSelectPod(podId: String) {
        if (podId.isBlank()) {
            Timber.w("Attempted to select a pod with blank ID")
            return
        }
        updateState {
            copy(
                selectedPodId = podId,
                currentStep = PairingStep.CONNECT,
                connectionProgress = PairingState.DEFAULT_CONNECTION_STEPS,
                isProcessing = true,
                error = null,
            )
        }
        startConnectionSequence(podId)
    }

    /**
     * Step 2: Restart BLE scan.
     */
    private fun onScanAgain() {
        updateState {
            copy(
                discoveredPods = emptyList(),
                isProcessing = true,
                error = null,
            )
        }
        startScanning()
    }

    /**
     * Step 4 -> Step 5: User confirms priming is done.
     */
    private fun onPrimeComplete() {
        if (currentState.primeProgress < 1f) {
            Timber.w("Attempted to complete priming before progress reached 100%%")
            return
        }
        updateState {
            copy(currentStep = PairingStep.APPLY, error = null)
        }
    }

    /**
     * Step 5: Record selected infusion site.
     */
    private fun onSelectSite(site: InfusionSite) {
        updateState { copy(selectedSite = site) }
    }

    /**
     * Step 5 -> Step 6: User confirms pod is applied.
     */
    private fun onPodApplied() {
        if (currentState.selectedSite == null) {
            Timber.w("Attempted to advance without selecting an infusion site")
            return
        }
        updateState {
            copy(
                currentStep = PairingStep.START,
                deliveryStarted = false,
                activationSubSteps = emptyList(),
                error = null,
            )
        }
    }

    /**
     * Step 6: Begin cannula insertion and delivery start.
     */
    private fun onBeginDelivery() {
        updateState {
            copy(
                isProcessing = true,
                deliveryStarted = true,
                activationSubSteps = PairingState.DEFAULT_ACTIVATION_STEPS,
                error = null,
            )
        }
        startActivationSequence()
    }

    // ── Async operations ───────────────────────────────────────────

    private fun startScanning() {
        launch {
            try {
                podManager.startScan().collect { pod ->
                    updateState {
                        copy(
                            discoveredPods = discoveredPods.filter { it.id != pod.id } + pod,
                            isProcessing = false,
                        )
                    }
                }
            } catch (e: Exception) {
                Timber.e(e, "BLE scan failed")
                updateState {
                    copy(isProcessing = false, error = e.message ?: "Scan failed")
                }
            }
        }
    }

    private fun startConnectionSequence(podId: String) {
        launch {
            try {
                // Sub-step 1: BLE connect
                updateConnectionStep(index = 0, status = SubStepStatus.IN_PROGRESS)
                podManager.connect(podId).getOrThrow()
                updateConnectionStep(index = 0, status = SubStepStatus.COMPLETED)

                // Sub-step 2: Services discovered (simulated as part of connect)
                updateConnectionStep(index = 1, status = SubStepStatus.IN_PROGRESS)
                delay(500)
                updateConnectionStep(index = 1, status = SubStepStatus.COMPLETED)

                // Sub-step 3: Authenticating
                updateConnectionStep(index = 2, status = SubStepStatus.IN_PROGRESS)
                podManager.authenticate().getOrThrow()
                updateConnectionStep(index = 2, status = SubStepStatus.COMPLETED)

                // Sub-step 4: Security established
                updateConnectionStep(index = 3, status = SubStepStatus.IN_PROGRESS)
                delay(500)
                updateConnectionStep(index = 3, status = SubStepStatus.COMPLETED)

                // Sub-step 5: Pod initialized
                updateConnectionStep(index = 4, status = SubStepStatus.IN_PROGRESS)
                delay(500)
                updateConnectionStep(index = 4, status = SubStepStatus.COMPLETED)

                // Advance to prime step
                updateState {
                    copy(
                        currentStep = PairingStep.PRIME,
                        isProcessing = true,
                        primeProgress = 0f,
                    )
                }
                startPriming()
            } catch (e: Exception) {
                Timber.e(e, "Connection sequence failed")
                val failedIndex = currentState.connectionProgress.indexOfFirst {
                    it.status == SubStepStatus.IN_PROGRESS
                }
                if (failedIndex >= 0) {
                    updateConnectionStep(index = failedIndex, status = SubStepStatus.FAILED)
                }
                updateState {
                    copy(isProcessing = false, error = e.message ?: "Connection failed")
                }
            }
        }
    }

    private fun startPriming() {
        launch {
            try {
                podManager.prime().collect { progress ->
                    updateState {
                        copy(
                            primeProgress = progress.percent,
                            isProcessing = !progress.isComplete,
                        )
                    }
                }
            } catch (e: Exception) {
                Timber.e(e, "Priming failed")
                updateState {
                    copy(isProcessing = false, error = e.message ?: "Priming failed")
                }
            }
        }
    }

    private fun startActivationSequence() {
        launch {
            try {
                var stepIndex = 0
                podManager.insertCannula().collect { progress ->
                    // Mark completed steps
                    while (stepIndex < (progress.percent * PairingState.DEFAULT_ACTIVATION_STEPS.size).toInt()) {
                        updateActivationStep(index = stepIndex, status = SubStepStatus.COMPLETED)
                        stepIndex++
                    }
                    // Mark current step in progress
                    if (stepIndex < PairingState.DEFAULT_ACTIVATION_STEPS.size) {
                        updateActivationStep(index = stepIndex, status = SubStepStatus.IN_PROGRESS)
                    }

                    updateState {
                        copy(activationProgress = progress.percent)
                    }

                    if (progress.isComplete) {
                        // Mark all steps completed
                        for (i in PairingState.DEFAULT_ACTIVATION_STEPS.indices) {
                            updateActivationStep(index = i, status = SubStepStatus.COMPLETED)
                        }
                    }
                }

                // Fetch final status and advance to completion
                val result = podManager.getStatus().getOrThrow()
                updateState {
                    copy(
                        currentStep = PairingStep.COMPLETE,
                        activationResult = result,
                        isProcessing = false,
                    )
                }
            } catch (e: Exception) {
                Timber.e(e, "Activation sequence failed")
                updateState {
                    copy(isProcessing = false, error = e.message ?: "Activation failed")
                }
            }
        }
    }

    // ── Helpers ─────────────────────────────────────────────────────

    private fun updateConnectionStep(index: Int, status: SubStepStatus) {
        updateState {
            val steps = connectionProgress.toMutableList()
            if (index in steps.indices) {
                steps[index] = steps[index].copy(status = status)
            }
            copy(connectionProgress = steps)
        }
    }

    private fun updateActivationStep(index: Int, status: SubStepStatus) {
        updateState {
            val steps = activationSubSteps.toMutableList()
            if (index in steps.indices) {
                steps[index] = steps[index].copy(status = status)
            }
            copy(activationSubSteps = steps)
        }
    }
}
