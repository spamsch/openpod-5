package com.openpod.core.ui.mvi

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Base ViewModel implementing the MVI (Model-View-Intent) pattern.
 *
 * ## Architecture
 *
 * ```
 * User Action → [Intent] → ViewModel.onIntent() → reduce state
 *                                                 → emit side effects
 *                         → [UiState] → Composable UI
 *                         → [Effect] → One-shot events (navigation, toasts)
 * ```
 *
 * ## Usage
 *
 * ```kotlin
 * class DashboardViewModel : MviViewModel<DashboardState, DashboardIntent, DashboardEffect>(
 *     initialState = DashboardState()
 * ) {
 *     override fun handleIntent(intent: DashboardIntent) {
 *         when (intent) {
 *             is DashboardIntent.RefreshData -> loadData()
 *             is DashboardIntent.NavigateToBolus -> emitEffect(DashboardEffect.OpenBolus)
 *         }
 *     }
 * }
 * ```
 *
 * ## Audit trail
 *
 * All state transitions are logged via Timber at DEBUG level with the tag
 * "MVI/{ClassName}". This provides a complete audit trail of every user
 * action and state change for debugging and incident investigation.
 *
 * @param S The UI state type (immutable data class).
 * @param I The intent type (sealed interface of user actions).
 * @param E The effect type (sealed interface of one-shot side effects).
 * @param initialState The starting state when the ViewModel is created.
 */
abstract class MviViewModel<S : UiState, I : UiIntent, E : UiEffect>(
    initialState: S,
) : ViewModel() {

    private val tag = "MVI/${this::class.simpleName}"

    private val _state = MutableStateFlow(initialState)

    /** Observable UI state. Compose screens collect this to render. */
    val state: StateFlow<S> = _state.asStateFlow()

    /** Current state snapshot. Use in handleIntent() to read current values. */
    protected val currentState: S get() = _state.value

    private val _effect = Channel<E>(Channel.BUFFERED)

    /** One-shot side effects (navigation, toasts, haptics). Collected once by the UI. */
    val effect = _effect.receiveAsFlow()

    /**
     * Process a user intent. Called from the UI layer when the user takes an action.
     *
     * Logs the intent for audit trail, then delegates to [handleIntent].
     */
    fun onIntent(intent: I) {
        Timber.tag(tag).d("Intent: %s", intent)
        handleIntent(intent)
    }

    /**
     * Subclass hook to handle each intent type.
     * Implementations should call [updateState] and/or [emitEffect].
     */
    protected abstract fun handleIntent(intent: I)

    /**
     * Atomically update the UI state using a reducer function.
     *
     * The reducer receives the current state and returns the new state.
     * State transitions are logged for the audit trail.
     *
     * @param reducer Pure function: (currentState) → newState.
     */
    protected fun updateState(reducer: S.() -> S) {
        val oldState = _state.value
        val newState = oldState.reducer()
        if (oldState != newState) {
            _state.value = newState
            Timber.tag(tag).d("State: %s", newState)
        }
    }

    /**
     * Emit a one-shot side effect to the UI.
     *
     * Effects are consumed exactly once (not replayed on recomposition).
     * Used for navigation, toasts, haptic feedback, etc.
     */
    protected fun emitEffect(effect: E) {
        Timber.tag(tag).d("Effect: %s", effect)
        viewModelScope.launch {
            _effect.send(effect)
        }
    }

    /**
     * Launch a coroutine in the ViewModel scope.
     *
     * Convenience wrapper that logs exceptions before propagating them.
     * Use for async operations (data loading, BLE commands, etc.).
     */
    protected fun launch(block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
            } catch (e: Exception) {
                Timber.tag(tag).e(e, "Coroutine failed")
                throw e
            }
        }
    }
}

/** Marker interface for UI state data classes. */
interface UiState

/** Marker interface for user intent sealed interfaces. */
interface UiIntent

/** Marker interface for one-shot effect sealed interfaces. */
interface UiEffect
