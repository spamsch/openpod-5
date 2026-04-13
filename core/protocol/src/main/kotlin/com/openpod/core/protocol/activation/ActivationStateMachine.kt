package com.openpod.core.protocol.activation

import com.openpod.core.protocol.command.PodCommand
import com.openpod.core.protocol.command.PodResponse
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber

/**
 * Pod activation lifecycle state.
 *
 * Activation proceeds through two phases:
 * - **Phase 1:** Pod identification, alert programming, and priming.
 * - **Phase 2:** Basal programming, cannula insertion, algorithm enablement.
 *
 * State transitions are strictly ordered. The state machine rejects out-of-order
 * transitions and moves to [FAILED] on any unrecoverable error.
 */
enum class ActivationState {
    /** Pod discovered, ready to begin activation. */
    READY,
    /** Activation sequence has been initiated. */
    STARTED,
    /** Successfully retrieved pod firmware version and identifiers. */
    GOT_VERSION,
    /** Unique ID assigned to the pod. */
    SET_UID,
    /** Alert configurations programmed. */
    PROGRAMMED_ALERTS,
    /** Pod primed (cannula mechanism filled). */
    PRIMED,
    /** Phase 1 complete — pod ready for Phase 2. */
    COMPLETED_PHASE_1,
    /** Scheduled basal rate programmed. */
    PROGRAMMED_BASAL,
    /** Cannula inserted. */
    INSERTED_CANNULA,
    /** AID algorithm enabled on the pod. */
    ENABLED_ALGORITHM,
    /** Post-activation status confirmed. */
    GOT_STATUS,
    /** Pod fully activated and delivering insulin. */
    ACTIVATED,
    /** Activation failed — pod may need to be discarded. */
    FAILED,
}

/**
 * Manages the multi-step pod activation flow.
 *
 * The state machine drives the activation protocol by issuing commands in
 * the correct order and advancing through [ActivationState] on each successful
 * response. If any step fails, the machine transitions to [ActivationState.FAILED].
 *
 * Usage:
 * ```kotlin
 * val machine = ActivationStateMachine()
 * while (machine.currentState.value != ActivationState.ACTIVATED) {
 *     val result = machine.advance { command -> session.sendCommand(command) }
 *     if (result.isFailure) break
 * }
 * ```
 *
 * **Thread safety:** This class is not thread-safe. Callers must ensure that
 * [advance] is not called concurrently (typically via a single coroutine).
 */
class ActivationStateMachine {

    private val _currentState = MutableStateFlow(ActivationState.READY)

    /** Observable activation state. */
    val currentState: StateFlow<ActivationState> = _currentState.asStateFlow()

    /** Pod ID discovered during scan, set before starting activation. */
    var podId: ByteArray = ByteArray(4)

    /** Unique ID to assign to the pod. */
    var uid: ByteArray = ByteArray(4)

    /** Manufacturing lot number from pod advertisement or version response. */
    var lotNumber: Long = 0L

    /** Manufacturing sequence number from pod advertisement or version response. */
    var sequenceNumber: Long = 0L

    /** Alert configurations to program. Must be set before advancing past STARTED. */
    var alertConfigs: List<com.openpod.core.protocol.command.AlertConfig> = emptyList()

    /** Prime volume in units. */
    var primeVolume: Double = DEFAULT_PRIME_VOLUME

    /** Basal segments for the initial basal program. */
    var basalSegments: List<com.openpod.core.protocol.command.BasalSegment> = emptyList()

    /** Cannula insertion prime volume in units. */
    var cannulaPrimeVolume: Double = DEFAULT_CANNULA_PRIME_VOLUME

    /**
     * Advance the activation state machine by one step.
     *
     * Determines the next command based on the current state, sends it via
     * the provided [connection] function, and transitions to the next state
     * on success.
     *
     * @param connection Suspending function that sends a command and returns
     *   the pod's response. Typically wraps [PodSession.sendCommand].
     * @return [Result.success] with the new state, or [Result.failure] if the
     *   step failed. On failure, [currentState] is set to [ActivationState.FAILED].
     */
    suspend fun advance(
        connection: suspend (PodCommand) -> PodResponse,
    ): Result<ActivationState> {
        val state = _currentState.value

        if (state == ActivationState.ACTIVATED) {
            Timber.d("Activation already complete")
            return Result.success(ActivationState.ACTIVATED)
        }

        if (state == ActivationState.FAILED) {
            Timber.w("Cannot advance from FAILED state — call reset() first")
            return Result.failure(IllegalStateException("Activation is in FAILED state"))
        }

        Timber.d("Advancing activation from state: %s", state.name)

        return try {
            val nextState = executeStep(state, connection)
            _currentState.value = nextState
            Timber.d("Activation advanced: %s -> %s", state.name, nextState.name)
            Result.success(nextState)
        } catch (e: Exception) {
            Timber.e(e, "Activation failed at state %s", state.name)
            _currentState.value = ActivationState.FAILED
            Result.failure(e)
        }
    }

    /**
     * Reset the state machine to [ActivationState.READY].
     *
     * Call this to retry activation after a failure, or to start a new
     * activation with a different pod.
     */
    fun reset() {
        Timber.d("Activation state machine reset")
        _currentState.value = ActivationState.READY
    }

