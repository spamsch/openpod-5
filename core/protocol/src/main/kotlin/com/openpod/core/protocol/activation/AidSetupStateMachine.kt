package com.openpod.core.protocol.activation

import com.openpod.core.protocol.command.PodCommand
import com.openpod.core.protocol.command.PodResponse
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber

/**
 * AID (Automated Insulin Delivery) configuration step.
 *
 * After pod activation, the AID algorithm must be configured with the user's
 * insulin therapy parameters. Steps are executed in order, and each step
 * sends a [PodCommand.ConfigureAid] with the appropriate data payload.
 */
enum class AidSetupStep {
    /** Set the pod's UTC clock. */
    UTC,
    /** Send Total Daily Insulin (TDI) baseline. */
    TDI,
    /** Send target blood glucose profile (time-segmented). */
    TARGET_BG,
    /** Send correction factor profile (time-segmented). */
    CORRECTION_FACTOR,
    /** Send Duration of Insulin Action (DIA) setting. */
    DIA,
    /** Send EGV (Estimated Glucose Value) threshold for algorithm. */
    EGV_THRESHOLD,
    /** Send recent insulin delivery history for algorithm warm-up. */
    INSULIN_HISTORY,
    /** Query the pod for AID algorithm status confirmation. */
    GET_STATUS,
    /** AID setup complete — algorithm is running. */
    COMPLETE,
    /** AID setup failed. */
    FAILED,
}

/**
 * Manages the post-activation AID algorithm configuration flow.
 *
 * The Omnipod 5 AID algorithm requires several insulin therapy parameters
 * to be configured before it can begin automated delivery. This state machine
 * drives that multi-step process.
 *
 * The caller must provide the configuration data for each step via the
 * [stepData] map before calling [advance].
 *
 * Usage:
 * ```kotlin
 * val aidSetup = AidSetupStateMachine()
 * aidSetup.stepData[AidSetupStep.UTC] = utcBytes
 * aidSetup.stepData[AidSetupStep.TDI] = tdiBytes
 * // ... set all step data
 *
 * while (aidSetup.currentStep.value != AidSetupStep.COMPLETE) {
 *     val result = aidSetup.advance { command -> session.sendCommand(command) }
 *     if (result.isFailure) break
 * }
 * ```
 *
 * **Thread safety:** This class is not thread-safe. Callers must ensure that
 * [advance] is not called concurrently.
 */
class AidSetupStateMachine {

    private val _currentStep = MutableStateFlow(AidSetupStep.UTC)

    /** Observable current setup step. */
    val currentStep: StateFlow<AidSetupStep> = _currentStep.asStateFlow()

    /**
     * Configuration data for each step.
     *
     * Must be populated before calling [advance] for the corresponding step.
     * The byte arrays are passed as-is to [PodCommand.ConfigureAid].
     */
    val stepData: MutableMap<AidSetupStep, ByteArray> = mutableMapOf()

    /**
     * Advance the AID setup by one step.
     *
     * Sends the appropriate [PodCommand.ConfigureAid] for the current step,
     * waits for acknowledgment, and transitions to the next step.
     *
     * @param connection Suspending function that sends a command and returns
     *   the pod's response.
     * @return [Result.success] with the new step, or [Result.failure] if the
     *   step failed. On failure, [currentStep] is set to [AidSetupStep.FAILED].
     */
    suspend fun advance(
        connection: suspend (PodCommand) -> PodResponse,
    ): Result<AidSetupStep> {
        val step = _currentStep.value

        if (step == AidSetupStep.COMPLETE) {
            Timber.d("AID setup already complete")
            return Result.success(AidSetupStep.COMPLETE)
        }

        if (step == AidSetupStep.FAILED) {
            Timber.w("Cannot advance AID setup from FAILED state — call reset() first")
            return Result.failure(IllegalStateException("AID setup is in FAILED state"))
        }

        Timber.d("Advancing AID setup from step: %s", step.name)

        return try {
            val nextStep = executeStep(step, connection)
            _currentStep.value = nextStep
            Timber.d("AID setup advanced: %s -> %s", step.name, nextStep.name)
            Result.success(nextStep)
        } catch (e: Exception) {
            Timber.e(e, "AID setup failed at step %s", step.name)
            _currentStep.value = AidSetupStep.FAILED
            Result.failure(e)
        }
    }

    /**
     * Reset the state machine to the first step ([AidSetupStep.UTC]).
     */
    fun reset() {
        Timber.d("AID setup state machine reset")
        _currentStep.value = AidSetupStep.UTC
    }

    /**
     * Execute the command for the current step and return the next step.
     */
    private suspend fun executeStep(
        step: AidSetupStep,
        connection: suspend (PodCommand) -> PodResponse,
    ): AidSetupStep = when (step) {

        AidSetupStep.UTC -> {
            sendConfigStep(step, connection)
            AidSetupStep.TDI
        }

        AidSetupStep.TDI -> {
            sendConfigStep(step, connection)
            AidSetupStep.TARGET_BG
        }

        AidSetupStep.TARGET_BG -> {
            sendConfigStep(step, connection)
            AidSetupStep.CORRECTION_FACTOR
        }

        AidSetupStep.CORRECTION_FACTOR -> {
            sendConfigStep(step, connection)
            AidSetupStep.DIA
        }

        AidSetupStep.DIA -> {
            sendConfigStep(step, connection)
            AidSetupStep.EGV_THRESHOLD
        }

        AidSetupStep.EGV_THRESHOLD -> {
            sendConfigStep(step, connection)
            AidSetupStep.INSULIN_HISTORY
        }

        AidSetupStep.INSULIN_HISTORY -> {
            sendConfigStep(step, connection)
            AidSetupStep.GET_STATUS
        }

        AidSetupStep.GET_STATUS -> {
            Timber.d("AID setup: verifying algorithm status")
            val response = connection(PodCommand.GetStatus)
            if (response is PodResponse.AidStatus) {
                Timber.d("AID setup: algorithm running, algorithmState=%d", response.algorithmState)
                AidSetupStep.COMPLETE
            } else if (response is PodResponse.StatusResponse) {
                // Some firmware versions respond with a StatusResponse instead
                Timber.d("AID setup: got StatusResponse, treating as complete")
                AidSetupStep.COMPLETE
            } else {
                throw IllegalStateException(
                    "Expected AidStatus or StatusResponse, got ${response.javaClass.simpleName}"
                )
            }
        }

        AidSetupStep.COMPLETE -> AidSetupStep.COMPLETE

        AidSetupStep.FAILED ->
            throw IllegalStateException("Cannot advance from FAILED state")
    }

    /**
     * Send a [PodCommand.ConfigureAid] for the given step and validate the response.
     *
     * @throws IllegalStateException if step data is missing or response is unexpected.
     */
    private suspend fun sendConfigStep(
        step: AidSetupStep,
        connection: suspend (PodCommand) -> PodResponse,
    ) {
        val data = stepData[step]
            ?: throw IllegalStateException("No data provided for AID setup step: ${step.name}")

        Timber.d("AID setup: sending configuration for step %s", step.name)
        val response = connection(PodCommand.ConfigureAid(step, data))

        if (response !is PodResponse.Acknowledge) {
            throw IllegalStateException(
                "Expected Acknowledge for ${step.name}, got ${response.javaClass.simpleName}"
            )
        }

        Timber.d("AID setup: step %s acknowledged", step.name)
    }
}
