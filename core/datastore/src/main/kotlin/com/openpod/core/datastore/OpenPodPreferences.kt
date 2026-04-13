package com.openpod.core.datastore

import com.openpod.model.glucose.GlucoseUnit
import com.openpod.model.onboarding.OnboardingStep
import kotlinx.coroutines.flow.Flow

/**
 * Interface defining all user preference operations for the OpenPod app.
 *
 * Preferences stored here are lightweight settings that do not require
 * the full relational schema of Room. All writes are atomic and all reads
 * are observable via [Flow]. The backing store is encrypted at rest.
 *
 * Implementations must be thread-safe — concurrent reads and writes from
 * different coroutines are expected during normal app operation.
 */
interface OpenPodPreferences {

    /**
     * Observe whether the user has completed onboarding.
     *
     * @return A [Flow] emitting `true` once onboarding is finished.
     */
    fun isOnboardingComplete(): Flow<Boolean>

    /**
     * Set the onboarding completion flag.
     *
     * @param complete `true` to mark onboarding as finished.
     */
    suspend fun setOnboardingComplete(complete: Boolean)

    /**
     * Observe the user's current onboarding step (for process-death recovery).
     *
     * @return A [Flow] emitting the current [OnboardingStep].
     */
    fun onboardingStep(): Flow<OnboardingStep>

    /**
     * Persist the user's current onboarding step.
     *
     * @param step The [OnboardingStep] the user has reached.
     */
    suspend fun setOnboardingStep(step: OnboardingStep)

    /**
     * Observe whether the user has configured a safety PIN.
     *
     * @return A [Flow] emitting `true` if a PIN is set.
     */
    fun isPinConfigured(): Flow<Boolean>

    /**
     * Set the PIN-configured flag.
     *
     * @param configured `true` if the user has set a PIN.
     */
    suspend fun setPinConfigured(configured: Boolean)

    /**
     * Observe whether the user has enabled biometric authentication.
     *
     * @return A [Flow] emitting `true` if biometrics are enabled.
     */
    fun isBiometricEnabled(): Flow<Boolean>

    /**
     * Set the biometric-enabled flag.
     *
     * @param enabled `true` to enable biometric auth.
     */
    suspend fun setBiometricEnabled(enabled: Boolean)

    /**
     * Observe the user's preferred glucose display unit.
     *
     * @return A [Flow] emitting the current [GlucoseUnit].
     */
    fun glucoseUnit(): Flow<GlucoseUnit>

    /**
     * Set the user's preferred glucose display unit.
     *
     * @param unit The [GlucoseUnit] to use for display.
     */
    suspend fun setGlucoseUnit(unit: GlucoseUnit)
}
