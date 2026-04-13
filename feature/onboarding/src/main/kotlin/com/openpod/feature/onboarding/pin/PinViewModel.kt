package com.openpod.feature.onboarding.pin

import com.openpod.core.datastore.OpenPodPreferences
import com.openpod.core.datastore.PinManager
import com.openpod.core.ui.mvi.MviViewModel
import com.openpod.core.ui.mvi.UiEffect
import com.openpod.core.ui.mvi.UiIntent
import com.openpod.core.ui.mvi.UiState
import dagger.hilt.android.lifecycle.HiltViewModel
import timber.log.Timber
import javax.inject.Inject

/**
 * Phases of the PIN setup flow.
 */
enum class PinPhase {
    /** Initial PIN entry. */
    ENTRY,
    /** Confirmation entry (must match initial PIN). */
    CONFIRMATION,
    /** Biometric opt-in prompt. */
    BIOMETRIC_PROMPT,
}

/**
 * UI state for the PIN setup screen.
 *
 * @property phase Current phase of the PIN setup flow.
 * @property digitCount Number of digits currently entered (0-4).
 * @property showShakeAnimation Whether to play the shake/error animation.
 * @property mismatchCount Number of consecutive confirmation mismatches.
 * @property errorMessage Error message to display below the dots.
 * @property showBiometricSheet Whether to show the biometric opt-in bottom sheet.
 */
data class PinState(
    val phase: PinPhase = PinPhase.ENTRY,
    val digitCount: Int = 0,
    val showShakeAnimation: Boolean = false,
    val mismatchCount: Int = 0,
    val errorMessage: String? = null,
    val showBiometricSheet: Boolean = false,
) : UiState

/** User intents for the PIN screen. */
sealed interface PinIntent : UiIntent {
    /** A digit key was pressed. */
    data class DigitEntered(val digit: Char) : PinIntent

    /** Backspace key was pressed. */
    data object Backspace : PinIntent

    /** Backspace long-press: clear all digits. */
    data object ClearAll : PinIntent

    /** Shake animation completed. */
    data object ShakeComplete : PinIntent

    /** User chose to enable biometrics. */
    data object EnableBiometrics : PinIntent

    /** User chose to skip biometrics. */
    data object SkipBiometrics : PinIntent

    /** Biometric enrollment result. */
    data class BiometricResult(val success: Boolean) : PinIntent
}

/** One-shot effects for the PIN screen. */
sealed interface PinEffect : UiEffect {
    /** Navigate to the Ready screen. */
    data object NavigateToReady : PinEffect

    /** Navigate back. */
    data object NavigateBack : PinEffect

    /** Show the system BiometricPrompt. */
    data object ShowBiometricPrompt : PinEffect

    /** Show a snackbar message. */
    data class ShowSnackbar(val message: String) : PinEffect
}

/**
 * ViewModel for the 4-digit safety PIN setup screen.
 *
 * Manages the two-phase entry (enter + confirm), mismatch handling with
 * shake animation, and biometric opt-in. Stores the PIN hash via [PinManager]
 * and biometric preference via [OpenPodPreferences].
 *
 * @param pinManager Secure PIN storage.
 * @param preferences App preferences for biometric flag.
 */
