package com.openpod.core.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.openpod.model.glucose.GlucoseUnit
import com.openpod.model.onboarding.OnboardingStep
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * DataStore-backed implementation of [OpenPodPreferences].
 *
 * All preferences are stored in an encrypted DataStore file (the encryption
 * is configured at the DataStore creation site in [com.openpod.core.datastore.di.PreferencesModule]).
 * Every write operation logs via Timber for audit traceability.
 */
@Singleton
internal class OpenPodPreferencesImpl @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) : OpenPodPreferences {

    override fun isOnboardingComplete(): Flow<Boolean> =
        dataStore.data.map { prefs ->
            prefs[KEY_ONBOARDING_COMPLETE] ?: false
        }

    override suspend fun setOnboardingComplete(complete: Boolean) {
        Timber.i("Setting onboarding complete = %s", complete)
        dataStore.edit { prefs ->
            prefs[KEY_ONBOARDING_COMPLETE] = complete
        }
    }

    override fun onboardingStep(): Flow<OnboardingStep> =
        dataStore.data.map { prefs ->
            val name = prefs[KEY_ONBOARDING_STEP]
            if (name != null) {
                try {
                    OnboardingStep.valueOf(name)
                } catch (_: IllegalArgumentException) {
                    Timber.w("Unknown onboarding step '%s', defaulting to WELCOME", name)
                    OnboardingStep.WELCOME
                }
            } else {
                OnboardingStep.WELCOME
            }
        }

    override suspend fun setOnboardingStep(step: OnboardingStep) {
        Timber.i("Setting onboarding step = %s", step.name)
        dataStore.edit { prefs ->
            prefs[KEY_ONBOARDING_STEP] = step.name
        }
    }

    override fun isPinConfigured(): Flow<Boolean> =
        dataStore.data.map { prefs ->
            prefs[KEY_PIN_CONFIGURED] ?: false
        }

    override suspend fun setPinConfigured(configured: Boolean) {
        Timber.i("Setting PIN configured = %s", configured)
        dataStore.edit { prefs ->
            prefs[KEY_PIN_CONFIGURED] = configured
        }
    }

    override fun isBiometricEnabled(): Flow<Boolean> =
        dataStore.data.map { prefs ->
            prefs[KEY_BIOMETRIC_ENABLED] ?: false
        }

    override suspend fun setBiometricEnabled(enabled: Boolean) {
        Timber.i("Setting biometric enabled = %s", enabled)
        dataStore.edit { prefs ->
            prefs[KEY_BIOMETRIC_ENABLED] = enabled
        }
    }

    override fun glucoseUnit(): Flow<GlucoseUnit> =
        dataStore.data.map { prefs ->
            val name = prefs[KEY_GLUCOSE_UNIT]
            if (name != null) {
                try {
                    GlucoseUnit.valueOf(name)
                } catch (_: IllegalArgumentException) {
                    Timber.w("Unknown glucose unit '%s', defaulting to MG_DL", name)
                    GlucoseUnit.MG_DL
                }
            } else {
                GlucoseUnit.MG_DL
            }
        }

    override suspend fun setGlucoseUnit(unit: GlucoseUnit) {
        Timber.i("Setting glucose unit = %s", unit.name)
        dataStore.edit { prefs ->
            prefs[KEY_GLUCOSE_UNIT] = unit.name
        }
    }

    internal companion object {
        val KEY_ONBOARDING_COMPLETE = booleanPreferencesKey("onboarding_complete")
        val KEY_ONBOARDING_STEP = stringPreferencesKey("onboarding_step")
        val KEY_PIN_CONFIGURED = booleanPreferencesKey("pin_configured")
        val KEY_BIOMETRIC_ENABLED = booleanPreferencesKey("biometric_enabled")
        val KEY_GLUCOSE_UNIT = stringPreferencesKey("glucose_unit")
    }
}