    /**
     * Execute the command for the current state and return the next state.
     *
     * @throws IllegalStateException if the response is unexpected.
     * @throws Exception if the connection function throws.
     */
    private suspend fun executeStep(
        state: ActivationState,
        connection: suspend (PodCommand) -> PodResponse,
    ): ActivationState = when (state) {

        ActivationState.READY -> {
            Timber.d("Activation: starting — sending GetVersion")
            val response = connection(PodCommand.GetVersion(podId))
            if (response is PodResponse.VersionInfo) {
                lotNumber = response.lotNumber
                sequenceNumber = response.sequenceNumber
                Timber.d("Activation: got version info, firmware=%s", response.firmwareVersion)
                ActivationState.GOT_VERSION
            } else {
                throw unexpectedResponse("VersionInfo", response)
            }
        }

        ActivationState.STARTED -> {
            // STARTED is a transitional state; treat the same as READY.
            Timber.d("Activation: sending GetVersion from STARTED")
            val response = connection(PodCommand.GetVersion(podId))
            if (response is PodResponse.VersionInfo) {
                lotNumber = response.lotNumber
                sequenceNumber = response.sequenceNumber
                ActivationState.GOT_VERSION
            } else {
                throw unexpectedResponse("VersionInfo", response)
            }
        }

        ActivationState.GOT_VERSION -> {
            Timber.d("Activation: sending SetUniqueId")
            val response = connection(PodCommand.SetUniqueId(uid, lotNumber, sequenceNumber))
            if (response is PodResponse.Acknowledge) {
                ActivationState.SET_UID
            } else {
                throw unexpectedResponse("Acknowledge", response)
            }
        }

        ActivationState.SET_UID -> {
            Timber.d("Activation: programming alerts")
            require(alertConfigs.isNotEmpty()) { "Alert configs must be set before programming alerts" }
            val response = connection(PodCommand.ProgramAlerts(alertConfigs))
            if (response is PodResponse.Acknowledge) {
                ActivationState.PROGRAMMED_ALERTS
            } else {
                throw unexpectedResponse("Acknowledge", response)
            }
        }

        ActivationState.PROGRAMMED_ALERTS -> {
            Timber.d("Activation: priming pod")
            val response = connection(PodCommand.PrimePod(primeVolume))
            if (response is PodResponse.Acknowledge) {
                ActivationState.PRIMED
            } else {
                throw unexpectedResponse("Acknowledge", response)
            }
        }

        ActivationState.PRIMED -> {
            Timber.d("Activation: Phase 1 complete, verifying status")
            val response = connection(PodCommand.GetStatus)
            if (response is PodResponse.StatusResponse) {
                ActivationState.COMPLETED_PHASE_1
            } else {
                throw unexpectedResponse("StatusResponse", response)
            }
        }

        ActivationState.COMPLETED_PHASE_1 -> {
            Timber.d("Activation: programming basal schedule")
            require(basalSegments.isNotEmpty()) { "Basal segments must be set before programming basal" }
            val response = connection(PodCommand.ProgramBasal(basalSegments))
            if (response is PodResponse.Acknowledge) {
                ActivationState.PROGRAMMED_BASAL
            } else {
                throw unexpectedResponse("Acknowledge", response)
            }
        }

        ActivationState.PROGRAMMED_BASAL -> {
            Timber.d("Activation: inserting cannula")
            val response = connection(PodCommand.InsertCannula(cannulaPrimeVolume))
            if (response is PodResponse.Acknowledge) {
                ActivationState.INSERTED_CANNULA
            } else {
                throw unexpectedResponse("Acknowledge", response)
            }
        }

        ActivationState.INSERTED_CANNULA -> {
            Timber.d("Activation: enabling AID algorithm")
            val aidEnableData = byteArrayOf(0x01) // Enable algorithm flag
            val response = connection(
                PodCommand.ConfigureAid(AidSetupStep.UTC, aidEnableData)
            )
            if (response is PodResponse.Acknowledge) {
                ActivationState.ENABLED_ALGORITHM
            } else {
                throw unexpectedResponse("Acknowledge", response)
            }
        }

        ActivationState.ENABLED_ALGORITHM -> {
            Timber.d("Activation: getting post-activation status")
            val response = connection(PodCommand.GetStatus)
            if (response is PodResponse.StatusResponse) {
                ActivationState.GOT_STATUS
            } else {
                throw unexpectedResponse("StatusResponse", response)
            }
        }

        ActivationState.GOT_STATUS -> {
            Timber.d("Activation: complete")
            ActivationState.ACTIVATED
        }

        ActivationState.ACTIVATED -> ActivationState.ACTIVATED

        ActivationState.FAILED ->
            throw IllegalStateException("Cannot advance from FAILED state")
    }

    private fun unexpectedResponse(expected: String, actual: PodResponse): IllegalStateException {
        val actualName = actual.javaClass.simpleName
        return IllegalStateException("Expected $expected response, got $actualName")
    }

    companion object {
        /** Default priming volume in units per Omnipod 5 spec. */
        private const val DEFAULT_PRIME_VOLUME = 2.6

        /** Default cannula fill volume in units. */
        private const val DEFAULT_CANNULA_PRIME_VOLUME = 0.5
    }
}
