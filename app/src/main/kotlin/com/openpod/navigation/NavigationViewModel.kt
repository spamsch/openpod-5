package com.openpod.navigation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.openpod.BuildConfig
import com.openpod.core.datastore.AppDataResetter
import com.openpod.core.datastore.OpenPodPreferences
import com.openpod.core.datastore.PinManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

/**
 * ViewModel for the root navigation host.
 *
 * Observes the onboarding completion state to determine whether
 * the app should start at the onboarding flow or the main dashboard.
 * The state is `null` until the first preference read completes,
 * preventing a flash of the wrong start screen.
 */
@HiltViewModel
class NavigationViewModel @Inject constructor(
    private val preferences: OpenPodPreferences,
    private val pinManager: PinManager,
    val appDataResetter: AppDataResetter,
) : ViewModel() {

    init {
        if (BuildConfig.SKIP_ONBOARDING) {
            viewModelScope.launch {
                Timber.i("Navigation: SKIP_ONBOARDING flag set — seeding test defaults")
                preferences.setOnboardingComplete(true)
                if (!pinManager.hasPin()) {
                    pinManager.storePin(DEFAULT_TEST_PIN)
                    Timber.i("Navigation: Test PIN set to %s", DEFAULT_TEST_PIN)
                }
            }
        }
    }

    companion object {
        /** Default PIN stored when SKIP_ONBOARDING is enabled. */
        const val DEFAULT_TEST_PIN = "3151"
    }

    /**
     * Whether onboarding has been completed.
     *
     * - `null` — still loading (show nothing)
     * - `false` — show onboarding
     * - `true` — show dashboard
     *
     * When SKIP_ONBOARDING is set, always returns true regardless of
     * stored preference to avoid a race with the init coroutine.
     */
    val isOnboardingComplete: StateFlow<Boolean?> = preferences.isOnboardingComplete()
        .map { complete ->
            val effective = complete || BuildConfig.SKIP_ONBOARDING
            Timber.d("Navigation: onboarding complete = %s (skip=%s)", effective, BuildConfig.SKIP_ONBOARDING)
            effective
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = null,
        )
}