@HiltViewModel
class PinViewModel @Inject constructor(
    private val pinManager: PinManager,
    private val preferences: OpenPodPreferences,
) : MviViewModel<PinState, PinIntent, PinEffect>(
    initialState = PinState(),
) {
    /** In-memory PIN storage for comparison. Never persisted until confirmed. */
    private var firstPin: String = ""
    private var currentDigits: StringBuilder = StringBuilder()

    override fun handleIntent(intent: PinIntent) {
        when (intent) {
            is PinIntent.DigitEntered -> handleDigit(intent.digit)
            PinIntent.Backspace -> handleBackspace()
            PinIntent.ClearAll -> handleClearAll()
            PinIntent.ShakeComplete -> handleShakeComplete()
            PinIntent.EnableBiometrics -> handleEnableBiometrics()
            PinIntent.SkipBiometrics -> handleSkipBiometrics()
            is PinIntent.BiometricResult -> handleBiometricResult(intent.success)
        }
    }

    private fun handleDigit(digit: Char) {
        if (currentDigits.length >= PIN_LENGTH) return
        if (currentState.showShakeAnimation) return

        currentDigits.append(digit)
        updateState { copy(digitCount = currentDigits.length, errorMessage = null) }

        if (currentDigits.length == PIN_LENGTH) {
            // Delay processing slightly to let the user see the last dot fill
            launch {
                kotlinx.coroutines.delay(200)
                processPinComplete()
            }
        }
    }

    private fun handleBackspace() {
        if (currentDigits.isEmpty()) return
        currentDigits.deleteCharAt(currentDigits.length - 1)
        updateState { copy(digitCount = currentDigits.length) }
    }

    private fun handleClearAll() {
        currentDigits.clear()
        updateState { copy(digitCount = 0) }
    }

    private fun processPinComplete() {
        val enteredPin = currentDigits.toString()

        when (currentState.phase) {
            PinPhase.ENTRY -> {
                firstPin = enteredPin
                currentDigits.clear()
                Timber.d("Initial PIN entered, switching to confirmation phase")
                updateState {
                    copy(
                        phase = PinPhase.CONFIRMATION,
                        digitCount = 0,
                        mismatchCount = 0,
                        errorMessage = null,
                    )
                }
            }

            PinPhase.CONFIRMATION -> {
                if (enteredPin == firstPin) {
                    Timber.i("PIN confirmed successfully")
                    storePinAndProceed(enteredPin)
                } else {
                    val newMismatchCount = currentState.mismatchCount + 1
                    Timber.w("PIN mismatch #%d", newMismatchCount)

                    if (newMismatchCount >= MAX_MISMATCHES) {
                        // Reset to initial entry after too many mismatches
                        currentDigits.clear()
                        firstPin = ""
                        updateState {
                            copy(
                                phase = PinPhase.ENTRY,
                                digitCount = 0,
                                showShakeAnimation = true,
                                mismatchCount = 0,
                                errorMessage = null, // Will show "start over" after shake
                            )
                        }
                    } else {
                        currentDigits.clear()
                        updateState {
                            copy(
                                digitCount = 0,
                                showShakeAnimation = true,
                                mismatchCount = newMismatchCount,
                            )
                        }
                    }
                }
            }

            PinPhase.BIOMETRIC_PROMPT -> {
                // Should not happen in biometric phase
            }
        }
    }

    private fun storePinAndProceed(pin: String) {
        launch {
            try {
                pinManager.storePin(pin)
                preferences.setPinConfigured(true)
                Timber.i("PIN stored successfully")

                // Check for biometric capability
                // In a real implementation, check BiometricManager.canAuthenticate()
                // For now, show biometric sheet
                currentDigits.clear()
                updateState {
                    copy(
                        showBiometricSheet = true,
                        phase = PinPhase.BIOMETRIC_PROMPT,
                    )
                }
            } catch (e: Exception) {
                Timber.e(e, "Failed to store PIN")
            }
        }
    }

    private fun handleShakeComplete() {
        updateState { copy(showShakeAnimation = false) }
    }

    private fun handleEnableBiometrics() {
        updateState { copy(showBiometricSheet = false) }
        emitEffect(PinEffect.ShowBiometricPrompt)
    }

    private fun handleSkipBiometrics() {
        Timber.i("User skipped biometric setup")
        updateState { copy(showBiometricSheet = false) }
        launch {
            preferences.setBiometricEnabled(false)
            emitEffect(PinEffect.NavigateToReady)
        }
    }

    private fun handleBiometricResult(success: Boolean) {
        launch {
            if (success) {
                Timber.i("Biometric enrollment successful")
                preferences.setBiometricEnabled(true)
            } else {
                Timber.w("Biometric enrollment failed or cancelled")
                preferences.setBiometricEnabled(false)
            }
            emitEffect(PinEffect.NavigateToReady)
        }
    }

    companion object {
        /** Required PIN length. */
        const val PIN_LENGTH = 4

        /** Maximum consecutive mismatches before resetting to initial entry. */
        private const val MAX_MISMATCHES = 3
    }
}
