package com.openpod.model.onboarding

/**
 * Tracks the user's progress through the onboarding flow.
 *
 * Once onboarding is complete, the app launches directly to the Dashboard
 * and these screens are never shown again.
 *
 * @property isComplete True if the user has finished all onboarding steps.
 *   Set when the user taps "Pair Your First Pod" on the Ready screen.
 * @property currentStep The furthest step the user has reached (for process death recovery).
 * @property pinConfigured True if the user has set a safety PIN.
 * @property biometricEnabled True if the user opted into biometric authentication.
 */
data class OnboardingState(
    val isComplete: Boolean = false,
    val currentStep: OnboardingStep = OnboardingStep.WELCOME,
    val pinConfigured: Boolean = false,
    val biometricEnabled: Boolean = false,
)

/**
 * Steps in the onboarding flow, in order.
 * Used for navigation and progress tracking.
 */
enum class OnboardingStep {
    WELCOME,
    PERMISSIONS,
    PROFILE,
    PIN,
    READY,
}

/**
 * Sub-steps within the Profile setup screen.
 * The profile screen uses an internal wizard to avoid overwhelming the user.
 */
enum class ProfileSubStep(val stepNumber: Int, val label: String) {
    DIA(1, "Duration of Insulin Action"),
    IC_RATIO(2, "Insulin-to-Carb Ratio"),
    CORRECTION_FACTOR(3, "Correction Factor"),
    TARGET_GLUCOSE(4, "Target Blood Glucose"),
    BASAL_PROGRAM(5, "Basal Program"),
}
